package de.norm.events.scraper.klunkerkranich

import de.norm.events.scraper.splitBilingualDescription
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit tests for [KlunkerkranichDetailPageScraper].
 *
 * The event page contributes a blurb, an entry charge and the full-size poster, so these cover the
 * two price shapes the venue writes — a range charged by arrival time, and a lone figure — and the
 * boilerplate the blurb has to be cut out of.
 *
 * Both fixtures had their `<script>` elements removed when they were captured: the pages inline
 * WordPress core's wp-emoji-loader, which CodeQL flags as `js/xss-through-dom`. None of them sits
 * inside the article, so the parsed markup is unchanged; do not re-capture the pages to put them
 * back.
 */
class KlunkerkranichDetailPageScraperTest {
    private val scraper = KlunkerkranichDetailPageScraper()

    private val rangeTitle = "HMWL x KLUNKERKRANICH w. Lex Ludlow, Kaldera, Martin Brodin, Alex Esser, Jesper Aubin, Raj Shindi"
    private val singleTitle = "LA MAISON x KLUNKERKRANICH"

    private fun page(name: String): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/klunkerkranich/$name")!!
                .bufferedReader()
                .readText(),
            "https://klunkerkranich.org/events/x/"
        )

    private fun rangePage(): Document = page("klunkerkranich-detail-price-range.html")

    private fun singlePage(): Document = page("klunkerkranich-detail-single-price.html")

    @Test
    fun `keeps the blurb and drops the schedule preamble, room labels and footer`() {
        val description = scraper.scrapeDescription(rangePage(), rangeTitle)

        description shouldBe
            "Ein zweites Mal in 2026 kommen unsere herzallerliebsten Schweden aus Malmö auf unser Dach und bringen wie " +
            "gewohnt eine ordentliche Packung LOVE OVERLOAD! HMWL ist kurz für House Music With Love und ist Label, " +
            "Community und Support für Produzent:innen und DJs international. Die Truppe übernimmt heute beide Floors " +
            "mit House, Minimal und tüchtig Groove. Samstag wie aus dem Bilderbuch.\n" +
            "[EN]\n" +
            "For the second time in 2026, our absolute favourites from Malmö, Sweden, are taking to our rooftop and, as " +
            "usual, bringing a proper dose of LOVE OVERLOAD! HMWL stands for House Music With Love and is a label, " +
            "community and support network for producers and DJs worldwide. Today, the crew are taking over both " +
            "floors with house, minimal and plenty of groove. A picture-perfect Saturday."
    }

    @Test
    fun `leaves the standing price notice and the venue's own link out of the blurb`() {
        val description = scraper.scrapeDescription(singlePage(), singleTitle)!!

        // Everything after the venue's own `_*_` rule is its standing footer.
        description shouldNotContain "Please note"
        description shouldNotContain "www.klunkerkranich.org"
        // The blurb itself survives intact, links the venue wrote into it included.
        description shouldNotContain "ab 11 Uhr"
        description shouldNotContain "Come early, stay late\n_*_"
    }

    @Test
    fun `stores a lone figure as the box-office price`() {
        scraper.scrapePrice(singlePage()) shouldBe (BigDecimal("3") to null)
    }

    @Test
    fun `stores an arrival-time range as a price note, without the standing notice`() {
        scraper.scrapePrice(rangePage()) shouldBe (null to "5-9€")
    }

    @Test
    fun `keeps a figure-less entry charge verbatim so a free night is still recognisable`() {
        val document =
            Jsoup.parse(
                """<aside class="c-article__sidebar"><h2>Wieviel</h2><p>Eintritt frei</p></aside>""",
                "https://klunkerkranich.org/events/x/"
            )

        scraper.scrapePrice(document) shouldBe (null to "Eintritt frei")
    }

    @Test
    fun `prefers the full-size poster the header image links to`() {
        scraper.scrapeImageUrl(rangePage()) shouldBe
            "https://klunkerkranich.org/wp-content/uploads/2025/10/Klunkerkranich-kunst-10.Oct25.jpg"
    }

    @Test
    fun `falls back to the rendered crop when the header image is not linked`() {
        val document =
            Jsoup.parse(
                """<div class="o-page-header__media"><img src="https://klunkerkranich.org/x-1400x933.jpg"></div>""",
                "https://klunkerkranich.org/events/x/"
            )

        scraper.scrapeImageUrl(document) shouldBe "https://klunkerkranich.org/x-1400x933.jpg"
    }

    @Test
    fun `returns nothing for a page without an article`() {
        val empty = Jsoup.parse("<html><body><main></main></body></html>", "https://klunkerkranich.org/events/x/")

        scraper.scrapeDescription(empty, singleTitle).shouldBeNull()
        scraper.scrapePrice(empty) shouldBe (null to null)
        scraper.scrapeImageUrl(empty).shouldBeNull()
    }

    // The importer cuts the blurb at its "[EN]" line (#330).
    @Test
    fun `the blurb cuts at its EN line into a German and an English half`() {
        val split = splitBilingualDescription(scraper.scrapeDescription(rangePage(), rangeTitle)).shouldNotBeNull()

        split.original shouldStartWith "Ein zweites Mal in 2026"
        split.original shouldNotContain "[EN]"
        split.alt shouldStartWith "For the second time in 2026"
        split.alt shouldNotContain "Ein zweites Mal"
    }
}
