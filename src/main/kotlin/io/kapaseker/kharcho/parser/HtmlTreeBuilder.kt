package io.kapaseker.kharcho.parser

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.Normalizer
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.nodes.*
import java.io.Reader

/**
 * HTML Tree Builder; creates a DOM from Tokens.
 */
class HtmlTreeBuilder : TreeBuilder() {
    private var state: HtmlTreeBuilderState? = null // the current state
    private var originalState: HtmlTreeBuilderState? = null // original / marked state

    private var baseUriSetFromDoc = false

    @Nullable
    var headElement: Element? = null // the current head element

    @get:Nullable
    @Nullable
    var formElement: FormElement? = null // the current form element

    @Nullable
    private var contextElement: Element? =
        null // fragment parse root; name only copy of context. could be null even if fragment parsing
    var formattingElements: java.util.ArrayList<Element>? =
        null // active (open) formatting elements
    private var tmplInsertMode: java.util.ArrayList<HtmlTreeBuilderState?>? =
        null // stack of Template Insertion modes
    private var pendingTableCharacters: MutableList<Token.Character?>? =
        null // chars in table to be shifted out
    private var emptyEnd: Token.EndTag? = null // reused empty end tag

    private var framesetOk = false // if ok to go into frameset
    var isFosterInserts: Boolean = false // if next inserts should be fostered
    var isFragmentParsing: Boolean = false // if parsing a fragment of html
        private set

    override fun defaultSettings(): ParseSettings {
        return ParseSettings.Companion.htmlDefault
    }

    override fun newInstance(): HtmlTreeBuilder {
        return HtmlTreeBuilder()
    }

    protected override fun initialiseParse(input: Reader?, baseUri: String?, parser: Parser?) {
        super.initialiseParse(input, baseUri, parser)

        // this is a bit mucky. todo - probably just create new parser objects to ensure all reset.
        state = HtmlTreeBuilderState.Initial
        originalState = null
        baseUriSetFromDoc = false
        headElement = null
        formElement = null
        contextElement = null
        formattingElements = java.util.ArrayList<Element>()
        tmplInsertMode = java.util.ArrayList<HtmlTreeBuilderState?>()
        pendingTableCharacters = java.util.ArrayList<Token.Character?>()
        emptyEnd = Token.EndTag(this)
        framesetOk = true
        this.isFosterInserts = false
        this.isFragmentParsing = false
    }

    override fun initialiseParseFragment(@Nullable context: Element?) {
        // context may be null
        state = HtmlTreeBuilderState.Initial
        this.isFragmentParsing = true

        if (context != null) {
            val contextName = context.normalName()
            contextElement =
                Element(tagFor(contextName, contextName, defaultNamespace(), settings), baseUri)
            if (context.ownerDocument() != null)  // quirks setup:
                doc.quirksMode(context.ownerDocument().quirksMode())

            // initialise the tokeniser state:
            when (contextName) {
                "script" -> tokeniser.transition(TokeniserState.ScriptData)
                "plaintext" -> tokeniser.transition(TokeniserState.PLAINTEXT)
                "template" -> {
                    tokeniser.transition(TokeniserState.Data)
                    pushTemplateMode(HtmlTreeBuilderState.InTemplate)
                }

                else -> {
                    val tag = contextElement!!.tag()
                    val textState = tag.textState()
                    if (textState != null) tokeniser.transition(textState) // style, xmp, title, textarea, etc; or custom
                    else tokeniser.transition(TokeniserState.Data)
                }
            }
            doc.appendChild(contextElement)
            push(contextElement)
            resetInsertionMode()

            // setup form element to nearest form on context (up ancestor chain). ensures form controls are associated
            // with form correctly
            var formSearch: Element? = context
            while (formSearch != null) {
                if (formSearch is FormElement) {
                    formElement = formSearch
                    break
                }
                formSearch = formSearch.parent()
            }
        }
    }

    override fun completeParseFragment(): MutableList<Node?>? {
        if (contextElement != null) {
            // depending on context and the input html, content may have been added outside of the root el
            // e.g. context=p, input=div, the div will have been pushed out.
            val nodes = contextElement!!.siblingNodes()
            if (!nodes.isEmpty()) contextElement!!.insertChildren(-1, nodes)
            return contextElement!!.childNodes()
        } else return doc.childNodes()
    }

    public override fun process(token: Token): Boolean {
        val dispatch =
            (if (useCurrentOrForeignInsert(token)) this.state else io.kapaseker.kharcho.parser.HtmlTreeBuilderState.ForeignContent)!!
        return dispatch.process(token, this)
    }

