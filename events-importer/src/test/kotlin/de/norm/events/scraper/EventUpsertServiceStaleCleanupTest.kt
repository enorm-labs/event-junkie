package de.norm.events.scraper

import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for the date-bounded pipeline logic in [EventUpsertService]: the
 * ingestion-side past-event filter (`dropPastEvents`) and the cleanup-side stale
 * removal (`removeStaleEvents`).
 *
 * Uses a fixed clock pinned to 2026-06-15 so all "today"/"tomorrow" calculations
 * are deterministic. Tests exercise both through the public
 * [EventUpsertService.upsertAndCleanup] method.
 */
class EventUpsertServiceStaleCleanupTest {
    private val eventRepository: EventRepository = mockk(relaxed = true)
    private val associationSyncService: AssociationSyncService = mockk(relaxed = true)

    /** Fixed "today" for all tests — 2026-06-15. */
    private val today = LocalDate.of(2026, 6, 15)
    private val tomorrow = today.plusDays(1)
    private val yesterday = today.minusDays(1)
    private val fixedClock: Clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private lateinit var service: EventUpsertService

    private val venueId = 10L
    private val eventSourceId = 1L

    /** Venue slug used for event slug generation — value doesn't matter for stale cleanup tests. */
    private val venueSlug = "test-venue"

    /** Creates a minimal [ScrapedEvent] for testing. */
    private fun scrapedEvent(
        title: String = "Test Event",
        eventDate: LocalDate,
        sourceId: String,
        startTime: LocalTime? = null
    ) = ScrapedEvent(
        title = title,
        eventDate = eventDate,
        startTime = startTime,
        sourceId = sourceId,
        sourceUrl = "https://example.com/event/test",
        eventType = "CONCERT",
        status = "SCHEDULED"
    )

    /** Creates a minimal [EventEntity] representing a persisted event. */
    private fun existingEvent(
        id: Long,
        eventDate: LocalDate,
        sourceId: String,
        title: String = "Existing Event"
    ) = EventEntity(
        id = id,
        venueId = venueId,
        title = title,
        slug = "slug-$id",
        eventDate = eventDate,
        sourceId = sourceId,
        eventSourceId = eventSourceId
    )

    @BeforeEach
    fun setUp() {
        service =
            EventUpsertService(
                eventRepository = eventRepository,
                associationSyncService = associationSyncService,
                clock = fixedClock
            )

        // Default stubs for the upsert pipeline (we're testing stale cleanup, not upsert)
        coEvery { eventRepository.findBySourceIdIn(any()) } returns emptyFlow()
        coEvery { eventRepository.deleteByIdIn(any()) } returns Unit
        coEvery { eventRepository.saveAll(any<Iterable<EventEntity>>()) } answers {
            firstArg<Iterable<EventEntity>>()
                .mapIndexed { index, entity -> entity.copy(id = entity.id ?: (100L + index)) }
                .asFlow()
        }
    }

