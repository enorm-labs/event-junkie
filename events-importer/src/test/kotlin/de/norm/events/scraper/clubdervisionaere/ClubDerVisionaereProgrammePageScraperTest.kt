package de.norm.events.scraper.clubdervisionaere

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for [ClubDerVisionaereProgrammePageScraper].
 *
 * Two snapshots of the same page are used, because the programme is seasonal and each
 * half of the year exercises different rooms:
 * - **summer** (`clubdervisionaere-programm.html`) — the open-air club (`.cdvRed`) plus
 *   Sonnenraum nights, including the shared-date block whose date cell is empty;
 * - **winter** (`clubdervisionaere-programm-winter.html`) — the boat (`.hoppetosseYellow`)
 *   plus Sonnenraum, with dates that roll over into the next year.
 *
 * Each fixture is read with a clock pinned before its earliest date so the year inferred
 * from the year-less `Wd. D.M.` cells stays deterministic.
 */
class ClubDerVisionaereProgrammePageScraperTest {
    private val sourceUrl = "https://clubdervisionaere.com/programm/"

    // Pinned before the summer snapshot's earliest date (31.7.) …
    private val summerScraper =
        ClubDerVisionaereProgrammePageScraper(Clock.fixed(Instant.parse("2026-07-25T10:00:00Z"), ZoneOffset.UTC))

    // … and before the winter snapshot's earliest date (12.1.), in the *previous* year, so the
    // winter fixture also covers the year rollover the weekday-based inference has to get right.
    private val winterScraper =
        ClubDerVisionaereProgrammePageScraper(Clock.fixed(Instant.parse("2025-12-20T10:00:00Z"), ZoneOffset.UTC))

    private fun page(fixture: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/clubdervisionaere/$fixture")!!
                .bufferedReader()
                .readText(),
            sourceUrl
        )

    private fun summer(room: ClubDerVisionaereRoom) = summerScraper.scrape(page("clubdervisionaere-programm.html"), sourceUrl, room)

    private fun winter(room: ClubDerVisionaereRoom) = winterScraper.scrape(page("clubdervisionaere-programm-winter.html"), sourceUrl, room)

    private val clubEvents by lazy { summer(ClubDerVisionaereRoom.CLUB) }
    private val sonnenraumEvents by lazy { summer(ClubDerVisionaereRoom.SONNENRAUM) }

    @Test
    fun `tags the club and the boat Techno, House and leaves the Sonnenraum's concerts untagged`() {
        clubEvents.map { it.genre }.distinct() shouldBe listOf("Techno, House")
        winter(ClubDerVisionaereRoom.MS_HOPPETOSSE).map { it.genre }.distinct() shouldBe listOf("Techno, House")
        sonnenraumEvents.map { it.genre }.distinct() shouldBe listOf(null)
    }

    @Test
    fun `splits the one listing into its three rooms by title colour class`() {
        // The summer page carries 20 blocks: 17 club nights and 3 in the Sonnenraum. The boat
        // is the winter location, so it legitimately contributes nothing to a summer import.
        clubEvents shouldHaveSize 17
        sonnenraumEvents shouldHaveSize 3
        summer(ClubDerVisionaereRoom.MS_HOPPETOSSE).shouldBeEmpty()
    }

