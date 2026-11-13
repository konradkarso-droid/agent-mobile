package com.uroboros.move

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.uroboros.AgentService
import com.uroboros.ProcessObjects
import com.uroboros.gate.link.GateLink
import com.uroboros.memory.EmergencyStop
import com.uroboros.memory.StopCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Переезд на телефоне: сеть, блокировки сна и Wi‑Fi, строки хода для экрана
 * врат. Что и в каком порядке — у [Move]; разговор телефонов — [MoveSession].
 *
 * Живёт в области процесса, а не экрана: экран врат можно закрыть, передача
 * идёт дальше, пока жива служба агента (она держит процесс).
 *
 * ЧЕГО НЕ УМЕЕТ: перезапуск нового телефона после приёма — просьба к системе
 * открыть агента заново и выход процесса; если система окна не откроет,
 * принятое применится при следующем запуске агента (строка говорит об этом);
 * адреса показывает только IPv4 частных сетей.
 */
object MoveRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var socket: Socket? = null

    private val _view = MutableStateFlow<List<String>>(emptyList())

    /** Строки хода для экрана врат: главная сверху, события ниже (новые сверху). */
    val view: StateFlow<List<String>> get() = _view

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> get() = _running

    private var head: String? = null
    private var progress: String? = null
    private val events = ArrayDeque<String>()
    private var progressAt = 0L

    /** Причина стопа на время передачи — по ней стоп и снимается, только свой. */
    private const val HOLD_NOTE = "Идёт переезд агента на другой телефон."

    @Synchronized
    private fun render() {
        _view.value = listOfNotNull(head, progress) + events
    }

    @Synchronized
    private fun note(line: String) {
        events.addFirst("${SimpleDateFormat("HH:mm", Locale.ROOT).format(Date())} $line")
        while (events.size > 20) events.removeLast()
        render()
    }

    private fun head(line: String?) {
        head = line
        progress = null
        render()
    }

    private fun progress(p: MoveProgress) {
        val now = System.currentTimeMillis()
        if (now - progressAt < 500 && p.done < p.total) return
        progressAt = now
        val pct = if (p.total > 0) p.done * 100 / p.total else 100
        progress = "${MoveCheck.size(p.done)} из ${MoveCheck.size(p.total)} ($pct%) — ${p.name}"
        render()
    }

    @Synchronized
    fun receive(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch { runReceive(app) }
    }

    @Synchronized
    fun send(context: Context, address: String, code: String) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch { runSend(app, address, code) }
    }

    fun cancel() {
        job?.cancel()
        runCatching { server?.close() }
        runCatching { socket?.close() }
    }

    // --- Новый телефон ---

    private suspend fun runReceive(app: Context) {
        _running.value = true
        val locks = Locks(app)
        try {
            head("Приём: проверяю этот телефон")
            val rows = MoveAndroid.memoryRows(app)
            if (rows > 0) return note("Приём не открыт: здесь уже есть память ($rows строк) — принимать агента можно только в чистую установку")
            if (MoveAndroid.tokenHere(app)) return note("Приём не открыт: здесь уже введён токен бота — сотри его («Токен» → «Стереть»): токен приедет со старого")
            val addresses = localAddresses()
            if (addresses.isEmpty()) return note("Приём не открыт: телефон не в сети Wi‑Fi — подключись к домашнему Wi‑Fi или к точке доступа старого телефона")
            MoveState.holdFor("идёт приём агента с другого телефона")
            locks.acquire()
            val code = MoveCode.newCode()
            val srv = ServerSocket().apply {
                reuseAddress = true
                soTimeout = Move.ACCEPT_WAIT_MS.toInt()
                bind(InetSocketAddress(Move.PORT))
            }
            server = srv
            head("Приём открыт. На старом телефоне: врата → «Переехать»; адрес ${addresses.joinToString(" или ")}, код ${MoveCode.format(code)}")
            var tries = 0
            while (true) {
                val sock = try {
                    srv.accept()
                } catch (e: SocketTimeoutException) {
                    note("Приём закрыт: за ${Move.ACCEPT_WAIT_MS / 60_000} мин никто не подключился")
                    break
                }
                socket = sock
                sock.soTimeout = Move.READ_TIMEOUT_MS
                note("Приём: подключился ${sock.inetAddress.hostAddress}")
                val outcome = sock.use { MoveSession.receive(it.getInputStream(), it.getOutputStream(), code, MoveAndroid.Sink(app)) { p -> progress(p) } }
                progress = null
                note(outcome.line)
                when (outcome) {
                    is MoveOutcome.Done -> {
                        head("Агент принят. Перезапускаю — если экран не откроется сам, открой агента: принятое применится при запуске")
                        delay(1500)
                        restart(app)
                        return
                    }
                    is MoveOutcome.WrongCode -> if (++tries >= Move.MAX_TRIES) {
                        note("Приём закрыт: $tries неверных кода — открой приём заново, код будет новый")
                        break
                    }
                    // Сорвалось — ждём повтора с тем же кодом: старый может переехать снова.
                    is MoveOutcome.Failed -> Unit
                }
            }
        } catch (e: CancellationException) {
            note("Приём прерван")
            throw e
        } catch (e: Exception) {
            note("Приём сорвался: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } finally {
            runCatching { server?.close() }
            server = null
            socket = null
            locks.release()
            MoveState.holdFor(null)
            head(null)
            _running.value = false
        }
    }

    private fun restart(app: Context) {
        runCatching {
            app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                app.startActivity(it)
            }
        }
        exitProcess(0)
    }

    /**
     * IPv4 частных сетей этого телефона: Wi‑Fi или своя точка доступа. Сотовая
     * сеть и туннели отсеяны по именам — у операторов бывают «частные» адреса
     * 10.x, и в подсказке они только сбивали бы.
     */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && NOT_LOCAL.none { p -> it.name.startsWith(p) } }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { a -> a.hostAddress?.let { MoveCode.parseAddress(it) } }
            .distinct()
    }.getOrDefault(emptyList())

    // --- Старый телефон ---

    private fun ownStop(): Boolean = (EmergencyStop.lastCause() as? StopCause.ByUser)?.note == HOLD_NOTE

    private suspend fun runSend(app: Context, address: String, code: String) {
        _running.value = true
        val locks = Locks(app)
        val moved = MoveState.movedSession(app)
        try {
            head("Переезд на $address")
            if (moved == null && EmergencyStop.isActive()) return note("Переезд не начат: взведён аварийный стоп — сними его на главном экране, потом переезжай")
            if (AgentService.state.value is AgentService.RunState.Running) return note("Переезд не начат: идёт разбор памяти — дождись его конца")
            MoveState.holdFor("идёт переезд агента на другой телефон")
            if (moved == null) EmergencyStop.triggerManual(HOLD_NOTE)
            locks.acquire()
            note("Переезд: жду, пока остановится телеграм")
            if (withTimeoutOrNull(GATE_WAIT_MS) { GateLink.idle.first { it } } == null) {
                return note("Переезд не начат: опрос телеграма не остановился за ${GATE_WAIT_MS / 1000} с")
            }
            note("Переезд: снимаю базы (разговор и ответы в телеграме ждут)")
            val source = ProcessObjects.get(app).turns.aside { MoveAndroid.source(app, moved) }
            val m = source.manifest
            val models = m.entries.count { it.kind == MoveManifest.Kind.MODEL }
            note(
                "Переезд: пакет — баз ${m.dbs.size}, строк ${m.counts.values.sum()}, моделей $models, всего ${MoveCheck.size(m.totalBytes)}" +
                    (if (models == 0) " (папка моделей не найдена — модель перенесёшь сам)" else "") + "; подключаюсь",
            )
            val sock = Socket()
            socket = sock
            val outcome = sock.use {
                it.connect(InetSocketAddress(address, Move.PORT), Move.CONNECT_TIMEOUT_MS)
                it.soTimeout = Move.READ_TIMEOUT_MS
                MoveSession.send(it.getInputStream(), it.getOutputStream(), code, source) { p -> progress(p) }
            }
            progress = null
            note(outcome.line)
        } catch (e: CancellationException) {
            note("Переезд прерван")
            throw e
        } catch (e: Exception) {
            note("Переезд сорвался: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } finally {
            runCatching { socket?.close() }
            socket = null
            runCatching { MoveAndroid.dropSource(app) }
            locks.release()
            MoveState.holdFor(null)
            val movedAt = MoveState.movedAt(app)
            if (movedAt != null) {
                // Замер насовсем: стоп с причиной переезда — его видно в красной полосе.
                if (ownStop() || !EmergencyStop.isActive()) {
                    EmergencyStop.clear()
                    EmergencyStop.triggerManual(MoveState.stopNote(movedAt))
                }
                head("Агент переехал. Здесь — копия на момент переезда; сотри её, когда новый телефон себя покажет")
            } else {
                if (ownStop()) EmergencyStop.clear()
                head(null)
            }
            _running.value = false
        }
    }

    /** Сотовые и туннельные интерфейсы Android: переезд по ним не идёт. */
    private val NOT_LOCAL = listOf("rmnet", "rev_rmnet", "ccmni", "pdp", "v4-", "clat", "tun", "ppp", "dummy", "ip6tnl", "sit", "ims")

    /** Ожидание остановки опроса: длинный опрос телеграма — до 50 с, плюс запас. */
    private const val GATE_WAIT_MS = 75_000L

    /** Процессор и Wi‑Fi не засыпают, пока идёт передача. */
    private class Locks(private val app: Context) {
        private var wake: PowerManager.WakeLock? = null
        private var wifi: WifiManager.WifiLock? = null

        fun acquire() {
            wake = runCatching {
                (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uroboros:move").apply { acquire(2 * 60 * 60_000L) }
            }.getOrNull()
            wifi = runCatching {
                @Suppress("DEPRECATION")
                (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "uroboros:move").apply { acquire() }
            }.getOrNull()
        }

        fun release() {
            runCatching { wake?.takeIf { it.isHeld }?.release() }
            runCatching { wifi?.takeIf { it.isHeld }?.release() }
        }
    }
}
