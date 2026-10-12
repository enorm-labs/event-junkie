package de.norm.events.scraper.cassiopeia

import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Unit tests for [CassiopeiaDetailPageScraper].
 *
 * Tests the pure HTML parsing logic in isolation, without HTTP fetching
 * or overview page fallback. Focuses on artist extraction edge cases
 * that are most naturally tested against the detail page scraper directly.
 */
class CassiopeiaDetailPageScraperTest {
    private val scraper = CassiopeiaDetailPageScraper()

    /**
     * Builds a minimal Cassiopeia detail page HTML with the given [title],
     * [category], and optional [descriptionParagraphs].
     */
    private fun buildDetailHtml(
        title: String,
        category: String = "Konzert",
        descriptionParagraphs: List<String> = emptyList()
    ): String {
        val paragraphs =
            descriptionParagraphs.joinToString("\n") {
                """<div class="paragraph events">$it</div>"""
            }
        return """
            <html><body>
            <div class="modul-section events">
                <h1 class="event-date dark event">$title</h1>
                <div class="text-block-wrapper events">
                    <div class="date-wrapper">
                        <div class="subheading invert">01</div>
                        <div class="subheading invert">.</div>
                        <div class="subheading invert">06</div>
                        <div class="subheading invert">.</div>
                        <div class="subheading invert event-mobile opposite">2026</div>
                    </div>
                </div>
                <div class="text-block-wrapper events">
                    <div class="subheading invert gap">$category</div>
                </div>
                <div class="paragraph-wrapper">$paragraphs</div>
            </div>
            </body></html>
            """.trimIndent()
    }

