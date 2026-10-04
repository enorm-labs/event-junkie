package de.norm.events.image

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.Locale

/**
 * Turns each stored original into the files the site serves.
 *
 * **Runs at import time, never per request.** imgproxy's documented shape is a resizing proxy in
 * front of a CDN; ADR-012 rejected a CDN and §5 of both privacy notices says there is none, so
 * on-demand resizing would spend CPU per visitor and cache nothing. Generating once makes every
 * object immutable and content addressed, which is what earns the one-year cache header.
 *
 * **It also fills in what the JVM could not measure.** A stock JDK has no WebP or AVIF reader, so
 * [ImageFetcher] leaves `intrinsic_width` null for those — 16% of staging's corpus. imgproxy decodes
 * them, so the dimensions arrive here.
 */
@Service
@Suppress("LongParameterList") // Constructor injection: one parameter per collaborator; splitting the service hides the wiring.
class ImageDerivativeService(
    private val repository: CachedImageRepository,
    private val variantRepository: CachedImageVariantRepository,
    private val client: ImgproxyClient,
    private val storage: ImageStorage,
    private val properties: ImgproxyProperties,
    private val imageProperties: ImageProperties,
    private val metrics: ImageCacheMetrics,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Generates the missing derivatives for one batch of originals.
     *
     * Nothing here fails an image outright. A width imgproxy refuses simply produces no row, and the
     * next pass asks again — which is what makes an interrupted run cost a retry rather than a gap.
     */
    @Suppress("ReturnCount")
    suspend fun generateBatch(): DerivativeOutcome {
        if (!properties.enabled || !storage.isEnabled()) return DerivativeOutcome()

        val pending = repository.findNeedingDerivatives(properties.expectedVariants, imageProperties.batchSize).toList()
        if (pending.isEmpty()) return DerivativeOutcome()

        var outcome = DerivativeOutcome()
        pending.forEach { outcome = outcome + generateFor(it) }

        metrics.recordDerivativePass(outcome)
        logger.info { "Derivative pass: $outcome" }
        return outcome
    }

    // Guard clauses: an image with no hash or no id is not an error, it is simply not ready.
    @Suppress("ReturnCount")
    private suspend fun generateFor(image: CachedImageEntity): DerivativeOutcome {
        val hash = image.contentHash ?: return DerivativeOutcome()
        val imageId = image.id ?: return DerivativeOutcome()
        val existing =
            variantRepository
                .findByCachedImageId(imageId)
                .toList()
                .map { it.width to it.format }
                .toSet()

        val probe = if (existing.isEmpty()) probe(hash) else null
        if (probe != null && probe.isBlank) return reject(image, imageId, probe.spread)

        var written = 0
        var refused = 0
        properties.widths.forEach { width ->
            properties.formats.forEach { format ->
                if (width to format in existing) return@forEach

                val bytes = probe?.takeIf { it.width == width && it.format == format }?.bytes ?: client.render(hash, width, format)
                if (bytes == null) {
                    refused++
                    return@forEach
                }
                val key = storage.storeDerivative(hash, width, format, bytes)
                if (key == null) {
                    refused++
                    return@forEach
                }
                variantRepository.save(
                    CachedImageVariantEntity(
                        cachedImageId = imageId,
                        width = width,
                        format = format,
                        storageKey = key,
                        byteSize = bytes.size.toLong()
                    )
                )
                written++
            }
        }

        return DerivativeOutcome(images = 1, variants = written, refused = refused)
    }

    /**
     * Renders the smallest JPEG before anything else, and measures it.
     *
     * JPEG because it is the one format the JVM decodes, and the one the site cannot serve without.
     * Null when JPEG is not configured, imgproxy refuses it, or the bytes do not decode: each of
     * those keeps the image.
     */
    @Suppress("ReturnCount") // One guard clause per reason to keep the image.
    private suspend fun probe(hash: String): Probe? {
        if (JPEG !in properties.formats) return null
        val width = properties.widths.minOrNull() ?: return null
        val bytes = client.render(hash, width, JPEG) ?: return null
        val spread = DerivativeUniformity.luminanceSpread(bytes) ?: return null
        return Probe(width, JPEG, bytes, spread)
    }

    /**
     * Writes no variant, and marks the row so the next pass does not render it again.
     *
     * `failure_reason` without `failed_at` is the mark. A fetch that brings new bytes clears both,
     * so a venue that replaces the flyer gets a new measurement.
     */
    private suspend fun reject(
        image: CachedImageEntity,
        imageId: Long,
        spread: Double
    ): DerivativeOutcome {
        val measured = "%.2f".format(Locale.ROOT, spread)
        logger.info { "Blank derivative rejected for image $imageId: luminance spread $measured < ${DerivativeUniformity.BLANK_SPREAD}" }
        repository.save(image.copy(failureReason = "$BLANK_REASON_PREFIX $measured", updatedAt = clock.instant()))
        return DerivativeOutcome(images = 1, blank = 1)
    }

    private class Probe(
        val width: Int,
        val format: String,
        val bytes: ByteArray,
        val spread: Double
    ) {
        val isBlank: Boolean get() = spread < DerivativeUniformity.BLANK_SPREAD
    }

    private companion object {
        const val JPEG = "jpg"
        const val BLANK_REASON_PREFIX = "blank derivative: luminance spread"
    }
}

/** What one derivative pass did. */
data class DerivativeOutcome(
    val images: Int = 0,
    val variants: Int = 0,
    val refused: Int = 0,
    /** Images whose JPEG probe was blank, so no variant was written for them. */
    val blank: Int = 0
) {
    operator fun plus(other: DerivativeOutcome): DerivativeOutcome =
        DerivativeOutcome(images + other.images, variants + other.variants, refused + other.refused, blank + other.blank)

    override fun toString(): String = "$images image(s), $variants variant(s) written, $refused refused, $blank blank"
}
