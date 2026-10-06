package de.norm.events.promoter

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PromoterControllerTest : BaseControllerTest() {
    @Test
    fun `GET promoters lists promoters with pagination metadata`(): Unit =
        runBlocking {
            insertPromoter("36 Concerts", "36-concerts")
            insertPromoter("Goodlive", "goodlive")

            webTestClient
                .get()
                .uri("/promoters")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                // default sort by name asc
                .jsonPath("$.content[0].slug")
                .isEqualTo("36-concerts")
        }

    @Test
    fun `GET promoters filters by case-insensitive name query`(): Unit =
        runBlocking {
            insertPromoter("36 Concerts", "36-concerts")
            insertPromoter("Goodlive", "goodlive")

            webTestClient
                .get()
                .uri("/promoters?q=good")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("goodlive")
        }

    @Test
    fun `GET promoters ignores an unknown or malicious sort parameter`(): Unit =
        runBlocking {
            insertPromoter("36 Concerts", "36-concerts")
            insertPromoter("Goodlive", "goodlive")

            // Swagger UI's array placeholder `["string"]` and an injection attempt both contain
            // characters Spring Data rejects; without the sort whitelist these would surface as a
            // 500. They are dropped, so the request succeeds with the default name-ascending order.
            for (sort in listOf("""["string"]""", "name; DROP TABLE events.promoter;--")) {
                webTestClient
                    .get()
                    .uri("/promoters?sort={sort}", sort)
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.totalElements")
                    .isEqualTo(2)
                    .jsonPath("$.content[0].slug")
                    .isEqualTo("36-concerts")
            }
        }

    // #2763: Z–A folds case as A–Z does; under the `C` collation "kiezklang" would come before "Trinity Music".
    @Test
    fun `GET promoters sorts names Z to A case-insensitively`(): Unit =
        runBlocking {
            insertPromoter("36 Concerts", "36-concerts")
            insertPromoter("kiezklang", "kiezklang")
            insertPromoter("Trinity Music", "trinity-music")

            webTestClient
                .get()
                .uri("/promoters?sort=name,desc")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[*].slug")
                .isEqualTo(listOf("trinity-music", "kiezklang", "36-concerts"))
        }

    // #1349: the count is events from today on, and the sort is the next 30 days (#2694); a promoter with only past events sits at 0, and a
    // name filter still applies under the count sort.
    @Test
    fun `GET promoters sorts by upcoming events and counts only events from today on`(): Unit =
        runBlocking {
            val venue = insertVenue("Lido", "lido")
            val busy = insertPromoter("Trinity Music", "trinity-music")
            val spent = insertPromoter("Bygone Concerts", "bygone-concerts")
            val quiet = insertPromoter("Quiet Agency", "quiet-agency")
            val today = LocalDate.now(ClockConfiguration.BERLIN)
            linkPromoter(insertEvent(venue, "Tonight", "tonight", today), busy)
            linkPromoter(insertEvent(venue, "Next week", "next-week", today.plusDays(7)), busy)
            linkPromoter(insertEvent(venue, "In two days", "in-two-days", today.plusDays(2)), busy)
            linkPromoter(insertEvent(venue, "Last year", "last-year", today.minusYears(1)), busy)
            linkPromoter(insertEvent(venue, "Yesterday", "yesterday", today.minusDays(1)), spent)
            linkPromoter(insertEvent(venue, "Tomorrow", "tomorrow", today.plusDays(1)), quiet)

            webTestClient
                .get()
                .uri("/promoters?sort=upcomingEvents,desc")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].slug")
                .isEqualTo("trinity-music")
                .jsonPath("$.content[0].upcomingEventCount")
                .isEqualTo(3)
                .jsonPath("$.content[1].slug")
                .isEqualTo("quiet-agency")
                .jsonPath("$.content[1].upcomingEventCount")
                .isEqualTo(1)
                .jsonPath("$.content[2].slug")
                .isEqualTo("bygone-concerts")
                .jsonPath("$.content[2].upcomingEventCount")
                .isEqualTo(0)

            webTestClient
                .get()
                .uri("/promoters?sort=upcomingEvents,desc&q=agency")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("quiet-agency")
        }

    // #2694: a promoter that publishes far ahead sorts below a busier one; the sort counts the next 30 days only.
    @Test
    fun `GET promoters sorts by events in the next 30 days, not by the whole horizon`(): Unit =
        runBlocking {
            val venue = insertVenue("Lido", "lido")
            val farAhead = insertPromoter("Far Ahead Concerts", "far-ahead-concerts")
            val busy = insertPromoter("Busy Agency", "busy-agency")
            val today = LocalDate.now(ClockConfiguration.BERLIN)
            linkPromoter(insertEvent(venue, "Soon", "soon", today.plusDays(3)), farAhead)
            (40L..44L).forEach { linkPromoter(insertEvent(venue, "Later $it", "later-$it", today.plusDays(it)), farAhead) }
            listOf(1L, 10L, 29L).forEach { linkPromoter(insertEvent(venue, "Gig $it", "gig-$it", today.plusDays(it)), busy) }
            linkPromoter(insertEvent(venue, "Past the window", "past-the-window", today.plusDays(31)), busy)

            webTestClient
                .get()
                .uri("/promoters?sort=upcomingEvents,desc")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].slug")
                .isEqualTo("busy-agency")
                .jsonPath("$.content[0].upcomingNext30DaysCount")
                .isEqualTo(3)
                .jsonPath("$.content[0].upcomingEventCount")
                .isEqualTo(4)
                .jsonPath("$.content[1].slug")
                .isEqualTo("far-ahead-concerts")
                .jsonPath("$.content[1].upcomingNext30DaysCount")
                .isEqualTo(1)
                .jsonPath("$.content[1].upcomingEventCount")
                .isEqualTo(6)
        }

    // #2694: production listed "Berlinmusiker.de" before "ATOK Berlin" with A–Z pressed.
    @Test
    fun `GET promoters with a search sorts by name when asked, and by relevance only without a sort`(): Unit =
        runBlocking {
            insertPromoter("Berlinmusiker.de", "berlinmusiker-de")
            insertPromoter("ATOK Berlin", "atok-berlin")

            webTestClient
                .get()
                .uri("/promoters?q=berlin&sort=name")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[*].slug")
                .isEqualTo(listOf("atok-berlin", "berlinmusiker-de"))

            webTestClient
                .get()
                .uri("/promoters?q=berlin")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[*].slug")
                .isEqualTo(listOf("berlinmusiker-de", "atok-berlin"))
        }

    @Test
    fun `GET promoters treats a wildcard in the query as a letter`(): Unit =
        runBlocking {
            insertPromoter("100% Live", "100-live")
            insertPromoter("Goodlive", "goodlive")

            webTestClient
                .get()
                .uri("/promoters?q={q}", "%")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("100-live")
        }

    @Test
    fun `GET promoters carries the description fields a card reads`(): Unit =
        runBlocking {
            insertPromoter(
                "36 Concerts",
                "36-concerts",
                description = "The in-house agency of Lido, Astra and Bi Nuu.",
                descriptionLanguage = "en",
                descriptionAlt = "Die Hausagentur von Lido, Astra und Bi Nuu.",
                descriptionAltLanguage = "de"
            )

            webTestClient
                .get()
                .uri("/promoters")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].descriptionLanguage")
                .isEqualTo("en")
                .jsonPath("$.content[0].descriptionAlt")
                .isEqualTo("Die Hausagentur von Lido, Astra und Bi Nuu.")
                .jsonPath("$.content[0].upcomingEventCount")
                .isEqualTo(0)
        }

    @Test
    fun `GET promoter by slug returns detail`(): Unit =
        runBlocking {
            insertPromoter(
                "36 Concerts",
                "36-concerts",
                description = "The in-house agency of Lido, Astra and Bi Nuu.",
                descriptionLanguage = "en",
                descriptionAlt = "Die Hausagentur von Lido, Astra und Bi Nuu.",
                descriptionAltLanguage = "de"
            )

            webTestClient
                .get()
                .uri("/promoters/36-concerts")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("36-concerts")
                .jsonPath("$.name")
                .isEqualTo("36 Concerts")
                .jsonPath("$.description")
                .isEqualTo("The in-house agency of Lido, Astra and Bi Nuu.")
                .jsonPath("$.descriptionLanguage")
                .isEqualTo("en")
                .jsonPath("$.descriptionAlt")
                .isEqualTo("Die Hausagentur von Lido, Astra und Bi Nuu.")
                .jsonPath("$.descriptionAltLanguage")
                .isEqualTo("de")
        }

    @Test
    fun `GET promoter by unknown slug returns 404`() {
        webTestClient
            .get()
            .uri("/promoters/nope")
            .exchange()
            .expectStatus()
            .isNotFound
    }
}
