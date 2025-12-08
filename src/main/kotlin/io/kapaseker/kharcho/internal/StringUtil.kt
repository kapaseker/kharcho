package io.kapaseker.kharcho.internal

import io.kapaseker.kharcho.helper.Validate
import java.net.MalformedURLException
import java.net.URL
import java.util.*
import java.util.function.BiConsumer
import java.util.function.BinaryOperator
import java.util.function.Function
import java.util.function.Supplier
import java.util.regex.Pattern
import java.util.stream.Collector
import kotlin.math.min

/**
 * A minimal String utility class. Designed for **internal** jsoup use only - the API and outcome may change without
 * notice.
 */
object StringUtil {
    // memoised padding up to 21 (blocks 0 to 20 spaces)
    val padding: Array<String?> = arrayOf<String?>(
        "",
        " ",
        "  ",
        "   ",
        "    ",
        "     ",
        "      ",
        "       ",
        "        ",
        "         ",
        "          ",
        "           ",
        "            ",
        "             ",
        "              ",
        "               ",
        "                ",
        "                 ",
        "                  ",
        "                   ",
        "                    "
    )

    /**
     * Join a collection of strings by a separator
     * @param strings collection of string objects
     * @param sep string to place between strings
     * @return joined string
     */
    fun join(strings: MutableCollection<*>, sep: String?): String? {
        return join(strings.iterator(), sep)
    }

    /**
     * Join a collection of strings by a separator
     * @param strings iterator of string objects
     * @param sep string to place between strings
     * @return joined string
     */
    fun join(strings: MutableIterator<*>, sep: String?): String? {
        if (!strings.hasNext()) return ""

        val start: String? = strings.next().toString()
        if (!strings.hasNext())  // only one, avoid builder
            return start

        val j = StringJoiner(sep)
        j.add(start)
        while (strings.hasNext()) {
            j.add(strings.next())
        }
        return j.complete()
    }

    /**
     * Join an array of strings by a separator
     * @param strings collection of string objects
     * @param sep string to place between strings
     * @return joined string
     */
    fun join(strings: Array<String?>, sep: String?): String? {
        return join(Arrays.asList<String?>(*strings), sep)
    }

    /**
     * Returns space padding, up to a max of maxPaddingWidth.
     * @param width amount of padding desired
     * @param maxPaddingWidth maximum padding to apply. Set to `-1` for unlimited.
     * @return string of spaces * width
     */
    /**
     * Returns space padding (up to the default max of 30). Use [.padding] to specify a different limit.
     * @param width amount of padding desired
     * @return string of spaces * width
     * @see .padding
     */
    @JvmStatic
    @JvmOverloads
    fun padding(width: Int, maxPaddingWidth: Int = 30): String? {
        var width = width
        Validate.isTrue(width >= 0, "width must be >= 0")
        Validate.isTrue(maxPaddingWidth >= -1)
        if (maxPaddingWidth != -1) width = min(width, maxPaddingWidth)
        if (width < padding.size) return padding[width]
        val out = CharArray(width)
        for (i in 0..<width) out[i] = ' '
        return String(out)
    }

    /**
     * Tests if a string is blank: null, empty, or only whitespace (" ", \r\n, \t, etc)
     * @param string string to test
     * @return if string is blank
     */
    @JvmStatic
    fun isBlank(string: String?): Boolean {
        if (string == null || string.isEmpty()) return true

        val l = string.length
        for (i in 0..<l) {
            if (!isWhitespace(string.codePointAt(i))) return false
        }
        return true
    }

    /**
     * Tests if a string starts with a newline character
     * @param string string to test
     * @return if its first character is a newline
     */
    fun startsWithNewline(string: String?): Boolean {
        if (string == null || string.length == 0) return false
        return string.get(0) == '\n'
    }

    /**
     * Tests if a string is numeric, i.e. contains only ASCII digit characters
     * @param string string to test
     * @return true if only digit chars, false if empty or null or contains non-digit chars
     */
    fun isNumeric(string: String?): Boolean {
        if (string == null || string.length == 0) return false

        val l = string.length
        for (i in 0..<l) {
            if (!isDigit(string.get(i))) return false
        }
        return true
    }

    /**
     * Tests if a code point is "whitespace" as defined in the HTML spec. Used for output HTML.
     * @param c code point to test
     * @return true if code point is whitespace, false otherwise
     * @see .isActuallyWhitespace
     */
    @JvmStatic
    fun isWhitespace(c: Int): Boolean {
        return c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\u000c'.code || c == '\r'.code
    }

