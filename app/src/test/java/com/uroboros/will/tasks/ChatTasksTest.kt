package com.uroboros.will.tasks

import com.uroboros.safety.SafetyZoneSource
import com.uroboros.safety.SafetyZone
import com.uroboros.search.DeferredSearchTelegram
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ChatTasksTest {
    private val plan = """[{"tool":"memory","goal":"Восстановить выбор"},{"tool":"write","goal":"Сравнить варианты"}]"""
    private val request = "Вспомни прошлый выбор и сравни варианты"
    private val store = object : ChatTasks.Storage {
        val data = ConcurrentHashMap<String, String>()
        override fun keys(): Set<String> = data.keys
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) { data[key] = value }
    }
    private val guard = object : SafetyZoneSource { override val zone = MutableStateFlow(SafetyZone.COMFORT) }
    private val recalls = AtomicInteger()
    private val sends = AtomicInteger()
    private val progress = java.util.concurrent.CopyOnWriteArrayList<String>()
    private fun tasks(scope: CoroutineScope, judge: suspend (String) -> String? = { "1" }, generate: suspend (String) -> String?) = ChatTasks(
        scope, guard, store, { true }, { true }, { _, _ -> recalls.incrementAndGet(); "Запись о выборе" },
        { _, _ -> error("Сеть закрыта") }, { _, text, _ ->
            if (text.startsWith("План\n")) {
                progress.add(text)
                true
            } else {
                sends.incrementAndGet()
                false
            }
        },
        judge, { _, material -> generate(material) },
    )
    private suspend fun until(test: () -> Boolean) = withTimeout(5000) { while (!test()) delay(5) }
    private fun state(key: String = "app") = store.read(key)?.let(::JSONObject)?.optString("state")

    @Test fun aReportOfAnEarlierMistakeIsNotANewAssignment() {
        assertFalse(ChatTaskPlan.accepts("Я уже сказал его имя, а ты вместо файла в своем репо Библию открыл. Передам в работу, пусть поправят) а то во время кодинга может выйти неловко)"))
    }

    @Test fun planMayHaveProseButMayNotContainTwoCompetingPlans() {
        assertNotNull(ChatTaskPlan.validate("Вот план.\n$plan\nГотово.", 0))
        assertNull(ChatTaskPlan.validate("$plan $plan", 0))
        assertNull(ChatTaskPlan.control("Я уже сказал его имя, а ты вместо файла открыл сайт", true, false))
    }

    @Test fun interruptedProcessResumesAtFirstUnconfirmedStep() = runBlocking {
        val first = CoroutineScope(Job())
        val executing = CompletableDeferred<Unit>()
        tasks(first) { if (it.contains("материалы")) { executing.complete(Unit); awaitCancellation() } else plan }
            .submit(request, "")
        withTimeout(5000) { executing.await() }
        first.coroutineContext.job.cancelAndJoin()
        tasks(this) { "Сравнение готово" }.recover()
        until { state() == "done" }
        assertEquals(1, recalls.get())
    }

    @Test fun watchdogCutDiscardsPartialTextAndRechecksCheckpoint() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val worker = tasks(this) {
            if (!it.contains("материалы")) plan else if (attempts.getAndIncrement() == 0) {
                entered.complete(Unit); release.await(); "Оборванный текст"
            } else "Проверенный итог"
        }
        worker.submit(request, "")
        withTimeout(5000) { entered.await() }
        worker.watchdogCut()
        assertEquals("paused", state())
        release.complete(Unit)
        until { worker.recover(); state() == "done" }
        assertEquals(1, recalls.get())
        assertEquals("Проверенный итог", worker.appStatus.value?.answer)
    }

    @Test fun shortJudgeCanRequestOneCorrection() = runBlocking {
        val verdicts = AtomicInteger()
        val drafts = AtomicInteger()
        val worker = tasks(this, { if (verdicts.incrementAndGet() == 1) "0" else "1" }) {
            if (it.contains("материалы")) "Черновик ${drafts.incrementAndGet()}" else plan
        }
        worker.submit(request, "")
        until { state() == "done" }
        assertEquals("Черновик 2", worker.appStatus.value?.answer)
        assertEquals(1, recalls.get())
        assertEquals(2, verdicts.get())
    }

    @Test fun missingOrAmbiguousVerdictLeavesDraftOpen() = runBlocking {
        val worker = tasks(this, { "2" }) { if (it.contains("материалы")) "Черновик" else plan }
        worker.submit(request, "")
        until { state() == "paused" }
        val row = JSONObject(store.read("app")!!)
        assertEquals("Черновик", row.getString("draft"))
        assertEquals(1, row.getJSONArray("results").length())
    }

    @Test fun restartDuringVerificationOnlyRepeatsVerification() = runBlocking {
        val first = CoroutineScope(Job())
        val verifying = CompletableDeferred<Unit>()
        tasks(first, { verifying.complete(Unit); awaitCancellation() }) {
            if (it.contains("материалы")) "Сохранённый ответ" else plan
        }.submit(request, "")
        withTimeout(5000) { verifying.await() }
        first.coroutineContext.job.cancelAndJoin()
        val next = tasks(this) { error("Черновик уже сохранён") }
        next.recover()
        until { state() == "done" }
        assertEquals("Сохранённый ответ", next.appStatus.value?.answer)
    }

    @Test fun warmPhoneWaitsBeforePlanningAndBeforeJudging() = runBlocking {
        val generated = AtomicInteger()
        val judged = AtomicInteger()
        guard.zone.value = SafetyZone.WARNING
        val worker = tasks(this, { judged.incrementAndGet(); "1" }) {
            generated.incrementAndGet()
            if (it.contains("материалы")) {
                guard.zone.value = SafetyZone.WARNING
                "Итог"
            } else plan
        }
        worker.submit(request, "")
        until { JSONObject(store.read("app")!!).optInt("attempts") == 1 }
        assertEquals(0, generated.get())
        guard.zone.value = SafetyZone.COMFORT
        until { JSONObject(store.read("app")!!).optString("draft") == "Итог" }
        assertEquals(0, judged.get())
        guard.zone.value = SafetyZone.COMFORT
        until { state() == "done" }
        assertEquals(2, generated.get())
        assertEquals(1, judged.get())
    }

    @Test fun comparisonResumesAfterCoolingWithoutRepeatingRecallOrSearch() = runBlocking {
        val file = listOf("src/main/assets", "app/src/main/assets").map {
            java.io.File(it, com.uroboros.memory.nav.LemmaHolder.ASSET)
        }.first { it.exists() }
        com.uroboros.memory.nav.LemmaHolder.load { file.inputStream() }
        val work = "Вспомни прошлый выбор, поищи в интернете Android и сравни варианты"
        val searches = AtomicInteger()
        val calls = AtomicInteger()
        fun worker(scope: CoroutineScope) = ChatTasks(scope, guard, store, { true }, { true },
            { _, _ -> recalls.incrementAndGet(); "Выбор вчера" },
            { query, _ ->
                assertEquals("Android", query.query)
                searches.incrementAndGet()
                guard.zone.value = SafetyZone.WARNING
                "Второй вариант из статьи\nhttps://example.org/source"
            }, { _, _, _ -> error("Поручение в приложении") }, { "1" }, { _, material ->
                calls.incrementAndGet()
                if (JSONObject(material).has("материалы")) {
                    assertTrue(material.contains("Выбор вчера") && material.contains("Второй вариант из статьи"))
                    "Сравнение двух вариантов"
                } else """[{"tool":"memory","goal":"Вспомнить выбор"},{"tool":"web","goal":"Найти Android"},{"tool":"write","goal":"Сравнить варианты"}]"""
            })
        val initial = CoroutineScope(Job())
        worker(initial).submit(work, "")
        until { JSONObject(store.read("app")!!).getJSONArray("results").length() == 2 }
        initial.coroutineContext.job.cancelAndJoin()
        guard.zone.value = SafetyZone.COMFORT
        val resumed = worker(this)
        resumed.recover()
        until { state() == "done" }
        assertEquals("Сравнение двух вариантов", resumed.appStatus.value?.answer)
        assertEquals(1, recalls.get())
        assertEquals(1, searches.get())
        assertEquals(2, calls.get())
    }

    @Test fun groupTopicsKeepIndependentPlansAndReplyAddresses() = runBlocking {
        val worker = tasks(this) { if (it.contains("материалы")) "Итог" else plan }
        for (thread in listOf(11L, 22L)) {
            worker.submit(request, "", DeferredSearchTelegram(7, -8, thread + 1, "grant", 42, thread))
        }
        until { sends.get() == 2 }
        for (thread in listOf(11L, 22L)) {
            val row = JSONObject(store.read("tg:7:-8/$thread")!!)
            assertEquals(42L, row.getLong("owner"))
            assertEquals(thread, row.getLong("thread"))
        }
    }

    @Test fun uncertainDeliveryIsNeverRepeatedOnStartup() = runBlocking {
        val tg = DeferredSearchTelegram(7, 8, 9, "grant")
        val worker = tasks(this) { if (it.contains("материалы")) "Итог" else plan }
        worker.submit(request, "", tg)
        until { sends.get() == 1 }
        val restored = tasks(this) { error("Повторная генерация") }
        restored.recover()
        assertTrue(restored.submit(request, "", tg).contains("не подтверждена"))
        assertEquals(1, sends.get())
        assertEquals(2, progress.size)
        assertTrue(progress.first().contains("→ Восстановить выбор"))
        assertTrue(progress.last().contains("✓ Восстановить выбор"))
        assertTrue(progress.last().contains("→ Сравнить варианты"))
    }

    @Test fun ordinaryRequestUsesDictionaryAndKeepsTwoDifferentSearchTopics() {
        val filename = com.uroboros.memory.nav.LemmaHolder.ASSET
        val file = listOf(java.io.File("src/main/assets", filename), java.io.File("app/src/main/assets", filename)).first { it.exists() }
        com.uroboros.memory.nav.LemmaHolder.load { file.inputStream() }
        assertTrue(ChatTaskPlan.accepts(request))
        val two = "Поищи в интернете Kotlin, поищи в интернете Android и сравни результаты"
        assertEquals(listOf("Kotlin", "Android"), ChatTaskPlan.webQueries(two))
        val direct = ChatTaskPlan.searchAndWrite(two, ChatTaskPlan.webQueries(two))!!
        assertEquals(listOf("web", "web", "write"), (0 until direct.length()).map { direct.getJSONObject(it).getString("tool") })
    }

    @Test fun validatesPlanAgainstAvailableToolsAndSize() {
        assertNotNull(ChatTaskPlan.validate(plan, 0))
        assertNull(ChatTaskPlan.validate(plan, 1))
        assertNull(ChatTaskPlan.validate(plan.replace("memory", "web"), 0))
        assertNull(ChatTaskPlan.validate(plan.replace("write", "send"), 0))
        assertNull(ChatTaskPlan.validate(JSONArray().toString(), 0))
    }

    @Test fun quotedRequestDoesNotStartTaskAndOfflineAppliesToWholeRequest() {
        assertFalse(ChatTaskPlan.accepts("Он сказал: «Вспомни и сравни»"))
        assertTrue(ChatTaskPlan.webQueries("Поищи в интернете данные и сравни, но без сети").isEmpty())
        assertFalse(ChatTaskPlan.accepts("Спасибо, очень интересно."))
    }

    @Test fun correctionsNeedRepeatedEvidenceAndIgnoreWatchdogCuts() {
        val history = JSONArray()
        TaskReview.record(history, "a", "memory", "missing")
        TaskReview.record(history, "a", "memory", "missing")
        TaskReview.record(history, "b", "watchdog", "cut")
        assertEquals("", TaskReview.hint(history))
        TaskReview.record(history, "c", "memory", "missing")
        assertTrue(TaskReview.hint(history).contains("2 из 2"))
        repeat(TaskReview.WINDOW) { TaskReview.record(history, "ok-$it", "memory", "ok") }
        assertEquals(TaskReview.WINDOW, history.length())
        assertEquals("", TaskReview.hint(history))
    }
}
