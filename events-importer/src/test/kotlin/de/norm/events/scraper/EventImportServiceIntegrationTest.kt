package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventRepository
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration test for the full event import pipeline with real database records.
 *
 * Unlike [EventImportServiceTest] which uses mocks, this test exercises the
 * complete upsert → cleanup pipeline against a real PostgreSQL database
 * via Testcontainers, verifying that events, artists, and associations are
 * correctly created, updated, and cleaned up.
 *
 * A mock [EventImporter] is injected via [TestConfiguration] to control
 * what the scraper "returns" without making real HTTP requests — the focus
 * is on the database pipeline, not the scraping logic.
 *
 * **This is the one context fork in the module that was kept (#965)**, and the `@Import` is why: it
 * puts a `@Primary` mock in the context, which is a different context configuration and so a second
 * cached context and container. The alternative is to build [EventImportService] by hand, the way
 * `DataQualitySnapshotIntegrationTest` builds its logger — but that test needs one collaborator and
 * this one autowires six, so hand-wiring them would trade a container for a constructor call nobody
 * can read. A fork that earns its keep is not a defect.
 */
@Import(EventImportServiceIntegrationTest.MockImporterConfiguration::class)
class EventImportServiceIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var eventImportService: EventImportService

    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var eventArtistRepository: EventArtistRepository

    @Autowired
    private lateinit var artistRepository: ArtistRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    /** The mock importer injected via [MockImporterConfiguration]. */
    @Autowired
    private lateinit var mockImporter: EventImporter

    private var venueId: Long = 0
    private var eventSourceId: Long = 0

    @BeforeEach
    fun setUpFixtures() {
        runBlocking {
            // Create a venue and event source for all tests
            val venue = venueRepository.save(VenueEntity(name = "Test Venue", slug = "test-venue"))
            venueId = requireNotNull(venue.id)

            val source =
                eventSourceRepository.save(
                    EventSourceEntity(
                        venueId = venueId,
                        name = "Test Source",
                        slug = "test-source",
                        url = "https://example.com/events",
                        sourceType = "CASSIOPEIA",
                        enabled = true
                    )
                )
            eventSourceId = requireNotNull(source.id)
        }
    }

    @Nested
    inner class UpsertPipeline {
        @Test
        fun `imports new events with artist auto-creation and associations`() {
            runBlocking {
                val scrapedEvents =
                    listOf(
                        ScrapedEvent(
                            title = "Concert Night",
                            eventDate = LocalDate.of(2026, 7, 15),
                            doorsTime = LocalTime.of(19, 0),
                            startTime = LocalTime.of(20, 0),
                            sourceId = "cassiopeia:concert-night",
                            sourceUrl = "https://example.com/event/concert-night",
                            eventType = "CONCERT",
                            genre = "Punk",
                            artists =
                                listOf(
                                    ScrapedArtist(name = "The Headliners", role = "HEADLINER"),
                                    ScrapedArtist(name = "Opening Act", role = "SUPPORT")
                                )
                        )
                    )

                stubImporterSuccess(scrapedEvents)

                val source = eventSourceRepository.findBySlug("test-source")!!
                val result = eventImportService.importFromSource(source)

                // Verify import result
                result.imported shouldBe true
                result.eventCount shouldBe 1
                result.error shouldBe null

                // Verify event was persisted correctly
                val events = eventRepository.findBySourceIdIn(listOf("cassiopeia:concert-night")).toList()
                events shouldHaveSize 1
                val event = events.first()
                event.title shouldBe "Concert Night"
                event.venueId shouldBe venueId
                event.eventSourceId shouldBe eventSourceId
                event.eventType shouldBe "CONCERT"
                event.genre shouldBe "Punk"
                event.doorsTime shouldBe LocalTime.of(19, 0)
                event.startTime shouldBe LocalTime.of(20, 0)

                // Verify artists were auto-created
                val headliner = artistRepository.findBySlug("the-headliners")
                headliner.shouldNotBeNull()
                headliner.name shouldBe "The Headliners"

                val support = artistRepository.findBySlug("opening-act")
                support.shouldNotBeNull()
                support.name shouldBe "Opening Act"

                // Verify event-artist associations with correct roles and billing order
                val associations = eventArtistRepository.findByEventId(requireNotNull(event.id)).toList()
                associations shouldHaveSize 2
                val headlinerAssoc = associations.first { it.artistId == headliner.id }
                headlinerAssoc.role shouldBe "HEADLINER"
                headlinerAssoc.billingOrder shouldBe 0
                val supportAssoc = associations.first { it.artistId == support.id }
                supportAssoc.role shouldBe "SUPPORT"
                supportAssoc.billingOrder shouldBe 1
            }
        }

        @Test
        fun `does not update unchanged events on re-import`() {
            runBlocking {
                // First import — create the event
                val initialEvents =
                    listOf(
                        ScrapedEvent(
                            title = "Stable Event",
                            eventDate = LocalDate.of(2026, 7, 25),
                            sourceId = "cassiopeia:stable-event",
                            sourceUrl = "https://example.com/event/stable",
                            eventType = "CONCERT",
                            genre = "Jazz"
                        )
                    )
                stubImporterSuccess(initialEvents)
                val source = eventSourceRepository.findBySlug("test-source")!!
                eventImportService.importFromSource(source)

                val originalEvent = eventRepository.findBySourceIdIn(listOf("cassiopeia:stable-event")).toList().first()
                val originalUpdatedAt = originalEvent.updatedAt

                // Second import — same data, nothing changed
                stubImporterSuccess(initialEvents)
                val refreshedSource = eventSourceRepository.findBySlug("test-source")!!
                eventImportService.importFromSource(refreshedSource)

                // Verify event was NOT re-saved — updated_at should remain unchanged
                val reloadedEvent = eventRepository.findBySourceIdIn(listOf("cassiopeia:stable-event")).toList().first()
                reloadedEvent.id shouldBe originalEvent.id
                reloadedEvent.updatedAt shouldBe originalUpdatedAt
                reloadedEvent.title shouldBe "Stable Event"
                reloadedEvent.genre shouldBe "Jazz"
            }
        }

        @Test
        fun `updates existing events on re-import`() {
            runBlocking {
                // First import — create the event
                val initialEvents =
                    listOf(
                        ScrapedEvent(
                            title = "Original Title",
                            eventDate = LocalDate.of(2026, 7, 20),
                            sourceId = "cassiopeia:updatable-event",
                            sourceUrl = "https://example.com/event/updatable",
                            eventType = "CONCERT",
                            genre = "Rock"
                        )
                    )
                stubImporterSuccess(initialEvents)
                val source = eventSourceRepository.findBySlug("test-source")!!
                eventImportService.importFromSource(source)

                val originalEvent = eventRepository.findBySourceIdIn(listOf("cassiopeia:updatable-event")).toList().first()

                // Second import — update the event with new title and genre
                val updatedEvents =
                    listOf(
                        ScrapedEvent(
                            title = "Updated Title",
                            eventDate = LocalDate.of(2026, 7, 20),
                            sourceId = "cassiopeia:updatable-event",
                            sourceUrl = "https://example.com/event/updatable",
                            eventType = "PARTY",
                            genre = "Electronic"
                        )
                    )
                stubImporterSuccess(updatedEvents)
                // Re-fetch source to get updated version after first import
                val refreshedSource = eventSourceRepository.findBySlug("test-source")!!
                eventImportService.importFromSource(refreshedSource)

                // Verify event was updated (same ID) not duplicated
                val events = eventRepository.findBySourceIdIn(listOf("cassiopeia:updatable-event")).toList()
                events shouldHaveSize 1
                val updated = events.first()
                updated.id shouldBe originalEvent.id
                updated.title shouldBe "Updated Title"
                updated.eventType shouldBe "PARTY"
                updated.genre shouldBe "Electronic"
            }
        }

        @Test
        fun `reuses existing artists by slug instead of creating duplicates`() {
            runBlocking {
                // Pre-create an artist that will match the scraped artist by slug
                val existingArtist =
                    artistRepository.save(
                        ArtistEntity(name = "Known Artist", slug = "known-artist")
                    )

                val scrapedEvents =
                    listOf(
                        ScrapedEvent(
                            title = "Show with Known Artist",
                            eventDate = LocalDate.of(2026, 8, 1),
                            sourceId = "cassiopeia:known-artist-show",
                            sourceUrl = "https://example.com/event/known",
                            artists = listOf(ScrapedArtist(name = "Known Artist", role = "HEADLINER"))
                        )
                    )
                stubImporterSuccess(scrapedEvents)

                val source = eventSourceRepository.findBySlug("test-source")!!
                eventImportService.importFromSource(source)

                // Verify no duplicate artist was created
                val artists = artistRepository.findBySlugIn(listOf("known-artist")).toList()
                artists shouldHaveSize 1
                artists.first().id shouldBe existingArtist.id

                // Verify the association links to the existing artist
                val event = eventRepository.findBySourceIdIn(listOf("cassiopeia:known-artist-show")).toList().first()
                val associations = eventArtistRepository.findByEventId(requireNotNull(event.id)).toList()
                associations shouldHaveSize 1
                associations.first().artistId shouldBe existingArtist.id
            }
        }
    }

    @Nested
    inner class StaleEventCleanup {
        @Test
        fun `removes stale events no longer listed by source`() {
            runBlocking {
                // First import — create two events
                val initialEvents =
                    listOf(
                        ScrapedEvent(
                            title = "Active Event",
                            eventDate = LocalDate.of(2026, 8, 10),
                            sourceId = "cassiopeia:active",
                            sourceUrl = "https://example.com/event/active"
                        ),
                        ScrapedEvent(
                            title = "Will Be Removed",
                            eventDate = LocalDate.of(2026, 8, 10),
                            sourceId = "cassiopeia:to-remove",
                            sourceUrl = "https://example.com/event/to-remove"
                        )
                    )
                stubImporterSuccess(initialEvents)
                val source = eventSourceRepository.findBySlug("test-source")!!
                eventImportService.importFromSource(source)

                // Verify both events exist
                eventRepository
                    .findBySourceIdIn(listOf("cassiopeia:active", "cassiopeia:to-remove"))
                    .toList() shouldHaveSize 2

                // Second import — only the active event remains
                val updatedEvents =
                    listOf(
                        ScrapedEvent(
                            title = "Active Event",
                            eventDate = LocalDate.of(2026, 8, 10),
                            sourceId = "cassiopeia:active",
                            sourceUrl = "https://example.com/event/active"
                        )
                    )
                stubImporterSuccess(updatedEvents)
                val refreshedSource = eventSourceRepository.findBySlug("test-source")!!
                eventImportService.importFromSource(refreshedSource)

                // The stale event should have been removed
                val activeEvents = eventRepository.findBySourceIdIn(listOf("cassiopeia:active")).toList()
                activeEvents shouldHaveSize 1
                activeEvents.first().title shouldBe "Active Event"

                val removedEvents = eventRepository.findBySourceIdIn(listOf("cassiopeia:to-remove")).toList()
                removedEvents shouldHaveSize 0
            }
        }

        @Test
        fun `does not delete events when scraped list is empty`() {
            runBlocking {
                // First import — create an event
                val initialEvents =
                    listOf(
                        ScrapedEvent(
                            title = "Existing Event",
                            eventDate = LocalDate.of(2026, 9, 1),
                            sourceId = "cassiopeia:existing",
                            sourceUrl = "https://example.com/event/existing"
                        )
                    )
                stubImporterSuccess(initialEvents)
                val source = eventSourceRepository.findBySlug("test-source")!!
                eventImportService.importFromSource(source)

                // Second import returns empty list — events should NOT be deleted
                stubImporterSuccess(emptyList())
                val refreshedSource = eventSourceRepository.findBySlug("test-source")!!
                eventImportService.importFromSource(refreshedSource)

                val events = eventRepository.findBySourceIdIn(listOf("cassiopeia:existing")).toList()
                events shouldHaveSize 1
            }
        }

        @Test
        fun `cleanup cascades to artist associations via ON DELETE CASCADE`() {
            runBlocking {
                // Import an event with an artist
                val events =
                    listOf(
                        ScrapedEvent(
                            title = "Event with Artist",
                            eventDate = LocalDate.of(2026, 8, 15),
                            sourceId = "cassiopeia:cascading",
                            sourceUrl = "https://example.com/event/cascading",
                            artists = listOf(ScrapedArtist(name = "Cascade Band", role = "HEADLINER"))
                        )
                    )
                stubImporterSuccess(events)
                val source = eventSourceRepository.findBySlug("test-source")!!
                eventImportService.importFromSource(source)

                val event = eventRepository.findBySourceIdIn(listOf("cassiopeia:cascading")).toList().first()
                val eventId = requireNotNull(event.id)
                eventArtistRepository.findByEventId(eventId).toList() shouldHaveSize 1

                // Re-import without this event — stale cleanup should delete it
                stubImporterSuccess(
                    listOf(
                        ScrapedEvent(
                            title = "Other Event",
                            eventDate = LocalDate.of(2026, 8, 15),
                            sourceId = "cassiopeia:other",
                            sourceUrl = "https://example.com/event/other"
                        )
                    )
                )
                val refreshedSource = eventSourceRepository.findBySlug("test-source")!!
                eventImportService.importFromSource(refreshedSource)

                // Event should be deleted, and its artist association should cascade
                eventRepository.findBySourceIdIn(listOf("cassiopeia:cascading")).toList() shouldHaveSize 0
                eventArtistRepository.findByEventId(eventId).toList() shouldHaveSize 0
            }
        }
    }

    @Nested
    inner class SourceStatusTracking {
        @Test
        fun `marks source as SUCCESS with event count after import`() {
            runBlocking {
                val events =
                    listOf(
                        ScrapedEvent(
                            title = "Event 1",
                            eventDate = LocalDate.of(2026, 7, 1),
                            sourceId = "cassiopeia:e1",
                            sourceUrl = "https://example.com/e1"
                        ),
                        ScrapedEvent(
                            title = "Event 2",
                            eventDate = LocalDate.of(2026, 7, 2),
                            sourceId = "cassiopeia:e2",
                            sourceUrl = "https://example.com/e2"
                        )
                    )
                stubImporterSuccess(events)
                val source = eventSourceRepository.findBySlug("test-source")!!
                eventImportService.importFromSource(source)

                val updatedSource = eventSourceRepository.findBySlug("test-source")!!
                updatedSource.status shouldBe ImportStatus.SUCCESS.name
                updatedSource.lastEventCount shouldBe 2
                updatedSource.lastError shouldBe null
                updatedSource.lastImportAt.shouldNotBeNull()
            }
        }

        @Test
        fun `marks source as FAILED when importer throws`() {
            runBlocking {
                coEvery { mockImporter.importEvents(any(), any(), any()) } throws
                    RuntimeException("Network timeout")

                val source = eventSourceRepository.findBySlug("test-source")!!
                val result = eventImportService.importFromSource(source)

                result.imported shouldBe false
                result.error shouldBe "Network timeout"

                val updatedSource = eventSourceRepository.findBySlug("test-source")!!
                updatedSource.status shouldBe ImportStatus.FAILED.name
                updatedSource.lastError shouldBe "Network timeout"
                updatedSource.retryCount shouldBe 1
            }
        }

        @Test
        fun `updates etag and lastModified on successful import`() {
            runBlocking {
                coEvery { mockImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(
                        events =
                            listOf(
                                ScrapedEvent(
                                    title = "Etag Test",
                                    eventDate = LocalDate.of(2026, 7, 10),
                                    sourceId = "cassiopeia:etag",
                                    sourceUrl = "https://example.com/etag"
                                )
                            ),
                        etag = "\"new-etag-123\"",
                        lastModified = "Wed, 15 Jul 2026 00:00:00 GMT"
                    )

                val source = eventSourceRepository.findBySlug("test-source")!!
                eventImportService.importFromSource(source)

                val updatedSource = eventSourceRepository.findBySlug("test-source")!!
                updatedSource.etag shouldBe "\"new-etag-123\""
                updatedSource.lastModified shouldBe "Wed, 15 Jul 2026 00:00:00 GMT"
            }
        }
    }

    @Nested
    inner class ConcurrentImportClaim {
        /**
         * Two runs of the same source must not overlap.
         *
         * Every import path funnels through a bounded semaphore, so a source can be requested
         * twice and stay un-started — still IDLE, still `last_import_at IS NULL`, and therefore
         * still due — for the whole wait. Before the source was claimed with a conditional
         * UPDATE, both runs would scrape and insert the same events and the loser died on the
         * `event_slug_key` unique index, rolling back and marking the source FAILED even though
         * the other run had just succeeded.
         *
         * The importer here blocks until both runs have entered, so they genuinely overlap
         * rather than merely running back to back.
         */
        @Test
        fun `only one of two concurrent runs imports the source`() {
            runBlocking {
                val bothStarted = CompletableDeferred<Unit>()
                val scrapeCount = AtomicInteger()
                coEvery { mockImporter.importEvents(any(), any(), any()) } coAnswers {
                    // Hold the first run inside the importer so the second overlaps it, but
                    // never hang the test if only one run ever gets this far.
                    scrapeCount.incrementAndGet()
                    withTimeoutOrNull(OVERLAP_WINDOW_MILLIS) { bothStarted.await() }
                    ImportResult.Success(
                        events = listOf(scrapedEvent("cassiopeia:concurrent", "Concurrent Night")),
                        etag = null,
                        lastModified = null
                    )
                }

                val source = eventSourceRepository.findBySlug("test-source")!!
                val results =
                    listOf(
                        async { eventImportService.importFromSource(source) },
                        async { eventImportService.importFromSource(source) }
                    ).also { bothStarted.complete(Unit) }.awaitAll()

                // Exactly one run claimed the source; the other skipped without scraping.
                scrapeCount.get() shouldBe 1
                results.count { it.imported } shouldBe 1
                results.count { !it.imported } shouldBe 1
                // The skipped run is not a failure — it reports no error and none is recorded.
                results.all { it.error == null } shouldBe true

                eventRepository.findBySourceIdIn(listOf("cassiopeia:concurrent")).toList() shouldHaveSize 1
                val updated = eventSourceRepository.findBySlug("test-source")!!
                updated.status shouldBe ImportStatus.SUCCESS.name
                updated.lastError.shouldBeNull()
            }
        }

        @Test
        fun `a source left RUNNING by a crashed run is not imported again until it is released`() {
            runBlocking {
                val running = eventSourceRepository.findBySlug("test-source")!!
                eventSourceRepository.save(running.copy(status = ImportStatus.RUNNING.name))

                // The mock importer is a shared Spring bean, so count this test's own scrapes
                // rather than verifying against its lifetime call count.
                val scrapeCount = AtomicInteger()
                coEvery { mockImporter.importEvents(any(), any(), any()) } coAnswers {
                    scrapeCount.incrementAndGet()
                    ImportResult.Success(events = emptyList(), etag = null, lastModified = null)
                }

                val result = eventImportService.importFromSource(eventSourceRepository.findBySlug("test-source")!!)

                result.imported shouldBe false
                scrapeCount.get() shouldBe 0
                // ScheduledImportService.resetStuckSources is what releases it, after the
                // staleness timeout — the claim itself never steals a running source.
                eventSourceRepository.findBySlug("test-source")!!.status shouldBe ImportStatus.RUNNING.name
            }
        }
    }

    // -- Helpers ---

    /** Minimal scraped event for the concurrency tests. */
    private fun scrapedEvent(
        sourceId: String,
        title: String
    ) = ScrapedEvent(
        title = title,
        eventDate = LocalDate.of(2026, 7, 15),
        sourceId = sourceId,
        sourceUrl = "https://example.com/event/$sourceId"
    )

    /** Stubs the mock importer to return a successful result with the given events. */
    private fun stubImporterSuccess(events: List<ScrapedEvent>) {
        coEvery { mockImporter.importEvents(any(), any(), any()) } returns
            ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /**
     * Test configuration that provides a mock [EventImporter] for CASSIOPEIA and a
     * fixed [Clock] for the import pipeline.
     *
     * The mock importer uses `@Primary` to override the real [CassiopeiaWebsiteImporter]
     * bean, ensuring the integration test controls what the importer returns without
     * making real HTTP requests.
     *
     * The fixed clock (pinned to 2026-07-01, on or before every fixture date) makes
     * [EventUpsertService]'s past-event cutoff deterministic — without it the cutoff
     * would drop fixtures dated before the real wall-clock day.
     */
    @TestConfiguration
    class MockImporterConfiguration {
        @Bean
        @Primary
        fun mockCassiopeiaImporter(): EventImporter =
            mockk {
                every { eventSource } returns EventSource.CASSIOPEIA
            }

        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC)
    }

    private companion object {
        /** Upper bound on how long the first concurrent run waits for the second to enter. */
        private const val OVERLAP_WINDOW_MILLIS = 5_000L
    }
}