    fun useCurrentOrForeignInsert(token: Token): Boolean {
        // https://html.spec.whatwg.org/multipage/parsing.html#tree-construction
        // If the stack of open elements is empty
        if (stack!!.isEmpty()) return true
        val el = currentElement()
        val ns = el.tag().namespace()

        // If the adjusted current node is an element in the HTML namespace
        if (Parser.Companion.NamespaceHtml == ns) return true

        // If the adjusted current node is a MathML text integration point and the token is a start tag whose tag name is neither "mglyph" nor "malignmark"
        // If the adjusted current node is a MathML text integration point and the token is a character token
        if (isMathmlTextIntegration(el)) {
            if (token.isStartTag()
                && ("mglyph" != token.asStartTag().normalName) && ("malignmark" != token.asStartTag().normalName)
            ) return true
            if (token.isCharacter()) return true
        }
        // If the adjusted current node is a MathML annotation-xml element and the token is a start tag whose tag name is "svg"
        if (Parser.Companion.NamespaceMathml == ns
            && el.nameIs("annotation-xml")
            && token.isStartTag()
            && "svg" == token.asStartTag().normalName
        ) return true

        // If the adjusted current node is an HTML integration point and the token is a start tag
        // If the adjusted current node is an HTML integration point and the token is a character token
        if (isHtmlIntegration(el)
            && (token.isStartTag() || token.isCharacter())
        ) return true

        // If the token is an end-of-file token
        return token.isEOF()
    }

    fun process(token: Token?, state: HtmlTreeBuilderState): Boolean {
        return state.process(token, this)
    }

    fun transition(state: HtmlTreeBuilderState) {
        this.state = state
    }

    fun state(): HtmlTreeBuilderState {
        return state!!
    }

    fun markInsertionMode() {
        originalState = state
    }

    fun originalState(): HtmlTreeBuilderState? {
        return originalState
    }

    fun framesetOk(framesetOk: Boolean) {
        this.framesetOk = framesetOk
    }

    fun framesetOk(): Boolean {
        return framesetOk
    }

    val document: Document?
        get() = doc

    val baseUri: String?

    fun maybeSetBaseUri(base: Element) {
        if (baseUriSetFromDoc)  // only listen to the first <base href> in parse
            return

        val href = base.absUrl("href")
        if (href.length != 0) { // ignore <base target> etc
            baseUri = href
            baseUriSetFromDoc = true
            doc.setBaseUri(href) // set on the doc so doc.createElement(Tag) will get updated base, and to update all descendants
        }
    }

    fun error(state: HtmlTreeBuilderState?) {
        if (parser.getErrors().canAddError()) parser.getErrors().add(
            ParseError(
                reader, "Unexpected %s token [%s] when in state [%s]",
                currentToken.tokenType(), currentToken, state
            )
        )
    }

    fun createElementFor(
        startTag: Token.StartTag,
        namespace: String?,
        forcePreserveCase: Boolean
    ): Element {
        // dedupe and normalize the attributes:
        val attributes = startTag.attributes
        if (attributes != null && !attributes.isEmpty()) {
            if (!forcePreserveCase) settings.normalizeAttributes(attributes)
            val dupes = attributes.deduplicate(settings)
            if (dupes > 0) {
                error("Dropped duplicate attribute(s) in tag [%s]", startTag.normalName)
            }
        }

        val tag = tagFor(
            startTag.name(), startTag.normalName, namespace,
            if (forcePreserveCase) ParseSettings.Companion.preserveCase else settings
        )

        return if (tag.normalName() == "form") FormElement(tag, null, attributes) else Element(
            tag,
            null,
            attributes
        )
    }

    /** Inserts an HTML element for the given tag  */
    fun insertElementFor(startTag: Token.StartTag): Element {
        val el = createElementFor(startTag, Parser.Companion.NamespaceHtml, false)
        doInsertElement(el)

        // handle self-closing tags. when the spec expects an empty (void) tag, will directly hit insertEmpty, so won't generate this fake end tag.
        if (startTag.isSelfClosing()) {
            val tag = el.tag()
            tag.setSeenSelfClose() // can infer output if in xml syntax
            if (tag.isEmpty()) {
                // treated as empty below; nothing further
            } else if (tag.isKnownTag() && tag.isSelfClosing()) {
                // ok, allow it. effectively a pop, but fiddles with the state. handles empty style, title etc which would otherwise leave us in data state
                tokeniser.transition(TokeniserState.Data) // handles <script />, otherwise needs breakout steps from script data
                tokeniser.emit(
                    emptyEnd!!.reset().name(el.tagName())
                ) // ensure we get out of whatever state we are in. emitted for yielded processing
            } else {
                // error it, and leave the inserted element on
                tokeniser.error("Tag [%s] cannot be self-closing; not a void tag", tag.normalName())
            }
        }

        if (el.tag().isEmpty()) {
            pop() // custom void tags behave like built-in voids (no children, not left on the stack); known empty go via insertEmpty
        }

        return el
    }

