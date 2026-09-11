package com.uroboros.memory

/**
 * Дело — несколько записей об одном предмете, связанных спорами.
 *
 * ЗАЧЕМ. Очередь на проверке показывает записи поштучно, и то, что три из них
 * спорят с одной и той же четвёртой, видно с экрана только сличением цитат.
 * Здесь связи складываются в группы, чтобы человек решал про дело целиком.
 *
 * КОГО МОЖНО ТРОНУТЬ. Только [visibleOpponents]: члены очереди уже скрыты, и
 * убирать их второй раз не из чего. Дело разбирается тем, что видимые стороны
 * уходят под разбор, а не тем, что скрытые уходят глубже.
 *
 * ЧИСЛА РЯДОМ — НЕ УКРАШЕНИЕ. [failedMembers] и [hiddenOutsideList] означают,
 * что дело показано НЕ ЦЕЛИКОМ, и без них человек, убравший видимые стороны,
 * уйдёт уверенным, что по предмету в выдаче не осталось ничего.
 */
internal data class DisputeCluster(
    /** Записи очереди, вошедшие в это дело. Порядок — как на экране. */
    val queueMembers: List<Sticker>,
    /** Видимые записи памяти, с которыми спорит хоть один член дела. */
    val visibleOpponents: List<Sticker>,
    /**
     * Сколько скрытых противников членов дела НЕ попало в показанную часть
     * очереди. Такие записи в дело не входят: их собственные споры неизвестны.
     */
    val hiddenOutsideList: Int,
    /**
     * У скольких членов дела проверка не состоялась. Их рёбра неизвестны, и
     * дело могло бы быть шире — молчание такой записи не значит ничего.
     */
    val failedMembers: Int,
)

/**
 * Сложить отчёты о спорах в дела.
 *
 * СЧИТАЕТСЯ ИЗ ГОТОВОГО. На вход идут те самые отчёты, которые экран очереди и
 * так запрашивает на каждую показанную запись: нового обращения к базе
 * группировка не стоит. [queue] и [reports] — параллельные списки, отчёт под
 * тем же номером, что и запись.
 *
 * ЭТО НЕ ЗАМЫКАНИЕ ПО ПРАВИЛУ ПРОТИВОРЕЧИЯ, и разница не словесная. Дело
 * растёт только по тем рёбрам, что уже лежат в отчётах: спор записи очереди с
 * её собственным противником. Противников противника никто не ищет, поэтому
 * дело не может разойтись на весь тег — а замыкание по правилу разошлось бы,
 * потому что правило не транзитивно.
 *
 * ВЕРШИНА ДЕЛА — ОБЫЧНО ВИДИМАЯ ЗАПИСЬ, А НЕ ЧЛЕН ОЧЕРЕДИ. Проверка стоит на
 * входе, поэтому бит поднимается только пришедшей позже: несколько записей
 * спорят с одной первой, а между собой могут не спорить вовсе. Группировка
 * только по членам очереди дала бы на таком деле ноль групп.
 *
 * РЁБРА КЛАДУТСЯ С ОБЕИХ СТОРОН НАМЕРЕННО, и это не дубль. Правило
 * противоречия симметрично (см. RiskTrigger.contradicts), так что связь A-B
 * видна из обоих отчётов; но у записи, чья проверка не состоялась, своих рёбер
 * нет вовсе, и отчёт соседа — единственный способ узнать о её связях.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - дело собрано по ПОКАЗАННОЙ части очереди. Член дела, не попавший в список
 *    из-за потолка, считается числом [DisputeCluster.hiddenOutsideList] и в
 *    дело не входит;
 *  - у видимой записи своего отчёта нет: известны только те её споры, что
 *    видны со стороны очереди. Настоящее дело вокруг неё может быть шире;
 *  - остывшие противники сюда не попадают вовсе — отчёт ищет их только тогда,
 *    когда горячих нет ни одного;
 *  - отсутствие ребра означает «правило не нашло спора», а не «записи не
 *    спорят»: пересказ теми же по смыслу, но другими словами не берётся.
 *
 * Сжатия путей в объединении нет намеренно: вершин здесь не больше, чем записей
 * на экране, а их потолок — два десятка. Поднимут потолок — это место надо
 * считать заново, вместе с ценой самой отрисовки.
 *
 * @return дела в порядке первого появления их записей на экране. Запись, не
 *         связанная ни с чем, возвращается делом из себя одной: решает экран,
 *         показывать ли такое дело группой.
 */
internal fun clusterDisputes(
    queue: List<Sticker>,
    reports: List<HourglassMemory.DisputeReport>,
): List<DisputeCluster> {
    require(queue.size == reports.size) {
        "списки должны идти парами: записей ${queue.size}, отчётов ${reports.size}"
    }
    if (queue.isEmpty()) return emptyList()

    val parent = HashMap<Long, Long>()

    fun root(id: Long): Long {
        var current = id
        while (true) {
            val up = parent.getOrPut(current) { current }
            if (up == current) return current
            current = up
        }
    }

    fun union(a: Long, b: Long) {
        val rootA = root(a)
        val rootB = root(b)
        if (rootA != rootB) parent[rootB] = rootA
    }

    val queueIds = queue.mapTo(HashSet()) { it.id }
    // Тексты противников нужны экрану, а приходят они только внутри отчётов.
    val known = LinkedHashMap<Long, Sticker>()
    for (sticker in queue) known[sticker.id] = sticker

    val visibleIds = HashSet<Long>()
    val hiddenOutsideIds = HashSet<Long>()

    for ((index, sticker) in queue.withIndex()) {
        // Вершина заводится даже без рёбер: одиночка — тоже дело, и пропасть
        // с экрана она не должна.
        root(sticker.id)
        val report = reports[index]
        for (opponent in report.visible) {
            if (!known.containsKey(opponent.id)) known[opponent.id] = opponent
            visibleIds += opponent.id
            union(sticker.id, opponent.id)
        }
        for (opponent in report.hidden) {
            if (!known.containsKey(opponent.id)) known[opponent.id] = opponent
            if (opponent.id !in queueIds) hiddenOutsideIds += opponent.id
            union(sticker.id, opponent.id)
        }
    }

    // Порядок дел задаётся очередью, а не обходом множества: экран должен
    // выглядеть одинаково при одних и тех же данных.
    val byRoot = LinkedHashMap<Long, MutableList<Long>>()
    for (sticker in queue) byRoot.getOrPut(root(sticker.id)) { mutableListOf() } += sticker.id
    for (id in known.keys) {
        if (id in queueIds) continue
        byRoot.getOrPut(root(id)) { mutableListOf() } += id
    }

    val failedIds = queue
        .filterIndexed { index, _ -> reports[index].failed }
        .mapTo(HashSet()) { it.id }

    return byRoot.values.map { ids ->
        DisputeCluster(
            queueMembers = ids.filter { it in queueIds }.mapNotNull { known[it] },
            visibleOpponents = ids.filter { it in visibleIds && it !in queueIds }.mapNotNull { known[it] },
            hiddenOutsideList = ids.count { it in hiddenOutsideIds },
            failedMembers = ids.count { it in failedIds },
        )
    }
}
