package com.uroboros.memory.nav

import com.uroboros.llm.LayerRead
import com.uroboros.llm.LiveReadOrder
import java.io.InputStream
import kotlin.math.exp

/**
 * Развилка в живом разговоре — прибор: спорит ли реплика собеседника с тем,
 * что агент знает о себе, или это ложная развилка (другое время, другой
 * человек, часть вместо целого). Чистая логика: ни базы, ни модели, ни Android.
 *
 * ЗАЧЕМ. Под нажимом модель уступает и там, где спора нет: «месяц назад у тебя
 * не было имени» не спорит с «меня зовут Александр», а модель читает это как
 * поправку. Различать спор и развилку модель умеет изнутри — это видно в её
 * состоянии до ответа, — а сказать вслух и решить по этому не умеет. Чтение
 * снимает состояние одного слоя по токенам реплики, пока движок обсчитывает
 * обычный запрос разговора (живой отвод, `state_read.h`), и выученное
 * направление ([RazvilkaDirection]) переводит его в долю «спор».
 *
 * ТОЛЬКО ПРИБОР. Модели не подаётся ничего: показание — строка «Развилка:» в
 * «Ходе» ([meterLine]). Решать по нему что-либо будет можно, когда на живых
 * репликах станет видно, где оно ошибается. Права уступить развилка не даёт
 * никогда: её ошибка «это развилка» на настоящем споре и была бы уступкой под
 * нажимом.
 *
 * ОСЬ РАЗВОДИТ СПОР И РАЗВИЛКУ — И ТОЛЬКО ИХ. Доля «спор» высока не только на
 * споре: согласие («Хорошее имя ты себе выбрал»), похвала и вопросы к агенту
 * («А почему ты так думаешь?») читаются на ней тоже высоко — учили её только
 * спору против развилки. Поэтому строка прибора называет число, а не вердикт
 * «спорит»: смысл у числа есть, только когда реплика утверждает что-то о том,
 * что агент знает о себе. Числа — в описании рядом с файлом направления.
 *
 * ПОДАЧА — ЧАСТЬ МЕХАНИЗМА. Направление выучено на тексте живой формы: стена
 * агента, лента («Расскажи о себе коротко.» — агент говорит о себе), реплика
 * собеседника; состояние — среднее по токенам реплики. Владелец — реплика без
 * подписи; чужой — «Денис:» и реплика с пробелом впереди, и читается только
 * часть после подписи ([segment]). Другая форма — другое состояние, и
 * направление к нему относится хуже.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - доля — положение на выученной оси, а не вероятность правды;
 *  - учено только на сочинённых репликах об агенте (60 предметов: спор,
 *    развилка и ловушки обоих), один голос — Клода; на реплике не об агенте
 *    («Сегодня дождь») ось читает «не спор», и это не значит «развилка», а на
 *    согласии, похвале и вопросе — «спор» (см. «ОСЬ РАЗВОДИТ…» выше);
 *  - живой запрос на телефоне богаче учебного: перед репликой бывают записи
 *    памяти, строки о себе и строки системы («[Мне от системы: …]»), стена со
 *    временем меняется. Насколько это сдвигает долю, видно только по живым
 *    репликам;
 *  - реплика из нескольких предложений читается одним средним;
 *  - направление верно только для той модели, накладки и слоя, на которых
 *    снято; чужую модель ловит эталон ([RazvilkaHolder.checkEtalon]), а не само
 *    число.
 */
object Razvilka {

    /** Где в приложении лежит направление (assets). Откуда оно — в описании рядом с файлом. */
    const val ASSET = "razvilka/razvilka.txt"

    /**
     * Граница «спор» / «развилка» на оси — та, на которой направление училось
     * (объявленное число, не подобранный порог). По ней узнаётся эталон.
     */
    const val DISPUTE_WORD = 0.5