    /**
     * Inserts a foreign element. Preserves the case of the tag name and of the attributes.
     */
    fun insertForeignElementFor(startTag: Token.StartTag, namespace: String?): Element {
        val el = createElementFor(startTag, namespace, true)
        doInsertElement(el)

        if (startTag.isSelfClosing()) { // foreign els are OK to self-close
            el.tag().setSeenSelfClose() // remember this is self-closing for output
            pop()
        }

        return el
    }

    fun insertEmptyElementFor(startTag: Token.StartTag): Element {
        val el = createElementFor(startTag, Parser.Companion.NamespaceHtml, false)
        doInsertElement(el)
        pop()
        return el
    }

    fun insertFormElement(
        startTag: Token.StartTag,
        onStack: Boolean,
        checkTemplateStack: Boolean
    ): FormElement {
        val el = createElementFor(startTag, Parser.Companion.NamespaceHtml, false) as FormElement

        if (checkTemplateStack) {
            if (!onStack("template")) this.formElement = el
        } else this.formElement = el

        doInsertElement(el)
        if (!onStack) pop()
        return el
    }

    /** Inserts the Element onto the stack. All element inserts must run through this method. Performs any general
     * tests on the Element before insertion.
     * @param el the Element to insert and make the current element
     */
    private fun doInsertElement(el: Element) {
        enforceStackDepthLimit()

        if (formElement != null && el.tag().namespace == Parser.Companion.NamespaceHtml && StringUtil.inSorted(
                el.normalName(),
                TagFormListed
            )
        ) formElement!!.addElement(el) // connect form controls to their form element


        // in HTML, the xmlns attribute if set must match what the parser set the tag's namespace to
        if (parser.getErrors().canAddError() && el.hasAttr("xmlns") && (el.attr("xmlns") != el.tag()
                .namespace())
        ) error("Invalid xmlns attribute [%s] on tag [%s]", el.attr("xmlns"), el.tagName())

        if (this.isFosterInserts && StringUtil.inSorted(
                currentElement().normalName(),
                HtmlTreeBuilderState.Constants.InTableFoster
            )
        ) insertInFosterParent(el)
        else currentElement().appendChild(el)

        push(el)
    }

    fun insertCommentNode(token: Token.Comment) {
        val node = Comment(token.getData())
        currentElement().appendChild(node)
        onNodeInserted(node)
    }

    /**
     * Inserts the provided character token into the current element. The tokenizer will have already raised precise character errors.
     *
     * @param characterToken the character token to insert
     * @param replace if true, replaces any null chars in the data with the replacement char (U+FFFD). If false, removes
     * null chars.
     */
    /** Inserts the provided character token into the current element. Any nulls in the data will be removed.  */
    @JvmOverloads
    fun insertCharacterNode(characterToken: Token.Character, replace: Boolean = false) {
        characterToken.normalizeNulls(replace)
        val el =
            currentElement() // will be doc if no current element; allows for whitespace to be inserted into the doc root object (not on the stack)
        insertCharacterToElement(characterToken, el)
    }

    /** Inserts the provided character token into the provided element.  */
    fun insertCharacterToElement(characterToken: Token.Character, el: Element) {
        val node: Node
        val data = characterToken.getData()

        if (characterToken.isCData()) node = CDataNode(data)
        else if (el.tag().andOption(Tag.Companion.Data)) node = DataNode(data)
        else node = TextNode(data)
        el.appendChild(node) // doesn't use insertNode, because we don't foster these; and will always have a stack.
        onNodeInserted(node)
    }

    val stack: ArrayList<Element>?

    fun onStack(el: Element?): Boolean {
        return Companion.onStack(stack, el)
    }

    /** Checks if there is an HTML element with the given name on the stack.  */
    fun onStack(elName: String?): Boolean {
        return getFromStack(elName) != null
    }

    /** Gets the nearest (lowest) HTML element with the given name from the stack.  */
    @Nullable
    fun getFromStack(elName: String?): Element? {
        val bottom = stack!!.size - 1
        val upper = if (bottom >= maxQueueDepth) bottom - maxQueueDepth else 0
        for (pos in bottom downTo upper) {
            val next = stack.get(pos)
            if (next.elementIs(elName, Parser.Companion.NamespaceHtml)) {
                return next
            }
        }
        return null
    }

    fun removeFromStack(el: Element?): Boolean {
        for (pos in stack!!.indices.reversed()) {
            val next = stack.get(pos)
            if (next === el) {
                stack.removeAt(pos)
                onNodeClosed(el)
                return true
            }
        }
        return false
    }

