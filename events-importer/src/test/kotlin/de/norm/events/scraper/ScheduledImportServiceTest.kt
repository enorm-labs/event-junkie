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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
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

    /** A whole-day window, so the interval and backoff tests below read no hour of day. */
    private val service =
        ScheduledImportService(
            eventSourceRepository,
            eventImportService,
            fixedClock,
            Duration.ofMinutes(30),
            ImportWindowProperties(start = "00:00", end = "00:00")
        )

    /** The shipped default, 02:00–06:00 Europe/Berlin. */
    private val windowed = ScheduledImportService(eventSourceRepository, eventImportService, fixedClock)

    private fun berlin(local: String): Instant = LocalDateTime.parse(local).atZone(BERLIN).toInstant()

    /** Creates a base [EventSourceEntity] with sensible defaults for testing. */
    private fun source(
        id: Long = 1L,
        slug: String = "test-source",
        status: String = ImportStatus.SUCCESS.name,
        importIntervalMinutes: Int = 60,
        retryCount: Int = 0,
        maxRetries: Int = 3,
        lastImportAt: Instant? = null,
        importWindowStart: LocalTime? = null,
        importWindowEnd: LocalTime? = null
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
        importWindowStart = importWindowStart,
        importWindowEnd = importWindowEnd,
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
    inner class InsideTheImportWindow {
        private fun daily(lastImportAt: Instant?) = source(importIntervalMinutes = 1440, lastImportAt = lastImportAt)

        @Test
        fun `a daily source waits for the window to open, which includes its first minute`() {
            val source = daily(berlin("2026-10-02T02:10"))

            windowed.isDue(source, berlin("2026-10-03T01:59")) shouldBe false
            windowed.isDue(source, berlin("2026-10-03T02:00")) shouldBe true
        }

        @Test
        fun `the window end is exclusive`() {
            val source = daily(berlin("2026-10-01T02:10"))

            windowed.isDue(source, berlin("2026-10-03T05:59")) shouldBe true
            windowed.isDue(source, berlin("2026-10-03T06:00")) shouldBe false
            windowed.isDue(source, berlin("2026-10-03T19:00")) shouldBe false
        }

        @Test
        fun `a daily source imported late in last night's window starts at this opening, so it cannot drift out`() {
            windowed.isDue(daily(berlin("2026-10-02T05:50")), berlin("2026-10-03T02:00")) shouldBe true
        }

        @Test
        fun `a daily source runs once per window`() {
            windowed.isDue(daily(berlin("2026-10-03T02:01")), berlin("2026-10-03T05:00")) shouldBe false
        }

        @Test
        fun `a forced import in the afternoon does not cost the next night's run`() {
            val source = daily(berlin("2026-10-02T15:00"))

            windowed.isDue(source, berlin("2026-10-02T22:00")) shouldBe false
            windowed.isDue(source, berlin("2026-10-03T02:00")) shouldBe true
        }

        @Test
        fun `a two-day interval skips every second window`() {
            val source = source(importIntervalMinutes = 2880, lastImportAt = berlin("2026-10-01T02:05"))

            windowed.isDue(source, berlin("2026-10-02T03:00")) shouldBe false
            windowed.isDue(source, berlin("2026-10-03T02:00")) shouldBe true
        }

        @Test
        fun `an interval under a day is a plain floor inside the window`() {
            val hourly = source(importIntervalMinutes = 60, lastImportAt = berlin("2026-10-03T02:30"))

            windowed.isDue(hourly, berlin("2026-10-03T03:00")) shouldBe false
            windowed.isDue(hourly, berlin("2026-10-03T03:31")) shouldBe true
            windowed.isDue(hourly.copy(lastImportAt = berlin("2026-10-03T05:00")), berlin("2026-10-03T07:00")) shouldBe false
        }

        @Test
        fun `a new source and a manual retry are due at any hour`() {
            windowed.isDue(daily(null), berlin("2026-10-03T14:00")) shouldBe true
            windowed.isDue(
                source(importIntervalMinutes = 1440, lastImportAt = berlin("2026-10-03T13:00"), status = ImportStatus.IDLE.name),
                berlin("2026-10-03T14:00")
            ) shouldBe true
        }

        @Test
        fun `a retry keeps its backoff outside the window`() {
            val failed = source(importIntervalMinutes = 1440, status = ImportStatus.FAILED.name, retryCount = 1)

            windowed.isDue(failed.copy(lastImportAt = berlin("2026-10-03T02:10")), berlin("2026-10-03T08:11")) shouldBe true
            windowed.isDue(failed.copy(lastImportAt = berlin("2026-10-03T02:10")), berlin("2026-10-03T08:09")) shouldBe false
        }

        @Test
        fun `a source past its retry budget waits for the window again`() {
            val spent =
                source(
                    importIntervalMinutes = 1440,
                    status = ImportStatus.FAILED.name,
                    retryCount = 3,
                    maxRetries = 3,
                    lastImportAt = berlin("2026-10-02T20:10")
                )

            windowed.isDue(spent, berlin("2026-10-03T01:00")) shouldBe false
            windowed.isDue(spent, berlin("2026-10-03T02:00")) shouldBe true
        }

        @Test
        fun `on the spring DST change the window opens at 03 00, because 02 00 does not exist`() {
            val source = daily(Instant.parse("2026-03-28T01:05:00Z")) // 02:05 CET

            windowed.isDue(source, Instant.parse("2026-03-29T00:59:00Z")) shouldBe false // 01:59 CET
            windowed.isDue(source, Instant.parse("2026-03-29T01:00:00Z")) shouldBe true // 03:00 CEST
        }

        @Test
        fun `on the autumn DST change the repeated hour does not import twice`() {
            val importedAtFirstTwoOClock = daily(Instant.parse("2026-10-25T00:00:00Z")) // 02:00 CEST

            windowed.isDue(daily(Instant.parse("2026-10-24T00:05:00Z")), Instant.parse("2026-10-25T00:00:00Z")) shouldBe true
            windowed.isDue(importedAtFirstTwoOClock, Instant.parse("2026-10-25T01:30:00Z")) shouldBe false // 02:30 CET
        }

        @Test
        fun `a source's own window replaces the global one and may wrap past midnight`() {
            val evening =
                daily(berlin("2026-10-01T23:00")).copy(
                    importWindowStart = LocalTime.of(22, 0),
                    importWindowEnd = LocalTime.of(1, 0)
                )

            windowed.isDue(evening, berlin("2026-10-02T23:30")) shouldBe true
            windowed.isDue(evening, berlin("2026-10-03T00:30")) shouldBe true
            windowed.isDue(evening, berlin("2026-10-03T02:30")) shouldBe false
        }

        @Test
        fun `a source's whole-day window falls back to the plain interval`() {
            val allDay =
                daily(berlin("2026-10-02T13:00")).copy(
                    importWindowStart = LocalTime.MIDNIGHT,
                    importWindowEnd = LocalTime.MIDNIGHT
                )

            windowed.isDue(allDay, berlin("2026-10-03T12:59")) shouldBe false
            windowed.isDue(allDay, berlin("2026-10-03T13:01")) shouldBe true
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

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
