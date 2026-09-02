package de.norm.events.scraper.madameclaude

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MadameClaudeApiScraper].
 *
 * Parses a saved snapshot of Madame Claude's WordPress REST API response
 * (`/wp-json/wp/v2/event?_embed=wp:featuredmedia`) for deterministic, offline-safe testing
 * without HTTP fetching. The fixture pins one event of every ACF `event_type` the venue uses.
 */
class MadameClaudeApiScraperTest {
    private val scraper = MadameClaudeApiScraper()

    private val events: List<ScrapedEvent> by lazy {
        val json =
            javaClass.classLoader
                .getResourceAsStream("scraper/madameclaude/madameclaude-events.json")!!
                .bufferedReader()
                .readText()
        scraper.scrape(json)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `parses every event in the API response`() {
        events shouldHaveSize 10
    }

    @Test
    fun `maps all fields of a representative concert from post date, acf and embedded media`() {
        val concert = event("madame_claude:sam-therapy-jonathan-bergen-dj-lichene")
        concert.title shouldBe "SAM THERAPY + Jonathan Bergen + DJ Lichene"
        concert.eventType shouldBe "CONCERT"
        // Date and start time both come from the post `date`; doors from acf.event_doors_time.
        concert.eventDate shouldBe LocalDate.of(2026, 8, 3)
        concert.startTime shouldBe LocalTime.of(20, 0)
        concert.doorsTime shouldBe LocalTime.of(19, 0)
        concert.imageUrl shouldBe "https://madameclaude.de/wp-content/uploads/2026/07/4786a4d65375d971-madameclaude.jpg"
        concert.sourceUrl shouldBe "https://madameclaude.de/event/sam-therapy-jonathan-bergen-dj-lichene/"
        concert.priceNote shouldBe "By Donation"
        concert.free shouldBe false
        concert.status shouldBe "SCHEDULED"
    }

    @Test
    fun `splits a concert co-bill from the title into headliners`() {
        event("madame_claude:sam-therapy-jonathan-bergen-dj-lichene").artists shouldContainExactly
            listOf(
                ScrapedArtist("SAM THERAPY", "HEADLINER"),
                ScrapedArtist("Jonathan Bergen", "HEADLINER"),
                ScrapedArtist("DJ Lichene", "HEADLINER")
            )
    }

    @Test
    fun `strips a trailing DJ-Set marker from a concert act name`() {
        // "… + EMMANUELLE 5 (DJ-Set)" on a Concert stays a headliner, with the suffix stripped.
        val concert = event("madame_claude:schilf-plum-texes-bgtcb-emmanuelle-5-dj-set")
        concert.eventType shouldBe "CONCERT"
        concert.artists.map { it.name } shouldContainExactly listOf("SCHILF", "PLUM TEXES", "BGTCB", "EMMANUELLE 5")
        concert.artists.map { it.role }.toSet() shouldBe setOf("HEADLINER")
    }

    @Test
    fun `types a DJ-Set night as PARTY and names its DJ from the title`() {
        val djSet = event("madame_claude:keikee-dj-set-2")
        djSet.eventType shouldBe "PARTY"
        djSet.artists shouldContainExactly listOf(ScrapedArtist("Keikee", "DJ"))
    }

    @Test
    fun `splits co-DJs on the conjunction, never on a slash inside one act name`() {
        djSetArtistsFromTitle("Lichene & Neue K (DJ-Set)") shouldContainExactly
            listOf(ScrapedArtist("Lichene", "DJ"), ScrapedArtist("Neue K", "DJ"))
        djSetArtistsFromTitle("Matthew Ryals + Morimoto / Wong duo (DJ-Set)") shouldContainExactly
            listOf(ScrapedArtist("Matthew Ryals", "DJ"), ScrapedArtist("Morimoto / Wong duo", "DJ"))
    }

    @Test
    fun `types an open mic as CONCERT and drops the denylisted series name from its lineup`() {
        // "Open Mic L. J. Fox + M Love (DJ-Set)" — the recurring series name is not an artist.
        val openMic = event("madame_claude:open-mic-l-j-fox-m-love-dj-set")
        openMic.eventType shouldBe "CONCERT"
        openMic.artists shouldContainExactly listOf(ScrapedArtist("M Love", "HEADLINER"))
    }

    @Test
    fun `types a music quiz as QUIZ with no artists and a free-entry flag`() {
        val quiz = event("madame_claude:music-quiz-154")
        quiz.eventType shouldBe "QUIZ"
        quiz.artists.shouldBeEmpty()
        quiz.free shouldBe true
        quiz.priceNote.shouldBeNull()
    }

    @Test
    fun `types a film night as SCREENING with no artists`() {
        val screening = event("madame_claude:shorties-films-screening-28")
        screening.eventType shouldBe "SCREENING"
        screening.artists.shouldBeEmpty()
        screening.subtitle shouldBe "Local short films compilation"
    }

    @Test
    fun `types a party as PARTY and mints no artist from its event-name title`() {
        val party = event("madame_claude:summer-break-send-off")
        party.eventType shouldBe "PARTY"
        party.artists.shouldBeEmpty()
    }

    @Test
    fun `types a karaoke night as PARTY with no artists`() {
        event("madame_claude:madame-claudes-17th-birthday-celebration").eventType shouldBe "PARTY"
    }

    @Test
    fun `types a festival as FESTIVAL, mints no artist, and unescapes HTML entities in the title`() {
        val festival = event("madame_claude:live-harry-merry-the-weak-and-the-strong-dj-sets-oberst-panizza-emmanuelle-5")
        festival.eventType shouldBe "FESTIVAL"
        festival.artists.shouldBeEmpty()
        // The raw title contains "&#038;"; it must be decoded to "&".
        festival.title shouldContain "THE WEAK AND THE STRONG & DJ OBERST PANIZZA"
        festival.title shouldNotContain "&#038;"
    }

    @Test
    fun `flattens the HTML description to tag-free text`() {
        val quiz = event("madame_claude:music-quiz-154")
        quiz.description shouldContain "Guess the song!"
        quiz.description!! shouldNotContain "<h3>"
        quiz.description shouldNotContain "&nbsp;"
    }

    @Test
    fun `returns an empty list for a non-array payload`() {
        scraper.scrape("{}").shouldBeEmpty()
    }

    @Test
    fun `returns an empty list for an unparseable body`() {
        scraper.scrape("not json at all").shouldBeEmpty()
    }

    @Test
    fun `returns an empty list for an empty array`() {
        scraper.scrape("[]").shouldBeEmpty()
    }

    // Audit T-13: this scraper was the one that never routed its title through the shared cleanup,
    // so the venue editor's stray double space reached the stored title verbatim.
    @Test
    fun `collapses a whitespace run in the title`() {
        val json =
            """
            [{"id":1,"slug":"adventurous-juan","date":"2026-08-06T23:00:00",
              "title":{"rendered":"Adventurous Juan  (DJ-Set)"},"acf":{}}]
            """.trimIndent()

        val event = scraper.scrape(json).single()

        event.title shouldBe "Adventurous Juan (DJ-Set)"
    }
}
