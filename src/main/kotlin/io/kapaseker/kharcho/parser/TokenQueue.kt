package io.kapaseker.kharcho.parser

import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.StringUtil.borrowBuilder
import io.kapaseker.kharcho.internal.StringUtil.isAsciiLetter
import io.kapaseker.kharcho.internal.StringUtil.isDigit
import io.kapaseker.kharcho.internal.StringUtil.isHexDigit
import io.kapaseker.kharcho.internal.StringUtil.isWhitespace
import io.kapaseker.kharcho.internal.StringUtil.releaseBuilder
import java.lang.AutoCloseable

/**
 * A character reader with helpers focusing on parsing CSS selectors. Used internally by jsoup. API subject to changes.
 */
class TokenQueue(data: String?) : AutoCloseable {
    private val reader: CharacterReader

    val isEmpty: Boolean
        /**
         * Is the queue empty?
         * @return true if no data left in queue.
         */
        get() = reader.isEmpty()

    /**
     * Consume one character off queue.
     * @return first character on queue.
     */
    fun consume(): Char {
        return reader.consume()
    }

    /**
     * Drops the next character off the queue.
     */
    fun advance() {
        if (!this.isEmpty) reader.advance()
    }

    fun current(): Char {
        return reader.current()
    }

    /**
     * Tests if the next characters on the queue match the sequence, case-insensitively.
     * @param seq String to check queue for.
     * @return true if the next characters match.
     */
    fun matches(seq: String): Boolean {
        return reader.matchesIgnoreCase(seq)
    }

    /** Tests if the next character on the queue matches the character, case-sensitively.  */
    fun matches(c: Char): Boolean {
        return reader.matches(c)
    }

    /**
     * Tests if the next characters match any of the sequences, case-**sensitively**.
     * @param seq list of chars to case-sensitively check for
     * @return true of any matched, false if none did
     */
    fun matchesAny(vararg seq: Char): Boolean {
        return reader.matchesAny(*seq)
    }

    /**
     * If the queue case-insensitively matches the supplied string, consume it off the queue.
     * @param seq String to search for, and if found, remove from queue.
     * @return true if found and removed, false if not found.
     */
    fun matchChomp(seq: String?): Boolean {
        return reader.matchConsumeIgnoreCase(seq)
    }

    /** If the queue matches the supplied (case-sensitive) character, consume it off the queue.  */
    fun matchChomp(c: Char): Boolean {
        if (reader.matches(c)) {
            consume()
            return true
        }
        return false
    }

    /**
     * Tests if queue starts with a whitespace character.
     * @return if starts with whitespace
     */
    fun matchesWhitespace(): Boolean {
        return isWhitespace(reader.current().code)
    }

    /**
     * Test if the queue matches a tag word character (letter or digit).
     * @return if matches a word character
     */
    fun matchesWord(): Boolean {
        return Character.isLetterOrDigit(reader.current())
    }

    /**
     * Consumes the supplied sequence of the queue, case-insensitively. If the queue does not start with the supplied
     * sequence, will throw an illegal state exception -- but you should be running match() against that condition.
     *
     * @param seq sequence to remove from head of queue.
     */
    fun consume(seq: String?) {
        val found = reader.matchConsumeIgnoreCase(seq)
        check(found) { "Queue did not match expected sequence" }
    }

    /**
     * Pulls a string off the queue, up to but exclusive of the match sequence, or to the queue running out.
     * @param seq String to end on (and not include in return, but leave on queue). **Case-sensitive.**
     * @return The matched data consumed from queue.
     */
    fun consumeTo(seq: String?): String? {
        return reader.consumeTo(seq)
    }

    /**
     * Consumes to the first sequence provided, or to the end of the queue. Leaves the terminator on the queue.
     * @param seq any number of terminators to consume to. **Case-insensitive.**
     * @return consumed string
     */
    fun consumeToAny(vararg seq: String): String {
        val sb = borrowBuilder()
        OUT@ while (!this.isEmpty) {
            for (s in seq) {
                if (reader.matchesIgnoreCase(s)) break@OUT
            }
            sb.append(consume())
        }
        return releaseBuilder(sb)
    }

