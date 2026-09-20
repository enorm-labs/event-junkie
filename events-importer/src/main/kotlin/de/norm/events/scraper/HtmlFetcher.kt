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
 * Reactive HTML fetcher with conditional-request support and Jsoup parsing, over the shared
 * politeness-throttled scraper [WebClient] ([SCRAPER_WEB_CLIENT]). Jsoup's `parse()` is
 * CPU-bound, so it runs on an injected IO dispatcher.
 *
 * Response bodies are read as bytes, not a decoded `String`: a retro host (Arcanoa) answers
 * `Content-Type: text/html` with no `charset` while the page is Latin-1, and Spring's
 * `StringDecoder` falls back to UTF-8, turning every umlaut into a replacement character. Raw
 * bytes let Jsoup apply its detection chain (BOM, HTTP `charset`, `<meta charset>`, UTF-8).
 *
 * JSON/API sources use [ApiClient], which shares the same [WebClient] bean and so the same
 * per-host throttle ([PerHostThrottlingFilter], [ScraperHttpClientConfig]) and User-Agent.
 */
@Component
class HtmlFetcher(
    @Qualifier(SCRAPER_WEB_CLIENT) private val webClient: WebClient,
    @Qualifier("ioDispatcher") private val ioDispatcher: CoroutineDispatcher
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Fetches and parses HTML from [url] with optional conditional-request headers. With both
     * [etag] and [lastModified] `null` the GET is unconditional; a 304 returns
     * [FetchResult.NotModified].
     *
     * @param etag the ETag from a previous fetch (`If-None-Match`).
     * @param lastModified the Last-Modified from a previous fetch (`If-Modified-Since`).
     * @param cookies sent verbatim, for a source that serves its programme only to a request that
     * carries one: without `rosa_age_ok` ROSA renders its age gate and no events. A cookie belongs
     * to one venue, so the value stays in that importer.
     * @return a [FetchResult].
     */
    suspend fun fetch(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
        cookies: Map<String, String> = emptyMap()
    ): FetchResult {
        // `url` as a payload field (#945): the value the "did this source 304 or change" question
        // filters on. The validators stay in the text, opaque hashes nothing aggregates on.
        logger.at(Level.INFO) {
            message = "Fetching source page (etag=$etag, lastModified=$lastModified)"
            payload = mapOf(LogFields.URL to url)
        }
        return webClient
            .get()
            // A pre-built URI so WebClient uses the percent-encoded URL verbatim; a String is a URI template
            // and re-encodes '%', double-encoding non-ASCII slugs into a 404.
            .uri(URI.create(url))
            .apply {
                etag?.let { header("If-None-Match", it) }
                lastModified?.let { header("If-Modified-Since", it) }
                cookies.forEach { (name, value) -> cookie(name, value) }
            }.awaitExchange { response ->
                handleResponse(response, url)
            }
    }

    /**
     * Fetches and parses HTML from [url] without conditional headers, for secondary pages; parsing
     * on the IO dispatcher.
     *
     * @return a parsed Jsoup [Document].
     */
    suspend fun fetchDocument(url: String): Document {
        val body = fetchRawBody(url)
        return parseHtml(body, url)
    }

    /**
     * Fetches raw HTML from [url] without conditional headers. Prefer [fetchDocument], which also
     * lets Jsoup detect the encoding from `<meta>`. Fails fast with [HttpFetchException] on any
     * 4xx/5xx so error pages are never parsed as event data.
     *
     * @return the body decoded with the charset the server declared, UTF-8 when it declared none.
     */
    suspend fun fetchHtml(url: String): String {
        val body = fetchRawBody(url)
        return String(body.bytes, body.charset())
    }

    /**
     * Fetches [url] as raw bytes, failing fast with [HttpFetchException] on any 4xx/5xx; undecoded
     * so the caller can apply the page's own encoding (class KDoc).
     */
    private suspend fun fetchRawBody(url: String): RawBody {
        logger.debug { "Fetching HTML body: $url" }
        return webClient
            .get()
            // A pre-built URI, so '%' is not re-encoded into a 404.
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
     * [FetchResult.NotModified] on 304, else the body parsed into a [FetchResult.Success].
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
     * Parses a raw HTML body on the IO dispatcher. The server-declared charset is passed through when
     * there is one; otherwise `null` hands Jsoup the detection job.
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
     * An undecoded response body plus the charset the server declared, if any. Not a `data class`:
     * a [ByteArray] payload would make generated `equals`/`hashCode` misleading.
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
 * Thrown when an HTTP fetch returns 4xx/5xx; recorded as a failure on the source by
 * [EventImportService.importFromSource].
 */
class HttpFetchException(
    /**
     * A property rather than only formatted into the message (#415): `importer.scrape.failures{reason}`
     * has to tell a 403 from a 500, and re-extracting a number from prose breaks the next time
     * someone improves the wording.
     */
    val statusCode: Int,
    url: String
) : RuntimeException("HTTP $statusCode when fetching $url")
