package com.uroboros.gate

import com.uroboros.memory.Sentences
import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.memory.nav.PersonForm
import com.uroboros.memory.nav.RetellTable
import com.uroboros.util.TextFold

/**
 * Развёртка слов из врат на сторону получателя: первое лицо сказавшего — в
 * третье, с его именем. «я работаю по субботам» → «Борис работает по
 * субботам»; «ты» — к кому он обращался, его агент: «ты вчера посчитал» →
 * «агент Бориса вчера посчитал».
 *
 * ЗАЧЕМ. Модель получателя пересказывает чужие слова своему человеку и путает
 * чужое «я» с ним: «Ты, Борис, говорил…» — Антону. Код поворачивает лица до
 * модели, и ей остаётся пересказать готовое. Главный выигрыш — у малых моделей;
 * крупная и сама пересказывает от третьего лица.
 *
 * ЧТО НЕ МЕНЯЕТСЯ НИКОГДА. Пакет и его слова ([GatePacket.words]): развёртка —
 * вид для модели, а не запись. В память, на экран как «Борис сказал» и в ответ
 * Борису идут слова как сказаны. Слей их — и ошибка развёртки станет «Борис
 * сказал» навсегда, сверять будет не с чем.
 *
 * КОГДА ВКЛЮЧАЕТСЯ ([unfold], иначе [Result.off] с причиной и слова как сказаны):
 *  - говорит человек, за которого агент ([GatePacket.saidBy] равен
 *    [GatePacket.represents], а не [GatePacket.from]). Сам агент «я» — не
 *    разобрано;
 *  - имя сказавшего знает получатель — из своего знания о собеседнике, НЕ из
 *    пакета. Имя в пакете — только слова: иначе чужой агент назовётся «Антоном»;
 *  - словарь начальных форм и таблица глаголов загружены.
 *
 * ПО ПРЕДЛОЖЕНИЮ. Предложение, в котором код не уверен, идёт как сказано, с
 * причиной ([Kept]); модели тогда подают «Сказал: Борис. Его слова: «…»». Для
 * смысла развёртка — последняя ступень: модель неверный смысл не отсеет, а
 * повторит увереннее. Поэтому отказ — по любому сомнению:
 *  - просьба к своему агенту: повелительное в начале любой части («напомни
 *    мне», «я тебя прошу, не забудь…»), кроме рамки доставки ниже;
 *  - «мы», «нам», «наш» без «мы с кем»: кто в этом «мы» — не известно;
 *  - глагол, у которого в одной форме два глагола: «пою» (петь, поить),
 *    «вожу», «плачу», «лечу» — различил бы только смысл;
 *  - глагол, чьего третьего лица словарь не подтвердил.
 *
 * ПРАВИЛА:
 *  - «я, меня, мне, мной» → имя в падеже ([NameCases]): «меня» после «у, от,
 *    для…» — родительный, иначе винительный; «мне» после «о, в, на, при» —
 *    предложный, иначе дательный. Предлог подстраивается («со мной» → «с
 *    Борисом», «обо мне» → «о Борисе»);
 *  - «мой X» → «X Бориса» — за первым существительным в трёх словах;
 *  - «ты», «твой» — так же, к кому обращался сказавший («агент Бориса»). Кроме
 *    обращения по имени к тому, кому перескажут («Антон, привет! как у тебя
 *    дела?»): тогда «ты» — он сам и остаётся «ты»;
 *  - «мы с тобой» → «Борис и агент Бориса», «мы с Машей» → «Борис с Машей»;
 *  - глагол 1-го и 2-го лица → 3-е: пара 2-го лица по таблице глаголов, затем
 *    окончание, и только если словарь подтвердил 3-е лицо того же глагола
 *    («могу» → «можешь» → «может»). Омоним с не-глаголом («дам», «бегу») читается
 *    глаголом, только если в той же части есть «я» (или «ты»);
 *  - глагол без «я» в своей части получает подлежащее перед собой (и перед
 *    «не», «сам»): «что не ем мясо» → «что Борис не ест мясо»;
 *  - прошедшее время первым словом предложения («вчера ездил…») — имя в
 *    начало, если в первой части нет своего кандидата в подлежащие: «позвонил
 *    Олег» имени не получает;
 *  - рамка доставки: «передай/скажи Антону, что …» снимается; «передай Антону
 *    привет» → «Борис передаёт Антону привет»; «спроси у Антона, …» → «Борис
 *    спрашивает, …». Внутри рамки «он/его/ему» — Антон (если род имени
 *    известен, см. [NameCases.genderOf]); «его» перед существительным —
 *    притяжательное, не трогается.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - «он» вне рамки остаётся «он»; «жена» без «моя» остаётся без «Бориса»;
 *  - подлежащее после прошедшего («позвонил Олег») и дополнение («купил
 *    хлеб») не различает — имя не вставляется ни туда, ни туда;
 *  - «ты» не подлежащим («Помнишь, кто ты?») всё равно считается подлежащим
 *    части — глагол остаётся без «агент Бориса»;
 *  - вставка подлежащего бывает коряво: «старый Борис отдаст сыну»;
 *  - род сказавшего не проверяет: «я сама» от Бориса даст «Борис сама»;
 *  - только русский, только по форме; смысла не видит;
 *  - склонение имени — по последней букве (см. [NameCases]).
 *
 * Проверено стендом на трёх моделях (4B, 7B, 8B): 80 придуманных фраз,
 * половина — написана до запуска кода, и 25 живых предложений одного владельца
 * своему агенту. Фраз вида «передай Антону…» вживую ещё не было.
 *
 * Чистый объект: ни сети, ни базы, ни Android.
 */
