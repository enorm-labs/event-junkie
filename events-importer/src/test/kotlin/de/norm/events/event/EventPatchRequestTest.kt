package de.norm.events.event

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.util.Optional

/** Unit tests for [EventPatchRequest.mergedOnto]: what a patch keeps, sets and clears (#3995). */
class EventPatchRequestTest {
    private val stored =
        EventResponse(
            id = 7,
            venueId = 3,
            title = "SLAYYYTER",
            subtitle = "Tour 2026",
            description = "Blurb",
            eventType = EventType.CONCERT,
            status = EventStatus.SCHEDULED,
            slug = "2026-10-27-metropol-slayyyter",
            eventDate = LocalDate.of(2026, 10, 27),
            doorsTime = LocalTime.of(19, 0),
            startTime = LocalTime.of(20, 0),
            imageUrl = null,
            sourceUrl = "https://example.de/event",
            sourceId = "metropol:2026-10-27-slayyyter",
            ticketUrl = "https://example.de/tickets",
            facebookEventUrl = null,
            genre = "Pop",
            genreTags = listOf("Pop"),
            pricePresale = BigDecimal("38.00"),
            priceBoxOffice = null,
            priceCurrency = "EUR",
            priceNote = null,
            soldOut = false,
            free = false,
            createdAt = null,
            updatedAt = null,
            artists = listOf(EventArtistResponse(artistId = 11, role = ArtistRole.HEADLINER, billingOrder = 0, stage = "Saal")),
            promoterIds = listOf(19),
            relocatedTo = "Huxleys"
        )

    private val untouched =
        EventRequest(
            venueId = 3,
            title = "SLAYYYTER",
            subtitle = "Tour 2026",
            description = "Blurb",
            eventType = EventType.CONCERT,
            status = EventStatus.SCHEDULED,
            eventDate = LocalDate.of(2026, 10, 27),
            doorsTime = LocalTime.of(19, 0),
            startTime = LocalTime.of(20, 0),
            sourceUrl = "https://example.de/event",
            sourceId = "metropol:2026-10-27-slayyyter",
            ticketUrl = "https://example.de/tickets",
            genre = "Pop",
            pricePresale = BigDecimal("38.00"),
            artists = listOf(EventArtistRequest(artistId = 11, role = ArtistRole.HEADLINER, billingOrder = 0, stage = "Saal")),
            promoterIds = listOf(19)
        )

    @Test
    fun `an empty patch gives back the stored event, and leaves the pick, festival and move to keep`() {
        EventPatchRequest().mergedOnto(stored) shouldBe untouched
    }

    @Test
    fun `a sent value replaces the stored one`() {
        EventPatchRequest(eventType = EventType.OTHER, ticketUrl = Optional.of("https://example.de/new"))
            .mergedOnto(stored) shouldBe untouched.copy(eventType = EventType.OTHER, ticketUrl = "https://example.de/new")
    }

    @Test
    fun `an explicit null clears a clearable field, and keeps a field every event has`() {
        EventPatchRequest(ticketUrl = Optional.empty(), pricePresale = Optional.empty(), title = null)
            .mergedOnto(stored) shouldBe untouched.copy(ticketUrl = null, pricePresale = null)
    }

    @Test
    fun `a sent lineup or promoter list replaces the whole list`() {
        val lineup = listOf(EventArtistRequest(artistId = 12, role = ArtistRole.SUPPORT, billingOrder = 1))
        EventPatchRequest(artists = lineup, promoterIds = emptyList())
            .mergedOnto(stored) shouldBe untouched.copy(artists = lineup, promoterIds = emptyList())
    }

    @Test
    fun `the move passes through as sent`() {
        val patch = EventPatchRequest(status = EventStatus.RELOCATED, relocatedTo = Optional.of("Bi Nuu"))
        patch.mergedOnto(stored) shouldBe untouched.copy(status = EventStatus.RELOCATED, relocatedTo = Optional.of("Bi Nuu"))
    }
}
