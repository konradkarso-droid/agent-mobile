package com.uroboros.memory.nav

import com.uroboros.memory.Sticker

/**
 * Как собеседник велел себя называть — по его собственным записям.
 *
 * Имя не настройка, а запись: собеседник сказал его в разговоре или сохранил
 * кнопкой, и у записи есть провенанс (кто сказал, когда, сколько раз). Отдельно
 * имя не хранится — ищется при чтении по тем же записям, что портрет
 * ([Portrait.of]), поэтому новой метки и переделки базы нет. Действует
 * последнее по времени заявление; прежние остаются в памяти историей.
 *
 * ОБОРОТЫ — УЗКИЙ ОБЪЯВЛЕННЫЙ СПИСОК, а не угадывание. Предложение целиком
 * должно быть одним из:
 *  - «Зови меня X» (и «Зовите меня X»);
 *  - «Меня зовут X»;
 *  - «Моё имя X» (и «Мое имя», с тире или без).
 * X — одно или два слова, каждое с заглавной буквы, можно в кавычках; после X
 * предложение кончается (точка, «!», «…», скобка-улыбка).
 *
 * ПОЧЕМУ ТАК УЗКО. Ложное имя ниже никто не отсеет: оно уходит модели на каждом
 * ходе о собеседнике. Пропуск восстановим — на экране «не задано», и человек
 * скажет ещё раз. Поэтому сомнение решается в сторону «нет заявления». По-русски
 * «меня зовут» значит и «меня приглашают» («Меня зовут на день рождения»), а
 * «зови меня» — «позови» («Зови меня, если что»): заглавная буква и конец
 * предложения сразу после имени отсекают оба случая.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - другие обороты («Можешь звать меня…», «Называй меня…») не ловит.
 *    «Называй» сознательно вне списка: после него имя обычно в творительном
 *    («Называй меня Кэпом»), а голова портрета ставит имя в именительный;
 *  - творительный падеж и после «зови» («Зови меня Кэпом») возьмёт как есть;
 *  - имя строчными («зови меня кэп») не возьмёт;
 *  - «Тебя зовут…» здесь не ловится намеренно: имя агента растёт из его
 *    опыта, а не назначается фразой;
 *  - личность не доказывает: кто пишет, знает только замок приложения;
 *  - смотрит записи одного собеседника — того, чей [PersonKey] передан.
 *
 * Чистый объект: ни базы, ни Android.
 */
object NameClaim {

    /** Найденное имя: что сказано, в какой записи, когда и сколько раз. */
    data class Found(val name: String, val recordId: Long, val at: Long, val times: Int)

    /** Итог поиска: просмотрено записей собеседника [records], найдено [found]. */
    data class Result(val records: Int, val found: Found?)

    private const val NAME_WORD = "[\"«„]?\\p{Lu}[\\p{L}-]*[\"»“]?"
    private const val NAME = "($NAME_WORD(?:\\s+$NAME_WORD)?)"
    private const val TAIL = "\\s*[.!…)]*\\s*"

    // Без IGNORE_CASE: регистр первой буквы оборота задан явно, а у имени
    // заглавная обязательна — флаг регистра снял бы это требование с \p{Lu}.
    private val FORMS = listOf(
        Regex("^[Зз]ови(?:те)?\\s+меня\\s+$NAME$TAIL$"),
        Regex("^[Мм]еня\\s+зовут\\s+$NAME$TAIL$"),
        Regex("^[Мм]о[её]\\s+имя\\s*(?:[—–-]\\s*)?$NAME$TAIL$"),
    )

    private val QUOTES = Regex("[\"«»„“]")

    /** Имя, заявленное в одном предложении, или null. */
    fun claimed(sentence: String): String? {
        val s = sentence.trim()
        for (form in FORMS) {
            val m = form.find(s) ?: continue
            val raw = m.groupValues[1]
            val words = raw.replace(QUOTES, "").split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            return words.joinToString(" ")
        }
        return null
    }

    /**
     * Последнее заявление имени собеседника [person] в его записях. Записи,
     * ждущие проверки и отвергнутые, не смотрятся — как в портрете.
     */
    fun of(records: List<Sticker>, person: PersonKey = PersonKey.OWNER): Result {
        val own = records.filter {
            Coordinates.speakerOf(it.source) == person && !it.reviewPending && it.rejectedAt == null
        }
        val claims = own.mapNotNull { r ->
            PersonForm.of(r.content).sentences.firstNotNullOfOrNull { claimed(it.sentence) }?.let { r to it }
        }
        val last = claims.maxByOrNull { it.first.createdAt } ?: return Result(own.size, null)
        val times = claims.count { it.second.equals(last.second, ignoreCase = true) }
        return Result(own.size, Found(last.second, last.first.id, last.first.createdAt, times))
    }

    /**
     * Строка прибора. [age] — давность записи словами (ProvenanceLabels).
     * [fed] — ушли ли модели строки портрета: имя идёт только в их подписи
     * (Portrait.header), без строк найденное имя модели не подаётся.
     */
    fun meterLine(result: Result?, age: (Long) -> String, fed: Boolean = true, failure: String? = null): String = when {
        failure != null -> "Имя собеседника: поиск не выполнен ($failure)"
        result == null -> "Имя собеседника: не искалось — вопрос не о собеседнике"
        result.found == null -> "Имя собеседника: не задано — заявлений 0, просмотрено записей ${result.records}"
        else -> "Имя собеседника: ${result.found.name} — с его слов, ${age(result.found.at)}" +
            (if (result.found.times > 1) ", заявлено раз: ${result.found.times}" else "") +
            " · запись №${result.found.recordId}" +
            (if (fed) "" else " · модели не ушло: строк портрета нет")
    }
}
