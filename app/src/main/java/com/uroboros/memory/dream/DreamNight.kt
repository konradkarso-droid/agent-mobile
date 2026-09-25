package com.uroboros.memory.dream

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Итог одной ночи сна: что проход увидел, кого пропустил, упёрся ли в потолок.
 *
 * ЗАЧЕМ ОТДЕЛЬНО ОТ СНОВ. Строка пишется на каждую ночь, в том числе на ночь без
 * единого сна. По одной таблице снов нельзя отличить «сон прошёл, связаться
 * было нечему» от «сна не было»: пустая ночь не оставляет там ни строки, и
 * последней ночью выглядела бы предыдущая, со своими снами. Здесь пустая ночь —
 * строка с нулём снов, а отсутствие строки значит, что прохода не было.
 *
 * Итог не хранится служебной строкой среди снов намеренно: всё, что читает
 * таблицу снов (показ, будущая подача модели), приняло бы её за сон.
 *
 * Все числа снимаются в момент прохода и потом не пересчитываются: записи с тех
 * пор могли остыть, скрыться или исчезнуть, а итог говорит о том, что было в ту
 * ночь. Смысл каждого числа — у одноимённого поля [DreamWeaver.Night].
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - ночь — это один проход, то есть одно долгое нажатие «Разбора памяти»; два
 *    нажатия за ночь дают две ночи;
 *  - проход, сорвавшийся на ошибке, строки не оставляет — о нём говорит только
 *    отчёт того прогона;
 *  - строки копятся и сами не удаляются, по одной на проход.
 *
 * КТО НАЧАЛ НОЧЬ ([startedBy]). Без этого поля ночь, в которую агент уснул сам,
 * на экране неотличима от ночи по кнопке — а проверить самостоятельный сон,
 * кроме как по этому различию, нечем. [of] его не заполняет: итог ночи
 * считается из плетения, а кто её начал, знает только проход (см.
 * [DreamRunner.run]).
 */
@Entity(tableName = "nights")
data class DreamNight(
    /** Начало прохода, мс. То же значение, что у снов этой ночи ([Dream.nightAt]). */
    @PrimaryKey val nightAt: Long,
    /** Сколько снов вошло в ночь. */
    val dreams: Int,
    val dreamers: Int,
    val dreamersCold: Int,
    val dreamersArchive: Int,
    val coldDreams: Int,
    val archiveDreams: Int,
    val skippedHidden: Int,
    val skippedQuestions: Int,
    val skippedAgentReports: Int,
    val ceilingHit: Boolean,
    /**
     * Имя значения [NightStart]; null — ночь прошла раньше, чем это стали
     * записывать. Значение по умолчанию описывает только такие старые строки:
     * выдумывать им начало нельзя, а новую ночь проход пишет всегда явно.
     */
    val startedBy: String? = null,
    /**
     * Река этой ночи: сколько вспомненных снов пришло притоками и сколько
     * снов реки из них сплелось. null у обоих — ночь прошла раньше, чем река
     * появилась: ноль здесь утверждал бы, что притоков не было, а это
     * неизвестно. Хранятся в строке ночи, а не пересчитываются: отметки
     * вспоминания потом меняются, и пересчёт задним числом врал бы.
     */
    val riverTributaries: Int? = null,
    val riverDreams: Int? = null,
    /**
     * Сколько записей из одних просьб не снилось (RiskTrigger.isOnlyRequests).
     * null — ночь прошла раньше, чем просьбы стали узнаваться: тогда они
     * снились наравне с утверждениями, и ноль здесь был бы неправдой.
     */
    val skippedRequests: Int? = null,
    /**
     * Номер записи, которая в эту ночь была лидером по касаниям без подсказки
     * и проходила условие лидера (см. [UnpromptedLeader]); null — такого
     * лидера не было, или сбой подсчёта (тогда о нём сказано в отчёте ночи),
     * или ночь прошла раньше, чем лидера стали записывать.
     *
     * Почему хранится в строке ночи, а не пересчитывается, — в KDoc
     * [UnpromptedLeader] («ПОЧЕМУ НОЧИ»).
     */
    val unpromptedLeaderId: Long? = null,
) {
    companion object {
        fun of(nightAt: Long, night: DreamWeaver.Night) = DreamNight(
            nightAt = nightAt,
            dreams = night.dreams.size,
            dreamers = night.dreamers,
            dreamersCold = night.dreamersCold,
            dreamersArchive = night.dreamersArchive,
            coldDreams = night.coldDreams,
            archiveDreams = night.archiveDreams,
            skippedHidden = night.skippedHidden,
            skippedQuestions = night.skippedQuestions,
            skippedRequests = night.skippedRequests,
            skippedAgentReports = night.skippedAgentReports,
            ceilingHit = night.ceilingHit,
        )
    }
}

/** Кто начал ночь. Хранится именем значения в [DreamNight.startedBy]. */
enum class NightStart {
    /** Агент уснул сам: давление сна и тишина. */
    SELF,

    /** Человек нажал «Разбор памяти». */
    BUTTON,
}
