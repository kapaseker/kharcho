package io.kapaseker.kharcho.select

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.helper.Regex
import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.Normalizer
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.nodes.*
import io.kapaseker.kharcho.parser.TokenQueue
import io.kapaseker.kharcho.select.Evaluator.TagStartsWith
import io.kapaseker.kharcho.select.NodeEvaluator.*
import io.kapaseker.kharcho.select.StructuralEvaluator.Ancestor
import io.kapaseker.kharcho.select.StructuralEvaluator.ImmediateParentRun
import java.lang.AutoCloseable
import java.util.function.Function
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Parses a CSS selector into an Evaluator tree.
 */
class QueryParser private constructor(query: String) : AutoCloseable {
    private val tq: TokenQueue
    private val query: String
    private var inNodeContext =
        false // ::comment:contains should act on node value, vs element text

    /**
     * Parse the query. We use this simplified expression of the grammar:
     * <pre>
     * SelectorGroup   ::= Selector (',' Selector)*
     * Selector        ::= [ Combinator ] SimpleSequence ( Combinator SimpleSequence )*
     * SimpleSequence  ::= [ TypeSelector ] ( ID | Class | Attribute | Pseudo )*
     * Pseudo           ::= ':' Name [ '(' SelectorGroup ')' ]
     * Combinator      ::= S+         // descendant (whitespace)
     * | '>'       // child
     * | '+'       // adjacent sibling
     * | '~'       // general sibling
    </pre> *
     *
     * See [selectors-4](https://www.w3.org/TR/selectors-4/#grammar) for the real thing
     */
    fun parse(): Evaluator {
        val eval = parseSelectorGroup()
        tq.consumeWhitespace()
        if (!tq.isEmpty()) throw Selector.SelectorParseException(
            "Could not parse query '%s': unexpected token at '%s'",
            query,
            tq.remainder()
        )
        return eval
    }

    fun parseSelectorGroup(): Evaluator {
        // SelectorGroup. Into an Or if > 1 Selector
        var left = parseSelector()
        while (tq.matchChomp(',')) {
            val right = parseSelector()
            left = or(left, right)
        }
        return left
    }

    fun parseSelector(): Evaluator {
        // Selector ::= [ Combinator ] SimpleSequence ( Combinator SimpleSequence )*
        tq.consumeWhitespace()

        var left: Evaluator
        if (tq.matchesAny(*Combinators)) {
            // e.g. query is "> div"; left side is root element
            left = StructuralEvaluator.Root()
        } else {
            left = parseSimpleSequence()
        }

        while (true) {
            var combinator = 0.toChar()
            if (tq.consumeWhitespace()) combinator = ' ' // maybe descendant?

            if (tq.matchesAny(*Combinators))  // no, explicit
                combinator = tq.consume()
            else if (tq.matchesAny(*SequenceEnders))  // , - space after simple like "foo , bar"; ) - close of :has()
                break

            if (combinator.code != 0) {
                val right = parseSimpleSequence()
                left = combinator(left, combinator, right)
            } else {
                break
            }
        }
        return left
    }

    fun parseSimpleSequence(): Evaluator {
        // SimpleSequence ::= TypeSelector? ( Hash | Class | Pseudo )*
        var left: Evaluator? = null
        tq.consumeWhitespace()

        // one optional type selector
        if (tq.matchesWord() || tq.matches("*|")) left = byTag()
        else if (tq.matchChomp('*')) left = Evaluator.AllElements()

        // zero or more subclasses (#, ., [)
        while (true) {
            val right = parseSubclass()
            if (right != null) {
                left = and(left, right)
            } else break // no more simple tokens
        }

        if (left == null) throw Selector.SelectorParseException(
            "Could not parse query '%s': unexpected token at '%s'",
            query,
            tq.remainder()
        )
        return left
    }

    fun parseSubclass(): @Nullable Evaluator? {
        //  Subclass: ID | Class | Attribute | Pseudo
        if (tq.matchChomp('#')) return byId()
        else if (tq.matchChomp('.')) return byClass()
        else if (tq.matches('[')) return byAttribute()
        else if (tq.matchChomp("::")) return parseNodeSelector() // ::comment etc
        else if (tq.matchChomp(':')) return parsePseudoSelector()
        else return null
    }

