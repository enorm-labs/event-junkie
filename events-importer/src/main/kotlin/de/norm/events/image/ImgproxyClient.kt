package de.norm.events.image

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchangeOrNull
import java.net.URI
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Asks the imgproxy sidecar for one derivative.
 *
 * **Its own client, not the scraper's.** The scraper's carries `robots.txt` enforcement and per-host
 * throttling, both of which are about being a polite guest on someone else's server. imgproxy is a
 * process in this pod reading our own bucket, so throttling it would only slow an import down and a
 * `robots.txt` lookup against loopback is meaningless.
 *
 * **imgproxy reads the source from object storage, and never from a venue.** Letting it fetch a
 * venue URL would go around the politeness machinery and the SSRF checks that live in
 * [ImageFetcher]; `IMGPROXY_ALLOWED_SOURCES` is what enforces that on the sidecar's side.
 */
@Component
class ImgproxyClient(
    private val properties: ImgproxyProperties,
    private val storageProperties: ImageStorageProperties,
    webClientBuilder: WebClient.Builder
) {
    private val logger = KotlinLogging.logger {}

    // A plain client: no filters, no User-Agent. The far end is a sidecar on loopback.
    private val webClient = webClientBuilder.build()

    /**
     * Renders [contentHash]'s original at [width] in [format], and returns the bytes.
     *
     * Null when imgproxy refuses it — a source past its resolution or file-size bound, or a format
     * it cannot read. The caller records the variants that did work rather than failing the image.
     */
    suspend fun render(
        contentHash: String,
        width: Int,
        format: String
    ): ByteArray? {
        val path = renderPath(contentHash, width, format)
        val url = "${properties.baseUrl.trimEnd('/')}${sign(path)}"

        return try {
            webClient.get().uri(URI.create(url)).awaitExchangeOrNull { response ->
                if (response.statusCode().is2xxSuccessful) {
                    response.awaitBody<ByteArray>()
                } else {
                    logger.warn { "imgproxy returned ${response.statusCode().value()} for $contentHash at ${width}px $format" }
                    null
                }
            }
        } catch (
            // A sidecar that is starting, out of memory or gone. All of them mean the same thing to
            // the caller: no variant this pass, try again on the next one.
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            logger.warn(e) { "imgproxy call failed for $contentHash at ${width}px $format" }
            null
        }
    }

    /**
     * The path imgproxy processes, without its signature.
     *
     * `rs:fit:<width>:0` fits inside the width and lets the height follow, so nothing is cropped —
     * the plan takes one aspect ratio and resizes only. `s3://` reaches the bucket directly, which
     * is why the sidecar needs credentials and an allow-list rather than network access to a venue.
     */
    internal fun renderPath(
        contentHash: String,
        width: Int,
        format: String
    ): String {
        val source = "s3://${storageProperties.bucket}/${storageProperties.prefix}/originals/$contentHash"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(source.toByteArray())
        return "/rs:fit:$width:0/$encoded.$format"
    }

    /**
     * Prefixes [path] with its signature, or with `/insecure` when no key is configured.
     *
     * A URL-safe Base64 HMAC-SHA256 over the salt followed by the path, with the leading slash
     * included — imgproxy's documented scheme. **The key and salt are hex**, which is easy to miss:
     * signing with the ASCII bytes of the hex string produces a signature imgproxy rejects, and the
     * error is a 403 that reads like a misconfigured key rather than a wrong encoding.
     */
    internal fun sign(path: String): String {
        if (!properties.isSigned()) return "/insecure$path"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.key.hexToByteArray(), "HmacSHA256"))
        mac.update(properties.salt.hexToByteArray())
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(path.toByteArray()))
        return "/$signature$path"
    }

    private fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()

    private companion object {
        const val HEX_RADIX = 16
    }
}
