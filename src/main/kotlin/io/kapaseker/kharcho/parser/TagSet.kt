package io.kapaseker.kharcho.parser

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.helper.Validate
import java.util.*
import java.util.function.Consumer

/**
 * A TagSet controls the [Tag] configuration for a Document's parse, and its serialization. It contains the initial
 * defaults, and after the parse, any additionally discovered tags.
 *
 * @see Parser.tagSet
 * @since 1.20.1
 */
class TagSet {
    private val tags: MutableMap<String?, MutableMap<String?, Tag?>?> =
        HashMap<String?, MutableMap<String?, Tag?>?>() // namespace -> tag name -> Tag

    @Nullable
    private val source: TagSet? // source to pull tags from on demand

    @Nullable
    private var customizers: ArrayList<Consumer<Tag?>>? = null // optional onNewTag tag customizer

    constructor() {
        source = null
    }

    constructor(original: TagSet) {
        this.source = original
        if (original.customizers != null) this.customizers =
            ArrayList<Consumer<Tag?>>(original.customizers)
    }

    /**
     * Insert a tag into this TagSet. If the tag already exists, it is replaced.
     *
     * Tags explicitly added like this are considered to be known tags (vs those that are dynamically created via
     * .valueOf() if not already in the set.
     *
     * @param tag the tag to add
     * @return this TagSet
     */
    fun add(tag: Tag): TagSet {
        tag.set(Tag.Companion.Known)
        doAdd(tag)
        return this
    }

    /** Adds the tag, but does not set defined. Used in .valueOf  */
    private fun doAdd(tag: Tag) {
        if (customizers != null) {
            for (customizer in customizers) {
                customizer.accept(tag)
            }
        }

        tags.computeIfAbsent(tag.namespace) { ns: kotlin.String? -> java.util.HashMap<kotlin.String?, io.kapaseker.kharcho.parser.Tag?>() }!!
            .put(tag.tagName, tag)
    }

    /**
     * Get an existing Tag from this TagSet by tagName and namespace. The tag name is not normalized, to support mixed
     * instances.
     *
     * @param tagName the case-sensitive tag name
     * @param namespace the namespace
     * @return the tag, or null if not found
     */
    @Nullable
    fun get(tagName: String, namespace: String): Tag? {
        Validate.notNull(tagName)
        Validate.notNull(namespace)

        // get from our tags
        val nsTags = tags.get(namespace)
        if (nsTags != null) {
            val tag = nsTags.get(tagName)
            if (tag != null) {
                return tag
            }
        }

        // not found; clone on demand from source if exists
        if (source != null) {
            val tag = source.get(tagName, namespace)
            if (tag != null) {
                val copy = tag.clone()
                doAdd(copy)
                return copy
            }
        }

        return null
    }

    /**
     * Tag.valueOf with the normalName via the token.normalName, to save redundant lower-casing passes.
     * Provide a null normalName unless we already have one; will be normalized if required from tagName.
     */
    fun valueOf(
        tagName: String,
        @Nullable normalName: String?,
        namespace: String,
        preserveTagCase: Boolean
    ): Tag {
        var tagName = tagName
        var normalName = normalName
        Validate.notNull(tagName)
        Validate.notNull(namespace)
        tagName = tagName.trim { it <= ' ' }
        Validate.notEmpty(tagName)
        var tag = get(tagName, namespace)
        if (tag != null) return tag

        // not found by tagName, try by normal
        if (normalName == null) normalName = ParseSettings.Companion.normalName(tagName)
        tagName = (if (preserveTagCase) tagName else normalName)!!
        tag = get(normalName!!, namespace)
        if (tag != null) {
            if (preserveTagCase && tagName != normalName) {
                tag = tag.clone() // copy so that the name update doesn't reset all instances
                tag.tagName = tagName
                doAdd(tag)
            }
            return tag
        }

        // not defined: return a new one
        tag = Tag(tagName, normalName, namespace)
        doAdd(tag)

        return tag
    }

