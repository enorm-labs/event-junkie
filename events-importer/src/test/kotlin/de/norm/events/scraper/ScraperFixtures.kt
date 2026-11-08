package de.norm.events.scraper

import io.mockk.coEvery
import io.mockk.mockk
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private object ScraperFixtures

/** The test resource `scraper/[path]` as UTF-8 text. */
fun fixtureText(path: String): String {
    val stream =
        checkNotNull(ScraperFixtures::class.java.classLoader.getResourceAsStream("scraper/$path")) {
            "No fixture at scraper/$path"
        }
    return stream.bufferedReader().use { it.readText() }
}

/** The test resource `scraper/[path]`, parsed as HTML with [baseUri] for resolving relative links. */
fun fixtureDocument(
    path: String,
    baseUri: String = ""
): Document = Jsoup.parse(fixtureText(path), baseUri)

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
