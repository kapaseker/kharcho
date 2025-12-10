package io.kapaseker.kharcho.parser

import java.util.*

/**
 * A Tag represents an Element's name and configured options, common throughout the Document. Options may affect the parse
 * and output.
 *
 * @see TagSet
 * @see Parser.tagSet
 */
class Tag internal constructor(
    /**
     * Get this tag's name.
     *
     * @return the tag's name
     */
    var name: String, // always the lower case version of this tag, regardless of case preservation mode
    var normalName: String,
    var namespace: String
) : Cloneable {
    var options: Int = 0

    /**
     * Create a new Tag, with the given name and namespace.
     *
     * The tag is not implicitly added to any TagSet.
     * @param tagName the name of the tag. Case-sensitive.
     * @param namespace the namespace for the tag.
     * @see TagSet.valueOf
     * @since 1.20.1
     */
    constructor(tagName: String, namespace: String) : this(
        tagName,
        ParseSettings.normalName(tagName),
        namespace
    )

    /**
     * Create a new Tag, with the given name, in the HTML namespace.
     *
     * The tag is not implicitly added to any TagSet.
     * @param tagName the name of the tag. Case-sensitive.
     * @see TagSet.valueOf
     * @since 1.20.1
     */
    constructor(tagName: String) : this(
        tagName,
        ParseSettings.normalName(tagName),
        Parser.NamespaceHtml
    )

    /**
     * Get this tag's name.
     * @return the tag's name
     */
    fun name(): String {
        return this.name
    }

    /**
     * Change the tag's name. As Tags are reused throughout a Document, this will change the name for all uses of this tag.
     * @param tagName the new name of the tag. Case-sensitive.
     * @return this tag
     * @since 1.20.1
     */
    fun name(tagName: String): Tag {
        this.name = tagName
        this.normalName = ParseSettings.normalName(tagName)
        return this
    }

    /**
     * Get this tag's prefix, if it has one; else the empty string.
     *
     * For example, `<book:title>` has prefix `book`, and tag name `book:title`.
     * @return the tag's prefix
     * @since 1.20.1
     */
    fun prefix(): String {
        val pos = name.indexOf(':')
        if (pos == -1) return ""
        else return name.substring(0, pos)
    }

    /**
     * Get this tag's local name. The local name is the name without the prefix (if any).
     *
     * For exmaple, `<book:title>` has local name `title`, and tag name `book:title`.
     * @return the tag's local name
     * @since 1.20.1
     */
    fun localName(): String {
        val pos = name.indexOf(':')
        return if (pos == -1) this.name else name.substring(pos + 1)
    }

    /**
     * Get this tag's normalized (lowercased) name.
     * @return the tag's normal name.
     */
    fun normalName(): String {
        return normalName
    }

    /**
     * Get this tag's namespace.
     * @return the tag's namespace
     */
    fun namespace(): String {
        return namespace
    }

    /**
     * Set the tag's namespace. As Tags are reused throughout a Document, this will change the namespace for all uses of this tag.
     * @param namespace the new namespace of the tag.
     * @return this tag
     * @since 1.20.1
     */
    fun namespace(namespace: String): Tag {
        this.namespace = namespace
        return this
    }

    /**
     * Set an option on this tag.
     *
     * Once a tag has a setting applied, it will be considered a known tag.
     * @param option the option to set
     * @return this tag
     * @since 1.20.1
     */
    fun set(option: Int): Tag {
        options = options or option
        options = options or Known // considered known if touched
        return this
    }

    /**
     * Test if an option is set on this tag.
     *
     * @param option the option to test
     * @return true if the option is set
     * @since 1.20.1
     */
    fun andOption(option: Int): Boolean {
        return (options and option) != 0
    }

    /**
     * Clear (unset) an option from this tag.
     * @param option the option to clear
     * @return this tag
     * @since 1.20.1
     */
    fun clear(option: Int): Tag {
        options = options and option.inv()
        // considered known if touched, unless explicitly clearing known
        if (option != Known) options = options or Known
        return this
    }

    val isBlock: Boolean
        /**
         * Gets if this is a block tag.
         *
         * @return if block tag
         */
        get() = (options and Block) != 0

    /**
     * Get if this is an InlineContainer tag.
     *
     * @return true if an InlineContainer (which formats children as inline).
     */
    @Deprecated("setting is only used within the Printer. Will be removed in a future release.")
    fun formatAsBlock(): Boolean {
        return (options and InlineContainer) != 0
    }

    val isInline: Boolean
        /**
         * Gets if this tag is an inline tag. Just the opposite of isBlock.
         *
         * @return if this tag is an inline tag.
         */
        get() = (options and Block) == 0

    val isEmpty: Boolean
        /**
         * Get if this is void (aka empty) tag.
         *
         * @return true if this is a void tag
         */
        get() = (options and Void) != 0

    val isSelfClosing: Boolean
        /**
         * Get if this tag is self-closing.
         *
         * @return if this tag should be output as self-closing.
         */
        get() = (options and SelfClose) != 0 || (options and Void) != 0

    val isKnownTag: Boolean
        /**
         * Get if this is a pre-defined tag in the TagSet, or was auto created on parsing.
         *
         * @return if a known tag
         */
        get() = (options and Known) != 0

    /**
     * Get if this tag should preserve whitespace within child text nodes.
     *
     * @return if preserve whitespace
     */
    fun preserveWhitespace(): Boolean {
        return (options and PreserveWhitespace) != 0
    }

    val isFormSubmittable: Boolean
        /**
         * Get if this tag represents an element that should be submitted with a form. E.g. input, option
         * @return if submittable with a form
         */
        get() = (options and FormSubmittable) != 0

    fun setSeenSelfClose() {
        options = options or SeenSelfClose // does not change known status
    }

    /**
     * If this Tag uses a specific text TokeniserState for its content, returns that; otherwise null.
     */
    fun textState(): TokeniserState? {
        return if (andOption(RcData)) TokeniserState.Rcdata else if (andOption(Data)) TokeniserState.Rawtext else null
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o !is Tag) return false
        val tag = o
        return this.name == tag.name &&
                namespace == tag.namespace &&
                normalName == tag.normalName && options == tag.options
    }

    /**
     * Hashcode of this Tag, consisting of the tag name and namespace.
     */
    override fun hashCode(): Int {
        return Objects.hash(
            this.name,
            namespace
        ) // options not included so that mutations do not prevent use as a key
    }

    override fun toString(): String {
        return this.name
    }

    public override fun clone(): Tag {
        try {
            return super.clone() as Tag
        } catch (e: CloneNotSupportedException) {
            throw RuntimeException(e)
        }
    }


    companion object {
        /** Tag option: the tag is known (specifically defined). This impacts if options may need to be inferred (when not
         * known) in, e.g., the pretty-printer. Set when a tag is added to a TagSet, or when settings are set().  */
        var Known: Int = 1

        /** Tag option: the tag is a void tag (e.g., `<img>`), that can contain no children, and in HTML does not require closing.  */
        var Void: Int = 1 shl 1

        /** Tag option: the tag is a block tag (e.g., `<div>`, `<p>`). Causes the element to be indented when pretty-printing. If not a block, it is inline.  */
        var Block: Int = 1 shl 2

        /** Tag option: the tag is a block tag that will only hold inline tags (e.g., `<p>`); used for formatting. (Must also set Block.)  */
        @JvmField
        var InlineContainer: Int = 1 shl 3

        /** Tag option: the tag can self-close (e.g., `<foo />`).  */
        var SelfClose: Int = 1 shl 4

        /** Tag option: the tag has been seen self-closing in this parse.  */
        @JvmField
        var SeenSelfClose: Int = 1 shl 5

        /** Tag option: the tag preserves whitespace (e.g., `<pre>`).  */
        @JvmField
        var PreserveWhitespace: Int = 1 shl 6

        /** Tag option: the tag is an RCDATA element that can have text and character references (e.g., `<title>`, `<textarea>`).  */
        var RcData: Int = 1 shl 7

        /** Tag option: the tag is a Data element that can have text but not character references (e.g., `<style>`, `<script>`).  */
        @JvmField
        var Data: Int = 1 shl 8

        /** Tag option: the tag's value will be included when submitting a form (e.g., `<input>`).  */
        var FormSubmittable: Int = 1 shl 9

        /**
         * Get a Tag by name. If not previously defined (unknown), returns a new generic tag, that can do anything.
         *
         *
         * Pre-defined tags (p, div etc) will be ==, but unknown tags are not registered and will only .equals().
         *
         *
         * @param name Name of tag, e.g. "p". Case-insensitive.
         * @param namespace the namespace for the tag.
         * @param settings used to control tag name sensitivity
         * @see TagSet
         *
         * @return The tag, either defined or new generic.
         */
        /**
         * Get a Tag by name. If not previously defined (unknown), returns a new generic tag, that can do anything.
         *
         *
         * Pre-defined tags (P, DIV etc) will be ==, but unknown tags are not registered and will only .equals().
         *
         *
         * @param tagName Name of tag, e.g. "p". **Case sensitive**.
         * @return The tag, either defined or new generic.
         * @see .valueOf
         */
        @JvmStatic
        @JvmOverloads
        fun valueOf(
            tagName: String,
            namespace: String = Parser.Companion.NamespaceHtml,
            settings: ParseSettings = ParseSettings.Companion.preserveCase
        ): Tag {
            return TagSet.Html().valueOf(tagName, null, namespace, settings.preserveTagCase())
        }

        /**
         * Get a Tag by name. If not previously defined (unknown), returns a new generic tag, that can do anything.
         *
         *
         * Pre-defined tags (P, DIV etc) will be ==, but unknown tags are not registered and will only .equals().
         *
         *
         * @param tagName Name of tag, e.g. "p". **Case sensitive**.
         * @param settings used to control tag name sensitivity
         * @return The tag, either defined or new generic.
         * @see .valueOf
         */
        @JvmStatic
        fun valueOf(tagName: String, settings: ParseSettings): Tag {
            return valueOf(tagName, Parser.NamespaceHtml, settings)
        }

        /**
         * Check if this tag name is a known HTML tag.
         *
         * @param tagName name of tag
         * @return if known HTML tag
         */
        fun isKnownTag(tagName: String): Boolean {
            return TagSet.HtmlTagSet.get(tagName, Parser.Companion.NamespaceHtml) != null
        }
    }
}
