package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.QuietAppendable
import io.kapaseker.kharcho.internal.StringUtil

/**
 * A `<!DOCTYPE>` node.
 */
class DocumentType(name: String, publicId: String, systemId: String) : LeafNode(name) {
    /**
     * Create a new doctype element.
     * @param name the doctype's name
     * @param publicId the doctype's public ID
     * @param systemId the doctype's system ID
     */
    init {
        Validate.notNull(publicId)
        Validate.notNull(systemId)
        attributes().add(NameKey, name).add(PublicId, publicId).add(SystemId, systemId)
        updatePubSyskey()
    }

    fun setPubSysKey(value: String?) {
        if (value != null) attr(PubSysKey, value)
    }

    private fun updatePubSyskey() {
        if (has(PublicId)) {
            attributes().add(PubSysKey, PUBLIC_KEY)
        } else if (has(SystemId)) attributes().add(PubSysKey, SYSTEM_KEY)
    }

    /**
     * Get this doctype's name (when set, or empty string)
     * @return doctype name
     */
    fun name(): String? {
        return attr(NameKey)
    }

    /**
     * Get this doctype's Public ID (when set, or empty string)
     * @return doctype Public ID
     */
    fun publicId(): String? {
        return attr(PublicId)
    }

    /**
     * Get this doctype's System ID (when set, or empty string)
     * @return doctype System ID
     */
    fun systemId(): String? {
        return attr(SystemId)
    }

    public override fun nodeName(): String {
        return "#doctype"
    }

    public override fun outerHtmlHead(accum: QuietAppendable, out: Document.OutputSettings) {
        if (out.syntax() == Document.OutputSettings.Syntax.html && !has(PublicId) && !has(SystemId)) {
            // looks like a html5 doctype, go lowercase for aesthetics
            accum.append("<!doctype")
        } else {
            accum.append("<!DOCTYPE")
        }
        if (has(NameKey)) accum.append(" ").append(attr(NameKey))
        if (has(PubSysKey)) accum.append(" ").append(attr(PubSysKey))
        if (has(PublicId)) accum.append(" \"").append(attr(PublicId)).append('"')
        if (has(SystemId)) accum.append(" \"").append(attr(SystemId)).append('"')
        accum.append('>')
    }


    private fun has(attribute: String?): Boolean {
        return !StringUtil.isBlank(attr(attribute!!))
    }

    companion object {
        // todo needs a bit of a chunky cleanup. this level of detail isn't needed
        const val PUBLIC_KEY: String = "PUBLIC"
        const val SYSTEM_KEY: String = "SYSTEM"
        private const val NameKey = "name"
        private const val PubSysKey = "pubSysKey" // PUBLIC or SYSTEM
        private const val PublicId = "publicId"
        private const val SystemId = "systemId"
    }
}
