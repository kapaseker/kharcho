package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.helper.Validate.notEmpty
import io.kapaseker.kharcho.helper.Validate.notNull
import io.kapaseker.kharcho.helper.W3CDom
import io.kapaseker.kharcho.parser.HtmlTreeBuilder
import io.kapaseker.kharcho.parser.Parser
import org.w3c.dom.NodeList
import java.util.*
import java.util.stream.Stream
import java.util.stream.StreamSupport

/**
 * Internal helpers for Nodes, to keep the actual node APIs relatively clean. A jsoup internal class, so don't use it as
 * there is no contract API.
 */
internal object NodeUtils {
    /**
     * Get the output setting for this node,  or if this node has no document (or parent), retrieve the default output
     * settings
     */
    fun outputSettings(node: Node): Document.OutputSettings {
        val owner = node.ownerDocument()
        return owner?.outputSettings() ?: (Document("")).outputSettings()
    }

    /**
     * Get the parser that was used to make this node, or the default HTML parser if it has no parent.
     */
    fun parser(node: Node): Parser {
        val doc = node.ownerDocument()
        return doc?.parser() ?: Parser(HtmlTreeBuilder())
    }

    /**
     * This impl works by compiling the input xpath expression, and then evaluating it against a W3C Document converted
     * from the original jsoup element. The original jsoup elements are then fetched from the w3c doc user data (where we
     * stashed them during conversion). This process could potentially be optimized by transpiling the compiled xpath
     * expression to a jsoup Evaluator when there's 1:1 support, thus saving the W3C document conversion stage.
     */
    fun <T : io.kapaseker.kharcho.nodes.Node?> selectXpath(
        xpath: String,
        el: Element,
        nodeType: Class<T?>
    ): MutableList<T?> {
        notEmpty(xpath)
        notNull(el)
        notNull(nodeType)

        val w3c = W3CDom().namespaceAware(false)
        val wDoc = w3c.fromJsoup(el)
        val contextNode = w3c.contextNode(wDoc)
        val nodeList = w3c.selectXpath(xpath, contextNode)
        return w3c.sourceNodes<T?>(nodeList, nodeType)
    }

    /** Creates a Stream, starting with the supplied node.  */
    fun <T : Node> stream(
        start: Node,
    ): Stream<T> {
        val iterator = NodeIterator<T>(start)
        val spliterator = spliterator(iterator)

        return StreamSupport.stream<T>(spliterator, false)
    }

    fun <T : Node> spliterator(iterator: MutableIterator<T?>): Spliterator<T?> {
        return Spliterators.spliteratorUnknownSize<T>(
            iterator,
            Spliterator.DISTINCT or Spliterator.NONNULL or Spliterator.ORDERED
        )
    }
}