object GateUnfold {

    /** Предложение, оставленное как сказано, и почему. */
    data class Kept(val sentence: String, val reason: String)

    /**
     * [text] — для модели: развёрнутые предложения и оставленные как сказаны,
     * по порядку. [off] — развёртка не включалась вовсе (тогда [text] — слова
     * как сказаны). [taken] — предложений взято, из них [changed] с заменой лица.
     */
    data class Result(
        val text: String,
        val off: String?,
        val taken: Int,
        val changed: Int,
        val kept: List<Kept>,
    )

    /**
     * [speakerName] — как получатель сам зовёт сказавшего (null — не знает);
     * [listenerName] — кому получатель перескажет, одним словом.
     */
    fun unfold(
        packet: GatePacket,
        speakerName: String?,
        listenerName: String,
        dictionary: LemmaDictionary?,
        table: RetellTable?,
    ): Result {
        val words = packet.words
        fun off(why: String) = Result(words, why, 0, 0, emptyList())
        if (packet.saidBy == packet.from) return off("говорит сам агент — для этого развёртка не разобрана")
        if (packet.saidBy != packet.represents) return off("сказал не тот, за кого говорит агент")
        if (speakerName.isNullOrBlank()) return off("имя собеседника не известно")
        if (dictionary == null || table == null) return off("словарь или таблица глаголов не загружены")

        val speaker = NameCases.of(speakerName)
        val addressee = NameCases.phrase("агент", speaker.of(NameCases.Case.GEN))
        val work = words.replace('ë', 'ё').replace('Ë', 'Ё')
        // Обращение по имени к тому, кому перескажут («Антон, привет!»): «ты» в
        // словах — он сам, и при пересказе ему остаётся «ты».
        val firstWord = WORD.find(work)
        val vocative = firstWord != null && firstWord.range.first == 0 &&
            fold(firstWord.value) == fold(listenerName) &&
            work.substring(firstWord.range.last + 1).trimStart().firstOrNull() in setOf(',', '!')
        val ctx = Ctx(
            speaker, if (vocative) null else addressee, NameCases.of(listenerName),
            NameCases.genderOf(listenerName), dictionary, table,
        )
        val out = ArrayList<String>()
        val kept = ArrayList<Kept>()
        var taken = 0
        var changed = 0
        for (s in Sentences.split(work)) {
            val r = sentence(s, ctx)
            if (r == null) {
                kept += Kept(s, ctx.reason)
                out += s
            } else {
                taken++
                if (r != s) changed++
                out += r
            }
        }
        return Result(out.joinToString(" "), null, taken, changed, kept)
    }

