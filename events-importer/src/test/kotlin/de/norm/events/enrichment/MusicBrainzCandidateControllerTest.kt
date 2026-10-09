package de.norm.events.enrichment

import de.norm.events.GlobalExceptionHandler
import de.norm.events.artist.Artist
import de.norm.events.artist.ArtistNotFoundException
import de.norm.events.artist.ArtistResponse
import de.norm.events.artist.ArtistService
import de.norm.events.musicbrainz.CandidateNameMatch
import de.norm.events.musicbrainz.MusicBrainzAlias
import de.norm.events.musicbrainz.MusicBrainzArea
import de.norm.events.musicbrainz.MusicBrainzCandidate
import de.norm.events.musicbrainz.MusicBrainzClient
import de.norm.events.musicbrainz.MusicBrainzLifeSpan
import de.norm.events.musicbrainz.MusicBrainzUnavailableException
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody

/**
 * The candidates endpoint with MusicBrainz replaced by a scripted client: tests never call
 * musicbrainz.org, whose limit is one caller per address.
 */
class MusicBrainzCandidateControllerTest {
    private val artistService = mockk<ArtistService>()
    private val client = mockk<MusicBrainzClient>()
    private val webTestClient =
        WebTestClient
            .bindToController(MusicBrainzCandidateController(artistService, client))
            .controllerAdvice(GlobalExceptionHandler())
            .build()

    private fun artist(name: String) = ArtistResponse.fromDomain(Artist(id = ARTIST_ID, name = name, slug = name.lowercase()))

    private fun candidates(): List<MusicBrainzCandidateResponse> =
        webTestClient
            .get()
            .uri("/api/admin/artists/$ARTIST_ID/musicbrainz-candidates")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<List<MusicBrainzCandidateResponse>>()
            .returnResult()
            .responseBody!!

    @Test
    fun `lists the candidates with an equal name first, and passes on an ensemble's dates but never a person's`() {
        coEvery { artistService.findById(ARTIST_ID) } returns artist("Accept")
        coEvery { client.search("Accept") } returns
            listOf(
                MusicBrainzCandidate(id = "mb-similar", name = "Acceptance", type = "Group"),
                MusicBrainzCandidate(
                    id = "mb-alias",
                    name = "Akzept",
                    aliases = listOf(MusicBrainzAlias("Accept")),
                    type = "Person",
                    lifeSpan = MusicBrainzLifeSpan("1961-02-03")
                ),
                MusicBrainzCandidate(
                    id = "mb-de",
                    name = "Accept",
                    country = "DE",
                    type = "Group",
                    disambiguation = "German heavy metal band",
                    area = MusicBrainzArea("Germany"),
                    lifeSpan = MusicBrainzLifeSpan(begin = "1976", end = "1989")
                ),
                MusicBrainzCandidate(id = "mb-person", name = "ACCEPT", type = "Person", disambiguation = " ", lifeSpan = MusicBrainzLifeSpan("1980-04-01")),
                MusicBrainzCandidate(id = "mb-untyped", name = "Accept", lifeSpan = MusicBrainzLifeSpan("1990"))
            )

        val listed = candidates()

        listed.map { it.musicbrainzId to it.nameMatch } shouldBe
            listOf(
                "mb-de" to CandidateNameMatch.NAME,
                "mb-person" to CandidateNameMatch.NAME,
                "mb-untyped" to CandidateNameMatch.NAME,
                "mb-alias" to CandidateNameMatch.OTHER_NAME,
                "mb-similar" to CandidateNameMatch.NONE
            )
        listed.first() shouldBe
            MusicBrainzCandidateResponse(
                musicbrainzId = "mb-de",
                name = "Accept",
                nameMatch = CandidateNameMatch.NAME,
                type = "Group",
                disambiguation = "German heavy metal band",
                country = "DE",
                area = "Germany",
                founded = "1976",
                dissolved = "1989",
                url = "https://musicbrainz.org/artist/mb-de"
            )
        // A person's life span is a birth date; an untyped candidate may be a person.
        listed.filter { it.type != "Group" }.map { it.founded } shouldBe listOf(null, null, null)
        listed.single { it.musicbrainzId == "mb-person" }.disambiguation shouldBe null
    }

    @Test
    fun `answers an empty list when MusicBrainz knows no such name`() {
        coEvery { artistService.findById(ARTIST_ID) } returns artist("Kein Bock auf Nazis")
        coEvery { client.search("Kein Bock auf Nazis") } returns emptyList()

        candidates() shouldBe emptyList()
    }

    @Test
    fun `answers 404 for an unknown artist without asking MusicBrainz`() {
        coEvery { artistService.findById(ARTIST_ID) } throws ArtistNotFoundException(ARTIST_ID)

        webTestClient
            .get()
            .uri("/api/admin/artists/$ARTIST_ID/musicbrainz-candidates")
            .exchange()
            .expectStatus()
            .isNotFound
        coVerify(exactly = 0) { client.search(any()) }
    }

    @Test
    fun `answers 503 when MusicBrainz does not answer`() {
        coEvery { artistService.findById(ARTIST_ID) } returns artist("Accept")
        coEvery { client.search("Accept") } throws MusicBrainzUnavailableException("MusicBrainz answered 503 4 times for 'Accept'")

        webTestClient
            .get()
            .uri("/api/admin/artists/$ARTIST_ID/musicbrainz-candidates")
            .exchange()
            .expectStatus()
            .isEqualTo(503)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("MusicBrainz did not answer. Try again in a minute.")
    }

    private companion object {
        const val ARTIST_ID = 7L
    }
}
