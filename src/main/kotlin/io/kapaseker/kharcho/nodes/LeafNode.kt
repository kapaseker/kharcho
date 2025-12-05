package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.QuietAppendable

/**
 * A node that does not hold any children. E.g.: [TextNode], [DataNode], [Comment].
 */
abstract class LeafNode : Node {
    var value: Any? // either a string value, or an attribute map (in the rare case multiple attributes are set)

    constructor() {
        value = ""
    }

    protected constructor(coreValue: String) {
        Validate.notNull(coreValue)
        value = coreValue
    }

    override fun hasAttributes(): Boolean {
        return value is Attributes
    }

    public override fun attributes(): Attributes? {
        ensureAttributes()
        return value as Attributes?
    }

    private fun ensureAttributes() {
        if (!hasAttributes()) { // then value is String coreValue
            val coreValue = value as String?
            val attributes = Attributes()
            value = attributes
            attributes.put(nodeName()!!, coreValue)
        }
    }

    fun coreValue(): String? {
        return attr(nodeName()!!)
    }

    override fun parent(): Element? {
        return parentNode
    }

    override fun nodeValue(): String? {
        return coreValue()
    }

    fun coreValue(value: String?) {
        attr(nodeName()!!, value)
    }

    override fun attr(key: String): String? {
        if (!hasAttributes()) {
            return if (nodeName() == key) value as String? else EmptyString
        }
        return super.attr(key)
    }

    override fun attr(key: String, value: String?): Node? {
        if (!hasAttributes() && key == nodeName()) {
            this.value = value
        } else {
            ensureAttributes()
            super.attr(key, value)
        }
        return this
    }

    public override fun hasAttr(key: String): Boolean {
        ensureAttributes()
        return super.hasAttr(key)
    }

    public override fun removeAttr(key: String): Node? {
        ensureAttributes()
        return super.removeAttr(key)
    }

    public override fun absUrl(key: String): String? {
        ensureAttributes()
        return super.absUrl(key)
    }

    public override fun baseUri(): String? {
        return if (parentNode != null) parentNode!!.baseUri() else ""
    }

    override fun doSetBaseUri(baseUri: String?) {
        // noop
    }

    public override fun childNodeSize(): Int {
        return 0
    }

    public override fun empty(): Node? {
        return this
    }

    override fun ensureChildNodes(): MutableList<Node?> {
        return EmptyNodes
    }

    override fun outerHtmlTail(accum: QuietAppendable?, out: Document.OutputSettings?) {}

    override fun doClone(parent: Node?): LeafNode {
        val clone = super.doClone(parent) as LeafNode

        // Object value could be plain string or attributes - need to clone
        if (hasAttributes()) clone.value = (value as Attributes).clone()

        return clone
    }
}
