package io.kapaseker.kharcho.helper

import io.kapaseker.kharcho.internal.ControllableInputStream
import io.kapaseker.kharcho.internal.Normalizer
import io.kapaseker.kharcho.internal.SimpleStreamReader
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.nodes.Comment
import io.kapaseker.kharcho.nodes.Document
import io.kapaseker.kharcho.nodes.XmlDeclaration
import io.kapaseker.kharcho.parser.Parser
import io.kapaseker.kharcho.select.Selector
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/**
 * Internal static utilities for handling data.
 *
 */
object DataUtil {
    private val charsetPattern: Pattern =
        Pattern.compile("(?i)\\bcharset=\\s*(?:[\"'])?([^\\s,;\"']*)")

    @JvmField
    val UTF_8: Charset =
        Charset.forName("UTF-8") // Don't use StandardCharsets, as those only appear in Android API 19, and we target 10.
    val defaultCharsetName: String = UTF_8.name() // used if not found in header or meta charset
    private val firstReadBufferSize = 1024 * 5
    private val mimeBoundaryChars =
        "-_1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray()
    const val boundaryLength: Int = 32

    /**
     * Loads and parses a file to a Document, with the HtmlParser. Files that are compressed with gzip (and end in `.gz` or `.z`)
     * are supported in addition to uncompressed files.
     *
     * @param file file to load
     * @param charsetName (optional) character set of input; specify `null` to attempt to autodetect. A BOM in
     * the file will always override this setting.
     * @param baseUri base URI of document, to resolve relative links against
     * @return Document
     * @throws IOException on IO error
     */
    @Throws(IOException::class)
    fun load(file: File, charsetName: String?, baseUri: String): Document {
        return load(file.toPath(), charsetName, baseUri)
    }

    /**
     * Loads and parses a file to a Document. Files that are compressed with gzip (and end in `.gz` or `.z`)
     * are supported in addition to uncompressed files.
     *
     * @param file file to load
     * @param charsetName (optional) character set of input; specify `null` to attempt to autodetect. A BOM in
     * the file will always override this setting.
     * @param baseUri base URI of document, to resolve relative links against
     * @param parser alternate [parser][Parser.xmlParser] to use.
     *
     * @return Document
     * @throws IOException on IO error
     * @since 1.14.2
     */
    @Throws(IOException::class)
    fun load(
        file: File,
        charsetName: String?,
        baseUri: String,
        parser: Parser
    ): Document {
        return load(file.toPath(), charsetName, baseUri, parser)
    }

    /**
     * Loads and parses a file to a Document. Files that are compressed with gzip (and end in `.gz` or `.z`)
     * are supported in addition to uncompressed files.
     *
     * @param path file to load
     * @param charsetName (optional) character set of input; specify `null` to attempt to autodetect. A BOM in
     * the file will always override this setting.
     * @param baseUri base URI of document, to resolve relative links against
     * @param parser alternate [parser][Parser.xmlParser] to use.
     *
     * @return Document
     * @throws IOException on IO error
     * @since 1.17.2
     */
    /**
     * Loads and parses a file to a Document, with the HtmlParser. Files that are compressed with gzip (and end in `.gz` or `.z`)
     * are supported in addition to uncompressed files.
     *
     * @param path file to load
     * @param charsetName (optional) character set of input; specify `null` to attempt to autodetect. A BOM in
     * the file will always override this setting.
     * @param baseUri base URI of document, to resolve relative links against
     * @return Document
     * @throws IOException on IO error
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun load(
        path: Path,
        charsetName: String?,
        baseUri: String,
        parser: Parser = Parser.htmlParser()
    ): Document {
        return parseInputStream(openStream(path), charsetName, baseUri, parser)
    }

    /** Open an input stream from a file; if it's a gzip file, returns a GZIPInputStream to unzip it.  */
    @Throws(IOException::class)
    private fun openStream(path: Path): ControllableInputStream {
        val byteChannel = Files.newByteChannel(path)
        var stream = Channels.newInputStream(byteChannel)
        val name = Normalizer.lowerCase(path.getFileName().toString())
        if (name.endsWith(".gz") || name.endsWith(".z")) {
            try {
                val zipped = (stream.read() == 0x1f && stream.read() == 0x8b) // gzip magic bytes
                byteChannel.position(0) // reset to start of file
                if (zipped) stream = GZIPInputStream(stream)
            } catch (e: IOException) {
                stream.close() // error during our first read; close the stream and cascade close byteChannel
                throw e
            }
        }
        return ControllableInputStream.wrap(stream, 0)
    }

