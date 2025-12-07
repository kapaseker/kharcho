package io.kapaseker.kharcho.parser

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.nodes.Document
import io.kapaseker.kharcho.nodes.Element
import io.kapaseker.kharcho.nodes.Node
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.locks.ReentrantLock

/**
 * Parses HTML or XML into a [Document]. Generally, it is simpler to use one of the parse methods in
 * [io.kapaseker.kharcho.Jsoup].
 *
 * Note that a given Parser instance object is threadsafe, but not concurrent. (Concurrent parse calls will
 * synchronize.) To reuse a Parser configuration in a multithreaded environment, use [.newInstance] to make
 * copies.
 */
class Parser : Cloneable {
    /**
     * Get the TreeBuilder currently in use.
     * @return current TreeBuilder.
     */
    val treeBuilder: TreeBuilder

    /**
     * Retrieve the parse errors, if any, from the last parse.
     * @return list of parse errors, up to the size of the maximum errors tracked.
     * @see .setTrackErrors
     */
    var errors: ParseErrorList
        private set
    private var settings: ParseSettings

    /**
     * Test if position tracking is enabled. If it is, Nodes will have a Position to track where in the original input
     * source they were created from. By default, tracking is not enabled.
     * @return current track position setting
     */
    var isTrackPosition: Boolean = false
        private set

    @Nullable
    private var tagSet: TagSet? = null
    private val lock = ReentrantLock()

    /**
     * Get the maximum parser depth (maximum number of open elements).
     * @return the current max parser depth
     */
    var maxDepth: Int
        private set

    /**
     * Create a new Parser, using the specified TreeBuilder
     * @param treeBuilder TreeBuilder to use to parse input into Documents.
     */
    constructor(treeBuilder: TreeBuilder) {
        this.treeBuilder = treeBuilder
        settings = treeBuilder.defaultSettings()
        errors = ParseErrorList.Companion.noTracking()
        maxDepth = treeBuilder.defaultMaxDepth()
    }

    /**
     * Creates a new Parser as a deep copy of this; including initializing a new TreeBuilder. Allows independent (multi-threaded) use.
     * @return a copied parser
     */
    fun newInstance(): Parser {
        return Parser(this)
    }

    public override fun clone(): Parser {
        return Parser(this)
    }

    private constructor(copy: Parser) {
        treeBuilder = copy.treeBuilder.newInstance() // because extended
        errors = ParseErrorList(copy.errors) // only copies size, not contents
        settings = ParseSettings(copy.settings)
        this.isTrackPosition = copy.isTrackPosition
        maxDepth = copy.maxDepth
        tagSet = TagSet(copy.tagSet())
    }

    /**
     * Parse the contents of a String.
     *
     * @param html HTML to parse
     * @param baseUri base URI of document (i.e. original fetch location), for resolving relative URLs.
     * @return parsed Document
     */
    fun parseInput(html: String, baseUri: String): Document {
        return parseInput(StringReader(html), baseUri)
    }

    /**
     * Parse the contents of Reader.
     *
     * @param inputHtml HTML to parse
     * @param baseUri base URI of document (i.e. original fetch location), for resolving relative URLs.
     * @return parsed Document
     * @throws java.io.UncheckedIOException if an I/O error occurs in the Reader
     */
    fun parseInput(inputHtml: Reader?, baseUri: String?): Document? {
        try {
            lock.lock() // using a lock vs synchronized to support loom threads
            return treeBuilder.parse(inputHtml, baseUri, this)
        } finally {
            lock.unlock()
        }
    }

    /**
     * Parse a fragment of HTML into a list of nodes. The context element, if supplied, supplies parsing context.
     *
     * @param fragment the fragment of HTML to parse
     * @param context (optional) the element that this HTML fragment is being parsed for (i.e. for inner HTML).
     * @param baseUri base URI of document (i.e. original fetch location), for resolving relative URLs.
     * @return list of nodes parsed from the input HTML.
     */
    fun parseFragmentInput(
        fragment: String,
        context: Element?,
        baseUri: String
    ): List<Node> {
        return parseFragmentInput(StringReader(fragment), context, baseUri)
    }

    /**
     * Parse a fragment of HTML into a list of nodes. The context element, if supplied, supplies parsing context.
     *
     * @param fragment the fragment of HTML to parse
     * @param context (optional) the element that this HTML fragment is being parsed for (i.e. for inner HTML).
     * @param baseUri base URI of document (i.e. original fetch location), for resolving relative URLs.
     * @return list of nodes parsed from the input HTML.
     * @throws java.io.UncheckedIOException if an I/O error occurs in the Reader
     */
    fun parseFragmentInput(
        fragment: Reader,
        context: Element?,
        baseUri: String
    ): List<Node> {
        try {
            lock.lock()
            return treeBuilder.parseFragment(fragment, context, baseUri, this)
        } finally {
            lock.unlock()
        }
    }

