package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.Charset

/**
 * Reactive HTML fetcher with conditional-request support and Jsoup parsing.
 *
 * Uses the shared, politeness-throttled scraper [WebClient] ([SCRAPER_WEB_CLIENT]) for
 * non-blocking HTTP fetching and Jsoup for HTML parsing. Supports ETag / Last-Modified
 * conditional headers to skip re-downloading pages that haven't changed since the last import.
 *
 * Jsoup's `parse()` is a CPU-bound blocking call, so it runs on an
 * injected IO dispatcher to avoid blocking the coroutine event loop.
 *
 * Response bodies are read as **bytes**, not as a decoded `String`: a retro venue host
 * (Arcanoa) answers `Content-Type: text/html` with no `charset` parameter while the page
 * is Latin-1, and Spring's `StringDecoder` falls back to UTF-8 in that case — which turns
 * every umlaut into a replacement character before a scraper ever sees it. Handing Jsoup
 * the raw bytes lets it apply the standard detection chain (BOM → HTTP `charset` →
 * `<meta charset>` → UTF-8), so the declared encoding wins wherever it is stated.
 *
 * Venues whose events come from a JSON/API source rather than a scrapeable HTML page use
 * [ApiClient] instead — it shares the same [WebClient] bean (and therefore the same per-host
 * throttle and User-Agent), so this class stays focused purely on HTML.
 *
 * Per-host politeness throttling is handled transparently by [PerHostThrottlingFilter],
 * registered as a filter on the shared [WebClient] (see [ScraperHttpClientConfig]).
 */
