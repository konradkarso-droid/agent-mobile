package com.uroboros.will.tasks

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.nav.SemanticFeatures
import com.uroboros.memory.nav.LemmaHolder
import com.uroboros.util.TextFold
import com.uroboros.search.SearchCommand
import com.uroboros.search.SearchIntent

/** Смысл реплики даёт повод строить план. Полномочия даёт канал. */
internal object ChatTaskPlan {
    private val split = Regex("""(?:[.!?;\n]+|,\s*|\s+и\s+)(?:\s*(?:а\s+)?(?:потом|затем|после этого)\s+)?""", RegexOption.IGNORE_CASE)
    private val quoted = Regex("""[«»"“”`]|(?:^|\n)\s*>""")

    fun parts(text: String): List<String> {
        if (text.length > 1600 || quoted.containsMatchIn(text) || SearchIntent.hasLink(text)) return emptyList()
        return text.split(split).map { it.trim().trimStart('-', '•').trim() }.filter { part ->
            val word = part.takeWhile(Char::isLetter).lowercase()
            SentenceKind.of(part).kind == SentenceKind.Kind.REQUEST ||
                LemmaHolder.loaded?.analyze(word)?.any { "impr" in it.tags } == true
        }
    }

    // Два поручения уже образуют список. Для одной реплики ищем просьбу о составном результате.
    fun accepts(text: String): Boolean {
        if (SearchCommand.hasDelay(text)) {
            if (SearchCommand.fromOwnerWords(text) is SearchCommand.Deferred) return false
        }
        val parts = parts(text)
        if (parts.size >= 2) return true
        val part = parts.singleOrNull() ?: return false
        val semantic = SemanticFeatures.available() ?: return false
        val words = semantic.words(tokens.findAll(part).map { it.value }.toList())
        val goals = semantic.words(listOf("сопоставление", "обзор", "сравнение", "сводка"))
        return words.any { it.noun && semantic.matches(it, goals, 0.60) }
    }

    private val tokens = Regex("""[\p{L}]+""")

    fun control(text: String, active: Boolean, awaitingDetail: Boolean): String? {
        if (text.length > 1600 || quoted.containsMatchIn(text)) return null
        val surface = tokens.findAll(TextFold.fold(text)).map { it.value }.toList()
        val forms = surface.flatMap { listOf(it) + LemmaHolder.loaded?.lemmas(it).orEmpty() }.toSet()
        val topic = forms.any { it in setOf("задача", "задачей", "поручение", "поручением", "план", "прогресс") }
        if ((topic && forms.any { it in setOf("что", "как", "статус", "где", "этап") }) ||
            ("где" in forms && "остановились" in forms)) return "status"
        if (!active) return null
        if (forms.any { it in setOf("только", "уточнение", "поправка", "вместо") } ||
            SearchIntent.forbidsSearch(text)) return "amend"
        if (surface.joinToString(" ") in setOf("уже не нужно", "не надо", "больше не нужно")) return "cancel"
        val requested = parts(text).isNotEmpty() || surface.size == 1
        if (requested && "не" !in forms && forms.any {
            it in setOf("отменить", "остановиться", "остановись", "отмени", "прекратить", "отменять")
        }) return "cancel"
        if ((requested && "не" !in forms && forms.any { it in setOf("продолжить", "продолжать", "продолжи", "продолжай") }) ||
            surface.joinToString(" ") in setOf("давай дальше", "попробуй еще раз")) return "resume"
        return if (awaitingDetail) "amend" else null
    }

    // В сеть уходит только тема из просьбы человека, никогда — дополнение из личной памяти.
    fun webQueries(text: String): List<String> = if (SearchIntent.forbidsSearch(text)) emptyList() else
        parts(text).mapNotNull { (SearchCommand.fromOwnerWords(it) as? SearchCommand.Find)?.query }.distinct()

    fun validate(raw: String, webSteps: Int): org.json.JSONArray? = runCatching {
        val steps = org.json.JSONArray(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        require(steps.length() in 1..3)
        val tools = (0 until steps.length()).map { index ->
            val step = steps.getJSONObject(index)
            require(step.getString("goal").trim().length in 3..180)
            step.getString("tool").also { require(it in setOf("write", "memory", "web")) }
        }
        require(tools.last() == "write" && tools.count { it == "web" } == webSteps)
        steps
    }.getOrNull()

}
