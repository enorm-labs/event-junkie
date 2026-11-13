package de.norm.events.scraper.crackbellmer

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalTime

/**
 * Unit tests for [CrackBellmerDetailPageScraper].
 *
 * The event page contributes a blurb, in two shapes: filled, with the Webflow rich-text editor's
 * zero-width-joiner spacer paragraphs between the real ones, and empty, which is what a bar night or
 * a closed day renders. Some blurbs carry a doors line, read with its free-entry clause.
 */
class CrackBellmerDetailPageScraperTest {
    private val scraper = CrackBellmerDetailPageScraper()
    private val pageUrl = "https://www.crackbellmer.de/events/bad-dad-yr8lk"

    @Test
    fun `joins the blurb's paragraphs and drops the spacer lines`() {
        val description = scraper.scrapeDescription(fixtureDocument("crackbellmer/crackbellmer-detail-blurb.html", pageUrl))

        description shouldBe
            "We're standing at the peak of summer and if not now, when? Join us on our terrace, in our garden or " +
            "preferably; on our dance floor! The night is neither young nor old, all that matters is our time here " +
            "together. Let's celebrate!\n" +
            "Crack Bellmer opens at 19:00, free entry until 22:00 when the DJs start.\n" +
            "Come early, stay late, tip the bar!"
        // The spacer paragraphs hold a zero-width joiner, which is not whitespace.
        description!!.shouldNotContain("‍")
    }

    @Test
    fun `returns null when the page carries no blurb`() {
        scraper.scrapeDescription(fixtureDocument("crackbellmer/crackbellmer-detail-no-blurb.html", pageUrl)).shouldBeNull()
    }

    @Test
    fun `returns null for a page without the event content wrapper`() {
        scraper.scrapeDescription(Jsoup.parse("<html><body><main></main></body></html>", pageUrl)).shouldBeNull()
    }

    @Test
    fun `reads the doors time and the free-entry note from the doors line`() {
        val doors = scraper.scrapeDoors(fixtureDocument("crackbellmer/crackbellmer-detail-doors.html", pageUrl))

        doors shouldBe CrackBellmerDetailPageScraper.DoorsLine(LocalTime.of(19, 0), "Free entry until 22:00")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "Door opens at 19:00                          | 19:00",
            "Doors open at 3 pm. Four artists             | 15:00",
            "Doors: 19:00                                 | 19:00",
            "Smut Slam Berlin: IN THE SHADOWS Doors 19:00, slam at 20:00 | 19:00",
            "Doors open at 00:30                          | 00:30",
            "Doors open at 12:30 am                       | 00:30"
        ]
    )
    fun `reads each doors spelling`(
        line: String,
        expected: String
    ) {
        scraper.scrapeDoors(blurb(line))?.time shouldBe LocalTime.parse(expected)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Crack Bellmer opens at 19:00, free entry until 22:00 when the DJs start.",
            "Bar from 7-10pm Tickets at the door from 10pm",
            "Bar from 7 pm Djs from 10 pm Doors: free entry until 10 pm"
        ]
    )
    fun `reads no doors time from a line that states none`(line: String) {
        scraper.scrapeDoors(blurb(line)).shouldBeNull()
    }

    @Test
    fun `returns null for the fixture blurb without a doors line`() {
        scraper.scrapeDoors(fixtureDocument("crackbellmer/crackbellmer-detail-blurb.html", pageUrl)).shouldBeNull()
    }

    @Test
    fun `keeps a doors line without a free-entry clause note-less`() {
        scraper.scrapeDoors(blurb("Doors open at 3 pm. Four artists"))?.freeEntryNote.shouldBeNull()
    }

    @Test
    fun `takes the free-entry clause only from the doors line`() {
        val doors = scraper.scrapeDoors(blurb("FREE ENTRY UNTIL 10 PM", "Doors 19:00, slam at 20:00"))

        doors shouldBe CrackBellmerDetailPageScraper.DoorsLine(LocalTime.of(19, 0), null)
    }

    private fun blurb(vararg paragraphs: String) =
        Jsoup.parse(
            "<html><body><div class=\"event-content-wrapper\"><div class=\"w-richtext\">" +
                paragraphs.joinToString("") { "<p>$it</p>" } +
                "</div></div></body></html>",
            pageUrl
        )
}
