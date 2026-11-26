package com.uroboros.search

import com.uroboros.memory.nav.LemmaDictionary
import java.io.IOException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class GitHubProject(val owner: String, val name: String) {
    val label get() = "$owner/$name"
    val url get() = "https://github.com/$label"

    fun api() = "https://api.github.com/repos/".toHttpUrl().newBuilder()
        .addPathSegment(owner).addPathSegment(name)

    fun raw(commit: String, path: String) = "https://raw.githubusercontent.com/".toHttpUrl().newBuilder()
        .addPathSegment(owner).addPathSegment(name).addPathSegment(commit).addPathSegments(path).build()

    fun codeLink(commit: String, path: String, snippet: CodeSnippet): String =
        url.toHttpUrl().newBuilder().addPathSegment("blob").addPathSegment(commit)
            .addPathSegments(path).fragment("L${snippet.start}-L${snippet.end}").build().toString()
}

internal sealed interface GitHubTarget {
    val project: GitHubProject

    data class Project(override val project: GitHubProject, val ref: String?) : GitHubTarget
    data class File(
        override val project: GitHubProject,
        val refAndPath: List<String>,
        val lines: IntRange?,
        val refPrefix: String = "",
    ) : GitHubTarget

    companion object {
        private val slug = Regex("[A-Za-z0-9_.-]{1,100}")
        private val range = Regex("L([1-9][0-9]{0,8})(?:-L([1-9][0-9]{0,8}))?")
        private val globalRoutes = setOf("topics", "orgs", "users", "marketplace", "settings", "sponsors", "collections", "features")

        fun parse(url: HttpUrl, fragment: String?): GitHubTarget? {
            if (url.host !in setOf("github.com", "www.github.com", "raw.githubusercontent.com") ||
                url.encodedQuery != null
            ) return null
            val path = url.pathSegments.dropLastWhile { it.isEmpty() }
            if (path.size < 2 || path.any { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it }) return null
            val owner = path[0]
            val name = path[1].removeSuffix(".git")
            if (!slug.matches(owner) || !slug.matches(name)) return null
            val project = GitHubProject(owner, name)
            val lines = fragment?.let(range::matchEntire)?.let {
                val from = it.groupValues[1].toInt()
                val to = it.groupValues[2].toIntOrNull() ?: from
                if (to >= from) from..to else null
            }
            return when {
                url.host == "raw.githubusercontent.com" && path.size >= 6 &&
                    path[2] == "refs" && path[3] in setOf("heads", "tags") ->
                    File(project, path.drop(4), lines, "refs/${path[3]}/")
                url.host == "raw.githubusercontent.com" && path.size >= 4 -> File(project, path.drop(2), lines)
                url.host == "raw.githubusercontent.com" -> null
                owner.lowercase() in globalRoutes -> null
                path.size == 2 -> Project(project, null)
                path[2] == "tree" && path.size >= 4 -> Project(project, path.drop(3).joinToString("/"))
                path[2] == "blob" && path.size >= 5 -> File(project, path.drop(3), lines)
                else -> null
            }
        }
    }
}

internal data class RepositoryExcerpt(val path: String, val snippet: CodeSnippet, val url: String)

internal sealed interface RepositoryReading {
    data class Refused(val why: String) : RepositoryReading
    data class Found(
        val project: GitHubProject,
        val commit: String,
        val excerpts: List<RepositoryExcerpt>,
        val inspected: Int,
        val fromCache: Int,
        val fileCount: Int? = null,
        val paths: List<String> = emptyList(),
        val note: String? = null,
    ) : RepositoryReading {
        fun status(): String {
            val scope = if (fileCount == null) "Открыт файл" else "Файлов на карте $fileCount, просмотрено $inspected"
            val cached = if (fromCache > 0) ", из кэша $fromCache" else ""
            return "GitHub · ${project.label} · ${commit.take(8)}\n$scope$cached." +
                (note?.let { " $it" } ?: "")
        }
    }
}

/**
 * Запросы ограничены присланным публичным репозиторием. Пути и версии берутся
 * из GitHub, выбор делает код. Тела файлов остаются текстом для читателя.
 * Карта и разобранные файлы помещаются в ограниченный кэш процесса.
 */
