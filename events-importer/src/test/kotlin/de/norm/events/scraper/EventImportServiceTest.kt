package de.norm.events.scraper

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.event.EventArtistEntity
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventPromoterRepository
import de.norm.events.event.EventRepository
import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.promoter.PromoterRepository
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [EventImportService].
 *
 * Tests the import pipeline logic (upsert, deduplication, stale event cleanup,
 * artist auto-creation, error handling) in isolation with mocked dependencies.
 * Persistence is delegated to a real [EventUpsertService] backed by a real
 * [AssociationSyncService] with mocked repositories.
 */
class EventImportServiceTest {
    private val eventSourceRepository: EventSourceRepository = mockk(relaxed = true)
    private val eventRepository: EventRepository = mockk(relaxed = true)
    private val eventArtistRepository: EventArtistRepository = mockk(relaxed = true)
    private val eventPromoterRepository: EventPromoterRepository = mockk(relaxed = true)
    private val eventGenreTagRepository: EventGenreTagRepository = mockk(relaxed = true)
    private val artistRepository: ArtistRepository = mockk(relaxed = true)

    /** Slug to stubbed artist id, so two different names never resolve to one artist. */
    private val artistIdsBySlug = mutableMapOf<String, Long>()

    /** Where those ids start. Any value works — distinctness is the point, not the number. */
    private val firstArtistId = 200L
    private val promoterRepository: PromoterRepository = mockk(relaxed = true)
    private val genreTagRepository: GenreTagRepository = mockk(relaxed = true)
    private val venueRepository: VenueRepository = mockk(relaxed = true)

    private val cassiopeiaImporter: EventImporter =
        mockk {
            coEvery { eventSource } returns EventSource.CASSIOPEIA
        }

    // TransactionalOperator that just executes the callback directly (no real transaction).
    // Mocks the underlying execute() method which executeAndAwait delegates to.
    private val transactionalOperator: TransactionalOperator =
        mockk {
            coEvery { execute(any<org.springframework.transaction.reactive.TransactionCallback<Any>>()) } answers {
                val callback = firstArg<org.springframework.transaction.reactive.TransactionCallback<Any>>()
                val reactiveTransaction = mockk<org.springframework.transaction.ReactiveTransaction>(relaxed = true)
                Flux.from(callback.doInTransaction(reactiveTransaction))
            }
        }

    /** Real services backed by mocked repositories — tested indirectly through the import pipeline. */
    private lateinit var associationSyncService: AssociationSyncService
    private lateinit var eventUpsertService: EventUpsertService
    private lateinit var service: EventImportService

    /**
     * Initialised here rather than in `setUp`: JUnit builds a fresh test instance per method, so a
     * field initialiser gives each test its own empty registry exactly as a `@BeforeEach` would —
     * and `setUp` is already at detekt's length limit.
     *
     * A real registry rather than a mock, because these tests assert on the meters that come out and
     * a relaxed mock would happily accept a name no dashboard matches.
     */
    private val registry = SimpleMeterRegistry()
    private val metrics = ImporterMetrics(registry)

    /**
     * Relaxed, because #472's coverage recording is a *measurement* hanging off a successful run and
     * none of the assertions in this file are about it — `FieldCoverageServiceTest` owns that rule.
     * What this file does still assert about it is the property that matters here: a coverage
     * failure must not fail an import (see the `measurement` test below).
     */
    private val fieldCoverageService: FieldCoverageService = mockk(relaxed = true)

    /**
     * Stubbed rather than real: the cache would reach the network for a `robots.txt`, and what these
     * tests assert is the import pipeline. [RobotsRulesCacheTest] covers the cache itself.
     */
    private val robotsRulesCache: RobotsRulesCache =
        mockk<RobotsRulesCache>().also {
            coEvery { it.check(any()) } returns
                RobotsCheck(host = "example.com", robotsTxtUrl = null, allowed = true, checkedAt = Instant.EPOCH)
        }

    /** Reusable event source entity with sensible defaults. */
    private fun source(
        id: Long = 1L,
        slug: String = "test-source",
        sourceType: String = "CASSIOPEIA",
        venueId: Long = 10L,
        url: String = "https://example.com/events",
        enabled: Boolean = true,
        etag: String? = null,
        lastModified: String? = null,
        lastEventCount: Int? = null,
        // A persisted source always carries a version (the column is NOT NULL DEFAULT 0), and
        // the import claim is gated on it, so the default mirrors a freshly inserted row.
        version: Long? = 0L
    ) = EventSourceEntity(
        id = id,
        venueId = venueId,
        name = "Test Source",
        slug = slug,
        url = url,
        sourceType = sourceType,
        enabled = enabled,
        etag = etag,
        lastModified = lastModified,
        lastEventCount = lastEventCount,
        version = version
    )

    /** Creates a minimal [ScrapedEvent] for testing. */
    private fun scrapedEvent(
        title: String = "Test Event",
        eventDate: LocalDate = LocalDate.of(2026, 6, 15),
        sourceId: String = "cassiopeia:test-event",
        sourceUrl: String = "https://example.com/event/test",
        eventType: String = "CONCERT",
        status: String = "SCHEDULED",
        artists: List<ScrapedArtist> = emptyList()
    ) = ScrapedEvent(
        title = title,
        eventDate = eventDate,
        sourceId = sourceId,
        sourceUrl = sourceUrl,
        eventType = eventType,
        status = status,
        artists = artists
    )

