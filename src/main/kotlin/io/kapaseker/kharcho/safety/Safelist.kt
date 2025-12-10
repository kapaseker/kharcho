package io.kapaseker.kharcho.safety

import io.kapaseker.kharcho.helper.Validate.isFalse
import io.kapaseker.kharcho.helper.Validate.isTrue
import io.kapaseker.kharcho.helper.Validate.notEmpty
import io.kapaseker.kharcho.helper.Validate.notNull
import io.kapaseker.kharcho.internal.Normalizer.lowerCase
import io.kapaseker.kharcho.nodes.Attributes
import io.kapaseker.kharcho.nodes.Element

/*
   Thank you to Ryan Grove (wonko.com) for the Ruby HTML cleaner http://github.com/rgrove/sanitize/, which inspired
   this safe-list configuration, and the initial defaults.
*/

/**
 * Safe-lists define what HTML (elements and attributes) to allow through the cleaner. Everything else is removed.
 *
 *
 * Start with one of the defaults:
 *
 *
 *  * [.none]
 *  * [.simpleText]
 *  * [.basic]
 *  * [.basicWithImages]
 *  * [.relaxed]
 *
 *
 *
 * If you need to allow more through (please be careful!), tweak a base safelist with:
 *
 *
 *  * [.addTags]
 *  * [.addAttributes]
 *  * [.addEnforcedAttribute]
 *  * [.addProtocols]
 *
 *
 *
 * You can remove any setting from an existing safelist with:
 *
 *
 *  * [.removeTags]
 *  * [.removeAttributes]
 *  * [.removeEnforcedAttribute]
 *  * [.removeProtocols]
 *
 *
 *
 *
 * The cleaner and these safelists assume that you want to clean a `body` fragment of HTML (to add user
 * supplied HTML into a templated page), and not to clean a full HTML document. If the latter is the case, you could wrap
 * the templated document HTML around the cleaned body HTML.
 *
 *
 *
 * If you are going to extend a safelist, please be very careful. Make sure you understand what attributes may lead to
 * XSS attack vectors. URL attributes are particularly vulnerable and require careful validation. See
 * the [XSS Filter Evasion Cheat Sheet](https://owasp.org/www-community/xss-filter-evasion-cheatsheet) for some
 * XSS attack examples (that jsoup will safegaurd against the default Cleaner and Safelist configuration).
 *
 */
class Safelist() {
    private val tagNames: MutableSet<TagName?> // tags allowed, lower case. e.g. [p, br, span]
    private val attributes: MutableMap<TagName?, MutableSet<AttributeKey?>> // tag -> attribute[]. allowed attributes [href] for a tag.
    private val enforcedAttributes: MutableMap<TagName?, MutableMap<AttributeKey?, AttributeValue?>> // always set these attribute values
    private val protocols: MutableMap<TagName?, MutableMap<AttributeKey?, MutableSet<Protocol?>>> // allowed URL protocols for attributes
    private var preserveRelativeLinks: Boolean // option to preserve relative links

    /**
     * Create a new, empty safelist. Generally it will be better to start with a default prepared safelist instead.
     *
     * @see .basic
     * @see .basicWithImages
     * @see .simpleText
     * @see .relaxed
     */
    init {
        tagNames = HashSet<TagName?>()
        attributes = HashMap<TagName?, MutableSet<AttributeKey?>>()
        enforcedAttributes = HashMap<TagName?, MutableMap<AttributeKey?, AttributeValue?>>()
        protocols = HashMap<TagName?, MutableMap<AttributeKey?, MutableSet<Protocol?>>>()
        preserveRelativeLinks = false
    }

