package io.kapaseker.kharcho.select

import io.kapaseker.kharcho.helper.Regex
import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.Normalizer
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.nodes.*
import io.kapaseker.kharcho.parser.ParseSettings
import java.util.function.Predicate
import java.util.regex.Pattern

/**
 * An Evaluator tests if an element (or a node) meets the selector's requirements. Obtain an evaluator for a given CSS selector
 * with [Selector.evaluatorOf]. If you are executing the same selector on many elements (or documents), it
 * can be more efficient to compile and reuse an Evaluator than to reparse the selector on each invocation of select().
 *
 * Evaluators are thread-safe and may be used concurrently across multiple documents.
 */
abstract class Evaluator protected constructor() {
    /**
     * Provides a Predicate for this Evaluator, matching the test Element.
     * @param root the root Element, for match evaluation
     * @return a predicate that accepts an Element to test for matches with this Evaluator
     * @since 1.17.1
     */
    fun asPredicate(root: Element?): Predicate<Element?> {
        return Predicate { element: Element? -> matches(root, element) }
    }

    fun asNodePredicate(root: Element?): Predicate<Node?> {
        return Predicate { node: Node? -> matches(root, node) }
    }

    /**
     * Test if the element meets the evaluator's requirements.
     *
     * @param root    Root of the matching subtree
     * @param element tested element
     * @return Returns <tt>true</tt> if the requirements are met or
     * <tt>false</tt> otherwise
     */
    abstract fun matches(root: Element?, element: Element?): Boolean

    fun matches(root: Element?, node: Node?): Boolean {
        if (node is Element) {
            return matches(root, node)
        } else if (node is LeafNode && wantsNodes()) {
            return matches(root, node)
        }
        return false
    }

    open fun matches(root: Element?, leafNode: LeafNode?): Boolean {
        return false
    }

    open fun wantsNodes(): Boolean {
        return false
    }

    /**
     * Reset any internal state in this Evaluator before executing a new Collector evaluation.
     */
    open fun reset() {
    }

    /**
     * A relative evaluator cost function. During evaluation, Evaluators are sorted by ascending cost as an optimization.
     * @return the relative cost of this Evaluator
     */
    open fun cost(): Int {
        return 5 // a nominal default cost
    }

    /**
     * Evaluator for tag name
     */
    class Tag(private val tagName: String?) : Evaluator() {
        override fun matches(root: Element?, element: Element): Boolean {
            return (element.nameIs(tagName))
        }

        protected override fun cost(): Int {
            return 1
        }

        override fun toString(): String {
            return String.format("%s", tagName)
        }
    }

    /**
     * Evaluator for tag name that starts with prefix; used for ns|*
     */
    class TagStartsWith(private val tagName: String) : Evaluator() {
        override fun matches(root: Element?, element: Element): Boolean {
            return (element.normalName().startsWith(tagName))
        }

        override fun toString(): String {
            return String.format("%s|*", tagName)
        }
    }


    /**
     * Evaluator for tag name that ends with suffix; used for *|el
     */
    class TagEndsWith(private val tagName: String) : Evaluator() {
        override fun matches(root: Element?, element: Element): Boolean {
            return (element.normalName().endsWith(tagName))
        }

        override fun toString(): String {
            return String.format("*|%s", tagName)
        }
    }

    /**
     * Evaluator for element id
     */
    class Id(private val id: String) : Evaluator() {
        override fun matches(root: Element?, element: Element): Boolean {
            return (id == element.id())
        }

        protected override fun cost(): Int {
            return 2
        }

        override fun toString(): String {
            return String.format("#%s", id)
        }
    }

    /**
     * Evaluator for element class
     */
    class Class(private val className: String?) : Evaluator() {
        override fun matches(root: Element?, element: Element): Boolean {
            return (element.hasClass(className))
        }

        protected override fun cost(): Int {
            return 8 // does whitespace scanning; more than .contains()
        }

        override fun toString(): String {
            return String.format(".%s", className)
        }
    }

    /**
     * Evaluator for attribute name matching
     */
    class Attribute(private val key: String?) : Evaluator() {
        override fun matches(root: Element?, element: Element): Boolean {
            return element.hasAttr(key)
        }

        protected override fun cost(): Int {
            return 2
        }

        override fun toString(): String {
            return String.format("[%s]", key)
        }
    }

    /**
     * Evaluator for attribute name prefix matching
     */
    class AttributeStarting(keyPrefix: String?) : Evaluator() {
        private val keyPrefix: String

