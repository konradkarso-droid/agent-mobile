package com.uroboros.llm

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Кто и когда говорил последним — на диске, чтобы пережить кому: когда ушла в
 * движок последняя реплика владельца и когда агент в последний раз написал
 * первым (и о чём, для прибора).
 *
 * ЗАЧЕМ НА ДИСКЕ. Из этих двух времён считаются «молчит ли владелец» и «ждёт ли
 * ответа прошлое сообщение агента» (см. initiative.InitiativeDecision и
 * dream.CuriosityAsk). В памяти процесса после комы их нет, и тогда вопрос,
 * если он хоть раз был, считался бы ждущим ответа до первой реплики владельца:
 * агент замолкал бы после каждой комы именно тогда, когда владелец молчит.
 *
 * ПОЧЕМУ В НАСТРОЙКАХ ПРИЛОЖЕНИЯ, а не в базе:
 *  - не в базе ленты: лента закрывается в архив по решению владельца, а
 *    закрытый разговор не значит, что владелец заговорил. Молчание — свойство
 *    владельца, а не разговора;
 *  - не в базе памяти: ради двух чисел там понадобились бы новая таблица и
 *    миграция, а записи памяти эти числа не описывают;
 *  - в настройках уже лежит выбранная модель, которую тело агента читает само
 *    (ModelPrefs), — то есть это место, которое служба и экран читают
 *    одинаково.
 *
 * ПИШЕТСЯ СРАЗУ (commit, а не apply): кома через секунду после реплики не
 * должна её стереть. Запись, которая не удалась, запоминается в процессе, и
 * чтение после неё отказывает: иначе на диске осталось бы старое время, и
 * агент решил бы, что владелец молчит, пока тот разговаривает.
 *
 * ЧЕГО НЕ УМЕЕТ: реплики, сказанные до появления этого файла, ему неизвестны —
 * до первой реплики владельца после установки молчание считать не с чего.
 */
class ConversationTimes(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Последнее сообщение, написанное агентом первым. */
    data class Initiative(val at: Long, val what: String)

    /**
     * @param ownerReplyAt когда ушла в движок последняя реплика владельца;
     *   null — ни одной с тех пор, как времена пишутся на диск.
     * @param initiative последнее сообщение агента, написанное первым; null — не было.
     */
    data class Snapshot(val ownerReplyAt: Long?, val initiative: Initiative?)

    /** Прочитать оба времени. Бросает, если прочитать нельзя (см. шапку). */
    suspend fun read(): Snapshot = withContext(Dispatchers.IO) {
        writeFailures.entries.firstOrNull()?.let { (key, why) ->
            throw IllegalStateException("последняя запись ($key) не удалась: $why")
        }
        val initiativeAt = if (prefs.contains(KEY_INITIATIVE_AT)) prefs.getLong(KEY_INITIATIVE_AT, 0L) else null
        Snapshot(
            ownerReplyAt = if (prefs.contains(KEY_OWNER_REPLY_AT)) prefs.getLong(KEY_OWNER_REPLY_AT, 0L) else null,
            initiative = initiativeAt?.let { Initiative(it, prefs.getString(KEY_INITIATIVE_WHAT, null) ?: "о чём — не записано") },
        )
    }

    /** Время последней реплики владельца; бросает, если прочитать нельзя. */
    suspend fun ownerReplyAt(): Long? = read().ownerReplyAt

    /** Реплика владельца ушла в движок в момент [at]. */
    suspend fun noteOwnerReply(at: Long) = write(KEY_OWNER_REPLY_AT) { putLong(KEY_OWNER_REPLY_AT, at) }

    /** Агент написал первым в момент [at]; [what] — о чём, словами для прибора. */
    suspend fun noteInitiative(at: Long, what: String) =
        write(KEY_INITIATIVE_AT) { putLong(KEY_INITIATIVE_AT, at).putString(KEY_INITIATIVE_WHAT, what) }

    /** Отказ записи помнится, пока та же величина не запишется удачно. */
    private suspend fun write(key: String, edit: SharedPreferences.Editor.() -> Unit) {
        val failure = withContext(Dispatchers.IO) {
            val ok = runCatching { prefs.edit().apply(edit).commit() }
            when {
                ok.isFailure -> ok.exceptionOrNull()?.javaClass?.simpleName ?: "сбой"
                ok.getOrNull() != true -> "диск не принял запись"
                else -> null
            }
        }
        if (failure == null) {
            writeFailures.remove(key)
        } else {
            writeFailures[key] = failure
        }
    }

    companion object {
        private const val NAME = "uroboros_conversation_times"
        private const val KEY_OWNER_REPLY_AT = "owner_reply_at"
        private const val KEY_INITIATIVE_AT = "initiative_at"
        private const val KEY_INITIATIVE_WHAT = "initiative_what"

        /**
         * Неудавшиеся записи этого процесса: величина → почему. Общие на
         * процесс, а не на объект: экран и служба заводят каждый свой объект,
         * а отказ записи экрана обязана увидеть и служба.
         */
        private val writeFailures = ConcurrentHashMap<String, String>()
    }
}