    /**
     * Deep copy an existing Safelist to a new Safelist.
     * @param copy the Safelist to copy
     */
    constructor(copy: Safelist) : this() {
        tagNames.addAll(copy.tagNames)
        for (copyTagAttributes in copy.attributes.entries) {
            attributes.put(copyTagAttributes.key, HashSet<AttributeKey?>(copyTagAttributes.value))
        }
        for (enforcedEntry in copy.enforcedAttributes.entries) {
            enforcedAttributes.put(
                enforcedEntry.key,
                HashMap<AttributeKey?, AttributeValue?>(enforcedEntry.value)
            )
        }
        for (protocolsEntry in copy.protocols.entries) {
            val attributeProtocolsCopy: MutableMap<AttributeKey?, MutableSet<Protocol?>?> =
                HashMap<AttributeKey?, MutableSet<Protocol?>?>()
            for (attributeProtocols in protocolsEntry.value.entries) {
                attributeProtocolsCopy.put(
                    attributeProtocols.key,
                    HashSet<Protocol?>(attributeProtocols.value)
                )
            }
            protocols.put(protocolsEntry.key, attributeProtocolsCopy)
        }
        preserveRelativeLinks = copy.preserveRelativeLinks
    }

    /**
     * Add a list of allowed elements to a safelist. (If a tag is not allowed, it will be removed from the HTML.)
     *
     * @param tags tag names to allow
     * @return this (for chaining)
     */
    fun addTags(vararg tags: String): Safelist {
        notNull(tags)

        for (tagName in tags) {
            notEmpty(tagName)
            isFalse(
                tagName.equals("noscript", ignoreCase = true),
                "noscript is unsupported in Safelists, due to incompatibilities between parsers with and without script-mode enabled"
            )
            tagNames.add(TagName.Companion.valueOf(tagName))
        }
        return this
    }

    /**
     * Remove a list of allowed elements from a safelist. (If a tag is not allowed, it will be removed from the HTML.)
     *
     * @param tags tag names to disallow
     * @return this (for chaining)
     */
    fun removeTags(vararg tags: String): Safelist {
        notNull(tags)

        for (tag in tags) {
            notEmpty(tag)
            val tagName: TagName = TagName.Companion.valueOf(tag)

            if (tagNames.remove(tagName)) { // Only look in sub-maps if tag was allowed
                attributes.remove(tagName)
                enforcedAttributes.remove(tagName)
                protocols.remove(tagName)
            }
        }
        return this
    }

    /**
     * Add a list of allowed attributes to a tag. (If an attribute is not allowed on an element, it will be removed.)
     *
     *
     * E.g.: `addAttributes("a", "href", "class")` allows `href` and `class` attributes
     * on `a` tags.
     *
     *
     *
     * To make an attribute valid for **all tags**, use the pseudo tag `:all`, e.g.
     * `addAttributes(":all", "class")`.
     *
     *
     * @param tag  The tag the attributes are for. The tag will be added to the allowed tag list if necessary.
     * @param attributes List of valid attributes for the tag
     * @return this (for chaining)
     */
    fun addAttributes(tag: String, vararg attributes: String): Safelist {
        notEmpty(tag)
        notNull(attributes)
        isTrue(attributes.size > 0, "No attribute names supplied.")

        addTags(tag)
        val tagName: TagName = TagName.Companion.valueOf(tag)
        val attributeSet: MutableSet<AttributeKey?> = HashSet<AttributeKey?>()
        for (key in attributes) {
            notEmpty(key)
            attributeSet.add(AttributeKey.Companion.valueOf(key))
        }
        val currentSet =
            this.attributes.computeIfAbsent(tagName) { k: TagName? -> HashSet<AttributeKey?>() }
        currentSet.addAll(attributeSet)
        return this
    }