@Component
class HtmlFetcher(
    @Qualifier(SCRAPER_WEB_CLIENT) private val webClient: WebClient,
    @Qualifier("ioDispatcher") private val ioDispatcher: CoroutineDispatcher
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Fetches and parses HTML from [url] with optional conditional-request headers.
     *
     * If both [etag] and [lastModified] are `null`, an unconditional GET is performed.
     * If the server responds with 304 Not Modified, returns [FetchResult.NotModified].
     * Otherwise, parses the HTML body and returns [FetchResult.Success].
     *
     * @param etag the ETag value from a previous fetch (sent as `If-None-Match`).
     * @param lastModified the Last-Modified value from a previous fetch (sent as `If-Modified-Since`).
     * @return a [FetchResult] indicating whether the page was modified or not.
     */
    suspend fun fetch(
        url: String,
        etag: String? = null,
        lastModified: String? = null
    ): FetchResult {
        // `url` as a payload field rather than inside the sentence (#945): it is the value the
        // "did this source 304 or actually change" question filters on. The two validators stay in
        // the text — opaque per-page hashes make a high-cardinality field nothing aggregates on.
        logger.at(Level.INFO) {
            message = "Fetching source page (etag=$etag, lastModified=$lastModified)"
            payload = mapOf(LogFields.URL to url)
        }
        return webClient
            .get()
            // Pass a pre-built URI so WebClient uses the (already percent-encoded) URL verbatim.
            // Passing a String treats it as a URI template and re-encodes '%', double-encoding
            // already-escaped paths (e.g. non-ASCII slugs) into a 404.
            .uri(URI.create(url))
            .apply {
                etag?.let { header("If-None-Match", it) }
                lastModified?.let { header("If-Modified-Since", it) }
            }.awaitExchange { response ->
                handleResponse(response, url)
            }
    }

    /**
     * Fetches and parses HTML from [url] without conditional-request headers.
     *
     * Convenience method for fetching secondary pages (e.g. event detail pages)
     * where change detection is not needed. Returns a parsed Jsoup [Document]
     * with parsing executed on the IO dispatcher to avoid blocking the coroutine
     * event loop.
     *
     * @return a parsed Jsoup [Document].
     */
    suspend fun fetchDocument(url: String): Document {
        val body = fetchRawBody(url)
        return parseHtml(body, url)
    }

    /**
     * Fetches raw HTML from [url] without conditional-request headers.
     *
     * Lower-level convenience method for fetching secondary pages (e.g. event detail pages)
     * where change detection is not needed. Prefer [fetchDocument] when a parsed [Document]
     * is needed, as it also moves Jsoup parsing to the IO dispatcher and lets Jsoup detect
     * the page's encoding from its `<meta>` tag. Fails fast with [HttpFetchException] on any
     * 4xx/5xx so error pages are never parsed as valid event data.
     *
     * @return the raw HTML body as a string, decoded with the charset the server declared
     *   (UTF-8 when it declared none — the meta tag cannot be honoured without parsing).
     */
    suspend fun fetchHtml(url: String): String {
        val body = fetchRawBody(url)
        return String(body.bytes, body.charset())
    }

    /**
     * Fetches [url] as raw bytes, failing fast with [HttpFetchException] on any 4xx/5xx.
     *
     * The bytes stay undecoded so the caller can apply the page's own declared encoding
     * (see the class KDoc) rather than Spring's UTF-8 default.
     */
    private suspend fun fetchRawBody(url: String): RawBody {
        logger.debug { "Fetching HTML body: $url" }
        return webClient
            .get()
            // Pass a pre-built URI so WebClient uses the (already percent-encoded) URL verbatim
            // instead of re-encoding '%' and double-encoding non-ASCII slugs into a 404.
            .uri(URI.create(url))
            .awaitExchange { response ->
                // Fail fast on HTTP errors to avoid returning error pages as valid data
                if (response.statusCode().isError) {
                    throw HttpFetchException(response.statusCode().value(), url)
                }
                RawBody(response.awaitBody<ByteArray>(), response.declaredCharsetName())
            }
    }

    /**
     * Processes the HTTP response: returns [FetchResult.NotModified] on 304,
     * or parses the body into a [FetchResult.Success] otherwise.
     */
    private suspend fun handleResponse(
        response: ClientResponse,
        url: String
    ): FetchResult {
        if (response.statusCode() == HttpStatus.NOT_MODIFIED) {
            logger.at(Level.INFO) {
                message = "Page not modified"
                payload = mapOf(LogFields.URL to url, LogFields.HTTP_STATUS to HttpStatus.NOT_MODIFIED.value())
            }
            return FetchResult.NotModified
        }

        // Fail fast on HTTP errors to avoid parsing error pages as valid event data
        if (response.statusCode().isError) {
            throw HttpFetchException(response.statusCode().value(), url)
        }

        val body = RawBody(response.awaitBody<ByteArray>(), response.declaredCharsetName())
        val newEtag = response.headers().asHttpHeaders().eTag
        val newLastModified = response.headers().asHttpHeaders().getFirst("Last-Modified")

        logger.at(Level.INFO) {
            message = "Fetched ${body.bytes.size} bytes (newEtag=$newEtag, newLastModified=$newLastModified)"
            payload = mapOf(LogFields.URL to url, LogFields.HTTP_STATUS to response.statusCode().value())
        }

        val document = parseHtml(body, url)
        return FetchResult.Success(
            document = document,
            etag = newEtag,
            lastModified = newLastModified
        )
    }

    /**
     * Parses a raw HTML body into a Jsoup [Document] on the IO dispatcher to avoid blocking
     * the coroutine event loop.
     *
     * The server-declared charset is passed through when there is one; otherwise `null` hands
     * Jsoup the detection job (BOM, then `<meta charset>`, then UTF-8).
     */
    private suspend fun parseHtml(
        body: RawBody,
        baseUri: String
    ): Document =
        withContext(ioDispatcher) {
            Jsoup.parse(ByteArrayInputStream(body.bytes), body.charsetName, baseUri)
        }

    /** The charset from the response's `Content-Type`, or `null` when the server declared none. */
    private fun ClientResponse.declaredCharsetName(): String? =
        headers()
            .asHttpHeaders()
            .contentType
            ?.charset
            ?.name()

    /**
     * An undecoded response body plus the charset name the server declared for it, if any.
     *
     * Deliberately not a `data class`: the payload is a [ByteArray], whose identity-based
     * `equals`/`hashCode` would make generated ones misleading.
     */
    private class RawBody(
        val bytes: ByteArray,
        val charsetName: String?
    ) {
        /** The declared charset, falling back to UTF-8 for an absent or unknown name. */
        fun charset(): Charset = charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }
}

/**
 * Result of an HTML fetch operation.
 */
sealed interface FetchResult {
    /** The page has not been modified since the last fetch (304 response). */
    data object NotModified : FetchResult

    data class Success(
        val document: Document,
        /** New ETag header from the response, if present. */
        val etag: String?,
        /** New Last-Modified header from the response, if present. */
        val lastModified: String?
    ) : FetchResult
}

/**
 * Exception thrown when an HTTP fetch returns an error status code (4xx/5xx).
 *
 * Propagates up to [EventImportService.importFromSource]'s catch block where it is
 * recorded as a failure on the event source.
 */
class HttpFetchException(
    /**
     * Kept as a property rather than only being formatted into the message (#415): the metric tag
     * `importer.scrape.failures{reason}` has to tell a 403 from a 500 from a parse failure, and
     * re-extracting a number from a human-readable string to do that is the kind of parsing that
     * breaks the next time someone improves the wording.
     */
    val statusCode: Int,
    url: String
) : RuntimeException("HTTP $statusCode when fetching $url")
