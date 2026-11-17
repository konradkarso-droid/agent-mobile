package com.uroboros.memory.dream

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.Sentences
import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.Anchors
import com.uroboros.memory.nav.ForeignSpeech
import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.util.TextFold
import kotlin.math.abs
import kotlin.math.ln

/**
 * Сюжет сна — то, что можно рассказать: записи, собравшиеся вокруг одной
 * записи-центра одной темой. Чистая арифметика над записями и словарём, без
 * модели и без базы. Места сюжетов под потолком ночи и почти-дубли решает
 * [DreamWeaver]; здесь — какие сюжеты вообще складываются.
 *
 * КАК СОБИРАЕТСЯ. Центром пробуется каждая запись, от новых к старым: рассказ
 * ночи — о недавнем.
 *  - РАЗГОВОР центра — до [SCENE_RECORDS] его соседей по времени (не дальше
 *    DreamWeaver.TIME_WINDOW_MS) из того же чата, ближние первыми, и только со
 *    словами-темами: «да», «Давай» сцене ничего не дают;
 *  - ЯДРО — одно слово-тема центра, которое есть ещё хотя бы в [MIN_ECHOES]
 *    записях вне разговора и всего не больше чем в [THEME_CEILING]. Из
 *    подходящих слов центра берётся то, чьи записи сильнее всего сходятся с
 *    центром (сумма редкости общих тем у лучших записей); при равенстве — по
 *    алфавиту;
 *  - ЭХО — записи с ядром вне разговора: первыми те, у кого больше общих с
 *    центром тем и они реже, затем более новые. Сколько влезет до
 *    DreamWeaver.MAX_PLOT_RECORDS.
 * Нет ядра — нет и сюжета вокруг этой записи. Записи сюжета идут по времени.
 *
 * ПОЧЕМУ ОДНА ТЕМА, А НЕ ЦЕПОЧКА. Связь по парам (сосед по времени, общее
 * слово через мост) у цепочки в целом общего не оставляет: десять записей —
 * девять разных поводов. Тема, общая для всех эхо, держит сюжет вместе. Одно
 * ядро, а не все слова центра: каждое слово тянуло своих, и сюжет рассыпался
 * на несколько тонких.
 *
 * СЛОВО-ТЕМА ([Words.of]) — имя или предмет по правилу якорей поиска
 * (nav.Anchors: что считается именем и предметом и какие слова разговора о
 * разговоре не берутся — там). Сверх того:
 *  - ключ — начальная форма по словарю, а не приставка поиска: «сне», «сна» и
 *    «сон» — одна тема; у незнакомого словарю слова — его приставка поиска;
 *  - слово с двумя начальными формами («теста» — тест или тесто) темой не
 *    бывает: сюжет склеил бы чужое, и отсеять склейку ниже некому — сюжет
 *    идёт в рассказ;
 *  - предложения-обычаи пропускаются (SentenceKind, «ОБЫЧАЙ»): «Доброй ночи»
 *    связывало записи «ночью»;
 *  - подпись «Имя:» — не тема (ForeignSpeech.unsigned);
 *  - имена агента — не тема: они есть почти в каждом обращении к нему, как «ты».
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - тема — слово, а не смысл: «телефон» и «смартфон» не сходятся;
 *  - только имена и предметы: «тренироваться», «выдумывать» темой не бывают —
 *    глаголы ядро набирало служебные («будем», «можешь»);
 *  - устойчивый оборот склеивает как тема: «по большому счёту» и «на счёт
 *    правил»;
 *  - длинный текст с десятком предметов сходится с центром чаще других и
 *    чаще становится эхом;
 *  - разговор центра в группе бывает не о теме: соседи по времени — не
 *    обязательно ответ на центр;
 *  - центр в сюжете не хранится — только записи по времени;
 *  - без словаря тем нет, и сюжетов нет вовсе.
 */
object DreamPlot {

    /** Сюжет: записи по времени и его ядро — ключ слова-темы ([Words.of]). */
    data class Plot(val recordIds: List<Long>, val core: String)

    /** Сюжеты-кандидаты и сколько записей нашли ядро — для прибора. */
    data class Result(val plots: List<Plot>, val centers: Int)

    /**
     * Слова-темы записи. Без словаря не создаётся ([of]): тогда сюжетов нет, и
     * это видно в отчёте ночи, а не нулём.
     */
    class Words private constructor(private val dictionary: LemmaDictionary, private val agentNames: Set<String>) {

