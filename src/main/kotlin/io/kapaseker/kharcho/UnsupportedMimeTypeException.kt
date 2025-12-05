package io.kapaseker.kharcho

import java.io.IOException

/**
 * Signals that a HTTP response returned a mime type that is not supported.
 */
class UnsupportedMimeTypeException(message: String?, val mimeType: String?, val url: String?) :
    IOException(message) {
    override fun toString(): String {
        return buildString {
            append(super.toString())
            append(". Mimetype=")
            append(mimeType)
            append(", URL=")
            append(url)
        }
    }
}
