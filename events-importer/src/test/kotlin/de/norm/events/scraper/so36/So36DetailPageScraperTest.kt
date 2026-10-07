package de.norm.events.scraper.so36

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [So36DetailPageScraper].
 *
 * Parses static detail-page fixtures for a concert and a party and asserts the
 * per-field extraction, including the concert-only artist roster and the
 * price/ticket fields that parties lack.
 */
class So36DetailPageScraperTest {
    private val scraper = So36DetailPageScraper()

    private val concertUrl =
        "https://www.so36.com/produkte/95201-tickets-poison-ruin-so36-berlin-am-09-07-2026"
    private val partyUrl =
        "https://www.so36.com/produkte/97683-tickets-last-night-so36-berlin-am-03-07-2026"

    private fun fixture(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/so36/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @Test
    fun `parses all fields of a concert detail page`() {
        val event = scraper.scrape(fixture("so36-detail-concert.html", concertUrl), concertUrl)
        event.shouldNotBeNull()

        event.title shouldBe "POISON RUIN"
        event.eventType shouldBe EventType.CONCERT.name
        event.subtitle shouldBe "+ GUM + CLAVV"
        event.eventDate shouldBe LocalDate.of(2026, 7, 9)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.pricePresale shouldBe BigDecimal("22.0")
        event.ticketUrl shouldBe "https://www.greyzone-tickets.de/produkte/1271"
        event.imageUrl.shouldNotBeNull()
        event.imageUrl shouldContain "HP_PoisonRuin"
        event.description.shouldNotBeNull()
        event.description shouldContain "Poison Ruin formed in Philadelphia"
        event.status shouldBe EventStatus.SCHEDULED.name
        event.sourceId shouldBe "so36:95201"
        event.soldOut shouldBe false
        event.free shouldBe false
        event.promoters shouldBe listOf("GreyZone Concerts")
    }

    @Test
    fun `reads the format a Konzert page names in its subtitle or title, and bills no act for it`() {
        // Upcoming SO36 rows on 2026-09-30, each filed under "Konzert".
        fun typeAndActs(
            title: String,
            subtitle: String
        ): Pair<String?, List<String>> {
            val document = fixture("so36-detail-concert.html", concertUrl)
            document.selectFirst("h1 [itemprop=name]")!!.text(title)
            document.selectFirst("small.subtitle")!!.text(subtitle)
            val event = scraper.scrape(document, concertUrl)!!
            return event.eventType to event.artists.map { it.name }
        }

        typeAndActs("FEMALE-FRONTED IS NOT A GENRE 5", "Punk- und Hardcore-Festival - Tag 1") shouldBe (EventType.FESTIVAL.name to emptyList())
        typeAndActs("(TH)INK ABOUT THAT", "queere antifaschistische Tattoo Convention") shouldBe (EventType.OTHER.name to emptyList())
        typeAndActs("PANEL: ¡MASH-IT-UP! WE WERE ALWAYS THERE!", "BIPoC and FLINTA* Perspectives on 50 Years of Punk") shouldBe
            (EventType.OTHER.name to emptyList())
        typeAndActs("TALK OHNE GAST", "Qualitätspodcast mit Till Reiners und Moritz Neumeier") shouldBe (EventType.SHOW.name to emptyList())
        // A support line naming a festival band is still a concert.
        typeAndActs("POISON RUIN", "+ Festival Band").first shouldBe EventType.CONCERT.name
    }

    @Test
    fun `reads the presale from the regular category and the box-office price from Abendkasse`() {
        // (TH)INK ABOUT THAT, 27 September 2026: regulär 10,00 €, ermäßigt 6,00 €, Abendkasse 12,00 €.
        fun offer(
            name: String,
            price: String
        ) = """<tr itemprop="offers" itemscope itemtype="http://schema.org/Offer"><td class="title"><span itemprop="name">$name</span></td>""" +
            """<td class="price"><span class="hidden" content="$price" itemprop="price"></span></td></tr>"""

        fun prices(vararg offers: String): Pair<BigDecimal?, BigDecimal?> {
            val document = fixture("so36-detail-concert.html", concertUrl)
            document.selectFirst("table.variants-listing tbody")!!.html(offers.joinToString(""))
            val event = scraper.scrape(document, concertUrl)!!
            return event.pricePresale to event.priceBoxOffice
        }

        prices(
            offer("(TH)INK ABOUT THAT | regulär", "10.0"),
            offer("(TH)INK ABOUT THAT | ermäßigt", "6.0"),
            offer("(TH)INK ABOUT THAT | Abendkasse", "12.0")
        ) shouldBe (BigDecimal("10.0") to BigDecimal("12.0"))
        // After the presale closed, only the door category was left.
        prices(offer("(TH)INK ABOUT THAT | Abendkasse", "12.0")) shouldBe (null to BigDecimal("12.0"))
        prices(offer("SADSVIT | Ticket", "49.0")) shouldBe (BigDecimal("49.0") to null)
    }

    @Test
    fun `extracts the concert lineup with the title as headliner and plus-prefixed support acts`() {
        val event = scraper.scrape(fixture("so36-detail-concert.html", concertUrl), concertUrl)
        event.shouldNotBeNull()

        event.artists shouldHaveSize 3
        event.artists[0].name shouldBe "POISON RUIN"
        event.artists[0].role shouldBe "HEADLINER"
        event.artists.drop(1).map { it.name } shouldBe listOf("GUM", "CLAVV")
        event.artists.drop(1).all { it.role == "SUPPORT" } shouldBe true
    }

    @Test
    fun `reads the acts after a mit billing frame and never the night's name`() {
        // "SADTEMBER mit TAHA, JOHNBOY M.IKARUS" + "+ Arbok 48 + Support": SADTEMBER is the night (#1132).
        val url = "https://www.so36.com/produkte/96647-tickets-sadtember-mit-taha-johnboy-m-ikarus-so36-berlin-am-05-09-2026"
        val event = scraper.scrape(fixture("so36-detail-mit-billing.html", url), url)
        event.shouldNotBeNull()

        event.title shouldBe "SADTEMBER mit TAHA, JOHNBOY M.IKARUS"
        event.artists.map { it.name to it.role } shouldBe
            listOf("TAHA" to "HEADLINER", "JOHNBOY M.IKARUS" to "HEADLINER", "Arbok 48" to "SUPPORT")
    }

    @Test
    fun `bills the part of a piped title before the pipe`() {
        // "SADSVIT | HYPHEN DASH", 30 September 2026; the ticket line is "SADSVIT | Ticket".
        val url = "https://www.so36.com/produkte/100114-tickets-sadsvit-hyphen-dash-so36-berlin-am-30-09-2026"
        val event = scraper.scrape(fixture("so36-detail-pipe-title.html", url), url)
        event.shouldNotBeNull()

        event.title shouldBe "SADSVIT | HYPHEN DASH"
        event.artists.map { it.name to it.role } shouldBe listOf("SADSVIT" to "HEADLINER")
    }

    @Test
    fun `reads a support line that opens with its label instead of a plus`() {
        // "PÖBEL & GESOCKS" with the subtitle "Support: GRENZER & BIERTOIFEL" (#1903).
        val url = "https://www.so36.com/produkte/99596-tickets-poebel-gesocks-so36-berlin-am-03-10-2026"
        val event = scraper.scrape(fixture("so36-detail-support-label.html", url), url)
        event.shouldNotBeNull()

        event.subtitle shouldBe "Support: GRENZER & BIERTOIFEL"
        event.artists.map { it.name to it.role } shouldBe
            listOf(
                "PÖBEL & GESOCKS" to "HEADLINER",
                "GRENZER" to "SUPPORT",
                "BIERTOIFEL" to "SUPPORT"
            )
    }

    @Test
    fun `keeps a band with an ampersand in its name whole and splits a co-billed title`() {
        // The concert titles with "&" on the listing on 2026-10-03 (#2414).
        fun headliners(title: String): List<String> {
            val document = fixture("so36-detail-concert.html", concertUrl)
            document.selectFirst("h1 [itemprop=name]")!!.text(title)
            return scraper
                .scrape(document, concertUrl)!!
                .artists
                .filter { it.role == "HEADLINER" }
                .map { it.name }
        }

        headliners("PÖBEL & GESOCKS") shouldBe listOf("PÖBEL & GESOCKS")
        headliners("TITO & TARANTULA") shouldBe listOf("TITO & TARANTULA")
        headliners("BOOZE & GLORY") shouldBe listOf("BOOZE & GLORY")
        headliners("Kai & Funky von TON STEINE SCHERBEN") shouldBe listOf("Kai & Funky von TON STEINE SCHERBEN")
        headliners("MASTER BOOT RECORD & FULCI") shouldBe listOf("MASTER BOOT RECORD", "FULCI")
    }

    @Test
    fun `reads a support line that opens with an ampersand as support acts`() {
        // "NASTY" with the subtitle "& CRAWLSPACE & PINTGLASS & GHETTO JUSTICE" (#1928).
        val url = "https://www.so36.com/produkte/96899-tickets-nasty-so36-berlin-am-12-11-2026"
        val event = scraper.scrape(fixture("so36-detail-ampersand.html", url), url)
        event.shouldNotBeNull()

        event.subtitle shouldBe "& CRAWLSPACE & PINTGLASS & GHETTO JUSTICE"
        event.artists.map { it.name to it.role } shouldBe
            listOf(
                "NASTY" to "HEADLINER",
                "CRAWLSPACE" to "SUPPORT",
                "PINTGLASS" to "SUPPORT",
                "GHETTO JUSTICE" to "SUPPORT"
            )
    }

    @Test
    fun `parses a party detail page without a lineup, price or ticket link`() {
        val event = scraper.scrape(fixture("so36-detail-party.html", partyUrl), partyUrl)
        event.shouldNotBeNull()

        event.title shouldBe "LAST NIGHT"
        event.eventType shouldBe EventType.PARTY.name
        // A party subtitle is a descriptive tagline, not a "+ …" support line.
        event.subtitle shouldBe "Die Indie-Pop Party"
        event.artists shouldHaveSize 0
        event.pricePresale shouldBe null
        event.ticketUrl shouldBe null
        event.doorsTime shouldBe LocalTime.of(22, 0)
        event.startTime shouldBe LocalTime.of(22, 0)
        event.sourceId shouldBe "so36:97683"
        // A house night credits "SO36", the venue itself, which is no promoter.
        event.promoters shouldHaveSize 0
    }

    @Test
    fun `bills a festival's bold, origin-tagged acts and not its name or presenters`() {
        val url = "https://www.so36.com/produkte/92099-tickets-canarias-calling-festival-so36-berlin-am-08-10-2026"
        val event = scraper.scrape(fixture("so36-detail-festival-lineup.html", url), url).shouldNotBeNull()

        event.eventType shouldBe EventType.FESTIVAL.name
        event.artists.map { it.name to it.role } shouldBe
            listOf("PLANLOS X", "DEATH BY HORSE", "BÍFIDOS", "ENRALE", "DURCHFALL").map { it to "HEADLINER" }
    }

    @Test
    fun `bills each night of a two-day festival only its own day's acts`() {
        val day1 = "https://www.so36.com/produkte/93090-tickets-female-fronted-is-not-a-genre-5-so36-berlin-am-22-10-2026"
        val day2 = "https://www.so36.com/produkte/90007-tickets-female-fronted-is-not-a-genre-5-so36-berlin-am-23-10-2026"

        // Day 1's subtitle says `Tag 1`; day 2's says nothing, so its rank among the run's dates decides.
        scraper
            .scrape(fixture("so36-detail-festival-day1.html", day1), day1)
            .shouldNotBeNull()
            .artists
            .map { it.name } shouldBe
            listOf("THE IRON ROSES", "KILL HER FIRST", "NEVER OBEY AGAIN", "WICK BAMBIX")
        scraper
            .scrape(fixture("so36-detail-festival-day2.html", day2), day2)
            .shouldNotBeNull()
            .artists
            .map { it.name } shouldBe
            listOf("MARCH", "WREX", "LUTHER", "WÜT", "THE RACCOONZ")
    }

    @Test
    fun `bills nobody when a festival's day cannot be told or its bold text names no origin`() {
        fun roster(html: String) = descriptionRoster(Jsoup.parse("""<div class="product_description">$html</div>"""), day = null)

        roster(
            "<p><u>Live - Day 1:</u></p><p><strong>A</strong> (X)</p><p><strong>B</strong> (Y)</p>" +
                "<p><u>Live - Day 2:</u></p><p><strong>C</strong> (X)</p><p><strong>D</strong> (Y)</p>"
        ) shouldBe emptyList()
        roster("<p>The current line-up features <strong>Jake Burns</strong> and <strong>Ali McMordie</strong>.</p>") shouldBe emptyList()
        roster("<p><strong>A</strong> (X)</p><p><strong>B (Y)</strong></p>") shouldBe listOf("A", "B")
    }

    @Test
    fun `reads the free-admission notice as a free night`() {
        // The ticket tab shows "Eintritt frei / Admission free!" where a price category would be (#1686).
        val url = "https://www.so36.com/produkte/92090-tickets-dav-jura-slam-so36-berlin-am-17-11-2026"
        val event = scraper.scrape(fixture("so36-detail-free.html", url), url)
        event.shouldNotBeNull()

        event.title shouldBe "DAV JURA SLAM"
        event.free shouldBe true
        event.pricePresale shouldBe null
        event.promoters shouldBe listOf("Deutscher Anwaltverein")
    }

    @Test
    fun `reads the Anbieter-Veranstalter field as the promoter`() {
        val url = "https://www.so36.com/produkte/99637-tickets-superstars-der-demokratie-so36-berlin-am-20-09-2026"
        val event = scraper.scrape(fixture("so36-detail-promoter.html", url), url)
        event.shouldNotBeNull()

        event.title shouldBe "SUPERSTARS DER DEMOKRATIE"
        event.promoters shouldBe listOf("Die PARTEI")
    }

    @Test
    fun `strips role labels and splits multi-act support lines, dropping label-only chunks`() {
        // Build a minimal concert detail page with a messy SO36 support subtitle.
        fun concertWithSubtitle(subtitle: String) =
            Jsoup.parse(
                """
                <html><body>
                  <small class="supertitle">Konzert</small>
                  <h1><span itemprop="name">HEADLINER</span><small class="subtitle">$subtitle</small></h1>
                </body></html>
                """.trimIndent(),
                concertUrl
            )

        fun supports(subtitle: String) =
            scraper
                .scrape(concertWithSubtitle(subtitle), concertUrl)!!
                .artists
                .filter { it.role == "SUPPORT" }
                .map { it.name }

        // Labels are stripped, not captured as part of the name.
        supports("+ Special Guest: FUCK") shouldBe listOf("FUCK")
        supports("+ Support: cosmic joke & bad beat") shouldBe listOf("cosmic joke", "bad beat")
        // "und"/"and" split per boundary: the leading act separates, but a backing-band
        // tail ("& The Sun Band") stays attached rather than becoming its own artist.
        supports("+ Earth Tongue und Scott Hepple & The Sun Band") shouldBe
            listOf("Earth Tongue", "Scott Hepple & The Sun Band")
        // A bare label with no act name is dropped entirely rather than becoming an artist.
        supports("+ div. Supports") shouldBe emptyList()
        supports("+ Support") shouldBe emptyList()
        // An event-segment label (aftershow slot) is not a performer and is dropped.
        supports("+ ACID AFTERSHOW") shouldBe emptyList()
        // A subtitle that opens with a support label is a support line without its "+" (#1903).
        supports("Support: DEMOB HAPPY") shouldBe listOf("DEMOB HAPPY")
        supports("Special Guest: The Flatliners") shouldBe listOf("The Flatliners")
        supports("Special Guests: THE NIGHT FLIGHT ORCHESTRA & EDGE OF PARADISE") shouldBe
            listOf("THE NIGHT FLIGHT ORCHESTRA", "EDGE OF PARADISE")
        // A leading "&" joins the acts to the headliner like "+" (#1928).
        supports("& Pool Girl") shouldBe listOf("Pool Girl")
        supports("& Special Guest: The Flatliners") shouldBe listOf("The Flatliners")
        // A tagline carries no labelled opener, so it bills nobody — an "&" inside it included.
        supports("Europa Tour 2026") shouldBe emptyList()
        supports("Präsentiert von Inge Borg & Gisela Sommer") shouldBe emptyList()
        supports("With Special Guest & Support") shouldBe emptyList()
        supports("feat. Birte Volta mit Special-Guests") shouldBe emptyList()
    }

    @Test
    fun `returns null when the page has no event title`() {
        val html = "<html><body><div>Not a product page</div></body></html>"
        scraper.scrape(Jsoup.parse(html, concertUrl), concertUrl) shouldBe null
    }

    @Test
    fun `keeps the cheapest of several tiers, names every tier, and links its own shop page`() {
        val url = "https://www.so36.com/produkte/100165-tickets-15-years-of-mash-up-so36-berlin-am-02-10-2026"
        val event = scraper.scrape(fixture("so36-detail-tiers.html", url), url).shouldNotBeNull()

        event.pricePresale shouldBe BigDecimal("24.5")
        event.priceNote shouldBe "Ticket social 24,50 € / Ticket regular 29,50 € / Ticket support 34,50 €"
        event.ticketUrl shouldBe url
    }

    // The whole text is one paragraph, English first; the pointer says only that German follows (#330).
    @Test
    fun `cuts a description in both languages where the German half begins`() {
        val url = "https://www.so36.com/tickets/x"
        val event = scraper.scrape(fixture("so36-detail-tiers.html", url), url).shouldNotBeNull()

        val english = event.description.shouldNotBeNull()
        english shouldStartWith "A queer-feminist double anniversary with panel, live concerts and DJs"
        english shouldNotContain "Deutsche Version"
        english shouldNotContain "Doppeljubiläum"
        event.descriptionAlt.shouldNotBeNull() shouldStartWith "Queer-feministisches Doppeljubiläum mit Panel"
    }

    @Test
    fun `stores a one-language description with no second language`() {
        val event = scraper.scrape(fixture("so36-detail-concert.html", concertUrl), concertUrl).shouldNotBeNull()

        event.descriptionAlt.shouldBeNull()
    }
}