    @BeforeEach
    fun setUp() {
        buildServices()
        stubDefaults()
    }

    /** Extracted from `setUp` so it stays under detekt's method-length limit as collaborators are added. */
    private fun buildServices() {
        associationSyncService =
            AssociationSyncService(
                eventArtistRepository = eventArtistRepository,
                eventPromoterRepository = eventPromoterRepository,
                eventGenreTagRepository = eventGenreTagRepository,
                artistRepository = artistRepository,
                promoterRepository = promoterRepository,
                genreTagRepository = genreTagRepository
            )

        eventUpsertService =
            EventUpsertService(
                eventRepository = eventRepository,
                associationSyncService = associationSyncService,
                // Pin "today" to the fixtures' event date so the past-event cutoff keeps them.
                clock = Clock.fixed(LocalDate.of(2026, 6, 15).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
            )

        service =
            EventImportService(
                eventSourceRepository = eventSourceRepository,
                eventUpsertService = eventUpsertService,
                eventImporters = listOf(cassiopeiaImporter),
                venueRepository = venueRepository,
                transactionalOperator = transactionalOperator,
                metrics = metrics,
                fieldCoverageService = fieldCoverageService,
                robotsRulesCache = robotsRulesCache,
                maxConcurrency = EventImportService.DEFAULT_MAX_CONCURRENCY
            )
    }

    private fun stubDefaults() {
        // The import claim succeeds by default, so every test below runs the full pipeline.
        // The relaxed mock would otherwise answer 0 and skip the import as already-claimed.
        coEvery { eventSourceRepository.claimForImport(any(), any(), any()) } returns 1

        // Default stubs: empty collections, save returns input with ID
        coEvery { eventRepository.findBySourceIdIn(any()) } returns emptyFlow()
        coEvery { artistRepository.findBySlugIn(any()) } returns emptyFlow()
        coEvery { eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any()) } returns emptyFlow()
        coEvery { eventRepository.deleteByIdIn(any()) } returns Unit
        coEvery { eventArtistRepository.findByEventIdIn(any()) } returns emptyFlow()
        coEvery { eventArtistRepository.deleteAllById(any()) } returns Unit

        // Default venue stub — returns a venue with a known slug for event slug generation
        coEvery { venueRepository.findById(any<Long>()) } returns
            VenueEntity(
                id = 10L,
                name = "Test Venue",
                slug = "test-venue"
            )

        coEvery { eventRepository.saveAll(any<Iterable<EventEntity>>()) } answers {
            firstArg<Iterable<EventEntity>>()
                .mapIndexed { index, entity ->
                    entity.copy(id = entity.id ?: (100L + index))
                }.asFlow()
        }
        coEvery { eventSourceRepository.save(any()) } answers {
            firstArg<EventSourceEntity>()
        }
        // Artist resolution uses a conflict-tolerant insert + read-back (not save()).
        coEvery { artistRepository.insertIfAbsent(any(), any()) } returns 1
        // One id per distinct slug, because that is what a real repository gives. A constant here
        // modelled two different artists sharing one id, which `UNIQUE (event_id, artist_id)` makes
        // impossible — and it is what hid #798: the duplicate rows the old sync built looked correct
        // against this stub and failed against Postgres.
        artistIdsBySlug.clear()
        coEvery { artistRepository.findBySlug(any()) } answers {
            val slug = firstArg<String>()
            val id = artistIdsBySlug.getOrPut(slug) { firstArtistId + artistIdsBySlug.size }
            ArtistEntity(id = id, name = slug, slug = slug)
        }
        coEvery { eventArtistRepository.saveAll(any<Iterable<EventArtistEntity>>()) } answers {
            firstArg<Iterable<EventArtistEntity>>()
                .mapIndexed { index, entity ->
                    entity.copy(id = (300L + index))
                }.asFlow()
        }
    }

