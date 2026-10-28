// Стенд модуля state_read (приложение) на настоящем движке вне телефона.
//
//   stend_read chitat модель стена.txt задания.txt слой выход.bin
//     то же чтение, что в приложении (nativeReadLayerMean): для каждого задания
//     «@@имя\n<начало>\x1f<отрезок>\x1d» — среднее выхода слоя по токенам
//     отрезка; в файл подряд: [заданий][ширина] float32.
//   stend_read skorost модель с_вызовом(0|1)
//     цена спящего обратного вызова: обсчёт 512 токенов и печать 64 токенов,
//     с вызовом в контексте и без; чтение не взводится.
//
// Контекст — как у приложения на телефоне: n_ubatch 256, кэш q8_0, flash attention.
#include "llama.h"
#include "state_read/state_read.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

static std::string slurp(const char * p) { std::ifstream f(p, std::ios::binary); std::stringstream s; s << f.rdbuf(); return s.str(); }
static double now() { return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count(); }

struct Item { std::string name, head, tail; };
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

int main(int argc, char ** argv) {
    if (argc < 4) { fprintf(stderr, "см. начало файла\n"); return 1; }
    std::string mode = argv[1];
    llama_backend_init();
    llama_model * m = llama_model_load_from_file(argv[2], llama_model_default_params());
    if (!m) { printf("модель не загрузилась\n"); return 1; }
    const llama_vocab * V = llama_model_get_vocab(m);
    static state_read::Hook hook;
    auto cp = llama_context_default_params();
    cp.n_ctx = 4096; cp.n_batch = 512; cp.n_ubatch = 256;
    int th = getenv("THREADS") ? atoi(getenv("THREADS")) : 2; cp.n_threads = th; cp.n_threads_batch = th;
    cp.type_k = GGML_TYPE_Q8_0; cp.type_v = GGML_TYPE_Q8_0; cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    bool with_cb = mode == "chitat" || (mode == "skorost" && atoi(argv[3]) != 0);
    if (with_cb) { cp.cb_eval = state_read::callback; cp.cb_eval_user_data = &hook; }
    llama_context * C = llama_init_from_model(m, cp);
    if (!C) { printf("контекст не создан\n"); return 1; }

    if (mode == "chitat") {
        if (argc < 7) { fprintf(stderr, "chitat: мало аргументов\n"); return 1; }
        std::string wall = slurp(argv[3]); int layer = atoi(argv[5]);
        FILE * out = fopen(argv[6], "wb");
        for (auto & it : load_items(argv[4])) {
            double t0 = now();
            state_read::Split s = state_read::split(V, wall, it.head, it.tail);
            if (!s.ok) { printf("%s: граница сдвинута\n", it.name.c_str()); return 1; }
            state_read::Result r = state_read::read_mean(C, hook, s.head, s.seg, layer, (int) cp.n_batch);
            if (r.mean.empty()) { printf("%s: не прочитано: %s\n", it.name.c_str(), r.why.c_str()); return 1; }
            fwrite(r.mean.data(), 4, r.mean.size(), out);
            printf("%s: начало %zu ток., отрезок %zu ток., %.2f с\n", it.name.c_str(), s.head.size(), s.seg.size(), now() - t0);
            fflush(stdout);
        }
        fclose(out);
        return 0;
    }
    // skorost
    std::vector<llama_token> p(512);
    for (int i = 0; i < 512; i++) p[i] = (llama_token) (1000 + (i * 37) % 20000);
    double best_pp = 1e9, best_tg = 1e9;
    auto * smp = llama_sampler_init_greedy();
    for (int rep = 0; rep < 3; rep++) {
        llama_memory_clear(llama_get_memory(C), true);
        double t0 = now();
        for (int i = 0; i < 512; i += 512) llama_decode(C, llama_batch_get_one(p.data() + i, 512));
        double t1 = now();
        for (int g = 0; g < 64; g++) {
            llama_token id = llama_sampler_sample(smp, C, -1);
            llama_decode(C, llama_batch_get_one(&id, 1));
        }
        double t2 = now();
        best_pp = std::min(best_pp, t1 - t0); best_tg = std::min(best_tg, t2 - t1);
    }
    printf("вызов %s: обсчёт 512 ток. %.2f с (%.1f ток/с), печать 64 ток. %.2f с (%.2f ток/с)\n",
           with_cb ? "есть" : "нет", best_pp, 512 / best_pp, best_tg, 64 / best_tg);
    return 0;
}
