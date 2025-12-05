package io.kapaseker.kharcho.select

import io.kapaseker.kharcho.nodes.Node

/**
 * A controllable Node visitor interface. Execute via [.traverse].
 *
 *
 * This interface provides two methods, `head` and `tail`. The head method is called when a node is first seen,
 * and the tail method when all that node's children have been visited.
 *
 *
 *
 * For each visited node, the resulting action may be:
 *
 *  * continue ([FilterResult.CONTINUE]),
 *  * skip all children ([FilterResult.SKIP_CHILDREN]),
 *  * skip node entirely ([FilterResult.SKIP_ENTIRELY]),
 *  * remove the subtree ([FilterResult.REMOVE]),
 *  * interrupt the iteration and return ([FilterResult.STOP]).
 *
 * The difference between [FilterResult.SKIP_CHILDREN] and [FilterResult.SKIP_ENTIRELY] is that the first
 * will invoke [NodeFilter.tail] on the node, while the latter will not.
 * Within [NodeFilter.tail], both are equivalent to [FilterResult.CONTINUE].
 *
 */
interface NodeFilter {
    /**
     * Traversal action.
     */
    enum class FilterResult {
        /** Continue processing the tree  */
        CONTINUE,

        /** Skip the child nodes, but do call [NodeFilter.tail] next.  */
        SKIP_CHILDREN,

        /** Skip the subtree, and do not call [NodeFilter.tail].  */
        SKIP_ENTIRELY,

        /** Remove the node and its children  */
        REMOVE,

        /** Stop processing  */
        STOP
    }

    /**
     * Callback for when a node is first visited.
     * @param node the node being visited.
     * @param depth the depth of the node, relative to the root node. E.g., the root node has depth 0, and a child node of that will have depth 1.
     * @return Traversal action
     */
    fun head(node: Node?, depth: Int): FilterResult?

    /**
     * Callback for when a node is last visited, after all of its descendants have been visited.
     *
     * This method has a default implementation to return [FilterResult.CONTINUE].
     * @param node the node being visited.
     * @param depth the depth of the node, relative to the root node. E.g., the root node has depth 0, and a child node of that will have depth 1.
     * @return Traversal action
     */
    fun tail(node: Node?, depth: Int): FilterResult {
        return FilterResult.CONTINUE
    }

    /**
     * Run a depth-first controlled traverse of the root and all of its descendants.
     * @param root the initial node point to traverse.
     * @since 1.21.1
     */
    fun traverse(root: Node?) {
        NodeTraversor.filter(this, root)
    }
}
