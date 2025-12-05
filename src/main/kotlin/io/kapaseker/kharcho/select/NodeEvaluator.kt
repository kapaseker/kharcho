package io.kapaseker.kharcho.select

import io.kapaseker.kharcho.helper.Regex
import io.kapaseker.kharcho.internal.Normalizer
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.nodes.Element
import io.kapaseker.kharcho.nodes.LeafNode
import io.kapaseker.kharcho.nodes.Node

internal abstract class NodeEvaluator : Evaluator() {
    override fun matches(root: Element?, element: Element?): Boolean {
        return evaluateMatch(element)
    }

    override fun matches(root: Element?, leaf: LeafNode?): Boolean {
        return evaluateMatch(leaf)
    }

    abstract fun evaluateMatch(node: Node?): Boolean

    override fun wantsNodes(): Boolean {
        return true
    }

    internal class InstanceType(val type: Class<out Node?>, selector: String) : NodeEvaluator() {
        val selector: String

        init {
            this.selector = "::" + selector
        }

        override fun evaluateMatch(node: Node?): Boolean {
            return type.isInstance(node)
        }

        protected override fun cost(): Int {
            return 1
        }

        override fun toString(): String {
            return selector
        }
    }

    internal class ContainsValue(searchText: String?) : NodeEvaluator() {
        private val searchText: String

        init {
            this.searchText = Normalizer.lowerCase(StringUtil.normaliseWhitespace(searchText))
        }

        override fun evaluateMatch(node: Node): Boolean {
            return Normalizer.lowerCase(node.nodeValue()).contains(searchText)
        }

        protected override fun cost(): Int {
            return 6
        }

        override fun toString(): String {
            return String.format(":contains(%s)", searchText)
        }
    }

    /**
     * Matches nodes with no value or only whitespace.
     */
    internal class BlankValue : NodeEvaluator() {
        override fun evaluateMatch(node: Node): Boolean {
            return StringUtil.isBlank(node.nodeValue())
        }

        protected override fun cost(): Int {
            return 4
        }

        override fun toString(): String {
            return ":blank"
        }
    }

    internal class MatchesValue(private val pattern: Regex) : NodeEvaluator() {
        override fun evaluateMatch(node: Node): Boolean {
            return pattern.matcher(node.nodeValue()).find()
        }

        protected override fun cost(): Int {
            return 8
        }

        override fun toString(): String {
            return String.format(":matches(%s)", pattern)
        }
    }
}