    /**
     * Parses a Document from an input steam.
     * @param `in` input stream to parse. The stream will be closed after reading.
     * @param charsetName character set of input (optional)
     * @param baseUri base URI of document, to resolve relative links against
     * @return Document
     * @throws IOException on IO error
     */
    @Throws(IOException::class)
    fun load(inStream: InputStream, charsetName: String?, baseUri: String): Document {
        return parseInputStream(
            ControllableInputStream.wrap(inStream, 0),
            charsetName,
            baseUri,
            Parser.htmlParser()
        )
    }

    /**
     * Parses a Document from an input steam, using the provided Parser.
     * @param `in` input stream to parse. The stream will be closed after reading.
     * @param charsetName character set of input (optional)
     * @param baseUri base URI of document, to resolve relative links against
     * @param parser alternate [parser][Parser.xmlParser] to use.
     * @return Document
     * @throws IOException on IO error
     */
    @Throws(IOException::class)
    fun load(
        inStream: InputStream,
        charsetName: String?,
        baseUri: String,
        parser: Parser
    ): Document {
        return parseInputStream(
            ControllableInputStream.wrap(inStream, 0),
            charsetName,
            baseUri,
            parser
        )
    }

    @Throws(IOException::class)
    fun parseInputStream(
        input: ControllableInputStream?,
        charsetName: String?,
        baseUri: String,
        parser: Parser
    ): Document {
        if (input == null) return Document(baseUri) // empty body


        val doc: Document
        var charsetDoc: CharsetDoc? = null
        try {
            charsetDoc = detectCharset(input, charsetName, baseUri, parser)
            doc = parseInputStream(charsetDoc, baseUri, parser)
        } finally {
            charsetDoc?.input?.close()
        }
        return doc
    }

    private val metaCharset = Selector.evaluatorOf("meta[http-equiv=content-type], meta[charset]")

    @Throws(IOException::class)
    fun detectCharset(
        input: ControllableInputStream,
        charsetName: String?,
        baseUri: String,
        parser: Parser
    ): CharsetDoc {
        var charsetName = charsetName
        var doc: Document? = null
        // read the start of the stream and look for a BOM or meta charset:
        // look for BOM - overrides any other header or input
        val bomCharset = detectCharsetFromBom(input) // resets / consumes appropriately
        if (bomCharset != null) charsetName = bomCharset

        if (charsetName == null) { // read ahead and determine from meta. safe first parse as UTF-8
            val origMax: Int = input.max()
            input.max(firstReadBufferSize)
            input.mark(firstReadBufferSize)
            input.allowClose(false) // ignores closes during parse, in case we need to rewind
            try {
                SimpleStreamReader(
                    input,
                    UTF_8
                ).use { reader ->  // input is currently capped to firstReadBufferSize
                    doc = parser.parseInput(reader, baseUri)
                    input.reset()
                    input.max(origMax) // reset for a full read if required
                }
            } catch (e: UncheckedIOException) {
                throw e.cause ?: IOException("Parsing failed", e)
            } finally {
                input.allowClose(true)
            }

            // look for <meta http-equiv="Content-Type" content="text/html;charset=gb2312"> or HTML5 <meta charset="gb2312">
            val metaElements = doc!!.select(metaCharset)
            var foundCharset: String? = null // if not found, will keep utf-8 as best attempt
            for (meta in metaElements) {
                if (meta!!.hasAttr("http-equiv")) foundCharset =
                    getCharsetFromContentType(meta.attr("content"))
                if (foundCharset == null && meta.hasAttr("charset")) foundCharset =
                    meta.attr("charset")
                if (foundCharset != null) break
            }

            // look for <?xml encoding='ISO-8859-1'?>
            if (foundCharset == null && doc.childNodeSize() > 0) {
                val first = doc.childNode(0)
                var decl: XmlDeclaration? = null
                if (first is XmlDeclaration) decl = first
                else if (first is Comment) {
                    if (first.isXmlDeclaration) decl = first.asXmlDeclaration()
                }
                if (decl != null && decl.name().equals("xml", ignoreCase = true)) {
                    foundCharset = decl.attr("encoding")
                }
            }
            foundCharset = validateCharset(foundCharset)
            if (foundCharset != null && !foundCharset.equals(
                    defaultCharsetName,
                    ignoreCase = true
                )
            ) { // need to re-decode. (case-insensitive check here to match how validate works)
                foundCharset = foundCharset.trim { it <= ' ' }.replace("[\"']".toRegex(), "")
                charsetName = foundCharset
                doc = null
            } else if (input.baseReadFully()) { // if we have read fully, and the charset was correct, keep that current parse
                input.close() // the parser tried to close it
            } else {
                doc = null
            }
        } else { // specified by content type header (or by user on file load)
            Validate.notEmpty(
                charsetName,
                "Must set charset arg to character set of file to parse. Set to null to attempt to detect from HTML"
            )
        }

        // finally: prepare the return struct
        if (charsetName == null) charsetName = defaultCharsetName
        val charset = if (charsetName == defaultCharsetName) UTF_8 else Charset.forName(charsetName)
        return CharsetDoc(charset, doc, input)
    }

