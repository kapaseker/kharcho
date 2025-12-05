package io.kapaseker.kharcho.parser

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.nodes.Document
import io.kapaseker.kharcho.nodes.DocumentType
import io.kapaseker.kharcho.nodes.Element

/**
 * The Tree Builder's current state. Each state embodies the processing for the state, and transitions to other states.
 */
internal enum class HtmlTreeBuilderState {
    Initial {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (HtmlTreeBuilderState.Companion.isWhitespace(t)) {
                return true // ignore whitespace until we get the first content
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment())
            } else if (t.isDoctype()) {
                // todo: parse error check on expected doctypes
                val d = t.asDoctype()
                val doctype = DocumentType(
                    tb.settings.normalizeTag(d.getName()),
                    d.getPublicIdentifier(),
                    d.getSystemIdentifier()
                )
                doctype.setPubSysKey(d.getPubSysKey())
                tb.getDocument().appendChild(doctype)
                tb.onNodeInserted(doctype)
                // todo: quirk state check on more doctype ids, if deemed useful (most are ancient legacy and presumably irrelevant)
                if (d.isForceQuirks() || (doctype.name() != "html") || doctype.publicId()
                        .equals("HTML", ignoreCase = true)
                ) tb.getDocument().quirksMode(
                    Document.QuirksMode.quirks
                )
                tb.transition(HtmlTreeBuilderState.BeforeHtml)
            } else {
                // todo: check not iframe srcdoc
                tb.getDocument().quirksMode(Document.QuirksMode.quirks) // missing doctype
                tb.transition(HtmlTreeBuilderState.BeforeHtml)
                return tb.process(t) // re-process token
            }
            return true
        }
    },
    BeforeHtml {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (t.isDoctype()) {
                tb.error(this)
                return false
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment())
            } else if (HtmlTreeBuilderState.Companion.isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter()) // out of spec - include whitespace
            } else if (t.isStartTag() && t.asStartTag().normalName() == "html") {
                tb.insertElementFor(t.asStartTag())
                tb.transition(HtmlTreeBuilderState.BeforeHead)
            } else if (t.isEndTag() && (inSorted(
                    t.asEndTag().normalName(),
                    Constants.BeforeHtmlToHead
                ))
            ) {
                return anythingElse(t, tb)
            } else if (t.isEndTag()) {
                tb.error(this)
                return false
            } else {
                return anythingElse(t, tb)
            }
            return true
        }

        private fun anythingElse(t: Token?, tb: HtmlTreeBuilder): Boolean {
            tb.processStartTag("html")
            tb.transition(HtmlTreeBuilderState.BeforeHead)
            return tb.process(t)
        }
    },
    BeforeHead {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (HtmlTreeBuilderState.Companion.isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter()) // out of spec - include whitespace
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment())
            } else if (t.isDoctype()) {
                tb.error(this)
                return false
            } else if (t.isStartTag() && t.asStartTag().normalName() == "html") {
                return HtmlTreeBuilderState.InBody.process(t, tb) // does not transition
            } else if (t.isStartTag() && t.asStartTag().normalName() == "head") {
                val head = tb.insertElementFor(t.asStartTag())
                tb.setHeadElement(head)
                tb.transition(HtmlTreeBuilderState.InHead)
            } else if (t.isEndTag() && (inSorted(
                    t.asEndTag().normalName(),
                    Constants.BeforeHtmlToHead
                ))
            ) {
                tb.processStartTag("head")
                return tb.process(t)
            } else if (t.isEndTag()) {
                tb.error(this)
                return false
            } else {
                tb.processStartTag("head")
                return tb.process(t)
            }
            return true
        }
    },
    InHead {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (HtmlTreeBuilderState.Companion.isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter()) // out of spec - include whitespace
                return true
            }
            val name: String
            when (t.type) {
                Token.TokenType.Comment -> tb.insertCommentNode(t.asComment())
                Token.TokenType.Doctype -> {
                    tb.error(this)
                    return false
                }

                Token.TokenType.StartTag -> {
                    val start = t.asStartTag()
                    name = start.normalName()
                    if (name == "html") {
                        return HtmlTreeBuilderState.InBody.process(t, tb)
                    } else if (inSorted(name, Constants.InHeadEmpty)) {
                        val el = tb.insertEmptyElementFor(start)
                        // jsoup special: update base the first time it is seen
                        if (name == "base" && el.hasAttr("href")) tb.maybeSetBaseUri(el)
                    } else if (name == "meta") {
                        tb.insertEmptyElementFor(start)
                    } else if (name == "title") {
                        HtmlTreeBuilderState.Companion.HandleTextState(
                            start,
                            tb,
                            tb.tagFor(start).textState()
                        )
                    } else if (inSorted(name, Constants.InHeadRaw)) {
                        HtmlTreeBuilderState.Companion.HandleTextState(
                            start,
                            tb,
                            tb.tagFor(start).textState()
                        )
                    } else if (name == "noscript") {
                        // else if noscript && scripting flag = true: rawtext (jsoup doesn't run script, to handle as noscript)
                        tb.insertElementFor(start)
                        tb.transition(HtmlTreeBuilderState.InHeadNoscript)
                    } else if (name == "script") {
                        // skips some script rules as won't execute them
                        tb.tokeniser.transition(TokeniserState.ScriptData)
                        tb.markInsertionMode()
                        tb.transition(HtmlTreeBuilderState.Text)
                        tb.insertElementFor(start)
                    } else if (name == "head") {
                        tb.error(this)
                        return false
                    } else if (name == "template") {
                        tb.insertElementFor(start)
                        tb.insertMarkerToFormattingElements()
                        tb.framesetOk(false)
                        tb.transition(HtmlTreeBuilderState.InTemplate)
                        tb.pushTemplateMode(HtmlTreeBuilderState.InTemplate)
                    } else {
                        return anythingElse(t, tb)
                    }
                }

                Token.TokenType.EndTag -> {
                    val end = t.asEndTag()
                    name = end.normalName()
                    if (name == "head") {
                        tb.pop()
                        tb.transition(HtmlTreeBuilderState.AfterHead)
                    } else if (inSorted(name, Constants.InHeadEnd)) {
                        return anythingElse(t, tb)
                    } else if (name == "template") {
                        if (!tb.onStack(name)) {
                            tb.error(this)
                        } else {
                            tb.generateImpliedEndTags(true)
                            if (!tb.currentElementIs(name)) tb.error(this)
                            tb.popStackToClose(name)
                            tb.clearFormattingElementsToLastMarker()
                            tb.popTemplateMode()
                            tb.resetInsertionMode()
                        }
                    } else {
                        tb.error(this)
                        return false
                    }
                }

                else -> return anythingElse(t, tb)
            }
            return true
        }

        private fun anythingElse(t: Token?, tb: TreeBuilder): Boolean {
            tb.processEndTag("head")
            return tb.process(t)
        }
    },
    InHeadNoscript {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (t.isDoctype()) {
                tb.error(this)
            } else if (t.isStartTag() && t.asStartTag().normalName() == "html") {
                return tb.process(t, HtmlTreeBuilderState.InBody)
            } else if (t.isEndTag() && t.asEndTag().normalName() == "noscript") {
                tb.pop()
                tb.transition(HtmlTreeBuilderState.InHead)
            } else if (HtmlTreeBuilderState.Companion.isWhitespace(t) || t.isComment() || (t.isStartTag() && inSorted(
                    t.asStartTag().normalName(),
                    Constants.InHeadNoScriptHead
                ))
            ) {
                return tb.process(t, HtmlTreeBuilderState.InHead)
            } else if (t.isEndTag() && t.asEndTag().normalName() == "br") {
                return anythingElse(t, tb)
            } else if ((t.isStartTag() && inSorted(
                    t.asStartTag().normalName(),
                    Constants.InHeadNoscriptIgnore
                )) || t.isEndTag()
            ) {
                tb.error(this)
                return false
            } else {
                return anythingElse(t, tb)
            }
            return true
        }

        private fun anythingElse(t: Token, tb: HtmlTreeBuilder): Boolean {
            // note that this deviates from spec, which is to pop out of noscript and reprocess in head:
            // https://html.spec.whatwg.org/multipage/parsing.html#parsing-main-inheadnoscript
            // allows content to be inserted as data
            tb.error(this)
            tb.insertCharacterNode(Token.Character().data(t.toString()))
            return true
        }
    },
    AfterHead {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (HtmlTreeBuilderState.Companion.isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter())
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment())
            } else if (t.isDoctype()) {
                tb.error(this)
            } else if (t.isStartTag()) {
                val startTag = t.asStartTag()
                val name = startTag.normalName()
                if (name == "html") {
                    return tb.process(t, HtmlTreeBuilderState.InBody)
                } else if (name == "body") {
                    tb.insertElementFor(startTag)
                    tb.framesetOk(false)
                    tb.transition(HtmlTreeBuilderState.InBody)
                } else if (name == "frameset") {
                    tb.insertElementFor(startTag)
                    tb.transition(HtmlTreeBuilderState.InFrameset)
                } else if (inSorted(name, Constants.InBodyStartToHead)) {
                    tb.error(this)
                    val head = tb.getHeadElement()
                    tb.push(head)
                    tb.process(t, HtmlTreeBuilderState.InHead)
                    tb.removeFromStack(head)
                } else if (name == "head") {
                    tb.error(this)
                    return false
                } else {
                    anythingElse(t, tb)
                }
            } else if (t.isEndTag()) {
                val name = t.asEndTag().normalName()
                if (inSorted(name, Constants.AfterHeadBody)) {
                    anythingElse(t, tb)
                } else if (name == "template") {
                    tb.process(t, HtmlTreeBuilderState.InHead)
                } else {
                    tb.error(this)
                    return false
                }
            } else {
                anythingElse(t, tb)
            }
            return true
        }

        private fun anythingElse(t: Token?, tb: HtmlTreeBuilder): Boolean {
            tb.processStartTag("body")
            tb.framesetOk(true)
            return tb.process(t)
        }
    },
    InBody {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            when (t.type) {
                Token.TokenType.Character -> {
                    val c = t.asCharacter()
                    if (tb.framesetOk() && HtmlTreeBuilderState.Companion.isWhitespace(c)) { // don't check if whitespace if frames already closed
                        tb.reconstructFormattingElements()
                        tb.insertCharacterNode(c)
                    } else {
                        tb.reconstructFormattingElements()
                        tb.insertCharacterNode(c) // strips nulls
                        tb.framesetOk(false)
                    }
                }

                Token.TokenType.Comment -> {
                    tb.insertCommentNode(t.asComment())
                }

                Token.TokenType.Doctype -> {
                    tb.error(this)
                    return false
                }

                Token.TokenType.StartTag -> return inBodyStartTag(t, tb)
                Token.TokenType.EndTag -> return inBodyEndTag(t, tb)
                Token.TokenType.EOF -> {
                    if (tb.templateModeSize() > 0) return tb.process(
                        t,
                        HtmlTreeBuilderState.InTemplate
                    )
                    if (tb.onStackNot(Constants.InBodyEndOtherErrors)) tb.error(this)
                }

                else -> Validate.wtf("Unexpected state: " + t.type) // XmlDecl only in XmlTreeBuilder
            }
            return true
        }

        private fun inBodyStartTag(t: Token, tb: HtmlTreeBuilder): Boolean {
            val startTag = t.asStartTag()
            val name = startTag.normalName()
            val stack: ArrayList<Element>
            var el: Element

            when (name) {
                "a" -> {
                    if (tb.getActiveFormattingElement("a") != null) {
                        tb.error(this)
                        tb.processEndTag("a")

                        // still on stack?
                        val remainingA = tb.getFromStack("a")
                        if (remainingA != null) {
                            tb.removeFromActiveFormattingElements(remainingA)
                            tb.removeFromStack(remainingA)
                        }
                    }
                    tb.reconstructFormattingElements()
                    el = tb.insertElementFor(startTag)
                    tb.pushActiveFormattingElements(el)
                }

                "span" -> {
                    // same as final else, but short circuits lots of checks
                    tb.reconstructFormattingElements()
                    tb.insertElementFor(startTag)
                }

                "li" -> {
                    tb.framesetOk(false)
                    stack = tb.getStack()
                    val i = stack.size - 1
                    while (i > 0) {
                        el = stack.get(i)
                        if (el.nameIs("li")) {
                            tb.processEndTag("li")
                            break
                        }
                        if (HtmlTreeBuilder.Companion.isSpecial(el) && !inSorted(
                                el.normalName(),
                                Constants.InBodyStartLiBreakers
                            )
                        ) break
                        i--
                    }
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p")
                    }
                    tb.insertElementFor(startTag)
                }

                "html" -> {
                    tb.error(this)
                    if (tb.onStack("template")) return false // ignore

                    // otherwise, merge attributes onto real html (if present)
                    stack = tb.getStack()
                    if (stack.size > 0) {
                        val html = tb.getStack().get(0)
                        HtmlTreeBuilderState.Companion.mergeAttributes(startTag, html)
                    }
                }

                "body" -> {
                    tb.error(this)
                    stack = tb.getStack()
                    if (stack.size < 2 || (stack.size > 2 && !stack.get(1)
                            .nameIs("body")) || tb.onStack("template")
                    ) {
                        // only in fragment case
                        return false // ignore
                    } else {
                        tb.framesetOk(false)
                        // will be on stack if this is a nested body. won't be if closed (which is a variance from spec, which leaves it on)
                        val body = tb.getFromStack("body")
                        if (body != null) HtmlTreeBuilderState.Companion.mergeAttributes(
                            startTag,
                            body
                        )
                    }
                }

                "frameset" -> {
                    tb.error(this)
                    stack = tb.getStack()
                    if (stack.size < 2 || (stack.size > 2 && !stack.get(1).nameIs("body"))) {
                        // only in fragment case
                        return false // ignore
                    } else if (!tb.framesetOk()) {
                        return false // ignore frameset
                    } else {
                        val second = stack.get(1)
                        if (second.parent() != null) second.remove()
                        // pop up to html element
                        while (stack.size > 1) stack.removeAt(stack.size - 1)
                        tb.insertElementFor(startTag)
                        tb.transition(HtmlTreeBuilderState.InFrameset)
                    }
                }

                "form" -> {
                    if (tb.getFormElement() != null && !tb.onStack("template")) {
                        tb.error(this)
                        return false
                    }
                    if (tb.inButtonScope("p")) {
                        tb.closeElement("p")
                    }
                    tb.insertFormElement(startTag, true, true) // won't associate to any template
                }

                "plaintext" -> {
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p")
                    }
                    tb.insertElementFor(startTag)
                    tb.tokeniser.transition(TokeniserState.PLAINTEXT) // once in, never gets out
                }

                "button" -> if (tb.inButtonScope("button")) {
                    // close and reprocess
                    tb.error(this)
                    tb.processEndTag("button")
                    tb.process(startTag)
                } else {
                    tb.reconstructFormattingElements()
                    tb.insertElementFor(startTag)
                    tb.framesetOk(false)
                }

                "nobr" -> {
                    tb.reconstructFormattingElements()
                    if (tb.inScope("nobr")) {
                        tb.error(this)
                        tb.processEndTag("nobr")
                        tb.reconstructFormattingElements()
                    }
                    el = tb.insertElementFor(startTag)
                    tb.pushActiveFormattingElements(el)
                }

                "table" -> {
                    if (tb.getDocument()
                            .quirksMode() != Document.QuirksMode.quirks && tb.inButtonScope("p")
                    ) {
                        tb.processEndTag("p")
                    }
                    tb.insertElementFor(startTag)
                    tb.framesetOk(false)
                    tb.transition(HtmlTreeBuilderState.InTable)
                }

                "input" -> {
                    tb.reconstructFormattingElements()
                    el = tb.insertEmptyElementFor(startTag)
                    if (!el.attr("type").equals("hidden", ignoreCase = true)) tb.framesetOk(false)
                }

                "hr" -> {
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p")
                    }
                    tb.insertEmptyElementFor(startTag)
                    tb.framesetOk(false)
                }

                "image" -> if (tb.getFromStack("svg") == null) return tb.process(startTag.name("img")) // change <image> to <img>, unless in svg
                else tb.insertElementFor(startTag)

                "textarea" -> {
                    tb.framesetOk(false)
                    HtmlTreeBuilderState.Companion.HandleTextState(
                        startTag,
                        tb,
                        tb.tagFor(startTag).textState()
                    )
                }

                "xmp" -> {
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p")
                    }
                    tb.reconstructFormattingElements()
                    tb.framesetOk(false)
                    HtmlTreeBuilderState.Companion.HandleTextState(
                        startTag,
                        tb,
                        tb.tagFor(startTag).textState()
                    )
                }

                "iframe" -> {
                    tb.framesetOk(false)
                    HtmlTreeBuilderState.Companion.HandleTextState(
                        startTag,
                        tb,
                        tb.tagFor(startTag).textState()
                    )
                }

                "noembed" ->                     // also handle noscript if script enabled
                    HtmlTreeBuilderState.Companion.HandleTextState(
                        startTag,
                        tb,
                        tb.tagFor(startTag).textState()
                    )

                "select" -> {
                    tb.reconstructFormattingElements()
                    tb.insertElementFor(startTag)
                    tb.framesetOk(false)
                    if (startTag.selfClosing) break // don't change states if not added to the stack


                    val state = tb.state()
                    if (state == HtmlTreeBuilderState.InTable || state == HtmlTreeBuilderState.InCaption || state == HtmlTreeBuilderState.InTableBody || state == HtmlTreeBuilderState.InRow || state == HtmlTreeBuilderState.InCell) tb.transition(
                        HtmlTreeBuilderState.InSelectInTable
                    )
                    else tb.transition(HtmlTreeBuilderState.InSelect)
                }

                "math" -> {
                    tb.reconstructFormattingElements()
                    tb.insertForeignElementFor(startTag, Parser.Companion.NamespaceMathml)
                }

                "svg" -> {
                    tb.reconstructFormattingElements()
                    tb.insertForeignElementFor(startTag, Parser.Companion.NamespaceSvg)
                }

                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p")
                    }
                    if (inSorted(tb.currentElement().normalName(), Constants.Headings)) {
                        tb.error(this)
                        tb.pop()
                    }
                    tb.insertElementFor(startTag)
                }

                "pre", "listing" -> {
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p")
                    }
                    tb.insertElementFor(startTag)
                    tb.reader.matchConsume("\n") // ignore LF if next token
                    tb.framesetOk(false)
                }

                "dd", "dt" -> {
                    tb.framesetOk(false)
                    stack = tb.getStack()
                    val bottom = stack.size - 1
                    val upper = if (bottom >= MaxStackScan) bottom - MaxStackScan else 0
                    val i = bottom
                    while (i >= upper) {
                        el = stack.get(i)
                        if (inSorted(el.normalName(), Constants.DdDt)) {
                            tb.processEndTag(el.normalName())
                            break
                        }
                        if (HtmlTreeBuilder.Companion.isSpecial(el) && !inSorted(
                                el.normalName(),
                                Constants.InBodyStartLiBreakers
                            )
                        ) break
                        i--
                    }
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p")
                    }
                    tb.insertElementFor(startTag)
                }

                "optgroup", "option" -> {
                    if (tb.currentElementIs("option")) tb.processEndTag("option")
                    tb.reconstructFormattingElements()
                    tb.insertElementFor(startTag)
                }

                "rb", "rtc" -> {
                    if (tb.inScope("ruby")) {
                        tb.generateImpliedEndTags()
                        if (!tb.currentElementIs("ruby")) tb.error(this)
                    }
                    tb.insertElementFor(startTag)
                }

                "rp", "rt" -> {
                    if (tb.inScope("ruby")) {
                        tb.generateImpliedEndTags("rtc")
                        if (!tb.currentElementIs("rtc") && !tb.currentElementIs("ruby")) tb.error(
                            this
                        )
                    }
                    tb.insertElementFor(startTag)
                }

                "area", "br", "embed", "img", "keygen", "wbr" -> {
                    tb.reconstructFormattingElements()
                    tb.insertEmptyElementFor(startTag)
                    tb.framesetOk(false)
                }

                "b", "big", "code", "em", "font", "i", "s", "small", "strike", "strong", "tt", "u" -> {
                    tb.reconstructFormattingElements()
                    el = tb.insertElementFor(startTag)
                    tb.pushActiveFormattingElements(el)
                }

                else -> {
                    val tag = tb.tagFor(startTag)
                    val textState = tag.textState()
                    if (textState != null) { // custom rcdata or rawtext (if we were in head, will have auto-transitioned here)
                        HtmlTreeBuilderState.Companion.HandleTextState(startTag, tb, textState)
                    } else if (!tag.isKnownTag()) { // no other special rules for custom tags
                        tb.insertElementFor(startTag)
                    } else if (inSorted(name, Constants.InBodyStartPClosers)) {
                        if (tb.inButtonScope("p")) tb.processEndTag("p")
                        tb.insertElementFor(startTag)
                    } else if (inSorted(name, Constants.InBodyStartToHead)) {
                        return tb.process(t, HtmlTreeBuilderState.InHead)
                    } else if (inSorted(name, Constants.InBodyStartApplets)) {
                        tb.reconstructFormattingElements()
                        tb.insertElementFor(startTag)
                        tb.insertMarkerToFormattingElements()
                        tb.framesetOk(false)
                    } else if (inSorted(name, Constants.InBodyStartMedia)) {
                        tb.insertEmptyElementFor(startTag)
                    } else if (inSorted(name, Constants.InBodyStartDrop)) {
                        tb.error(this)
                        return false
                    } else {
                        tb.reconstructFormattingElements()
                        tb.insertElementFor(startTag)
                    }
                }
            }
            return true
        }
        private static
        val MaxStackScan: Int = 24 // used for DD / DT scan, prevents runaway

        private fun inBodyEndTag(t: Token, tb: HtmlTreeBuilder): Boolean {
            val endTag = t.asEndTag()
            val name = endTag.normalName()

            when (name) {
                "template" -> tb.process(t, HtmlTreeBuilderState.InHead)
                "sarcasm", "span" ->                     // same as final fall through, but saves short circuit
                    return anyOtherEndTag(t, tb)

                "li" -> if (!tb.inListItemScope(name)) {
                    tb.error(this)
                    return false
                } else {
                    tb.generateImpliedEndTags(name)
                    if (!tb.currentElementIs(name)) tb.error(this)
                    tb.popStackToClose(name)
                }

                "body" -> if (!tb.inScope("body")) {
                    tb.error(this)
                    return false
                } else {
                    if (tb.onStackNot(Constants.InBodyEndOtherErrors)) tb.error(this)
                    tb.trackNodePosition(
                        tb.getFromStack("body"),
                        false
                    ) // track source position of close; body is left on stack, in case of trailers
                    tb.transition(HtmlTreeBuilderState.AfterBody)
                }

                "html" -> if (!tb.onStack("body")) {
                    tb.error(this)
                    return false // ignore
                } else {
                    if (tb.onStackNot(Constants.InBodyEndOtherErrors)) tb.error(this)
                    tb.transition(HtmlTreeBuilderState.AfterBody)
                    return tb.process(t) // re-process
                }

                "form" -> if (!tb.onStack("template")) {
                    val currentForm: Element? = tb.getFormElement()
                    tb.setFormElement(null)
                    if (currentForm == null || !tb.inScope(name)) {
                        tb.error(this)
                        return false
                    }
                    tb.generateImpliedEndTags()
                    if (!tb.currentElementIs(name)) tb.error(this)
                    // remove currentForm from stack. will shift anything under up.
                    tb.removeFromStack(currentForm)
                } else { // template on stack
                    if (!tb.inScope(name)) {
                        tb.error(this)
                        return false
                    }
                    tb.generateImpliedEndTags()
                    if (!tb.currentElementIs(name)) tb.error(this)
                    tb.popStackToClose(name)
                }

                "p" -> if (!tb.inButtonScope(name)) {
                    tb.error(this)
                    tb.processStartTag(name) // if no p to close, creates an empty <p></p>
                    return tb.process(endTag)
                } else {
                    tb.generateImpliedEndTags(name)
                    if (!tb.currentElementIs(name)) tb.error(this)
                    tb.popStackToClose(name)
                }

                "dd", "dt" -> if (!tb.inScope(name)) {
                    tb.error(this)
                    return false
                } else {
                    tb.generateImpliedEndTags(name)
                    if (!tb.currentElementIs(name)) tb.error(this)
                    tb.popStackToClose(name)
                }

                "h1", "h2", "h3", "h4", "h5", "h6" -> if (!tb.inScope(Constants.Headings)) {
                    tb.error(this)
                    return false
                } else {
                    tb.generateImpliedEndTags(name)
                    if (!tb.currentElementIs(name)) tb.error(this)
                    tb.popStackToClose(*Constants.Headings)
                }

                "br" -> {
                    tb.error(this)
                    tb.processStartTag("br")
                    return false
                }

                else ->                     // todo - move rest to switch if desired
                    if (inSorted(name, Constants.InBodyEndAdoptionFormatters)) {
                        return inBodyEndTagAdoption(t, tb)
                    } else if (inSorted(name, Constants.InBodyEndClosers)) {
                        if (!tb.inScope(name)) {
                            // nothing to close
                            tb.error(this)
                            return false
                        } else {
                            tb.generateImpliedEndTags()
                            if (!tb.currentElementIs(name)) tb.error(this)
                            tb.popStackToClose(name)
                        }
                    } else if (inSorted(name, Constants.InBodyStartApplets)) {
                        if (!tb.inScope("name")) {
                            if (!tb.inScope(name)) {
                                tb.error(this)
                                return false
                            }
                            tb.generateImpliedEndTags()
                            if (!tb.currentElementIs(name)) tb.error(this)
                            tb.popStackToClose(name)
                            tb.clearFormattingElementsToLastMarker()
                        }
                    } else {
                        return anyOtherEndTag(t, tb)
                    }
            }
            return true
        }

        fun anyOtherEndTag(t: Token, tb: HtmlTreeBuilder): Boolean {
            val name =
                t.asEndTag().normalName // case insensitive search - goal is to preserve output case, not for the parse to be case sensitive
            val stack = tb.getStack()

            // deviate from spec slightly to speed when super deeply nested
            val elFromStack = tb.getFromStack(name)
            if (elFromStack == null) {
                tb.error(this)
                return false
            }

            for (pos in stack.indices.reversed()) {
                val node = stack.get(pos)
                if (node.nameIs(name)) {
                    tb.generateImpliedEndTags(name)
                    if (!tb.currentElementIs(name)) tb.error(this)
                    tb.popStackToClose(name)
                    break
                } else {
                    if (HtmlTreeBuilder.Companion.isSpecial(node)) {
                        tb.error(this)
                        return false
                    }
                }
            }
            return true
        }

        private fun inBodyEndTagAdoption(t: Token, tb: HtmlTreeBuilder): Boolean {
            // https://html.spec.whatwg.org/multipage/parsing.html#adoption-agency-algorithm
            // JH: Including the spec notes here to simplify tracking / correcting. It's a bit gnarly and there may still be some nuances I haven't caught. But test cases and comparisons to browsers check out.

            // The adoption agency algorithm, which takes as its only argument a token token for which the algorithm is being run, consists of the following steps:

            val endTag = t.asEndTag()
            val subject = endTag.normalName // 1. Let subject be token's tag name.

            // 2. If the [current node] is an [HTML element] whose tag name is subject, and the [current node] is not in the [list of active formatting elements], then pop the [current node] off the [stack of open elements] and return.
            if (tb.currentElement()
                    .normalName() == subject && !tb.isInActiveFormattingElements(tb.currentElement())
            ) {
                tb.pop()
                return true
            }
            var outer = 0 // 3. Let outerLoopCounter be 0.
            while (true) { // 4. While true:
                if (outer >= 8) { // 1. If outerLoopCounter is greater than or equal to 8, then return.
                    return true
                }
                outer++ // 2. Increment outerLoopCounter by 1.

                // 3. Let formattingElement be the last element in the [list of active formatting elements] that:
                //  - is between the end of the list and the last [marker] in the list, if any, or the start of the list otherwise, and
                //  - has the tag name subject.
                //  If there is no such element, then return and instead act as described in the "any other end tag" entry above.
                var formatEl: Element? = null
                for (i in tb.formattingElements.indices.reversed()) {
                    val next = tb.formattingElements.get(i)
                    if (next == null)  // marker
                        break
                    if (next.normalName() == subject) {
                        formatEl = next
                        break
                    }
                }
                if (formatEl == null) {
                    return anyOtherEndTag(t, tb)
                }

                // 4. If formattingElement is not in the [stack of open elements], then this is a [parse error]; remove the element from the list, and return.
                if (!tb.onStack(formatEl)) {
                    tb.error(this)
                    tb.removeFromActiveFormattingElements(formatEl)
                    return true
                }

                //  5. If formattingElement is in the [stack of open elements], but the element is not [in scope], then this is a [parse error]; return.
                if (!tb.inScope(formatEl.normalName())) {
                    tb.error(this)
                    return false
                } else if (tb.currentElement() !== formatEl) { //  6. If formattingElement is not the [current node], this is a [parse error].
                    tb.error(this)
                }

                //  7. Let furthestBlock be the topmost node in the [stack of open elements] that is lower in the stack than formattingElement, and is an element in the [special]category. There might not be one.
                var furthestBlock: Element? = null
                val stack = tb.getStack()
                val fei = stack.lastIndexOf(formatEl)
                if (fei != -1) { // look down the stack
                    for (i in fei + 1..<stack.size) {
                        val el = stack.get(i)
                        if (HtmlTreeBuilder.Companion.isSpecial(el)) {
                            furthestBlock = el
                            break
                        }
                    }
                }

                //  8. If there is no furthestBlock, then the UA must first pop all the nodes from the bottom of the [stack of open elements], from the [current node] up to and including formattingElement, then remove formattingElement from the [list of active formatting elements], and finally return.
                if (furthestBlock == null) {
                    while (tb.currentElement() !== formatEl) {
                        tb.pop()
                    }
                    tb.pop()
                    tb.removeFromActiveFormattingElements(formatEl)
                    return true
                }

                val commonAncestor =
                    tb.aboveOnStack(formatEl) // 9. Let commonAncestor be the element immediately above formattingElement in the [stack of open elements].
                if (commonAncestor == null) {
                    tb.error(this)
                    return true
                } // Would be a WTF


                // 10. Let a bookmark note the position of formattingElement in the [list of active formatting elements] relative to the elements on either side of it in the list.
                // JH - I think this means its index? Or do we need a linked list?
                var bookmark = tb.positionOfElement(formatEl)

                var el: Element? = furthestBlock //  11. Let node and lastNode be furthestBlock.
                var lastEl: Element? = furthestBlock
                var inner = 0 // 12. Let innerLoopCounter be 0.

                while (true) { // 13. While true:
                    inner++ // 1. Increment innerLoopCounter by 1.
                    // 2. Let node be the element immediately above node in the [stack of open elements], or if node is no longer in the [stack of open elements] , the element that was immediately above node in the [stack of open elements] before node was removed.
                    if (!tb.onStack(el)) {
                        // if node was removed from stack, use the element that was above it
                        el = el!!.parent() // JH - is there a situation where it's not the parent?
                    } else {
                        el = tb.aboveOnStack(el)
                    }
                    if (el == null || el.nameIs("body")) {
                        tb.error(this) // shouldn't be able to hit
                        break
                    }
                    //  3. If node is formattingElement, then [break].
                    if (el === formatEl) {
                        break
                    }

                    //  4. If innerLoopCounter is greater than 3 and node is in the [list of active formatting elements], then remove node from the [list of active formatting elements].
                    if (inner > 3 && tb.isInActiveFormattingElements(el)) {
                        tb.removeFromActiveFormattingElements(el)
                        break
                    }
                    // 5. If node is not in the [list of active formatting elements], then remove node from the [stack of open elements] and [continue].
                    if (!tb.isInActiveFormattingElements(el)) {
                        tb.removeFromStack(el)
                        continue
                    }

                    //  6. [Create an element for the token] for which the element node was created, in the [HTML namespace], with commonAncestor as the intended parent; replace the entry for node in the [list of active formatting elements] with an entry for the new element, replace the entry for node in the [stack of open elements] with an entry for the new element, and let node be the new element.
                    if (!tb.onStack(el)) { // stale formatting element; cannot adopt/replace
                        tb.error(this)
                        tb.removeFromActiveFormattingElements(el)
                        break // exit inner loop; proceed with step 14 using current lastEl
                    }
                    val replacement = Element(
                        tb.tagFor(
                            el.nodeName(),
                            el.normalName(),
                            tb.defaultNamespace(),
                            ParseSettings.Companion.preserveCase
                        ), tb.getBaseUri()
                    )
                    tb.replaceActiveFormattingElement(el, replacement)
                    tb.replaceOnStack(el, replacement)
                    el = replacement

                    //  7. If lastNode is furthestBlock, then move the aforementioned bookmark to be immediately after the new node in the [list of active formatting elements].
                    if (lastEl === furthestBlock) {
                        bookmark = tb.positionOfElement(el) + 1
                    }
                    el.appendChild(lastEl) // 8. [Append] lastNode to node.
                    lastEl = el // 9. Set lastNode to node.
                } // end inner loop # 13


                // 14. Insert whatever lastNode ended up being in the previous step at the [appropriate place for inserting a node], but using commonAncestor as the _override target_.
                // todo - impl https://html.spec.whatwg.org/multipage/parsing.html#appropriate-place-for-inserting-a-node fostering
                // just use commonAncestor as target:
                commonAncestor.appendChild(lastEl)
                // 15. [Create an element for the token] for which formattingElement was created, in the [HTML namespace], with furthestBlock as the intended parent.
                val adoptor = Element(formatEl.tag(), tb.getBaseUri())
                adoptor.attributes().addAll(formatEl.attributes()) // also attributes
                // 16. Take all of the child nodes of furthestBlock and append them to the element created in the last step.
                for (child in furthestBlock.childNodes()) {
                    adoptor.appendChild(child)
                }

                furthestBlock.appendChild(adoptor) // 17. Append that new element to furthestBlock.
                // 18. Remove formattingElement from the [list of active formatting elements], and insert the new element into the [list of active formatting elements] at the position of the aforementioned bookmark.
                tb.removeFromActiveFormattingElements(formatEl)
                tb.pushWithBookmark(adoptor, bookmark)
                // 19. Remove formattingElement from the [stack of open elements], and insert the new element into the [stack of open elements] immediately below the position of furthestBlock in that stack.
                tb.removeFromStack(formatEl)
                tb.insertOnStackAfter(furthestBlock, adoptor)
            } // end of outer loop # 4
        }
    },
    Text {
        // in script, style etc. normally treated as data tags
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (t.isCharacter()) {
                tb.insertCharacterNode(t.asCharacter())
            } else if (t.isEOF()) {
                tb.error(this)
                // if current node is script: already started
                tb.pop()
                tb.transition(tb.originalState())
                if (tb.state() === HtmlTreeBuilderState.Text)  // stack is such that we couldn't transition out; just close
                    tb.transition(HtmlTreeBuilderState.InBody)
                return tb.process(t)
            } else if (t.isEndTag()) {
                // if: An end tag whose tag name is "script" -- scripting nesting level, if evaluating scripts
                tb.pop()
                tb.transition(tb.originalState())
            }
            return true
        }
    },
    InTable {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (t.isCharacter() && inSorted(
                    tb.currentElement().normalName(),
                    Constants.InTableFoster
                )
            ) {
                tb.resetPendingTableCharacters()
                tb.markInsertionMode()
                tb.transition(HtmlTreeBuilderState.InTableText)
                return tb.process(t)
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment())
                return true
            } else if (t.isDoctype()) {
                tb.error(this)
                return false
            } else if (t.isStartTag()) {
                val startTag = t.asStartTag()
                val name = startTag.normalName()
                if (name == "caption") {
                    tb.clearStackToTableContext()
                    tb.insertMarkerToFormattingElements()
                    tb.insertElementFor(startTag)
                    tb.transition(HtmlTreeBuilderState.InCaption)
                } else if (name == "colgroup") {
                    tb.clearStackToTableContext()
                    tb.insertElementFor(startTag)
                    tb.transition(HtmlTreeBuilderState.InColumnGroup)
                } else if (name == "col") {
                    tb.clearStackToTableContext()
                    tb.processStartTag("colgroup")
                    return tb.process(t)
                } else if (inSorted(name, Constants.InTableToBody)) {
                    tb.clearStackToTableContext()
                    tb.insertElementFor(startTag)
                    tb.transition(HtmlTreeBuilderState.InTableBody)
                } else if (inSorted(name, Constants.InTableAddBody)) {
                    tb.clearStackToTableContext()
                    tb.processStartTag("tbody")
                    return tb.process(t)
                } else if (name == "table") {
                    tb.error(this)
                    if (!tb.inTableScope(name)) { // ignore it
                        return false
                    } else {
                        tb.popStackToClose(name)
                        if (!tb.resetInsertionMode()) {
                            // not per spec - but haven't transitioned out of table. so try something else
                            tb.insertElementFor(startTag)
                            return true
                        }
                        return tb.process(t)
                    }
                } else if (inSorted(name, Constants.InTableToHead)) {
                    return tb.process(t, HtmlTreeBuilderState.InHead)
                } else if (name == "input") {
                    if (!(startTag.hasAttributes() && startTag.attributes.get("type")
                            .equals("hidden", ignoreCase = true))
                    ) {
                        return anythingElse(t, tb)
                    } else {
                        tb.insertEmptyElementFor(startTag)
                    }
                } else if (name == "form") {
                    tb.error(this)
                    if (tb.getFormElement() != null || tb.onStack("template")) return false
                    else {
                        tb.insertFormElement(
                            startTag,
                            false,
                            false
                        ) // not added to stack. can associate to template
                    }
                } else {
                    return anythingElse(t, tb)
                }
                return true // todo: check if should return processed http://www.whatwg.org/specs/web-apps/current-work/multipage/tree-construction.html#parsing-main-intable
            } else if (t.isEndTag()) {
                val endTag = t.asEndTag()
                val name = endTag.normalName()

                if (name == "table") {
                    if (!tb.inTableScope(name)) {
                        tb.error(this)
                        return false
                    } else {
                        tb.popStackToClose("table")
                        tb.resetInsertionMode()
                    }
                } else if (inSorted(name, Constants.InTableEndErr)) {
                    tb.error(this)
                    return false
                } else if (name == "template") {
                    tb.process(t, HtmlTreeBuilderState.InHead)
                } else {
                    return anythingElse(t, tb)
                }
                return true // todo: as above todo
            } else if (t.isEOF()) {
                if (tb.currentElementIs("html")) tb.error(this)
                return true // stops parsing
            }
            return anythingElse(t, tb)
        }

        fun anythingElse(t: Token?, tb: HtmlTreeBuilder): Boolean {
            tb.error(this)
            tb.setFosterInserts(true)
            tb.process(t, HtmlTreeBuilderState.InBody)
            tb.setFosterInserts(false)
            return true
        }
    },
    InTableText {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (t.type == Token.TokenType.Character) {
                tb.addPendingTableCharacters(t.asCharacter()) // gets to insertCharacterNode, which strips nulls
            } else {
                // insert gathered table text into the correct element:
                if (tb.getPendingTableCharacters().size > 0) {
                    val og =
                        tb.currentToken // update current token, so we can track cursor pos correctly
                    for (c in tb.getPendingTableCharacters()) {
                        tb.currentToken = c
                        if (!HtmlTreeBuilderState.Companion.isWhitespace(c)) {
                            // InTable anything else section:
                            tb.error(this)
                            if (inSorted(
                                    tb.currentElement().normalName(),
                                    Constants.InTableFoster
                                )
                            ) {
                                tb.setFosterInserts(true)
                                tb.process(c, HtmlTreeBuilderState.InBody)
                                tb.setFosterInserts(false)
                            } else {
                                tb.process(c, HtmlTreeBuilderState.InBody)
                            }
                        } else tb.insertCharacterNode(c)
                    }
                    tb.currentToken = og
                    tb.resetPendingTableCharacters()
                }
                tb.transition(tb.originalState())
                return tb.process(t)
            }
            return true
        }
    },
    InCaption {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (t.isEndTag() && t.asEndTag().normalName() == "caption") {
                if (!tb.inTableScope("caption")) { // fragment case
                    tb.error(this)
                    return false
                } else {
                    tb.generateImpliedEndTags()
                    if (!tb.currentElementIs("caption")) tb.error(this)
                    tb.popStackToClose("caption")
                    tb.clearFormattingElementsToLastMarker()
                    tb.transition(HtmlTreeBuilderState.InTable)
                }
            } else if ((t.isStartTag() && inSorted(
                    t.asStartTag().normalName(),
                    Constants.InCellCol
                ) ||
                        t.isEndTag() && t.asEndTag().normalName() == "table")
            ) {
                // same as above but processes after transition
                if (!tb.inTableScope("caption")) { // fragment case
                    tb.error(this)
                    return false
                }
                tb.generateImpliedEndTags(false)
                if (!tb.currentElementIs("caption")) tb.error(this)
                tb.popStackToClose("caption")
                tb.clearFormattingElementsToLastMarker()
                tb.transition(HtmlTreeBuilderState.InTable)
                HtmlTreeBuilderState.InTable.process(t, tb) // doesn't check foreign context
            } else if (t.isEndTag() && inSorted(
                    t.asEndTag().normalName(),
                    Constants.InCaptionIgnore
                )
            ) {
                tb.error(this)
                return false
            } else {
                return tb.process(t, HtmlTreeBuilderState.InBody)
            }
            return true
        }
    },
    InColumnGroup {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (HtmlTreeBuilderState.Companion.isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter())
                return true
            }
            when (t.type) {
                Token.TokenType.Comment -> tb.insertCommentNode(t.asComment())
                Token.TokenType.Doctype -> tb.error(this)
                Token.TokenType.StartTag -> {
                    val startTag = t.asStartTag()
                    when (startTag.normalName()) {
                        "html" -> return tb.process(t, HtmlTreeBuilderState.InBody)
                        "col" -> tb.insertEmptyElementFor(startTag)
                        "template" -> tb.process(t, HtmlTreeBuilderState.InHead)
                        else -> return anythingElse(t, tb)
                    }
                }

                Token.TokenType.EndTag -> {
                    val endTag = t.asEndTag()
                    val name = endTag.normalName()
                    when (name) {
                        "colgroup" -> if (!tb.currentElementIs(name)) {
                            tb.error(this)
                            return false
                        } else {
                            tb.pop()
                            tb.transition(HtmlTreeBuilderState.InTable)
                        }

                        "template" -> tb.process(t, HtmlTreeBuilderState.InHead)
                        else -> return anythingElse(t, tb)
                    }
                }

                Token.TokenType.EOF -> if (tb.currentElementIs("html")) return true // stop parsing; frag case
                else return anythingElse(t, tb)

                else -> return anythingElse(t, tb)
            }
            return true
        }

        private fun anythingElse(t: Token?, tb: HtmlTreeBuilder): Boolean {
            if (!tb.currentElementIs("colgroup")) {
                tb.error(this)
                return false
            }
            tb.pop()
            tb.transition(HtmlTreeBuilderState.InTable)
            tb.process(t)
            return true
        }
    },
    InTableBody {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            val name: String

            when (t.type) {
                Token.TokenType.StartTag -> {
                    val startTag = t.asStartTag()
                    name = startTag.normalName()
                    if (name == "tr") {
                        tb.clearStackToTableBodyContext()
                        tb.insertElementFor(startTag)
                        tb.transition(HtmlTreeBuilderState.InRow)
                    } else if (inSorted(name, Constants.InCellNames)) {
                        tb.error(this)
                        tb.processStartTag("tr")
                        return tb.process(startTag)
                    } else if (inSorted(name, Constants.InTableBodyExit)) {
                        return exitTableBody(t, tb)
                    } else return anythingElse(t, tb)
                }

                Token.TokenType.EndTag -> {
                    val endTag = t.asEndTag()
                    name = endTag.normalName()
                    if (inSorted(name, Constants.InTableEndIgnore)) {
                        if (!tb.inTableScope(name)) {
                            tb.error(this)
                            return false
                        } else {
                            tb.clearStackToTableBodyContext()
                            tb.pop()
                            tb.transition(HtmlTreeBuilderState.InTable)
                        }
                    } else if (name == "table") {
                        return exitTableBody(t, tb)
                    } else if (inSorted(name, Constants.InTableBodyEndIgnore)) {
                        tb.error(this)
                        return false
                    } else return anythingElse(t, tb)
                }

                else -> return anythingElse(t, tb)
            }
            return true
        }

        private fun exitTableBody(t: Token?, tb: HtmlTreeBuilder): Boolean {
            if (!(tb.inTableScope("tbody") || tb.inTableScope("thead") || tb.inScope("tfoot"))) {
                // frag case
                tb.error(this)
                return false
            }
            tb.clearStackToTableBodyContext()
            tb.processEndTag(tb.currentElement().normalName()) // tbody, tfoot, thead
            return tb.process(t)
        }

        private fun anythingElse(t: Token?, tb: HtmlTreeBuilder): Boolean {
            return tb.process(t, HtmlTreeBuilderState.InTable)
        }
    },
    InRow {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (t.isStartTag()) {
                val startTag = t.asStartTag()
                val name = startTag.normalName()

                if (inSorted(name, Constants.InCellNames)) { // td, th
                    tb.clearStackToTableRowContext()
                    tb.insertElementFor(startTag)
                    tb.transition(HtmlTreeBuilderState.InCell)
                    tb.insertMarkerToFormattingElements()
                } else if (inSorted(
                        name,
                        Constants.InRowMissing
                    )
                ) { // "caption", "col", "colgroup", "tbody", "tfoot", "thead", "tr"
                    if (!tb.inTableScope("tr")) {
                        tb.error(this)
                        return false
                    }
                    tb.clearStackToTableRowContext()
                    tb.pop() // tr
                    tb.transition(HtmlTreeBuilderState.InTableBody)
                    return tb.process(t)
                } else {
                    return anythingElse(t, tb)
                }
            } else if (t.isEndTag()) {
                val endTag = t.asEndTag()
                val name = endTag.normalName()

                if (name == "tr") {
                    if (!tb.inTableScope(name)) {
                        tb.error(this) // frag
                        return false
                    }
                    tb.clearStackToTableRowContext()
                    tb.pop() // tr
                    tb.transition(HtmlTreeBuilderState.InTableBody)
                } else if (name == "table") {
                    if (!tb.inTableScope("tr")) {
                        tb.error(this)
                        return false
                    }
                    tb.clearStackToTableRowContext()
                    tb.pop() // tr
                    tb.transition(HtmlTreeBuilderState.InTableBody)
                    return tb.process(t)
                } else if (inSorted(name, Constants.InTableToBody)) { // "tbody", "tfoot", "thead"
                    if (!tb.inTableScope(name)) {
                        tb.error(this)
                        return false
                    }
                    if (!tb.inTableScope("tr")) {
                        // not an error per spec?
                        return false
                    }
                    tb.clearStackToTableRowContext()
                    tb.pop() // tr
                    tb.transition(HtmlTreeBuilderState.InTableBody)
                    return tb.process(t)
                } else if (inSorted(name, Constants.InRowIgnore)) {
                    tb.error(this)
                    return false
                } else {
                    return anythingElse(t, tb)
                }
            } else {
                return anythingElse(t, tb)
            }
            return true
        }

        private fun anythingElse(t: Token?, tb: HtmlTreeBuilder): Boolean {
            return tb.process(t, HtmlTreeBuilderState.InTable)
        }
    },
    InCell {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (t.isEndTag()) {
                val endTag = t.asEndTag()
                val name = endTag.normalName()

                if (inSorted(name, Constants.InCellNames)) { // td, th
                    if (!tb.inTableScope(name)) {
                        tb.error(this)
                        tb.transition(HtmlTreeBuilderState.InRow) // might not be in scope if empty: <td /> and processing fake end tag
                        return false
                    }
                    tb.generateImpliedEndTags()
                    if (!tb.currentElementIs(name)) tb.error(this)
                    tb.popStackToClose(name)
                    tb.clearFormattingElementsToLastMarker()
                    tb.transition(HtmlTreeBuilderState.InRow)
                } else if (inSorted(name, Constants.InCellBody)) {
                    tb.error(this)
                    return false
                } else if (inSorted(name, Constants.InCellTable)) {
                    if (!tb.inTableScope(name)) {
                        tb.error(this)
                        return false
                    }
                    closeCell(tb)
                    return tb.process(t)
                } else {
                    return anythingElse(t, tb)
                }
            } else if (t.isStartTag() &&
                inSorted(t.asStartTag().normalName(), Constants.InCellCol)
            ) {
                if (!(tb.inTableScope("td") || tb.inTableScope("th"))) {
                    tb.error(this)
                    return false
                }
                closeCell(tb)
                return tb.process(t)
            } else {
                return anythingElse(t, tb)
            }
            return true
        }

        private fun anythingElse(t: Token?, tb: HtmlTreeBuilder): Boolean {
            return tb.process(t, HtmlTreeBuilderState.InBody)
        }

        private fun closeCell(tb: HtmlTreeBuilder) {
            if (tb.inTableScope("td")) tb.processEndTag("td")
            else tb.processEndTag("th") // only here if th or td in scope
        }
    },
    InSelect {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            val name: String

            when (t.type) {
                Token.TokenType.Character -> tb.insertCharacterNode(t.asCharacter())
                Token.TokenType.Comment -> tb.insertCommentNode(t.asComment())
                Token.TokenType.Doctype -> {
                    tb.error(this)
                    return false
                }

                Token.TokenType.StartTag -> {
                    val start = t.asStartTag()
                    name = start.normalName()
                    if (name == "html") return tb.process(start, HtmlTreeBuilderState.InBody)
                    else if (name == "option") {
                        if (tb.currentElementIs("option")) tb.processEndTag("option")
                        tb.insertElementFor(start)
                    } else if (name == "optgroup") {
                        if (tb.currentElementIs("option")) tb.processEndTag("option") // pop option and flow to pop optgroup

                        if (tb.currentElementIs("optgroup")) tb.processEndTag("optgroup")
                        tb.insertElementFor(start)
                    } else if (name == "select") {
                        tb.error(this)
                        return tb.processEndTag("select")
                    } else if (inSorted(name, Constants.InSelectEnd)) {
                        tb.error(this)
                        if (!tb.inSelectScope("select")) return false // frag

                        // spec says close select then reprocess; leads to recursion. iter directly:
                        do {
                            tb.popStackToClose("select")
                            tb.resetInsertionMode()
                        } while (tb.inSelectScope("select")) // collapse invalid nested selects
                        return tb.process(start)
                    } else if (name == "script" || name == "template") {
                        return tb.process(t, HtmlTreeBuilderState.InHead)
                    } else {
                        return anythingElse(t, tb)
                    }
                }

                Token.TokenType.EndTag -> {
                    val end = t.asEndTag()
                    name = end.normalName()
                    when (name) {
                        "optgroup" -> {
                            if (tb.currentElementIs("option") && tb.aboveOnStack(tb.currentElement()) != null && tb.aboveOnStack(
                                    tb.currentElement()
                                ).nameIs("optgroup")
                            ) tb.processEndTag("option")
                            if (tb.currentElementIs("optgroup")) tb.pop()
                            else tb.error(this)
                        }

                        "option" -> if (tb.currentElementIs("option")) tb.pop()
                        else tb.error(this)

                        "select" -> if (!tb.inSelectScope(name)) {
                            tb.error(this)
                            return false
                        } else {
                            tb.popStackToClose(name)
                            tb.resetInsertionMode()
                        }

                        "template" -> return tb.process(t, HtmlTreeBuilderState.InHead)
                        else -> return anythingElse(t, tb)
                    }
                }

                Token.TokenType.EOF -> if (!tb.currentElementIs("html")) tb.error(this)
                else -> return anythingElse(t, tb)
            }
            return true
        }

        private fun anythingElse(t: Token?, tb: HtmlTreeBuilder): Boolean {
            tb.error(this)
            return false
        }
    },
    InSelectInTable {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (t.isStartTag() && inSorted(
                    t.asStartTag().normalName(),
                    Constants.InSelectTableEnd
                )
            ) {
                tb.error(this)
                tb.popStackToClose("select")
                tb.resetInsertionMode()
                return tb.process(t)
            } else if (t.isEndTag() && inSorted(
                    t.asEndTag().normalName(),
                    Constants.InSelectTableEnd
                )
            ) {
                tb.error(this)
                if (tb.inTableScope(t.asEndTag().normalName())) {
                    tb.popStackToClose("select")
                    tb.resetInsertionMode()
                    return (tb.process(t))
                } else return false
            } else {
                return tb.process(t, HtmlTreeBuilderState.InSelect)
            }
        }
    },
    InTemplate {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            val name: String
            when (t.type) {
                Token.TokenType.Character, Token.TokenType.Comment, Token.TokenType.Doctype -> tb.process(
                    t,
                    HtmlTreeBuilderState.InBody
                )

                Token.TokenType.StartTag -> {
                    name = t.asStartTag().normalName()
                    if (inSorted(name, Constants.InTemplateToHead)) tb.process(
                        t,
                        HtmlTreeBuilderState.InHead
                    )
                    else if (inSorted(name, Constants.InTemplateToTable)) {
                        tb.popTemplateMode()
                        tb.pushTemplateMode(HtmlTreeBuilderState.InTable)
                        tb.transition(HtmlTreeBuilderState.InTable)
                        return tb.process(t)
                    } else if (name == "col") {
                        tb.popTemplateMode()
                        tb.pushTemplateMode(HtmlTreeBuilderState.InColumnGroup)
                        tb.transition(HtmlTreeBuilderState.InColumnGroup)
                        return tb.process(t)
                    } else if (name == "tr") {
                        tb.popTemplateMode()
                        tb.pushTemplateMode(HtmlTreeBuilderState.InTableBody)
                        tb.transition(HtmlTreeBuilderState.InTableBody)
                        return tb.process(t)
                    } else if (name == "td" || name == "th") {
                        tb.popTemplateMode()
                        tb.pushTemplateMode(HtmlTreeBuilderState.InRow)
                        tb.transition(HtmlTreeBuilderState.InRow)
                        return tb.process(t)
                    } else {
                        tb.popTemplateMode()
                        tb.pushTemplateMode(HtmlTreeBuilderState.InBody)
                        tb.transition(HtmlTreeBuilderState.InBody)
                        return tb.process(t)
                    }
                }

                Token.TokenType.EndTag -> {
                    name = t.asEndTag().normalName()
                    if (name == "template") tb.process(t, HtmlTreeBuilderState.InHead)
                    else {
                        tb.error(this)
                        return false
                    }
                }

                Token.TokenType.EOF -> {
                    if (!tb.onStack("template")) { // stop parsing
                        return true
                    }
                    tb.error(this)
                    tb.popStackToClose("template")
                    tb.clearFormattingElementsToLastMarker()
                    tb.popTemplateMode()
                    tb.resetInsertionMode()
                    // spec deviation - if we did not break out of Template, stop processing, and don't worry about cleaning up ultra-deep template stacks
                    // limited depth because this can recurse and will blow stack if too deep
                    if (tb.state() !== HtmlTreeBuilderState.InTemplate && tb.templateModeSize() < 12) return tb.process(
                        t
                    )
                    else return true
                }

                else -> Validate.wtf("Unexpected state: " + t.type) // XmlDecl only in XmlTreeBuilder
            }
            return true
        }
    },
    AfterBody {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            val html = tb.getFromStack("html")
            if (HtmlTreeBuilderState.Companion.isWhitespace(t)) {
                // spec deviation - currently body is still on stack, but we want this to go to the html node
                if (html != null) tb.insertCharacterToElement(t.asCharacter(), html)
                else tb.process(t, HtmlTreeBuilderState.InBody) // will get into body
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment()) // into html node
            } else if (t.isDoctype()) {
                tb.error(this)
                return false
            } else if (t.isStartTag() && t.asStartTag().normalName() == "html") {
                return tb.process(t, HtmlTreeBuilderState.InBody)
            } else if (t.isEndTag() && t.asEndTag().normalName() == "html") {
                if (tb.isFragmentParsing()) {
                    tb.error(this)
                    return false
                } else {
                    if (html != null) tb.trackNodePosition(
                        html,
                        false
                    ) // track source position of close; html is left on stack, in case of trailers

                    tb.transition(HtmlTreeBuilderState.AfterAfterBody)
                }
            } else if (t.isEOF()) {
                // chillax! we're done
            } else {
                tb.error(this)
                tb.resetBody()
                return tb.process(t)
            }
            return true
        }
    },
    InFrameset {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (HtmlTreeBuilderState.Companion.isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter())
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment())
            } else if (t.isDoctype()) {
                tb.error(this)
                return false
            } else if (t.isStartTag()) {
                val start = t.asStartTag()
                when (start.normalName()) {
                    "html" -> return tb.process(start, HtmlTreeBuilderState.InBody)
                    "frameset" -> tb.insertElementFor(start)
                    "frame" -> tb.insertEmptyElementFor(start)
                    "noframes" -> return tb.process(start, HtmlTreeBuilderState.InHead)
                    else -> {
                        tb.error(this)
                        return false
                    }
                }
            } else if (t.isEndTag() && t.asEndTag().normalName() == "frameset") {
                if (!tb.currentElementIs("frameset")) { // spec checks if el is html; deviate to confirm we are about to pop the frameset el
                    tb.error(this)
                    return false
                } else {
                    tb.pop()
                    if (!tb.isFragmentParsing() && !tb.currentElementIs("frameset")) {
                        tb.transition(HtmlTreeBuilderState.AfterFrameset)
                    }
                }
            } else if (t.isEOF()) {
                if (!tb.currentElementIs("html")) {
                    tb.error(this)
                    return true
                }
            } else {
                tb.error(this)
                return false
            }
            return true
        }
    },
    AfterFrameset {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (HtmlTreeBuilderState.Companion.isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter())
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment())
            } else if (t.isDoctype()) {
                tb.error(this)
                return false
            } else if (t.isStartTag() && t.asStartTag().normalName() == "html") {
                return tb.process(t, HtmlTreeBuilderState.InBody)
            } else if (t.isEndTag() && t.asEndTag().normalName() == "html") {
                tb.transition(HtmlTreeBuilderState.AfterAfterFrameset)
            } else if (t.isStartTag() && t.asStartTag().normalName() == "noframes") {
                return tb.process(t, HtmlTreeBuilderState.InHead)
            } else if (t.isEOF()) {
                // cool your heels, we're complete
            } else {
                tb.error(this)
                return false
            }
            return true
        }
    },
    AfterAfterBody {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (t.isComment()) {
                tb.insertCommentNode(t.asComment())
            } else if (t.isDoctype() || (t.isStartTag() && t.asStartTag().normalName() == "html")) {
                return tb.process(t, HtmlTreeBuilderState.InBody)
            } else if (HtmlTreeBuilderState.Companion.isWhitespace(t)) {
                // spec deviation - body and html still on stack, but want this space to go after </html>
                val doc: Element? = tb.getDocument()
                tb.insertCharacterToElement(t.asCharacter(), doc)
            } else if (t.isEOF()) {
                // nice work chuck
            } else {
                tb.error(this)
                tb.resetBody()
                return tb.process(t)
            }
            return true
        }
    },
    AfterAfterFrameset {
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            if (t.isComment()) {
                tb.insertCommentNode(t.asComment())
            } else if (t.isDoctype() || HtmlTreeBuilderState.Companion.isWhitespace(t) || (t.isStartTag() && t.asStartTag()
                    .normalName() == "html")
            ) {
                return tb.process(t, HtmlTreeBuilderState.InBody)
            } else if (t.isEOF()) {
                // nice work chuck
            } else if (t.isStartTag() && t.asStartTag().normalName() == "noframes") {
                return tb.process(t, HtmlTreeBuilderState.InHead)
            } else {
                tb.error(this)
                return false
            }
            return true
        }
    },
    ForeignContent {
        // https://html.spec.whatwg.org/multipage/parsing.html#parsing-main-inforeign
        override fun process(t: Token, tb: HtmlTreeBuilder): Boolean {
            when (t.type) {
                Token.TokenType.Character -> {
                    val c = t.asCharacter()
                    if (HtmlTreeBuilderState.Companion.isWhitespace(c)) tb.insertCharacterNode(c)
                    else {
                        tb.insertCharacterNode(c, true) // replace nulls
                        tb.framesetOk(false)
                    }
                }

                Token.TokenType.Comment -> tb.insertCommentNode(t.asComment())
                Token.TokenType.Doctype -> tb.error(this)
                Token.TokenType.StartTag -> {
                    val start = t.asStartTag()
                    if (StringUtil.`in`(
                            start.normalName,
                            *Constants.InForeignToHtml
                        )
                    ) return processAsHtml(t, tb)
                    if (start.normalName == "font" && (start.hasAttributeIgnoreCase("color")
                                || start.hasAttributeIgnoreCase("face")
                                || start.hasAttributeIgnoreCase("size"))
                    ) return processAsHtml(t, tb)

                    // Any other start:
                    // (whatwg says to fix up tag name and attribute case per a table - we will preserve original case instead)
                    val namespace = tb.currentElement().tag().namespace()
                    tb.insertForeignElementFor(start, namespace)

                    // (self-closing handled in insert)
                    // if self-closing svg script -- level and execution elided

                    // seemingly not in spec, but as browser behavior, get into ScriptData state for svg script; and allow custom data tags
                    val textState =
                        tb.tagFor(start.tagName.value(), start.normalName, namespace, tb.settings)
                            .textState()
                    if (textState != null) {
                        if (start.normalName == "script") tb.tokeniser.transition(TokeniserState.ScriptData)
                        else tb.tokeniser.transition(textState)
                    }
                }

                Token.TokenType.EndTag -> {
                    val end = t.asEndTag()
                    if (end.normalName == "br" || end.normalName == "p") return processAsHtml(t, tb)
                    if (end.normalName == "script" && tb.currentElementIs(
                            "script",
                            Parser.Companion.NamespaceSvg
                        )
                    ) {
                        // script level and execution elided.
                        tb.pop()
                        return true
                    }

                    // Any other end tag
                    val stack = tb.getStack()
                    if (stack.isEmpty()) Validate.wtf("Stack unexpectedly empty")
                    val i = stack.size - 1
                    val el = stack.get(i)
                    if (!el.nameIs(end.normalName)) tb.error(this)
                    while (i != 0) {
                        if (el.nameIs(end.normalName)) {
                            tb.popStackToCloseAnyNamespace(el.normalName())
                            return true
                        }
                        i--
                        el = stack.get(i)
                        if (el.tag().namespace() == Parser.Companion.NamespaceHtml) {
                            return processAsHtml(t, tb)
                        }
                    }
                }

                Token.TokenType.EOF -> {}
                else -> Validate.wtf("Unexpected state: " + t.type) // XmlDecl only in XmlTreeBuilder
            }
            return true
        }

        fun processAsHtml(t: Token?, tb: HtmlTreeBuilder): Boolean {
            return tb.state().process(t, tb)
        }
    };

    abstract fun process(t: Token?, tb: HtmlTreeBuilder?): Boolean

    // lists of tags to search through
    internal object Constants {
        val InHeadEmpty: Array<String?> =
            arrayOf<String>("base", "basefont", "bgsound", "command", "link")
        val InHeadRaw: Array<String?> = arrayOf<String>("noframes", "style")
        val InHeadEnd: Array<String?> = arrayOf<String>("body", "br", "html")
        val AfterHeadBody: Array<String?> = arrayOf<String>("body", "br", "html")
        val BeforeHtmlToHead: Array<String?> = arrayOf<String>("body", "br", "head", "html")
        val InHeadNoScriptHead: Array<String?> =
            arrayOf<String>("basefont", "bgsound", "link", "meta", "noframes", "style")
        val InBodyStartToHead: Array<String?> = arrayOf<String>(
            "base",
            "basefont",
            "bgsound",
            "command",
            "link",
            "meta",
            "noframes",
            "script",
            "style",
            "template",
            "title"
        )
        val InBodyStartPClosers: Array<String?> = arrayOf<String>(
            "address", "article", "aside", "blockquote", "center", "details", "dir", "div", "dl",
            "fieldset", "figcaption", "figure", "footer", "header", "hgroup", "menu", "nav", "ol",
            "p", "section", "summary", "ul"
        )
        val Headings: Array<String?> = arrayOf<String>("h1", "h2", "h3", "h4", "h5", "h6")
        val InBodyStartLiBreakers: Array<String?> = arrayOf<String>("address", "div", "p")
        val DdDt: Array<String?> = arrayOf<String>("dd", "dt")
        val InBodyStartApplets: Array<String?> = arrayOf<String>("applet", "marquee", "object")
        val InBodyStartMedia: Array<String?> = arrayOf<String>("param", "source", "track")
        val InBodyStartInputAttribs: Array<String?> = arrayOf<String>("action", "name", "prompt")
        val InBodyStartDrop: Array<String?> = arrayOf<String>(
            "caption",
            "col",
            "colgroup",
            "frame",
            "head",
            "tbody",
            "td",
            "tfoot",
            "th",
            "thead",
            "tr"
        )
        val InBodyEndClosers: Array<String?> = arrayOf<String>(
            "address",
            "article",
            "aside",
            "blockquote",
            "button",
            "center",
            "details",
            "dir",
            "div",
            "dl",
            "fieldset",
            "figcaption",
            "figure",
            "footer",
            "header",
            "hgroup",
            "listing",
            "menu",
            "nav",
            "ol",
            "pre",
            "section",
            "summary",
            "ul"
        )
        val InBodyEndOtherErrors: Array<String?> = arrayOf<String>(
            "body",
            "dd",
            "dt",
            "html",
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
        val InBodyEndAdoptionFormatters: Array<String?> = arrayOf<String>(
            "a",
            "b",
            "big",
            "code",
            "em",
            "font",
            "i",
            "nobr",
            "s",
            "small",
            "strike",
            "strong",
            "tt",
            "u"
        )
        val InTableToBody: Array<String?> = arrayOf<String>("tbody", "tfoot", "thead")
        val InTableAddBody: Array<String?> = arrayOf<String>("td", "th", "tr")
        val InTableToHead: Array<String?> = arrayOf<String>("script", "style", "template")
        val InCellNames: Array<String?> = arrayOf<String>("td", "th")
        val InCellBody: Array<String?> =
            arrayOf<String>("body", "caption", "col", "colgroup", "html")
        val InCellTable: Array<String?> = arrayOf<String>("table", "tbody", "tfoot", "thead", "tr")
        val InCellCol: Array<String?> = arrayOf<String>(
            "caption",
            "col",
            "colgroup",
            "tbody",
            "td",
            "tfoot",
            "th",
            "thead",
            "tr"
        )
        val InTableEndErr: Array<String?> = arrayOf<String>(
            "body",
            "caption",
            "col",
            "colgroup",
            "html",
            "tbody",
            "td",
            "tfoot",
            "th",
            "thead",
            "tr"
        )
        val InTableFoster: Array<String?> =
            arrayOf<String>("table", "tbody", "tfoot", "thead", "tr")
        val InTableBodyExit: Array<String?> =
            arrayOf<String>("caption", "col", "colgroup", "tbody", "tfoot", "thead")
        val InTableBodyEndIgnore: Array<String?> =
            arrayOf<String>("body", "caption", "col", "colgroup", "html", "td", "th", "tr")
        val InRowMissing: Array<String?> =
            arrayOf<String>("caption", "col", "colgroup", "tbody", "tfoot", "thead", "tr")
        val InRowIgnore: Array<String?> =
            arrayOf<String>("body", "caption", "col", "colgroup", "html", "td", "th")
        val InSelectEnd: Array<String?> = arrayOf<String>("input", "keygen", "textarea")
        val InSelectTableEnd: Array<String?> =
            arrayOf<String>("caption", "table", "tbody", "td", "tfoot", "th", "thead", "tr")
        val InTableEndIgnore: Array<String?> = arrayOf<String>("tbody", "tfoot", "thead")
        val InHeadNoscriptIgnore: Array<String?> = arrayOf<String>("head", "noscript")
        val InCaptionIgnore: Array<String?> = arrayOf<String>(
            "body",
            "col",
            "colgroup",
            "html",
            "tbody",
            "td",
            "tfoot",
            "th",
            "thead",
            "tr"
        )
        val InTemplateToHead: Array<String?> = arrayOf<String>(
            "base",
            "basefont",
            "bgsound",
            "link",
            "meta",
            "noframes",
            "script",
            "style",
            "template",
            "title"
        )
        val InTemplateToTable: Array<String?> =
            arrayOf<String>("caption", "colgroup", "tbody", "tfoot", "thead")
        val InForeignToHtml: Array<String?> = arrayOf<String>(
            "b",
            "big",
            "blockquote",
            "body",
            "br",
            "center",
            "code",
            "dd",
            "div",
            "dl",
            "dt",
            "em",
            "embed",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "head",
            "hr",
            "i",
            "img",
            "li",
            "listing",
            "menu",
            "meta",
            "nobr",
            "ol",
            "p",
            "pre",
            "ruby",
            "s",
            "small",
            "span",
            "strike",
            "strong",
            "sub",
            "sup",
            "table",
            "tt",
            "u",
            "ul",
            "var"
        )
    }

    companion object {
        private fun mergeAttributes(source: Token.StartTag, dest: Element) {
            if (!source.hasAttributes()) return
            for (attr in source.attributes) { // only iterates public attributes
                val destAttrs = dest.attributes()
                if (!destAttrs.hasKey(attr.key)) {
                    val range: AttributeRange? =
                        attr.sourceRange() // need to grab range before its parent changes
                    destAttrs.put(attr)
                    if (source.trackSource) { // copy the attribute range
                        destAttrs.sourceRange(attr.key, range)
                    }
                }
            }
        }

        private val nullString = '\u0000'.toString()

        private fun isWhitespace(t: Token): Boolean {
            if (t.isCharacter()) {
                val data = t.asCharacter().getData()
                return StringUtil.isBlank(data)
            }
            return false
        }

        private fun HandleTextState(
            startTag: Token.StartTag?,
            tb: HtmlTreeBuilder,
            @Nullable state: TokeniserState?
        ) {
            if (state != null) tb.tokeniser.transition(state)
            tb.markInsertionMode()
            tb.transition(HtmlTreeBuilderState.Text)
            tb.insertElementFor(startTag)
        }
    }
}
