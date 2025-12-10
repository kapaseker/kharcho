package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.internal.QuietAppendable
import io.kapaseker.kharcho.parser.Tag

/**
 * Represents a [TextNode] as an [Element], to enable text nodes to be selected with
 * the [io.kapaseker.kharcho.select.Selector] `:matchText` syntax.
 */
@Deprecated("use {@link Element#selectNodes(String, Class)} instead, with selector of <code>::textnode</code> and class <code>TextNode</code>.")
class PseudoTextElement(tag: Tag?, baseUri: String?, attributes: Attributes?) :
    Element(tag, baseUri, attributes) {
    override fun outerHtmlHead(accum: QuietAppendable, out: Document.OutputSettings) {
    }

    override fun outerHtmlTail(accum: QuietAppendable, out: Document.OutputSettings) {
    }
}
