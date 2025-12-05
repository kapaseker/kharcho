package io.kapaseker.kharcho.safety

import io.kapaseker.kharcho.helper.Validate.notNull
import io.kapaseker.kharcho.nodes.*
import io.kapaseker.kharcho.parser.ParseErrorList.Companion.tracking
import io.kapaseker.kharcho.parser.Parser.Companion.parseFragment
import io.kapaseker.kharcho.select.NodeVisitor

/**
 * The safelist based HTML cleaner. Use to ensure that end-user provided HTML contains only the elements and attributes
 * that you are expecting; no junk, and no cross-site scripting attacks!
 *
 *
 * The HTML cleaner parses the input as HTML and then runs it through a safe-list, so the output HTML can only contain
 * HTML that is allowed by the safelist.
 *
 *
 *
 * It is assumed that the input HTML is a body fragment; the clean methods only pull from the source's body, and the
 * canned safe-lists only allow body contained tags.
 *
 *
 *
 * Rather than interacting directly with a Cleaner object, generally see the `clean` methods in [io.kapaseker.kharcho.Jsoup].
 *
 */
class Cleaner(safelist: Safelist) {
    private val safelist: Safelist

    /**
     * Create a new cleaner, that sanitizes documents using the supplied safelist.
     * @param safelist safe-list to clean with
     */
    init {
        notNull(safelist)
        this.safelist = safelist
    }

    /**
     * Creates a new, clean document, from the original dirty document, containing only elements allowed by the safelist.
     * The original document is not modified. Only elements from the dirty document's `body` are used. The
     * OutputSettings of the original document are cloned into the clean document.
     * @param dirtyDocument Untrusted base document to clean.
     * @return cleaned document.
     */
    fun clean(dirtyDocument: Document): Document {
        notNull(dirtyDocument)

        val clean = Document.createShell(dirtyDocument.baseUri())
        copySafeNodes(dirtyDocument.body(), clean.body())
        clean.outputSettings(dirtyDocument.outputSettings().clone())

        return clean
    }

    /**
     * Determines if the input document's **body** is valid, against the safelist. It is considered valid if all the
     * tags and attributes in the input HTML are allowed by the safelist, and that there is no content in the
     * `head`.
     *
     *
     * This method is intended to be used in a user interface as a validator for user input. Note that regardless of the
     * output of this method, the input document **must always** be normalized using a method such as
     * [.clean], and the result of that method used to store or serialize the document before later reuse
     * such as presentation to end users. This ensures that enforced attributes are set correctly, and that any
     * differences between how a given browser and how jsoup parses the input HTML are normalized.
     *
     *
     * Example:
     * <pre>`Document inputDoc = Jsoup.parse(inputHtml);
     * Cleaner cleaner = new Cleaner(Safelist.relaxed());
     * boolean isValid = cleaner.isValid(inputDoc);
     * Document normalizedDoc = cleaner.clean(inputDoc);
    `</pre> *
     *
     * @param dirtyDocument document to test
     * @return true if no tags or attributes need to be removed; false if they do
     */
    fun isValid(dirtyDocument: Document): Boolean {
        notNull(dirtyDocument)

        val clean = Document.createShell(dirtyDocument.baseUri())
        val numDiscarded = copySafeNodes(dirtyDocument.body(), clean.body())
        return numDiscarded == 0
                && dirtyDocument.head().childNodes()
            .isEmpty() // because we only look at the body, but we start from a shell, make sure there's nothing in the head
    }