    /**
     * Tests if a code point is "whitespace" as defined by what it looks like. Used for Element.text etc.
     * @param c code point to test
     * @return true if code point is whitespace, false otherwise
     */
    fun isActuallyWhitespace(c: Int): Boolean {
        return c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\u000c'.code || c == '\r'.code || c == 160
        // 160 is &nbsp; (non-breaking space). Not in the spec but expected.
    }

    fun isInvisibleChar(c: Int): Boolean {
        return c == 8203 || c == 173 // zero width sp, soft hyphen
        // previously also included zw non join, zw join - but removing those breaks semantic meaning of text
    }

    /**
     * Normalise the whitespace within this string; multiple spaces collapse to a single, and all whitespace characters
     * (e.g. newline, tab) convert to a simple space.
     * @param string content to normalise
     * @return normalised string
     */
    @JvmStatic
    fun normaliseWhitespace(string: String): String {
        val sb = borrowBuilder()
        appendNormalisedWhitespace(sb, string, false)
        return releaseBuilder(sb)
    }

    /**
     * After normalizing the whitespace within a string, appends it to a string builder.
     * @param accum builder to append to
     * @param string string to normalize whitespace within
     * @param stripLeading set to true if you wish to remove any leading whitespace
     */
    @JvmStatic
    fun appendNormalisedWhitespace(accum: StringBuilder, string: String, stripLeading: Boolean) {
        var lastWasWhite = false
        var reachedNonWhite = false

        val len = string.length
        var c: Int
        var i = 0
        while (i < len) {
            c = string.codePointAt(i)
            if (isActuallyWhitespace(c)) {
                if ((stripLeading && !reachedNonWhite) || lastWasWhite) {
                    i += Character.charCount(c)
                    continue
                }
                accum.append(' ')
                lastWasWhite = true
            } else if (!isInvisibleChar(c)) {
                accum.appendCodePoint(c)
                lastWasWhite = false
                reachedNonWhite = true
            }
            i += Character.charCount(c)
        }
    }

    @JvmStatic
    fun checkIn(needle: String, vararg haystack: String): Boolean {
        val len = haystack.size
        for (i in 0..<len) {
            if (haystack[i] == needle) return true
        }
        return false
    }

    fun inSorted(needle: String?, haystack: Array<String?>): Boolean {
        return Arrays.binarySearch(haystack, needle) >= 0
    }

    /**
     * Tests that a String contains only ASCII characters.
     * @param string scanned string
     * @return true if all characters are in range 0 - 127
     */
    fun isAscii(string: String): Boolean {
        Validate.notNull(string)
        for (i in 0..<string.length) {
            val c = string.get(i).code
            if (c > 127) { // ascii range
                return false
            }
        }
        return true
    }

    private val extraDotSegmentsPattern: Pattern = Pattern.compile("^/(?>(?>\\.\\.?/)+)")

    /**
     * Create a new absolute URL, from a provided existing absolute URL and a relative URL component.
     * @param base the existing absolute base URL
     * @param relUrl the relative URL to resolve. (If it's already absolute, it will be returned)
     * @return the resolved absolute URL
     * @throws MalformedURLException if an error occurred generating the URL
     */
    @Throws(MalformedURLException::class)
    fun resolve(base: URL, relUrl: String): URL {
        var relUrl = relUrl
        relUrl = stripControlChars(relUrl)!!
        // workaround: java resolves '//path/file + ?foo' to '//path/?foo', not '//path/file?foo' as desired
        if (relUrl.startsWith("?")) relUrl = base.getPath() + relUrl
        // workaround: //example.com + ./foo = //example.com/./foo, not //example.com/foo
        val url = URL(base, relUrl)
        var fixedFile = extraDotSegmentsPattern.matcher(url.getFile()).replaceFirst("/")
        if (url.getRef() != null) {
            fixedFile = fixedFile + "#" + url.getRef()
        }
        return URL(url.getProtocol(), url.getHost(), url.getPort(), fixedFile)
    }

    /**
     * Create a new absolute URL, from a provided existing absolute URL and a relative URL component.
     * @param baseUrl the existing absolute base URL
     * @param relUrl the relative URL to resolve. (If it's already absolute, it will be returned)
     * @return an absolute URL if one was able to be generated, or the empty string if not
     */
    @JvmStatic
    fun resolve(baseUrl: String, relUrl: String): String {
        // workaround: java will allow control chars in a path URL and may treat as relative, but Chrome / Firefox will strip and may see as a scheme. Normalize to browser's view.
        var baseUrl = baseUrl
        var relUrl = relUrl
        baseUrl = stripControlChars(baseUrl)!!
        relUrl = stripControlChars(relUrl)!!
        try {
            val base: URL?
            try {
                base = URL(baseUrl)
            } catch (e: MalformedURLException) {
                // the base is unsuitable, but the attribute/rel may be abs on its own, so try that
                val abs = URL(relUrl)
                return abs.toExternalForm()
            }
            return resolve(base, relUrl).toExternalForm()
        } catch (e: MalformedURLException) {
            // it may still be valid, just that Java doesn't have a registered stream handler for it, e.g. tel
            // we test here vs at start to normalize supported URLs (e.g. HTTP -> http)
            return if (validUriScheme.matcher(relUrl).find()) relUrl else ""
        }
    }

