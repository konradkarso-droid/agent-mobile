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
        val out = SpannableStringBuilder()

        out.append("СУДЬЯ ПАМЯТИ\n")
        if (counters.judged == 0) {
            // Отдельная ветка: «не запускали» и «запускали, споров нет» — разные
            // ответы, и второй ничего не говорит, пока не случился первый.
            out.append("Разбор не запускался. Долгое нажатие на «Разбор памяти» запускает.")
            return out
        }
        out.append("Разобрано пар: ").append(counters.judged.toString())
        out.append(" · спорных: ").append(counters.disputes.toString()).append("\n")
        out.append("Просмотрено: ").append(counters.reviewed.toString())
        out.append(" · из них промахов судьи: ").append(counters.misses.toString()).append("\n")

        if (pending.isEmpty()) {
            out.append(
                if (counters.disputes == 0) "Спорных пар не нашлось."
                else "Все спорные пары просмотрены."
            )
            return out
        }

        out.append("\nНажмите на пару, чтобы разобрать. Судья ошибается часто: ")
        out.append("это повод посмотреть, а не повод править память.\n")
        for (pair in pending) {
            out.append("\n")
            val start = out.length
            out.append("#").append(pair.firstId.toString()).append(" ").append(pair.first)
            out.append("\n")
            out.append("#").append(pair.secondId.toString()).append(" ").append(pair.second)
            out.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = askAbout(modelIdentity, pair, onChanged)
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = linkColor
                        ds.isUnderlineText = false
                    }
                },
                start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            out.append("\n")
        }
        return out
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
                askWhichWrong(modelIdentity, pair, onChanged)
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
    private fun askWhichWrong(modelIdentity: String, pair: DisputePair, onChanged: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Какая запись неверна?")
            .setMessage(
                "1. «${pair.first}»\n\n2. «${pair.second}»\n\n" +
                    "Неверная будет отвергнута: уйдёт из ответов агента навсегда. " +
                    "Из памяти она не стирается, но вернуть её нажатием нельзя."
            )
            .setPositiveButton("Первая") { _, _ -> rejectOne(modelIdentity, pair, pair.firstId, onChanged) }
            .setNegativeButton("Вторая") { _, _ -> rejectOne(modelIdentity, pair, pair.secondId, onChanged) }
            .setNeutralButton("Пока не знаю", null)
            .show()
    }

    /**
     * Отвергнуть и отметить спор настоящим — именно в этом порядке. Отметка
     * убирает пару из списка, поэтому ставится только после того, как
     * отвержение прошло: иначе при сбое базы пара исчезла бы, запись осталась
     * в ответах, и вернуться к ней отсюда было бы нельзя.
     */
    private fun rejectOne(modelIdentity: String, pair: DisputePair, id: Long, onChanged: () -> Unit) {
        scope.launch {
            val ok = reject(id)
            if (ok) launcher.mark(modelIdentity, pair, HumanVerdict.CONFIRMED)
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
