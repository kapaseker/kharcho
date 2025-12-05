package io.kapaseker.kharcho

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.helper.DataUtil
import io.kapaseker.kharcho.internal.SharedConstants
import io.kapaseker.kharcho.nodes.Document
import io.kapaseker.kharcho.parser.Parser
import io.kapaseker.kharcho.safety.Cleaner
import io.kapaseker.kharcho.safety.Safelist
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path

/**
 * The core public access point to the jsoup functionality.
 *
 * @author Jonathan Hedley
 */
object Kharcho {
    /**
     * Parse HTML into a Document. The parser will make a sensible, balanced document tree out of any HTML.
     *
     * @param html    HTML to parse
     * @param baseUri The URL where the HTML was retrieved from. Used to resolve relative URLs to absolute URLs, that occur
     * before the HTML declares a `<base href>` tag.
     * @return sane HTML
     */
    @JvmStatic
    fun parse(html: String, baseUri: String): Document {
        return Parser.parse(html, baseUri)
    }

    /**
     * Parse HTML into a Document, using the provided Parser. You can provide an alternate parser, such as a simple XML
     * (non-HTML) parser.
     *
     * @param html    HTML to parse
     * @param baseUri The URL where the HTML was retrieved from. Used to resolve relative URLs to absolute URLs, that occur
     * before the HTML declares a `<base href>` tag.
     * @param parser  alternate [parser][Parser.xmlParser] to use.
     * @return sane HTML
     */
    @JvmStatic
    fun parse(html: String, baseUri: String, parser: Parser): Document {
        return parser.parseInput(html, baseUri)
    }

    /**
     * Parse HTML into a Document, using the provided Parser. You can provide an alternate parser, such as a simple XML
     * (non-HTML) parser.  As no base URI is specified, absolute URL resolution, if required, relies on the HTML including
     * a `<base href>` tag.
     *
     * @param html   HTML to parse
     * before the HTML declares a `<base href>` tag.
     * @param parser alternate [parser][Parser.xmlParser] to use.
     * @return sane HTML
     */
    @JvmStatic
    fun parse(html: String, parser: Parser): Document {
        return parser.parseInput(html, "")
    }

    /**
     * Parse HTML into a Document. As no base URI is specified, absolute URL resolution, if required, relies on the HTML
     * including a `<base href>` tag.
     *
     * @param html HTML to parse
     * @return sane HTML
     * @see .parse
     */
    @JvmStatic
    fun parse(html: String): Document {
        return Parser.parse(html, "")
    }

