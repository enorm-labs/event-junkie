package de.norm.events.scraper.karakas

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Unit tests for [KarakasPageScraper], against saved copies of the comedy and pub quiz pages. */
class KarakasPageScraperTest {
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneId.of("Europe/Berlin")).toInstant(), ZoneId.of("Europe/Berlin"))
    private val scraper = KarakasPageScraper(clock)
    private val comedyUrl = "https://karakasbar.com/comedy-shows"
    private val quizUrl = "https://karakasbar.com/pub-quiz-1"

    private fun document(
        fixture: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/karakas/$fixture")!!
            .bufferedReader()
            .readText(),
        url
    )

    private val comedy = document("karakas-comedy-shows.html", comedyUrl)
    private val quiz = document("karakas-pub-quiz.html", quizUrl)

    @Test
    fun `scrape reads the tour cards on the comedy page`() {
        val events = scraper.scrape(comedy, comedyUrl, EventType.COMEDY)

        events.map { it.eventDate to it.title } shouldContainExactly
            listOf(
                LocalDate.of(2026, 10, 17) to "Carluis Por El Mundo",
                LocalDate.of(2026, 10, 20) to "Sprachenchaos",
                LocalDate.of(2026, 10, 24) to "WIR REDEN DIE WELT – Live Comedy Podcast mit lni & Jong",
                LocalDate.of(2026, 11, 14) to "DANIEL PISTOLA EN BERLÍN",
                LocalDate.of(2027, 1, 16) to "Katharina Block's Show",
                LocalDate.of(2027, 3, 17) to "OPHELIE HOCQUARD - CLAUDE"
            )
    }

    @Test
    fun `a tour card links its outside shop and its full-size image`() {
        val event = scraper.scrape(comedy, comedyUrl, EventType.COMEDY).single { it.title == "Sprachenchaos" }

        event.eventType shouldBe "COMEDY"
        event.ticketUrl shouldBe "https://getjungle.io/en/event/02c1effc-28d9-4912-985e-07e69d305f09"
        event.imageUrl shouldBe "https://img1.wsimg.com/isteam/ip/ad795882-2f3c-4098-9227-326ffaee1e1f/a91133a5-2a8d-459b-bab4-a5e53da8adfa.webp"
        event.startTime.shouldBeNull()
        event.sourceUrl shouldBe comedyUrl
        event.sourceId shouldBe "karakas:2026-10-20-sprachenchaos"
    }

    @Test
    fun `scrape reads the quiz announcements, each on its nearest year`() {
        val events = scraper.scrape(quiz, quizUrl, EventType.QUIZ)

        events.map { it.eventDate to it.title } shouldContainExactly
            listOf(LocalDate.of(2026, 10, 18) to "K-POP QUIZ", LocalDate.of(2026, 11, 5) to "TWO BRAINS Pub Quiz")
        val quizNight = events.last()
        quizNight.eventType shouldBe "QUIZ"
        quizNight.description shouldBe "Think you are the smartass of the group? Time to prove it! Grab your friends and come to our pub quiz"
        quizNight.ticketUrl!! shouldStartWith "https://www.eventbrite.de/e/english-pub-quiz-the-late-night-show-tickets-1998815768771"
        quizNight.imageUrl shouldBe "https://img1.wsimg.com/isteam/ip/ad795882-2f3c-4098-9227-326ffaee1e1f/blob-66cf0d1.png"
    }

    @Test
    fun `a quiz date early in the year listed in the autumn is next year's`() {
        val january = Jsoup.parse(quiz.outerHtml().replace("When: 5th November", "When: 8th January"), quizUrl)

        scraper.scrape(january, quizUrl, EventType.QUIZ).last().eventDate shouldBe LocalDate.of(2027, 1, 8)
    }

    @Test
    fun `calendarScripts lists the comedy page's published scripts, the page's own first`() {
        scraper.calendarScripts(comedy) shouldContainExactly
            listOf(
                "https://img1.wsimg.com/blobby/go/ad795882-2f3c-4098-9227-326ffaee1e1f/gpub/8a1257d2a3cc8f98/script.js",
                "https://img1.wsimg.com/blobby/go/ad795882-2f3c-4098-9227-326ffaee1e1f/gpub/ac4d62496081468/script.js"
            )
    }

    @Test
    fun `calendarScripts is empty for a page without the calendar`() {
        scraper.calendarScripts(quiz).shouldBeEmpty()
    }

    @Test
    fun `scrape returns no events for an empty page`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", quizUrl), quizUrl, EventType.QUIZ).shouldBeEmpty()
    }
}
