package com.uroboros.memory.nav

import com.uroboros.memory.Layer
import com.uroboros.memory.Sentences
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
 * КОГДА ИДЁТ МОДЕЛИ — повод ([Reason], [reasonOf]); ищется на каждом ходе,
 * подаётся по поводу:
 *  - ВОПРОС О СОБЕСЕДНИКЕ ([asksAbout]) — весь портрет ([Result.chosen]).
 *    Вопросом считается предложение реплики с «?», у которого адрес
 *    «владелец» или «оба» (Coordinates.questionAddress, без наследования от
 *    прошлых вопросов) и есть первое лицо (PersonForm.ofSentence). Адрес всей
 *    реплики для этого не годится: он ставится и утверждениям («Я работаю по
 *    субботам»), и безличным репликам по наследству, и тогда портрет шёл
 *    почти на каждом ходе — модель пересказывает его без спроса;
 *  - ТЕМА ([topic]) — реплика не вопрос о собеседнике, но в ней есть редкое
 *    существительное, и оно есть в прошедших строках портрета: идут только
 *    эти строки, не больше [TOPIC_LIMIT], под своей подписью ([TOPIC_HEADER]).
 *    Тема — начальная форма слова реплики по словарю (LemmaDictionary), у
 *    которого есть разбор «существительное» и нет ни одного служебного
 *    ([FUNCTION_WORDS]): в словаре «по» — ещё и «ПО», программа, а «с» —
 *    сокращение. Редкое — встречается не больше чем в [RARE] записях этого
 *    собеседника: частое и так находит общий отбор по словам, а портрет по
 *    частому слову («код», «агент») был бы тем же портретом на каждом ходе.
 *    Словарь не загружен — по теме не ищется, вопрос о собеседнике работает;
 *  - иначе портрета нет, и модели о нём не говорится ничего: строка
 *    [NOTHING] — ответ на вопрос о собеседнике, а не на каждый ход.
 *
 * ПОВТОР РЕПЛИКИ. Предложение записи, совпадающее с предложением текущей
 * реплики (регистр, «ё» и знак в конце не важны), снимается: владелец
 * повторил то, что уже говорил, и получил бы в ответ свою же фразу.
 * Сама текущая реплика в записях хода не встречается — в память она ложится
 * после ответа (автозапись в MainActivity), здесь ловится только сказанное
 * раньше теми же словами.
 *
 * КАКАЯ ЗАПИСЬ НЕ СМОТРИТСЯ ВОВСЕ. Запись в [COLUMN_LINES] и больше непустых
 * строк — текст столбиком: стихи, песня, вставленный чужой текст. «Я» в нём —
 * лицо текста, а не собеседника («Молча сижу под окошком темницы»), и пересказ
 * подал бы его модели как сказанное собеседником о себе. Сняты все её
 * предложения; прибор считает их отдельно («столбиком»).
 *
 * КАКОЕ ПРЕДЛОЖЕНИЕ БЕРЁТСЯ. Запись режется на предложения (Sentences.split,
 * через PersonForm.of), и каждое проходит проверки по порядку; первая
 * не пройденная — причина, по которой оно снято (её и считает прибор):
 *  0. СВОЯ РЕЧЬ — не подпись, не пересказ, не цитата текста: «я» во
 *     вставленной реплике Клода — Клод, а не собеседник (граница и её промахи —
 *     ForeignSpeech.ownSentences);
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
 *    на него пока нельзя;
 *  - тема — совпадение слова, а не смысла: синоним («инструмент» и
 *    «рубанок») или та же тема другими словами не находятся; слово вне
 *    словаря (имя «Кэп», латиница, новое слово) темой не становится;
 *    омоним со служебным разбором темой не становится никогда;
 *  - тема берётся только из текущей реплики: о чём говорили ход назад, не
 *    держится;
 *  - редкость меняется с памятью: тема, сказанная в третий раз, перестаёт
 *    быть редкой;
 *  - поправку подаёт как есть: на «Я не работаю по субботам» тема находит
 *    «Я работаю по субботам» — строку, которую реплика только что отменила;
 *  - «вопрос о собеседнике» — по форме: «Привет, ты со мной?» тоже вопрос с
 *    адресом «оба» и первым лицом, и портрет придёт целиком зря.
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
 * ЧИСЛА [LIMIT], [PER_RECORD], [FRESH], [COLUMN_LINES], [RARE] и [TOPIC_LIMIT] объявлены, не измерены. Проверены на ходах
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

    /** Тема редкая, если её слово есть не больше чем в стольких записях собеседника. */
    const val RARE = 2

    /** Сколько строк портрета идёт модели по теме — не больше. */
    const val TOPIC_LIMIT = 2

    /** Части речи, при которых слово темой не бывает, даже если у него есть разбор «существительное». */
    val FUNCTION_WORDS = setOf("PREP", "CONJ", "PRCL", "INTJ", "NPRO")

    /** Почему портрет идёт модели на этом ходе (описание объекта, «КОГДА ИДЁТ МОДЕЛИ»). */
    enum class Reason { QUESTION, TOPIC, NONE }

    /**
     * Тема реплики: [nouns] — начальные формы слов-тем реплики (существительные
     * без служебных разборов), [rare] — те из них, что редкие ([RARE]),
     * [lines] — строки портрета с редкими словами, свежие первыми, не больше
     * [TOPIC_LIMIT]; [words] — по каким словам строки нашлись.
     */
    data class Topic(
        val nouns: List<String>,
        val rare: List<String>,
        val lines: List<Line>,
        val words: List<String>,
    )

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
        /** Снято как повтор текущей реплики (описание объекта, «ПОВТОР РЕПЛИКИ»). */
        val repeats: Int = 0,
        /** Снято проверкой 0 — чужая речь (ForeignSpeech.ownSentences). */
        val foreign: Int = 0,
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
     * Подпись над строками портрета, поданными по теме. Не [HEADER]: «знаю
     * только это» над одной-двумя строками темы сказало бы модели, что больше
     * о собеседнике ничего нет. Имени здесь нет: имя — ответ на вопрос о
     * собеседнике. Подпись объявлена, стендом на модели не подбиралась.
     */
    const val TOPIC_HEADER = "Об этом ты мне говорил:"

    /**
     * Сообщение с записями хода (голосом агента): портрет под [HEADER], пустая
     * строка, остальные записи. [lines] — все записи хода в порядке подачи
     * (записи прошлых ходов модели не подаются, см.
     * ConversationJournal.messagesFor); из них портретные — те, что есть в
     * [portraitLines]. Подпись ставится, только если строки портрета есть.
     * [nothing] — портрет искался и пуст: вместо него [NOTHING].
     * [name] — как собеседник велел себя называть ([NameClaim]); подпись
     * тогда [header] с именем. Без строк портрета имя не подаётся.
     * [topic] — строки поданы по теме: подпись [TOPIC_HEADER], без имени, и
     * [nothing] не действует.
     * null — сообщения не будет.
     */
    fun recall(
        lines: List<String>,
        portraitLines: Set<String>,
        nothing: Boolean,
        name: String? = null,
        topic: Boolean = false,
    ): String? {
        val (portrait, rest) = lines.partition { it in portraitLines }
        val parts = ArrayList<String>()
        when {
            portrait.isNotEmpty() -> parts += (listOf(if (topic) TOPIC_HEADER else header(name)) + portrait).joinToString("\n")
            nothing && !topic -> parts += NOTHING
        }
        if (rest.isNotEmpty()) parts += rest.joinToString("\n")
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /**
     * Реплика [reply] спрашивает о собеседнике (описание объекта, «КОГДА ИДЁТ
     * МОДЕЛИ»): есть предложение с «?», адресом «владелец» или «оба» и первым
     * лицом. Лицо считается от говорящего: «я» в реплике — тот, кто её сказал.
     */
    fun asksAbout(reply: String): Boolean = Sentences.split(reply).any { s ->
        s.trimEnd().endsWith('?') &&
            Coordinates.questionAddress(s).let { it == Address.OWNER || it == Address.BOTH } &&
            PersonForm.Person.FIRST in PersonForm.ofSentence(s)
    }

    /** Повод хода: вопрос о собеседнике важнее темы; темы без строк — нет повода. */
    fun reasonOf(asks: Boolean, topic: Topic?): Reason = when {
        asks -> Reason.QUESTION
        topic != null && topic.lines.isNotEmpty() -> Reason.TOPIC
        else -> Reason.NONE
    }

    /** Строки, которые идут модели по поводу [reason]. */
    fun fedLines(reason: Reason, result: Result?, topic: Topic?): List<Line> = when (reason) {
        Reason.QUESTION -> result?.chosen.orEmpty()
        Reason.TOPIC -> topic?.lines.orEmpty()
        Reason.NONE -> emptyList()
    }

    /**
     * Портрет собеседника [person] по записям памяти. Записи, ждущие проверки
     * и отвергнутые, не смотрятся — как в облаке (MainActivity.cloudSources).
     * [current] — текущая реплика: её предложения в портрет не берутся
     * (описание объекта, «ПОВТОР РЕПЛИКИ»).
     */
    fun of(records: List<Sticker>, person: PersonKey = PersonKey.OWNER, current: String = ""): Result {
        val repeatKeys = Sentences.split(current).map { sentenceKey(it) }.filter { it.isNotEmpty() }.toSet()
        var repeats = 0
        val own = ownRecords(records, person)
            .sortedByDescending { it.createdAt }
        var sentences = 0
        var toAgent = 0
        var notStatement = 0
        var noFirst = 0
        var inColumn = 0
        var aboutTalk = 0
        var foreign = 0
        val passed = ArrayList<Line>()
        for (record in own) {
            val column = inColumn(record.content)
            // Столбиком — запись снята целиком, разбирать её речь незачем.
            val mine = if (column) emptyList() else ForeignSpeech.ownSentences(record.content)
            for ((i, s) in PersonForm.of(record.content).sentences.withIndex()) {
                sentences++
                val p = s.persons
                when {
                    column -> inColumn++
                    !mine.getOrElse(i) { true } -> foreign++
                    MirrorFilter.addressesAgent(s) || PersonForm.Person.WE_WITH_YOU in p -> toAgent++
                    s.sentence.trimEnd().endsWith('?') ||
                        PersonForm.Person.IMPERATIVE in p ||
                        PersonForm.Person.UNCLEAR in p -> notStatement++
                    !hasFirstPronoun(s.sentence) -> noFirst++
                    TalkRemark.isRemark(s.sentence) -> aboutTalk++
                    sentenceKey(s.sentence) in repeatKeys -> repeats++
                    else -> passed += Line(
                        record.id, s.sentence, record.createdAt,
                        fromArchive = record.layer == Layer.PURPLE.name,
                        touches = record.userMatchCount,
                    )
                }
            }
        }
        return Result(
            own.size, sentences, toAgent, notStatement, noFirst, passed, choose(passed), inColumn, aboutTalk, repeats,
            foreign,
        )
    }

    /**
     * Тема реплики [reply] и строки портрета [result] с ней (описание объекта,
     * «КОГДА ИДЁТ МОДЕЛИ»). Редкость считается по тем же записям собеседника
     * [person], что и портрет ([records] — все записи, отбор здесь). Нет слов-тем —
     * записи не перебираются.
     */
    fun topic(
        result: Result,
        records: List<Sticker>,
        reply: String,
        dictionary: LemmaDictionary,
        person: PersonKey = PersonKey.OWNER,
    ): Topic {
        val nouns = LinkedHashSet<String>()
        for (word in words(reply)) nouns += nounLemmas(word, dictionary)
        if (nouns.isEmpty()) return Topic(emptyList(), emptyList(), emptyList(), emptyList())
        val counts = HashMap<String, Int>()
        for (record in ownRecords(records, person)) {
            lemmasOf(record.content, dictionary).filter { it in nouns }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val rare = nouns.filter { (counts[it] ?: 0) in 1..RARE }
        if (rare.isEmpty()) return Topic(nouns.toList(), emptyList(), emptyList(), emptyList())
        val hits = result.passed.asSequence()
            .map { line -> line to lemmasOf(line.sentence, dictionary).filter { it in rare } }
            .filter { it.second.isNotEmpty() }
            .take(TOPIC_LIMIT)
            .toList()
        val found = hits.flatMapTo(HashSet()) { it.second }
        return Topic(nouns.toList(), rare, hits.map { it.first }, rare.filter { it in found })
    }

    /**
     * Начальные формы слова [word] как темы: разборы «существительное», если
     * ни одного служебного ([FUNCTION_WORDS]) нет; иначе и для незнакомого
     * слова — пусто. Двузначное слово («белки» — белка и белок) даёт все
     * свои существительные. Одно правило темы на портрет и карту окна (WindowMap).
     */
    fun nounLemmas(word: String, dictionary: LemmaDictionary): Set<String> {
        val analyses = dictionary.analyze(word)
        if (analyses.any { it.partOfSpeech in FUNCTION_WORDS }) return emptySet()
        return analyses.filter { it.partOfSpeech == "NOUN" }.mapTo(LinkedHashSet()) { it.lemma }
    }

    private fun ownRecords(records: List<Sticker>, person: PersonKey): List<Sticker> =
        records.filter { Coordinates.speakerOf(it.source) == person && !it.reviewPending && it.rejectedAt == null }

    // Слово для словаря — буквы и дефис («когда-нибудь» в словаре одним словом).
    private val WORD = Regex("[\\p{L}-]+")

    private fun words(text: String): List<String> = WORD.findAll(text).map { it.value.trim('-') }.filter { it.isNotEmpty() }.toList()

    /** Начальные формы всех разборов всех слов текста; незнакомые слова не дают ничего. */
    private fun lemmasOf(text: String, dictionary: LemmaDictionary): Set<String> =
        words(text).flatMapTo(HashSet()) { dictionary.lemmas(it) }

    /** Предложение для сверки с репликой: приведённое, без пробелов и знаков по краям. */
    private fun sentenceKey(sentence: String): String =
        TextFold.fold(sentence).trim().trimEnd('.', '!', '?', '…', ' ').trim()

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
     * Строка прибора «О собеседнике:». Печатается всегда. [reason] — повод хода
     * ([reasonOf]); [result] — null, если поиск не посчитался; [failure] — имя
     * сбоя поиска портрета (ход при этом идёт); [topic] — null, если тема не
     * искалась, тогда [topicOff] — почему; [fed] — поданы ли строки модели;
     * [whyNotFed] — почему нет, если причина известна. Без повода строка
     * говорит, на каком шаге тема не нашлась: слов-тем нет, они не редкие или
     * строк с ними в портрете нет.
     */
    fun meterLine(
        reason: Reason,
        result: Result?,
        topic: Topic?,
        fed: Boolean,
        failure: String? = null,
        whyNotFed: String? = null,
        topicOff: String? = null,
    ): String {
        if (failure != null) return "не посчитался — $failure"
        if (result == null) return "не посчитался"
        val repeats = if (result.repeats > 0) " · снято повторов реплики ${result.repeats}" else ""
        val feeding = (if (fed) " · подано модели" else " · модели не подано" + (whyNotFed?.let { " — $it" } ?: "")) +
            " · не грелось"
        return when (reason) {
            Reason.QUESTION -> {
                val archive = result.chosen.count { it.fromArchive }
                "вопрос о собеседнике → весь портрет · его записей ${result.records}, предложений ${result.sentences}" +
                    " · снято: столбиком ${result.inColumn}, чужая речь ${result.foreign}, обращение к агенту ${result.toAgent}, вопрос/просьба/«мы» ${result.notStatement}," +
                    " без «я/мой» ${result.noFirstPronoun}, о разговоре ${result.aboutTalk}" +
                    repeats +
                    " · прошло ${result.passed.size}, в портрет ${result.chosen.size} (из архива $archive)" + feeding
            }
            Reason.TOPIC ->
                "тема: ${topic!!.words.joinToString(", ")} → строк ${topic.lines.size}" +
                    " (прошло в портрет ${result.passed.size})" + repeats + feeding
            Reason.NONE -> "не вопрос о собеседнике; " + when {
                topic == null -> "по теме не ищу — ${topicOff ?: "причина неизвестна"}"
                topic.nouns.isEmpty() -> "темы нет — существительных в реплике нет"
                topic.rare.isEmpty() -> "темы нет — ${topic.nouns.joinToString(", ")}: не редкие (в записях больше $RARE раз) или их нет в записях"
                else -> "темы нет — ${topic.rare.joinToString(", ")}: в строках портрета нет (прошло ${result.passed.size})"
            } + repeats + " · портрет не подан"
        }
    }
}
