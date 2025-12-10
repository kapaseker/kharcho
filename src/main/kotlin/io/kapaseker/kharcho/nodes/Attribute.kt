package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.Normalizer
import io.kapaseker.kharcho.internal.QuietAppendable
import io.kapaseker.kharcho.internal.SharedConstants
import io.kapaseker.kharcho.internal.StringUtil
import java.io.IOException
import java.util.*
import java.util.regex.Pattern

/**
 * A single key + value attribute. (Only used for presentation.)
 */
open class Attribute(_key: String, attrVal: String?, parent: Attributes?) : MutableMap.MutableEntry<String, String>, Cloneable {

    override lateinit var key: String

    private var attrVal: String?

    var parent: Attributes? // used to update the holding Attributes when the key / value is changed via this interface


    /**
     * Create a new attribute from unencoded (raw) key and value.
     * @param key attribute key; case is preserved.
     * @param value attribute value (may be null)
     * @param parent the containing Attributes (this Attribute is not automatically added to said Attributes)
     * @see .createFromEncoded
     */
    init {
        val iKey = _key.trim()
        Validate.notEmpty(iKey) // trimming could potentially make empty, so validate here
        this.key = iKey
        this.attrVal = attrVal
        this.parent = parent
    }

    /**
     * Create a new attribute from unencoded (raw) key and value.
     * @param key attribute key; case is preserved.
     * @param value attribute value (may be null)
     * @see .createFromEncoded
     */
    constructor(key: String, value: String?) : this(key, value, null)

    /**
     * Set the attribute key; case is preserved.
     * @param key the new key; must not be null
     */
    fun setKey(key: String) {
        var key = key
        Validate.notNull(key)
        key = key.trim()
        Validate.notEmpty(key) // trimming could potentially make empty, so validate here
        if (parent != null) {
            val i = parent!!.indexOfKey(this.key)
            if (i != Attributes.NotFound) {
                val oldKey = parent!!.keys[i]
                parent!!.keys[i] = key

                // if tracking source positions, update the key in the range map
                val ranges: MutableMap<String, Range.AttributeRange>? = parent!!.ranges
                if (ranges != null) {
                    val range: Range.AttributeRange? = ranges.remove(oldKey)
                    if (range != null) {
                        ranges[key] = range
                    }
                }
            }
        }
        this.key = key
    }

    override val value: String
        /**
         * Get the attribute value. Will return an empty string if the value is not set.
         * @return the attribute value
         */
        get() = Attributes.checkNotNull(attrVal)

    /**
     * Check if this Attribute has a value. Set boolean attributes have no value.
     * @return if this is a boolean attribute / attribute without a value
     */
    fun hasDeclaredValue(): Boolean {
        return attrVal != null
    }

    /**
     * Set the attribute value.
     * @param newValue the new attribute value; may be null (to set an enabled boolean attribute)
     * @return the previous value (if was null; an empty string)
     */
    override fun setValue(newValue: String): String {
        var oldVal = this.attrVal
        if (parent != null) {
            val i = parent!!.indexOfKey(this.key)
            if (i != Attributes.NotFound) {
                oldVal = parent!!.get(this.key) // trust the container more
                parent!!.vals[i] = newValue
            }
        }
        this.attrVal = newValue
        return Attributes.checkNotNull(oldVal)
    }

    /**
     * Get this attribute's key prefix, if it has one; else the empty string.
     * 
     * For example, the attribute `og:title` has prefix `og`, and local `title`.
     * 
     * @return the tag's prefix
     * @since 1.20.1
     */
    fun prefix(): String {
        val pos = key.indexOf(':')
        if (pos == -1) return ""
        else return key.substring(0, pos)
    }

    /**
     * Get this attribute's local name. The local name is the name without the prefix (if any).
     * 
     * For example, the attribute key `og:title` has local name `title`.
     * 
     * @return the tag's local name
     * @since 1.20.1
     */
    fun localName(): String? {
        val pos = key.indexOf(':')
        if (pos == -1) return key
        else return key.substring(pos + 1)
    }

