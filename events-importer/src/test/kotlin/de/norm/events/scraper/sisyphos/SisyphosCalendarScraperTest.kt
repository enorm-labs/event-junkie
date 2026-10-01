package de.norm.events.scraper.sisyphos

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class SisyphosCalendarScraperTest {
    private val scraper = SisyphosCalendarScraper()

    private val fixtureJson: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/sisyphos/sisyphos-calendar.json")!!
            .bufferedReader()
            .readText()

    private val events = scraper.scrape(fixtureJson)

    private fun night(title: String) = events.single { it.title == title }

    @Test
    fun `every entry is a party keyed on its Berlin opening date`() {
        events shouldHaveSize 9
        events.map { it.eventType }.toSet() shouldBe setOf("PARTY")
        night("SISY 54 - NICHTGEBURTSTAG").sourceId shouldBe "sisyphos:2026-10-02"
    }

    @Test
    fun `a weekend runs from its opening to its closing in Berlin time`() {
        val weekend = night("SISY 54 - NICHTGEBURTSTAG")
        weekend.eventDate shouldBe LocalDate.of(2026, 10, 2)
        weekend.startTime shouldBe LocalTime.of(22, 0)
        weekend.endDate shouldBe LocalDate.of(2026, 10, 6)
        weekend.endTime shouldBe LocalTime.of(10, 0)
        weekend.sourceUrl shouldBe "https://www.sisyphos-berlin.net/"
    }

    @Test
    fun `a closing after the switch to winter time stays 10 00`() {
        val weekend = night("RAVE.IOLI")
        weekend.endDate shouldBe LocalDate.of(2026, 10, 26)
        weekend.endTime shouldBe LocalTime.of(10, 0)
    }

    @Test
    fun `the New Year's party opens on the first of January`() {
        val party = night("SILVESTER")
        party.eventDate shouldBe LocalDate.of(2027, 1, 1)
        party.startTime shouldBe LocalTime.of(1, 0)
    }

    @Test
    fun `a ticketed night trims its title and links its shop product`() {
        val generations = night("generationS")
        generations.sourceId shouldBe "sisyphos:2026-10-10"
        generations.startTime shouldBe LocalTime.of(14, 0)
        generations.endDate shouldBe LocalDate.of(2026, 10, 11)
        generations.endTime shouldBe LocalTime.of(6, 0)
        generations.ticketUrl shouldBe "https://www.sisyphos-berlin.net/products/generations-10-okt-2026"
    }

    @Test
    fun `the default Party subtitle is dropped and a real one kept`() {
        night("HAPPY RAVE HAPPY LIFE").subtitle.shouldBeNull()
        night("SISYPHOS WINTERMARKT").subtitle shouldBe "Kunst und Klamotte"
        night("SAUNIPHOS").subtitle shouldBe "Samstag 14-8 / Sonntag 12-0"
    }

    @Test
    fun `an Eintritt frei subtitle makes the night free`() {
        val openDay = night("TAG DES OFFENEN TORS")
        openDay.free shouldBe true
        openDay.subtitle.shouldBeNull()
        night("HAPPY RAVE HAPPY LIFE").free shouldBe false
    }

    @Test
    fun `a stock placeholder image is left out and the club's own kept`() {
        night("RAVEOLUTION").imageUrl.shouldBeNull()
        night("SISY 54 - NICHTGEBURTSTAG").imageUrl!! shouldStartWith "https://dashboard.sisyphos-berlin.net/"
    }

    @Test
    fun `the blurb keeps one line per paragraph`() {
        val description = night("SISY 54 - NICHTGEBURTSTAG").description!!
        description.lines().first() shouldBe
            "Sisyphos wird endlich 18 – und das feiern wir mit einem Motto, das wie kaum ein anderes für Exzess, Lebenslust und legendäre Nächte steht: Sisy54!🪩"
        description.lines() shouldContain "KULTURPROGRAMM"
        description.lines() shouldContain "MINIPLAYBACKSHOW"
    }

    @Test
    fun `a malformed entry is skipped and the rest kept`() {
        val json =
            """[{"title": "Broken", "startRaw": "not a date"},
               {"title": "Kept", "startRaw": "2026-10-16T20:00:00.000Z", "endRaw": "2026-10-19T08:00:00.000Z"}]"""
        scraper.scrape(json).map { it.title } shouldBe listOf("Kept")
    }

    @Test
    fun `an entry without a title or an opening is skipped`() {
        val json = """[{"startRaw": "2026-10-16T20:00:00.000Z"}, {"title": "No opening"}]"""
        scraper.scrape(json) shouldHaveSize 0
    }

    @Test
    fun `two entries opening on one day keep the first`() {
        val json =
            """[{"title": "First", "startRaw": "2026-10-16T20:00:00.000Z"},
               {"title": "Second", "startRaw": "2026-10-16T21:00:00.000Z"}]"""
        scraper.scrape(json).map { it.title } shouldBe listOf("First")
    }

    @Test
    fun `a payload that is not an array yields nothing`() {
        scraper.scrape("""{"events": []}""") shouldHaveSize 0
        scraper.scrape("not json") shouldHaveSize 0
    }
}
