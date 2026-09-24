package com.uroboros.initiative

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Когда агент заговаривает первым. Чистое решение: ни базы, ни Android — затем
 * и вынесено, чтобы правила закрепить тестами. Входы собирает тело агента
 * (AgentService) раз в минуту бодрствования.
 *
 * УСЛОВИЯ ОБЩИЕ, ЧТО СКАЗАТЬ — ОТ ИСТОЧНИКА. Здесь только то, что верно для
 * любого повода заговорить: безопасность устройства, занятость модели, лента,
 * молчание владельца, не больше одного сообщения без ответа. Есть ли что
 * сказать, решает источник ([InitiativeSource]); сюда приходит только его
 * отказ словами.
 *
 * НЕ БОЛЬШЕ ОДНОГО БЕЗ ОТВЕТА, ни от какого источника. Не ответил владелец —
 * агент молчит, без напоминаний и повторов. Ответом считается любая реплика
 * владельца, ушедшая в движок после сообщения агента.
 *
 * МОЛЧАНИЕ — ОТ ПОСЛЕДНЕЙ РЕПЛИКИ ВЛАДЕЛЬЦА, а не от последнего обращения к
 * модели: суд и сон — не разговор, и ночной разбор не должен делать владельца
 * «только что говорившим».
 *
 * ВРЕМЯ СУТОК НЕ ПРОВЕРЯЕТСЯ: у владельца свой график. Ночь решает режим
 * телефона «Не беспокоить».
 *
 * ЗАРЯДКА НЕ ТРЕБУЕТСЯ, в отличие от суда: сообщение — один ход, а не часы
 * работы. Нижняя граница заряда — у сторожа (longRunBlockReason), число здесь
 * не повторяется.
 *
 * Порядок отказов — как у SelfJudgeDecision.refusal: первое невыполненное
 * условие и называется.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - владелец, который читает экран, но не пишет, для него молчит;
 *  - сообщение без ответа ждёт ответа вечно: напоминаний нет намеренно;
 *  - до первой реплики владельца, записанной на диск, молчание считать не с
 *    чего, и агент первым не пишет (см. llm.ConversationTimes).
 */
object InitiativeDecision {

    /**
     * Сколько владелец должен молчать, чтобы агент заговорил первым.
     * Объявленное число, не подобранное.
     */
    const val SILENCE_MS = 60L * 60 * 1000

    /**
     * Строка модели о молчании. Живёт рядом с [SILENCE_MS] и меняется вместе с
     * ним: «больше часа» — это оно словами. «Пользователь», а не «владелец» —
     * так модель уже зовут собеседника (см. dream.CuriosityAsk.line).
     */
    const val SILENCE_WORDS = "Пользователь молчит больше часа."

    data class Inputs(
        /** Идёт разбор памяти или сон. */
        val running: Boolean,
        val emergencyStop: Boolean,
        /** Прислал ли сторож хоть одно показание батареи. */
        val powerKnown: Boolean,
        /** Зона сторожа — «норма». */
        val zoneNormal: Boolean,
        /** Отказ сторожа словами (DeviceSafetyWatchdog.longRunBlockReason); null — можно. */
        val watchdogRefusal: String?,
        val engineBusy: Boolean,
        /** Лента в памяти пуста, а на диске лежит сохранённый разговор. */
        val journalNotRaised: Boolean,
        /** Настройка «После комы продолжать разговор сам». */
        val autoContinue: Boolean,
        /** Почему не прочитались времена с диска; null — прочитались. */
        val timesUnreadable: String?,
        /** Последняя реплика владельца; null — ни одной на диске. */
        val ownerReplyAt: Long?,
        /** Последнее сообщение агента, написанное первым; null — не было. */
        val lastInitiativeAt: Long?,
        val now: Long,
        /** Отказ источника словами; null — источнику есть что сказать. */
        val sourceRefusal: String?,
        /** Выбиралась ли модель хоть раз — иначе загружать нечего. */
        val modelChosen: Boolean,
    )

    /** Почему агент сейчас не пишет первым, словами; null — условия выполнены. */
    fun refusal(i: Inputs): String? {
        val silentMs = i.ownerReplyAt?.let { i.now - it }
        return when {
            i.running -> "идёт разбор памяти или сон"
            i.emergencyStop -> "взведён аварийный стоп"
            !i.powerKnown -> "сторож ещё не прислал показаний батареи"
            !i.zoneNormal -> "зона не «норма»"
            i.watchdogRefusal != null -> i.watchdogRefusal
            i.engineBusy -> "модель занята"
            // При включённой настройке лента поднимется сама после загрузки
            // модели; не поднимется — тело назовёт почему.
            i.journalNotRaised && !i.autoContinue -> "разговор с диска не поднят — решает владелец"
            i.timesUnreadable != null -> "не прочиталось, когда писал владелец — ${i.timesUnreadable}"
            silentMs == null -> "владелец ещё не писал — молчание не с чего считать"
            silentMs < SILENCE_MS -> "владелец молчит ${minutes(silentMs)} мин из ${minutes(SILENCE_MS)}"
            awaiting(i.lastInitiativeAt, i.ownerReplyAt) -> "прошлое сообщение первым ещё без ответа"
            i.sourceRefusal != null -> "нечего сказать: ${i.sourceRefusal}"
            !i.modelChosen -> "модель ни разу не выбиралась — загружать нечего"
            else -> null
        }
    }

    /** Ждёт ли ответа сообщение агента, написанное первым в [initiativeAt]. */
    fun awaiting(initiativeAt: Long?, ownerReplyAt: Long?): Boolean =
        initiativeAt != null && (ownerReplyAt == null || ownerReplyAt <= initiativeAt)

    /**
     * Строка прибора. Печатается всегда: молчащий выход неотличим от
     * сломанного. Сообщение без ответа называется раньше любого отказа: пока
     * оно ждёт, это главное, что агент делает первым.
     *
     * @param what о чём было последнее сообщение первым (как его назвал источник).
     * @param note приписка о доставке; null — без приписки.
     */
    fun meter(
        refusal: String?,
        lastInitiativeAt: Long?,
        what: String?,
        ownerReplyAt: Long?,
        note: String? = null,
        clock: (Long) -> String = ::clock,
    ): String {
        val tail = note?.let { " · $it" } ?: ""
        if (lastInitiativeAt != null && awaiting(lastInitiativeAt, ownerReplyAt)) {
            return "Первым: написал в ${clock(lastInitiativeAt)} ($what), жду ответа$tail"
        }
        return if (refusal == null) "Первым: пишу$tail" else "Первым: не пишу — $refusal$tail"
    }

    private fun minutes(ms: Long): Long = ms.coerceAtLeast(0) / 60_000

    private fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}
