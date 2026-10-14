package io.github.h_sib.declared.core.json

/**
 * A small strict JSON reader for the two reference files bundled with the app. It exists so `core` needs no library
 * and no Android class to read them, and so it can be tested to reject anything malformed.
 * Objects become `LinkedHashMap<String, Any?>`, arrays `List<Any?>`, numbers `Long` or `Double`.
 */
object Json {
    class JsonException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipSpace()
        val v = p.value()
        p.skipSpace()
        if (!p.atEnd()) throw JsonException("unexpected text after the value at ${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipSpace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos += 1
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else throw JsonException("unexpected end")

        private fun expect(c: Char) {
            if (peek() != c) throw JsonException("expected '$c' at $pos")
            pos += 1
        }

        fun value(): Any? {
            skipSpace()
            return when (val c = peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw JsonException("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("expected $word at $pos")
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') { pos += 1; return m }
            while (true) {
                skipSpace()
                val k = str()
                skipSpace()
                expect(':')
                if (m.containsKey(k)) throw JsonException("duplicate key \"$k\"")
                m[k] = value()
                skipSpace()
                when (peek()) {
                    ',' -> pos += 1
                    '}' -> { pos += 1; return m }
                    else -> throw JsonException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val l = ArrayList<Any?>()
            skipSpace()
            if (peek() == ']') { pos += 1; return l }
            while (true) {
                l.add(value())
                skipSpace()
                when (peek()) {
                    ',' -> pos += 1
                    ']' -> { pos += 1; return l }
                    else -> throw JsonException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonException("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw JsonException("unterminated escape")
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
                                if (pos + 4 > s.length) throw JsonException("short \\u escape")
                                sb.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: throw JsonException("bad \\u escape at $pos"))
                                pos += 4
                            }
                            else -> throw JsonException("bad escape '\\$e' at ${pos - 1}")
                        }
                    }
                    c < ' ' -> throw JsonException("raw control character in a string at ${pos - 1}")
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = pos
            if (peek() == '-') pos += 1
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos += 1
            val text = s.substring(start, pos)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw JsonException("bad number '$text'")
        }
    }
}
