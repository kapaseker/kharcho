package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.helper.DataUtil
import io.kapaseker.kharcho.helper.Validate.isTrue
import io.kapaseker.kharcho.internal.QuietAppendable
import io.kapaseker.kharcho.internal.QuietAppendable.Companion.wrap
import io.kapaseker.kharcho.internal.StringUtil.borrowBuilder
import io.kapaseker.kharcho.internal.StringUtil.isWhitespace
import io.kapaseker.kharcho.internal.StringUtil.releaseBuilder
import io.kapaseker.kharcho.parser.CharacterReader
import io.kapaseker.kharcho.parser.Parser.Companion.unescapeEntities
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.util.*
import java.util.function.Supplier

/**
 * HTML entities, and escape routines. Source: [W3C
 * HTML named character references](http://www.w3.org/TR/html5/named-character-references.html#named-character-references).
 */
object Entities {
    // constants for escape options:
    const val ForText: Int = 0x1
    const val ForAttribute: Int = 0x2
    const val Normalise: Int = 0x4
    const val TrimLeading: Int = 0x8
    const val TrimTrailing: Int = 0x10

    private val empty = -1
    private const val emptyName = ""
    const val codepointRadix: Int = 36
    private val codeDelims = charArrayOf(',', ';')
    private val multipoints = HashMap<String?, String?>() // name -> multiple character references

    private const val BaseCount = 106
    private val baseSorted =
        ArrayList<String>(BaseCount) // names sorted longest first, for prefix matching

    /**
     * Check if the input is a known named entity
     *
     * @param name the possible entity name (e.g. "lt" or "amp")
     * @return true if a known named entity
     */
    fun isNamedEntity(name: String?): Boolean {
        return EscapeMode.extended.codepointForName(name) != empty
    }

    /**
     * Check if the input is a known named entity in the base entity set.
     *
     * @param name the possible entity name (e.g. "lt" or "amp")
     * @return true if a known named entity in the base set
     * @see .isNamedEntity
     */
    fun isBaseNamedEntity(name: String?): Boolean {
        return EscapeMode.base.codepointForName(name) != empty
    }

    /**
     * Get the character(s) represented by the named entity
     *
     * @param name entity (e.g. "lt" or "amp")
     * @return the string value of the character(s) represented by this entity, or "" if not defined
     */
    fun getByName(name: String?): String {
        val `val` = multipoints.get(name)
        if (`val` != null) return `val`
        val codepoint = EscapeMode.extended.codepointForName(name)
        if (codepoint != empty) return String(intArrayOf(codepoint), 0, 1)
        return emptyName
    }

    fun codepointsForName(name: String?, codepoints: IntArray): Int {
        val `val` = multipoints.get(name)
        if (`val` != null) {
            codepoints[0] = `val`.codePointAt(0)
            codepoints[1] = `val`.codePointAt(1)
            return 2
        }
        val codepoint = EscapeMode.extended.codepointForName(name)
        if (codepoint != empty) {
            codepoints[0] = codepoint
            return 1
        }
        return 0
    }

    /**
     * Finds the longest base named entity that is a prefix of the input. That is, input "notit" would return "not".
     *
     * @return longest entity name that is a prefix of the input, or "" if no entity matches
     */
    fun findPrefix(input: String): String {
        for (name in baseSorted) {
            if (input.startsWith(name)) return name
        }
        return emptyName
        // if perf critical, could look at using a Trie vs a scan
    }

    /**
     * HTML escape an input string. That is, `<` is returned as `&lt;`. The escaped string is suitable for use
     * both in attributes and in text data.
     * @param data the un-escaped string to escape
     * @param out the output settings to use. This configures the character set escaped against (that is, if a
     * character is supported in the output character set, it doesn't have to be escaped), and also HTML or XML
     * settings.
     * @return the escaped string
     */
    fun escape(data: String?, out: Document.OutputSettings): String {
        return escapeString(data, out.escapeMode(), out.charset())
    }

    /**
     * HTML escape an input string, using the default settings (UTF-8, base entities). That is, `<` is
     * returned as `&lt;`. The escaped string is suitable for use both in attributes and in text data.
     * @param data the un-escaped string to escape
     * @return the escaped string
     * @see .escape
     */
    fun escape(data: String?): String {
        return escapeString(data, EscapeMode.base, DataUtil.UTF_8)
    }

    private fun escapeString(data: String?, escapeMode: EscapeMode, charset: Charset): String {
        if (data == null) return ""
        val sb = borrowBuilder()
        doEscape(data, wrap(sb), escapeMode, charset, ForText or ForAttribute)
        return releaseBuilder(sb)
    }

    fun escape(accum: QuietAppendable, data: String, out: Document.OutputSettings, options: Int) {
        doEscape(data, accum, out.escapeMode(), out.charset(), options)
    }