    /**
     * Determines if the input document's **body HTML** is valid, against the safelist. It is considered valid if all
     * the tags and attributes in the input HTML are allowed by the safelist.
     *
     *
     * This method is intended to be used in a user interface as a validator for user input. Note that regardless of the
     * output of this method, the input document **must always** be normalized using a method such as
     * [.clean], and the result of that method used to store or serialize the document before later reuse
     * such as presentation to end users. This ensures that enforced attributes are set correctly, and that any
     * differences between how a given browser and how jsoup parses the input HTML are normalized.
     *
     *
     * Example:
     * <pre>`Document inputDoc = Jsoup.parse(inputHtml);
     * Cleaner cleaner = new Cleaner(Safelist.relaxed());
     * boolean isValid = cleaner.isValidBodyHtml(inputHtml);
     * Document normalizedDoc = cleaner.clean(inputDoc);
    `</pre> *
     *
     * @param bodyHtml HTML fragment to test
     * @return true if no tags or attributes need to be removed; false if they do
     */
    fun isValidBodyHtml(bodyHtml: String): Boolean {
        val baseUri =
            if (safelist.preserveRelativeLinks()) DummyUri else "" // fake base URI to allow relative URLs to remain valid
        val clean = Document.createShell(baseUri)
        val dirty = Document.createShell(baseUri)
        val errorList = tracking(1)
        val nodes = parseFragment(bodyHtml, dirty.body(), baseUri, errorList)
        dirty.body().insertChildren(0, nodes)
        val numDiscarded = copySafeNodes(dirty.body(), clean.body())
        return numDiscarded == 0 && errorList.isEmpty()
    }

    /**
     * Iterates the input and copies trusted nodes (tags, attributes, text) into the destination.
     */
    private inner class CleaningVisitor(
        private val root: Element?, // current element to append nodes to
        private var destination: Element
    ) : NodeVisitor {
        private var numDiscarded = 0

        init {
            this.destination = destination
        }

        override fun head(source: Node?, depth: Int) {
            if (source is Element) {
                val sourceEl = source

                if (safelist.isSafeTag(sourceEl.normalName())) { // safe, clone and copy safe attrs
                    val meta = createSafeElement(sourceEl)
                    val destChild = meta.el
                    destination.appendChild(destChild)

                    numDiscarded += meta.numAttribsDiscarded
                    destination = destChild
                } else if (source !== root) { // not a safe tag, so don't add. don't count root against discarded.
                    numDiscarded++
                }
            } else if (source is TextNode) {
                val sourceText = source
                val destText = TextNode(sourceText.getWholeText())
                destination.appendChild(destText)
            } else if (source is DataNode && safelist.isSafeTag(source.parent().normalName())) {
                val sourceData = source
                val destData = DataNode(sourceData.getWholeData())
                destination.appendChild(destData)
            } else { // else, we don't care about comments, xml proc instructions, etc
                numDiscarded++
            }
        }

        override fun tail(source: Node?, depth: Int) {
            if (source is Element && safelist.isSafeTag(source.normalName())) {
                destination = destination.parent() // would have descended, so pop destination stack
            }
        }
    }

    private fun copySafeNodes(source: Element?, dest: Element): Int {
        val cleaningVisitor: CleaningVisitor = Cleaner.CleaningVisitor(source, dest)
        cleaningVisitor.traverse(source)
        return cleaningVisitor.numDiscarded
    }

    private fun createSafeElement(sourceEl: Element): ElementMeta {
        val dest =
            sourceEl.shallowClone() // reuses tag, clones attributes and preserves any user data
        val sourceTag = sourceEl.tagName()
        val destAttrs = dest.attributes()
        dest.clearAttributes() // clear all non-internal attributes, ready for safe copy

        var numDiscarded = 0
        val sourceAttrs = sourceEl.attributes()
        for (sourceAttr in sourceAttrs) {
            if (safelist.isSafeAttribute(sourceTag, sourceEl, sourceAttr)) destAttrs.put(sourceAttr)
            else numDiscarded++
        }


        val enforcedAttrs = safelist.getEnforcedAttributes(sourceTag)
        // special case for <a href rel=nofollow>, only apply to external links:
        if (sourceEl.nameIs("a") && enforcedAttrs.get("rel") == "nofollow") {
            val href = sourceEl.absUrl("href")
            val sourceBase = sourceEl.baseUri()
            if (!href.isEmpty() && !sourceBase.isEmpty() && href.startsWith(sourceBase)) { // same site, so don't set the nofollow
                enforcedAttrs.remove("rel")
            }
        }

        destAttrs.addAll(enforcedAttrs)
        dest.attributes().addAll(destAttrs) // re-attach, if removed in clear
        return ElementMeta(dest, numDiscarded)
    }

    private class ElementMeta(el: Element, numAttribsDiscarded: Int) {
        var el: Element
        var numAttribsDiscarded: Int

        init {
            this.el = el
            this.numAttribsDiscarded = numAttribsDiscarded
        }
    }
}
