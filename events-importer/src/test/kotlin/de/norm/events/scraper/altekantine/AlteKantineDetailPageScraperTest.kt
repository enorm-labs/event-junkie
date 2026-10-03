package de.norm.events.scraper.altekantine

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for [AlteKantineDetailPageScraper].
 *
 * Uses real `?p=<id>` post snapshots: a party night (clean "Was: Party" kind, a
 * numeric door price, a resident DJ, description and poster), a quiz night (a
 * free-text "Was" label that falls back to a title-keyword classification, and a
 * "DJ: Pubquiz" format label that must not become an artist), and a Kantine Deluxe
 * night with an early-entry door tier. A fixed clock
 * (2026-07-01) keeps year inference on the year-less `Wann:` date deterministic.
 */
class AlteKantineDetailPageScraperTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-01T10:00:00Z"), ZoneOffset.UTC)
    private val scraper = AlteKantineDetailPageScraper(clock)

    private fun parse(
        fixture: String,
        sourceUrl: String
    ) = scraper.scrape(
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/altekantine/$fixture")!!
                .bufferedReader()
                .readText(),
            sourceUrl
        ),
        sourceUrl
    )

    @Test
    fun `parses all fields of a party night`() {
        val event = parse("altekantine-detail-party.html", "https://alte-kantine.eu/?p=12371").shouldNotBeNull()

        event.title shouldBe "Everybody Dance Now!"
        event.eventType shouldBe EventType.PARTY.name
        // "Wann: 23.07." with no year → 2026 (nearest occurrence to the fixed clock).
        event.eventDate shouldBe LocalDate.of(2026, 7, 23)
        event.startTime shouldBe LocalTime.of(22, 0)
        // "Eintritt: 4 €" → a numeric box-office price, so no free-form note. The text's
        // "Eintritt mit Passwort … bis Mitternacht 2€" needs a password, so it is no door tier.
        event.priceBoxOffice shouldBe BigDecimal("4")
        event.priceNote.shouldBeNull()
        event.imageUrl shouldBe "https://www.alte-kantine.eu/dist/Bilder/Programm/party_poster-everybody_dance_now1.jpg"
        event.description.shouldNotBeNull() shouldContain "Nach der bleiernen Pause"
        event.sourceId shouldBe "alte_kantine:12371"
        // The subtitle lives only on the overview; the detail page leaves it for the merge to fill.
        event.subtitle.shouldBeNull()
        // A party's performer is its resident DJ.
        event.artists shouldContainExactly listOf(ScrapedArtist(name = "Funky Henning", role = "DJ"))
    }

    @Test
    fun `drops a symbol-only price and an empty DJ field, and reads free entry from the text`() {
        val event = parse("altekantine-detail-freeform-price.html", "https://alte-kantine.eu/?p=12349").shouldNotBeNull()

        event.title shouldBe "Beer Pong Night"
        event.eventType shouldBe EventType.PARTY.name
        // "Eintritt:  €" carries no number → no box-office price, and the lone "€" is not kept as a note.
        event.priceBoxOffice.shouldBeNull()
        // The text says it instead: entry is free, and only players pay a fee to take part.
        event.priceNote shouldBe "Freier Eintritt für Alle"
        event.toEventEntity(venueId = 1L, venueSlug = "alte-kantine", eventSourceId = 1L).free shouldBe true
        // The "DJ:" row is empty, so no performer is minted.
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `stores the early-entry tier as the door price and names both tiers in the note`() {
        val event = parse("altekantine-detail-early-entry-tier.html", "https://alte-kantine.eu/?p=12449").shouldNotBeNull()

        event.title shouldBe "*Kantine Deluxe*"
        event.eventDate shouldBe LocalDate.of(2026, 10, 9)
        // "Eintritt: 7 €" in the field list, "Eintritt bis 0 Uhr: 4€" in the text: the lowest tier is the price (#2083).
        event.priceBoxOffice shouldBe BigDecimal("4")
        event.priceNote shouldBe "4 € bis 0 Uhr, danach 7 €"
    }

    @Test
    fun `keeps the field price when the early-entry line is not cheaper, and writes cents in the note`() {
        fun eventWith(
            eintritt: String,
            line: String
        ) = scraper
            .scrape(
                Jsoup.parse(
                    """
                    <h2 class="heading-1">Kantine Deluxe</h2>
                    <div class="line-height-28"><p class="p1">Kantine Deluxe</p><p><strong>$line</strong></p></div>
                    <ul class="list-style-6"><li><label>Wann:</label>09.10.</li><li><label>Eintritt:</label>$eintritt</li></ul>
                    """.trimIndent()
                ),
                "https://alte-kantine.eu/?p=1"
            ).shouldNotBeNull()

        eventWith("7 €", "Eintritt bis 0 Uhr: 7€").run {
            priceBoxOffice shouldBe BigDecimal("7")
            priceNote.shouldBeNull()
        }
        eventWith("7,50 €", "Eintritt bis Mitternacht 4,50 €").run {
            priceBoxOffice shouldBe BigDecimal("4.50")
            priceNote shouldBe "4,50 € bis Mitternacht, danach 7,50 €"
        }
    }

    @Test
    fun `classifies a quiz from the title when the Was label is free text`() {
        val event = parse("altekantine-detail-quiz.html", "https://alte-kantine.eu/?p=12281").shouldNotBeNull()

        event.title shouldBe "Quiz Night Show"
        // "Was: The Quiz Night Show" is not a known kind label → title keyword classifies it QUIZ.
        event.eventType shouldBe EventType.QUIZ.name
        event.eventDate shouldBe LocalDate.of(2026, 7, 17)
        event.startTime shouldBe LocalTime.of(19, 30)
        event.priceBoxOffice shouldBe BigDecimal("10")
        // "DJ: Pubquiz" is a format label, not a performer, and a quiz has no act anyway.
        event.artists.shouldBeEmpty()
    }
}