    /**
     * Get this attribute's namespace URI, if the attribute was prefixed with a defined namespace name. Otherwise, returns
     * the empty string. These will only be defined if using the XML parser.
     * @return the tag's namespace URI, or empty string if not defined
     * @since 1.20.1
     */
    fun namespace(): String {
        // set as el.attributes.userData(SharedConstants.XmlnsAttr + prefix, ns)
        if (parent != null) {
            val ns = parent!!.userData(SharedConstants.XmlnsAttr + prefix()) as String?
            if (ns != null) return ns
        }
        return ""
    }

    /**
     * Get the HTML representation of this attribute; e.g. `href="index.html"`.
     * @return HTML
     */
    fun html(): String {
        val sb: StringBuilder = StringUtil.borrowBuilder()
        html(QuietAppendable.wrap(sb), Document.OutputSettings())
        return StringUtil.releaseBuilder(sb)
    }

    /**
     * Get the source ranges (start to end positions) in the original input source from which this attribute's **name**
     * and **value** were parsed.
     * 
     * Position tracking must be enabled prior to parsing the content.
     * @return the ranges for the attribute's name and value, or `untracked` if the attribute does not exist or its range
     * was not tracked.
     * @see org.jsoup.parser.Parser.setTrackPosition
     * @see Attributes.sourceRange
     * @see Node.sourceRange
     * @see Element.endSourceRange
     * @since 1.17.1
     */
    fun sourceRange(): Range.AttributeRange {
        if (parent == null) return Range.AttributeRange.UntrackedAttr
        return parent!!.sourceRange(key)
    }

    fun html(accum: QuietAppendable, out: Document.OutputSettings) {
        Companion.html(key, attrVal, accum, out)
    }

    @Deprecated("internal method; use {@link #html(String, String, QuietAppendable, Document.OutputSettings)} with {@link org.jsoup.internal.QuietAppendable#wrap(Appendable)} instead. Will be removed in jsoup 1.24.1. ")
    @Throws(IOException::class)
    protected fun html(accum: Appendable, out: Document.OutputSettings) {
        html(key, attrVal, accum, out)
    }


    /**
     * Get the string representation of this attribute, implemented as [.html].
     * @return string
     */
    override fun toString(): String {
        return html()
    }

    val isDataAttribute: Boolean
        get() = isDataAttribute(key)

    /**
     * Collapsible if it's a boolean attribute and value is empty or same as name
     * 
     * @param out output settings
     * @return  Returns whether collapsible or not
     */
    @Deprecated("internal method; use {@link #shouldCollapseAttribute(String, String, Document.OutputSettings)} instead. Will be removed in jsoup 1.24.1.")
    protected fun shouldCollapseAttribute(out: Document.OutputSettings): Boolean {
        return shouldCollapseAttribute(key, attrVal, out)
    }

    override fun equals(o: Any?): Boolean { // note parent not considered
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false
        val attribute = o as Attribute
        return key == attribute.key && attrVal == attribute.attrVal
    }

    override fun hashCode(): Int { // note parent not considered
        return Objects.hash(key, attrVal)
    }

    public override fun clone(): Attribute {
        try {
            return super.clone() as Attribute
        } catch (e: CloneNotSupportedException) {
            throw RuntimeException(e)
        }
    }

