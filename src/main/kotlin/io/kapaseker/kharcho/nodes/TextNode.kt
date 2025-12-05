package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.helper.Validate.isTrue
import io.kapaseker.kharcho.internal.QuietAppendable
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.internal.StringUtil.isBlank

/**
 * A text node.
 *
 * @author Jonathan Hedley, jonathan@hedley.net
 */
open class TextNode
/**
 * Create a new TextNode representing the supplied (unencoded) text).
 *
 * @param text raw text
 * @see .createFromEncoded
 */
    (text: String?) : LeafNode(text) {
    override fun nodeName(): String {
        return "#text"
    }

    /**
     * Get the text content of this text node.
     * @return Unencoded, normalised text.
     * @see TextNode.getWholeText
     */
    open fun text(): String? {
        return StringUtil.normaliseWhitespace(this.wholeText)
    }

    /**
     * Set the text content of this text node.
     * @param text unencoded text
     * @return this, for chaining
     */
    fun text(text: String?): TextNode {
        coreValue(text)
        return this
    }

    val wholeText: String
        /**
         * Get the (unencoded) text of this text node, including any newlines and spaces present in the original.
         * @return text
         */
        get() = coreValue()

    val isBlank: Boolean
        /**
         * Test if this text node is blank -- that is, empty or only whitespace (including newlines).
         * @return true if this document is empty or only whitespace, false if it contains any text content.
         */
        get() = isBlank(coreValue())

    /**
     * Split this text node into two nodes at the specified string offset. After splitting, this node will contain the
     * original text up to the offset, and will have a new text node sibling containing the text after the offset.
     * @param offset string offset point to split node at.
     * @return the newly created text node containing the text after the offset.
     */
    fun splitText(offset: Int): TextNode {
        val text = coreValue()
        isTrue(offset >= 0, "Split offset must be not be negative")
        isTrue(offset < text.length, "Split offset must not be greater than current text length")

        val head = text.substring(0, offset)
        val tail = text.substring(offset)
        text(head)
        val tailNode = TextNode(tail)
        if (parentNode != null) parentNode.addChildren(siblingIndex() + 1, tailNode)

        return tailNode
    }

    override fun outerHtmlHead(accum: QuietAppendable?, out: Document.OutputSettings) {
        Entities.escape(accum, coreValue(), out, Entities.ForText)
    }

    override fun toString(): String {
        return outerHtml()
    }

    override fun clone(): TextNode? {
        return super.clone() as TextNode?
    }

    companion object {
        /**
         * Create a new TextNode from HTML encoded (aka escaped) data.
         * @param encodedText Text containing encoded HTML (e.g. `&lt;`)
         * @return TextNode containing unencoded data (e.g. `<`)
         */
        fun createFromEncoded(encodedText: String?): TextNode {
            val text = Entities.unescape(encodedText)
            return TextNode(text)
        }

        fun normaliseWhitespace(text: String): String {
            var text = text
            text = StringUtil.normaliseWhitespace(text)
            return text
        }

        fun stripLeadingWhitespace(text: String): String {
            return text.replaceFirst("^\\s+".toRegex(), "")
        }

        @JvmStatic
        fun lastCharIsWhitespace(sb: StringBuilder): Boolean {
            return sb.length != 0 && sb.get(sb.length - 1) == ' '
        }
    }
}