    @Nested
    inner class ImportFromSource {
        @Test
        fun `successful import creates events and returns result`() =
            runTest {
                val src = source()
                val events = listOf(scrapedEvent(title = "Show A", sourceId = "cassiopeia:show-a"))

                coEvery { cassiopeiaImporter.importEvents(src.url, src.etag, src.lastModified) } returns
                    ImportResult.Success(events = events, etag = "\"new-etag\"", lastModified = "Wed, 01 Jan 2026 00:00:00 GMT")

                val result = service.importFromSource(src)

                result.imported shouldBe true
                result.eventCount shouldBe 1
                result.sourceSlug shouldBe "test-source"
                result.error shouldBe null
            }

        @Test
        fun `NotModified result returns imported=false`() =
            runTest {
                val src = source(etag = "\"old-etag\"")

                coEvery { cassiopeiaImporter.importEvents(src.url, src.etag, src.lastModified) } returns
                    ImportResult.NotModified

                val result = service.importFromSource(src)

                result.imported shouldBe false
                result.eventCount shouldBe 0
                result.error shouldBe null
            }

        @Test
        fun `unknown source type records misconfiguration and returns error`() =
            runTest {
                val src = source(sourceType = "NONEXISTENT")

                val result = service.importFromSource(src)

                result.imported shouldBe false
                result.error shouldBe "Unknown source type 'NONEXISTENT'"

                // Should mark the source as MISCONFIGURED (not FAILED) — config errors don't consume retry budget
                coVerify {
                    eventSourceRepository.save(match { it.status == ImportStatus.MISCONFIGURED.name && it.retryCount == 0 })
                }
            }

        @Test
        fun `no importer registered records misconfiguration`() =
            runTest {
                val emptyService =
                    EventImportService(
                        eventSourceRepository = eventSourceRepository,
                        eventUpsertService = eventUpsertService,
                        eventImporters = emptyList(),
                        venueRepository = venueRepository,
                        transactionalOperator = transactionalOperator,
                        metrics = metrics,
                        fieldCoverageService = fieldCoverageService,
                        robotsRulesCache = robotsRulesCache,
                        maxConcurrency = EventImportService.DEFAULT_MAX_CONCURRENCY
                    )

                val src = source()
                val result = emptyService.importFromSource(src)

                result.imported shouldBe false
                result.error shouldBe "No importer registered for source type 'CASSIOPEIA'"

                // Should mark as MISCONFIGURED (not FAILED) — missing importer is a config issue
                coVerify {
                    eventSourceRepository.save(match { it.status == ImportStatus.MISCONFIGURED.name && it.retryCount == 0 })
                }
            }

        @Test
        fun `exception during import records failure`() =
            runTest {
                val src = source()
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } throws
                    RuntimeException("Network timeout")

                val result = service.importFromSource(src)

                result.imported shouldBe false
                result.error shouldBe "Network timeout"

                coVerify {
                    eventSourceRepository.save(match { it.status == ImportStatus.FAILED.name })
                }
            }

        @Test
        fun `source without persisted id throws IllegalArgumentException`() =
            runTest {
                val src = source().copy(id = null)

                val ex =
                    shouldThrow<IllegalArgumentException> {
                        service.importFromSource(src)
                    }
                ex.message shouldBe "Event source must be persisted (have a non-null id) before importing"
            }

        @Test
        fun `claims the source as RUNNING before importing it`() =
            runTest {
                val src = source()
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = emptyList(), etag = null, lastModified = null)

                service.importFromSource(src)

