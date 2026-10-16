package de.norm.events.instagram

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientException
import org.springframework.web.reactive.function.client.awaitBodyOrNull
import org.springframework.web.reactive.function.client.awaitExchangeOrNull
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/** One image of a post, held in memory for one model call and never written anywhere (ADR-049). */
class PostImage(
    val bytes: ByteArray,
    val mediaType: MediaType
)

/**
 * Downloads a post's images from Meta's CDN for the model (ADR-049).
 *
 * Only an `https` URL on Instagram's or Facebook's CDN is fetched, and only a JPEG, PNG or WebP of
 * at most [MAX_BYTES], the size Anthropic takes. A failed download is a `WARN`, and the post goes to
 * the model with the images that did arrive.
 */
@Component
class InstagramImageFetcher(
    @Qualifier(INSTAGRAM_IMAGE_WEB_CLIENT) private val webClient: WebClient
) {
    private val logger = KotlinLogging.logger {}

    suspend fun fetch(urls: List<String>): List<PostImage> = urls.mapNotNull { fetch(it) }

    private suspend fun fetch(url: String): PostImage? {
        val uri = cdnUri(url)
        if (uri == null) {
            logger.warn { "Not a Meta CDN image URL, so it is not fetched: host '${hostOf(url)}'" }
            return null
        }
        return try {
            webClient
                .get()
                .uri(uri)
                .awaitExchangeOrNull { response ->
                    val type = response.headers().contentType().orElse(null)
                    val status = response.statusCode().value()
                    val body = response.awaitBodyOrNull<ByteArray>()
                    when {
                        status != HTTP_OK || body == null -> {
                            logger.warn { "An image download from '${uri.host}' answered $status" }
                            null
                        }

                        type == null || IMAGE_TYPES.none { it.equalsTypeAndSubtype(type) } -> {
                            logger.warn { "An image download from '${uri.host}' gave '$type', which the model does not take" }
                            null
                        }

                        else -> {
                            PostImage(body, MediaType(type.type, type.subtype))
                        }
                    }
                }
        } catch (e: DataBufferLimitException) {
            logger.warn(e) { "An image from '${uri.host}' is over $MAX_BYTES bytes, so it is not sent" }
            null
        } catch (e: IOException) {
            logger.warn(e) { "An image download from '${uri.host}' got no answer" }
            null
        } catch (e: WebClientException) {
            logger.warn(e) { "An image download from '${uri.host}' failed" }
            null
        }
    }

    private fun cdnUri(url: String): URI? =
        parse(url)?.takeIf { uri ->
            val host = uri.host.orEmpty().lowercase()
            uri.scheme == "https" && CDN_DOMAINS.any { host.endsWith(".$it") }
        }

    /** The host only: the query of a CDN URL is a signature and stays out of the log. */
    private fun hostOf(url: String): String = parse(url)?.host.orEmpty()

    private fun parse(url: String): URI? =
        try {
            URI(url)
        } catch (_: URISyntaxException) {
            null
        }

    companion object {
        /** Anthropic refuses an image over 5 MB. */
        const val MAX_BYTES = 5 * 1024 * 1024

        private const val HTTP_OK = 200
        private val CDN_DOMAINS = listOf("cdninstagram.com", "fbcdn.net")
        private val IMAGE_TYPES = listOf(MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG, MediaType.parseMediaType("image/webp"))
    }
}
