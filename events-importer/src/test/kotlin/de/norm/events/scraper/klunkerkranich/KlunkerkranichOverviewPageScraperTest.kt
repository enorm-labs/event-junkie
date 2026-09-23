package de.norm.events.scraper.klunkerkranich

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
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
import java.time.ZoneId

/**
 * Unit tests for [KlunkerkranichOverviewPageScraper].
 *
 * Uses a real `/events/` snapshot of the venue's ten-day rolling programme. Every night in it
 * carries an ISO date in its slug, so the year-less fallback — the listing prints "Mittwoch, 05.
 * August" and no year anywhere — is exercised against a hand-built card instead.
 *
 * The fixture's `<script>` elements were removed when it was captured: the page inlines WordPress
 * core's wp-emoji-loader, which CodeQL flags as `js/xss-through-dom`. None of them sits inside the
 * day list, so the parsed markup is unchanged; do not re-capture the page to put them back.
 */
class KlunkerkranichOverviewPageScraperTest {
    /** The fixture's capture date, so the fallback's year inference is stable. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-05T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val sourceUrl = "https://klunkerkranich.org/events/"
    private val scraper = KlunkerkranichOverviewPageScraper(clock)
    private val events = scrape(fixture())

    private fun scrape(html: String): List<ScrapedEvent> = scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/klunkerkranich/klunkerkranich-overview.html")!!
            .bufferedReader()
            .readText()

    private fun on(
        date: LocalDate,
        title: String
    ): ScrapedEvent = events.first { it.eventDate == date && it.title == title }

    /** A day list holding one card, for the cases the real snapshot does not cover. */
    private fun cardHtml(
        href: String,
        dateLine: String,
        timeLine: String = "17:00 — 00:00",
        title: String = "WOCHENMITTE w. Pascale Project"
    ): String =
        """
        <div class="c-events-overview__day-list"><div class="c-events-overview__event">
          <article class="o-card">
            <a href="$href" class="o-card__wrapper">
              <header class="o-card__header">
                <div class="o-card__meta o-card__meta--primary">$dateLine</div>
                <div class="o-card__meta o-card__meta--secondary">$timeLine</div>
                <h2 class="o-card__title">$title</h2>
              </header>
            </a>
          </article>
        </div></div>
        """.trimIndent()

    @Test
    fun `parses every night of the published programme`() {
        // Twelve cards over ten days — the venue announces about a week and a half ahead.
        events shouldHaveSize 12
        events.first().eventDate shouldBe LocalDate.of(2026, 8, 5)
        events.last().eventDate shouldBe LocalDate.of(2026, 8, 14)
    }

    @Test
    fun `does not read the hero above the list as a thirteenth night`() {
        // The soonest night is rendered twice: once as the `o-page-header` teaser, once as its card.
        events.count { it.sourceId == "klunkerkranich:2026-08-05-wochenmitte-w-pascale-project" } shouldBe 1
    }

    @Test
    fun `parses a fully populated night`() {
        val night = on(LocalDate.of(2026, 8, 11), "BLAUES STÜNDCHEN w. Yvois")

        night.sourceId shouldBe "klunkerkranich:2026-08-11-blaues-stuendchen-w-yvois"
        night.sourceUrl shouldBe "https://klunkerkranich.org/events/2026-08-11-blaues-stuendchen-w-yvois/"
        night.eventType shouldBe EventType.PARTY.name
        night.startTime shouldBe LocalTime.of(17, 0)
        // "17:00 — 00:00": midnight closes the roof on the next day (ADR-029).
        night.endDate shouldBe LocalDate.of(2026, 8, 12)
        night.endTime shouldBe LocalTime.of(0, 0)
        night.imageUrl shouldBe
            "https://klunkerkranich.org/wp-content/uploads/2026/07/Klunkerkranich-deko-aussicht-su-18.Jun26-2-520x320.jpg"
        night.artists shouldContainExactly listOf(ScrapedArtist(name = "Yvois", role = "DJ"))
    }

    @Test
    fun `reads the hours range as start and end, with no doors time`() {
        // "16:00 — 03:00" is when the roof is open: the start, and an end on the following morning.
        val night = on(LocalDate.of(2026, 8, 14), "FERNAB ÜBER DEN DÄCHERN w. Kīto, Juli Lee, K2WO b2b Her, Woanders, ELENE")

        night.startTime shouldBe LocalTime.of(16, 0)
        night.endDate shouldBe LocalDate.of(2026, 8, 15)
        night.endTime shouldBe LocalTime.of(3, 0)
        night.doorsTime.shouldBeNull()
    }

    @Test
    fun `splits the lineup after the w marker on commas, conjunctions and b2b`() {
        val night = on(LocalDate.of(2026, 8, 10), "MONDAY ROAST w. Cem Orlow & Nils Ohrmann")

        night.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Cem Orlow", role = "DJ"),
                ScrapedArtist(name = "Nils Ohrmann", role = "DJ")
            )

        val b2bNight = on(LocalDate.of(2026, 8, 14), "FERNAB ÜBER DEN DÄCHERN w. Kīto, Juli Lee, K2WO b2b Her, Woanders, ELENE")

