package de.norm.events.scraper.zenner

import de.norm.events.common.BERLIN
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [ZennerApiScraper], parsing the saved `/page-data/programm/page-data.json`
 * snapshot.
 *
 * The artefact carries the venue's whole archive (115 nodes spanning 2023–2026), so every
 * test pins a fixed [Clock] — the scraper's past-event cutoff would otherwise make the
 * expected results drift with the wall clock. [CUTOFF_CLOCK] is set to the snapshot's
 * 19 July 2026 so the one cancelled event in the fixture is still in scope.
 */
class ZennerApiScraperTest {
    private val fixtureJson: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/zenner/zenner-page-data.json")!!
            .bufferedReader()
            .readText()

    private val scraper = ZennerApiScraper(CUTOFF_CLOCK)

    private fun scrape() = scraper.scrape(fixtureJson, SOURCE_URL)

    @Test
    fun `scrape extracts every published upcoming event`() {
        // 115 nodes in the artefact; all but these are past-dated or in an unpublished room.
        scrape().map { it.title } shouldContainExactly
            listOf(
                "SIP!",
                "SIP! w/ Haseeb Iqbal (All Day Long)",
                "SIP! w/ Coco Maria (All Day Long)",
                "SIP! Closing",
                "180 min w/ Barker (live)",
                "NICE ONE WEEKENDER",
                "Trinity presents: Nathan Fake",
                "LITTLEBIG",
                "180 min w/ Polygonia (live)"
            )
    }

    @Test
    fun `scrape maps every field of a fully-populated concert`() {
        val event = scrape().single { it.title == "180 min w/ Barker (live)" }

        event.sourceId shouldBe "zenner:3de1d1a3-591c-50c9-86c9-69f8bd55a4e8"
        event.sourceUrl shouldBe SOURCE_URL
        event.eventType shouldBe EventType.CONCERT.name
        event.eventDate shouldBe LocalDate.of(2026, 9, 24)
        // 17:00Z is 19:00 in Berlin — the venue's own page renders the converted time.
        event.startTime shouldBe LocalTime.of(19, 0)
        event.doorsTime.shouldBeNull()
        event.ticketUrl shouldBe "https://ra.co/events/2378121"
        event.imageUrl shouldStartWith "https://cdn.sanity.io/images/2le2eemj/production/"
        event.status shouldBe EventStatus.SCHEDULED.name
        event.description.shouldNotBeNull() shouldStartWith "A key figure in Berlin"
        event.artists.map { it.name to it.role } shouldContainExactly listOf("Barker" to ArtistRole.HEADLINER)
        // The venue publishes no prices, doors times, sold-out state or genre.
        event.pricePresale.shouldBeNull()
        event.priceBoxOffice.shouldBeNull()
        event.genre.shouldBeNull()
        event.soldOut shouldBe false
        event.subtitle.shouldBeNull()
    }