    override fun onStackPrunedForDepth(element: Element) {
        // handle other effects of popping to keep state correct
        if (element === headElement) headElement = null
        if (element === formElement) this.formElement = null
        removeFromActiveFormattingElements(element)
        if (element.nameIs("template")) {
            clearFormattingElementsToLastMarker()
            if (templateModeSize() > 0) popTemplateMode()
            resetInsertionMode()
        }
    }

    /** Pops the stack until the given HTML element is removed.  */
    @Nullable
    fun popStackToClose(elName: String?): Element? {
        for (pos in stack!!.indices.reversed()) {
            val el = pop()
            if (el.elementIs(elName, Parser.Companion.NamespaceHtml)) {
                return el
            }
        }
        return null
    }

    /** Pops the stack until an element with the supplied name is removed, irrespective of namespace.  */
    @Nullable
    fun popStackToCloseAnyNamespace(elName: String?): Element? {
        for (pos in stack!!.indices.reversed()) {
            val el = pop()
            if (el.nameIs(elName)) {
                return el
            }
        }
        return null
    }

    /** Pops the stack until one of the given HTML elements is removed.  */
    fun popStackToClose(vararg elNames: String?) { // elnames is sorted, comes from Constants
        for (pos in stack!!.indices.reversed()) {
            val el = pop()
            if (inSorted(el.normalName(), elNames) && Parser.Companion.NamespaceHtml == el.tag()
                    .namespace()
            ) {
                break
            }
        }
    }

    fun clearStackToTableContext() {
        clearStackToContext("table", "template")
    }

    fun clearStackToTableBodyContext() {
        clearStackToContext("tbody", "tfoot", "thead", "template")
    }

    fun clearStackToTableRowContext() {
        clearStackToContext("tr", "template")
    }

    /** Removes elements from the stack until one of the supplied HTML elements is removed.  */
    private fun clearStackToContext(vararg nodeNames: String?) {
        for (pos in stack!!.indices.reversed()) {
            val next = stack.get(pos)
            if (Parser.Companion.NamespaceHtml == next.tag().namespace() &&
                (StringUtil.checkIn(next.normalName(), *nodeNames) || next.nameIs("html"))
            ) break
            else pop()
        }
    }

    /**
     * Gets the Element immediately above the supplied element on the stack. Which due to adoption, may not necessarily be
     * its parent.
     *
     * @param el
     * @return the Element immediately above the supplied element, or null if there is no such element.
     */
    @Nullable
    fun aboveOnStack(el: Element?): Element? {
        if (!onStack(el)) return null
        for (pos in stack!!.size - 1 downTo 1) {
            val next = stack.get(pos)
            if (next === el) {
                return stack.get(pos - 1)
            }
        }
        return null
    }

    fun insertOnStackAfter(after: Element?, `in`: Element?) {
        val i = stack!!.lastIndexOf(after)
        if (i == -1) {
            error("Did not find element on stack to insert after")
            stack.add(`in`)
            // may happen on particularly malformed inputs during adoption
        } else {
            stack.add(i + 1, `in`)
        }
    }

    fun replaceOnStack(out: Element?, `in`: Element?) {
        Companion.replaceInQueue(stack, out, `in`)
    }

    /**
     * Reset the insertion mode, by searching up the stack for an appropriate insertion mode. The stack search depth
     * is limited to [.maxQueueDepth].
     * @return true if the insertion mode was actually changed.
     */
    fun resetInsertionMode(): Boolean {
        // https://html.spec.whatwg.org/multipage/parsing.html#the-insertion-mode
        var last = false
        val bottom = stack!!.size - 1
        val upper = if (bottom >= maxQueueDepth) bottom - maxQueueDepth else 0
        val origState = this.state

        if (stack.size == 0) { // nothing left of stack, just get to body
            transition(HtmlTreeBuilderState.InBody)
        }

        LOOP@ for (pos in bottom downTo upper) {
            var node = stack.get(pos)
            if (pos == upper) {
                last = true
                if (this.isFragmentParsing) node = contextElement
            }
            val name = if (node != null) node.normalName() else ""
            if (Parser.Companion.NamespaceHtml != node.tag()
                    .namespace()
            ) continue  // only looking for HTML elements here


            when (name) {
                "select" -> {
                    transition(HtmlTreeBuilderState.InSelect)
                    // todo - should loop up (with some limit) and check for table or template hits
                    break@LOOP
                }

                "td", "th" -> if (!last) {
                    transition(HtmlTreeBuilderState.InCell)
                    break@LOOP
                }

                "tr" -> {
                    transition(HtmlTreeBuilderState.InRow)
                    break@LOOP
                }

                "tbody", "thead", "tfoot" -> {
                    transition(HtmlTreeBuilderState.InTableBody)
                    break@LOOP
                }

                "caption" -> {
                    transition(HtmlTreeBuilderState.InCaption)
                    break@LOOP
                }

                "colgroup" -> {
                    transition(HtmlTreeBuilderState.InColumnGroup)
                    break@LOOP
                }

                "table" -> {
                    transition(HtmlTreeBuilderState.InTable)
                    break@LOOP
                }

                "template" -> {
                    val tmplState = currentTemplateMode()
                    Validate.notNull(tmplState, "Bug: no template insertion mode on stack!")
                    transition(tmplState)
                    break@LOOP
                }

                "head" -> if (!last) {
                    transition(HtmlTreeBuilderState.InHead)
                    break@LOOP
                }

                "body" -> {
                    transition(HtmlTreeBuilderState.InBody)
                    break@LOOP
                }

                "frameset" -> {
                    transition(HtmlTreeBuilderState.InFrameset)
                    break@LOOP
                }

                "html" -> {
                    transition(if (headElement == null) HtmlTreeBuilderState.BeforeHead else HtmlTreeBuilderState.AfterHead)
                    break@LOOP
                }
            }
            if (last) {
                transition(HtmlTreeBuilderState.InBody)
                break
            }
        }
        return state !== origState
    }

