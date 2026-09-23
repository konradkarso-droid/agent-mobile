package com.uroboros.memory.dream

import androidx.room.withTransaction
import com.uroboros.memory.HourglassMemory
import com.uroboros.memory.MemoryDatabase
import kotlinx.coroutines.CancellationException

/**
 * Один ночной проход сна: остудить слои, сплести сны, записать ночь, сказать
 * словами, что вышло.
 *
 * ПОЧЕМУ ОТДЕЛЬНО ОТ [DreamWeaver]. Сплетение — чистый счёт над списком записей,
 * его видно тестами насквозь. Здесь всё, что требует базы: чтение, запись,
 * порядок действий. Граница проведена так, чтобы дорогое место (правила, по
 * которым что-то снится) проверялось без Android.
 *
 * ПОРЯДОК. Остывание идёт до чтения, иначе запись, у которой срок вышел час
 * назад, сосчиталась бы горячей, и счётчики слоёв врали бы. Записываются ночь и
 * её сны одной транзакцией: ночь без своих снов и сны без своей ночи одинаково
 * читались бы как правда, будучи половиной работы.
 *
 * СОН НИКОГО НЕ ГРЕЕТ И НЕ ТРОГАЕТ ЗАПИСИ. Проход только читает их и пишет в
 * свои две таблицы. Приснившаяся холодная запись остаётся холодной: воспоминание
 * не делает старое нынешним.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - сорвавшийся проход не оставляет строки ночи: об этом говорит только отчёт
 *    того прогона. Пропуск восстановим — тот же материал сплетётся в следующую
 *    ночь, поэтому срыв сна не должен отнимать ночь у судьи;
 *  - вся память читается в память процесса целиком. При сотнях записей это
 *    ничто, при десятках тысяч придётся менять;
 *  - проход не знает, ночь сейчас или полдень: ночью его делает то, что человек
 *    запускает разбор на ночь.
 */
object DreamRunner {

    /**
     * Пройти ночь и вернуть готовые строки для отчёта. Исключений не выпускает,
     * кроме отмены: срыв сна — это строка в отчёте, а не сорванный прогон.
     */
    suspend fun run(
        db: MemoryDatabase,
        nightAt: Long = System.currentTimeMillis(),
    ): String {
        return try {
            val stickers = db.stickerDao()
            HourglassMemory(stickers).migrateExpired()
            val night = DreamWeaver.weave(stickers.getAll())
            val row = DreamNight.of(nightAt, night)
            val rows = night.dreams.map {
                Dream(
                    nightAt = nightAt,
                    recordIds = it.recordIds.joinToString(","),
                    kind = it.kind.name,
                )
            }
            db.withTransaction {
                db.dreamDao().insertNight(row)
                db.dreamDao().insertAll(rows)
            }
            describe(row)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            describeFailure(t)
        }
    }

    /**
     * Итог ночи словами. Строки с нулями не печатаются: постоянный нуль
     * перестают замечать, а эти числа нужны именно тогда, когда они не нули.
     */
    fun describe(row: DreamNight): String = buildString {
        append(if (row.dreams == 0) "Сон: ничего не приснилось." else "Сон: снов ${row.dreams}.")
        append("\n")
        // «Участвовало», а не «снилось»: прошли отбор сна, но связаться могли не
        // все. Сколько приснилось на деле, видно в разделе «Сны».
        append("Участвовало записей: ").append(row.dreamers)
        if (row.dreamersCold > 0) append(", из них холодных ").append(row.dreamersCold)
        if (row.dreamersArchive > 0) append(", из архива ").append(row.dreamersArchive)
        append("\n")
        val notDreamt = buildList {
            if (row.skippedHidden > 0) add("скрытых ${row.skippedHidden}")
            if (row.skippedQuestions > 0) add("вопросов ${row.skippedQuestions}")
            if (row.skippedAgentReports > 0) add("отчётов агента ${row.skippedAgentReports}")
        }
        if (notDreamt.isNotEmpty()) {
            append("Не снились: ").append(notDreamt.joinToString(", ")).append("\n")
        }
        if (row.coldDreams > 0 || row.archiveDreams > 0) {
            append("Снов со старым: ").append(row.coldDreams + row.archiveDreams)
            if (row.archiveDreams > 0) append(", из них с архивом ").append(row.archiveDreams)
            append("\n")
        }
        if (row.ceilingHit) {
            append("Потолок снов сработал — до длинных сюжетов дело не дошло.\n")
        }
    }.trimEnd()

    /**
     * Срыв прохода словами. Название класса ошибки остаётся: без него «что-то
     * пошло не так» не отличить от другой поломки.
     */
    fun describeFailure(t: Throwable): String =
        "Сон сорвался: ${t.javaClass.simpleName}: ${t.message ?: "без пояснения"}.\n" +
            "Ночь пропущена, тот же материал сплетётся в следующую."
}
