package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [ScheduledImportService].
 *
 * Covers the scheduling logic (due-date evaluation, capped exponential backoff,
 * staleness detection) in isolation with mocked dependencies.
 */
class ScheduledImportServiceTest {
    private val eventSourceRepository: EventSourceRepository = mockk(relaxed = true)
    private val eventImportService: EventImportService = mockk(relaxed = true)

    private val now: Instant = Instant.parse("2026-05-14T12:00:00Z")

    /** Fixed clock pinned to [now] so tick() uses the same reference time as the test fixtures. */
    private val fixedClock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val service = ScheduledImportService(eventSourceRepository, eventImportService, fixedClock)

    /** Creates a base [EventSourceEntity] with sensible defaults for testing. */
    private fun source(
        id: Long = 1L,
        slug: String = "test-source",
        status: String = ImportStatus.SUCCESS.name,
        importIntervalMinutes: Int = 60,
        retryCount: Int = 0,
        maxRetries: Int = 3,
        lastImportAt: Instant? = null
    ) = EventSourceEntity(
        id = id,
        venueId = 1L,
        name = "Test Source",
        slug = slug,
        url = "https://example.com",
        sourceType = "CASSIOPEIA",
        enabled = true,
        importIntervalMinutes = importIntervalMinutes,
        retryCount = retryCount,
        maxRetries = maxRetries,
        lastImportAt = lastImportAt,
        status = status
    )

    @Nested
    inner class IsDue {
        @Test
        fun `source with no last import is always due`() {
            val result = service.isDue(source(lastImportAt = null), now)
            result shouldBe true
        }

        @Test
        fun `IDLE source with existing lastImportAt is always due`() {
            // After retry(), lastImportAt is preserved but status is IDLE — should still be due
            val thirtyMinAgo = now.minus(Duration.ofMinutes(30))
            val result =
                service.isDue(
                    source(
                        importIntervalMinutes = 1440,
                        lastImportAt = thirtyMinAgo,
                        status = ImportStatus.IDLE.name
                    ),
                    now
                )
            result shouldBe true
        }

        @Test
        fun `source imported longer ago than interval is due`() {
            val twoHoursAgo = now.minus(Duration.ofHours(2))
            val result = service.isDue(source(importIntervalMinutes = 60, lastImportAt = twoHoursAgo), now)
            result shouldBe true
        }

        @Test
        fun `source imported less than interval ago is not due`() {
            val thirtyMinAgo = now.minus(Duration.ofMinutes(30))
            val result = service.isDue(source(importIntervalMinutes = 60, lastImportAt = thirtyMinAgo), now)
            result shouldBe false
        }

        @Test
        fun `source imported exactly at interval boundary is not due`() {
            val exactlyOneHourAgo = now.minus(Duration.ofHours(1))
            val result = service.isDue(source(importIntervalMinutes = 60, lastImportAt = exactlyOneHourAgo), now)
            result shouldBe false
        }

        @Test
        fun `failed source with retryCount 1 uses 2x backoff`() {
            // Base interval = 60min, backoff = 2^1 = 2x → effective = 120min
            val ninetyMinAgo = now.minus(Duration.ofMinutes(90))
            val result =
                service.isDue(
                    source(
                        importIntervalMinutes = 60,
                        lastImportAt = ninetyMinAgo,
                        status = ImportStatus.FAILED.name,
                        retryCount = 1
                    ),
                    now
                )
            // 90min < 120min effective interval → not due yet
            result shouldBe false
        }

        @Test
        fun `failed source with retryCount 1 becomes due after 2x interval`() {
            // Base interval = 60min, backoff = 2^1 = 2x → effective = 120min
            val threeHoursAgo = now.minus(Duration.ofHours(3))
            val result =
                service.isDue(
                    source(
                        importIntervalMinutes = 60,
                        lastImportAt = threeHoursAgo,
                        status = ImportStatus.FAILED.name,
                        retryCount = 1
                    ),
                    now
                )
            // 180min > 120min effective interval → due
            result shouldBe true
        }

        @Test
        fun `failed source with retryCount 3 uses 8x backoff while it stays under the cap`() {
            // Base interval = 30min, backoff = 2^3 = 8x → effective = 240min, still under 6h,
            // so a short-interval source keeps the doubling schedule it always had.
            // maxRetries = 5 so retryCount = 3 is still retrying rather than spent.
            val threeHoursAgo = now.minus(Duration.ofHours(3))
            val result =
                service.isDue(
                    source(
                        importIntervalMinutes = 30,
                        lastImportAt = threeHoursAgo,
                        status = ImportStatus.FAILED.name,
                        retryCount = 3,
                        maxRetries = 5
                    ),
                    now
                )
            // 180min < 240min effective interval → not due yet
            result shouldBe false
        }

        @Test
        fun `retry interval never exceeds six hours, whatever the source interval`() {
            // The #659 regression. Base interval = 1440min (the default), backoff = 2^1 = 2x.
            // Uncapped that is 48h — which is exactly the gap loge sat in between its failure
            // on 2026-08-21 11:54 and its next attempt on 2026-08-23 11:55.
            val sevenHoursAgo = now.minus(Duration.ofHours(7))
            val result =
                service.isDue(
                    source(
                        importIntervalMinutes = EventSourceEntity.DEFAULT_IMPORT_INTERVAL_MINUTES,
                        lastImportAt = sevenHoursAgo,
                        status = ImportStatus.FAILED.name,
                        retryCount = 1
                    ),
                    now
                )
            // 7h > the 6h cap → due, rather than waiting another 41h
            result shouldBe true
        }

        @Test
        fun `a daily source is not retried before the six-hour cap elapses`() {
            val fiveHoursAgo = now.minus(Duration.ofHours(5))
            val result =
                service.isDue(
                    source(
                        importIntervalMinutes = EventSourceEntity.DEFAULT_IMPORT_INTERVAL_MINUTES,
                        lastImportAt = fiveHoursAgo,
                        status = ImportStatus.FAILED.name,
                        retryCount = 1
                    ),
                    now
                )
            result shouldBe false
        }

        @Test
        fun `a spent retry budget returns the source to its own interval rather than off the schedule`() {
            // retryCount = maxRetries: the shortened retry cadence ends, the normal one resumes.
            // Base interval = 60min and the last attempt was 2h ago → due.
            val twoHoursAgo = now.minus(Duration.ofHours(2))
            val result =
                service.isDue(
                    source(
                        importIntervalMinutes = 60,
                        lastImportAt = twoHoursAgo,
                        status = ImportStatus.FAILED.name,
                        retryCount = 3,
                        maxRetries = 3
                    ),
                    now
                )
            result shouldBe true
        }

        @Test
        fun `a source past its retry budget still waits out its own interval`() {
            // The fallback is the base interval, not "always due" — a permanently broken daily
            // source is attempted once a day, not once a tick.
            val twoHoursAgo = now.minus(Duration.ofHours(2))
            val result =
                service.isDue(
                    source(
                        importIntervalMinutes = EventSourceEntity.DEFAULT_IMPORT_INTERVAL_MINUTES,
                        lastImportAt = twoHoursAgo,
                        status = ImportStatus.FAILED.name,
                        retryCount = 10,
                        maxRetries = 3
                    ),
                    now
                )
            result shouldBe false
        }

        @Test
        fun `successful source with retryCount 0 uses base interval without backoff`() {
            val twoHoursAgo = now.minus(Duration.ofHours(2))
            val result =
                service.isDue(
                    source(
                        importIntervalMinutes = 60,
                        lastImportAt = twoHoursAgo,
                        status = ImportStatus.SUCCESS.name,
                        retryCount = 0
                    ),
                    now
                )
            result shouldBe true
        }
    }