    @Nested
    inner class TomorrowLowerBound {
        @Test
        fun `does not delete today's event even if missing from scraped results`() =
            runTest {
                // Scenario: A today-event exists in DB but the venue website no longer
                // lists it (common when venues show only "upcoming" from tomorrow).
                // The scraper returns only a tomorrow event.
                val scrapedEvents =
                    listOf(
                        scrapedEvent(title = "Tomorrow Gig", eventDate = tomorrow, sourceId = "src:tomorrow-gig")
                    )

                val tomorrowEvent = existingEvent(id = 2L, eventDate = tomorrow, sourceId = "src:tomorrow-gig")

                // The repository query uses tomorrow..maxScrapedDate, so today's event
                // should never even be returned by the query.
                val fromDateSlot = slot<LocalDate>()
                coEvery {
                    eventRepository.findByEventSourceIdAndEventDateBetween(
                        eventSourceId = eventSourceId,
                        fromDate = capture(fromDateSlot),
                        toDate = any()
                    )
                } returns listOf(tomorrowEvent).asFlow()

                service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                fromDateSlot.captured shouldBe tomorrow

                coVerify(exactly = 0) {
                    eventRepository.deleteByIdIn(match { 1L in it })
                }
            }

        @Test
        fun `deletes stale tomorrow event that is no longer listed`() =
            runTest {
                // Scenario: Tomorrow's event was previously imported but is now gone
                // from the website (genuinely cancelled). Another event exists for the
                // day after tomorrow.
                val dayAfterTomorrow = tomorrow.plusDays(1)

                val scrapedEvents =
                    listOf(
                        scrapedEvent(
                            title = "Day After Tomorrow Gig",
                            eventDate = dayAfterTomorrow,
                            sourceId = "src:day-after"
                        )
                    )

                val staleEvent = existingEvent(id = 1L, eventDate = tomorrow, sourceId = "src:cancelled-gig")
                val activeEvent = existingEvent(id = 2L, eventDate = dayAfterTomorrow, sourceId = "src:day-after")

                coEvery {
                    eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any())
                } returns listOf(staleEvent, activeEvent).asFlow()

                service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                coVerify {
                    eventRepository.deleteByIdIn(match { 1L in it && 2L !in it })
                }
            }

        @Test
        fun `does not delete today's cancelled event - it expires naturally`() =
            runTest {
                // Scenario: Today's event was genuinely cancelled (removed from website).
                // The scraper returns events starting from 3 days out.
                // The event stays in DB until it becomes a past event — acceptable trade-off.
                val threeDaysOut = today.plusDays(3)
                val fourDaysOut = today.plusDays(4)

                val scrapedEvents =
                    listOf(
                        scrapedEvent(title = "Future Gig 1", eventDate = threeDaysOut, sourceId = "src:future-1"),
                        scrapedEvent(title = "Future Gig 2", eventDate = fourDaysOut, sourceId = "src:future-2")
                    )

                // Query starts from tomorrow, so today's event won't be in the result set
                coEvery {
                    eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any())
                } returns emptyFlow()