    /** Places the body back onto the stack and moves to InBody, for cases in AfterBody / AfterAfterBody when more content comes  */
    fun resetBody() {
        if (!onStack("body")) {
            stack!!.add(doc.body()) // not onNodeInserted, as already seen
        }
        transition(HtmlTreeBuilderState.InBody)
    }

    // todo: tidy up in specific scope methods
    private val specificScopeTarget = arrayOf<String?>(null)

    private fun inSpecificScope(
        targetName: String?,
        baseTypes: Array<String?>?,
        extraTypes: Array<String?>?
    ): Boolean {
        specificScopeTarget[0] = targetName
        return inSpecificScope(specificScopeTarget, baseTypes, extraTypes)
    }

    private fun inSpecificScope(
        targetNames: Array<String?>?,
        baseTypes: Array<String?>?,
        @Nullable extraTypes: Array<String?>?
    ): Boolean {
        // https://html.spec.whatwg.org/multipage/parsing.html#has-an-element-in-the-specific-scope
        val bottom = stack!!.size - 1
        // don't walk too far up the tree
        for (pos in bottom downTo 0) {
            val el = stack.get(pos)
            val elName = el.normalName()
            // namespace checks - arguments provided are always in html ns, with this bolt-on for math and svg:
            val ns = el.tag().namespace()
            if (ns == Parser.Companion.NamespaceHtml) {
                if (inSorted(elName, targetNames)) return true
                if (inSorted(elName, baseTypes)) return false
                if (extraTypes != null && inSorted(elName, extraTypes)) return false
            } else if (baseTypes == TagsSearchInScope) {
                if (ns == Parser.Companion.NamespaceMathml && inSorted(
                        elName,
                        TagSearchInScopeMath
                    )
                ) return false
                if (ns == Parser.Companion.NamespaceSvg && inSorted(
                        elName,
                        TagSearchInScopeSvg
                    )
                ) return false
            }
        }
        //Validate.fail("Should not be reachable"); // would end up false because hitting 'html' at root (basetypes)
        return false
    }

    fun inScope(targetNames: Array<String?>?): Boolean {
        return inSpecificScope(targetNames, TagsSearchInScope, null)
    }

    @JvmOverloads
    fun inScope(targetName: String?, extras: Array<String?>? = null): Boolean {
        return inSpecificScope(targetName, TagsSearchInScope, extras)
    }

    fun inListItemScope(targetName: String?): Boolean {
        return inScope(targetName, TagSearchList)
    }

    fun inButtonScope(targetName: String?): Boolean {
        return inScope(targetName, TagSearchButton)
    }

    fun inTableScope(targetName: String?): Boolean {
        return inSpecificScope(targetName, TagSearchTableScope, null)
    }

    fun inSelectScope(targetName: String?): Boolean {
        for (pos in stack!!.indices.reversed()) {
            val el = stack.get(pos)
            val elName = el.normalName()
            if (elName == targetName) return true
            if (!inSorted(elName, TagSearchSelectScope))  // all elements except
                return false
        }
        return false // nothing left on stack
    }

    /** Tests if there is some element on the stack that is not in the provided set.  */
    fun onStackNot(allowedTags: Array<String?>?): Boolean {
        for (pos in stack!!.indices.reversed()) {
            val elName = stack.get(pos).normalName()
            if (!inSorted(elName, allowedTags)) return true
        }
        return false
    }

    fun resetPendingTableCharacters() {
        pendingTableCharacters!!.clear()
    }

    fun getPendingTableCharacters(): MutableList<Token.Character?> {
        return pendingTableCharacters!!
    }

    fun addPendingTableCharacters(c: Token.Character) {
        // make a copy of the token to maintain its state (as Tokens are otherwise reset)
        val copy = Token.Character(c)
        pendingTableCharacters!!.add(copy)
    }