    @Nested
    inner class ResetStuckSources {
        @BeforeEach
        fun setUp() {
            // Default: no due sources
            coEvery { eventSourceRepository.findDueForImport(any()) } returns emptyFlow()
        }

        @Test
        fun `tick resets sources stuck in RUNNING beyond staleness timeout`() =
            runTest {
                val stuckSource =
                    source(
                        status = ImportStatus.RUNNING.name,
                        lastImportAt = now.minus(Duration.ofMinutes(45))
                    )
                coEvery { eventSourceRepository.findStuckSources(any()) } returns listOf(stuckSource).asFlow()

                service.tick()

                coVerify {
                    eventSourceRepository.save(
                        match {
                            it.status == ImportStatus.FAILED.name &&
                                it.retryCount == stuckSource.retryCount + 1 &&
                                it.lastError?.contains("timed out") == true
                        }
                    )
                }
            }

        @Test
        fun `tick does not reset sources when none are stuck`() =
            runTest {
                coEvery { eventSourceRepository.findStuckSources(any()) } returns emptyFlow()

                service.tick()

                coVerify(exactly = 0) {
                    eventSourceRepository.save(any())
                }
            }
    }

    @Nested
    inner class ImportDueSources {
        @BeforeEach
        fun setUp() {
            // Default: no stuck sources
            coEvery { eventSourceRepository.findStuckSources(any()) } returns emptyFlow()
        }

        @Test
        fun `tick imports due sources concurrently`() =
            runTest {
                val dueSource = source(lastImportAt = null)
                coEvery { eventSourceRepository.findDueForImport(any()) } returns listOf(dueSource).asFlow()
                coEvery { eventImportService.importConcurrently(any()) } returns
                    listOf(ImportResultResponse(sourceSlug = "test-source", imported = true, eventCount = 5))

                service.tick()

                coVerify(exactly = 1) { eventImportService.importConcurrently(listOf(dueSource)) }
            }

        @Test
        fun `tick skips sources that are not yet due after per-source filtering`() =
            runTest {
                // Source returned by the broad query but not yet due based on its individual interval
                val notYetDue =
                    source(
                        importIntervalMinutes = 1440,
                        lastImportAt = now.minus(Duration.ofHours(1))
                    )
                coEvery { eventSourceRepository.findDueForImport(any()) } returns listOf(notYetDue).asFlow()

                service.tick()

                coVerify(exactly = 0) { eventImportService.importConcurrently(any()) }
            }
    }
}
