package com.uroboros.gate

/**
 * JSON ответов Bot API — нестрогий разбор, только для чтения.
 *
 * ПОЧЕМУ НЕ [GateJson]. Тот строг нарочно: незнакомое, дробное, null, глубина
 * больше четырёх — отказ, потому что так пакет не пронесёт лишнего. Ответ
 * телеграма — не пакет: в нём десятки полей, которые здесь не нужны, бывают
 * дробные числа (координаты) и null, а сообщение лежит на пятом уровне
 * (`result` → обновление → `message` → `chat`). Строгий разбор отказал бы
 * каждому ответу. `org.json` из Android тоже не годится: в обычном JVM-тесте
 * это заглушка.
 *
 * ЧТО ДАЁТ: объект — `Map<String, Any?>`, массив — `List<Any?>`, строка —
 * `String`, целое — `Long`, дробное — `Double`, `true/false`, `null`. Брать
 * нужное — через [obj], [long], [str] и соседей: чужой вид поля — null, а не
 * исключение. Поля, которые разбор взял, проверяют уже врата.
 *
 * ЧЕГО НЕ УМЕЕТ: целое длиннее 64 бит читает дробным; глубже [MAX_DEPTH] —
 * [Malformed]. Пишет — только [GateJson]; здесь записи нет.
 */
object TgJson {

    class Malformed(message: String) : Exception(message)

    /** Объявленное число, не подобранное: ответу телеграма хватает и десяти уровней. */
    const val MAX_DEPTH = 32

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipSpace()
        val v = p.value(0)
        p.skipSpace()
        if (p.pos != text.length) throw Malformed("лишнее после конца на ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    fun list(v: Any?): List<Any?>? = v as? List<Any?>

    fun long(v: Any?): Long? = v as? Long

    fun str(v: Any?): String? = v as? String

    fun bool(v: Any?): Boolean? = v as? Boolean

    private class Parser(val t: String) {
        var pos = 0

        fun skipSpace() {
            while (pos < t.length && t[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
        }

        fun value(depth: Int): Any? {
            if (depth >= MAX_DEPTH) throw Malformed("глубже $MAX_DEPTH уровней")
            if (pos >= t.length) throw Malformed("конец текста вместо значения")
            return when (val c = t[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw Malformed("знак «$c» на $pos")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!t.startsWith(w, pos)) throw Malformed("не «$w» на $pos")
            pos += w.length
            return v
        }

        private fun number(): Any {
            val start = pos
            if (t[pos] == '-') pos++
            while (pos < t.length && (t[pos] in '0'..'9' || t[pos] in ".eE+-")) pos++
            val s = t.substring(start, pos)
            return s.toLongOrNull() ?: s.toDoubleOrNull() ?: throw Malformed("не число «$s» на $start")
        }

        private fun string(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= t.length) throw Malformed("строка без конца")
                val c = t[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= t.length) throw Malformed("строка без конца")
                        when (val e = t[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > t.length) throw Malformed("короткое \\u")
                                val code = t.substring(pos, pos + 4).toIntOrNull(16) ?: throw Malformed("плохое \\u на $pos")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw Malformed("незнакомое «\\$e»")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++
            val items = ArrayList<Any?>()
            skipSpace()
            if (pos < t.length && t[pos] == ']') { pos++; return items }
            while (true) {
                skipSpace()
                items += value(depth + 1)
                skipSpace()
                if (pos >= t.length) throw Malformed("массив без конца")
                when (t[pos++]) {
                    ',' -> continue
                    ']' -> return items
                    else -> throw Malformed("в массиве на ${pos - 1}")
                }
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++
            val fields = LinkedHashMap<String, Any?>()
            skipSpace()
            if (pos < t.length && t[pos] == '}') { pos++; return fields }
            while (true) {
                skipSpace()
                if (pos >= t.length || t[pos] != '"') throw Malformed("ключ не строкой на $pos")
                val key = string()
                skipSpace()
                if (pos >= t.length || t[pos] != ':') throw Malformed("нет «:» на $pos")
                pos++
                skipSpace()
                fields[key] = value(depth + 1)
                skipSpace()
                if (pos >= t.length) throw Malformed("объект без конца")
                when (t[pos++]) {
                    ',' -> continue
                    '}' -> return fields
                    else -> throw Malformed("в объекте на ${pos - 1}")
                }
            }
        }
    }
}
