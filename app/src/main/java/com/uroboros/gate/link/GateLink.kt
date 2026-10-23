package com.uroboros.gate.link

import android.content.Context
import com.uroboros.gate.GateBotApi
import com.uroboros.gate.GateClock
import com.uroboros.gate.GateIntake
import com.uroboros.gate.GatePost
import com.uroboros.gate.GateTelegram
import com.uroboros.gate.store.GateDatabase
import com.uroboros.gate.store.GateKeystoreOpener
import com.uroboros.gate.store.GateKeystoreSigner
import com.uroboros.gate.store.GateStore
import com.uroboros.gate.store.GateTokenStore
import com.uroboros.memory.EmergencyStop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Канал врат через телеграм: опрос бота агента, приём форм, отправка.
 * Живёт в службе агента ([com.uroboros.AgentService]) — опрос идёт, только пока
 * она жива; своего будильника нет.
 *
 * ПОЧЕМУ БОТ ИЗ ПРОЦЕССА, А НЕ КЛИЕНТ TELEGRAM. Bot API — обычный HTTPS:
 * ни приложения Telegram, ни его библиотеки на телефоне агента для этого не
 * нужно. Тяжёлое приложение рядом вытеснило бы модель агента из памяти, а
 * здесь сеть берёт килобайты.
 *
 * ОПРОС ДЛИННЫЙ ([GateBotApi.POLL_SECONDS]): одна открытая связь, сервер
 * отвечает, когда пришло что-то, или по сроку. Сбой — пауза, растущая до
 * потолка ([GateBotApi.pauseMs]). Ответ 409 — тревога: этим токеном опрашивает
 * кто-то ещё (двойник с украденным токеном или прежний опрос после перезапуска
 * — отличить нельзя, строка говорит оба). 401 — токен не принят: опрос ждёт
 * другого токена.
 *
 * СДВИГ ОПРОСА — в настройках врат, а не в базе: потерянный сдвиг значит, что
 * телеграм пришлёт непрочитанное ещё раз, а повторы врата ловят по номерам в
 * базе. Сдвиг пишется после разбора обновления, а не до: упал разбор — то же
 * придёт снова, лишнее отсеется.
 *
 * ПРИНЯТОЕ — ТОЛЬКО СТРОКАМИ ([lines]), в памяти процесса: ни в память агента,
 * ни модели. Что делать с пришедшим, решается отдельно.
 *
 * АВАРИЙНЫЙ СТОП останавливает опрос и отправку: стоп только запрещает.
 *
 * ЧЕГО НЕ УМЕЕТ: служба убита — не опрашивает (телеграм держит
 * недоставленное сутки, остальное закрывает повтор отправителя); первое
 * письмо собеседнику — только по имени пользователя (`@имя`), по номеру не
 * пробовал; имя ↔ номер помнит только в памяти процесса ([names]).
 */
object GateLink {

    /** Объявленное число, не подобранное: строк на экране врат. */
    const val MAX_LINES = 50

    /** Как часто убирать базу врат ([GateStore.cleanup]). Объявленное число. */
    private const val CLEANUP_EVERY_MS = 6 * 60 * 60_000L

    private val _status = MutableStateFlow(listOf("Телеграм: не опрашиваю — служба агента не запущена"))

    /** Состояние канала: опрос, мой бот, мои ключи, база. */
    val status: StateFlow<List<String>> get() = _status

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Последние события: пришло, ушло, отказы, тревоги. Новые — сверху. */
    val lines: StateFlow<List<String>> get() = _lines

    /** Имя пользователя → номер, как их видел канал. Только память процесса. */
    private val names = ConcurrentHashMap<String, Long>()

    private class Session(
        val token: String,
        val http: TgHttp,
        val me: GateBotApi.Me,
        val store: GateStore,
        val post: GatePost,
        /** Строки о себе: бот, ключи, база — база считается заново при каждом показе. */
        val head: () -> List<String>,
    )

