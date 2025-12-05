package io.kapaseker.kharcho.parser

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.internal.StringUtil

/**
 * A value holder for Tokens, as the stream is Tokenized. Can hold a String or a StringBuilder.
 *
 * The goal is to minimize String copies -- the tokenizer tries to read the entirety of the token's data in one it, and
 * set that as the simple String value. But if it turns out we need to append, fall back to a StringBuilder, which we get
 * out of the pool (to reduce the GC load).
 */
internal class TokenData {
    @Nullable
    private var value: String? = null

    @Nullable
    private var builder: StringBuilder? = null

    fun set(str: String?) {
        reset()
        value = str
    }

    fun append(str: String?) {
        if (builder != null) {
            builder!!.append(str)
        } else if (value != null) {
            flipToBuilder()
            builder!!.append(str)
        } else {
            value = str
        }
    }

    fun append(c: Char) {
        if (builder != null) {
            builder!!.append(c)
        } else if (value != null) {
            flipToBuilder()
            builder!!.append(c)
        } else {
            value = c.toString()
        }
    }

    fun appendCodePoint(codepoint: Int) {
        if (builder != null) {
            builder!!.appendCodePoint(codepoint)
        } else if (value != null) {
            flipToBuilder()
            builder!!.appendCodePoint(codepoint)
        } else {
            value = String(Character.toChars(codepoint))
        }
    }

    private fun flipToBuilder() {
        builder = StringUtil.borrowBuilder()
        builder!!.append(value)
        value = null
    }

    fun hasData(): Boolean {
        return builder != null || value != null
    }

    fun reset() {
        if (builder != null) {
            StringUtil.releaseBuilderVoid(builder!!)
            builder = null
        }
        value = null
    }

    fun value(): String? {
        if (builder != null) {
            // in rare case we get hit twice, don't toString the builder twice
            value = builder.toString()
            StringUtil.releaseBuilder(builder!!)
            builder = null
            return value
        }
        return if (value != null) value else ""
    }

    override fun toString(): String {
        // for debug views; no side effects
        if (builder != null) return builder.toString()
        return (if (value != null) value else "")!!
    }
}
