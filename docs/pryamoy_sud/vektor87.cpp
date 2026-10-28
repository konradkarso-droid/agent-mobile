// Стенд вектора голоса: снять внутреннее состояние модели по слоям и приложить
// вектор к нему при печати ответа. Работает с форком llama.cpp приложения, где
// нет открытого способа подать управляющий вектор: и чтение, и приложение идут
// через обратный вызов обсчёта (cb_eval) по выходам слоёв «l_out-N».
//
//   vektor87 snyat модель стена.txt задания.txt выход.bin
//     для каждого задания — среднее состояние каждого слоя по токенам ОТВЕТА
//     (без реплики собеседника), в файл подряд: [заданий][слоёв-1][ширина] float32.
//   vektor87 pisat модель стена.txt задания.txt вектор.bin сила слой_от слой_до макс_токенов
//     жадный ответ на каждое задание; к выходу слоёв от..до прибавлено сила × вектор
//     (вектор: [слоёв-1][ширина] float32). Сила 0 — ответ без вектора.
//
// Стена обсчитывается один раз без вектора, её состояние снимается и
// возвращается перед каждым заданием — как снимок стены в приложении.
//
// Задания: «@@имя\n<текст до ответа>\x1f<ответ>\x1d». В режиме pisat ответ пуст.
//
// Чего не умеет. Работает только на процессоре: данные слоя читаются и
// правятся прямо в памяти. Последний слой не трогается: у него строки уже
// отобраны под выдачу. Всё задание обсчитывается одной пачкой — длиннее
// пачки (2048) задание быть не должно.
#include "llama.h"
#include "ggml.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

struct Item { std::string name, prefix, reply; };

struct Hook {
    int n_layer = 0, n_embd = 0;
    // чтение
    bool capture = false; int row0 = 0; std::vector<double> acc; std::vector<int> rows;
    // приложение
    const float * vec = nullptr; float coef = 0; int la = 1, lb = 0;
};

static int layer_of(const ggml_tensor * t) {
    if (strncmp(t->name, "l_out-", 6) != 0) return -1;
    return atoi(t->name + 6);
}

static bool cb(ggml_tensor * t, bool ask, void * ud) {
    Hook * h = (Hook *) ud;
    int il = layer_of(t);
    if (ask) return il >= 0 && il < h->n_layer - 1;
    if (il < 0 || il >= h->n_layer - 1) return true;
    if (t->type != GGML_TYPE_F32 || t->ne[0] != h->n_embd || t->nb[1] != (size_t) h->n_embd * 4) {
        fprintf(stderr, "слой %d: неожиданная форма, пропущен\n", il);
        return true;
    }
    float * d = (float *) t->data;
    int nr = (int) t->ne[1];
    if (h->vec && h->coef != 0 && il >= h->la && il <= h->lb) {
        const float * v = h->vec + (size_t) il * h->n_embd;
        for (int r = 0; r < nr; r++) for (int j = 0; j < h->n_embd; j++) d[(size_t) r * h->n_embd + j] += h->coef * v[j];
    }
    if (h->capture) {
        double * a = h->acc.data() + (size_t) il * h->n_embd;
        for (int r = h->row0; r < nr; r++) { for (int j = 0; j < h->n_embd; j++) a[j] += d[(size_t) r * h->n_embd + j]; h->rows[il]++; }
    }
    return true;
}

static std::string slurp(const char * p) { std::ifstream f(p, std::ios::binary); std::stringstream s; s << f.rdbuf(); return s.str(); }

static std::vector<Item> load_items(const char * p) {
    std::vector<Item> out; std::string s = slurp(p); size_t i = 0;
    while ((i = s.find("@@", i)) != std::string::npos) {
        size_t nl = s.find('\n', i), us = s.find('\x1f', nl), gs = s.find('\x1d', us);
        if (nl == std::string::npos || us == std::string::npos || gs == std::string::npos) break;
        out.push_back({s.substr(i + 2, nl - i - 2), s.substr(nl + 1, us - nl - 1), s.substr(us + 1, gs - us - 1)});
        i = gs + 1;
    }
    return out;
}

static std::vector<llama_token> tok(const llama_vocab * V, const std::string & s, bool add) {
    std::vector<llama_token> t(s.size() + 16);
    int n = llama_tokenize(V, s.c_str(), s.size(), t.data(), t.size(), add, true);
    t.resize(n > 0 ? n : 0); return t;
}

static double now() { return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count(); }

