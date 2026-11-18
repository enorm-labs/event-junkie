package de.norm.events.scraper.klunkerkranich

import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.splitBilingualDescription
import io.kotest.matchers.collections.shouldBeEmpty
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

    private val pageUrl = "https://klunkerkranich.org/events/x/"

    private fun rangePage(): Document = fixtureDocument("klunkerkranich/klunkerkranich-detail-price-range.html", pageUrl)

    private fun singlePage(): Document = fixtureDocument("klunkerkranich/klunkerkranich-detail-single-price.html", pageUrl)

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

    /** An event page whose prose block holds [paragraphs], one `<p>` each. */
    private fun page(vararg paragraphs: String): Document =
        Jsoup.parse(
            "<div class=\"c-article__content\">" + paragraphs.joinToString("") { "<p>$it</p>" } + "</div>",
            pageUrl
        )

    @Test
    fun `reads the line-up block after the last room heading, up to the rule`() {
        val lineup = scraper.scrapeLineup(rangePage(), "LA MAISON x KLUNKERKRANICH")

        lineup shouldBe listOf("Lex Ludlow", "Kaldera", "Martin Brodin", "Alex Esser", "Jesper Aubin", "Raj Shindi")
    }

    @Test
    fun `reads no line-up from a page without a room heading`() {
        scraper.scrapeLineup(page("*Wohnzimmer ab 18 Uhr", "Ein Abend.", "Lex Ludlow", "_*_"), "NIGHT").shouldBeEmpty()
    }

    @Test
    fun `drops links, credits, the no-clouds note and affiliations from the line-up block`() {
        val lineup =
            scraper.scrapeLineup(
                page(
                    "*Wohnzimmer:",
                    "presented by die brotfrau",
                    "hosted by Gieza Poke",
                    "https://www.instagram.com/someone/",
                    "JaMoll [Sumpfkrüge]",
                    "no clouds",
                    "_*_",
                    "After the rule"
                ),
                "SOME NIGHT"
            )

        lineup shouldBe listOf("JaMoll")
    }

    @Test
    fun `drops a line that restates the night but keeps an act the title also names`() {
        val lineup =
            scraper.scrapeLineup(
                page("*Wohnzimmer:", "DJANGO LASSI *live", "HEAP Album Release Show", "DJ Set by Seraphim"),
                "Django Lassi – HEAP Album Release | DJ Seraphim Balkanique · Gypsy · Swingcore"
            )

        lineup shouldBe listOf("DJANGO LASSI *live", "DJ Set by Seraphim")
    }

    @Test
    fun `keeps an act that opens with a one-word part of the title`() {
        // `Gypsy` is too short to mark a line as the night's name.
        scraper.scrapeLineup(page("*Wohnzimmer:", "Gypsy Kings *live"), "Gypsy | Swing Night") shouldBe listOf("Gypsy Kings *live")
    }

    @Test
    fun `drops a line that repeats the whole title`() {
        scraper.scrapeLineup(page("*Wohnzimmer:", "Sunday Social"), "SUNDAY SOCIAL").shouldBeEmpty()
    }
}