    private fun parsePseudoSelector(): Evaluator? {
        val pseudo = tq.consumeCssIdentifier()
        when (pseudo) {
            "lt" -> return Evaluator.IndexLessThan(consumeIndex())
            "gt" -> return Evaluator.IndexGreaterThan(consumeIndex())
            "eq" -> return Evaluator.IndexEquals(consumeIndex())
            "has" -> return has()
            "is" -> return `is`()
            "contains" -> return contains(false)
            "containsOwn" -> return contains(true)
            "containsWholeText" -> return containsWholeText(false)
            "containsWholeOwnText" -> return containsWholeText(true)
            "containsData" -> return containsData()
            "matches" -> return matches(false)
            "matchesOwn" -> return matches(true)
            "matchesWholeText" -> return matchesWholeText(false)
            "matchesWholeOwnText" -> return matchesWholeText(true)
            "not" -> return not()
            "nth-child" -> return cssNthChild(false, false)
            "nth-last-child" -> return cssNthChild(true, false)
            "nth-of-type" -> return cssNthChild(false, true)
            "nth-last-of-type" -> return cssNthChild(true, true)
            "first-child" -> return Evaluator.IsFirstChild()
            "last-child" -> return Evaluator.IsLastChild()
            "first-of-type" -> return Evaluator.IsFirstOfType()
            "last-of-type" -> return Evaluator.IsLastOfType()
            "only-child" -> return Evaluator.IsOnlyChild()
            "only-of-type" -> return Evaluator.IsOnlyOfType()
            "empty" -> return Evaluator.IsEmpty()
            "blank" -> return BlankValue()
            "root" -> return Evaluator.IsRoot()
            "matchText" -> return Evaluator.MatchText()
            else -> throw Selector.SelectorParseException(
                "Could not parse query '%s': unexpected token at '%s'",
                query,
                tq.remainder()
            )
        }
    }

    // ::comment etc
    private fun parseNodeSelector(): Evaluator? {
        val pseudo = tq.consumeCssIdentifier()
        inNodeContext = true // Enter node context

        var left: Evaluator?
        when (pseudo) {
            "node" -> left = InstanceType(Node::class.java, pseudo)
            "leafnode" -> left = InstanceType(LeafNode::class.java, pseudo)
            "text" -> left = InstanceType(TextNode::class.java, pseudo)
            "comment" -> left = InstanceType(Comment::class.java, pseudo)
            "data" -> left = InstanceType(DataNode::class.java, pseudo)
            "cdata" -> left = InstanceType(CDataNode::class.java, pseudo)
            else -> throw Selector.SelectorParseException(
                "Could not parse query '%s': unknown node type '::%s'", query, pseudo
            )
        }

        // Handle following subclasses in node context (like ::comment:contains())
        var right: Evaluator?
        while ((parseSubclass().also { right = it }) != null) {
            left = Companion.and(left, right!!)
        }

        inNodeContext = false
        return left
    }

    private fun byId(): Evaluator {
        val id = tq.consumeCssIdentifier()
        Validate.notEmpty(id)
        return Evaluator.Id(id)
    }

    private fun byClass(): Evaluator {
        val className = tq.consumeCssIdentifier()
        Validate.notEmpty(className)
        return Evaluator.Class(className.trim { it <= ' ' })
    }

    private fun byTag(): Evaluator {
        // todo - these aren't dealing perfectly with case sensitivity. For case sensitive parsers, we should also make
        // the tag in the selector case-sensitive (and also attribute names). But for now, normalize (lower-case) for
        // consistency - both the selector and the element tag
        var tagName = Normalizer.normalize(tq.consumeElementSelector())
        Validate.notEmpty(tagName)

        // namespaces:
        if (tagName.startsWith("*|")) { // namespaces: wildcard match equals(tagName) or ending in ":"+tagName
            val plainTag = tagName.substring(2) // strip *|
            return CombiningEvaluator.Or(
                Evaluator.Tag(plainTag),
                Evaluator.TagEndsWith(":" + plainTag)
            )
        } else if (tagName.endsWith("|*")) { // ns|*
            val ns = tagName.substring(0, tagName.length - 2) + ":" // strip |*, to ns:
            return TagStartsWith(ns)
        } else if (tagName.contains("|")) { // flip "abc|def" to "abc:def"
            tagName = tagName.replace("|", ":")
        }

        return Evaluator.Tag(tagName)
    }

    private fun byAttribute(): Evaluator {
        TokenQueue(tq.chompBalanced('[', ']')).use { cq ->
            return evaluatorForAttribute(cq)
        }
    }