    /**
     * Parse the contents of a file as HTML.
     *
     * @param file        file to load HTML from. Supports gzipped files (ending in .z or .gz).
     * @param charsetName (optional) character set of file contents. Set to `null` to determine from `http-equiv` meta tag, if
     * present, or fall back to `UTF-8` (which is often safe to do).
     * @param baseUri     The URL where the HTML was retrieved from, to resolve relative links against.
     * @return sane HTML
     * @throws IOException if the file could not be found, or read, or if the charsetName is invalid.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parse(file: File, charsetName: @Nullable String, baseUri: String): Document {
        return DataUtil.load(file, charsetName, baseUri)
    }

    /**
     * Parse the contents of a file as HTML. The location of the file is used as the base URI to qualify relative URLs.
     *
     * @param file        file to load HTML from. Supports gzipped files (ending in .z or .gz).
     * @param charsetName (optional) character set of file contents. Set to `null` to determine from `http-equiv` meta tag, if
     * present, or fall back to `UTF-8` (which is often safe to do).
     * @return sane HTML
     * @throws IOException if the file could not be found, or read, or if the charsetName is invalid.
     * @see .parse
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parse(file: File, charsetName: @Nullable String): Document {
        return DataUtil.load(file, charsetName, file.getAbsolutePath())
    }

    /**
     * Parse the contents of a file as HTML. The location of the file is used as the base URI to qualify relative URLs.
     * The charset used to read the file will be determined by the byte-order-mark (BOM), or a `<meta charset>` tag,
     * or if neither is present, will be `UTF-8`.
     *
     *
     * This is the equivalent of calling [parse(file, null)][.parse]
     *
     * @param file the file to load HTML from. Supports gzipped files (ending in .z or .gz).
     * @return sane HTML
     * @throws IOException if the file could not be found or read.
     * @see .parse
     * @since 1.15.1
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parse(file: File): Document {
        return DataUtil.load(file, null, file.getAbsolutePath())
    }

    /**
     * Parse the contents of a file as HTML.
     *
     * @param file        file to load HTML from. Supports gzipped files (ending in .z or .gz).
     * @param charsetName (optional) character set of file contents. Set to `null` to determine from `http-equiv` meta tag, if
     * present, or fall back to `UTF-8` (which is often safe to do).
     * @param baseUri     The URL where the HTML was retrieved from, to resolve relative links against.
     * @param parser      alternate [parser][Parser.xmlParser] to use.
     * @return sane HTML
     * @throws IOException if the file could not be found, or read, or if the charsetName is invalid.
     * @since 1.14.2
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parse(
        file: File,
        charsetName: @Nullable String,
        baseUri: String,
        parser: Parser
    ): Document {
        return DataUtil.load(file, charsetName, baseUri, parser)
    }

    /**
     * Parse the contents of a file as HTML.
     *
     * @param path        file to load HTML from. Supports gzipped files (ending in .z or .gz).
     * @param charsetName (optional) character set of file contents. Set to `null` to determine from `http-equiv` meta tag, if
     * present, or fall back to `UTF-8` (which is often safe to do).
     * @param baseUri     The URL where the HTML was retrieved from, to resolve relative links against.
     * @return sane HTML
     * @throws IOException if the file could not be found, or read, or if the charsetName is invalid.
     * @since 1.18.1
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parse(path: Path, charsetName: @Nullable String, baseUri: String): Document {
        return DataUtil.load(path, charsetName, baseUri)
    }

    /**
     * Parse the contents of a file as HTML. The location of the file is used as the base URI to qualify relative URLs.
     *
     * @param path        file to load HTML from. Supports gzipped files (ending in .z or .gz).
     * @param charsetName (optional) character set of file contents. Set to `null` to determine from `http-equiv` meta tag, if
     * present, or fall back to `UTF-8` (which is often safe to do).
     * @return sane HTML
     * @throws IOException if the file could not be found, or read, or if the charsetName is invalid.
     * @see .parse
     * @since 1.18.1
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parse(path: Path, charsetName: @Nullable String): Document {
        return DataUtil.load(path, charsetName, path.toAbsolutePath().toString())
    }

    /**
     * Parse the contents of a file as HTML. The location of the file is used as the base URI to qualify relative URLs.
     * The charset used to read the file will be determined by the byte-order-mark (BOM), or a `<meta charset>` tag,
     * or if neither is present, will be `UTF-8`.
     *
     *
     * This is the equivalent of calling [parse(file, null)][.parse]
     *
     * @param path the file to load HTML from. Supports gzipped files (ending in .z or .gz).
     * @return sane HTML
     * @throws IOException if the file could not be found or read.
     * @see .parse
     * @since 1.18.1
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parse(path: Path): Document {
        return DataUtil.load(path, null, path.toAbsolutePath().toString())
    }

    /**
     * Parse the contents of a file as HTML.
     *
     * @param path        file to load HTML from. Supports gzipped files (ending in .z or .gz).
     * @param charsetName (optional) character set of file contents. Set to `null` to determine from `http-equiv` meta tag, if
     * present, or fall back to `UTF-8` (which is often safe to do).
     * @param baseUri     The URL where the HTML was retrieved from, to resolve relative links against.
     * @param parser      alternate [parser][Parser.xmlParser] to use.
     * @return sane HTML
     * @throws IOException if the file could not be found, or read, or if the charsetName is invalid.
     * @since 1.18.1
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parse(
        path: Path,
        charsetName: @Nullable String,
        baseUri: String,
        parser: Parser
    ): Document {
        return DataUtil.load(path, charsetName, baseUri, parser)
    }

    /**
     * Read an input stream, and parse it to a Document.
     *
     * @param in          input stream to read. The stream will be closed after reading.
     * @param charsetName (optional) character set of file contents. Set to `null` to determine from `http-equiv` meta tag, if
     * present, or fall back to `UTF-8` (which is often safe to do).
     * @param baseUri     The URL where the HTML was retrieved from, to resolve relative links against.
     * @return sane HTML
     * @throws IOException if the stream could not be read, or if the charsetName is invalid.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parse(`in`: InputStream, charsetName: @Nullable String, baseUri: String): Document {
        return DataUtil.load(`in`, charsetName, baseUri)
    }

    /**
     * Read an input stream, and parse it to a Document. You can provide an alternate parser, such as a simple XML
     * (non-HTML) parser.
     *
     * @param in          input stream to read. Make sure to close it after parsing.
     * @param charsetName (optional) character set of file contents. Set to `null` to determine from `http-equiv` meta tag, if
     * present, or fall back to `UTF-8` (which is often safe to do).
     * @param baseUri     The URL where the HTML was retrieved from, to resolve relative links against.
     * @param parser      alternate [parser][Parser.xmlParser] to use.
     * @return sane HTML
     * @throws IOException if the stream could not be read, or if the charsetName is invalid.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parse(
        `in`: InputStream,
        charsetName: @Nullable String,
        baseUri: String,
        parser: Parser
    ): Document {
        return DataUtil.load(`in`, charsetName, baseUri, parser)
    }

    /**
     * Parse a fragment of HTML, with the assumption that it forms the `body` of the HTML.
     *
     * @param bodyHtml body HTML fragment
     * @param baseUri  URL to resolve relative URLs against.
     * @return sane HTML document
     * @see Document.body
     */
    fun parseBodyFragment(bodyHtml: String, baseUri: String): Document {
        return Parser.parseBodyFragment(bodyHtml, baseUri)
    }