    /**
     * Строка прибора: «развёрнуто» отличается от «нечего разворачивать» (взято
     * без замен) и от «отказался» (как сказано, с причиной).
     */
    fun meterLine(r: Result): String {
        if (r.off != null) return "Врата: развёртки нет — ${r.off}; слова как сказаны"
        val all = r.taken + r.kept.size
        val sb = StringBuilder("Врата: взято ${r.taken} из $all предл. (с заменой лица ${r.changed})")
        if (r.kept.isNotEmpty()) {
            sb.append(" · как сказано ${r.kept.size}: ")
            sb.append(r.kept.joinToString("; ") { it.reason })
        }
        return sb.toString()
    }

    private class Ctx(
        val s: NameCases.Forms,
        val a: NameCases.Forms?,
        val l: NameCases.Forms,
        val lGender: NameCases.Gender,
        val dict: LemmaDictionary,
        val table: RetellTable,
    ) {
        /** Почему последнее предложение оставлено как сказано. */
        var reason: String = ""
    }

    private val WORD = Regex("\\p{L}+(?:-\\p{L}+)*")
    private val CLAUSE_BREAK = Regex("[,;:]|\\s[—–-]\\s")

    private fun fold(w: String) = TextFold.fold(w)

    private val DELIVER = setOf("передай", "скажи", "напиши", "сообщи", "передайте", "скажите", "напишите", "сообщите")
    private val DELIVER_THIRD = mapOf(
        "передай" to "передаёт", "скажи" to "говорит", "напиши" to "пишет", "сообщи" to "сообщает",
        "передайте" to "передаёт", "скажите" to "говорит", "напишите" to "пишет", "сообщите" to "сообщает",
    )
    private val ASK = setOf("спроси", "спросите")

    private val GEN_PREP = setOf("у", "от", "до", "без", "для", "из", "около", "кроме", "возле", "мимо", "после", "вместо", "среди", "вокруг", "насчет")
    private val PREP_PREP = setOf("о", "об", "обо", "при", "в", "во", "на")

    /**
     * Что на какой падеж меняется: N — именительный, G — родительный или
     * винительный, D — дательный или предложный, I — творительный. Сами слова —
     * те же, что у определителя лица (PersonForm.FIRST_PRONOUNS,
     * PersonForm.SECOND_PRONOUNS; сверяется тестом); остальные слова тех списков —
     * притяжательные «мой», «твой».
     */
    internal val FIRST = mapOf("я" to 'N', "меня" to 'G', "мне" to 'D', "мной" to 'I', "мною" to 'I')
    internal val SECOND = mapOf("ты" to 'N', "тебя" to 'G', "тебе" to 'D', "тобой" to 'I', "тобою" to 'I')
    private val MY = PersonForm.FIRST_PRONOUNS - FIRST.keys
    private val YOUR = PersonForm.SECOND_PRONOUNS - SECOND.keys
    private val WE = PersonForm.WE_PRONOUNS
    private val OUR = PersonForm.OUR_PRONOUNS
    private val HE = mapOf("он" to 'N', "его" to 'G', "него" to 'G', "ему" to 'D', "нему" to 'D', "ним" to 'I', "нем" to 'P')
    private val SHE = mapOf("она" to 'N', "ее" to 'G', "нее" to 'G', "ей" to 'D')
    private val LEAD = setOf("ADVB", "CONJ", "PRCL", "INTJ")

    /** Слово и пробелы перед ним. */
    private class Tok(val gap: String, val word: String) {
        val f = fold(word)
        var repl: String? = null
        var drop = false
        var before: String? = null
        var after: String? = null
    }

