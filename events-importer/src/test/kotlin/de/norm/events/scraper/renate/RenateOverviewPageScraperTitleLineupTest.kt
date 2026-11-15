package de.norm.events.scraper.renate

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** The acts a Renate night's `w/` title bills when its block bills none, on the homepage as it read on 2026-11-10. */
class RenateOverviewPageScraperTitleLineupTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val baseUrl = "https://www.renate.cc/"
    private val scraper = RenateOverviewPageScraper(clock)
    private val events = scraper.scrape(fixtureDocument("renate/renate-overview-2026-11-10.html", baseUrl), baseUrl)

    private fun actsOf(title: String): List<ScrapedArtist> = events.single { it.title == title }.artists

    @Test
    fun `bills the act a w-slash title names, without its friends`() {
        actsOf("OUR HOUSE IS YOUR HOUSE w/ JOHN LOVELESS & FRIENDS") shouldContainExactly
            listOf(ScrapedArtist(name = "JOHN LOVELESS", role = ArtistRole.DJ, titleDerived = true))
    }

    @Test
    fun `bills each act of a w-slash title, split at an ampersand and a co-host's x`() {
        actsOf("OUR HOUSE IS YOUR HOUSE: Vinyl Market & Scratching Workshop w/ DJ EF & Hityawitthat").map { it.name } shouldContainExactly
            listOf("DJ EF", "Hityawitthat")
        actsOf("OUR HOUSE IS YOUR HOUSE: Open Decks w/ xcuse:u x CRUSH").map { it.name } shouldContainExactly listOf("xcuse:u", "CRUSH")
    }

    @Test
    fun `bills nobody for a series night or a title without w-slash`() {
        actsOf("OUR HOUSE IS YOUR HOUSE w/ QUEER VINYL NIGHT").shouldBeEmpty()
        actsOf("OUR HOUSE IS YOUR HOUSE: More TBA").shouldBeEmpty()
        actsOf("Renate NYE 2026/27").shouldBeEmpty()
    }

    @Test
    fun `keeps the block's lineup over the title`() {
        events.filter { it.title.startsWith("Renate Klubnacht") }.flatMap { it.artists }.none { it.titleDerived } shouldBe true
        val html =
            """
            <div class="prog-row">
              <div class="prog-day">Fri.</div><div class="prog-date">20.11.</div>
              <div class="prog-title">OUR HOUSE IS YOUR HOUSE w/ JOHN LOVELESS &amp; FRIENDS</div>
              <span class="cat-btn">CLUB</span>
              <div class="prog-text"><p><strong>GREEN</strong></p><p>DJ Neckbone</p><p>MLE</p></div>
            </div>
            """.trimIndent()
        scraper
            .scrape(Jsoup.parse(html, baseUrl), baseUrl)
            .single()
            .artists
            .map { it.name to it.titleDerived } shouldContainExactly
            listOf("DJ Neckbone" to false, "MLE" to false)
    }

    @Test
    fun `reads no crew of a Klubnacht title as an act`() {
        titleLineup("Renate Klubnacht w/ HOUSE OF HIGHLIFE & ELSE X SOUL EXPRESS").shouldBeEmpty()
    }

    @Test
    fun `drops guests, residents and placeholders`() {
        titleLineup("OUR HOUSE IS YOUR HOUSE w/ Kidcat + guests").map { it.name } shouldContainExactly listOf("Kidcat")
        titleLineup("OUR HOUSE IS YOUR HOUSE w/ Residents").shouldBeEmpty()
        titleLineup("OUR HOUSE IS YOUR HOUSE w/ TBA").shouldBeEmpty()
    }

    @Test
    fun `cuts the night's own words after a dash`() {
        titleLineup("Renate Garten w/ Dub & Dal – Build a beat workshop for kids").map { it.name }.none { "workshop" in it } shouldBe true
    }
}