    private fun doEscape(
        data: String,
        accum: QuietAppendable,
        mode: EscapeMode,
        charset: Charset,
        options: Int
    ) {
        val coreCharset: CoreCharset = CoreCharset.Companion.byName(charset.name())
        val fallback = encoderFor(charset)
        val length = data.length

        var codePoint: Int
        var lastWasWhite = false
        var reachedNonWhite = false
        var skipped = false
        var offset = 0
        while (offset < length) {
            codePoint = data.codePointAt(offset)

            if ((options and Normalise) != 0) {
                if (isWhitespace(codePoint)) {
                    if ((options and TrimLeading) != 0 && !reachedNonWhite) {
                        offset += Character.charCount(codePoint)
                        continue
                    }
                    if (lastWasWhite) {
                        offset += Character.charCount(codePoint)
                        continue
                    }
                    if ((options and TrimTrailing) != 0) {
                        skipped = true
                        offset += Character.charCount(codePoint)
                        continue
                    }
                    accum.append(' ')
                    lastWasWhite = true
                    offset += Character.charCount(codePoint)
                    continue
                } else {
                    lastWasWhite = false
                    reachedNonWhite = true
                    if (skipped) {
                        accum.append(' ') // wasn't the end, so need to place a normalized space
                        skipped = false
                    }
                }
            }
            appendEscaped(codePoint, accum, options, mode, coreCharset, fallback)
            offset += Character.charCount(codePoint)
        }
    }

    private fun appendEscaped(
        codePoint: Int, accum: QuietAppendable, options: Int, escapeMode: EscapeMode,
        coreCharset: CoreCharset, fallback: CharsetEncoder
    ) {
        // specific character range for xml 1.0; drop (not encode) if so
        if (EscapeMode.xhtml == escapeMode && !isValidXmlChar(codePoint)) {
            return
        }

        // surrogate pairs, split implementation for efficiency on single char common case (saves creating strings, char[]):
        val c = codePoint.toChar()
        if (codePoint < Character.MIN_SUPPLEMENTARY_CODE_POINT) {
            // html specific and required escapes:
            when (c) {
                '&' -> accum.append("&amp;")
                0xA0 -> appendNbsp(accum, escapeMode)
                '<' -> accum.append("&lt;")
                '>' -> accum.append("&gt;")
                '"' -> if ((options and ForAttribute) != 0) accum.append("&quot;")
                else accum.append(c)

                '\'' ->                     // special case for the Entities.escape(string) method when we are maximally escaping. Otherwise, because we output attributes in "", there's no need to escape.
                    appendApos(accum, options, escapeMode)

                0x9, 0xA, 0xD -> accum.append(c)
                else -> if (c.code < 0x20 || !canEncode(coreCharset, c, fallback)) appendEncoded(
                    accum,
                    escapeMode,
                    codePoint
                )
                else accum.append(c)
            }
        } else {
            if (canEncode(coreCharset, c, fallback)) {
                // reads into charBuf - we go through these steps to avoid GC objects as much as possible (would be a new String and a new char[2] for each character)
                val chars = charBuf.get()
                val len = Character.toChars(codePoint, chars, 0)
                accum.append(chars, 0, len)
            } else {
                appendEncoded(accum, escapeMode, codePoint)
            }
        }
    }

    private val charBuf: ThreadLocal<CharArray> =
        ThreadLocal.withInitial<CharArray?>(Supplier { CharArray(2) })

    private fun appendNbsp(accum: QuietAppendable, escapeMode: EscapeMode?) {
        if (escapeMode != EscapeMode.xhtml) accum.append("&nbsp;")
        else accum.append("&#xa0;")
    }

    private fun appendApos(accum: QuietAppendable, options: Int, escapeMode: EscapeMode?) {
        if ((options and ForAttribute) != 0 && (options and ForText) != 0) {
            if (escapeMode == EscapeMode.xhtml) accum.append("&#x27;")
            else accum.append("&apos;")
        } else {
            accum.append('\'')
        }
    }

    private fun appendEncoded(accum: QuietAppendable, escapeMode: EscapeMode, codePoint: Int) {
        val name = escapeMode.nameForCodepoint(codePoint)
        if (emptyName != name)  // ok for identity check
            accum.append('&')!!.append(name)!!.append(';')
        else accum.append("&#x")!!.append(Integer.toHexString(codePoint))!!.append(';')
    }

    /**
     * Un-escape an HTML escaped string. That is, `&lt;` is returned as `<`.
     *
     * @param string the HTML string to un-escape
     * @return the unescaped string
     */
    fun unescape(string: String): String {
        return unescape(string, false)
    }

    /**
     * Unescape the input string.
     *
     * @param string to un-HTML-escape
     * @param strict if "strict" (that is, requires trailing ';' char, otherwise that's optional)
     * @return unescaped string
     */
    fun unescape(string: String, strict: Boolean): String {
        return unescapeEntities(string, strict)
    }

