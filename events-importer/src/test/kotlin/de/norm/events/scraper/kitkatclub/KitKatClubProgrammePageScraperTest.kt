package de.norm.events.scraper.kitkatclub

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class KitKatClubProgrammePageScraperTest {
    private val sourceUrl = "https://kitkatclub.org/Home/Club/Index.html"
    private val clock = Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val scraper = KitKatClubProgrammePageScraper(clock)

    private val events by lazy {
        val html = javaClass.classLoader.getResourceAsStream("scraper/kitkatclub/kitkatclub-programme.html")!!
        scraper.scrape(Jsoup.parse(html, null, sourceUrl), sourceUrl)
    }

    private fun night(date: String) = events.single { it.eventDate == LocalDate.parse(date) }

    private fun row(
        date: String,
        title: String,
        lineup: String = ""
    ) = """
        <tr><td><span class="ClubTermineDatum">$date<br>ab 23h</span></td>
        <td><div class="Datensatz"><h4 class="ClubTermineTitel">$title</h4>
        <p class="ClubTermineText"><span class="ClubTerminePunkt">Line up:</span> $lineup</p></div></td></tr>
    """

    private fun scrapeRows(
        vararg rows: String,
        at: String = "2026-10-04T10:00:00Z"
    ) = KitKatClubProgrammePageScraper(Clock.fixed(Instant.parse(at), ZoneId.of("Europe/Berlin")))
        .scrape(Jsoup.parse("<table>${rows.joinToString("")}</table>", sourceUrl), sourceUrl)

    @Test
    fun `reads one party per night with the date as its identity`() {
        events shouldHaveSize 8
        events.map { it.sourceId }.toSet() shouldHaveSize 8
        events.all { it.eventType == "PARTY" && it.sourceUrl == sourceUrl } shouldBe true
        events.first().sourceId shouldBe "kitkatclub:2026-09-28"
    }

    @Test
    fun `maps a night split across rooms`() {
        val fourPlay = night("2026-10-02")

        fourPlay.title shouldBe "Four Play"
        fourPlay.doorsTime shouldBe LocalTime.of(20, 0)
        fourPlay.startTime shouldBe LocalTime.of(22, 0)
        fourPlay.genre shouldBe "Techno, House"
        fourPlay.ticketUrl shouldBe "https://de.ra.co/events/2326244"
        fourPlay.description!! shouldStartWith "\"The Four Play project was born in spring 2016"
        fourPlay.description shouldNotContain "https://de.ra.co"
        fourPlay.description shouldContain "\nDresscode: Kinky style: Latex"
        fourPlay.artists.filter { it.stage == "Main" }.map { it.name } shouldContainExactly
            listOf("Karim Alkhayat", "Maniaclina", "Molly Lollen", "Nat Suprise")
        fourPlay.artists.filter { it.stage == "4. Raum" }.map { it.name } shouldContainExactly
            listOf("Bunny Swamp", "Nibiru", "Digitalsteak", "Hekuli", "Burny Bass", "Kawasaki Omura", "Kinoko")
        fourPlay.artists.filter { it.stage == "Prisma Bar" }.map { it.name } shouldContainExactly
            listOf("Javier Anxiety", "Mati Amoretti", "Don Andres", "Magdita")
        fourPlay.artists.single { it.stage == "Salon Rouge" }.name shouldBe "Mei"
        fourPlay.artists.all { it.role == "DJ" } shouldBe true
    }

    @Test
    fun `reads only the labelled sentences of a line-up written as prose`() {
        val saturday = night("2026-10-03")

        saturday.artists.map { it.stage to it.name } shouldContainExactly
            listOf(
                "Mainfloor" to "Unerhört",
                null to "Nikos Incravalle",
                null to "Sudo",
                null to "Don Tom",
                null to "Don Basti",
                null to "Cabaret Sultana",
                "Prisma Floor" to "Zwei'E",
                "Prisma Floor" to "The Shredder",
                "Prisma Floor" to "Cheesebeat"
            )
        saturday.genre.shouldBeNull()
        saturday.ticketUrl.shouldBeNull()
    }

    @Test
    fun `reads a plain list and bills nobody for a secret line-up`() {
        night("2026-09-30").artists.map { it.name } shouldContainExactly listOf("Jordan", "Grace Thompson", "Diana May", "Rhapsodie")
        night("2026-10-05").artists.map { it.name } shouldContainExactly
            listOf("Sylvie Maziarz", "Rik Laren", "Tia", "Frankie Flowerz", "Ricardo Rodriguez")
        night("2026-10-01").artists.shouldBeEmpty()
    }

    @Test
    fun `reads the weekend warm-up in either language as doors before the party`() {
        night("2026-10-03").doorsTime shouldBe LocalTime.of(20, 0)
        night("2026-10-03").startTime shouldBe LocalTime.of(22, 0)
        events.filter { it.eventDate.dayOfWeek.value < 5 }.all { it.doorsTime == null } shouldBe true
    }

    @Test
    fun `reads a morning start and a night without a ticket link`() {
        night("2026-10-04").startTime shouldBe LocalTime.of(8, 0)
        night("2026-10-04").doorsTime.shouldBeNull()
        night("2026-10-07").ticketUrl.shouldBeNull()
        night("2026-10-07").description!! shouldEndWith "Dresscode: Be particular, creative, sexy, wicked, kinky, especially or crazy. But never boring!"
    }

    @Test
    fun `dates a January night in the next year from its weekday`() {
        val events = scrapeRows(row("Sa.<br>02.&nbsp;Jan", "CarneBall Bizarre"), at = "2026-12-28T10:00:00Z")

        events.single().eventDate shouldBe LocalDate.of(2027, 1, 2)
        events.single().startTime shouldBe LocalTime.of(23, 0)
    }

    @Test
    fun `keeps a whole act together and skips a row without a date`() {
        val events = scrapeRows(row("Fr.<br>09.&nbsp;Okt", "GEGEN", "Main: DJ One b2b DJ Two."), row("tba", "Psycho"))

        events.single().artists.map { it.name } shouldContainExactly listOf("DJ One", "DJ Two")
    }

    @Test
    fun `returns no events for a page without a programme`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", sourceUrl), sourceUrl).shouldBeEmpty()
    }
}
