package de.norm.events.image

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The polled half of the image meters: what the database says, moved into the gauges. */
class ImageMetricsRefreshServiceTest {
    private val repository: CachedImageRepository = mockk(relaxed = true)

    private lateinit var registry: SimpleMeterRegistry
    private lateinit var metrics: ImageCacheMetrics
    private lateinit var service: ImageMetricsRefreshService

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        metrics = ImageCacheMetrics(registry)
        service = ImageMetricsRefreshService(repository, ImgproxyProperties(), metrics)

        coEvery { repository.countUrlStates() } returns ImageUrlCountsRow(0, 0, 0, 0)
        coEvery { repository.countNeedingDerivatives(any()) } returns 0
    }

    @Test
    fun `one refresh publishes both gauges`() =
        runTest {
            coEvery { repository.countUrlStates() } returns ImageUrlCountsRow(cached = 1500, failed = 12, pending = 66, withheld = 3)
            coEvery { repository.countNeedingDerivatives(any()) } returns 411

            service.refreshGauges()

            gauge("images.urls", "cached") shouldBe 1500.0
            gauge("images.urls", "pending") shouldBe 66.0
            registry
                .find("images.derivatives.backlog")
                .gauge()
                .shouldNotBeNull()
                .value() shouldBe 411.0
        }

    /**
     * The backlog is counted against the same product the generating pass uses, so the number cannot
     * describe a different amount of work than the pass is doing.
     */
    @Test
    fun `the backlog is counted against the configured variant count`() =
        runTest {
            service.refreshGauges()

            coVerify { repository.countNeedingDerivatives(ImgproxyProperties.DEFAULT_WIDTHS.size * ImgproxyProperties.DEFAULT_FORMATS.size) }
        }

    /**
     * **A monitoring refresh must not be able to kill the scheduler.** It shares the one that runs
     * the fetch and the sweep, so an uncaught exception here would stop caching images — a far worse
     * failure than a gauge that stops moving, which is itself detectable.
     */
    @Test
    fun `a failing query freezes the gauges rather than propagating`() =
        runTest {
            metrics.updateUrlStates(ImageUrlCountsRow(cached = 7, failed = 0, pending = 0, withheld = 0))
            coEvery { repository.countUrlStates() } throws IllegalStateException("database is unwell")

            service.refreshGauges()

            gauge("images.urls", "cached") shouldBe 7.0
        }

    private fun gauge(
        name: String,
        state: String
    ): Double =
        registry
            .find(name)
            .tag("state", state)
            .gauge()
            .shouldNotBeNull()
            .value()
}
