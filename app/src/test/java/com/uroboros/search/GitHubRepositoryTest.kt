package com.uroboros.search

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRepositoryTest {
    private val commit = "a".repeat(40)
    private val tree = "b".repeat(40)
    private val blob = "c".repeat(40)
    private val project = GitHubProject("someone", "project")

    private fun ref() = JSONArray().put(JSONObject().put("sha", commit).put("commit",
        JSONObject().put("tree", JSONObject().put("sha", tree)))).toString()

    private fun listing(vararg files: Pair<String, String>): String = JSONObject().put("tree",
        JSONArray().apply {
            files.forEach { (path, sha) ->
                put(JSONObject().put("path", path).put("type", "blob").put("mode", "100644")
                    .put("size", 120).put("sha", sha))
            }
        },
    ).toString()

    private fun response(body: String = "", code: Int = 200) =
        CrawlResponse(code, "", body.toByteArray(), null, "")

    @Test
    fun linksRecognizePublicRepositoryAndSourceAndRejectUnrelatedRoutes() {
        fun parse(link: String) = GitHubTarget.parse(link.toHttpUrl(), link.toHttpUrl().fragment)
        assertEquals(GitHubTarget.Project(project, null), parse("https://github.com/someone/project"))
        assertEquals(GitHubTarget.Project(project, "feature/search"), parse("https://github.com/someone/project/tree/feature/search"))
        assertEquals(
            GitHubTarget.File(project, listOf("main", "src", "File.kt"), 12..14),
            parse("https://github.com/someone/project/blob/main/src/File.kt#L12-L14"),
        )
        assertNull(parse("https://github.com/someone/project/issues/1"))
        assertNull(parse("https://github.com/topics/android"))
        assertNull(parse("https://github.com.evil.example/someone/project"))
        assertNull(parse("https://github.com/someone/project/blob/main/src%2Fother%2FFile.kt"))
        assertNull(parse("https://github.com/someone/project?token=123"))
        assertEquals(
            GitHubTarget.File(project, listOf("feature", "search", "File.kt"), null, "refs/heads/"),
            parse("https://raw.githubusercontent.com/someone/project/refs/heads/feature/search/File.kt"),
        )
        assertNull(SearchCommand.fromOwnerWords("Поищи в репозитории https://github.com/someone/project настройки"))
    }

    @Test
    fun rootListingThenNamedMarkdownUsesTheSameCommit() = runBlocking {
        val repository = GitHubRepository { url, kind, _ ->
            when {
                kind == CrawlResource.REPOSITORY_SOURCE -> {
                    assertEquals(project.raw(commit, "BIBLE.md"), url)
                    response("# Уроборос\nПравила агента.")
                }
                "/trees/" in url.encodedPath -> response(listing("BIBLE.md" to blob, "src/A.kt" to blob))
                else -> response(ref())
            }
        }
        val view = repository.read(GitHubTarget.Project(project, null), "Что видно на главной странице?") { true } as RepositoryReading.Found
        assertEquals(listOf("BIBLE.md", "src/"), view.paths)
        val file = GitHubTarget.File(view.project, listOf(view.commit, "BIBLE.md"), null)
        val read = repository.read(file, "Открой BIBLE.md") { true } as RepositoryReading.Found
        assertTrue(read.excerpts.single().snippet.numbered().contains("Правила агента"))
    }

    @Test
    fun repositoryFetchesRelevantFilesAndReusesVersionedMapAndParsedSource() = runBlocking {
        val requestUrls = mutableListOf<String>()
        val search = GitHubRepository { url, type, _ ->
            requestUrls += url.toString()
            when (type) {
                CrawlResource.REPOSITORY_JSON ->
                    if ("/trees/" in url.encodedPath) response(listing(
                        "src/DeferredSearch.kt" to blob,
                        "assets/logo.jpg" to "d".repeat(40),
                        "src/Unrelated.kt" to "e".repeat(40),
                        "tools/deferred.py" to "f".repeat(40),
                    )) else response(ref())
                CrawlResource.REPOSITORY_SOURCE -> {
                    assertTrue(url.encodedPath.contains(commit))
                    if (url.encodedPath.endsWith(".kt")) response("package demo\n\nfun scheduleDeferred() {\n    enqueue()\n}\n")
                    else response("def deferred():\n    pass\n")
                }
                else -> error("Запрос должен читать карту или исходник")
            }
        }
        val target = GitHubTarget.Project(project, null)
        val question = "Где запускается отложенный поиск?"
        val first = search.read(target, question) { true } as RepositoryReading.Found
        assertEquals(4, first.fileCount)
        assertEquals(2, first.inspected)
        assertEquals(0, first.fromCache)
        assertEquals("src/DeferredSearch.kt", first.excerpts.first().path)
        assertEquals(3, first.excerpts.first().snippet.start)
        assertEquals(5, first.excerpts.first().snippet.end)
        assertTrue(first.excerpts.first().url.endsWith("/blob/$commit/src/DeferredSearch.kt#L3-L5"))
        assertTrue(first.excerpts.first().snippet.numbered().contains("4 |     enqueue()"))
        assertTrue(first.excerpts.any { it.path.endsWith(".py") })
        assertFalse(requestUrls.any { "Unrelated" in it || "logo" in it })
        assertEquals(4, requestUrls.size)

        val again = search.read(target, question) { true } as RepositoryReading.Found
        assertEquals(2, again.fromCache)
        assertEquals(5, requestUrls.size)
        assertEquals(first.excerpts, again.excerpts)
    }

    @Test
    fun fileAtCommitUsesOnlyRawAndKeepsRequestedLines() = runBlocking {
        val search = GitHubRepository { url, type, _ ->
            assertEquals(CrawlResource.REPOSITORY_SOURCE, type)
            assertEquals("raw.githubusercontent.com", url.host)
            assertTrue(url.encodedPath.endsWith("/$commit/dir/File.kt"))
            response("package a\nfun hello() {\n    say()\n}\n")
        }
        val target = GitHubTarget.File(project, listOf(commit, "dir", "File.kt"), 2..3)
        val result = search.read(target, "разбери") { true } as RepositoryReading.Found
        assertEquals("2 | fun hello() {\n3 |     say()", result.excerpts.single().snippet.numbered())
    }

    @Test
    fun withdrawnPermissionStopsBeforeAnyFileIsFetched() = runBlocking {
        var allowed = true
        var requests = 0
        val search = GitHubRepository { _, _, _ ->
            requests++
            if (requests == 1) response(ref()) else {
                allowed = false
                response(listing("DeferredSearch.kt" to blob))
            }
        }
        val result = search.read(GitHubTarget.Project(project, null), "DeferredSearch") { allowed }
        assertTrue(result is RepositoryReading.Refused)
        assertEquals(2, requests)
    }

    @Test
    fun kotlinIndexFindsDeclarationsImportsAndLiteralDependencies() {
        val code = """
            package demo
            import a.b.Task as Work
            // fun invented() {}
            val snippet = "class Invented"
            class Desk
            fun schedule() = prepare()
            fun prepare() {
                val message = "}"
                action()
            }
            implementation("org.sample:library:1.0")
            implementation(project(":core"))
            // implementation("ignore")
            implementation(libs.xyz)
        """.trimIndent()
        val entries = KotlinCodeIndexer.index(code)
        assertEquals(
            listOf(
                CodeEntry("a.b.Task Work", CodeEntryKind.IMPORT, 2, 2),
                CodeEntry("Desk", CodeEntryKind.DECLARATION, 5, 5),
                CodeEntry("schedule", CodeEntryKind.FUNCTION, 6, 6),
                CodeEntry("prepare", CodeEntryKind.FUNCTION, 7, 10),
                CodeEntry("org.sample:library:1.0", CodeEntryKind.DEPENDENCY, 11, 11),
                CodeEntry(":core", CodeEntryKind.DEPENDENCY, 12, 12),
            ), entries,
        )
    }

    @Test
    fun explicitDependencyQuestionCanSurfaceBuildMetadata() {
        val words = RepositoryWords("Какие зависимости у проекта?")
        assertTrue(words.fileScore(RepositoryFile("app/build.gradle.kts", blob, 200)) >
            words.fileScore(RepositoryFile("app/Random.kt", commit, 200)))
    }

    @Test
    fun repositoryTransportSeparatesRawAndJsonFromHtml() {
        assertTrue(CrawlResource.REPOSITORY_SOURCE.accepts("text/plain"))
        assertFalse(CrawlResource.REPOSITORY_SOURCE.accepts("text/html"))
        assertTrue(CrawlResource.REPOSITORY_JSON.accepts("application/json"))
        assertFalse(CrawlResource.PAGE.accepts("text/plain"))
    }
}
