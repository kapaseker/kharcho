package io.kapaseker.kharcho.select

import io.kapaseker.kharcho.nodes.Node

/**
 * Node visitor interface, used to walk the DOM, and visit each Node. Execute via [.traverse] or
 * [Node.traverse]. The traversal is depth-first.
 *
 *
 * This interface provides two methods, [.head] and [.tail]. The head method is called when a node is first
 * seen, and the tail method when all that node's children have been visited. As an example, `head` can be used to
 * emit a start tag for a node, and `tail` to create the end tag. The `tail` method defaults to a no-op, so
 * the `head` method is the [FunctionalInterface].
 *
 *
 * **Example:**
 * <pre>`
 * doc.body().traverse((node, depth) -> {
 * switch (node) {
 * case Element el     -> print(el.tag() + ": " + el.ownText());
 * case DataNode data  -> print("Data: " + data.getWholeData());
 * default             -> print(node.nodeName() + " at depth " + depth);
 * }
 * });
`</pre> *
 */
fun interface NodeVisitor {
    /**
     * Callback for when a node is first visited.
     *
     * The node may be modified (e.g. [Node.attr], replaced [Node.replaceWith]) or removed
     * [Node.remove]. If it's `instanceOf Element`, you may cast it to an [Element] and access those
     * methods.
     *
     * @param node the node being visited.
     * @param depth the depth of the node, relative to the root node. E.g., the root node has depth 0, and a child node
     * of that will have depth 1.
     */
    fun head(node: Node, depth: Int)

    /**
     * Callback for when a node is last visited, after all of its descendants have been visited.
     *
     * This method has a default no-op implementation.
     *
     * Note that neither replacement with [Node.replaceWith] nor removal with [Node.remove] is
     * supported during `tail()`.
     *
     * @param node the node being visited.
     * @param depth the depth of the node, relative to the root node. E.g., the root node has depth 0, and a child node
     * of that will have depth 1.
     */
    fun tail(node: Node, depth: Int) {
        // no-op by default, to allow just specifying the head() method
    }

    /**
     * Run a depth-first traverse of the root and all of its descendants.
     * @param root the initial node point to traverse.
     * @since 1.21.1
     */
    fun traverse(root: Node) {
        NodeTraversor.traverse(this, root)
    }
}