    private fun evaluatorForAttribute(cq: TokenQueue): Evaluator {
        val key = cq.consumeToAny(*AttributeEvals) // eq, not, start, end, contain, match, (no val)
        Validate.notEmpty(key)
        Validate.isFalse(key == "abs:", "Absolute attribute key must have a name")
        cq.consumeWhitespace()
        val eval: Evaluator

        if (cq.isEmpty()) {
            if (key!!.startsWith("^")) eval = Evaluator.AttributeStarting(key.substring(1))
            else if (key == "*")  // any attribute
                eval = Evaluator.AttributeStarting("")
            else eval = Evaluator.Attribute(key)
        } else {
            if (cq.matchChomp('=')) eval = Evaluator.AttributeWithValue(key, cq.remainder())
            else if (cq.matchChomp("!=")) eval =
                Evaluator.AttributeWithValueNot(key, cq.remainder())
            else if (cq.matchChomp("^=")) eval =
                Evaluator.AttributeWithValueStarting(key, cq.remainder())
            else if (cq.matchChomp("$=")) eval =
                Evaluator.AttributeWithValueEnding(key, cq.remainder())
            else if (cq.matchChomp("*=")) eval =
                Evaluator.AttributeWithValueContaining(key, cq.remainder())
            else if (cq.matchChomp("~=")) eval =
                Evaluator.AttributeWithValueMatching(key, Regex.compile(cq.remainder()))
            else throw Selector.SelectorParseException(
                "Could not parse attribute query '%s': unexpected token at '%s'",
                query,
                cq.remainder()
            )
        }
        return eval
    }

    /**
     * Create a new QueryParser.
     * @param query CSS query
     */
    init {
        var query = query
        Validate.notEmpty(query)
        query = query.trim { it <= ' ' }
        this.query = query
        this.tq = TokenQueue(query)
    }

    private fun cssNthChild(last: Boolean, ofType: Boolean): Evaluator {
        val arg =
            Normalizer.normalize(consumeParens()) // arg is like "odd", or "-n+2", within nth-child(odd)
        val step: Int
        val offset: Int
        if ("odd" == arg) {
            step = 2
            offset = 1
        } else if ("even" == arg) {
            step = 2
            offset = 0
        } else {
            val stepOffsetM: Matcher?
            val stepM: Matcher?
            if ((NthStepOffset.matcher(arg).also { stepOffsetM = it }).matches()) {
                if (stepOffsetM!!.group(3) != null)  // has digits, like 3n+2 or -3n+2
                    step = stepOffsetM.group(1).replaceFirst("^\\+".toRegex(), "").toInt()
                else  // no digits, might be like n+2, or -n+2. if group(2) == "-", it’s -1;
                    step = if ("-" == stepOffsetM.group(2)) -1 else 1
                offset =
                    if (stepOffsetM.group(4) != null) stepOffsetM.group(4)
                        .replaceFirst("^\\+".toRegex(), "").toInt() else 0
            } else if ((NthOffset.matcher(arg).also { stepM = it }).matches()) {
                step = 0
                offset = stepM!!.group().replaceFirst("^\\+".toRegex(), "").toInt()
            } else {
                throw Selector.SelectorParseException(
                    "Could not parse nth-index '%s': unexpected format",
                    arg
                )
            }
        }

        return if (ofType)
            (if (last) Evaluator.IsNthLastOfType(step, offset) else Evaluator.IsNthOfType(
                step,
                offset
            ))
        else
            (if (last) Evaluator.IsNthLastChild(step, offset) else Evaluator.IsNthChild(
                step,
                offset
            ))
    }

    private fun consumeParens(): String? {
        return tq.chompBalanced('(', ')')
    }

    private fun consumeIndex(): Int {
        val index = consumeParens()!!.trim { it <= ' ' }
        Validate.isTrue(StringUtil.isNumeric(index), "Index must be numeric")
        return index.toInt()
    }

    // pseudo selector :has(el)
    private fun has(): Evaluator? {
        return parseNested(
            Function { evaluator: Evaluator? -> Has(evaluator) },
            ":has() must have a selector"
        )
    }

    // pseudo selector :is()
    private fun `is`(): Evaluator? {
        return parseNested(
            Function { evaluator: Evaluator? -> Is(evaluator) },
            ":is() must have a selector"
        )
    }

    private fun parseNested(func: Function<Evaluator?, Evaluator?>, err: String?): Evaluator? {
        Validate.isTrue(tq.matchChomp('('), err)
        val eval = parseSelectorGroup()
        Validate.isTrue(tq.matchChomp(')'), err)
        return func.apply(eval)
    }

    // pseudo selector :contains(text), containsOwn(text)
    private fun contains(own: Boolean): Evaluator {
        val query = if (own) ":containsOwn" else ":contains"
        val searchText = TokenQueue.unescape(consumeParens())
        Validate.notEmpty(searchText, query + "(text) query must not be empty")

        if (inNodeContext) return ContainsValue(searchText)

        return if (own)
            Evaluator.ContainsOwnText(searchText)
        else
            Evaluator.ContainsText(searchText)
    }

