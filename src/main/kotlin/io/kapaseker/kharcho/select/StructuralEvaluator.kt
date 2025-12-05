package io.kapaseker.kharcho.select

import io.kapaseker.kharcho.internal.SoftPool
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.nodes.*
import java.util.*
import java.util.function.Supplier

/**
 * Base structural evaluator.
 */
internal abstract class StructuralEvaluator(val evaluator: Evaluator) : Evaluator() {
    var wantsNodes: Boolean // if the evaluator requested nodes, not just elements

    override fun wantsNodes(): Boolean {
        return wantsNodes
    }

    // Memoize inner matches, to save repeated re-evaluations of parent, sibling etc.
    // root + element: Boolean matches. ThreadLocal in case the Evaluator is compiled then reused across multi threads
    val threadMemo: ThreadLocal<MutableMap<Node?, MutableMap<Node?, Boolean?>>> =
        ThreadLocal.withInitial<MutableMap<Node?, MutableMap<Node?, Boolean?>?>?>(
            Supplier { WeakHashMap() })

    init {
        wantsNodes = evaluator.wantsNodes()
    }

    fun memoMatches(root: Element?, node: Node?): Boolean {
        val rootMemo = threadMemo.get()
        val memo = rootMemo.computeIfAbsent(root) { r: Node? -> WeakHashMap<Node?, Boolean?>() }
        return memo.computeIfAbsent(node) { test: io.kapaseker.kharcho.nodes.Node? ->
            evaluator.matches(
                root,
                test
            )
        }!!
    }

    protected override fun reset() {
        threadMemo.remove()
        evaluator.reset()
        super.reset()
    }

    override fun matches(root: Element?, element: Element?): Boolean {
        return evaluateMatch(root, element)
    }

    override fun matches(root: Element?, leafNode: LeafNode?): Boolean {
        return evaluateMatch(root, leafNode)
    }

    abstract fun evaluateMatch(root: Element?, node: Node?): Boolean

    internal class Root : Evaluator() {
        override fun matches(root: Element?, element: Element?): Boolean {
            return root === element
        }

        protected override fun cost(): Int {
            return 1
        }

        override fun toString(): String {
            return ">"
        }
    }

    internal class Has(evaluator: Evaluator) : StructuralEvaluator(evaluator) {
        // the element here is just a placeholder so this can be final - gets set in restart()
        private val checkSiblings: Boolean // evaluating against siblings (or children)

        init {
            checkSiblings = evalWantsSiblings(evaluator)
        }

        override fun matches(root: Element?, element: Element): Boolean {
            if (checkSiblings) { // evaluating against siblings
                var sib = element.firstElementSibling()
                while (sib != null) {
                    if (sib !== element && evaluator.matches(
                            element,
                            sib
                        )
                    ) { // don't match against self
                        return true
                    }
                    sib = sib.nextElementSibling()
                }
            }
            // otherwise we only want to match children (or below), and not the input element. And we want to minimize GCs so reusing the Iterator obj
            val it: NodeIterator<Node?> = NodeIterPool.borrow()
            it.restart(element)
            try {
                while (it.hasNext()) {
                    val node = it.next()
                    if (node === element) continue  // don't match self, only descendants

                    if (evaluator.matches(element, node)) {
                        return true
                    }
                }
            } finally {
                NodeIterPool.release(it)
            }
            return false
        }

        override fun evaluateMatch(root: Element?, node: Node?): Boolean {
            return false // unused; :has(::comment)) goes via implicit root combinator
        }

        protected override fun cost(): Int {
            return 10 * evaluator.cost()
        }

        override fun toString(): String {
            return String.format(":has(%s)", evaluator)
        }

        companion object {
            val NodeIterPool: SoftPool<NodeIterator<Node?>> =
                SoftPool<NodeIterator<Node?>>(Supplier {
                    NodeIterator<Node?>(
                        TextNode(""), Node::class.java
                    )
                })

            /* Test if the :has sub-clause wants sibling elements (vs nested elements) - will be a Combining eval */
            private fun evalWantsSiblings(eval: Evaluator?): Boolean {
                if (eval is CombiningEvaluator) {
                    val ce = eval
                    for (innerEval in ce.evaluators) {
                        if (innerEval is PreviousSibling || innerEval is ImmediatePreviousSibling) return true
                    }
                }
                return false
            }
        }
    }

