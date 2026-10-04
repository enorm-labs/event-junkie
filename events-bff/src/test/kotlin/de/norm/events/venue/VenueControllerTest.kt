package de.norm.events.venue

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

class VenueControllerTest : BaseControllerTest() {
    @Test
    fun `GET venues lists venues with pagination metadata`(): Unit =
        runBlocking {
            insertVenue("Astra", "astra")
            insertVenue("Lido", "lido")

            webTestClient
                .get()
                .uri("/venues")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                .jsonPath("$.content.length()")
                .isEqualTo(2)
                // default sort by name asc
                .jsonPath("$.content[0].slug")
                .isEqualTo("astra")
        }

    @Test
    fun `GET venues filters by case-insensitive name query`(): Unit =
        runBlocking {
            insertVenue("Astra Kulturhaus", "astra")
            insertVenue("Lido", "lido")

            webTestClient
                .get()
                .uri("/venues?q=astra")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("astra")
        }

    @Test
    fun `GET venues filters by district, any of several`(): Unit =
        runBlocking {
            insertVenue("Astra", "astra", district = "kreuzberg")
            insertVenue("Lido", "lido", district = "kreuzberg")
            insertVenue("Berghain", "berghain", district = "mitte")

            webTestClient
                .get()
                .uri("/venues?district=mitte")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("berghain")

            webTestClient
                .get()
                .uri("/venues?district=mitte&district=kreuzberg&district=unknown")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[*].slug")
                .isEqualTo(listOf("astra", "berghain", "lido"))
        }

    @Test
    fun `GET venues combines name query and district filter`(): Unit =
        runBlocking {
            insertVenue("Astra Kulturhaus", "astra", district = "kreuzberg")
            insertVenue("Astra Bar", "astra-bar", district = "mitte")
            insertVenue("Lido", "lido", district = "kreuzberg")

            webTestClient
                .get()
                .uri("/venues?q=astra&district=kreuzberg")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("astra")
        }

    @Test
    fun `GET venues ignores an unknown or malicious sort parameter`(): Unit =
        runBlocking {
            insertVenue("Astra", "astra")
            insertVenue("Lido", "lido")

            // Swagger UI's array placeholder `["string"]` and an injection attempt both contain
            // characters Spring Data rejects; without the sort whitelist these would surface as a
            // 500. They are dropped, so the request succeeds with the default name-ascending order.
            for (sort in listOf("""["string"]""", "name; DROP TABLE events.venue;--")) {
                webTestClient
                    .get()
                    .uri("/venues?sort={sort}", sort)
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.totalElements")
                    .isEqualTo(2)
                    .jsonPath("$.content[0].slug")
                    .isEqualTo("astra")
            }
        }

    @Test
    fun `GET venues honours a whitelisted sort property`(): Unit =
        runBlocking {
            insertVenue("Astra", "astra")
            insertVenue("Lido", "lido")

            webTestClient
                .get()
                .uri("/venues?sort=name,desc")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].slug")
                .isEqualTo("lido")
        }

    // The collation is `C`; without case-folding "tipi" would sort after "Zenner".
    @Test
    fun `GET venues sorts names case-insensitively`(): Unit =
        runBlocking {
            insertVenue("Zenner", "zenner")
            insertVenue("tipi am Kanzleramt", "tipi")

            webTestClient
                .get()
                .uri("/venues")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].slug")
                .isEqualTo("tipi")
        }

    // #360: the count is events from today on; a venue with only past events sits at 0, and the
    // filters still apply under the count sort.
    @Test
    fun `GET venues sorts by upcoming events and counts only events from today on`(): Unit =
        runBlocking {
            val busy = insertVenue("Lido", "lido", district = "kreuzberg")
            val spent = insertVenue("Astra", "astra", district = "friedrichshain")
            val quiet = insertVenue("Bi Nuu", "bi-nuu", district = "kreuzberg")
            val today = LocalDate.now(ClockConfiguration.BERLIN)
            insertEvent(busy, "Tonight", "tonight", today)
            insertEvent(busy, "Next week", "next-week", today.plusDays(7))
            insertEvent(busy, "Last year", "last-year", today.minusYears(1))
            insertEvent(spent, "Yesterday", "yesterday", today.minusDays(1))
            insertEvent(quiet, "Tomorrow", "tomorrow", today.plusDays(1))

            webTestClient
                .get()
                .uri("/venues?sort=upcomingEvents,desc")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].slug")
                .isEqualTo("lido")
                .jsonPath("$.content[0].upcomingEventCount")
                .isEqualTo(2)
                .jsonPath("$.content[1].slug")
                .isEqualTo("bi-nuu")
                .jsonPath("$.content[1].upcomingEventCount")
                .isEqualTo(1)
                .jsonPath("$.content[2].slug")
                .isEqualTo("astra")
                .jsonPath("$.content[2].upcomingEventCount")
                .isEqualTo(0)

            webTestClient
                .get()
                .uri("/venues?sort=upcomingEvents,desc&district=kreuzberg&q=nuu")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("bi-nuu")
        }

    @Test
    fun `GET venue by slug returns detail`(): Unit =
        runBlocking {
            insertVenue(
                "Astra",
                "astra",
                address = "Revaler Str. 99",
                description = "A large concert hall on the RAW-Gelände in Friedrichshain."
            )

            webTestClient
                .get()
                .uri("/venues/astra")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("astra")
                .jsonPath("$.name")
                .isEqualTo("Astra")
                .jsonPath("$.address")
                .isEqualTo("Revaler Str. 99")
                .jsonPath("$.description")
                .isEqualTo("A large concert hall on the RAW-Gelände in Friedrichshain.")
        }

    @Test
    fun `GET venue by unknown slug returns 404`() {
        webTestClient
            .get()
            .uri("/venues/nope")
            .exchange()
            .expectStatus()
            .isNotFound
    }
}
