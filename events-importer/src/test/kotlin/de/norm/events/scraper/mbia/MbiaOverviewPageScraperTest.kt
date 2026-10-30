package de.norm.events.scraper.mbia

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MbiaOverviewPageScraperTest {
    private val scraper = MbiaOverviewPageScraper()
    private val baseUrl = "https://www.m-bia.de/veranstaltungen"

    private fun scrape(fixture: String) =
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/mbia/$fixture")!!
                    .bufferedReader()
                    .readText(),
                baseUrl
            ),
            baseUrl
        )

    private val events by lazy { scrape("mbia-veranstaltungen.html").associateBy { it.title } }

    @Test
    fun `reads every record of the collection, past nights included`() {
        events.keys shouldContainExactlyInAnyOrder
            listOf(
                "Silvester Bäng 2026 by Intoxication",
                "Rave Cage w/ Blondee & Bassmassage",
                "GOA MAGIC pres. REVERENCE",
                "9Y TEKKNOLOGIE",
                "Nyctophilia Halloween SPCL",
                "Hypenotic",
                "Inception with Luminatix",
                "VEXED presents BERBA",
                "Sanctus pres. Diztorted [Belfast]"
            )
    }

    @Test
    fun `keeps the Berlin date and drops the time no page prints`() {
        // 2026-10-30T21:00Z is 22:00 in winter time; 2026-03-20T20:00Z is 21:00 before the clocks change.
        events.getValue("Nyctophilia Halloween SPCL").eventDate shouldBe LocalDate.of(2026, 10, 30)
        events.getValue("Hypenotic").eventDate shouldBe LocalDate.of(2026, 3, 20)
        events.values.forEach { it.startTime.shouldBeNull() }
    }

    @Test
    fun `maps a record to a club night with its page, styles, flyer and id`() {
        val night = events.getValue("9Y TEKKNOLOGIE")

        night.eventType shouldBe EventType.PARTY.name
        night.sourceId shouldBe "m_bia:06b37d6c-b323-4902-8a99-e5cf415470aa"
        night.sourceUrl shouldBe "https://www.m-bia.de/events/efn-november-2026"
        night.genre shouldBe "Tekkno, Hardtekk, Hardtechno"
        night.imageUrl shouldBe "https://static.wixstatic.com/media/f2ae2b_7a2dde53600044ad82832dd41c344a21~mv2.jpg"
        night.description.shouldBeNull()
    }

    @Test
    fun `keeps a flyer stored as a plain URL`() {
        events.getValue("GOA MAGIC pres. REVERENCE").imageUrl!! shouldStartWith "https://lh3.googleusercontent.com/d/"
    }

    @Test
    fun `reads the description and trims a title's trailing newline`() {
        events.getValue("Inception with Luminatix").description!! shouldStartWith "14 | NOV"
    }

    @Test
    fun `keeps a ticket link only when it sells one night`() {
        events.getValue("Nyctophilia Halloween SPCL").ticketUrl shouldBe "https://de.ra.co/events/2481473"
        events.getValue("Inception with Luminatix").ticketUrl!! shouldStartWith "https://www.eventbrite.de/e/"
        events.getValue("Rave Cage w/ Blondee & Bassmassage").ticketUrl.shouldBeNull()
    }

    @Test
    fun `names the floor of each act when both floors have acts, without the style line`() {
        events.getValue("Nyctophilia Halloween SPCL").artists shouldContainExactly
            listOf("Vansessy", "NYXEA", "SON!CA", "Kimberly", "Särathonin").map { ScrapedArtist(it, ArtistRole.DJ, stage = "Arena Floor") } +
            listOf("Alica Røugh", "Shanty", "Erin_onair", "Lisa Mona").map { ScrapedArtist(it, ArtistRole.DJ, stage = "Bunker Floor") }
    }

    @Test
    fun `drops emoji, a floor note and a placeholder`() {
        events.getValue("GOA MAGIC pres. REVERENCE").artists.map { it.name to it.stage } shouldContainExactly
            listOf("Reverence", "Daora", "Lewii", "Baraquz", "John Kotti", "Joki").map { it to "Arena Floor" } + ("Støffwechsel" to "Bunker Floor")
        events.getValue("Rave Cage w/ Blondee & Bassmassage").artists shouldContainExactly
            listOf(ScrapedArtist("Blondee", ArtistRole.DJ), ScrapedArtist("Bassmassage", ArtistRole.DJ))
    }

    @Test
    fun `drops a contest slot and the city an act comes from`() {
        events
            .getValue("VEXED presents BERBA")
            .artists
            .map { it.name }
            .none { it.contains("CONTEST", ignoreCase = true) } shouldBe true
        events
            .getValue("Sanctus pres. Diztorted [Belfast]")
            .artists
            .first()
            .name shouldBe "Diztorted"
    }

    @Test
    fun `reads a trailing LIVE as a live set`() {
        val acts = events.getValue("9Y TEKKNOLOGIE").artists

        acts.take(2) shouldContainExactly listOf(ScrapedArtist("D-FENCE", ArtistRole.LIVE), ScrapedArtist("FABITEKK", ArtistRole.LIVE))
        acts.map { it.role }.distinct() shouldContainExactly listOf(ArtistRole.LIVE)
    }

    @Test
    fun `a night without a lineup or a title marker has no acts`() {
        events.getValue("Silvester Bäng 2026 by Intoxication").artists.shouldBeEmpty()
    }

    @Test
    fun `a night without a lineup takes its acts from the title`() {
        actsFromTitle("Rave Cage with Dr. Sheppat & NYXEA").map { it.name } shouldContainExactly listOf("Dr. Sheppat", "NYXEA")
        actsFromTitle("GOA Herbst Edition [pres. Naturalize, Vertigos]").map { it.name } shouldContainExactly listOf("Naturalize", "Vertigos")
        actsFromTitle("Freakquency").shouldBeEmpty()
    }

    @Test
    fun `a Goa night named in the title gets the Goa style`() {
        mbiaGenre(listOf("Progressive Psytrance", "Proggy"), "GOA MAGIC pres. REVERENCE") shouldBe "Progressive Psytrance, Proggy, Goa"
        mbiaGenre(listOf("Hard Trance"), "Goanautika Nikolaus Special") shouldBe "Hard Trance, Goa"
        mbiaGenre(listOf("Techno"), "Goalkeeper Rave") shouldBe "Techno"
        mbiaGenre(emptyList(), "Inception").shouldBeNull()
    }

    @Test
    fun `reads a Wix media reference as its static URL`() {
        imageUrl("wix:image://v1/f2ae2b_ab~mv2.png/Flyer%201.png#originWidth=1080&originHeight=1350") shouldBe
            "https://static.wixstatic.com/media/f2ae2b_ab~mv2.png"
        imageUrl("wix:vector://v1/x.svg/y").shouldBeNull()
    }

    @Test
    fun `a page without the collection yields nothing`() {
        scrape("mbia-veranstaltungen-no-collection.html").shouldBeEmpty()
    }
}
