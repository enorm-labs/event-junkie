package de.norm.events.scraper.speakeazy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class SpeakeazyOverviewPageScraperTest {
    private val sourceUrl = "https://www.speakeazyberlin.de/events"
    private val scraper = SpeakeazyOverviewPageScraper()

    private val events by lazy {
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/speakeazy/speakeazy-events.html")!!
                    .bufferedReader()
                    .readText(),
                sourceUrl
            ),
            sourceUrl
        )
    }

    private fun event(slug: String) = events.single { it.sourceId == "speakeazy:$slug" }

    /** One upcoming listing entry, in the listing's own markup. */
    private fun listing(
        slug: String,
        title: String
    ) = scraper.scrape(
        Jsoup.parse(
            """
            <article class="eventlist-event eventlist-event--upcoming">
              <h1 class="eventlist-title"><a href="/events/$slug" class="eventlist-title-link">$title</a></h1>
              <time class="event-date" datetime="2026-10-17">Samstag, 17. Oktober 2026</time>
              <time class="event-time-localized-start" datetime="2026-10-17">20:00</time>
            </article>
            """.trimIndent(),
            sourceUrl
        ),
        sourceUrl
    )

    @Test
    fun `reads the upcoming nights and drops the private party and the past ones`() {
        events shouldHaveSize 6
        events.none { it.title.contains("Private Party", ignoreCase = true) } shouldBe true
        events.all { !it.eventDate.isBefore(LocalDate.of(2026, 10, 24)) } shouldBe true
    }

    @Test
    fun `maps a concert from the listing, leaving the blurb and the price to its page`() {
        val beatles = event("yeah-yeah-yeah-")

        beatles.title shouldBe "Yeah! Yeah! Yeah !"
        beatles.eventType shouldBe "CONCERT"
        beatles.eventDate shouldBe LocalDate.of(2026, 11, 14)
        beatles.startTime shouldBe LocalTime.of(20, 0)
        beatles.endDate shouldBe LocalDate.of(2026, 11, 14)
        beatles.endTime shouldBe LocalTime.of(23, 0)
        beatles.sourceUrl shouldBe "https://www.speakeazyberlin.de/events/yeah-yeah-yeah-"
        beatles.imageUrl!! shouldStartWith "https://images.squarespace-cdn.com/"
        beatles.description.shouldBeNull()
        beatles.priceBoxOffice.shouldBeNull()
    }

    @Test
    fun `splits a double bill into its two acts`() {
        listing("the-reveries", "AK In Control + The Reveries").single().artists.map { it.name } shouldContainExactly
            listOf("AK In Control", "The Reveries")
    }

    @Test
    fun `keeps a duo whole before its programme name`() {
        val dylan = event("annherung-an-bob-dylan")

        dylan.title shouldBe "Stock & Pankow: Annäherung an Bob Dylan"
        dylan.artists.map { it.name } shouldContainExactly listOf("Stock & Pankow")
    }

    @Test
    fun `bills no act for a house session`() {
        event("bassball-singer-songwriter-session-rybgg-7jwze-5z8w4-pjye6").artists.shouldBeEmpty()
        listing("bassball-ost-west-session", "Bassball Ost-West Session").single().artists.shouldBeEmpty()
    }

    @Test
    fun `returns no events for a page without a listing`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", sourceUrl), sourceUrl).shouldBeEmpty()
    }
}
