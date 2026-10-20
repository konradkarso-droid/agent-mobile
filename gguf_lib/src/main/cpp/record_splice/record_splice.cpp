#include "record_splice.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>

#include <dirent.h>
#include <sys/stat.h>
#include <utime.h>

#include <nlohmann/json.hpp>

namespace record_splice {

// ── Разрез ─────────────────────────────────────────────────────────────────

Plan parse_plan(const std::string & json_text) {
    Plan plan;
    try {
        auto j = nlohmann::json::parse(json_text);
        plan.recall = j.value("recall", "");
        plan.on = j.value("on", false);
        if (j.contains("lines") && j["lines"].is_array()) {
            for (auto & l : j["lines"]) {
                plan.lines.push_back({l.value("s", ""), l.value("c", "")});
            }
        }
    } catch (...) {
        return Plan{};
    }
    return plan;
}

Cut cut(const std::string & prompt, const Plan & plan) {
    Cut out;
    if (plan.recall.empty() || plan.lines.empty()) {
        out.why = "записей в ходе нет";
        return out;
    }
    const size_t start = prompt.rfind(plan.recall);
    if (start == std::string::npos) {
        out.why = "вспоминание в запросе не найдено";
        return out;
    }
    const size_t end = start + plan.recall.size();

    size_t cursor = 0;
    for (size_t i = 0; i < plan.lines.size(); i++) {
        const std::string whole = plan.lines[i].sig + plan.lines[i].content;
        const size_t at = whole.empty() ? std::string::npos : prompt.find(whole, std::max(cursor, start));
        if (at == std::string::npos || at + whole.size() > end) {
            out.segments.clear();
            out.why = "строка " + std::to_string(i + 1) + " во вспоминании не найдена";
            return out;
        }
        if (at > cursor) out.segments.push_back({prompt.substr(cursor, at - cursor), -1});
        out.segments.push_back({whole, (int) i});
        cursor = at + whole.size();
    }
    if (cursor < prompt.size()) out.segments.push_back({prompt.substr(cursor), -1});
    out.ok = true;
    return out;
}

int seam_tokens(int n) {
    if (n <= 0) return 0;
    const int k = (int) std::ceil(SEAM_SHARE * n);
    return k < 1 ? 1 : (k > n ? n : k);
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    std::vector<llama_token> tokens(text.size() + 16);
    int n = llama_tokenize(vocab, text.c_str(), (int) text.size(), tokens.data(), (int) tokens.size(), add_special, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), (int) text.size(), tokens.data(), (int) tokens.size(), add_special, true);
    }
    tokens.resize(n > 0 ? n : 0);
    return tokens;
}

Counts count(const llama_vocab * vocab, const Plan & plan, const Cut & cut) {
    Counts c;
    c.lines = (int) plan.lines.size();
    for (const Line & line : plan.lines) {
        if (!line.sig.empty()) c.sig_tokens += (int) tokenize(vocab, line.sig, false).size();
        c.content_tokens += (int) tokenize(vocab, line.content, false).size();
    }
    for (const Segment & s : cut.segments) {
        if (s.line < 0) continue;
        const int n = (int) tokenize(vocab, chunk_text(s.text), false).size();
        c.line_tokens += n;
        c.seam_tokens += seam_tokens(n);
    }
    return c;
}

std::string chunk_text(const std::string & line) {
    return line + "\n";
}

namespace {

// Байты текста, который даёт токен; служебные метки — своим текстом, как их
// пишет шаблон чата.
std::string piece(const llama_vocab * vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, true);
    if (n >= 0) return std::string(buf, n);
    std::string big((size_t) -n, '\0');
    n = llama_token_to_piece(vocab, t, big.data(), (int) big.size(), 0, true);
    return n >= 0 ? big.substr(0, (size_t) n) : std::string();
}

} // namespace

