package com.uroboros.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class Layer { RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE }
enum class Importance { LOW, MEDIUM, HIGH }

// Item 3 / Track A (info-source trust, designed 2026-08-20): два новых поля,
// tag-only — никогда не участвуют в Prism decay/ranking математике.
// Устанавливаются один раз при создании, затем не пересматриваются.
enum class SourceKind { USER_STATED, AGENT_INFERRED, OCR_EXTRACTED }
enum class ConfidenceLevel { OBSERVED, INFERRED, UNCERTAIN }

@Entity(tableName = "stickers")
data class Sticker(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    var lastAccessedAt: Long = System.currentTimeMillis(),
    var accessCount: Int = 0,
    val tag: String = "general",
    var layer: String = Layer.GREEN.name,
    var expiryTime: Long? = null,
    var importance: String = Importance.MEDIUM.name,
    var reviewPending: Boolean = false,
    val source: String = SourceKind.USER_STATED.name,
    val confidence: String = ConfidenceLevel.OBSERVED.name,

    // Item 3: сырой счётчик пользы, из которого важность будет выводиться позже.
    //
    // Растёт только тогда, когда запись прошла отбор релевантности в поисковом пути
    // по запросу ПОЛЬЗОВАТЕЛЯ. Обращения, порождённые самим агентом, не считаются:
    // иначе система накачивала бы важность собственных записей и через неё лепила
    // свой будущий контекст. Отдельно от accessCount намеренно — тот растёт у всего,
    // что попало в выдачу, а выдачей распоряжается сортировка по важности, и замкнуть
    // одно на другое значит сделать запись важной за то, что она уже была наверху.
    //
    // Ноль означает "ни разу не совпала", а НЕ "неважна". Запись может быть жизненно
    // нужной и ни разу не пересечься словами ни с одним запросом: высокое значение —
    // свидетельство, низкое — не свидетельство обратного.
    //
    // Чего счётчик не умеет: он меряет совпадение слов, а не смысл, и не отличает
    // запись, которую прочли и она помогла, от записи, которую прочли и отбросили.
    //
    // Счётчик не сбрасывается никогда, в том числе при изменении content. Значит
    // после правки текста записи число описывает пользу СТАРОГО текста, а выглядит
    // как относящееся к новому. Сегодня это безвредно: способа отредактировать
    // содержимое записи в приложении нет. Появится — придётся решать, что делать со
    // счётом, и решать это надо будет тогда же, а не после первой перезаписи.
    //
    // Сегодня из него ничего не выводится — сначала надо посмотреть на живые числа.
    // Перетирать его вычисленной важностью нельзя: тогда правило вывода станет
    // непересчитываемым, а сырое наблюдение исчезнет.
    var userMatchCount: Int = 0
)
