package io.kapaseker.kharcho.parser

import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.Normalizer
import io.kapaseker.kharcho.nodes.Attributes
import io.kapaseker.kharcho.nodes.Range

/**
 * Parse tokens for the Tokeniser.
 */
abstract class Token private constructor(val type: TokenType) {

    var startPos: Int = 0
    var endPos: Int = UnsetPos // position in CharacterReader this token was read from

    fun tokenType(): String {
        return this.javaClass.getSimpleName()
    }

    /**
     * Reset the data represent by this token, for reuse. Prevents the need to create transfer objects for every
     * piece of data, which immediately get GCed.
     */
    open fun reset(): Token {
        startPos = UnsetPos
        endPos = UnsetPos
        return this
    }

    fun startPos(): Int {
        return startPos
    }

    fun startPos(pos: Int) {
        startPos = pos
    }

    fun endPos(): Int {
        return endPos
    }

    fun endPos(pos: Int) {
        endPos = pos
    }

    internal class Doctype : Token(TokenType.Doctype) {
        val name: TokenData = TokenData()

        var pubSysKey: String? = null
        val publicIdentifier: TokenData = TokenData()
        val systemIdentifier: TokenData = TokenData()
        var isForceQuirks: Boolean = false

        override fun reset(): Token {
            super.reset()
            name.reset()
            pubSysKey = null
            publicIdentifier.reset()
            systemIdentifier.reset()
            this.isForceQuirks = false
            return this
        }

        fun getName(): String {
            return name.value()
        }

        fun getPublicIdentifier(): String {
            return publicIdentifier.value()
        }

        fun getSystemIdentifier(): String {
            return systemIdentifier.value()
        }

        override fun toString(): String {
            return "<!doctype " + getName() + ">"
        }
    }

    internal abstract class Tag(type: TokenType, treeBuilder: TreeBuilder) : Token(type) {
        var tagName: TokenData = TokenData()

        var normalName: String? = null // lc version of tag name, for case-insensitive tree build
        var isSelfClosing: Boolean = false

        var attributes: Attributes? =
            null // start tags get attributes on construction. End tags get attributes on first new attribute (but only for parser convenience, not used).

        private val attrName = TokenData()
        private val attrValue = TokenData()
        private var hasEmptyAttrValue =
            false // distinguish boolean attribute from empty string value

        // attribute source range tracking
        val treeBuilder: TreeBuilder
        val trackSource: Boolean
        var attrNameStart: Int = 0
        var attrNameEnd: Int = 0
        var attrValStart: Int = 0
        var attrValEnd: Int = 0

        override fun reset(): Tag {
            super.reset()
            tagName.reset()
            normalName = null
            this.isSelfClosing = false
            attributes = null
            resetPendingAttr()
            return this
        }

        private fun resetPendingAttr() {
            attrName.reset()
            attrValue.reset()
            hasEmptyAttrValue = false

            if (trackSource) {
                attrValEnd = UnsetPos
                attrValStart = attrValEnd
                attrNameEnd = attrValStart
                attrNameStart = attrNameEnd
            }
        }

        init {
            this.treeBuilder = treeBuilder
            this.trackSource = treeBuilder.trackSourceRange
        }

        fun newAttribute() {
            if (attributes == null) attributes = Attributes()

            if (attrName.hasData() && attributes!!.size() < MaxAttributes) {
                // the tokeniser has skipped whitespace control chars, but trimming could collapse to empty for other control codes, so verify here
                var name = attrName.value()
                name = name.trim { it <= ' ' }
                if (!name.isEmpty()) {
                    val value: String?
                    if (attrValue.hasData()) value = attrValue.value()
                    else if (hasEmptyAttrValue) value = ""
                    else value = null
                    // note that we add, not put. So that the first is kept, and rest are deduped, once in a context where case sensitivity is known, and we can warn for duplicates.
                    attributes!!.add(name, value)

                    trackAttributeRange(name)
                }
            }
            resetPendingAttr()
        }