internal class GitHubRepository(
    private val fetch: suspend (HttpUrl, CrawlResource, PageValidators) -> CrawlResponse,
) {
    private data class Version(val commit: String, val tree: String)
    private data class Tree(val files: List<RepositoryFile>, val partial: Boolean)
    private data class Candidate(val file: RepositoryFile, val source: RepositorySource?, val score: Int)
    private class Failure(val reason: String, val code: Int = 0) : IOException(reason)

    private val trees = Cache<String, Tree>(3)
    private val sources = Cache<String, RepositorySource>(16)

    suspend fun read(
        target: GitHubTarget,
        question: String,
        dictionary: LemmaDictionary? = null,
        allowed: () -> Boolean,
    ): RepositoryReading = try {
        val words = RepositoryWords(question, dictionary)
        when (target) {
            is GitHubTarget.File -> readFile(target, words, allowed)
            is GitHubTarget.Project -> readProject(target, question.isBlank(), words, allowed)
        }
    } catch (failure: Failure) {
        RepositoryReading.Refused(failure.reason)
    }

    private suspend fun readProject(
        target: GitHubTarget.Project, overview: Boolean, words: RepositoryWords, allowed: () -> Boolean,
    ): RepositoryReading {
        val project = target.project
        val version = resolve(project, target.ref, allowed)
        val tree = trees[version.tree] ?: tree(project, version.tree, allowed).also { trees.put(version.tree, it) }
        val candidates = tree.files.asSequence()
            .filter { it.size in 1..CrawlResource.REPOSITORY_SOURCE.limit && isText(it.name) }
            .map { file ->
                val cached = sources[cacheKey(file.path, file.blob)]
                val score = if (overview) {
                    when (file.name.lowercase()) {
                        "readme.md", "readme" -> 15
                        "architecture.md" -> 10
                        else -> 0
                    }
                } else words.fileScore(file, cached?.entries.orEmpty())
                Candidate(file, cached, score)
            }
            .toMutableList()

        val excerpts = mutableListOf<RepositoryExcerpt>()
        val related = mutableMapOf<String, MutableList<String>>()
        fun score(candidate: Candidate): Int {
            val path = candidate.file.path.substringBeforeLast('.')
            val references = related[candidate.file.name.substringBeforeLast('.')].orEmpty()
            return candidate.score + references.count { path.endsWith(it) } * 14
        }
        val order = compareByDescending<Candidate>(::score)
            .thenBy { it.file.path.count { ch -> ch == '/' } }.thenBy { it.file.path }
        var loaded = 0
        var cached = 0
        var missing: String? = null
        val hasCandidates = candidates.any { it.score > 0 }
        for (attempt in 0 until MAX_FILES) {
            val chosen = candidates.minWithOrNull(order) ?: break
            if (score(chosen) == 0) break
            candidates.remove(chosen)
            val (file, saved) = chosen
            requireAllowed(allowed)
            val source = try {
                saved?.also { cached++ } ?: download(project, version.commit, file.path, allowed)
                    .also { sources.put(cacheKey(file.path, file.blob), it) }
            } catch (failure: Failure) {
                requireAllowed(allowed)
                missing = failure.reason
                if (failure.code == 403 || failure.code == 429) break
                continue
            }
            loaded++
            if (!overview) source.entries.asSequence().filter { it.kind == CodeEntryKind.IMPORT }
                .map { it.name.substringBefore(' ') }.distinct().forEach { qualified ->
                    val path = "/" + qualified.replace('.', '/')
                    related.getOrPut(qualified.substringAfterLast('.')) { mutableListOf() }.add(path)
                }
            source.snippet(words)?.let { snippet ->
                excerpts += RepositoryExcerpt(file.path, snippet, project.codeLink(version.commit, file.path, snippet))
            }
        }
        requireAllowed(allowed)
        val paths = if (excerpts.isEmpty()) tree.files.asSequence()
            .map { it.path.substringBefore('/') }.distinct().take(10).toList() else emptyList()
        val note = listOfNotNull(
            "Поиск по путям, прочитанным объявлениям и явным импортам.",
            "Карта неполная: GitHub или лимит телефона сократил список файлов.".takeIf { tree.partial },
            "Подходящих файлов по этому вопросу не нашлось.".takeUnless { hasCandidates },
            missing,
        ).joinToString(" ")
        return RepositoryReading.Found(project, version.commit, excerpts, loaded, cached, tree.files.size, paths, note)
    }

    private suspend fun readFile(
        target: GitHubTarget.File, words: RepositoryWords, allowed: () -> Boolean,
    ): RepositoryReading {
        val project = target.project
        val parts = target.refAndPath
        // При ссылке на ветку со слешем GitHub не отделяет её от пути.
        // Проверяем несколько возможных границ. Полный SHA задаёт точную границу.
        val refs = if (target.refPrefix.isEmpty() && sha.matches(parts[0])) 1 else minOf(parts.size - 1, MAX_REF_PARTS)
        for (size in 1..refs) {
            val ref = target.refPrefix + parts.take(size).joinToString("/")
            val path = parts.drop(size).joinToString("/")
            try {
                val commit = if (sha.matches(ref)) ref else resolve(project, ref, allowed).commit
                val key = "${project.label}:$commit:$path"
                val saved = sources[key]
                val source = saved ?: download(project, commit, path, allowed).also { sources.put(key, it) }
                requireAllowed(allowed)
                val snippet = source.snippet(words, target.lines)
                    ?: return RepositoryReading.Refused("в файле нет указанных строк")
                val note = "Показан фрагмент до строки ${snippet.end}.".takeIf {
                    target.lines?.last?.let { end -> snippet.end < end } == true
                }
                return RepositoryReading.Found(project, commit,
                    listOf(RepositoryExcerpt(path, snippet, project.codeLink(commit, path, snippet))), 1,
                    if (saved == null) 0 else 1, note = note)
            } catch (failure: Failure) {
                if (failure.code != 404) throw failure
            }
        }
        return RepositoryReading.Refused("файл или ветка не найдены. Для ветки со сложным именем подойдёт ссылка на конкретный коммит")
    }

    private suspend fun resolve(project: GitHubProject, ref: String?, allowed: () -> Boolean): Version {
        val url = project.api().addPathSegment("commits").addQueryParameter("per_page", "1")
            .apply { if (ref != null) addQueryParameter("sha", ref) }.build()
        val response = response(url, CrawlResource.REPOSITORY_JSON, allowed)
        return parseJson {
            val commit = org.json.JSONArray(response).getJSONObject(0)
            Version(commit.getString("sha").checkedSha(),
                commit.getJSONObject("commit").getJSONObject("tree").getString("sha").checkedSha())
        }
    }

    private suspend fun tree(project: GitHubProject, id: String, allowed: () -> Boolean): Tree {
        val url = project.api().addPathSegments("git/trees").addPathSegment(id)
            .addQueryParameter("recursive", "1").build()
        val response = response(url, CrawlResource.REPOSITORY_JSON, allowed)
        return parseJson {
            val tree = org.json.JSONObject(response)
            val rows = tree.getJSONArray("tree")
            val files = ArrayList<RepositoryFile>()
            for (index in 0 until rows.length()) {
                if (files.size == MAX_MAP_FILES) break
                val row = rows.getJSONObject(index)
                val path = row.getString("path")
                val blob = row.getString("sha")
                if (row.getString("type") == "blob" && row.optString("mode") in setOf("100644", "100755") &&
                    path.length in 1..512 && path.split('/').none { it.isBlank() || it == "." || it == ".." } &&
                    path.none { it.isISOControl() || it == '\\' } && sha.matches(blob)
                ) files += RepositoryFile(path, blob, row.optInt("size", -1))
            }
            Tree(files, tree.optBoolean("truncated") || files.size == MAX_MAP_FILES)
        }
    }

    private suspend fun download(project: GitHubProject, commit: String, path: String, allowed: () -> Boolean): RepositorySource {
        val source = response(project.raw(commit, path), CrawlResource.REPOSITORY_SOURCE, allowed)
        if (source.any { it == '\u0000' } || source.isBlank()) throw Failure("GitHub не вернул читаемый исходник.")
        return RepositorySource.read(path, source)
    }

    private suspend fun response(url: HttpUrl, type: CrawlResource, allowed: () -> Boolean): String {
        requireAllowed(allowed)
        val timeout = if (type == CrawlResource.REPOSITORY_JSON) MAP_REQUEST_MS else SOURCE_REQUEST_MS
        val reply = try {
            withTimeoutOrNull(timeout) { fetch(url, type, PageValidators()) }
        } catch (_: IOException) {
            throw Failure("Сетевой сбой при чтении GitHub или файл превышает допустимый размер.")
        }
        requireAllowed(allowed)
        if (reply == null) throw Failure("GitHub не ответил вовремя.")
        when (reply.code) {
            200 -> Unit
            404 -> throw Failure("GitHub не нашёл публичный репозиторий, версию или файл.", 404)
            403, 429 -> throw Failure("GitHub ограничил частоту запросов или доступ.", reply.code)
            415 -> throw Failure("GitHub вернул неподдерживаемый формат файла.")
            else -> throw Failure("GitHub ответил кодом ${reply.code}.", reply.code)
        }
        return parseJson { reply.body.decodeToString(throwOnInvalidSequence = true) }
    }

    private fun requireAllowed(allowed: () -> Boolean) {
        if (!allowed()) throw Failure("чтение остановлено: разрешение или состояние устройства изменилось")
    }

    private inline fun <T> parseJson(parse: () -> T): T =
        try { parse() } catch (_: Exception) { throw Failure("GitHub вернул данные в неожиданном формате.") }

    private fun String.checkedSha(): String = takeIf(sha::matches) ?: error("SHA")
    private fun cacheKey(path: String, blob: String) = path.substringAfterLast('.') + ":" + blob

    private class Cache<K, V>(private val limit: Int) {
        private val values = LinkedHashMap<K, V>(limit, 0.75f, true)

        @Synchronized operator fun get(key: K): V? = values[key]

        @Synchronized fun put(key: K, value: V) {
            values[key] = value
            if (values.size > limit) values.remove(values.keys.first())
        }
    }

    private companion object {
        val sha = Regex("[0-9a-f]{40}")
        val extensions = setOf("kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "go", "rs",
            "c", "h", "cc", "cpp", "hpp", "cs", "rb", "swift", "md", "txt", "toml", "xml", "json", "yaml", "yml", "gradle")
        fun isText(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in extensions ||
            name in setOf("README", "Makefile", "Dockerfile", "go.mod")

        // Объявленные пределы одной просьбы и локального кэша на телефоне.
        const val MAX_FILES = 4
        const val MAX_MAP_FILES = 6_000
        const val MAX_REF_PARTS = 3
        const val MAP_REQUEST_MS = 12_000L
        const val SOURCE_REQUEST_MS = 8_000L
    }
}