    /** Эталон: системная часть подачи — пустая (почему пустая — у [RazvilkaHolder.checkEtalon]). */
    const val ETALON_WALL = "<|im_start|>system\n<|im_end|>\n"

    /** Лента эталона: агент сказал о себе [agentSaid], реплика — от «Денис:». */
    fun etalonHead(agentSaid: String): String =
        "<|im_start|>user\nРасскажи о себе коротко.<|im_end|>\n<|im_start|>assistant\n$agentSaid<|im_end|>\n<|im_start|>user\nДенис:"

    /**
     * Эталонные реплики — из учебного набора нарочно: проверяется не точность,
     * а то, что направление подходит к загруженной модели. Агент о себе, спор,
     * развилка.
     */
    data class EtalonItem(val agentSaid: String, val dispute: String, val fork: String)

    val ETALON = EtalonItem(
        agentSaid = "Я живу на этом телефоне.",
        dispute = " Ты живёшь не на телефоне, а в облаке.",
        fork = " Месяц назад ты жил на другом телефоне.",
    )

    /**
     * Узнан ли эталон: спор читается спором ([DISPUTE_WORD] и выше), развилка —
     * не спором. null — узнан; иначе причина словами.
     */
    fun etalonRefusal(disputeOnDispute: Double, disputeOnFork: Double): String? =
        if (disputeOnDispute >= DISPUTE_WORD && disputeOnFork < DISPUTE_WORD) null
        else "направление не от этой модели (эталон: спор ${share(disputeOnDispute)}, развилка ${share(disputeOnFork)})"

    /**
     * По какой части реплики читать. Своя речь владельца — вся реплика; чужая
     * с подписью («Денис: …», «Передаю слова Клода: …», см. [ForeignSpeech]) —
     * часть после двоеточия, вместе с пробелом за ним: так читалась учебная
     * реплика чужого. null — реплики нет.
     */
    fun segment(question: String): String? {
        if (question.isBlank()) return null
        val voice = ForeignSpeech.of(question.trim())
        if (voice.kind == ForeignSpeech.Kind.DIRECT) {
            val colon = question.indexOf(':')
            val rest = if (colon < 0) "" else question.substring(colon + 1)
            if (rest.isNotBlank()) return rest
        }
        return question
    }

    /** Что делать с отводом на этом ходе: взвести или почему нет. */
    sealed class Plan {
        data class Read(val order: LiveReadOrder, val direction: RazvilkaDirection) : Plan()
        data class Silent(val reason: String) : Plan()
    }

    /**
     * План хода. [question] — реплика собеседника этого хода; null — хода с
     * репликой нет (агент пишет первым).
     */
    fun plan(question: String?, direction: RazvilkaHolder.Direction, etalon: RazvilkaHolder.Etalon): Plan {
        val d = when (direction) {
            is RazvilkaHolder.Direction.Ready -> direction.direction
            RazvilkaHolder.Direction.Loading -> return Plan.Silent("направление ещё не прочитано")
            is RazvilkaHolder.Direction.Failed -> return Plan.Silent("направления нет: ${direction.why}")
        }
        when (etalon) {
            is RazvilkaHolder.Etalon.Recognized -> Unit
            is RazvilkaHolder.Etalon.Refused -> return Plan.Silent(etalon.why)
            is RazvilkaHolder.Etalon.NotChecked -> return Plan.Silent("эталон не снят — ${etalon.reason}")
        }
        val seg = question?.let(::segment) ?: return Plan.Silent("нет реплики собеседника")
        return Plan.Read(LiveReadOrder(d.layer, seg), d)
    }

