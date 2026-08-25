package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import java.net.URI

/**
 * Reactive HTTP client for venues whose events come from a JSON/API source rather than a
 * scrapeable HTML page — the most stable possible source (ADR-007 §"Selector Strategy"
 * priority 1). Examples: Festsaal Kreuzberg's Wagtail headless-CMS REST API and Neue
 * Zukunft's Elfsight "Event Calendar" widget boot API.
 *
 * The counterpart to [HtmlFetcher]: same reactive [WebClient], same shared per-host
 * politeness throttle and identifying `User-Agent` (both inject the single
 * [SCRAPER_WEB_CLIENT] bean), but scoped to structured-data fetching, so a JSON importer
 * depends on a name that describes what it fetches.
 *
 * [fetchJson] returns the response **body verbatim** rather than deserializing it here.
 * Parsing stays in the venue's pure `*ApiScraper` (no I/O), which keeps it trivially
 * testable against a saved JSON snapshot and lets each scraper use its own configured
 * Jackson mapper — consistent with the HTML scrapers, which parse a pre-fetched `Document`.
 *
 * Staying on the reactive [WebClient] (rather than Spring's blocking `RestClient`) is a
 * deliberate choice to honour the non-blocking stack (ADR-001): a blocking client in a
 * coroutine importer would stall the event loop.
 */
@Component
class ApiClient(
    @Qualifier(SCRAPER_WEB_CLIENT) private val webClient: WebClient
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Fetches the raw response body from [url] (typically a JSON API endpoint).
     *
     * Returns the body verbatim so the caller's pure parser can deserialize it. Fails fast
     * with [HttpFetchException] on any 4xx/5xx so error payloads are never mistaken for data.
     *
     * @param url the API URL to fetch.
     * @return the raw response body as a string.
     */
    suspend fun fetchJson(url: String): String {
        logger.debug { "Fetching JSON body: $url" }
        return webClient
            .get()
            // Pass a pre-built URI so WebClient uses the (already percent-encoded) URL verbatim
            // instead of re-encoding '%' and double-encoding query parameters.
            .uri(URI.create(url))
            .awaitExchange { response ->
                // Fail fast on HTTP errors to avoid returning error pages as valid data.
                if (response.statusCode().isError) {
                    throw HttpFetchException(response.statusCode().value(), url)
                }
                response.awaitBody<String>()
            }
    }
}
