package io.kapaseker.kharcho.parser

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.nodes.*
import io.kapaseker.kharcho.select.NodeVisitor
import java.io.Reader

/**
 * @author Jonathan Hedley
 */
internal abstract class TreeBuilder {
    var parser: Parser? = null
    var reader: CharacterReader? = null
    var tokeniser: Tokeniser? = null
    var doc: Document? = null // current doc we are building into
    var stack: ArrayList<Element>? = null // the stack of open elements
    var baseUri: String? = null // current base uri, for creating new elements
    var currentToken: Token? =
        null // currentToken is used for error and source position tracking. Null at start of fragment parse
    var settings: ParseSettings? = null
    var tagSet: TagSet? = null // the tags we're using in this parse

    var nodeListener: NodeVisitor? = null // optional listener for node add / removes

    private var start: Token.StartTag? = null // start tag to process
    private val end = Token.EndTag(this)
    abstract fun defaultSettings(): ParseSettings?

    var trackSourceRange: Boolean =
        false // optionally tracks the source range of nodes and attributes

    open fun initialiseParse(input: Reader, baseUri: String, parser: Parser) {
        Validate.notNullParam(input, "input")
        Validate.notNullParam(baseUri, "baseUri")
        Validate.notNull(parser)

        doc = Document(parser.defaultNamespace(), baseUri)
        doc!!.parser(parser)
        this.parser = parser
        settings = parser.settings()
        reader = CharacterReader(input)
        trackSourceRange = parser.isTrackPosition()
        reader!!.trackNewlines(parser.isTrackErrors() || trackSourceRange) // when tracking errors or source ranges, enable newline tracking for better legibility
        if (parser.isTrackErrors()) parser.getErrors().clear()
        tokeniser = Tokeniser(this)
        stack = ArrayList<Element>(32)
        tagSet = parser.tagSet()
        start = Token.StartTag(this)
        currentToken = start // init current token to the virtual start token.
        this.baseUri = baseUri
        onNodeInserted(doc!!)
    }

    fun completeParse() {
        // tidy up - as the Parser and Treebuilder are retained in document for settings / fragments
        if (reader == null) return
        reader!!.close()
        reader = null
        tokeniser = null
        stack = null
    }

    fun parse(input: Reader, baseUri: String, parser: Parser): Document {
        initialiseParse(input, baseUri, parser)
        runParser()
        return doc!!
    }

    fun parseFragment(
        inputFragment: Reader,
        context: Element?,
        baseUri: String,
        parser: Parser
    ): MutableList<Node> {
        initialiseParse(inputFragment, baseUri, parser)
        initialiseParseFragment(context)
        runParser()
        return completeParseFragment()
    }

    open fun initialiseParseFragment(context: Element?) {
        // in Html, sets up context; no-op in XML
    }

    abstract fun completeParseFragment(): MutableList<Node>

    /** Set the node listener, which will then get callbacks for node insert and removals.  */
    fun nodeListener(nodeListener: NodeVisitor?) {
        this.nodeListener = nodeListener
    }

    /**
     * Create a new copy of this TreeBuilder
     * @return copy, ready for a new parse
     */
    abstract fun newInstance(): TreeBuilder?

    fun runParser() {
        do {
        } while (stepParser()) // run until stepParser sees EOF
        completeParse()
    }

    fun stepParser(): Boolean {
        // if we have reached the end already, step by popping off the stack, to hit nodeRemoved callbacks:
        if (currentToken!!.type == Token.TokenType.EOF) {
            if (stack == null) {
                return false
            }
            if (stack!!.isEmpty()) {
                onNodeClosed(doc!!) // the root doc is not on the stack, so let this final step close it
                stack = null
                return true
            }
            pop()
            return true
        }
        val token = tokeniser!!.read()
        currentToken = token
        process(token)
        token.reset()
        return true
    }

    abstract fun process(token: Token?): Boolean

    fun processStartTag(name: String?): Boolean {
        // these are "virtual" start tags (auto-created by the treebuilder), so not tracking the start position
        val start = this.start!!
        if (currentToken === start) { // don't recycle an in-use token
            return process(Token.StartTag(this).name(name))
        }
        return process(start.reset().name(name))
    }

    fun processStartTag(name: String?, attrs: Attributes?): Boolean {
        val start = this.start!!
        if (currentToken === start) { // don't recycle an in-use token
            return process(Token.StartTag(this).nameAttr(name, attrs))
        }
        start.reset()
        start.nameAttr(name, attrs)
        return process(start)
    }

    fun processEndTag(name: String?): Boolean {
        if (currentToken === end) { // don't recycle an in-use token
            return process(Token.EndTag(this).name(name))
        }
        return process(end.reset().name(name))
    }

    /**
     * Removes the last Element from the stack, hits onNodeClosed, and then returns it.
     * @return
     */
    open fun pop(): Element {
        val size = stack!!.size
        val removed = stack!!.removeAt(size - 1)
        onNodeClosed(removed)
        return removed
    }

    /**
     * Adds the specified Element to the end of the stack, and hits onNodeInserted.
     * @param element
     */
    fun push(element: Element) {
        stack!!.add(element)
        onNodeInserted(element)
    }

