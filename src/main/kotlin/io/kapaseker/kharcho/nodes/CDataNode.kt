package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.internal.QuietAppendable

/**
 * A Character Data node, to support CDATA sections.
 */
class CDataNode(text: String?) : TextNode(text) {
    override fun nodeName(): String {
        return "#cdata"
    }

    /**
     * Get the un-encoded, **non-normalized** text content of this CDataNode.
     * @return un-encoded, non-normalized text
     */
    override fun text(): String? {
        return getWholeText()
    }

    override fun outerHtmlHead(accum: QuietAppendable, out: Document.OutputSettings?) {
        accum
            .append("<![CDATA[")!!
            .append(getWholeText())!!
            .append("]]>")
    }

    override fun clone(): CDataNode? {
        return super.clone() as CDataNode?
    }
}
