package com.uroboros.memory.dream

import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sticker

/**
 * Дверь сна: записи, которые принёс поданный сон, несколько ходов видны отбору
 * к вопросу — холодные и архивные тоже.
 *
 * ЗАЧЕМ. Отбор к вопросу берёт только горячие слои; холодное и архив — лишь по
 * словам вроде «архив». Сон же приносит агенту запись из любого слоя. Без
 * двери разговор о принесённом обрывался бы на следующем ходе: вопрос о ней
 * её уже не находил. Через дверь запись снова становится найденной — а значит,
 * по ней сверяются расхождения и через неё всплывают её собственные сны:
 * ассоциация тянет следующую.
 *
 * ВИДНА — НЕ ЗНАЧИТ ПОДАНА. Запись из двери входит в найденные, только если
 * вопрос задевает её хотя бы одной значимой основой; иначе она остаётся там,
 * где уже есть, — строкой сна в ленте. И не больше [MAX_PER_TURN] на ход.
 *
 * НИЧЕГО НЕ ГРЕЕТ. Найденная через дверь запись не получает ни обращения, ни
 * пользы, ни прогрева: дверь только открывает видимость. Греет запись одно
 * вспоминание агентом (см. [AgentRecall]), и дверь его не обходит — найденное
 * вспоминанием не считается.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - живёт в памяти процесса: кома закрывает все двери;
 *  - ходы считает ответами, а не временем: дверь, открытая вечером, утром
 *    открыта так же, если между ними не было разговора;
 *  - совпадение основ — это слова, а не смысл, как и везде в отборе.
 */
object DreamDoor {

    /** Три хода. Стартовое число: столько обычно длится разговор об одном. */
    const val DOOR_TURNS = 3

    /** Не больше двух записей из двери на ход — столько же, сколько снов. */
    const val MAX_PER_TURN = 2

    private val open = LinkedHashMap<Long, Int>()

    /** Номера записей, чья дверь сейчас открыта, — от недавно открытых к старым. */
    @Synchronized
    fun openIds(): List<Long> = open.keys.reversed()

    /**
     * Ход закончился: у всех открытых дверей остаётся на ход меньше, затем
     * открываются двери для [brought] — записей, принесённых снами этого хода.
     * Уже открытая дверь открывается заново на полный срок.
     */
    @Synchronized
    fun afterTurn(brought: Collection<Long>) {
        val it = open.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value <= 1) it.remove() else entry.setValue(entry.value - 1)
        }
        for (id in brought) {
            open.remove(id)
            open[id] = DOOR_TURNS
        }
    }

    /**
     * Какие из записей за открытыми дверями войдут в найденные к этому
     * вопросу. Скрытые, отвергнутые, вопросы, просьбы и уже найденные
     * пропускаются.
     */
    fun pick(behindDoor: List<Sticker>, found: List<Sticker>, question: String): List<Sticker> {
        val asked = RiskTrigger.significantStems(question)
        if (asked.isEmpty()) return emptyList()
        val foundIds = found.mapTo(HashSet()) { it.id }
        return behindDoor.asSequence()
            .filter { it.id !in foundIds }
            .filter { !it.reviewPending && it.rejectedAt == null }
            .filter { !RiskTrigger.assertsNothing(it.content) }
            .filter { RiskTrigger.significantStems(it.content).any { stem -> stem in asked } }
            .take(MAX_PER_TURN)
            .toList()
    }

    /** Для тестов: все двери закрыты. */
    @Synchronized
    internal fun closeAll() = open.clear()
}