    // gets & sets

    val isTrackErrors: Boolean
        /**
         * Check if parse error tracking is enabled.
         * @return current track error state.
         */
        get() = errors.getMaxSize() > 0

    /**
     * Enable or disable parse error tracking for the next parse.
     * @param maxErrors the maximum number of errors to track. Set to 0 to disable.
     * @return this, for chaining
     */
    fun setTrackErrors(maxErrors: Int): Parser {
        errors =
            if (maxErrors > 0) ParseErrorList.Companion.tracking(maxErrors) else ParseErrorList.Companion.noTracking()
        return this
    }

    /**
     * Enable or disable source position tracking. If enabled, Nodes will have a Position to track where in the original
     * input source they were created from.
     * @param trackPosition position tracking setting; `true` to enable
     * @return this Parser, for chaining
     */
    fun setTrackPosition(trackPosition: Boolean): Parser {
        this.isTrackPosition = trackPosition
        return this
    }

    /**
     * Update the ParseSettings of this Parser, to control the case sensitivity of tags and attributes.
     * @param settings the new settings
     * @return this Parser
     */
    fun settings(settings: ParseSettings): Parser {
        this.settings = settings
        return this
    }

    /**
     * Gets the current ParseSettings for this Parser
     * @return current ParseSettings
     */
    fun settings(): ParseSettings {
        return settings
    }

    /**
     * Set the parser's maximum stack depth (maximum number of open elements). When reached, new open elements will be
     * removed to prevent excessive nesting. Defaults to 512 for the HTML parser, and unlimited for the XML
     * parser.
     *
     * @param maxDepth maximum parser depth; must be >= 1
     * @return this Parser, for chaining
     */
    fun setMaxDepth(maxDepth: Int): Parser {
        Validate.isTrue(maxDepth >= 1, "maxDepth must be >= 1")
        this.maxDepth = maxDepth
        return this
    }

    /**
     * Set a custom TagSet to use for this Parser. This allows you to define your own tags, and control how they are
     * parsed. For example, you can set a tag to preserve whitespace, or to be treated as a block tag.
     *
     * You can start with the [TagSet.Html] defaults and customize, or a new empty TagSet.
     *
     * @param tagSet the TagSet to use. This gets copied, so that changes that the parse makes (tags found in the document will be added) do not clobber the original TagSet.
     * @return this Parser
     * @since 1.20.1
     */
    fun tagSet(tagSet: TagSet): Parser {
        Validate.notNull(tagSet)
        this.tagSet = TagSet(tagSet) // copy it as we are going to mutate it
        return this
    }

    /**
     * Get the current TagSet for this Parser, which will be either this parser's default, or one that you have set.
     * @return the current TagSet. After the parse, this will contain any new tags that were found in the document.
     * @since 1.20.1
     */
    fun tagSet(): TagSet? {
        if (tagSet == null) tagSet = treeBuilder.defaultTagSet()
        return tagSet
    }

    fun defaultNamespace(): String? {
        return this.treeBuilder.defaultNamespace()
    }

    /**
     * Utility method to unescape HTML entities from a string, using this `Parser`'s configuration (for example, to
     * collect errors while unescaping).
     *
     * @param string HTML escaped string
     * @param inAttribute if the string is to be escaped in strict mode (as attributes are)
     * @return an unescaped string
     * @see .setTrackErrors
     * @see .unescapeEntities
     */
    fun unescape(string: String, inAttribute: Boolean): String {
        Validate.notNull(string)
        if (string.indexOf('&') < 0) return string // nothing to unescape

        this.treeBuilder.initialiseParse(StringReader(string), "", this)
        val tokeniser = Tokeniser(this.treeBuilder)
        return tokeniser.unescapeEntities(inAttribute)
    }