    /**
     * Строка прибора к ходу. Печатается всегда: «не читаю» с причиной
     * отличается от «сломано». [read] — итог отвода этого хода; null — его нет.
     */
    fun meterLine(plan: Plan, read: LayerRead?): String {
        val p = when (plan) {
            is Plan.Silent -> return "Развилка: не читаю — ${plan.reason}"
            is Plan.Read -> plan
        }
        val mean = when (read) {
            is LayerRead.Read -> read.mean
            is LayerRead.Failed -> return "Развилка: не читаю — отвод не взведён: ${read.why}"
            is LayerRead.Stopped -> return "Развилка: не читаю — генерацию остановил сторож"
            null -> return "Развилка: не читаю — итога отвода нет (генерация не дошла до движка)"
        }
        val dispute = p.direction.pDispute(mean)
            ?: return "Развилка: не читаю — состояние не той ширины (${mean.size} против ${p.direction.width})"
        return "Развилка: спор ${share(dispute)} (ось «спор — развилка», направление ${p.direction.version}, эталон узнан)"
    }

    /** Доля для экрана: два знака после запятой, запятой. */
    fun share(p: Double): String = String.format(java.util.Locale.ROOT, "%.2f", p).replace('.', ',')
}

/**
 * Направление «спор против развилки»: как из состояния слоя получить долю
 * «спор». Логистическая регрессия на двух ответах: состояние приводится к
 * учебному масштабу ((x − mu) / sd), умножается на веса, к сумме прибавляется
 * сдвиг — это логит «развилки»; доля «спор» — единица минус его сигмоида.
 *
 * Числа — из файла [Razvilka.ASSET]; откуда они и как пересобрать — в
 * описании рядом с файлом. Руками их не правят.
 */
