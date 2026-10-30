package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate

/** The ticket link [ScrapedEvent.toEventEntity] stores. Kesselhaus published `https://`, a Tickets button to nowhere (#4140). */
class ScrapedEventTicketUrlTest {
    private fun storedTicketUrl(ticketUrl: String?) =
        ScrapedEvent(
            title = "Nemzzz",
            eventDate = LocalDate.of(2026, 12, 30),
            sourceId = "kesselhaus:7052",
            sourceUrl = "https://www.kesselhaus.net/calendar/7052",
            ticketUrl = ticketUrl
        ).toEventEntity(venueId = 1L, venueSlug = "kesselhaus", eventSourceId = 1L).ticketUrl

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = ["https://", "http://", "https:///path", "/tickets", "mailto:tickets@example.de", ""])
    fun `a ticket URL that names no host is dropped`(ticketUrl: String) {
        storedTicketUrl(ticketUrl).shouldBeNull()
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = ["https://shop.example/e/1", "https://my_shop.example/e/1", "https://shop.example/e/a b"])
    fun `a ticket URL that names a host is kept`(ticketUrl: String) {
        storedTicketUrl(ticketUrl) shouldBe ticketUrl
    }

    @Test
    fun `no ticket URL stays none, and a kept one still loses its click identifier`() {
        storedTicketUrl(null).shouldBeNull()
        storedTicketUrl("https://shop.example/e/1?fbclid=x") shouldBe "https://shop.example/e/1"
    }
}
