package com.uroboros.move

import java.security.SecureRandom

/**
 * Код приёма и адрес — то, что человек переносит со старого экрана на новый
 * руками ([Move]).
 *
 * Код — цифры, а не буквы: на телефоне их набирают с цифровой клавиатуры, и
 * спутать «0» с «О» нельзя. Пробелы и дефисы при вводе не важны.
 *
 * Чистый объект.
 */
object MoveCode {

    const val DIGITS = 10

    fun newCode(random: SecureRandom = SecureRandom()): String =
        (1..DIGITS).joinToString("") { random.nextInt(10).toString() }

    /** «4821 7731 06» — группами по четыре, как читают вслух. */
    fun format(code: String): String = code.chunked(4).joinToString(" ")

    /** Введённое человеком → код; null — не [DIGITS] цифр. */
    fun parse(input: String): String? {
        val digits = input.filter { !it.isWhitespace() && it != '-' }
        return digits.takeIf { it.length == DIGITS && it.all { c -> c in '0'..'9' } }
    }

    /**
     * Адрес IPv4 частной сети; null — не он. Только частные сети: переезд не
     * должен уходить в интернет, даже если человек ошибся в адресе.
     */
    fun parseAddress(input: String): String? {
        val parts = input.trim().split('.')
        if (parts.size != 4) return null
        val n = parts.map { p -> p.toIntOrNull()?.takeIf { p.isNotEmpty() && p.length <= 3 && it in 0..255 } ?: return null }
        val private = n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
        return if (private) n.joinToString(".") else null
    }

    /** «192.168.43.» из своего адреса — подсказка в поле адреса на старом телефоне. */
    fun prefixOf(address: String): String = address.substringBeforeLast('.', "") + "."
}
