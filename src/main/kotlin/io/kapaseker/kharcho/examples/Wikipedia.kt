package io.kapaseker.kharcho.examples

import io.kapaseker.kharcho.Jsoup
import io.kapaseker.kharcho.nodes.Document
import java.net.URI
import javax.net.ssl.HttpsURLConnection


/**
 * A simple example, used on the kharcho website.
 *
 * To invoke from the command line, assuming you've downloaded the jsoup-examples
 * jar to your current directory:
 *
 */
fun main() {
    val doc: Document = Jsoup.parse(fetchContent("https://nba.hupu.com/"))
    log(doc.title())

    val newsHeadlines = doc.select(".normal.list-item-link.PSiteBasketballRecommentList_W")
    for (headline in newsHeadlines) {
        log("%s\n\t%s", headline.text(), headline.absUrl("href"))
    }
}

fun fetchContent(url: String): String {

    val url = URI(url).toURL()
    val connection = url.openConnection() as HttpsURLConnection

    connection.requestMethod = "GET"
    connection.connect()

    val content = connection.inputStream.bufferedReader().use { it.readText() }
    connection.disconnect()

    return content
}

private fun log(msg: String, vararg vals: String?) {
    println(String.format(msg, *vals))
}

