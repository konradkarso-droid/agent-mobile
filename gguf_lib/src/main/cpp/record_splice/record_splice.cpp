#include "record_splice.h"

#include <cmath>

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
