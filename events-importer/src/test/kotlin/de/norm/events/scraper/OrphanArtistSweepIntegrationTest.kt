package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.UnbilledArtistStore
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * [OrphanArtistSweep] against a real database (#350): which rows the delete takes, which it keeps,
 * and that it does nothing while an import runs. The bean itself is off in tests
 * (`app.scheduling.enabled: false`), so each test builds one.
 */
class OrphanArtistSweepIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var artistRepository: ArtistRepository

    @Autowired
    private lateinit var unbilledArtistStore: UnbilledArtistStore

    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

    private var venueId: Long = 0

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            venueId = insertVenue()
            insertEvent("e1")
            insertArtist("Billed Act", "billed-act", ageDays = 30)
            link("e1", "billed-act")
            insertArtist("Tontom (Br) *live", "tontom-br-live", ageDays = 30)
            insertArtist("Minted Today", "minted-today", ageDays = 0)
            insertArtist("Matched Act", "matched-act", ageDays = 30, extra = "musicbrainz_match = 'EXACT'")
            insertArtist("Curated Act", "curated-act", ageDays = 30, extra = "website_url = 'https://curated.example'")
        }

    @Test
    fun `deletes an old unbilled row and keeps billed, new, matched and curated rows`(): Unit =
        runBlocking {
            sweepAt(Instant.now()).runOnce() shouldBe 1L

            slugs() shouldContainExactlyInAnyOrder listOf("billed-act", "minted-today", "matched-act", "curated-act")
        }

    @Test
    fun `does nothing while an import runs`(): Unit =
        runBlocking {
            insertSource("running", ImportStatus.RUNNING)

            sweepAt(Instant.now()).runOnce().shouldBeNull()

            slugs() shouldContainExactlyInAnyOrder
                listOf("billed-act", "tontom-br-live", "minted-today", "matched-act", "curated-act")
        }

    private fun sweepAt(now: Instant) = OrphanArtistSweep(unbilledArtistStore, eventSourceRepository, Clock.fixed(now, ZoneOffset.UTC))

    private suspend fun slugs(): List<String> = artistRepository.findAll().map { it.slug }.toList()

    private suspend fun insertVenue(): Long =
        databaseClient
            .sql("INSERT INTO events.venue (name, slug) VALUES ('Test Venue', 'test-venue') RETURNING id")
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun insertEvent(sourceId: String) =
        databaseClient
            .sql(
                "INSERT INTO events.event (venue_id, title, slug, event_date, source_id) " +
                    "VALUES ($venueId, '$sourceId', '$sourceId', DATE '2026-01-01', '$sourceId')"
            ).await()

    private suspend fun insertArtist(
        name: String,
        slug: String,
        ageDays: Int,
        extra: String? = null
    ) {
        databaseClient
            .sql("INSERT INTO events.artist (name, slug, created_at) VALUES ('$name', '$slug', NOW() - INTERVAL '$ageDays days')")
            .await()
        extra?.let { databaseClient.sql("UPDATE events.artist SET $it WHERE slug = '$slug'").await() }
    }

    private suspend fun link(
        sourceId: String,
        artistSlug: String
    ) = databaseClient
        .sql(
            "INSERT INTO events.event_artist (event_id, artist_id) " +
                "SELECT e.id, a.id FROM events.event e, events.artist a WHERE e.source_id = '$sourceId' AND a.slug = '$artistSlug'"
        ).await()

    private suspend fun insertSource(
        slug: String,
        status: ImportStatus
    ) = databaseClient
        .sql(
            "INSERT INTO events.event_source (venue_id, name, slug, url, source_type, status) " +
                "VALUES ($venueId, '$slug', '$slug', 'https://$slug.example/events', 'CASSIOPEIA', '${status.name}')"
        ).await()
}