    /** Implements the :is(sub-query) pseudo-selector  */
    internal class Is(evaluator: Evaluator) : StructuralEvaluator(evaluator) {
        override fun evaluateMatch(root: Element?, node: Node?): Boolean {
            return evaluator.matches(root, node)
        }

        protected override fun cost(): Int {
            return 2 + evaluator.cost()
        }

        override fun toString(): String {
            return String.format(":is(%s)", evaluator)
        }
    }

    internal class Not(evaluator: Evaluator) : StructuralEvaluator(evaluator) {
        override fun evaluateMatch(root: Element?, node: Node?): Boolean {
            return !memoMatches(root, node)
        }

        protected override fun cost(): Int {
            return 2 + evaluator.cost()
        }

        override fun toString(): String {
            return String.format(":not(%s)", evaluator)
        }
    }

    /**
     * Any Ancestor (i.e., ascending parent chain.).
     */
    internal class Ancestor(evaluator: Evaluator) : StructuralEvaluator(evaluator) {
        override fun evaluateMatch(root: Element?, node: Node): Boolean {
            if (root === node) return false

            var parent = node.parent()
            while (parent != null) {
                if (memoMatches(root, parent)) return true
                if (parent === root) break
                parent = parent.parent()
            }
            return false
        }

        protected override fun cost(): Int {
            return 8 * evaluator.cost() // probably lower than has(), but still significant, depending on doc and el depth.
        }

        override fun toString(): String {
            return String.format("%s ", evaluator)
        }
    }

    /**
     * Holds a list of evaluators for one > two > three immediate parent matches, and the final direct evaluator under
     * test. To match, these are effectively ANDed together, starting from the last, matching up to the first.
     */
    internal class ImmediateParentRun(evaluator: Evaluator) : StructuralEvaluator(evaluator) {
        val evaluators: ArrayList<Evaluator> = ArrayList<Evaluator>()
        var cost: Int = 2

        init {
            evaluators.add(evaluator)
            cost += evaluator.cost()
        }

        fun add(evaluator: Evaluator) {
            evaluators.add(evaluator)
            cost += evaluator.cost()
            wantsNodes = wantsNodes or evaluator.wantsNodes()
        }

        override fun evaluateMatch(root: Element?, node: Node?): Boolean {
            var node = node
            if (node === root) return false // cannot match as the second eval (first parent test) would be above the root


            for (i in evaluators.indices.reversed()) {
                if (node == null) return false
                val eval = evaluators.get(i)
                if (!eval.matches(root, node)) return false
                node = node.parent()
            }
            return true
        }

        protected override fun cost(): Int {
            return cost
        }

        override fun reset() {
            for (evaluator in evaluators) {
                evaluator.reset()
            }
            super.reset()
        }

        override fun toString(): String {
            return StringUtil.join(evaluators, " > ")
        }
    }

    internal class PreviousSibling(evaluator: Evaluator) : StructuralEvaluator(evaluator) {
        // matches any previous sibling, so can be same in Element only or wantsNodes context
        override fun evaluateMatch(root: Element?, node: Node): Boolean {
            if (root === node) return false

            var sib = node.firstSibling()
            while (sib != null) {
                if (sib === node) break
                if (memoMatches(root, sib)) return true
                sib = sib.nextSibling()
            }

            return false
        }

        protected override fun cost(): Int {
            return 3 * evaluator.cost()
        }

        override fun toString(): String {
            return String.format("%s ~ ", evaluator)
        }
    }

    internal class ImmediatePreviousSibling(evaluator: Evaluator) : StructuralEvaluator(evaluator) {
        override fun evaluateMatch(root: Element?, node: Node): Boolean {
            if (root === node) return false

            val prev = if (wantsNodes) node.previousSibling() else node.previousElementSibling()
            return prev != null && memoMatches(root, prev)
        }

        protected override fun cost(): Int {
            return 2 + evaluator.cost()
        }

        override fun toString(): String {
            return String.format("%s + ", evaluator)
        }
    }
}