        init {
            Validate.notNull(keyPrefix) // OK to be empty - will find elements with any attributes
            this.keyPrefix = Normalizer.lowerCase(keyPrefix)
        }

        override fun matches(root: Element?, element: Element): Boolean {
            val values = element.attributes().asList()
            for (attribute in values) {
                if (Normalizer.lowerCase(attribute.key).startsWith(keyPrefix)) return true
            }
            return false
        }

        protected override fun cost(): Int {
            return 6
        }

        override fun toString(): String {
            return String.format("[^%s]", keyPrefix)
        }
    }

    /**
     * Evaluator for attribute name/value matching
     */
    class AttributeWithValue(key: String?, value: String?) : AttributeKeyPair(key, value) {
        override fun matches(root: Element?, element: Element): Boolean {
            return element.hasAttr(key) && value.equals(element.attr(key), ignoreCase = true)
        }

        protected override fun cost(): Int {
            return 3
        }

        override fun toString(): String {
            return String.format("[%s=%s]", key, value)
        }
    }

    /**
     * Evaluator for attribute name != value matching
     */
    class AttributeWithValueNot(key: String?, value: String?) : AttributeKeyPair(key, value) {
        override fun matches(root: Element?, element: Element): Boolean {
            return !value.equals(element.attr(key), ignoreCase = true)
        }

        protected override fun cost(): Int {
            return 3
        }

        override fun toString(): String {
            return String.format("[%s!=%s]", key, value)
        }
    }

    /**
     * Evaluator for attribute name/value matching (value prefix)
     */
    class AttributeWithValueStarting(key: String?, value: String?) : AttributeKeyPair(key, value) {
        override fun matches(root: Element?, element: Element): Boolean {
            return element.hasAttr(key) && Normalizer.lowerCase(element.attr(key))
                .startsWith(value) // value is lower case already
        }

        protected override fun cost(): Int {
            return 4
        }

        override fun toString(): String {
            return String.format("[%s^=%s]", key, value)
        }
    }

    /**
     * Evaluator for attribute name/value matching (value ending)
     */
    class AttributeWithValueEnding(key: String?, value: String?) : AttributeKeyPair(key, value) {
        override fun matches(root: Element?, element: Element): Boolean {
            return element.hasAttr(key) && Normalizer.lowerCase(element.attr(key))
                .endsWith(value) // value is lower case
        }

        protected override fun cost(): Int {
            return 4
        }

        override fun toString(): String {
            return String.format("[%s$=%s]", key, value)
        }
    }

    /**
     * Evaluator for attribute name/value matching (value containing)
     */
    class AttributeWithValueContaining(key: String?, value: String?) :
        AttributeKeyPair(key, value) {
        override fun matches(root: Element?, element: Element): Boolean {
            return element.hasAttr(key) && Normalizer.lowerCase(element.attr(key))
                .contains(value) // value is lower case
        }

        protected override fun cost(): Int {
            return 6
        }

        override fun toString(): String {
            return String.format("[%s*=%s]", key, value)
        }
    }

    /**
     * Evaluator for attribute name/value matching (value regex matching)
     */
    class AttributeWithValueMatching(key: String?, val pattern: Regex) : Evaluator() {
        val key: String

        init {
            this.key = Normalizer.normalize(key)
        }

        constructor(key: String?, pattern: Pattern?) : this(
            key,
            Regex.fromPattern(pattern)
        ) // api compat


        override fun matches(root: Element?, element: Element): Boolean {
            return element.hasAttr(key) && pattern.matcher(element.attr(key)).find()
        }

        protected override fun cost(): Int {
            return 8
        }

        override fun toString(): String {
            return String.format("[%s~=%s]", key, pattern.toString())
        }
    }

    /**
     * Abstract evaluator for attribute name/value matching
     */
    abstract class AttributeKeyPair(key: String?, value: String?) : Evaluator() {
        val key: String
        val value: String

        init {
            var value = value
            Validate.notEmpty(key)
            Validate.notNull(value)

            this.key = Normalizer.normalize(key)
            val quoted = value!!.startsWith("'") && value.endsWith("'")
                    || value.startsWith("\"") && value.endsWith("\"")
            if (quoted) {
                Validate.isTrue(value.length > 1, "Quoted value must have content")
                value = value.substring(1, value.length - 1)
            }

            this.value = Normalizer.lowerCase(value) // case-insensitive match
        }