    companion object {
        const val NamespaceHtml: String = "http://www.w3.org/1999/xhtml"
        const val NamespaceXml: String = "http://www.w3.org/XML/1998/namespace"
        const val NamespaceMathml: String = "http://www.w3.org/1998/Math/MathML"
        const val NamespaceSvg: String = "http://www.w3.org/2000/svg"

        // static parse functions below
        /**
         * Parse HTML into a Document.
         *
         * @param html HTML to parse
         * @param baseUri base URI of document (i.e. original fetch location), for resolving relative URLs.
         *
         * @return parsed Document
         */
        fun parse(html: String, baseUri: String): Document {
            val treeBuilder: TreeBuilder = HtmlTreeBuilder()
            return treeBuilder.parse(StringReader(html), baseUri, Parser(treeBuilder))
        }

        /**
         * Parse a fragment of HTML into a list of nodes. The context element, if supplied, supplies parsing context.
         *
         * @param fragmentHtml the fragment of HTML to parse
         * @param context (optional) the element that this HTML fragment is being parsed for (i.e. for inner HTML). This
         * provides stack context (for implicit element creation).
         * @param baseUri base URI of document (i.e. original fetch location), for resolving relative URLs.
         *
         * @return list of nodes parsed from the input HTML. Note that the context element, if supplied, is not modified.
         */
        fun parseFragment(
            fragmentHtml: String,
            context: Element?,
            baseUri: String?
        ): MutableList<Node?>? {
            val treeBuilder = HtmlTreeBuilder()
            return treeBuilder.parseFragment(
                StringReader(fragmentHtml),
                context,
                baseUri,
                Parser(treeBuilder)
            )
        }

        /**
         * Parse a fragment of HTML into a list of nodes. The context element, if supplied, supplies parsing context.
         *
         * @param fragmentHtml the fragment of HTML to parse
         * @param context (optional) the element that this HTML fragment is being parsed for (i.e. for inner HTML). This
         * provides stack context (for implicit element creation).
         * @param baseUri base URI of document (i.e. original fetch location), for resolving relative URLs.
         * @param errorList list to add errors to
         *
         * @return list of nodes parsed from the input HTML. Note that the context element, if supplied, is not modified.
         */
        @JvmStatic
        fun parseFragment(
            fragmentHtml: String,
            context: Element?,
            baseUri: String?,
            errorList: ParseErrorList
        ): MutableList<Node?>? {
            val treeBuilder = HtmlTreeBuilder()
            val parser = Parser(treeBuilder)
            parser.errors = errorList
            return treeBuilder.parseFragment(StringReader(fragmentHtml), context, baseUri, parser)
        }

        /**
         * Parse a fragment of XML into a list of nodes.
         *
         * @param fragmentXml the fragment of XML to parse
         * @param baseUri base URI of document (i.e. original fetch location), for resolving relative URLs.
         * @return list of nodes parsed from the input XML.
         */
        fun parseXmlFragment(fragmentXml: String, baseUri: String?): MutableList<Node?>? {
            val treeBuilder = XmlTreeBuilder()
            return treeBuilder.parseFragment(
                StringReader(fragmentXml),
                null,
                baseUri,
                Parser(treeBuilder)
            )
        }

        /**
         * Parse a fragment of HTML into the `body` of a Document.
         *
         * @param bodyHtml fragment of HTML
         * @param baseUri base URI of document (i.e. original fetch location), for resolving relative URLs.
         *
         * @return Document, with empty head, and HTML parsed into body
         */
        fun parseBodyFragment(bodyHtml: String, baseUri: String?): Document {
            val doc = Document.createShell(baseUri)
            val body = doc.body()
            val nodeList: MutableList<Node?>? = parseFragment(bodyHtml, body, baseUri)
            body.appendChildren(nodeList)
            return doc
        }

        /**
         * Utility method to unescape HTML entities from a string.
         *
         * To track errors while unescaping, use
         * [.unescape] with a Parser instance that has error tracking enabled.
         *
         * @param string HTML escaped string
         * @param inAttribute if the string is to be escaped in strict mode (as attributes are)
         * @return an unescaped string
         * @see .unescape
         */
        @JvmStatic
        fun unescapeEntities(string: String, inAttribute: Boolean): String {
            Validate.notNull(string)
            if (string.indexOf('&') < 0) return string // nothing to unescape

            return htmlParser().unescape(string, inAttribute)
        }

        // builders
        /**
         * Create a new HTML parser. This parser treats input as HTML5, and enforces the creation of a normalised document,
         * based on a knowledge of the semantics of the incoming tags.
         * @return a new HTML parser.
         */
        @JvmStatic
        fun htmlParser(): Parser {
            return Parser(HtmlTreeBuilder())
        }

        /**
         * Create a new XML parser. This parser assumes no knowledge of the incoming tags and does not treat it as HTML,
         * rather creates a simple tree directly from the input.
         * @return a new simple XML parser.
         */
        @JvmStatic
        fun xmlParser(): Parser {
            return Parser(XmlTreeBuilder()).setMaxDepth(Int.Companion.MAX_VALUE)
        }
    }
}
