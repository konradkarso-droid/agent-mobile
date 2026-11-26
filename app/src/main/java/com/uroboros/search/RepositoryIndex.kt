package com.uroboros.search

import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.util.StructuralBoundary
import java.util.Locale

internal data class RepositoryFile(val path: String, val blob: String, val size: Int) {
    val name get() = path.substringAfterLast('/')
}

internal enum class CodeEntryKind { DECLARATION, FUNCTION, IMPORT, DEPENDENCY }

internal data class CodeEntry(val name: String, val kind: CodeEntryKind, val start: Int, val end: Int)

/** Позиции строк начинаются с единицы, как на GitHub. */
internal data class CodeSnippet(val start: Int, val lines: List<String>) {
    val end get() = start + lines.lastIndex
    fun numbered(): String = lines.mapIndexed { offset, line -> "${start + offset} | $line" }.joinToString("\n")
}

internal fun interface CodeIndexer {
    fun index(source: String): List<CodeEntry>
}

internal class RepositoryWords(question: String, dictionary: LemmaDictionary? = null) {
    private val exact = tokens.findAll(question).map { it.value.lowercase(Locale.ROOT) }.toSet()
    private val words = SearchWords(searchable(question), dictionary)
    private val relatedTerms = translations.filter { (pattern, _) -> pattern.containsMatchIn(question) }.map { it.second }
    private val actions = relatedTerms.flatten().intersect(setOf("schedule", "enqueue", "send", "execute", "run", "start"))
    val dependencies = Regex("зависим|библиотек|dependency|dependencies|gradle|сборк", RegexOption.IGNORE_CASE)
        .containsMatchIn(question)

    fun score(text: String): Int {
        val normalized = searchable(text)
        val normalizedTokens = normalized.split(' ').toSet()
        val concepts = relatedTerms.count { alternatives -> alternatives.any { it in normalizedTokens } }
        return words.matchingGroups(normalized).size * 4 + concepts * 3 +
            if (concepts > 1) 4 else 0
    }

    fun fileScore(file: RepositoryFile, entries: List<CodeEntry> = emptyList()): Int {
        val stem = file.name.substringBeforeLast('.')
        val named = if (stem.lowercase(Locale.ROOT) in exact) 40 else 0
        val projectFile = if (dependencies && file.name in buildFiles) 15 else 0
        return named + projectFile + score(stem) * 3 + score(file.path.substringBeforeLast('/', "")) +
            (entries.maxOfOrNull { score(it.name) } ?: 0)
    }

    fun actionScore(name: String): Int = actions.count { it in searchable(name).split(' ') } * 52

    fun names(name: String): Boolean = name.lowercase(Locale.ROOT) in exact

    private companion object {
        val tokens = Regex("""[\p{L}\p{N}_]+""")
        val caseChange = Regex("""(?<=[\p{Ll}\p{N}])(?=\p{Lu})|(?<=\p{Lu})(?=\p{Lu}\p{Ll})""")
        val translations = listOf(
            "отлож" to listOf("deferred", "delayed"),
            "запуска|выполн|начина" to listOf("run", "start", "execute"),
            "ставит|назнача|расписан|планир|таймер" to listOf("schedule", "enqueue", "scheduler"),
            "поиск|ищ[ие]|искать" to listOf("search"),
            "отправ|достав" to listOf("send", "delivery"),
            "телеграм|telegram" to listOf("telegram", "chat"),
            "результат" to listOf("result"),
            "ответ" to listOf("reply", "response"),
            "веб|интернет" to listOf("web"),
            "команд" to listOf("command"),
            "обещ" to listOf("promise", "promises"),
            "разрешен|согласие" to listOf("permission", "consent"),
        ).map { (stem, english) -> Regex(stem, RegexOption.IGNORE_CASE) to english }
        val buildFiles = setOf("build.gradle.kts", "build.gradle", "pom.xml", "pyproject.toml", "package.json", "go.mod", "Cargo.toml")

        fun searchable(text: String): String = tokens.findAll(text).flatMap { match ->
            val name = match.value
            sequenceOf(name) + caseChange.split(name).asSequence().flatMap { it.split('_').asSequence() }
        }.filter { it.isNotEmpty() }.joinToString(" ").lowercase(Locale.ROOT)
    }
}

internal class RepositorySource(private val lines: List<String>, val entries: List<CodeEntry>) {
    fun snippet(words: RepositoryWords, requested: IntRange? = null): CodeSnippet? {
        if (lines.isEmpty()) return null
        if (requested != null) {
            if (requested.first !in 1..lines.size) return null
            return slice(requested.first, requested.last)
        }
        if (words.dependencies) {
            entries.firstOrNull { it.kind == CodeEntryKind.DEPENDENCY }?.let {
                return slice(it.start, it.start + 19)
            }
        }
        val declaration = entries.maxByOrNull {
            val evidence = lines.subList(it.start - 1, minOf(lines.size, it.start + 9))
                .joinToString(" ") { line -> line.take(180) }
            val action = if (it.kind == CodeEntryKind.FUNCTION) words.actionScore(it.name) + 5 else 0
            val exact = if (words.names(it.name)) 65 else 0
            words.score(it.name) * 3 + words.score(evidence) + action + exact
        }
        if (declaration != null && words.score(declaration.name) > 0) {
            // К сигнатуре добавляем тело либо ближайшие строки, если конец не разобран.
            val last = if (declaration.end > declaration.start) declaration.end else declaration.start + 20
            return slice(declaration.start, last)
        }
        val closest = lines.indices.maxByOrNull { words.score(lines[it].take(320)) } ?: 0
        val first = if (words.score(lines[closest].take(320)) > 0) (closest - 3).coerceAtLeast(0) else 0
        return slice(first + 1, first + 18)
    }

