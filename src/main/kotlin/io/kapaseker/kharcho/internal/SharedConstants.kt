package io.kapaseker.kharcho.internal

/**
 * jsoup constants used between packages. Do not use as they may change without warning. Users will not be able to see
 * this package when modules are enabled.
 */
object SharedConstants {
    const val UserDataKey: String = "/jsoup.userdata"
    const val AttrRangeKey: String = "jsoup.attrs"
    const val RangeKey: String = "jsoup.start"
    const val EndRangeKey: String = "jsoup.end"
    const val XmlnsAttr: String = "jsoup.xmlns-"

    val DefaultBufferSize: Int = 8 * 1024

    @JvmField
    val FormSubmitTags: Array<String?> = arrayOf<String?>(
        "input", "keygen", "object", "select", "textarea"
    )

    const val DummyUri: String =
        "https://dummy.example/" // used as a base URI if none provided, to allow abs url resolution to preserve relative links

    const val UseHttpClient: String = "jsoup.useHttpClient"

    const val UseRe2j: String =
        "jsoup.useRe2j" // enables use of the re2j regular expression engine when true and it's on the classpath
}