    /**
     * Remove a list of allowed attributes from a tag. (If an attribute is not allowed on an element, it will be removed.)
     *
     *
     * E.g.: `removeAttributes("a", "href", "class")` disallows `href` and `class`
     * attributes on `a` tags.
     *
     *
     *
     * To make an attribute invalid for **all tags**, use the pseudo tag `:all`, e.g.
     * `removeAttributes(":all", "class")`.
     *
     *
     * @param tag  The tag the attributes are for.
     * @param attributes List of invalid attributes for the tag
     * @return this (for chaining)
     */
    fun removeAttributes(tag: String, vararg attributes: String): Safelist {
        notEmpty(tag)
        notNull(attributes)
        isTrue(attributes.size > 0, "No attribute names supplied.")

        val tagName: TagName = TagName.Companion.valueOf(tag)
        val attributeSet: MutableSet<AttributeKey?> = HashSet<AttributeKey?>()
        for (key in attributes) {
            notEmpty(key)
            attributeSet.add(AttributeKey.Companion.valueOf(key))
        }
        if (tagNames.contains(tagName) && this.attributes.containsKey(tagName)) { // Only look in sub-maps if tag was allowed
            val currentSet: MutableSet<AttributeKey?> = this.attributes.get(tagName)!!
            currentSet.removeAll(attributeSet)

            if (currentSet.isEmpty())  // Remove tag from attribute map if no attributes are allowed for tag
                this.attributes.remove(tagName)
        }
        if (tag == All) { // Attribute needs to be removed from all individually set tags
            val it = this.attributes.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                val currentSet = entry.value
                currentSet.removeAll(attributeSet)
                if (currentSet.isEmpty())  // Remove tag from attribute map if no attributes are allowed for tag
                    it.remove()
            }
        }
        return this
    }

    /**
     * Add an enforced attribute to a tag. An enforced attribute will always be added to the element. If the element
     * already has the attribute set, it will be overridden with this value.
     *
     *
     * E.g.: `addEnforcedAttribute("a", "rel", "nofollow")` will make all `a` tags output as
     * `<a href="..." rel="nofollow">`
     *
     *
     * @param tag   The tag the enforced attribute is for. The tag will be added to the allowed tag list if necessary.
     * @param attribute   The attribute name
     * @param value The enforced attribute value
     * @return this (for chaining)
     */
    fun addEnforcedAttribute(tag: String, attribute: String, value: String): Safelist {
        notEmpty(tag)
        notEmpty(attribute)
        notEmpty(value)

        val tagName: TagName = TagName.Companion.valueOf(tag)
        tagNames.add(tagName)
        val attrKey: AttributeKey = AttributeKey.Companion.valueOf(attribute)
        val attrVal: AttributeValue = AttributeValue.Companion.valueOf(value)

        val attrMap =
            enforcedAttributes.computeIfAbsent(tagName) { k: TagName? -> HashMap<AttributeKey?, AttributeValue?>() }
        attrMap.put(attrKey, attrVal)
        return this
    }

    /**
     * Remove a previously configured enforced attribute from a tag.
     *
     * @param tag   The tag the enforced attribute is for.
     * @param attribute   The attribute name
     * @return this (for chaining)
     */
    fun removeEnforcedAttribute(tag: String, attribute: String): Safelist {
        notEmpty(tag)
        notEmpty(attribute)

        val tagName: TagName = TagName.Companion.valueOf(tag)
        if (tagNames.contains(tagName) && enforcedAttributes.containsKey(tagName)) {
            val attrKey: AttributeKey = AttributeKey.Companion.valueOf(attribute)
            val attrMap: MutableMap<AttributeKey?, AttributeValue?> =
                enforcedAttributes.get(tagName)!!
            attrMap.remove(attrKey)

            if (attrMap.isEmpty())  // Remove tag from enforced attribute map if no enforced attributes are present
                enforcedAttributes.remove(tagName)
        }
        return this
    }

    /**
     * Configure this Safelist to preserve relative links in an element's URL attribute, or convert them to absolute
     * links. By default, this is **false**: URLs will be  made absolute (e.g. start with an allowed protocol, like
     * e.g. `http://`.
     *
     * @param preserve `true` to allow relative links, `false` (default) to deny
     * @return this Safelist, for chaining.
     * @see .addProtocols
     */
    fun preserveRelativeLinks(preserve: Boolean): Safelist {
        preserveRelativeLinks = preserve
        return this
    }

    /**
     * Get the current setting for preserving relative links.
     * @return `true` if relative links are preserved, `false` if they are converted to absolute.
     */
    fun preserveRelativeLinks(): Boolean {
        return preserveRelativeLinks
    }

    /**
     * Add allowed URL protocols for an element's URL attribute. This restricts the possible values of the attribute to
     * URLs with the defined protocol.
     *
     *
     * E.g.: `addProtocols("a", "href", "ftp", "http", "https")`
     *
     *
     *
     * To allow a link to an in-page URL anchor (i.e. `<a href="#anchor">`, add a `#`:<br></br>
     * E.g.: `addProtocols("a", "href", "#")`
     *
     *
     * @param tag       Tag the URL protocol is for
     * @param attribute       Attribute name
     * @param protocols List of valid protocols
     * @return this, for chaining
     */
    fun addProtocols(tag: String, attribute: String, vararg protocols: String): Safelist {
        notEmpty(tag)
        notEmpty(attribute)
        notNull(protocols)

        val tagName: TagName = TagName.Companion.valueOf(tag)
        val attrKey: AttributeKey = AttributeKey.Companion.valueOf(attribute)
        val attrMap =
            this.protocols.computeIfAbsent(tagName) { k: TagName? -> HashMap<AttributeKey?, MutableSet<Protocol?>?>() }
        val protSet = attrMap.computeIfAbsent(attrKey) { k: AttributeKey? -> HashSet<Protocol?>() }

        for (protocol in protocols) {
            notEmpty(protocol)
            val prot: Protocol = Protocol.Companion.valueOf(protocol)
            protSet.add(prot)
        }
        return this
    }

    /**
     * Remove allowed URL protocols for an element's URL attribute. If you remove all protocols for an attribute, that
     * attribute will allow any protocol.
     *
     *
     * E.g.: `removeProtocols("a", "href", "ftp")`
     *
     *
     * @param tag Tag the URL protocol is for
     * @param attribute Attribute name
     * @param removeProtocols List of invalid protocols
     * @return this, for chaining
     */
    fun removeProtocols(tag: String, attribute: String, vararg removeProtocols: String): Safelist {
        notEmpty(tag)
        notEmpty(attribute)
        notNull(removeProtocols)

        val tagName: TagName = TagName.Companion.valueOf(tag)
        val attr: AttributeKey = AttributeKey.Companion.valueOf(attribute)

        // make sure that what we're removing actually exists; otherwise can open the tag to any data and that can
        // be surprising
        isTrue(protocols.containsKey(tagName), "Cannot remove a protocol that is not set.")
        val tagProtocols: MutableMap<AttributeKey?, MutableSet<Protocol?>> =
            protocols.get(tagName)!!
        isTrue(tagProtocols.containsKey(attr), "Cannot remove a protocol that is not set.")

        val attrProtocols: MutableSet<Protocol?> = tagProtocols.get(attr)!!
        for (protocol in removeProtocols) {
            notEmpty(protocol)
            attrProtocols.remove(Protocol.Companion.valueOf(protocol))
        }

        if (attrProtocols.isEmpty()) { // Remove protocol set if empty
            tagProtocols.remove(attr)
            if (tagProtocols.isEmpty())  // Remove entry for tag if empty
                protocols.remove(tagName)
        }
        return this
    }

    /**
     * Test if the supplied tag is allowed by this safelist.
     * @param tag test tag
     * @return true if allowed
     */
    fun isSafeTag(tag: String?): Boolean {
        return tagNames.contains(TagName.Companion.valueOf(tag))
    }

    /**
     * Test if the supplied attribute is allowed by this safelist for this tag.
     * @param tagName tag to consider allowing the attribute in
     * @param el element under test, to confirm protocol
     * @param attr attribute under test
     * @return true if allowed
     */
    fun isSafeAttribute(tagName: String, el: Element, attr: Attribute): Boolean {
        val tag: TagName = TagName.Companion.valueOf(tagName)
        val key: AttributeKey = AttributeKey.Companion.valueOf(attr.key)

        val okSet = attributes.get(tag)
        if (okSet != null && okSet.contains(key)) {
            if (protocols.containsKey(tag)) {
                val attrProts: MutableMap<AttributeKey?, MutableSet<Protocol?>?> =
                    protocols.get(tag)
                // ok if not defined protocol; otherwise test
                return !attrProts.containsKey(key) || testValidProtocol(
                    el,
                    attr,
                    attrProts.get(key)
                )
            } else { // attribute found, no protocols defined, so OK
                return true
            }
        }
        // might be an enforced attribute?
        val enforcedSet = enforcedAttributes.get(tag)
        if (enforcedSet != null) {
            val expect = getEnforcedAttributes(tagName)
            val attrKey = attr.key
            if (expect.hasKeyIgnoreCase(attrKey)) {
                return expect.getIgnoreCase(attrKey) == attr.value
            }
        }
        // no attributes defined for tag, try :all tag
        return tagName != All && isSafeAttribute(All, el, attr)
    }

    private fun testValidProtocol(
        el: Element,
        attr: Attribute,
        protocols: MutableSet<Protocol>
    ): Boolean {
        // try to resolve relative urls to abs, and optionally update the attribute so output html has abs.
        // rels without a baseuri get removed
        var value = el.absUrl(attr.key)
        if (value.length == 0) value =
            attr.value // if it could not be made abs, run as-is to allow custom unknown protocols

        if (!preserveRelativeLinks) attr.setValue(value)

        for (protocol in protocols) {
            var prot = protocol.toString()

            if (prot == "#") { // allows anchor links
                if (isValidAnchor(value)) {
                    return true
                } else {
                    continue
                }
            }

            prot += ":"

            if (lowerCase(value).startsWith(prot)) {
                return true
            }
        }
        return false
    }

    /**
     * Gets the Attributes that should be enforced for a given tag
     * @param tagName the tag
     * @return the attributes that will be enforced; empty if none are set for the given tag
     */
    fun getEnforcedAttributes(tagName: String?): Attributes {
        val attrs = Attributes()
        val tag: TagName = TagName.Companion.valueOf(tagName)
        if (enforcedAttributes.containsKey(tag)) {
            val keyVals: MutableMap<AttributeKey?, AttributeValue?> = enforcedAttributes.get(tag)!!
            for (entry in keyVals.entries) {
                attrs.put(entry.key.toString(), entry.value.toString())
            }
        }
        return attrs
    }

    // named types for config. All just hold strings, but here for my sanity.
    internal class TagName(value: String) : TypedValue(value) {
        companion object {
            fun valueOf(value: String?): TagName {
                return TagName(lowerCase(value))
            }
        }
    }

    internal class AttributeKey(value: String) : TypedValue(value) {
        companion object {
            fun valueOf(value: String?): AttributeKey {
                return AttributeKey(lowerCase(value))
            }
        }
    }

    internal class AttributeValue(value: String) : TypedValue(value) {
        companion object {
            fun valueOf(value: String): AttributeValue {
                return AttributeValue(value)
            }
        }
    }

    internal class Protocol(value: String) : TypedValue(value) {
        companion object {
            fun valueOf(value: String): Protocol {
                return Protocol(value)
            }
        }
    }

    internal abstract class TypedValue(value: String) {
        private val value: String

        init {
            notNull(value)
            this.value = value
        }

        override fun hashCode(): Int {
            return value.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) return true
            if (obj == null || javaClass != obj.javaClass) return false
            val other = obj as TypedValue
            return value == other.value
        }

        override fun toString(): String {
            return value
        }
    }

    companion object {
        private const val All = ":all"

        /**
         * This safelist allows only text nodes: any HTML Element or any Node other than a TextNode will be removed.
         *
         *
         * Note that the output of [io.kapaseker.kharcho.Jsoup.clean] is still **HTML** even when using
         * this Safelist, and so any HTML entities in the output will be appropriately escaped. If you want plain text, not
         * HTML, you should use a text method such as [Element.text] instead, after cleaning the document.
         *
         *
         * Example:
         * <pre>`String sourceBodyHtml = "<p>5 is &lt; 6.</p>";
         * String html = Jsoup.clean(sourceBodyHtml, Safelist.none());
         *
         * Cleaner cleaner = new Cleaner(Safelist.none());
         * String text = cleaner.clean(Jsoup.parse(sourceBodyHtml)).text();
         *
         * // html is: 5 is &lt; 6.
         * // text is: 5 is < 6.
        `</pre> *
         *
         * @return safelist
         */
        fun none(): Safelist {
            return Safelist()
        }

        /**
         * This safelist allows only simple text formatting: `b, em, i, strong, u`. All other HTML (tags and
         * attributes) will be removed.
         *
         * @return safelist
         */
        fun simpleText(): Safelist {
            return Safelist()
                .addTags("b", "em", "i", "strong", "u")
        }

        /**
         *
         *
         * This safelist allows a fuller range of text nodes: `a, b, blockquote, br, cite, code, dd, dl, dt, em, i, li,
         * ol, p, pre, q, small, span, strike, strong, sub, sup, u, ul`, and appropriate attributes.
         *
         *
         *
         * Links (`a` elements) can point to `http, https, ftp, mailto`, and have an enforced
         * `rel=nofollow` attribute if they link offsite (as indicated by the specified base URI).
         *
         *
         *
         * Does not allow images.
         *
         *
         * @return safelist
         */
        fun basic(): Safelist {
            return Safelist()
                .addTags(
                    "a", "b", "blockquote", "br", "cite", "code", "dd", "dl", "dt", "em",
                    "i", "li", "ol", "p", "pre", "q", "small", "span", "strike", "strong", "sub",
                    "sup", "u", "ul"
                )

                .addAttributes("a", "href")
                .addAttributes("blockquote", "cite")
                .addAttributes("q", "cite")

                .addProtocols("a", "href", "ftp", "http", "https", "mailto")
                .addProtocols("blockquote", "cite", "http", "https")
                .addProtocols("cite", "cite", "http", "https")

                .addEnforcedAttribute("a", "rel", "nofollow")
            // has special handling for external links, in Cleaner
        }

        /**
         * This safelist allows the same text tags as [.basic], and also allows `img` tags, with appropriate
         * attributes, with `src` pointing to `http` or `https`.
         *
         * @return safelist
         */
        fun basicWithImages(): Safelist {
            return basic()
                .addTags("img")
                .addAttributes("img", "align", "alt", "height", "src", "title", "width")
                .addProtocols("img", "src", "http", "https")
        }

        /**
         * This safelist allows a full range of text and structural body HTML: `a, b, blockquote, br, caption, cite,
         * code, col, colgroup, dd, div, dl, dt, em, h1, h2, h3, h4, h5, h6, i, img, li, ol, p, pre, q, small, span, strike, strong, sub,
         * sup, table, tbody, td, tfoot, th, thead, tr, u, ul`
         *
         *
         * Links do not have an enforced `rel=nofollow` attribute, but you can add that if desired.
         *
         *
         * @return safelist
         */
        fun relaxed(): Safelist {
            return Safelist()
                .addTags(
                    "a", "b", "blockquote", "br", "caption", "cite", "code", "col",
                    "colgroup", "dd", "div", "dl", "dt", "em", "h1", "h2", "h3", "h4", "h5", "h6",
                    "i", "img", "li", "ol", "p", "pre", "q", "small", "span", "strike", "strong",
                    "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "u",
                    "ul"
                )

                .addAttributes("a", "href", "title")
                .addAttributes("blockquote", "cite")
                .addAttributes("col", "span", "width")
                .addAttributes("colgroup", "span", "width")
                .addAttributes("img", "align", "alt", "height", "src", "title", "width")
                .addAttributes("ol", "start", "type")
                .addAttributes("q", "cite")
                .addAttributes("table", "summary", "width")
                .addAttributes("td", "abbr", "axis", "colspan", "rowspan", "width")
                .addAttributes(
                    "th", "abbr", "axis", "colspan", "rowspan", "scope",
                    "width"
                )
                .addAttributes("ul", "type")

                .addProtocols("a", "href", "ftp", "http", "https", "mailto")
                .addProtocols("blockquote", "cite", "http", "https")
                .addProtocols("cite", "cite", "http", "https")
                .addProtocols("img", "src", "http", "https")
                .addProtocols("q", "cite", "http", "https")
        }

        private fun isValidAnchor(value: String): Boolean {
            return value.startsWith("#") && !value.matches(".*\\s.*".toRegex())
        }
    }
}
