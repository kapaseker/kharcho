package io.kapaseker.kharcho.helper

import io.kapaseker.kharcho.internal.Normalizer
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.nodes.*
import io.kapaseker.kharcho.parser.HtmlTreeBuilder
import io.kapaseker.kharcho.select.NodeVisitor
import io.kapaseker.kharcho.select.Selector
import org.w3c.dom.DOMException
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.StringWriter
import java.util.*
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathExpressionException
import javax.xml.xpath.XPathFactory
import javax.xml.xpath.XPathFactoryConfigurationException

/**
 * Helper class to transform a [io.kapaseker.kharcho.nodes.Document] to a [org.w3c.dom.Document][Document],
 * for integration with toolsets that use the W3C DOM.
 */
class W3CDom {
    protected var factory: DocumentBuilderFactory
    private var namespaceAware = true // false when using selectXpath, for user's query convenience

    init {
        factory = DocumentBuilderFactory.newInstance()
        factory.setNamespaceAware(true)
    }

    /**
     * Returns if this W3C DOM is namespace aware. By default, this will be `true`, but is disabled for simplicity
     * when using XPath selectors in [Element.selectXpath].
     * @return the current namespace aware setting.
     */
    fun namespaceAware(): Boolean {
        return namespaceAware
    }

    /**
     * Update the namespace aware setting. This impacts the factory that is used to create W3C nodes from jsoup nodes.
     *
     * For HTML documents, controls if the document will be in the default `http://www.w3.org/1999/xhtml`
     * namespace if otherwise unset..
     * @param namespaceAware the updated setting
     * @return this W3CDom, for chaining.
     */
    fun namespaceAware(namespaceAware: Boolean): W3CDom {
        this.namespaceAware = namespaceAware
        factory.setNamespaceAware(namespaceAware)
        return this
    }

    /**
     * Convert a jsoup Document to a W3C Document. The created nodes will link back to the original
     * jsoup nodes in the user property [.SourceProperty] (but after conversion, changes on one side will not
     * flow to the other).
     *
     * @param in jsoup doc
     * @return a W3C DOM Document representing the jsoup Document or Element contents.
     */
    fun fromJsoup(`in`: io.kapaseker.kharcho.nodes.Document?): Document {
        // just method API backcompat
        return fromJsoup(`in` as Element?)
    }

    /**
     * Convert a jsoup DOM to a W3C Document. The created nodes will link back to the original
     * jsoup nodes in the user property [.SourceProperty] (but after conversion, changes on one side will not
     * flow to the other). The input Element is used as a context node, but the whole surrounding jsoup Document is
     * converted. (If you just want a subtree converted, use [.convert].)
     *
     * @param in jsoup element or doc
     * @return a W3C DOM Document representing the jsoup Document or Element contents.
     * @see .sourceNodes
     * @see .contextNode
     */
    fun fromJsoup(`in`: Element?): Document {
        Validate.notNull(`in`)
        val builder: DocumentBuilder
        try {
            builder = factory.newDocumentBuilder()
            val impl = builder.getDOMImplementation()
            val out = builder.newDocument()
            val inDoc = `in`!!.ownerDocument()
            val doctype = if (inDoc != null) inDoc.documentType() else null
            if (doctype != null) {
                try {
                    val documentType = impl.createDocumentType(
                        doctype.name(),
                        doctype.publicId(),
                        doctype.systemId()
                    )
                    out.appendChild(documentType)
                } catch (ignored: DOMException) {
                    // invalid / empty doctype dropped
                }
            }
            out.setXmlStandalone(true)
            // if in is Document, use the root element, not the wrapping document, as the context:
            val context =
                if (`in` is io.kapaseker.kharcho.nodes.Document) `in`.firstElementChild() else `in`
            out.setUserData(ContextProperty, context, null)
            convert((if (inDoc != null) inDoc else `in`), out)
            return out
        } catch (e: ParserConfigurationException) {
            throw IllegalStateException(e)
        }
    }

    /**
     * Converts a jsoup document into the provided W3C Document. If required, you can set options on the output
     * document before converting.
     *
     * @param in jsoup doc
     * @param out w3c doc
     * @see fromJsoup
     */
    fun convert(`in`: io.kapaseker.kharcho.nodes.Document?, out: Document) {
        // just provides method API backcompat
        convert((`in` as io.kapaseker.kharcho.nodes.Element?)!!, out)
    }

