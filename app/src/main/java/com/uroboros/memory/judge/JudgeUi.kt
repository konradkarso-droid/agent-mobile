package com.uroboros.memory.judge

import android.app.Activity
import android.app.AlertDialog
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
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
 * ЧЕГО ЗДЕСЬ НЕТ. Разбор память не меняет: отметка человека говорит только о
 * том, что он посмотрел и что решил. Ни скрыть запись, ни поправить её отсюда
 * нельзя, и это не упущение — судья ошибается часто, а действие, которое ничего
 * не меняет, ошибку делает безвредной. Скрыть спорную запись можно там же, где
 * и раньше, через очередь на проверку.
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
                    "Ответ уберёт пару из списка. Память не изменится ни в том, ни в другом " +
                    "случае: чтобы убрать запись из ответов агента, её надо скрыть через " +
                    "очередь на проверку."
            )
            .setPositiveButton("Спор настоящий") { _, _ ->
                scope.launch {
                    launcher.mark(modelIdentity, pair, HumanVerdict.CONFIRMED)
                    onChanged()
                }
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
}