        private fun trackAttributeRange(name: String?) {
            var name = name
            if (trackSource && this.isStartTag) {
                val start = asStartTag()
                val r = start.treeBuilder.reader
                val preserve = start.treeBuilder.settings.preserveAttributeCase()

                checkNotNull(attributes)
                if (!preserve) name = Normalizer.lowerCase(name)
                if (attributes!!.sourceRange(name).nameRange()
                        .isTracked()
                ) return  // dedupe ranges as we go; actual attributes get deduped later for error count


                // if there's no value (e.g. boolean), make it an implicit range at current
                if (!attrValue.hasData()) {
                    attrValEnd = attrNameEnd
                    attrValStart = attrValEnd
                }

                val range: AttributeRange = AttributeRange(
                    Range(
                        Range.Position(
                            attrNameStart,
                            r.lineNumber(attrNameStart),
                            r.columnNumber(attrNameStart)
                        ),
                        Range.Position(
                            attrNameEnd,
                            r.lineNumber(attrNameEnd),
                            r.columnNumber(attrNameEnd)
                        )
                    ),
                    Range(
                        Range.Position(
                            attrValStart,
                            r.lineNumber(attrValStart),
                            r.columnNumber(attrValStart)
                        ),
                        Range.Position(
                            attrValEnd,
                            r.lineNumber(attrValEnd),
                            r.columnNumber(attrValEnd)
                        )
                    )
                )
                attributes!!.sourceRange(name, range)
            }
        }

        fun hasAttributes(): Boolean {
            return attributes != null
        }

        fun hasAttributeIgnoreCase(key: String?): Boolean {
            return attributes != null && attributes!!.hasKeyIgnoreCase(key)
        }

        fun finaliseTag() {
            // finalises for emit
            if (attrName.hasData()) {
                newAttribute()
            }
        }

        /** Preserves case  */
        fun name(): String { // preserves case, for input into Tag.valueOf (which may drop case)
            return tagName.value()
        }

        /** Lower case  */
        fun normalName(): String? { // lower case, used in tree building for working out where in tree it should go
            Validate.isFalse(normalName == null || normalName!!.isEmpty())
            return normalName
        }

        fun toStringName(): String {
            val name = tagName.value()
            return if (name.isEmpty()) "[unset]" else name
        }

        fun name(name: String?): Tag {
            tagName.set(name)
            normalName = ParseSettings.Companion.normalName(tagName.value())
            return this
        }

        // these appenders are rarely hit in not null state-- caused by null chars.
        fun appendTagName(append: String) {
            // might have null chars - need to replace with null replacement character
            var append = append
            append = append.replace(
                TokeniserState.Companion.nullChar,
                Tokeniser.Companion.replacementChar
            )
            tagName.append(append)
            normalName = ParseSettings.Companion.normalName(tagName.value())
        }

        fun appendTagName(append: Char) {
            appendTagName(append.toString()) // so that normalname gets updated too
        }

        fun appendAttributeName(append: String, startPos: Int, endPos: Int) {
            // might have null chars because we eat in one pass - need to replace with null replacement character
            var append = append
            append = append.replace(
                TokeniserState.Companion.nullChar,
                Tokeniser.Companion.replacementChar
            )
            attrName.append(append)
            attrNamePos(startPos, endPos)
        }

        fun appendAttributeName(append: Char, startPos: Int, endPos: Int) {
            attrName.append(append)
            attrNamePos(startPos, endPos)
        }

        fun appendAttributeValue(append: String?, startPos: Int, endPos: Int) {
            attrValue.append(append)
            attrValPos(startPos, endPos)
        }

        fun appendAttributeValue(append: Char, startPos: Int, endPos: Int) {
            attrValue.append(append)
            attrValPos(startPos, endPos)
        }

        fun appendAttributeValue(appendCodepoints: IntArray, startPos: Int, endPos: Int) {
            for (codepoint in appendCodepoints) {
                attrValue.appendCodePoint(codepoint)
            }
            attrValPos(startPos, endPos)
        }

        fun setEmptyAttributeValue() {
            hasEmptyAttrValue = true
        }

        private fun attrNamePos(startPos: Int, endPos: Int) {
            if (trackSource) {
                attrNameStart =
                    if (attrNameStart > UnsetPos) attrNameStart else startPos // latches to first
                attrNameEnd = endPos
            }
        }

        private fun attrValPos(startPos: Int, endPos: Int) {
            if (trackSource) {
                attrValStart =
                    if (attrValStart > UnsetPos) attrValStart else startPos // latches to first
                attrValEnd = endPos
            }
        }

        abstract override fun toString(): String

        companion object {
            /* Limits runaway crafted HTML from spewing attributes and getting a little sluggish in ensureCapacity.
        Real-world HTML will P99 around 8 attributes, so plenty of headroom. Implemented here and not in the Attributes
        object so that API users can add more if ever required. */
            private const val MaxAttributes = 512
        }
    }

