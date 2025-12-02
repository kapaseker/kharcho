package io.kapaseker.kharcho.helper;

import org.w3c.dom.Comment;
import org.w3c.dom.DOMException;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathFactoryConfigurationException;

import io.kapaseker.kharcho.annotations.Nullable;
import io.kapaseker.kharcho.internal.Normalizer;
import io.kapaseker.kharcho.internal.StringUtil;
import io.kapaseker.kharcho.nodes.Attribute;
import io.kapaseker.kharcho.parser.HtmlTreeBuilder;
import io.kapaseker.kharcho.select.NodeVisitor;
import io.kapaseker.kharcho.select.Selector;

import static io.kapaseker.kharcho.nodes.Document.OutputSettings.Syntax;
import static javax.xml.transform.OutputKeys.METHOD;

/**
 * Helper class to transform a {@link io.kapaseker.kharcho.nodes.Document} to a {@link Document org.w3c.dom.Document},
 * for integration with toolsets that use the W3C DOM.
 */
public class W3CDom {
    /** For W3C Documents created by this class, this property is set on each node to link back to the original jsoup node. */
    public static final String SourceProperty = "jsoupSource";
    private static final String ContextProperty = "jsoupContextSource"; // tracks the jsoup context element on w3c doc
    private static final String ContextNodeProperty = "jsoupContextNode"; // the w3c node used as the creating context

    /**
     To get support for XPath versions &gt; 1, set this property to the classname of an alternate XPathFactory
     implementation. (For e.g. {@code net.sf.saxon.xpath.XPathFactoryImpl}).
     */
    public static final String XPathFactoryProperty = "javax.xml.xpath.XPathFactory:jsoup";

    protected DocumentBuilderFactory factory;
    private boolean namespaceAware = true; // false when using selectXpath, for user's query convenience

    public W3CDom() {
        factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
    }

    /**
     Returns if this W3C DOM is namespace aware. By default, this will be {@code true}, but is disabled for simplicity
     when using XPath selectors in {@link io.kapaseker.kharcho.nodes.Element#selectXpath(String)}.
     @return the current namespace aware setting.
     */
    public boolean namespaceAware() {
        return namespaceAware;
    }

    /**
     Update the namespace aware setting. This impacts the factory that is used to create W3C nodes from jsoup nodes.
     <p>For HTML documents, controls if the document will be in the default {@code http://www.w3.org/1999/xhtml}
     namespace if otherwise unset.</p>.
     @param namespaceAware the updated setting
     @return this W3CDom, for chaining.
     */
    public W3CDom namespaceAware(boolean namespaceAware) {
        this.namespaceAware = namespaceAware;
        factory.setNamespaceAware(namespaceAware);
        return this;
    }

    /**
     * Converts a jsoup DOM to a W3C DOM.
     *
     * @param in jsoup Document
     * @return W3C Document
     */
    public static Document convert(io.kapaseker.kharcho.nodes.Document in) {
        return (new W3CDom().fromJsoup(in));
    }

