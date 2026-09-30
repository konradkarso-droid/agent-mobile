package com.uroboros.memory.nav

import com.uroboros.memory.Sentences
import com.uroboros.util.TextFold

/**
 * Разворот смешанной записи владельца — где есть и его «я», и «ты» агента
 * («Работаю над твоей памятью»), — на сторону агента: «ты работаешь над моей
 * памятью». Результат идёт модели пересказом (строка — в
 * memory.ProvenanceLabels), сама запись в памяти не меняется никогда.
 *
 * ПОЧЕМУ ТАК. Малая модель сама два местоимения сразу не разворачивает — ни с
 * подсказкой, ни с пометками при местоимениях — и приписывает владельцу строки
 * агента. Замер стендом (одна модель 3B, вопрос «Что знаешь обо мне?», 24
 * ответа на вариант): развёрнутый кодом текст, поданный отдельными
 * предложениями, путает её так же, как цитата (3 верных из 24); тот же текст
 * одной фразой, с «ты» в начале своей части предложения, — 17 из 24. Поэтому
 * форма выхода точная, и менять её без нового замера нельзя: перенос «ты»,
 * склейка через «; », строчная первая буква.
 *
 * Глаголы — по таблице пар ([RetellTable]), а не по окончаниям: окончание не
 * отличает «работаю» от «памятью», а ошибка здесь не безопасна — она
 * переписывает слово владельца. Определитель лица ([PersonForm]) таблицу не
 * использует.
 *
 * Правила по порядку:
 *  1. латинские «ë»/«Ë» → «ё»/«Ё» (только в рабочем тексте);
 *  2. предложения — [Sentences.split] (повторы не склеиваются);
 *  3. предложение не берётся, если в нём «?», если первое слово — просьба
 *     ([PersonForm.firstWordIsRequest]) или если в нём неоднозначная форма
 *     таблицы («лечу»);
 *  4. слово — буквы, дефис внутри слова его не рвёт («когда-нибудь»);
 *  5. местоимения меняются парами [PRONOUN_PAIRS]; «мы с тобой»: при
 *     «мы/нас/нам/нами/наш…» в предложении «тобой/тобою» не трогается;
 *  6. глагол — по таблице, только однозначные формы; слова, которых в таблице
 *     нет, не трогаются;
 *  7. предлог перед новым местоимением подстраивается ([PREPOSITION_FIX]);
 *  8. регистр первой буквы заменённого слова сохраняется;
 *  9. «ты» — в начало своей части предложения ([moveTy]);
 * 10. склейка: конечные «.», «!», «…» сняты, первая буква строчная, предложения
 *     через «; », в конце точка. Ни одного взятого предложения — выхода нет.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - проверено стендом на одной модели (3B), одном вопросе («Что знаешь обо
 *    мне?») и четырёх фразах владельца; перенос «ты» помог на одной (о памяти
 *    и устройстве агента), на трёх других ни помог, ни навредил;
 *  - просьба не первым словом («Ладно, потерпи немного…») не ловится и после
 *    разворота повисает просьбой агента к владельцу;
 *  - ложная просьба у первого слова («Теперь…», «Твоими…» — мягкий знак,
 *    окончание на «-и») выбрасывает утверждение; «Если…» выбрасывается как
 *    просьба по совпадению окончания — опираться на это нельзя;
 *  - неоднозначные формы (около 160: «лечу», «будешь»…) — предложение не
 *    берётся целиком;
 *  - незнакомый таблице глагол (сленг: «дебажу») не разворачивается при
 *    развёрнутом местоимении — выйдет «ты дебажу»; как часто — не мерено;
 *  - только единственное число; «мы» не трогается;
 *  - смысл не проверяется: обобщённое «ты» («Всегда носи с собой…»)
 *    развернётся как личное;
 *  - граница части — по знакам и списку [CONJUNCTIONS]; пропущенная запятая её
 *    ломает; частицы в начале («Так, ну … ты») — неочевидный случай;
 *  - первая буква каждого предложения становится строчной — имя собственное в
 *    начале предложения тоже;
 *  - фразы без «ты» (владелец часто пишет без местоимения: «Работаю…») перенос
 *    не трогает. Вставлять «ты» туда, где его нет, замер пользы не показал;
 *  - память таблицы на телефоне не мерена.
 */
