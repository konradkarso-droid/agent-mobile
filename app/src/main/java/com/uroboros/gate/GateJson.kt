package com.uroboros.gate

/**
 * Узкий строгий JSON для тела пакета врат — свой, без библиотеки.
 *
 * Почему свой: в проекте нет JSON-библиотеки, а `org.json` из Android в
 * обычном JVM-тесте — заглушка. Пакету нужна только часть JSON, и чем она
 * уже, тем меньше способов прислать то, что разберётся наугад.
 *
 * ЧТО ПРИНИМАЕТСЯ: объект, массив, строка, целое число, true/false. ЧТО НЕТ:
 * дробные числа, null, ведущие нули, повтор ключа в объекте, глубина больше
 * [MAX_DEPTH], что-либо после закрывающей скобки. Любое из этого — [Malformed]:
 * пакет молчит целиком, а не разбирается по частям.
 */
object GateJson {

    sealed class Value {
        data class Str(val s: String) : Value()
        data class Num(val n: Long) : Value()
        data class Bool(val b: Boolean) : Value()
        data class Arr(val items: List<Value>) : Value()
        /** Порядок ключей — как пришли; повторов нет. */
        data class Obj(val fields: Map<String, Value>) : Value()
    }

    class Malformed(message: String) : Exception(message)

    /** Объявленное число, не подобранное: пакету хватает трёх уровней. */
    const val MAX_DEPTH = 4

    fun parse(text: String): Value {
        val p = Parser(text)
        p.skipSpace()
        val v = p.value(0)
        p.skipSpace()
        if (p.pos != text.length) throw Malformed("лишнее после конца на ${p.pos}")
        return v
    }

    fun write(v: Value): String = StringBuilder().also { writeTo(it, v) }.toString()

    private fun writeTo(sb: StringBuilder, v: Value) {
        when (v) {
            is Value.Str -> writeString(sb, v.s)
            is Value.Num -> sb.append(v.n)
            is Value.Bool -> sb.append(v.b)
            is Value.Arr -> {
                sb.append('[')
                v.items.forEachIndexed { i, item -> if (i > 0) sb.append(','); writeTo(sb, item) }
                sb.append(']')
            }
            is Value.Obj -> {
                sb.append('{')
                var first = true
                for ((k, item) in v.fields) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k)
                    sb.append(':')
                    writeTo(sb, item)
                }
                sb.append('}')
            }
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val t: String) {
        var pos = 0

        fun skipSpace() {
            while (pos < t.length && (t[pos] == ' ' || t[pos] == '\n' || t[pos] == '\r' || t[pos] == '\t')) pos++
        }

        fun value(depth: Int): Value {
            if (depth >= MAX_DEPTH) throw Malformed("глубже $MAX_DEPTH уровней")
            if (pos >= t.length) throw Malformed("конец текста вместо значения")
            return when (val c = t[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> Value.Str(string())
                't' -> word("true", Value.Bool(true))
                'f' -> word("false", Value.Bool(false))
                else -> if (c == '-' || c in '0'..'9') number() else throw Malformed("знак «$c» на $pos")
            }
        }

        private fun word(w: String, v: Value): Value {
            if (!t.startsWith(w, pos)) throw Malformed("не «$w» на $pos")
            pos += w.length
            return v
        }

        private fun number(): Value {
            val start = pos
            if (t[pos] == '-') pos++
            val digitsStart = pos
            while (pos < t.length && t[pos] in '0'..'9') pos++
            val digits = pos - digitsStart
            if (digits == 0) throw Malformed("число без цифр на $start")
            if (digits > 1 && t[digitsStart] == '0') throw Malformed("ведущий ноль на $start")
            if (pos < t.length && (t[pos] == '.' || t[pos] == 'e' || t[pos] == 'E')) {
                throw Malformed("дробное число на $start")
            }
            return Value.Num(t.substring(start, pos).toLongOrNull() ?: throw Malformed("число вне long на $start"))
        }

        private fun string(): String {
            pos++ // открывающая кавычка
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
                    c < ' ' -> throw Malformed("управляющий знак в строке на ${pos - 1}")
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(depth: Int): Value {
            pos++
            val items = ArrayList<Value>()
            skipSpace()
            if (pos < t.length && t[pos] == ']') { pos++; return Value.Arr(items) }
            while (true) {
                skipSpace()
                items += value(depth + 1)
                skipSpace()
                if (pos >= t.length) throw Malformed("массив без конца")
                when (t[pos++]) {
                    ',' -> continue
                    ']' -> return Value.Arr(items)
                    else -> throw Malformed("в массиве на ${pos - 1}")
                }
            }
        }

        private fun obj(depth: Int): Value {
            pos++
            val fields = LinkedHashMap<String, Value>()
            skipSpace()
            if (pos < t.length && t[pos] == '}') { pos++; return Value.Obj(fields) }
            while (true) {
                skipSpace()
                if (pos >= t.length || t[pos] != '"') throw Malformed("ключ не строкой на $pos")
                val key = string()
                if (key in fields) throw Malformed("ключ «$key» дважды")
                skipSpace()
                if (pos >= t.length || t[pos] != ':') throw Malformed("нет «:» на $pos")
                pos++
                skipSpace()
                fields[key] = value(depth + 1)
                skipSpace()
                if (pos >= t.length) throw Malformed("объект без конца")
                when (t[pos++]) {
                    ',' -> continue
                    '}' -> return Value.Obj(fields)
                    else -> throw Malformed("в объекте на ${pos - 1}")
                }
            }
        }
    }
}