    private fun sentence(raw: String, c: Ctx): String? {
        var s = raw
        var prefix = ""
        var frame = false
        // Рамка доставки: «передай Антону, что …» — поручение агенту отправителя,
        // а не слова для Антона; «спроси у Антона, …» — вопрос сказавшего.
        run {
            val ws = WORD.findAll(s).toList()
            if (ws.size < 3) return@run
            val f0 = fold(ws[0].value)
            val f1 = fold(ws[1].value)
            if (f0 in DELIVER && f1 == fold(c.l.of(NameCases.Case.DAT)) && fold(ws[2].value) == "что" && ws.size > 3) {
                s = s.substring(ws[3].range.first)
                frame = true
            } else if (f0 in DELIVER && f1 == fold(c.l.of(NameCases.Case.DAT))) {
                // Без «что» — передаётся вещь, а не слова: «передай Антону привет».
                prefix = c.s.of(NameCases.Case.NOM) + " " + DELIVER_THIRD.getValue(f0) + " " + ws[1].value + " "
                s = s.substring(ws[2].range.first)
                frame = true
            } else if (f0 in ASK) {
                val idx = when {
                    f1 == "у" && fold(ws[2].value) == fold(c.l.of(NameCases.Case.GEN)) -> 3
                    f1 == fold(c.l.of(NameCases.Case.ACC)) -> 2
                    else -> -1
                }
                if (idx > 0 && ws.size > idx) {
                    s = s.substring(ws[idx].range.first)
                    prefix = c.s.of(NameCases.Case.NOM) + " спрашивает, "
                    frame = true
                }
            }
        }

        val toks = ArrayList<Tok>()
        val clause = ArrayList<Int>()
        var pos = 0
        var cl = 0
        for (m in WORD.findAll(s)) {
            val gap = s.substring(pos, m.range.first)
            if (toks.isNotEmpty() && CLAUSE_BREAK.containsMatchIn(gap)) cl++
            toks += Tok(gap, m.value)
            clause += cl
            pos = m.range.last + 1
        }
        val tail = s.substring(pos)
        if (toks.isEmpty()) return refuse(c, "нет слов")

        // Просьба к своему агенту (кроме рамки доставки) Антону не пересказывается:
        // повелительное в начале любой части («я тебя прошу, не забудь…»).
        if (!frame) {
            for (start in toks.indices) {
                if (start > 0 && clause[start] == clause[start - 1]) continue
                var w = start
                while (w < toks.size && clause[w] == clause[start] && toks[w].f == "не") w++
                if (w < toks.size && clause[w] == clause[start] && isImperative(toks[w].f, c)) return refuse(c, "просьба к своему агенту: «${toks[w].word}»")
            }
        }

        val hasI = HashSet<Int>()
        val hasYou = HashSet<Int>()
        val weDone = HashSet<Int>()

        // «мы с тобой», «мы с Машей»: «мы» — сказавший (и его собеседник).
        for (i in toks.indices) {
            if (toks[i].f != "мы") continue
            if (i + 2 < toks.size && toks[i + 1].f in setOf("с", "со")) {
                if (toks[i + 2].f in setOf("тобой", "тобою")) {
                    toks[i].repl = c.s.of(NameCases.Case.NOM) + " и " + (c.a?.of(NameCases.Case.NOM) ?: "ты")
                    toks[i + 1].drop = true
                    toks[i + 2].drop = true
                } else {
                    toks[i].repl = c.s.of(NameCases.Case.NOM)
                }
                weDone += clause[i]
                hasI += clause[i]
            }
        }
        // Иное «мы» — кто ещё в нём, не знаю.
        for (t in toks) if ((t.f in WE || t.f in OUR) && t.repl == null && !t.drop) return refuse(c, "«${t.word}» — не ясно, кто в этом «мы»")

        for (i in toks.indices) {
            val t = toks[i]
            if (t.repl != null || t.drop) continue
            val prev = if (i > 0) toks[i - 1].f else ""
            when {
                t.f in FIRST -> {
                    t.repl = c.s.of(caseOf(FIRST.getValue(t.f), prev))
                    if (t.f == "я") hasI += clause[i]
                }
                t.f in MY -> possessive(toks, i, c.s, c)
                c.a != null && t.f in SECOND -> {
                    t.repl = c.a.of(caseOf(SECOND.getValue(t.f), prev))
                    if (t.f == "ты") hasYou += clause[i]
                }
                c.a != null && t.f in YOUR -> possessive(toks, i, c.a, c)
                frame && c.lGender == NameCases.Gender.MASC && t.f in HE -> {
                    if ((t.f == "его") && i + 1 < toks.size && isNounOrAdj(toks[i + 1].f, c)) continue
                    t.repl = c.l.of(caseOf(HE.getValue(t.f), prev))
                }
                frame && c.lGender == NameCases.Gender.FEMN && t.f in SHE -> {
                    if (t.f == "ее" && i + 1 < toks.size && isNounOrAdj(toks[i + 1].f, c)) continue
                    t.repl = c.l.of(caseOf(SHE.getValue(t.f), prev))
                }
            }
        }

        // Глаголы.
        for (i in toks.indices) {
            val t = toks[i]
            if (t.repl != null || t.drop) continue
            // «я» в той же части: омоним («дам», «бегу») читается глаголом —
            // иначе выйдет «Борис дам».
            val looseFirst = clause[i] in hasI
            val looseSecond = clause[i] in hasYou
            if (c.table.isAmbiguous(t.f) && (isFirstSing(t.f, c, looseFirst) || c.a != null)) return refuse(c, verbReason(t.word, c))
            when {
                isFirstSing(t.f, c, looseFirst) -> {
                    t.repl = keepCase(t.word, thirdSing(t.word, c.table.pairOf(t.word), c) ?: return refuse(c, verbReason(t.word, c)))
                    if (clause[i] !in hasI) insertSubject(toks, i, c.s.of(NameCases.Case.NOM))
                }
                c.a != null && isSecondSing(t.f, c, looseSecond) -> {
                    t.repl = keepCase(t.word, thirdSing(t.word, t.word, c) ?: return refuse(c, verbReason(t.word, c)))
                    if (clause[i] !in hasYou) insertSubject(toks, i, c.a.of(NameCases.Case.NOM))
                }
                isFirstPlural(t.f, c) -> {
                    if (clause[i] !in weDone) return refuse(c, "«${t.word}» — не ясно, кто в этом «мы»")
                    t.repl = keepCase(t.word, thirdPlural(t.word, c) ?: return refuse(c, verbReason(t.word, c)))
                }
            }
        }

        // Прошедшее без подлежащего в начале предложения («вчера ездил…») — сказавший.
        if (hasI.isEmpty() && hasYou.isEmpty() && prefix.isEmpty()) {
            val j = toks.indexOfFirst { !isLead(it.f, c) }
            if (j >= 0 && toks[j].repl == null && isPersonalPast(toks[j].f, c) && clause[j] == 0 &&
                !hasOwnSubject(toks, clause, j, c)
            ) {
                putBefore(toks[0], c.s.of(NameCases.Case.NOM))
            }
        }

        fixPrepositions(toks)

        val sb = StringBuilder(prefix)
        for ((i, t) in toks.withIndex()) {
            if (t.drop) continue
            val gap = if (i == 0 && prefix.isNotEmpty()) "" else t.gap
            sb.append(gap)
            if (t.before != null) sb.append(t.before).append(' ')
            sb.append(t.repl ?: t.word)
            if (t.after != null) sb.append(' ').append(t.after)
        }
        sb.append(tail)
        var out = sb.toString().replace(Regex("\\s{2,}"), " ").trim()
        if (raw.firstOrNull()?.isUpperCase() == true && out.isNotEmpty()) out = out[0].uppercase() + out.substring(1)
        return out
    }