    /**
     * Pulls a balanced string off the queue. E.g. if queue is "(one (two) three) four", (,) will return "one (two) three",
     * and leave " four" on the queue. Unbalanced openers and closers can be quoted (with ' or ") or escaped (with \).
     * Those escapes will be left in the returned string, which is suitable for regexes (where we need to preserve the
     * escape), but unsuitable for contains text strings; use unescape for that.
     *
     * @param open opener
     * @param close closer
     * @return data matched from the queue
     */
    fun chompBalanced(open: Char, close: Char): String {
        val accum = borrowBuilder()
        var depth = 0
        var prev = 0.toChar()
        var inSingle = false
        var inDouble = false
        var inRegexQE = false // regex \Q .. \E escapes from Pattern.quote()
        reader.mark() // mark the initial position to restore if needed

        do {
            if (this.isEmpty) break
            val c = consume()
            if (prev == Esc) {
                if (c == 'Q') inRegexQE = true
                else if (c == 'E') inRegexQE = false
                accum.append(c)
            } else {
                if (c == '\'' && c != open && !inDouble) inSingle = !inSingle
                else if (c == '"' && c != open && !inSingle) inDouble = !inDouble

                if (inSingle || inDouble || inRegexQE) {
                    accum.append(c)
                } else if (c == open) {
                    depth++
                    if (depth > 1) accum.append(c) // don't include the outer match pair in the return
                } else if (c == close) {
                    depth--
                    if (depth > 0) accum.append(c)
                } else {
                    accum.append(c)
                }
            }
            prev = c
        } while (depth > 0)

        val out = releaseBuilder(accum)
        if (depth > 0) { // ran out of queue before seeing enough )
            reader.rewindToMark() // restore position if we don't have a balanced string
            Validate.fail("Did not find balanced marker at '" + out + "'")
        }
        return out
    }

    /**
     * Pulls the next run of whitespace characters of the queue.
     * @return Whether consuming whitespace or not
     */
    fun consumeWhitespace(): Boolean {
        var seen = false
        while (matchesWhitespace()) {
            advance()
            seen = true
        }
        return seen
    }

    /**
     * Consume a CSS element selector (tag name, but | instead of : for namespaces (or *| for wildcard namespace), to not conflict with :pseudo selects).
     *
     * @return tag name
     */
    fun consumeElementSelector(): String {
        return consumeEscapedCssIdentifier(*ElementSelectorChars)
    }

    /**
     * Consume a CSS identifier (ID or class) off the queue.
     *
     * Note: For backwards compatibility this method supports improperly formatted CSS identifiers, e.g. `1` instead
     * of `\31`.
     *
     * @return The unescaped identifier.
     * @throws IllegalArgumentException if an invalid escape sequence was found. Afterward, the state of the TokenQueue
     * is undefined.
     * @see [CSS Syntax Module Level 3, Consume an ident sequence](https://www.w3.org/TR/css-syntax-3/.consume-name)
     * @see [CSS Syntax Module Level 3, ident-token](https://www.w3.org/TR/css-syntax-3/.typedef-ident-token)
     */
    fun consumeCssIdentifier(): String {
        require(!this.isEmpty) { "CSS identifier expected, but end of input found" }

        // Fast path for CSS identifiers that don't contain escape sequences.
        val identifier =
            reader.consumeMatching(CharacterReader.CharPredicate { c: Char -> isIdent(c) })
        var c = current()
        if (c != Esc && c != Unicode_Null) {
            // If we didn't end on an Esc or a Null, we consumed the whole identifier
            return identifier
        }

        // An escape sequence was found. Use a StringBuilder to store the decoded CSS identifier.
        val out = borrowBuilder()
        if (!identifier.isEmpty()) {
            // Copy the CSS identifier up to the first escape sequence.
            out.append(identifier)
        }

        while (!this.isEmpty) {
            c = current()
            if (isIdent(c)) {
                out.append(consume())
            } else if (c == Unicode_Null) {
                // https://www.w3.org/TR/css-syntax-3/#input-preprocessing
                advance()
                out.append(Replacement)
            } else if (c == Esc) {
                advance()
                if (!this.isEmpty && isNewline(current())) {
                    // Not a valid escape sequence. This is treated as the end of the CSS identifier.
                    reader.unconsume()
                    break
                } else {
                    consumeCssEscapeSequenceInto(out)
                }
            } else {
                break
            }
        }
        return releaseBuilder(out)
    }

