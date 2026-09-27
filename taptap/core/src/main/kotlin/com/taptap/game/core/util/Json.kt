package com.taptap.game.core.util

/**
 * Minimal JSON codec for the save file. Values map to Kotlin types:
 * object → [Map], array → [List], string → [String], integer → [Long], other numbers → [Double],
 * `true`/`false` → [Boolean], `null` → `null`.
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val value = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Trailing data at ${p.pos}")
        return value
    }

    fun write(value: Any?): String = StringBuilder(256).also { writeTo(value, it) }.toString()

    private fun writeTo(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(value, sb)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Float -> writeDouble(value.toDouble(), sb)
            is Double -> writeDouble(value, sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(':')
                    writeTo(v, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(v, sb)
                }
                sb.append(']')
            }
            is IntArray -> writeTo(value.toList(), sb)
            is LongArray -> writeTo(value.toList(), sb)
            else -> throw JsonException("Unsupported type ${value::class.java.name}")
        }
    }

    private fun writeDouble(d: Double, sb: StringBuilder) {
        if (d.isNaN() || d.isInfinite()) {
            sb.append("0")
        } else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) {
            sb.append(d.toLong().toString())
        } else {
            sb.append(d.toString())
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u")
                    val hex = Integer.toHexString(c.code)
                    for (i in hex.length until 4) sb.append('0')
                    sb.append(hex)
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            if (atEnd()) throw JsonException("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw JsonException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("Bad literal at $pos")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (peek() != '"') throw JsonException("Expected key at $pos")
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                map[key] = readValue()
                skipWs()
                when (next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw JsonException("Expected , or } at ${pos - 1}")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                skipWs()
                list.add(readValue())
                skipWs()
                when (next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw JsonException("Expected , or ] at ${pos - 1}")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            var isDouble = false
            while (pos < s.length) {
                val c = s[pos]
                if (c.isDigit()) {
                    pos++
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isDouble = true
                    pos++
                } else {
                    break
                }
            }
            val text = s.substring(start, pos)
            return try {
                if (isDouble) text.toDouble() else text.toLong()
            } catch (e: NumberFormatException) {
                throw JsonException("Bad number '$text'")
            }
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else s[pos]
        private fun next(): Char = if (atEnd()) throw JsonException("Unexpected end") else s[pos++]
        private fun expect(c: Char) {
            if (next() != c) throw JsonException("Expected '$c' at ${pos - 1}")
        }
    }
}

class JsonException(message: String) : RuntimeException(message)

/** Typed, lenient read access to a parsed JSON object (missing/invalid values fall back to defaults). */
class JObj(val map: Map<String, Any?>) {
    fun has(key: String) = map.containsKey(key)
    fun long(key: String, def: Long = 0L): Long = (map[key] as? Number)?.toLong() ?: def
    fun int(key: String, def: Int = 0): Int = (map[key] as? Number)?.let {
        val l = it.toLong()
        if (l > Int.MAX_VALUE) Int.MAX_VALUE else if (l < Int.MIN_VALUE) Int.MIN_VALUE else l.toInt()
    } ?: def
    fun double(key: String, def: Double = 0.0): Double = (map[key] as? Number)?.toDouble() ?: def
    fun float(key: String, def: Float = 0f): Float = (map[key] as? Number)?.toFloat() ?: def
    fun bool(key: String, def: Boolean = false): Boolean = map[key] as? Boolean ?: def
    fun string(key: String, def: String = ""): String = map[key] as? String ?: def
    fun stringOrNull(key: String): String? = map[key] as? String

    @Suppress("UNCHECKED_CAST")
    fun obj(key: String): JObj = JObj((map[key] as? Map<String, Any?>) ?: emptyMap())

    fun list(key: String): List<Any?> = map[key] as? List<Any?> ?: emptyList()

    fun objList(key: String): List<JObj> = list(key).mapNotNull { v ->
        @Suppress("UNCHECKED_CAST")
        (v as? Map<String, Any?>)?.let { JObj(it) }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(text: String): JObj = JObj(Json.parse(text) as? Map<String, Any?> ?: throw JsonException("Root is not an object"))
    }
}
