package io.kapaseker.kharcho.select

import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.nodes.Element
import io.kapaseker.kharcho.nodes.LeafNode
import java.util.*
import java.util.function.ToIntFunction

/**
 * Base combining (and, or) evaluator.
 */
abstract class CombiningEvaluator internal constructor() : Evaluator() {
    val evaluators: ArrayList<Evaluator> // maintain original order so that #toString() is sensible
    val sortedEvaluators: MutableList<Evaluator> // cost ascending order
    var num: Int = 0
    var cost: Int = 0
    var wantsNodes: Boolean = false

    init {
        evaluators = ArrayList<Evaluator>()
        sortedEvaluators = ArrayList<Evaluator>()
    }

    internal constructor(evaluators: MutableCollection<Evaluator?>?) : this() {
        this.evaluators.addAll(evaluators)
        updateEvaluators()
    }

    fun add(e: Evaluator?) {
        evaluators.add(e!!)
        updateEvaluators()
    }

    protected override fun reset() {
        for (evaluator in evaluators) {
            evaluator.reset()
        }
        super.reset()
    }

    protected override fun cost(): Int {
        return cost
    }

    override fun wantsNodes(): Boolean {
        return wantsNodes
    }

    fun updateEvaluators() {
        // used so we don't need to bash on size() for every match test
        num = evaluators.size

        // sort the evaluators by lowest cost first, to optimize the evaluation order
        cost = 0
        for (evaluator in evaluators) {
            cost += evaluator.cost()
        }
        sortedEvaluators.clear()
        sortedEvaluators.addAll(evaluators)
        sortedEvaluators.sort(Comparator.comparingInt<Evaluator?>(ToIntFunction { obj: Evaluator? -> obj!!.cost() }))

        // any want nodes?
        for (evaluator in evaluators) {
            if (evaluator.wantsNodes()) {
                wantsNodes = true
                break
            }
        }
    }

    class And(evaluators: MutableCollection<Evaluator?>?) : CombiningEvaluator(evaluators) {
        internal constructor(vararg evaluators: Evaluator?) : this(Arrays.asList<Evaluator?>(*evaluators))

        override fun matches(root: Element?, el: Element?): Boolean {
            for (i in 0..<num) {
                val eval = sortedEvaluators.get(i)
                if (!eval.matches(root, el)) return false
            }
            return true
        }

        public override fun matches(root: Element?, leaf: LeafNode?): Boolean {
            for (i in 0..<num) {
                val eval = sortedEvaluators.get(i)
                if (!eval.matches(root, leaf)) return false
            }
            return true
        }

        override fun toString(): String {
            return StringUtil.join(evaluators, "")
        }
    }

    class Or : CombiningEvaluator {
        /**
         * Create a new Or evaluator. The initial evaluators are ANDed together and used as the first clause of the OR.
         * @param evaluators initial OR clause (these are wrapped into an AND evaluator).
         */
        constructor(evaluators: MutableCollection<Evaluator?>?) : super() {
            if (num > 1) this.evaluators.add(And(evaluators))
            else  // 0 or 1
                this.evaluators.addAll(evaluators)
            updateEvaluators()
        }

        internal constructor(vararg evaluators: Evaluator?) : this(Arrays.asList<Evaluator?>(*evaluators))

        internal constructor() : super()

        override fun matches(root: Element?, element: Element?): Boolean {
            for (i in 0..<num) {
                val eval = sortedEvaluators.get(i)
                if (eval.matches(root, element)) return true
            }
            return false
        }

        public override fun matches(root: Element?, leaf: LeafNode?): Boolean {
            for (i in 0..<num) {
                val eval = sortedEvaluators.get(i)
                if (eval.matches(root, leaf)) return true
            }
            return false
        }

        override fun toString(): String {
            return StringUtil.join(evaluators, ", ")
        }
    }
}
