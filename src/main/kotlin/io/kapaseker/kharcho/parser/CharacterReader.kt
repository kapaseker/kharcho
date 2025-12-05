package io.kapaseker.kharcho.parser

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.StringUtil
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.io.UncheckedIOException
import java.lang.AutoCloseable
import java.util.*
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.min

/**
 * CharacterReader consumes tokens off a string. Used internally by jsoup. API subject to changes.
 *
 * If the underlying reader throws an IOException during any operation, the CharacterReader will throw an
 * [UncheckedIOException]. That won't happen with String / StringReader inputs.
 */
class CharacterReader(input: Reader) : AutoCloseable {
    private var stringCache: Array<String?>? // holds reused strings in this doc, to lessen garbage
    private var reader: Reader? // underlying Reader, will be backed by a buffered+controlled input stream, or StringReader
    private var charBuf: CharArray? // character buffer we consume from; filled from Reader
    private var bufPos = 0 // position in charBuf that's been consumed to
    private var bufLength =
        0 // the num of characters actually buffered in charBuf, <= charBuf.length
    private var fillPoint =
        0 // how far into the charBuf we read before re-filling. 0.5 of charBuf.length after bufferUp
    private var consumed =
        0 // how many characters total have been consumed from this CharacterReader (less the current bufPos)
    private var bufMark = -1 // if not -1, the marked rewind position
    private var readFully =
        false // if the underlying stream has been completely read, no value in further buffering

    @Nullable
    private var newlinePositions: ArrayList<Int?>? =
        null // optionally track the pos() position of newlines - scans during bufferUp()
    private var lineNumberOffset = 1 // line numbers start at 1; += newlinePosition[indexof(pos)]

    constructor(input: Reader, sz: Int) : this(input) // sz is no longer used


    constructor(input: String) : this(StringReader(input))

    override fun close() {
        if (reader == null) return
        try {
            reader!!.close()
        } catch (ignored: IOException) {
        } finally {
            reader = null
            Arrays.fill(
                charBuf,
                0.toChar()
            ) // before release, clear the buffer. Not required, but acts as a safety net, and makes debug view clearer
            BufferPool.release(charBuf)
            charBuf = null
            StringPool.release(stringCache) // conversely, we don't clear the string cache, so we can reuse the contents
            stringCache = null
        }
    }

    private fun bufferUp() {
        if (readFully || bufPos < fillPoint || bufMark != -1) return
        doBufferUp() // structured so bufferUp may become an intrinsic candidate
    }

