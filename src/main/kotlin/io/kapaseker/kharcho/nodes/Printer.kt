package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.parser.Tag
import io.kapaseker.kharcho.select.NodeVisitor

/** Base Printer  */
internal open class Printer(
    val root: Node?,
    accum: QuietAppendable,
    settings: Document.OutputSettings
) : NodeVisitor {
    val accum: QuietAppendable
    val settings: Document.OutputSettings

    init {
        this.accum = accum
        this.settings = settings
    }

    open fun addHead(el: Element, depth: Int) {
        el.outerHtmlHead(accum, settings)
    }

    open fun addTail(el: Element, depth: Int) {
        el.outerHtmlTail(accum, settings)
    }

    open fun addText(textNode: TextNode, textOptions: Int, depth: Int) {
        val options = Entities.ForText or textOptions
        Entities.escape(accum, textNode.coreValue(), settings, options)
    }

    open fun addNode(node: LeafNode, depth: Int) {
        node.outerHtmlHead(accum, settings)
    }

    fun indent(depth: Int) {
        accum.append('\n')
            .append(StringUtil.padding(depth * settings.indentAmount(), settings.maxPaddingWidth()))
    }

    override fun head(node: Node, depth: Int) {
        if (node.javaClass == TextNode::class.java) addText(
            node as TextNode,
            0,
            depth
        ) // Excludes CData; falls to addNode
        else if (node is Element) addHead(node, depth)
        else addNode(node as LeafNode, depth)
    }

    override fun tail(node: Node?, depth: Int) {
        if (node is Element) { // otherwise a LeafNode
            addTail(node, depth)
        }
    }

    /** Pretty Printer  */
    internal open class Pretty(
        root: Node?,
        accum: QuietAppendable,
        settings: Document.OutputSettings
    ) : Printer(root, accum, settings) {
        var preserveWhitespace: Boolean = false

        override fun addHead(el: Element, depth: Int) {
            if (shouldIndent(el)) indent(depth)
            super.addHead(el, depth)
            if (tagIs(Tag.PreserveWhitespace, el)) preserveWhitespace = true
        }

        override fun addTail(el: Element, depth: Int) {
            if (shouldIndent(nextNonBlank(el.firstChild()))) {
                indent(depth)
            }
            super.addTail(el, depth)

            // clear the preserveWhitespace if this element is not, and there are none on the stack above
            if (preserveWhitespace && el.tag.`is`(Tag.PreserveWhitespace)) {
                var parent = el.parent()
                while (parent != null) {
                    if (parent.tag().preserveWhitespace()) return  // keep

                    parent = parent.parent()
                }
                preserveWhitespace = false
            }
        }

        override fun addNode(node: LeafNode, depth: Int) {
            if (shouldIndent(node)) indent(depth)
            super.addNode(node, depth)
        }

        override fun addText(node: TextNode, textOptions: Int, depth: Int) {
            var textOptions = textOptions
            if (!preserveWhitespace) {
                textOptions = textOptions or Entities.Normalise
                textOptions = textTrim(node, textOptions)

                if (!node.isBlank && isBlockEl(node.parentNode) && shouldIndent(node)) indent(depth)
            }

            super.addText(node, textOptions, depth)
        }

        fun textTrim(node: TextNode, options: Int): Int {
            var options = options
            if (!isBlockEl(node.parentNode)) return options // don't trim inline, whitespace significant

            val prev = node.previousSibling()
            var next = node.nextSibling()

            // if previous is not an inline element
            if (!(prev is Element && !isBlockEl(prev))) {
                // if there is no previous sib; or not a text node and should be indented
                if (prev == null || prev !is TextNode && shouldIndent(prev)) options =
                    options or Entities.TrimLeading
            }

            if (next == null || next !is TextNode && shouldIndent(next)) {
                options = options or Entities.TrimTrailing
            } else { // trim trailing whitespace if the next non-empty TextNode has leading whitespace
                next = nextNonBlank(next)
                if (next is TextNode && StringUtil.isWhitespace(
                        next.nodeValue().codePointAt(0)
                    )
                ) options = options or Entities.TrimTrailing
            }

            return options
        }

        open fun shouldIndent(@Nullable node: Node?): Boolean {
            if (node == null || node === root || preserveWhitespace || isBlankText(node)) return false
            if (isBlockEl(node)) return true

            val prevSib: Node? = previousNonblank(node)
            if (isBlockEl(prevSib)) return true

            val parent = node.parentNode
            if (!isBlockEl(parent) || parent.tag().`is`(Tag.InlineContainer) || !hasNonTextNodes(
                    parent
                )
            ) return false

            return prevSib == null ||
                    (prevSib !is TextNode &&
                            (isBlockEl(prevSib) || prevSib !is Element))
        }

        open fun isBlockEl(@Nullable node: Node?): Boolean {
            if (node == null) return false
            if (node is Element) {
                val el = node
                return el.isBlock() ||
                        (!el.tag.isKnownTag && (el.parentNode is Document || hasChildBlocks(el)))
            }

            return false
        }

        init {
            // check if there is a pre on stack
            var node = root
            while (node != null) {
                if (tagIs(Tag.PreserveWhitespace, node)) {
                    preserveWhitespace = true
                    break
                }
                node = node.parentNode()
            }
        }

        companion object {
            /**
             * Returns true if any of the Element's child nodes should indent. Checks the last 5 nodes only (to minimize
             * scans).
             */
            fun hasChildBlocks(el: Element): Boolean {
                var child = el.firstElementChild()
                var i = 0
                while (i < maxScan && child != null) {
                    if (child.isBlock() || !child.tag.isKnownTag) return true
                    child = child.nextElementSibling()
                    i++
                }
                return false
            }

            private const val maxScan = 5

            fun hasNonTextNodes(el: Element): Boolean {
                var child = el.firstChild()
                var i = 0
                while (i < maxScan && child != null) {
                    if (child !is TextNode) return true
                    child = child.nextSibling()
                    i++
                }
                return false
            }

            @Nullable
            fun previousNonblank(node: Node): Node? {
                var prev = node.previousSibling()
                while (isBlankText(prev)) prev = prev!!.previousSibling()
                return prev
            }

            @Nullable
            fun nextNonBlank(@Nullable node: Node?): Node? {
                var node = node
                while (isBlankText(node)) node = node!!.nextSibling()
                return node
            }

            fun isBlankText(@Nullable node: Node?): Boolean {
                return node is TextNode && node.isBlank
            }

            fun tagIs(option: Int, @Nullable node: Node?): Boolean {
                return node is Element && node.tag.`is`(option)
            }
        }
    }

    /** Outline Printer  */
    internal class Outline(root: Node?, accum: QuietAppendable, settings: Document.OutputSettings) :
        Pretty(root, accum, settings) {
        override fun isBlockEl(@Nullable node: Node?): Boolean {
            return node != null
        }

        override fun shouldIndent(@Nullable node: Node?): Boolean {
            if (node == null || node === root || preserveWhitespace || isBlankText(node)) return false
            if (node is TextNode) {
                return node.previousSibling() != null || node.nextSibling() != null
            }
            return true
        }
    }

    companion object {
        fun printerFor(root: Node, accum: QuietAppendable): Printer {
            val settings = NodeUtils.outputSettings(root)
            if (settings.outline()) return Outline(root, accum, settings)
            if (settings.prettyPrint()) return Pretty(root, accum, settings)
            return Printer(root, accum, settings)
        }
    }
}