    /**
     * Get a Tag by name from this TagSet. If not previously defined (unknown), returns a new tag.
     *
     * New tags will be added to this TagSet.
     *
     * @param tagName Name of tag, e.g. "p".
     * @param namespace the namespace for the tag.
     * @param settings used to control tag name sensitivity
     * @return The tag, either defined or new generic.
     */
    /**
     * Get a Tag by name from this TagSet. If not previously defined (unknown), returns a new tag.
     *
     * New tags will be added to this TagSet.
     *
     * @param tagName Name of tag, e.g. "p". **Case-sensitive**.
     * @param namespace the namespace for the tag.
     * @return The tag, either defined or new generic.
     * @see .valueOf
     */
    @JvmOverloads
    fun valueOf(
        tagName: String,
        namespace: String,
        settings: ParseSettings = ParseSettings.Companion.preserveCase
    ): Tag {
        return valueOf(tagName, null, namespace, settings.preserveTagCase())
    }

    /**
     * Register a callback to customize each [Tag] as it's added to this TagSet.
     *
     * Customizers are invoked once per Tag, when they are added (explicitly or via the valueOf methods).
     *
     *
     * For example, to allow all unknown tags to be self-closing during when parsing as HTML:
     * <pre>`
     * Parser parser = Parser.htmlParser();
     * parser.tagSet().onNewTag(tag -> {
     * if (!tag.isKnownTag())
     * tag.set(Tag.SelfClose);
     * });
     *
     * Document doc = Jsoup.parse(html, parser);
    `</pre> *
     *
     * @param customizer a `Consumer<Tag>` that will be called for each newly added or cloned Tag; callers can
     * inspect and modify the Tag's state (e.g. set options)
     * @return this TagSet, to allow method chaining
     * @since 1.21.0
     */
    fun onNewTag(customizer: Consumer<Tag?>): TagSet {
        Validate.notNull(customizer)
        if (customizers == null) customizers = ArrayList<Consumer<Tag?>>()
        customizers!!.add(customizer)
        return this
    }

    override fun equals(o: Any?): Boolean {
        if (o !is TagSet) return false
        val tagSet = o
        return tags == tagSet.tags
    }

    override fun hashCode(): Int {
        return Objects.hashCode(tags)
    }

    private fun setupTags(
        namespace: String,
        tagNames: Array<String>,
        tagModifier: Consumer<Tag?>
    ): TagSet {
        for (tagName in tagNames) {
            var tag = get(tagName, namespace)
            if (tag == null) {
                tag = Tag(tagName, tagName, namespace) // normal name is already normal here
                tag.options = 0 // clear defaults
                add(tag)
            }
            tagModifier.accept(tag)
        }
        return this
    }

