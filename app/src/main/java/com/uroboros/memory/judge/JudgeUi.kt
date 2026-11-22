package com.uroboros.memory.judge

import android.app.Activity
import android.app.AlertDialog
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Спорные пары на экране: раздел в «Показать» и разбор пары по нажатию.
 *
 * Собран так же, как очередь на проверку: нажимаемые строки прямо в собранном
 * тексте и диалог по нажатию. Не из подражания — это единственный способ
 * нажатия, который на этом экране уже есть, и второй завёл бы два разных обихода
 * для двух почти одинаковых списков.
 *
 * ОТМЕТКА И ОТВЕРЖЕНИЕ — ДВА РАЗНЫХ РЕШЕНИЯ. Отметка («спор настоящий» или
 * «промах судьи») говорит о судье: по ней меряется, как часто он ошибается, и
 * память она не меняет. Отвержение говорит о записи: человек счёл её неверной,
 * и она уходит из ответов агента навсегда (см. HourglassMemory.reject). Поэтому
 * после «спор настоящий» идёт отдельный вопрос — какая из двух неверна, — с
 * ответом «пока не знаю». Слить их значило бы, что отметка сама выбирает
 * сторону, а сторону в этом проекте не выбирает ни один механизм, только
 * человек. После «промаха» отвергать нечего: спора нет.
 *
 * Список показывает только НЕПРОСМОТРЕННЫЕ пары. Просмотренные остаются в
 * хранилище: удалить строку значило бы вернуть пару в очередь, и следующий
 * прогон показал бы её снова.
 */
