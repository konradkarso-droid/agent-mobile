package com.uroboros.documents

import android.content.Context
import com.uroboros.memory.SentenceKind
import com.uroboros.memory.nav.SemanticFeatures
import org.json.JSONObject

internal object ChatDocuments {
    fun scope(bot: Long, chat: Long, thread: Long?) = "tg:$bot:$chat" + (thread?.let { "/$it" } ?: "")
    fun format(name: String) = name.substringAfterLast('.', "").lowercase().takeIf { it in setOf("txt", "docx") }
    private fun prefs(app: Context) = app.getSharedPreferences("chat_documents", Context.MODE_PRIVATE)

    // В диалоге хранится последняя рабочая копия. Задача забирает снимок при постановке.
    @Synchronized fun attach(app: Context, key: String, name: String, bytes: ByteArray, sender: String, message: Long) {
        val ext = requireNotNull(format(name)) { "Поддерживаются TXT и DOCX" }
        val text = DocumentText.read(ext, bytes)
        val doc = JSONObject().put("name", name.takeLast(180)).put("text", text)
            .put("format", ext).put("sender", sender).put("message", message)
        val store = prefs(app)
        val edit = store.edit()
        if (message > 0) {
            val ids = (listOf(message) + store.getString("$key:ids", "").orEmpty().split(",").mapNotNull(String::toLongOrNull)).distinct()
            ids.drop(4).forEach { edit.remove("$key:msg:$it") }
            edit.putString("$key:ids", ids.take(4).joinToString(",")).putString("$key:msg:$message", doc.toString())
        }
        check(edit.putString(key, doc.toString()).commit()) { "Не удалось сохранить документ" }
    }

    fun has(app: Context, key: String) = prefs(app).contains(key)
    fun latest(app: Context, key: String) = prefs(app).getString(key, null)?.let(::JSONObject)

    private val tokens = Regex("""[\p{L}]+""")
    private val references = setOf("файл", "файла", "файле", "документ", "документа", "документе", "вложение", "docx", "txt", "word")
    fun requested(text: String, attached: Boolean = false): Boolean {
        if (text.isBlank() || text.length > 1600 || text.lineSequence().first().trimStart().startsWith(">")) return false
        val forms = tokens.findAll(text.lowercase()).map { it.value }.toList()
        if (forms.firstOrNull() == "не") return false
        val sem = SemanticFeatures.available()
        val words = sem?.words(forms).orEmpty()
        val asks = SentenceKind.of(text).kind == SentenceKind.Kind.REQUEST || words.any { it.imperative }
        if (!asks) return false
        if (attached && (replace.containsMatchIn(text) || extract.containsMatchIn(text))) return true
        if (forms.any { it in references }) return true
        val concepts = sem?.words(listOf("документ", "вложение", "файл")) ?: return false
        if (words.any { it.noun && sem.matches(it, concepts, 0.68) }) return true
        val edits = sem.words(listOf("редактировать", "исправить", "сократить", "переписать"))
        return attached && words.any { it.imperative && sem.matches(it, edits, 0.65) }
    }

    fun formatFor(request: String, source: JSONObject?): String {
        val words = tokens.findAll(request.lowercase()).map { it.value }.toSet()
        return when {
            "docx" in words || "word" in words -> "docx"
            "txt" in words -> "txt"
            else -> source?.optString("format") ?: "docx"
        }
    }

    private val subject = Regex("""\s+(?:про|насчёт|насчет|касательно|о|об)\s+(.+)""", RegexOption.IGNORE_CASE)
    private fun searchTopic(request: String): String? {
        extract.find(request)?.let { return it.groupValues[1] }
        val sem = SemanticFeatures.available() ?: return null
        val marker = subject.find(request) ?: return null
        val words = sem.words(tokens.findAll(request.take(marker.range.first).lowercase()).map { it.value }.toList())
        val selection = sem.words(listOf("найти", "выбрать", "извлечь", "показать"))
        return marker.groupValues[1].takeIf { words.any { word -> word.imperative && sem.matches(word, selection, 0.65) } }
    }

    // Совпадение считается по абзацам, чтобы вернуть точную выдержку из источника.
    fun passage(text: String, topic: String): String? {
        val sem = SemanticFeatures.available()
        val terms = tokens.findAll(topic.lowercase()).map { it.value }.toSet()
        val goals = sem?.words(terms).orEmpty()
        return text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val forms = tokens.findAll(line.lowercase()).map { it.value }.toSet()
            val candidates = sem?.words(forms).orEmpty()
            val score = if (sem == null) terms.intersect(forms).size.toDouble() else
                goals.sumOf { goal -> candidates.maxOfOrNull { sem.score(goal, it) }?.takeIf { it >= 0.62 } ?: 0.0 }
            line to score
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    private val literal = Regex("""(?:создай|сделай|сохрани|составь)\s+(?:(?:файл|документ)\s+)?(?:txt|word|docx|документ|файл)\s+с\s+текстом\s+[«"](.+)[»"][.!]?""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val replace = Regex("""(?:замени(?:ть)?|замените)\s+[«"]([^»"]+)[»"]\s+на\s+[«"]([^»"]*)[»"]""", RegexOption.IGNORE_CASE)
    private val extract = Regex("""(?:абзац|фрагмент|отрывок)\s+(?:о|об|про)\s+(.+?)(?:\s+из\s+(?:файла|документа))?[.!?]?$""", RegexOption.IGNORE_CASE)
    fun local(request: String, source: JSONObject?): String? {
        literal.matchEntire(request.trim())?.let { return it.groupValues[1] }
        val text = source?.getString("text")
        replace.find(request)?.let {
            require(text != null && it.groupValues[1] in text) { "Исходная фраза не найдена" }
            val old = it.groupValues[1]
            val new = it.groupValues[2]
            if (new.length > old.length) {
                val count = Regex(Regex.escape(old)).findAll(text).count()
                require(text.length.toLong() + count.toLong() * (new.length - old.length) <= DocumentText.MAX_CHARS) { "После замены текст слишком велик" }
            }
            return text.replace(old, new)
        }
        searchTopic(request)?.let { topic ->
            return requireNotNull(text?.let { body -> passage(body, topic) }) { "Подходящий абзац не найден" }
        }
        if (Regex("""(?:пришли|отправь|перешли|верни|сохрани)\s+(?:этот\s+)?(?:файл|документ)(?:\s+(?:в|как)\s+(?:txt|word|docx))?[.!]?""", RegexOption.IGNORE_CASE).matches(request.trim()))
            return requireNotNull(text) { "Сначала приложите документ" }
        return null
    }
}
