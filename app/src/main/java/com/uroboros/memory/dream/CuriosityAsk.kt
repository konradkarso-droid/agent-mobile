package com.uroboros.memory.dream

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.Sentences

/**
 * Первый выход пружины любопытства: спросить владельца о связи записей сна,
 * который сжал её сильнее всех. Решение чистое: ни базы, ни Android — затем и вынесено, чтобы
 * правила закрепить тестами. Входы собирает тот, кто собирает ход; база — в
 * [CuriosityAskMarker].
 *
 * УСЛОВИЕ — ВКЛАД ЛИДЕРА, А НЕ СУММА. Спрашивается об одном сне, а давление —
 * сумма по всем снам окна: сумма может перейти порог, когда ни один сон сам по
 * себе не выделяется. Поэтому здесь смотрится вклад сна-лидера
 * ([CuriosityPressure.Leader.contribution]); сумма остаётся на приборе.
 *
 * ВЫХОД ТЕРМИНАЛЬНЫЙ. Ниже нет никого, кто отсеял бы лишний вопрос, поэтому
 * всякое сомнение решается в сторону «не спрашивать». Пропуск безвреден:
 * счёт никуда не девается, и спросить можно будет позже.
 *
 * НЕ БОЛЬШЕ ОДНОГО ВОПРОСА БЕЗ ОТВЕТА. Пока владелец после вопроса не прислал
 * ни одной реплики, нового вопроса нет. Любая следующая реплика снимает
 * ожидание; ответила ли она о сне — решает подхват ([DreamPickup]). Реплика
 * считается присланной, когда ушла в движок (та же мерка, что «подан»), а
 * решение принимается раньше, при сборке, — поэтому на ходе сразу за вопросом
 * новый вопрос не предлагается, даже если эта самая реплика и есть ответ.
 *
 * ГДЕ ЖИВЁТ «ЖДЁТ ОТВЕТА». Время вопроса — в базе ([Dream.askedAt], самое
 * позднее), время последней реплики владельца — на диске
 * (llm.ConversationTimes), поэтому ожидание переживает кому. Не прочиталось
 * второе — не спрашивать: неизвестное решается в сторону молчания.
 *
 * СЛОВА АГЕНТУ — НЕ «ТЕБЕ ИНТЕРЕСНО» И НЕ СОН. Давление — совпадение слов, а
 * не интерес, поэтому строка не называет интереса. Сна модель тоже не видит
 * (почему — в шапке [DreamRecall]): строка перечисляет записи сна-лидера и
 * говорит, что агента занимает их связь.
 *
 * Порог, веса и числа агент не меняет: их здесь нечем менять из ответа модели.
 *
 * ВТОРОЙ ИСТОЧНИК — ПРОБЕЛ ([decideGap]). Агент когда-то признал, что не знает
 * ([Gaps]), и владелец сейчас рассказывает о том же — значит, есть кого
 * спросить, и вопрос по теме разговора. Давление пробела со сном не
 * складывается: это разные источники одного выхода, и вопрос на ответ один.
 * Когда годны оба, берётся пробел: он касается реплики по построению, сон —
 * нет. Запись, найденная к реплике, приходит в том же ходе обычной записью и
 * служит зацепкой для вопроса — отдельной строки у неё нет.
 *
 * ТРЕТИЙ ИСТОЧНИК — РАССКАЗ ([decideTell]). У сна есть принятый вывод
 * ([Conclusion]), и о нём ещё не рассказано: агент не спрашивает о связи, а
 * сам говорит, что подумал во сне, — с подписью сна ([Conclusion.dreamt]),
 * никогда как факт и никогда как слова собеседника. Порядок источников:
 * пробел, затем рассказ, затем вопрос. Пробел касается реплики по
 * построению; рассказ — готовая мысль; вопрос — сырая связь. Порог у
 * рассказа свой и ниже ([TELL_MIN]): вывод уже отобран проверкой, и ждать,
 * пока сон наберёт вклад вопроса, значило бы почти никогда не рассказать.
 * Рассказ ставит ту же метку, что вопрос ([Dream.askedAt]), поэтому «не
 * больше одного без ответа» у них общее.
 *
 * Спрашивается только при ясном: реплика владельца — утверждение во всех
 * предложениях (SentenceKind; сомнительный вид — не утверждение: вопрос или
 * просьба владельца оставляют ход ему), прошлый ответ агента без вопроса
 * (иначе реплика — ответ на него, и новый вопрос сразу за ним — допрос), вопрос
 * о сне ответа не ждёт. Пробел годен, если он открыт, касается реплики и
 * признан на рассказе владельца, а не на его вопросе: на вопрос владельца
 * («Кто Админ?», «Из какого дерева колодка?») переспрашивать его самого нечем —
 * он спрашивал, потому что ответа ждал от агента.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - «спрошен» у сна значит «предложение спросить ушло в модель», а не «модель
 *    спросила»: отличить второе надёжно нечем. Сон, о котором модель
 *    промолчала, всё равно разряжен. У пробела иначе: спрошен он, только если
 *    агент задал вопрос о предмете ([Gaps]), — промолчал, и пробел предложат
 *    снова на следующем рассказе по теме;
 *  - пробел годен по основам слов, а не по смыслу (там же, в [Gaps]); признан ли
 *    он на рассказе — по виду предложений ([SentenceKind]), со всеми их
 *    промахами;
 *  - ответ ловится только следующей репликой, и только если ход с вопросом
 *    лёг в ленту: на нуле токенов сон отмечен спрошенным, а проверять ответ
 *    нечем (см. [DreamPickup]);
 *  - записи лидера могут прийти в той же реплике и обычными записями, если
 *    их принесла ассоциация к ответу ([DreamRecall]): выход её не трогает;
 *  - порог объявлен, а не измерен.
 */