object Retelling {

    /** Развёрнутый текст ([text] = null — брать нечего) и число невзятых предложений. */
    data class Result(val text: String?, val dropped: Int)

    /**
     * Пары местоимений — одно место. Те же слова, что
     * [PersonForm.FIRST_PRONOUNS] и [PersonForm.SECOND_PRONOUNS] (сверяется
     * тестом): там они — признак лица, здесь — что на что меняется.
     */
    val PRONOUN_PAIRS = listOf(
        "я" to "ты", "меня" to "тебя", "мне" to "тебе", "мной" to "тобой", "мною" to "тобою",
        "мой" to "твой", "моя" to "твоя", "моё" to "твоё", "мои" to "твои", "моего" to "твоего",
        "моей" to "твоей", "моему" to "твоему", "моём" to "твоём", "моим" to "твоим", "моими" to "твоими",
        "моих" to "твоих", "мою" to "твою",
    )

    private val PRONOUNS: Map<String, String> = buildMap {
        for ((a, b) in PRONOUN_PAIRS) {
            put(TextFold.fold(a), b)
            put(TextFold.fold(b), a)
        }
    }

    private val WITH_YOU = setOf("тобой", "тобою")

    /** Предлог перед новым местоимением: пара «предлог, местоимение» → верный предлог. */
    private val PREPOSITION_FIX = mapOf(
        ("со" to "тобой") to "с", ("со" to "тобою") to "с", ("с" to "мной") to "со", ("с" to "мною") to "со",
        ("обо" to "тебе") to "о", ("о" to "мне") to "обо",
        ("ко" to "тебе") to "к", ("к" to "мне") to "ко",
        ("передо" to "тобой") to "перед", ("перед" to "мной") to "передо",
        ("надо" to "тобой") to "над", ("над" to "мной") to "надо",
        ("подо" to "тобой") to "под", ("под" to "мной") to "подо",
    )

    /**
     * Союзы и частицы, которые могут стоять в части предложения перед «ты».
     * Список объявленный, не подобранный: «чем, где, который…» нужны, иначе
     * «больше, чем я думал» дало бы «больше, ты чем думал».
     */
    val CONJUNCTIONS = setOf(
        "и", "а", "но", "да", "или", "что", "чтобы", "когда", "если", "хотя", "пока", "поскольку",
        "потому", "так", "как", "ведь", "ну", "вот", "зато", "однако", "либо", "тоже", "также", "раз",
        "чем", "где", "куда", "откуда", "зачем", "почему", "кто", "чей",
        "который", "которая", "которое", "которые", "которого", "которой", "которую", "котором",
        "которым", "которыми", "которых",
        "будто", "словно", "ибо",
    )

    private val WORD = Regex("\\p{L}+(?:-\\p{L}+)*")
    private val PART_BOUNDARY = Regex(",|;|:|\\s[—–-]\\s")
    private val END_MARKS = Regex("[.!…]+$")

    fun retell(text: String, table: RetellTable): Result {
        val work = text.replace('ë', 'ё').replace('Ë', 'Ё')
        val kept = ArrayList<String>()
        var dropped = 0
        for (sentence in Sentences.split(work)) {
            val words = WORD.findAll(sentence).map { it.value }.toList()
            if ('?' in sentence || PersonForm.firstWordIsRequest(sentence) ||
                words.any { table.isAmbiguous(it) }
            ) {
                dropped++
                continue
            }
            kept += moveTy(swap(sentence, table))
        }
        if (kept.isEmpty()) return Result(null, dropped)
        val body = kept.map { s ->
            val t = END_MARKS.replace(s.trim(), "")
            if (t.isEmpty()) t else t.substring(0, 1).lowercase() + t.substring(1)
        }
        return Result(body.joinToString("; ") + ".", dropped)
    }

