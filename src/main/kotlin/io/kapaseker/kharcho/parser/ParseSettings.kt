package io.kapaseker.kharcho.parser

import io.kapaseker.kharcho.internal.Normalizer.lowerCase
import io.kapaseker.kharcho.internal.Normalizer.normalize
import io.kapaseker.kharcho.nodes.Attributes

/**
 * Controls parser case settings, to optionally preserve tag and/or attribute name case.
 */
class ParseSettings(tag: Boolean, attribute: Boolean) {
    private val preserveTagCase: Boolean
    private val preserveAttributeCase: Boolean

    /**
     * Returns true if preserving tag name case.
     */
    fun preserveTagCase(): Boolean {
        return preserveTagCase
    }

    /**
     * Returns true if preserving attribute case.
     */
    fun preserveAttributeCase(): Boolean {
        return preserveAttributeCase
    }

    /**
     * Define parse settings.
     * @param tag preserve tag case?
     * @param attribute preserve attribute name case?
     */
    init {
        preserveTagCase = tag
        preserveAttributeCase = attribute
    }

    internal constructor(copy: ParseSettings) : this(
        copy.preserveTagCase,
        copy.preserveAttributeCase
    )

    /**
     * Normalizes a tag name according to the case preservation setting.
     */
    fun normalizeTag(name: String): String {
        var name = name
        name = name.trim { it <= ' ' }
        if (!preserveTagCase) name = lowerCase(name)
        return name
    }

    /**
     * Normalizes an attribute according to the case preservation setting.
     */
    fun normalizeAttribute(name: String): String {
        var name = name
        name = name.trim { it <= ' ' }
        if (!preserveAttributeCase) name = lowerCase(name)
        return name
    }

    fun normalizeAttributes(attributes: Attributes) {
        if (!preserveAttributeCase) {
            attributes.normalize()
        }
    }

    companion object {
        /**
         * HTML default settings: both tag and attribute names are lower-cased during parsing.
         */
        @JvmField
        val htmlDefault: ParseSettings

        /**
         * Preserve both tag and attribute case.
         */
        @JvmField
        val preserveCase: ParseSettings

        init {
            htmlDefault = ParseSettings(false, false)
            preserveCase = ParseSettings(true, true)
        }

        /** Returns the normal name that a Tag will have (trimmed and lower-cased)  */
        fun normalName(name: String?): String {
            return normalize(name)
        }
    }
}
