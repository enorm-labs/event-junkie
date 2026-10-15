package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** `GET /events/{slug}/related`: upcoming events of the same kind sharing artists, the venue or genre tags (#359, #3337). */
class EventRelatedTest : BaseControllerTest() {
    /** The BFF's day, not the runner's: they differ between midnight in Berlin and midnight UTC. */
    private val today: LocalDate = LocalDate.now(ClockConfiguration.BERLIN)

    @Test
    fun `ranks a shared artist above a genre at another venue, and that genre above the venue alone`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            val sameHeaven = insertVenue("SameHeaven", "sameheaven")
            val artist = insertArtist("Sam Prekop", "sam-prekop")
            val ambient = insertGenreTag("Ambient", "ambient")
            val rock = insertGenreTag("Rock", "rock")

            val event = insertEvent(lido, "Source", "source", today.plusDays(1))
            linkArtist(event, artist)
            linkGenre(event, ambient)
            // Another tagged event, so Ambient is not on every tagged event and outweighs the venue.
            linkGenre(insertEvent(sameHeaven, "Tagged", "tagged", today.plusDays(1), eventType = "SCREENING"), rock)

            // Inserted in reverse rank, with the best match latest, so the order is not the insert order.
            insertEvent(lido, "Same Venue", "same-venue", today.plusDays(2))
            linkGenre(insertEvent(sameHeaven, "Same Genre", "same-genre", today.plusDays(3)), ambient)
            linkArtist(insertEvent(sameHeaven, "Same Artist", "same-artist", today.plusDays(4)), artist)
            insertEvent(sameHeaven, "Unrelated", "unrelated", today.plusDays(1))

            relatedSlugs().isEqualTo(listOf("same-artist", "same-genre", "same-venue"))
        }

    @Test
    fun `weighs a rare genre above a common one, counting only the events that are not over`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            val sameHeaven = insertVenue("SameHeaven", "sameheaven")
            val postHardcore = insertGenreTag("Post Hardcore", "post-hardcore")
            val rock = insertGenreTag("Rock", "rock")

            val event = insertEvent(lido, "Source", "source", today.plusDays(1))
            linkGenre(event, postHardcore)
            linkGenre(event, rock)

            linkGenre(insertEvent(sameHeaven, "Rock 1", "rock-1", today.plusDays(2)), rock)
            linkGenre(insertEvent(sameHeaven, "Rare", "rare", today.plusDays(5)), postHardcore)
            linkGenre(insertEvent(sameHeaven, "Rock 2", "rock-2", today.plusDays(6)), rock)
            linkGenre(insertEvent(sameHeaven, "Rock 3", "rock-3", today.plusDays(7)), rock)
            // Counted, these would make Post Hardcore the commoner tag.
            (1..3).forEach { linkGenre(insertEvent(sameHeaven, "Past $it", "past-$it", today.minusDays(it + 1L)), postHardcore) }

            relatedSlugs().isEqualTo(listOf("rare", "rock-1", "rock-2", "rock-3"))
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

            val bothGenres = insertEvent(sameHeaven, "Both Genres", "both-genres", today.plusDays(5))
            linkGenre(bothGenres, ambient)
            linkGenre(bothGenres, drone)
            insertEvent(lido, "Same Venue", "same-venue", today.plusDays(4))
            // Drone is on every tagged event, so it weighs as much as the venue, and the earlier date leads.
            linkGenre(insertEvent(sameHeaven, "Drone Elsewhere", "drone-elsewhere", today.plusDays(3)), drone)
            linkGenre(insertEvent(lido, "Venue And Genre", "venue-and-genre", today.plusDays(9)), drone)

            relatedSlugs().isEqualTo(listOf("both-genres", "venue-and-genre", "drone-elsewhere", "same-venue"))
        }

    @Test
    fun `suggests only the same kind of event, the exact type first`(): Unit =
        runBlocking {
            val festsaal = insertVenue("Festsaal", "festsaal")
            insertEvent(festsaal, "Podcast Live", "source", today.plusDays(1), eventType = "SHOW")

            insertEvent(festsaal, "Concert", "concert", today.plusDays(1))
            insertEvent(festsaal, "Film", "film", today.plusDays(2), eventType = "SCREENING")
            insertEvent(festsaal, "Stand-up", "stand-up", today.plusDays(3), eventType = "COMEDY")
            insertEvent(festsaal, "Another Show", "another-show", today.plusDays(4), eventType = "SHOW")

            relatedSlugs().isEqualTo(listOf("another-show", "stand-up"))
        }

    @Test
    fun `keeps a shared artist out when the event is of another kind`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            val artist = insertArtist("Sam Prekop", "sam-prekop")
            linkArtist(insertEvent(lido, "Source", "source", today.plusDays(1)), artist)
            linkArtist(insertEvent(lido, "Reading", "reading", today.plusDays(2), eventType = "READING"), artist)
            insertEvent(lido, "Party", "party", today.plusDays(3), eventType = "PARTY")

            relatedSlugs().isEqualTo(listOf("party"))
        }

    @Test
    fun `matches an event of no known kind only with another`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            insertEvent(lido, "Source", "source", today.plusDays(1), eventType = "OTHER")
            insertEvent(lido, "Concert", "concert", today.plusDays(2))
            insertEvent(lido, "Market", "market", today.plusDays(3), eventType = "OTHER")

            relatedSlugs().isEqualTo(listOf("market"))
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

    private fun relatedSlugs() =
        webTestClient
            .get()
            .uri("/events/source/related")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[*].slug")
}
