package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** `GET /events/{slug}/related`: upcoming events sharing artists, the venue or genre tags (#359). */
class EventRelatedTest : BaseControllerTest() {
    /** The BFF's day, not the runner's: they differ between midnight in Berlin and midnight UTC. */
    private val today: LocalDate = LocalDate.now(ClockConfiguration.BERLIN)

    @Test
    fun `ranks a shared artist above the venue, and the venue above a genre`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            val sameHeaven = insertVenue("SameHeaven", "sameheaven")
            val artist = insertArtist("Sam Prekop", "sam-prekop")
            val ambient = insertGenreTag("Ambient", "ambient")

            val event = insertEvent(lido, "Source", "source", today.plusDays(1))
            linkArtist(event, artist)
            linkGenre(event, ambient)

            // Inserted in reverse rank, with the best match latest, so the order is not the insert order.
            linkGenre(insertEvent(sameHeaven, "Same Genre", "same-genre", today.plusDays(2)), ambient)
            insertEvent(lido, "Same Venue", "same-venue", today.plusDays(3))
            linkArtist(insertEvent(sameHeaven, "Same Artist", "same-artist", today.plusDays(4)), artist)
            insertEvent(sameHeaven, "Unrelated", "unrelated", today.plusDays(1))

            webTestClient
                .get()
                .uri("/events/source/related")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$[*].slug")
                .isEqualTo(listOf("same-artist", "same-venue", "same-genre"))
                .jsonPath("$[0].venue.slug")
                .isEqualTo("sameheaven")
        }

    @Test
    fun `adds up every match, and breaks a tie by date`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            val sameHeaven = insertVenue("SameHeaven", "sameheaven")
            val ambient = insertGenreTag("Ambient", "ambient")
            val drone = insertGenreTag("Drone", "drone")

            val event = insertEvent(lido, "Source", "source", today.plusDays(1))
            linkGenre(event, ambient)
            linkGenre(event, drone)

            // Two shared genres (2) tie with the venue (2); the earlier date leads.
            val bothGenres = insertEvent(sameHeaven, "Both Genres", "both-genres", today.plusDays(5))
            linkGenre(bothGenres, ambient)
            linkGenre(bothGenres, drone)
            insertEvent(lido, "Same Venue", "same-venue", today.plusDays(4))
            // The venue and a genre (3) beat both.
            linkGenre(insertEvent(lido, "Venue And Genre", "venue-and-genre", today.plusDays(9)), drone)

            webTestClient
                .get()
                .uri("/events/source/related")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$[*].slug")
                .isEqualTo(listOf("venue-and-genre", "same-venue", "both-genres"))
        }

    @Test
    fun `leaves out the event itself, past events, and anything past four`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            insertEvent(lido, "Source", "source", today.plusDays(1))
            insertEvent(lido, "Last Week", "last-week", today.minusDays(7))
            (1..5).forEach { insertEvent(lido, "Night $it", "night-$it", today.plusDays(it + 1L)) }

            webTestClient
                .get()
                .uri("/events/source/related")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$[*].slug")
                .isEqualTo(listOf("night-1", "night-2", "night-3", "night-4"))
        }

    @Test
    fun `answers a past event with what is still to come`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            insertEvent(lido, "Source", "source", today.minusDays(3))
            insertEvent(lido, "Next", "next", today.plusDays(3))

            webTestClient
                .get()
                .uri("/events/source/related")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$[*].slug")
                .isEqualTo(listOf("next"))
        }

    @Test
    fun `is an empty list when nothing matches`(): Unit =
        runBlocking {
            insertEvent(insertVenue("Lido", "lido"), "Source", "source", today.plusDays(1))
            insertEvent(insertVenue("SameHeaven", "sameheaven"), "Other", "other", today.plusDays(1))

            webTestClient
                .get()
                .uri("/events/source/related")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .json("[]")
        }

    @Test
    fun `is cached apart from the event's own detail`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            insertEvent(lido, "Source", "source", today.plusDays(1))
            insertEvent(lido, "First", "first", today.plusDays(2))

            webTestClient
                .get()
                .uri("/events/source")
                .exchange()
                .expectStatus()
                .isOk
            val related = {
                webTestClient
                    .get()
                    .uri("/events/source/related")
                    .exchange()
                    .expectBody()
                    .jsonPath("$[*].slug")
            }
            related().isEqualTo(listOf("first"))
            // Inside the TTL a new match stays unseen: the list came from the cache.
            insertEvent(lido, "Earlier", "earlier", today.plusDays(1))
            related().isEqualTo(listOf("first"))
        }

    @Test
    fun `is a 404 for an unknown slug`() {
        webTestClient
            .get()
            .uri("/events/no-such-event/related")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `rejects a query parameter`(): Unit =
        runBlocking {
            insertEvent(insertVenue("Lido", "lido"), "Source", "source", today.plusDays(1))

            webTestClient
                .get()
                .uri("/events/source/related?limit=10")
                .exchange()
                .expectStatus()
                .isBadRequest
        }
}