object CuriosityAsk {

    /**
     * Порог вклада лидера. Объявленное число, не подобранное: два подхвата
     * владельцем или три вспоминания агентом. Настоящее — из замеров по
     * прибору (строка «Спросить: … вклад лидера N из 6»).
     */
    const val MIN_CONTRIBUTION = 6

    /**
     * Порог вклада для рассказа вывода. Объявленное число, не подобранное:
     * владелец хоть раз подхватил сон (вес 3) или агент дважды его вспомнил.
     * Одного вспоминания агентом (вклад 2) мало — тогда рассказывался бы
     * любой вывод: выводы пробуются только по снам с вкладом. Почему ниже
     * [MIN_CONTRIBUTION] — в KDoc объекта, «ТРЕТИЙ ИСТОЧНИК».
     */
    const val TELL_MIN = 3

    sealed class Decision {
        /** Спросить о сне [leader]. */
        data class Ask(val leader: CuriosityPressure.Leader) : Decision()

        /** Не спрашивать; [reason] — почему, словами для прибора. */
        data class Refuse(val reason: String) : Decision()
    }

    /**
     * Ждёт ли прошлый вопрос ответа.
     *
     * @param lastAskedAt когда спрошено в последний раз; null — ни разу.
     * @param lastOwnerReplyAt когда ушла в движок последняя реплика
     *   владельца; null — ни одной на диске (см. «ГДЕ ЖИВЁТ» выше).
     */
    fun awaiting(lastAskedAt: Long?, lastOwnerReplyAt: Long?): Boolean =
        lastAskedAt != null && (lastOwnerReplyAt == null || lastOwnerReplyAt <= lastAskedAt)