        // "K2WO b2b Her" is one slot shared by two DJs, and both are stored.
        b2bNight.artists.map { it.name } shouldContainExactly listOf("Kīto", "Juli Lee", "K2WO", "Her", "Woanders", "ELENE")
    }

    // The venue bills a duo and two separate acts the same way, so a named duo is what holds (#1760).
    @Test
    fun `keeps a named duo whole and splits the pair billed identically beside it`() {
        val duo = billing("MAGICIAN ON DUTY LABEL SHOWCASE w. Mia Kober, Überhaupt & Außerdem, PYSH, kayḆe, Magician On Duty")

        duo.map { it.name } shouldContainExactly
            listOf("Mia Kober", "Überhaupt & Außerdem", "PYSH", "kayḆe", "Magician On Duty")

        // Same shape on the same programme, and the page's prose calls them two residents.
        billing("DUSTY BALLROOM w. Cane Cattivo *live, Ilo Pan & Elmo Lewis").map { it.name } shouldContainExactly
            listOf("Cane Cattivo", "Ilo Pan", "Elmo Lewis")
    }

    @Test
    fun `still splits the billings the conjunction rule exists for`() {
        // A shared slot, and a collective whose unnamed cast is dropped rather than minted.
        billing("THE COOLEST KIDS IN TOWN w. Big Leg b2b *n8").map { it.name } shouldContainExactly listOf("Big Leg", "*n8")
        billing("WELLENBRUCH x BANDIDAS w. Emorine, Bandidas & Friends").map { it.name } shouldContainExactly
            listOf("Emorine", "Bandidas")
    }

    /** The acts of one title, read through a card the real snapshot does not carry. */
    private fun billing(title: String): List<ScrapedArtist> =
        scrape(cardHtml(href = "/events/2026-09-24-a-night/", dateLine = "Mittwoch, 24. September", title = title))
            .single()
            .artists

    @Test
    fun `reads the acts of a promoter's presents billing`() {
        val night = on(LocalDate.of(2026, 8, 12), "Sonic Odyssey presents: IBAAKU & K’BOKO *live")

        // The *live marker qualifies the whole billing, so both halves of the pairing are live acts.
        night.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "IBAAKU", role = "HEADLINER"),
                ScrapedArtist(name = "K’BOKO", role = "HEADLINER")
            )
    }

    @Test
    fun `reads a hosted night's acts from the venue's w marker, not the promoter's presents`() {
        // The 17.09.2026 card (#1494): "presents:" names the night, "w." names the four acts.
        val night =
            scrape(
                cardHtml(
                    href = "https://klunkerkranich.org/events/2026-09-17-countercult-presents-sketchy-sessions/",
                    dateLine = "Donnerstag, 17. September",
                    title =
                        "COUNTERCULT presents: SKETCHY SESSIONS | jazz &amp; draw rooftop sunset jam " +
                            "w. Analog Beats Collective, Sketchy Shona, Mad Mod, Natascha Roze"
                )
            ).single()

        night.artists.map { it.name } shouldContainExactly listOf("Analog Beats Collective", "Sketchy Shona", "Mad Mod", "Natascha Roze")
    }

    @Test
    fun `bills a live act as a headliner and a DJ night's acts as DJs`() {
        on(LocalDate.of(2026, 8, 13), "SOULJAM x KLUNKERKRANICH w. Soul Jam Collective *live").artists shouldContainExactly
            listOf(ScrapedArtist(name = "Soul Jam Collective", role = "HEADLINER"))

        on(LocalDate.of(2026, 8, 9), "TENDER MESH MUSIC w. Big Leg, MCRD").artists.map { it.role } shouldContainExactly listOf("DJ", "DJ")
    }

    @Test
    fun `bills a collective and its members as separate acts`() {
        // "…Miz Kiara,In Limbo Audio: Sven Howland & Niklas Gietmann & Nicolai Toma, p.toile": the
        // first member kept the team's name glued to its own (#1137).
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/klunkerkranich/klunkerkranich-overview-collective.html")!!
                .bufferedReader()
                .readText()
        val clique = scrape(html).first { it.title.startsWith("CLIQUE BOOKING") }

        val names = clique.artists.map { it.name }
        names shouldContainAll listOf("In Limbo Audio", "Sven Howland", "Niklas Gietmann", "Nicolai Toma", "Miz Kiara", "p.toile")
        names.none { it.contains(':') } shouldBe true
        clique.artists.first { it.name == "Acua" }.role shouldBe "HEADLINER"
        clique.artists.first { it.name == "Sven Howland" }.role shouldBe "DJ"
    }

    @Test
    fun `mints no artist from a title that bills none`() {
        // "LA MAISON x KLUNKERKRANICH" names the night and its guest collective, not a performer.
        on(LocalDate.of(2026, 8, 9), "LA MAISON x KLUNKERKRANICH").artists.shouldBeEmpty()
    }

    @Test
    fun `falls back to the rendered German date, inferring the year from the weekday`() {
        // A slug without the venue's usual ISO prefix leaves the year-less card date as the only source.
        val night = scrape(cardHtml(href = "https://klunkerkranich.org/events/wochenmitte/", dateLine = "Mittwoch, 05. August")).single()

        night.eventDate shouldBe LocalDate.of(2026, 8, 5)
        night.sourceId shouldBe "klunkerkranich:wochenmitte"
    }

    @Test
    fun `skips a card whose date neither the slug nor the rendering supplies`() {
        scrape(cardHtml(href = "https://klunkerkranich.org/events/wochenmitte/", dateLine = "demnächst")).shouldBeEmpty()
    }

    @Test
    fun `returns no events for a page without a programme`() {
        scrape("<html><body><main class=\"c-events-overview\"></main></body></html>").shouldBeEmpty()
    }
}