Layout spans(const llama_vocab * vocab, const std::vector<llama_token> & tokens,
             const std::string & prompt, const Cut & cut) {
    Layout out;
    const int N = (int) tokens.size();
    // Где в тексте начинается каждый токен. Служебное начало (BOS), если
    // движок его добавил, текста в запросе не имеет: пробуем без него.
    std::vector<std::string> pieces(N);
    for (int i = 0; i < N; i++) pieces[i] = piece(vocab, tokens[i]);
    std::vector<size_t> at;
    bool fits = false;
    for (int lead = 0; lead <= 1 && lead <= N && !fits; lead++) {
        at.assign(N + 1, 0);
        size_t pos = 0;
        fits = true;
        for (int i = 0; i < N; i++) {
            at[i] = pos;
            if (i < lead) continue;
            if (prompt.compare(pos, pieces[i].size(), pieces[i]) != 0) { fits = false; break; }
            pos += pieces[i].size();
        }
        at[N] = pos;
        fits = fits && pos == prompt.size();
    }
    if (!fits) {
        out.why = "токены запроса не складываются в его текст";
        return out;
    }
    // Первый токен, начинающийся не раньше символа c; -1 — ровно на c токена нет.
    auto first_from = [&](size_t c) {
        return (int) (std::lower_bound(at.begin(), at.end() - 1, c) - at.begin());
    };
    auto starts_at = [&](size_t c) {
        const int i = first_from(c);
        return (i < N && at[i] == c) || (i == N && at[N] == c) ? i : -1;
    };

    int done = 0;           // токены до done уже разложены
    size_t c = 0;           // символ, с которого начинается следующий кусок разреза
    for (const Segment & seg : cut.segments) {
        const size_t a = c;
        c += seg.text.size();
        if (seg.line < 0) continue;
        const size_t b = c;
        const bool newline = b < prompt.size() && prompt[b] == '\n';
        const int ti = starts_at(a);
        const int tj = newline ? starts_at(b + 1) : -1;
        Span line;
        line.line = seg.line;
        line.text = chunk_text(seg.text);
        if (ti >= done && tj > ti) {
            line.begin = ti;
            line.end = tj;
            const auto own = tokenize(vocab, line.text, false);
            line.whole = std::equal(tokens.begin() + ti, tokens.begin() + tj, own.begin(), own.end());
        } else {
            // Не отрезать ровно: строке достаются целые токены внутри неё,
            // считать её всё равно заново.
            line.begin = std::max(done, first_from(a));
            line.end = std::max(line.begin, first_from(b));
        }
        if (line.begin > done) out.spans.push_back({done, line.begin, -1, false, ""});
        out.spans.push_back(line);
        done = line.end;
    }
    if (done < N) out.spans.push_back({done, N, -1, false, ""});
    out.ok = true;
    return out;
}

// ── Куски ──────────────────────────────────────────────────────────────────