    private fun slice(start: Int, end: Int): CodeSnippet {
        var chars = 0
        val selected = lines.subList(start - 1, minOf(end, lines.size, start + MAX_LINES - 1)).map { line ->
            line.take(MAX_LINE_CHARS).trimEnd { it.isHighSurrogate() }
        }.takeWhile { line ->
            chars += line.length + 9
            chars <= MAX_SNIPPET_CHARS
        }
        return CodeSnippet(start, selected)
    }

    companion object {
        // Объявленные пределы объёма одного фрагмента для местной модели.
        private const val MAX_LINES = 28
        private const val MAX_LINE_CHARS = 280
        private const val MAX_SNIPPET_CHARS = 1_000

        fun read(path: String, text: String): RepositorySource {
            val indexer = KotlinCodeIndexer.takeIf { path.endsWith(".kt") || path.endsWith(".kts") }
            return RepositorySource(text.lines(), indexer?.index(text).orEmpty())
        }
    }
}

/** Разбирает объявления и явные импорты. Имена вызовов без разрешения типов остаются текстом. */
internal object KotlinCodeIndexer : CodeIndexer {
    private const val ID = """(?:[\p{L}_][\p{L}\p{N}_]*|`[^`]+?`)"""
    private val function = Regex("""\bfun\s+(?:<[^>]+>\s*)?(?:[\w.<>,? ]+\.)?($ID)\s*\(""")
    private val declaration = Regex("""\b(?:class|interface|object|typealias)\s+($ID)""")
    private val import = Regex("""^\s*import\s+([\w.*`]+)(?:\s+as\s+($ID))?\s*;?\s*$""")
    private val dependency = Regex(
        """^\s*(?:implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation)\s*\(\s*(?:project\()?["']([^"'\$]+)["']""",
    )
    override fun index(source: String): List<CodeEntry> {
        val lines = maskNonCode(source).lines()
        val original = source.lines()
        return buildList {
            for ((index, line) in lines.withIndex()) {
                val number = index + 1
                function.find(line)?.let { match ->
                    val name = match.groupValues[1].trim('`')
                    val end = if (hasBlock(lines, index, match.range.last)) {
                        val nearby = lines.subList(index, minOf(index + 48, lines.size)).joinToString("\n")
                        StructuralBoundary.findFunctionSpans(nearby).firstOrNull {
                            it.name == name && it.startLine == 0
                        }?.let { number + it.endLine }
                    } else null
                    add(CodeEntry(name, CodeEntryKind.FUNCTION, number, end ?: number))
                }
                declaration.findAll(line).forEach { match ->
                    add(CodeEntry(match.groupValues[1].trim('`'), CodeEntryKind.DECLARATION, number, number))
                }
                import.matchEntire(line)?.let {
                    add(CodeEntry(it.groupValues.drop(1).filter(String::isNotBlank).joinToString(" "),
                        CodeEntryKind.IMPORT, number, number))
                }
                dependency.find(original[index])?.takeIf { line.trimStart().firstOrNull()?.isLetter() == true }?.let {
                    add(CodeEntry(it.groupValues[1], CodeEntryKind.DEPENDENCY, number, number))
                }
            }
        }
    }

    private fun hasBlock(lines: List<String>, start: Int, openingParen: Int): Boolean {
        val signature = lines[start].substring(openingParen) + "\n" +
            lines.subList(start + 1, minOf(start + 25, lines.size)).joinToString("\n")
        var depth = 0
        for ((index, ch) in signature.withIndex()) {
            if (ch == '(') depth++
            if (ch == ')' && --depth == 0) {
                return blockAfterParameters.containsMatchIn(signature.substring(index + 1))
            }
        }
        return false
    }

    private val blockAfterParameters = Regex("""^\s*(?::[^={}\n]+)?\s*\{""")

    /** Скрывает комментарии и литералы, сохраняя номера строк и позиции. */
    private fun maskNonCode(source: String): String {
        val masked = StringBuilder(source)
        var at = 0
        while (at < source.length) {
            val end = when {
                source.startsWith("//", at) -> source.indexOfAny(charArrayOf('\r', '\n'), at + 2)
                    .takeIf { it >= 0 } ?: source.length
                source.startsWith("/*", at) -> blockCommentEnd(source, at)
                source.startsWith("\"\"\"", at) -> source.indexOf("\"\"\"", at + 3)
                    .takeIf { it >= 0 }?.plus(3) ?: source.length
                source[at] == '"' || source[at] == '\'' -> literalEnd(source, at)
                else -> {
                    at++
                    continue
                }
            }
            for (index in at until end) {
                if (source[index] != '\n' && source[index] != '\r') masked.setCharAt(index, ' ')
            }
            at = end
        }
        return masked.toString()
    }

    private fun blockCommentEnd(source: String, start: Int): Int {
        var at = start + 2
        var depth = 1
        while (at < source.length) {
            when {
                source.startsWith("/*", at) -> { depth++; at += 2 }
                source.startsWith("*/", at) -> {
                    at += 2
                    if (--depth == 0) return at
                }
                else -> at++
            }
        }
        return at
    }

    private fun literalEnd(source: String, start: Int): Int {
        var at = start + 1
        while (at < source.length) {
            val ch = source[at++]
            if (ch == source[start] || ch == '\n' || ch == '\r') break
            if (ch == '\\' && at < source.length) at++
        }
        return at
    }
}
