package de.norm.events.scraper.yaam

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class YaamApiScraperTest {
    private val scraper = YaamApiScraper()
    private val page =
        scraper.scrapePage(
            javaClass.classLoader
                .getResourceAsStream("scraper/yaam/yaam-events.json")!!
                .bufferedReader()
                .readText()
        )

    private fun event(slug: String) = page.events.single { it.sourceId == "yaam:$slug" }

    @Test
    fun `reads every event of the one page`() {
        page.events.map { it.sourceId } shouldContainExactly listOf("yaam:noanda-soul-jam-10-11-2026", "yaam:noanda-soul-jam-08-12-2026")
        page.nextPageUrl.shouldBeNull()
    }

    @Test
    fun `maps a jam session with its excerpt text and full-size flyer`() {
        val jam = event("noanda-soul-jam-10-11-2026")

        jam.title shouldBe "Noanda Soul Jam"
        jam.eventType shouldBe EventType.CONCERT.name
        jam.typeIsFallback shouldBe false
        jam.eventDate shouldBe LocalDate.of(2026, 11, 10)
        jam.startTime shouldBe LocalTime.of(21, 0)
        jam.endDate shouldBe LocalDate.of(2026, 11, 10)
        jam.endTime shouldBe LocalTime.of(23, 30)
        jam.sourceUrl shouldBe "https://yaam.de/event/noanda-soul-jam-10-11-2026/"
        jam.imageUrl shouldBe "https://yaam.de/wp-content/uploads/2026/10/A11A8822-F842-4D80-9D1E-161B5EA363CD.png"
        jam.ticketUrl.shouldBeNull()
        jam.artists.shouldBeEmpty()
        jam.description!! shouldStartWith "🎺 NOANDA SOUL JAM SESSION @ YAAM BERLIN"
        jam.description shouldContain "Soul, Jazz, Funk & Groove – live am Strand!\n"
        jam.description shouldNotContain "&#038;"
    }

    @Test
    fun `reads the start date, not the date typed into the title`() {
        val jam = event("noanda-soul-jam-08-12-2026")

        jam.title shouldBe "Noanda Soul Jam"
        jam.eventDate shouldBe LocalDate.of(2026, 11, 10)
        jam.imageUrl shouldBe "https://yaam.de/wp-content/uploads/2026/10/5B164CE1-EA06-431B-979C-93927AA120D2-3.png"
    }

    @Test
    fun `types an unmarked night as a fallback and keeps a featured image`() {
        val json =
            """
            {"events": [{"slug": "reggae-sunday", "title": "Reggae Sunday", "url": "https://yaam.de/event/reggae-sunday/",
              "start_date": "2026-11-15 16:00:00", "end_date": "2026-11-15 16:00:00",
              "image": {"url": "https://yaam.de/wp-content/uploads/2026/11/reggae.jpg"}, "website": "https://tickets.example/1"}]}
            """.trimIndent()

        val night = scraper.scrapePage(json).events.single()

        night.eventType shouldBe EventType.OTHER.name
        night.typeIsFallback shouldBe true
        night.imageUrl shouldBe "https://yaam.de/wp-content/uploads/2026/11/reggae.jpg"
        night.ticketUrl shouldBe "https://tickets.example/1"
        night.endTime.shouldBeNull()
        night.description.shouldBeNull()
    }

    @Test
    fun `skips an event without a title or a start`() {
        val json =
            """
            {"events": [{"slug": "a", "title": "", "url": "https://yaam.de/event/a/", "start_date": "2026-11-15 16:00:00"},
                        {"slug": "b", "title": "B", "url": "https://yaam.de/event/b/"}]}
            """.trimIndent()

        scraper.scrapePage(json).events.shouldBeEmpty()
    }

    @Test
    fun `an unparseable body is an empty last page`() {
        val result = scraper.scrapePage("<html>")

        result.events.shouldBeEmpty()
        result.nextPageUrl.shouldBeNull()
    }
}