    private fun consumeCssEscapeSequenceInto(out: StringBuilder) {
        if (this.isEmpty) {
            out.append(Replacement)
            return
        }

        val firstEscaped = consume()
        if (!isHexDigit(firstEscaped)) {
            out.append(firstEscaped)
        } else {
            reader.unconsume() // put back the first hex digit
            val hexString = reader.consumeMatching(
                CharacterReader.CharPredicate { obj: Char -> obj.isHexDigit() },
                6
            ) // consume up to 6 hex digits
            val codePoint: Int
            try {
                codePoint = hexString.toInt(16)
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Invalid escape sequence: " + hexString, e)
            }
            if (isValidCodePoint(codePoint)) {
                out.appendCodePoint(codePoint)
            } else {
                out.append(Replacement)
            }

            if (!this.isEmpty) {
                val c = current()
                if (c == '\r') {
                    // Since there's currently no input preprocessing, check for CRLF here.
                    // https://www.w3.org/TR/css-syntax-3/#input-preprocessing
                    advance()
                    if (!this.isEmpty && current() == '\n') advance()
                } else if (c == ' ' || c == '\t' || isNewline(c)) {
                    advance()
                }
            }
        }
    }

    /**
     * Create a new TokenQueue.
     * @param data string of data to back queue.
     */
    init {
        reader = CharacterReader(data)
    }

    private fun consumeEscapedCssIdentifier(vararg matches: Char): String {
        val sb = borrowBuilder()
        while (!this.isEmpty) {
            val c = current()
            if (c == Esc) {
                advance()
                if (!this.isEmpty) sb.append(consume())
                else break
            } else if (matchesCssIdentifier(*matches)) {
                sb.append(c)
                advance()
            } else {
                break
            }
        }
        return releaseBuilder(sb)
    }

    private fun matchesCssIdentifier(vararg matches: Char): Boolean {
        return matchesWord() || reader.matchesAny(*matches)
    }

    /**
     * Consume and return whatever is left on the queue.
     * @return remainder of queue.
     */
    fun remainder(): String {
        return reader.consumeToEnd()
    }

    override fun toString(): String {
        return reader.toString()
    }

    override fun close() {
        reader.close() // releases buffer back to pool
    }

