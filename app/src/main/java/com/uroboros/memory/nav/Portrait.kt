package com.uroboros.memory.nav

import com.uroboros.memory.Layer
import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.Coordinates.Address
import com.uroboros.util.TextFold

/**
 * Портрет собеседника — его собственные слова о себе, найденные по провенансу
 * (кто сказал = он), а не по словам вопроса.
 *
 * ЗАЧЕМ. На вопрос «Что знаешь обо мне?» общий отбор ищет по словам вопроса и
 * находит «знаешь», а не собеседника. Портрет ищет по тому, кто сказал:
 * все записи собеседника, из любого слоя, включая архив.
 *
 * КОГДА ИЩЕТСЯ: только на адрес вопроса «владелец» или «владелец и агент»
 * (Coordinates.questionAddress). На «агент» и «не определён» не ищется.
 *
 * КАКОЕ ПРЕДЛОЖЕНИЕ БЕРЁТСЯ. Запись режется на предложения (Sentences.split,
 * через PersonForm.of), и каждое проходит три проверки по порядку; первая
 * не пройденная — причина, по которой оно снято (её и считает прибор):
 *  1. ГРАНИЦА — нет обращения к агенту: ни второго лица («ты», «тебя»,
 *     «твой», глагол второго лица — «запомнишь»), ни «мы с тобой»
 *     (PersonForm.Person.SECOND, WE_WITH_YOU).
 *     Предложение, где собеседник говорит агенту о нём самом («Работаю над
 *     твоей памятью»), малая модель переворачивает и произносит как своё.
 *  2. УТВЕРЖДЕНИЕ — не вопрос (кончается на «?»), не просьба (начинается с
 *     повелительного, PersonForm.Person.IMPERATIVE), без «мы», чьё «мы» не
 *     ясно (PersonForm.Person.UNCLEAR).
 *  3. ЕСТЬ МЕСТОИМЕНИЕ ПЕРВОГО ЛИЦА — «я», «мой», «мне», «меня» во всех
 *     формах (PersonForm.FIRST_PRONOUNS). Именно местоимение, не глагол:
 *     PersonForm узнаёт первое лицо и по окончанию глагола, а окончание путает
 *     с существительными («смыслу», «деятельностью»). Местоимение отсекает
 *     заодно реплики момента, в живой речи его обычно опускают («Да, скоро
 *     ложусь», «Надеюсь, всё получится», «Хочу обсуждать погоду»).
 *
 * ПОРЯДОК — два рода мест, и разведены они намеренно:
 *  - первые [FRESH] мест — самые свежие записи, по одному предложению с
 *    каждой: люди меняются, свежее сказанное о себе актуальнее;
 *  - остальные — по касаниям (Sticker.userMatchCount: сколько раз запись
 *    совпала со словами реплик собеседника), при равенстве свежие выше:
 *    факт, к которому разговор возвращается, держится в портрете, даже когда
 *    он старый.
 *  Только свежесть вытеснила бы старые устойчивые факты шестёркой последних
 *  дней; только касания вытеснили бы всё недавно сказанное — у нового касаний
 *  ещё нет. Слой в порядок не входит: остывание портрет не чистит, реплики
 *  момента отсекает проверка 3.
 *
 * ПОРТРЕТ ЗАПИСИ НЕ ГРЕЕТ. Здесь только чтение: ни счёт обращений, ни слой не
 * меняются, ни за то, что предложение нашлось, ни за то, что агент его потом
 * повторит. Иначе эхо (модель повторяет ближайшую строку дословно) грело бы
 * реплики момента, и они не остывали бы никогда. Прогрев остаётся за обычным
 * отбором по словам вопроса: факт теплеет, когда разговор сам к нему
 * возвращается.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - факт без местоимения («Работаю по субботам», «Люблю рубанки») не
 *    берётся — промах в сторону пропуска, собеседник его перескажет;
 *  - границу держит PersonForm и с его ошибками: глагол второго лица, которого
 *    он не узнал, обращение к агенту не выдаёт;
 *  - короткие реплики с «я» («Я про сны.», «Да, я помню это.») проходят:
 *    отличить факт от реплики момента по форме здесь нечем;
 *  - «мы», сказанное о себе и других людях («мы в клубе»), снимается вместе с
 *    неясным «мы»;
 *  - время — когда сказано, а не когда это было правдой;
 *  - собеседник о себе в третьем лице («Админ работает…») не узнаётся;
 *  - касания считают и подсказанное: собеседник повторил тему, которую
 *    только что назвал агент, — касание засчитано. Чистый счёт
 *    (userMatchUnpromptedCount) на молодой памяти почти весь нулевой, опереться
 *    на него пока нельзя.
 *
 * ЧИСЛА [LIMIT], [PER_RECORD] и [FRESH] объявлены, не измерены. Проверены на ходах
 * одного владельца с памятью меньше месяца; перепроверять по прибору
 * «О собеседнике:», когда портрет пойдёт модели.
 *
 * Чистый объект: ни базы, ни Android.
 */
object Portrait {