                // The RUNNING transition is a conditional UPDATE, not a read-modify-write save,
                // so that two callers cannot both claim the same source. It is gated on the
                // version that was read, making the claim a compare-and-swap on that exact row.
                coVerify { eventSourceRepository.claimForImport(1L, 0L, any()) }
            }

        @Test
        fun `skips the import when the source is already claimed by another run`() =
            runTest {
                val src = source()
                coEvery { eventSourceRepository.claimForImport(any(), any(), any()) } returns 0

                val result = service.importFromSource(src)

                result.imported shouldBe false
                result.eventCount shouldBe 0
                result.error shouldBe null
                // The losing run must not scrape, upsert, or touch the source's status.
                coVerify(exactly = 0) { cassiopeiaImporter.importEvents(any(), any(), any()) }
                coVerify(exactly = 0) { eventSourceRepository.save(any()) }
                coVerify(exactly = 0) { eventRepository.saveAll(any<Iterable<EventEntity>>()) }
            }

        @Test
        fun `skips the import when the source was imported after this run read it`() =
            runTest {
                // A scheduler tick reads a source while it is still IDLE, then waits on the
                // import semaphore. A manual trigger imports the source in the meantime, so by
                // the time this run claims, the row is back at SUCCESS — a status-only guard
                // would let it re-scrape the venue. The version it read has moved on, so the
                // conditional UPDATE matches no row.
                val staleSource = source(version = 0L)
                coEvery { eventSourceRepository.claimForImport(1L, 0L, any()) } returns 0

                val result = service.importFromSource(staleSource)

                result.imported shouldBe false
                result.eventCount shouldBe 0
                result.error shouldBe null
                coVerify(exactly = 0) { cassiopeiaImporter.importEvents(any(), any(), any()) }
                coVerify(exactly = 0) { eventSourceRepository.save(any()) }
            }

        @Test
        fun `closes the import at the version the claim wrote`() =
            runTest {
                // The claim increments the version, and it only succeeds when the row still
                // matched the version that was read — so the closing save can carry that exact
                // value instead of guessing, and no longer trips optimistic locking.
                val src = source(version = 7L)
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = emptyList(), etag = null, lastModified = null)

                service.importFromSource(src)

                coVerify { eventSourceRepository.claimForImport(1L, 7L, any()) }
                coVerify { eventSourceRepository.save(match { it.version == 8L && it.status == ImportStatus.SUCCESS.name }) }
                coVerify(exactly = 0) { eventSourceRepository.findById(any<Long>()) }
            }

        @Test
        fun `marks source as SUCCESS after successful import`() =
            runTest {
                val src = source()
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(
                        events = listOf(scrapedEvent()),
                        etag = "\"new-etag\"",
                        lastModified = null
                    )

                service.importFromSource(src)

                coVerify {
                    eventSourceRepository.save(match { it.status == ImportStatus.SUCCESS.name && it.lastEventCount == 1 })
                }
            }
    }

    /**
     * The meters the pipeline emits (#415).
     *
     * These matter more than they look. A scraper does not fail loudly: when a venue redesigns its
     * site the importer keeps running, reports success, and silently writes zero events — and the
     * only thing that ever notices is one of these series. So the tests assert **which outcome each
     * path produces**, because the states that are easiest to merge by accident are exactly the ones
     * that mean different things: not-modified, skipped-because-claimed and misconfigured all return
     * `imported=false, eventCount=0`.
     */
    @Nested
    inner class Metrics {
        private fun outcomeCount(
            slug: String,
            outcome: String
        ) = registry
            .find("importer.run.outcome")
            .tags("source", slug, "outcome", outcome)
            .counter()
            ?.count() ?: 0.0

        @Test
        fun `a successful import is tagged success and records a duration`() =
            runTest {
                val src = source()
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = listOf(scrapedEvent(title = "Show A", sourceId = "cassiopeia:show-a")), etag = null, lastModified = null)

                service.importFromSource(src)

                outcomeCount("test-source", "success") shouldBe 1.0
                registry
                    .find("importer.run.duration")
                    .tag("source", "test-source")
                    .timer()!!
                    .count() shouldBe 1L
            }

        @Test
        fun `a 304 is not_modified rather than success — it imported nothing and that is fine`() =
            runTest {
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns ImportResult.NotModified

                service.importFromSource(source(etag = "\"old-etag\""))

                outcomeCount("test-source", "not_modified") shouldBe 1.0
                outcomeCount("test-source", "success") shouldBe 0.0
            }

        /**
         * The column and the in-memory gauge have to agree, because the gauge has two feeds — this
         * one, immediate, and [MetricsRefreshService], which republishes from the column every
         * minute. A disagreement would make the value jump once a minute and read as clock skew.
         */
        @Test
        fun `a successful run stamps last_success_at, and the gauge agrees with it`() =
            runTest {
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = listOf(scrapedEvent()), etag = null, lastModified = null)

                service.importFromSource(source())

                coVerify { eventSourceRepository.save(match { it.lastSuccessAt != null && it.lastSuccessAt == it.lastImportAt }) }
                registry.find("importer.source.last_success").tag("source", "test-source").gauge() shouldNotBe null
            }

        @Test
        fun `a 304 stamps last_success_at too — the venue answered, which is the scraper working`() =
            runTest {
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns ImportResult.NotModified

                service.importFromSource(source(etag = "\"old-etag\""))

                coVerify { eventSourceRepository.save(match { it.lastSuccessAt != null }) }
                registry.find("importer.source.last_success").tag("source", "test-source").gauge() shouldNotBe null
            }

        /**
         * The other half of the same argument (#659). A 304 is a working scraper, so it must not
         * write the number an emptied one would write. `loge` reported `lastEventCount = 0` on a
         * run that succeeded, and the listing had six events on it the whole time — the run simply
         * never read it.
         */
        @Test
        fun `a 304 carries the previous event count forward instead of zeroing it`() =
            runTest {
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns ImportResult.NotModified

                service.importFromSource(source(etag = "\"old-etag\"", lastEventCount = 6))

                coVerify { eventSourceRepository.save(match { it.lastEventCount == 6 }) }
            }

        /**
         * The asymmetry that makes the column worth having: `last_import_at` moves on a failure and
         * `last_success_at` must not, or the staleness alert can never fire.
         */
        @Test
        fun `a failed run moves last_import_at and leaves last_success_at where it was`() =
            runTest {
                val previousSuccess = Instant.parse("2026-06-13T04:00:00Z")
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } throws IllegalStateException("selector no longer matches")

                service.importFromSource(source().copy(lastSuccessAt = previousSuccess))

                coVerify {
                    eventSourceRepository.save(
                        match {
                            it.status == ImportStatus.FAILED.name &&
                                it.lastSuccessAt == previousSuccess &&
                                it.lastImportAt != previousSuccess
                        }
                    )
                }
            }

        @Test
        fun `an unknown source type is misconfigured rather than failed, because retrying cannot help`() =
            runTest {
                service.importFromSource(source(sourceType = "NONEXISTENT"))

                outcomeCount("test-source", "misconfigured") shouldBe 1.0
                outcomeCount("test-source", "failed") shouldBe 0.0
            }

        @Test
        fun `losing the claim is skipped, not failed — the other run is doing the work`() =
            runTest {
                coEvery { eventSourceRepository.claimForImport(any(), any(), any()) } returns 0

                service.importFromSource(source())

                outcomeCount("test-source", "skipped") shouldBe 1.0
                outcomeCount("test-source", "failed") shouldBe 0.0
            }

        @Test
        fun `a thrown import is failed, and records a scrape failure with its reason`() =
            runTest {
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } throws
                    HttpFetchException(403, "https://cassiopeia.example/events")

                service.importFromSource(source())

                outcomeCount("test-source", "failed") shouldBe 1.0
                registry
                    .find("importer.scrape.failures")
                    .tags("source", "test-source", "reason", "http_forbidden")
                    .counter()!!
                    .count() shouldBe 1.0
            }

        /**
         * The distinction the `operation` tag exists for. `skipped` here is change detection
         * reporting that it worked — a source returning only skips for days is either genuinely
         * static or silently broken, and this is half of what tells those apart.
         */
        @Test
        fun `writes are split into inserted, updated and skipped`() =
            runTest {
                // One row already in the database byte-identical to what the scraper returns, and one
                // whose title has moved. Built by hand in the same shape as
                // `skips saving unchanged events…` above, because change detection compares the whole
                // entity — deriving the "unchanged" row from the scraped event would compare it with
                // itself and prove nothing.
                // The three titles differ deliberately: deduplication keys on date + title + start
                // time, so three same-titled events on one date would collapse into one before any of
                // this is reached — which is how the first attempt at this test silently measured a
                // single insert.
                val unchangedRow =
                    EventEntity(
                        id = 42L,
                        venueId = 10L,
                        title = "Same Show",
                        slug = "2026-06-15-test-venue-same-show",
                        eventDate = LocalDate.of(2026, 6, 15),
                        sourceId = "cassiopeia:same",
                        sourceUrl = "https://example.com/event/test",
                        eventSourceId = 1L,
                        eventType = "CONCERT",
                        status = "SCHEDULED"
                    )
                val staleRow =
                    unchangedRow.copy(
                        id = 43L,
                        sourceId = "cassiopeia:changed",
                        title = "Old Title",
                        slug = "2026-06-15-test-venue-old-title"
                    )

                coEvery { eventRepository.findBySourceIdIn(any()) } returns listOf(unchangedRow, staleRow).asFlow()

                val scraped =
                    listOf(
                        scrapedEvent(title = "Same Show", sourceId = "cassiopeia:same", eventDate = LocalDate.of(2026, 6, 15)),
                        scrapedEvent(title = "Changed Show", sourceId = "cassiopeia:changed", eventDate = LocalDate.of(2026, 6, 15)),
                        scrapedEvent(title = "New Show", sourceId = "cassiopeia:new", eventDate = LocalDate.of(2026, 6, 15))
                    )
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = scraped, etag = null, lastModified = null)

                service.importFromSource(source())

                fun written(operation: String) =
                    registry
                        .find("importer.events.written")
                        .tags("source", "test-source", "operation", operation)
                        .counter()
                        ?.count() ?: 0.0

                written("inserted") shouldBe 1.0
                written("updated") shouldBe 1.0
                written("skipped") shouldBe 1.0
            }
    }

    @Nested
    inner class UpsertAndArtistCreation {
        @Test
        fun `auto-creates unknown artists during import`() =
            runTest {
                val src = source()
                val events =
                    listOf(
                        scrapedEvent(
                            title = "Concert Night",
                            sourceId = "cassiopeia:concert-night",
                            artists = listOf(ScrapedArtist(name = "New Band", role = "HEADLINER"))
                        )
                    )

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = events, etag = null, lastModified = null)

                service.importFromSource(src)

                coVerify {
                    artistRepository.insertIfAbsent("New Band", "new-band")
                }
            }

        @Test
        fun `reuses existing artist by slug instead of creating duplicate`() =
            runTest {
                val src = source()
                val existingArtist = ArtistEntity(id = 50L, name = "Existing Band", slug = "existing-band")

                coEvery { artistRepository.findBySlugIn(any()) } returns listOf(existingArtist).asFlow()

                val events =
                    listOf(
                        scrapedEvent(
                            title = "Show",
                            sourceId = "cassiopeia:show",
                            artists = listOf(ScrapedArtist(name = "Existing Band", role = "HEADLINER"))
                        )
                    )

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = events, etag = null, lastModified = null)

                service.importFromSource(src)

                coVerify(exactly = 0) {
                    artistRepository.insertIfAbsent(any(), any())
                }

                coVerify {
                    eventArtistRepository.saveAll(
                        match<Iterable<EventArtistEntity>> { entities ->
                            entities.any { it.artistId == 50L }
                        }
                    )
                }
            }

        @Test
        fun `updates existing event instead of creating duplicate`() =
            runTest {
                val src = source()
                val existingEvent =
                    EventEntity(
                        id = 42L,
                        venueId = 10L,
                        title = "Old Title",
                        slug = "old-slug",
                        eventDate = LocalDate.of(2026, 6, 15),
                        sourceId = "cassiopeia:show",
                        eventSourceId = 1L
                    )

                coEvery { eventRepository.findBySourceIdIn(any()) } returns listOf(existingEvent).asFlow()

                val events =
                    listOf(
                        scrapedEvent(
                            title = "Updated Title",
                            sourceId = "cassiopeia:show",
                            eventDate = LocalDate.of(2026, 6, 15)
                        )
                    )

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = events, etag = null, lastModified = null)

                service.importFromSource(src)

                coVerify {
                    eventRepository.saveAll(
                        match<Iterable<EventEntity>> { entities ->
                            entities.any { it.id == 42L && it.title == "Updated Title" }
                        }
                    )
                }
            }

        @Test
        fun `skips saving unchanged events to avoid unnecessary database writes`() =
            runTest {
                val src = source()
                // Existing event in DB matches exactly what the scraper returns — no changes
                val existingEvent =
                    EventEntity(
                        id = 42L,
                        venueId = 10L,
                        title = "Concert Night",
                        slug = "2026-06-15-test-venue-concert-night",
                        eventDate = LocalDate.of(2026, 6, 15),
                        sourceId = "cassiopeia:show",
                        sourceUrl = "https://example.com/event/test",
                        eventSourceId = 1L,
                        eventType = "CONCERT",
                        status = "SCHEDULED"
                    )

                coEvery { eventRepository.findBySourceIdIn(any()) } returns listOf(existingEvent).asFlow()

                val events =
                    listOf(
                        scrapedEvent(
                            title = "Concert Night",
                            sourceId = "cassiopeia:show",
                            eventDate = LocalDate.of(2026, 6, 15)
                        )
                    )

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = events, etag = null, lastModified = null)

                val result = service.importFromSource(src)

                result.imported shouldBe true
                result.eventCount shouldBe 1

                coVerify(exactly = 0) {
                    eventRepository.saveAll(any<Iterable<EventEntity>>())
                }
            }

        @Test
        fun `creates event-artist associations with correct billing order`() =
            runTest {
                val src = source()
                val events =
                    listOf(
                        scrapedEvent(
                            title = "Multi-Artist Show",
                            sourceId = "cassiopeia:multi",
                            artists =
                                listOf(
                                    ScrapedArtist(name = "Headliner", role = "HEADLINER"),
                                    ScrapedArtist(name = "Support Act", role = "SUPPORT")
                                )
                        )
                    )

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = events, etag = null, lastModified = null)

                val savedAssociations = mutableListOf<EventArtistEntity>()
                coEvery { eventArtistRepository.saveAll(any<Iterable<EventArtistEntity>>()) } answers {
                    firstArg<Iterable<EventArtistEntity>>()
                        .mapIndexed { index, entity ->
                            entity.also { savedAssociations.add(it) }.copy(id = (300L + index))
                        }.asFlow()
                }

                service.importFromSource(src)

                savedAssociations.size shouldBe 2
                savedAssociations[0].billingOrder shouldBe 0
                savedAssociations[0].role shouldBe "HEADLINER"
                savedAssociations[1].billingOrder shouldBe 1
                savedAssociations[1].role shouldBe "SUPPORT"
            }
    }

    @Nested
    inner class Deduplication {
        @Test
        fun `deduplicates scraped events with same date and title`() =
            runTest {
                val src = source()
                val events =
                    listOf(
                        scrapedEvent(title = "Dup Event", eventDate = LocalDate.of(2026, 6, 15), sourceId = "cassiopeia:dup-1"),
                        scrapedEvent(title = "Dup Event", eventDate = LocalDate.of(2026, 6, 15), sourceId = "cassiopeia:dup-2")
                    )

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = events, etag = null, lastModified = null)

                val result = service.importFromSource(src)

                result.eventCount shouldBe 1
            }
    }

    @Nested
    inner class StaleEventCleanup {
        @Test
        fun `removes stale events no longer listed by source`() =
            runTest {
                val src = source()
                val eventDate = LocalDate.of(2026, 6, 15)

                // Currently scraped events
                val scrapedEvents =
                    listOf(
                        scrapedEvent(title = "Active Event", sourceId = "cassiopeia:active", eventDate = eventDate)
                    )

                // Existing events in DB — one is stale (not in scraped list)
                val activeEvent =
                    EventEntity(
                        id = 1L,
                        venueId = 10L,
                        title = "Active Event",
                        slug = "active",
                        eventDate = eventDate,
                        sourceId = "cassiopeia:active",
                        eventSourceId = 1L
                    )
                val staleEvent =
                    EventEntity(
                        id = 2L,
                        venueId = 10L,
                        title = "Removed Event",
                        slug = "removed",
                        eventDate = eventDate,
                        sourceId = "cassiopeia:removed",
                        eventSourceId = 1L
                    )

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = scrapedEvents, etag = null, lastModified = null)
                coEvery {
                    eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any())
                } returns listOf(activeEvent, staleEvent).asFlow()

                service.importFromSource(src)

                coVerify {
                    eventRepository.deleteByIdIn(match { 2L in it })
                }
            }

        @Test
        fun `does not delete events when scraped list is empty`() =
            runTest {
                val src = source()

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = emptyList(), etag = null, lastModified = null)

                service.importFromSource(src)

                coVerify(exactly = 0) {
                    eventRepository.deleteByIdIn(any())
                }
            }
    }

    @Nested
    inner class ImportConcurrently {
        @Test
        fun `empty source list returns empty result list`() =
            runTest {
                val results = service.importConcurrently(emptyList())

                results shouldBe emptyList()
            }

        @Test
        fun `invokes importFromSource for each source and preserves result ordering`() =
            runTest {
                val src1 = source(id = 1L, slug = "source-1")
                val src2 = source(id = 2L, slug = "source-2")
                val src3 = source(id = 3L, slug = "source-3")

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = emptyList(), etag = null, lastModified = null)

                val results = service.importConcurrently(listOf(src1, src2, src3))

                results.size shouldBe 3
                results[0].sourceSlug shouldBe "source-1"
                results[1].sourceSlug shouldBe "source-2"
                results[2].sourceSlug shouldBe "source-3"
            }

        @Test
        fun `one source failure does not prevent other sources from completing`() =
            runTest {
                val src1 = source(id = 1L, slug = "success-source", url = "https://example.com/source-1")
                val src2 = source(id = 2L, slug = "failing-source", url = "https://example.com/source-2")
                val src3 = source(id = 3L, slug = "another-success", url = "https://example.com/source-3")

                // src1 and src3 succeed; src2 throws
                coEvery { cassiopeiaImporter.importEvents(src1.url, any(), any()) } returns
                    ImportResult.Success(events = listOf(scrapedEvent(sourceId = "cassiopeia:s1")), etag = null, lastModified = null)
                coEvery { cassiopeiaImporter.importEvents(src2.url, any(), any()) } throws
                    RuntimeException("Connection refused")
                coEvery { cassiopeiaImporter.importEvents(src3.url, any(), any()) } returns
                    ImportResult.Success(events = listOf(scrapedEvent(sourceId = "cassiopeia:s3")), etag = null, lastModified = null)

                val results = service.importConcurrently(listOf(src1, src2, src3))

                // All three results returned — failure is isolated to the individual source
                results.size shouldBe 3
                results[0].imported shouldBe true
                results[1].imported shouldBe false
                results[1].error shouldBe "Connection refused"
                results[2].imported shouldBe true
            }
    }

    @Nested
    inner class ImportAll {
        @Test
        fun `importAll processes all enabled sources`() =
            runTest {
                val src1 = source(id = 1L, slug = "source-1")
                val src2 = source(id = 2L, slug = "source-2")

                coEvery { eventSourceRepository.findByEnabledTrue() } returns listOf(src1, src2).asFlow()
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = emptyList(), etag = null, lastModified = null)

                val results = service.importAll()

                results.size shouldBe 2
            }

        @Test
        fun `importAll returns empty list when no sources enabled`() =
            runTest {
                coEvery { eventSourceRepository.findByEnabledTrue() } returns emptyFlow()

                val results = service.importAll()

                results.size shouldBe 0
            }
    }

    @Nested
    inner class ImportBySlug {
        @Test
        fun `importBySlug delegates to importFromSource`() =
            runTest {
                val src = source(slug = "my-source")
                coEvery { eventSourceRepository.findBySlug("my-source") } returns src
                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.NotModified

                val result = service.importBySlug("my-source")

                result.sourceSlug shouldBe "my-source"
                result.imported shouldBe false
            }

        @Test
        fun `importBySlug throws EventSourceNotFoundException for unknown slug`() =
            runTest {
                coEvery { eventSourceRepository.findBySlug("unknown") } returns null

                shouldThrow<EventSourceNotFoundException> {
                    service.importBySlug("unknown")
                }
            }
    }

    @Nested
    inner class EnumParsing {
        @Test
        fun `unknown event type falls back to OTHER`() =
            runTest {
                val src = source()
                val events =
                    listOf(
                        scrapedEvent(title = "Weird Type", sourceId = "cassiopeia:weird", eventType = "UNKNOWN_TYPE")
                    )

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = events, etag = null, lastModified = null)

                val savedEvents = mutableListOf<EventEntity>()
                coEvery { eventRepository.saveAll(any<Iterable<EventEntity>>()) } answers {
                    firstArg<Iterable<EventEntity>>()
                        .map { entity ->
                            entity.copy(id = entity.id ?: 100L).also { savedEvents.add(it) }
                        }.asFlow()
                }

                service.importFromSource(src)

                savedEvents.first().eventType shouldBe "OTHER"
            }

        @Test
        fun `unknown event status falls back to SCHEDULED`() =
            runTest {
                val src = source()
                val events =
                    listOf(
                        scrapedEvent(title = "Bad Status", sourceId = "cassiopeia:bad", status = "INVALID_STATUS")
                    )

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = events, etag = null, lastModified = null)

                val savedEvents = mutableListOf<EventEntity>()
                coEvery { eventRepository.saveAll(any<Iterable<EventEntity>>()) } answers {
                    firstArg<Iterable<EventEntity>>()
                        .map { entity ->
                            entity.copy(id = entity.id ?: 100L).also { savedEvents.add(it) }
                        }.asFlow()
                }

                service.importFromSource(src)

                savedEvents.first().status shouldBe "SCHEDULED"
            }

        @Test
        fun `unknown artist role falls back to HEADLINER`() =
            runTest {
                val src = source()
                val events =
                    listOf(
                        scrapedEvent(
                            title = "Bad Role",
                            sourceId = "cassiopeia:role",
                            artists = listOf(ScrapedArtist(name = "Band", role = "UNKNOWN_ROLE"))
                        )
                    )

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = events, etag = null, lastModified = null)

                val savedAssociations = mutableListOf<EventArtistEntity>()
                coEvery { eventArtistRepository.saveAll(any<Iterable<EventArtistEntity>>()) } answers {
                    firstArg<Iterable<EventArtistEntity>>()
                        .mapIndexed { index, entity ->
                            entity.also { savedAssociations.add(it) }.copy(id = (300L + index))
                        }.asFlow()
                }

                service.importFromSource(src)

                savedAssociations.first().role shouldBe "HEADLINER"
            }
    }

    @Nested
    inner class ETagAndLastModified {
        @Test
        fun `successful import updates etag and lastModified on source`() =
            runTest {
                val src = source(etag = "\"old\"", lastModified = "Mon, 01 Jan 2026 00:00:00 GMT")

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(
                        events = listOf(scrapedEvent()),
                        etag = "\"new-etag\"",
                        lastModified = "Wed, 15 Jun 2026 00:00:00 GMT"
                    )

                service.importFromSource(src)

                coVerify {
                    eventSourceRepository.save(
                        match {
                            it.status == ImportStatus.SUCCESS.name &&
                                it.etag == "\"new-etag\"" &&
                                it.lastModified == "Wed, 15 Jun 2026 00:00:00 GMT"
                        }
                    )
                }
            }
    }

    @Nested
    inner class OptimisticLockingRetry {
        @Test
        fun `retries markSuccess with fresh entity on OptimisticLockingFailureException`() =
            runTest {
                val src = source()
                val freshSource = source(id = 1L).copy(version = 5L)

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } returns
                    ImportResult.Success(events = listOf(scrapedEvent()), etag = null, lastModified = null)

                // The RUNNING claim is an UPDATE, not a save — so markSuccess is the first save.
                // It throws once, and the retry (the second save) succeeds.
                var saveCallCount = 0
                coEvery { eventSourceRepository.save(any()) } answers {
                    saveCallCount++
                    val entity = firstArg<EventSourceEntity>()
                    if (saveCallCount == 1 && entity.status == ImportStatus.SUCCESS.name) {
                        throw OptimisticLockingFailureException("Version conflict")
                    }
                    entity
                }
                coEvery { eventSourceRepository.findById(1L) } returns freshSource

                val result = service.importFromSource(src)

                result.imported shouldBe true
                coVerify { eventSourceRepository.findById(1L) }
                coVerify(atLeast = 2) { eventSourceRepository.save(any()) }
            }

        @Test
        fun `retries markFailed with fresh entity on OptimisticLockingFailureException`() =
            runTest {
                val src = source()
                val freshSource = source(id = 1L).copy(version = 5L)

                coEvery { cassiopeiaImporter.importEvents(any(), any(), any()) } throws
                    RuntimeException("Network timeout")

                // The RUNNING claim is an UPDATE, not a save — so markFailed is the first save.
                // It throws once, and the retry (the second save) succeeds.
                var saveCallCount = 0
                coEvery { eventSourceRepository.save(any()) } answers {
                    saveCallCount++
                    val entity = firstArg<EventSourceEntity>()
                    if (saveCallCount == 1 && entity.status == ImportStatus.FAILED.name) {
                        throw OptimisticLockingFailureException("Version conflict")
                    }
                    entity
                }
                coEvery { eventSourceRepository.findById(1L) } returns freshSource

                val result = service.importFromSource(src)

                result.imported shouldBe false
                result.error shouldBe "Network timeout"
                coVerify { eventSourceRepository.findById(1L) }
            }
    }

    @Nested
    inner class GlobalConcurrencyBound {
        /**
         * The concurrency limit must be *global*, not per-batch: a scheduled batch
         * ([EventImportService.importConcurrently]) running at the same time as a manual
         * fire-and-forget trigger ([EventImportService.importFromSource], as used by
         * `ImportJobLauncher`) must together never exceed `maxConcurrency` in-flight imports.
         */
        @Test
        fun `scheduled batch and manual trigger together never exceed maxConcurrency`() =
            runTest {
                val active = AtomicInteger(0)
                val maxObserved = AtomicInteger(0)
                val maxConcurrency = 2

                val boundedService =
                    EventImportService(
                        eventSourceRepository = eventSourceRepository,
                        eventUpsertService = eventUpsertService,
                        eventImporters = listOf(ConcurrencyTrackingImporter(active, maxObserved)),
                        venueRepository = venueRepository,
                        transactionalOperator = transactionalOperator,
                        metrics = metrics,
                        fieldCoverageService = fieldCoverageService,
                        robotsRulesCache = robotsRulesCache,
                        maxConcurrency = maxConcurrency
                    )

                val batchSources = (1..4).map { source(id = it.toLong(), slug = "batch-$it") }
                val manualSource = source(id = 99L, slug = "manual")

                // Run a scheduled batch of 4 concurrently with a manual single-source import.
                coroutineScope {
                    val batch = async { boundedService.importConcurrently(batchSources) }
                    val manual = async { boundedService.importFromSource(manualSource) }

                    batch.await().size shouldBe 4
                    manual.await().sourceSlug shouldBe "manual"
                }

                // Never exceeded the global bound — the manual path did not stack on top of the batch...
                maxObserved.get() shouldBeLessThanOrEqual maxConcurrency
                // ...and the bound was actually reached, proving the two paths shared one permit pool.
                maxObserved.get() shouldBe maxConcurrency
            }
    }
}

/**
 * Test [EventImporter] that records the peak number of concurrent [importEvents] calls,
 * used to assert the global concurrency bound in [EventImportServiceTest.GlobalConcurrencyBound].
 * It suspends (via [delay]) while "in flight" so overlapping imports are observable under
 * `runTest`'s virtual time.
 */
private class ConcurrencyTrackingImporter(
    private val active: AtomicInteger,
    private val maxObserved: AtomicInteger
) : EventImporter {
    override val eventSource = EventSource.CASSIOPEIA

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val current = active.incrementAndGet()
        maxObserved.updateAndGet { maxOf(it, current) }
        try {
            delay(IN_FLIGHT_MILLIS)
        } finally {
            active.decrementAndGet()
        }
        return ImportResult.Success(events = emptyList(), etag = null, lastModified = null)
    }

    private companion object {
        const val IN_FLIGHT_MILLIS = 50L
    }
}