    /**
     * Parse a fragment of HTML, with the assumption that it forms the `body` of the HTML.
     *
     * @param bodyHtml body HTML fragment
     * @return sane HTML document
     * @see Document.body
     */
    fun parseBodyFragment(bodyHtml: String): Document {
        return Parser.parseBodyFragment(bodyHtml, "")
    }

    /**
     * Get safe HTML from untrusted input HTML, by parsing input HTML and filtering it through an allow-list of safe
     * tags and attributes.
     *
     * @param bodyHtml input untrusted HTML (body fragment)
     * @param baseUri  URL to resolve relative URLs against
     * @param safelist list of permitted HTML elements
     * @return safe HTML (body fragment)
     * @see Cleaner.clean
     */
    fun clean(bodyHtml: String, baseUri: String, safelist: Safelist): String {
        var baseUri = baseUri
        if (baseUri.isEmpty() && safelist.preserveRelativeLinks()) {
            baseUri =
                SharedConstants.DummyUri // set a placeholder URI to allow relative links to pass abs resolution for protocol tests; won't leak to output
        }

        val dirty = parseBodyFragment(bodyHtml, baseUri)
        val cleaner = Cleaner(safelist)
        val clean = cleaner.clean(dirty)
        return clean.body().html()
    }

    /**
     * Get safe HTML from untrusted input HTML, by parsing input HTML and filtering it through a safe-list of permitted
     * tags and attributes.
     *
     *
     * Note that as this method does not take a base href URL to resolve attributes with relative URLs against, those
     * URLs will be removed, unless the input HTML contains a `<base href> tag`. If you wish to preserve those, use
     * the [Jsoup.clean] method instead, and enable
     * [Safelist.preserveRelativeLinks].
     *
     *
     * Note that the output of this method is still **HTML** even when using the TextNode only
     * [Safelist.none], and so any HTML entities in the output will be appropriately escaped.
     * If you want plain text, not HTML, you should use a text method such as [Element.text] instead, after
     * cleaning the document.
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
     * @param bodyHtml input untrusted HTML (body fragment)
     * @param safelist list of permitted HTML elements
     * @return safe HTML (body fragment)
     * @see Cleaner.clean
     */
    @JvmStatic
    fun clean(bodyHtml: String, safelist: Safelist): String {
        return clean(bodyHtml, "", safelist)
    }

    /**
     * Get safe HTML from untrusted input HTML, by parsing input HTML and filtering it through a safe-list of
     * permitted tags and attributes.
     *
     * The HTML is treated as a body fragment; it's expected the cleaned HTML will be used within the body of an
     * existing document. If you want to clean full documents, use [Cleaner.clean] instead, and add
     * structural tags (`html, head, body` etc) to the safelist.
     *
     * @param bodyHtml       input untrusted HTML (body fragment)
     * @param baseUri        URL to resolve relative URLs against
     * @param safelist       list of permitted HTML elements
     * @param outputSettings document output settings; use to control pretty-printing and entity escape modes
     * @return safe HTML (body fragment)
     * @see Cleaner.clean
     */
    fun clean(
        bodyHtml: String,
        baseUri: String,
        safelist: Safelist,
        outputSettings: Document.OutputSettings
    ): String {
        val dirty = parseBodyFragment(bodyHtml, baseUri)
        val cleaner = Cleaner(safelist)
        val clean = cleaner.clean(dirty)
        clean.outputSettings(outputSettings)
        return clean.body().html()
    }

    /**
     * Test if the input body HTML has only tags and attributes allowed by the Safelist. Useful for form validation.
     *
     *
     * This method is intended to be used in a user interface as a validator for user input. Note that regardless of the
     * output of this method, the input document **must always** be normalized using a method such as
     * [.clean], and the result of that method used to store or serialize the document
     * before later reuse such as presentation to end users. This ensures that enforced attributes are set correctly, and
     * that any differences between how a given browser and how jsoup parses the input HTML are normalized.
     *
     *
     * Example:
     * <pre>`Safelist safelist = Safelist.relaxed();
     * boolean isValid = Jsoup.isValid(sourceBodyHtml, safelist);
     * String normalizedHtml = Jsoup.clean(sourceBodyHtml, "https://example.com/", safelist);
    `</pre> *
     *
     * Assumes the HTML is a body fragment (i.e. will be used in an existing HTML document body.)
     *
     * @param bodyHtml HTML to test
     * @param safelist safelist to test against
     * @return true if no tags or attributes were removed; false otherwise
     * @see .clean
     */
    fun isValid(bodyHtml: String, safelist: Safelist): Boolean {
        return Cleaner(safelist).isValidBodyHtml(bodyHtml)
    }
}