namespace {

// Обсчитать токены в последовательность seq с позиции pos, без выходов модели.
bool decode(llama_context * ctx, const std::vector<llama_token> & t, llama_seq_id seq, int pos) {
    const int step = (int) llama_n_batch(ctx);
    for (size_t i = 0; i < t.size(); i += step) {
        const int n = (int) std::min(t.size() - i, (size_t) step);
        llama_batch b = llama_batch_init(n, 0, 1);
        for (int j = 0; j < n; j++) {
            b.token[j] = t[i + j];
            b.pos[j] = pos + (int) i + j;
            b.n_seq_id[j] = 1;
            b.seq_id[j][0] = seq;
            b.logits[j] = false;
        }
        b.n_tokens = n;
        const int rc = llama_decode(ctx, b);
        llama_batch_free(b);
        if (rc != 0) return false;
    }
    return true;
}

// Запись куска: метка, отпечаток, токены стены, токены строки, кэш строки.
const char MAGIC[8] = {'U', 'R', 'S', 'P', 'L', 'C', '2', 0};
const char SUFFIX[] = ".chunk";

void put_u32(std::vector<uint8_t> & out, uint32_t v) {
    for (int i = 0; i < 4; i++) out.push_back((uint8_t) (v >> (8 * i)));
}

void put_tokens(std::vector<uint8_t> & out, const std::vector<llama_token> & t) {
    put_u32(out, (uint32_t) t.size());
    for (llama_token x : t) put_u32(out, (uint32_t) x);
}

struct Reader {
    const std::vector<uint8_t> & in;
    size_t at = 0;
    bool ok = true;
    uint32_t u32() {
        if (at + 4 > in.size()) { ok = false; return 0; }
        uint32_t v = 0;
        for (int i = 0; i < 4; i++) v |= (uint32_t) in[at + i] << (8 * i);
        at += 4;
        return v;
    }
    std::string str() {
        const uint32_t n = u32();
        if (!ok || at + n > in.size()) { ok = false; return ""; }
        std::string s((const char *) in.data() + at, n);
        at += n;
        return s;
    }
    std::vector<llama_token> tokens() {
        const uint32_t n = u32();
        std::vector<llama_token> t;
        if (!ok || at + 4ull * n > in.size()) { ok = false; return t; }
        t.reserve(n);
        for (uint32_t i = 0; i < n; i++) t.push_back((llama_token) u32());
        return t;
    }
};

bool ends_with(const std::string & s, const char * suffix) {
    const size_t n = strlen(suffix);
    return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
}

} // namespace

std::string chunk_file_name(const std::string & line_text) {
    uint64_t h = 1469598103934665603ull;
    for (unsigned char c : line_text) {
        h ^= c;
        h *= 1099511628211ull;
    }
    char buf[32];
    snprintf(buf, sizeof(buf), "%016llx", (unsigned long long) h);
    return std::string(buf) + SUFFIX;
}

bool ensure_wall(llama_context * ctx, const std::vector<llama_token> & wall,
                 const std::vector<llama_token> & main_tokens, int main_n_past) {
    llama_memory_t mem = llama_get_memory(ctx);
    const int W = (int) wall.size();
    if (W == 0) return false;
    if (llama_memory_seq_pos_min(mem, SEQ_WALL) == 0 && llama_memory_seq_pos_max(mem, SEQ_WALL) == W - 1) return true;
    llama_memory_seq_rm(mem, SEQ_WALL, -1, -1);
    const bool main_has_wall = main_n_past >= W && (int) main_tokens.size() >= W &&
        std::equal(wall.begin(), wall.end(), main_tokens.begin());
    if (main_has_wall) {
        llama_memory_seq_cp(mem, SEQ_MAIN, SEQ_WALL, 0, W);
        return true;
    }
    if (decode(ctx, wall, SEQ_WALL, 0)) return true;
    llama_memory_seq_rm(mem, SEQ_WALL, -1, -1);
    return false;
}

bool build_chunk(llama_context * ctx, const ChunkKey & key, std::vector<uint8_t> & out) {
    out.clear();
    if (key.content.empty()) return false;
    llama_memory_t mem = llama_get_memory(ctx);
    const int W = (int) key.wall.size();
    llama_memory_seq_rm(mem, SEQ_WORK, -1, -1);
    llama_memory_seq_cp(mem, SEQ_WALL, SEQ_WORK, 0, W);
    if (decode(ctx, key.content, SEQ_WORK, W)) {
        // В кусок идут только ячейки строки: стена у всех кусков общая.
        llama_memory_seq_rm(mem, SEQ_WORK, 0, W);
        const size_t size = llama_state_seq_get_size(ctx, SEQ_WORK);
        std::vector<uint8_t> data(size);
        const size_t got = size ? llama_state_seq_get_data(ctx, data.data(), size, SEQ_WORK) : 0;
        if (got > 0) {
            out.insert(out.end(), MAGIC, MAGIC + sizeof(MAGIC));
            put_u32(out, (uint32_t) key.identity.size());
            out.insert(out.end(), key.identity.begin(), key.identity.end());
            put_tokens(out, key.wall);
            put_tokens(out, key.content);
            put_u32(out, (uint32_t) got);
            out.insert(out.end(), data.begin(), data.begin() + got);
        }
    }
    llama_memory_seq_rm(mem, SEQ_WORK, -1, -1);
    return !out.empty();
}

