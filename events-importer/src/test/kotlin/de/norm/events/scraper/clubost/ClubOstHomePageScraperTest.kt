package de.norm.events.scraper.clubost

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class ClubOstHomePageScraperTest {
    private val url = "https://clubost.de/"
    private val scraper = ClubOstHomePageScraper()
    private val events = scraper.scrape(fixtureDocument("clubost/clubost-home.html", url), url)

    private fun on(date: String) = events.single { it.eventDate == LocalDate.parse(date) }

    private fun scrapeJson(json: String) = scraper.scrape(Jsoup.parse("""<script type="application/json" id="events-data">$json</script>""", url), url)

    @Test
    fun `reads every event in the block`() {
        events shouldHaveSize 13
    }

    @Test
    fun `reads a night's fields and takes the door note out of the title`() {
        val savory = on("2026-11-26")
        savory.title shouldBe "SAVORY"
        savory.priceNote shouldBe "FREE ENTRY TILL 00:30"
        savory.startTime shouldBe LocalTime.of(23, 0)
        savory.endDate shouldBe LocalDate.of(2026, 11, 27)
        savory.endTime shouldBe LocalTime.of(6, 0)
        savory.eventType shouldBe "PARTY"
        savory.sourceId shouldBe "club_ost:3005038e-4723-4115-b203-e59e5c461724"
        savory.sourceUrl shouldBe "https://clubost.de/#programme"
        savory.ticketUrl shouldBe "https://ra.co/events/2569654"
        savory.imageUrl shouldBe
            "https://ost.klubtool.de/api/public/artwork/3005038e-4723-4115-b203-e59e5c461724/1834e6b7-408f-442a-9255-adcd2644755e.png"
    }

    @Test
    fun `a night on one floor has a room and no stages`() {
        val savory = on("2026-11-26")
        savory.room shouldBe "Schmiede"
        savory.artists.map { it.stage }.distinct() shouldContainExactly listOf(null)
        savory.artists.map { it.name } shouldContainExactly listOf("Nassia", "Hanne B", "Cobb Douglas")
        savory.artists.map { it.role }.distinct() shouldContainExactly listOf(ArtistRole.DJ)
    }

    @Test
    fun `a set after midnight takes its day from the offset`() {
        val acts = on("2026-11-26").artists
        acts[0].setStart shouldBe Instant.parse("2026-11-26T22:00:00Z")
        acts[0].setEnd shouldBe Instant.parse("2026-11-27T00:30:00Z")
        acts[2].setEnd shouldBe Instant.parse("2026-11-27T05:00:00Z")
    }

    @Test
    fun `a night on two floors has stages and no room`() {
        val night = on("2026-11-27")
        night.room.shouldBeNull()
        night.artists.first { it.name == "Eza" }.stage shouldBe "Lager"
        night.artists.first { it.name == "Bishalbacht" }.stage shouldBe "Schmiede"
    }

    @Test
    fun `the Intro slot is not an act`() {
        on("2026-11-27").artists.map { it.name } shouldNotContain "Intro"
    }

    @Test
    fun `each DJ of a back-to-back keeps the slot's floor and times`() {
        val pair = on("2026-12-31").artists.filter { it.name in setOf("Sparkly Pony", "Gerald") }
        pair.map { it.name } shouldContainExactly listOf("Sparkly Pony", "Gerald")
        pair.map { Triple(it.stage, it.setStart, it.setEnd) }.distinct() shouldContainExactly
            listOf(Triple("Lager", Instant.parse("2027-01-01T01:00:00Z"), Instant.parse("2027-01-01T04:00:00Z")))
    }

    @Test
    fun `New Year's Eve ends on its stated date and its sets cross both midnights`() {
        val night = on("2026-12-31")
        night.endDate shouldBe LocalDate.of(2027, 1, 2)
        night.endTime shouldBe LocalTime.of(10, 0)
        night.artists.first().setStart shouldBe Instant.parse("2026-12-31T22:55:00Z")
        night.artists.last().setEnd shouldBe Instant.parse("2027-01-02T09:00:00Z")
    }

    @Test
    fun `a booking without floor and times is an act with neither`() {
        val act = on("2026-12-05").artists.first()
        act.name shouldBe "Diffrent"
        act.stage.shouldBeNull()
        act.setStart.shouldBeNull()
        act.setEnd.shouldBeNull()
        on("2026-12-05").room.shouldBeNull()
    }

    @Test
    fun `a night without bookings has no acts`() {
        on("2027-09-18").artists.shouldBeEmpty()
    }

    @Test
    fun `skips a row without id, title or date and keeps the rest`() {
        val rows =
            """[{"title":"A","date":"2026-12-01"},{"id":"1","date":"2026-12-01"},{"id":"2","title":"B","date":"soon"},{"id":"3","title":"C","date":"2026-12-01"}]"""
        val event = scrapeJson(rows).single()
        event.title shouldBe "C"
        event.endDate.shouldBeNull()
        event.ticketUrl.shouldBeNull()
    }

    @Test
    fun `a missing, malformed or non-array block yields no events`() {
        scraper.scrape(Jsoup.parse("<html></html>", url), url).shouldBeEmpty()
        scrapeJson("[{").shouldBeEmpty()
        scrapeJson("{}").shouldBeEmpty()
    }

    @Test
    fun `splitDoorNote keeps a title that is nothing but a bracket`() {
        splitDoorNote("Slave to the rave") shouldBe ("Slave to the rave" to null)
        splitDoorNote("[TBA]") shouldBe ("[TBA]" to null)
    }
}
