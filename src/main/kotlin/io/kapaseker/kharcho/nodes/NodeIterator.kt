package io.kapaseker.kharcho.nodes

/**
 * Iterate through a Node and its tree of descendants, in document order, and returns nodes of the specified type. This
 * iterator supports structural changes to the tree during the traversal, such as [Node.remove],
 * [Node.replaceWith], [Node.wrap], etc.
 *
 * See also the [NodeTraversor][io.kapaseker.kharcho.select.NodeTraversor] if `head` and `tail` callbacks are
 * desired for each node.
 * @since 1.17.1
 */
class NodeIterator<T : Node>(start: Node) : MutableIterator<T> {

    private var root: Node? = null

    private var next: T? = null // the next node to return
    private lateinit var current: Node// the current (last emitted) node

    private lateinit var previous: Node
    private var currentParent: Node? = null

    /**
     * Create a NoteIterator that will iterate the supplied node, and all of its descendants. The returned [.next]
     * type will be filtered to the input type.
     * @param start initial node
     * @param type node type to filter for
     */
    init {
        restart(start)
    }

    /**
     * Restart this Iterator from the specified start node. Will act as if it were newly constructed. Useful for e.g. to
     * save some GC if the iterator is used in a tight loop.
     * @param start the new start node.
     */
    fun restart(start: Node) {

        (start as? T)?.let {
            next = it
        }

        current = start
        previous = start
        root = start
        currentParent = start.parent()
    }

    override fun hasNext(): Boolean {
        maybeFindNext()
        return next != null
    }

    override fun next(): T {
        maybeFindNext()
        val result = next ?: throw NoSuchElementException()
        previous = current
        current = result
        currentParent = current.parent()
        next = null
        return result
    }

    /**
     * If next is not null, looks for and sets next. If next is null after this, we have reached the end.
     */
    private fun maybeFindNext() {
        if (next != null) return

        //  change detected (removed or replaced), redo from previous
        if (currentParent != null && !current.hasParent()) current = previous

        next = findNextNode()
    }

    private fun findNextNode(): T? {
        var node = current
        while (true) {
            if (node.childNodeSize() > 0) node = node.childNode(0) // descend children
            else if (root == node) return null // complete when all children of root are fully visited
            else {
                node.nextSibling()?.let {
                    node = it
                } ?: run {
                    while (true) {
                        val parent = node.parent() ?: break
                        node = parent// pop out of descendants
                        if (root == node) return null // got back to root; complete

                        node.nextSibling()?.let {
                            node = it
                            break
                        }
                    }
                }
            }
            (node as? T)?.let {
                return it
            }
        }
    }

    override fun remove() {
        current.remove()
    }

    companion object {
        /**
         * Create a NoteIterator that will iterate the supplied node, and all of its descendants. All node types will be
         * returned.
         * @param start initial node
         */
        fun from(start: Node): NodeIterator<Node> {
            return NodeIterator(start)
        }
    }
}
