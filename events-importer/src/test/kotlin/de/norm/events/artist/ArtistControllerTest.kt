package de.norm.events.artist

import de.norm.events.BaseControllerTest
import de.norm.events.common.PageResponse
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.test.web.reactive.server.expectBody
import java.time.LocalDate
import java.time.ZoneId

class ArtistControllerTest : BaseControllerTest() {
    @Autowired
    private lateinit var enrichmentStore: ArtistEnrichmentStore

    @Autowired
    private lateinit var artistRepository: ArtistRepository

    /** Creates an artist via the API and returns the persisted [ArtistResponse]. */
    private fun createArtist(request: ArtistRequest = ArtistRequestFixtures.adicts()): ArtistResponse =
        webTestClient
            .post()
            .uri("/api/admin/artists")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<ArtistResponse>()
            .returnResult()
            .responseBody!!

    private fun deleteArtist(id: Long) {
        webTestClient
            .delete()
            .uri("/api/admin/artists/$id")
            .exchange()
            .expectStatus()
            .isNoContent
    }

    @Test
    fun `POST, GET, PUT, DELETE artist lifecycle`() {
        // Create
        val created = createArtist()

        created.name shouldBe "The Adicts"
        created.slug shouldBe "the-adicts"
        created.websiteUrl shouldBe "https://theadicts.net/"

        val id = created.id

        // Read
        webTestClient
            .get()
            .uri("/api/admin/artists/$id")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<ArtistResponse>()
            .consumeWith { result ->
                result.responseBody!!.name shouldBe "The Adicts"
            }

        // Update
        webTestClient
            .put()
            .uri("/api/admin/artists/$id")
            .bodyValue(ArtistRequestFixtures.adicts(name = "The Adicts UK", description = "Updated bio"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<ArtistResponse>()
            .consumeWith { result ->
                val artist = result.responseBody!!
                artist.name shouldBe "The Adicts UK"
                artist.slug shouldBe "the-adicts-uk"
                artist.description shouldBe "Updated bio"
            }

        // Delete
        deleteArtist(id)

        // Verify deleted
        webTestClient
            .get()
            .uri("/api/admin/artists/$id")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET artist by non-existent ID returns 404`() {
        webTestClient
            .get()
            .uri("/api/admin/artists/99999")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET all artists returns list`() {
        val created = createArtist(ArtistRequestFixtures.create(name = "Maid of Ace"))

        val artists =
            webTestClient
                .get()
                .uri("/api/admin/artists")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<ArtistResponse>>()
                .returnResult()
                .responseBody!!

        // `.content`, never `.size`: on the envelope that field is the *page* size, so an assertion
        // written against it passes whatever the listing returned (#810).
        artists.content.size shouldBeGreaterThanOrEqual 1
        artists.content.map { it.id } shouldContain created.id
        // Everything created here fits on one page, so the total must equal what came back.
        artists.totalElements shouldBe artists.content.size.toLong()
    }

    // #2988: the admin's lineup picker finds an artist by part of the name.
    @Test
    fun `GET artists with name finds the names that contain it, ignoring case, and counts only those`() {
        val adicts = createArtist(ArtistRequestFixtures.adicts())
        val maid = createArtist(ArtistRequestFixtures.create(name = "Maid of Ace"))
        val percent = createArtist(ArtistRequestFixtures.create(name = "100% Beat"))

        // The name goes in as a URI variable, so `%` arrives encoded as `%25`.
        fun search(name: String): PageResponse<ArtistResponse> =
            webTestClient
                .get()
                .uri {
                    it
                        .path("/api/admin/artists")
                        .queryParam("name", "{name}")
                        .queryParam("size", 50)
                        .build(name)
                }.exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<ArtistResponse>>()
                .returnResult()
                .responseBody!!

        assertSoftly {
            search("ADICTS").content.map { it.id } shouldContainExactly listOf(adicts.id)
            search("the adicts").totalElements shouldBe 1
            search("of a").content.map { it.id } shouldContainExactly listOf(maid.id)
            search("Bellmer").content shouldBe emptyList()
            search("Bellmer").totalElements shouldBe 0
            // `%` is a letter here, not a wildcard that matches every name.
            search("%").content.map { it.id } shouldContainExactly listOf(percent.id)
            search("_").content shouldBe emptyList()
            // A blank name narrows nothing.
            search(" ").totalElements shouldBe 3
        }
    }

    @Test
    fun `POST artist with blank name returns 400`() {
        webTestClient
            .post()
            .uri("/api/admin/artists")
            .bodyValue(ArtistRequestFixtures.create(name = ""))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `POST artist with whitespace-only name returns 400`() {
        webTestClient
            .post()
            .uri("/api/admin/artists")
            .bodyValue(ArtistRequestFixtures.create(name = "   "))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `POST artist with duplicate name returns 409 with descriptive message`() {
        createArtist(ArtistRequestFixtures.adicts())

        // Second artist with the same name should conflict on slug
        webTestClient
            .post()
            .uri("/api/admin/artists")
            .bodyValue(ArtistRequestFixtures.adicts())
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("An artist with slug 'the-adicts' already exists (generated from name 'The Adicts')")
    }

    @Test
    fun `PUT artist with name that collides with existing slug returns 409`() {
        val first = createArtist(ArtistRequestFixtures.create(name = "Motörhead"))
        val second = createArtist(ArtistRequestFixtures.create(name = "Unique Artist"))

        // Renaming second artist to a name whose slug collides with the first
        webTestClient
            .put()
            .uri("/api/admin/artists/${second.id}")
            .bodyValue(ArtistRequestFixtures.create(name = "Motorhead"))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("An artist with slug 'motorhead' already exists (generated from name 'Motorhead')")

        // Clean up
        deleteArtist(first.id)
        deleteArtist(second.id)
    }

    @Test
    fun `an act with an ARTIST_SLUG_OVERRIDES entry is created beside its namesake and keeps its slug on rename`() {
        val gore = createArtist(ArtistRequestFixtures.create(name = "Gore"))
        val goere = createArtist(ArtistRequestFixtures.create(name = "Göre"))

        // A rename recomputes the slug; without the override it would be `gore` and answer 409 (#2942).
        webTestClient
            .put()
            .uri("/api/admin/artists/${goere.id}")
            .bodyValue(ArtistRequestFixtures.create(name = "GÖRE"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.slug")
            .isEqualTo("goere")

        deleteArtist(gore.id)
        deleteArtist(goere.id)
    }

    @Test
    fun `PUT artist with blank name returns 400`() {
        val created = createArtist()

        webTestClient
            .put()
            .uri("/api/admin/artists/${created.id}")
            .bodyValue(ArtistRequestFixtures.create(name = ""))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `PUT keeps a licensed text's credit and its other-language lead while the text is unchanged, and drops both once a person edits the text`() {
        val created = createArtist(ArtistRequestFixtures.adicts(description = null))
        val lead = "The Adicts are an English punk rock band from Ipswich, formed in 1975 and known for their Clockwork Orange look."
        runBlocking {
            enrichmentStore.store(
                created.id,
                mapOf(
                    "description" to lead,
                    "description_language" to "en",
                    "description_attribution" to "Wikipedia",
                    "description_licence_id" to "CC-BY-SA-4.0",
                    "description_source_url" to "https://en.wikipedia.org/wiki/The_Adicts",
                    "description_alt" to "The Adicts sind eine englische Punkband aus Ipswich, die 1975 gegründet wurde.",
                    "description_alt_language" to "de",
                    "description_alt_attribution" to "Wikipedia",
                    "description_alt_licence_id" to "CC-BY-SA-4.0",
                    "description_alt_source_url" to "https://de.wikipedia.org/wiki/The_Adicts"
                )
            )
        }

        fun put(description: String) =
            webTestClient
                .put()
                .uri("/api/admin/artists/${created.id}")
                .bodyValue(ArtistRequestFixtures.adicts(description = description))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<ArtistResponse>()
                .returnResult()
                .responseBody!!

        val unchanged = put(lead)
        unchanged.descriptionAttribution shouldBe "Wikipedia"
        unchanged.descriptionLanguage shouldBe "en"
        unchanged.descriptionSourceUrl shouldBe "https://en.wikipedia.org/wiki/The_Adicts"
        unchanged.descriptionAltLanguage shouldBe "de"
        unchanged.descriptionAltSourceUrl shouldBe "https://de.wikipedia.org/wiki/The_Adicts"

        val edited = put("Punk aus Ipswich, seit 1975.")
        edited.description shouldBe "Punk aus Ipswich, seit 1975."
        edited.descriptionLanguage shouldBe null
        edited.descriptionAttribution shouldBe null
        edited.descriptionLicenceId shouldBe null
        edited.descriptionSourceUrl shouldBe null
        edited.descriptionAlt shouldBe null
        edited.descriptionAltLanguage shouldBe null
        edited.descriptionAltAttribution shouldBe null
        edited.descriptionAltLicenceId shouldBe null
        edited.descriptionAltSourceUrl shouldBe null

        deleteArtist(created.id)
    }

    @Test
    fun `PUT with a MusicBrainz id stores an EXACT match the sweep keeps, across a rename in the same request`() {
        val created = createArtist()
        runBlocking { artistRepository.storeMusicBrainzVerdict(created.id, MusicBrainzMatch.AMBIGUOUS.name, null) }
        val mbid = "06e3bce0-c612-4a5f-b095-9ffed1e4a656"

        fun put(request: ArtistRequest) =
            webTestClient
                .put()
                .uri("/api/admin/artists/${created.id}")
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<ArtistResponse>()
                .returnResult()
                .responseBody!!

        val pinned = put(ArtistRequestFixtures.adicts().copy(name = "Adicts", musicbrainzId = mbid))
        pinned.musicbrainzMatch shouldBe MusicBrainzMatch.EXACT
        pinned.musicbrainzId shouldBe mbid
        runBlocking {
            artistRepository.findNeedingMusicBrainzLookup(listOf(created.id)).toList() shouldBe emptyList()
            artistRepository.findNeedingMusicBrainzEnrichment(listOf(created.id)).toList().map { it.id } shouldBe listOf(created.id)
        }

        // A PUT without the field leaves the verdict alone.
        put(ArtistRequestFixtures.adicts().copy(name = "Adicts")).musicbrainzId shouldBe mbid

        deleteArtist(created.id)
    }

    @Test
    fun `PUT with a MusicBrainz id that is not a lowercase UUID returns 400`() {
        val created = createArtist()

        webTestClient
            .put()
            .uri("/api/admin/artists/${created.id}")
            .bodyValue(ArtistRequestFixtures.adicts().copy(musicbrainzId = "06E3BCE0-C612-4A5F-B095-9FFED1E4A656"))
            .exchange()
            .expectStatus()
            .isBadRequest

        deleteArtist(created.id)
    }

    // #2946: the review of AMBIGUOUS names lists one verdict, and the acts billed soon.
    private fun list(
        query: String,
        size: Int = 50
    ): PageResponse<ArtistResponse> =
        webTestClient
            .get()
            .uri("/api/admin/artists?size=$size&$query")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<PageResponse<ArtistResponse>>()
            .returnResult()
            .responseBody!!

    private fun verdict(
        artist: ArtistResponse,
        match: MusicBrainzMatch
    ) = runBlocking { artistRepository.storeMusicBrainzVerdict(artist.id, match.name, if (match == MusicBrainzMatch.EXACT) MBID else null) }

    @Test
    fun `GET artists with musicbrainzMatch lists that verdict only, and narrows a name search too`() {
        val accept = createArtist(ArtistRequestFixtures.create(name = "Accept"))
        val acceptance = createArtist(ArtistRequestFixtures.create(name = "Acceptance"))
        val gore = createArtist(ArtistRequestFixtures.create(name = "Gore"))
        val unchecked = createArtist(ArtistRequestFixtures.create(name = "Unchecked Act"))
        verdict(accept, MusicBrainzMatch.AMBIGUOUS)
        verdict(acceptance, MusicBrainzMatch.EXACT)
        verdict(gore, MusicBrainzMatch.AMBIGUOUS)

        assertSoftly {
            list("musicbrainzMatch=AMBIGUOUS").content.map { it.id } shouldContainExactly listOf(accept.id, gore.id)
            list("musicbrainzMatch=AMBIGUOUS").totalElements shouldBe 2
            list("musicbrainzMatch=EXACT").content.map { it.id } shouldContainExactly listOf(acceptance.id)
            list("musicbrainzMatch=NONE").totalElements shouldBe 0
            list("musicbrainzMatch=UNCHECKED").content.map { it.id } shouldContainExactly listOf(unchecked.id)
            list("musicbrainzMatch=AMBIGUOUS&name=accept").content.map { it.id } shouldContainExactly listOf(accept.id)
            list("musicbrainzMatch=AMBIGUOUS&name=accept").totalElements shouldBe 1
        }
    }

    @Test
    fun `GET artists with upcomingWithinDays keeps the acts billed from today to that day, a festival each day, no cancelled or postponed night`() {
        val today = LocalDate.now(ZoneId.of("Europe/Berlin"))
        val venueId = runBlocking { venue() }
        val acts =
            listOf(
                "Today Act",
                "Last Day Act",
                "Day After Act",
                "Yesterday Act",
                "Festival Act",
                "Past Festival Act",
                "Cancelled Act",
                "Postponed Act",
                "Relocated Act",
                "Unbilled Act"
            ).associateWith { createArtist(ArtistRequestFixtures.create(name = it)) }
        runBlocking {
            event(venueId, "today", today, acts.getValue("Today Act"))
            event(venueId, "last-day", today.plusDays(14), acts.getValue("Last Day Act"))
            event(venueId, "day-after", today.plusDays(15), acts.getValue("Day After Act"))
            event(venueId, "yesterday", today.minusDays(1), acts.getValue("Yesterday Act"))
            event(venueId, "festival", today.minusDays(3), acts.getValue("Festival Act"), endDate = today.plusDays(1))
            event(venueId, "past-festival", today.minusDays(3), acts.getValue("Past Festival Act"), endDate = today.minusDays(1))
            event(venueId, "cancelled", today.plusDays(1), acts.getValue("Cancelled Act"), status = "CANCELLED")
            event(venueId, "postponed", today.plusDays(1), acts.getValue("Postponed Act"), status = "POSTPONED")
            event(venueId, "relocated", today.plusDays(1), acts.getValue("Relocated Act"), status = "RELOCATED")
            // A second night for the same act must not list it twice.
            event(venueId, "today-again", today.plusDays(2), acts.getValue("Today Act"))
        }
        verdict(acts.getValue("Festival Act"), MusicBrainzMatch.AMBIGUOUS)
        verdict(acts.getValue("Unbilled Act"), MusicBrainzMatch.AMBIGUOUS)

        fun names(query: String) = list(query).content.map { it.name }

        assertSoftly {
            names("upcomingWithinDays=14") shouldContainExactly listOf("Festival Act", "Last Day Act", "Relocated Act", "Today Act")
            list("upcomingWithinDays=14").totalElements shouldBe 4
            names("upcomingWithinDays=0") shouldContainExactly listOf("Festival Act", "Today Act")
            names("upcomingWithinDays=14&musicbrainzMatch=AMBIGUOUS") shouldContainExactly listOf("Festival Act")
            names("upcomingWithinDays=14&name=last") shouldContainExactly listOf("Last Day Act")
            names("upcomingWithinDays=14&sort=name,desc") shouldContainExactly listOf("Today Act", "Relocated Act", "Last Day Act", "Festival Act")
        }
        list("upcomingWithinDays=14&page=1", size = 2).content.map { it.name } shouldContainExactly listOf("Relocated Act", "Today Act")
    }

    @Test
    fun `GET artists with upcomingWithinDays and nobody billed answers an empty page`() {
        createArtist(ArtistRequestFixtures.create(name = "Unbilled Act"))

        val page = list("upcomingWithinDays=14")
        page.content shouldBe emptyList()
        page.totalElements shouldBe 0
    }

    @Test
    fun `GET artists rejects an upcomingWithinDays out of range and an unknown verdict with 400`() {
        listOf("upcomingWithinDays=-1", "upcomingWithinDays=91", "upcomingWithinDays=soon", "musicbrainzMatch=MAYBE").forEach { query ->
            webTestClient
                .get()
                .uri("/api/admin/artists?$query")
                .exchange()
                .expectStatus()
                .isBadRequest
        }
    }

    @Test
    fun `PUT musicbrainz-id stores an EXACT match the sweep keeps, and leaves every other field as it was`() {
        val created =
            createArtist(
                ArtistRequestFixtures
                    .adicts(imageUrl = "https://example.com/adicts.jpg", instagramUrl = "https://www.instagram.com/theadictsofficial/")
                    .copy(
                        imageAttribution = "Photographer, via Wikimedia Commons",
                        imageLicenceId = "CC-BY-SA-4.0",
                        imageSourceUrl = "https://commons.wikimedia.org/wiki/File:Adicts.jpg",
                        bandcampUrl = "https://theadicts.bandcamp.com/"
                    )
            )
        verdict(created, MusicBrainzMatch.AMBIGUOUS)
        val before = getArtist(created.id)

        val after = putMusicBrainzId(created.id, MBID)

        after.musicbrainzMatch shouldBe MusicBrainzMatch.EXACT
        after.musicbrainzId shouldBe MBID
        getArtist(created.id) shouldBe after
        // Everything but the verdict, its timestamp and the row's own stamp is what it was.
        after.copy(musicbrainzId = null, musicbrainzMatch = before.musicbrainzMatch, musicbrainzCheckedAt = null, updatedAt = null) shouldBe
            before.copy(musicbrainzCheckedAt = null, updatedAt = null)
        runBlocking {
            artistRepository.findNeedingMusicBrainzLookup(listOf(created.id)).toList() shouldBe emptyList()
            artistRepository.findNeedingMusicBrainzEnrichment(listOf(created.id)).toList().map { it.id } shouldBe listOf(created.id)
        }

        // The same id again changes nothing.
        putMusicBrainzId(created.id, MBID) shouldBe after
        // Another id replaces the first.
        putMusicBrainzId(created.id, OTHER_MBID).musicbrainzId shouldBe OTHER_MBID
    }

    @Test
    fun `PUT musicbrainz-id answers 404 for an unknown artist and 400 for a missing or malformed id`() {
        val created = createArtist()

        webTestClient
            .put()
            .uri("/api/admin/artists/99999/musicbrainz-id")
            .bodyValue(mapOf("musicbrainzId" to MBID))
            .exchange()
            .expectStatus()
            .isNotFound
        val malformed = listOf(emptyMap(), mapOf("musicbrainzId" to null), mapOf("musicbrainzId" to MBID.uppercase()), mapOf("musicbrainzId" to "not-an-mbid"))
        malformed.forEach { body ->
            webTestClient
                .put()
                .uri("/api/admin/artists/${created.id}/musicbrainz-id")
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isBadRequest
        }
        getArtist(created.id).musicbrainzMatch shouldBe MusicBrainzMatch.UNCHECKED
    }

    private fun getArtist(id: Long): ArtistResponse =
        webTestClient
            .get()
            .uri("/api/admin/artists/$id")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<ArtistResponse>()
            .returnResult()
            .responseBody!!

    private fun putMusicBrainzId(
        id: Long,
        mbid: String
    ): ArtistResponse =
        webTestClient
            .put()
            .uri("/api/admin/artists/$id/musicbrainz-id")
            .bodyValue(MusicBrainzIdRequest(mbid))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<ArtistResponse>()
            .returnResult()
            .responseBody!!

    private suspend fun venue(): Long =
        databaseClient
            .sql("INSERT INTO events.venue (name, slug) VALUES ('Arena', 'arena') RETURNING id")
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun event(
        venueId: Long,
        sourceId: String,
        date: LocalDate,
        artist: ArtistResponse,
        endDate: LocalDate? = null,
        status: String = "SCHEDULED"
    ) {
        databaseClient
            .sql(
                "INSERT INTO events.event (venue_id, title, slug, event_date, end_date, source_id, status) " +
                    "VALUES (:venueId, :sourceId, :sourceId, :date, :endDate, :sourceId, :status)"
            ).bind("venueId", venueId)
            .bind("sourceId", sourceId)
            .bind("date", date)
            .let { spec -> endDate?.let { spec.bind("endDate", it) } ?: spec.bindNull("endDate", LocalDate::class.java) }
            .bind("status", status)
            .await()
        databaseClient
            .sql(
                "INSERT INTO events.event_artist (event_id, artist_id) " +
                    "SELECT e.id, :artistId FROM events.event e WHERE e.source_id = :sourceId"
            ).bind("artistId", artist.id)
            .bind("sourceId", sourceId)
            .await()
    }

    private companion object {
        const val MBID = "06e3bce0-c612-4a5f-b095-9ffed1e4a656"
        const val OTHER_MBID = "5b11f4ce-a62d-471e-81fc-a69a8278c7da"
    }
}