    /**
     * 13.2.6.3 Closing elements that have implied end tags
     * When the steps below require the UA to generate implied end tags, then, while the current node is a dd element, a dt element, an li element, an optgroup element, an option element, a p element, an rb element, an rp element, an rt element, or an rtc element, the UA must pop the current node off the stack of open elements.
     *
     * If a step requires the UA to generate implied end tags but lists an element to exclude from the process, then the UA must perform the above steps as if that element was not in the above list.
     *
     * When the steps below require the UA to generate all implied end tags thoroughly, then, while the current node is a caption element, a colgroup element, a dd element, a dt element, an li element, an optgroup element, an option element, a p element, an rb element, an rp element, an rt element, an rtc element, a tbody element, a td element, a tfoot element, a th element, a thead element, or a tr element, the UA must pop the current node off the stack of open elements.
     *
     * @param excludeTag If a step requires the UA to generate implied end tags but lists an element to exclude from the
     * process, then the UA must perform the above steps as if that element was not in the above list.
     */
    fun generateImpliedEndTags(excludeTag: String?) {
        while (inSorted(currentElement().normalName(), TagSearchEndTags)) {
            if (excludeTag != null && currentElementIs(excludeTag)) break
            pop()
        }
    }

    /**
     * Pops HTML elements off the stack according to the implied end tag rules
     * @param thorough if we are thorough (includes table elements etc) or not
     */
    @JvmOverloads
    fun generateImpliedEndTags(thorough: Boolean = false) {
        val search: Array<String?> = if (thorough) TagThoroughSearchEndTags else TagSearchEndTags
        while (Parser.Companion.NamespaceHtml == currentElement().tag().namespace()
            && inSorted(currentElement().normalName(), search)
        ) {
            pop()
        }
    }

    fun closeElement(name: String) {
        generateImpliedEndTags(name)
        if (name != currentElement().normalName()) error(state())
        popStackToClose(name)
    }

    fun lastFormattingElement(): Element? {
        return if (formattingElements!!.size > 0) formattingElements!!.get(formattingElements!!.size - 1) else null
    }

    fun positionOfElement(el: Element?): Int {
        for (i in formattingElements!!.indices) {
            if (el === formattingElements!!.get(i)) return i
        }
        return -1
    }

    fun removeLastFormattingElement(): Element? {
        val size = formattingElements!!.size
        if (size > 0) return formattingElements!!.removeAt(size - 1)
        else return null
    }

    // active formatting elements
    fun pushActiveFormattingElements(`in`: Element) {
        checkActiveFormattingElements(`in`)
        formattingElements!!.add(`in`)
    }

    fun pushWithBookmark(`in`: Element, bookmark: Int) {
        checkActiveFormattingElements(`in`)
        // catch any range errors and assume bookmark is incorrect - saves a redundant range check.
        try {
            formattingElements!!.add(bookmark, `in`)
        } catch (e: IndexOutOfBoundsException) {
            formattingElements!!.add(`in`)
        }
    }

    fun checkActiveFormattingElements(`in`: Element) {
        var numSeen = 0
        val size = formattingElements!!.size - 1
        var ceil: Int = size - maxUsedFormattingElements
        if (ceil < 0) ceil = 0

        for (pos in size downTo ceil) {
            val el = formattingElements!!.get(pos)
            if (el == null)  // marker
                break

            if (isSameFormattingElement(`in`, el)) numSeen++

            if (numSeen == 3) {
                formattingElements!!.removeAt(pos)
                break
            }
        }
    }

    fun reconstructFormattingElements() {
        if (stack!!.size > maxQueueDepth) return
        val last = lastFormattingElement()
        if (last == null || onStack(last)) return

        var entry: Element? = last
        val size = formattingElements!!.size
        var ceil: Int = size - maxUsedFormattingElements
        if (ceil < 0) ceil = 0
        var pos = size - 1
        var skip = false
        while (true) {
            if (pos == ceil) { // step 4. if none before, skip to 8
                skip = true
                break
            }
            entry = formattingElements!!.get(--pos) // step 5. one earlier than entry
            if (entry == null || onStack(entry))  // step 6 - neither marker nor on stack
                break // jump to 8, else continue back to 4
        }
        while (true) {
            if (!skip)  // step 7: on later than entry
                entry = formattingElements!!.get(++pos)
            Validate.notNull(entry!!) // should not occur, as we break at last element

            // 8. create new element from element, 9 insert into current node, onto stack
            skip = false // can only skip increment from 4.
            val newEl = Element(
                tagFor(
                    entry.nodeName(),
                    entry.normalName(),
                    defaultNamespace(),
                    settings
                ), null, entry.attributes().clone()
            )
            doInsertElement(newEl)

            // 10. replace entry with new entry
            formattingElements!!.set(pos, newEl)

            // 11
            if (pos == size - 1)  // if not last entry in list, jump to 7
                break
        }
    }

