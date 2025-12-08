package io.kapaseker.kharcho.parser

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.nodes.*
import java.io.Reader
import java.io.StringReader
import java.util.*

/**
 * Use the `XmlTreeBuilder` when you want to parse XML without any of the HTML DOM rules being applied to the
 * document.
 *
 * Usage example: `Document xmlDoc = Jsoup.parse(html, baseUrl, Parser.xmlParser());`
 *
 * @author Jonathan Hedley
 */
class XmlTreeBuilder : TreeBuilder() {
    private val namespacesStack =
        ArrayDeque<HashMap<String?, String?>?>() // stack of namespaces, prefix => urn

    override fun defaultSettings(): ParseSettings {
        return ParseSettings.Companion.preserveCase
    }

    protected override fun initialiseParse(input: Reader?, baseUri: String?, parser: Parser?) {
        super.initialiseParse(input, baseUri, parser)
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .prettyPrint(false) // as XML, we don't understand what whitespace is significant or not

        namespacesStack.clear()
        val ns = HashMap<String?, String?>()
        ns.put("xml", Parser.Companion.NamespaceXml)
        ns.put("", Parser.Companion.NamespaceXml)
        namespacesStack.push(ns)
    }

    override fun initialiseParseFragment(@Nullable context: Element?) {
        super.initialiseParseFragment(context)
        if (context == null) return

        // transition to the tag's text state if available
        val textState = context.tag().textState()
        if (textState != null) tokeniser.transition(textState)

        // reconstitute the namespace stack by traversing the element and its parents (top down)
        val chain = context.parents()
        chain.add(0, context)
        for (i in chain.indices.reversed()) {
            val el: Element = chain.get(i)!!
            val namespaces = HashMap<String?, String?>(namespacesStack.peek())
            namespacesStack.push(namespaces)
            if (el.attributesSize() > 0) {
                processNamespaces(el.attributes(), namespaces)
            }
        }
    }

    fun parse(input: Reader?, baseUri: String?): Document? {
        return parse(input, baseUri, Parser(this))
    }

    fun parse(input: String, baseUri: String?): Document? {
        return parse(StringReader(input), baseUri, Parser(this))
    }

    override fun completeParseFragment(): MutableList<Node?>? {
        return doc.childNodes()
    }

    override fun newInstance(): XmlTreeBuilder {
        return XmlTreeBuilder()
    }

    public override fun defaultNamespace(): String {
        return Parser.Companion.NamespaceXml
    }

    override fun defaultTagSet(): TagSet {
        return TagSet() // an empty tagset
    }

    override fun defaultMaxDepth(): Int {
        return Int.Companion.MAX_VALUE
    }

    protected override fun process(token: Token): Boolean {
        currentToken = token

        // start tag, end tag, doctype, xmldecl, comment, character, eof
        when (token.type) {
            Token.TokenType.StartTag -> insertElementFor(token.asStartTag())
            Token.TokenType.EndTag -> popStackToClose(token.asEndTag())
            Token.TokenType.Comment -> insertCommentFor(token.asComment())
            Token.TokenType.Character -> insertCharacterFor(token.asCharacter())
            Token.TokenType.Doctype -> insertDoctypeFor(token.asDoctype())
            Token.TokenType.XmlDecl -> insertXmlDeclarationFor(token.asXmlDecl())
            Token.TokenType.EOF -> {}
            else -> Validate.fail("Unexpected token type: " + token.type)
        }
        return true
    }

    fun insertElementFor(startTag: Token.StartTag) {
        // handle namespace for tag
        val namespaces = HashMap<String?, String?>(namespacesStack.peek())
        namespacesStack.push(namespaces)

        val attributes = startTag.attributes
        if (attributes != null) {
            settings.normalizeAttributes(attributes)
            attributes.deduplicate(settings)
            processNamespaces(attributes, namespaces)
            applyNamespacesToAttributes(attributes, namespaces)
        }

        enforceStackDepthLimit()

        val tagName = startTag.tagName.value()
        val ns: String? = resolveNamespace(tagName, namespaces)
        val tag = tagFor(tagName, startTag.normalName, ns, settings)
        val el = Element(tag, null, attributes)
        currentElement().appendChild(el)
        push(el)

        if (startTag.isSelfClosing()) {
            tag.setSeenSelfClose()
            pop() // push & pop ensures onNodeInserted & onNodeClosed
        } else if (tag.isEmpty()) {
            pop() // custom defined void tag
        } else {
            val textState = tag.textState()
            if (textState != null) tokeniser.transition(textState)
        }
    }

