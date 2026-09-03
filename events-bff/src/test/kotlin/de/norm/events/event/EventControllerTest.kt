package de.norm.events.event

import de.norm.events.BaseControllerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class EventControllerTest : BaseControllerTest() {
    @Test
    fun `GET events returns only upcoming events with pagination metadata`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Today Show", "today-show", LocalDate.now())
            insertEvent(venueId, "Future Show", "future-show", LocalDate.now().plusDays(5))
            insertEvent(venueId, "Past Show", "past-show", LocalDate.now().minusDays(5))

            webTestClient
                .get()
                .uri("/events?size=1")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                .jsonPath("$.totalPages")
                .isEqualTo(2)
                .jsonPath("$.page")
                .isEqualTo(0)
                .jsonPath("$.size")
                .isEqualTo(1)
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("today-show")
                .jsonPath("$.content[0].venue.slug")
                .isEqualTo("astra")
        }

    @Test
    fun `GET events clamps an oversized page size rather than serving it`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Today Show", "today-show", LocalDate.now())

            // The wiring is what this covers: WebFluxConfiguration constructs the resolver by hand,
            // so the cap reaches a request only if it is passed in. Spring's servlet-only
            // `spring.data.web.pageable.max-page-size` cannot do it here (#268).
            webTestClient
                .get()
                .uri("/events?size=5000")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.size")
                .isEqualTo(100)
        }

    @Test
    fun `GET events filters by venue slug`(): Unit =
        runBlocking {
            val astra = insertVenue("Astra", "astra")
            val lido = insertVenue("Lido", "lido")
            insertEvent(astra, "At Astra", "at-astra", LocalDate.now())
            insertEvent(lido, "At Lido", "at-lido", LocalDate.now())

            webTestClient
                .get()
                .uri("/events?venue=lido")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("at-lido")
        }

    @Test
    fun `GET events filters by district`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido", district = "friedrichshain-kreuzberg")
            val sameiden = insertVenue("SameHeaven", "sameheaven", district = "neukoelln")
            insertEvent(lido, "Kreuzberg Gig", "kreuzberg-gig", LocalDate.now())
            insertEvent(sameiden, "Neukölln Gig", "neukoelln-gig", LocalDate.now())

            webTestClient
                .get()
                .uri("/events?district=friedrichshain-kreuzberg")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("kreuzberg-gig")
                .jsonPath("$.content[0].venue.district")
                .isEqualTo("friedrichshain-kreuzberg")
        }

    @Test
    fun `GET events filters by genre slug and artist slug`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            val punk = insertGenreTag("Punk", "punk")
            val techno = insertGenreTag("Techno", "techno")
            val artist = insertArtist("The Adicts", "the-adicts")

            val punkEvent = insertEvent(venueId, "Punk Night", "punk-night", LocalDate.now())
            linkGenre(punkEvent, punk)
            linkArtist(punkEvent, artist)

            val technoEvent = insertEvent(venueId, "Techno Night", "techno-night", LocalDate.now())
            linkGenre(technoEvent, techno)

            webTestClient
                .get()
                .uri("/events?genre=punk")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("punk-night")
                .jsonPath("$.content[0].genreTags[0]")
                .isEqualTo("Punk")
                .jsonPath("$.content[0].artistNames[0]")
                .isEqualTo("The Adicts")

            webTestClient
                .get()
                .uri("/events?artist=the-adicts")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("punk-night")
        }

    @Test
    fun `GET events filters by price range and search query`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Cheap Gig", "cheap-gig", LocalDate.now(), pricePresale = BigDecimal("10.00"))
            insertEvent(venueId, "Pricey Gig", "pricey-gig", LocalDate.now(), pricePresale = BigDecimal("80.00"))

            webTestClient
                .get()
                .uri("/events?minPrice=50")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("pricey-gig")

            webTestClient
                .get()
                .uri("/events?q=cheap")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("cheap-gig")
        }

    @Test
    fun `GET events price filter falls back to box-office price when presale is unknown`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            // Presale known — matched on presale.
            insertEvent(venueId, "Presale Gig", "presale-gig", LocalDate.now(), pricePresale = BigDecimal("80.00"))
            // No presale, but a box-office price within range — matched via COALESCE fallback.
            insertEvent(venueId, "Door Gig", "door-gig", LocalDate.now(), priceBoxOffice = BigDecimal("60.00"))
            // Box-office price below the bound — excluded.
            insertEvent(venueId, "Door Cheap", "door-cheap", LocalDate.now(), priceBoxOffice = BigDecimal("20.00"))
            // No price at all — excluded by any bound.
            insertEvent(venueId, "Free Gig", "free-gig", LocalDate.now())

            webTestClient
                .get()
                .uri("/events?minPrice=50")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                .jsonPath("$.content[?(@.slug == 'door-gig')]")
                .exists()
                .jsonPath("$.content[?(@.slug == 'presale-gig')]")
                .exists()
                .jsonPath("$.content[?(@.slug == 'free-gig')]")
                .doesNotExist()
                .jsonPath("$.content[?(@.slug == 'door-cheap')]")
                .doesNotExist()
        }

    @Test
    fun `GET events excludes sold-out events only when excludeSoldOut is set`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Available Gig", "available-gig", LocalDate.now())
            insertEvent(venueId, "Sold Out Gig", "sold-out-gig", LocalDate.now(), soldOut = true)

            // Default: both events are returned.
            webTestClient
                .get()
                .uri("/events")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)

            // excludeSoldOut=true drops the sold-out event.
            webTestClient
                .get()
                .uri("/events?excludeSoldOut=true")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("available-gig")

            // excludeSoldOut=false imposes no constraint.
            webTestClient
                .get()
                .uri("/events?excludeSoldOut=false")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
        }

    @Test
    fun `GET events returns only free events when free is set`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Paid Gig", "paid-gig", LocalDate.now())
            insertEvent(venueId, "Free Gig", "free-gig", LocalDate.now(), free = true)

            // Default: both events are returned.
            webTestClient
                .get()
                .uri("/events")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)

            // free=true returns only the free event, and exposes the flag on the summary.
            webTestClient
                .get()
                .uri("/events?free=true")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("free-gig")
                .jsonPath("$.content[0].free")
                .isEqualTo(true)
        }

    @Test
    fun `GET events orders same-day events by start time`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            val date = LocalDate.now().plusDays(1)
            insertEvent(venueId, "Late Show", "late-show", date, startTime = LocalTime.of(22, 0))
            insertEvent(venueId, "Early Show", "early-show", date, startTime = LocalTime.of(18, 0))

            webTestClient
                .get()
                .uri("/events")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].slug")
                .isEqualTo("early-show")
                .jsonPath("$.content[1].slug")
                .isEqualTo("late-show")
        }

    @Test
    fun `GET events today returns only today's events`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Today", "today", LocalDate.now())
            insertEvent(venueId, "Tomorrow", "tomorrow", LocalDate.now().plusDays(1))

            webTestClient
                .get()
                .uri("/events/today")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].slug")
                .isEqualTo("today")
        }

    @Test
    fun `GET events calendar returns events within range and rejects inverted range`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "In Range", "in-range", LocalDate.now().plusDays(3))
            insertEvent(venueId, "Out Of Range", "out-of-range", LocalDate.now().plusDays(40))

            val from = LocalDate.now()
            val to = LocalDate.now().plusDays(7)
            webTestClient
                .get()
                .uri("/events/calendar?from=$from&to=$to")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].slug")
                .isEqualTo("in-range")

            webTestClient
                .get()
                .uri("/events/calendar?from=$to&to=$from")
                .exchange()
                .expectStatus()
                .isBadRequest
        }

    @Test
    fun `GET events calendar applies the same filters as the search endpoint`(): Unit =
        runBlocking {
            val astra = insertVenue("Astra", "astra", district = "friedrichshain-kreuzberg")
            val lido = insertVenue("Lido", "lido", district = "neukoelln")
            val techno = insertGenreTag("Techno", "techno")

            val technoNight = insertEvent(astra, "Techno Night", "techno-night", LocalDate.now().plusDays(2), pricePresale = BigDecimal("15.00"))
            linkGenre(technoNight, techno)
            insertEvent(lido, "Jazz Night", "jazz-night", LocalDate.now().plusDays(3), pricePresale = BigDecimal("40.00"))

            val from = LocalDate.now()
            val to = LocalDate.now().plusDays(7)

            // Each filter narrows the range down to the one event that satisfies it.
            listOf(
                "venue=astra",
                "district=friedrichshain-kreuzberg",
                "genre=techno",
                "q=techno",
                "maxPrice=20"
            ).forEach { filter ->
                webTestClient
                    .get()
                    .uri("/events/calendar?from=$from&to=$to&$filter")
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(1)
                    .jsonPath("$[0].slug")
                    .isEqualTo("techno-night")
            }

            // No filter at all still returns the whole range.
            webTestClient
                .get()
                .uri("/events/calendar?from=$from&to=$to")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(2)
        }

    @Test
    fun `GET event by slug returns full detail with associations`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra", address = "Revaler Str. 99")
            val headliner = insertArtist("The Adicts", "the-adicts")
            val support = insertArtist("Maid Of Ace", "maid-of-ace")
            val promoter = insertPromoter("36 Concerts", "36-concerts")
            val punk = insertGenreTag("Punk", "punk")

            val eventId = insertEvent(venueId, "The Adicts", "the-adicts-live", LocalDate.now(), subtitle = "Tour 2026")
            linkArtist(eventId, headliner, role = "HEADLINER", billingOrder = 0)
            linkArtist(eventId, support, role = "SUPPORT", billingOrder = 1)
            linkPromoter(eventId, promoter)
            linkGenre(eventId, punk)

            webTestClient
                .get()
                .uri("/events/the-adicts-live")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("the-adicts-live")
                .jsonPath("$.subtitle")
                .isEqualTo("Tour 2026")
                .jsonPath("$.venue.slug")
                .isEqualTo("astra")
                .jsonPath("$.lineup.length()")
                .isEqualTo(2)
                .jsonPath("$.lineup[0].artist.slug")
                .isEqualTo("the-adicts")
                .jsonPath("$.lineup[0].role")
                .isEqualTo("HEADLINER")
                .jsonPath("$.lineup[1].artist.slug")
                .isEqualTo("maid-of-ace")
                .jsonPath("$.promoters[0].slug")
                .isEqualTo("36-concerts")
                .jsonPath("$.genreTags[0]")
                .isEqualTo("Punk")
        }

    @Test
    fun `GET event by unknown slug returns 404`() {
        webTestClient
            .get()
            .uri("/events/does-not-exist")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    // A retention policy (#350) has to fail here before it can turn every shared past-event
    // link into a 404. See #362.
    @Test
    fun `GET event by slug resolves an event that has already happened`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Last Month", "last-month", LocalDate.now().minusDays(30))

            webTestClient
                .get()
                .uri("/events/last-month")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("last-month")
        }

    @Test
    fun `GET events returns past events when the range asks for them`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Last Month", "last-month", LocalDate.now().minusDays(30))
            insertEvent(venueId, "Next Month", "next-month", LocalDate.now().plusDays(30))

            webTestClient
                .get()
                .uri("/events?to=${LocalDate.now().minusDays(1)}")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("last-month")
        }

    @Test
    fun `GET events sorts a past range newest first when asked`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Older", "older", LocalDate.now().minusDays(30))
            insertEvent(venueId, "Newer", "newer", LocalDate.now().minusDays(2))

            // Descending has to survive the resolver that appends `id` to every sort.
            webTestClient
                .get()
                .uri("/events?to=${LocalDate.now().minusDays(1)}&sort=eventDate,desc")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].slug")
                .isEqualTo("newer")
                .jsonPath("$.content[1].slug")
                .isEqualTo("older")
        }

    @Test
    fun `GET events ignores an unknown or malicious sort parameter`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Later", "later", LocalDate.now().plusDays(2))
            insertEvent(venueId, "Sooner", "sooner", LocalDate.now().plusDays(1))

            // An unmapped sort property (here a SQL-injection attempt) is dropped by the
            // ORDER BY whitelist, so the request succeeds with the default chronological order
            // rather than erroring or executing the injected text.
            webTestClient
                .get()
                .uri("/events?sort={sort}", "event_date; DROP TABLE events.event;--")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                .jsonPath("$.content[0].slug")
                .isEqualTo("sooner")
                .jsonPath("$.content[1].slug")
                .isEqualTo("later")

            // The table is intact — the injection string was bound/ignored as data, not run as SQL.
            webTestClient
                .get()
                .uri("/events")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
        }

    @Test
    @DisplayName("while image serving is off, the venue's own URL is handed out unchanged")
    fun `an uncached image url survives the default configuration`(): Unit =
        runBlocking {
            // `app.images.serving.enabled` is false everywhere until an environment holds a full set
            // of derivatives (ADR-019). If this ever returned null the cards would go blank on every
            // environment that has not enabled serving yet, which is all of them today.
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Show", "show", LocalDate.now().plusDays(2), imageUrl = "https://venue.test/poster.jpg")

            webTestClient
                .get()
                .uri("/events/show")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.imageUrl")
                .isEqualTo("https://venue.test/poster.jpg")
        }

    @Test
    @DisplayName("without bucket credentials the route reports the store unavailable, not the image missing")
    fun `serving without a storage client is a 503`(): Unit =
        runBlocking {
            // A 404 here would tell a browser to remember an absence caused by our own
            // configuration. The row says the object exists; the default context has no credentials,
            // so no client is built — the state a local run and every not-yet-serving environment is
            // in.
            insertCachedImage("https://venue.test/poster.jpg", CONTENT_HASH, listOf(288))

            webTestClient
                .get()
                .uri("/images/$CONTENT_HASH/288.jpg")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        }

    private companion object {
        const val CONTENT_HASH = "0f4b2c1d5e6a7b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e"
    }
}
