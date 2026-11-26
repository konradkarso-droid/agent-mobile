package com.uroboros.search

import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.uroboros.memory.ActionProvenance
import com.uroboros.memory.ActionRequest
import com.uroboros.memory.ActionType
import com.uroboros.memory.EmergencyStop
import com.uroboros.memory.GateResult
import com.uroboros.memory.GatedAction
import com.uroboros.memory.StandingConsent
import com.uroboros.memory.dream.CuriosityFollowUp
import com.uroboros.memory.nav.LemmaHolder
import com.uroboros.memory.nav.MeaningHolder
import com.uroboros.move.MoveState
import com.uroboros.safety.DeviceSafetyWatchdog
import com.uroboros.safety.SafetyZone
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class SearchPlatform(
    private val app: Context,
    private val watchdog: DeviceSafetyWatchdog,
    private val processScope: CoroutineScope,
    /** Почему модель сейчас не может отвечать (null — может); причина ляжет в строку «Ответ модели недоступен — …». */
    private val modelHalted: () -> String? = { null },
    infer: suspend (String, String) -> String?,
) {
    private val preferences = app.getSharedPreferences("web_search", Context.MODE_PRIVATE)
    private val index = LocalWebIndex(app)
    private val crawler = WebCrawler(index)
    private val directFetch = CrawlTransport(budgets = FetchBudgets.DIRECT)::fetch
    private val linkOpener = LinkOpener(directFetch)
    private val github = GitHubRepository(directFetch)
    private val reader = PageReader(infer)
    private val autoRefresh = WebRefreshSchedule(app)

    init {
        // Выученные из присланых ссылок источники переживают перезапуск: восстановлены
        // с диска до первого поиска и открытия ссылки. CrawlSources — состояние
        // процесса; диск — здесь, у владельца поиска.
        preferences.getStringSet(LEARNED_SOURCES, emptySet())?.let(CrawlSources::restore)
    }

    private fun blocked() = EmergencyStop.isActive() || watchdog.zone.value == SafetyZone.CRITICAL

    private val engine = WebSearch(
        history = JsonSearchHistory(
            readJson = { preferences.getString(HISTORY, "[]").orEmpty() },
            writeJson = { preferences.edit().putString(HISTORY, it).commit() },
        ),
        stopped = combine(EmergencyStop.active, watchdog.zone) { stop, zone -> stop || zone == SafetyZone.CRITICAL },
        blocked = ::blocked,
        available = { true },
        authorize = { true },
        provider = SearchProvider { answer(it, lookup(it)) },
        requestTimeoutMs = 125_000,
    )

    suspend fun fromApp(
        command: SearchCommand,
        ownerPresent: () -> Boolean = { false },
        onReply: ((SearchReply) -> Unit)? = null,
    ): SearchReply = processScope.async(Dispatchers.IO) {
        val reply = when (command) {
            is SearchCommand.Find, SearchCommand.Refresh -> perform(command, SearchChannel.APP)
            is SearchCommand.AutoRefresh -> autoRefreshCommand(command, ownerPresent)
            SearchCommand.Last -> engine.snapshot().latestReply()
            SearchCommand.History -> SearchReply(engine.snapshot().historyText())
            is SearchCommand.Deferred -> scheduleDeferred(command)
        }
        if (onReply != null) withContext(Dispatchers.Main.immediate) { onReply(reply) }
        reply
    }.await()

    suspend fun fromTelegram(
        command: SearchCommand,
        messageId: String,
        stillOwner: () -> Boolean,
        onAccepted: () -> Unit,
    ): SearchReply = withContext(Dispatchers.IO) {
        perform(command, SearchChannel.TELEGRAM, messageId, stillOwner, onAccepted)
    }

    suspend fun snapshot(): SearchView = withContext(Dispatchers.IO) { engine.snapshot() }

    fun deferredSearchAllowed(): Boolean = !blocked() && MoveState.holdReason(app) == null

    fun scheduleDeferred(
        command: SearchCommand.Deferred,
        telegram: DeferredSearchTelegram? = null,
        stillAllowed: () -> Boolean = { true },
    ): SearchReply {
        val now = ZonedDateTime.now()
        val query = command.query.trim()
        if (!query.any { it.isLetterOrDigit() } || !validSearchPhrase(query)) {
            return SearchReply("Укажи, что искать. Например, «поищи через час расписание поездов».")
        }
        val runAt = DeferredSearchParser.runAt(command.time, now)
            ?: return SearchReply("Укажи время в будущем. Например, «через час» или «завтра в 19:00».")
        if (!deferredSearchAllowed() || !stillAllowed()) return SearchReply(SearchStatus.DENIED.label)
        val request = DeferredSearch(
            id = telegram?.requestId ?: "deferred:" + UUID.randomUUID(),
            query = query, runAt = runAt,
            channel = if (telegram == null) SearchChannel.APP else SearchChannel.TELEGRAM, telegram = telegram,
        )
        val scheduled = try {
            DeferredSearchScheduler(app).enqueue(request)
        } catch (_: Exception) {
            null
        } ?: return SearchReply("Не удалось поставить поиск в расписание Android.")
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(scheduled))
        val result = if (telegram == null) "Результат появится в истории поиска." else "Результат пришлю в этот чат."
        return SearchReply("Поиск «$query» запланирован. Android запустит его не раньше $time, когда будут сеть и заряд. $result")
    }

    suspend fun runDeferred(request: DeferredSearch, stillAllowed: () -> Boolean = { true }): SearchReply =
        perform(
            SearchCommand.Find(request.query), request.channel, request.id,
            stillOwner = { deferredSearchAllowed() && stillAllowed() }, resume = true,
        )

    /**
     * Прямое открытие присланой ссылки: приём адреса, ворота действий, загрузка,
     * чтение. Ответ — готовый текст для чата с источником; отказ тоже текстом,
     * чтобы отправитель видел причину, а не молчание.
     *
     * [origin] задаёт компонент и происхождение для ворот: ссылка из группы
     * идёт не-пользовательским происхождением и без отдельного разрешения
     * владельца ([consent]) ворота её отклоняют сами. В историю поиска
     * открытие не пишется: «что искал» и «какие страницы открывал по чужим
     * ссылкам» — разные вопросы.
     */
    suspend fun followLink(
        origin: LinkOrigin,
        address: String,
        question: String,
        consent: StandingConsent? = null,
        stillAllowed: () -> Boolean = { true },
    ): SearchReply = withContext(Dispatchers.IO) {
        fun refused(why: String) = SearchReply("Ссылку не открываю: $why")
        if (blocked()) return@withContext refused("аварийный стоп или критическая зона — сеть закрыта")
        val url = LinkAdmission.admit(address)
            ?: return@withContext refused("адрес не годится — не https, с паролем, с портом или слишком длинный")
        val repository = if (origin == LinkOrigin.GROUP) null
            else GitHubTarget.parse(url, address.substringAfter('#', ""))
        val verdict = GatedAction.evaluate(
            app,
            ActionRequest(
                type = ActionType.NETWORK_CALL,
                requestedBy = if (repository == null) origin.requestedBy else "repository-search/" + origin.name,
                provenance = origin.provenance, crossesDeviceBoundary = true, isReversible = false,
            ),
            consent,
        )
        if (verdict.result != GateResult.ALLOW) return@withContext refused("ворота действий отказали — причина в журнале действий")
        if (repository != null) {
            return@withContext readRepository(repository, question) { stillAllowed() && !blocked() }
        }
        val opened = withTimeoutOrNull(OPEN_LIMIT_MS) { linkOpener.open(url) { stillAllowed() && !blocked() } }
            ?: return@withContext refused("не уложился по времени")
        when (opened) {
            is LinkOpener.Open.Refused -> refused(opened.why)
            is LinkOpener.Open.Page -> {
                // Страница открылась и прочиталась — её сайт становится источником обхода
                // (см. learnSource): белый список растёт из присланых ссылок обоих каналов.
                learnSource(opened.url)
                val query = question.trim().ifEmpty { LinkOpener.SUMMARY_QUESTION }
                // Чтение помнит, кончилось ли само: сорванный срок и честный пустой ответ
                // модели дают одинаковый null, а называются по-разному — владелец
                // видит причину, а не молчание.
                var finished = false
                val answer = withTimeoutOrNull(READ_LIMIT_MS) {
                    reader.answer(query, listOf(IndexedPage(opened.url, opened.title, opened.text)))
                        .also { finished = true }
                }
                val text = if (answer != null) "$answer\n\nИсточник: ${opened.url}"
                else modelUnavailableText(
                    why = if (finished) modelHalted() else "чтение не уложилось в срок",
                    title = opened.title, text = opened.text, url = opened.url,
                )
                SearchReply(text, sourceUrls = listOf(opened.url))
            }
        }
    }

    private suspend fun readRepository(
        target: GitHubTarget, question: String, allowed: () -> Boolean,
    ): SearchReply {
        val result = withTimeoutOrNull(REPOSITORY_LIMIT_MS) {
            github.read(target, question, LemmaHolder.loaded, allowed)
        } ?: return SearchReply("Репозиторий не успел открыться за отведённое время.")
        if (result is RepositoryReading.Refused) return SearchReply("Репозиторий не прочитан: ${result.why}")
        check(result is RepositoryReading.Found)
        if (!allowed()) return SearchReply("Чтение репозитория остановлено.")
        if (result.excerpts.isEmpty()) {
            val paths = result.paths.joinToString(", ")
            val list = if (paths.isEmpty()) "" else "\n\nНа карте: $paths"
            return SearchReply(result.status() + list, sourceUrls = listOf(result.project.url))
        }

        val excerpts = result.excerpts
        val query = question.ifBlank { "Объясни, что видно из этих файлов репозитория." }
        var finished = false
        val answer = withTimeoutOrNull(READ_LIMIT_MS) {
            reader.answerCode(query, excerpts).also { finished = true }
        }
        if (!allowed()) return SearchReply("Чтение репозитория остановлено.")
        val first = excerpts.first()
        val body = answer ?: buildString {
            val reason = if (finished) modelHalted() else "чтение не уложилось в срок"
            appendLine(reason?.let { "Ответ модели недоступен — $it." } ?: "Ответ модели недоступен.")
            appendLine(first.path)
            append(first.snippet.numbered().lineSequence().take(9).joinToString("\n").take(750))
        }
        val references = excerpts.mapIndexed { i, excerpt -> "[${i + 1}] ${excerpt.url}" }.joinToString("\n")
        val text = result.status() + "\n\n" + body + "\n\n" + references
        return SearchReply(text, sourceUrls = excerpts.map { it.url })
    }

    /**
     * Выучить источник из открывшейся страницы: сайт, чью страницу агент прочёл
     * по просьбе человека, становится адресом обхода — белый список растёт и от
     * ссылок из телеграма, и от ссылок из чата приложения (оба канала идут через
     * [followLink]). Хост берётся конечный, после переадресаций: обход пойдёт
     * туда, где лежит текст. Отказ и не открывшаяся страница ничего не выучивают —
     * источником становится прочитанный сайт, а не названный наугад.
     *
     * Открытая страница тут же сеется в очередь обхода (frontier): без посева
     * выученный сайт молчал бы — обход находит адреса только ссылками с уже
     * прочитанных страниц и посевами. Прочитает её обход сам, со своей паузой и
     * robots.txt, которых у прямого открытия нет; адрес с запросом (?) в очередь
     * не сеется — он не адрес обхода (CrawlSources.address).
     *
     * Новый хост сохраняется на диск сразу: выученное переживает перезапуск.
     */
    private fun learnSource(pageUrl: String) {
        val host = pageUrl.toHttpUrlOrNull()?.host ?: return
        if (CrawlSources.learn(host)) {
            preferences.edit().putStringSet(LEARNED_SOURCES, CrawlSources.learned()).commit()
        }
        // После выучивания: новому хосту адрес страницы обязан уже проходить.
        CrawlSources.address(pageUrl)?.let { page -> index.seed(listOf(page.toString())) }
    }

    suspend fun autoRefreshCommand(
        command: SearchCommand.AutoRefresh,
        ownerPresent: () -> Boolean = { false },
        stillAllowed: () -> Boolean = { true },
    ): SearchReply {
        val grant = if (command == SearchCommand.AutoRefresh.Enable) {
            withContext(Dispatchers.Main.immediate) {
                if (ownerPresent()) grantScheduledRefresh(System.currentTimeMillis()) else null
            }
        } else null
        return withContext(Dispatchers.IO) {
            if (!stillAllowed()) return@withContext SearchReply(SearchStatus.DENIED.label)
            try {
                when (command) {
                    SearchCommand.AutoRefresh.Enable -> if (grant == null) {
                        SearchReply("Включить автообновление можно в приложении после открытия замка.")
                    } else autoRefresh.enable(grant)
                    SearchCommand.AutoRefresh.Disable -> autoRefresh.disable()
                    SearchCommand.AutoRefresh.Status -> SearchReply(
                        "${indexLine(index.status())}\n${autoRefresh.status().text}",
                    )
                }
            } catch (_: IllegalStateException) {
                SearchReply("Не удалось связаться с планировщиком Android.")
            } catch (_: SecurityException) {
                SearchReply("Android ограничил доступ к планировщику.")
            }
        }
    }

    suspend fun refreshScheduled(network: Network) {
        val grant = autoRefresh.consent() ?: return
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val allowed = {
            autoRefresh.consent()?.id == grant.id && !blocked() &&
                watchdog.zone.value < SafetyZone.FATIGUE && MoveState.holdReason(app) == null &&
                connectivity?.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        }
        val result = engine.background {
            if (!allowed()) {
                return@background SearchPage(emptyList(), status = SearchStatus.STOPPED,
                    detail = "Условия фонового обхода сейчас не выполнены.")
            }
            guardedRefresh(scheduledRefreshRequest(), allowed, grant) {
                WebCrawler(index, CrawlTransport(network)::fetch).refresh(emptyList(), allowed, forceSeeds = false)
            }
        }
        val message = if (result.status == SearchStatus.BUSY) {
            "Фоновая проверка уступила текущему поиску."
        } else result.detail ?: (result.status ?: SearchStatus.UNAVAILABLE).label
        autoRefresh.record(grant, message)
    }

    private suspend fun perform(
        command: SearchCommand, channel: SearchChannel, requestId: String = UUID.randomUUID().toString(),
        stillOwner: () -> Boolean = { true }, onAccepted: () -> Unit = {}, resume: Boolean = false,
    ): SearchReply {
        val phrase = when (command) {
            is SearchCommand.Find -> command.query
            SearchCommand.Refresh -> "Обновить источники"
            else -> error("Not a search operation")
        }
        return engine.search(
            phrase, channel, requestId, stillOwner, onAccepted, refresh = command == SearchCommand.Refresh, resume = resume,
        ) {
            val allowed = { stillOwner() && !blocked() }
            if (command == SearchCommand.Refresh) {
                refresh(channel, emptyList(), allowed, forceSeeds = true)
            } else {
                searchQuery(phrase, channel, allowed)
            }
        }
    }

    private suspend fun searchQuery(query: String, channel: SearchChannel, allowed: () -> Boolean): SearchPage {
        val route = SearchRoute.parse(query)
        val request = route.publication
        val window = request.window
        if (request.news) return newsListing(route, channel, allowed)
        if (route.source != null) {
            val local = lookupWithSemantics(request.subject, window, route.source, allowed)
            if (local.indexed.isNotEmpty() && (!local.needsRefresh || local.indexed.any { it.semanticSpan != null })) {
                return answer(request.subject, local, "Источник задан: ${route.source.name}.", window)
            }
            return newsListing(route, channel, allowed)
        }
        var matches = lookupWithSemantics(request.subject, window, null, allowed)
        var update: SearchPage? = null
        if (allowed() && (window != null || matches.needsRefresh)) {
            update = refresh(channel, matches.words.terms, allowed, window = window)
            if (allowed()) matches = lookupWithSemantics(request.subject, window, null, allowed, build = false)
        }
        if (window == null && matches.indexed.isEmpty() && matches.status.pages == 0 && update != null) return update
        if (matches.indexed.isEmpty() && request.subject.isNotBlank() && allowed()) {
            val alternative = withTimeoutOrNull(10_000) { reader.alternative(request.subject) }
            if (alternative != null && allowed()) {
                val rewritten = lookup(alternative, window)
                if (rewritten.indexed.isNotEmpty()) matches = rewritten
            }
        }
        if (!allowed()) return SearchPage(emptyList(), status = SearchStatus.DENIED)
        val updateNote = update?.detail?.let { note ->
            if (update.status == SearchStatus.DENIED) "${indexLine(matches.status)} $note" else note
        }
        val result = answer(query, matches, updateNote, window)
        return withSearchFollowUp(result, matches.status.pages > 0 &&
            (update == null || update.status == SearchStatus.UPDATED), sourceSpecified = false)
    }

    private suspend fun newsListing(
        route: SearchRoute, channel: SearchChannel, allowed: () -> Boolean,
    ): SearchPage {
        val request = route.publication
        val window = request.window ?: PublicationWindow(0L, System.currentTimeMillis() + 1L, "доступное в ленте")
        val sources = sourcesForNews(request.subject, route.source)
        val networkAllowed = GatedAction.evaluate(
            app,
            ActionRequest(
                type = ActionType.NETWORK_CALL, requestedBy = "web-search/news/" + channel.name,
                provenance = ActionProvenance.USER, crossesDeviceBoundary = true, isReversible = false,
            ),
        ).result == GateResult.ALLOW
        val checks = if (networkAllowed && allowed()) crawler.checkFeeds(sources, allowed, explicit = true)
        else sources.map { FeedCheck(it, index.feedState(it.feed.toString()), false) }
        if (!allowed()) return SearchPage(emptyList(), status = SearchStatus.DENIED)
        if (request.subject.isNotBlank()) LemmaHolder.load { app.assets.open(LemmaHolder.ASSET) }
        val words = SearchWords(newsWords(request.subject), LemmaHolder.loaded)
        val savedHeadlines = index.headlines(sources, window, System.currentTimeMillis())
        val headlines = selectHeadlines(savedHeadlines, words, window, SEARCH_LIMIT)
        val matches = if (headlines.isEmpty() && request.window != null) {
            val stored = lookup(request.subject, window, route.source).indexed.mapNotNull { match ->
                val page = match.page
                val address = page.url.toHttpUrlOrNull() ?: return@mapNotNull null
                val source = sources.firstOrNull { it.accepts(address) } ?: return@mapNotNull null
                NewsHeadline(source, FeedArticle(page.url, page.title, page.publishedAt), indexed = true)
            }
            selectHeadlines(stored, words, window, SEARCH_LIMIT)
        } else headlines
        val selected = matches.mapNotNull { item ->
            val article = item.article
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(checkNotNull(article.publishedAt)))
            val origin = if (item.indexed) "Публикация из локального индекса." else "Заголовок из ленты."
            SearchHit.from("$date · ${item.source.name} · ${article.title}", article.url, origin)
                ?.let { item to it }
        }
        val hits = selected.map { it.second }
        val articles = if (request.summary && selected.isNotEmpty() && modelHalted() == null) {
            val toRead = selected.take(2).map { it.first }
            val sourcesByUrl = toRead.mapIndexed { number, item -> item.article.url to number + 1 }.toMap()
            crawler.readHeadlines(toRead, allowed, networkAllowed).mapNotNull { page ->
                sourcesByUrl[page.url]?.let { it to page }
            }
        } else emptyList()
        val summary = if (allowed() && articles.isNotEmpty()) withTimeoutOrNull(NEWS_SUMMARY_MS) {
            reader.answerBrief(request.subject.ifBlank { "Кратко перескажи новости" }, articles, words)
        } else null
        if (!allowed()) return SearchPage(emptyList(), status = SearchStatus.DENIED)

        val detail = buildString {
            append("Период — ${window.label}. ")
            if (hits.isEmpty()) append("Совпадений за этот период среди доступных публикаций нет. ")
            if (!networkAllowed) append("Обновление сети отклонено. ")
            if (request.summary && summary == null && hits.isNotEmpty()) {
                append("Краткий пересказ сейчас недоступен. ")
            }
            if (summary != null) {
                append(articles.joinToString(prefix = "Пересказ по статьям ", postfix = ". ") { "[${it.first}]" })
                if (articles.size < hits.size) append("Остальные позиции приведены заголовками. ")
            }
            val savedBySource = savedHeadlines.groupBy { it.source }
            val dates = DateFormat.getDateInstance(DateFormat.SHORT)
            append(checks.joinToString(prefix = "Ленты — ", separator = "; ", postfix = ".") { check ->
                val last = check.state.checkedAt?.let {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
                }
                val status = when {
                    check.state.failed && last != null -> "сбой, данные от $last"
                    check.state.failed -> "сбой"
                    check.attempted && last != null -> "проверено $last"
                    last != null -> "данные от $last"
                    else -> "нет записи о проверке"
                }
                val observed = savedBySource[check.source].orEmpty().mapNotNull { it.article.publishedAt }
                val first = observed.minOrNull()?.let { dates.format(Date(it)) }
                val lastDay = observed.maxOrNull()?.let { dates.format(Date(it)) }
                val span = if (first == null) "" else if (first == lastDay) " ($first)" else " ($first – $lastDay)"
                "${check.source.name} · $status · заголовков за период ${observed.size}$span"
            })
        }
        return withSearchFollowUp(
            SearchPage(hits, status = if (hits.isEmpty()) SearchStatus.EMPTY else SearchStatus.FOUND,
                detail = detail, answer = summary),
            networkAllowed && checks.any { !it.state.failed && it.state.checkedAt != null },
            sourceSpecified = route.source != null,
        )
    }

    private fun withSearchFollowUp(page: SearchPage, usableSources: Boolean, sourceSpecified: Boolean): SearchPage {
        val followUp = CuriosityFollowUp.afterSearch(usableSources, page.hits.size, sourceSpecified) ?: return page
        return page.copy(detail = listOfNotNull(page.detail, followUp).joinToString(" "))
    }

    private suspend fun refresh(
        channel: SearchChannel, terms: List<String>, allowed: () -> Boolean, forceSeeds: Boolean = false,
        window: PublicationWindow? = null,
    ): SearchPage {
        val request = ActionRequest(
            type = ActionType.NETWORK_CALL, requestedBy = "web-search/crawl/" + channel.name,
            provenance = ActionProvenance.USER, crossesDeviceBoundary = true, isReversible = false,
        )
        return guardedRefresh(request, allowed) { crawler.refresh(terms, allowed, forceSeeds, window) }
    }

    private suspend fun guardedRefresh(
        request: ActionRequest, allowed: () -> Boolean, consent: StandingConsent? = null,
        execute: suspend () -> CrawlReport,
    ): SearchPage {
        val verdict = GatedAction.evaluate(app, request, consent)
        if (!allowed()) return SearchPage(emptyList(), status = SearchStatus.DENIED)
        if (verdict.result != GateResult.ALLOW) {
            return SearchPage(emptyList(), status = SearchStatus.DENIED, detail = "Врата отклонили сетевой обход.")
        }
        val report = execute()
        // Дозаполнение использует тот же рабочий проход и проверку остановки.
        prepareSemantic(allowed)
        return report.toSearchPage()
    }

    private data class Matches(
        val status: IndexStatus,
        val words: SearchWords,
        val indexed: List<IndexedMatch>,
        val needsRefresh: Boolean = indexed.firstOrNull()?.missingWords?.isNotEmpty() ?: true,
        val semanticNote: String? = null,
    )

    private fun semanticEncoder(): WebSemantics? {
        LemmaHolder.load { app.assets.open(LemmaHolder.ASSET) }
        MeaningHolder.load { app.assets.open(MeaningHolder.ASSET) }
        val dictionary = LemmaHolder.loaded ?: return null
        val vectors = MeaningHolder.loaded ?: return null
        return WebSemantics(vectors, dictionary)
    }

    private suspend fun prepareSemantic(allowed: () -> Boolean) {
        if (!allowed()) return
        try {
            withTimeoutOrNull(1_500) {
                semanticEncoder()?.let { index.fillSemantic(it, allowed) }
            }
        } catch (_: SQLiteException) {
            // Векторный слой может уступить место основному поиску.
        }
    }

    private suspend fun lookupWithSemantics(
        query: String, window: PublicationWindow?, source: WebFeedSource?, allowed: () -> Boolean, build: Boolean = true,
    ): Matches {
        val lexical = lookup(query, window, source, diversify = false)
        val base = lexical.copy(indexed = diverseMatches(lexical.indexed, SEARCH_LIMIT))
        if (base.status.pages == 0 || base.words.terms.isEmpty() || !allowed()) return base
        val mixed = try {
            withTimeoutOrNull(2_000) {
                val encoder = semanticEncoder() ?: return@withTimeoutOrNull null
                val vector = encoder.query(query) ?: return@withTimeoutOrNull emptyList<IndexedMatch>()
                if (build) withTimeoutOrNull(1_000) { index.fillSemantic(encoder, allowed) }
                val byMeaning = index.findSemantic(encoder, vector, base.words, CANDIDATE_LIMIT, window, source, allowed)
                if (byMeaning.isEmpty()) return@withTimeoutOrNull emptyList<IndexedMatch>()
                diverseMatches(combineWebMatches(lexical.indexed, byMeaning), SEARCH_LIMIT)
            }
        } catch (_: SQLiteException) {
            null
        }
        return when {
            mixed == null -> base.copy(semanticNote = "Поиск по смыслу сейчас недоступен.")
            mixed.isEmpty() -> base.copy(semanticNote = "По смыслу дополнительных выдержек пока нет.")
            else -> base.copy(indexed = mixed, semanticNote = "Выдача учитывает слова и смысл фрагментов.")
        }
    }

    private fun lookup(
        query: String, window: PublicationWindow? = null, source: WebFeedSource? = null, diversify: Boolean = true,
    ): Matches {
        val status = index.status()
        if (status.pages > 0) LemmaHolder.load { app.assets.open(LemmaHolder.ASSET) }
        val words = SearchWords(query, LemmaHolder.loaded)
        val indexed = if (status.pages == 0) emptyList() else index.find(words, CANDIDATE_LIMIT, window, source)
        return Matches(status, words, if (diversify) diverseMatches(indexed, SEARCH_LIMIT) else indexed)
    }

    private suspend fun answer(
        query: String, matches: Matches, updateNote: String? = null, window: PublicationWindow? = null,
    ): SearchPage {
        val sources = matches.indexed.mapNotNull { match ->
            val page = match.page
            val date = page.publishedAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DATE) }
            val title = if (date == null) page.title else "$date · ${page.title}"
            val semanticText = match.semanticSpan?.text(page.text)?.takeIf { match.missingWords.isNotEmpty() }
            val excerptPage = if (semanticText == null) page else page.copy(text = semanticText)
            SearchHit.from(title, page.url, bestExcerpt(excerptPage, matches.words))?.let { it to match }
        }
        val readable = sources.map { (_, match) ->
            val page = match.page
            val fragment = match.semanticSpan?.text(page.text)
            if (fragment != null && match.missingWords.isNotEmpty() && !asksForWholePage(query)) {
                page.copy(text = fragment)
            } else page
        }
        val answer = if (readable.isEmpty()) null else withTimeoutOrNull(75_000) {
            reader.answer(query, readable, matches.words)
        }
        val missingWords = sources.firstOrNull()?.second?.missingWords.orEmpty()
        val detail = buildString {
            append(updateNote ?: "${indexLine(matches.status)} Поиск выполнен по локальному индексу.")
            matches.semanticNote?.let { append(" "); append(it) }
            window?.let { append(" Период по датам публикаций в RSS и Atom — ${it.label}.") }
            if (missingWords.isNotEmpty()) {
                append(" Совпадение частичное. На первой странице не найдены слова «")
                append(missingWords.joinToString("», «"))
                append("».")
            }
            if (sources.isNotEmpty() && answer == null) {
                append(" Ответ модели недоступен. Ниже найдённые выдержки.")
            }
        }
        return SearchPage(sources.map { it.first }, answer = answer, detail = detail)
    }

    companion object {
        private const val HISTORY = "history"
        private const val LEARNED_SOURCES = "learned_sources"
        private const val CANDIDATE_LIMIT = 12

        /** Пределы открытия одной ссылки и чтения её страниц. Объявленные числа, как у поиска;
         * предел открытия виден юнит-тесту бюджетов прямого открытия (LinkOpenerTest). */
        internal const val OPEN_LIMIT_MS = 20_000L
        private const val REPOSITORY_LIMIT_MS = 60_000L
        private const val READ_LIMIT_MS = 75_000L
        private const val NEWS_SUMMARY_MS = 22_000L
        private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}

