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
 * Строка состояния агента: что с ним изменилось с прошлого раза. Только на
 * экран, в «Подробно» («О себе сейчас»).
 *
 * ТОЛЬКО НА ЭКРАН. Модели строка не подаётся. Лёжа в реплике собеседника,
 * она путала авторство при любой форме. Во втором лице модель 3B
 * переписывала её собеседнику: «ты очнулся в 17:22» выходило «Проснёшься в
 * 17:22». В первом лице под меткой (llm.ConversationJournal.TO_AGENT_MARK)
 * выходило своё, приписанное собеседнику: «я очнулся в 15:42» — «событие в
 * 15:42 оставило в твоей памяти воспоминание», «у меня накопилось, что
 * обдумать» — «погода накопилась в твоей памяти». Рычага строка агенту не
 * давала (см. «ЧЕГО НЕ УМЕЕТ»), так что без неё он не теряет ничего, чем
 * пользовался, а с ней путает, кто что сказал. Вернуть её модели можно
 * только другим каналом и только после замера, что тот держит авторство
 * лучше метки.
 *
 * ТОЛЬКО ПРИ ПЕРЕМЕНЕ. Одинаковая строка на каждом ходе заслонила бы саму
 * перемену, поэтому строка появляется, лишь когда с прошлого показа что-то
 * сдвинулось:
 *  - был перерыв в жизни (сменился счёт подъёмов, см. [AgentLife]);
 *  - на сон накопилось (давление сна стало больше нуля, см. SleepPressure) —
 *    один раз на цикл, а не на каждое новое утверждение;
 *  - разговор занял 50, 75 или 90 % места.
 *
 * ИСТОЧНИК ОДИН С ЭКРАНОМ. Числа берутся из тех же приборов, что показывает
 * «Подробно» в других строках; эта строка ничего не считает сама. Иначе
 * строки разошлись бы, и не было бы видно, какая врёт.
 *
 * ФОРМА — ДЛЯ ВОЗМОЖНОГО ВОЗВРАТА К МОДЕЛИ. Строки от первого лица и без
 * слова «сон»: второе лицо уходит собеседнику (см. выше), а сны в разговор
 * не подаются ни в каком виде — днём действуют только их результаты, и слово
 * «сон» служило модели крючком: она принималась говорить о снах, и не своих,
 * а собеседника. Проверяет это SelfStateTest.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - сведения, а не рычаг: узнав, что накопилось на сон, агент уснуть не
 *    может — спит служба по своим порогам;
 *  - что показано, помнится в памяти процесса. После комы первая строка
 *    говорит всё, что сейчас правда, — прошлый показ неизвестен;
 *  - пороги ленты — объявленные числа.
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
            parts += "У меня накопилось, что обдумать."
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