    @Test
    fun `scrape extracts headliner and support from concert with support line`() {
        val html = buildDetailHtml("Pharmakon", descriptionParagraphs = listOf("Support: Aska", "Bio text."))
        val doc = Jsoup.parse(html, "https://cassiopeia-berlin.de/event/pharmakon-123")
        val event = scraper.scrape(doc, "https://cassiopeia-berlin.de/event/pharmakon-123")

        event shouldNotBe null
        event!!.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Pharmakon", role = "HEADLINER", titleDerived = true),
                ScrapedArtist(name = "Aska", role = "SUPPORT")
            )
    }

    @Test
    fun `scrape splits a support line that names two acts`() {
        // Loma Prieta, 4 October 2026: one labelled line, two bands (#2623).
        val html = buildDetailHtml("Loma Prieta", descriptionParagraphs = listOf("Support: deshielo + la haine", "Bio text."))
        val url = "https://cassiopeia-berlin.de/event/loma-prieta-111623520"

        scraper.scrape(Jsoup.parse(html, url), url)!!.artists.map { it.name to it.role } shouldContainExactly
            listOf("Loma Prieta" to "HEADLINER", "deshielo" to "SUPPORT", "la haine" to "SUPPORT")
    }

    @Test
    fun `scrape reads a plus line as support, and not at a party`() {
        // jolle, 28 September 2026: the relocation notice, then the support on a line of its own.
        val concert =
            buildDetailHtml(
                "jolle",
                descriptionParagraphs = listOf("Hinweis: das Konzert wurde vom Lido in das cassiopeia verlegt!", "+ Dani Lia", "Bio text.")
            )
        scraper
            .scrape(Jsoup.parse(concert, "https://cassiopeia-berlin.de/event/jolle-111687604"), "https://cassiopeia-berlin.de/event/jolle-111687604")!!
            .artists
            .map { it.name to it.role } shouldContainExactly listOf("jolle" to "HEADLINER", "Dani Lia" to "SUPPORT")

        val party = buildDetailHtml("Forever Young Party", category = "Party", descriptionParagraphs = listOf("+ free Kicker... garden.... und vieles mehr!"))
        scraper
            .scrape(Jsoup.parse(party, "https://cassiopeia-berlin.de/event/forever-young-1"), "https://cassiopeia-berlin.de/event/forever-young-1")!!
            .artists
            .shouldBeEmpty()
    }

    @Test
    fun `scrape extracts title as headliner from concert without support line`() {
        val html = buildDetailHtml("Pharmakon", descriptionParagraphs = listOf("Bio text, no support line."))
        val doc = Jsoup.parse(html, "https://cassiopeia-berlin.de/event/pharmakon-123")
        val event = scraper.scrape(doc, "https://cassiopeia-berlin.de/event/pharmakon-123")

        event shouldNotBe null
        // CONCERT confirms the title is the headliner, even with no "Support:" line
        event!!.artists shouldContainExactly listOf(ScrapedArtist(name = "Pharmakon", role = "HEADLINER", titleDerived = true))
    }

    @Test
    fun `scrape filters an event-name title (festival slot) rather than minting a fake artist`() {
        val html = buildDetailHtml("Grey City Fest Opener", descriptionParagraphs = listOf("A festival event."))
        val doc = Jsoup.parse(html, "https://cassiopeia-berlin.de/event/grey-city-123")
        val event = scraper.scrape(doc, "https://cassiopeia-berlin.de/event/grey-city-123")

        event shouldNotBe null
        // "Grey City Fest Opener" is a festival slot name, filtered by isNonArtistName
        event!!.artists.shouldBeEmpty()
    }

    @ParameterizedTest(name = "scrape filters out placeholder headliner: \"{0}\"")
    @ValueSource(strings = ["TBA", "tba", "TBD", "N.N.", "t.b.a."])
    fun `scrape filters out placeholder headliner names`(placeholder: String) {
        val html = buildDetailHtml(placeholder, descriptionParagraphs = listOf("Support: Real Artist"))
        val doc = Jsoup.parse(html, "https://cassiopeia-berlin.de/event/tba-123")
        val event = scraper.scrape(doc, "https://cassiopeia-berlin.de/event/tba-123")

        event shouldNotBe null
        // Placeholder headliner is filtered, only the real support act remains
        event!!.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Real Artist", role = "SUPPORT")
            )
    }

    @ParameterizedTest(name = "scrape extracts headliner when support act is placeholder: \"{0}\"")
    @ValueSource(strings = ["TBA", "tba", "TBD", "N.N.", "t.b.a."])
    fun `scrape extracts headliner even when support act is a placeholder`(placeholder: String) {
        val html = buildDetailHtml("Döll", descriptionParagraphs = listOf("Support: $placeholder"))
        val doc = Jsoup.parse(html, "https://cassiopeia-berlin.de/event/doell-123")
        val event = scraper.scrape(doc, "https://cassiopeia-berlin.de/event/doell-123")

        event shouldNotBe null
        // "Support: TBA" confirms the headliner pattern — title IS the artist.
        // The placeholder support act is filtered from the output,
        // but the headliner is still extracted.
        event!!.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Döll", role = "HEADLINER", titleDerived = true)
            )
    }

    @Test
    fun `scrape does not extract artists from party events`() {
        val html = buildDetailHtml("Super Tuesday", category = "Party", descriptionParagraphs = listOf("Party time!"))
        val doc = Jsoup.parse(html, "https://cassiopeia-berlin.de/event/super-tuesday-123")
        val event = scraper.scrape(doc, "https://cassiopeia-berlin.de/event/super-tuesday-123")

        event shouldNotBe null
        event!!.artists.shouldBeEmpty()
    }

    @Test
    fun `types a Sonstiges quiz night by its title and mints no act`() {
        // Quizcarraldo: category "Sonstiges", genre slot "Kneipenquiz" (#2791).
        val url = "https://cassiopeia-berlin.de/event/quizcarraldo-kneipenquiz-111687594"
        val html = buildDetailHtml("Quizcarraldo Kneipenquiz", category = "Sonstiges")
        val event = scraper.scrape(Jsoup.parse(html, url), url).shouldNotBeNull()

        event.eventType shouldBe "QUIZ"
        event.artists.shouldBeEmpty()
    }

    @ParameterizedTest
    @CsvSource(
        "Sonstiges, Flohmarkt im Hof, OTHER",
        "Sonstiges, Quizcarraldo Kneipenquiz, QUIZ",
        "Konzert, Quiz the Band, CONCERT",
        "Party, Pub Quiz Afterparty, PARTY"
    )
    fun `reclassifies only the catch-all category by title`(
        category: String,
        title: String,
        expected: String
    ) {
        cassiopeiaEventType(category, title) shouldBe expected
    }

    @Test
    fun `leaves a missing category untyped, whatever the title says`() {
        cassiopeiaEventType(null, "Quizcarraldo Kneipenquiz").shouldBeNull()
    }

    @Test
    fun `reads a presented-by genre slot as the promoter and leaves the genre empty`() {
        // Thrash Talk Vol 5: the genre slot says "presented by ATOK Berlin" (#1951).
        val url = "https://cassiopeia-berlin.de/event/thrash-talk-vol-5-111591357"
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/cassiopeia/cassiopeia-detail-presented-by.html")!!
                .bufferedReader()
                .readText()
        val event = scraper.scrape(Jsoup.parse(html, url), url).shouldNotBeNull()

        event.promoters shouldBe listOf("ATOK Berlin")
        event.genre.shouldBeNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["presented by ATOK Berlin", "Präsentiert von: ATOK Berlin", "  presented   by ATOK Berlin "])
    fun `finds the promoter in a credit, in either language`(slot: String) {
        presenterInCredit(slot) shouldBe "ATOK Berlin"
    }

    @ParameterizedTest
    @ValueSource(strings = ["Noise", "Thrash Metal", "presented by", ""])
    fun `leaves a genre alone`(slot: String) {
        presenterInCredit(slot).shouldBeNull()
    }

    @Test
    fun `reads a credit list in the description as the promoters`() {
        // BSK, 2027: the last paragraph credits three presenters (#3166).
        val url = "https://cassiopeia-berlin.de/event/bsk-111651972"
        val event = scraper.scrape(Jsoup.parse(fixture("cassiopeia-detail-body-credit.html"), url), url).shouldNotBeNull()

        event.promoters shouldContainExactly listOf("Kidnap Music", "Audiolith Booking", "ROSA e.V.")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "Präsentiert von Trinity Music | Trinity Music",
            "präsentiert von Loft Concerts | Loft Concerts",
            "präsentiert von: Kidnap Music, Audiolith | Kidnap Music;Audiolith",
            "Presented by GreyZone Concerts & ATOK Berlin | GreyZone Concerts;ATOK Berlin"
        ]
    )
    fun `reads a description paragraph the credit opens`(
        credit: String,
        expected: String
    ) {
        val html = buildDetailHtml("The Good The Bad And The Zugly", descriptionParagraphs = listOf("Punk aus Bern.", "Support: tba", credit))
        val url = "https://cassiopeia-berlin.de/event/zugly-1"
        val event = scraper.scrape(Jsoup.parse(html, url), url).shouldNotBeNull()

        event.promoters shouldContainExactly expected.split(";")
        event.artists.map { it.name } shouldContainExactly listOf("The Good The Bad And The Zugly")
    }

    @Test
    fun `reads no promoter from prose that mentions presenting`() {
        val paragraphs =
            listOf(
                "Matze Rossi präsentiert sein neues Album live, presented by nobody but himself.",
                "Die Band presents her new record — präsentiert von der eigenen Crew."
            )
        val html = buildDetailHtml("Matze Rossi Band", descriptionParagraphs = paragraphs)
        val url = "https://cassiopeia-berlin.de/event/matze-rossi-band-1"

        scraper
            .scrape(Jsoup.parse(html, url), url)
            .shouldNotBeNull()
            .promoters
            .shouldBeEmpty()
    }

    @Test
    fun `lists a promoter credited in the genre slot and the description once`() {
        val html =
            buildDetailHtml("Thrash Talk", descriptionParagraphs = listOf("presented by ATOK Berlin"))
                .replace(
                    """<div class="subheading invert gap">Konzert</div>""",
                    """<div class="subheading invert gap">Konzert</div><div>presented by ATOK Berlin</div>"""
                )
        val url = "https://cassiopeia-berlin.de/event/thrash-talk-1"

        scraper.scrape(Jsoup.parse(html, url), url).shouldNotBeNull().promoters shouldContainExactly listOf("ATOK Berlin")
    }

    @Test
    fun `reads a festival's line-up list as headliners and drops its name from the genre`() {
        // Crunch Tapes Fest, 11 October 2026: five bold heads after "Line-up:", three of them in one paragraph (#3102).
        val url = "https://cassiopeia-berlin.de/event/crunch-tapes-fest-111635212"
        val event = scraper.scrape(Jsoup.parse(fixture("cassiopeia-detail-lineup-list.html"), url), url).shouldNotBeNull()

        event.eventType shouldBe "CONCERT"
        event.artists shouldContainExactly
            listOf("Sonic Death", "Shaemless", "Mud Grief", "Dusk Saffron", "Etsamoe").map { ScrapedArtist(name = it, role = "HEADLINER") }
        event.genre.shouldBeNull()
        event.promoters.shouldBeEmpty()
    }

    @Test
    fun `keeps a headliner concert's genre and acts`() {
        val url = "https://cassiopeia-berlin.de/event/pharmakon-123"
        val event = scraper.scrape(Jsoup.parse(fixture("cassiopeia-detail-concert.html"), url), url).shouldNotBeNull()

        event.genre shouldBe "Noise"
        event.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Pharmakon", role = "HEADLINER", titleDerived = true),
                ScrapedArtist(name = "Aska", role = "SUPPORT")
            )
    }

    @Test
    fun `reads only bold heads after the line-up label`() {
        val html =
            buildDetailHtml(
                "Crunch Fest",
                descriptionParagraphs =
                    listOf(
                        "**Before** — a head above the label is not an act.",
                        "LINE UP",
                        "We bring the **loudest** bands — and a market.",
                        "**Sonic Death** – garage punk. **TBA** — more to come.",
                        "Support: Aska"
                    )
            )
        val url = "https://cassiopeia-berlin.de/event/crunch-fest-1"
        val event = scraper.scrape(Jsoup.parse(html, url), url).shouldNotBeNull()

        event.artists shouldContainExactly
            listOf(ScrapedArtist(name = "Sonic Death", role = "HEADLINER"), ScrapedArtist(name = "Aska", role = "SUPPORT"))
    }

    @Test
    fun `lists a title headliner who is also in the line-up once`() {
        val html = buildDetailHtml("Sonic Death", descriptionParagraphs = listOf("Line-up:", "**Sonic Death** — garage punk.", "**Shaemless** — post-punk."))
        val url = "https://cassiopeia-berlin.de/event/sonic-death-1"
        val event = scraper.scrape(Jsoup.parse(html, url), url).shouldNotBeNull()

        event.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Sonic Death", role = "HEADLINER", titleDerived = true),
                ScrapedArtist(name = "Shaemless", role = "HEADLINER")
            )
    }

    @Test
    fun `reads no line-up at a party`() {
        val html = buildDetailHtml("Crunch Party", category = "Party", descriptionParagraphs = listOf("Line-up:", "**DJ Crunch** — all night."))
        val url = "https://cassiopeia-berlin.de/event/crunch-party-1"

        scraper
            .scrape(Jsoup.parse(html, url), url)
            .shouldNotBeNull()
            .artists
            .shouldBeEmpty()
    }

    @ParameterizedTest
    // Every genre slot of the 48 upcoming production rows on 11 October 2026, but the festival name.
    @ValueSource(
        strings = [
            "80s, Disco & Hip Hop", "Indie Anthems, Alternative Pop", "Drag. Pop. Retro. Karaoke. Live-Acts", "post-punk / grunge rock",
            "Kneipenquiz", "Doom Metal", "Metalcore", "80s, Rock, New Wave", "Deutschpunk", "Pop, Hip Hop, House", "80s All Out & Pop",
            "Pop, Rock, Indie, Karaoke", "Post Punk", "Metal", "Queer Icons & Pop Hits", "Rock, Indie, Core, Live-Band", "Hardcore",
            "Indie-Rock", "Pop Punk", "Hip-House, Club-Rap", "Throwback Indie, Twenty One Pilots, Ikkimel Party", "Alternative/Indie",
            "Straight Edge Hardcore", "Dark Rock, NDH", "Beatdown Hardcore", "Dance \\ Electrionic \\ Rock", "Deutschrap",
            "Deathcore, Modern Metal, Hardcore", "Punk, Rock", "Alternative Rock, Punk Rock, Heartland Rock", "Rock", "post-hardcore, emo",
            "Darkwave", "Retro, Pop, House", "Modern Metalcore", "Bewegungs-Punk", "Rap", "electronic screamo", "Electro House", "Punk Rock",
            "Folk Rock, Mittelalter Rock", "Noise", "Classic Indie"
        ]
    )
    fun `keeps a genre slot that names a known style, as written`(slot: String) {
        cassiopeiaGenre(slot) shouldBe slot
    }

    @ParameterizedTest
    @ValueSource(strings = ["Crunch Fest Berlin", "presented by ATOK Berlin", ""])
    fun `drops a genre slot that names no style`(slot: String) {
        cassiopeiaGenre(slot).shouldBeNull()
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/cassiopeia/$name")!!
            .bufferedReader()
            .readText()
}