class JudgeUi(
    private val activity: Activity,
    private val launcher: JudgeLauncher,
    private val linkColor: Int,
    private val scope: CoroutineScope,
    /**
     * Отвергнуть запись по номеру. Приходит снаружи, а не через [launcher]:
     * отвержение — решение о памяти, а не о судье, и судья о нём не знает.
     * Истина — запрос прошёл.
     */
    private val reject: suspend (Long) -> Boolean,
) {

    /**
     * Готовый раздел для «Показать»: заголовок с числами и список пар.
     *
     * [onChanged] зовётся после отметки — экран пересобирается заново, как и
     * после любого другого действия с памятью. Пересборка, а не правка строки:
     * числа в заголовке от отметки тоже меняются, и правка одной строки увела
     * бы их в рассинхрон с телом раздела.
     */
    suspend fun section(modelIdentity: String, onChanged: () -> Unit): CharSequence {
        val counters = launcher.counters(modelIdentity)
        val pending = launcher.pendingDisputes(modelIdentity)
        val manual = manualSection(onChanged)
        val out = SpannableStringBuilder()

        out.append("СУДЬЯ ПАМЯТИ\n")
        if (counters.judged == 0 && counters.directRead == 0 && manual.isEmpty()) {
            // Отдельная ветка: «не запускали» и «запускали, споров нет» — разные
            // ответы, и второй ничего не говорит, пока не случился первый.
            out.append("Разбор не запускался. Долгое нажатие на «Разбор памяти» запускает.")
            return out
        }
        out.append("Разобрано пар: ").append(counters.judged.toString())
        out.append(" · спорных: ").append(counters.disputes.toString()).append("\n")
        out.append("Просмотрено: ").append(counters.reviewed.toString())
        out.append(" · из них промахов судьи: ").append(counters.misses.toString()).append("\n")
        appendRings(out, counters.byRing)
        out.append("Прямой судья: прочитано пар ").append(counters.directRead.toString())
        out.append(" · отброшено (печатному не шли): ").append(counters.directDropped.toString()).append("\n")

        if (pending.isEmpty()) {
            out.append(
                if (counters.disputes == 0) "Спорных пар не нашлось."
                else "Все спорные пары просмотрены."
            )
            return out.append(manual)
        }

        out.append("\nНажмите на пару, чтобы разобрать. Судья ошибается часто: ")
        out.append("это повод посмотреть, а не повод править память.\n")
        for (pair in pending) {
            appendPair(out, pair) { askAbout(modelIdentity, pair, onChanged) }
        }
        return out.append(manual)
    }

    suspend fun manualSection(onChanged: () -> Unit): CharSequence {
        val manual = launcher.pendingManual()
        if (manual.isEmpty()) return ""
        val out = SpannableStringBuilder("\n\nРУЧНОЙ СУД · ${manual.size}\n")
        out.append("Пары после двух отсечек по времени. Нажмите на пару, чтобы разобрать.\n")
        for ((pair, stage) in manual) {
            appendPair(out, pair, "Последняя отсечка — ${stage.label}") {
                askManual(pair, onChanged)
            }
        }
        return out
    }

    private fun appendPair(
        out: SpannableStringBuilder,
        pair: DisputePair,
        detail: String? = null,
        onOpen: () -> Unit,
    ) {
        out.append("\n")
        val start = out.length
        out.append("#").append(pair.firstId.toString()).append(" ").append(pair.first)
        out.append("\n#").append(pair.secondId.toString()).append(" ").append(pair.second)
        detail?.let { out.append("\n").append(it) }
        out.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) = onOpen()
                override fun updateDrawState(ds: TextPaint) {
                    ds.color = linkColor
                    ds.isUnderlineText = false
                }
            },
            start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        out.append("\n")
    }

    private fun askManual(pair: DisputePair, onChanged: () -> Unit) {
        val saveDispute: suspend () -> Unit = { launcher.reviewManual(pair, dispute = true) }
        AlertDialog.Builder(activity)
            .setTitle("Спорят ли записи?")
            .setMessage("№${pair.firstId} · ${pair.first}\n\n№${pair.secondId} · ${pair.second}")
            .setPositiveButton("Есть спор") { _, _ ->
                askWhichWrong(pair, saveDispute, onChanged, saveWithoutRejection = true)
            }
            .setNegativeButton("Нет спора") { _, _ ->
                scope.launch {
                    launcher.reviewManual(pair, dispute = false)
                    onChanged()
                }
            }
            .setNeutralButton("Вернуть в автосуд") { _, _ ->
                scope.launch {
                    launcher.returnToQueue(pair)
                    onChanged()
                }
            }
            .show()
    }

    /**
     * Счёт по кольцам сита — ради одного вопроса: находит ли внешнее кольцо
     * хоть один спор, который человек подтвердил (см. JudgeSieve). Каждое
     * кольцо — своей строкой: слитые в одну цифру, они этот ответ скрыли бы.
     *
     * Строка «вне колец» — только когда такие вердикты есть: это пары,
     * судившиеся до сита, и ровно по ним видно, что сито потеряло бы на уже
     * пройденной истории. Подтверждённый спор в этой строке — прямой довод,
     * что граница сита неверна.
     */
    private fun appendRings(out: SpannableStringBuilder, rings: RingCounters) {
        out.append("По кольцам сита (пар · спорных · подтверждено · промахов):\n")
        appendRing(out, "внутреннее", rings.inner)
        appendRing(out, "внешнее", rings.outer)
        if (rings.outside.judged > 0) appendRing(out, "вне колец, до сита", rings.outside)
    }

    private fun appendRing(out: SpannableStringBuilder, name: String, tally: RingTally) {
        out.append("  ").append(name).append(": ")
        out.append(tally.judged.toString()).append(" · ")
        out.append(tally.disputes.toString()).append(" · ")
        out.append(tally.confirmed.toString()).append(" · ")
        out.append(tally.misses.toString()).append("\n")
    }

    /**
     * Спросить человека про пару.
     *
     * Ответов два, и оба что-то значат: «спор» уводит пару из списка и остаётся
     * в хранилище как подтверждённый, «промах» — то же самое, но идёт в счёт
     * ошибок судьи. Третьей кнопки «просто убрать» нет намеренно: она дала бы
     * лёгкий способ очистить список, ничего не сказав, и счёт промахов сразу
     * перестал бы что-либо мерить.
     *
     * Отмена ничего не записывает. Снять отметку из этого диалога нельзя —
     * просмотренной пары в списке уже нет; способ вернуть её появится тогда,
     * когда понадобится смотреть просмотренные, а не раньше.
     */
    private fun askAbout(modelIdentity: String, pair: DisputePair, onChanged: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Спор или промах?")
            .setMessage(
                "«${pair.first}»\n\nпротив\n\n«${pair.second}»\n\n" +
                    "«Промах судьи» уберёт пару из списка и память не изменит. «Спор " +
                    "настоящий» спросит, какая из двух записей неверна."
            )
            .setPositiveButton("Спор настоящий") { _, _ ->
                askWhichWrong(pair, { launcher.mark(modelIdentity, pair, HumanVerdict.CONFIRMED) }, onChanged)
            }
            .setNegativeButton("Промах судьи") { _, _ ->
                scope.launch {
                    launcher.mark(modelIdentity, pair, HumanVerdict.MISS)
                    onChanged()
                }
            }
            .setNeutralButton("Отмена", null)
            .show()
    }

    /**
     * Какая из двух записей неверна.
     *
     * Отметка «спор настоящий» ставится только вместе с выбором стороны. «Пока
     * не знаю» не записывает ничего, и пара остаётся в списке: отмеченная пара
     * из списка уходит, и вернуться к выбору стороны было бы неоткуда.
     *
     * Отдельного подтверждения нет: до этого вопроса человек уже прочёл обе
     * записи и сказал, что спор настоящий, то есть действие начато двумя
     * нажатиями, а не одним случайным.
     */
    private fun askWhichWrong(
        pair: DisputePair,
        markDispute: suspend () -> Unit,
        onChanged: () -> Unit,
        saveWithoutRejection: Boolean = false,
    ) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Какая запись неверна?")
            .setMessage(
                "1. «${pair.first}»\n\n2. «${pair.second}»\n\n" +
                    "Неверная будет отвергнута: уйдёт из ответов агента навсегда. " +
                    "Из памяти она не стирается, но вернуть её нажатием нельзя."
            )
            .setPositiveButton("Первая") { _, _ -> rejectOne(pair.firstId, markDispute, onChanged) }
            .setNegativeButton("Вторая") { _, _ -> rejectOne(pair.secondId, markDispute, onChanged) }
        if (saveWithoutRejection) {
            dialog.setNeutralButton("Сохранить спор") { _, _ ->
                scope.launch {
                    markDispute()
                    onChanged()
                }
            }
        } else {
            dialog.setNeutralButton("Пока не знаю", null)
        }
        dialog.show()
    }

    /**
     * Отвергнуть и отметить спор настоящим — именно в этом порядке. Отметка
     * убирает пару из списка, поэтому ставится только после того, как
     * отвержение прошло: иначе при сбое базы пара исчезла бы, запись осталась
     * в ответах, и вернуться к ней отсюда было бы нельзя.
     */
    private fun rejectOne(id: Long, markDispute: suspend () -> Unit, onChanged: () -> Unit) {
        scope.launch {
            val ok = reject(id)
            if (ok) markDispute()
            Toast.makeText(
                activity,
                if (ok) "Отвергнута запись №$id"
                else "Не отвергнуто: память не ответила. Запись осталась в ответах, пара — в списке.",
                Toast.LENGTH_LONG,
            ).show()
            onChanged()
        }
    }
}
