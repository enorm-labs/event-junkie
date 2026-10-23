package de.norm.events.scraper.eighthundreda

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class EightHundredAHomePageScraperTest {
    private val baseUrl = "https://www.800aberlin.com/"
    private val events =
        EightHundredAHomePageScraper().scrape(
            Jsoup.parse(javaClass.classLoader.getResourceAsStream("scraper/eighthundreda/800a-home.html")!!, null, baseUrl),
            baseUrl
        )

    private fun event(slug: String) = events.single { it.sourceUrl.endsWith("/events/$slug") }

    @Test
    fun `reads the calendar rows and leaves out the courses`() {
        events shouldHaveSize 31
        events.map { it.title } shouldNotContain "Improv Beginners"
    }

    @Test
    fun `reads a burlesque night in Berlin time with doors, price, performers and image`() {
        val show = event("naughty-horror-show-Oct-25")

        show.sourceId shouldBe "eight_hundred_a:911d5e72-4ce4-4822-9a7d-d2a70eab9883"
        show.eventType shouldBe EventType.SHOW.name
        show.eventDate shouldBe LocalDate.of(2026, 10, 25)
        show.startTime shouldBe LocalTime.of(19, 0)
        show.doorsTime shouldBe LocalTime.of(18, 0)
        show.endTime shouldBe LocalTime.of(22, 0)
        show.pricePresale shouldBe BigDecimal("17.56")
        show.ticketUrl!! shouldStartWith "https://rausgegangen.de/en/events/naughty-horror-show-0/"
        show.imageUrl shouldBe "https://pkuixlkyxclroyymxkyf.supabase.co/storage/v1/object/public/event-images/09572761-ec5c-4078-97f8-9f9bf8f3ea3c.png"
        show.artists shouldContainExactly listOf(ScrapedArtist("Alice La Douce"), ScrapedArtist("Harpy Fatale"))
        show.promoters.shouldBeEmpty()
        show.descriptionAlt!! shouldStartWith "GET READY"
    }

    @Test
    fun `reads the Thursday open stage as a donation concert, not a free one`() {
        val night = event("thursday-night-special-Oct-29")

        night.eventType shouldBe EventType.CONCERT.name
        night.subtitle shouldBe "Open stage & Variety"
        night.free shouldBe false
        night.priceNote shouldBe "Donation 5–10 €"
        night.pricePresale.shouldBeNull()
        night.artists.shouldBeEmpty()
    }

    @Test
    fun `drops a placeholder time and keeps the date`() {
        val comedy = event("xhuliano-dule-remuntada-Oct-30")

        comedy.eventType shouldBe EventType.COMEDY.name
        comedy.eventDate shouldBe LocalDate.of(2026, 10, 30)
        comedy.startTime.shouldBeNull()
        comedy.doorsTime.shouldBeNull()
        comedy.endTime.shouldBeNull()
        comedy.artists shouldContainExactly listOf(ScrapedArtist("XHULIANO DULE"))
        comedy.descriptionAlt.shouldBeNull()
    }

    @Test
    fun `bills an external host as the promoter`() {
        event("drunk-classics-Oct-24-1").promoters shouldContainExactly listOf("Drunk Classics Berlin")
        event("b-movie-night-chopping-mall-1986-Nov-25").eventType shouldBe EventType.SCREENING.name
    }

    @Test
    fun `answers nothing for a page without the island`() {
        EightHundredAHomePageScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
