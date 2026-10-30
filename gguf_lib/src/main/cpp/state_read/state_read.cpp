#include "state_read.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>

#include "ggml.h"
#include "../record_splice/record_splice.h"

namespace state_read {

namespace {

int layer_of(const ggml_tensor * t) {
    if (strncmp(t->name, "l_out-", 6) != 0) return -1;
    return atoi(t->name + 6);
}

}  // namespace

bool callback(ggml_tensor * t, bool ask, void * user_data) {
    auto * h = static_cast<Hook *>(user_data);
    const int want = h->layer.load();
    if (want < 0) return ask ? false : true;
    if (ask) return layer_of(t) == want;
    if (layer_of(t) != want) return true;
    if (t->type != GGML_TYPE_F32 || t->ne[0] != h->n_embd || t->nb[1] != (size_t) h->n_embd * sizeof(float)) {
        h->bad_shape = true;
        return true;
    }
    // Строки выхода слоя — токены текущей физической пачки по порядку. Пачки
    // идут подряд, поэтому номер токена во всём тексте — seen + r.
    const float * d = static_cast<const float *>(t->data);
    const long nr = (long) t->ne[1];
    for (long r = 0; r < nr; r++) {
        if (h->seen + r < h->row0 || h->seen + r >= h->row1) continue;
        const float * row = d + (size_t) r * h->n_embd;
        for (int j = 0; j < h->n_embd; j++) h->acc[j] += row[j];
        h->rows++;
    }
    h->seen += nr;
    return true;
}

Split split(const llama_vocab * vocab, const std::string & wall, const std::string & head, const std::string & tail) {
    Split s;
    std::vector<llama_token> w = record_splice::tokenize(vocab, wall, true);
    std::vector<llama_token> h = record_splice::tokenize(vocab, head, false);
    std::vector<llama_token> all = record_splice::tokenize(vocab, head + tail, false);
    if (all.size() <= h.size() || !std::equal(h.begin(), h.end(), all.begin())) return s;
    s.ok = true;
    s.head = w;
    s.head.insert(s.head.end(), h.begin(), h.end());
    s.seg.assign(all.begin() + (long) h.size(), all.end());
    return s;
}

Result read_mean(llama_context * ctx, Hook & hook, const std::vector<llama_token> & head,
                 const std::vector<llama_token> & seg, int layer, int n_batch) {
    Result out;
    const llama_model * model = llama_get_model(ctx);
    const int n_layer = llama_model_n_layer(model);
    if (layer < 0 || layer >= n_layer - 1) { out.why = "слоя с таким номером читать нельзя"; return out; }
    if (seg.empty()) { out.why = "отрезок пуст"; return out; }
    std::vector<llama_token> all = head;
    all.insert(all.end(), seg.begin(), seg.end());
    if ((int) all.size() > (int) llama_n_ctx(ctx)) { out.why = "текст длиннее окна"; return out; }
    if (n_batch <= 0) n_batch = 512;

    llama_memory_clear(llama_get_memory(ctx), true);

    // Весь текст — одним обсчётом, а не начало и отрезок порознь: на телефоне
    // обращение к модели стоит прежде всего чтения всех весов из памяти, и
    // вторая пачка из десятка токенов обошлась бы почти во второе обращение.
    hook.n_embd = llama_model_n_embd(model);
    hook.acc.assign((size_t) hook.n_embd, 0.0);
    hook.rows = 0;
    hook.seen = 0;
    hook.row0 = (long) head.size();
    hook.row1 = LONG_MAX;
    hook.bad_shape = false;
    hook.layer.store(layer);
    bool ok = true;
    for (size_t i = 0; ok && i < all.size(); i += (size_t) n_batch) {
        const int k = (int) std::min<size_t>((size_t) n_batch, all.size() - i);
        ok = llama_decode(ctx, llama_batch_get_one(all.data() + i, k)) == 0;
    }
    hook.layer.store(-1);

    if (!ok) { out.why = "сбой движка при обсчёте"; return out; }
    if (hook.bad_shape) { out.why = "выход слоя неожиданной формы"; return out; }
    if (hook.seen != (long) all.size() || hook.rows != (long) seg.size()) { out.why = "прочитаны не все токены отрезка"; return out; }
    out.mean.resize((size_t) hook.n_embd);
    for (int j = 0; j < hook.n_embd; j++) out.mean[j] = (float) (hook.acc[j] / (double) hook.rows);
    return out;
}

Window locate(const llama_vocab * vocab, const std::string & prompt, const std::vector<llama_token> & tokens,
              size_t c0, size_t c1) {
    Window w;
    if (c0 >= c1 || c1 > prompt.size()) { w.why = "отрезок пуст"; return w; }
    // Начало запроса до отрезка и до его конца — токенами, как резал бы их
    // токенизатор отдельно. Совпали с началом токенов запроса — край отрезка
    // лежит на границе токенов; нет — токенизатор слил край с соседом, и
    // прочитанное было бы не тем отрезком, на котором учили.
    const std::vector<llama_token> a = record_splice::tokenize(vocab, prompt.substr(0, c0), true);
    const std::vector<llama_token> b = record_splice::tokenize(vocab, prompt.substr(0, c1), true);
    auto prefix = [&](const std::vector<llama_token> & p) {
        return p.size() <= tokens.size() && std::equal(p.begin(), p.end(), tokens.begin());
    };
    if (!prefix(a) || !prefix(b) || b.size() <= a.size()) {
        w.why = "граница реплики сдвинута токенизатором";
        return w;
    }
    w.ok = true;
    w.t0 = (long) a.size();
    w.t1 = (long) b.size();
    return w;
}

bool arm(Hook & hook, int n_embd, int layer, const Window & w, long first) {
    hook.layer.store(-1);
    if (!w.ok || w.t0 < first) return false;
    hook.n_embd = n_embd;
    hook.acc.assign((size_t) n_embd, 0.0);
    hook.rows = 0;
    hook.seen = first;
    hook.row0 = w.t0;
    hook.row1 = w.t1;
    hook.bad_shape = false;
    hook.layer.store(layer);
    return true;
}

Result take(Hook & hook, const Window & w) {
    Result out;
    const bool armed = hook.layer.load() >= 0;
    hook.layer.store(-1);
    hook.row1 = LONG_MAX;
    if (!armed) { out.why = "отвод не взведён"; return out; }
    if (hook.bad_shape) { out.why = "выход слоя неожиданной формы"; return out; }
    if (hook.rows != w.t1 - w.t0) { out.why = "прочитаны не все токены реплики"; return out; }
    out.mean.resize((size_t) hook.n_embd);
    for (int j = 0; j < hook.n_embd; j++) out.mean[j] = (float) (hook.acc[j] / (double) hook.rows);
    return out;
}

}  // namespace state_read
