package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate

class ClickIdentifiersTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "https://shop.example/e/1?fbclid=IwY2xjawTVr2Jl | https://shop.example/e/1",
            "https://shop.example/e/1?id=7&fbclid=x&seat=2 | https://shop.example/e/1?id=7&seat=2",
            "https://shop.example/e/1?fbclid=x&id=7 | https://shop.example/e/1?id=7",
            "https://shop.example/e/1?gclid=a&msclkid=b&dclid=c&id=7 | https://shop.example/e/1?id=7",
            "https://shop.example/e/1?FBCLID=x&GClid=y | https://shop.example/e/1",
            "https://shop.example/e/1?fbclid=x#tickets | https://shop.example/e/1#tickets",
            "https://shop.example/e/1?id=7&fbclid=x#tickets | https://shop.example/e/1?id=7#tickets",
            "https://shop.example/e/1?fbclid=x&amp;id=7 | https://shop.example/e/1?id=7",
            "https://shop.example/e/1?id=7&amp;fbclid=x&amp;seat=2 | https://shop.example/e/1?id=7&amp;seat=2",
            "https://shop.example/e/1?id=7&fbclid=x& | https://shop.example/e/1?id=7",
            "https://shop.example/e/1?fbclid | https://shop.example/e/1"
        ]
    )
    fun `removes every click identifier and keeps the rest of the link as written`(
        url: String,
        stored: String
    ) {
        stripClickIdentifiers(url) shouldBe stored
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "https://shop.example/e/1",
            "https://shop.example/e/1?affiliate=SZ3&utm_campaign=SzeneTickets+GmbH",
            "https://shop.example/e/1?q=a%26b&id=7&",
            "https://shop.example/e/1?fbclid_note=x&myfbclid=y",
            "https://shop.example/#/e/1?fbclid=x"
        ]
    )
    fun `returns a link without a click identifier unchanged`(url: String) {
        stripClickIdentifiers(url) shouldBeSameInstanceAs url
    }

    @Test
    fun `leaves a click identifier in the fragment alone`() {
        stripClickIdentifiers("https://shop.example/e/1?id=7#fbclid=x") shouldBe "https://shop.example/e/1?id=7#fbclid=x"
    }

    @Test
    fun `an event is stored with its ticket link and its page cleaned`() {
        val entity =
            ScrapedEvent(
                title = "Anemone",
                eventDate = LocalDate.of(2026, 10, 24),
                sourceUrl = "https://venue.example/event/7?fbclid=IwY2xj",
                sourceId = "urban_spree:anemone",
                ticketUrl = "https://rausgegangen.de/events/anemone-nl-berlin-show-urban-spree-0/?fbclid=IwY2xjawTVr2Jl"
            ).toEventEntity(venueId = 1L, venueSlug = "urban-spree", eventSourceId = 1L)

        entity.ticketUrl shouldBe "https://rausgegangen.de/events/anemone-nl-berlin-show-urban-spree-0/"
        entity.sourceUrl shouldBe "https://venue.example/event/7"
    }
}
