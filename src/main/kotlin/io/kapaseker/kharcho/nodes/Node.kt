package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.QuietAppendable
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.parser.ParseSettings
import io.kapaseker.kharcho.select.NodeFilter
import io.kapaseker.kharcho.select.NodeVisitor
import java.io.IOException
import java.util.*
import java.util.function.Consumer
import java.util.stream.Stream

/**
 * The base, abstract Node model. [Element], [Document], [Comment], [TextNode], et al.,
 * are instances of Node.
 *
 * @author Jonathan Hedley, jonathan@hedley.net
 */
abstract class Node
/**
 * Default constructor. Doesn't set up base uri, children, or attributes; use with caution.
 */
protected constructor() : Cloneable {

    @JvmField
    var parentNode: Element? = null // Nodes don't always have parents
    var siblingIndex: Int = 0

    /**
     * Get the node name of this node. Use for debugging purposes and not logic switching (for that, use instanceof).
     * @return node name
     */
    abstract fun nodeName(): String?

    /**
     * Get the normalized name of this node. For node types other than Element, this is the same as [.nodeName].
     * For an Element, will be the lower-cased tag name.
     * @return normalized node name
     * @since 1.15.4.
     */
    open fun normalName(): String? {
        return nodeName()
    }

    /**
     * Get the node's value. For a TextNode, the whole text; for a Comment, the comment data; for an Element,
     * wholeOwnText. Returns "" if there is no value.
     * @return the node's value
     */
    open fun nodeValue(): String? {
        return ""
    }

    /**
     * Test if this node has the specified normalized name, in any namespace.
     * @param normalName a normalized element name (e.g. `div`).
     * @return true if the element's normal name matches exactly
     * @since 1.17.2
     */
    fun nameIs(normalName: String?): Boolean {
        return normalName() == normalName
    }

    /**
     * Test if this node's parent has the specified normalized name.
     * @param normalName a normalized name (e.g. `div`).
     * @return true if the parent element's normal name matches exactly
     * @since 1.17.2
     */
    fun parentNameIs(normalName: String?): Boolean {
        return parentNode != null && parentNode!!.normalName() == normalName
    }

    /**
     * Test if this node's parent is an Element with the specified normalized name and namespace.
     * @param normalName a normalized element name (e.g. `div`).
     * @param namespace the namespace
     * @return true if the parent element's normal name matches exactly, and that element is in the specified namespace
     * @since 1.17.2
     */
    fun parentElementIs(normalName: String?, namespace: String?): Boolean {
        return parentNode != null && parentNode is Element
                && (parentNode as Element).elementIs(normalName, namespace)
    }

    /**
     * Check if this Node has an actual Attributes object.
     */
    abstract fun hasAttributes(): Boolean

    /**
     * Checks if this node has a parent. Nodes won't have parents if (e.g.) they are newly created and not added as a child
     * to an existing node, or if they are a [.shallowClone]. In such cases, [.parent] will return `null`.
     * @return if this node has a parent.
     */
    fun hasParent(): Boolean {
        return parentNode != null
    }

    /**
     * Get an attribute's value by its key. **Case insensitive**
     *
     *
     * To get an absolute URL from an attribute that may be a relative URL, prefix the key with `**abs:**`,
     * which is a shortcut to the [.absUrl] method.
     *
     * E.g.:
     * <blockquote>`String url = a.attr("abs:href");`</blockquote>
     *
     * @param attributeKey The attribute key.
     * @return The attribute, or empty string if not present (to avoid nulls).
     * @see .attributes
     * @see .hasAttr
     * @see .absUrl
     */
    open fun attr(attributeKey: String): String? {
        Validate.notNull(attributeKey)
        if (!hasAttributes()) return EmptyString

        val `val` = attributes()!!.getIgnoreCase(attributeKey)
        if (`val`!!.length > 0) return `val`
        else if (attributeKey.startsWith("abs:")) return absUrl(attributeKey.substring("abs:".length))
        else return ""
    }

    /**
     * Get each of the Element's attributes.
     * @return attributes (which implements Iterable, with the same order as presented in the original HTML).
     */
    abstract fun attributes(): Attributes?

    /**
     * Get the number of attributes that this Node has.
     * @return the number of attributes
     * @since 1.14.2
     */
    fun attributesSize(): Int {
        // added so that we can test how many attributes exist without implicitly creating the Attributes object
        return if (hasAttributes()) attributes()!!.size() else 0
    }

    /**
     * Set an attribute (key=value). If the attribute already exists, it is replaced. The attribute key comparison is
     * **case insensitive**. The key will be set with case sensitivity as set in the parser settings.
     * @param attributeKey The attribute key.
     * @param attributeValue The attribute value.
     * @return this (for chaining)
     */
    open fun attr(attributeKey: String, attributeValue: String?): Node? {
        var attributeKey = attributeKey
        val doc = ownerDocument()
        val settings = if (doc != null) doc.parser().settings() else ParseSettings.htmlDefault
        attributeKey = settings.normalizeAttribute(attributeKey)
        attributes()!!.putIgnoreCase(attributeKey, attributeValue)
        return this
    }

    /**
     * Test if this Node has an attribute. **Case insensitive**.
     * @param attributeKey The attribute key to check.
     * @return true if the attribute exists, false if not.
     */
    open fun hasAttr(attributeKey: String): Boolean {
        Validate.notNull(attributeKey)
        if (!hasAttributes()) return false

        if (attributeKey.startsWith("abs:")) {
            val key = attributeKey.substring("abs:".length)
            if (attributes()!!.hasKeyIgnoreCase(key) && !absUrl(key)!!.isEmpty()) return true
        }
        return attributes()!!.hasKeyIgnoreCase(attributeKey)
    }

    /**
     * Remove an attribute from this node.
     * @param attributeKey The attribute to remove.
     * @return this (for chaining)
     */
    open fun removeAttr(attributeKey: String): Node? {
        Validate.notNull(attributeKey)
        if (hasAttributes()) attributes()!!.removeIgnoreCase(attributeKey)
        return this
    }

    /**
     * Clear (remove) each of the attributes in this node.
     * @return this, for chaining
     */
    open fun clearAttributes(): Node? {
        if (hasAttributes()) {
            val it: MutableIterator<Attribute?> = attributes()!!.iterator()
            while (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        return this
    }

    /**
     * Get the base URI that applies to this node. Will return an empty string if not defined. Used to make relative links
     * absolute.
     *
     * @return base URI
     * @see .absUrl
     */
    abstract fun baseUri(): String?

    /**
     * Set the baseUri for just this node (not its descendants), if this Node tracks base URIs.
     * @param baseUri new URI
     */
    protected abstract fun doSetBaseUri(baseUri: String?)

    /**
     * Update the base URI of this node and all of its descendants.
     * @param baseUri base URI to set
     */
    fun setBaseUri(baseUri: String) {
        Validate.notNull(baseUri)
        doSetBaseUri(baseUri)
    }

    /**
     * Get an absolute URL from a URL attribute that may be relative (such as an `<a href>` or
     * `<img src>`).
     *
     *
     * E.g.: `String absUrl = linkEl.absUrl("href");`
     *
     *
     *
     * If the attribute value is already absolute (i.e. it starts with a protocol, like
     * `http://` or `https://` etc), and it successfully parses as a URL, the attribute is
     * returned directly. Otherwise, it is treated as a URL relative to the element's [.baseUri], and made
     * absolute using that.
     *
     *
     *
     * As an alternate, you can use the [.attr] method with the `abs:` prefix, e.g.:
     * `String absUrl = linkEl.attr("abs:href");`
     *
     *
     * @param attributeKey The attribute key
     * @return An absolute URL if one could be made, or an empty string (not null) if the attribute was missing or
     * could not be made successfully into a URL.
     * @see .attr
     *
     * @see java.net.URL.URL
     */
    open fun absUrl(attributeKey: String): String? {
        Validate.notEmpty(attributeKey)
        if (!(hasAttributes() && attributes()!!.hasKeyIgnoreCase(attributeKey)))  // not using hasAttr, so that we don't recurse down hasAttr->absUrl
            return ""

        return StringUtil.resolve(baseUri()!!, attributes()!!.getIgnoreCase(attributeKey)!!)
    }

    protected abstract fun ensureChildNodes(): MutableList<Node>

    /**
     * Get a child node by its 0-based index.
     * @param index index of child node
     * @return the child node at this index.
     * @throws IndexOutOfBoundsException if the index is out of bounds.
     */
    fun childNode(index: Int): Node {
        return ensureChildNodes()[index]
    }

    /**
     * Get this node's children. Presented as an unmodifiable list: new children can not be added, but the child nodes
     * themselves can be manipulated.
     * @return list of children. If no children, returns an empty list.
     */
    fun childNodes(): MutableList<Node?> {
        if (childNodeSize() == 0) return EmptyNodes

        val children = ensureChildNodes()
        val rewrap: MutableList<Node?> =
            ArrayList<Node?>(children.size) // wrapped so that looping and moving will not throw a CME as the source changes
        rewrap.addAll(children)
        return Collections.unmodifiableList<Node?>(rewrap)
    }

    /**
     * Returns a deep copy of this node's children. Changes made to these nodes will not be reflected in the original
     * nodes
     * @return a deep copy of this node's children
     */
    fun childNodesCopy(): MutableList<Node?> {
        val nodes = ensureChildNodes()
        val children = ArrayList<Node?>(nodes.size)
        for (node in nodes) {
            children.add(node.clone())
        }
        return children
    }

    /**
     * Get the number of child nodes that this node holds.
     * @return the number of child nodes that this node holds.
     */
    abstract fun childNodeSize(): Int

    protected fun childNodesAsArray(): Array<Node> {
        return ensureChildNodes().toTypedArray<Node>()
    }

    /**
     * Delete all this node's children.
     * @return this node, for chaining
     */
    abstract fun empty(): Node?

    /**
     * Gets this node's parent node. This is always an Element.
     * @return parent node; or null if no parent.
     * @see .hasParent
     * @see .parentElement
     */
    open fun parent(): Node? {
        return parentNode
    }

    /**
     * Gets this node's parent Element.
     * @return parent element; or null if this node has no parent.
     * @see .hasParent
     * @since 1.21.1
     */
    fun parentElement(): Element? {
        return parentNode
    }

    /**
     * Gets this node's parent node. Not overridable by extending classes, so useful if you really just need the Node type.
     * @return parent node; or null if no parent.
     */
    fun parentNode(): Node? {
        return parentNode
    }

    /**
     * Get this node's root node; that is, its topmost ancestor. If this node is the top ancestor, returns `this`.
     * @return topmost ancestor.
     */
    open fun root(): Node? {
        var node: Node? = this
        while (node!!.parentNode != null) node = node.parentNode
        return node
    }

    /**
     * Gets the Document associated with this Node.
     * @return the Document associated with this Node, or null if there is no such Document.
     */
    fun ownerDocument(): Document? {
        var node: Node? = this
        while (node != null) {
            if (node is Document) return node
            node = node.parentNode
        }
        return null
    }

    /**
     * Remove (delete) this node from the DOM tree. If this node has children, they are also removed. If this node is
     * an orphan, nothing happens.
     */
    fun remove() {
        if (parentNode != null) parentNode.removeChild(this)
    }

    /**
     * Insert the specified HTML into the DOM before this node (as a preceding sibling).
     * @param html HTML to add before this node
     * @return this node, for chaining
     * @see .after
     */
    open fun before(html: String): Node? {
        addSiblingHtml(siblingIndex(), html)
        return this
    }

    /**
     * Insert the specified node into the DOM before this node (as a preceding sibling).
     * @param node to add before this node
     * @return this node, for chaining
     * @see .after
     */
    open fun before(node: Node): Node? {
        Validate.notNull(node)
        Validate.notNull(parentNode!!)

        // if the incoming node is a sibling of this, remove it first so siblingIndex is correct on add
        if (node.parentNode === parentNode) node.remove()

        parentNode.addChildren(siblingIndex(), node)
        return this
    }

    /**
     * Insert the specified HTML into the DOM after this node (as a following sibling).
     * @param html HTML to add after this node
     * @return this node, for chaining
     * @see .before
     */
    open fun after(html: String): Node? {
        addSiblingHtml(siblingIndex() + 1, html)
        return this
    }

    /**
     * Insert the specified node into the DOM after this node (as a following sibling).
     * @param node to add after this node
     * @return this node, for chaining
     * @see .before
     */
    open fun after(node: Node): Node? {
        Validate.notNull(node)
        Validate.notNull(parentNode!!)

        // if the incoming node is a sibling of this, remove it first so siblingIndex is correct on add
        if (node.parentNode === parentNode) node.remove()

        parentNode.addChildren(siblingIndex() + 1, node)
        return this
    }

    private fun addSiblingHtml(index: Int, html: String) {
        Validate.notNull(html)
        Validate.notNull(parentNode!!)

        val context = if (parentNode is Element) parentNode as Element else null
        val nodes: MutableList<Node>? =
            NodeUtils.parser(this).parseFragmentInput(html, context, baseUri())
        parentNode.addChildren(index, *nodes!!.toTypedArray<Node?>())
    }

    /**
     * Wrap the supplied HTML around this node.
     *
     * @param html HTML to wrap around this node, e.g. `<div class="head"></div>`. Can be arbitrarily deep. If
     * the input HTML does not parse to a result starting with an Element, this will be a no-op.
     * @return this node, for chaining.
     */
    open fun wrap(html: String): Node? {
        Validate.notEmpty(html)

        // Parse context - parent (because wrapping), this, or null
        val context =
            if (parentNode != null && parentNode is Element) parentNode as Element else if (this is Element) this else null
        val wrapChildren: MutableList<Node>? =
            NodeUtils.parser(this).parseFragmentInput(html, context, baseUri())
        val wrapNode: Node? = wrapChildren!!.get(0)
        if (wrapNode !is Element)  // nothing to wrap with; noop
            return this

        val wrap = wrapNode
        val deepest: Element = getDeepChild(wrap)
        if (parentNode != null) parentNode.replaceChild(this, wrap)
        deepest.addChildren(this) // side effect of tricking wrapChildren to lose first

        // remainder (unbalanced wrap, like <div></div><p></p> -- The <p> is remainder
        if (wrapChildren.size > 0) {
            for (i in wrapChildren.indices) {
                val remainder = wrapChildren.get(i)
                // if no parent, this could be the wrap node, so skip
                if (wrap === remainder) continue

                if (remainder.parentNode != null) remainder.parentNode.removeChild(remainder)
                wrap.after(remainder)
            }
        }
        return this
    }

    /**
     * Removes this node from the DOM, and moves its children up into the node's parent. This has the effect of dropping
     * the node but keeping its children.
     *
     *
     * For example, with the input html:
     *
     *
     * `<div>One <span>Two <b>Three</b></span></div>`
     * Calling `element.unwrap()` on the `span` element will result in the html:
     *
     * `<div>One Two <b>Three</b></div>`
     * and the `"Two "` [TextNode] being returned.
     *
     * @return the first child of this node, after the node has been unwrapped. @{code Null} if the node had no children.
     * @see .remove
     * @see .wrap
     */
    fun unwrap(): Node? {
        Validate.notNull(parentNode!!)
        val firstChild = firstChild()
        parentNode.addChildren(siblingIndex(), *this.childNodesAsArray())
        this.remove()

        return firstChild
    }

    /**
     * Replace this node in the DOM with the supplied node.
     * @param in the node that will replace the existing node.
     */
    fun replaceWith(`in`: Node) {
        Validate.notNull(`in`)
        if (parentNode == null) parentNode =
            `in`.parentNode // allows old to have been temp removed before replacing

        Validate.notNull(parentNode!!)
        parentNode.replaceChild(this, `in`)
    }

    protected fun setParentNode(parentNode: Node) {
        Validate.notNull(parentNode)
        if (this.parentNode != null) this.parentNode.removeChild(this)
        assert(parentNode is Element)
        this.parentNode = parentNode as Element
    }

    protected fun replaceChild(out: Node, `in`: Node) {
        Validate.isTrue(out.parentNode === this)
        Validate.notNull(`in`)
        if (out === `in`) return  // no-op self replacement


        if (`in`.parentNode != null) `in`.parentNode.removeChild(`in`)

        val index = out.siblingIndex()
        ensureChildNodes().set(index, `in`)
        `in`.parentNode = this as Element
        `in`.setSiblingIndex(index)
        out.parentNode = null

        this.childNodes.incrementMod() // as mod count not changed in set(), requires explicit update, to invalidate the child element cache
    }

    open fun removeChild(out: Node) {
        Validate.isTrue(out.parentNode === this)
        val el = this as Element
        if (el.hasValidChildren())  // can remove by index
            ensureChildNodes().removeAt(out.siblingIndex)
        else ensureChildNodes().remove(out) // iterates, but potentially not every one


        el.invalidateChildren()
        out.parentNode = null
    }

    protected fun addChildren(vararg children: Node) {
        //most used. short circuit addChildren(int), which hits reindex children and array copy
        val nodes = ensureChildNodes()

        for (child in children) {
            reparentChild(child)
            nodes.add(child)
            child.setSiblingIndex(nodes.size - 1)
        }
    }

    protected fun addChildren(index: Int, vararg children: Node) {
        // todo clean up all these and use the list, not the var array. just need to be careful when iterating the incoming (as we are removing as we go)
        Validate.notNull(children)
        if (children.size == 0) return
        val nodes = ensureChildNodes()

        // fast path - if used as a wrap (index=0, children = child[0].parent.children - do inplace
        val firstParent = children[0].parent()
        if (firstParent != null && firstParent.childNodeSize() == children.size) {
            var sameList = true
            val firstParentNodes = firstParent.ensureChildNodes()
            // identity check contents to see if same
            var i = children.size
            while (i-- > 0) {
                if (children[i] !== firstParentNodes.get(i)) {
                    sameList = false
                    break
                }
            }
            if (sameList) { // moving, so OK to empty firstParent and short-circuit
                firstParent.empty()
                nodes.addAll(index, Arrays.asList<Node?>(*children))
                i = children.size
                assert(this is Element)
                while (i-- > 0) {
                    children[i].parentNode = this as Element
                }
                (this as Element).invalidateChildren()
                return
            }
        }

        Validate.noNullElements(children)
        for (child in children) {
            reparentChild(child)
        }
        nodes.addAll(index, Arrays.asList<Node?>(*children))
        (this as Element).invalidateChildren()
    }

    protected fun reparentChild(child: Node) {
        child.setParentNode(this)
    }

    /**
     * Retrieves this node's sibling nodes. Similar to [node.parent.childNodes()][.childNodes], but does not
     * include this node (a node is not a sibling of itself).
     * @return node siblings. If the node has no parent, returns an empty list.
     */
    fun siblingNodes(): MutableList<Node?> {
        if (parentNode == null) return mutableListOf<Node?>()

        val nodes = parentNode!!.ensureChildNodes()
        val siblings: MutableList<Node?> = ArrayList<Node?>(nodes.size - 1)
        for (node in nodes) if (node !== this) siblings.add(node)
        return siblings
    }

    /**
     * Get this node's next sibling.
     * @return next sibling, or `null` if this is the last sibling
     */
    fun nextSibling(): Node? {
        if (parentNode == null) return null // root


        val siblings = parentNode!!.ensureChildNodes()
        val index = siblingIndex() + 1
        if (siblings.size > index) {
            val node = siblings.get(index)
            assert(node.siblingIndex == index) // sanity test that invalidations haven't missed
            return node
        } else return null
    }

    /**
     * Get this node's previous sibling.
     * @return the previous sibling, or @{code null} if this is the first sibling
     */
    fun previousSibling(): Node? {
        if (parentNode == null) return null // root


        if (siblingIndex() > 0) return parentNode!!.ensureChildNodes().get(siblingIndex - 1)
        else return null
    }

    /**
     * Get the list index of this node in its node sibling list. E.g. if this is the first node
     * sibling, returns 0.
     * @return position in node sibling list
     * @see Element.elementSiblingIndex
     */
    fun siblingIndex(): Int {
        if (parentNode != null && !parentNode!!.childNodes.validChildren) parentNode!!.reindexChildren()

        return siblingIndex
    }

    fun setSiblingIndex(siblingIndex: Int) {
        this.siblingIndex = siblingIndex
    }

    /**
     * Gets the first child node of this node, or `null` if there is none. This could be any Node type, such as an
     * Element, TextNode, Comment, etc. Use [Element.firstElementChild] to get the first Element child.
     * @return the first child node, or null if there are no children.
     * @see Element.firstElementChild
     * @see .lastChild
     * @since 1.15.2
     */
    fun firstChild(): Node? {
        if (childNodeSize() == 0) return null
        return ensureChildNodes().get(0)
    }

    /**
     * Gets the last child node of this node, or `null` if there is none.
     * @return the last child node, or null if there are no children.
     * @see Element.lastElementChild
     * @see .firstChild
     * @since 1.15.2
     */
    fun lastChild(): Node? {
        val size = childNodeSize()
        if (size == 0) return null
        val children = ensureChildNodes()
        return children.get(size - 1)
    }

    /**
     * Gets the first sibling of this node. That may be this node.
     *
     * @return the first sibling node
     * @since 1.21.1
     */
    fun firstSibling(): Node? {
        if (parentNode != null) {
            return parentNode!!.firstChild()
        } else return this // orphan is its own first sibling
    }

    /**
     * Gets the last sibling of this node. That may be this node.
     *
     * @return the last sibling (aka the parent's last child)
     * @since 1.21.1
     */
    fun lastSibling(): Node? {
        if (parentNode != null) {
            return parentNode!!.lastChild()
        } else return this
    }

    /**
     * Gets the next sibling Element of this node. E.g., if a `div` contains two `p`s, the
     * `nextElementSibling` of the first `p` is the second `p`.
     *
     * This is similar to [.nextSibling], but specifically finds only Elements.
     *
     * @return the next element, or null if there is no next element
     * @see .previousElementSibling
     */
    fun nextElementSibling(): Element? {
        var next: Node? = this
        while ((next!!.nextSibling().also { next = it }) != null) {
            if (next is Element) return next
        }
        return null
    }

    /**
     * Gets the previous Element sibling of this node.
     *
     * @return the previous element, or null if there is no previous element
     * @see .nextElementSibling
     */
    fun previousElementSibling(): Element? {
        var prev: Node? = this
        while ((prev!!.previousSibling().also { prev = it }) != null) {
            if (prev is Element) return prev
        }
        return null
    }

    /**
     * Perform a depth-first traversal through this node and its descendants.
     * @param nodeVisitor the visitor callbacks to perform on each node
     * @return this node, for chaining
     */
    open fun traverse(nodeVisitor: NodeVisitor): Node? {
        Validate.notNull(nodeVisitor)
        nodeVisitor.traverse(this)
        return this
    }

    /**
     * Perform the supplied action on this Node and each of its descendants, during a depth-first traversal. Nodes may be
     * inspected, changed, added, replaced, or removed.
     * @param action the function to perform on the node
     * @return this Node, for chaining
     * @see Element.forEach
     */
    open fun forEachNode(action: Consumer<in Node?>): Node? {
        Validate.notNull(action)
        nodeStream().forEach(action)
        return this
    }

    /**
     * Perform a depth-first controllable traversal through this node and its descendants.
     * @param nodeFilter the filter callbacks to perform on each node
     * @return this node, for chaining
     */
    open fun filter(nodeFilter: NodeFilter): Node? {
        Validate.notNull(nodeFilter)
        nodeFilter.traverse(this)
        return this
    }

    /**
     * Returns a Stream of this Node and all of its descendant Nodes. The stream has document order.
     * @return a stream of all nodes.
     * @see Element.stream
     * @since 1.17.1
     */
    fun nodeStream(): Stream<Node?> {
        return NodeUtils.stream<Node?>(this, Node::class.java)
    }

    /**
     * Returns a Stream of this and descendant nodes, containing only nodes of the specified type. The stream has document
     * order.
     * @return a stream of nodes filtered by type.
     * @see Element.stream
     * @since 1.17.1
     */
    fun <T : Node?> nodeStream(type: Class<T?>?): Stream<T?> {
        return NodeUtils.stream<T?>(this, type)
    }

    /**
     * Get the outer HTML of this node. For example, on a `p` element, may return `<p>Para</p>`.
     * @return outer HTML
     * @see Element.html
     * @see Element.text
     */
    open fun outerHtml(): String? {
        val sb = StringUtil.borrowBuilder()
        outerHtml(QuietAppendable.wrap(sb))
        return StringUtil.releaseBuilder(sb)
    }

    protected fun outerHtml(accum: Appendable) {
        outerHtml(QuietAppendable.wrap(accum))
    }

    protected fun outerHtml(accum: QuietAppendable?) {
        val printer = Printer.printerFor(this, accum)
        printer.traverse(this)
    }

    /**
     * Get the outer HTML of this node.
     *
     * @param accum accumulator to place HTML into
     * @param out
     */
    abstract fun outerHtmlHead(accum: QuietAppendable?, out: Document.OutputSettings?)

    abstract fun outerHtmlTail(accum: QuietAppendable?, out: Document.OutputSettings?)

    /**
     * Write this node and its children to the given [Appendable].
     *
     * @param appendable the [Appendable] to write to.
     * @return the supplied [Appendable], for chaining.
     * @throws io.kapaseker.kharcho.SerializationException if the appendable throws an IOException.
     */
    open fun <T : Appendable?> html(appendable: T?): T? {
        outerHtml(appendable)
        return appendable
    }

    /**
     * Get the source range (start and end positions) in the original input source from which this node was parsed.
     * Position tracking must be enabled prior to parsing the content. For an Element, this will be the positions of the
     * start tag.
     * @return the range for the start of the node, or `untracked` if its range was not tracked.
     * @see io.kapaseker.kharcho.parser.Parser.setTrackPosition
     * @see Range.isImplicit
     * @see Element.endSourceRange
     * @see Attributes.sourceRange
     * @since 1.15.2
     */
    fun sourceRange(): Range? {
        return Range.of(this, true)
    }

    /**
     * Gets this node's outer HTML.
     * @return outer HTML.
     * @see .outerHtml
     */
    override fun toString(): String {
        return outerHtml()!!
    }

    @Deprecated("internal method moved into Printer; will be removed in a future version ")
    @Throws(
        IOException::class
    )
    protected fun indent(accum: Appendable, depth: Int, out: Document.OutputSettings) {
        accum.append('\n')
            .append(StringUtil.padding(depth * out.indentAmount(), out.maxPaddingWidth()))
    }

    /**
     * Check if this node is the same instance of another (object identity test).
     *
     * For a node value equality check, see [.hasSameValue]
     * @param o other object to compare to
     * @return true if the content of this node is the same as the other
     * @see Node.hasSameValue
     */
    override fun equals(o: Any?): Boolean {
        // implemented just so that javadoc is clear this is an identity test
        return this === o
    }

    /**
     * Provides a hashCode for this Node, based on its object identity. Changes to the Node's content will not impact the
     * result.
     * @return an object identity based hashcode for this Node
     */
    override fun hashCode(): Int {
        // implemented so that javadoc and scanners are clear this is an identity test
        return super.hashCode()
    }

    /**
     * Check if this node has the same content as another node. A node is considered the same if its name, attributes and content match the
     * other node; particularly its position in the tree does not influence its similarity.
     * @param o other object to compare to
     * @return true if the content of this node is the same as the other
     */
    fun hasSameValue(o: Any?): Boolean {
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false

        return this.outerHtml() == (o as Node).outerHtml()
    }

    /**
     * Create a stand-alone, deep copy of this node, and all of its children. The cloned node will have no siblings.
     *
     *
     *  * If this node is a [LeafNode], the clone will have no parent.
     *  * If this node is an [Element], the clone will have a simple owning [Document] to retain the
     * configured output settings and parser.
     *
     *
     * The cloned node may be adopted into another Document or node structure using
     * [Element.appendChild].
     *
     * @return a stand-alone cloned node, including clones of any children
     * @see .shallowClone
     */
    public override fun clone(): Node? {
        val thisClone = doClone(null) // splits for orphan

        // Queue up nodes that need their children cloned (BFS).
        val nodesToProcess = LinkedList<Node>()
        nodesToProcess.add(thisClone)

        while (!nodesToProcess.isEmpty()) {
            val currParent = nodesToProcess.remove()

            val size = currParent.childNodeSize()
            for (i in 0..<size) {
                val childNodes = currParent.ensureChildNodes()
                val childClone = childNodes.get(i).doClone(currParent)
                childNodes.set(i, childClone)
                nodesToProcess.add(childClone)
            }
        }

        return thisClone
    }

    /**
     * Create a stand-alone, shallow copy of this node. None of its children (if any) will be cloned, and it will have
     * no parent or sibling nodes.
     * @return a single independent copy of this node
     * @see .clone
     */
    open fun shallowClone(): Node {
        return doClone(null)
    }

    /*
     * Return a clone of the node using the given parent (which can be null).
     * Not a deep copy of children.
     */
    protected open fun doClone(parent: Node?): Node {
        assert(parent == null || parent is Element)
        val clone: Node

        try {
            clone = super.clone() as Node
        } catch (e: CloneNotSupportedException) {
            throw RuntimeException(e)
        }

        clone.parentNode = parent as Element? // can be null, to create an orphan split
        clone.siblingIndex = if (parent == null) 0 else siblingIndex()
        // if not keeping the parent, shallowClone the ownerDocument to preserve its settings
        if (parent == null && this !is Document) {
            val doc = ownerDocument()
            if (doc != null) {
                val docClone = doc.shallowClone()
                clone.parentNode = docClone
                docClone.ensureChildNodes().add(clone)
            }
        }

        return clone
    }

    companion object {
        @JvmField
        val EmptyNodes: MutableList<Node?> = mutableListOf<Node?>()
        const val EmptyString: String = ""
        private fun getDeepChild(el: Element): Element {
            var el = el
            var child = el.firstElementChild()
            while (child != null) {
                el = child
                child = child.firstElementChild()
            }
            return el
        }
    }
}
