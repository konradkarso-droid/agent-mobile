package com.uroboros.memory

import android.content.Context

/**
 * Чем кончилась перепроверка записи, которую человек собирается принять из
 * очереди.
 *
 * Запечатан: третье значение обязательно, иначе упавшая проверка выглядела бы
 * как чистота — тот самый промах в благополучную сторону, ради которого сбой
 * сравнения при сохранении и поднимает бит.
 *
 * Противник отдаётся текстом, а не номером записи: читать это будет человек, и
 * "спорит с записью 47" ему не говорит ничего.
 */
sealed class AcceptCheck {

    /** Сейчас спорит с лежащей записью. Приёму не мешает — человек решает сам. */
    data class Disputes(val opponentId: Long, val opponentContent: String) : AcceptCheck()

    /** Сейчас ни с чем не спорит. */
    object Clean : AcceptCheck()

    /** Перепроверка не состоялась. Единственный исход, при котором приёма нет. */
    object CheckFailed : AcceptCheck()

    /**
     * Принимается ли запись при таком исходе. Правило живёт на типе, а не у
     * вызывающего: иначе второй экран, написанный позже, решит иначе.
     */
    val allowsAccept: Boolean
        get() = this !is CheckFailed
}

/**
 * Чем кончилось сохранение — в виде, пригодном для чтения человеком.
 *
 * Запечатан: «сохранено» и «не сохранено, такое уже лежит» — разные события, а
 * вызывающему видно одно и то же опустевшее поле ввода. Пока исход был числом,
 * экран не мог их различить и говорил «Сохранено» в обоих случаях.
 *
 * Похожая запись отдаётся ТЕКСТОМ и временем, а не номером, по той же причине,
 * что и противник в [AcceptCheck]: читать это будет человек, и «похоже на
 * запись 47» ему не говорит ничего. Время нужно потому, что похожие записи
 * различаются в основном им — сам текст у них почти одинаков.
 */
sealed class SaveResult {

    /** Запись сохранена, похожих рядом не нашлось. */
    data class Saved(val id: Long) : SaveResult()

    /**
     * Запись сохранена, но рядом уже лежит почти такая же: тексты сходятся
     * всюду, кроме знаков и разделителей. Различие могло быть значащим,
     * поэтому запись сохранена, а не съедена, и человеку сказано.
     */
    data class SavedNearDuplicate(
        val id: Long,
        val similarToContent: String,
        val similarToCreatedAt: Long
    ) : SaveResult()

    /**
     * Запись НЕ сохранена: дословный повтор уже лежащей. Номер здесь — чужой,
     * записи по нему вызывающий не создавал; он дан для показа, а не для того,
     * чтобы считать его номером своей записи.
     */
    data class Duplicate(
        val existingId: Long,
        val existingContent: String,
        val existingCreatedAt: Long
    ) : SaveResult()

    /**
     * Прибавилась ли запись в памяти. Правило живёт на типе, а не у
     * вызывающего: иначе второй экран, написанный позже, решит иначе.
     */
    val stored: Boolean
        get() = this !is Duplicate
}


/**
 * Фасад памяти — единственная точка записи и чтения Sticker'ов для остального кода.
 *
 * Дыра №4 (аудит 2026-08-21): у saveEvent() не было параметров source/confidence,
 * хотя поля на Sticker существуют и подтверждены на устройстве (item 3, трек A).
 * Из-за этого ВСЁ, включая вакцина-строки, порождённые самим агентом, писалось со
 * значениями по умолчанию USER_STATED/OBSERVED — то есть агент помечал собственные
 * выводы как сказанное пользователем и наблюдённое. Это ровно обратное замыслу трека A.
 *
 * Решение: у source и confidence НЕТ значений по умолчанию. Каждый вызывающий обязан
 * назвать происхождение явно. Умолчание здесь было бы тихой подменой провенанса —
 * тот же класс ошибки, что и no-authority-via-text-claim, только на стороне записи.
 * Значения по умолчанию на самой сущности Sticker сознательно ОСТАВЛЕНЫ: они
 * описывают строки, созданные до миграции 6→7, и к новым записям отношения не имеют.
 *
 * Что НЕ трогается этой правкой (осознанно, см. разбор): dao.getAll() в горячем пути
 * getContext() и отсутствие транзакции при обновлении слоёв. Это вторая половина
 * дыры №4, она затрагивает уже оттестированную логику ранжирования и делается
 * отдельно, не в один заход с изменением контракта записи.
 */