internal fun diverseMatches(matches: List<IndexedMatch>, limit: Int): List<IndexedMatch> {
    if (matches.size <= limit) return matches
    val selected = mutableListOf<IndexedMatch>()
    val counts = mutableMapOf<String, Int>()
    for (match in matches) {
        val host = runCatching { java.net.URI(match.page.url).host?.lowercase() }.getOrNull()
            ?: match.page.url
        if ((counts[host] ?: 0) >= 2) continue
        selected += match
        counts[host] = (counts[host] ?: 0) + 1
        if (selected.size == limit) break
    }
    if (selected.size < limit) {
        for (match in matches) {
            if (match in selected) continue
            selected += match
            if (selected.size == limit) break
        }
    }
    return selected
}

internal fun CrawlReport.toSearchPage(): SearchPage {
    val verifiedPages = loaded + unchanged
    val outcome = when {
        timedOut -> SearchStatus.TIMED_OUT
        verifiedPages > 0 -> SearchStatus.UPDATED
        attempted > 0 || (feedsAttempted > 0 && feedsChecked == 0) -> SearchStatus.CRAWL_FAILED
        status.pages == 0 -> SearchStatus.INDEX_EMPTY
        else -> SearchStatus.UPDATED
    }
    val detail = buildString {
        append(indexLine(status))
        when {
            verifiedPages > 0 -> append(" Загружено страниц: $loaded. Подтверждено без повторной загрузки: $unchanged.")
            timedOut -> Unit
            attempted > 0 -> append(" Источники не вернули доступных страниц.")
            else -> append(" Сейчас нет страниц, ожидающих обхода.")
        }
        if (feedsAttempted > 0) append(" Проверено лент: $feedsChecked из $feedsAttempted.")
        if (timedOut) append(" Достигнут лимит времени сетевого обхода.")
    }
    return SearchPage(emptyList(), status = outcome, detail = detail)
}

private fun indexLine(status: IndexStatus): String {
    val date = status.checkedAt?.let {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
    }
    return "В локальном индексе страниц: ${status.pages}." + if (date == null) "" else " Последняя проверка: $date."
}
