package de.norm.events.scraper

import io.mockk.coEvery
import io.mockk.mockk
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.InputStream
import java.nio.charset.Charset

private object ScraperFixtures

private fun fixtureStream(path: String): InputStream =
    checkNotNull(ScraperFixtures::class.java.classLoader.getResourceAsStream("scraper/$path")) {
        "No fixture at scraper/$path"
    }

/** The test resource `scraper/[path]` as UTF-8 text. */
fun fixtureText(path: String): String = fixtureStream(path).bufferedReader().use { it.readText() }

/**
 * The test resource `scraper/[path]`, parsed as HTML with [baseUri] for resolving relative links.
 *
 * A null [charset] parses the bytes the way `HtmlFetcher` does: Jsoup reads the charset from the
 * page's meta tag. A snapshot of a page that is not UTF-8 needs it.
 */
fun fixtureDocument(
    path: String,
    baseUri: String = "",
    charset: Charset? = Charsets.UTF_8
): Document =
    if (charset == Charsets.UTF_8) {
        Jsoup.parse(fixtureText(path), baseUri)
    } else {
        fixtureStream(path).use { Jsoup.parse(it, charset?.name(), baseUri) }
    }

/**
 * Stubs [fetcher] to answer each URL with its fixture, parsed with the URL as base URI.
 *
 * `fetch` answers a [FetchResult.Success] without validators. A test that needs an ETag, a status
 * or a sequence of answers stubs that call itself, after this one.
 */
fun stubFetcher(
    vararg pages: Pair<String, String>,
    fetcher: HtmlFetcher = mockk()
): HtmlFetcher {
    pages.forEach { (url, path) ->
        coEvery { fetcher.fetch(url, any(), any(), any()) } answers {
            FetchResult.Success(fixtureDocument(path, url), etag = null, lastModified = null)
        }
        coEvery { fetcher.fetchDocument(url) } answers { fixtureDocument(path, url) }
        coEvery { fetcher.fetchHtml(url) } answers { fixtureText(path) }
    }
    return fetcher
}