class TrustedMediator(context: Context) {
    private val dao = MemoryDatabase.getInstance(context).stickerDao()

    /**
     * Прибор входа в очередь на проверку. Собирается здесь по той же причине,
     * что и канарейка: одна точка сборки памяти, и укладка на диск достаётся
     * прибору отсюда, а не через тех, кто зовёт сохранение.
     *
     * Без укладки прибор тоже работает, но считает лишь до перезапуска, и
     * говорит об этом в своём отчёте. Здесь она подключена.
     */
    private val reviewWitness = ReviewWitness(PrefsReviewWitnessStore(context))

    private val hourglass = HourglassMemory(dao, reviewWitness)
    private val snapshotDao = MemoryDatabase.getInstance(context).lastStableSnapshotDao()

    /**
     * Item 6, подшаг 1c (2026-08-22): канарейка ходит в DAO через фасад, а не напрямую
     * из UI — иначе L5 обращался бы в L0 через голову L2 (ARCHITECTURE.md §1).
     * MemoryCanary создаётся здесь один раз: он без состояния, хранит только ссылку
     * на dao, так что отдельный экземпляр на каждый вызов был бы мусором на ровном месте.
     */
    private val canary = MemoryCanary(dao)

    /**
     * Готовый текст снимка состояния памяти. ТОЛЬКО ЧТЕНИЕ — ни одной записи.
     *
     * Сознательно возвращается строка, а не MemorySnapshot: снимок "до" сейчас
     * никуда не сохраняется (это было бы либо новая таблица, либо стикер — а стикер
     * изменил бы то самое число total, которое канарейка и меряет). Пользователь
     * фиксирует "до" сам, до первого живого прогона sweep'а. Когда появится место
     * для хранения baseline, здесь добавится второй метод, возвращающий сам объект.
     */
    suspend fun memoryCanaryReport(): String = canary.format(canary.snapshot())

    /**
     * Готовый текст показаний прибора очереди. ТОЛЬКО ЧТЕНИЕ.
     *
     * Отдельный отчёт, а не строка в снимке памяти, и слить их нельзя. Снимок
     * считает состояние базы, прибор — поток входов в очередь; числа приходят с
     * разных сторон и служат проверкой друг другу, а слитые в один отчёт это
     * свойство потеряли бы. Что именно с чем сверяется — в шапке
     * [ReviewWitness]; повторять здесь незачем, две копии разойдутся.
     *
     * suspend не потому, что ходит в базу, — прибор в неё не ходит вовсе, — а
     * потому, что первое обращение поднимает показания с диска.
     */
    suspend fun reviewWitnessReport(): String = reviewWitness.report()

    /**
     * Item 6, ремонт данных (2026-08-22). Разовый вызов: возвращает в спектр
     * записи, застрявшие без expiryTime из-за прежнего храповика прогрева.
     * Проброс без логики — решение о том, что и куда чинить, принимает
     * HourglassMemory через Prism.classify, а не фасад.
     *
     * @return сколько записей было починено.
     */
    suspend fun repairStuckLayers(): Int = hourglass.repairStuckLayers()

    /**
     * Разовый ремонт провенанса (2026-08-24). Перемаркирует исторические
     * вакцина-строки, помеченные как сказанное пользователем, в вывод агента.
     * Проброс без логики — критерий и значения решает HourglassMemory.
     *
     * @return сколько записей перемаркировано.
     */
    suspend fun repairToteProvenance(): Int = hourglass.repairToteProvenance()

