package io.kapaseker.kharcho.select

import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.nodes.Node
import io.kapaseker.kharcho.select.NodeTraversor.filter

/**
 * A depth-first node traversor. Use to walk through all nodes under and including the specified root node, in document
 * order. The [NodeVisitor.head] and [NodeVisitor.tail] methods will be called for
 * each node.
 *
 *  During traversal, structural changes to nodes are supported (e.g. {[Node.replaceWith],
 * [Node.remove]}
 *
 */
object NodeTraversor {
    /**
     * Run a depth-first traverse of the root and all of its descendants.
     * @param visitor Node visitor.
     * @param root the initial node point to traverse.
     * @see NodeVisitor.traverse
     */
    fun traverse(visitor: NodeVisitor?, root: Node?) {
        Validate.notNull(visitor)
        Validate.notNull(root)
        var node = root
        var depth = 0

        while (node != null) {
            val parent =
                node.parentNode() // remember parent to find nodes that get replaced in .head
            val origSize = if (parent != null) parent.childNodeSize() else 0
            val next = node.nextSibling()

            visitor!!.head(node, depth) // visit current node

            // check for modifications to the tree
            if (parent != null && !node.hasParent()) { // removed or replaced
                if (origSize == parent.childNodeSize()) { // replaced
                    node =
                        parent.childNode(node.siblingIndex()) // replace ditches parent but keeps sibling index
                    continue
                }
                // else, removed
                node = next
                if (node == null) {
                    // was last in parent. need to walk up the tree, tail()ing on the way, until we find a suitable next. Otherwise, would revisit ancestor nodes.
                    node = parent
                    while (true) {
                        depth--
                        visitor.tail(node, depth)
                        if (node === root) break
                        if (node!!.nextSibling() != null) {
                            node = node.nextSibling()
                            break
                        }
                        node = node.parentNode()
                        if (node == null) break
                    }
                    if (node === root || node == null) break // done, break outer
                }
                continue  // don't tail removed
            }

            if (node.childNodeSize() > 0) { // descend
                node = node.childNode(0)
                depth++
            } else {
                while (true) {
                    checkNotNull(node) // as depth > 0, will have parent
                    if (!(node.nextSibling() == null && depth > 0)) break
                    visitor.tail(node, depth) // when no more siblings, ascend
                    node = node.parentNode()
                    depth--
                }
                visitor.tail(node, depth)
                if (node === root) break
                node = node.nextSibling()
            }
        }
    }

    /**
     * Run a depth-first traversal of each Element.
     * @param visitor Node visitor.
     * @param elements Elements to traverse.
     */
    fun traverse(visitor: NodeVisitor?, elements: Elements?) {
        Validate.notNull(visitor)
        Validate.notNull(elements)
        for (el in elements!!) traverse(visitor, el)
    }

    /**
     * Run a depth-first filtered traversal of the root and all of its descendants.
     * @param filter NodeFilter visitor.
     * @param root the root node point to traverse.
     * @return The filter result of the root node, or [FilterResult.STOP].
     * @see NodeFilter
     */
    fun filter(filter: NodeFilter, root: Node?): NodeFilter.FilterResult? {
        var node = root
        var depth = 0

        while (node != null) {
            var result = filter.head(node, depth)
            if (result == NodeFilter.FilterResult.STOP) return result
            // Descend into child nodes:
            if (result == NodeFilter.FilterResult.CONTINUE && node.childNodeSize() > 0) {
                node = node.childNode(0)
                ++depth
                continue
            }
            // No siblings, move upwards:
            while (true) {
                checkNotNull(node) // depth > 0, so has parent
                if (!(node.nextSibling() == null && depth > 0)) break
                // 'tail' current node:
                if (result == NodeFilter.FilterResult.CONTINUE || result == NodeFilter.FilterResult.SKIP_CHILDREN) {
                    result = filter.tail(node, depth)
                    if (result == NodeFilter.FilterResult.STOP) return result
                }
                val prev: Node? = node // In case we need to remove it below.
                node = node.parentNode()
                depth--
                if (result == NodeFilter.FilterResult.REMOVE) prev!!.remove() // Remove AFTER finding parent.

                result = NodeFilter.FilterResult.CONTINUE // Parent was not pruned.
            }
            // 'tail' current node, then proceed with siblings:
            if (result == NodeFilter.FilterResult.CONTINUE || result == NodeFilter.FilterResult.SKIP_CHILDREN) {
                result = filter.tail(node, depth)
                if (result == NodeFilter.FilterResult.STOP) return result
            }
            if (node === root) return result
            val prev: Node? = node // In case we need to remove it below.
            node = node.nextSibling()
            if (result == NodeFilter.FilterResult.REMOVE) prev!!.remove() // Remove AFTER finding sibling.
        }
        // root == null?
        return NodeFilter.FilterResult.CONTINUE
    }

    /**
     * Run a depth-first filtered traversal of each Element.
     * @param filter NodeFilter visitor.
     * @see NodeFilter
     */
    fun filter(filter: NodeFilter?, elements: Elements?) {
        Validate.notNull(filter)
        Validate.notNull(elements)
        for (el in elements!!) if (NodeTraversor.filter(
                filter!!,
                el
            ) == NodeFilter.FilterResult.STOP
        ) break
    }
}
