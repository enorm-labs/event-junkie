package de.norm.events.image

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What one derivative pass does, and what it refuses to do twice.
 *
 * The arithmetic here is small and the consequences are not: this decides how many objects land in
 * the bucket and how many times a venue's image is re-rendered. Every case below is a way the pass
 * could quietly do the wrong amount of work.
 */
class ImageDerivativeServiceTest {
    private val repository = mockk<CachedImageRepository>()
    private val variants = mockk<CachedImageVariantRepository>(relaxed = true)
    private val client = mockk<ImgproxyClient>()
    private val storage = mockk<ImageStorage>()

    private fun service(
        properties: ImgproxyProperties = ImgproxyProperties(enabled = true, widths = listOf(192), formats = listOf("avif", "webp")),
        storageEnabled: Boolean = true
    ): ImageDerivativeService {
        every { storage.isEnabled() } returns storageEnabled
        return ImageDerivativeService(repository, variants, client, storage, properties, ImageProperties(), ImageCacheMetrics(SimpleMeterRegistry()))
    }

    private val stored = CachedImageEntity(id = 1, sourceUrl = "https://venue.test/a.jpg", contentHash = "abc123")

    @Test
    fun `does nothing at all while imgproxy is disabled`() =
        runTest {
            // The default everywhere. Off must not reach the database, or a disabled feature still
            // costs a query every tick.
            service(ImgproxyProperties(enabled = false)).generateBatch() shouldBe DerivativeOutcome()

            coVerify(exactly = 0) { repository.findNeedingDerivatives(any(), any()) }
        }

    @Test
    fun `does nothing when there is nowhere to store the result`() =
        runTest {
            // Rendering without a bucket burns imgproxy's CPU for bytes that are then dropped.
            service(storageEnabled = false).generateBatch() shouldBe DerivativeOutcome()

            coVerify(exactly = 0) { repository.findNeedingDerivatives(any(), any()) }
        }

    @Test
    fun `asks for as many variants as the width and format sets multiply out to`() =
        runTest {
            // The expected count is what tells `findNeedingDerivatives` an image is finished. Get it
            // wrong and every image looks either permanently incomplete or done before it is.
            coEvery { repository.findNeedingDerivatives(any(), any()) } returns emptyFlow()

            service(ImgproxyProperties(enabled = true, widths = listOf(192, 288, 768), formats = listOf("avif", "webp"))).generateBatch()

            coVerify { repository.findNeedingDerivatives(expectedVariants = 6, limit = any()) }
        }

    @Test
    fun `renders and records every missing variant`() =
        runTest {
            coEvery { repository.findNeedingDerivatives(any(), any()) } returns flowOf(stored)
            every { variants.findByCachedImageId(1) } returns emptyFlow()
            coEvery { client.render(any(), any(), any()) } returns byteArrayOf(9, 9)
            coEvery { storage.storeDerivative(any(), any(), any(), any()) } returns "staging/derived/abc123/192.avif"

            service().generateBatch() shouldBe DerivativeOutcome(images = 1, variants = 2, refused = 0)

            coVerify(exactly = 1) { variants.save(match { it.width == 192 && it.format == "avif" }) }
            coVerify(exactly = 1) { variants.save(match { it.width == 192 && it.format == "webp" }) }
        }

    @Test
    fun `never re-renders a variant that already exists`() =
        runTest {
            // An interrupted run leaves some variants behind. Re-rendering them would be imgproxy
            // CPU and a bucket write for an object that is already there, byte for byte — the keys
            // are content addressed, so the second write cannot even differ.
            coEvery { repository.findNeedingDerivatives(any(), any()) } returns flowOf(stored)
            every { variants.findByCachedImageId(1) } returns
                flowOf(CachedImageVariantEntity(cachedImageId = 1, width = 192, format = "avif", storageKey = "k", byteSize = 1))
            coEvery { client.render(any(), any(), any()) } returns byteArrayOf(9, 9)
            coEvery { storage.storeDerivative(any(), any(), any(), any()) } returns "k2"

            service().generateBatch() shouldBe DerivativeOutcome(images = 1, variants = 1, refused = 0)

            coVerify(exactly = 0) { client.render("abc123", 192, "avif") }
            coVerify(exactly = 1) { client.render("abc123", 192, "webp") }
        }

    @Test
    fun `counts a refusal without writing a row for it`() =
        runTest {
            // imgproxy refusing one width must not produce a variant row, or the database would
            // point at an object that was never stored and the serving path reads the row.
            coEvery { repository.findNeedingDerivatives(any(), any()) } returns flowOf(stored)
            every { variants.findByCachedImageId(1) } returns emptyFlow()
            coEvery { client.render(any(), any(), "avif") } returns null
            coEvery { client.render(any(), any(), "webp") } returns byteArrayOf(9)
            coEvery { storage.storeDerivative(any(), any(), any(), any()) } returns "k"

            service().generateBatch() shouldBe DerivativeOutcome(images = 1, variants = 1, refused = 1)

            coVerify(exactly = 1) { variants.save(any()) }
        }

    @Test
    fun `a store that fails is a refusal, not a recorded variant`() =
        runTest {
            coEvery { repository.findNeedingDerivatives(any(), any()) } returns flowOf(stored)
            every { variants.findByCachedImageId(1) } returns emptyFlow()
            coEvery { client.render(any(), any(), any()) } returns byteArrayOf(9)
            coEvery { storage.storeDerivative(any(), any(), any(), any()) } returns null

            service().generateBatch() shouldBe DerivativeOutcome(images = 1, variants = 0, refused = 2)

            coVerify(exactly = 0) { variants.save(any()) }
        }

    @Test
    fun `skips an image with no content hash rather than rendering nothing`() =
        runTest {
            // A row exists before its fetch succeeds. Asking imgproxy to render a null hash would be
            // a request for an object that does not exist.
            coEvery { repository.findNeedingDerivatives(any(), any()) } returns
                flowOf(CachedImageEntity(id = 2, sourceUrl = "https://venue.test/b.jpg", contentHash = null))

            service().generateBatch() shouldBe DerivativeOutcome()

            coVerify(exactly = 0) { client.render(any(), any(), any()) }
        }
}