    /**
     * Сохраняет запись в память. source/confidence обязательны — см. KDoc класса.
     *
     * Ориентир для вызывающих:
     *  - ввод пользователя         -> USER_STATED  + OBSERVED
     *  - вывод/итог работы агента  -> AGENT_INFERRED + INFERRED
     *  - распознанный текст с фото -> OCR_EXTRACTED + UNCERTAIN
     *
     * ВОЗВРАЩАЕТ ИСХОД, А НЕ НОМЕР. Дословный повтор в память не кладётся, и
     * номера новой записи в этом случае не существует. Пока возвращалось
     * число, отличить это от обычного сохранения было нельзя, и экран говорил
     * «Сохранено» там, где ничего не прибавилось.
     *
     * Что считать повтором и что почти-повтором, решает HourglassMemory;
     * фасад лишь переводит исход в читаемый вид. Границы отсева — в KDoc
     * HourglassMemory.saveEventChecked, повторять их здесь незачем: две копии
     * разойдутся.
     */
    suspend fun saveEvent(
        content: String,
        source: SourceKind,
        confidence: ConfidenceLevel,
        tag: String = "general"
    ): SaveResult {
        val sticker = Sticker(
            content = content,
            tag = tag,
            source = source.name,
            confidence = confidence.name
        )
        return describeSave(hourglass.saveEventChecked(sticker))
    }

    /**
     * Достать текст похожей записи по номеру.
     *
     * Пропавшая запись не выдаётся за отсутствие похожей: отсев её нашёл, а
     * показать нечем. Человек увидит это прямо, а не пустую строку. Та же
     * форма, что у [describe] для противника.
     */
    private suspend fun describeSave(outcome: HourglassMemory.SaveOutcome): SaveResult =
        when (outcome) {
            is HourglassMemory.SaveOutcome.Saved -> SaveResult.Saved(outcome.id)

            is HourglassMemory.SaveOutcome.SavedNearDuplicate -> SaveResult.SavedNearDuplicate(
                id = outcome.id,
                similarToContent = dao.getById(outcome.similarToId)?.content
                    ?: "запись не найдена",
                similarToCreatedAt = outcome.similarToCreatedAt
            )

            is HourglassMemory.SaveOutcome.Duplicate -> SaveResult.Duplicate(
                existingId = outcome.existingId,
                existingContent = dao.getById(outcome.existingId)?.content
                    ?: "запись не найдена",
                existingCreatedAt = outcome.existingCreatedAt
            )
        }

    /**
     * Достать записи под запрос. purpose обязателен и умолчания не имеет — по той же
     * причине, что source/confidence у saveEvent: умолчание молча приписало бы
     * обращению чужой смысл, только здесь на стороне чтения.
     *
     * Что от него зависит: засчитывается ли записям польза (item 3). Засчитывается
     * лишь ANSWERING_USER, и лишь тем, кто нашёлся по словам вопроса. Смысл значений
     * и почему ось именно "зачем", а не "кто спрашивает", — у RetrievalPurpose.
     *
     * Фасад решения не принимает: он не выбирает purpose за вызывающего и не
     * подставляет его по догадке. Назвать цель может только тот, кто знает, ради чего
     * спрашивает.
     */
    suspend fun getContextFor(
        purpose: RetrievalPurpose,
        query: String? = null,
        limit: Int = 10
    ): List<Sticker> {
        return hourglass.getContextFor(purpose, query, limit)
    }

    /**
     * То же самое плюс готовая строка о том, чем закончился отбор: сколько слов
     * искали, сколько записей нашлось, сколько прошло, сколько отсеяно и почему.
     *
     * Проброс без логики — состояния отбора и их формулировки решает
     * HourglassMemory, фасад не пересказывает их своими словами. Наружу идёт
     * готовый текст, а не сам тип: за пределами памяти он нужен только чтобы его
     * показать, и ветвиться по нему в UI было бы решением об отборе, принятым
     * мимо того, кто отбирает. Та же форма, что у memoryCanaryReport().
     */
    suspend fun getContextWithSummary(
        purpose: RetrievalPurpose,
        query: String? = null,
        limit: Int = 10
    ): ContextResult {
        return hourglass.getContextWithSummary(purpose, query, limit)
    }

    suspend fun totalStickers(): Int = dao.count()
    suspend fun getPendingReview(): List<Sticker> = dao.getPendingReview()
    suspend fun clearAllPendingReview(): Int = dao.clearAllReviewPending()

    /**
     * С кем запись спорит сейчас и где эти записи лежат. ТОЛЬКО ЧТЕНИЕ,
     * только для показа: на приём этот отчёт не влияет, приём идёт через
     * [checkBeforeAccept] и [acceptFromReview].
     *
     * Проброс без логики — что считать спором и какие слои смотреть, решает
     * HourglassMemory. Что означает пустой отчёт и чего он не умеет —
     * в KDoc HourglassMemory.disputesOf; повторять здесь незачем, две копии
     * разойдутся.
     */
    suspend fun disputesOf(sticker: Sticker): HourglassMemory.DisputeReport =
        hourglass.disputesOf(sticker)

