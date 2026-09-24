package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sticker
import com.uroboros.memory.StickerDao

/**
 * Подхватил ли владелец сон: заговорил ли он в своей следующей реплике о том,
 * что было в сне, поданном модели на предыдущем ходе.
 *
 * ПОДХВАЧЕН = В РЕПЛИКЕ ЕСТЬ СЛОВА СНА, КОТОРЫХ НЕ БЫЛО В ПРОШЛОМ ВОПРОСЕ.
 * Сопоставление тем же приёмом, что у вспоминания агентом (см. [AgentRecall]):
 * значимые основы записей сна ([RiskTrigger.significantStems]) минус основы
 * исключённого текста. Исключается предыдущий вопрос владельца: иначе владелец,
 * продолжающий свою же тему, засчитывался бы как подхвативший сон — сон к
 * этому вопросу и подбирался по его словам.
 *
 * ПОРОГ — ОДНА ОБЩАЯ ОСНОВА, а не две, как у [AgentRecall.MIN_SHARED_STEMS].
 * Приём общий, порог нет, и причина в том, куда уходит промах. Вспоминание
 * греет запись — это действие, которое ниже никто не отсеет. Подхват только
 * прибавляет вес пружине любопытства (см. [CuriosityPressure]), а у накопителя
 * сигнала лишнее восстановимо, пропуск нет. Объявленное число, не подобранное;
 * перепроверять по прибору — строка «в этом ходе подхвачен сон …» при
 * репликах, где владелец о сне не говорил.
 *
 * СПРОШЕННЫЙ СОН — НЕ ПОДХВАТ, А ОТВЕТ. Если о сне агенту было предложено
 * спросить ([CuriosityAsk]), та же совпавшая реплика отмечается ответом
 * ([Dream.answeredCount]), а не подхватом: почему — у [Dream.askedAt].
 * Правило сопоставления одно, различается только отметка. Спрошен ли сон,
 * читается из базы в момент отметки ([DreamPickupMarker]), а не из снимка,
 * снятого при подаче: снимок мог быть снят до вопроса.
 *
 * ТОЛЬКО ВЕС. Подхват не греет записи, ничего не пишет в память и
 * подтверждением факта не считается: совпадение слов — не согласие.
 *
 * МОЛЧАНИЕ. Сон со скрытым, отвергнутым или удалённым звеном не
 * подхватывается — то же правило, что у показа и подачи ([Dream.silences]).
 * Звенья проверяются на момент реплики, а не подачи: запись могли скрыть
 * между ходами.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - сравнивает слова, а не смысл: пересказ своими словами не засчитается,
 *    случайное совпадение одной основы — засчитается;
 *  - видит только сны, поданные на одном предыдущем ходе. Сон, поданный
 *    раньше, владелец может подхватить и позже — такое не считается;
 *  - «предыдущий ход» живёт в памяти процесса, как двери сна ([DreamDoor]):
 *    после комы первая реплика ничего не подхватывает;
 *  - проверка тратится одна на ход: реплика, которая дошла до движка, но хода
 *    не закрыла, всё равно её съедает. Иначе повторная отправка той же реплики
 *    засчитала бы подхват дважды.
 */
object DreamPickup {

    /** Сны, поданные модели на предыдущем ходе, и вопрос того хода. */
    data class Previous(val question: String, val dreams: List<Dream>)

    private var previous: Previous? = null

    /** Ход закрыт: запомнить, что на нём подано. Пустая подача стирает прежнее. */
    @Synchronized
    fun afterTurn(question: String, served: List<Dream>) {
        previous = if (served.isEmpty()) null else Previous(question, served)
    }

    /** Забрать поданное на предыдущем ходе. Второй вызов вернёт null. */
    @Synchronized
    fun take(): Previous? = previous.also { previous = null }

    /** Разговор закрыт: подхватывать в новом разговоре нечего. */
    @Synchronized
    fun forget() {
        previous = null
    }

    /**
     * Какие из поданных снов подхвачены репликой [reply].
     *
     * @param served поданные сны с их записями, прочитанными заново; null —
     *   записи больше нет.
     * @param previousQuestion вопрос того хода, на котором сны поданы.
     * @return сны с живыми записями — для прибора.
     */
    fun pickedUp(
        served: List<Pair<Dream, List<Sticker?>>>,
        previousQuestion: String,
        reply: String,
    ): List<Pair<Dream, List<Sticker>>> {
        val said = RiskTrigger.significantStems(reply)
        if (said.isEmpty()) return emptyList()
        val excluded = RiskTrigger.significantStems(previousQuestion)
        return served.distinctBy { it.first.nightAt to it.first.recordIds }.mapNotNull { (dream, records) ->
            if (records.any { Dream.silences(it) }) return@mapNotNull null
            val live = records.map { it!! }
            val own = live.flatMapTo(HashSet()) { RiskTrigger.significantStems(it.content) } - excluded
            if (own.none { it in said }) null else dream to live
        }
    }
}

/**
 * Исполнение подхвата в базе: записи снов читаются заново, правило — в
 * [DreamPickup], отметка — через [DreamServedDao]. Записи только читаются по
 * номеру, без отметки обращения: подхват ничего не греет.
 */
class DreamPickupMarker(
    private val served: DreamServedDao,
    private val stickers: StickerDao,
) {

    constructor(context: Context) : this(
        MemoryDatabase.getInstance(context).dreamServedDao(),
        MemoryDatabase.getInstance(context).stickerDao(),
    )

    /**
     * Что поймала реплика: подхваченные сны и спрошенные сны, о которых она
     * ответила. Порознь — см. «СПРОШЕННЫЙ СОН» у [DreamPickup].
     */
    data class Caught(
        val pickedUp: List<Pair<Dream, List<Sticker>>>,
        val answered: List<Pair<Dream, List<Sticker>>>,
    )

    /** @return пойманные сны с их записями; уже отмечены в базе. */
    suspend fun pickUp(
        previous: DreamPickup.Previous,
        reply: String,
        now: Long = System.currentTimeMillis(),
    ): Caught {
        val withRecords = previous.dreams.map { dream -> dream to dream.ids().map { stickers.getById(it) } }
        val caught = DreamPickup.pickedUp(withRecords, previous.question, reply)
        val pickedUp = mutableListOf<Pair<Dream, List<Sticker>>>()
        val answered = mutableListOf<Pair<Dream, List<Sticker>>>()
        for (pair in caught) {
            val dream = pair.first
            if (served.askedAt(dream.nightAt, dream.recordIds) != null) {
                served.markAnswered(dream.nightAt, dream.recordIds, now)
                answered += pair
            } else {
                served.markPickedUp(dream.nightAt, dream.recordIds, now)
                pickedUp += pair
            }
        }
        return Caught(pickedUp, answered)
    }
}
