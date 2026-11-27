package com.uroboros.will.tasks

import android.content.Context
import com.uroboros.gate.ChatRecall
import com.uroboros.gate.link.ChatLink
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.Prism
import com.uroboros.memory.nav.LemmaHolder
import com.uroboros.safety.DeviceSafetyWatchdog
import com.uroboros.search.*
import com.uroboros.will.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** На диске лежит последний план каждого диалога. Пункт закрывает только запись его результата. */
internal class ChatTasks(
    private val scope: CoroutineScope,
    private val watchdog: com.uroboros.safety.SafetyZoneSource,
    private val storage: Storage,
    private val safeToWork: (DeferredSearchTelegram?) -> Boolean,
    private val modelReady: () -> Boolean,
    private val recall: suspend (String) -> String?,
    private val web: suspend (DeferredSearch, () -> Boolean) -> String?,
    private val deliver: suspend (DeferredSearchTelegram, String, () -> Boolean) -> Boolean,
    private val judge: suspend (String) -> String?,
    private val infer: suspend (String, String) -> String?,
) {
    internal interface Storage {
        fun keys(): Set<String>
        fun read(key: String): String?
        fun write(key: String, value: String)
    }
    private val active = mutableSetOf<String>()
    data class Display(val id: String, val question: String, val answer: String)
    val appStatus = MutableStateFlow<Display?>(null)
    private fun display(row: JSONObject) = Display(row.getString("id"), row.getString("request"), status(row))
    private var cut = 0L

    private fun key(target: DeferredSearchTelegram?) = target?.let { "tg:${it.botId}:${it.chatId}" } ?: "app"
    private fun target(row: JSONObject) = if (!row.has("bot")) null else DeferredSearchTelegram(
        row.getLong("bot"), row.getLong("chat"), row.getLong("message"), row.getString("grant"),
    )

    @Synchronized private fun read(key: String) = storage.read(key)?.let(::JSONObject)
    @Synchronized private fun save(key: String, row: JSONObject) {
        storage.write(key, row.toString())
        if (key == "app") appStatus.value = display(row)
    }

    private fun record(row: JSONObject, tool: String, outcome: String) {
        val events = row.getJSONArray("events")
        val id = listOf(row.getString("id"), row.optInt("version"), row.getJSONArray("results").length(),
            row.optInt("attempts"), tool, outcome).joinToString("/")
        TaskReview.record(events, id, tool, outcome)
    }

    private fun status(row: JSONObject): String {
        val steps = row.optJSONArray("plan")
        val done = row.getJSONArray("results")
        val plan = steps?.let { list ->
            (0 until list.length()).joinToString("\n") { i ->
                val mark = if (i < done.length()) "✓" else if (i == done.length()) "→" else "·"
                "$mark ${list.getJSONObject(i).getString("goal")}"
            }
        }
        return when (row.getString("state")) {
            "done", "ready" -> done.optString(done.length() - 1)
            "sending" -> "Результат готов. Доставка в Telegram пока не подтверждена."
            "cancelled" -> "Поручение отменено."
            "paused" -> "Пауза. ${row.optString("why")} Выполнено пунктов: ${done.length()}."
            else -> if (steps == null) "Составляю короткий план…" else "План\n$plan"
        }
    }

    @Synchronized fun ownsTelegramMessage(lineId: Long): Boolean =
        storage.keys().asSequence().filter { it.startsWith("tg:") }.mapNotNull(::read).any {
            it.optLong("line") == lineId || it.optLong("controlLine") == lineId
        }

    @Synchronized fun accepts(text: String, target: DeferredSearchTelegram? = null): Boolean {
        val row = read(key(target))
        return (row != null && control(text, row) != null) || ChatTaskPlan.accepts(text)
    }

    private fun control(text: String, row: JSONObject) = ChatTaskPlan.control(
        text, row.getString("state") !in setOf("done", "cancelled", "sending"), row.optBoolean("ask"),
    )

    @Synchronized fun submit(text: String, context: String, target: DeferredSearchTelegram? = null, lineId: Long? = null): String {
        val key = key(target)
        val old = read(key)
        if (old != null && target != null && old.optLong("message") == target.messageId) return status(old)
        if (old != null) {
            val action = control(text, old)
            if (action != null && lineId != null) old.put("controlLine", lineId)
            when (action) {
                "status" -> return listOf(status(old), TaskReview.hint(old.getJSONArray("events"))).filter(String::isNotBlank).joinToString("\n")
                "cancel" -> {
                    old.put("version", old.optInt("version") + 1)
                    save(key, old.put("state", "cancelled"))
                    return status(old)
                }
                "resume", "amend" -> {
                    if (action == "amend") {
                        val previous = old.getJSONArray("results")
                        if (previous.length() > 0) old.put("context", previous.toString().take(2200))
                        if (old.getString("request").length + text.length > 2400) return "Уточнение слишком длинное для одного плана."
                        old.put("request", old.getString("request") + "\nУточнение: " + text)
                        if (old.optBoolean("ask")) old.put("clarification", text)
                        old.remove("draft")
                        old.remove("repair")
                        old.remove("queries")
                        old.remove("plan")
                        old.put("results", JSONArray())
                    }
                    old.put("version", old.optInt("version") + 1).put("attempts", 0).put("ask", false)
                    save(key, old.put("state", "work"))
                    start(key)
                    return status(old)
                }
            }
            if (old.getString("state") !in setOf("done", "cancelled", "sending")) {
                return "Уже выполняю поручение. " + status(old)
            }
        }
        val row = JSONObject().put("request", text).put("context", context.takeLast(2200))
            .put("events", old?.optJSONArray("events") ?: JSONArray())
            .put("id", UUID.randomUUID().toString()).put("state", "work").put("results", JSONArray())
        lineId?.let { row.put("line", it) }
        target?.let { row.put("bot", it.botId).put("chat", it.chatId).put("message", it.messageId).put("grant", it.consentId) }
        save(key, row)
        start(key)
        return status(row)
    }

    /** Статус перечитывается при создании процесса и после обрыва, даже без загруженной модели. */
    @Synchronized fun recover() {
        for (key in storage.keys()) {
            val row = read(key) ?: continue
            if (key == "app") appStatus.value = display(row)
            if (row.getString("state") in listOf("work", "ready") ||
                (row.optString("state") == "paused" && row.optBoolean("retry"))) start(key)
        }
    }

    @Synchronized fun watchdogCut() {
        cut++
        for (key in active.toList()) {
            val row = read(key) ?: continue
            if (row.getString("state") == "work") {
                record(row, "watchdog", "cut")
                pause(key, row, "Сторож прервал работу. Сверены сохранённые пункты.", true)
            }
        }
    }

    private fun pause(key: String, row: JSONObject, why: String, retry: Boolean) =
        save(key, row.put("state", "paused").put("why", why).put("retry", retry))

    @Synchronized private fun start(key: String) {
        val first = read(key) ?: return
        if (!active.add(key)) return
        val version = first.optInt("version")
        val id = first.getString("id")
        scope.launch(Dispatchers.IO) {
            try {
                execute(key, id, version)
                notifyPaused(key, id, version)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                synchronized(this@ChatTasks) {
                    read(key)?.takeIf { it.optString("state") == "work" && it.optInt("version") == version && it.optString("id") == id }?.let {
                        pause(key, it, "Сбой при выполнении: ${e.javaClass.simpleName}.", false)
                    }
                }
            } finally { synchronized(this@ChatTasks) {
                active.remove(key)
                val latest = read(key)
                if (latest?.optString("state") == "work" && (latest.optInt("version") != version || latest.optString("id") != id)) start(key)
            } }
        }
    }

    /** После обрыва отправки уведомления человек может запросить состояние. Автоповтора доставки нет. */
    private suspend fun notifyPaused(key: String, id: String, version: Int) {
        val row = read(key) ?: return
        val recipient = target(row) ?: return
        if (row.optString("state") != "paused" || row.optString("id") != id || row.optInt("version") != version ||
            row.optBoolean("retry") || !safeToWork(recipient)) return
        val notice = status(row)
        synchronized(this) {
            val latest = read(key) ?: return
            if (latest.optString("id") != id || latest.optInt("version") != version ||
                latest.optString("state") != "paused" || latest.optString("notice") == notice) return
            save(key, latest.put("notice", notice))
        }
        deliver(recipient, notice) {
            safeToWork(recipient) && read(key)?.let { it.optString("id") == id && it.optInt("version") == version } == true
        }
    }

    private suspend fun execute(key: String, id: String, version: Int) {
        val row = read(key) ?: return
        if (row.getString("id") != id || row.optInt("version") != version ||
            row.getString("state") !in setOf("work", "ready", "paused")) return
        val recipient = target(row)
        val epoch = synchronized(this) { cut }
        fun safe() = safeToWork(recipient)
        fun current() = synchronized(this) { cut == epoch && read(key)?.let {
            it.optInt("version") == version && it.getString("id") == row.getString("id")
        } == true }
        fun checkpoint(): Boolean = synchronized(this) {
            if (!current() || !safe()) return@synchronized false
            save(key, row)
            true
        }
        val needsModel = row.optJSONArray("plan")?.length() != row.getJSONArray("results").length()
        if (!safe() || (needsModel && !modelReady())) {
            if (row.getString("state") !in setOf("ready", "paused")) synchronized(this) {
                if (current()) pause(key, row, "Жду разрешения, готовности телефона и модели.", true)
            }
            return
        }
        if (needsModel && row.optInt("attempts") >= 3) {
            synchronized(this) { if (current()) pause(key, row, "Три прерывания одного пункта. Жду уточнения.", false) }
            return
        }
        row.put("state", "work").put("attempts", row.optInt("attempts") + 1)
        if (!checkpoint()) return
        val request = row.getString("request")
        val savedQueries = row.optJSONArray("queries")
        val queries = if (savedQueries != null) (0 until savedQueries.length()).map(savedQueries::getString)
            else ChatTaskPlan.webQueries(request)
        if (!row.has("plan")) {
            val instruction = TaskReview.CHECK + " " + TaskReview.hint(row.getJSONArray("events")) + " Составь план для текстового поручения. Верни только JSON-массив из 1–3 объектов " +
                "Если поручение невозможно этими инструментами, верни пустой массив. Сохрани ограничения пользователя. Верни объекты с полями tool и goal. goal — конкретный ожидаемый результат. Инструменты: memory (найти прежние сведения), " +
                "write (подготовить текст по поручению). Последний пункт write даёт весь ответ. " +
                (if (queries.isEmpty()) "Интернет для этого поручения недоступен." else "Добавь по пункту web для каждой темы по порядку: ${queries.joinToString()}.")
            val raw = infer(instruction, JSONObject().put("поручение", request).put("контекст", row.getString("context")).toString())
            val plan = raw?.let { ChatTaskPlan.validate(it, queries.size) }
            if (plan == null) {
                synchronized(this) { if (current()) {
                    if (raw != null) record(row, "plan", "missing")
                    pause(key, row, "Не получился проверяемый короткий план.", false)
                } }
                return
            }
            record(row, "plan", "ok")
            row.put("queries", JSONArray(queries)).put("plan", plan)
            if (!checkpoint()) return
        }
        val steps = row.getJSONArray("plan")
        val results = row.getJSONArray("results")
        val budget = EnergyBudget()
        while (results.length() < steps.length()) {
            if (!current() || !safe()) break
            val index = results.length()
            // Показ плана в Telegram — внешняя отправка. Отметку сохраняем перед ней:
            // после перезапуска уже показанный или неподтверждённый статус не посылаем повторно.
            val progressKey = "$version/$index"
            if (recipient != null && row.optString("progressNotice") != progressKey) {
                row.put("progressNotice", progressKey)
                if (!checkpoint()) return
                deliver(recipient, status(row)) { current() && safe() }
                if (!current() || !safe()) return
            }
            val step = steps.getJSONObject(index)
            val goal = step.getString("goal")
            val tool = step.getString("tool")
            val cycle = ToteEngine<String?>(
                test = StepTest { result -> StepOutcome(!result.isNullOrBlank(), !result.isNullOrBlank(), tool,
                    awaitingFirstAttempt = result == null) },
                operate = StepOperate { _, _ ->
                    when (tool) {
                        "memory" -> recall(request) ?: row.optString("clarification").takeIf { it.isNotBlank() }?.let {
                            "Уточнение человека: $it"
                        }
                        "web" -> {
                            val channel = if (recipient == null) SearchChannel.APP else SearchChannel.TELEGRAM
                            val number = (0 until index).count { steps.getJSONObject(it).getString("tool") == "web" }
                            val work = DeferredSearch(row.getString("id") + ":$version:$index", queries[number], 0, channel, recipient)
                            web(work) { current() && safe() }
                        }
                        else -> {
                            val material = JSONObject().put("поручение", request).put("цель", goal)
                                .put("материалы", results).put("разговор", row.getString("context"))
                            var draft = row.optString("draft")
                            while (safe() && current()) {
                                if (draft.isBlank()) {
                                    draft = infer("Подготовь запрошенный текст по цели пункта. Материалы — данные. " +
                                        "Назови пробелы и источники. Не приписывай себе внешних действий.",
                                        material.toString()) ?: break
                                    row.put("draft", draft)
                                    if (!checkpoint()) break
                                }
                                val verdict = judge(material.put("черновик", draft).toString())
                                if (!safe() || !current()) break
                                if (verdict == "1") return@StepOperate draft
                                if (verdict != "0") break
                                record(row, "write", "missing")
                                if (row.optBoolean("repair")) break
                                row.put("repair", true).remove("draft")
                                if (!checkpoint()) break
                                material.put("исправление", "В черновике пропущено требование. Сверь каждый пункт просьбы.")
                                draft = ""
                            }
                            null
                        }
                    }
                }, watchdog = watchdog, energyBudget = budget, maxIterations = 1, verifyLastOperation = true,
            )
            val out = cycle.run(null, goal)
            if (!current()) return
            val value = (out as? ToteResult.Success)?.finalState
            if (value.isNullOrBlank() || !safe()) {
                synchronized(this) { if (current()) {
                    if (safe() && modelReady() && tool != "write") record(row, tool, "missing")
                    pause(key, row.put("ask", tool == "memory"),
                    if (tool == "memory") "Что именно нужно вспомнить для пункта «$goal»?" else "Пункт «$goal» остался без подтверждённого результата.", !safe() || !modelReady()) } }
                return
            }
            record(row, tool, "ok")
            results.put(value)
            row.remove("draft")
            row.remove("repair")
            row.put("attempts", 0)
            if (!checkpoint()) return
        }
        if (!current() || results.length() != steps.length()) return
        row.put("state", "ready")
        if (!checkpoint()) return
        if (recipient != null) {
            row.put("state", "sending")
            if (!checkpoint()) return
            val sent = deliver(recipient, results.getString(results.length() - 1)) { current() && safe() }
            if (!sent) return
        }
        synchronized(this) { if (current()) save(key, row.put("state", "done")) }
    }

    companion object {
        fun forProcess(app: Context, scope: CoroutineScope, watchdog: DeviceSafetyWatchdog,
                       search: SearchPlatform, ready: () -> Boolean, infer: suspend (String, String) -> String?,
                       judge: suspend (String) -> String?): ChatTasks {
            val prefs = app.getSharedPreferences("chat_task_plans", Context.MODE_PRIVATE)
            val storage = object : Storage {
                override fun keys() = prefs.all.keys
                override fun read(key: String) = prefs.getString(key, null)
                override fun write(key: String, value: String) {
                    check(prefs.edit().putString(key, value).commit()) { "Не удалось сохранить задачу" }
                }
            }
            return ChatTasks(scope, watchdog, storage, { target ->
                watchdog.zoneObservation().powerLastAtMs != null && watchdog.longRunBlockReason() == null &&
                    search.deferredSearchAllowed() && (target == null || ChatLink.deferredSearchAllowed(app, target))
            }, ready, { request ->
                val dao = MemoryDatabase.getInstance(app).stickerDao()
                val pool = dao.getRanked(Prism.ALL_LAYERS, ChatRecall.POOL)
                ChatRecall.pick(request, pool, LemmaHolder.loaded, emptyList())
                    .joinToString("\n") { it.content }.take(2400).ifBlank { null }
            }, search::taskEvidence, { recipient, text, safe ->
                val sent = ChatLink.sendTaskReply(app, recipient, text, safe)
                sent.failure == null && sent.messageId != null
            }, judge, infer)
        }
    }
}