    /**
     * Перепроверить запись перед тем, как показать человеку подтверждение
     * приёма. ТОЛЬКО ЧТЕНИЕ — бит не трогается.
     *
     * Отвечает на вопрос "спорит ли эта запись СЕГОДНЯ", а не "почему она сюда
     * попала": причина постановки нигде не сохраняется, и чистый исход у
     * записи, попавшей в очередь по сбою сравнения, совершенно законен.
     */
    suspend fun checkBeforeAccept(sticker: Sticker): AcceptCheck =
        describe(hourglass.recheckForAccept(sticker))

    /**
     * Принять запись из очереди, пройдя перепроверку.
     *
     * Смысл действия ровно один и назван здесь, а не на экране: запись
     * перестаёт быть скрытой, возвращается в выдачу отбора и снова может уйти
     * в контекст модели. Отказа и отсрочки в этом механизме нет — они появятся
     * вместе с судьёй памяти, которого сегодня не существует, а до тех пор три
     * исхода вместо одного означали бы состояния, которые никто не разбирает.
     *
     * НЕОБРАТИМО, и обратного действия здесь нет намеренно. Вернуть запись в
     * карантин можно только тем же способом, каким она туда попала: сохранить
     * спорное утверждение заново, и бит поставит RiskTrigger. Отдельный путь
     * "вернуть" был бы вторым местом, где решается, что считать спорным.
     *
     * Кто принимает: только человек. Пути для агента отсюда нет и не заводится —
     * назначать его судьёй собственной памяти значит отдать ему решение о том,
     * что он потом прочитает как факт.
     *
     * ПОЧЕМУ ПРОВЕРКА ЗДЕСЬ, А НЕ ТОЛЬКО НА ЭКРАНЕ. Приём необратим и ниже него
     * никого нет: отсеять лишнее некому. Поэтому отказ при несостоявшейся
     * проверке живёт на фасаде, где его нельзя обойти, заведя второй экран.
     *
     * ПОЧЕМУ СПОР НЕ ЗАПРЕЩАЕТ ПРИЁМ. Запись, попавшая сюда по настоящему
     * спору, будет спорить и завтра — противная запись никуда не делась. Запрет
     * сделал бы очередь неразбираемой ровно для тех записей, ради которых она
     * заведена. Механизм показывает, решает человек.
     *
     * Проверка идёт заново, а не берётся из [checkBeforeAccept]: между показом
     * и нажатием проходит время, и решение о необратимом действии принимается
     * по свежему состоянию, а не по тому, что было на экране.
     *
     * @return исход перепроверки. Запись принята тогда и только тогда, когда
     *         [AcceptCheck.allowsAccept] истинно.
     */
    suspend fun acceptFromReview(sticker: Sticker): AcceptCheck {
        val check = describe(hourglass.recheckForAccept(sticker))
        if (check.allowsAccept) dao.clearReviewPending(sticker.id)
        return check
    }

    /**
     * Достать текст противника по номеру записи.
     *
     * Пропавшая запись не считается чистотой: спор был найден, а показать его
     * нечем. Человек увидит это прямо, а не пустую строку.
     */
    private suspend fun describe(outcome: HourglassMemory.RecheckOutcome): AcceptCheck =
        when (outcome) {
            is HourglassMemory.RecheckOutcome.Disputes -> AcceptCheck.Disputes(
                opponentId = outcome.withId,
                opponentContent = dao.getById(outcome.withId)?.content
                    ?: "запись не найдена"
            )
            HourglassMemory.RecheckOutcome.Clean -> AcceptCheck.Clean
            HourglassMemory.RecheckOutcome.Failed -> AcceptCheck.CheckFailed
        }

    /** Item 6b/8: перезаписывает единственный снимок последнего стабильного состояния. */
    suspend fun saveStableSnapshot(code: String) {
        snapshotDao.save(LastStableSnapshot(code = code))
    }

    /** Item 6b/8: читает снимок последнего стабильного состояния (null, если ещё не было). */
    suspend fun getStableSnapshot(): LastStableSnapshot? = snapshotDao.get()
}
