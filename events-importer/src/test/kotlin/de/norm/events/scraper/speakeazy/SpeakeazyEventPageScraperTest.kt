package de.norm.events.scraper.speakeazy

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class SpeakeazyEventPageScraperTest {
    private val scraper = SpeakeazyEventPageScraper()
    private val base = "https://www.speakeazyberlin.de/events"
    private val bluesHtml = "speakeazy/speakeazy-event-the-sixth-dimension-blues.html"
    private val siebertHtml = "speakeazy/speakeazy-event-markus-siebert-band.html"
    private val bassballHtml = "speakeazy/speakeazy-event-bassball.html"

    private fun listed(slug: String) =
        ScrapedEvent(
            title = slug,
            eventType = "CONCERT",
            eventDate = LocalDate.of(2026, 10, 24),
            sourceUrl = "$base/$slug",
            sourceId = "speakeazy:$slug"
        )

    private fun page(body: String): Document = Jsoup.parse("<html><body>$body</body></html>", "$base/inline")

    @Test
    fun `reads the blurb, the door price and the ticket shop`() {
        val slug = "the-sixth-dimension-blues"
        val blues = scraper.enrich(listed(slug), fixtureDocument(bluesHtml, "$base/$slug")).shouldNotBeNull()

        blues.description!! shouldStartWith "Wir sind The Sixth Dimension Blues aus Berlin-Brandenburg"
        blues.description shouldNotContain "Abendkasse"
        blues.priceBoxOffice shouldBe BigDecimal("16")
        blues.pricePresale.shouldBeNull()
        blues.ticketUrl!! shouldStartWith "https://www.eventim-light.com/de/a/5f4e2ed0e54618072055210e/e/"
    }

    @Test
    fun `reads the regular price from a qualified door price`() {
        val slug = "markus-siebert-band"
        scraper.enrich(listed(slug), fixtureDocument(siebertHtml, "$base/$slug"))!!.priceBoxOffice shouldBe BigDecimal("26")
    }

    @Test
    fun `keeps the blurb of a page that prints no price`() {
        val slug = "bassball-singer-songwriter-session-rybgg-7jwze-5z8w4-pjye6"
        val session = scraper.enrich(listed(slug), fixtureDocument(bassballHtml, "$base/$slug")).shouldNotBeNull()

        session.description!! shouldStartWith "Musikalische Unterstützung von einer hochkarätigen Studioband"
        session.priceBoxOffice.shouldBeNull()
    }

    @Test
    fun `drops a blurb that is still to come but keeps the price`() {
        val event =
            scraper.enrich(
                listed("tba"),
                page(
                    """
                    <article class="eventitem">
                      <div class="sqs-html-content"><p>Text folgt</p></div>
                      <div class="sqs-html-content"><p>Abendkasse 18€</p></div>
                    </article>
                    """.trimIndent()
                )
            )!!

        event.description.shouldBeNull()
        event.priceBoxOffice shouldBe BigDecimal("18")
        event.ticketUrl.shouldBeNull()
    }

    @Test
    fun `keeps a blurb that mentions the box office in a long sentence`() {
        val blurb = "Karten gibt es an der Abendkasse, und wer früh kommt, bekommt einen der wenigen Plätze direkt vor der Bühne."
        val event = scraper.enrich(listed("long"), page("""<article class="eventitem"><div class="sqs-html-content"><p>$blurb</p></div></article>"""))!!

        event.description shouldBe blurb
        event.priceBoxOffice.shouldBeNull()
    }

    @Test
    fun `yields nothing for a page without the event article`() {
        scraper.enrich(listed("gone"), page("<p>Seite nicht gefunden</p>")).shouldBeNull()
    }
}