    /** Сколько предложений портрета идёт модели — не больше. */
    const val LIMIT = 6

    /** Сколько предложений одной записи идёт модели — не больше. */
    const val PER_RECORD = 2

    /** Сколько мест отдано самым свежим записям (по предложению с каждой). */
    const val FRESH = 3

    /**
     * Предложение портрета. [fromArchive] — запись лежит в фиолетовом слое;
     * [touches] — касания записи (Sticker.userMatchCount).
     */
    data class Line(
        val recordId: Long,
        val sentence: String,
        val createdAt: Long,
        val fromArchive: Boolean,
        val touches: Int = 0,
    )

    /**
     * Итог поиска: сколько записей собеседника и предложений в них
     * просмотрено, сколько снято каждой проверкой, что прошло (все, свежие
     * первыми) и что из прошедшего идёт модели ([chosen], порядок — в
     * описании объекта, в пределах [LIMIT] и [PER_RECORD]).
     */
    data class Result(
        val records: Int,
        val sentences: Int,
        val toAgent: Int,
        val notStatement: Int,
        val noFirstPronoun: Int,
        val passed: List<Line>,
        val chosen: List<Line>,
    )

    /** Ищется ли портрет на вопрос с этим адресом. */
    fun searched(address: Address): Boolean = address == Address.OWNER || address == Address.BOTH

    /**
     * Портрет собеседника [person] по записям памяти. Записи, ждущие проверки
     * и отвергнутые, не смотрятся — как в облаке (MainActivity.cloudSources).
     */
    fun of(records: List<Sticker>, person: PersonKey = PersonKey.OWNER): Result {
        val own = records
            .filter { Coordinates.speakerOf(it.source) == person && !it.reviewPending && it.rejectedAt == null }
            .sortedByDescending { it.createdAt }
        var sentences = 0
        var toAgent = 0
        var notStatement = 0
        var noFirst = 0
        val passed = ArrayList<Line>()
        for (record in own) {
            for (s in PersonForm.of(record.content).sentences) {
                sentences++
                val p = s.persons
                when {
                    PersonForm.Person.SECOND in p || PersonForm.Person.WE_WITH_YOU in p -> toAgent++
                    s.sentence.trimEnd().endsWith('?') ||
                        PersonForm.Person.IMPERATIVE in p ||
                        PersonForm.Person.UNCLEAR in p -> notStatement++
                    !hasFirstPronoun(s.sentence) -> noFirst++
                    else -> passed += Line(
                        record.id, s.sentence, record.createdAt,
                        fromArchive = record.layer == Layer.PURPLE.name,
                        touches = record.userMatchCount,
                    )
                }
            }
        }
        return Result(own.size, sentences, toAgent, notStatement, noFirst, passed, choose(passed))
    }

    /**
     * [FRESH] свежих записей по предложению, затем остальное по касаниям;
     * не больше [PER_RECORD] с записи и [LIMIT] всего. [passed] — свежие
     * первыми; сортировка устойчивая, поэтому при равных касаниях свежие
     * остаются выше.
     */
    private fun choose(passed: List<Line>): List<Line> {
        val fresh = passed.distinctBy { it.recordId }.take(FRESH)
        val rest = passed.filter { it !in fresh }.sortedByDescending { it.touches }
        val perRecord = HashMap<Long, Int>()
        val out = ArrayList<Line>()
        for (line in fresh + rest) {
            if (out.size >= LIMIT) break
            val n = perRecord[line.recordId] ?: 0
            if (n >= PER_RECORD) continue
            perRecord[line.recordId] = n + 1
            out += line
        }
        return out
    }

    // Слова — как их видит PersonForm: приведённые (TextFold), по буквам.
    private val NOT_LETTER = Regex("[^\\p{L}]+")

    private fun hasFirstPronoun(sentence: String): Boolean =
        TextFold.fold(sentence).split(NOT_LETTER).any { it in PersonForm.FIRST_PRONOUNS }

    /**
     * Строка прибора «О собеседнике:». Печатается всегда. [result] — null,
     * если на этом адресе портрет не ищется; [failure] — имя сбоя, если
     * поиск упал (ход при этом идёт). [fed] — поданы ли строки модели.
     */
    fun meterLine(address: Address, result: Result?, fed: Boolean, failure: String? = null): String {
        val head = "адрес — ${Coordinates.addressLabel(address)}"
        if (!searched(address)) return "$head · не ищется"
        if (failure != null) return "$head · не посчитался — $failure"
        if (result == null) return "$head · не посчитался"
        val archive = result.chosen.count { it.fromArchive }
        return "$head · его записей ${result.records}, предложений ${result.sentences}" +
            " · снято: обращение к агенту ${result.toAgent}, вопрос/просьба/«мы» ${result.notStatement}," +
            " без «я/мой» ${result.noFirstPronoun}" +
            " · прошло ${result.passed.size}, в портрет ${result.chosen.size} (из архива $archive)" +
            (if (fed) " · подано модели" else " · модели не подано — только прибор") +
            " · не грелось"
    }
}