    private fun refuse(c: Ctx, why: String): String? {
        c.reason = why
        return null
    }

    private fun verbReason(word: String, c: Ctx): String {
        val lemmas = c.dict.analyze(word).filter { it.partOfSpeech == "VERB" }.mapTo(LinkedHashSet()) { it.lemma }
        return if (lemmas.size > 1) "«$word» — два глагола в одной форме (${lemmas.joinToString(", ")})"
        else "«$word» — не нашёл третьего лица"
    }

    private fun caseOf(code: Char, prev: String): NameCases.Case = when (code) {
        'N' -> NameCases.Case.NOM
        'G' -> if (prev in GEN_PREP) NameCases.Case.GEN else NameCases.Case.ACC
        'D' -> if (prev in PREP_PREP) NameCases.Case.PREP else NameCases.Case.DAT
        'I' -> NameCases.Case.INS
        else -> NameCases.Case.PREP
    }

    /** «моя сестра» → «сестра Бориса»: родительный — за первым существительным в трёх словах. */
    private fun possessive(toks: List<Tok>, i: Int, who: NameCases.Forms, c: Ctx) {
        val gen = who.of(NameCases.Case.GEN)
        for (k in i + 1..minOf(i + 3, toks.size - 1)) {
            if (c.dict.analyze(toks[k].f).any { it.partOfSpeech == "NOUN" }) {
                toks[i].drop = true
                toks[k].after = gen
                if (toks[i].word.first().isUpperCase()) toks[k].repl = toks[k].word[0].uppercase() + toks[k].word.substring(1)
                return
            }
        }
        toks[i].repl = gen
    }

