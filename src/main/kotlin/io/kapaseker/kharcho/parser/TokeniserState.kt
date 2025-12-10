package io.kapaseker.kharcho.parser

import io.kapaseker.kharcho.nodes.Document
import io.kapaseker.kharcho.nodes.DocumentType
import io.kapaseker.kharcho.parser.Token.XmlDecl

/**
 * States and transition activations for the Tokeniser.
 */
enum class TokeniserState {
    Data {
        // in data state, gather characters until a character reference or tag is found
        override fun read(t: Tokeniser, r: CharacterReader) {
            when (r.current()) {
                '&' -> t.advanceTransition(TokeniserState.CharacterReferenceInData)
                '<' -> t.advanceTransition(TokeniserState.TagOpen)
                TokeniserState.Companion.nullChar -> {
                    t.error(this) // NOT replacement character (oddly?)
                    t.emit(r.consume())
                }

                TokeniserState.Companion.eof -> t.emit(Token.EOF())
                else -> {
                    val data = r.consumeData()
                    t.emit(data)
                }
            }
        }
    },
    CharacterReferenceInData {
        // from & in data
        override fun read(t: Tokeniser, r: CharacterReader?) {
            TokeniserState.Companion.readCharRef(t, TokeniserState.Data)
        }
    },
    Rcdata {
        // Rcdata has text with character references
        /** handles data in title, textarea etc */
        override fun read(t: Tokeniser, r: CharacterReader) {
            when (r.current()) {
                '&' -> t.advanceTransition(TokeniserState.CharacterReferenceInRcdata)
                '<' -> t.advanceTransition(TokeniserState.RcdataLessthanSign)
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    r.advance()
                    t.emit(TokeniserState.Companion.replacementChar)
                }

                TokeniserState.Companion.eof -> t.emit(Token.EOF())
                else -> {
                    val data = r.consumeData()
                    t.emit(data)
                }
            }
        }
    },
    CharacterReferenceInRcdata {
        override fun read(t: Tokeniser, r: CharacterReader?) {
            TokeniserState.Companion.readCharRef(t, TokeniserState.Rcdata)
        }
    },
    Rawtext {
        override fun read(t: Tokeniser, r: CharacterReader) {
            TokeniserState.Companion.readRawData(t, r, this, TokeniserState.RawtextLessthanSign)
        }
    },
    ScriptData {
        override fun read(t: Tokeniser, r: CharacterReader) {
            TokeniserState.Companion.readRawData(t, r, this, TokeniserState.ScriptDataLessthanSign)
        }
    },
    PLAINTEXT {
        override fun read(t: Tokeniser, r: CharacterReader) {
            when (r.current()) {
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    r.advance()
                    t.emit(TokeniserState.Companion.replacementChar)
                }

                TokeniserState.Companion.eof -> t.emit(Token.EOF())
                else -> {
                    val data: String? = r.consumeTo(TokeniserState.Companion.nullChar)
                    t.emit(data)
                }
            }
        }
    },
    TagOpen {
        // from < in data
        override fun read(t: Tokeniser, r: CharacterReader) {
            when (r.current()) {
                '!' -> t.advanceTransition(TokeniserState.MarkupDeclarationOpen)
                '/' -> t.advanceTransition(TokeniserState.EndTagOpen)
                '?' -> if (t.syntax == Document.OutputSettings.Syntax.xml) {
                    t.advanceTransition(TokeniserState.MarkupProcessingOpen)
                } else {
                    t.createBogusCommentPending()
                    t.transition(TokeniserState.BogusComment)
                }

                else -> if (r.matchesAsciiAlpha()) {
                    t.createTagPending(true)
                    t.transition(TokeniserState.TagName)
                } else {
                    t.error(this)
                    t.emit('<') // char that got us here
                    t.transition(TokeniserState.Data)
                }
            }
        }
    },
    EndTagOpen {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.isEmpty()) {
                t.eofError(this)
                t.emit("</")
                t.transition(TokeniserState.Data)
            } else if (r.matchesAsciiAlpha()) {
                t.createTagPending(false)
                t.transition(TokeniserState.TagName)
            } else if (r.matches('>')) {
                t.error(this)
                t.advanceTransition(TokeniserState.Data)
            } else {
                t.error(this)
                t.createBogusCommentPending()
                t.commentPending.append('/') // push the / back on that got us here
                t.transition(TokeniserState.BogusComment)
            }
        }
    },
    TagName {
        // from < or </ in data, will have start or end tag pending
        override fun read(t: Tokeniser, r: CharacterReader) {
            // previous TagOpen state did NOT consume, will have a letter char in current
            val tagName = r.consumeTagName()
            t.tagPending.appendTagName(tagName)

            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> t.transition(TokeniserState.BeforeAttributeName)
                '/' -> t.transition(TokeniserState.SelfClosingStartTag)
                '>' -> {
                    t.emitTagPending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.nullChar -> t.tagPending.appendTagName(TokeniserState.Companion.replacementStr)
                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.transition(TokeniserState.Data)
                }

                else -> t.tagPending.appendTagName(c)
            }
        }
    },
    RcdataLessthanSign {
        // from < in rcdata
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.matches('/')) {
                t.createTempBuffer()
                t.advanceTransition(TokeniserState.RCDATAEndTagOpen)
            } else if (r.readFully() && r.matchesAsciiAlpha() && t.appropriateEndTagName() != null && !r.containsIgnoreCase(
                    t.appropriateEndTagSeq()
                )
            ) {
                // diverge from spec: got a start tag, but there's no appropriate end tag (</title>), so rather than
                // consuming to EOF; break out here
                t.tagPending = t.createTagPending(false).name(t.appropriateEndTagName())
                t.emitTagPending()
                t.transition(TokeniserState.TagOpen) // straight into TagOpen, as we came from < and looks like we're on a start tag
            } else {
                t.emit('<')
                t.transition(TokeniserState.Rcdata)
            }
        }
    },
    RCDATAEndTagOpen {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.matchesAsciiAlpha()) {
                t.createTagPending(false)
                t.tagPending.appendTagName(r.current())
                t.dataBuffer.append(r.current())
                t.advanceTransition(TokeniserState.RCDATAEndTagName)
            } else {
                t.emit("</")
                t.transition(TokeniserState.Rcdata)
            }
        }
    },
    RCDATAEndTagName {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.matchesAsciiAlpha()) {
                val name = r.consumeTagName()
                t.tagPending.appendTagName(name)
                t.dataBuffer.append(name)
                return
            }

            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> if (t.isAppropriateEndTagToken()) t.transition(
                    TokeniserState.BeforeAttributeName
                )
                else anythingElse(t, r)

                '/' -> if (t.isAppropriateEndTagToken()) t.transition(TokeniserState.SelfClosingStartTag)
                else anythingElse(t, r)

                '>' -> if (t.isAppropriateEndTagToken()) {
                    t.emitTagPending()
                    t.transition(TokeniserState.Data)
                } else anythingElse(t, r)

                else -> anythingElse(t, r)
            }
        }

        private fun anythingElse(t: Tokeniser, r: CharacterReader) {
            t.emit("</")
            t.emit(t.dataBuffer.value())
            r.unconsume()
            t.transition(TokeniserState.Rcdata)
        }
    },
    RawtextLessthanSign {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.matches('/')) {
                t.createTempBuffer()
                t.advanceTransition(TokeniserState.RawtextEndTagOpen)
            } else {
                t.emit('<')
                t.transition(TokeniserState.Rawtext)
            }
        }
    },
    RawtextEndTagOpen {
        override fun read(t: Tokeniser, r: CharacterReader) {
            TokeniserState.Companion.readEndTag(
                t,
                r,
                TokeniserState.RawtextEndTagName,
                TokeniserState.Rawtext
            )
        }
    },
    RawtextEndTagName {
        override fun read(t: Tokeniser, r: CharacterReader) {
            TokeniserState.Companion.handleDataEndTag(t, r, TokeniserState.Rawtext)
        }
    },
    ScriptDataLessthanSign {
        override fun read(t: Tokeniser, r: CharacterReader) {
            when (r.consume()) {
                '/' -> {
                    t.createTempBuffer()
                    t.transition(TokeniserState.ScriptDataEndTagOpen)
                }

                '!' -> {
                    t.emit("<!")
                    t.transition(TokeniserState.ScriptDataEscapeStart)
                }

                TokeniserState.Companion.eof -> {
                    t.emit('<')
                    t.eofError(this)
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.emit('<')
                    r.unconsume()
                    t.transition(TokeniserState.ScriptData)
                }
            }
        }
    },
    ScriptDataEndTagOpen {
        override fun read(t: Tokeniser, r: CharacterReader) {
            TokeniserState.Companion.readEndTag(
                t,
                r,
                TokeniserState.ScriptDataEndTagName,
                TokeniserState.ScriptData
            )
        }
    },
    ScriptDataEndTagName {
        override fun read(t: Tokeniser, r: CharacterReader) {
            TokeniserState.Companion.handleDataEndTag(t, r, TokeniserState.ScriptData)
        }
    },
    ScriptDataEscapeStart {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.matches('-')) {
                t.emit('-')
                t.advanceTransition(TokeniserState.ScriptDataEscapeStartDash)
            } else {
                t.transition(TokeniserState.ScriptData)
            }
        }
    },
    ScriptDataEscapeStartDash {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.matches('-')) {
                t.emit('-')
                t.advanceTransition(TokeniserState.ScriptDataEscapedDashDash)
            } else {
                t.transition(TokeniserState.ScriptData)
            }
        }
    },
    ScriptDataEscaped {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.isEmpty()) {
                t.eofError(this)
                t.transition(TokeniserState.Data)
                return
            }

            when (r.current()) {
                '-' -> {
                    t.emit('-')
                    t.advanceTransition(TokeniserState.ScriptDataEscapedDash)
                }

                '<' -> t.advanceTransition(TokeniserState.ScriptDataEscapedLessthanSign)
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    r.advance()
                    t.emit(TokeniserState.Companion.replacementChar)
                }

                else -> {
                    val data = r.consumeToAny('-', '<', TokeniserState.Companion.nullChar)
                    t.emit(data)
                }
            }
        }
    },
    ScriptDataEscapedDash {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.isEmpty()) {
                t.eofError(this)
                t.transition(TokeniserState.Data)
                return
            }

            val c = r.consume()
            when (c) {
                '-' -> {
                    t.emit(c)
                    t.transition(TokeniserState.ScriptDataEscapedDashDash)
                }

                '<' -> t.transition(TokeniserState.ScriptDataEscapedLessthanSign)
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.emit(TokeniserState.Companion.replacementChar)
                    t.transition(TokeniserState.ScriptDataEscaped)
                }

                else -> {
                    t.emit(c)
                    t.transition(TokeniserState.ScriptDataEscaped)
                }
            }
        }
    },
    ScriptDataEscapedDashDash {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.isEmpty()) {
                t.eofError(this)
                t.transition(TokeniserState.Data)
                return
            }

            val c = r.consume()
            when (c) {
                '-' -> t.emit(c)
                '<' -> t.transition(TokeniserState.ScriptDataEscapedLessthanSign)
                '>' -> {
                    t.emit(c)
                    t.transition(TokeniserState.ScriptData)
                }

                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.emit(TokeniserState.Companion.replacementChar)
                    t.transition(TokeniserState.ScriptDataEscaped)
                }

                else -> {
                    t.emit(c)
                    t.transition(TokeniserState.ScriptDataEscaped)
                }
            }
        }
    },
    ScriptDataEscapedLessthanSign {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.matchesAsciiAlpha()) {
                t.createTempBuffer()
                t.dataBuffer.append(r.current())
                t.emit('<')
                t.emit(r.current())
                t.advanceTransition(TokeniserState.ScriptDataDoubleEscapeStart)
            } else if (r.matches('/')) {
                t.createTempBuffer()
                t.advanceTransition(TokeniserState.ScriptDataEscapedEndTagOpen)
            } else {
                t.emit('<')
                t.transition(TokeniserState.ScriptDataEscaped)
            }
        }
    },
    ScriptDataEscapedEndTagOpen {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.matchesAsciiAlpha()) {
                t.createTagPending(false)
                t.tagPending.appendTagName(r.current())
                t.dataBuffer.append(r.current())
                t.advanceTransition(TokeniserState.ScriptDataEscapedEndTagName)
            } else {
                t.emit("</")
                t.transition(TokeniserState.ScriptDataEscaped)
            }
        }
    },
    ScriptDataEscapedEndTagName {
        override fun read(t: Tokeniser, r: CharacterReader) {
            TokeniserState.Companion.handleDataEndTag(t, r, TokeniserState.ScriptDataEscaped)
        }
    },
    ScriptDataDoubleEscapeStart {
        override fun read(t: Tokeniser, r: CharacterReader) {
            TokeniserState.Companion.handleDataDoubleEscapeTag(
                t,
                r,
                TokeniserState.ScriptDataDoubleEscaped,
                TokeniserState.ScriptDataEscaped
            )
        }
    },
    ScriptDataDoubleEscaped {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.current()
            when (c) {
                '-' -> {
                    t.emit(c)
                    t.advanceTransition(TokeniserState.ScriptDataDoubleEscapedDash)
                }

                '<' -> {
                    t.emit(c)
                    t.advanceTransition(TokeniserState.ScriptDataDoubleEscapedLessthanSign)
                }

                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    r.advance()
                    t.emit(TokeniserState.Companion.replacementChar)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    val data = r.consumeToAny('-', '<', TokeniserState.Companion.nullChar)
                    t.emit(data)
                }
            }
        }
    },
    ScriptDataDoubleEscapedDash {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '-' -> {
                    t.emit(c)
                    t.transition(TokeniserState.ScriptDataDoubleEscapedDashDash)
                }

                '<' -> {
                    t.emit(c)
                    t.transition(TokeniserState.ScriptDataDoubleEscapedLessthanSign)
                }

                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.emit(TokeniserState.Companion.replacementChar)
                    t.transition(TokeniserState.ScriptDataDoubleEscaped)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.emit(c)
                    t.transition(TokeniserState.ScriptDataDoubleEscaped)
                }
            }
        }
    },
    ScriptDataDoubleEscapedDashDash {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '-' -> t.emit(c)
                '<' -> {
                    t.emit(c)
                    t.transition(TokeniserState.ScriptDataDoubleEscapedLessthanSign)
                }

                '>' -> {
                    t.emit(c)
                    t.transition(TokeniserState.ScriptData)
                }

                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.emit(TokeniserState.Companion.replacementChar)
                    t.transition(TokeniserState.ScriptDataDoubleEscaped)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.emit(c)
                    t.transition(TokeniserState.ScriptDataDoubleEscaped)
                }
            }
        }
    },
    ScriptDataDoubleEscapedLessthanSign {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.matches('/')) {
                t.emit('/')
                t.createTempBuffer()
                t.advanceTransition(TokeniserState.ScriptDataDoubleEscapeEnd)
            } else {
                t.transition(TokeniserState.ScriptDataDoubleEscaped)
            }
        }
    },
    ScriptDataDoubleEscapeEnd {
        override fun read(t: Tokeniser, r: CharacterReader) {
            TokeniserState.Companion.handleDataDoubleEscapeTag(
                t,
                r,
                TokeniserState.ScriptDataEscaped,
                TokeniserState.ScriptDataDoubleEscaped
            )
        }
    },
    BeforeAttributeName {
        // from tagname <xxx
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> {}
                '/' -> t.transition(TokeniserState.SelfClosingStartTag)
                '>' -> {
                    t.emitTagPending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.nullChar -> {
                    r.unconsume()
                    t.error(this)
                    t.tagPending.newAttribute()
                    t.transition(TokeniserState.AttributeName)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.transition(TokeniserState.Data)
                }

                '"', '\'', '=' -> {
                    t.error(this)
                    t.tagPending.newAttribute()
                    t.tagPending.appendAttributeName(c, r.pos() - 1, r.pos())
                    t.transition(TokeniserState.AttributeName)
                }

                '?' -> {
                    if (t.tagPending is XmlDecl) break
                    t.tagPending.newAttribute()
                    r.unconsume()
                    t.transition(TokeniserState.AttributeName)
                }

                else -> {
                    t.tagPending.newAttribute()
                    r.unconsume()
                    t.transition(TokeniserState.AttributeName)
                }
            }
        }
    },
    AttributeName {
        // from before attribute name
        override fun read(t: Tokeniser, r: CharacterReader) {
            var pos = r.pos()
            val name =
                r.consumeToAnySorted(*TokeniserState.Companion.attributeNameCharsSorted) // spec deviate - consume and emit nulls in one hit vs stepping
            t.tagPending.appendAttributeName(name, pos, r.pos())

            pos = r.pos()
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> t.transition(TokeniserState.AfterAttributeName)
                '/' -> t.transition(TokeniserState.SelfClosingStartTag)
                '=' -> t.transition(TokeniserState.BeforeAttributeValue)
                '>' -> {
                    t.emitTagPending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.transition(TokeniserState.Data)
                }

                '"', '\'', '<' -> {
                    t.error(this)
                    t.tagPending.appendAttributeName(c, pos, r.pos())
                }

                '?' -> {
                    if (t.syntax == Document.OutputSettings.Syntax.xml && t.tagPending is XmlDecl) {
                        t.transition(TokeniserState.AfterAttributeName)
                        break
                    } // otherwise default - take it

                    t.tagPending.appendAttributeName(c, pos, r.pos())
                }

                else -> t.tagPending.appendAttributeName(c, pos, r.pos())
            }
        }
    },
    AfterAttributeName {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> {}
                '/' -> t.transition(TokeniserState.SelfClosingStartTag)
                '=' -> t.transition(TokeniserState.BeforeAttributeValue)
                '>' -> {
                    t.emitTagPending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.tagPending.appendAttributeName(
                        TokeniserState.Companion.replacementChar,
                        r.pos() - 1,
                        r.pos()
                    )
                    t.transition(TokeniserState.AttributeName)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.transition(TokeniserState.Data)
                }

                '"', '\'', '<' -> {
                    t.error(this)
                    t.tagPending.newAttribute()
                    t.tagPending.appendAttributeName(c, r.pos() - 1, r.pos())
                    t.transition(TokeniserState.AttributeName)
                }

                else -> {
                    t.tagPending.newAttribute()
                    r.unconsume()
                    t.transition(TokeniserState.AttributeName)
                }
            }
        }
    },
    BeforeAttributeValue {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> {}
                '"' -> t.transition(TokeniserState.AttributeValue_doubleQuoted)
                '&' -> {
                    r.unconsume()
                    t.transition(TokeniserState.AttributeValue_unquoted)
                }

                '\'' -> t.transition(TokeniserState.AttributeValue_singleQuoted)
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.tagPending.appendAttributeValue(
                        TokeniserState.Companion.replacementChar,
                        r.pos() - 1,
                        r.pos()
                    )
                    t.transition(TokeniserState.AttributeValue_unquoted)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.emitTagPending()
                    t.transition(TokeniserState.Data)
                }

                '>' -> {
                    t.error(this)
                    t.emitTagPending()
                    t.transition(TokeniserState.Data)
                }

                '<', '=', '`' -> {
                    t.error(this)
                    t.tagPending.appendAttributeValue(c, r.pos() - 1, r.pos())
                    t.transition(TokeniserState.AttributeValue_unquoted)
                }

                else -> {
                    r.unconsume()
                    t.transition(TokeniserState.AttributeValue_unquoted)
                }
            }
        }
    },
    AttributeValue_doubleQuoted {
        override fun read(t: Tokeniser, r: CharacterReader) {
            var pos = r.pos()
            val value = r.consumeAttributeQuoted(false)
            if (value.length > 0) t.tagPending.appendAttributeValue(value, pos, r.pos())
            else t.tagPending.setEmptyAttributeValue()

            pos = r.pos()
            val c = r.consume()
            when (c) {
                '"' -> t.transition(TokeniserState.AfterAttributeValue_quoted)
                '&' -> {
                    val ref = t.consumeCharacterReference('"', true)
                    if (ref != null) t.tagPending.appendAttributeValue(ref, pos, r.pos())
                    else t.tagPending.appendAttributeValue('&', pos, r.pos())
                }

                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.tagPending.appendAttributeValue(
                        TokeniserState.Companion.replacementChar,
                        pos,
                        r.pos()
                    )
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.transition(TokeniserState.Data)
                }

                else -> t.tagPending.appendAttributeValue(c, pos, r.pos())
            }
        }
    },
    AttributeValue_singleQuoted {
        override fun read(t: Tokeniser, r: CharacterReader) {
            var pos = r.pos()
            val value = r.consumeAttributeQuoted(true)
            if (value.length > 0) t.tagPending.appendAttributeValue(value, pos, r.pos())
            else t.tagPending.setEmptyAttributeValue()

            pos = r.pos()
            val c = r.consume()
            when (c) {
                '\'' -> t.transition(TokeniserState.AfterAttributeValue_quoted)
                '&' -> {
                    val ref = t.consumeCharacterReference('\'', true)
                    if (ref != null) t.tagPending.appendAttributeValue(ref, pos, r.pos())
                    else t.tagPending.appendAttributeValue('&', pos, r.pos())
                }

                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.tagPending.appendAttributeValue(
                        TokeniserState.Companion.replacementChar,
                        pos,
                        r.pos()
                    )
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.transition(TokeniserState.Data)
                }

                else -> t.tagPending.appendAttributeValue(c, pos, r.pos())
            }
        }
    },
    AttributeValue_unquoted {
        override fun read(t: Tokeniser, r: CharacterReader) {
            var pos = r.pos()
            val value = r.consumeToAnySorted(*TokeniserState.Companion.attributeValueUnquoted)
            if (value.length > 0) t.tagPending.appendAttributeValue(value, pos, r.pos())

            pos = r.pos()
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> t.transition(TokeniserState.BeforeAttributeName)
                '&' -> {
                    val ref = t.consumeCharacterReference('>', true)
                    if (ref != null) t.tagPending.appendAttributeValue(ref, pos, r.pos())
                    else t.tagPending.appendAttributeValue('&', pos, r.pos())
                }

                '>' -> {
                    t.emitTagPending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.tagPending.appendAttributeValue(
                        TokeniserState.Companion.replacementChar,
                        pos,
                        r.pos()
                    )
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.transition(TokeniserState.Data)
                }

                '"', '\'', '<', '=', '`' -> {
                    t.error(this)
                    t.tagPending.appendAttributeValue(c, pos, r.pos())
                }

                else -> t.tagPending.appendAttributeValue(c, pos, r.pos())
            }
        }
    },

    // CharacterReferenceInAttributeValue state handled inline
    AfterAttributeValue_quoted {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> t.transition(TokeniserState.BeforeAttributeName)
                '/' -> t.transition(TokeniserState.SelfClosingStartTag)
                '>' -> {
                    t.emitTagPending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.transition(TokeniserState.Data)
                }

                '?' -> {
                    if (t.tagPending is XmlDecl) break
                    r.unconsume()
                    t.error(this)
                    t.transition(TokeniserState.BeforeAttributeName)
                }

                else -> {
                    r.unconsume()
                    t.error(this)
                    t.transition(TokeniserState.BeforeAttributeName)
                }
            }
        }
    },
    SelfClosingStartTag {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '>' -> {
                    t.tagPending.selfClosing = true
                    t.emitTagPending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    r.unconsume()
                    t.error(this)
                    t.transition(TokeniserState.BeforeAttributeName)
                }
            }
        }
    },
    BogusComment {
        override fun read(t: Tokeniser, r: CharacterReader) {
            // todo: handle bogus comment starting from eof. when does that trigger?
            t.commentPending.append(r.consumeTo('>'))
            // todo: replace nullChar with replaceChar
            val next = r.current()
            if (next == '>' || next == TokeniserState.Companion.eof) {
                r.consume()
                t.emitCommentPending()
                t.transition(TokeniserState.Data)
            }
        }
    },
    MarkupDeclarationOpen {
        // from <!
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.matchConsume("--")) {
                t.createCommentPending()
                t.transition(TokeniserState.CommentStart)
            } else if (r.matchConsumeIgnoreCase("DOCTYPE")) {
                t.transition(TokeniserState.Doctype)
            } else if (r.matchConsume("[CDATA[")) {
                // todo: should actually check current namespace, and only non-html allows cdata. until namespace
                // is implemented properly, keep handling as cdata
                //} else if (!t.currentNodeInHtmlNS() && r.matchConsume("[CDATA[")) {
                t.createTempBuffer()
                t.transition(TokeniserState.CdataSection)
            } else {
                if (t.syntax == Document.OutputSettings.Syntax.xml && r.matchesAsciiAlpha()) {
                    t.createXmlDeclPending(true)
                    t.transition(TokeniserState.TagName) // treat <!ENTITY as XML Declaration, with tag-like handling
                } else {
                    t.error(this)
                    t.createBogusCommentPending()
                    t.transition(TokeniserState.BogusComment)
                }
            }
        }
    },
    MarkupProcessingOpen {
        // From <? in syntax XML
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.matchesAsciiAlpha()) {
                t.createXmlDeclPending(false)
                t.transition(TokeniserState.TagName) // treat <?xml... as XML Declaration (processing instruction), with tag-like handling
            } else {
                t.error(this)
                t.createBogusCommentPending()
                t.commentPending.append('?') // push the ? to the start of the comment
                t.transition(TokeniserState.BogusComment)
            }
        }
    },
    CommentStart {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '-' -> t.transition(TokeniserState.CommentStartDash)
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.commentPending.append(TokeniserState.Companion.replacementChar)
                    t.transition(TokeniserState.Comment)
                }

                '>' -> {
                    t.error(this)
                    t.emitCommentPending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.emitCommentPending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    r.unconsume()
                    t.transition(TokeniserState.Comment)
                }
            }
        }
    },
    CommentStartDash {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '-' -> t.transition(TokeniserState.CommentEnd)
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.commentPending.append(TokeniserState.Companion.replacementChar)
                    t.transition(TokeniserState.Comment)
                }

                '>' -> {
                    t.error(this)
                    t.emitCommentPending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.emitCommentPending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.commentPending.append(c)
                    t.transition(TokeniserState.Comment)
                }
            }
        }
    },
    Comment {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.current()
            when (c) {
                '-' -> t.advanceTransition(TokeniserState.CommentEndDash)
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    r.advance()
                    t.commentPending.append(TokeniserState.Companion.replacementChar)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.emitCommentPending()
                    t.transition(TokeniserState.Data)
                }

                else -> t.commentPending.append(
                    r.consumeToAny(
                        '-',
                        TokeniserState.Companion.nullChar
                    )
                )
            }
        }
    },
    CommentEndDash {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '-' -> t.transition(TokeniserState.CommentEnd)
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.commentPending.append('-').append(TokeniserState.Companion.replacementChar)
                    t.transition(TokeniserState.Comment)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.emitCommentPending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.commentPending.append('-').append(c)
                    t.transition(TokeniserState.Comment)
                }
            }
        }
    },
    CommentEnd {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '>' -> {
                    t.emitCommentPending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.commentPending.append("--").append(TokeniserState.Companion.replacementChar)
                    t.transition(TokeniserState.Comment)
                }

                '!' -> t.transition(TokeniserState.CommentEndBang)
                '-' -> t.commentPending.append('-')
                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.emitCommentPending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.commentPending.append("--").append(c)
                    t.transition(TokeniserState.Comment)
                }
            }
        }
    },
    CommentEndBang {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '-' -> {
                    t.commentPending.append("--!")
                    t.transition(TokeniserState.CommentEndDash)
                }

                '>' -> {
                    t.emitCommentPending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.commentPending.append("--!").append(TokeniserState.Companion.replacementChar)
                    t.transition(TokeniserState.Comment)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.emitCommentPending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.commentPending.append("--!").append(c)
                    t.transition(TokeniserState.Comment)
                }
            }
        }
    },
    Doctype {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> t.transition(TokeniserState.BeforeDoctypeName)
                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.error(this)
                    t.createDoctypePending()
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                '>' -> {
                    t.error(this)
                    t.createDoctypePending()
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.error(this)
                    t.transition(TokeniserState.BeforeDoctypeName)
                }
            }
        }
    },
    BeforeDoctypeName {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.matchesAsciiAlpha()) {
                t.createDoctypePending()
                t.transition(TokeniserState.DoctypeName)
                return
            }
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> {}
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.createDoctypePending()
                    t.doctypePending.name.append(TokeniserState.Companion.replacementChar)
                    t.transition(TokeniserState.DoctypeName)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.createDoctypePending()
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.createDoctypePending()
                    t.doctypePending.name.append(c)
                    t.transition(TokeniserState.DoctypeName)
                }
            }
        }
    },
    DoctypeName {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.matchesAsciiAlpha()) {
                val name = r.consumeLetterSequence()
                t.doctypePending.name.append(name)
                return
            }
            val c = r.consume()
            when (c) {
                '>' -> {
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                '\t', '\n', '\r', '\f', ' ' -> t.transition(TokeniserState.AfterDoctypeName)
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.doctypePending.name.append(TokeniserState.Companion.replacementChar)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> t.doctypePending.name.append(c)
            }
        }
    },
    AfterDoctypeName {
        override fun read(t: Tokeniser, r: CharacterReader) {
            if (r.isEmpty()) {
                t.eofError(this)
                t.doctypePending.forceQuirks = true
                t.emitDoctypePending()
                t.transition(TokeniserState.Data)
                return
            }
            if (r.matchesAny('\t', '\n', '\r', '\f', ' ')) r.advance() // ignore whitespace
            else if (r.matches('>')) {
                t.emitDoctypePending()
                t.advanceTransition(TokeniserState.Data)
            } else if (r.matchConsumeIgnoreCase(DocumentType.PUBLIC_KEY)) {
                t.doctypePending.pubSysKey = DocumentType.PUBLIC_KEY
                t.transition(TokeniserState.AfterDoctypePublicKeyword)
            } else if (r.matchConsumeIgnoreCase(DocumentType.SYSTEM_KEY)) {
                t.doctypePending.pubSysKey = DocumentType.SYSTEM_KEY
                t.transition(TokeniserState.AfterDoctypeSystemKeyword)
            } else {
                t.error(this)
                t.doctypePending.forceQuirks = true
                t.advanceTransition(TokeniserState.BogusDoctype)
            }
        }
    },
    AfterDoctypePublicKeyword {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> t.transition(TokeniserState.BeforeDoctypePublicIdentifier)
                '"' -> {
                    t.error(this)
                    // set public id to empty string
                    t.transition(TokeniserState.DoctypePublicIdentifier_doubleQuoted)
                }

                '\'' -> {
                    t.error(this)
                    // set public id to empty string
                    t.transition(TokeniserState.DoctypePublicIdentifier_singleQuoted)
                }

                '>' -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.transition(TokeniserState.BogusDoctype)
                }
            }
        }
    },
    BeforeDoctypePublicIdentifier {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> {}
                '"' ->                     // set public id to empty string
                    t.transition(TokeniserState.DoctypePublicIdentifier_doubleQuoted)

                '\'' ->                     // set public id to empty string
                    t.transition(TokeniserState.DoctypePublicIdentifier_singleQuoted)

                '>' -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.transition(TokeniserState.BogusDoctype)
                }
            }
        }
    },
    DoctypePublicIdentifier_doubleQuoted {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '"' -> t.transition(TokeniserState.AfterDoctypePublicIdentifier)
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.doctypePending.publicIdentifier.append(TokeniserState.Companion.replacementChar)
                }

                '>' -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> t.doctypePending.publicIdentifier.append(c)
            }
        }
    },
    DoctypePublicIdentifier_singleQuoted {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\'' -> t.transition(TokeniserState.AfterDoctypePublicIdentifier)
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.doctypePending.publicIdentifier.append(TokeniserState.Companion.replacementChar)
                }

                '>' -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> t.doctypePending.publicIdentifier.append(c)
            }
        }
    },
    AfterDoctypePublicIdentifier {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> t.transition(TokeniserState.BetweenDoctypePublicAndSystemIdentifiers)
                '>' -> {
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                '"' -> {
                    t.error(this)
                    // system id empty
                    t.transition(TokeniserState.DoctypeSystemIdentifier_doubleQuoted)
                }

                '\'' -> {
                    t.error(this)
                    // system id empty
                    t.transition(TokeniserState.DoctypeSystemIdentifier_singleQuoted)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.transition(TokeniserState.BogusDoctype)
                }
            }
        }
    },
    BetweenDoctypePublicAndSystemIdentifiers {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> {}
                '>' -> {
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                '"' -> {
                    t.error(this)
                    // system id empty
                    t.transition(TokeniserState.DoctypeSystemIdentifier_doubleQuoted)
                }

                '\'' -> {
                    t.error(this)
                    // system id empty
                    t.transition(TokeniserState.DoctypeSystemIdentifier_singleQuoted)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.transition(TokeniserState.BogusDoctype)
                }
            }
        }
    },
    AfterDoctypeSystemKeyword {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> t.transition(TokeniserState.BeforeDoctypeSystemIdentifier)
                '>' -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                '"' -> {
                    t.error(this)
                    // system id empty
                    t.transition(TokeniserState.DoctypeSystemIdentifier_doubleQuoted)
                }

                '\'' -> {
                    t.error(this)
                    // system id empty
                    t.transition(TokeniserState.DoctypeSystemIdentifier_singleQuoted)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                }
            }
        }
    },
    BeforeDoctypeSystemIdentifier {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> {}
                '"' ->                     // set system id to empty string
                    t.transition(TokeniserState.DoctypeSystemIdentifier_doubleQuoted)

                '\'' ->                     // set public id to empty string
                    t.transition(TokeniserState.DoctypeSystemIdentifier_singleQuoted)

                '>' -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.transition(TokeniserState.BogusDoctype)
                }
            }
        }
    },
    DoctypeSystemIdentifier_doubleQuoted {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '"' -> t.transition(TokeniserState.AfterDoctypeSystemIdentifier)
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.doctypePending.systemIdentifier.append(TokeniserState.Companion.replacementChar)
                }

                '>' -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> t.doctypePending.systemIdentifier.append(c)
            }
        }
    },
    DoctypeSystemIdentifier_singleQuoted {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\'' -> t.transition(TokeniserState.AfterDoctypeSystemIdentifier)
                TokeniserState.Companion.nullChar -> {
                    t.error(this)
                    t.doctypePending.systemIdentifier.append(TokeniserState.Companion.replacementChar)
                }

                '>' -> {
                    t.error(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> t.doctypePending.systemIdentifier.append(c)
            }
        }
    },
    AfterDoctypeSystemIdentifier {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ' -> {}
                '>' -> {
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.eofError(this)
                    t.doctypePending.forceQuirks = true
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> {
                    t.error(this)
                    t.transition(TokeniserState.BogusDoctype)
                }
            }
        }
    },
    BogusDoctype {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val c = r.consume()
            when (c) {
                '>' -> {
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                TokeniserState.Companion.eof -> {
                    t.emitDoctypePending()
                    t.transition(TokeniserState.Data)
                }

                else -> {}
            }
        }
    },
    CdataSection {
        override fun read(t: Tokeniser, r: CharacterReader) {
            val data = r.consumeTo("]]>")
            t.dataBuffer.append(data)
            if (r.matchConsume("]]>") || r.isEmpty()) {
                t.emit(Token.CData(t.dataBuffer.value()))
                t.transition(TokeniserState.Data)
            } // otherwise, buffer underrun, stay in data section
        }
    };


    abstract fun read(t: Tokeniser?, r: CharacterReader?)

    companion object {
        const val nullChar: Char = '\u0000'

        // char searches. must be sorted, used in inSorted. MUST update TokeniserStateTest if more arrays are added.
        val attributeNameCharsSorted: CharArray =
            charArrayOf('\t', '\n', '\f', '\r', ' ', '"', '\'', '/', '<', '=', '>', '?')
        val attributeValueUnquoted: CharArray =
            charArrayOf(nullChar, '\t', '\n', '\f', '\r', ' ', '"', '&', '\'', '<', '=', '>', '`')

        private val replacementChar: Char = Tokeniser.Companion.replacementChar
        private val replacementStr = Tokeniser.Companion.replacementChar.toString()
        private val eof: Char = CharacterReader.Companion.EOF

        /**
         * Handles RawtextEndTagName, ScriptDataEndTagName, and ScriptDataEscapedEndTagName. Same body impl, just
         * different else exit transitions.
         */
        private fun handleDataEndTag(
            t: Tokeniser,
            r: CharacterReader,
            elseTransition: TokeniserState?
        ) {
            if (r.matchesAsciiAlpha()) {
                val name = r.consumeTagName()
                t.tagPending.appendTagName(name)
                t.dataBuffer.append(name)
                return
            }

            var needsExitTransition = false
            if (t.isAppropriateEndTagToken() && !r.isEmpty()) {
                val c = r.consume()
                when (c) {
                    '\t', '\n', '\r', '\f', ' ' -> t.transition(TokeniserState.BeforeAttributeName)
                    '/' -> t.transition(TokeniserState.SelfClosingStartTag)
                    '>' -> {
                        t.emitTagPending()
                        t.transition(TokeniserState.Data)
                    }

                    else -> {
                        t.dataBuffer.append(c)
                        needsExitTransition = true
                    }
                }
            } else {
                needsExitTransition = true
            }

            if (needsExitTransition) {
                t.emit("</")
                t.emit(t.dataBuffer.value())
                t.transition(elseTransition)
            }
        }

        private fun readRawData(
            t: Tokeniser,
            r: CharacterReader,
            current: TokeniserState?,
            advance: TokeniserState?
        ) {
            when (r.current()) {
                '<' -> t.advanceTransition(advance)
                nullChar -> {
                    t.error(current)
                    r.advance()
                    t.emit(replacementChar)
                }

                eof -> t.emit(Token.EOF())
                else -> {
                    val data = r.consumeRawData()
                    t.emit(data)
                }
            }
        }

        private fun readCharRef(t: Tokeniser, advance: TokeniserState?) {
            val c = t.consumeCharacterReference(null, false)
            if (c == null) t.emit('&')
            else t.emit(c)
            t.transition(advance)
        }

        private fun readEndTag(
            t: Tokeniser,
            r: CharacterReader,
            a: TokeniserState?,
            b: TokeniserState?
        ) {
            if (r.matchesAsciiAlpha()) {
                t.createTagPending(false)
                t.transition(a)
            } else {
                t.emit("</")
                t.transition(b)
            }
        }

        private fun handleDataDoubleEscapeTag(
            t: Tokeniser,
            r: CharacterReader,
            primary: TokeniserState?,
            fallback: TokeniserState?
        ) {
            if (r.matchesAsciiAlpha()) {
                val name = r.consumeLetterSequence()
                t.dataBuffer.append(name)
                t.emit(name)
                return
            }

            val c = r.consume()
            when (c) {
                '\t', '\n', '\r', '\f', ' ', '/', '>' -> {
                    if (t.dataBuffer.value() == "script") t.transition(primary)
                    else t.transition(fallback)
                    t.emit(c)
                }

                else -> {
                    r.unconsume()
                    t.transition(fallback)
                }
            }
        }
    }
}
