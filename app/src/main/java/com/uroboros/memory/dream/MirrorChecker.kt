package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.MemoryDatabase

/**
 * Сверка «сбылось» в ходе разговора: реплика владельца сверяется с вариантами
 * зеркала, окно которых не кончилось. Правило — в [Mirror.check]; здесь только
 * чтение и отметка в таблице зеркала. Больше ничего не пишется: ни касаний, ни
 * прогрева записей, ни веса любопытства.
 */
class MirrorChecker(private val mirror: MirrorDao) {

    constructor(context: Context) : this(MemoryDatabase.getInstance(context).mirrorDao())

    /**
     * Сверить реплику [reply], сказанную в момент [at]. Каждому сверенному
     * варианту прибавляется реплика, сбывшемуся пишется время, основы и сама
     * реплика.
     *
     * @return сколько вариантов сбылось этой репликой.
     */
    suspend fun check(reply: String, at: Long): Int {
        var fulfilled = 0
        for (variant in mirror.unsettled(Mirror.CHECK_WINDOW, at)) {
            val checked = Mirror.check(
                variant.text, Mirror.splitStems(variant.excludedStems), variant.repliesSeen, reply,
            ) ?: continue
            val words = checked.fulfilledWords
            if (words == null) {
                mirror.markSeen(variant.id, checked.repliesSeen)
            } else {
                mirror.markFulfilled(variant.id, checked.repliesSeen, at, Mirror.joinStems(words), reply)
                fulfilled++
            }
        }
        return fulfilled
    }
}