    internal class StartTag  // TreeBuilder is provided so if tracking, can get line / column positions for Range; and can dedupe as we go
        (treeBuilder: TreeBuilder) : Tag(TokenType.StartTag, treeBuilder) {
        override fun reset(): Tag {
            super.reset()
            attributes = null
            return this
        }

        fun nameAttr(name: String?, attributes: Attributes?): StartTag {
            this.tagName.set(name)
            this.attributes = attributes
            normalName = ParseSettings.Companion.normalName(name)
            return this
        }

        override fun toString(): String {
            val closer = if (this.isSelfClosing) "/>" else ">"
            if (hasAttributes() && attributes!!.size() > 0) return "<" + toStringName() + " " + attributes.toString() + closer
            else return "<" + toStringName() + closer
        }
    }

    internal class EndTag(treeBuilder: TreeBuilder) : Tag(TokenType.EndTag, treeBuilder) {
        override fun toString(): String {
            return "</" + toStringName() + ">"
        }
    }

    internal class Comment : Token(TokenType.Comment) {
        private val data = TokenData()
        var bogus: Boolean = false

        override fun reset(): Token {
            super.reset()
            data.reset()
            bogus = false
            return this
        }

        fun getData(): String {
            return data.value()
        }

        fun append(append: String?): Comment {
            data.append(append)
            return this
        }

        fun append(append: Char): Comment {
            data.append(append)
            return this
        }

        override fun toString(): String {
            return "<!--" + getData() + "-->"
        }
    }

    internal open class Character : Token {
        val data: TokenData = TokenData()

        constructor() : super(TokenType.Character)

        /** Deep copy  */
        constructor(source: Character) : super(TokenType.Character) {
            this.startPos = source.startPos
            this.endPos = source.endPos
            this.data.set(source.data.value())
        }

        override fun reset(): Token {
            super.reset()
            data.reset()
            return this
        }

        fun data(str: String?): Character {
            data.set(str)
            return this
        }

        fun append(str: String?): Character {
            data.append(str)
            return this
        }

        fun getData(): String {
            return data.value()
        }

        override fun toString(): String {
            return getData()
        }

        /**
         * Normalize null chars in the data. If replace is true, replaces with the replacement char; if false, removes.
         */
        fun normalizeNulls(replace: Boolean) {
            var data = this.data.value()
            if (data.indexOf(TokeniserState.Companion.nullChar) == -1) return

            data = (if (replace) data.replace(
                TokeniserState.Companion.nullChar,
                Tokeniser.Companion.replacementChar
            ) else data.replace(
                nullString, ""
            ))
            this.data.set(data)
        }

        companion object {
            private val nullString = TokeniserState.Companion.nullChar.toString()
        }
    }

    internal class CData(data: String?) : Character() {
        init {
            this.data(data)
        }

        override fun toString(): String {
            return "<![CDATA[" + getData() + "]]>"
        }
    }

    /**
     * XmlDeclaration - extends Tag for pseudo attribute support
     */
    internal class XmlDecl(treeBuilder: TreeBuilder) : Tag(TokenType.XmlDecl, treeBuilder) {
        var isDeclaration: Boolean = true // <!..>, or <?...?> if false (a processing instruction)

        override fun reset(): XmlDecl {
            super.reset()
            isDeclaration = true
            return this
        }

        override fun toString(): String {
            val open = if (isDeclaration) "<!" else "<?"
            val close = if (isDeclaration) ">" else "?>"
            if (hasAttributes() && attributes!!.size() > 0) return open + toStringName() + " " + attributes.toString() + close
            else return open + toStringName() + close
        }
    }

    internal class EOF : Token(TokenType.EOF) {
        override fun reset(): Token {
            super.reset()
            return this
        }

        override fun toString(): String {
            return ""
        }
    }

    val isDoctype: Boolean
        get() = type == TokenType.Doctype

    fun asDoctype(): Doctype {
        return this as Doctype
    }

    val isStartTag: Boolean
        get() = type == TokenType.StartTag

    fun asStartTag(): StartTag {
        return this as StartTag
    }

    val isEndTag: Boolean
        get() = type == TokenType.EndTag

    fun asEndTag(): EndTag {
        return this as EndTag
    }

    val isComment: Boolean
        get() = type == TokenType.Comment

    fun asComment(): Comment {
        return this as Comment
    }

    val isCharacter: Boolean
        get() = type == TokenType.Character

    val isCData: Boolean
        get() = this is CData

    fun asCharacter(): Character {
        return this as Character
    }

    fun asXmlDecl(): XmlDecl {
        return this as XmlDecl
    }

    val isEOF: Boolean
        get() = type == TokenType.EOF

    enum class TokenType {
        Doctype,
        StartTag,
        EndTag,
        Comment,
        Character,  // note no CData - treated in builder as an extension of Character
        XmlDecl,
        EOF
    }

    companion object {
        val UnsetPos: Int = -1
    }
}
