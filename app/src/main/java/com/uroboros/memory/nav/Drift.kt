package com.uroboros.memory.nav

import com.uroboros.util.TextFold
import java.util.Locale
import kotlin.math.abs

/**
 * Поправка на дрейф: показание тела, сказанное агентом раньше, поднятое
 * навигацией сейчас. Чистая логика.
 *
 * ЗАЧЕМ. Заряд, температура, время «сейчас» верны только в момент, когда
 * сказаны. Своя речь из архива (OwnSpeech) приходит модели с подписью
 * времени («сегодня ночью»), но без точки отправления — того «сейчас», от
 * которого подпись посчитана. Сравнить модели не с чем, и старое число она
 * берёт как нынешнее. Здесь смещение считает код и называет его словами:
 * «С тех пор прошло 3 ч 51 мин; заряд сейчас 64% (было 81%, −17)». Вычитать
 * модели ничего не надо.
 *
 * САМО ПРЕДЛОЖЕНИЕ НЕ МЕНЯЕТСЯ: слова остаются, как сказаны, поправка стоит
 * рядом с ними.
 *
 * ТОЛЬКО СВОЯ РЕЧЬ. Показание в записи владельца — другое тело («у меня на
 * телефоне 20%»), и поправка его заряда нынешним зарядом агента была бы
 * ложью. Своя речь — агента по построению, предложения о владельце из неё
 * убраны (OwnSpeech.said).
 *
 * ЧТО СЧИТАЕТСЯ ПОКАЗАНИЕМ — объявленная граница, по полям приборов агента:
 *  - заряд: число с «%» или «процент…» и слово «заряд», «батаре…» или
 *    «аккумулятор…» в том же предложении;
 *  - температура: число с «°» или «градус…»;
 *  - время: «ЧЧ:ММ» и слово «сейчас» в том же предложении. Без «сейчас»
 *    время — момент события («очнулся в 15:42»), а не показание.
 * Число с процентом без слова заряда («лента заполнена на 40%», «уверен на
 * 80%») показанием тела не считается.
 *
 * СТРОГО, ПОТОМУ ЧТО НИЖЕ НИКТО НЕ ОТСЕЕТ. Поправка идёт модели прямо.
 * Неоднозначно — два числа одного поля в предложении, нынешнее значение
 * прибора не известно — число не правится, называется только прошедшее
 * время. Поправить неверно хуже, чем не поправить: не поправленное остаётся
 * как было до этого механизма.
 *
 * ПОПРАВКА ВЕРНА ТОЛЬКО В СВОЁМ ХОДЕ. Ход хранит её дословно, но модели
 * записи прошлых ходов не подаются (см. ConversationJournal.messagesFor):
 * застывшая поправка стареет так же, как само показание. Строка своей речи,
 * снова выбранная отбором, уходит с поправкой, посчитанной заново.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - числа словами («восемьдесят процентов») не видит. Готовый разбор чисел
 *    (RiskTrigger) сюда не годится: составное числительное он даёт двумя
 *    числами и не знает, где число стоит, а единицу надо найти рядом с ним;
 *  - не знает, заряжался ли телефон между двумя точками: даёт разницу, а не
 *    скорость разряда;
 *  - у старого хода без своего времени момент — «не позже закрытия
 *    разговора» (Coordinates.turnTime), и прошедшее время тогда занижено;
 *  - «Сейчас» внутри старой цитаты не трогает — это слова агента;
 *  - полей, которых нет у приборов (скорость, память, лента), не правит;
 *  - показание без числа («я не перегреваюсь») не видит.
 */
object Drift {

    /** Точка отправления: момент хода и показания приборов в нём; null — не известно. */
    data class Origin(
        val at: Long,
        val chargePercent: Int?,
        val charging: Boolean,
        val temperatureC: Double?,
        val clock: String,
    )

    /**
     * Поправка к одному предложению.
     *
     * @property forModel хвост строки для модели — одно или два предложения с
     *   пробелом впереди; пусто — поправить нечего, кроме уже сказанного в
     *   подписи.
     * @property meter то же для прибора «Дрейф:».
     */
    data class Correction(val forModel: String, val meter: String)