                service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                // No deletions — today's cancelled event is outside the cleanup window
                coVerify(exactly = 0) {
                    eventRepository.deleteByIdIn(any())
                }
            }
    }

    @Nested
    inner class DateRangeBounds {
        @Test
        fun `cleanup window is tomorrow to max scraped date`() =
            runTest {
                val latestDate = today.plusDays(30)

                val scrapedEvents =
                    listOf(
                        scrapedEvent(title = "Near Event", eventDate = tomorrow, sourceId = "src:near"),
                        scrapedEvent(title = "Far Event", eventDate = latestDate, sourceId = "src:far")
                    )

                val fromDateSlot = slot<LocalDate>()
                val toDateSlot = slot<LocalDate>()
                coEvery {
                    eventRepository.findByEventSourceIdAndEventDateBetween(
                        eventSourceId = eventSourceId,
                        fromDate = capture(fromDateSlot),
                        toDate = capture(toDateSlot)
                    )
                } returns emptyFlow()

                service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                fromDateSlot.captured shouldBe tomorrow
                toDateSlot.captured shouldBe latestDate
            }

        @Test
        fun `does not delete events beyond the max scraped date`() =
            runTest {
                // Scenario: Events exist in the DB beyond the scraper's date range
                // (e.g. from a previous deeper scrape). They should not be touched.
                val scrapedDate = tomorrow

                val scrapedEvents =
                    listOf(
                        scrapedEvent(title = "Tomorrow Gig", eventDate = scrapedDate, sourceId = "src:tomorrow")
                    )

                val tomorrowEvent = existingEvent(id = 1L, eventDate = scrapedDate, sourceId = "src:tomorrow")
                // This event is beyond maxScrapedDate — it won't be in the query results
                // because the repository query is bounded by toDate=scrapedDate

                val toDateSlot = slot<LocalDate>()
                coEvery {
                    eventRepository.findByEventSourceIdAndEventDateBetween(
                        eventSourceId = any(),
                        fromDate = any(),
                        toDate = capture(toDateSlot)
                    )
                } returns listOf(tomorrowEvent).asFlow()

                service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                toDateSlot.captured shouldBe scrapedDate

                // No deletions — the only event in range is still in the scraped list
                coVerify(exactly = 0) {
                    eventRepository.deleteByIdIn(any())
                }
            }

        @Test
        fun `all scraped events on same date uses that date as both min and max`() =
            runTest {
                // Scenario: All scraped events are for the same day (e.g. a single-day festival).
                // The cleanup window should be tomorrow..thatDate. If all events are for tomorrow,
                // both bounds collapse to the same date.
                val scrapedEvents =
                    listOf(
                        scrapedEvent(title = "Festival Act 1", eventDate = tomorrow, sourceId = "src:act-1"),
                        scrapedEvent(title = "Festival Act 2", eventDate = tomorrow, sourceId = "src:act-2")
                    )

                val staleEvent = existingEvent(id = 3L, eventDate = tomorrow, sourceId = "src:cancelled-act")
                val activeEvents =
                    listOf(
                        existingEvent(id = 1L, eventDate = tomorrow, sourceId = "src:act-1"),
                        existingEvent(id = 2L, eventDate = tomorrow, sourceId = "src:act-2")
                    )

                coEvery {
                    eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any())
                } returns (activeEvents + staleEvent).asFlow()

                service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                coVerify {
                    eventRepository.deleteByIdIn(match { it == listOf(3L) })
                }
            }
    }

    @Nested
    inner class PastEventFilter {
        @Test
        fun `drops scraped events before today and keeps today onward`() =
            runTest {
                // Calendar-style sources re-list past shows; the pipeline must drop them
                // before upsert so they are never resurrected. Today is kept (may still run).
                val scrapedEvents =
                    listOf(
                        scrapedEvent(title = "Old Show", eventDate = today.minusDays(1), sourceId = "src:past"),
                        scrapedEvent(title = "Today Show", eventDate = today, sourceId = "src:today"),
                        scrapedEvent(title = "Future Show", eventDate = tomorrow, sourceId = "src:future")
                    )

                val sourceIdSlot = slot<List<String>>()
                coEvery { eventRepository.findBySourceIdIn(capture(sourceIdSlot)) } returns emptyFlow()

                val upserted = service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                // The past event is dropped before upsert; today + future survive.
                upserted.total shouldBe 2
                // Nothing existed beforehand (findBySourceIdIn is stubbed empty), so both are inserts
                // rather than updates — the distinction `importer.events.written{operation}` reports.
                upserted.inserted shouldBe 2
                upserted.updated shouldBe 0
                upserted.skipped shouldBe 0
                sourceIdSlot.captured shouldBe listOf("src:today", "src:future")
            }

        @Test
        fun `upserts nothing and deletes nothing when every scraped event is past`() =
            runTest {
                val scrapedEvents =
                    listOf(
                        scrapedEvent(title = "Old One", eventDate = today.minusDays(2), sourceId = "src:old-1"),
                        scrapedEvent(title = "Old Two", eventDate = today.minusDays(1), sourceId = "src:old-2")
                    )

                val upserted = service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                upserted.total shouldBe 0
                upserted.inserted shouldBe 0
                // With no upcoming events, stale cleanup has nothing to query or delete.
                coVerify(exactly = 0) { eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any()) }
                coVerify(exactly = 0) { eventRepository.deleteByIdIn(any()) }
            }
    }

    @Nested
    inner class AllScrapedEventsToday {
        @Test
        fun `skips cleanup when all scraped events are for today`() =
            runTest {
                // Scenario: The scraper returns only today's events. The cleanup window
                // would be tomorrow..today which is an empty/invalid range, but the
                // maxScrapedDate (today) < tomorrow, so no existing events should be found.
                val scrapedEvents =
                    listOf(
                        scrapedEvent(title = "Today Show", eventDate = today, sourceId = "src:today-show")
                    )

                val fromDateSlot = slot<LocalDate>()
                val toDateSlot = slot<LocalDate>()
                coEvery {
                    eventRepository.findByEventSourceIdAndEventDateBetween(
                        eventSourceId = eventSourceId,
                        fromDate = capture(fromDateSlot),
                        toDate = capture(toDateSlot)
                    )
                } returns emptyFlow()

                service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                // The window is tomorrow..today — the repository should handle this
                // as an empty range and return no results
                fromDateSlot.captured shouldBe tomorrow
                toDateSlot.captured shouldBe today

                coVerify(exactly = 0) {
                    eventRepository.deleteByIdIn(any())
                }
            }
    }

    /**
     * Two sittings of one production on one day, and the same night published twice, arrive
     * looking identical: same venue, same date, same title. The start time is what separates
     * them, and both `event.slug` and `event.source_id` are `UNIQUE`, so getting it wrong either
     * loses a real event or fails the whole import.
     */
    @Nested
    inner class SameDayDuplicatesAndSittings {
        @Test
        fun `keeps both sittings of a production and gives each its own slug`() =
            runTest {
                // Theater im Delphi's Schwanensee: 15:00 matinee and 20:00 evening show.
                val scrapedEvents =
                    listOf(
                        scrapedEvent("Schwanensee", tomorrow, "delphi:488/matinee", LocalTime.of(15, 0)),
                        scrapedEvent("Schwanensee", tomorrow, "delphi:488/evening", LocalTime.of(20, 0))
                    )
                val saved = slot<Iterable<EventEntity>>()
                coEvery { eventRepository.saveAll(capture(saved)) } answers { emptyFlow() }

                service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                // Both survive, and *both* carry the time — not just the later one, so neither
                // reads as the "real" event and page order cannot swap two public URLs.
                saved.captured.map { it.slug } shouldBe
                    listOf(
                        "$tomorrow-test-venue-schwanensee-1500",
                        "$tomorrow-test-venue-schwanensee-2000"
                    )
            }

        @Test
        fun `drops a night the venue published twice at the same time`() =
            runTest {
                // SO36's two-day festival: a combi ticket and a day-one ticket, same 19:30 start.
                // Only one event is happening, and the first by page order wins.
                val scrapedEvents =
                    listOf(
                        scrapedEvent("Female-Fronted", tomorrow, "so36:93090", LocalTime.of(19, 30)),
                        scrapedEvent("Female-Fronted", tomorrow, "so36:90006", LocalTime.of(19, 30))
                    )
                val saved = slot<Iterable<EventEntity>>()
                coEvery { eventRepository.saveAll(capture(saved)) } answers { emptyFlow() }

                service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                saved.captured.map { it.sourceId } shouldBe listOf("so36:93090")
                // No discriminator: an event with no same-slug sibling keeps its plain slug.
                saved.captured.map { it.slug } shouldBe listOf("$tomorrow-test-venue-female-fronted")
            }

        @Test
        fun `collapses same-day duplicates that carry no time at all`() =
            runTest {
                // Without times there is nothing to tell two sittings apart, and a venue that
                // publishes none has far more likely listed one night twice.
                val scrapedEvents =
                    listOf(
                        scrapedEvent("Untimed Show", tomorrow, "src:a"),
                        scrapedEvent("Untimed Show", tomorrow, "src:b")
                    )
                val saved = slot<Iterable<EventEntity>>()
                coEvery { eventRepository.saveAll(capture(saved)) } answers { emptyFlow() }

                service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                saved.captured.map { it.sourceId } shouldBe listOf("src:a")
            }

        @Test
        fun `collapses two sittings that share one sourceId, whatever their times`() =
            runTest {
                // Admiralspalast keys on the show and date, not the session, so its matinee and
                // evening arrive under one id. `event.source_id` is UNIQUE, so they cannot both be
                // stored: without this guard both entities carry the same database id and saveAll
                // issues two UPDATEs to one row, whose slug then flips on every import.
                val scrapedEvents =
                    listOf(
                        scrapedEvent("Mamma Mia", tomorrow, "admiralspalast:mamma-mia-2027-09-18", LocalTime.of(15, 0)),
                        scrapedEvent("Mamma Mia", tomorrow, "admiralspalast:mamma-mia-2027-09-18", LocalTime.of(19, 30))
                    )
                val saved = slot<Iterable<EventEntity>>()
                coEvery { eventRepository.saveAll(capture(saved)) } answers { emptyFlow() }

                service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                // One row, and no discriminator — the surviving event has no same-slug sibling, so
                // its slug must not churn just because the venue lists a second sitting.
                saved.captured.map { it.slug } shouldBe listOf("$tomorrow-test-venue-mamma-mia")
            }

        @Test
        fun `frees a stale row's slug before inserting the row that needs it`() =
            runTest {
                // The SO36 failure: a stale row holds the slug an incoming row is about to take.
                // Deleting must happen first, or the INSERT collides and fails the whole batch.
                val stale = existingEvent(id = 7L, eventDate = tomorrow, sourceId = "so36:90006")
                coEvery {
                    eventRepository.findByEventSourceIdAndEventDateBetween(eventSourceId, any(), any())
                } returns listOf(stale).asFlow()

                service.upsertAndCleanup(
                    listOf(scrapedEvent("Female-Fronted", tomorrow, "so36:93090", LocalTime.of(19, 30))),
                    venueId,
                    venueSlug,
                    eventSourceId
                )

                coVerifyOrder {
                    eventRepository.deleteByIdIn(listOf(7L))
                    eventRepository.saveAll(any<Iterable<EventEntity>>())
                }
            }
    }

    /**
     * The counts `importer.events.dropped` is built from (#982).
     *
     * Asserted on [UpsertOutcome] rather than on a `MeterRegistry`, because that is where the
     * boundary is: this service computes the numbers and deliberately does **not** hold the source
     * slug the tag needs, so `EventImportService` is what records them. A test that reached for a
     * registry here would be testing the wrong object.
     */
    @Nested
    inner class DropCounts {
        @Test
        fun `reports past and duplicate drops separately`() =
            runTest {
                val scrapedEvents =
                    listOf(
                        scrapedEvent(title = "Over", eventDate = yesterday, sourceId = "src:over"),
                        scrapedEvent(title = "Gig", eventDate = tomorrow, sourceId = "src:gig"),
                        // Same sourceId as the line above — one row by identity, whatever the title.
                        scrapedEvent(title = "Gig again", eventDate = tomorrow, sourceId = "src:gig")
                    )
                coEvery {
                    eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any())
                } returns emptyList<EventEntity>().asFlow()

                val outcome = service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                outcome.droppedPast shouldBe 1
                outcome.droppedDuplicate shouldBe 1
                outcome.dropped shouldBe 2
            }

        @Test
        fun `keeps drops out of total, which feeds lastEventCount`() =
            runTest {
                val scrapedEvents =
                    listOf(
                        scrapedEvent(title = "Over", eventDate = yesterday, sourceId = "src:over"),
                        scrapedEvent(title = "Gig", eventDate = tomorrow, sourceId = "src:gig")
                    )
                coEvery {
                    eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any())
                } returns emptyList<EventEntity>().asFlow()

                val outcome = service.upsertAndCleanup(scrapedEvents, venueId, venueSlug, eventSourceId)

                // `total` means "events this source holds" and reaches event_source.last_event_count.
                // Folding a dropped event into it would move a number every dashboard reads.
                outcome.total shouldBe 1
                outcome.droppedPast shouldBe 1
            }
    }
}
