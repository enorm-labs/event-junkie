package de.norm.events.scraper.mbia

import de.norm.events.common.BERLIN
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate

/** Unit tests for [MbiaApiScraper], against the posts listing captured on 2026-10-17. */
class MbiaApiScraperTest {
    private val clock: Clock = Clock.fixed(LocalDate.of(2026, 10, 17).atStartOfDay(BERLIN).toInstant(), BERLIN)
    private val scraper = MbiaApiScraper(clock)

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/mbia/mbia-posts.json")!!
            .bufferedReader()
            .readText()

    @Test
    fun `scrape keeps tonight and later and drops the past night`() {
        val page = scraper.scrape(fixture())

        page.postCount shouldBe 8
        page.items shouldHaveSize 7
        page.items.map { it.eventDate }.minOrNull() shouldBe LocalDate.of(2026, 10, 17)
        page.items.none { it.title == "Bassquake by Vero" } shouldBe true
        page.oldestDate shouldBe LocalDate.of(2026, 10, 16)
    }

    @Test
    fun `scrape maps a post onto a full event`() {
        val event = scraper.scrape(fixture()).items.single { it.sourceId == "m_bia:4664" }

        event.title shouldBe "Rave Cage with Dr. Sheppat & NYXEA"
        event.eventDate shouldBe LocalDate.of(2026, 10, 23)
        event.eventType shouldBe EventType.PARTY.name
        event.sourceUrl shouldBe "https://www.m-bia.de/rave-cage-with-dr-sheppat-nyxea/"
        event.imageUrl shouldBe "https://www.m-bia.de/wp-content/uploads/2026/10/Bild-46.jpeg"
        event.ticketUrl shouldBe "https://de.ra.co/events/2542269"
        event.description.shouldBeNull()
        event.startTime.shouldBeNull()
        event.doorsTime.shouldBeNull()
        event.artists.map { it.name } shouldContainExactly listOf("Dr. Sheppat", "NYXEA")
        event.artists.all { it.role == ArtistRole.DJ } shouldBe true
    }

    @Test
    fun `scrape reads the acts behind each title marker`() {
        val acts = scraper.scrape(fixture()).items.associate { it.title to it.artists.map { a -> a.name } }

        acts["GOA Herbst Edition [pres. Naturalize, Vertigos]"] shouldBe listOf("Naturalize", "Vertigos")
        acts["9Y TEKKNOLOGIE • D-FENCE • FABITEKK"] shouldBe listOf("D-FENCE", "FABITEKK")
        acts["Goanautika w./ Bliss"] shouldBe listOf("Bliss")
        acts["Freakquency"].orEmpty().shouldBeEmpty()
        acts["Intoxication Halloween SPECIAL"].orEmpty().shouldBeEmpty()
    }

    @Test
    fun `scrape keeps prose beside the link as the description`() {
        val json =
            """[{"id":7,"date":"2026-10-30T23:00:00","link":"https://www.m-bia.de/x/","title":{"rendered":"Night"},""" +
                """"content":{"rendered":"<p>Psytrance all night.</p><p><a href=\"https://de.ra.co/events/1\">https://de.ra.co/events/1</a></p>"}}]"""

        val event = scraper.scrape(json).items.single()

        event.description shouldBe "Psytrance all night."
        event.ticketUrl shouldBe "https://de.ra.co/events/1"
        event.imageUrl.shouldBeNull()
    }

    @Test
    fun `scrape skips a post without a title or a parseable date`() {
        val json =
            """[{"id":1,"date":"2026-10-30T23:00:00","title":{"rendered":"  "}},""" +
                """{"id":2,"date":"soon","title":{"rendered":"Night"}}]"""

        val page = scraper.scrape(json)

        page.items.shouldBeEmpty()
        page.postCount shouldBe 2
    }

    @Test
    fun `scrape returns an empty page for a body that is not an array`() {
        scraper.scrape("""{"code":"rest_no_route"}""").postCount shouldBe 0
        scraper.scrape("not json").items.shouldBeEmpty()
    }
}