    fun clearFormattingElementsToLastMarker() {
        while (!formattingElements!!.isEmpty()) {
            val el = removeLastFormattingElement()
            if (el == null) break
        }
    }

    fun removeFromActiveFormattingElements(el: Element?) {
        for (pos in formattingElements!!.indices.reversed()) {
            val next = formattingElements!!.get(pos)
            if (next === el) {
                formattingElements!!.removeAt(pos)
                break
            }
        }
    }

    fun isInActiveFormattingElements(el: Element?): Boolean {
        return Companion.onStack(formattingElements!!, el)
    }

    @Nullable
    fun getActiveFormattingElement(nodeName: String?): Element? {
        for (pos in formattingElements!!.indices.reversed()) {
            val next = formattingElements!!.get(pos)
            if (next == null)  // scope marker
                break
            else if (next.nameIs(nodeName)) return next
        }
        return null
    }

    fun replaceActiveFormattingElement(out: Element?, `in`: Element?) {
        Companion.replaceInQueue(formattingElements!!, out, `in`)
    }

    fun insertMarkerToFormattingElements() {
        formattingElements!!.add(null)
    }

    fun insertInFosterParent(`in`: Node?) {
        val fosterParent: Element?
        val lastTable = getFromStack("table")
        var isLastTableParent = false
        if (lastTable != null) {
            if (lastTable.parent() != null) {
                fosterParent = lastTable.parent()
                isLastTableParent = true
            } else fosterParent = aboveOnStack(lastTable)
        } else { // no table == frag
            fosterParent = stack!!.get(0)
        }

        if (isLastTableParent) {
            Validate.notNull(lastTable!!) // last table cannot be null by this point.
            lastTable.before(`in`)
        } else fosterParent!!.appendChild(`in`)
    }

    // Template Insertion Mode stack
    fun pushTemplateMode(state: HtmlTreeBuilderState?) {
        tmplInsertMode!!.add(state)
    }

    @Nullable
    fun popTemplateMode(): HtmlTreeBuilderState? {
        if (tmplInsertMode!!.size > 0) {
            return tmplInsertMode!!.removeAt(tmplInsertMode!!.size - 1)
        } else {
            return null
        }
    }

    fun templateModeSize(): Int {
        return tmplInsertMode!!.size
    }

    @Nullable
    fun currentTemplateMode(): HtmlTreeBuilderState {
        return (if (tmplInsertMode.size > 0) tmplInsertMode.get(tmplInsertMode.size - 1) else null)!!
    }

    override fun toString(): String {
        return "TreeBuilder{" +
                "currentToken=" + currentToken +
                ", state=" + state +
                ", currentElement=" + currentElement() +
                '}'
    }

