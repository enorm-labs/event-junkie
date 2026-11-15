package de.norm.events.image

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Level as LogbackLevel

/** The polled half of the image meters: what the database says, moved into the gauges. */
class ImageMetricsRefreshServiceTest {
    private val repository: CachedImageRepository = mockk(relaxed = true)

    private lateinit var registry: SimpleMeterRegistry
    private lateinit var metrics: ImageCacheMetrics
    private lateinit var service: ImageMetricsRefreshService
    private lateinit var appender: ListAppender<ILoggingEvent>
    private val serviceLogger = LoggerFactory.getLogger(ImageMetricsRefreshService::class.java) as Logger

    @BeforeEach
    fun setUp() {
        appender = ListAppender<ILoggingEvent>().apply { start() }
        serviceLogger.addAppender(appender)
        registry = SimpleMeterRegistry()
        metrics = ImageCacheMetrics(registry)
        service = ImageMetricsRefreshService(repository, ImgproxyProperties(), metrics)

        coEvery { repository.countUrlStates() } returns ImageUrlCountsRow(0, 0, 0, 0)
        coEvery { repository.countNeedingDerivatives(any()) } returns 0
    }

    @AfterEach
    fun detachAppender() {
        serviceLogger.detachAppender(appender)
        appender.stop()
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
     * **A failing query freezes the gauges.** The last values stay, and a gauge that stops moving is
     * itself detectable.
     */
    @Test
    fun `a failing query freezes the gauges rather than propagating`() =
        runTest {
            metrics.updateUrlStates(ImageUrlCountsRow(cached = 7, failed = 0, pending = 0, withheld = 0))
            coEvery { repository.countUrlStates() } throws IllegalStateException("database is unwell")

            service.refreshGauges()

            gauge("images.urls", "cached") shouldBe 7.0
        }

    @Test
    fun `a stray cancellation from a query is logged here and freezes the gauges`() =
        runTest {
            metrics.updateUrlStates(ImageUrlCountsRow(cached = 7, failed = 0, pending = 0, withheld = 0))
            coEvery { repository.countUrlStates() } throws CancellationException("a cancelled future's")

            service.refreshGauges()

            gauge("images.urls", "cached") shouldBe 7.0
            val event = appender.list.single()
            event.level shouldBe LogbackLevel.WARN
            event.formattedMessage shouldBe "Could not refresh the image cache gauges; they keep their previous values"
        }

    @Test
    fun `a cancelled job stops without logging a failure`() =
        runTest {
            coEvery { repository.countUrlStates() } throws CancellationException("shutdown")
            var reachedAfter = false

            launch {
                cancel()
                service.refreshGauges()
                reachedAfter = true
            }.join()

            reachedAfter shouldBe false
            appender.list.shouldBeEmpty()
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