    /**
     * Решение. Порядок отказов — как у SelfJudgeDecision.refusal: первое
     * невыполненное условие и называется.
     */
    fun decide(pressure: CuriosityPressure.Result, awaitingAnswer: Boolean): Decision {
        if (awaitingAnswer) return Decision.Refuse("прошлый вопрос без ответа")
        val leader = pressure.leader ?: return Decision.Refuse("лидера нет")
        // Лидер по построению не молчит и не спрошен (см. CuriosityPressure);
        // проверка здесь — страховка на случай, если построение поменяют.
        if (leader.dream.askedAt != null) return Decision.Refuse("лидер уже спрошен")
        if (leader.contribution < MIN_CONTRIBUTION) {
            return Decision.Refuse("вклад лидера ${leader.contribution} из $MIN_CONTRIBUTION")
        }
        return Decision.Ask(leader)
    }

    /**
     * Строка для модели: что агента занимает — от первого лица, как подписи
     * записей (см. ProvenanceLabels), — и просьба во втором лице, как строка
     * состояния (см. SelfState).
     *
     * «Пользователь», а не «владелец»: этим словом модель уже зовут
     * собеседника в метках записей (ProvenanceLabels), второе имя для того же
     * человека было бы для неё третьим лицом в разговоре.
     */
    fun line(leader: CuriosityPressure.Leader): String =
        "${about(leader)} Если к месту — спроси пользователя об этом, одним вопросом."

    /**
     * Строка для пути «агент пишет первым» (initiative.CuriositySource).
     * Отличается от [line] только последней фразой: там владелец спросил о
     * своём, и модель решает, уместен ли вопрос; здесь владелец молчит,
     * других дел у хода нет, и «если к месту» оставляло бы модели лазейку
     * прислать рассуждение вместо вопроса.
     */
    fun lineFirst(leader: CuriosityPressure.Leader): String =
        "${about(leader)} Спроси пользователя об этом, одним вопросом."

    /**
     * Общая часть обеих строк: записи лидера целиком, в порядке его цепочки.
     * Без ссылок на записи ответа: строка стоит выше записей, и ссылаться ей
     * не на что.
     */
    private fun about(leader: CuriosityPressure.Leader): String =
        "Меня занимает, как связано: ${leader.records.joinToString(", ") { "«${it.content}»" }}."

    /** Решение о рассказе вывода сна. */
    sealed class TellDecision {
        /** Рассказать вывод [teller]. */
        data class Tell(val teller: CuriosityPressure.Teller) : TellDecision()

        /** Не рассказывать; [reason] — почему, словами для прибора. */
        data class Refuse(val reason: String) : TellDecision()
    }

    /**
     * Рассказать ли вывод сна. Условия реплики владельца — те же, что у
     * вопроса о сне ([decide]): ожидание ответа общее. Берётся первый в
     * [CuriosityPressure.Result.toTell] — с наибольшим вкладом.
     */
    fun decideTell(pressure: CuriosityPressure.Result, awaitingAnswer: Boolean): TellDecision {
        if (awaitingAnswer) return TellDecision.Refuse("прошлый вопрос без ответа")
        val first = pressure.toTell.firstOrNull() ?: return TellDecision.Refuse("выводов к рассказу нет")
        if (first.leader.contribution < TELL_MIN) {
            return TellDecision.Refuse("вклад сна с выводом ${first.leader.contribution} из $TELL_MIN")
        }
        return TellDecision.Tell(first)
    }

    /**
     * Строка для модели о выводе: мысль с подписью сна и просьба рассказать,
     * сказав, откуда она. Записи сна не перечисляются: вывод сложен из их
     * слов, а сами записи, если нужны, приходят в ходе обычным путём.
     */
    fun tellLine(teller: CuriosityPressure.Teller): String =
        "${Conclusion.dreamt(teller.text)} Если к месту — расскажи пользователю об этом одной фразой " +
            "и скажи, что это пришло во сне."

    /** Решение о вопросе по пробелу. */
    sealed class GapDecision {
        /** Спросить о пробеле [gap]. */
        data class Ask(val gap: Gaps.Gap) : GapDecision()

        /** Не спрашивать; [reason] — почему, словами для прибора. */
        data class Refuse(val reason: String) : GapDecision()
    }