    /** Подлежащее перед глаголом, а перед «не» и «сам» — перед ними. */
    private fun insertSubject(toks: List<Tok>, i: Int, subject: String) {
        var at = i
        while (at > 0 && toks[at - 1].f in setOf("не", "сам", "сама")) at--
        putBefore(toks[at], subject)
    }

    /** Вставка перед словом; заглавная первого слова переходит к вставке («Работаю…» → «Борис работает…»). */
    private fun putBefore(t: Tok, subject: String) {
        val text = t.repl ?: t.word
        if (text.first().isUpperCase()) {
            t.repl = text[0].lowercase() + text.substring(1)
            t.before = subject[0].uppercase() + subject.substring(1)
        } else {
            t.before = subject
        }
    }

    private fun fixPrepositions(toks: List<Tok>) {
        for (i in 0 until toks.size - 1) {
            val next = toks[i + 1].repl ?: continue
            val f = toks[i].f
            val vowel = next.lowercase().firstOrNull()?.let { it in "аеёиоуыэюя" } == true
            val fix = when (f) {
                "со" -> "с"
                "о", "об", "обо" -> if (vowel) "об" else "о"
                "ко" -> "к"
                "передо" -> "перед"
                "надо" -> "над"
                "подо" -> "под"
                else -> null
            }
            if (fix != null && fix != f) toks[i].repl = keepCase(toks[i].word, fix)
        }
    }

    private fun keepCase(source: String, w: String) =
        if (source.firstOrNull()?.isUpperCase() == true) w[0].uppercase() + w.substring(1) else w

    private fun verbsOnly(f: String, c: Ctx): List<LemmaDictionary.Analysis>? {
        val a = c.dict.analyze(f)
        if (a.isEmpty()) return null
        // Имя собственное («иду» — и «Ида») и вводное слово («знаешь», «думаю») глаголу не мешают.
        if (a.any { it.partOfSpeech != "VERB" && it.tags.none { t -> t in setOf("Name", "Surn", "Patr", "Geox", "Prnt") } }) return emptyList()
        return a
    }

    /** [loose] — омоним с не-глаголом тоже глагол (в части есть «я» или «ты»). */
    private fun isFirstSing(f: String, c: Ctx, loose: Boolean): Boolean {
        if (c.dict.analyze(f).isEmpty()) return c.table.pairOf(f) != null && Regex("(у|ю|усь|юсь)$").containsMatchIn(f)
        val v = if (loose) c.dict.analyze(f).filter { it.partOfSpeech == "VERB" } else verbsOnly(f, c).orEmpty()
        return v.any { "1per" in it.tags && "sing" in it.tags }
    }

    private fun isSecondSing(f: String, c: Ctx, loose: Boolean): Boolean {
        if (c.dict.analyze(f).isEmpty()) return c.table.pairOf(f) != null && Regex("(шь|шься)$").containsMatchIn(f)
        val v = if (loose) c.dict.analyze(f).filter { it.partOfSpeech == "VERB" } else verbsOnly(f, c).orEmpty()
        return v.any { "2per" in it.tags && "sing" in it.tags && "impr" !in it.tags }
    }

    /**
     * В первой части после глагола есть своё подлежащее-кандидат: существительное
     * или имя не после предлога («позвонил Олег»). Падежа словарь не знает,
     * поэтому и дополнение («купил хлеб») считается кандидатом — промах в сторону
     * пропуска: имя сказавшего не вставляется.
     */
    private fun hasOwnSubject(toks: List<Tok>, clause: List<Int>, j: Int, c: Ctx): Boolean {
        for (k in j + 1 until toks.size) {
            if (clause[k] != 0) break
            val a = c.dict.analyze(toks[k].f)
            val nounish = a.any { it.partOfSpeech == "NOUN" || "Name" in it.tags } ||
                (a.isEmpty() && toks[k].word.first().isUpperCase())
            val afterPrep = c.dict.analyze(toks[k - 1].f).any { it.partOfSpeech == "PREP" }
            if (nounish && !afterPrep) return true
        }
        return false
    }