    private fun containsWholeText(own: Boolean): Evaluator {
        val query = if (own) ":containsWholeOwnText" else ":containsWholeText"
        val searchText = TokenQueue.unescape(consumeParens())
        Validate.notEmpty(searchText, query + "(text) query must not be empty")
        return if (own)
            Evaluator.ContainsWholeOwnText(searchText)
        else
            Evaluator.ContainsWholeText(searchText)
    }

    // pseudo selector :containsData(data)
    private fun containsData(): Evaluator {
        val searchText = TokenQueue.unescape(consumeParens())
        Validate.notEmpty(searchText, ":containsData(text) query must not be empty")
        return Evaluator.ContainsData(searchText)
    }

    // :matches(regex), matchesOwn(regex)
    private fun matches(own: Boolean): Evaluator {
        val query = if (own) ":matchesOwn" else ":matches"
        val regex = consumeParens() // don't unescape, as regex bits will be escaped
        Validate.notEmpty(regex, query + "(regex) query must not be empty")
        val pattern = Regex.compile(regex)

        if (inNodeContext) return MatchesValue(pattern)

        return if (own)
            Evaluator.MatchesOwn(pattern)
        else
            Evaluator.Matches(pattern)
    }

    // :matches(regex), matchesOwn(regex)
    private fun matchesWholeText(own: Boolean): Evaluator {
        val query = if (own) ":matchesWholeOwnText" else ":matchesWholeText"
        val regex = consumeParens() // don't unescape, as regex bits will be escaped
        Validate.notEmpty(regex, query + "(regex) query must not be empty")

        val pattern = Regex.compile(regex)
        return if (own)
            Evaluator.MatchesWholeOwnText(pattern)
        else
            Evaluator.MatchesWholeText(pattern)
    }

    // :not(selector)
    private fun not(): Evaluator {
        val subQuery = consumeParens()
        Validate.notEmpty(subQuery, ":not(selector) subselect must not be empty")

        return StructuralEvaluator.Not(Companion.parse(subQuery!!))
    }

    override fun toString(): String {
        return query
    }

    override fun close() {
        tq.close()
    }

    companion object {
        private val Combinators =
            charArrayOf('>', '+', '~') // ' ' is also a combinator, but found implicitly
        private val AttributeEvals: Array<String?> =
            arrayOf<String>("=", "!=", "^=", "$=", "*=", "~=")
        private val SequenceEnders = charArrayOf(',', ')')

        /**
         * Parse a CSS query into an Evaluator. If you are evaluating the same query repeatedly, it may be more efficient to
         * parse it once and reuse the Evaluator.
         *
         * @param query CSS query
         * @return Evaluator
         * @see Selector selector query syntax
         * @throws Selector.SelectorParseException if the CSS query is invalid
         */
        fun parse(query: String): Evaluator {
            try {
                QueryParser(query).use { p ->
                    return p.parse()
                }
            } catch (e: IllegalArgumentException) {
                throw Selector.SelectorParseException(e.message)
            }
        }

        fun combinator(left: Evaluator?, combinator: Char, right: Evaluator): Evaluator {
            when (combinator) {
                '>' -> {
                    val run = if (left is ImmediateParentRun) left else ImmediateParentRun(left)
                    run.add(right)
                    return run
                }

                ' ' -> return and(Ancestor(left), right)
                '+' -> return and(StructuralEvaluator.ImmediatePreviousSibling(left), right)
                '~' -> return and(StructuralEvaluator.PreviousSibling(left), right)
                else -> throw Selector.SelectorParseException("Unknown combinator '%s'", combinator)
            }
        }

        /** Merge two evals into an Or.  */
        fun or(left: Evaluator, right: Evaluator?): Evaluator {
            if (left is CombiningEvaluator.Or) {
                left.add(right)
                return left
            }
            return CombiningEvaluator.Or(left, right)
        }

        /** Merge two evals into an And.  */
        fun and(left: @Nullable Evaluator?, right: Evaluator): Evaluator {
            if (left == null) return right
            if (left is CombiningEvaluator.And) {
                left.add(right)
                return left
            }
            return CombiningEvaluator.And(left, right)
        }

        //pseudo selectors :first-child, :last-child, :nth-child, ...
        private val NthStepOffset: Pattern =
            Pattern.compile("(([+-])?(\\d+)?)n(\\s*([+-])?\\s*\\d+)?", Pattern.CASE_INSENSITIVE)
        private val NthOffset: Pattern = Pattern.compile("([+-])?(\\d+)")
    }
}