        @Deprecated(
            """since 1.22.1, use {@link #AttributeKeyPair(String, String)}; the previous trimQuoted parameter is no longer used.
         This constructor will be removed in a future release."""
        )
        constructor(key: String?, value: String?, ignored: Boolean) : this(key, value)
    }

    /**
     * Evaluator for any / all element matching
     */
    class AllElements : Evaluator() {
        override fun matches(root: Element?, element: Element?): Boolean {
            return true
        }

        protected override fun cost(): Int {
            return 10
        }

        override fun toString(): String {
            return "*"
        }
    }

    /**
     * Evaluator for matching by sibling index number (e &lt; idx)
     */
    class IndexLessThan(index: Int) : IndexEvaluator(index) {
        override fun matches(root: Element?, element: Element): Boolean {
            return root !== element && element.elementSiblingIndex() < index
        }

        override fun toString(): String {
            return String.format(":lt(%d)", index)
        }
    }

    /**
     * Evaluator for matching by sibling index number (e &gt; idx)
     */
    class IndexGreaterThan(index: Int) : IndexEvaluator(index) {
        override fun matches(root: Element?, element: Element): Boolean {
            return element.elementSiblingIndex() > index
        }

        override fun toString(): String {
            return String.format(":gt(%d)", index)
        }
    }

    /**
     * Evaluator for matching by sibling index number (e = idx)
     */
    class IndexEquals(index: Int) : IndexEvaluator(index) {
        override fun matches(root: Element?, element: Element): Boolean {
            return element.elementSiblingIndex() == index
        }

        override fun toString(): String {
            return String.format(":eq(%d)", index)
        }
    }

    /**
     * Evaluator for matching the last sibling (css :last-child)
     */
    class IsLastChild : Evaluator() {
        override fun matches(root: Element?, element: Element): Boolean {
            val p = element.parent()
            return p != null && (p !is Document) && element === p.lastElementChild()
        }

        override fun toString(): String {
            return ":last-child"
        }
    }

    class IsFirstOfType : IsNthOfType(0, 1) {
        override fun toString(): String {
            return ":first-of-type"
        }
    }

    class IsLastOfType : IsNthLastOfType(0, 1) {
        override fun toString(): String {
            return ":last-of-type"
        }
    }


    abstract class CssNthEvaluator(
        /** Step  */
        protected val a: Int,
        /** Offset  */
        protected val b: Int
    ) : Evaluator() {
        constructor(offset: Int) : this(0, offset)

        override fun matches(root: Element?, element: Element): Boolean {
            val p = element.parent()
            if (p == null || (p is Document)) return false

            val pos = calculatePosition(root, element)
            if (a == 0) return pos == b

            return (pos - b) * a >= 0 && (pos - b) % a == 0
        }

        override fun toString(): String {
            val format =
                if (a == 0)
                    ":%s(%3\$d)" // only offset (b)
                else
                    if (b == 0)
                        ":%s(%2\$dn)" // only step (a)
                    else
                        ":%s(%2\$dn%3$+d)" // step, offset
            return String.format(format, this.pseudoClass, a, b)
        }

        protected abstract val pseudoClass: String?

        protected abstract fun calculatePosition(root: Element?, element: Element?): Int
    }


    /**
     * css-compatible Evaluator for :eq (css :nth-child)
     *
     * @see IndexEquals
     */
    class IsNthChild(step: Int, offset: Int) : CssNthEvaluator(step, offset) {
        override fun calculatePosition(root: Element?, element: Element): Int {
            return element.elementSiblingIndex() + 1
        }

        override fun getPseudoClass(): String {
            return "nth-child"
        }
    }

    /**
     * css pseudo class :nth-last-child)
     *
     * @see IndexEquals
     */
    class IsNthLastChild(step: Int, offset: Int) : CssNthEvaluator(step, offset) {
        override fun calculatePosition(root: Element?, element: Element): Int {
            if (element.parent() == null) return 0
            return element.parent().childrenSize() - element.elementSiblingIndex()
        }

        override fun getPseudoClass(): String {
            return "nth-last-child"
        }
    }

    /**
     * css pseudo class nth-of-type
     *
     */
    open class IsNthOfType(step: Int, offset: Int) : CssNthEvaluator(step, offset) {
        override fun calculatePosition(root: Element?, element: Element): Int {
            val parent = element.parent()
            if (parent == null) return 0

            var pos = 0
            val size = parent.childNodeSize()
            for (i in 0..<size) {
                val node = parent.childNode(i)
                if (node.normalName() == element.normalName()) pos++
                if (node === element) break
            }
            return pos
        }

