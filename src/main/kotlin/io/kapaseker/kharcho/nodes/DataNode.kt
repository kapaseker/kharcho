package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.internal.QuietAppendable

/**
 * A data node, for contents of style, script tags etc, where contents should not show in text().
 *
 * @author Jonathan Hedley, jonathan@hedley.net
 */
class DataNode
/**
 * Create a new DataNode.
 * @param data data contents
 */
    (data: String) : LeafNode(data) {
    public override fun nodeName(): String {
        return "#data"
    }

    val wholeData: String
        /**
         * Get the data contents of this node. Will be unescaped and with original new lines, space etc.
         * @return data
         */
        get() = coreValue()

    /**
     * Set the data contents of this node.
     * @param data un-encoded data
     * @return this node, for chaining
     */
    fun setWholeData(data: String?): DataNode {
        coreValue(data)
        return this
    }

    public override fun outerHtmlHead(accum: QuietAppendable, out: Document.OutputSettings) {
        /* For XML output, escape the DataNode in a CData section. The data may contain pseudo-CData content if it was
        parsed as HTML, so don't double up Cdata. Output in polyglot HTML / XHTML / XML format. */
        val data = this.wholeData
        if (out.syntax() == Document.OutputSettings.Syntax.xml && !data.contains("<![CDATA[")) {
            if (parentNameIs("script")) accum.append("//<![CDATA[\n")!!.append(data)!!
                .append("\n//]]>")
            else if (parentNameIs("style")) accum.append("/*<![CDATA[*/\n")!!.append(data)!!
                .append("\n/*]]>*/")
            else accum.append("<![CDATA[")!!.append(data)!!.append("]]>")
        } else {
            // In HTML, data is not escaped in the output of data nodes, so < and & in script, style is OK
            accum.append(data)
        }
    }

    override fun clone(): DataNode {
        return super.clone() as DataNode
    }
}
