package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistOccupationRepository
import de.norm.events.enrichment.LookupPass
import de.norm.events.event.EventRepository
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.wikimedia.WikimediaClient
import de.norm.events.wikimedia.WikimediaProperties
import de.norm.events.wikimedia.WikimediaUnavailableException
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import java.time.LocalDate

/**
 * ADR-039 against a real PostgreSQL: the occupation read stores one flag per EXACT row, and a
 * comedian headliner types a fallback night `COMEDY`, on import and on stored rows. Wikidata is a
 * scripted client.
 */
class ComedianTypingIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var occupations: ArtistOccupationRepository

    @Autowired
    private lateinit var eventRepository: EventRepository

    private val wikimedia = mockk<WikimediaClient>()
    private val today = LocalDate.now(BERLIN)
    private var venueId: Long = 0

    private fun occupationService(enabled: Boolean = true) = ArtistOccupationService(occupations, wikimedia, WikimediaProperties(enabled = enabled))

    private fun typing() = PerformerTyping(occupations, eventRepository)

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            venueId =
                databaseClient
                    .sql("INSERT INTO events.venue (name, slug) VALUES ('Arena', 'arena') RETURNING id")
                    .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
                    .awaitSingle()
        }

    @Test
    fun `stores a cabaret performer who is also a musician as a comedian, and a musician as none`(): Unit =
        runBlocking {
            artist("Dieter Nuhr", "dieter-nuhr", "EXACT", wikidata = "Q76152")
            artist("Baby Keem", "baby-keem", "EXACT", wikidata = "Q42")
            artist("Unmatched", "unmatched", "NONE", wikidata = "Q7")
            artist("No Link", "no-link", "EXACT", wikidata = null)
            coEvery { wikimedia.occupationsOf("Q76152") } returns setOf("Q15214752", "Q639669")
            coEvery { wikimedia.occupationsOf("Q42") } returns setOf("Q639669")

            occupationService().sweep() shouldBe LookupPass(owed = 2, stored = 2)

            comedian("dieter-nuhr") shouldBe "true"
            comedian("baby-keem") shouldBe "false"
            comedian("unmatched") shouldBe "unread"
            comedian("no-link") shouldBe "unread"
            occupationService().sweep() shouldBe LookupPass(owed = 0, stored = 0)
        }

    @Test
    fun `a failed read leaves the row for the next tick, and a switched-off read asks nothing`(): Unit =
        runBlocking {
            artist("Torsten Sträter", "torsten-strater", "EXACT", wikidata = "Q2440019")
            coEvery { wikimedia.occupationsOf(any()) } throws WikimediaUnavailableException("503")

            occupationService().sweep() shouldBe LookupPass(owed = 1, stored = 0)
            comedian("torsten-strater") shouldBe "unread"

            occupationService(enabled = false).sweep() shouldBe LookupPass.OFF
            coVerify(exactly = 1) { wikimedia.occupationsOf(any()) }
        }

    @Test
    fun `types an upcoming fallback night COMEDY by its comedian headliner, and nothing else`(): Unit =
        runBlocking {
            artist("Dieter Nuhr", "dieter-nuhr", "EXACT", wikidata = "Q76152", comedian = true)
            artist("Baby Keem", "baby-keem", "EXACT", wikidata = "Q42", comedian = false)
            event("nuhr", today.plusDays(10), fallback = true, headliner = "dieter-nuhr")
            event("nuhr-cued", today.plusDays(11), fallback = false, headliner = "dieter-nuhr")
            event("nuhr-past", today.minusDays(10), fallback = true, headliner = "dieter-nuhr")
            event("keem", today.plusDays(12), fallback = true, headliner = "baby-keem")
            event("keem-with-nuhr-support", today.plusDays(13), fallback = true, headliner = "baby-keem", support = "dieter-nuhr")

            typing().retypeStored() shouldBe 1

            type("nuhr") shouldBe "COMEDY"
            type("nuhr-cued") shouldBe "CONCERT"
            type("nuhr-past") shouldBe "CONCERT"
            type("keem") shouldBe "CONCERT"
            type("keem-with-nuhr-support") shouldBe "CONCERT"
            typing().retypeStored() shouldBe 0
        }

    @Test
    fun `the import types a fallback night by its stored comedian headliner and leaves a cued one`(): Unit =
        runBlocking {
            artist("Dieter Nuhr", "dieter-nuhr", "EXACT", wikidata = "Q76152", comedian = true)

            fun scraped(
                id: String,
                fallback: Boolean
            ) = ScrapedEvent(
                title = "Dieter Nuhr",
                eventType = "CONCERT",
                typeIsFallback = fallback,
                eventDate = today.plusDays(5),
                sourceUrl = "https://arena.example/$id",
                sourceId = "arena:$id",
                artists = listOf(ScrapedArtist("Dieter Nuhr", "HEADLINER", titleDerived = true))
            )

            val typed = typing().retype(listOf(scraped("a", fallback = true), scraped("b", fallback = false)))

            typed.map { it.eventType } shouldBe listOf("COMEDY", "CONCERT")
            typed.first().toEventEntity(venueId, "arena", 1L).typeIsFallback shouldBe true
        }

    private suspend fun artist(
        name: String,
        slug: String,
        match: String,
        wikidata: String?,
        comedian: Boolean? = null
    ) {
        databaseClient
            .sql(
                "INSERT INTO events.artist (name, slug, musicbrainz_match, musicbrainz_id, wikidata_url, comedian) " +
                    "VALUES (:name, :slug, :match, :mbid, :wikidata, :comedian)"
            ).bind("name", name)
            .bind("slug", slug)
            .bind("match", match)
            .let { if (match == "EXACT") it.bind("mbid", "mbid-$slug") else it.bindNull("mbid", String::class.java) }
            .let { spec -> wikidata?.let { spec.bind("wikidata", "https://www.wikidata.org/wiki/$it") } ?: spec.bindNull("wikidata", String::class.java) }
            .let { spec -> comedian?.let { spec.bind("comedian", it) } ?: spec.bindNull("comedian", Boolean::class.javaObjectType) }
            .await()
    }

    private suspend fun event(
        sourceId: String,
        date: LocalDate,
        fallback: Boolean,
        headliner: String,
        support: String? = null
    ) {
        databaseClient
            .sql(
                "INSERT INTO events.event (venue_id, title, slug, event_date, source_id, event_type, type_is_fallback) " +
                    "VALUES ($venueId, '$sourceId', '$sourceId', :date, '$sourceId', 'CONCERT', $fallback)"
            ).bind("date", date)
            .await()
        link(sourceId, headliner, "HEADLINER")
        support?.let { link(sourceId, it, "SUPPORT") }
    }

    private suspend fun link(
        sourceId: String,
        artistSlug: String,
        role: String
    ) = databaseClient
        .sql(
            "INSERT INTO events.event_artist (event_id, artist_id, role) " +
                "SELECT e.id, a.id, '$role' FROM events.event e, events.artist a WHERE e.source_id = '$sourceId' AND a.slug = '$artistSlug'"
        ).await()

    /** The stored flag as text, `unread` for NULL: a row mapper may not return null. */
    private suspend fun comedian(slug: String): String =
        databaseClient
            .sql("SELECT COALESCE(comedian::text, 'unread') AS flag FROM events.artist WHERE slug = '$slug'")
            .map { row, _ -> row.get("flag", String::class.java)!! }
            .awaitSingle()

    private suspend fun type(sourceId: String): String =
        databaseClient
            .sql("SELECT event_type FROM events.event WHERE source_id = '$sourceId'")
            .map { row, _ -> row.get("event_type", String::class.java)!! }
            .awaitSingle()
}
