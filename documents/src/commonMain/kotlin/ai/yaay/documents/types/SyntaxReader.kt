package ai.yaay.documents.types

/** Tokenizer shared by the type-declaration and value syntaxes. `#` starts a comment. */
internal class SyntaxReader(private val text: String) {
    sealed interface Token { val at: Int }
    data class Ident(val value: String, override val at: Int) : Token
    data class Str(val value: String, override val at: Int) : Token
    data class Num(val value: Double, override val at: Int) : Token
    /** `@handle`: a short or full object identity, see [ObjectHandles]. */
    data class Handle(val value: String, override val at: Int) : Token
    data class Punct(val value: Char, override val at: Int) : Token
    data class End(override val at: Int) : Token

    val tokens: List<Token> = tokenize()
    var index: Int = 0

    fun peek(offset: Int = 0): Token = tokens[minOf(index + offset, tokens.lastIndex)]
    fun next(): Token = tokens[index].also { if (index < tokens.lastIndex) index++ }
    fun atEnd(): Boolean = peek() is End
    fun isPunct(c: Char, offset: Int = 0): Boolean = (peek(offset) as? Punct)?.value == c
    fun isIdent(value: String? = null, offset: Int = 0): Boolean = peek(offset).let { it is Ident && (value == null || it.value == value) }
    fun accept(c: Char): Boolean = isPunct(c).also { if (it) next() }
    fun punct(c: Char) { val token = next(); if ((token as? Punct)?.value != c) fail(token, "Expected '$c'") }
    fun ident(what: String = "an identifier"): String { val token = next(); return (token as? Ident ?: fail(token, "Expected $what")).value }
    /** A record field, map key or enum choice: a bare identifier or a quoted string. */
    fun name(what: String): String = when (val token = next()) {
        is Ident -> token.value
        is Str -> token.value
        else -> fail(token, "Expected $what")
    }
    fun expectEnd() { if (!atEnd()) fail(peek(), "Unexpected input") }

    private fun position(at: Int): String {
        val before = text.substring(0, minOf(at, text.length))
        return "${before.count { it == '\n' } + 1}:${at - (before.lastIndexOf('\n') + 1) + 1}"
    }
    fun fail(token: Token, message: String): Nothing {
        val found = when (token) {
            is End -> "end of input"
            is Ident -> "'${token.value}'"
            is Str -> "string"
            is Num -> "number"
            is Handle -> "@${token.value}"
            is Punct -> "'${token.value}'"
        }
        throw IllegalArgumentException("$message at ${position(token.at)}, found $found")
    }

    private fun tokenize(): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        fun error(at: Int, message: String): Nothing = throw IllegalArgumentException("$message at ${position(at)}")
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '#' -> while (i < text.length && text[i] != '\n') i++
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    out.add(Ident(text.substring(start, i), start))
                }
                c.isDigit() || (c == '-' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                    val start = i
                    if (c == '-') i++
                    while (i < text.length && text[i].isDigit()) i++
                    if (i + 1 < text.length && text[i] == '.' && text[i + 1].isDigit()) { i++; while (i < text.length && text[i].isDigit()) i++ }
                    if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                        i++
                        if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
                        if (i >= text.length || !text[i].isDigit()) error(i, "Malformed number exponent")
                        while (i < text.length && text[i].isDigit()) i++
                    }
                    out.add(Num(text.substring(start, i).toDouble(), start))
                }
                c == '"' -> {
                    val start = i++
                    val value = StringBuilder()
                    while (true) {
                        if (i >= text.length) error(start, "Unterminated string")
                        val d = text[i++]
                        if (d == '"') break
                        if (d != '\\') { value.append(d); continue }
                        if (i >= text.length) error(start, "Unterminated string")
                        when (val e = text[i++]) {
                            '"', '\\' -> value.append(e)
                            'n' -> value.append('\n')
                            't' -> value.append('\t')
                            'r' -> value.append('\r')
                            'u' -> {
                                if (i + 4 > text.length) error(i, "Malformed \\u escape")
                                value.append(text.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: error(i, "Malformed \\u escape"))
                                i += 4
                            }
                            else -> error(i - 1, "Unknown escape \\$e")
                        }
                    }
                    out.add(Str(value.toString(), start))
                }
                c == '@' -> {
                    val start = i++
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "_-:")) i++
                    if (i == start + 1) error(start, "Expected a handle after '@'")
                    out.add(Handle(text.substring(start + 1, i), start))
                }
                c in "{}[]()<>,:|=;." -> { out.add(Punct(c, i)); i++ }
                else -> error(i, "Unexpected character '$c'")
            }
        }
        out.add(End(text.length))
        return out
    }

    companion object {
        private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
        fun isIdentifier(value: String): Boolean = identifier.matches(value)
        fun quote(value: String): String = buildString {
            append('"')
            for (c in value) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\t' -> append("\\t"); '\r' -> append("\\r")
                else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
            }
            append('"')
        }
        /** Bare when it reads back as the same identifier, quoted otherwise. */
        fun nameOrQuoted(value: String, reserved: Set<String> = emptySet()): String =
            if (isIdentifier(value) && value !in reserved) value else quote(value)
    }
}