bool chunk_matches(const std::vector<uint8_t> & bytes, const ChunkKey & key) {
    if (bytes.size() < sizeof(MAGIC) || memcmp(bytes.data(), MAGIC, sizeof(MAGIC)) != 0) return false;
    Reader r{bytes, sizeof(MAGIC)};
    const std::string identity = r.str();
    const auto wall = r.tokens();
    const auto content = r.tokens();
    const uint32_t data_size = r.u32();
    return r.ok && r.at + data_size == bytes.size() && identity == key.identity &&
        wall == key.wall && content == key.content;
}

bool insert_chunk(llama_context * ctx, const std::vector<uint8_t> & bytes, const ChunkKey & expect,
                  int off, int seam, std::string & why) {
    if (bytes.size() < sizeof(MAGIC) || memcmp(bytes.data(), MAGIC, sizeof(MAGIC)) != 0) {
        why = "кусок не нашего вида";
        return false;
    }
    Reader r{bytes, sizeof(MAGIC)};
    const std::string identity = r.str();
    const auto wall = r.tokens();
    const auto content = r.tokens();
    const uint32_t data_size = r.u32();
    if (!r.ok || r.at + data_size != bytes.size()) { why = "кусок повреждён"; return false; }
    if (identity != expect.identity) { why = "кусок от другой загрузки модели"; return false; }
    if (wall != expect.wall) { why = "кусок обсчитан после другой стены"; return false; }
    if (content != expect.content) { why = "текст строки другой"; return false; }

    const int W = (int) wall.size();
    const int n = (int) content.size();
    seam = std::max(0, std::min(seam, n));
    llama_memory_t mem = llama_get_memory(ctx);
    llama_memory_seq_rm(mem, SEQ_WORK, -1, -1);
    if (llama_state_seq_set_data(ctx, bytes.data() + r.at, data_size, SEQ_WORK) == 0) {
        llama_memory_seq_rm(mem, SEQ_WORK, -1, -1);
        why = "движок не принял кусок";
        return false;
    }
    if (llama_memory_seq_pos_min(mem, SEQ_WORK) != W || llama_memory_seq_pos_max(mem, SEQ_WORK) != W + n - 1) {
        llama_memory_seq_rm(mem, SEQ_WORK, -1, -1);
        why = "в куске не те позиции";
        return false;
    }
    if (off != W) llama_memory_seq_add(mem, SEQ_WORK, W, W + n, off - W);
    // Сначала свежий шов, потом готовый остаток: в основную последовательность
    // можно дописывать только продолжение.
    if (seam > 0 && !decode(ctx, std::vector<llama_token>(content.begin(), content.begin() + seam), SEQ_MAIN, off)) {
        llama_memory_seq_rm(mem, SEQ_MAIN, off, -1);
        llama_memory_seq_rm(mem, SEQ_WORK, -1, -1);
        why = "сбой движка на шве";
        return false;
    }
    if (seam < n) llama_memory_seq_cp(mem, SEQ_WORK, SEQ_MAIN, off + seam, off + n);
    llama_memory_seq_rm(mem, SEQ_WORK, -1, -1);
    return true;
}

// ── Ход ────────────────────────────────────────────────────────────────────

