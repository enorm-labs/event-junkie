package de.norm.events.scraper.kubiz

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class KubizApiScraperTest {
    /** Before the fixture's 2026 nights, so they read as upcoming and the 2025 ones as past. */
    private val scraper = KubizApiScraper(clockAt("2026-02-20"))

    private val fixture: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/kubiz/kubiz-posts.json")!!
            .bufferedReader()
            .readText()

    private val page = scraper.scrape(fixture)

    private fun clockAt(date: String): Clock = Clock.fixed(Instant.parse("${date}T09:00:00Z"), ZoneId.of("Europe/Berlin"))

    private fun event(id: Long): ScrapedEvent = page.events.single { it.sourceId == "kubiz:$id" }

    /** A one-post payload, published on [published]. */
    private fun post(
        title: String,
        published: String = "2024-01-02T10:00:00",
        content: String = "<h4>20 Uhr, Aula</h4><p>Text</p>"
    ): String {
        val escapedTitle = title.replace("\"", "\\\"")
        val escapedContent = content.replace("\"", "\\\"")
        return """[{"id":1,"date":"$published","link":"https://www.kubiz-wallenberg.de/?p=1",""" +
            """"title":{"rendered":"$escapedTitle"},"content":{"rendered":"$escapedContent"}}]"""
    }

    private fun scrapeOne(
        title: String,
        published: String = "2024-01-02T10:00:00",
        content: String = "<h4>20 Uhr, Aula</h4><p>Text</p>"
    ): List<ScrapedEvent> = KubizApiScraper(clockAt("2024-01-01")).scrape(post(title, published, content)).events

    @Test
    fun `keeps the upcoming dated posts and drops news, a series start and past nights`() {
        page.postCount shouldBe 22
        page.events.map { it.eventDate } shouldContainExactly
            listOf("2026-09-18", "2026-08-29", "2026-06-26", "2026-05-29", "2026-04-08", "2026-03-25", "2026-03-13", "2026-03-07", "2026-03-06", "2026-02-28")
                .map(LocalDate::parse)
    }

    @Test
    fun `parses a jazz night in full`() {
        val night = event(9045)

        night.title shouldBe "Jazzkonzert: JESSICA KANDELER AND THE MEGA NICE BAND"
        night.eventDate shouldBe LocalDate.of(2026, 3, 7)
        night.startTime shouldBe LocalTime.of(20, 0)
        night.room shouldBe "Aula"
        night.eventType shouldBe EventType.CONCERT.name
        night.sourceUrl shouldBe "https://www.kubiz-wallenberg.de/?p=9045"
        night.imageUrl shouldBe "https://www.kubiz-wallenberg.de/wp-content/uploads/2026/03/1000045726.png"
        night.description.shouldNotBeNull() shouldStartWith "JESSICA KANDELER"
        night.description.shouldNotBeNull() shouldContain "Jazz, Soul, Blues"
        night.artists.map { it.name } shouldContainExactly listOf("JESSICA KANDELER AND THE MEGA NICE BAND")
    }

    @Test
    fun `reads a trailing date with its year`() {
        val night = event(9252)

        night.title shouldBe "Jazz im KuBiZ"
        night.eventDate shouldBe LocalDate.of(2026, 9, 18)
        night.startTime.shouldBeNull()
        night.artists.shouldBeEmpty()
    }

    @Test
    fun `types each night by the house's format word`() {
        event(9192).eventType shouldBe EventType.SCREENING.name
        event(9151).eventType shouldBe EventType.SCREENING.name
        event(9051).eventType shouldBe EventType.SHOW.name
        event(9057).eventType shouldBe EventType.CONCERT.name
    }

    @Test
    fun `reads the start time from the first heading, with or without Uhr`() {
        event(9192).startTime shouldBe LocalTime.of(20, 30)
        event(9192).room.shouldBeNull()
        event(9151).startTime shouldBe LocalTime.of(20, 30)
        event(9151).room shouldBe "Offener Raum"
    }

    @Test
    fun `bills the act after a series label or inside quotes, and none for a programme name`() {
        event(9039).artists.map { it.name } shouldContainExactly listOf("KERN KLANG")
        event(9229).artists.map { it.name } shouldContainExactly listOf("FIVE FLYING Elephants")
        event(9057).artists.map { it.name } shouldContainExactly listOf("Floating Sofa Quartet")
        event(9244).artists.shouldBeEmpty()
    }

    @Test
    fun `takes the year of a date without one from the publish date, rolling into the next year`() {
        val events = scrapeOne("15.1. JAZZ IM KUBIZ: Lajos Meinberg Trio", published = "2025-12-20T10:00:00")

        events.single().eventDate shouldBe LocalDate.of(2026, 1, 15)
        events.single().artists.map { it.name } shouldContainExactly listOf("Lajos Meinberg Trio")
    }

    @Test
    fun `reads a date after a weekday`() {
        scrapeOne("Mi,18.12.24: SPY COPS (Film und Diskussionsveranstaltung)").single().let {
            it.eventDate shouldBe LocalDate.of(2024, 12, 18)
            it.title shouldBe "SPY COPS (Film und Diskussionsveranstaltung)"
            it.eventType shouldBe EventType.SCREENING.name
        }
        scrapeOne("Fr. 17.05.24 &#8211; Jazzkonzert in der Aula").single().eventDate shouldBe LocalDate.of(2024, 5, 17)
        scrapeOne("15. 11.24 Jazz").single().eventDate shouldBe LocalDate.of(2024, 11, 15)
    }

    @Test
    fun `skips a post whose title names no single date`() {
        scrapeOne("3. &#8211; 4. 7.2024 &#8222;JAZZWOCHE&#8220; IM KUBIZ").shouldBeEmpty()
        scrapeOne("27-29.9. Ausstellung NEAR MINT").shouldBeEmpty()
        scrapeOne("Einweihung im Garten am Do. 28.9 16:30").shouldBeEmpty()
        scrapeOne("Immer Donnerstags: FOOLING!").shouldBeEmpty()
        scrapeOne("32.13.24 Jazz").shouldBeEmpty()
    }

    @Test
    fun `drops participation formats`() {
        scrapeOne("22.2. »Der will mich fressen!!!« Jugend-Theaterprojekt &#8211; Schnuppertermin").shouldBeEmpty()
        scrapeOne("7.12. WHO CARES?! WORKSHOP ZU CAREARBEIT").shouldBeEmpty()
        scrapeOne("Flohmarkt 4.5.24 Ja!Space").shouldBeEmpty()
    }

    @Test
    fun `joins a title broken by a line break and bills the act after it`() {
        val night = scrapeOne("17.10.24 JAZZ IM KUBIZ:<br>AMADEO BONAIUTO QUARTET").single()

        night.title shouldBe "JAZZ IM KUBIZ: AMADEO BONAIUTO QUARTET"
        night.artists.map { it.name } shouldContainExactly listOf("AMADEO BONAIUTO QUARTET")
    }

    @Test
    fun `bills no act for a tribute`() {
        scrapeOne("11.10.24 JAZZ IM KUBIZ: HOMMAGE À COLETTE MAGNY").single().artists.shouldBeEmpty()
    }

    @Test
    fun `keeps the price line as a note and reads free entry`() {
        val paid = scrapeOne("6.3.24 Jazzkonzert: X", content = "<h4>20 Uhr, Aula</h4><p>Tickets: 12-15 Euro</p>").single()
        paid.priceNote shouldBe "12-15 Euro"
        paid.free shouldBe false

        scrapeOne("6.3.24 Jazzkonzert: X", content = "<h4>20 Uhr</h4><p>Eintritt frei</p>").single().free shouldBe true
    }

    @Test
    fun `reads no time from a heading without minutes or Uhr`() {
        scrapeOne("6.3.24 Jazz", content = "<h4>48 Tage</h4>").single().startTime.shouldBeNull()
    }

    @Test
    fun `yields an empty page for a body that is not a JSON array`() {
        scraper.scrape("not json").let {
            it.events.shouldBeEmpty()
            it.postCount shouldBe 0
        }
        scraper.scrape("{}").postCount shouldBe 0
        scraper.scrape("[]").events shouldHaveSize 0
    }
}