    /**
     * Converts a jsoup element into the provided W3C Document. If required, you can set options on the output
     * document before converting.
     *
     * @param in jsoup element
     * @param out w3c doc
     * @see fromJsoup
     */
    fun convert(`in`: Element, out: Document) {
        val builder = W3CBuilder(out)
        builder.namespaceAware = namespaceAware
        val inDoc = `in`.ownerDocument()
        if (inDoc != null) {
            if (!StringUtil.isBlank(inDoc.location())) {
                out.setDocumentURI(inDoc.location())
            }
            builder.syntax = inDoc.outputSettings().syntax()
        }
        val rootEl =
            checkNotNull(if (`in` is io.kapaseker.kharcho.nodes.Document) `in`.firstElementChild() else `in`) // skip the #root node if a Document
        builder.traverse(rootEl)
    }

    /**
     * Evaluate an XPath query against the supplied document, and return the results.
     * @param xpath an XPath query
     * @param doc the document to evaluate against
     * @return the matches nodes
     */
    fun selectXpath(xpath: String?, doc: Document?): NodeList {
        return selectXpath(xpath, doc as Node?)
    }

    /**
     * Evaluate an XPath query against the supplied context node, and return the results.
     * @param xpath an XPath query
     * @param contextNode the context node to evaluate against
     * @return the matches nodes
     */
    fun selectXpath(xpath: String?, contextNode: Node?): NodeList {
        Validate.notEmptyParam(xpath, "xpath")
        Validate.notNullParam(contextNode, "contextNode")

        val nodeList: NodeList
        try {
            // if there is a configured XPath factory, use that instead of the Java base impl:
            val property = System.getProperty(XPathFactoryProperty)
            val xPathFactory =
                if (property != null) XPathFactory.newInstance("jsoup") else XPathFactory.newInstance()

            val expression = xPathFactory.newXPath().compile(xpath)
            nodeList = expression.evaluate(
                contextNode,
                XPathConstants.NODESET
            ) as NodeList // love the strong typing here /s
            Validate.notNull(nodeList)
        } catch (e: XPathExpressionException) {
            throw Selector.SelectorParseException(
                e, "Could not evaluate XPath query [%s]: %s", xpath, e.message
            )
        } catch (e: XPathFactoryConfigurationException) {
            throw Selector.SelectorParseException(
                e, "Could not evaluate XPath query [%s]: %s", xpath, e.message
            )
        }
        return nodeList
    }

    /**
     * Retrieves the original jsoup DOM nodes from a nodelist created by this convertor.
     * @param nodeList the W3C nodes to get the original jsoup nodes from
     * @param nodeType the jsoup node type to retrieve (e.g. Element, DataNode, etc)
     * @param <T> node type
     * @return a list of the original nodes
    </T> */
    fun <T : io.kapaseker.kharcho.nodes.Node?> sourceNodes(
        nodeList: NodeList?,
        nodeType: Class<T?>?
    ): MutableList<T?> {
        Validate.notNull(nodeList)
        Validate.notNull(nodeType)
        val nodes: MutableList<T?> = ArrayList<T?>(nodeList!!.getLength())

        for (i in 0..<nodeList.getLength()) {
            val node = nodeList.item(i)
            val source = node.getUserData(SourceProperty)
            if (nodeType!!.isInstance(source)) nodes.add(nodeType.cast(source))
        }

        return nodes
    }

    /**
     * For a Document created by [.fromJsoup], retrieves the W3C context node.
     * @param wDoc Document created by this class
     * @return the corresponding W3C Node to the jsoup Element that was used as the creating context.
     */
    fun contextNode(wDoc: Document): Node? {
        return wDoc.getUserData(ContextNodeProperty) as Node?
    }

    /**
     * Implements the conversion by walking the input.
     */
    protected class W3CBuilder(private val doc: Document) : NodeVisitor {
        var namespaceAware = true
        private var dest: Node
        var syntax: io.kapaseker.kharcho.nodes.Document.OutputSettings.Syntax? =
            io.kapaseker.kharcho.nodes.Document.OutputSettings.Syntax.xml // the syntax (to coerce attributes to). From the input doc if available.

        /*@Nullable*/
        private val contextElement: Element? // todo - unsure why this can't be marked nullable?

