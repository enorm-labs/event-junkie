package de.norm.events.wikimedia

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientException
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Wikidata or Commons did not answer, or answered an error. A counter and a retry on the next sweep. */
class WikimediaUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * The picture behind a Wikidata item: its `P18` claim, then that file's `imageinfo` on Commons.
 *
 * Two hosts, one pace: `wbgetclaims` on Wikidata names the file, `imageinfo` on Commons renders a
 * thumbnail at [WikimediaProperties.thumbWidth] and states the licence and the author. Both are
 * MediaWiki API reads with no key. A missing item, a missing claim and a missing file are all
 * "no picture", never an error: most acts have no `P18`, and the row is complete without one.
 */
@Component
class WikimediaClient(
    @Qualifier(WIKIMEDIA_WEB_CLIENT) private val webClient: WebClient,
    private val properties: WikimediaProperties
) {
    private val logger = KotlinLogging.logger {}
    private val pace = Mutex()
    private var lastRequest: TimeMark? = null

    /** The largest file the image fetcher reads; a caller refuses a larger original before storing it. */
    val maxBytes: Long get() = properties.maxBytes

    /** The `P18` picture of [wikidataId] (`Q3374548`) as Commons states it, or null when there is none or the read is off. */
    suspend fun imageFor(wikidataId: String): CommonsImage? = if (properties.enabled) fileOf(wikidataId)?.let { file -> imageInfo(file) } else null

    private suspend fun fileOf(wikidataId: String): String? {
        val uri =
            UriComponentsBuilder
                .fromUriString(properties.wikidataBaseUrl)
                .queryParam("action", "wbgetclaims")
                .queryParam("entity", wikidataId)
                .queryParam("property", P18)
                .queryParam("format", "json")
                .build()
                .toUri()
        val body = get(uri, "Wikidata $wikidataId")
        // MediaWiki answers an unknown entity with 200 and an `error` object rather than a status.
        if (body.has("error")) {
            logger.info { "Wikidata answered '${body.path("error").path("code").asString("")}' for $wikidataId" }
            return null
        }
        return body
            .path("claims")
            .path(P18)
            .firstOrNull()
            ?.path("mainsnak")
            ?.path("datavalue")
            ?.path("value")
            ?.asString(null)
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun imageInfo(file: String): CommonsImage? {
        val uri =
            UriComponentsBuilder
                .fromUriString(properties.commonsBaseUrl)
                .queryParam("action", "query")
                .queryParam("titles", "{title}")
                .queryParam("prop", "imageinfo")
                .queryParam("iiprop", "url|extmetadata|size|mime")
                .queryParam("iiurlwidth", properties.thumbWidth)
                .queryParam("format", "json")
                .encode()
                .buildAndExpand("File:$file")
                .toUri()
        val page = get(uri, "Commons File:$file").path("query").path("pages").firstOrNull()
        if (page == null || page.has("missing")) {
            logger.info { "Commons has no file '$file'" }
            return null
        }
        val info = page.path("imageinfo").firstOrNull()
        val thumb = info?.path("thumburl")?.asString("")?.takeIf { it.isNotBlank() }
        return if (info != null && thumb != null) commonsImage(info, withoutQuery(thumb)) else null
    }

    private fun commonsImage(
        info: JsonNode,
        thumb: String
    ): CommonsImage {
        val meta = info.path("extmetadata")
        return CommonsImage(
            thumbUrl = thumb,
            licenceShortName = meta.path("LicenseShortName").path("value").textOrNull(),
            artistHtml = meta.path("Artist").path("value").textOrNull(),
            descriptionUrl = info.path("descriptionurl").textOrNull(),
            size = info.path("size").asLong(0),
            mime = info.path("mime").textOrNull(),
            servedOriginal = thumb == withoutQuery(info.path("url").asString(""))
        )
    }

    private suspend fun get(
        uri: URI,
        subject: String
    ): JsonNode =
        try {
            pace.withLock {
                waitForPace()
                webClient.get().uri(uri).awaitExchange { response ->
                    if (response.statusCode().isError) throw WikimediaUnavailableException("$subject answered ${response.statusCode().value()}")
                    response.awaitBody<JsonNode>()
                }
            }
        } catch (e: IOException) {
            throw WikimediaUnavailableException("$subject did not answer", e)
        } catch (e: WebClientException) {
            throw WikimediaUnavailableException("$subject request failed", e)
        }

    private suspend fun waitForPace() {
        val since = lastRequest?.elapsedNow()
        val gap = properties.politeDelayMillis.milliseconds
        if (since != null && since < gap) delay(gap - since)
        lastRequest = TimeSource.Monotonic.markNow()
    }

    private fun JsonNode.textOrNull(): String? = asString(null)?.takeIf { it.isNotBlank() }

    companion object {
        private const val P18 = "P18"

        /** Commons appends `utm_` parameters to a thumbnail URL; the column holds the image, not the campaign. */
        fun withoutQuery(url: String): String = url.substringBefore('?')
    }
}