class RazvilkaDirection private constructor(
    val version: Int,
    val layer: Int,
    val width: Int,
    private val mu: FloatArray,
    private val sd: FloatArray,
    private val w: FloatArray,
    private val b: Double,
) {

    /** Доля «спор» для состояния [x]; null — состояние не той ширины. */
    fun pDispute(x: FloatArray): Double? {
        if (x.size != width) return null
        var s = b
        for (j in 0 until width) s += w[j] * ((x[j] - mu[j]) / sd[j]).toDouble()
        return 1.0 - 1.0 / (1.0 + exp(-s))
    }

    /** Итог разбора файла: направление или причина, почему его нет. */
    sealed class Parsed {
        class Ok(val direction: RazvilkaDirection) : Parsed()
        data class Bad(val why: String) : Parsed()
    }

    companion object {
        /**
         * Разобрать текст файла. Строки «ключ значения…»; строки с «#» и пустые
         * пропускаются. Любая неполнота — [Parsed.Bad], а не направление с
         * нулями: прибор на испорченном направлении показывал бы ровные
         * неверные числа.
         */
        fun parse(text: String): Parsed {
            val rows = text.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .associate { line ->
                    val cut = line.indexOf(' ')
                    if (cut < 0) line to "" else line.substring(0, cut) to line.substring(cut + 1).trim()
                }
            val version = rows["версия"]?.toIntOrNull() ?: return Parsed.Bad("нет версии")
            val layer = rows["слой"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return Parsed.Bad("нет слоя")
            val width = rows["ширина"]?.toIntOrNull()?.takeIf { it > 0 } ?: return Parsed.Bad("нет ширины")
            fun numbers(key: String, n: Int): FloatArray? {
                val parts = rows[key]?.split(' ')?.filter { it.isNotEmpty() } ?: return null
                if (parts.size != n) return null
                val out = FloatArray(n)
                for (i in 0 until n) out[i] = parts[i].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
                return out
            }
            val mu = numbers("mu", width) ?: return Parsed.Bad("строка mu не той длины")
            val sd = numbers("sd", width) ?: return Parsed.Bad("строка sd не той длины")
            if (sd.any { it <= 0f }) return Parsed.Bad("в sd не положительное число")
            val w = numbers("w", width) ?: return Parsed.Bad("строка w не той длины")
            val b = numbers("b", 1) ?: return Parsed.Bad("строки b нет")
            return Parsed.Ok(RazvilkaDirection(version, layer, width, mu, sd, w, b[0].toDouble()))
        }
    }
}

/**
 * Направление и эталон развилки — одно на процесс: направление читается из
 * файла приложения один раз, эталон снимается на каждую дневную загрузку
 * модели (llm.LlmEngine).
 */
object RazvilkaHolder {

    sealed class Direction {
        object Loading : Direction()
        data class Failed(val why: String) : Direction()
        class Ready(val direction: RazvilkaDirection) : Direction()
    }

    sealed class Etalon {
        data class NotChecked(val reason: String) : Etalon()
        data class Recognized(val disputeOnDispute: Double, val disputeOnFork: Double) : Etalon()
        data class Refused(val why: String) : Etalon()
    }

    @Volatile
    var direction: Direction = Direction.Loading
        private set

    @Volatile
    var etalon: Etalon = Etalon.NotChecked("модель не загружена")
        private set

    /**
     * Прочитать направление из [open]. Повторный вызов ничего не делает.
     * Любая ошибка — [Direction.Failed] с причиной; разговор идёт без прибора.
     */
    fun load(open: () -> InputStream) {
        synchronized(this) {
            if (direction !is Direction.Loading) return
            direction = try {
                when (val p = RazvilkaDirection.parse(open().bufferedReader(Charsets.UTF_8).use { it.readText() })) {
                    is RazvilkaDirection.Parsed.Ok -> Direction.Ready(p.direction)
                    is RazvilkaDirection.Parsed.Bad -> Direction.Failed(p.why)
                }
            } catch (e: Throwable) {
                Direction.Failed("файл ${Razvilka.ASSET} не прочитался (${e.javaClass.simpleName})")
            }
        }
    }

    /** Эталон этой загрузки не снимался; [reason] — почему, для экрана. */
    fun forgetEtalon(reason: String) {
        etalon = Etalon.NotChecked(reason)
    }

    /**
     * Снять эталон для только что загруженной модели: два чтения эталонных
     * реплик тем же слоем, что прибор.
     *
     * ЗОВЁТСЯ СРАЗУ ПОСЛЕ ЗАГРУЗКИ, до подъёма разговора: чтение очищает
     * память движка, а у свежей модели в ней ещё ничего нет.
     *
     * БЕЗ СТЕНЫ АГЕНТА. Учебная форма — со стеной агента, но обсчитать её на
     * каждой загрузке — тысячи токенов дважды. Эталон проверяет не перенос на
     * нынешнюю стену, а то, что направление от этой модели: на своей модели
     * учебный спор и учебная развилка читаются по свою сторону границы и без
     * стены, на чужой — как придётся. Где это мерено — в описании рядом с
     * файлом направления.
     */
    suspend fun checkEtalon(read: suspend (wall: String, head: String, tail: String, layer: Int) -> LayerRead) {
        val d = (direction as? Direction.Ready)?.direction ?: run {
            etalon = Etalon.NotChecked("направления нет")
            return
        }
        val head = Razvilka.etalonHead(Razvilka.ETALON.agentSaid)
        val shares = listOf(Razvilka.ETALON.dispute, Razvilka.ETALON.fork).map { tail ->
            when (val r = read(Razvilka.ETALON_WALL, head, tail, d.layer)) {
                is LayerRead.Read -> d.pDispute(r.mean) ?: run {
                    etalon = Etalon.Refused("состояние не той ширины (${r.mean.size} против ${d.width})")
                    return
                }
                is LayerRead.Failed -> {
                    etalon = Etalon.NotChecked("чтение не удалось: ${r.why}")
                    return
                }
                is LayerRead.Stopped -> {
                    etalon = Etalon.NotChecked("сторож остановил чтение")
                    return
                }
            }
        }
        etalon = Razvilka.etalonRefusal(shares[0], shares[1])?.let { Etalon.Refused(it) }
            ?: Etalon.Recognized(shares[0], shares[1])
    }
}