int main(int argc, char ** argv) {
    if (argc < 5) { fprintf(stderr, "см. начало файла\n"); return 1; }
    std::string mode = argv[1];
    Hook h;
    llama_backend_init();
    llama_model * m = llama_model_load_from_file(argv[2], llama_model_default_params());
    if (!m) { printf("модель не загрузилась\n"); return 1; }
    const llama_vocab * V = llama_model_get_vocab(m);
    h.n_layer = llama_model_n_layer(m); h.n_embd = llama_model_n_embd(m);
    auto cp = llama_context_default_params();
    cp.n_ctx = 4096; cp.n_batch = 2048; cp.n_ubatch = 2048;
    int th = getenv("THREADS") ? atoi(getenv("THREADS")) : 2; cp.n_threads = th; cp.n_threads_batch = th;
    cp.type_k = GGML_TYPE_Q8_0; cp.type_v = GGML_TYPE_Q8_0; cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cp.cb_eval = cb; cp.cb_eval_user_data = &h;
    llama_context * C = llama_init_from_model(m, cp);
    if (!C) { printf("контекст не создан\n"); return 1; }
    printf("слоёв %d, ширина %d\n", h.n_layer, h.n_embd);

    std::vector<float> vec;
    int maxtok = 60;
    if (mode == "pisat") {
        if (argc < 10) { fprintf(stderr, "pisat: мало аргументов\n"); return 1; }
        std::string vb = slurp(argv[5]);
        vec.resize(vb.size() / 4); memcpy(vec.data(), vb.data(), vec.size() * 4);
        if (vec.size() != (size_t) (h.n_layer - 1) * h.n_embd) { printf("вектор не той формы: %zu\n", vec.size()); return 1; }
        h.coef = atof(argv[6]); h.la = atoi(argv[7]); h.lb = atoi(argv[8]); maxtok = atoi(argv[9]);
    }

    // Стена — один раз, без вектора и без чтения.
    std::vector<llama_token> wt = tok(V, slurp(argv[3]), true);
    double t0 = now();
    for (size_t i = 0; i < wt.size(); i += 2048) {
        int k = (int) std::min<size_t>(2048, wt.size() - i);
        if (llama_decode(C, llama_batch_get_one(wt.data() + i, k))) { printf("стена не обсчиталась\n"); return 1; }
    }
    size_t ssz = llama_state_seq_get_size(C, 0);
    std::vector<uint8_t> snap(ssz);
    llama_state_seq_get_data(C, snap.data(), ssz, 0);
    printf("стена %zu ток. за %.1f с, снимок %.1f МБ\n", wt.size(), now() - t0, ssz / 1048576.0);
    fflush(stdout);

    h.vec = vec.empty() ? nullptr : vec.data();
    std::vector<Item> items = load_items(argv[4]);
    FILE * out = mode == "snyat" ? fopen(argv[5], "wb") : nullptr;
    auto * smp = llama_sampler_init_greedy();

    for (auto & it : items) {
        llama_memory_clear(llama_get_memory(C), true);
        if (llama_state_seq_set_data(C, snap.data(), ssz, 0) != ssz) { printf("%s: снимок не встал\n", it.name.c_str()); return 1; }
        std::vector<llama_token> pt = tok(V, it.prefix, false);
        double t1 = now();
        if (mode == "snyat") {
            std::vector<llama_token> ft = tok(V, it.prefix + it.reply, false);
            bool same = ft.size() > pt.size() && std::equal(pt.begin(), pt.end(), ft.begin());
            h.capture = true; h.row0 = (int) pt.size();
            h.acc.assign((size_t) (h.n_layer - 1) * h.n_embd, 0.0); h.rows.assign(h.n_layer - 1, 0);
            if (llama_decode(C, llama_batch_get_one(ft.data(), (int) ft.size()))) { printf("%s: обсчёт упал\n", it.name.c_str()); return 1; }
            h.capture = false;
            std::vector<float> mean(h.acc.size());
            for (int il = 0; il < h.n_layer - 1; il++)
                for (int j = 0; j < h.n_embd; j++)
                    mean[(size_t) il * h.n_embd + j] = h.rows[il] ? (float) (h.acc[(size_t) il * h.n_embd + j] / h.rows[il]) : 0.f;
            fwrite(mean.data(), 4, mean.size(), out);
            printf("%s: реплика %zu ток., ответ %zu ток.%s, %.1f с\n", it.name.c_str(), pt.size(), ft.size() - pt.size(),
                   same ? "" : " (ГРАНИЦА ОТВЕТА СДВИНУТА)", now() - t1);
        } else {
            if (llama_decode(C, llama_batch_get_one(pt.data(), (int) pt.size()))) { printf("%s: обсчёт упал\n", it.name.c_str()); return 1; }
            std::string txt; int g = 0;
            for (; g < maxtok; g++) {
                llama_token id = llama_sampler_sample(smp, C, -1);
                if (llama_vocab_is_eog(V, id)) break;
                char b[256]; int l = llama_token_to_piece(V, id, b, sizeof b, 0, true);
                txt.append(b, l > 0 ? l : 0);
                if (llama_decode(C, llama_batch_get_one(&id, 1))) { printf("%s: печать упала\n", it.name.c_str()); return 1; }
            }
            for (auto & c : txt) if (c == '\n') c = ' ';
            printf("%s\t%.3f\t%d%s\t%s\n", it.name.c_str(), h.coef, g, g >= maxtok ? "+" : "", txt.c_str());
        }
        fflush(stdout);
    }
    if (out) fclose(out);
    return 0;
}
