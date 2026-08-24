package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import java.time.Instant

/**
 * The coarse half of the two-phase due-date filter, against a real database (#659).
 *
 * [EventSourceRepository.findDueForImport] is raw SQL in an annotation, so a unit test can only
 * assert what a mock was told to return. What matters here is **which rows the database hands
 * back**, and in particular which ones it silently withholds: a source missing from this result
 * is a source the scheduler never sees and nothing reports on, because absence is not a value any
 * alert rule can select.
 *
 * The clause under test used to read `AND (status != 'FAILED' OR retry_count < max_retries)`.
 * That is the withholding this fixture is built to catch.
 *
 * @see ScheduledImportServiceTest for the precise half — interval and backoff arithmetic.
 */
class FindDueForImportIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

    /**
     * One source per reason the query might drop a row.
     *
     * `healthy`      — the baseline; nothing about it is unusual.
     * `retrying`     — FAILED with budget left, which was always a candidate.
     * `budget-spent` — FAILED with `retry_count = max_retries`: **the row #659 is about.**
     * `over-budget`  — `retry_count` past `max_retries`, which is reachable because every further
     *                  failure increments it. Bounding on equality alone would miss this one.
     * `disabled`     — an operator's explicit "stop", and the correct way to take a source off the
     *                  schedule. It must stay excluded.
     * `running`      — overlap prevention; excluding it is what stops a second concurrent run.
     * `misconfigured`— a permanent config error that no retry cadence can resolve.
     */
    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            val venueId = insertVenue()
            insertSource(venueId, "healthy", ImportStatus.SUCCESS, retryCount = 0)
            insertSource(venueId, "retrying", ImportStatus.FAILED, retryCount = 1)
            insertSource(venueId, "budget-spent", ImportStatus.FAILED, retryCount = 3)
            insertSource(venueId, "over-budget", ImportStatus.FAILED, retryCount = 9)
            insertSource(venueId, "disabled", ImportStatus.SUCCESS, retryCount = 0, enabled = false)
            insertSource(venueId, "running", ImportStatus.RUNNING, retryCount = 0)
            insertSource(venueId, "misconfigured", ImportStatus.MISCONFIGURED, retryCount = 0)
        }

    /**
     * **The regression.** A source that has spent its retry budget stays a candidate, so
     * [ScheduledImportService.isDue] gets to put it back on its own interval. Before #659 the
     * query dropped it and it was never attempted again until someone ran `retryAll` — a source
     * that stops being tried looks, to every rule reading `event_source`, exactly like one that
     * has nothing to do.
     */
    @Test
    fun `a source that has spent its retry budget is still a candidate`(): Unit =
        runBlocking {
            val slugs = dueSlugs()
            slugs shouldContain "budget-spent"
            slugs shouldContain "over-budget"
        }

    @Test
    fun `healthy and retrying sources are candidates`(): Unit =
        runBlocking {
            val slugs = dueSlugs()
            slugs shouldContain "healthy"
            slugs shouldContain "retrying"
        }

    /** The three exclusions that are still exclusions, each for a reason a retry cannot fix. */
    @Test
    fun `disabled, running and misconfigured sources are excluded`(): Unit =
        runBlocking {
            val slugs = dueSlugs()
            slugs shouldNotContain "disabled"
            slugs shouldNotContain "running"
            slugs shouldNotContain "misconfigured"
        }

    private suspend fun dueSlugs(): List<String> =
        eventSourceRepository
            .findDueForImport(Instant.now())
            .toList()
            .map { it.slug }

    // --- seeding ---------------------------------------------------------------------------------
    //
    // Raw SQL rather than the repository, for the reason PerSourceEventsGaugeIntegrationTest gives:
    // seeding through the mapping layer under test would let one bug hide another. `last_import_at`
    // is far enough back that every source is past even the longest interval, so this fixture
    // isolates the SQL clause from the interval arithmetic.

    private suspend fun insertVenue(): Long =
        databaseClient
            .sql(
                "INSERT INTO events.venue (name, slug, address, city, postal_code) " +
                    "VALUES ('Test Venue', 'test-venue', 'Somewhere 1', 'Berlin', '10999') RETURNING id"
            ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun insertSource(
        venueId: Long,
        slug: String,
        status: ImportStatus,
        retryCount: Int,
        enabled: Boolean = true
    ) = databaseClient
        .sql(
            """
            INSERT INTO events.event_source
                (venue_id, name, slug, url, source_type, enabled, status, retry_count, max_retries, last_import_at)
            VALUES ($venueId, '$slug', '$slug', 'https://$slug.example/events', 'CASSIOPEIA',
                    $enabled, '${status.name}', $retryCount, ${EventSourceEntity.DEFAULT_MAX_RETRIES},
                    NOW() - INTERVAL '365 days')
            """.trimIndent()
        ).await()
}