    @Volatile
    private var session: Session? = null

    private var job: Job? = null

    /** Запустить опрос в области службы [scope]; уже идёт — ничего. */
    @Synchronized
    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch(Dispatchers.IO) {
            try {
                loop(app)
            } finally {
                session = null
                _status.value = listOf("Телеграм: не опрашиваю — служба агента остановлена")
            }
        }
    }

    private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms))

    private fun day(ms: Long) = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(Date(ms))

    private fun note(vararg ls: String?) {
        val add = ls.filterNotNull().map { "${clock(System.currentTimeMillis())} $it" }
        if (add.isEmpty()) return
        synchronized(this) { _lines.value = (add.reversed() + _lines.value).take(MAX_LINES) }
    }

    private fun status(s: Session?, line: String) {
        val head = s?.let { runCatching { it.head() }.getOrElse { e -> listOf("Врата: база не читается — ${e.javaClass.simpleName}") } }
        _status.value = listOf(line) + (head ?: emptyList())
    }

    private suspend fun loop(app: Context) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var failures = 0
        var refusedToken: String? = null
        var cleanedAt = 0L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            if (EmergencyStop.isActive()) {
                status(session, "Телеграм: не опрашиваю — аварийный стоп")
                delay(10_000)
                continue
            }
            val token = try {
                GateTokenStore.read(app)
            } catch (e: Exception) {
                status(null, "Телеграм: не опрашиваю — токен не читается: ${e.javaClass.simpleName}")
                delay(60_000)
                continue
            }
            if (token == null) {
                session = null
                status(null, "Телеграм: не опрашиваю — нет токена (ввести — кнопка «Токен» вверху справа)")
                delay(5_000)
                continue
            }
            if (token == refusedToken) {
                status(null, "Телеграм: не опрашиваю — токен не принят телеграмом; введите другой")
                delay(5_000)
                continue
            }
            val held = session
            val s: Session = if (held != null && held.token == token) held else {
                val opened = try {
                    open(app, token, prefs)
                } catch (e: Refused401) {
                    refusedToken = token
                    note("Телеграм: токен не принят — ${e.message}")
                    continue
                } catch (e: Exception) {
                    failures++
                    status(null, "Телеграм: не опрашиваю — ${GateBotApi.scrub(e.message ?: e.javaClass.simpleName, token)}; " +
                        "снова через ${GateBotApi.pauseMs(failures) / 1000} с")
                    delay(GateBotApi.pauseMs(failures))
                    continue
                }
                session = opened
                cleanedAt = 0L
                opened
            }
            val now = System.currentTimeMillis()
            if (now - cleanedAt >= CLEANUP_EVERY_MS) {
                runCatching { s.store.cleanup(now) }
                cleanedAt = now
            }
            for (send in runCatching { s.post.due() }.getOrDefault(emptyList())) deliver(s, send)

            val offset = prefs.getLong(offsetKey(s.me.id), -1L).takeIf { it >= 0 }
            val reply = try {
                s.http.call(GateBotApi.updatesUrl(token, offset), readTimeoutMs = (GateBotApi.POLL_SECONDS + 20) * 1000)
            } catch (e: IOException) {
                failures++
                status(s, "Телеграм: нет связи — ${e.message}; снова через ${GateBotApi.pauseMs(failures) / 1000} с")
                delay(GateBotApi.pauseMs(failures))
                continue
            }
            when (reply) {
                is GateBotApi.Reply.Error -> {
                    failures++
                    when (reply.code) {
                        409 -> note("Телеграм: тревога — этим ботом опрашивает кто-то ещё (ответ 409; возможно, перезапуск)")
                        401 -> {
                            refusedToken = token
                            session = null
                            note("Телеграм: токен не принят — ${reply.description}")
                            continue
                        }
                        else -> note("Телеграм: ошибка ${reply.code} — ${GateBotApi.scrub(reply.description, token)}")
                    }
                    status(s, "Телеграм: опрос не удался (${reply.code}); снова через ${GateBotApi.pauseMs(failures) / 1000} с")
                    delay(GateBotApi.pauseMs(failures))
                }
                is GateBotApi.Reply.Ok -> {
                    failures = 0
                    status(s, "Телеграм: опрос идёт, последний ответ сервера ${clock(System.currentTimeMillis())}")
                    for (u in GateBotApi.updates(reply.result)) {
                        u.message?.let { handle(s, it) }
                        prefs.edit().putLong(offsetKey(s.me.id), u.id + 1).apply()
                    }
                }
            }
        }
    }

    private class Refused401(message: String) : Exception(message)

    /** Открыть канал по токену: кто я (`getMe`), база, ключи, почта. */
    private fun open(app: Context, token: String, prefs: android.content.SharedPreferences): Session {
        val http = TgHttp(token)
        val me = when (val r = http.call(GateBotApi.methodUrl(token, "getMe"))) {
            is GateBotApi.Reply.Error -> if (r.code == 401) throw Refused401(r.description) else throw IOException("getMe: ${r.code} ${r.description}")
            is GateBotApi.Reply.Ok -> GateBotApi.me(r.result) ?: throw IOException("getMe: ответ без номера")
        }
        val meKey = GateTelegram.userKey(me.id)
        val before = prefs.getLong(LAST_ME, 0L)
        if (before != 0L && before != me.id) {
            note("Телеграм: другой бот — был ${GateTelegram.userKey(before).id}, стал ${meKey.id}; для собеседников это новый агент")
        }
        prefs.edit().putLong(LAST_ME, me.id).apply()
        val store = try {
            GateStore(GateDatabase.getInstance(app), meKey).also { it.meterLine() }
        } catch (e: Exception) {
            throw IOException("Врата: база не открылась — ${e.javaClass.simpleName}: ${e.message}")
        }
        val signer = GateKeystoreSigner.openOrCreate()
        val opener = GateKeystoreOpener.openOrCreate(app)
        val intake = GateIntake(meKey, numbers = store.numbers, keys = store::signKeys, opener = opener)
        val post = GatePost(meKey, intake, store, signer, opener.publicKey, now = System::currentTimeMillis)
        val bot = "Телеграм: бот ${me.username?.let { "@$it" } ?: "без имени"} (${meKey.id})"
        val keys = listOf(signer.meterLine(::day), opener.meterLine(::day))
        val head = { listOf(bot) + keys + store.meterLine() }
        return Session(token, http, me, store, post, head)
    }

    private fun handle(s: Session, m: GateBotApi.Message) {
        val fromUsername = m.fromUsername
        if (m.fromId != null && fromUsername != null) names[fromUsername.lowercase(Locale.ROOT)] = m.fromId
        GateClock.selfCheck(m.dateSec * 1000, System.currentTimeMillis(), GateTelegram.SERVER)?.let { note(it) }
        val doc = m.doc
        var file: ByteArray? = null
        if (GateBotApi.needsFile(m) && doc != null) {
            file = try {
                fetch(s, doc.fileId)
            } catch (e: IOException) {
                note("Не пакет: файл не скачан — ${e.message}")
                return
            }
        } else if (doc != null && !GateTelegram.worthFetching(doc.size, KONVERT_LIMITS)) {
            note("Не пакет: файл ${doc.size} Б больше предела врат — не скачан")
            return
        }
        when (val inc = GateTelegram.incoming(m.toTg(file))) {
            is GateTelegram.Incoming.NotGate -> note(inc.words)
            is GateTelegram.Incoming.Gate -> {
                val got = try {
                    s.post.receive(inc)
                } catch (e: Exception) {
                    note("Врата: приём сорвался — ${e.javaClass.simpleName}: ${e.message}")
                    return
                }
                note(*got.lines.toTypedArray())
                got.reply?.let { deliver(s, it, chat = m.chatId.toString()) }
            }
        }
    }

    private fun fetch(s: Session, fileId: String): ByteArray {
        val path = when (val r = s.http.call(GateBotApi.getFileUrl(s.token, fileId))) {
            is GateBotApi.Reply.Error -> throw IOException("getFile: ${r.code} ${GateBotApi.scrub(r.description, s.token)}")
            is GateBotApi.Reply.Ok -> GateBotApi.filePath(r.result) ?: throw IOException("getFile: без пути")
        }
        return s.http.download(path, KONVERT_LIMITS.maxBytes)
    }

    private val KONVERT_LIMITS = GateIntake.Limits.DECLARED.let { it.copy(maxBytes = it.maxKonvertBytes) }

    /**
     * Отправить [send] в чат [chat] (по умолчанию — личка собеседника по его
     * номеру). Итог — строка и, если всё ушло, номер чата из ответа канала.
     */
    private fun deliver(s: Session, send: GatePost.Send, chat: String? = null): Long? {
        val to = chat ?: send.to?.let { GateTelegram.userId(it) }?.toString()
        if (to == null) {
            note("Не отправлено: не знаю номера ${send.to?.id ?: "собеседника"}")
            return null
        }
        var chatId: Long? = null
        for (form in send.forms) {
            val r = try {
                s.http.sendDocument(to, GateBotApi.fileNameOf(form), form)
            } catch (e: IOException) {
                note("Не отправлено ($to): ${e.message}")
                return null
            }
            when (r) {
                is GateBotApi.Reply.Error -> {
                    note("Не отправлено ($to): ${r.code} ${GateBotApi.scrub(r.description, s.token)}")
                    return null
                }
                is GateBotApi.Reply.Ok -> chatId = GateBotApi.sentChatId(r.result) ?: chatId
            }
        }
        val now = System.currentTimeMillis()
        val peer = send.to ?: chatId?.let { GateTelegram.userKey(it) }
        s.post.delivered(send, now, peer)
        note(*send.lines.toTypedArray())
        val what = send.forms.joinToString(", ") { GateBotApi.fileNameOf(it).removeSuffix(".txt") }
        note("Ушло в канал (${peer?.id ?: to}): $what — это ещё не «принял»")
        return chatId
    }

    // --- Действия владельца с экрана врат ---

    private fun ready(): Session? = session.takeIf { !EmergencyStop.isActive() }

    /** Познакомиться: моя карточка по имени пользователя [username]. Блокирует поток. */
    fun introduce(username: String) {
        val s = ready() ?: return note("Не отправлено: канал не готов (нет токена, нет службы или аварийный стоп)")
        val name = username.trim().removePrefix("@")
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' }) return note("Не отправлено: «$username» — не имя пользователя")
        val chatId = deliver(s, s.post.card(null), chat = "@$name") ?: return
        names[name.lowercase(Locale.ROOT)] = chatId
    }

    /** Пробный пакет слов [words] агенту [to] (`@имя`, номер или `tg:user:номер`). Блокирует поток. */
    fun sendWords(to: String, words: String) {
        val s = ready() ?: return note("Не отправлено: канал не готов (нет токена, нет службы или аварийный стоп)")
        if (words.isBlank()) return note("Не отправлено: слова пустые")
        val t = to.trim()
        val id = when {
            t.startsWith("@") -> names[t.removePrefix("@").lowercase(Locale.ROOT)]
                ?: return note("Не отправлено: номер $t не известен — сначала «Познакомиться»")
            t.startsWith("tg:user:") -> t.removePrefix("tg:user:").toLongOrNull()
            else -> t.toLongOrNull()
        } ?: return note("Не отправлено: «$to» — не @имя и не номер")
        deliver(s, s.post.words(GateTelegram.userKey(id), words.trim()))
    }

    private const val PREFS = "uroboros_gate_link"
    private const val LAST_ME = "last_me"

    private fun offsetKey(meId: Long) = "offset_$meId"
}