    /** Текст, разбитый на куски: чётные — между словами (могут быть пустыми), нечётные — слова. */
    private fun tokens(s: String): MutableList<String> {
        val out = ArrayList<String>()
        var pos = 0
        for (m in WORD.findAll(s)) {
            out += s.substring(pos, m.range.first)
            out += m.value
            pos = m.range.last + 1
        }
        out += s.substring(pos)
        return out
    }

    private fun keepCase(source: String, replacement: String): String =
        if (source.firstOrNull()?.isUpperCase() == true) {
            replacement.substring(0, 1).uppercase() + replacement.substring(1)
        } else replacement

    private fun swap(sentence: String, table: RetellTable): String {
        val toks = tokens(sentence)
        val folded = toks.indices.filter { it % 2 == 1 }.map { TextFold.fold(toks[it]) }
        val weWithYou = folded.any { it in PersonForm.WE_PRONOUNS || it.startsWith("наш") } &&
            folded.any { it in WITH_YOU }
        for (i in 1 until toks.size step 2) {
            val w = TextFold.fold(toks[i])
            val replacement = when {
                w in PRONOUNS -> if (weWithYou && w in WITH_YOU) null else PRONOUNS[w]
                else -> table.pairOf(w)
            }
            if (replacement != null) toks[i] = keepCase(toks[i], replacement)
        }
        for (i in 1 until toks.size - 2 step 2) {
            val fix = PREPOSITION_FIX[TextFold.fold(toks[i]) to TextFold.fold(toks[i + 2])]
            if (fix != null) toks[i] = keepCase(toks[i], fix)
        }
        return toks.joinToString("")
    }

    /**
     * «Ты» — в начало своей части предложения. Части разделяют «,» «;» «:» и
     * тире с пробелами вокруг. В части берётся первое «ты»; если все слова
     * перед ним — из [CONJUNCTIONS] (или слов перед ним нет), ничего не
     * меняется. Иначе «ты» встаёт за ведущие союзы части, а без них — в самое
     * начало части. Попало на место первого слова предложения с заглавной —
     * заглавной становится «Ты», а слово — строчным.
     */
    private fun moveTy(sentence: String): String {
        val out = StringBuilder()
        var pos = 0
        var firstPart = true
        val bounds = PART_BOUNDARY.findAll(sentence).toList()
        for (k in 0..bounds.size) {
            val end = if (k < bounds.size) bounds[k].range.first else sentence.length
            val part = sentence.substring(pos, end)
            out.append(if (part.isBlank()) part else moveTyInPart(part, firstPart))
            if (part.isNotBlank()) firstPart = false
            if (k < bounds.size) {
                out.append(bounds[k].value)
                pos = bounds[k].range.last + 1
            }
        }
        return out.toString()
    }

    private fun moveTyInPart(part: String, firstPartOfSentence: Boolean): String {
        val toks = tokens(part)
        val wordIdx = toks.indices.filter { it % 2 == 1 }
        val ty = wordIdx.firstOrNull { TextFold.fold(toks[it]) == "ты" } ?: return part
        val before = wordIdx.filter { it < ty }
        if (before.all { TextFold.fold(toks[it]) in CONJUNCTIONS }) return part
        var lead = 0
        while (lead < before.size && TextFold.fold(toks[before[lead]]) in CONJUNCTIONS) lead++
        val at = before[lead]
        val capital = firstPartOfSentence && at == wordIdx.first() && toks[at].first().isUpperCase()

        // «ты» вынимается вместе с одним пробелом рядом.
        toks.removeAt(ty)
        if (ty < toks.size && toks[ty].startsWith(" ") && toks[ty - 1].endsWith(" ")) {
            toks[ty] = toks[ty].substring(1)
        } else if (toks[ty - 1].endsWith(" ")) {
            toks[ty - 1] = toks[ty - 1].dropLast(1)
        }
        val word = if (capital) {
            toks[at] = toks[at].substring(0, 1).lowercase() + toks[at].substring(1)
            "Ты"
        } else "ты"
        toks.add(at, "$word ")
        return toks.joinToString("")
    }
}
