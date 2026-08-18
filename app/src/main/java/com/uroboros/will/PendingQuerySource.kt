package com.uroboros.will

import java.util.concurrent.atomic.AtomicReference

/**
 * Item 9: канал связи между внешним вводом (UI) и TOTE-циклом, работающим в фоне.
 * Дешёвый неблокирующий poll вместо Channel/Flow (вариант F, зафиксировано 2026-08-18) —
 * движку нужно только "есть ли необработанный запрос", не полноценный стриминг.
 */
fun interface PendingQuerySource {
    /** Возвращает и одновременно очищает накопленный запрос, если он есть. */
    fun poll(): String?
}

/**
 * Простая реализация на AtomicReference — одна атомарная операция getAndSet,
 * без буферов и диспетчеров корутин. UI-слой вызывает submit() при вводе пользователя;
 * ToteEngine вызывает poll() на каждом шве между test() и operate().
 */
class SimplePendingQuerySource : PendingQuerySource {
    private val pending = AtomicReference<String?>(null)

    fun submit(query: String) {
        pending.set(query)
    }

    override fun poll(): String? = pending.getAndSet(null)
}
