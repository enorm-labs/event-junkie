package de.norm.events.image

import de.norm.events.BaseControllerTest
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.test.web.reactive.server.expectBody

/**
 * The image cache gauges against a real database and a real exposition (#880).
 *
 * Two things cannot be tested any other way, and both fail silently — the same pair
 * `PerSourceEventsGaugeIntegrationTest` was written for:
 *
 * 1. **The projection maps by column label.** `countUrlStates` aliases four `FILTER` expressions to
 *    match [ImageUrlCountsRow]'s properties, nothing checks that at compile time, and a mismatch is
 *    a mapping exception or a wrong number at runtime. A mocked repository returns what it was
 *    handed and sees none of it.
 * 2. **The published name is not the meter name.** Micrometer rewrites on the way out, which is how
 *    `db.events.total` reached the exposition as `db_events` and broke every rule written from the
 *    documented name. Only the exposition text settles what an alert can select on.
 */
class ImageMetricsIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var refreshService: ImageMetricsRefreshService

    @Autowired
    private lateinit var registry: MeterRegistry

    /**
     * Five image URLs, one per state plus a second pending one.
     *
     * `cached`   — a row holding a content hash, and no variants yet, so it is also the backlog.
     * `failed`   — tried and refused, inside its cooldown.
     * `withheld` — tombstoned by a takedown. It holds a hash and must count as neither cached nor
     *              pending: nothing will fetch it again, so counting it as work shows a backlog that
     *              can never drain.
     * `fresh`    — no row at all. The ordinary case for a URL the fetcher has not reached.
     * `started`  — a row with neither hash nor failure, which is what an interrupted pass leaves.
     */
    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            val venueId = insertVenue()
            listOf("cached", "failed", "withheld", "fresh", "started").forEach { insertEvent(venueId, it) }

            insertImage("cached", contentHash = "'aaa'")
            insertImage("failed", failedAt = "now()")
            insertImage("withheld", contentHash = "'ccc'", deletedAt = "now()")
            insertImage("started")

            refreshService.refreshGauges()
        }

    @Test
    fun `each URL is counted once, in the state its row puts it in`() {
        gauge("images.urls", "cached") shouldBe 1.0
        gauge("images.urls", "failed") shouldBe 1.0
        gauge("images.urls", "withheld") shouldBe 1.0
        gauge("images.urls", "pending") shouldBe 2.0
    }

    /**
     * The property that makes the four safe to stack on one graph, and the one a `FILTER` clause is
     * easiest to get wrong: a URL in two buckets, or in none.
     */
    @Test
    fun `the four states sum to every referenced URL`() {
        listOf("cached", "failed", "withheld", "pending").sumOf { gauge("images.urls", it) } shouldBe 5.0
    }

    /** A tombstoned image owns no work, so it is absent from the backlog even holding a hash. */
    @Test
    fun `the derivative backlog counts stored images short of their variants, and skips takedowns`() {
        registry.find("images.derivatives.backlog").gauge()!!.value() shouldBe 1.0
    }

    /**
     * The names an alert rule selects on, read off the wire rather than off the Kotlin constant.
     * `images.urls` must not arrive as anything else, and nothing but this settles it.
     */
    @Test
    fun `the gauges reach the exposition under the names a rule can use`() {
        val body =
            webTestClient
                .get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<String>()
                .returnResult()
                .responseBody!!

        listOf(
            """images_urls{state="cached"}""",
            """images_urls{state="pending"}""",
            "images_derivatives_backlog",
            """images_fetch_total{outcome="failed"}"""
        ).forEach {
            assert(body.contains(it)) { "expected '$it' in the exposition; got:\n${body.take(3000)}" }
        }
    }

    private fun gauge(
        name: String,
        state: String
    ): Double =
        registry
            .find(name)
            .tag("state", state)
            .gauge()!!
            .value()

    // --- seeding ---------------------------------------------------------------------------------
    //
    // Raw SQL rather than the repositories, as PerSourceEventsGaugeIntegrationTest does it: seeding
    // through the mapping layer under test would let one bug hide another.

    private suspend fun insertVenue(): Long =
        databaseClient
            .sql(
                "INSERT INTO events.venue (name, slug, address, city, postal_code) " +
                    "VALUES ('Test Venue', 'test-venue', 'Somewhere 1', 'Berlin', '10999') RETURNING id"
            ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun insertEvent(
        venueId: Long,
        slug: String
    ) = databaseClient
        .sql(
            """
            INSERT INTO events.event (venue_id, title, event_type, slug, event_date, source_id, image_url)
            VALUES ($venueId, '$slug', 'CONCERT', '$slug', CURRENT_DATE + 7, '$slug', '$IMAGE_HOST/$slug.jpg')
            """.trimIndent()
        ).await()

    private suspend fun insertImage(
        slug: String,
        contentHash: String = "NULL",
        failedAt: String = "NULL",
        deletedAt: String = "NULL"
    ) = databaseClient
        .sql(
            """
            INSERT INTO events.cached_image (source_url, content_hash, failed_at, deleted_at)
            VALUES ('$IMAGE_HOST/$slug.jpg', $contentHash, $failedAt, $deletedAt)
            """.trimIndent()
        ).await()

    private companion object {
        const val IMAGE_HOST = "https://venue.test"
    }
}