        /** Ключи слов-тем записи — описание объекта, «СЛОВО-ТЕМА». */
        fun of(text: String): Set<String> {
            val out = LinkedHashSet<String>()
            for (sentence in Sentences.split(ForeignSpeech.unsigned(text))) {
                if (SentenceKind.of(sentence).kind == SentenceKind.Kind.SOCIAL) continue
                for (anchor in Anchors.of(sentence, dictionary)) {
                    if (anchor.word in agentNames) continue
                    val lemmas = dictionary.lemmas(anchor.word)
                    when {
                        lemmas.isEmpty() -> out += anchor.prefix
                        lemmas.size == 1 -> out += lemmas.first()
                    }
                }
            }
            return out
        }

        companion object {
            /**
             * [dictionary] null — словарь не загружен, и слов-тем нет. [agentNames] —
             * объявленные владельцем имена агента, как написаны: берётся каждое
             * слово из букв («@AlexAI_agent» — «alexai» и «agent»).
             */
            fun of(dictionary: LemmaDictionary?, agentNames: Collection<String>): Words? {
                dictionary ?: return null
                val names = agentNames.flatMap { NAME_PART.findAll(TextFold.fold(it)).map { m -> m.value }.toList() }
                    .filter { it.length >= MIN_NAME }
                    .toSet()
                return Words(dictionary, names)
            }

            private val NAME_PART = Regex("\\p{L}+")

            /** Короче — не имя, а случайный кусок («ai»). Объявленное число. */
            private const val MIN_NAME = 3
        }
    }

    /**
     * Сюжеты из записей, уже прошедших отбор сна ([dreamers], см. DreamWeaver,
     * «КТО НЕ СНИТСЯ»). Почти-дубли ещё не сняты, мест не назначено.
     */
    fun weave(dreamers: List<Sticker>, words: Words): Result {
        val themes = dreamers.associate { it.id to words.of(it.content) }
        val carriers = HashMap<String, MutableList<Long>>()
        for (record in dreamers) for (t in themes.getValue(record.id)) carriers.getOrPut(t) { mutableListOf() } += record.id
        val total = dreamers.size.toDouble()
        fun rarity(theme: String) = ln(total / carriers.getValue(theme).size)

        val plots = mutableListOf<Plot>()
        var centers = 0
        for (center in dreamers.sortedByDescending { it.id }) {
            val own = themes.getValue(center.id)
            if (own.isEmpty()) continue
            val scene = dreamers.asSequence()
                .filter {
                    it.id != center.id && it.chat == center.chat &&
                        abs(it.createdAt - center.createdAt) <= DreamWeaver.TIME_WINDOW_MS &&
                        themes.getValue(it.id).isNotEmpty()
                }
                .sortedWith(compareBy<Sticker> { abs(it.createdAt - center.createdAt) }.thenBy { it.id })
                .take(SCENE_RECORDS)
                .map { it.id }
                .toList()
            val inScene = scene.toSet() + center.id
            val room = DreamWeaver.MAX_PLOT_RECORDS - inScene.size
            fun outside(theme: String) = carriers.getValue(theme).filter { it !in inScene }
            fun pull(id: Long) = own.filter { it in themes.getValue(id) }.sumOf { rarity(it) }
            val cores = own.filter { carriers.getValue(it).size <= THEME_CEILING && outside(it).size >= MIN_ECHOES }
            if (cores.isEmpty()) continue
            centers++
            fun strength(theme: String) = outside(theme).map { pull(it) }.sortedDescending().take(room).sum()
            val core = cores.sortedWith(compareByDescending<String> { strength(it) }.thenBy { it }).first()
            val echoes = outside(core)
                .sortedWith(compareByDescending<Long> { pull(it) }.thenByDescending { it })
                .take(room)
            val members = inScene + echoes
            val ordered = dreamers.filter { it.id in members }.sortedWith(compareBy<Sticker> { it.createdAt }.thenBy { it.id }).map { it.id }
            plots += Plot(ordered, core)
        }
        return Result(plots, centers)
    }

    /**
     * Сколько соседей центра по разговору входит в сюжет. Объявленное число:
     * сцена задаёт, о чём шла речь, а эхо — сюжет; больше трёх реплик одного
     * разговора вытесняли бы эхо.
     */
    const val SCENE_RECORDS = 3

    /**
     * Сколько записей с ядром вне разговора нужно сюжету. Объявленное число:
     * одна такая запись — простая ассоциация, а не тема.
     */
    const val MIN_ECHOES = 2

    /**
     * В скольких записях материала может быть слово, чтобы оставаться темой.
     * Объявленное число, сверено глазами на одной выгрузке памяти: около
     * двадцатой доли материала (~600 записей). Чаще — слово уже фон разговора,
     * а не тема; при росте памяти доля падает, и число пора пересматривать.
     * Перепроверяется строкой ядер в отчёте ночи.
     */
    const val THEME_CEILING = 30
}