    companion object {
        private val booleanAttributes = arrayOf<String?>(
            "allowfullscreen",
            "async",
            "autofocus",
            "checked",
            "compact",
            "declare",
            "default",
            "defer",
            "disabled",
            "formnovalidate",
            "hidden",
            "inert",
            "ismap",
            "itemscope",
            "multiple",
            "muted",
            "nohref",
            "noresize",
            "noshade",
            "novalidate",
            "nowrap",
            "open",
            "readonly",
            "required",
            "reversed",
            "seamless",
            "selected",
            "sortable",
            "truespeed",
            "typemustmatch"
        )

        fun html(key: String, `val`: String?, accum: QuietAppendable, out: Document.OutputSettings) {
            val newKey = getValidKey(key, out.syntax()) ?: return
            htmlNoValidate(newKey, `val`, accum, out)
        }

        @Deprecated("internal method; use {@link #html(String, String, QuietAppendable, Document.OutputSettings)} with {@link org.jsoup.internal.QuietAppendable#wrap(Appendable)} instead. Will be removed in jsoup 1.24.1. ")
        @Throws(IOException::class)
        protected fun html(key: String, `val`: String?, accum: Appendable, out: Document.OutputSettings) {
            html(key, `val`, QuietAppendable.wrap(accum), out)
        }

        fun htmlNoValidate(key: String, `val`: String?, accum: QuietAppendable, out: Document.OutputSettings) {
            // structured like this so that Attributes can check we can write first, so it can add whitespace correctly
            accum.append(key)
            if (!shouldCollapseAttribute(key, `val`, out)) {
                accum.append("=\"")
                Entities.escape(accum, Attributes.checkNotNull(`val`), out, Entities.ForAttribute) // preserves whitespace
                accum.append('"')
            }
        }

        private val xmlKeyReplace: Pattern = Pattern.compile("[^-a-zA-Z0-9_:.]+")
        private val htmlKeyReplace: Pattern = Pattern.compile("[\\x00-\\x1f\\x7f-\\x9f \"'/=]+")

        /**
         * Get a valid attribute key for the given syntax. If the key is not valid, it will be coerced into a valid key.
         * @param key the original attribute key
         * @param syntax HTML or XML
         * @return the original key if it's valid; a key with invalid characters replaced with "_" otherwise; or null if a valid key could not be created.
         */
        fun getValidKey(key: String, syntax: Document.OutputSettings.Syntax): String? {
            var key = key
            if (syntax === Document.OutputSettings.Syntax.xml && !isValidXmlKey(key)) {
                key = xmlKeyReplace.matcher(key).replaceAll("_")
                return if (isValidXmlKey(key)) key else null // null if could not be coerced
            } else if (syntax === Document.OutputSettings.Syntax.html && !isValidHtmlKey(key)) {
                key = htmlKeyReplace.matcher(key).replaceAll("_")
                return if (isValidHtmlKey(key)) key else null // null if could not be coerced
            }
            return key
        }

        // perf critical in html() so using manual scan vs regex:
        // note that we aren't using anything in supplemental space, so OK to iter charAt
        private fun isValidXmlKey(key: String): Boolean {
            // =~ [a-zA-Z_:][-a-zA-Z0-9_:.]*
            val length = key.length
            if (length == 0) return false
            var c = key.get(0)
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == ':')) return false
            for (i in 1..<length) {
                c = key.get(i)
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == ':' || c == '.')) return false
            }
            return true
        }

        private fun isValidHtmlKey(key: String): Boolean {
            // =~ [\x00-\x1f\x7f-\x9f "'/=]+
            val length = key.length
            if (length == 0) return false
            for (i in 0..<length) {
                val c = key.get(i)
                if ((c.code <= 0x1f) || (c.code >= 0x7f && c.code <= 0x9f) || c == ' ' || c == '"' || c == '\'' || c == '/' || c == '=') return false
            }
            return true
        }

        /**
         * Create a new Attribute from an unencoded key and a HTML attribute encoded value.
         * @param unencodedKey assumes the key is not encoded, as can be only run of simple \w chars.
         * @param encodedValue HTML attribute encoded value
         * @return attribute
         */
        fun createFromEncoded(unencodedKey: String, encodedValue: String): Attribute {
            val value = Entities.unescape(encodedValue, true)
            return Attribute(unencodedKey, value, null) // parent will get set when Put
        }

        protected fun isDataAttribute(key: String): Boolean {
            return key.startsWith(Attributes.dataPrefix) && key.length > Attributes.dataPrefix.length
        }

        // collapse unknown foo=null, known checked=null, checked="", checked=checked; write out others
        protected fun shouldCollapseAttribute(key: String?, `val`: String?, out: Document.OutputSettings): Boolean {
            return (out.syntax() == Document.OutputSettings.Syntax.html && (`val` == null || (`val`.isEmpty() || `val`.equals(key, ignoreCase = true)) && isBooleanAttribute(key)))
        }

        /**
         * Checks if this attribute name is defined as a boolean attribute in HTML5
         */
        fun isBooleanAttribute(key: String?): Boolean {
            return Arrays.binarySearch(booleanAttributes, Normalizer.lowerCase(key)) >= 0
        }
    }
}