    /**
     * Ensures the stack respects [Parser.getMaxDepth] by closing the deepest open elements until there is room for
     * a new insertion.
     */
    fun enforceStackDepthLimit() {
        val maxDepth = parser!!.getMaxDepth()
        if (maxDepth == Int.Companion.MAX_VALUE) return
        while (stack!!.size >= maxDepth) {
            val trimmed = pop()
            onStackPrunedForDepth(trimmed)
        }
    }

    /**
     * Hook for the HTML Tree Builder that needs to clean up when an element is removed due to the depth limit
     */
    open fun onStackPrunedForDepth(element: Element?) {
        // default no-op
    }

    /**
     * Default maximum depth for parsers using this tree builder.
     */
    open fun defaultMaxDepth(): Int {
        return 512
    }

    /**
     * Get the current element (last on the stack). If all items have been removed, returns the document instead
     * (which might not actually be on the stack; use stack.size() == 0 to test if required.
     * @return the last element on the stack, if any; or the root document
     */
    fun currentElement(): Element? {
        val size = stack!!.size
        return if (size > 0) stack!!.get(size - 1) else doc
    }

    /**
     * Checks if the Current Element's normal name equals the supplied name, in the HTML namespace.
     * @param normalName name to check
     * @return true if there is a current element on the stack, and its name equals the supplied
     */
    fun currentElementIs(normalName: String?): Boolean {
        if (stack!!.size == 0) return false
        val current = currentElement()
        return current != null && current.normalName() == normalName
                && current.tag().namespace() == Parser.Companion.NamespaceHtml
    }

    /**
     * Checks if the Current Element's normal name equals the supplied name, in the specified namespace.
     * @param normalName name to check
     * @param namespace the namespace
     * @return true if there is a current element on the stack, and its name equals the supplied
     */
    fun currentElementIs(normalName: String?, namespace: String?): Boolean {
        if (stack!!.size == 0) return false
        val current = currentElement()
        return current != null && current.normalName() == normalName
                && current.tag().namespace() == namespace
    }

    /**
     * If the parser is tracking errors, add an error at the current position.
     * @param msg error message
     */
    fun error(msg: String?) {
        error(msg, *(null as kotlin.Array<kotlin.Any?>?)!!)
    }

    /**
     * If the parser is tracking errors, add an error at the current position.
     * @param msg error message template
     * @param args template arguments
     */
    fun error(msg: String?, vararg args: Any?) {
        val errors = parser!!.getErrors()
        if (errors.canAddError()) errors.add(ParseError(reader, msg, *args))
    }

    fun tagFor(
        tagName: String?,
        normalName: String?,
        namespace: String?,
        settings: ParseSettings
    ): Tag? {
        return tagSet!!.valueOf(tagName, normalName, namespace, settings.preserveTagCase())
    }

    fun tagFor(token: Token.Tag): Tag? {
        return tagSet!!.valueOf(
            token.name(),
            token.normalName,
            defaultNamespace(),
            settings!!.preserveTagCase()
        )
    }

    /**
     * Gets the default namespace for this TreeBuilder
     * @return the default namespace
     */
    open fun defaultNamespace(): String? {
        return Parser.Companion.NamespaceHtml
    }

    open fun defaultTagSet(): TagSet? {
        return TagSet.Companion.Html()
    }

    /**
     * Called by implementing TreeBuilders when a node has been inserted. This implementation includes optionally tracking
     * the source range of the node.  @param node the node that was just inserted
     */
    fun onNodeInserted(node: Node) {
        trackNodePosition(node, true)

        if (nodeListener != null) nodeListener!!.head(node, stack!!.size)
    }

    /**
     * Called by implementing TreeBuilders when a node is explicitly closed. This implementation includes optionally
     * tracking the closing source range of the node.  @param node the node being closed
     */
    fun onNodeClosed(node: Node) {
        trackNodePosition(node, false)

        if (nodeListener != null) nodeListener!!.tail(node, stack!!.size)
    }

    fun trackNodePosition(node: Node, isStart: Boolean) {
        if (!trackSourceRange) return

        val token = currentToken!!
        var startPos = token.startPos()
        var endPos = token.endPos()

        // handle implicit element open / closes.
        if (node is Element) {
            val el = node
            if (token.isEOF()) {
                if (el.endSourceRange()
                        .isTracked()
                ) return  // /body and /html are left on stack until EOF, don't reset them

                endPos = reader!!.pos()
                startPos = endPos
            } else if (isStart) { // opening tag
                if (!token.isStartTag() || el.normalName() != token.asStartTag().normalName) {
                    endPos = startPos
                }
            } else { // closing tag
                if (!el.tag().isEmpty() && !el.tag().isSelfClosing()) {
                    if (!token.isEndTag() || el.normalName() != token.asEndTag().normalName) {
                        endPos = startPos
                    }
                }
            }
        }

        val startPosition =
            Range.Position(startPos, reader!!.lineNumber(startPos), reader!!.columnNumber(startPos))
        val endPosition =
            Range.Position(endPos, reader!!.lineNumber(endPos), reader!!.columnNumber(endPos))
        val range = Range(startPosition, endPosition)
        node.attributes()
            .userData(if (isStart) SharedConstants.RangeKey else SharedConstants.EndRangeKey, range)
    }
}