        override fun getPseudoClass(): String {
            return "nth-of-type"
        }
    }

    open class IsNthLastOfType(step: Int, offset: Int) : CssNthEvaluator(step, offset) {
        override fun calculatePosition(root: Element?, element: Element): Int {
            val parent = element.parent()
            if (parent == null) return 0

            var pos = 0
            var next: Element? = element
            while (next != null) {
                if (next.normalName() == element.normalName()) pos++
                next = next.nextElementSibling()
            }
            return pos
        }

        override fun getPseudoClass(): String {
            return "nth-last-of-type"
        }
    }

    /**
     * Evaluator for matching the first sibling (css :first-child)
     */
    class IsFirstChild : Evaluator() {
        override fun matches(root: Element?, element: Element): Boolean {
            val p = element.parent()
            return p != null && (p !is Document) && element === p.firstElementChild()
        }

        override fun toString(): String {
            return ":first-child"
        }
    }

    /**
     * css3 pseudo-class :root
     * @see [:root selector](http://www.w3.org/TR/selectors/.root-pseudo)
     */
    class IsRoot : Evaluator() {
        override fun matches(root: Element?, element: Element?): Boolean {
            val r = if (root is Document) root.firstElementChild() else root
            return element === r
        }

        protected override fun cost(): Int {
            return 1
        }

        override fun toString(): String {
            return ":root"
        }
    }

    class IsOnlyChild : Evaluator() {
        override fun matches(root: Element?, element: Element): Boolean {
            val p = element.parent()
            return p != null && (p !is Document) && element.siblingElements().isEmpty()
        }

        override fun toString(): String {
            return ":only-child"
        }
    }

    class IsOnlyOfType : Evaluator() {
        override fun matches(root: Element?, element: Element): Boolean {
            val p = element.parent()
            if (p == null || p is Document) return false

            var pos = 0
            var next = p.firstElementChild()
            while (next != null) {
                if (next.normalName() == element.normalName()) pos++
                if (pos > 1) break
                next = next.nextElementSibling()
            }
            return pos == 1
        }

        override fun toString(): String {
            return ":only-of-type"
        }
    }

    class IsEmpty : Evaluator() {
        override fun matches(root: Element?, el: Element): Boolean {
            var n = el.firstChild()
            while (n != null) {
                if (n is TextNode) {
                    if (!n.isBlank()) return false // non-blank text: not empty
                } else if (!(n is Comment || n is XmlDeclaration || n is DocumentType)) return false // non "blank" element: not empty

                n = n.nextSibling()
            }
            return true
        }

        override fun toString(): String {
            return ":empty"
        }
    }

    /**
     * Abstract evaluator for sibling index matching
     *
     * @author ant
     */
    abstract class IndexEvaluator(val index: Int) : Evaluator()

    /**
     * Evaluator for matching Element (and its descendants) text
     */
    class ContainsText(searchText: String?) : Evaluator() {
        private val searchText: String

        init {
            this.searchText = Normalizer.lowerCase(StringUtil.normaliseWhitespace(searchText))
        }

        override fun matches(root: Element?, element: Element): Boolean {
            return Normalizer.lowerCase(element.text()).contains(searchText)
        }

        protected override fun cost(): Int {
            return 10
        }

        override fun toString(): String {
            return String.format(":contains(%s)", searchText)
        }
    }

    /**
     * Evaluator for matching Element (and its descendants) wholeText. Neither the input nor the element text is
     * normalized. `:containsWholeText()`
     * @since 1.15.1.
     */
    class ContainsWholeText(private val searchText: String) : Evaluator() {
        override fun matches(root: Element?, element: Element): Boolean {
            return element.wholeText().contains(searchText)
        }

        protected override fun cost(): Int {
            return 10
        }

        override fun toString(): String {
            return String.format(":containsWholeText(%s)", searchText)
        }
    }

    /**
     * Evaluator for matching Element (but **not** its descendants) wholeText. Neither the input nor the element text is
     * normalized. `:containsWholeOwnText()`
     * @since 1.15.1.
     */
    class ContainsWholeOwnText(private val searchText: String) : Evaluator() {
        override fun matches(root: Element?, element: Element): Boolean {
            return element.wholeOwnText().contains(searchText)
        }

        override fun toString(): String {
            return String.format(":containsWholeOwnText(%s)", searchText)
        }
    }