        override fun head(source: io.kapaseker.kharcho.nodes.Node, depth: Int) {
            if (source is Element) {
                val sourceEl = source
                val namespace = if (namespaceAware) sourceEl.tag().namespace() else null
                val tagName = Normalizer.xmlSafeTagName(sourceEl.tagName())
                try {
                    // use an empty namespace if none is present but the tag name has a prefix
                    val imputedNamespace =
                        if (namespace == null && tagName.orEmpty().contains(":")) "" else namespace
                    val el = doc.createElementNS(imputedNamespace, tagName)
                    copyAttributes(sourceEl, el)
                    append(el, sourceEl)
                    if (sourceEl === contextElement) doc.setUserData(ContextNodeProperty, el, null)
                    dest = el // descend
                } catch (e: DOMException) {
                    // If the Normalize didn't get it XML / W3C safe, inserts as plain text
                    append(doc.createTextNode("<" + tagName + ">"), sourceEl)
                }
            } else if (source is TextNode) {
                val sourceText = source
                val text = doc.createTextNode(sourceText.wholeText)
                append(text, sourceText)
            } else if (source is Comment) {
                val sourceComment = source
                val comment = doc.createComment(sourceComment.data)
                append(comment, sourceComment)
            } else if (source is DataNode) {
                val sourceData = source
                val node = doc.createTextNode(sourceData.wholeData)
                append(node, sourceData)
            } else {
                // unhandled. note that doctype is not handled here - rather it is used in the initial doc creation
            }
        }

        private fun append(append: Node, source: io.kapaseker.kharcho.nodes.Node?) {
            append.setUserData(SourceProperty, source, null)
            dest.appendChild(append)
        }

        override fun tail(source: io.kapaseker.kharcho.nodes.Node, depth: Int) {
            if (source is Element && dest.getParentNode() is org.w3c.dom.Element) {
                dest = dest.getParentNode() // undescend
            }
        }

        private fun copyAttributes(jEl: Element, wEl: org.w3c.dom.Element) {
            for (attribute in jEl.attributes()) {
                try {
                    setAttribute(jEl, wEl, attribute, syntax)
                } catch (e: DOMException) {
                    if (syntax != io.kapaseker.kharcho.nodes.Document.OutputSettings.Syntax.xml) setAttribute(
                        jEl,
                        wEl,
                        attribute,
                        io.kapaseker.kharcho.nodes.Document.OutputSettings.Syntax.xml
                    )
                }
            }
        }

        @Throws(DOMException::class)
        private fun setAttribute(
            jEl: Element,
            wEl: org.w3c.dom.Element,
            attribute: Attribute,
            syntax: io.kapaseker.kharcho.nodes.Document.OutputSettings.Syntax?
        ) {
            val key = Attribute.getValidKey(attribute.key, syntax)
            if (key != null) {
                val namespace = attribute.namespace()
                if (namespaceAware && !namespace.isEmpty()) wEl.setAttributeNS(
                    namespace,
                    key,
                    attribute.value
                )
                else wEl.setAttribute(key, attribute.value)
                maybeAddUndeclaredNs(namespace, key, jEl, wEl)
            }
        }

        /**
         * Add a namespace declaration for an attribute with a prefix if it is not already present. Ensures that attributes
         * with prefixes have the corresponding namespace declared, E.g. attribute "v-bind:foo" gets another attribute
         * "xmlns:v-bind='undefined'. So that the asString() transformation pass is valid.
         * If the parser was HTML we don't have a discovered namespace but we are trying to coerce it, so walk up the
         * element stack and find it.
         */
        private fun maybeAddUndeclaredNs(
            namespace: String,
            attrKey: String,
            jEl: Element,
            wEl: org.w3c.dom.Element
        ) {
            var namespace = namespace
            if (!namespaceAware || !namespace.isEmpty()) return
            val pos = attrKey.indexOf(':')
            if (pos != -1) { // prefixed but no namespace defined during parse, add a fake so that w3c serialization doesn't blow up
                val prefix = attrKey.substring(0, pos)
                if (prefix == "xmlns") return
                val doc = jEl.ownerDocument()
                if (doc != null && doc.parser().treeBuilder is HtmlTreeBuilder) {
                    // try walking up the stack and seeing if there is a namespace declared for this prefix (and that we didn't parse because HTML)
                    var el: Element? = jEl
                    while (el != null) {
                        val ns = el.attr("xmlns:" + prefix)
                        if (!ns.isEmpty()) {
                            namespace = ns
                            // found it, set it
                            wEl.setAttributeNS(namespace, attrKey, jEl.attr(attrKey))
                            return
                        }
                        el = el.parent()
                    }
                }

                // otherwise, put in a fake one
                wEl.setAttribute("xmlns:" + prefix, undefinedNs)
            }
        }

