package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.internal.QuietAppendable
import io.kapaseker.kharcho.internal.QuietAppendable.Companion.wrap
import io.kapaseker.kharcho.internal.StringUtil.borrowBuilder
import io.kapaseker.kharcho.internal.StringUtil.releaseBuilder

/**
 * An XML Declaration. Includes support for treating the declaration contents as pseudo attributes.
 */
class XmlDeclaration
/**
 * Create a new XML declaration
 * @param name of declaration
 * @param isDeclaration `true` if a declaration (first char is `!`), otherwise a processing instruction (first char is `?`).
 */(
    name: String,
    /**
     * First char is `!` if isDeclaration, like in `<!ENTITY ...>`.
     * Otherwise, is `?`, a processing instruction, like `<?xml .... ?>` (and note trailing `?`).
     */
    private val isDeclaration: Boolean
) : LeafNode(name) {
    override fun nodeName(): String {
        return "#declaration"
    }

    /**
     * Get the name of this declaration.
     * @return name of this declaration.
     */
    fun name(): String {
        return coreValue()
    }

    val wholeDeclaration: String
        /**
         * Get the unencoded XML declaration.
         * @return XML declaration
         */
        get() {
            val sb =
                borrowBuilder()
            getWholeDeclaration(
                wrap(sb),
                Document.OutputSettings()
            )
            return releaseBuilder(sb).trim { it <= ' ' }
        }

    private fun getWholeDeclaration(accum: QuietAppendable, out: Document.OutputSettings) {
        for (attribute in attributes()) {
            val key = attribute.getKey()
            val `val` = attribute.value
            if (key != nodeName()) { // skips coreValue (name)
                accum.append(' ')
                // basically like Attribute, but skip empty vals in XML
                accum.append(key)
                if (!`val`.isEmpty()) {
                    accum.append("=\"")
                    Entities.escape(accum, `val`, out, Entities.ForAttribute)
                    accum.append('"')
                }
            }
        }
    }

    override fun outerHtmlHead(accum: QuietAppendable, out: Document.OutputSettings) {
        accum
            .append("<")
            .append(if (isDeclaration) "!" else "?")
            .append(coreValue())
        getWholeDeclaration(accum, out)
        accum
            .append(if (isDeclaration) "" else "?")
            .append(">")
    }

    override fun outerHtmlTail(accum: QuietAppendable, out: Document.OutputSettings) {
    }

    override fun toString(): String {
        return outerHtml()
    }

    override fun clone(): XmlDeclaration {
        return super.clone() as XmlDeclaration
    }
}