    fun insertLeafNode(node: LeafNode?) {
        currentElement().appendChild(node)
        onNodeInserted(node)
    }

    fun insertCommentFor(commentToken: Token.Comment) {
        val comment = Comment(commentToken.getData())
        insertLeafNode(comment)
    }

    fun insertCharacterFor(token: Token.Character) {
        val data = token.getData()
        val node: LeafNode?
        if (token.isCData()) node = CDataNode(data)
        else if (currentElement().tag().`is`(Tag.Companion.Data)) node = DataNode(data)
        else node = TextNode(data)
        insertLeafNode(node)
    }

    fun insertDoctypeFor(token: Token.Doctype) {
        val doctypeNode = DocumentType(
            settings.normalizeTag(token.getName()),
            token.getPublicIdentifier(),
            token.getSystemIdentifier()
        )
        doctypeNode.setPubSysKey(token.getPubSysKey())
        insertLeafNode(doctypeNode)
    }

    fun insertXmlDeclarationFor(token: XmlDecl) {
        val decl = XmlDeclaration(token.name(), token.isDeclaration)
        if (token.attributes != null) decl.attributes().addAll(token.attributes)
        insertLeafNode(decl)
    }

    override fun pop(): Element? {
        namespacesStack.pop()
        return super.pop()
    }

    /**
     * If the stack contains an element with this tag's name, pop up the stack to remove the first occurrence. If not
     * found, skips.
     *
     * @param endTag tag to close
     */
    protected fun popStackToClose(endTag: Token.EndTag) {
        // like in HtmlTreeBuilder - don't scan up forever for very (artificially) deeply nested stacks
        val elName = settings.normalizeTag(endTag.name())
        var firstFound: Element? = null

        val bottom = stack.size - 1
        val upper = if (bottom >= maxQueueDepth) bottom - maxQueueDepth else 0

        for (pos in stack.size - 1 downTo upper) {
            val next = stack.get(pos)
            if (next.nodeName() == elName) {
                firstFound = next
                break
            }
        }
        if (firstFound == null) return  // not found, skip


        for (pos in stack.indices.reversed()) {
            val next = pop()
            if (next === firstFound) {
                break
            }
        }
    }

    companion object {
        const val XmlnsKey: String = "xmlns"
        const val XmlnsPrefix: String = "xmlns:"
        private fun processNamespaces(
            attributes: Attributes,
            namespaces: HashMap<String?, String?>
        ) {
            // process attributes for namespaces (xmlns, xmlns:)
            for (attr in attributes) {
                val key = attr.key
                val value = attr.value
                if (key == XmlnsKey) {
                    namespaces.put("", value) // new default for this level
                } else if (key.startsWith(XmlnsPrefix)) {
                    val nsPrefix: String = key.substring(XmlnsPrefix.length)
                    namespaces.put(nsPrefix, value)
                }
            }
        }

        private fun applyNamespacesToAttributes(
            attributes: Attributes,
            namespaces: HashMap<String?, String?>
        ) {
            // second pass, apply namespace to attributes. Collects them first then adds (as userData is an attribute)
            val attrPrefix: MutableMap<String?, String?> = HashMap<String?, String?>()
            for (attr in attributes) {
                val prefix = attr.prefix()
                if (!prefix.isEmpty()) {
                    if (prefix == XmlnsKey) continue
                    val ns = namespaces.get(prefix)
                    if (ns != null) attrPrefix.put(SharedConstants.XmlnsAttr + prefix, ns)
                }
            }
            for (entry in attrPrefix.entries) attributes.userData(entry.key, entry.value)
        }

        private fun resolveNamespace(
            tagName: String,
            namespaces: HashMap<String?, String?>
        ): String? {
            var ns = namespaces.get("")
            val pos = tagName.indexOf(':')
            if (pos > 0) {
                val prefix = tagName.substring(0, pos)
                if (namespaces.containsKey(prefix)) ns = namespaces.get(prefix)
            }
            return ns
        }

        private const val maxQueueDepth =
            256 // an arbitrary tension point between real XML and crafted pain
    }
}