    companion object {
        // tag searches. must be sorted, used in inSorted. HtmlTreeBuilderTest validates they're sorted.
        val TagsSearchInScope: Array<String?> = arrayOf<String>( // a particular element in scope
            "applet", "caption", "html", "marquee", "object", "table", "td", "template", "th"
        )

        // math and svg namespaces for particular element in scope
        val TagSearchInScopeMath: Array<String?> = arrayOf<String>(
            "annotation-xml", "mi", "mn", "mo", "ms", "mtext"
        )
        val TagSearchInScopeSvg: Array<String?> = arrayOf<String>(
            "desc", "foreignObject", "title"
        )

        val TagSearchList: Array<String?> = arrayOf<String>("ol", "ul")
        val TagSearchButton: Array<String?> = arrayOf<String>("button")
        val TagSearchTableScope: Array<String?> = arrayOf<String>("html", "table")
        val TagSearchSelectScope: Array<String?> = arrayOf<String>("optgroup", "option")
        val TagSearchEndTags: Array<String?> =
            arrayOf<String>("dd", "dt", "li", "optgroup", "option", "p", "rb", "rp", "rt", "rtc")
        val TagThoroughSearchEndTags: Array<String?> = arrayOf<String>(
            "caption",
            "colgroup",
            "dd",
            "dt",
            "li",
            "optgroup",
            "option",
            "p",
            "rb",
            "rp",
            "rt",
            "rtc",
            "tbody",
            "td",
            "tfoot",
            "th",
            "thead",
            "tr"
        )
        val TagSearchSpecial: Array<String?> = arrayOf<String>(
            "address",
            "applet",
            "area",
            "article",
            "aside",
            "base",
            "basefont",
            "bgsound",
            "blockquote",
            "body",
            "br",
            "button",
            "caption",
            "center",
            "col",
            "colgroup",
            "dd",
            "details",
            "dir",
            "div",
            "dl",
            "dt",
            "embed",
            "fieldset",
            "figcaption",
            "figure",
            "footer",
            "form",
            "frame",
            "frameset",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "head",
            "header",
            "hgroup",
            "hr",
            "html",
            "iframe",
            "img",
            "input",
            "keygen",
            "li",
            "link",
            "listing",
            "main",
            "marquee",
            "menu",
            "meta",
            "nav",
            "noembed",
            "noframes",
            "noscript",
            "object",
            "ol",
            "p",
            "param",
            "plaintext",
            "pre",
            "script",
            "search",
            "section",
            "select",
            "source",
            "style",
            "summary",
            "table",
            "tbody",
            "td",
            "template",
            "textarea",
            "tfoot",
            "th",
            "thead",
            "title",
            "tr",
            "track",
            "ul",
            "wbr",
            "xmp"
        )
        var TagSearchSpecialMath: Array<String?> = arrayOf<String?>(
            "annotation-xml",
            "mi",
            "mn",
            "mo",
            "ms",
            "mtext"
        ) // differs to MathML text integration point; adds annotation-xml
        val TagMathMlTextIntegration: Array<String?> =
            arrayOf<String>("mi", "mn", "mo", "ms", "mtext")
        val TagSvgHtmlIntegration: Array<String?> =
            arrayOf<String>("desc", "foreignObject", "title")
        val TagFormListed: Array<String?> = arrayOf<String?>(
            "button", "fieldset", "input", "keygen", "object", "output", "select", "textarea"
        )

        @Deprecated("This is not used anymore. Will be removed in a future release. ")
        const val MaxScopeSearchDepth: Int = 100

        fun isMathmlTextIntegration(el: Element): Boolean {
            /*
        A node is a MathML text integration point if it is one of the following elements:
        A MathML mi element
        A MathML mo element
        A MathML mn element
        A MathML ms element
        A MathML mtext element
         */
            return (Parser.Companion.NamespaceMathml == el.tag().namespace()
                    && StringUtil.inSorted(el.normalName(), TagMathMlTextIntegration))
        }

        fun isHtmlIntegration(el: Element): Boolean {
            /*
        A node is an HTML integration point if it is one of the following elements:
        A MathML annotation-xml element whose start tag token had an attribute with the name "encoding" whose value was an ASCII case-insensitive match for the string "text/html"
        A MathML annotation-xml element whose start tag token had an attribute with the name "encoding" whose value was an ASCII case-insensitive match for the string "application/xhtml+xml"
        An SVG foreignObject element
        An SVG desc element
        An SVG title element
         */
            if (Parser.Companion.NamespaceMathml == el.tag().namespace()
                && el.nameIs("annotation-xml")
            ) {
                val encoding = Normalizer.normalize(el.attr("encoding"))
                if (encoding == "text/html" || encoding == "application/xhtml+xml") return true
            }
            // note using .tagName for case-sensitive hit here of foreignObject
            return Parser.Companion.NamespaceSvg == el.tag()
                .namespace() && StringUtil.checkIn(el.tagName(), *TagSvgHtmlIntegration)
        }

        private const val maxQueueDepth =
            256 // an arbitrary tension point between real HTML and crafted pain

        private fun onStack(queue: java.util.ArrayList<Element>, element: Element?): Boolean {
            val bottom = queue.size - 1
            val upper = if (bottom >= maxQueueDepth) bottom - maxQueueDepth else 0
            for (pos in bottom downTo upper) {
                val next: Element? = queue.get(pos)
                if (next === element) {
                    return true
                }
            }
            return false
        }

        private fun replaceInQueue(
            queue: java.util.ArrayList<Element>,
            out: Element?,
            `in`: Element?
        ) {
            val i = queue.lastIndexOf(out)
            Validate.isTrue(i != -1)
            queue.set(i, `in`!!)
        }

        fun isSpecial(el: Element): Boolean {
            val namespace = el.tag().namespace()
            val name = el.normalName()
            when (namespace) {
                Parser.Companion.NamespaceHtml -> return inSorted(name, TagSearchSpecial)
                Parser.Companion.NamespaceMathml -> return inSorted(name, TagSearchSpecialMath)
                Parser.Companion.NamespaceSvg -> return inSorted(name, TagSvgHtmlIntegration)
                else -> return false
            }
        }

        private fun isSameFormattingElement(a: Element, b: Element): Boolean {
            // same if: same namespace, tag, and attributes. Element.equals only checks tag, might in future check children
            return a.normalName() == b.normalName() &&  // a.namespace().equals(b.namespace()) &&
                    a.attributes() == b.attributes()
            // todo: namespaces
        }

        private const val maxUsedFormattingElements = 12 // limit how many elements get recreated
    }
}
