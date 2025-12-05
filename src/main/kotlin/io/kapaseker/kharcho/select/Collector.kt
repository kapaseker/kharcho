package io.kapaseker.kharcho.select

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.nodes.Element
import io.kapaseker.kharcho.nodes.Node
import java.util.function.Supplier
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * Collects a list of elements that match the supplied criteria.
 *
 * @author Jonathan Hedley
 */
object Collector {
    /**
     * Build a list of elements, by visiting the root and every descendant of root, and testing it against the Evaluator.
     * @param eval Evaluator to test elements against
     * @param root root of tree to descend
     * @return list of matches; empty if none
     */
    @JvmStatic
    fun collect(eval: Evaluator, root: Element): Elements {
        val stream = if (eval.wantsNodes()) Collector.streamNodes<Element?>(
            eval,
            root,
            Element::class.java
        ) else stream(eval, root)
        val els = stream.collect(Collectors.toCollection(Supplier { Elements() }))
        eval.reset() // drops any held memos
        return els
    }

    /**
     * Obtain a Stream of elements by visiting the root and every descendant of root and testing it against the evaluator.
     *
     * @param evaluator Evaluator to test elements against
     * @param root root of tree to descend
     * @return A [Stream] of matches
     * @since 1.19.1
     */
    fun stream(evaluator: Evaluator, root: Element): Stream<Element?> {
        evaluator.reset()
        return root.stream().filter(evaluator.asPredicate(root))
    }

    /**
     * Obtain a Stream of nodes, of the specified type, by visiting the root and every descendant of root and testing it
     * against the evaluator.
     *
     * @param evaluator Evaluator to test elements against
     * @param root root of tree to descend
     * @param type the type of node to collect (e.g. [Element], [LeafNode], [TextNode] etc)
     * @param <T> the type of node to collect
     * @return A [Stream] of matches
     * @since 1.21.1
    </T> */
    fun <T : Node?> streamNodes(evaluator: Evaluator, root: Element, type: Class<T?>?): Stream<T?> {
        evaluator.reset()
        return root.nodeStream<T?>(type).filter(evaluator.asNodePredicate(root))
    }

    /**
     * Finds the first Element that matches the Evaluator that descends from the root, and stops the query once that first
     * match is found.
     * @param eval Evaluator to test elements against
     * @param root root of tree to descend
     * @return the first match; `null` if none
     */
    @JvmStatic
    fun findFirst(eval: Evaluator, root: Element): @Nullable Element? {
        val el = stream(eval, root).findFirst().orElse(null)
        eval.reset()
        return el
    }

    /**
     * Finds the first Node that matches the Evaluator that descends from the root, and stops the query once that first
     * match is found.
     *
     * @param eval Evaluator to test elements against
     * @param root root of tree to descend
     * @param type the type of node to collect (e.g. [Element], [LeafNode], [TextNode] etc)
     * @return the first match; `null` if none
     * @since 1.21.1
     */
    fun <T : Node?> findFirstNode(eval: Evaluator, root: Element, type: Class<T?>?): @Nullable T? {
        val node = streamNodes<T?>(eval, root, type).findFirst().orElse(null)
        eval.reset()
        return node
    }

    /**
     * Build a list of nodes that match the supplied criteria, by visiting the root and every descendant of root, and
     * testing it against the Evaluator.
     *
     * @param evaluator Evaluator to test elements against
     * @param root root of tree to descend
     * @param type the type of node to collect (e.g. [Element], [LeafNode], [TextNode] etc)
     * @param <T> the type of node to collect
     * @return list of matches; empty if none
    </T> */
    fun <T : Node?> collectNodes(evaluator: Evaluator, root: Element, type: Class<T?>?): Nodes<T?> {
        return streamNodes<T?>(
            evaluator,
            root,
            type
        ).collect(Collectors.toCollection(Supplier { Nodes() }))
    }
}
