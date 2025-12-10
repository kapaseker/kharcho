package io.kapaseker.kharcho.select

import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.nodes.Node
import java.util.function.Predicate
import java.util.function.UnaryOperator

/**
 * A list of [Node] objects, with methods that act on every node in the list.
 *
 * Methods that [set][.set], [remove][.remove], or
 * [replace][.replaceAll] nodes in the list will also act on the underlying
 * [DOM][io.kapaseker.kharcho.nodes.Document].
 *
 *
 * If there are other bulk methods (perhaps from Elements) that would be useful here, please [provide feedback](https://jsoup.org/discussion).
 *
 * @see Element.selectNodes
 * @see Element.selectNodes
 * @since 1.21.1
 */
open class Nodes<T : Node> : ArrayList<T> {

    constructor()

    constructor(initialCapacity: Int) : super(initialCapacity)

    constructor(nodes: Collection<T>) : super(nodes)

    /**
     * Creates a deep copy of these nodes.
     * @return a deep copy
     */
    override fun clone(): Nodes<T> {
        val clone = Nodes<T>(size)
        for (node in this) clone.add(node.clone() as T)
        return clone
    }

    /**
     * Convenience method to get the Nodes as a plain ArrayList. This allows modification to the list of nodes
     * without modifying the source Document. I.e. whereas calling `nodes.remove(0)` will remove the nodes from
     * both the Nodes and the DOM, `nodes.asList().remove(0)` will remove the node from the list only.
     *
     * Each Node is still the same DOM connected Node.
     *
     * @return a new ArrayList containing the nodes in this list
     * @see .Nodes
     */
    open fun asList(): List<T> {
        return this.toList()
    }

    /**
     * Remove each matched node from the DOM.
     *
     * The nodes will still be retained in this list, in case further processing of them is desired.
     *
     *
     * E.g. HTML: `<div><p>Hello</p> <p>there</p> <img></div>`<br></br>
     * `doc.select("p").remove();`<br></br>
     * HTML = `<div> <img></div>`
     *
     *
     * Note that this method should not be used to clean user-submitted HTML; rather, use [io.kapaseker.kharcho.safety.Cleaner]
     * to clean HTML.
     *
     * @return this, for chaining
     * @see Element.empty
     * @see Elements.empty
     * @see .clear
     */
    open fun remove(): Nodes<T> {
        for (node in this) {
            node.remove()
        }
        return this
    }

    /**
     * Get the combined outer HTML of all matched nodes.
     *
     * @return string of all node's outer HTML.
     * @see Elements.text
     * @see Elements.html
     */
    fun outerHtml(): String? {
        return stream()
            .map { obj: T? -> obj!!.outerHtml() }
            .collect(StringUtil.joining("\n"))
    }

    /**
     * Get the combined outer HTML of all matched nodes. Alias of [.outerHtml].
     *
     * @return string of all the node's outer HTML.
     * @see Elements.text
     * @see .outerHtml
     */
    override fun toString(): String {
        return outerHtml()!!
    }

    /**
     * Insert the supplied HTML before each matched node's outer HTML.
     *
     * @param html HTML to insert before each node
     * @return this, for chaining
     * @see Element.before
     */
    open fun before(html: String): Nodes<T> {
        for (node in this) {
            node.before(html)
        }
        return this
    }

    /**
     * Insert the supplied HTML after each matched nodes's outer HTML.
     *
     * @param html HTML to insert after each node
     * @return this, for chaining
     * @see Element.after
     */
    open fun after(html: String): Nodes<T> {
        for (node in this) {
            node.after(html)
        }
        return this
    }

    /**
     * Wrap the supplied HTML around each matched node. For example, with HTML
     * `<p><b>This</b> is <b>Jsoup</b></p>`,
     * `doc.select("b").wrap("<i></i>");`
     * becomes `<p><i><b>This</b></i> is <i><b>jsoup</b></i></p>`
     * @param html HTML to wrap around each node, e.g. `<div class="head"></div>`. Can be arbitrarily deep.
     * @return this (for chaining)
     * @see Element.wrap
     */
    open fun wrap(html: String): Nodes<T> {
        Validate.notEmpty(html)
        for (node in this) {
            node.wrap(html)
        }
        return this
    }

