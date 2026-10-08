package de.norm.events.scraper.showfenster

import de.norm.events.scraper.ScrapedEvent
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class ShowfensterOverviewPageScraperTest {
    private val events: List<ScrapedEvent> by lazy {
        val html = javaClass.getResource("/scraper/showfenster/showfenster-overview.html")!!.readText()
        ShowfensterOverviewPageScraper().scrape(Jsoup.parse(html, BASE_URL), BASE_URL)
    }

    private fun on(
        date: String,
        titleStart: String
    ): ScrapedEvent = events.single { it.eventDate == LocalDate.parse(date) && it.title.startsWith(titleStart) }

    @Test
    fun `reads every event in scope, with past nights the upsert drops`() {
        // 56 in the payload: 7 children's shows and 3 swing course nights are out of scope.
        events shouldHaveSize 46
    }

    @Test
    fun `reads a concert in full`() {
        val event = on("2026-10-17", "Nadia Lafi")
        assertSoftly(event) {
            title shouldBe "Nadia Lafi - Konzert"
            eventType shouldBe "CONCERT"
            startTime shouldBe LocalTime.of(19, 30)
            endDate shouldBe LocalDate.of(2026, 10, 17)
            endTime shouldBe LocalTime.of(22, 0)
            description shouldBe "Jazz, Swing, Chanson"
            genre shouldBe "Jazz, Blues"
            artists.map { artist -> artist.name } shouldBe listOf("Nadia Lafi")
            sourceUrl shouldBe "https://www.showfenster-show.de/event-details/nadia-lafi-konzert"
            sourceId shouldBe "showfenster:$NADIA_LAFI_ID"
            ticketUrl shouldBe "https://eventfrog.de/de/p/konzerte/jazz-blues/nadia-lafi-konzert-7480216959897868472.html"
            imageUrl?.startsWith("https://static.wixstatic.com/media/") shouldBe true
            status shouldBe "SCHEDULED"
            soldOut shouldBe false
            free shouldBe false
        }
    }

    @Test
    fun `types each event from its Eventfrog category`() {
        assertSoftly {
            on("2026-10-10", "Max Olbrich").eventType shouldBe "COMEDY"
            on("2026-10-09", "Winterfeld & Lang").eventType shouldBe "READING"
            on("2026-10-18", "Die Showfenster").eventType shouldBe "SHOW"
            on("2026-09-23", "Lina").eventType shouldBe "EXHIBITION"
            on("2026-10-08", "Das Kneipenquiz").eventType shouldBe "QUIZ"
            on("2026-11-01", "Appetithäppchen").eventType shouldBe "SHOW"
        }
    }

    @Test
    fun `drops children's shows and the swing course`() {
        val titles = events.map { it.title }
        assertSoftly {
            titles shouldNotContain "Der Grüne Georg"
            titles shouldNotContain "Ute Kahrmann - Rapunzel"
            titles shouldNotContain "Lette ´t Swing"
            events.none { it.title.startsWith("Lette") } shouldBe true
        }
    }

    @Test
    fun `reads a cancellation note in the title, then cuts it`() {
        val cancelled = on("2026-09-20", "Lina Lärche")
        val ill = on("2026-10-07", "Melanie Haupt")
        assertSoftly {
            cancelled.title shouldBe "Lina Lärche - Witz & Donner"
            cancelled.status shouldBe "CANCELLED"
            cancelled.statusNote shouldBe "fällt aus, nächster Termin am 23.10."
            on("2026-09-27", "Die Showfenster").title shouldBe "Die Showfenster - Varieté - Show"
            ill.title shouldBe "Melanie Haupt"
            ill.status shouldBe "CANCELLED"
        }
    }

    @Test
    fun `keys an event on its Wix id, because the slug follows the title`() {
        // The slug of the cancelled date is `lina-larche-witz-donner-fallt-aus-nachster-termin-am-23-10`.
        on("2026-09-20", "Lina Lärche").sourceId shouldBe "showfenster:a8763309-d11f-4b5f-ab81-1ca15ac53961"
    }

    @Test
    fun `reads a sold-out note and cuts it`() {
        val event = on("2026-10-28", "Ludger K.")
        assertSoftly(event) {
            title shouldBe "Ludger K. - Verstehen Sie DOITSCH?"
            soldOut shouldBe true
            artists.map { artist -> artist.name } shouldBe listOf("Ludger K.")
        }
    }

    @Test
    fun `marks free entry from the description, but not a fairy tale told freely`() {
        val openStage = on("2026-10-15", "Offene Bühne")
        assertSoftly {
            openStage.free shouldBe true
            openStage.ticketUrl shouldBe null
            openStage.eventType shouldBe "OTHER"
            // "Frei nach dem Märchen …"
            on("2026-11-04", "Puppentheater Don Oswaldo").free shouldBe false
        }
    }

    @Test
    fun `bills no act for the house's own formats or a title without a dash`() {
        assertSoftly {
            on("2026-10-04", "Die Gold und Tier Show").artists.shouldBeEmpty()
            on("2026-10-30", "Wunschkonzert").artists.shouldBeEmpty()
            on("2026-10-22", "Die Showfenster").artists.shouldBeEmpty()
            on("2026-10-03", "Gerd Normann").artists.map { it.name } shouldBe listOf("Gerd Normann", "Lina Lärche")
        }
    }

    private companion object {
        const val BASE_URL = "https://www.showfenster-show.de/%C3%BCbersichtskalender"
        const val NADIA_LAFI_ID = "67909bde-802b-47f7-90c3-68e433d073ab"
    }
}
