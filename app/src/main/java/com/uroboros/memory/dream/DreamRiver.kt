package com.uroboros.memory.dream

import com.uroboros.memory.Sticker

/**
 * Река: сны, сплетённые из снов. Вспомненный вчера сон продолжается сегодняшним.
 *
 * ПРИТОК — сон прошлой ночи, который агент вспомнил (см. [AgentRecall]): взял
 * принесённую им запись в свой ответ. Подача без вспоминания притоком не
 * становится: вспомнить — значит взять, а не получить.
 *
 * ПЛЕТЕНИЕ. Приток продолжается сном этой ночи, который делит с ним хотя бы
 * одну запись и приносит хотя бы одну новую: приток (1, 2) и сон (2, 5) дают
 * сон реки (1, 2, 5). Связь идёт только через общую запись — время между
 * ночами ничего не значит. Сон реки — такой же сон, со своей пометкой; его
 * подают модели, как все, и если агент вспомнит его, завтра он сам станет
 * притоком.
 *
 * ОСТЫВАНИЕ — ЗА НОЧЬ. Притоки берутся только из прошлой ночи. Невспомненное
 * дальше не течёт: чтобы цепочка жила, её надо вспоминать.
 *
 * ПРОТИВ ЭХА. Вспомненное подаётся чаще, потому что его чаще подавали, — петля
 * популярности. Её держат три предела: у каждого сна реки есть запись, которой
 * в притоке не было; на приток не больше [PER_TRIBUTARY] продолжений; цепочка
 * длиннее [MAX_CHAIN] не плетётся — дорастя до предела, она перестаёт расти.
 * И свой потолок на ночь, [CEILING], отдельный от потолка снов: пачка снов
 * одного разговора не должна молча вытеснять реку.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - вспоминание — совпадение слов ответа с записью, а не понимание (см.
 *    [AgentRecall]), и река наследует это;
 *  - не взвешивает, какой приток важнее: берёт свежевспомненные первыми;
 *  - все пределы — объявленные стартовые числа.
 */
object DreamRiver {

    /** Вид сна реки в базе и в показе. Не входит в [DreamWeaver.Kind]: ночь сама его не плетёт. */
    const val KIND = "RIVER"

    /** Потолок снов реки за ночь, отдельный от потолка ночи. */
    const val CEILING = 10

    /** Не больше двух продолжений на один приток. */
    const val PER_TRIBUTARY = 2

    /** Цепочка реки — не длиннее пяти записей. */
    const val MAX_CHAIN = 5

    /**
     * Сплести реку.
     *
     * @param tributaries вспомненные сны прошлой ночи;
     * @param tonight сны этой ночи, как их сплёл [DreamWeaver];
     * @param byId записи по номеру — чтобы приток со скрытым или исчезнувшим
     *        звеном молчал так же, как молчит сон (см. [Dream.silences]).
     * @return цепочки номеров снов реки, без повторов и без совпадений со
     *         снами этой ночи.
     */
    fun weave(
        tributaries: List<Dream>,
        tonight: List<DreamWeaver.Woven>,
        byId: Map<Long, Sticker>,
    ): List<List<Long>> {
        val taken = tonight.mapTo(HashSet()) { it.recordIds }
        val river = ArrayList<List<Long>>()
        val ordered = tributaries.sortedByDescending { it.lastRecalledAt ?: 0L }
        for (tributary in ordered) {
            if (river.size >= CEILING) break
            val chain = tributary.ids()
            if (chain.isEmpty() || chain.any { Dream.silences(byId[it]) }) continue
            var continued = 0
            for (dream in tonight) {
                if (continued >= PER_TRIBUTARY || river.size >= CEILING) break
                if (dream.recordIds.none { it in chain }) continue
                val fresh = dream.recordIds.filter { it !in chain }
                if (fresh.isEmpty()) continue
                val next = chain + fresh
                if (next.size > MAX_CHAIN || next in taken) continue
                taken += next
                river += next
                continued++
            }
        }
        return river
    }
}