    // list-like methods
    /**
     * Get the first matched element.
     * @return The first matched element, or `null` if contents is empty.
     */
    open fun first(): T? {
        return if (isEmpty()) null else get(0)
    }

    /**
     * Get the last matched element.
     * @return The last matched element, or `null` if contents is empty.
     */
    open fun last(): T? {
        return if (isEmpty()) null else get(size - 1)
    }

    // ArrayList<T> methods that update the DOM:
    /**
     * Replace the node at the specified index in this list, and in the DOM.
     *
     * @param index index of the node to replace
     * @param node node to be stored at the specified position
     * @return the old Node at this index
     */
    override fun set(index: Int, node: T): T {
        Validate.notNull(node)
        val old = super.set(index = index, element = node)
        old.replaceWith(node)
        return old
    }

    /**
     * Remove the node at the specified index in this list, and from the DOM.
     *
     * @param index the index of the node to be removed
     * @return the old node at this index
     * @see .deselect
     */
    override fun removeAt(index: Int): T {
        val old = super.removeAt(index)
        old.remove()
        return old
    }

    /**
     * Remove the specified node from this list, and from the DOM.
     *
     * @param o node to be removed from this list, if present
     * @return if this list contained the Node
     * @see .deselect
     */
    override fun remove(o: T): Boolean {
        val index = super.indexOf(o)
        if (index == -1) {
            return false
        } else {
            removeAt(index)
            return true
        }
    }

    /**
     * Remove the node at the specified index in this list, but not from the DOM.
     *
     * @param index the index of the node to be removed
     * @return the old node at this index
     * @see .remove
     */
    open fun deselect(index: Int): T? {
        return super.removeAt(index)
    }

    /**
     * Remove the specified node from this list, but not from the DOM.
     *
     * @param o node to be removed from this list, if present
     * @return if this list contained the Node
     * @see .remove
     */
    fun deselect(o: T): Boolean {
        return super.remove(o)
    }

    /**
     * Removes all the nodes from this list, and each of them from the DOM.
     *
     * @see .deselectAll
     */
    override fun clear() {
        remove()
        super.clear()
    }

    /**
     * Like [.clear], removes all the nodes from this list, but not from the DOM.
     *
     * @see .clear
     */
    fun deselectAll() {
        super.clear()
    }

    /**
     * Removes from this list, and from the DOM, each of the nodes that are contained in the specified collection and are
     * in this list.
     *
     * @param c collection containing nodes to be removed from this list
     * @return `true` if nodes were removed from this list
     */
    override fun removeAll(c: Collection<T>): Boolean {
        var anyRemoved = false
        for (o in c) {
            anyRemoved = anyRemoved or this.remove(o)
        }
        return anyRemoved
    }

    /**
     * Retain in this list, and in the DOM, only the nodes that are in the specified collection and are in this list. In
     * other words, remove nodes from this list and the DOM any item that is in this list but not in the specified
     * collection.
     *
     * @param toRemove collection containing nodes to be retained in this list
     * @return `true` if nodes were removed from this list
     * @since 1.17.1
     */
    override fun retainAll(toRemove: Collection<T>): Boolean {
        var anyRemoved = false
        val it = this.iterator()
        while (it.hasNext()) {
            val el = it.next()
            if (!toRemove.contains(el)) {
                it.remove()
                anyRemoved = true
            }
        }
        return anyRemoved
    }

    /**
     * Remove from the list, and from the DOM, all nodes in this list that mach the given predicate.
     *
     * @param filter a predicate which returns `true` for nodes to be removed
     * @return `true` if nodes were removed from this list
     */
    override fun removeIf(filter: Predicate<in T>): Boolean {
        var anyRemoved = false
        val it = this.iterator()
        while (it.hasNext()) {
            val node = it.next()
            if (filter.test(node)) {
                it.remove()
                anyRemoved = true
            }
        }
        return anyRemoved
    }

    /**
     * Replace each node in this list with the result of the operator, and update the DOM.
     *
     * @param operator the operator to apply to each node
     */
    override fun replaceAll(operator: UnaryOperator<T>) {
        for (i in this.indices) {
            this.set(i, operator.apply(this.get(i)))
        }
    }
}