    /**
     * Вопрос по пробелу к реплике [reply]. Условия — в KDoc объекта («ВТОРОЙ
     * ИСТОЧНИК»); первое невыполненное и называется. Из нескольких годных
     * берётся признанный последним.
     *
     * @param previousAnswer прошлый ответ агента в ленте; null — ответа нет.
     * @param awaitingDream вопрос о сне ждёт ответа ([awaiting]).
     */
    fun decideGap(gaps: List<Gaps.Gap>, reply: String, previousAnswer: String?, awaitingDream: Boolean): GapDecision {
        val readings = Sentences.split(reply).map { SentenceKind.of(it) }
        readings.firstOrNull { it.kind != SentenceKind.Kind.STATEMENT }?.let {
            val what = if (it.kind == SentenceKind.Kind.QUESTION) "вопрос" else "просьба"
            return GapDecision.Refuse("реплика — $what, ход у владельца")
        }
        if (previousAnswer != null && Sentences.split(previousAnswer).any { it.trimEnd().endsWith('?') }) {
            return GapDecision.Refuse("реплика — после вопроса агента")
        }
        if (awaitingDream) return GapDecision.Refuse("вопрос о сне без ответа")
        val touching = gaps.filter { it.state == Gaps.State.OPEN && Gaps.touches(it, reply) }
        if (touching.isEmpty()) return GapDecision.Refuse("открытого пробела по теме нет")
        val told = touching.filter { gap ->
            Sentences.split(gap.question).any {
                SentenceKind.of(it).kind == SentenceKind.Kind.STATEMENT && Gaps.touches(gap, it)
            }
        }
        val gap = told.lastOrNull()
            ?: return GapDecision.Refuse("пробел по теме признан на вопросе владельца — переспрашивать нечем")
        return GapDecision.Ask(gap)
    }

    /**
     * Строка для модели о пробеле: своя фраза признания — как своя речь в
     * записях (ProvenanceLabels), — и та же просьба, что в [line].
     */
    fun gapLine(gap: Gaps.Gap): String =
        "Меня занимает то, чего я не знал, когда говорил: «${gap.admission.trim()}». " +
            "Если к месту — спроси пользователя об этом, одним вопросом."

    /**
     * Строка прибора обоих источников. Пробел, если спрошен, перебивает сон
     * (см. KDoc объекта); иначе — решение о сне и почему не о пробеле.
     */
    fun meter(gap: GapDecision, dream: Decision): String = when (gap) {
        is GapDecision.Ask -> "Спросить: в этой реплике предложено спросить о пробеле «${gap.gap.admission.trim()}»"
        is GapDecision.Refuse -> meter(dream) + " · о пробеле не спрашиваю — ${gap.reason}"
    }

    /**
     * Строка прибора всех трёх источников в их порядке (см. «ТРЕТИЙ
     * ИСТОЧНИК»): что предложено и почему молчат те, что выше.
     */
    fun meter(gap: GapDecision, tell: TellDecision, dream: Decision): String = when {
        gap is GapDecision.Ask -> meter(gap, dream)
        tell is TellDecision.Tell -> "Спросить: в этой реплике предложено рассказать вывод сна " +
            "«${tell.teller.text}» · о пробеле не спрашиваю — ${(gap as GapDecision.Refuse).reason}"
        else -> meter(gap, dream) + " · рассказать: ${(tell as TellDecision.Refuse).reason}"
    }

    /** Строка прибора. Печатается всегда: молчащий выход неотличим от сломанного. */
    fun meter(decision: Decision): String = when (decision) {
        is Decision.Refuse -> "Спросить: не спрашиваю — ${decision.reason}"
        is Decision.Ask -> "Спросить: в этой реплике предложено спросить о сне " +
            "«${DreamView.brief(decision.leader.brief.kind, decision.leader.brief.texts)}»"
    }

}
