package de.norm.events.image

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import org.slf4j.LoggerFactory
import java.awt.Color

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

    private val withJpeg = ImgproxyProperties(enabled = true, widths = listOf(192, 512), formats = listOf("webp", "jpg"))

    private fun stubBatch() {
        coEvery { repository.findNeedingDerivatives(any(), any()) } returns flowOf(stored)
        every { variants.findByCachedImageId(1) } returns emptyFlow()
        coEvery { repository.save(any()) } answers { firstArg() }
        coEvery { storage.storeDerivative(any(), any(), any(), any()) } returns "k"
    }

    @Test
    fun `rejects a blank derivative, writes nothing and marks the row`() =
        runTest {
            // BOXHOPPING's GIF renders as frame 0, which is black. No variant means the card shows the title poster.
            stubBatch()
            coEvery { client.render(any(), any(), any()) } returns flatJpeg(Color.BLACK)
            val logged = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger(ImageDerivativeService::class.java) as Logger
            logger.addAppender(logged)

            try {
                service(withJpeg).generateBatch() shouldBe DerivativeOutcome(images = 1, blank = 1)
            } finally {
                logger.detachAppender(logged)
            }

            coVerify(exactly = 1) { client.render("abc123", 192, "jpg") }
            coVerify(exactly = 1) { client.render(any(), any(), any()) }
            coVerify(exactly = 0) { storage.storeDerivative(any(), any(), any(), any()) }
            coVerify(exactly = 0) { variants.save(any()) }
            coVerify(exactly = 1) {
                repository.save(match { it.id == 1L && it.failedAt == null && it.failureReason == "blank derivative: luminance spread 0.00" })
            }
            logged.list.single { it.formattedMessage.startsWith("Blank derivative") }.let {
                it.level shouldBe Level.INFO
                it.formattedMessage shouldBe "Blank derivative rejected for image 1: luminance spread 0.00 < 2.0"
            }
        }

    @Test
    fun `keeps a normal derivative and stores the probe instead of rendering it twice`() =
        runTest {
            stubBatch()
            coEvery { client.render(any(), any(), any()) } returns
                testJpeg { g ->
                    g.color = Color.WHITE
                    g.fillRect(0, 0, 192, 108)
                    g.color = Color.RED
                    g.fillOval(40, 20, 100, 70)
                }

            service(withJpeg).generateBatch() shouldBe DerivativeOutcome(images = 1, variants = 4, refused = 0)

            coVerify(exactly = 1) { client.render("abc123", 192, "jpg") }
            coVerify(exactly = 4) { variants.save(any()) }
            coVerify(exactly = 0) { repository.save(any()) }
        }

    @Test
    fun `keeps a dark flyer whose mean is low but whose spread is normal`() =
        runTest {
            stubBatch()
            coEvery { client.render(any(), any(), any()) } returns darkFlyerJpeg()

            service(withJpeg).generateBatch() shouldBe DerivativeOutcome(images = 1, variants = 4, refused = 0)

            coVerify(exactly = 0) { repository.save(any()) }
        }

    @Test
    fun `does not probe an image that already has variants`() =
        runTest {
            // Its variants are served already, so rejecting it now would change nothing a visitor sees.
            coEvery { repository.findNeedingDerivatives(any(), any()) } returns flowOf(stored)
            every { variants.findByCachedImageId(1) } returns
                flowOf(CachedImageVariantEntity(cachedImageId = 1, width = 192, format = "jpg", storageKey = "k", byteSize = 1))
            coEvery { client.render(any(), any(), any()) } returns flatJpeg(Color.BLACK)
            coEvery { storage.storeDerivative(any(), any(), any(), any()) } returns "k2"

            service(withJpeg).generateBatch() shouldBe DerivativeOutcome(images = 1, variants = 3, refused = 0)

            coVerify(exactly = 0) { client.render("abc123", 192, "jpg") }
        }
}