    @Throws(IOException::class)
    fun parseInputStream(charsetDoc: CharsetDoc, baseUri: String, parser: Parser): Document {
        // if doc != null it was fully parsed during charset detection; so just return that
        if (charsetDoc.doc != null) return charsetDoc.doc!!

        val input = charsetDoc.input
        Validate.notNull(input)
        val doc: Document
        val charset = charsetDoc.charset
        SimpleStreamReader(input, charset).use { reader ->
            try {
                doc = parser.parseInput(reader, baseUri)
            } catch (e: UncheckedIOException) {
                // io exception when parsing (not seen before because reading the stream as we go)
                throw e.cause ?: IOException("Parsing failed", e)
            }
            doc.outputSettings().charset(charset)
            if (!charset.canEncode()) {
                // some charsets can read but not encode; switch to an encodable charset and update the meta el
                doc.charset(UTF_8)
            }
        }
        return doc
    }

    fun emptyByteBuffer(): ByteBuffer {
        return ByteBuffer.allocate(0)
    }

    /**
     * Parse out a charset from a content type header. If the charset is not supported, returns null (so the default
     * will kick in.)
     * @param contentType e.g. "text/html; charset=EUC-JP"
     * @return "EUC-JP", or null if not found. Charset is trimmed and uppercased.
     */
    fun getCharsetFromContentType(contentType: String?): String? {
        if (contentType == null) return null
        val m = charsetPattern.matcher(contentType)
        if (m.find()) {
            var charset = m.group(1).trim { it <= ' ' }
            charset = charset.replace("charset=", "")
            return validateCharset(charset)
        }
        return null
    }

    private fun validateCharset(cs: String?): String? {
        var cs = cs
        if (cs == null || cs.length == 0) return null
        cs = cs.trim { it <= ' ' }.replace("[\"']".toRegex(), "")
        try {
            if (Charset.isSupported(cs)) return cs
            cs = cs.uppercase()
            if (Charset.isSupported(cs)) return cs
        } catch (e: IllegalCharsetNameException) {
            // if all this charset matching fails.... we just take the default
        }
        return null
    }

    /**
     * Creates a random string, suitable for use as a mime boundary
     */
    fun mimeBoundary(): String {
        val mime = StringUtil.borrowBuilder()
        val rand = Random()
        for (i in 0..<boundaryLength) {
            mime.append(mimeBoundaryChars[rand.nextInt(mimeBoundaryChars.size)])
        }
        return StringUtil.releaseBuilder(mime)
    }

    @Throws(IOException::class)
    private fun detectCharsetFromBom(input: ControllableInputStream): String? {
        val bom = ByteArray(4)
        input.mark(bom.size)
        input.read(bom, 0, 4)
        input.reset()

        // 16 and 32 decoders consume the BOM to determine be/le; utf-8 should be consumed here
        if (bom[0].toInt() == 0x00 && bom[1].toInt() == 0x00 && bom[2] == 0xFE.toByte() && bom[3] == 0xFF.toByte() ||  // BE
            bom[0] == 0xFF.toByte() && bom[1] == 0xFE.toByte() && bom[2].toInt() == 0x00 && bom[3].toInt() == 0x00
        ) { // LE
            return "UTF-32" // and I hope it's on your system
        } else if (bom[0] == 0xFE.toByte() && bom[1] == 0xFF.toByte() ||  // BE
            bom[0] == 0xFF.toByte() && bom[1] == 0xFE.toByte()
        ) {
            return "UTF-16" // in all Javas
        } else if (bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte()) {
            input.read(bom, 0, 3) // consume the UTF-8 BOM
            return "UTF-8" // in all Javas
        }
        return null
    }
}

/** A struct to return a detected charset, and a document (if fully read).  */
class CharsetDoc(
    var charset: Charset,
    var doc: Document?,
    var input: InputStream
)
