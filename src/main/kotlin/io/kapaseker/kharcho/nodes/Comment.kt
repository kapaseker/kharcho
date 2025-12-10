package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.internal.QuietAppendable
import io.kapaseker.kharcho.parser.Parser

/**
 * A comment node.
 *
 * @author Jonathan Hedley, jonathan@hedley.net
 */

/**
 * Create a new comment node.
 * @param data The contents of the comment
 */
class Comment(data: String) : LeafNode(data) {
    override fun nodeName(): String {
        return "#comment"
    }

    val data: String
        /**
         * Get the contents of the comment.
         * @return comment content
         */
        get() = coreValue()

    fun setData(data: String?): Comment {
        coreValue(data)
        return this
    }

    override fun outerHtmlHead(accum: QuietAppendable, out: Document.OutputSettings) {
        accum.append("<!--").append(this.data).append("-->")
    }

    override fun clone(): Comment {
        return super.clone() as Comment
    }

    val isXmlDeclaration: Boolean
        /**
         * Check if this comment looks like an XML Declaration. This is the case when the HTML parser sees an XML
         * declaration or processing instruction. Other than doctypes, those aren't part of HTML, and will be parsed as a
         * bogus comment.
         * @return true if it looks like, maybe, it's an XML Declaration.
         * @see .asXmlDeclaration
         */
        get() {
            val data = this.data
            return isXmlDeclarationData(data)
        }

    /**
     * Attempt to cast this comment to an XML Declaration node.
     * @return an XML declaration if it could be parsed as one, null otherwise.
     * @see .isXmlDeclaration
     */
    fun asXmlDeclaration(): XmlDeclaration? {
        val fragment = "<" + this.data + ">"
        val parser = Parser.xmlParser()
        val nodes = parser.parseFragmentInput(fragment, null, "")
        if (!nodes!!.isEmpty() && nodes[0] is XmlDeclaration) return nodes[0] as XmlDeclaration?
        return null
    }

    companion object {
        private fun isXmlDeclarationData(data: String): Boolean {
            return (data.length > 1 && (data.startsWith("!") || data.startsWith("?")))
        }
    }
}
