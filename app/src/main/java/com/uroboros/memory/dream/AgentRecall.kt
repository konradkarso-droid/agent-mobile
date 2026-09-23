package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.HourglassMemory
import com.uroboros.memory.Layer
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.Prism
import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sticker
import com.uroboros.memory.StickerDao

/**
 * Вспомнил ли агент то, что принёс ему сон, — и куда воспоминание греет запись.
 *
 * ВСПОМНИЛ = ИСПОЛЬЗОВАЛ В ОТВЕТЕ. Сон подаёт модели записи, которых не было
 * среди найденных к вопросу. Если в ответе агента стоят значимые слова такой
 * записи, которых не было ни в вопросе, ни в найденных записях, — он взял их из
 * сна. Решает агент, а не человек: подхватит ли запись собеседник, здесь не
 * спрашивается.
 *
 * Сомнение решается в сторону «не вспомнил»: воспоминание греет запись, а это
 * действие, которое ниже никто не отсеет. Поэтому слова вопроса и найденных
 * записей вычитаются, и совпасть должно не меньше [MIN_SHARED_STEMS] основ.
 *
 * ПРОГРЕВ — ОДНА СТУПЕНЬ И НЕ ЧАЩЕ [WARM_EVERY_MS]. Вспоминание агента греет
 * его же будущий контекст: вспомнил → согрел → чаще в отборе → чаще снится →
 * снова вспомнил. Суточный предел держит эту петлю не быстрее остывания
 * верхнего слоя: запись, которую агент вспоминает каждый день, стоит наверху,
 * перестал — уходит вниз обычным ходом. Красный слой не греется и не
 * остывает — он вне этого пути совсем; выше оранжевого не поднимает потолок
 * самого Prism.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - сравнивает слова, а не смысл. Пересказ своими словами не засчитается,
 *    случайное совпадение двух основ — засчитается;
 *  - видит только сны, поданные на этом ходе. Сон, поданный раньше и лежащий
 *    в ленте, агент тоже может вспомнить позже — такое не считается;
 *  - короткая запись из одной значимой основы вспоминается по одной основе:
 *    требовать двух от записи, где она одна, значило бы не засчитывать её
 *    никогда.
 */
object AgentRecall {

    /**
     * Две общие основы. Стартовое число: одна основа совпадает случайно слишком
     * часто, чтобы греть по ней. Перепроверять по прибору — сколько записей
     * «вспомнено» при ответах, где сон явно не пересказывался.
     */
    const val MIN_SHARED_STEMS = 2

    /** Сутки: столько живёт верхний слой, отсюда и равновесие наверху. */
    const val WARM_EVERY_MS = 24L * 60 * 60 * 1000

    /**
     * Какие из принесённых сном записей агент использовал в ответе.
     *
     * @param brought записи из поданных снов, которых нет среди найденных;
     * @param found записи, найденные к вопросу, — их слова не засчитываются.
     */
    fun recalled(brought: List<Sticker>, found: List<Sticker>, question: String, answer: String): List<Sticker> {
        val excluded = HashSet<String>()
        excluded += RiskTrigger.significantStems(question)
        for (record in found) excluded += RiskTrigger.significantStems(record.content)
        val said = RiskTrigger.significantStems(answer)
        return brought.distinctBy { it.id }.filter { record ->
            val own = RiskTrigger.significantStems(record.content) - excluded
            if (own.isEmpty()) return@filter false
            val shared = own.intersect(said).size
            shared >= minOf(MIN_SHARED_STEMS, own.size)
        }
    }

    /**
     * Куда воспоминание греет запись сейчас, или null — не греть: запись в
     * красном слое, скрыта, отвергнута или уже грелась воспоминанием меньше
     * суток назад. Оранжевая запись «греется» на свой же слой — срок её
     * продлевается, и это то самое равновесие наверху.
     */
    fun warmTarget(record: Sticker, now: Long): Layer? {
        if (record.reviewPending || record.rejectedAt != null) return null
        val layer = Layer.valueOf(record.layer)
        if (layer == Layer.RED) return null
        val last = record.lastAgentRecallAt
        if (last != null && now - last < WARM_EVERY_MS) return null
        return Prism.warmerLayer(layer)
    }

    /**
     * Какие из поданных снов вспомнены: хоть одна принесённая ими запись
     * вошла во вспомненные. Запись, найденная к вопросу, сну не засчитывается —
     * её агент знал и без сна.
     *
     * @param served поданные сны с их записями, как их подала [DreamRecall].
     */
    fun recalledDreams(served: List<Pair<Dream, List<Sticker>>>, recalledIds: Set<Long>): List<Dream> =
        served.filter { (_, records) -> records.any { it.id in recalledIds } }
            .map { it.first }
            .distinctBy { it.nightAt to it.recordIds }

    /** Итог вспоминания на одном ходе: сколько вспомнено и сколько из них согрето. */
    data class Outcome(val recalled: Int, val warmed: Int)

    /**
     * Строка прибора. Печатается и при нуле: ноль при принесённых записях —
     * «не вспомнил», а без строки он неотличим от «сон не подавался».
     */
    fun meter(brought: Int, outcome: Outcome): String? {
        if (brought == 0) return null
        return "Вспомнено агентом: ${outcome.recalled} из принесённых снами $brought" +
            if (outcome.recalled > 0) " · согрето ${outcome.warmed}" else ""
    }
}

/**
 * Исполнение вспоминания в базе: счёт каждой вспомненной записи и прогрев там,
 * где [AgentRecall.warmTarget] разрешает. Правила — в [AgentRecall]; здесь
 * только чтение и запись.
 *
 * ПУТЬ АГЕНТА, в отличие от отвержения и приёма: это его собственное
 * вспоминание. Потолок у пути жёсткий — одна ступень в сутки, красный не
 * трогается, скрытые и отвергнутые записи пропускаются.
 */
class AgentRecaller(
    private val stickers: StickerDao,
    private val recall: AgentRecallDao,
) {

    constructor(context: Context) : this(
        MemoryDatabase.getInstance(context).stickerDao(),
        MemoryDatabase.getInstance(context).agentRecallDao(),
    )

    suspend fun recall(ids: Collection<Long>, now: Long = System.currentTimeMillis()): AgentRecall.Outcome {
        if (ids.isEmpty()) return AgentRecall.Outcome(0, 0)
        // Остывание первым, как у всякого, кто читает слой: иначе запись,
        // остывшая по часам, грелась бы от слоя, где её уже нет.
        HourglassMemory(stickers).migrateExpired()
        var recalled = 0
        var warmed = 0
        for (id in ids.distinct()) {
            val record = stickers.getById(id) ?: continue
            if (record.reviewPending || record.rejectedAt != null) continue
            recall.touchAgentRecall(id)
            recalled++
            val target = AgentRecall.warmTarget(record, now) ?: continue
            recall.warmByAgentRecall(id, target.name, Prism.newInterval(target)?.let { now + it }, now)
            warmed++
        }
        return AgentRecall.Outcome(recalled, warmed)
    }

    /** Отметить сны вспомненными — они станут притоками реки (см. [DreamRiver]). */
    suspend fun markDreams(dreams: List<Dream>, now: Long = System.currentTimeMillis()) {
        for (dream in dreams) recall.markDreamRecalled(dream.nightAt, dream.recordIds, now)
    }
}