    /*
     * Provides a fast-path for Encoder.canEncode, which drastically improves performance on Android post JellyBean.
     * After KitKat, the implementation of canEncode degrades to the point of being useless. For non ASCII or UTF,
     * performance may be bad. We can add more encoders for common character sets that are impacted by performance
     * issues on Android if required.
     *
     * Benchmarks:     *
     * OLD toHtml() impl v New (fastpath) in millis
     * Wiki: 1895, 16
     * CNN: 6378, 55
     * Alterslash: 3013, 28
     * Jsoup: 167, 2
     */
    private fun canEncode(charset: CoreCharset, c: Char, fallback: CharsetEncoder): Boolean {
        // todo add more charset tests if impacted by Android's bad perf in canEncode
        when (charset) {
            CoreCharset.ascii -> return c.code < 0x80
            CoreCharset.utf -> return !(c >= Character.MIN_SURROGATE && c.code < (Character.MAX_SURROGATE.code + 1)) // !Character.isSurrogate(c); but not in Android 10 desugar
            else -> return fallback.canEncode(c)
        }
    }

    private fun isValidXmlChar(codePoint: Int): Boolean {
        // https://www.w3.org/TR/2006/REC-xml-20060816/Overview.html#charsets
        // Char	   ::=   	#x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]	any Unicode character, excluding the surrogate blocks, FFFE, and FFFF.
        return (codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFFFD) || (codePoint >= 0x10000 && codePoint <= 0x10FFFF))
    }

    // cache the last used fallback encoder to save recreating on every use
    private val LocalEncoder = ThreadLocal<CharsetEncoder?>()
    private fun encoderFor(charset: Charset): CharsetEncoder {
        var encoder = LocalEncoder.get()
        if (encoder == null || encoder.charset() != charset) {
            encoder = charset.newEncoder()
            LocalEncoder.set(encoder)
        }
        return encoder
    }

    private fun load(e: EscapeMode, pointsData: String, size: Int) {
        e.nameKeys = arrayOfNulls<String>(size)
        e.codeVals = IntArray(size)
        e.codeKeys = IntArray(size)
        e.nameVals = arrayOfNulls<String>(size)

        var i = 0
        CharacterReader(pointsData).use { reader ->
            while (!reader.isEmpty) {
                // NotNestedLessLess=10913,824;1887&

                val name = reader.consumeTo('=')
                reader.advance()
                val cp1 = reader.consumeToAny(*codeDelims).toInt(codepointRadix)
                val codeDelim = reader.current()
                reader.advance()
                val cp2: Int
                if (codeDelim == ',') {
                    cp2 = reader.consumeTo(';').toInt(codepointRadix)
                    reader.advance()
                } else {
                    cp2 = empty
                }
                val indexS = reader.consumeTo('&')
                val index = indexS.toInt(codepointRadix)
                reader.advance()

                e.nameKeys[i] = name
                e.codeVals[i] = cp1
                e.codeKeys[index] = cp1
                e.nameVals[index] = name

                if (cp2 != empty) {
                    multipoints.put(name, String(intArrayOf(cp1, cp2), 0, 2))
                }
                i++
            }
            isTrue(i == size, "Unexpected count of entities loaded")
        }
    }

    enum class EscapeMode(file: String, size: Int) {
        /**
         * Restricted entities suitable for XHTML output: lt, gt, amp, and quot only.
         */
        xhtml(EntitiesData.xmlPoints, 4),

        /**
         * Default HTML output entities.
         */
        base(EntitiesData.basePoints, 106),

        /**
         * Complete HTML entities.
         */
        extended(EntitiesData.fullPoints, 2125);

        // table of named references to their codepoints. sorted so we can binary search. built by BuildEntities.
        private var nameKeys: Array<String?>
        private var codeVals: IntArray // limitation is the few references with multiple characters; those go into multipoints.

        // table of codepoints to named entities.
        private var codeKeys: IntArray // we don't support multicodepoints to single named value currently
        private var nameVals: Array<String?>

        init {
            load(this, file, size)
        }

        fun codepointForName(name: String?): Int {
            val index = Arrays.binarySearch(nameKeys, name)
            return if (index >= 0) codeVals[index] else empty
        }

        fun nameForCodepoint(codepoint: Int): String? {
            val index = Arrays.binarySearch(codeKeys, codepoint)
            if (index >= 0) {
                // the results are ordered so lower case versions of same codepoint come after uppercase, and we prefer to emit lower
                // (and binary search for same item with multi results is undefined
                return if (index < nameVals.size - 1 && codeKeys[index + 1] == codepoint) nameVals[index + 1] else nameVals[index]
            }
            return emptyName
        }

        companion object {
            init {
                // sort the base names by length, for prefix matching
                Collections.addAll<String?>(baseSorted, *EscapeMode.base.nameKeys)
                baseSorted.sort(Comparator { a: String?, b: String? -> b!!.length - a!!.length })
            }
        }
    }

    internal enum class CoreCharset {
        ascii, utf, fallback;

        companion object {
            fun byName(name: String): CoreCharset {
                if (name == "US-ASCII") return CoreCharset.ascii
                if (name.startsWith("UTF-"))  // covers UTF-8, UTF-16, et al
                    return CoreCharset.utf
                return CoreCharset.fallback
            }
        }
    }
}
