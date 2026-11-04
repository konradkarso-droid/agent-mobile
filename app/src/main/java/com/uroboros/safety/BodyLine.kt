package com.uroboros.safety

import java.util.Locale

/**
 * Строка тела агента для модели — заряд, температура, зона сторожа. Одна на
 * всех, кто её подаёт: выжимка взгляда на приборы на экране
 * (MainActivity.deskSummary) и конец нити в чате телеграма
 * (gate.link.ChatLink). Две копии разошлись бы со временем, и не видно было бы,
 * какая врёт. Слова зон — тоже здесь ([zoneWord]): ими же подписаны зоны в
 * «Подробно».
 *
 * Числа — из сторожа (DeviceSafetyWatchdog.power и zone), то есть те же, что в
 * шторке. Неизвестное — «?», а не ноль.
 *
 * ЧЕГО НЕ УМЕЕТ: это показание на момент сборки строки, а не прогноз; в чате
 * агент отвечает только в зоне «норма» (gate.ChatReply), поэтому зона там
 * всегда «норма», а меняются заряд и температура.
 *
 * Чистый объект.
 */
object BodyLine {

    fun zoneWord(zone: SafetyZone): String = when (zone) {
        SafetyZone.COMFORT -> "норма"
        SafetyZone.WARNING -> "нагрев"
        SafetyZone.FATIGUE -> "утомление"
        SafetyZone.CRITICAL -> "критическая"
    }

    fun of(power: PowerState, zone: SafetyZone): String {
        val charge = if (power.percentKnown) "${power.percent}%" else "?"
        val plug = if (power.charging) " (заряжается)" else ""
        val temp = if (power.temperatureKnown) String.format(Locale.US, "%.1f°C", power.temperatureCelsius) else "?"
        return "Тело: заряд $charge$plug, $temp, зона ${zoneWord(zone)}"
    }
}
