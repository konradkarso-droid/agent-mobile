package com.uroboros.memory.dream

import android.content.Context
import com.uroboros.memory.desk.Promises
import java.security.MessageDigest

/** Отметка поданной сверки. Содержимое обещаний и сведения об их исполнении здесь не хранятся. */
internal class CuriosityFollowUpStore(context: Context) {
    private val prefs = context.getSharedPreferences("curiosity_followups", Context.MODE_PRIVATE)

    fun offered(channel: String, promise: Promises.Promise): Boolean = prefs.contains(key(channel, promise))

    fun note(channel: String, promise: Promises.Promise, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val entries = prefs.all.entries.filter { (it.value as? Long)?.let { at ->
            now - at in 0..Promises.TERM_MS
        } == true }.sortedByDescending { it.value as Long }
        val retained = entries.take(MAX_MARKS - 1).mapTo(HashSet()) { it.key }
        val edit = prefs.edit()
        for (old in prefs.all.keys - retained) edit.remove(old)
        edit.putLong(key(channel, promise), now).apply()
    }

    private fun key(channel: String, promise: Promises.Promise): String {
        val input = "$channel\n${promise.at}\n${promise.sentence}"
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        val lock = Any()
        // Объявленный предел локальных отметок за срок доски.
        const val MAX_MARKS = 128
    }
}
