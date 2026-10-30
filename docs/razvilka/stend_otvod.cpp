// Стенд живого отвода (state_read: locate / arm / take) на настоящем движке вне
// телефона — тем путём, каким слой снимает приложение в ходе разговора.
//
//   stend_otvod модель стена.txt задания.txt слой выход.bin
//
// Стена обсчитывается один раз, её состояние снимается и ставится перед каждым
// заданием — как снимок стены с диска в приложении. Задание «@@имя\n<лента до
// реплики>\x1f<реплика>\x1d»: запрос — стена + лента + реплика + конец хода и
// начало ответа агента; токены — всего запроса разом (как режет движок),
// отрезок ищется в них (locate), отвод взводится с первого токена после стены
// (arm), обсчёт идёт пачками логической длины 512 (как eval_tokens), итог —
// take. В файл подряд: [заданий][ширина] float32.
//
// Контекст — как у приложения на телефоне: n_ubatch 256, кэш q8_0, flash attention.
//
// Сборка: g++ -O2 -std=c++17 stend_otvod.cpp
//   ../../gguf_lib/src/main/cpp/state_read/state_read.cpp ../../gguf_lib/src/main/cpp/record_splice/record_splice.cpp
//   -I../../gguf_lib/src/main/cpp -I<форк>/include -I<форк>/ggml/include
//   -L<сборка>/bin -lllama -lggml -lggml-base -lggml-cpu -Wl,-rpath,<сборка>/bin
#include "llama.h"
#include "state_read/state_read.h"
#include "record_splice/record_splice.h"
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
    if (argc < 6) { fprintf(stderr, "см. начало файла\n"); return 1; }
    llama_backend_init();
    llama_model * m = llama_model_load_from_file(argv[1], llama_model_default_params());
    if (!m) { printf("модель не загрузилась\n"); return 1; }
    const llama_vocab * V = llama_model_get_vocab(m);
    static state_read::Hook hook;
    auto cp = llama_context_default_params();
    cp.n_ctx = 4096; cp.n_batch = 512; cp.n_ubatch = 256;
    int th = getenv("THREADS") ? atoi(getenv("THREADS")) : 2; cp.n_threads = th; cp.n_threads_batch = th;
    cp.type_k = GGML_TYPE_Q8_0; cp.type_v = GGML_TYPE_Q8_0; cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cp.cb_eval = state_read::callback; cp.cb_eval_user_data = &hook;
    llama_context * C = llama_init_from_model(m, cp);
    if (!C) { printf("контекст не создан\n"); return 1; }
    const int layer = atoi(argv[4]);
    const int n_embd = llama_model_n_embd(m);

    const std::string wall = slurp(argv[2]);
    std::vector<llama_token> wt = record_splice::tokenize(V, wall, true);
    for (size_t i = 0; i < wt.size(); i += 512) {
        int k = (int) std::min<size_t>(512, wt.size() - i);
        if (llama_decode(C, llama_batch_get_one(wt.data() + i, k))) { printf("стена не обсчиталась\n"); return 1; }
    }
    size_t ssz = llama_state_seq_get_size(C, 0);
    std::vector<uint8_t> snap(ssz);
    llama_state_seq_get_data(C, snap.data(), ssz, 0);

    FILE * out = fopen(argv[5], "wb");
    const std::string turn_end = "<|im_end|>\n<|im_start|>assistant\n";
    for (auto & it : load_items(argv[3])) {
        double t0 = now();
        const std::string prompt = wall + it.head + it.tail + turn_end;
        std::vector<llama_token> tokens = record_splice::tokenize(V, prompt, true);
        if (tokens.size() <= wt.size() || !std::equal(wt.begin(), wt.end(), tokens.begin())) {
            printf("%s: стена в запросе режется иначе\n", it.name.c_str()); return 1;
        }
        const size_t c0 = wall.size() + it.head.size(), c1 = c0 + it.tail.size();
        state_read::Window w = state_read::locate(V, prompt, tokens, c0, c1);
        if (!w.ok) { printf("%s: %s\n", it.name.c_str(), w.why.c_str()); return 1; }
        llama_memory_clear(llama_get_memory(C), true);
        if (llama_state_seq_set_data(C, snap.data(), ssz, 0) != ssz) { printf("%s: снимок не встал\n", it.name.c_str()); return 1; }
        long n_past = (long) wt.size();
        if (!state_read::arm(hook, n_embd, layer, w, n_past)) { printf("%s: не взвёлся\n", it.name.c_str()); return 1; }
        bool ok = true;
        for (size_t i = wt.size(); ok && i < tokens.size(); i += 512) {
            int k = (int) std::min<size_t>(512, tokens.size() - i);
            llama_batch b = llama_batch_init(k, 0, 1);
            for (int j = 0; j < k; j++) {
                b.token[j] = tokens[i + j]; b.pos[j] = (llama_pos) (n_past + j); b.n_seq_id[j] = 1; b.seq_id[j][0] = 0;
                b.logits[j] = j == k - 1;
            }
            b.n_tokens = k;
            ok = llama_decode(C, b) == 0;
            llama_batch_free(b);
            n_past += k;
        }
        state_read::Result r = state_read::take(hook, w);
        if (!ok || r.mean.empty()) { printf("%s: не прочитано: %s\n", it.name.c_str(), ok ? r.why.c_str() : "сбой обсчёта"); return 1; }
        fwrite(r.mean.data(), 4, r.mean.size(), out);
        printf("%s: реплика — токены %ld…%ld, %.2f с\n", it.name.c_str(), w.t0, w.t1, now() - t0);
        fflush(stdout);
    }
    fclose(out);
    return 0;
}