    /**
     * Evaluator for matching Element (and its descendants) data
     */
    class ContainsData(searchText: String?) : Evaluator() {
        private val searchText: String

        init {
            this.searchText = Normalizer.lowerCase(searchText)
        }

        override fun matches(root: Element?, element: Element): Boolean {
            return Normalizer.lowerCase(element.data())
                .contains(searchText) // not whitespace normalized
        }

        override fun toString(): String {
            return String.format(":containsData(%s)", searchText)
        }
    }

    /**
     * Evaluator for matching Element's own text
     */
    class ContainsOwnText(searchText: String?) : Evaluator() {
        private val searchText: String

        init {
            this.searchText = Normalizer.lowerCase(StringUtil.normaliseWhitespace(searchText))
        }

        override fun matches(root: Element?, element: Element): Boolean {
            return Normalizer.lowerCase(element.ownText()).contains(searchText)
        }

        override fun toString(): String {
            return String.format(":containsOwn(%s)", searchText)
        }
    }

    /**
     * Evaluator for matching Element (and its descendants) text with regex
     */
    class Matches(private val pattern: Regex) : Evaluator() {
        constructor(pattern: Pattern?) : this(Regex.fromPattern(pattern))

        override fun matches(root: Element?, element: Element): Boolean {
            return pattern.matcher(element.text()).find()
        }

        protected override fun cost(): Int {
            return 8
        }

        override fun toString(): String {
            return String.format(":matches(%s)", pattern)
        }
    }

    /**
     * Evaluator for matching Element's own text with regex
     */
    class MatchesOwn(private val pattern: Regex) : Evaluator() {
        constructor(pattern: Pattern?) : this(Regex.fromPattern(pattern))

        override fun matches(root: Element?, element: Element): Boolean {
            return pattern.matcher(element.ownText()).find()
        }

        protected override fun cost(): Int {
            return 7
        }

        override fun toString(): String {
            return String.format(":matchesOwn(%s)", pattern)
        }
    }

    /**
     * Evaluator for matching Element (and its descendants) whole text with regex.
     * @since 1.15.1.
     */
    class MatchesWholeText : Evaluator {
        private val pattern: Regex

        constructor(pattern: Regex) {
            this.pattern = pattern
        }

        constructor(pattern: Pattern?) {
            this.pattern = Regex.fromPattern(pattern)
        }

        override fun matches(root: Element?, element: Element): Boolean {
            return pattern.matcher(element.wholeText()).find()
        }

        protected override fun cost(): Int {
            return 8
        }

        override fun toString(): String {
            return String.format(":matchesWholeText(%s)", pattern)
        }
    }

    /**
     * Evaluator for matching Element's own whole text with regex.
     * @since 1.15.1.
     */
    class MatchesWholeOwnText(private val pattern: Regex) : Evaluator() {
        constructor(pattern: Pattern?) : this(Regex.fromPattern(pattern))

        override fun matches(root: Element?, element: Element): Boolean {
            val m = pattern.matcher(element.wholeOwnText())
            return m.find()
        }

        protected override fun cost(): Int {
            return 7
        }

        override fun toString(): String {
            return String.format(":matchesWholeOwnText(%s)", pattern)
        }
    }

    @Deprecated("This selector is deprecated and will be removed in a future version. Migrate to <code>::textnode</code> using the <code>Element#selectNodes()</code> method instead.")
    class MatchText : Evaluator() {
        init {
            // log a deprecated error on first use; users typically won't directly construct this Evaluator and so won't otherwise get deprecation warnings
            if (!loggedError) {
                loggedError = true
                System.err.println("WARNING: :matchText selector is deprecated and will be removed in a future version. Use Element#selectNodes(String, Class) with selector ::textnode and class TextNode instead.")
            }
        }

        override fun matches(root: Element?, element: Element): Boolean {
            if (element is PseudoTextElement) return true

            val textNodes = element.textNodes()
            for (textNode in textNodes) {
                val pel = PseudoTextElement(
                    io.kapaseker.kharcho.parser.Tag.valueOf(
                        element.tagName(),
                        element.tag().namespace(),
                        ParseSettings.preserveCase
                    ), element.baseUri(), element.attributes()
                )
                textNode.replaceWith(pel)
                pel.appendChild(textNode)
            }
            return false
        }

        protected override fun cost(): Int {
            return -1 // forces first evaluation, which prepares the DOM for later evaluator matches
        }

        override fun toString(): String {
            return ":matchText"
        }

        companion object {
            private var loggedError = false
        }
    }
}