    @Test
    fun `scrape converts the UTC eventDate instant to the venue's Berlin wall clock`() {
        // Every fixture event is stored as a UTC instant and rendered converted on the site.
        val summer = scrape().single { it.title == "SIP! w/ Haseeb Iqbal (All Day Long)" }
        // 2026-08-09T13:00Z → 15:00 CEST (UTC+2)
        summer.eventDate shouldBe LocalDate.of(2026, 8, 9)
        summer.startTime shouldBe LocalTime.of(15, 0)

        val winter = scrape().single { it.title == "180 min w/ Polygonia (live)" }
        // 2026-11-05T18:00Z → 19:00 CET (UTC+1)
        winter.eventDate shouldBe LocalDate.of(2026, 11, 5)
        winter.startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `scrape reads a cancellation announced only in the blurb`() {
        val cancelled = scrape().single { it.title == "SIP!" }

        cancelled.status shouldBe EventStatus.CANCELLED.name
        cancelled.description.shouldNotBeNull() shouldStartWith "Wegen schlechten Wetters abgesagt!"
    }

    @Test
    fun `scrape skips events in a room the venue has unpublished`() {
        // The Biergarten flag is false in the snapshot, hiding its public-viewing programme.
        scrape().none { it.title.startsWith("WM-Finale") } shouldBe true
    }

    @Test
    fun `scrape drops past events`() {
        // Same fixture, a clock eleven days later: only the 19 July event falls out.
        val later = ZennerApiScraper(fixedClock("2026-07-30T09:00:00Z")).scrape(fixtureJson, SOURCE_URL)

        later shouldHaveSize 8
        later.none { it.title == "SIP!" } shouldBe true
    }

    @Test
    fun `scrape trusts the venue's own kind labels`() {
        scrape().single { it.title == "LITTLEBIG" }.eventType shouldBe EventType.PARTY.name
        scrape().single { it.title == "Trinity presents: Nathan Fake" }.eventType shouldBe EventType.CONCERT.name
        scrape().single { it.title == "180 min w/ Barker (live)" }.eventType shouldBe EventType.CONCERT.name
    }

    @Test
    fun `scrape types a Weingarten open air as the SIP day party it is`() {
        scrape().filter { it.title.startsWith("SIP!") }.forAll { it.eventType shouldBe EventType.PARTY.name }
    }

    @Test
    fun `scrape leaves a Biergarten open air to the title cue rather than typing it a party`() {
        // The same "Open Air" label covers the beer garden's ice disco and festival
        // days, so the room — not the label — decides. (Synthetic: the snapshot's Biergarten
        // programme is unpublished, and an absent flags block treats every room as published.)
        val payload =
            """
            {"result":{"data":{"queryKultur":{"nodes":[
              {"id":"-ice","title":"Eisdisko","typeOfEvent":"Open Air","place":"Biergarten","eventDate":"2026-09-01T18:00:00.000Z"},
              {"id":"-fest","title":"TU.PI Festival","typeOfEvent":"Open Air","place":"Biergarten","eventDate":"2026-09-02T18:00:00.000Z"},
              {"id":"-sip","title":"SIP!","typeOfEvent":"Open Air","place":"Weingarten","eventDate":"2026-09-03T18:00:00.000Z"}
            ]}}}}
            """.trimIndent()

        val byTitle = scraper.scrape(payload, SOURCE_URL).associateBy { it.title }

        byTitle.getValue("Eisdisko").eventType shouldBe EventType.OTHER.name
        byTitle.getValue("SIP!").eventType shouldBe EventType.PARTY.name
        // A festival title stays OTHER so the persistence boundary can promote it to FESTIVAL,
        // which it only does for CONCERT/OTHER — a PARTY here would suppress that.
        byTitle.getValue("TU.PI Festival").eventType shouldBe EventType.OTHER.name
    }

    @Test
    fun `scrape exempts a festival title in the Weingarten from the day-party rule`() {
        val payload =
            """
            {"result":{"data":{"queryKultur":{"nodes":[
              {"id":"-w","title":"Wine Garden Festival","typeOfEvent":"Open Air","place":"Weingarten","eventDate":"2026-09-01T18:00:00.000Z"}
            ]}}}}
            """.trimIndent()

        val event = scraper.scrape(payload, SOURCE_URL).single()

        event.eventType shouldBe EventType.OTHER.name
        // Confirms the downstream promotion the exemption exists to preserve.
        event.toEventEntity(venueId = 1, venueSlug = "zenner", eventSourceId = 1).eventType shouldBe EventType.FESTIVAL.name
    }

    @Test
    fun `scrape types the venue's catch-all Event label from the title instead of guessing`() {
        // "Event" says nothing about the kind; with no title cue the event stays OTHER.
        val payload =
            """
            {"result":{"data":{"queryKultur":{"nodes":[
              {"id":"-t","title":"Wein-Tasting","typeOfEvent":"Event","place":"Weingarten","eventDate":"2026-09-01T18:00:00.000Z"}
            ]}}}}
            """.trimIndent()

        scraper.scrape(payload, SOURCE_URL).single().eventType shouldBe EventType.OTHER.name
    }

    @Test
    fun `scrape derives the billed act from a promoter-framed concert title`() {
        scrape().single { it.title == "Trinity presents: Nathan Fake" }.artists.map { it.name } shouldContainExactly listOf("Nathan Fake")
        scrape().single { it.title == "180 min w/ Polygonia (live)" }.artists.map { it.name } shouldContainExactly listOf("Polygonia")
    }

    @Test
    fun `scrape derives the guest DJ from a party's w-slash billing frame`() {
        scrape().single { it.title == "SIP! w/ Haseeb Iqbal (All Day Long)" }.artists.map { it.name to it.role } shouldContainExactly
            listOf("Haseeb Iqbal" to ArtistRole.DJ)
        scrape().single { it.title == "SIP! w/ Coco Maria (All Day Long)" }.artists.map { it.name to it.role } shouldContainExactly
            listOf("Coco Maria" to ArtistRole.DJ)
    }

    @Test
    fun `scrape mints no artists for a party announcing no guest`() {
        // A bare series edition names the night, not a performer.
        scrape().single { it.title == "NICE ONE WEEKENDER" }.artists.shouldHaveSize(0)
        scrape().single { it.title == "SIP! Closing" }.artists.shouldHaveSize(0)
        scrape().single { it.title == "LITTLEBIG" }.artists.shouldHaveSize(0)
    }

    @Test
    fun `scrape splits co-billed guest DJs and leaves a promoter's event name alone`() {
        val payload =
            """
            {"result":{"data":{"queryKultur":{"nodes":[
              {"id":"-a","title":"SIP! w/ Donna Leake & Coco Maria (All Night Long)","typeOfEvent":"Open Air","place":"Weingarten","eventDate":"2026-09-01T18:00:00.000Z"},
              {"id":"-b","title":"Gene On Earth presents Rave 'n' Cruise","typeOfEvent":"Party","place":"Saal","eventDate":"2026-09-02T18:00:00.000Z"}
            ]}}}}
            """.trimIndent()

        val byTitle = scraper.scrape(payload, SOURCE_URL).associateBy { it.title }

        byTitle.getValue("SIP! w/ Donna Leake & Coco Maria (All Night Long)").artists.map { it.name to it.role } shouldContainExactly
            listOf("Donna Leake" to ArtistRole.DJ, "Coco Maria" to ArtistRole.DJ)
        // A "presents" tail is as often an event name as an act, so parties mine only `w/`.
        byTitle.getValue("Gene On Earth presents Rave 'n' Cruise").artists.shouldHaveSize(0)
    }

    @Test
    fun `scrape keeps a concert's mid-title collaborator billing intact`() {
        // `w/` joins collaborators here rather than framing a guest, and the concert path —
        // which anchors its own `w/` frame to a leading duration — must not tear it apart.
        val payload =
            """
            {"result":{"data":{"queryKultur":{"nodes":[
              {"id":"-c","title":"Analogue Foundation presents: David August w/ MFO (live)","typeOfEvent":"Konzert","place":"Saal","eventDate":"2026-09-01T18:00:00.000Z"}
            ]}}}}
            """.trimIndent()

        scraper
            .scrape(payload, SOURCE_URL)
            .single()
            .artists
            .map { it.name to it.role } shouldContainExactly
            listOf("David August w/ MFO" to ArtistRole.HEADLINER)
    }

    @Test
    fun `scrape skips a flea market and an ice-skating slot, and keeps the ice disco`() {
        // Events 3894 and 11807.
        val payload =
            """
            {"result":{"data":{"queryKultur":{"nodes":[
              {"id":"-m","title":"Indoor Flohmarkt - Freier Eintritt","typeOfEvent":"Event","place":"Saal","eventDate":"2026-11-08T10:00:00.000Z"},
              {"id":"-s","title":"Eislaufen","typeOfEvent":"Event","place":"Biergarten","eventDate":"2026-11-14T10:00:00.000Z"},
              {"id":"-d","title":"Eisdisko","typeOfEvent":"Open Air","place":"Biergarten","eventDate":"2026-11-14T18:00:00.000Z"}
            ]}}}}
            """.trimIndent()

        scraper.scrape(payload, SOURCE_URL).map { it.title } shouldContainExactly listOf("Eisdisko")
        // The snapshot's two "Pop-Up Flohmarkt (Indoor)" dates in the Saal.
        val archive = ZennerApiScraper(fixedClock("2023-01-01T00:00:00Z")).scrape(fixtureJson, SOURCE_URL).map { it.title }
        archive.filter { "Flohmarkt" in it } shouldHaveSize 0
    }

    @Test
    fun `scrape drops the bare placeholder blurb the venue leaves on an event with no text`() {
        scrape().single { it.title == "SIP! Closing" }.description.shouldBeNull()
    }

    @Test
    fun `scrape ignores a mailto enquiry address rather than storing it as a ticket link`() {
        // The venue links its dinners and room bookings to a `mailto:` address.
        val payload =
            """
            {"result":{"data":{"queryKultur":{"nodes":[
              {"id":"-g","title":"Martinsgansessen im Zenner","typeOfEvent":"Event","place":"Saal","eventDate":"2026-11-13T18:00:00.000Z","linkEvent":"mailto:info@example.de"},
              {"id":"-y","title":"Yeule","typeOfEvent":"Konzert","place":"Saal","eventDate":"2026-11-14T19:00:00.000Z","linkEvent":"https://dice.fm/event/yeule"}
            ]}}}}
            """.trimIndent()

        val byTitle = scraper.scrape(payload, SOURCE_URL).associateBy { it.title }

        byTitle.getValue("Martinsgansessen im Zenner").ticketUrl.shouldBeNull()
        byTitle.getValue("Yeule").ticketUrl shouldBe "https://dice.fm/event/yeule"
    }

    @Test
    fun `scrape returns no events for a payload with no event nodes`() {
        scraper.scrape("""{"result":{"data":{}}}""", SOURCE_URL) shouldHaveSize 0
    }

    @Test
    fun `scrape returns no events for an unparseable payload`() {
        scraper.scrape("not json at all", SOURCE_URL) shouldHaveSize 0
    }

    @Test
    fun `scrape skips a node with no id, title, or parseable date`() {
        val payload =
            """
            {"result":{"data":{"queryKultur":{"nodes":[
              {"title":"No id","eventDate":"2026-09-01T18:00:00.000Z"},
              {"id":"-a","eventDate":"2026-09-01T18:00:00.000Z"},
              {"id":"-b","title":"No date"},
              {"id":"-c","title":"Broken date","eventDate":"someday"},
              {"id":"-d","title":"Kept","eventDate":"2026-09-01T18:00:00.000Z"}
            ]}}}}
            """.trimIndent()

        val events = scraper.scrape(payload, SOURCE_URL)

        events shouldHaveSize 1
        events.single().title shouldBe "Kept"
        events.single().sourceId shouldBe "zenner:d"
    }

    @Test
    fun `scrape treats every room as published when the payload carries no visibility flags`() {
        val payload =
            """
            {"result":{"data":{"queryKultur":{"nodes":[
              {"id":"-x","title":"Beer garden night","place":"Biergarten","eventDate":"2026-09-01T18:00:00.000Z"}
            ]}}}}
            """.trimIndent()

        scraper.scrape(payload, SOURCE_URL) shouldHaveSize 1
    }

    private companion object {
        const val SOURCE_URL = "https://zenner.berlin/programm"

        fun fixedClock(instant: String): Clock = Clock.fixed(Instant.parse(instant), BERLIN)

        /** 19 July 2026 — the snapshot's own "today", keeping its one cancelled event in scope. */
        val CUTOFF_CLOCK: Clock = fixedClock("2026-07-19T09:00:00Z")
    }
}