    /**
     * Поправка к предложению [sentence], сказанному в [said] (null — момент не
     * известен), от точки [origin]. null — в предложении нет ни одного
     * показания, поправлять нечего.
     */
    fun of(sentence: String, said: Long?, origin: Origin): Correction? {
        val text = TextFold.fold(sentence)
        val charges = PERCENT.findAll(text).map { it.groupValues[1].toInt() }.toList()
        val temps = DEGREES.findAll(text).map { it.groupValues[1].replace(',', '.').toDouble() }.toList()
        val clocks = CLOCK.findAll(text).toList()
        val chargeField = charges.isNotEmpty() && CHARGE_WORDS.any { it in text }
        val timeField = clocks.isNotEmpty() && NOW_WORD.containsMatchIn(text)
        if (!chargeField && temps.isEmpty() && !timeField) return null

        val parts = mutableListOf<String>()
        val meters = mutableListOf<String>()
        val unsure = mutableListOf<String>()
        if (chargeField) {
            val now = origin.chargePercent
            when {
                charges.size > 1 -> unsure += "заряд: чисел ${charges.size}"
                now == null -> unsure += "заряд: прибор не знает"
                else -> {
                    val was = charges.single()
                    val plug = if (origin.charging) ", заряжается" else ""
                    parts += "заряд сейчас $now% (было $was%, ${signed(now - was)}$plug)"
                    meters += "заряд $was→$now"
                }
            }
        }
        if (temps.isNotEmpty()) {
            val now = origin.temperatureC
            when {
                temps.size > 1 -> unsure += "температура: чисел ${temps.size}"
                now == null -> unsure += "температура: прибор не знает"
                else -> {
                    val was = temps.single()
                    parts += "температура сейчас ${one(now)}°C (было ${one(was)}°C, ${signedOne(now - was)})"
                    meters += "температура ${one(was)}→${one(now)}"
                }
            }
        }
        if (timeField) {
            if (clocks.size > 1) {
                unsure += "время: чисел ${clocks.size}"
            } else {
                parts += "время сейчас ${origin.clock}"
                meters += "время ${clocks.single().value}→${origin.clock}"
            }
        }

        val passed = said?.let { origin.at - it }?.takeIf { it >= 0 }?.let(::duration)
        val head = passed?.let { "С тех пор прошло $it" }
        val body = parts.joinToString("; ")
        val forModel = when {
            head != null && body.isNotEmpty() -> " $head; $body."
            head != null -> " $head."
            body.isNotEmpty() -> " ${body.replaceFirstChar { it.uppercase() }}."
            else -> ""
        }
        val meter = buildList {
            if (meters.isNotEmpty()) add(meters.joinToString(", "))
            if (unsure.isNotEmpty()) add("не поправлено (${unsure.joinToString("; ")})")
            add(passed?.let { "прошло $it" } ?: "сколько прошло — не известно")
        }.joinToString(", ")
        return Correction(forModel, meter)
    }

    /** Строка прибора «Дрейф:» хода по поправкам строк своей речи, поданных модели. */
    fun meterLine(ownSeated: Int, corrections: List<Pair<String, Correction>>): String = when {
        ownSeated == 0 -> "Дрейф: своя речь в этот ход не поднималась"
        corrections.isEmpty() -> "Дрейф: показаний в своей речи нет (строк: $ownSeated)"
        else -> "Дрейф: " + corrections.joinToString(" · ") { (sentence, c) ->
            "«${sentence.take(METER_QUOTE)}${if (sentence.length > METER_QUOTE) "…" else ""}» — ${c.meter}"
        }
    }

    /** «3 ч 51 мин», «2 дн 3 ч», «меньше минуты». */
    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        if (minutes < 1) return "меньше минуты"
        val days = minutes / (24 * 60)
        val hours = (minutes / 60) % 24
        val mins = minutes % 60
        return when {
            days > 0 -> listOfNotNull("$days дн", if (hours > 0) "$hours ч" else null).joinToString(" ")
            hours > 0 -> listOfNotNull("$hours ч", if (mins > 0) "$mins мин" else null).joinToString(" ")
            else -> "$mins мин"
        }
    }

    private fun signed(d: Int) = if (d > 0) "+$d" else if (d < 0) "−${-d}" else "без перемен"
    private fun signedOne(d: Double) = when {
        abs(d) < 0.05 -> "без перемен"
        d > 0 -> "+${one(d)}"
        else -> "−${one(-d)}"
    }
    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)

    private const val METER_QUOTE = 40

    /** Число с процентом: «81%», «81 %», «81 процент», «81 процента». */
    private val PERCENT = Regex("(?<![\\d.,])(\\d{1,3})\\s*(?:%|процент)")

    /** Число с градусом: «33°», «33.0°C», «33,5 градуса». */
    private val DEGREES = Regex("(?<![\\d.,])(\\d{1,2}(?:[.,]\\d)?)\\s*(?:°|градус)")

    /** Время «ЧЧ:ММ». */
    private val CLOCK = Regex("(?<!\\d)(?:[01]?\\d|2[0-3]):[0-5]\\d(?!\\d)")

    private val NOW_WORD = Regex("(?<!\\p{L})сейчас(?!\\p{L})")

    private val CHARGE_WORDS = listOf("заряд", "батаре", "аккумулятор")
}