    private fun isFirstPlural(f: String, c: Ctx): Boolean {
        val v = verbsOnly(f, c) ?: return false
        return v.any { "1per" in it.tags && "plur" in it.tags && "impr" !in it.tags }
    }

    private fun isImperative(f: String, c: Ctx): Boolean {
        val a = c.dict.analyze(f)
        if (a.isEmpty() || a.any { it.partOfSpeech in setOf("NPRO", "NUMR", "PREP", "CONJ", "PRCL") || "Apro" in it.tags }) return false
        return a.any { it.partOfSpeech == "VERB" && "impr" in it.tags && "incl" !in it.tags }
    }

    private fun isLead(f: String, c: Ctx): Boolean {
        val a = c.dict.analyze(f)
        return a.isNotEmpty() && a.all { it.partOfSpeech in LEAD }
    }

    private fun isPersonalPast(f: String, c: Ctx): Boolean {
        val v = verbsOnly(f, c) ?: return false
        return v.isNotEmpty() && v.any { "past" in it.tags && "sing" in it.tags }
    }

    /** Слово похоже на существительное или прилагательное и не бывает предлогом («в» — и буква, и предлог). */
    private fun isNounOrAdj(f: String, c: Ctx): Boolean {
        val a = c.dict.analyze(f)
        return a.any { it.partOfSpeech in setOf("NOUN", "ADJF") } && a.none { it.partOfSpeech == "PREP" }
    }

    /**
     * Третье лицо единственного числа от формы 1-го ([original]) через парную
     * форму 2-го ([second], по таблице) или от формы 2-го. Ответ — только то,
     * что словарь узнаёт как 3-е лицо того же глагола; иначе null.
     */
    private fun thirdSing(original: String, second: String?, c: Ctx): String? {
        val lemmas = c.dict.analyze(original).filter { it.partOfSpeech == "VERB" }.mapTo(HashSet()) { it.lemma }
        // Два глагола в одной форме («пою» — петь и поить): какой — не знаю.
        if (lemmas.size > 1) return null
        val cands = ArrayList<String>()
        if (second != null) {
            val refl = second.endsWith("ся")
            val core = if (refl) second.dropLast(2) else second
            val suf = if (refl) "ся" else ""
            for ((e2, e3) in listOf("ешь" to "ет", "ёшь" to "ёт", "ишь" to "ит")) {
                if (core.endsWith(e2)) cands += core.dropLast(e2.length) + e3 + suf
            }
            if (core.endsWith("шь")) cands += core.dropLast(2) + "ст" + suf
        }
        val o = original.lowercase()
        if (o.endsWith("м")) cands += o.dropLast(1) + "ст"
        for (end in listOf("усь", "юсь")) if (o.endsWith(end)) {
            val st = o.dropLast(3)
            cands += listOf(st + "ется", st + "ётся", st + "ится", st + "ается")
        }
        for (end in listOf("у", "ю")) if (o.endsWith(end)) {
            val st = o.dropLast(1)
            cands += listOf(st + "ет", st + "ёт", st + "ит")
        }
        return cands.firstOrNull { cand ->
            c.dict.analyze(cand).any { it.partOfSpeech == "VERB" && "3per" in it.tags && "sing" in it.tags && it.lemma in lemmas }
        }
    }

    private fun thirdPlural(original: String, c: Ctx): String? {
        val lemmas = c.dict.lemmas(original)
        val o = original.lowercase()
        val refl = o.endsWith("ся")
        val core = if (refl) o.dropLast(2) else o
        val suf = if (refl) "ся" else ""
        if (!(core.endsWith("ем") || core.endsWith("ём") || core.endsWith("им"))) return null
        val st = core.dropLast(2)
        return listOf("ут", "ют", "ят", "ат").map { st + it + suf }.firstOrNull { cand ->
            c.dict.analyze(cand).any { it.partOfSpeech == "VERB" && "3per" in it.tags && "plur" in it.tags && it.lemma in lemmas }
        }
    }
}
