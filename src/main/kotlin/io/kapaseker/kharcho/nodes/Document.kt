package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.helper.DataUtil
import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.parser.ParseSettings
import io.kapaseker.kharcho.parser.Parser
import io.kapaseker.kharcho.parser.Tag
import io.kapaseker.kharcho.select.Elements
import io.kapaseker.kharcho.select.Evaluator
import java.nio.charset.Charset

/**
 * A HTML Document.
 *
 * @author Jonathan Hedley, jonathan@hedley.net
 */
class Document private constructor(
    namespace: String?, private val location: String?, // the parser used to parse this document
    private var parser: Parser
) : Element(
    Tag("#root", namespace),
    location
) {
    private var outputSettings = OutputSettings()
    private var quirksMode: QuirksMode? = QuirksMode.noQuirks

    /**
     * Create a new, empty Document, in the specified namespace.
     * @param namespace the namespace of this Document's root node.
     * @param baseUri base URI of document
     * @see Jsoup.parse
     * @see .createShell
     */
    constructor(namespace: String?, baseUri: String?) : this(
        namespace,
        baseUri,
        Parser.htmlParser()
    ) // default HTML parser, but overridable


    /**
     * Create a new, empty Document, in the HTML namespace.
     * @param baseUri base URI of document
     * @see Jsoup.parse
     * @see .Document
     */
    constructor(baseUri: String?) : this(Parser.Companion.NamespaceHtml, baseUri)

    /**
     * Get the URL this Document was parsed from. If the starting URL is a redirect,
     * this will return the final URL from which the document was served from.
     *
     * Will return an empty string if the location is unknown (e.g. if parsed from a String).
     * @return location
     */
    fun location(): String? {
        return location
    }

    /**
     * Returns this Document's doctype.
     * @return document type, or null if not set
     */
    fun documentType(): DocumentType? {
        for (node in childNodes) {
            if (node is DocumentType) return node
            else if (node !is LeafNode)  // scans forward across comments, text, processing instructions etc
                break
        }
        return null
    }

    /**
     * Find the root HTML element, or create it if it doesn't exist.
     * @return the root HTML element.
     */
    private fun htmlEl(): Element {
        var el = firstElementChild()
        while (el != null) {
            if (el.nameIs("html")) return el
            el = el.nextElementSibling()
        }
        return appendElement("html")
    }

    /**
     * Get this document's `head` element.
     *
     *
     * As a side effect, if this Document does not already have an HTML structure, it will be created. If you do not want
     * that, use `#selectFirst("head")` instead.
     *
     * @return `head` element.
     */
    fun head(): Element? {
        val html = htmlEl()
        var el = html.firstElementChild()
        while (el != null) {
            if (el.nameIs("head")) return el
            el = el.nextElementSibling()
        }
        return html.prependElement("head")
    }

    /**
     * Get this document's `<body>` or `<frameset>` element.
     *
     *
     * As a **side-effect**, if this Document does not already have an HTML structure, it will be created with a `<body>` element. If you do not want that, use `#selectFirst("body")` instead.
     *
     * @return `body` element for documents with a `<body>`, a new `<body>` element if the document
     * had no contents, or the outermost `<frameset> element` for frameset documents.
     */
    fun body(): Element {
        val html = htmlEl()
        var el = html.firstElementChild()
        while (el != null) {
            if (el.nameIs("body") || el.nameIs("frameset")) return el
            el = el.nextElementSibling()
        }
        return html.appendElement("body")
    }

    /**
     * Get each of the `<form>` elements contained in this document.
     * @return a List of FormElement objects, which will be empty if there are none.
     * @see Elements.forms
     * @see FormElement.elements
     * @since 1.15.4
     */
    fun forms(): MutableList<FormElement?> {
        return select("form").forms()
    }

    /**
     * Selects the first [FormElement] in this document that matches the query. If none match, throws an
     * [IllegalArgumentException].
     * @param cssQuery a [Selector] CSS query
     * @return the first matching `<form>` element
     * @throws IllegalArgumentException if no match is found
     * @since 1.15.4
     */
    fun expectForm(cssQuery: String?): FormElement? {
        val els = select(cssQuery)
        for (el in els) {
            if (el is FormElement) return el
        }
        Validate.fail("No form elements matched the query '%s' in the document.", cssQuery)
        return null // (not really)
    }

    /**
     * Get the string contents of the document's `title` element.
     * @return Trimmed title, or empty string if none set.
     */
    fun title(): String {
        // title is a preserve whitespace tag (for document output), but normalised here
        val titleEl: Element? = head()!!.selectFirst(titleEval)
        return if (titleEl != null) StringUtil.normaliseWhitespace(titleEl.text())
            .trim { it <= ' ' } else ""
    }

    /**
     * Set the document's `title` element. Updates the existing element, or adds `title` to `head` if
     * not present
     * @param title string to set as title
     */
    fun title(title: String) {
        Validate.notNull(title)
        var titleEl: Element? = head()!!.selectFirst(titleEval)
        if (titleEl == null)  // add to head
            titleEl = head()!!.appendElement("title")
        titleEl.text(title)
    }

    /**
     * Create a new Element, with this document's base uri. Does not make the new element a child of this document.
     * @param tagName element tag name (e.g. `a`)
     * @return new element
     */
    fun createElement(tagName: String): Element {
        return Element(
            parser.tagSet()!!
                .valueOf(tagName, parser.defaultNamespace()!!, ParseSettings.preserveCase),
            searchUpForAttribute(this, BaseUriKey)
        )
    }

    public override fun outerHtml(): String? {
        return super.html() // no outer wrapper tag
    }

    /**
     * Set the text of the `body` of this document. Any existing nodes within the body will be cleared.
     * @param text un-encoded text
     * @return this document
     */
    override fun text(text: String?): Element {
        body()!!.text(text) // overridden to not nuke doc structure
        return this
    }

    override fun nodeName(): String? {
        return "#document"
    }

    /**
     * Set the output character set of this Document. This method is equivalent to
     * [OutputSettings.charset(Charset)][OutputSettings.charset], but additionally adds or
     * updates the charset / encoding element within the Document.
     *
     *
     * If there's no existing element with charset / encoding information yet, one will
     * be created. Obsolete charset / encoding definitions are removed.
     *
     *
     * **Elements used:**
     *
     *
     *  * **HTML:** *&lt;meta charset="CHARSET"&gt;*
     *  * **XML:** *&lt;?xml version="1.0" encoding="CHARSET"&gt;*
     *
     *
     * @param charset Charset
     * @see OutputSettings.charset
     */
    fun charset(charset: Charset) {
        outputSettings.charset(charset)
        ensureMetaCharsetElement()
    }

    /**
     * Get the output character set of this Document. This method is equivalent to [OutputSettings.charset].
     *
     * @return the current Charset
     * @see OutputSettings.charset
     */
    fun charset(): Charset {
        return outputSettings.charset()
    }

    override fun clone(): Document? {
        val clone = super.clone() as Document?
        if (attributes != null) clone!!.attributes = attributes.clone()
        clone!!.outputSettings = this.outputSettings.clone()
        // parser is pointer copy
        return clone
    }

    override fun shallowClone(): Document {
        val clone = Document(this.tag().namespace(), baseUri(), parser) // preserves parser pointer
        if (attributes != null) clone.attributes = attributes.clone()
        clone.outputSettings = this.outputSettings.clone()
        return clone
    }


    private fun ensureMetaCharsetElement() {
        val syntax = outputSettings().syntax()

        if (syntax == OutputSettings.Syntax.html) {
            val metaCharset = selectFirst("meta[charset]")
            if (metaCharset != null) {
                metaCharset.attr("charset", charset().displayName())
            } else {
                head()!!.appendElement("meta").attr("charset", charset().displayName())
            }
            select("meta[name=charset]").remove() // Remove obsolete elements
        } else if (syntax == OutputSettings.Syntax.xml) {
            val decl = ensureXmlDecl()
            decl.attr("version", "1.0")
            decl.attr("encoding", charset().displayName())
        }
    }

    private fun ensureXmlDecl(): XmlDeclaration {
        val node = firstChild()
        if (node is XmlDeclaration) {
            val decl = node
            if (decl.name() == "xml") return decl
        }
        val decl = XmlDeclaration("xml", false)
        prependChild(decl)
        return decl
    }


    /**
     * A Document's output settings control the form of the text() and html() methods.
     */
    class OutputSettings
    /**
     * Create a new OutputSettings object, with the default settings (UTF-8, HTML, EscapeMode.base, pretty-printing,
     * indent amount of 1).
     */
        : Cloneable {
        /**
         * The output serialization syntax.
         */
        enum class Syntax {
            html, xml
        }

        private var escapeMode = Entities.EscapeMode.base
        private var charset = DataUtil.UTF_8
        private var prettyPrint = true
        private var outline = false
        private var indentAmount = 1
        private var maxPaddingWidth = 30
        private var syntax: Syntax? = Syntax.html

        /**
         * Get the document's current entity escape mode:
         *
         *  * `xhtml`, the minimal named entities in XHTML / XML
         *  * `base`, which provides a limited set of named HTML
         * entities and escapes other characters as numbered entities for maximum compatibility
         *  * `extended`,
         * which uses the complete set of HTML named entities.
         *
         *
         * The default escape mode is `base`.
         * @return the document's current escape mode
         */
        fun escapeMode(): Entities.EscapeMode {
            return escapeMode
        }

        /**
         * Set the document's escape mode, which determines how characters are escaped when the output character set
         * does not support a given character:- using either a named or a numbered escape.
         * @param escapeMode the new escape mode to use
         * @return the document's output settings, for chaining
         */
        fun escapeMode(escapeMode: Entities.EscapeMode): OutputSettings {
            this.escapeMode = escapeMode
            return this
        }

        /**
         * Get the document's current output charset, which is used to control which characters are escaped when
         * generating HTML (via the `html()` methods), and which are kept intact.
         *
         *
         * Where possible (when parsing from a URL or File), the document's output charset is automatically set to the
         * input charset. Otherwise, it defaults to UTF-8.
         * @return the document's current charset.
         */
        fun charset(): Charset {
            return charset
        }

        /**
         * Update the document's output charset.
         * @param charset the new charset to use.
         * @return the document's output settings, for chaining
         */
        fun charset(charset: Charset): OutputSettings {
            this.charset = charset
            return this
        }

        /**
         * Update the document's output charset.
         * @param charset the new charset (by name) to use.
         * @return the document's output settings, for chaining
         */
        fun charset(charset: String): OutputSettings {
            charset(Charset.forName(charset))
            return this
        }

        /**
         * Get the document's current output syntax.
         * @return current syntax
         */
        fun syntax(): Syntax? {
            return syntax
        }

        /**
         * Set the document's output syntax. Either `html`, with empty tags and boolean attributes (etc), or
         * `xml`, with self-closing tags.
         *
         * When set to [xml][Syntax.xml], the [escapeMode][.escapeMode] is
         * automatically set to [Entities.EscapeMode.xhtml], but may be subsequently changed if desired.
         * @param syntax serialization syntax
         * @return the document's output settings, for chaining
         */
        fun syntax(syntax: Syntax?): OutputSettings {
            this.syntax = syntax
            if (syntax == Syntax.xml) this.escapeMode(Entities.EscapeMode.xhtml)
            return this
        }

        /**
         * Get if pretty printing is enabled. Default is true. If disabled, the HTML output methods will not re-format
         * the output, and the output will generally look like the input.
         * @return if pretty printing is enabled.
         */
        fun prettyPrint(): Boolean {
            return prettyPrint
        }

        /**
         * Enable or disable pretty printing.
         * @param pretty new pretty print setting
         * @return this, for chaining
         */
        fun prettyPrint(pretty: Boolean): OutputSettings {
            prettyPrint = pretty
            return this
        }

        /**
         * Get if outline mode is enabled. Default is false. If enabled, the HTML output methods will consider
         * all tags as block.
         * @return if outline mode is enabled.
         */
        fun outline(): Boolean {
            return outline
        }

        /**
         * Enable or disable HTML outline mode.
         * @param outlineMode new outline setting
         * @return this, for chaining
         */
        fun outline(outlineMode: Boolean): OutputSettings {
            outline = outlineMode
            return this
        }

        /**
         * Get the current tag indent amount, used when pretty printing.
         * @return the current indent amount
         */
        fun indentAmount(): Int {
            return indentAmount
        }

        /**
         * Set the indent amount for pretty printing
         * @param indentAmount number of spaces to use for indenting each level. Must be &gt;= 0.
         * @return this, for chaining
         */
        fun indentAmount(indentAmount: Int): OutputSettings {
            Validate.isTrue(indentAmount >= 0)
            this.indentAmount = indentAmount
            return this
        }

        /**
         * Get the current max padding amount, used when pretty printing
         * so very deeply nested nodes don't get insane padding amounts.
         * @return the current indent amount
         */
        fun maxPaddingWidth(): Int {
            return maxPaddingWidth
        }

        /**
         * Set the max padding amount for pretty printing so very deeply nested nodes don't get insane padding amounts.
         * @param maxPaddingWidth number of spaces to use for indenting each level of nested nodes. Must be &gt;= -1.
         * Default is 30 and -1 means unlimited.
         * @return this, for chaining
         */
        fun maxPaddingWidth(maxPaddingWidth: Int): OutputSettings {
            Validate.isTrue(maxPaddingWidth >= -1)
            this.maxPaddingWidth = maxPaddingWidth
            return this
        }

        public override fun clone(): OutputSettings {
            val clone: OutputSettings
            try {
                clone = super.clone() as OutputSettings
            } catch (e: CloneNotSupportedException) {
                throw RuntimeException(e)
            }
            clone.charset(charset.name()) // new charset, coreCharset, and charset encoder
            clone.escapeMode = Entities.EscapeMode.valueOf(escapeMode.name)
            // indentAmount, maxPaddingWidth, and prettyPrint are primitives so object.clone() will handle
            return clone
        }
    }

    /**
     * Get the document's current output settings.
     * @return the document's current output settings.
     */
    fun outputSettings(): OutputSettings {
        return outputSettings
    }

    /**
     * Set the document's output settings.
     * @param outputSettings new output settings.
     * @return this document, for chaining.
     */
    fun outputSettings(outputSettings: OutputSettings): Document {
        Validate.notNull(outputSettings)
        this.outputSettings = outputSettings
        return this
    }

    enum class QuirksMode {
        noQuirks, quirks, limitedQuirks
    }

    fun quirksMode(): QuirksMode? {
        return quirksMode
    }

    fun quirksMode(quirksMode: QuirksMode?): Document {
        this.quirksMode = quirksMode
        return this
    }

    /**
     * Get the parser that was used to parse this document.
     * @return the parser
     */
    fun parser(): Parser {
        return parser
    }

    /**
     * Set the parser used to create this document. This parser is then used when further parsing within this document
     * is required.
     * @param parser the configured parser to use when further parsing is required for this document.
     * @return this document, for chaining.
     */
    fun parser(parser: Parser): Document {
        this.parser = parser
        return this
    }

    companion object {
        /**
         * Create a valid, empty shell of an HTML document, suitable for adding more elements to.
         * @param baseUri baseUri of document
         * @return document with html, head, and body elements.
         */
        fun createShell(baseUri: String): Document {
            Validate.notNull(baseUri)

            val doc = Document(baseUri)
            val html = doc.appendElement("html")
            html.appendElement("head")
            html.appendElement("body")

            return doc
        }

        private val titleEval: Evaluator = Evaluator.Tag("title")
    }
}