bool eval_turn(llama_context * ctx, const std::vector<llama_token> & tokens, const Layout & layout,
               const std::string & identity, const std::vector<llama_token> & wall,
               const std::string & dir, int & n_past, const EvalFresh & eval, TurnStats & stats) {
    // Свежие токены копятся от n_past до upto и обсчитываются одной пачкой.
    int upto = n_past;
    auto flush = [&]() {
        if (upto <= n_past) return true;
        const std::vector<llama_token> fresh(tokens.begin() + n_past, tokens.begin() + upto);
        stats.fresh_tokens += (int) fresh.size();
        return eval(fresh, n_past);
    };
    for (const Span & sp : layout.spans) {
        if (sp.end <= n_past) continue;  // уже в кэше с прошлого хода
        if (sp.line >= 0) {
            stats.lines++;
            if (!sp.whole) stats.unsplit++;
        }
        // Готовым можно взять только строку целиком: середину строки из куска
        // не взять.
        if (sp.line >= 0 && sp.whole && sp.begin >= n_past && !dir.empty()) {
            const std::string path = dir + "/" + chunk_file_name(sp.text);
            const ChunkKey key{identity, wall, std::vector<llama_token>(tokens.begin() + sp.begin, tokens.begin() + sp.end)};
            std::vector<uint8_t> bytes;
            if (!read_file(path, bytes)) {
                stats.missing++;
            } else if (!chunk_matches(bytes, key)) {
                stats.stale++;
            } else {
                upto = sp.begin;
                if (!flush()) return false;
                std::string why;
                const int seam = seam_tokens(sp.end - sp.begin);
                if (insert_chunk(ctx, bytes, key, sp.begin, seam, why)) {
                    touch_file(path);
                    stats.ready++;
                    stats.seam_tokens += seam;
                    stats.fresh_tokens += seam;
                    n_past = upto = sp.end;
                    continue;
                }
                if (why == "сбой движка на шве" || why == "движок не принял кусок") stats.failed++;
                else stats.stale++;
            }
        }
        upto = sp.end;
    }
    upto = (int) tokens.size();
    return flush();
}

// ── Хранилище ──────────────────────────────────────────────────────────────

bool read_file(const std::string & path, std::vector<uint8_t> & out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    f.seekg(0, std::ios::end);
    const std::streamoff size = f.tellg();
    if (size <= 0) return false;
    f.seekg(0, std::ios::beg);
    out.resize((size_t) size);
    f.read((char *) out.data(), size);
    return (bool) f;
}

bool write_file(const std::string & path, const std::vector<uint8_t> & bytes) {
    const std::string tmp = path + ".tmp";
    {
        std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
        if (!f) return false;
        f.write((const char *) bytes.data(), (std::streamsize) bytes.size());
        if (!f) { std::remove(tmp.c_str()); return false; }
    }
    if (std::rename(tmp.c_str(), path.c_str()) != 0) { std::remove(tmp.c_str()); return false; }
    return true;
}

void touch_file(const std::string & path) {
    utime(path.c_str(), nullptr);
}

int enforce_cap(const std::string & dir, uint64_t max_bytes, uint64_t & total_after) {
    struct Entry { std::string path; uint64_t size; time_t mtime; };
    std::vector<Entry> entries;
    uint64_t total = 0;
    if (DIR * d = opendir(dir.c_str())) {
        while (dirent * e = readdir(d)) {
            const std::string name = e->d_name;
            if (!ends_with(name, SUFFIX)) continue;
            const std::string path = dir + "/" + name;
            struct stat st{};
            if (stat(path.c_str(), &st) != 0) continue;
            entries.push_back({path, (uint64_t) st.st_size, st.st_mtime});
            total += (uint64_t) st.st_size;
        }
        closedir(d);
    }
    std::sort(entries.begin(), entries.end(), [](const Entry & a, const Entry & b) { return a.mtime < b.mtime; });
    int removed = 0;
    for (const Entry & e : entries) {
        if (total <= max_bytes) break;
        if (std::remove(e.path.c_str()) == 0) {
            total -= e.size;
            removed++;
        }
    }
    total_after = total;
    return removed;
}

} // namespace record_splice