    /**
     * Reads into the buffer. Will throw an UncheckedIOException if the underling reader throws an IOException.
     * @throws UncheckedIOException if the underlying reader throws an IOException
     */
    private fun doBufferUp() {
        /*
        The flow:
        - if read fully, or if bufPos < fillPoint, or if marked - do not fill.
        - update readerPos (total amount consumed from this CharacterReader) += bufPos
        - shift charBuf contents such that bufPos = 0; set next read offset (bufLength) -= shift amount
        - loop read the Reader until we fill charBuf. bufLength += read.
        - readFully = true when read = -1
         */
        consumed += bufPos
        bufLength -= bufPos
        if (bufLength > 0) System.arraycopy(charBuf, bufPos, charBuf, 0, bufLength)
        bufPos = 0
        while (bufLength < BufferSize) {
            try {
                val read = reader!!.read(charBuf, bufLength, charBuf!!.size - bufLength)
                if (read == -1) {
                    readFully = true
                    break
                }
                if (read == 0) {
                    break // if we have a surrogate on the buffer boundary and trying to read 1; will have enough in our buffer to proceed
                }
                bufLength += read
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }
        fillPoint = min(bufLength, RefillPoint)

        scanBufferForNewlines() // if enabled, we index newline positions for line number tracking
        lastIcSeq = null // cache for last containsIgnoreCase(seq)
    }

    fun mark() {
        // make sure there is enough look ahead capacity
        if (bufLength - bufPos < RewindLimit) fillPoint = 0

        bufferUp()
        bufMark = bufPos
    }

    fun unmark() {
        bufMark = -1
    }

    fun rewindToMark() {
        if (bufMark == -1) throw UncheckedIOException(IOException("Mark invalid"))

        bufPos = bufMark
        unmark()
    }

    /**
     * Gets the position currently read to in the content. Starts at 0.
     * @return current position
     */
    fun pos(): Int {
        return consumed + bufPos
    }

    /** Tests if the buffer has been fully read.  */
    fun readFully(): Boolean {
        return readFully
    }

    /**
     * Enables or disables line number tracking. By default, will be **off**.Tracking line numbers improves the
     * legibility of parser error messages, for example. Tracking should be enabled before any content is read to be of
     * use.
     *
     * @param track set tracking on|off
     * @since 1.14.3
     */
    fun trackNewlines(track: Boolean) {
        if (track && newlinePositions == null) {
            newlinePositions = ArrayList<Int?>(BufferSize / 80) // rough guess of likely count
            scanBufferForNewlines() // first pass when enabled; subsequently called during bufferUp
        } else if (!track) newlinePositions = null
    }

    val isTrackNewlines: Boolean
        /**
         * Check if the tracking of newlines is enabled.
         * @return the current newline tracking state
         * @since 1.14.3
         */
        get() = newlinePositions != null

    /**
     * Get the current line number (that the reader has consumed to). Starts at line #1.
     * @return the current line number, or 1 if line tracking is not enabled.
     * @since 1.14.3
     * @see .trackNewlines
     */
    fun lineNumber(): Int {
        return lineNumber(pos())
    }

    fun lineNumber(pos: Int): Int {
        // note that this impl needs to be called before the next buffer up or line numberoffset will be wrong. if that
        // causes issues, can remove the reset of newlinepositions during buffer, at the cost of a larger tracking array
        if (!this.isTrackNewlines) return 1

        val i = lineNumIndex(pos)
        if (i == -1) return lineNumberOffset // first line

        return i + lineNumberOffset + 1
    }

    /**
     * Get the current column number (that the reader has consumed to). Starts at column #1.
     * @return the current column number
     * @since 1.14.3
     * @see .trackNewlines
     */
    fun columnNumber(): Int {
        return columnNumber(pos())
    }

    fun columnNumber(pos: Int): Int {
        if (!this.isTrackNewlines) return pos + 1

        val i = lineNumIndex(pos)
        if (i == -1) return pos + 1
        return pos - newlinePositions!!.get(i)!! + 1
    }

    /**
     * Get a formatted string representing the current line and column positions. E.g. `5:10` indicating line
     * number 5 and column number 10.
     * @return line:col position
     * @since 1.14.3
     * @see .trackNewlines
     */
    fun posLineCol(): String {
        return lineNumber().toString() + ":" + columnNumber()
    }

    private fun lineNumIndex(pos: Int): Int {
        if (!this.isTrackNewlines) return 0
        var i = Collections.binarySearch<Int?>(newlinePositions, pos)
        if (i < -1) i = abs(i) - 2
        return i
    }

    /**
     * Scans the buffer for newline position, and tracks their location in newlinePositions.
     */
    private fun scanBufferForNewlines() {
        if (!this.isTrackNewlines) return

        if (newlinePositions!!.size > 0) {
            // work out the line number that we have read up to (as we have likely scanned past this point)
            var index = lineNumIndex(consumed)
            if (index == -1) index = 0 // first line

            val linePos = newlinePositions!!.get(index)!!
            lineNumberOffset += index // the num lines we've read up to
            newlinePositions!!.clear()
            newlinePositions!!.add(linePos) // roll the last read pos to first, for cursor num after buffer
        }

        for (i in bufPos..<bufLength) {
            if (charBuf!![i] == '\n') newlinePositions!!.add(1 + consumed + i)
        }
    }

    val isEmpty: Boolean
        /**
         * Tests if all the content has been read.
         * @return true if nothing left to read.
         */
        get() {
            bufferUp()
            return bufPos >= bufLength
        }

    private val isEmptyNoBufferUp: Boolean
        get() = bufPos >= bufLength

    /**
     * Get the char at the current position.
     * @return char
     */
    fun current(): Char {
        bufferUp()
        return if (this.isEmptyNoBufferUp) EOF else charBuf!![bufPos]
    }

    /**
     * Consume one character off the queue.
     * @return first character on queue, or EOF if the queue is empty.
     */
    fun consume(): Char {
        bufferUp()
        val `val` = if (this.isEmptyNoBufferUp) EOF else charBuf!![bufPos]
        bufPos++
        return `val`
    }

    /**
     * Unconsume one character (bufPos--). MUST only be called directly after a consume(), and no chance of a bufferUp.
     */
    fun unconsume() {
        if (bufPos < 1) throw UncheckedIOException(IOException("WTF: No buffer left to unconsume.")) // a bug if this fires, need to trace it.


        bufPos--
    }

    /**
     * Moves the current position by one.
     */
    fun advance() {
        bufPos++
    }

    /**
     * Returns the number of characters between the current position and the next instance of the input char
     * @param c scan target
     * @return offset between current position and next instance of target. -1 if not found.
     */
    fun nextIndexOf(c: Char): Int {
        // doesn't handle scanning for surrogates
        bufferUp()
        for (i in bufPos..<bufLength) {
            if (c == charBuf!![i]) return i - bufPos
        }
        return -1
    }

    /**
     * Returns the number of characters between the current position and the next instance of the input sequence
     *
     * @param seq scan target
     * @return offset between current position and next instance of target. -1 if not found.
     */
    fun nextIndexOf(seq: CharSequence): Int {
        bufferUp()
        // doesn't handle scanning for surrogates
        val startChar = seq.get(0)
        var offset = bufPos
        while (offset < bufLength) {
            // scan to first instance of startchar:
            if (startChar != charBuf!![offset]) while (++offset < bufLength && startChar != charBuf!![offset]) { /* empty */
            }
            var i = offset + 1
            val last = i + seq.length - 1
            if (offset < bufLength && last <= bufLength) {
                var j = 1
                while (i < last && seq.get(j) == charBuf!![i]) {
                    i++
                    j++
                }
                if (i == last)  // found full sequence
                    return offset - bufPos
            }
            offset++
        }
        return -1
    }

    /**
     * Reads characters up to the specific char.
     * @param c the delimiter
     * @return the chars read
     */
    fun consumeTo(c: Char): String {
        val offset = nextIndexOf(c)
        if (offset != -1) {
            val consumed: String = Companion.cacheString(charBuf!!, stringCache!!, bufPos, offset)
            bufPos += offset
            return consumed
        } else {
            return consumeToEnd()
        }
    }

    /**
     * Reads the characters up to (but not including) the specified case-sensitive string.
     *
     * If the sequence is not found in the buffer, will return the remainder of the current buffered amount, less the
     * length of the sequence, such that this call may be repeated.
     * @param seq the delimiter
     * @return the chars read
     */
    fun consumeTo(seq: String): String {
        val offset = nextIndexOf(seq)
        if (offset != -1) {
            val consumed: String = Companion.cacheString(charBuf!!, stringCache!!, bufPos, offset)
            bufPos += offset
            return consumed
        } else if (bufLength - bufPos < seq.length) {
            // nextIndexOf() did a bufferUp(), so if the buffer is shorter than the search string, we must be at EOF
            return consumeToEnd()
        } else {
            // the string we're looking for may be straddling a buffer boundary, so keep (length - 1) characters
            // unread in case they contain the beginning of the search string
            val endPos = bufLength - seq.length + 1
            val consumed: String =
                Companion.cacheString(charBuf!!, stringCache!!, bufPos, endPos - bufPos)
            bufPos = endPos
            return consumed
        }
    }

    /**
     * Read characters while the input predicate returns true, up to a maximum length.
     * @param func predicate to test
     * @param maxLength maximum length to read. -1 indicates no maximum
     * @return characters read
     */
    /**
     * Read characters while the input predicate returns true.
     * @return characters read
     */
    @JvmOverloads
    fun consumeMatching(func: CharPredicate, maxLength: Int = -1): String {
        bufferUp()
        var pos = bufPos
        val start = pos
        val remaining = bufLength
        val `val` = charBuf

        while (pos < remaining && (maxLength == -1 || pos - start < maxLength) && func.test(`val`!![pos])) {
            pos++
        }

        bufPos = pos
        return if (pos > start) Companion.cacheString(
            charBuf!!,
            stringCache!!,
            start,
            pos - start
        ) else ""
    }

    /**
     * Read characters until the first of any delimiters is found.
     * @param chars delimiters to scan for
     * @return characters read up to the matched delimiter.
     */
    fun consumeToAny(vararg chars: Char): String {
        return consumeMatching(CharPredicate { c: Char ->  // seeks until we see one of the terminating chars
            for (seek in chars) if (c == seek) return@consumeMatching false
            true
        })
    }

    fun consumeToAnySorted(vararg chars: Char): String {
        return consumeMatching(CharPredicate { c: Char ->
            Arrays.binarySearch(
                chars,
                c
            ) < 0
        }) // matches until a hit
    }

    fun consumeData(): String {
        // consumes until &, <, null
        return consumeMatching(CharPredicate { c: Char -> c != '&' && c != '<' && c != TokeniserState.Companion.nullChar })
    }

    fun consumeAttributeQuoted(single: Boolean): String {
        // null, " or ', &
        return consumeMatching(CharPredicate { c: Char -> c != TokeniserState.Companion.nullChar && c != '&' && (if (single) c != '\'' else c != '"') })
    }

    fun consumeRawData(): String {
        // <, null
        return consumeMatching(CharPredicate { c: Char -> c != '<' && c != TokeniserState.Companion.nullChar })
    }

    fun consumeTagName(): String {
        // '\t', '\n', '\r', '\f', ' ', '/', '>'
        // NOTE: out of spec; does not stop and append on nullChar but eats
        return consumeMatching(CharPredicate { c: Char ->
            when (c) {
                '\t', '\n', '\r', '\f', ' ', '/', '>' -> return@consumeMatching false
            }
            true
        })
    }

    fun consumeToEnd(): String {
        bufferUp()
        val data: String =
            Companion.cacheString(charBuf!!, stringCache!!, bufPos, bufLength - bufPos)
        bufPos = bufLength
        return data
    }

    fun consumeLetterSequence(): String {
        return consumeMatching(CharPredicate { ch: Char -> Character.isLetter(ch) })
    }

    fun consumeLetterThenDigitSequence(): String {
        bufferUp()
        val start = bufPos
        while (bufPos < bufLength) {
            if (StringUtil.isAsciiLetter(charBuf!![bufPos])) bufPos++
            else break
        }
        while (!this.isEmptyNoBufferUp) {
            if (StringUtil.isDigit(charBuf!![bufPos])) bufPos++
            else break
        }

        return Companion.cacheString(charBuf!!, stringCache!!, start, bufPos - start)
    }

    fun consumeHexSequence(): String {
        return consumeMatching(CharPredicate { obj: Char -> obj.isHexDigit() })
    }

    fun consumeDigitSequence(): String {
        return consumeMatching(CharPredicate { c: Char -> c >= '0' && c <= '9' })
    }

    fun matches(c: Char): Boolean {
        return !this.isEmpty && charBuf!![bufPos] == c
    }

    fun matches(seq: String): Boolean {
        bufferUp()
        val scanLength = seq.length
        if (scanLength > bufLength - bufPos) return false

        for (offset in 0..<scanLength) if (seq.get(offset) != charBuf!![bufPos + offset]) return false
        return true
    }

    fun matchesIgnoreCase(seq: String): Boolean {
        bufferUp()
        val scanLength = seq.length
        if (scanLength > bufLength - bufPos) return false

        for (offset in 0..<scanLength) {
            var scan = seq.get(offset)
            var target = charBuf!![bufPos + offset]
            if (scan == target) continue

            scan = scan.uppercaseChar()
            target = target.uppercaseChar()
            if (scan != target) return false
        }
        return true
    }

    /**
     * Tests if the next character in the queue matches any of the characters in the sequence, case sensitively.
     * @param seq list of characters to check for
     * @return true if any matched, false if none did
     */
    fun matchesAny(vararg seq: Char): Boolean {
        if (this.isEmpty) return false

        bufferUp()
        val c = charBuf!![bufPos]
        for (seek in seq) {
            if (seek == c) return true
        }
        return false
    }

    fun matchesAnySorted(seq: CharArray): Boolean {
        bufferUp()
        return !this.isEmpty && Arrays.binarySearch(seq, charBuf!![bufPos]) >= 0
    }

    /**
     * Checks if the current pos matches an ascii alpha (A-Z a-z) per https://infra.spec.whatwg.org/#ascii-alpha
     * @return if it matches or not
     */
    fun matchesAsciiAlpha(): Boolean {
        if (this.isEmpty) return false
        return StringUtil.isAsciiLetter(charBuf!![bufPos])
    }

    fun matchesDigit(): Boolean {
        if (this.isEmpty) return false
        return StringUtil.isDigit(charBuf!![bufPos])
    }

    fun matchConsume(seq: String): Boolean {
        bufferUp()
        if (matches(seq)) {
            bufPos += seq.length
            return true
        } else {
            return false
        }
    }

    fun matchConsumeIgnoreCase(seq: String): Boolean {
        if (matchesIgnoreCase(seq)) {
            bufPos += seq.length
            return true
        } else {
            return false
        }
    }

    // we maintain a cache of the previously scanned sequence, and return that if applicable on repeated scans.
    // that improves the situation where there is a sequence of <p<p<p<p<p<p<p...</title> and we're bashing on the <p
    // looking for the </title>. Resets in bufferUp()
    @Nullable
    private var lastIcSeq: String? = null // scan cache
    private var lastIcIndex = 0 // nearest found indexOf

    init {
        Validate.notNull(input)
        reader = input
        charBuf = BufferPool.borrow()
        stringCache = StringPool.borrow()
        bufferUp()
    }

    /** Used to check presence of ,  when we're in RCData and see a <xxx. Only finds consistent case.></xxx.>  */
    fun containsIgnoreCase(seq: String): Boolean {
        if (seq == lastIcSeq) {
            if (lastIcIndex == -1) return false
            if (lastIcIndex >= bufPos) return true
        }
        lastIcSeq = seq

        val loScan = seq.lowercase()
        val lo = nextIndexOf(loScan)
        if (lo > -1) {
            lastIcIndex = bufPos + lo
            return true
        }

        val hiScan = seq.uppercase()
        val hi = nextIndexOf(hiScan)
        val found = hi > -1
        lastIcIndex =
            if (found) bufPos + hi else -1 // we don't care about finding the nearest, just that buf contains
        return found
    }

    override fun toString(): String {
        if (bufLength - bufPos < 0) return ""
        return kotlin.text.String(charBuf!!, bufPos, bufLength - bufPos)
    }

    // just used for testing
    fun rangeEquals(start: Int, count: Int, cached: String): Boolean {
        return Companion.rangeEquals(charBuf!!, start, count, cached)
    }

    internal fun interface CharPredicate {
        fun test(c: Char): Boolean
    }

    companion object {
        val EOF: Char = -1.toChar()
        private const val MaxStringCacheLen = 12
        private const val StringCacheSize = 512
        private val StringPool: SoftPool<Array<String?>?> = SoftPool<Array<String?>?>(Supplier {
            arrayOfNulls<String>(
                StringCacheSize
            )
        }) // reuse cache between iterations

        val BufferSize: Int = 1024 * 2 // visible for testing
        val RefillPoint: Int =
            BufferSize / 2 // when bufPos characters read, refill; visible for testing
        private const val RewindLimit =
            1024 // the maximum we can rewind. No HTML entities can be larger than this.

        private val BufferPool: SoftPool<CharArray?> = SoftPool<CharArray?>(Supplier {
            CharArray(
                BufferSize
            )
        }) // recycled char buffer

        /**
         * Caches short strings, as a flyweight pattern, to reduce GC load. Just for this doc, to prevent leaks.
         *
         *
         * Simplistic, and on hash collisions just falls back to creating a new string, vs a full HashMap with Entry list.
         * That saves both having to create objects as hash keys, and running through the entry list, at the expense of
         * some more duplicates.
         */
        private fun cacheString(
            charBuf: CharArray,
            stringCache: Array<String?>,
            start: Int,
            count: Int
        ): String {
            if (count > MaxStringCacheLen)  // don't cache strings that are too big
                return String(charBuf, start, count)
            if (count < 1) return ""

            // calculate hash:
            var hash = 0
            val end = count + start
            for (i in start..<end) {
                hash = 31 * hash + charBuf[i].code
            }

            // get from cache
            val index = hash and StringCacheSize - 1
            var cached = stringCache[index]

            if (cached != null && rangeEquals(charBuf, start, count, cached))  // positive hit
                return cached
            else {
                cached = String(charBuf, start, count)
                stringCache[index] =
                    cached // add or replace, assuming most recently used are most likely to recur next
            }

            return cached
        }

        /**
         * Check if the value of the provided range equals the string.
         */
        fun rangeEquals(charBuf: CharArray, start: Int, count: Int, cached: String): Boolean {
            var count = count
            if (count == cached.length) {
                var i = start
                var j = 0
                while (count-- != 0) {
                    if (charBuf[i++] != cached.get(j++)) return false
                }
                return true
            }
            return false
        }
    }
}
