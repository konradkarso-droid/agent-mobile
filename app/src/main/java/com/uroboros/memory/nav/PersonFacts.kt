package com.uroboros.memory.nav

import com.uroboros.memory.Sticker

/**
 * Факты о собеседнике — ночная отметка «факт / момент» у строк его портрета
 * (Portrait). Чистая часть: что размечать, как спросить модель, как прочесть
 * ответ, строки прибора. Базу и модель зовёт dream.FactMarkStep.
 *
 * ЗАЧЕМ. Память хранит реплики, а не знания: среди строк «я о себе»
 * большинство — реплики момента («теперь я понял, спасибо»), и выбор
 * портрета по свежести и касаниям подавал их вместо фактов («по профессии я
 * химик» лежал в холоде и не подавался ни разу). Отметка ставит факты первыми
 * (Portrait, «ПОРЯДОК»).
 *
 * ЧТО ХРАНИТСЯ, А ЧТО СЧИТАЕТСЯ. Связь «собеседник — его запись» не хранится:
 * она считается из ключа говорящего (Coordinates.speakerOf). Хранится только
 * то, что не выводится, — отметка модели у предложения записи
 * (dream.FactMarkRow). Ключ отметки — номер записи и текст предложения
 * ([Key]): правка правила разбивки на предложения оставит прежние отметки без
 * строк, и такие строки разметятся заново.
 *
 * МОДЕЛЬ ТОЛЬКО ОТМЕЧАЕТ. Текст записи никто не переписывает: модель отвечает
 * одним словом о готовой фразе. Поэтому путать лица ей негде — в пересказе
 * она путала, кто что сделал.
 *
 * ПОЧЕМУ НЕ ПО ВЕСУ ОБЛАКА. Облако (Clouds) остывает за неделю и держит то,
 * о чём человек говорит сейчас, а не то, кто он: порядок фактов по весу
 * облака поднимал наверх повторы имени и опускал работу и увлечения. Облако
 * остаётся прибором и источником слов-тем.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - отметка — мнение модели о форме фразы, а не проверка правды; модель
 *    ошибается в обе стороны (момент назван фактом, факт — моментом).
 *    Строка, названная моментом, не пропадает: она в портрете на общем месте;
 *  - устаревший факт не снимает: «работаю по субботам» остаётся фактом, пока
 *    запись жива;
 *  - видит только строки портрета: факт без «я» («Работаю по субботам»)
 *    портрет не берёт, и размечать его нечего;
 *  - отметка однажды — навсегда: другая модель старые отметки не пересматривает;
 *  - ответ, где нет ни «факт», ни «момент», хранится как непонятный и не
 *    переспрашивается — иначе одна такая строка забирала бы место каждой ночью.
 */
object PersonFacts {

    /** Ключ отметки: предложение записи. */
    data class Key(val recordId: Long, val sentence: String)

    /** Что ответила модель. */
    enum class Verdict { FACT, MOMENT, UNCLEAR }

    /**
     * Сколько строк размечать за одну ночь. Объявленное число, не подобранное:
     * на стенде около 6 с на строку — заход в пару минут; остальное — в
     * следующие ночи, свежие первыми.
     */
    const val PER_NIGHT = 30

    /** Потолок ответа: одно слово. */
    const val ANSWER_TOKENS = 6

    /**
     * Вопрос модели. Подобран стендом на модели телефона (Falcon-H1 7B) на 94
     * строках портрета: три прогона давали один и тот же ответ. Для другой
     * модели перемерять.
     */
    const val SYSTEM =
        "Ты помогаешь вести карточку фактов о человеке. Тебе дают одну фразу, которую человек сказал о себе. " +
            "Реши: это устойчивый факт о нём — имя, прозвище, работа, профессия, увлечения, привычки, черты, предпочтения, — " +
            "который будет верен и через месяц? Или это реплика момента: настроение, реакция в разговоре, " +
            "что он сейчас делает, чужие слова? Ответь одним словом: «факт» или «момент»."

    fun request(sentence: String): String = "Фраза: «$sentence»"

    /** Ответ модели словом. «момент» проверяется вторым: «не факт, а момент» — момент. */
    fun parse(answer: String): Verdict {
        val a = answer.lowercase()
        return when {
            "момент" in a -> Verdict.MOMENT
            "факт" in a -> Verdict.FACT
            else -> Verdict.UNCLEAR
        }
    }

    /**
     * Чьи портреты размечать: владелец и каждый, у кого есть записи с ключом
     * из канала (кроме ключа владельца [ownerKey] — это он же).
     */
    fun persons(records: List<Sticker>, ownerKey: String?): List<PersonKey> =
        listOf(PersonKey.OWNER) + records.mapNotNull { it.speaker }.filter { it != ownerKey }.distinct().map { PersonKey(it) }

    /**
     * Что размечать этой ночью: неразмеченные строки портретов всех [persons],
     * свежие первыми, не больше [limit]. [marked] — уже размеченные ключи.
     */
    fun toMark(
        records: List<Sticker>,
        ownerKey: String?,
        marked: Set<Key>,
        limit: Int = PER_NIGHT,
    ): Pending {
        val all = persons(records, ownerKey).flatMap { person ->
            Portrait.of(records, person, ownerKey = ownerKey).passed.map { Key(it.recordId, it.sentence) to it.createdAt }
        }.distinctBy { it.first }.filter { it.first !in marked }
        return Pending(all.sortedByDescending { it.second }.take(limit).map { it.first }, all.size)
    }

    /** [next] — что размечать сейчас; [total] — сколько всего не размечено. */
    data class Pending(val next: List<Key>, val total: Int)

    const val OUTCOME_HEAD = "Факты: "

    /** Итог ночного шага. [stoppedBy] — почему остановился раньше. */
    fun outcome(facts: Int, moments: Int, unclear: Int, left: Int, stoppedBy: String?): String {
        val done = facts + moments + unclear
        if (done == 0 && stoppedBy != null) return "${OUTCOME_HEAD}не размечал — $stoppedBy"
        if (done == 0) return "${OUTCOME_HEAD}размечать нечего — все строки портретов размечены"
        val unclearPart = if (unclear > 0) ", непонятных $unclear" else ""
        val stop = stoppedBy?.let { " · остановился: $it" } ?: ""
        return "${OUTCOME_HEAD}размечено $done (фактов $facts, моментов $moments$unclearPart), осталось $left$stop"
    }

    /** Часть строки «О собеседнике:» — как размечен портрет. */
    fun marksLine(result: Portrait.Result): String {
        val facts = result.passed.count { it.fact == true }
        val unmarked = result.passed.count { it.fact == null }
        val chosenFacts = result.chosen.count { it.fact == true }
        val stale = if (result.outdated > 0) ", устаревших ${result.outdated}" else ""
        return "фактов $facts, не размечено $unmarked, в портрете фактов $chosenFacts$stale"
    }

    /** Отметка строки для показа: «факт», «момент», «не размечена». */
    fun label(line: Portrait.Line): String = when (line.fact) {
        true -> "факт"
        false -> "момент"
        null -> "не размечена"
    }
}