    @Test
    fun `extracts all fields of a representative club night`() {
        val event = clubEvents.first { it.sourceId == "club_der_visionaere:41724" }

        event.title shouldBe "Wordless"
        // "Fr. 31.7." with no year → 2026 (31 July falls on a Friday nearest the pinned clock).
        event.eventDate shouldBe LocalDate.of(2026, 7, 31)
        // Every listing on this page is a club night; the venue publishes no category.
        event.eventType shouldBe EventType.PARTY.name
        // No per-event pages — the programme page is every event's source URL.
        event.sourceUrl shouldBe sourceUrl
        // The WordPress post id is the stable identity; the room decides the prefix.
        event.sourceId shouldBe "club_der_visionaere:41724"
        // The page publishes none of these: no times, prices, tickets, images or genre.
        event.startTime.shouldBeNull()
        event.doorsTime.shouldBeNull()
        event.imageUrl.shouldBeNull()
        event.ticketUrl.shouldBeNull()
        event.pricePresale.shouldBeNull()

        // "Main:" / "Chill Floor:" headings become the stage of the acts below them; the
        // "live" marker on Lobanov K. bills them as a performer rather than a DJ, and the
        // "// More TBA" placeholder is dropped.
        event.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Olya Smok", role = "DJ", stage = "Main"),
                ScrapedArtist(name = "Serenne", role = "DJ", stage = "Main"),
                ScrapedArtist(name = "Timur Basha", role = "DJ", stage = "Main"),
                ScrapedArtist(name = "Yone-Ko", role = "DJ", stage = "Main"),
                ScrapedArtist(name = "Lobanov K.", role = "HEADLINER", stage = "Chill Floor"),
                ScrapedArtist(name = "Masayuki Tomita", role = "DJ", stage = "Chill Floor")
            )
    }

    @Test
    fun `splits co-billed and back-to-back slots but keeps parenthesised names whole`() {
        val discoSour = clubEvents.first { it.sourceId == "club_der_visionaere:41775" }
        // "// Yapacc & Pakkadej LIVE" → two live acts; the bracketed member list of
        // "Los Refrescos (…)" is one act, and the "(3)" Resident Advisor disambiguator stays.
        discoSour.artistNames() shouldContainExactly
            listOf(
                "James Dean Brown",
                "Yapacc",
                "Pakkadej",
                "Los Refrescos (Dandy Jack & Argenis Brito)",
                "Tau Car",
                "Chica Paula",
                "himeee",
                "Push EBP",
                "Ponura",
                "Dimitrios (3)"
            )

        // "// XDB b2b Onirik" → two DJs; "// Ctrl+Opt & Gwenan" splits at the conjunction
        // only, leaving the "+" inside Ctrl+Opt's own name intact.
        val offTheGrid = clubEvents.first { it.sourceId == "club_der_visionaere:41777" }
        offTheGrid.artistNames() shouldContainExactly
            listOf(
                "David Hornung",
                "Gwenan (Phase Space Live)",
                "Karine",
                "Shakolin",
                "Lola Haro",
                "XDB",
                "Onirik",
                "Ctrl+Opt",
                "Gwenan"
            )
    }

    @Test
    fun `bills a live-band section as headliners and drops an act repeated in a later section`() {
        val event = clubEvents.first { it.sourceId == "club_der_visionaere:41800" }

        event.title shouldBe "Remain In Love"
        // "Live Band featuring:" bills its acts as performers, "DJ Sets:" as DJs. Mike Shannon
        // and The Mole appear in both sections — a second entry would collide on the
        // event_artist (event_id, artist_id) unique constraint, so the first billing wins.
        event.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Baby Vulture", role = "HEADLINER"),
                ScrapedArtist(name = "Deadbeat", role = "HEADLINER"),
                ScrapedArtist(name = "Hreno", role = "HEADLINER"),
                ScrapedArtist(name = "Mike Shannon", role = "HEADLINER"),
                ScrapedArtist(name = "The Mole", role = "HEADLINER"),
                ScrapedArtist(name = "Tom Trago", role = "HEADLINER"),
                ScrapedArtist(name = "Joli B", role = "HEADLINER"),
                ScrapedArtist(name = "Jonas “Stackhouse” Robinson", role = "DJ"),
                ScrapedArtist(name = "Alex", role = "DJ"),
                ScrapedArtist(name = "Laetitia Katapult", role = "DJ")
            )
    }

    @Test
    fun `a block with an empty date cell inherits the date of the block above it`() {
        // The Monday Earkestra night shares 3 August with the club's "Dunkle Dummies", so the
        // theme prints the date only on the first of the two and leaves this cell empty.
        val shared = sonnenraumEvents.first { it.sourceId == "sonnenraum:41733" }
        shared.eventDate shouldBe LocalDate.of(2026, 8, 3)
        clubEvents.first { it.sourceId == "club_der_visionaere:41784" }.eventDate shouldBe LocalDate.of(2026, 8, 3)

        // The following week's Earkestra has a date cell of its own and is unaffected.
        sonnenraumEvents.first { it.sourceId == "sonnenraum:41794" }.eventDate shouldBe LocalDate.of(2026, 8, 10)
    }

    @Test
    fun `keeps a live act but drops the unbooked guest-DJ slot and its set times`() {
        val event = sonnenraumEvents.first { it.sourceId == "sonnenraum:41733" }

        // "// The Omniversal Earkestra LIVE from 21:00" → the band, billed live; the set time comes
        // off the name. "// Guest DJs from 23:00" names no act at all. The earlier of the two slot
        // times stands in for the night's start, which the listing states no other way (#1687).
        event.artists shouldContainExactly listOf(ScrapedArtist(name = "The Omniversal Earkestra", role = "HEADLINER"))
        event.startTime shouldBe LocalTime.of(21, 0)
    }

    @Test
    fun `parses the winter programme into the boat and the Sonnenraum, rolling the year over`() {
        val boatEvents = winter(ClubDerVisionaereRoom.MS_HOPPETOSSE)
        val winterSonnenraum = winter(ClubDerVisionaereRoom.SONNENRAUM)

        boatEvents shouldHaveSize 4
        winterSonnenraum shouldHaveSize 4
        // The open-air club is closed in winter — no `.cdvRed` night on this snapshot.
        winter(ClubDerVisionaereRoom.CLUB).shouldBeEmpty()

        val chezDoc = boatEvents.first()
        chezDoc.title shouldBe "Chez Doc"
        chezDoc.sourceId shouldBe "ms_hoppetosse:7138"
        // "Fr. 16.1." read from a December clock → January of the following year.
        chezDoc.eventDate shouldBe LocalDate.of(2026, 1, 16)
        chezDoc.artistNames() shouldContainExactly listOf("Massone", "Perro Jimbo", "Tsuruta", "VIKk", "Walrus")

        // The furthest-out night rolls over the year and into February.
        winterSonnenraum.last().eventDate shouldBe LocalDate.of(2026, 2, 28)
        // A lineup that is nothing but "// TBA" yields no artists rather than a placeholder one.
        winterSonnenraum.first { it.sourceId == "sonnenraum:41418" }.artists.shouldBeEmpty()
    }

    @Test
    fun `returns no events for a page without the programme column`() {
        val emptyDoc = Jsoup.parse("<html><body><p>Baustelle</p></body></html>", sourceUrl)
        ClubDerVisionaereRoom.entries.forEach { room ->
            summerScraper.scrape(emptyDoc, sourceUrl, room).shouldBeEmpty()
        }
    }

    private fun ScrapedEvent.artistNames(): List<String> = artists.map { it.name }
}
