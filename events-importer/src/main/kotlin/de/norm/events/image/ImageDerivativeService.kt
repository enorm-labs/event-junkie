package de.norm.events.image

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service

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
    private val metrics: ImageCacheMetrics
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

        var written = 0
        var refused = 0
        properties.widths.forEach { width ->
            properties.formats.forEach { format ->
                if (width to format in existing) return@forEach

                val bytes = client.render(hash, width, format)
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
}

/** What one derivative pass did. */
data class DerivativeOutcome(
    val images: Int = 0,
    val variants: Int = 0,
    val refused: Int = 0
) {
    operator fun plus(other: DerivativeOutcome): DerivativeOutcome =
        DerivativeOutcome(images + other.images, variants + other.variants, refused + other.refused)

    override fun toString(): String = "$images image(s), $variants variant(s) written, $refused refused"
}
