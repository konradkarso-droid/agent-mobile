package com.uroboros

import android.content.Context
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.dream.SleepPressure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Строка состояния агента в реплике: что с ним изменилось с прошлого раза.
 *
 * ПОЧЕМУ В РЕПЛИКЕ, А НЕ В СТЕНЕ. Состояние меняется от хода к ходу, а стена
 * лежит обсчитанной в кэше; перемена в ней стоит полного пересчёта стены.
 * Устройство агента — в стене (см. llm.BuildSelfDescription), состояние — здесь.
 *
 * ТОЛЬКО ПРИ ПЕРЕМЕНЕ. Строка ложится в ленту и остаётся там. Одинаковая
 * строка на каждом ходе забила бы ленту повторами, поэтому она появляется,
 * лишь когда с прошлого показа что-то сдвинулось:
 *  - был перерыв в жизни (сменился счёт подъёмов, см. [AgentLife]);
 *  - на сон накопилось (давление сна стало больше нуля, см. SleepPressure) —
 *    один раз на цикл, а не на каждое новое утверждение;
 *  - разговор занял 50, 75 или 90 % места.
 *
 * ИСТОЧНИК ОДИН С ЭКРАНОМ. Числа берутся из тех же приборов, что показывает
 * «Подробно»; строка ничего не считает сама. Иначе агент и экран разошлись бы,
 * и не было бы видно, кто из них врёт.
 *
 * ОТ ПЕРВОГО ЛИЦА, РЕЧЬЮ АГЕНТА — как подписи записей (memory.ProvenanceLabels)
 * и строка любопытства (dream.CuriosityAsk). Модель переписывает служебные
 * строки в ответ дословно. Строка во втором лице, переписанная так, обращается
 * уже к собеседнику: «ты очнулся в 17:22» выходит «Проснёшься в 17:22», «у
 * тебя накопилось» — «Обсуди, что накопилось». Строка от первого лица,
 * переписанная в ответ, остаётся правдой об агенте. Обратная цена первого
 * лица — «я» в реплике собеседника читается как его слова; от неё строку
 * отделяет метка, которую ставит сборка реплики (см.
 * llm.ConversationJournal.TO_AGENT_MARK).
 *
 * О СНЕ НЕ ГОВОРИТСЯ ВОВСЕ — ни что агент спал, ни когда, ни число снов. Сны в
 * разговор не подаются ни в каком виде, днём действуют только их результаты,
 * и голое «я поспал» агенту ничего не давало, а модели служило крючком: она
 * принималась говорить о снах, и не своих, а собеседника.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - сведения, а не рычаг: узнав, что накопилось на сон, агент уснуть не
 *    может — спит служба по своим порогам;
 *  - что показано, помнится в памяти процесса. После комы первая строка
 *    говорит всё, что сейчас правда, — прошлый показ неизвестен;
 *  - пороги ленты — объявленные числа;
 *  - строка стоит в реплике собеседника, и «я» в ней модель может принять за
 *    его слова — та же цена, что у подписей записей.
 */
object SelfState {

    data class Snapshot(
        /** Подъёмов тела с установки (см. [AgentLife.Record.starts]). */
        val starts: Int,
        /** Когда поднялось нынешнее тело, мс. */
        val aliveSince: Long?,
        /** Давление сна (см. SleepPressure.Reading.changed). */
        val sleepPressure: Int,
        /** Сколько процентов места занял разговор. */
        val ribbonPercent: Int,
    )

    /** Пороги заполнения ленты, о которых говорится. */
    val RIBBON_BANDS = listOf(50, 75, 90)

    private fun band(percent: Int): Int = RIBBON_BANDS.count { percent >= it }

    /**
     * Строка о переменах от [prev] к [now], или null — меняться нечему.
     * [prev] == null — прошлый показ неизвестен (новый процесс): говорится
     * всё, что сейчас правда.
     */
    fun line(prev: Snapshot?, now: Snapshot): String? {
        val parts = ArrayList<String>(4)
        val revived = if (prev == null) now.starts > 1 else now.starts != prev.starts
        if (revived && now.aliveSince != null) {
            parts += "Перед этим у меня был перерыв в жизни: я очнулся в ${clock(now.aliveSince)}."
        }
        if (now.sleepPressure > 0 && (prev == null || prev.sleepPressure == 0)) {
            parts += "С последнего сна у меня накопилось, что обдумать."
        }
        val nowBand = band(now.ribbonPercent)
        if (nowBand > 0 && nowBand > (prev?.let { band(it.ribbonPercent) } ?: 0)) {
            parts += "Наш разговор длинный: он занял ${RIBBON_BANDS[nowBand - 1]} % моего места для разговора."
        }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    @Volatile
    private var shown: Snapshot? = null

    /** Что показано в последний раз в этом процессе; null — ещё ничего. */
    fun lastShown(): Snapshot? = shown

    /**
     * Запомнить показанное. Зовёт тот, кто собирает ход, — после того, как
     * ход лёг в ленту: реплика, не дошедшая до модели, не показана.
     */
    fun markShown(snapshot: Snapshot) {
        shown = snapshot
    }

    /** Снимок из приборов. Бросает исключение, если база не ответила. */
    suspend fun read(context: Context, ribbonPercent: Int): Snapshot {
        val life = AgentLife.read(context)
        val db = MemoryDatabase.getInstance(context)
        val night = db.dreamDao().lastNight()
        val rows = night?.let { db.dreamDao().ofNight(it.nightAt) }.orEmpty()
        val records = db.stickerDao().getAll()
        val pressure = withContext(Dispatchers.Default) { SleepPressure.measure(records, night, rows) }
        return Snapshot(
            starts = life.starts,
            aliveSince = life.lastStartAt,
            sleepPressure = pressure.changed,
            ribbonPercent = ribbonPercent,
        )
    }

    private fun clock(millis: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))
}
