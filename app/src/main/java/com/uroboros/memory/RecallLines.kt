package com.uroboros.memory

import com.uroboros.memory.nav.Coordinates
import com.uroboros.memory.nav.MirrorFilter
import com.uroboros.memory.nav.RetellTable
import java.time.ZoneId

/**
 * Строка записи памяти в том виде, в каком она уходит модели, — одно место
 * для экрана и для лички владельца в телеграме (gate.ChatRecall). Чистый
 * объект: запись и координаты хода на входе, строка на выходе.
 *
 * ЗАЧЕМ ОТДЕЛЬНО. Текст записи для модели решают двое: зеркало
 * ([MirrorFilter.shown] — что из записи показать на этот адрес вопроса и не
 * пересказом ли) и подпись ([ProvenanceLabels] — кто сказал и когда). Пока
 * записи подавал один экран, это стояло в его ходе. Со вторым подающим копия
 * разошлась бы молча: одному каналу запись шла бы цитатой, другому —
 * пересказом, и видно этого не было бы нигде.
 *
 * ЧЕГО ЗДЕСЬ НЕТ: отбора (кто из записей идёт в ход — круг,
 * HourglassMemory.getContextWithSummary), портрета собеседника, своей речи
 * агента и снов — у них свои подписи там, где они собираются.
 */
object RecallLines {

    /**
     * Строка для модели: [text]; [retold] — запись ушла пересказом, а не
     * цитатой; [parts] — те же части строки без времени, для ночной сборки
     * готовых записей (RecordChunks), null — у записи нет источника.
     */
    data class Line(val text: String, val retold: Boolean, val parts: ProvenanceLabels.Parts?)

    /**
     * Строка записи [sticker] к моменту [now] на вопрос с адресом [address].
     * [recordEpisode] и [currentEpisode] — номера разговоров записи и хода
     * (запись из прошлого разговора подписывается так, см.
     * [ProvenanceLabels.recordForModel]); null — разговоры не известны.
     */
    fun of(
        sticker: Sticker,
        address: Coordinates.Address,
        retell: RetellTable?,
        now: Long,
        recordEpisode: Int?,
        currentEpisode: Int?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Line {
        val shown = MirrorFilter.shown(sticker, address, retell)
        if (shown != null && shown.retold) {
            return Line(
                ProvenanceLabels.retoldForModel(sticker, shown.text, now, zone, recordEpisode, currentEpisode),
                retold = true,
                parts = ProvenanceLabels.retoldParts(shown.text),
            )
        }
        val record = if (shown == null) sticker else sticker.copy(content = shown.text)
        return Line(
            ProvenanceLabels.recordForModel(record, now, zone, recordEpisode, currentEpisode),
            retold = false,
            parts = ProvenanceLabels.recordParts(record),
        )
    }
}