    /**
     * Serialize a W3C document to a String. Provide Properties to define output settings including if HTML or XML. If
     * you don't provide the properties ({@code null}), the output will be auto-detected based on the content of the
     * document.
     *
     * @param doc Document
     * @param properties (optional/nullable) the output properties to use. See {@link
     *     Transformer#setOutputProperties(Properties)} and {@link OutputKeys}
     * @return Document as string
     * @see #OutputHtml
     * @see #OutputXml
     * @see OutputKeys#ENCODING
     * @see OutputKeys#OMIT_XML_DECLARATION
     * @see OutputKeys#STANDALONE
     * @see OutputKeys#DOCTYPE_PUBLIC
     * @see OutputKeys#CDATA_SECTION_ELEMENTS
     * @see OutputKeys#INDENT
     * @see OutputKeys#MEDIA_TYPE
     */
    public static String asString(Document doc, @Nullable Map<String, String> properties) {
        try {
            DOMSource domSource = new DOMSource(doc);
            StringWriter writer = new StringWriter();
            StreamResult result = new StreamResult(writer);
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            if (properties != null)
                transformer.setOutputProperties(propertiesFromMap(properties));

            if (doc.getDoctype() != null) {
                DocumentType doctype = doc.getDoctype();
                if (!StringUtil.isBlank(doctype.getPublicId()))
                    transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, doctype.getPublicId());
                if (!StringUtil.isBlank(doctype.getSystemId()))
                    transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, doctype.getSystemId());
                    // handle <!doctype html> for legacy dom.
                else if (doctype.getName().equalsIgnoreCase("html")
                    && StringUtil.isBlank(doctype.getPublicId())
                    && StringUtil.isBlank(doctype.getSystemId()))
                    transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, "about:legacy-compat");
            }

            transformer.transform(domSource, result);
            return writer.toString();

        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
    }

    static Properties propertiesFromMap(Map<String, String> map) {
        Properties props = new Properties();
        props.putAll(map);
        return props;
    }

    /** Canned default for HTML output. */
    public static HashMap<String, String> OutputHtml() {
        return methodMap("html");
    }

    /** Canned default for XML output. */
    public static HashMap<String, String> OutputXml() {
        return methodMap("xml");
    }

    private static HashMap<String, String> methodMap(String method) {
        HashMap<String, String> map = new HashMap<>();
        map.put(METHOD, method);
        return map;
    }

    /**
     * Convert a jsoup Document to a W3C Document. The created nodes will link back to the original
     * jsoup nodes in the user property {@link #SourceProperty} (but after conversion, changes on one side will not
     * flow to the other).
     *
     * @param in jsoup doc
     * @return a W3C DOM Document representing the jsoup Document or Element contents.
     */
    public Document fromJsoup(io.kapaseker.kharcho.nodes.Document in) {
        // just method API backcompat
        return fromJsoup((io.kapaseker.kharcho.nodes.Element) in);
    }

    /**
     * Convert a jsoup DOM to a W3C Document. The created nodes will link back to the original
     * jsoup nodes in the user property {@link #SourceProperty} (but after conversion, changes on one side will not
     * flow to the other). The input Element is used as a context node, but the whole surrounding jsoup Document is
     * converted. (If you just want a subtree converted, use {@link #convert(io.kapaseker.kharcho.nodes.Element, Document)}.)
     *
     * @param in jsoup element or doc
     * @return a W3C DOM Document representing the jsoup Document or Element contents.
     * @see #sourceNodes(NodeList, Class)
     * @see #contextNode(Document)
     */
    public Document fromJsoup(io.kapaseker.kharcho.nodes.Element in) {
        Validate.notNull(in);
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
            DOMImplementation impl = builder.getDOMImplementation();
            Document out = builder.newDocument();
            io.kapaseker.kharcho.nodes.Document inDoc = in.ownerDocument();
            io.kapaseker.kharcho.nodes.DocumentType doctype = inDoc != null ? inDoc.documentType() : null;
            if (doctype != null) {
                try {
                    DocumentType documentType = impl.createDocumentType(doctype.name(), doctype.publicId(), doctype.systemId());
                    out.appendChild(documentType);
                } catch (DOMException ignored) {
                    // invalid / empty doctype dropped
                }
            }
            out.setXmlStandalone(true);
            // if in is Document, use the root element, not the wrapping document, as the context:
            io.kapaseker.kharcho.nodes.Element context = (in instanceof io.kapaseker.kharcho.nodes.Document) ? in.firstElementChild() : in;
            out.setUserData(ContextProperty, context, null);
            convert(inDoc != null ? inDoc : in, out);
            return out;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Converts a jsoup document into the provided W3C Document. If required, you can set options on the output
     * document before converting.
     *
     * @param in jsoup doc
     * @param out w3c doc
     * @see io.kapaseker.kharcho.helper.W3CDom#fromJsoup(io.kapaseker.kharcho.nodes.Element)
     */
    public void convert(io.kapaseker.kharcho.nodes.Document in, Document out) {
        // just provides method API backcompat
        convert((io.kapaseker.kharcho.nodes.Element) in, out);
    }

    /**
     * Converts a jsoup element into the provided W3C Document. If required, you can set options on the output
     * document before converting.
     *
     * @param in jsoup element
     * @param out w3c doc
     * @see io.kapaseker.kharcho.helper.W3CDom#fromJsoup(io.kapaseker.kharcho.nodes.Element)
     */
    public void convert(io.kapaseker.kharcho.nodes.Element in, Document out) {
        W3CBuilder builder = new W3CBuilder(out);
        builder.namespaceAware = namespaceAware;
        io.kapaseker.kharcho.nodes.Document inDoc = in.ownerDocument();
        if (inDoc != null) {
            if (!StringUtil.isBlank(inDoc.location())) {
                out.setDocumentURI(inDoc.location());
            }
            builder.syntax = inDoc.outputSettings().syntax();
        }
        io.kapaseker.kharcho.nodes.Element rootEl = in instanceof io.kapaseker.kharcho.nodes.Document ? in.firstElementChild() : in; // skip the #root node if a Document
        assert rootEl != null;
        builder.traverse(rootEl);
    }

    /**
     Evaluate an XPath query against the supplied document, and return the results.
     @param xpath an XPath query
     @param doc the document to evaluate against
     @return the matches nodes
     */
    public NodeList selectXpath(String xpath, Document doc) {
        return selectXpath(xpath, (Node) doc);
    }

    /**
     Evaluate an XPath query against the supplied context node, and return the results.
     @param xpath an XPath query
     @param contextNode the context node to evaluate against
     @return the matches nodes
     */
    public NodeList selectXpath(String xpath, Node contextNode) {
        Validate.notEmptyParam(xpath, "xpath");
        Validate.notNullParam(contextNode, "contextNode");

        NodeList nodeList;
        try {
            // if there is a configured XPath factory, use that instead of the Java base impl:
            String property = System.getProperty(XPathFactoryProperty);
            final XPathFactory xPathFactory = property != null ?
                XPathFactory.newInstance("jsoup") :
                XPathFactory.newInstance();

            XPathExpression expression = xPathFactory.newXPath().compile(xpath);
            nodeList = (NodeList) expression.evaluate(contextNode, XPathConstants.NODESET); // love the strong typing here /s
            Validate.notNull(nodeList);
        } catch (XPathExpressionException | XPathFactoryConfigurationException e) {
            throw new Selector.SelectorParseException(
                e, "Could not evaluate XPath query [%s]: %s", xpath, e.getMessage());
        }
        return nodeList;
    }

    /**
     Retrieves the original jsoup DOM nodes from a nodelist created by this convertor.
     @param nodeList the W3C nodes to get the original jsoup nodes from
     @param nodeType the jsoup node type to retrieve (e.g. Element, DataNode, etc)
     @param <T> node type
     @return a list of the original nodes
     */
    public <T extends io.kapaseker.kharcho.nodes.Node> List<T> sourceNodes(NodeList nodeList, Class<T> nodeType) {
        Validate.notNull(nodeList);
        Validate.notNull(nodeType);
        List<T> nodes = new ArrayList<>(nodeList.getLength());

        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            Object source = node.getUserData(W3CDom.SourceProperty);
            if (nodeType.isInstance(source))
                nodes.add(nodeType.cast(source));
        }

        return nodes;
    }

    /**
     For a Document created by {@link #fromJsoup(io.kapaseker.kharcho.nodes.Element)}, retrieves the W3C context node.
     @param wDoc Document created by this class
     @return the corresponding W3C Node to the jsoup Element that was used as the creating context.
     */
    public Node contextNode(Document wDoc) {
        return (Node) wDoc.getUserData(ContextNodeProperty);
    }

    /**
     * Serialize a W3C document that was created by {@link #fromJsoup(io.kapaseker.kharcho.nodes.Element)} to a String.
     * The output format will be XML or HTML depending on the content of the doc.
     *
     * @param doc Document
     * @return Document as string
     * @see W3CDom#asString(Document, Map)
     */
    public String asString(Document doc) {
        return asString(doc, null);
    }

    /**
     * Implements the conversion by walking the input.
     */
    protected static class W3CBuilder implements NodeVisitor {
        private final Document doc;
        private boolean namespaceAware = true;
        private Node dest;
        private Syntax syntax = Syntax.xml; // the syntax (to coerce attributes to). From the input doc if available.
        /*@Nullable*/ private final io.kapaseker.kharcho.nodes.Element contextElement; // todo - unsure why this can't be marked nullable?

        public W3CBuilder(Document doc) {
            this.doc = doc;
            dest = doc;
            contextElement = (io.kapaseker.kharcho.nodes.Element) doc.getUserData(ContextProperty); // Track the context jsoup Element, so we can save the corresponding w3c element
        }

        @Override
        public void head(io.kapaseker.kharcho.nodes.Node source, int depth) {
            if (source instanceof io.kapaseker.kharcho.nodes.Element) {
                io.kapaseker.kharcho.nodes.Element sourceEl = (io.kapaseker.kharcho.nodes.Element) source;
                String namespace = namespaceAware ? sourceEl.tag().namespace() : null;
                String tagName = Normalizer.xmlSafeTagName(sourceEl.tagName());
                try {
                    // use an empty namespace if none is present but the tag name has a prefix
                    String imputedNamespace = namespace == null && tagName.contains(":") ? "" : namespace;
                    Element el = doc.createElementNS(imputedNamespace, tagName);
                    copyAttributes(sourceEl, el);
                    append(el, sourceEl);
                    if (sourceEl == contextElement)
                        doc.setUserData(ContextNodeProperty, el, null);
                    dest = el; // descend
                } catch (DOMException e) {
                    // If the Normalize didn't get it XML / W3C safe, inserts as plain text
                    append(doc.createTextNode("<" + tagName + ">"), sourceEl);
                }
            } else if (source instanceof io.kapaseker.kharcho.nodes.TextNode) {
                io.kapaseker.kharcho.nodes.TextNode sourceText = (io.kapaseker.kharcho.nodes.TextNode) source;
                Text text = doc.createTextNode(sourceText.getWholeText());
                append(text, sourceText);
            } else if (source instanceof io.kapaseker.kharcho.nodes.Comment) {
                io.kapaseker.kharcho.nodes.Comment sourceComment = (io.kapaseker.kharcho.nodes.Comment) source;
                Comment comment = doc.createComment(sourceComment.getData());
                append(comment, sourceComment);
            } else if (source instanceof io.kapaseker.kharcho.nodes.DataNode) {
                io.kapaseker.kharcho.nodes.DataNode sourceData = (io.kapaseker.kharcho.nodes.DataNode) source;
                Text node = doc.createTextNode(sourceData.getWholeData());
                append(node, sourceData);
            } else {
                // unhandled. note that doctype is not handled here - rather it is used in the initial doc creation
            }
        }

        private void append(Node append, io.kapaseker.kharcho.nodes.Node source) {
            append.setUserData(SourceProperty, source, null);
            dest.appendChild(append);
        }

        @Override
        public void tail(io.kapaseker.kharcho.nodes.Node source, int depth) {
            if (source instanceof io.kapaseker.kharcho.nodes.Element && dest.getParentNode() instanceof Element) {
                dest = dest.getParentNode(); // undescend
            }
        }

        private void copyAttributes(io.kapaseker.kharcho.nodes.Element jEl, Element wEl) {
            for (Attribute attribute : jEl.attributes()) {
                try {
                    setAttribute(jEl, wEl, attribute, syntax);
                } catch (DOMException e) {
                    if (syntax != Syntax.xml)
                        setAttribute(jEl, wEl, attribute, Syntax.xml);
                }
            }
        }

        private void setAttribute(io.kapaseker.kharcho.nodes.Element jEl, Element wEl, Attribute attribute, Syntax syntax) throws DOMException {
            String key = Attribute.getValidKey(attribute.getKey(), syntax);
            if (key != null) {
                String namespace = attribute.namespace();
                if (namespaceAware && !namespace.isEmpty())
                    wEl.setAttributeNS(namespace, key, attribute.getValue());
                else
                    wEl.setAttribute(key, attribute.getValue());
                maybeAddUndeclaredNs(namespace, key, jEl, wEl);
            }
        }

        /**
         Add a namespace declaration for an attribute with a prefix if it is not already present. Ensures that attributes
         with prefixes have the corresponding namespace declared, E.g. attribute "v-bind:foo" gets another attribute
         "xmlns:v-bind='undefined'. So that the asString() transformation pass is valid.
         If the parser was HTML we don't have a discovered namespace but we are trying to coerce it, so walk up the
         element stack and find it.
         */
        private void maybeAddUndeclaredNs(String namespace, String attrKey, io.kapaseker.kharcho.nodes.Element jEl, Element wEl) {
            if (!namespaceAware || !namespace.isEmpty()) return;
            int pos = attrKey.indexOf(':');
            if (pos != -1) { // prefixed but no namespace defined during parse, add a fake so that w3c serialization doesn't blow up
                String prefix = attrKey.substring(0, pos);
                if (prefix.equals("xmlns")) return;
                io.kapaseker.kharcho.nodes.Document doc = jEl.ownerDocument();
                if (doc != null && doc.parser().getTreeBuilder() instanceof HtmlTreeBuilder) {
                    // try walking up the stack and seeing if there is a namespace declared for this prefix (and that we didn't parse because HTML)
                    for (io.kapaseker.kharcho.nodes.Element el = jEl; el != null; el = el.parent()) {
                        String ns = el.attr("xmlns:" + prefix);
                        if (!ns.isEmpty()) {
                            namespace = ns;
                            // found it, set it
                            wEl.setAttributeNS(namespace, attrKey, jEl.attr(attrKey));
                            return;
                        }
                    }
                }

                // otherwise, put in a fake one
                wEl.setAttribute("xmlns:" + prefix, undefinedNs);
            }
        }
        private static final String undefinedNs = "undefined";
    }

}
