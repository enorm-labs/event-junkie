package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.awaitRowsUpdated
import java.time.LocalDate

/** Each line-up artist carries the Bandcamp and SoundCloud links the event page offers beside the act (#2723). */
class EventLineupListenLinksTest : BaseControllerTest() {
    @Test
    fun `GET event by slug carries each line-up artist's Bandcamp and SoundCloud links, and none where the artist has none`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            val headliner = insertArtist("The Adicts", "the-adicts")
            val support = insertArtist("Maid Of Ace", "maid-of-ace")
            val eventId = insertEvent(venueId, "The Adicts", "the-adicts-live", LocalDate.now(ClockConfiguration.BERLIN))
            linkArtist(eventId, headliner, role = "HEADLINER", billingOrder = 0)
            linkArtist(eventId, support, role = "SUPPORT", billingOrder = 1)
            databaseClient
                .sql(
                    """
                    UPDATE events.artist
                    SET bandcamp_url = 'https://theadicts.bandcamp.com/', soundcloud_url = 'https://soundcloud.com/the-adicts'
                    WHERE id = :id
                    """.trimIndent()
                ).bind("id", headliner)
                .fetch()
                .awaitRowsUpdated()

            webTestClient
                .get()
                .uri("/events/the-adicts-live")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.lineup[0].artist.bandcampUrl")
                .isEqualTo("https://theadicts.bandcamp.com/")
                .jsonPath("$.lineup[0].artist.soundcloudUrl")
                .isEqualTo("https://soundcloud.com/the-adicts")
                .jsonPath("$.lineup[1].artist.slug")
                .isEqualTo("maid-of-ace")
                .jsonPath("$.lineup[1].artist.bandcampUrl")
                .doesNotExist()
                .jsonPath("$.lineup[1].artist.soundcloudUrl")
                .doesNotExist()
        }
}