    private val validUriScheme: Pattern = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+-.]*:")

    private val controlChars: Pattern =
        Pattern.compile("[\\x00-\\x1f]*") // matches ascii 0 - 31, to strip from url

    private fun stripControlChars(input: String): String? {
        return controlChars.matcher(input).replaceAll("")
    }

    private const val InitBuilderSize = 1024
    private val MaxBuilderSize = 8 * 1024
    private val BuilderPool = SoftPool<StringBuilder>(
        Supplier { StringBuilder(InitBuilderSize) })

    /**
     * Maintains cached StringBuilders in a flyweight pattern, to minimize new StringBuilder GCs. The StringBuilder is
     * prevented from growing too large.
     *
     *
     * Care must be taken to release the builder once its work has been completed, with [.releaseBuilder]
     * @return an empty StringBuilder
     */
    @JvmStatic
    fun borrowBuilder(): StringBuilder {
        return BuilderPool.borrow()
    }

    /**
     * Release a borrowed builder. Care must be taken not to use the builder after it has been returned, as its
     * contents may be changed by this method, or by a concurrent thread.
     * @param sb the StringBuilder to release.
     * @return the string value of the released String Builder (as an incentive to release it!).
     */
    @JvmStatic
    fun releaseBuilder(sb: StringBuilder): String {
        Validate.notNull(sb)
        val string = sb.toString()
        releaseBuilderVoid(sb)
        return string
    }

    /**
     * Releases a borrowed builder, but does not call .toString() on it. Useful in case you already have that string.
     * @param sb the StringBuilder to release.
     * @see .releaseBuilder
     */
    @JvmStatic
    fun releaseBuilderVoid(sb: StringBuilder) {
        // if it hasn't grown too big, reset it and return it to the pool:
        if (sb.length <= MaxBuilderSize) {
            sb.delete(0, sb.length) // make sure it's emptied on release
            BuilderPool.release(sb)
        }
    }

    /**
     * Return a [Collector] similar to the one returned by [Collectors.joining],
     * but backed by jsoup's [StringJoiner], which allows for more efficient garbage collection.
     *
     * @param delimiter The delimiter for separating the strings.
     * @return A `Collector` which concatenates CharSequence elements, separated by the specified delimiter
     */
    @JvmStatic
    fun joining(delimiter: String?): Collector<CharSequence?, *, String?> {
        return Collector.of<CharSequence?, StringJoiner?, String?>(
            { StringJoiner(delimiter) },
            { obj: StringJoiner?, stringy: CharSequence? -> obj!!.add(stringy) },
            { j1: StringJoiner?, j2: StringJoiner? ->
                j1!!.append(j2!!.complete())
                j1
            },
            { obj: StringJoiner? -> obj!!.complete() })
    }

    @JvmStatic
    fun isAsciiLetter(c: Char): Boolean {
        c.isLetter()
        return c in 'a'..'z' || c in 'A'..'Z'
    }

    @JvmStatic
    fun isDigit(c: Char): Boolean {
        return c in '0'..'9'
    }

    @JvmStatic
    fun isHexDigit(c: Char): Boolean {
        return isDigit(c) || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F'
    }

    /**
     * A StringJoiner allows incremental / filtered joining of a set of stringable objects.
     * @since 1.14.1
     */
    class StringJoiner
    /**
     * Create a new joiner, that uses the specified separator. MUST call [.complete] or will leak a thread
     * local string builder.
     *
     * @param separator the token to insert between strings
     */(val separator: String?) {
        var sb: StringBuilder =
            borrowBuilder() // sets null on builder release so can't accidentally be reused
        var first: Boolean = true

        /**
         * Add another item to the joiner, will be separated
         */
        fun add(stringy: Any?): StringJoiner {
            Validate.notNull(sb) // don't reuse
            if (!first) sb.append(separator)
            sb.append(stringy)
            first = false
            return this
        }

        /**
         * Append content to the current item; not separated
         */
        fun append(stringy: Any?): StringJoiner {
            Validate.notNull(sb) // don't reuse
            sb.append(stringy)
            return this
        }

        /**
         * Return the joined string, and release the builder back to the pool. This joiner cannot be reused.
         */
        fun complete(): String {
            val string = releaseBuilder(sb)
            return string
        }
    }
}
