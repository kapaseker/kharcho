package io.kapaseker.kharcho.internal

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.nodes.Attribute
import io.kapaseker.kharcho.nodes.Document

/**
 * Util methods for normalizing strings. Jsoup internal use only, please don't depend on this API.
 */
object Normalizer {
    /** Drops the input string to lower case.  */
    @JvmStatic
    fun lowerCase(input: String?): String {
        return if (input != null) input.lowercase() else ""
    }

    /** Lower-cases and trims the input string.  */
    @JvmStatic
    fun normalize(input: String?): String {
        return lowerCase(input).trim { it <= ' ' }
    }

    /**
     * If a string literal, just lower case the string; otherwise lower-case and trim.
     */
    @JvmStatic
    @Deprecated("internal function; will be removed in a future version.")
    fun normalize(input: String?, isStringLiteral: Boolean): String {
        return if (isStringLiteral) lowerCase(input) else normalize(input)
    }

    /** Minimal helper to get an otherwise OK HTML name like "foo&lt;bar" to "foo_bar".  */
    @JvmStatic
    @Nullable
    fun xmlSafeTagName(tagname: String?): String? {
        return Attribute.getValidKey(
            tagname,
            Document.OutputSettings.Syntax.xml
        ) // Reuses the Attribute key normal, which is same for xml tag names
    }
}