    companion object {
        private const val Esc = '\\' // escape char for chomp balanced.
        private const val Hyphen_Minus = '-'
        private const val Unicode_Null = '\u0000'
        private const val Replacement = '\uFFFD'

        /**
         * Unescape a \ escaped string.
         * @param in backslash escaped string
         * @return unescaped string
         */
        fun unescape(`in`: String): String {
            if (`in`.indexOf(Esc) == -1) return `in`

            val out = borrowBuilder()
            var last = 0.toChar()
            for (c in `in`.toCharArray()) {
                var c = c
                if (c == Esc) {
                    if (last == Esc) {
                        out.append(c)
                        c = 0.toChar()
                    }
                } else out.append(c)
                last = c
            }
            return releaseBuilder(out)
        }

        /**
         * Given a CSS identifier (such as a tag, ID, or class), escape any CSS special characters that would otherwise not be
         * valid in a selector.
         *
         * @see [CSS Object Model, serialize an identifier](https://www.w3.org/TR/cssom-1/.serialize-an-identifier)
         */
        @JvmStatic
        fun escapeCssIdentifier(`in`: String): String {
            if (`in`.isEmpty()) return `in`

            val out = borrowBuilder()
            val q = TokenQueue(`in`)

            val firstChar = q.current()
            if (firstChar == Hyphen_Minus) {
                q.advance()
                if (q.isEmpty) {
                    // If the character is the first character and is a "-" (U+002D), and there is no second character, then
                    // the escaped character.
                    appendEscaped(out, Hyphen_Minus)
                } else {
                    out.append(Hyphen_Minus)

                    val secondChar = q.current()
                    if (isDigit(secondChar)) {
                        // If the character is the second character and is in the range [0-9] (U+0030 to U+0039) and the
                        // first character is a "-" (U+002D), then the character escaped as code point.
                        appendEscapedCodepoint(out, q.consume())
                    }
                }
            } else if (isDigit(firstChar)) {
                // If the character is the first character and is in the range [0-9] (U+0030 to U+0039), then the character
                // escaped as code point.
                appendEscapedCodepoint(out, q.consume())
            }

            while (!q.isEmpty) {
                // Note: It's fine to iterate on chars because non-ASCII characters are never escaped. So surrogate pairs
                // are kept intact.
                val c = q.consume()
                if (c == Unicode_Null) {
                    // If the character is NULL (U+0000), then the REPLACEMENT CHARACTER (U+FFFD).
                    out.append(Replacement)
                } else if (c <= '\u001F' || c == '\u007F') {
                    // If the character is in the range [\1-\1f] (U+0001 to U+001F) or is U+007F, then the character
                    // escaped as code point.
                    appendEscapedCodepoint(out, c)
                } else if (isIdent(c)) {
                    // If the character is not handled by one of the above rules and is greater than or equal to U+0080,
                    // is "-" (U+002D) or "_" (U+005F), or is in one of the ranges [0-9] (U+0030 to U+0039),
                    // [A-Z] (U+0041 to U+005A), or [a-z] (U+0061 to U+007A), then the character itself.
                    out.append(c)
                } else {
                    // Otherwise, the escaped character.
                    appendEscaped(out, c)
                }
            }

            q.close()
            return releaseBuilder(out)
        }

        private fun appendEscaped(out: StringBuilder, c: Char) {
            out.append(Esc).append(c)
        }

        private fun appendEscapedCodepoint(out: StringBuilder, c: Char) {
            out.append(Esc).append(Integer.toHexString(c.code)).append(' ')
        }

        private val ElementSelectorChars = charArrayOf('*', '|', '_', '-')

        // statics below specifically for CSS identifiers:
        // https://www.w3.org/TR/css-syntax-3/#non-ascii-code-point
        private fun isNonAscii(c: Char): Boolean {
            return c >= '\u0080'
        }

        // https://www.w3.org/TR/css-syntax-3/#ident-start-code-point
        private fun isIdentStart(c: Char): Boolean {
            return c == '_' || isAsciiLetter(c) || isNonAscii(c)
        }

        // https://www.w3.org/TR/css-syntax-3/#ident-code-point
        private fun isIdent(c: Char): Boolean {
            return c == Hyphen_Minus || isDigit(c) || isIdentStart(c)
        }

        // https://www.w3.org/TR/css-syntax-3/#newline
        // Note: currently there's no preprocessing happening.
        private fun isNewline(c: Char): Boolean {
            return c == '\n' || c == '\r' || c == '\f'
        }

        // https://www.w3.org/TR/css-syntax-3/#consume-an-escaped-code-point
        private fun isValidCodePoint(codePoint: Int): Boolean {
            return codePoint != 0 && Character.isValidCodePoint(codePoint) && !Character.isSurrogate(
                codePoint.toChar()
            )
        }

        private val CssIdentifierChars = charArrayOf('-', '_')
    }
}