        init {
            dest = doc
            contextElement =
                doc.getUserData(ContextProperty) as Element? // Track the context jsoup Element, so we can save the corresponding w3c element
        }

        companion object {
            private const val undefinedNs = "undefined"
        }
    }

    companion object {
        /** For W3C Documents created by this class, this property is set on each node to link back to the original jsoup node.  */
        const val SourceProperty: String = "jsoupSource"
        private const val ContextProperty =
            "jsoupContextSource" // tracks the jsoup context element on w3c doc
        private const val ContextNodeProperty =
            "jsoupContextNode" // the w3c node used as the creating context

        /**
         * To get support for XPath versions &gt; 1, set this property to the classname of an alternate XPathFactory
         * implementation. (For e.g. `net.sf.saxon.xpath.XPathFactoryImpl`).
         */
        const val XPathFactoryProperty: String = "javax.xml.xpath.XPathFactory:jsoup"

        /**
         * Converts a jsoup DOM to a W3C DOM.
         *
         * @param in jsoup Document
         * @return W3C Document
         */
        fun convert(`in`: io.kapaseker.kharcho.nodes.Document?): Document? {
            return (W3CDom().fromJsoup(`in`))
        }

        /**
         * Serialize a W3C document to a String. Provide Properties to define output settings including if HTML or XML. If
         * you don't provide the properties (`null`), the output will be auto-detected based on the content of the
         * document.
         *
         * @param doc Document
         * @param properties (optional/nullable) the output properties to use. See [     ][Transformer.setOutputProperties] and [OutputKeys]
         * @return Document as string
         * @see .OutputHtml
         *
         * @see .OutputXml
         *
         * @see OutputKeys.ENCODING
         *
         * @see OutputKeys.OMIT_XML_DECLARATION
         *
         * @see OutputKeys.STANDALONE
         *
         * @see OutputKeys.DOCTYPE_PUBLIC
         *
         * @see OutputKeys.CDATA_SECTION_ELEMENTS
         *
         * @see OutputKeys.INDENT
         *
         * @see OutputKeys.MEDIA_TYPE
         */
        /**
         * Serialize a W3C document that was created by [.fromJsoup] to a String.
         * The output format will be XML or HTML depending on the content of the doc.
         *
         * @param doc Document
         * @return Document as string
         * @see W3CDom.asString
         */
        @JvmOverloads
        fun asString(
            doc: Document,
            properties: MutableMap<String?, String?>? = null
        ): String? {
            try {
                val domSource = DOMSource(doc)
                val writer = StringWriter()
                val result = StreamResult(writer)
                val tf = TransformerFactory.newInstance()
                val transformer = tf.newTransformer()
                if (properties != null) transformer.setOutputProperties(propertiesFromMap(properties))

                if (doc.getDoctype() != null) {
                    val doctype = doc.getDoctype()
                    if (!StringUtil.isBlank(doctype.getPublicId())) transformer.setOutputProperty(
                        OutputKeys.DOCTYPE_PUBLIC, doctype.getPublicId()
                    )
                    if (!StringUtil.isBlank(doctype.getSystemId())) transformer.setOutputProperty(
                        OutputKeys.DOCTYPE_SYSTEM, doctype.getSystemId()
                    )
                    else if (doctype.getName().equals("html", ignoreCase = true)
                        && StringUtil.isBlank(doctype.getPublicId())
                        && StringUtil.isBlank(doctype.getSystemId())
                    ) transformer.setOutputProperty(
                        OutputKeys.DOCTYPE_SYSTEM, "about:legacy-compat"
                    )
                }

                transformer.transform(domSource, result)
                return writer.toString()
            } catch (e: TransformerException) {
                throw IllegalStateException(e)
            }
        }

        fun propertiesFromMap(map: MutableMap<String?, String?>?): Properties {
            val props = Properties()
            props.putAll(map!!)
            return props
        }

        /** Canned default for HTML output.  */
        fun OutputHtml(): HashMap<String?, String?> {
            return methodMap("html")
        }

        /** Canned default for XML output.  */
        fun OutputXml(): HashMap<String?, String?> {
            return methodMap("xml")
        }

        private fun methodMap(method: String?): HashMap<String?, String?> {
            val map = HashMap<String?, String?>()
            map.put(OutputKeys.METHOD, method)
            return map
        }
    }
}
