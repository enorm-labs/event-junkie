package de.norm.events.scraper.littlestagebar

import de.norm.events.scraper.BERLIN
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [LittleStageBarFeedScraper]. */
class LittleStageBarFeedScraperTest {
    private val baseUrl = "https://littlestageclubneukoelln.wordpress.com/feed/"
    private val scraper = LittleStageBarFeedScraper(Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), BERLIN))

    private val events by lazy {
        val xml =
            javaClass.classLoader
                .getResourceAsStream("scraper/littlestagebar/littlestagebar-feed.xml")
                .let { requireNotNull(it) }
                .bufferedReader()
                .readText()
        scraper.scrape(xml, baseUrl)
    }

    private fun bySlug(slug: String) = events.single { it.sourceId == "little_stage_bar:$slug" }

    private fun item(content: String) =
        """
        <rss><channel><item>
        <title>Konzert</title>
        <link>https://littlestageclubneukoelln.wordpress.com/2026/10/01/konzert/</link>
        <content:encoded><![CDATA[$content]]></content:encoded>
        </item></channel></rss>
        """.trimIndent()

    @Test
    fun `reads only the posts whose prose names exactly one date`() {
        events.map { it.sourceId.substringAfter(':') } shouldContainExactly
            listOf(
                "face-driller-bohrer-im-kopf",
                "andreas-gunther-und-internationale-musiker-in-berlin",
                "neukolln-live-acid-omas",
                "noisy-advent-mit-splash-lemon",
                "rhymefabrik"
            )
    }

    @Test
    fun `parses a numeric date with a two-digit year and the clock on the next line`() {
        val event = bySlug("face-driller-bohrer-im-kopf")

        event.title shouldBe "Face Driller – Bohrer im Kopf"
        event.eventDate shouldBe LocalDate.of(2026, 9, 26)
        event.startTime shouldBe LocalTime.of(20, 30)
        event.doorsTime.shouldBeNull()
        event.eventType shouldBe "CONCERT"
        event.sourceUrl shouldBe "https://littlestageclubneukoelln.wordpress.com/2026/09/22/face-driller-bohrer-im-kopf/"
        event.imageUrl shouldBe "https://littlestageclubneukoelln.wordpress.com/wp-content/uploads/2026/09/facedriller.jpg"
        event.description.orEmpty() shouldStartWith "Sonnabend – 26.9.26\n20:30 Uhr\nFACE DRILLER"
    }

    @Test
    fun `parses a spelled-out month with and without a space after the day`() {
        bySlug("andreas-gunther-und-internationale-musiker-in-berlin").eventDate shouldBe LocalDate.of(2026, 2, 14)
        bySlug("andreas-gunther-und-internationale-musiker-in-berlin").startTime shouldBe LocalTime.of(20, 0)
        bySlug("neukolln-live-acid-omas").eventDate shouldBe LocalDate.of(2026, 1, 24)
        bySlug("neukolln-live-acid-omas").startTime shouldBe LocalTime.of(20, 30)
    }

    @Test
    fun `takes a clock printed just before the date`() {
        val event = bySlug("noisy-advent-mit-splash-lemon")

        event.eventDate shouldBe LocalDate.of(2025, 12, 14)
        event.startTime shouldBe LocalTime.of(15, 0)
        event.free shouldBe true
    }

    @Test
    fun `fixes the year of a year-less date by the weekday before it and reads Einlass as doors`() {
        val event = bySlug("rhymefabrik")

        event.eventDate shouldBe LocalDate.of(2025, 12, 1)
        event.doorsTime shouldBe LocalTime.of(20, 0)
        event.startTime.shouldBeNull()
    }

    @Test
    fun `skips a year-less date without a weekday`() {
        scraper.scrape(item("<p>12.Oktober 20 Uhr</p>"), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `skips a post that names two dates`() {
        scraper.scrape(item("<p>Freitag 2.10.26 und Samstag 3.10.26, 20 Uhr</p>"), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `skips a weekly series even when it names a date`() {
        scraper.scrape(item("<p>Jeden Freitag 20 Uhr, auch am Sonnabend 19.09.26</p>"), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `leaves the time empty when no clock is near the date`() {
        val event = scraper.scrape(item("<p>Sonnabend, 3.10.26</p><p>Eine lange Beschreibung ohne jede Uhrzeit, die weitergeht und weitergeht.</p>"), baseUrl)

        event.single().eventDate shouldBe LocalDate.of(2026, 10, 3)
        event.single().startTime.shouldBeNull()
    }
}
