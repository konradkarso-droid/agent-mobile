package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.gate.link.ChatPrefs
import com.uroboros.memory.MemoryDatabase
import com.uroboros.memory.nav.LemmaHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Сверка заявок о себе ([SelfClaim.Checker]) по памяти устройства — одно место
 * для экрана, телеграма, доски и облака агента. Разойдись они, одна и та же
 * выдумка подхватывалась бы в одном месте и отсеивалась в другом.
 *
 * Читает при каждом вызове: память, сны последних [DreamWeaver.QUEUE_NIGHTS]
 * ночей, имена агента и ключ владельца. Словарь не грузит — его грузят экран и
 * служба; не загружен — сверки нет ([NO_DICTIONARY]).
 */
object SelfClaimReader {

    suspend fun checker(context: Context): SelfClaim.Checker? {
        val words = DreamPlot.Words.of(LemmaHolder.loaded, ChatPrefs.names(context)) ?: return null
        val db = MemoryDatabase.getInstance(context)
        return withContext(Dispatchers.IO) {
            SelfClaim.Checker(
                db.stickerDao().getAll(),
                db.dreamDao().ofRecentNights(DreamWeaver.QUEUE_NIGHTS, 0),
                words,
                ChatPrefs.owner(context)?.key?.id,
            )
        }
    }

    /** Почему сверки нет, когда [checker] вернул null. */
    const val NO_DICTIONARY = "словарь начальных форм не загружен"
}
