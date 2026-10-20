#include "record_splice.h"

#include <cmath>
#include <algorithm>
#include <cstring>

#include <nlohmann/json.hpp>

namespace record_splice {

Plan parse_plan(const std::string & json_text) {
    Plan plan;
    try {
        auto j = nlohmann::json::parse(json_text);
        plan.recall = j.value("recall", "");
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

    std::string fresh = prompt.substr(0, start);
    size_t cursor = start;
    for (size_t i = 0; i < plan.lines.size(); i++) {
        const Line & line = plan.lines[i];
        const std::string whole = line.sig + line.content;
        const size_t at = prompt.find(whole, cursor);
        if (whole.empty() || line.content.empty() || at == std::string::npos || at + whole.size() > end) {
            out.why = "строка " + std::to_string(i + 1) + " во вспоминании не найдена";
            return out;
        }
        fresh += prompt.substr(cursor, at - cursor);
        fresh += line.sig;
        std::string content = line.content;
        size_t after = at + whole.size();
        if (after < end && prompt[after] == '\n') {
            content += '\n';
            after++;
        }
        if (!fresh.empty()) out.segments.push_back({fresh, -1});
        fresh.clear();
        out.segments.push_back({content, (int) i});
        cursor = after;
    }
    fresh += prompt.substr(cursor);
    if (!fresh.empty()) out.segments.push_back({fresh, -1});
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

// Запись куска: метка, отпечаток, токены стены, токены содержания, кэш.
const char MAGIC[8] = {'U', 'R', 'S', 'P', 'L', 'C', '1', 0};

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

} // namespace

bool build_chunk(llama_context * ctx, llama_seq_id wall_seq, llama_seq_id work_seq,
                 const ChunkKey & key, std::vector<uint8_t> & out) {
    out.clear();
    if (key.content.empty()) return false;
    llama_memory_t mem = llama_get_memory(ctx);
    const int W = (int) key.wall.size();
    llama_memory_seq_rm(mem, work_seq, -1, -1);
    llama_memory_seq_cp(mem, wall_seq, work_seq, 0, W);
    const bool ok = decode(ctx, key.content, work_seq, W);
    if (ok) {
        // В кусок идут только ячейки содержания: стена у всех кусков общая и
        // лежит в кэше стены, хранить её с каждым куском незачем.
        llama_memory_seq_rm(mem, work_seq, 0, W);
        const size_t size = llama_state_seq_get_size(ctx, work_seq);
        std::vector<uint8_t> data(size);
        const size_t got = size ? llama_state_seq_get_data(ctx, data.data(), size, work_seq) : 0;
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
    llama_memory_seq_rm(mem, work_seq, -1, -1);
    return !out.empty();
}

bool insert_chunk(llama_context * ctx, llama_seq_id main_seq, llama_seq_id work_seq,
                  const std::vector<uint8_t> & bytes, const ChunkKey & expect,
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
    if (identity != expect.identity) { why = "кусок от другой модели или накладки"; return false; }
    if (wall != expect.wall) { why = "кусок обсчитан после другой стены"; return false; }
    if (content != expect.content) { why = "текст записи изменился"; return false; }

    const int W = (int) wall.size();
    const int n = (int) content.size();
    if (seam < 0) seam = 0;
    if (seam > n) seam = n;
    llama_memory_t mem = llama_get_memory(ctx);
    llama_memory_seq_rm(mem, work_seq, -1, -1);
    if (llama_state_seq_set_data(ctx, bytes.data() + r.at, data_size, work_seq) == 0) {
        llama_memory_seq_rm(mem, work_seq, -1, -1);
        why = "движок не принял кусок";
        return false;
    }
    if (llama_memory_seq_pos_min(mem, work_seq) != W || llama_memory_seq_pos_max(mem, work_seq) != W + n - 1) {
        llama_memory_seq_rm(mem, work_seq, -1, -1);
        why = "в куске не те позиции";
        return false;
    }
    if (off != W) llama_memory_seq_add(mem, work_seq, W, W + n, off - W);
    // Сначала свежий шов, потом готовый остаток: в основную последовательность
    // можно дописывать только продолжение.
    if (seam > 0 && !decode(ctx, std::vector<llama_token>(content.begin(), content.begin() + seam), main_seq, off)) {
        llama_memory_seq_rm(mem, main_seq, off, -1);
        llama_memory_seq_rm(mem, work_seq, -1, -1);
        why = "сбой движка на шве";
        return false;
    }
    if (seam < n) llama_memory_seq_cp(mem, work_seq, main_seq, off + seam, off + n);
    llama_memory_seq_rm(mem, work_seq, -1, -1);
    return true;
}

Counts count(const llama_vocab * vocab, const Plan & plan, const Cut & cut) {
    Counts c;
    c.lines = (int) plan.lines.size();
    for (const Line & line : plan.lines) {
        const int sig = line.sig.empty() ? 0 : (int) tokenize(vocab, line.sig, false).size();
        c.sig_tokens += sig;
        c.seam_whole_tokens += seam_tokens(sig + (int) tokenize(vocab, line.content, false).size());
    }
    for (const Segment & s : cut.segments) {
        if (s.line < 0) continue;
        const int n = (int) tokenize(vocab, s.text, false).size();
        c.content_tokens += n;
        c.seam_tokens += seam_tokens(n);
    }
    return c;
}

} // namespace record_splice
