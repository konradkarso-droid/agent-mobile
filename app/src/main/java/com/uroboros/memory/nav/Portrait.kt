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
 * КАКАЯ ЗАПИСЬ НЕ СМОТРИТСЯ ВОВСЕ. Запись в [COLUMN_LINES] и больше непустых
 * строк — текст столбиком: стихи, песня, вставленный чужой текст. «Я» в нём —
 * лицо текста, а не собеседника («Молча сижу под окошком темницы»), и пересказ
 * подал бы его модели как сказанное собеседником о себе. Сняты все её
 * предложения; прибор считает их отдельно («столбиком»).
 *
 * КАКОЕ ПРЕДЛОЖЕНИЕ БЕРЁТСЯ. Запись режется на предложения (Sentences.split,
 * через PersonForm.of), и каждое проходит четыре проверки по порядку; первая
 * не пройденная — причина, по которой оно снято (её и считает прибор):
 *  1. ГРАНИЦА — нет обращения к агенту (MirrorFilter.addressesAgent — тот
 *     же признак, что у общего отбора) и нет «мы с тобой»: портрет — о нём
 *     самом, а не о них двоих. Предложение, где собеседник говорит агенту о
 *     нём самом («Работаю над твоей памятью»), малая модель переворачивает и
 *     произносит как своё.
 *  2. УТВЕРЖДЕНИЕ — не вопрос (кончается на «?»), не просьба (начинается с
 *     повелительного, PersonForm.Person.IMPERATIVE), без «мы», чьё «мы» не
 *     ясно (PersonForm.Person.UNCLEAR).
 *  3. ЕСТЬ МЕСТОИМЕНИЕ ПЕРВОГО ЛИЦА — «я», «мой», «мне», «меня» во всех
 *     формах (PersonForm.FIRST_PRONOUNS). Именно местоимение, не глагол:
 *     PersonForm узнаёт первое лицо и по окончанию глагола, а окончание путает
 *     с существительными («смыслу», «деятельностью»). Местоимение отсекает
 *     заодно реплики момента, в живой речи его обычно опускают («Да, скоро
 *     ложусь», «Надеюсь, всё получится», «Хочу обсуждать погоду»).
 *  4. НЕ ЗАМЕЧАНИЕ О РАЗГОВОРЕ — «Я спросил кто я, а не что я говорил» не
 *     о собеседнике, а о ходе разговора (TalkRemark: признаки, порог и чего
 *     он не умеет — там).
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
 *  - цитата, стих или песня одной-тремя строками или прозой не узнаются —
 *    признак только форма столбика, не смысл; и наоборот, рассказ о себе,
 *    набранный столбиком в [COLUMN_LINES] строк и больше, снимается целиком;
 *  - замечание о самом разговоре узнаётся с промахами TalkRemark (там же
 *    перечислены);
 *  - касания считают и подсказанное: собеседник повторил тему, которую
 *    только что назвал агент, — касание засчитано. Чистый счёт
 *    (userMatchUnpromptedCount) на молодой памяти почти весь нулевой, опереться
 *    на него пока нельзя.
 *
 * КАК ИДЁТ МОДЕЛИ. Пересказом со стороны агента («С твоих слов …: ты
 * работаешь по субботам» — ProvenanceLabels.retoldForModel, разворот
 * Retelling по таблице RetellHolder.loaded), под подписью ([header]), первым
 * абзацем записей хода; строки общего отбора — следующим абзацем ([recall]).
 * Цитатой («Твои слова: «Я работаю…»») малая модель переворачивает «я»
 * собеседника в своё — пересказ поворачивает лицо кодом. Таблица не
 * загружена — портрет модели не подаётся, прибор говорит почему.
 * Запись, попавшая в портрет, из общего отбора того же хода не подаётся:
 * одна запись дважды — цитатой и пересказом — только путает.
 *
 * ЧИСЛА [LIMIT], [PER_RECORD], [FRESH] и [COLUMN_LINES] объявлены, не измерены. Проверены на ходах
 * одного владельца с памятью меньше месяца; перепроверять по прибору
 * «О собеседнике:».
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
     * С какого числа непустых строк запись считается текстом столбиком и в
     * портрет не смотрится. Короткий ответ в две-три строки так не снимается.
     */
    const val COLUMN_LINES = 4

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
     * просмотрено, сколько снято каждой проверкой ([inColumn] — предложения
     * записей столбиком, [COLUMN_LINES]), что прошло (все, свежие
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
        val inColumn: Int = 0,
        /** Снято проверкой 4 — замечание о разговоре ([TalkRemark]). */
        val aboutTalk: Int = 0,
    )

    /** Подпись над строками портрета в записях хода, когда имя не задано. */
    const val HEADER = "О собеседнике я знаю только это:"

    /**
     * Подпись над строками портрета, когда собеседник назвал себя
     * ([NameClaim]): «Ты — Кэп, мой создатель. О тебе я знаю только это:».
     *
     * ПОЧЕМУ ТАКАЯ. Подобрана стендом на малой модели (3B) на ходах «Что
     * знаешь обо мне?» и «Кто я?»; для другой модели перемерять. Имя во втором лице
     * чинит «Кто я?» (без него — «нет информации о вашем имени»). Слово
     * «создатель» держит строки ниже как сведения: без него подпись читается
     * готовым ответом, и модель переписывает её вместе со строками. Цена слова —
     * изредка путаница ролей («создатель устройства»). Штампы «С твоих слов …»
     * на каждой строке остаются: без них модель перестаёт считать строки
     * сказанным собеседником о себе, и одна общая отметка их не заменяет.
     *
     * ГРАНИЦА «СОЗДАТЕЛЬ = ВЛАДЕЛЕЦ» ОБЪЯВЛЕНА, А НЕ УСТАНОВЛЕНА. Подпись
     * ставится на портрет владельца, и создателем названо его лицо. Это верно,
     * пока устройством владеет тот, кто растил агента. Различить создателя и
     * нового владельца (передали телефон) пока нечем: записи обоих — записи
     * владельца. Когда появится такое различение, «мой создатель» ставится
     * только создателю.
     */
    fun header(name: String?): String =
        if (name == null) HEADER else "Ты — $name, мой создатель. О тебе я знаю только это:"

    /**
     * Строка вместо портрета, когда ни одно предложение собеседника о себе не
     * прошло: модель говорит «знаю мало» и может спросить, а не заполняет
     * пустоту строками стены о самом агенте.
     */
    const val NOTHING = "О собеседнике я пока ничего не знаю с его слов."

    /**
     * Сообщение с записями хода (голосом агента): портрет под [HEADER], пустая
     * строка, остальные записи. [lines] — все записи хода в порядке подачи
     * (записи прошлых ходов модели не подаются, см.
     * ConversationJournal.messagesFor); из них портретные — те, что есть в
     * [portraitLines]. Подпись ставится, только если строки портрета есть.
     * [nothing] — портрет искался и пуст: вместо него [NOTHING].
     * [name] — как собеседник велел себя называть ([NameClaim]); подпись
     * тогда [header] с именем. Без строк портрета имя не подаётся.
     * null — сообщения не будет.
     */
    fun recall(lines: List<String>, portraitLines: Set<String>, nothing: Boolean, name: String? = null): String? {
        val (portrait, rest) = lines.partition { it in portraitLines }
        val parts = ArrayList<String>()
        when {
            portrait.isNotEmpty() -> parts += (listOf(header(name)) + portrait).joinToString("\n")
            nothing -> parts += NOTHING
        }
        if (rest.isNotEmpty()) parts += rest.joinToString("\n")
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

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
        var inColumn = 0
        var aboutTalk = 0
        val passed = ArrayList<Line>()
        for (record in own) {
            val column = inColumn(record.content)
            for (s in PersonForm.of(record.content).sentences) {
                sentences++
                val p = s.persons
                when {
                    column -> inColumn++
                    MirrorFilter.addressesAgent(s) || PersonForm.Person.WE_WITH_YOU in p -> toAgent++
                    s.sentence.trimEnd().endsWith('?') ||
                        PersonForm.Person.IMPERATIVE in p ||
                        PersonForm.Person.UNCLEAR in p -> notStatement++
                    !hasFirstPronoun(s.sentence) -> noFirst++
                    TalkRemark.isRemark(s.sentence) -> aboutTalk++
                    else -> passed += Line(
                        record.id, s.sentence, record.createdAt,
                        fromArchive = record.layer == Layer.PURPLE.name,
                        touches = record.userMatchCount,
                    )
                }
            }
        }
        return Result(own.size, sentences, toAgent, notStatement, noFirst, passed, choose(passed), inColumn, aboutTalk)
    }

    /** Запись — текст столбиком (описание объекта, [COLUMN_LINES]). */
    fun inColumn(text: String): Boolean = text.lines().count { it.isNotBlank() } >= COLUMN_LINES

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
     * поиск упал (ход при этом идёт). [fed] — поданы ли строки модели;
     * [whyNotFed] — почему нет, если причина известна.
     */
    fun meterLine(
        address: Address,
        result: Result?,
        fed: Boolean,
        failure: String? = null,
        whyNotFed: String? = null,
    ): String {
        val head = "адрес — ${Coordinates.addressLabel(address)}"
        if (!searched(address)) return "$head · не ищется"
        if (failure != null) return "$head · не посчитался — $failure"
        if (result == null) return "$head · не посчитался"
        val archive = result.chosen.count { it.fromArchive }
        return "$head · его записей ${result.records}, предложений ${result.sentences}" +
            " · снято: столбиком ${result.inColumn}, обращение к агенту ${result.toAgent}, вопрос/просьба/«мы» ${result.notStatement}," +
            " без «я/мой» ${result.noFirstPronoun}, о разговоре ${result.aboutTalk}" +
            " · прошло ${result.passed.size}, в портрет ${result.chosen.size} (из архива $archive)" +
            (if (fed) " · подано модели" else " · модели не подано" + (whyNotFed?.let { " — $it" } ?: "")) +
            " · не грелось"
    }
}