    companion object {
        val HtmlTagSet: TagSet = initHtmlDefault()

        /**
         * Returns a mutable copy of the default HTML tag set.
         */
        fun Html(): TagSet {
            return TagSet(HtmlTagSet)
        }

        // Default HTML initialization
        /**
         * Initialize the default HTML tag set.
         */
        fun initHtmlDefault(): TagSet {
            val blockTags = arrayOf<String>(
                "html",
                "head",
                "body",
                "frameset",
                "script",
                "noscript",
                "style",
                "meta",
                "link",
                "title",
                "frame",
                "noframes",
                "section",
                "nav",
                "aside",
                "hgroup",
                "header",
                "footer",
                "p",
                "h1",
                "h2",
                "h3",
                "h4",
                "h5",
                "h6",
                "br",
                "button",
                "ul",
                "ol",
                "pre",
                "div",
                "blockquote",
                "hr",
                "address",
                "figure",
                "figcaption",
                "form",
                "fieldset",
                "ins",
                "del",
                "dl",
                "dt",
                "dd",
                "li",
                "table",
                "caption",
                "thead",
                "tfoot",
                "tbody",
                "colgroup",
                "col",
                "tr",
                "th",
                "td",
                "video",
                "audio",
                "canvas",
                "details",
                "menu",
                "plaintext",
                "template",
                "article",
                "main",
                "center",
                "template",
                "dir",
                "applet",
                "marquee",
                "listing",  // deprecated but still known / special handling
                "#root" // the outer Document
            )
            val inlineTags = arrayOf<String>(
                "object",
                "base",
                "font",
                "tt",
                "i",
                "b",
                "u",
                "big",
                "small",
                "em",
                "strong",
                "dfn",
                "code",
                "samp",
                "kbd",
                "var",
                "cite",
                "abbr",
                "time",
                "acronym",
                "mark",
                "ruby",
                "rt",
                "rp",
                "rtc",
                "a",
                "img",
                "wbr",
                "map",
                "q",
                "sub",
                "sup",
                "bdo",
                "iframe",
                "embed",
                "span",
                "input",
                "select",
                "textarea",
                "label",
                "optgroup",
                "option",
                "legend",
                "datalist",
                "keygen",
                "output",
                "progress",
                "meter",
                "area",
                "param",
                "source",
                "track",
                "summary",
                "command",
                "device",
                "area",
                "basefont",
                "bgsound",
                "menuitem",
                "param",
                "source",
                "track",
                "data",
                "bdi",
                "s",
                "strike",
                "nobr",
                "rb",  // deprecated but still known / special handling
            )
            val inlineContainers = arrayOf<String>( // can only contain inline; aka phrasing content
                "title",
                "a",
                "p",
                "h1",
                "h2",
                "h3",
                "h4",
                "h5",
                "h6",
                "pre",
                "address",
                "li",
                "th",
                "td",
                "script",
                "style",
                "ins",
                "del",
                "s",
                "button"
            )
            val voidTags = arrayOf<String>(
                "meta",
                "link",
                "base",
                "frame",
                "img",
                "br",
                "wbr",
                "embed",
                "hr",
                "input",
                "keygen",
                "col",
                "command",
                "device",
                "area",
                "basefont",
                "bgsound",
                "menuitem",
                "param",
                "source",
                "track"
            )
            val preserveWhitespaceTags = arrayOf<String>(
                "pre", "plaintext", "title", "textarea", "script"
            )
            val rcdataTags = arrayOf<String>("title", "textarea")
            val dataTags =
                arrayOf<String>("iframe", "noembed", "noframes", "script", "style", "xmp")
            val formSubmitTags: Array<String> = SharedConstants.FormSubmitTags
            val blockMathTags = arrayOf<String>("math")
            val inlineMathTags = arrayOf<String>("mi", "mo", "msup", "mn", "mtext")
            val blockSvgTags = arrayOf<String>(
                "svg",
                "femerge",
                "femergenode"
            ) // note these are LC versions, but actually preserve case
            val inlineSvgTags = arrayOf<String>("text")
            val dataSvgTags = arrayOf<String>("script")

            return TagSet()
                .setupTags(Parser.Companion.NamespaceHtml, blockTags, Consumer { tag: Tag? ->
                    tag!!.set(
                        Tag.Companion.Block
                    )
                })
                .setupTags(
                    Parser.Companion.NamespaceHtml,
                    inlineTags,
                    Consumer { tag: Tag? -> tag!!.set(0) })
                .setupTags(Parser.Companion.NamespaceHtml, inlineContainers, Consumer { tag: Tag? ->
                    tag!!.set(
                        Tag.Companion.InlineContainer
                    )
                })
                .setupTags(Parser.Companion.NamespaceHtml, voidTags, Consumer { tag: Tag? ->
                    tag!!.set(
                        Tag.Companion.Void
                    )
                })
                .setupTags(
                    Parser.Companion.NamespaceHtml,
                    preserveWhitespaceTags,
                    Consumer { tag: Tag? ->
                        tag!!.set(
                            Tag.Companion.PreserveWhitespace
                        )
                    })
                .setupTags(Parser.Companion.NamespaceHtml, rcdataTags, Consumer { tag: Tag? ->
                    tag!!.set(
                        Tag.Companion.RcData
                    )
                })
                .setupTags(Parser.Companion.NamespaceHtml, dataTags, Consumer { tag: Tag? ->
                    tag!!.set(
                        Tag.Companion.Data
                    )
                })
                .setupTags(Parser.Companion.NamespaceHtml, formSubmitTags, Consumer { tag: Tag? ->
                    tag!!.set(
                        Tag.Companion.FormSubmittable
                    )
                })
                .setupTags(Parser.Companion.NamespaceMathml, blockMathTags, Consumer { tag: Tag? ->
                    tag!!.set(
                        Tag.Companion.Block
                    )
                })
                .setupTags(
                    Parser.Companion.NamespaceMathml,
                    inlineMathTags,
                    Consumer { tag: Tag? -> tag!!.set(0) })
                .setupTags(Parser.Companion.NamespaceSvg, blockSvgTags, Consumer { tag: Tag? ->
                    tag!!.set(
                        Tag.Companion.Block
                    )
                })
                .setupTags(
                    Parser.Companion.NamespaceSvg,
                    inlineSvgTags,
                    Consumer { tag: Tag? -> tag!!.set(0) })
                .setupTags(Parser.Companion.NamespaceSvg, dataSvgTags, Consumer { tag: Tag? ->
                    tag!!.set(
                        Tag.Companion.Data
                    )
                })
        }
    }
}
