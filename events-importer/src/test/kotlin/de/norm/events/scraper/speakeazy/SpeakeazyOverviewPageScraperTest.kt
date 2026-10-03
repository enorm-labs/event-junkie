package de.norm.events.scraper.speakeazy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
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

    @Test
    fun `reads the upcoming nights and drops the private party and the past ones`() {
        events shouldHaveSize 9
        events.none { it.title.contains("Private Party") } shouldBe true
        events.all { !it.eventDate.isBefore(LocalDate.of(2026, 10, 3)) } shouldBe true
    }

    @Test
    fun `maps a concert with its door price`() {
        val beatles = event("yeah-yeah-yeah-")

        beatles.title shouldBe "Yeah! Yeah! Yeah !"
        beatles.eventType shouldBe "CONCERT"
        beatles.eventDate shouldBe LocalDate.of(2026, 11, 14)
        beatles.startTime shouldBe LocalTime.of(20, 0)
        beatles.endDate shouldBe LocalDate.of(2026, 11, 14)
        beatles.endTime shouldBe LocalTime.of(23, 0)
        beatles.priceBoxOffice shouldBe BigDecimal("20")
        beatles.pricePresale.shouldBeNull()
        beatles.sourceUrl shouldBe "https://www.speakeazyberlin.de/events/yeah-yeah-yeah-"
        beatles.imageUrl!! shouldStartWith "https://images.squarespace-cdn.com/"
        beatles.description!! shouldStartWith "Yeah! Yeah! Yeah!\nThe Beatles Tribute Band"
        beatles.description shouldNotContain "Abendkasse"
    }

    @Test
    fun `splits a double bill into its two acts`() {
        event("the-reveries").artists.map { it.name } shouldContainExactly listOf("AK In Control", "The Reveries")
    }

    @Test
    fun `keeps a duo whole before its programme name`() {
        val dylan = event("annherung-an-bob-dylan")

        dylan.title shouldBe "Stock & Pankow: Annäherung an Bob Dylan"
        dylan.artists.map { it.name } shouldContainExactly listOf("Stock & Pankow")
    }

    @Test
    fun `bills no act for a house session`() {
        event("bassball-ost-west-session").artists.shouldBeEmpty()
        event("bassball-singer-songwriter-session-rybgg-7jwze-x5zzd").artists.shouldBeEmpty()
    }

    @Test
    fun `drops a blurb that is still to come but keeps the price`() {
        val blues = event("the-sixth-dimension-blues")

        blues.description.shouldBeNull()
        blues.priceBoxOffice shouldBe BigDecimal("16")
    }

    @Test
    fun `reads the regular price from a qualified door price`() {
        event("markus-siebert-band").priceBoxOffice shouldBe BigDecimal("26")
    }

    @Test
    fun `returns no events for a page without a listing`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", sourceUrl), sourceUrl).shouldBeEmpty()
    }
}
