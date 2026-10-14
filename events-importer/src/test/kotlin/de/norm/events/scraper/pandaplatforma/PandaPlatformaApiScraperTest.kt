package de.norm.events.scraper.pandaplatforma

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class PandaPlatformaApiScraperTest {
    private val scraper = PandaPlatformaApiScraper()

    private val fixture by lazy {
        javaClass.classLoader
            .getResourceAsStream("scraper/pandaplatforma/pandaplatforma-events.json")!!
            .bufferedReader()
            .readText()
    }

    private val page by lazy { scraper.scrapePage(fixture) }

    private fun event(slug: String) = page.events.single { it.sourceId == "panda_platforma:$slug" }

    private fun names(slug: String) = event(slug).artists.map { it.name }

    /** The fixture's `igr-titov` concert alone, with [title] and [description] in place of its own. */
    private fun concert(
        title: String,
        description: String
    ) = JsonMapper().let { mapper ->
        val root = mapper.readTree(fixture) as ObjectNode
        val events = root.withArray("events")
        val night = events.first { it.path("slug").asString() == "igr-titov" } as ObjectNode
        night.put("title", title).put("description", description)
        events.removeAll()
        events.add(night)
        scraper.scrapePage(mapper.writeValueAsString(root)).events.single()
    }

    @Test
    fun `reads every event except the night at another club`() {
        page.events shouldHaveSize 21
        page.events.none { it.sourceId.contains("pandagoes-frannz") } shouldBe true
        page.nextPageUrl.shouldBeNull()
    }

    @Test
    fun `maps a jazz night with its credited players`() {
        val jazz = event("pandajazz-haffner-reznichenko-mills-breu")

        jazz.title shouldBe "Haffner / Reznichenko / Mills / Breu"
        jazz.eventType shouldBe "CONCERT"
        jazz.typeIsFallback shouldBe false
        jazz.genre shouldBe "Experimental, Jazz"
        jazz.eventDate shouldBe LocalDate.of(2026, 10, 28)
        jazz.startTime shouldBe LocalTime.of(20, 0)
        jazz.endTime shouldBe LocalTime.of(22, 30)
        jazz.pricePresale shouldBe BigDecimal("10")
        jazz.free shouldBe false
        jazz.sourceUrl shouldBe "https://panda-platforma.berlin/event/pandajazz-haffner-reznichenko-mills-breu/"
        jazz.imageUrl shouldBe "https://panda-platforma.berlin/wp-content/uploads/Black-and-White-Design-6-.jpg"
        jazz.artists.map { it.name } shouldContainExactly listOf("Jonathon Haffner", "Olga Reznichenko", "Kellen Mills", "Maximilian Breu")
        jazz.promoters.shouldBeEmpty()
    }

    @Test
    fun `types a night by its series, text before music`() {
        event("tobias-bamborschke-aus-der-ferne-wirkt-die-h-lle-wie-ein-abenteuer").eventType shouldBe "READING"
        event("panda-poetry-slam-halloween").eventType shouldBe "READING"
        event("55th-bday-of-delphinov-theatrical-poetry-performance-44").eventType shouldBe "SHOW"
        event("exhibition-opening-the-eye-of-the-beholder-by-ekaterina-sisfontes").eventType shouldBe "EXHIBITION"
        event("trawy-i-kamienie").genre shouldBe "World"
    }

    @Test
    fun `falls back on the title for an event filed under no series`() {
        val reading = event("28-staged-reading")

        // "“28”: staged reading" names its format in English.
        reading.eventType shouldBe "READING"
        reading.typeIsFallback shouldBe true
        reading.ticketUrl shouldBe "https://www.eventbrite.com/e/28-staged-reading-tickets-2002750668161"
        reading.pricePresale.shouldBeNull()
        reading.free shouldBe false
    }

    @Test
    fun `reads a free event and a price range`() {
        val lecture = event("gasan-gusejnov-russian-profanity-in-a-disintegrating-empire")
        lecture.free shouldBe true
        lecture.pricePresale.shouldBeNull()

        val range = event("foaie-verde-virtuosic-world-music-from-budapest-across-the-balkans-to-the-black-sea")
        range.pricePresale.shouldBeNull()
        range.priceNote shouldBe "€20.00 – €25.00"
    }

    @Test
    fun `credits players named before a colon, bold or not`() {
        names("incense-of-music-76-under-the-voice") shouldContainExactly listOf("AJ Nilles", "Grégoire Simon", "Yodfat Miron")
        names("trawy-i-kamienie") shouldContainExactly listOf("Emilia Niezbecka", "Zhenja Oks", "Stefan Ziemer")
        names("pandajazz-ensemble-su") shouldContainExactly listOf("SO Sol-I", "Peter EHWALD", "KIM Bo-Sung", "SHIN Hyo Jin")
    }

    @Test
    fun `credits players named before a dash without bold`() {
        names("foaie-verde-virtuosic-world-music-from-budapest-across-the-balkans-to-the-black-sea") shouldContainExactly
            listOf("Katalin Horvath", "Jordan Djevic", "Felix Borel", "Frank Wekenmann", "Veit Hübner")
    }

    @Test
    fun `bills the title of a concert without a credit list`() {
        val titov = event("igr-titov").artists
        titov.map { it.name } shouldContainExactly listOf("Igr Titov")
        titov.single().titleDerived shouldBe true
        names("llull-machines-present-dear-boy") shouldContainExactly listOf("LLULL MACHINES")
        names("god-is-a-perfect-sine-d-arcangelo") shouldContainExactly listOf("GOD IS A PERFECT SINE", "D’Arcangelo")
    }

    @Test
    fun `bills nobody on a series night that names its players only in prose`() {
        event("peyvand-3-iran-ava-iran-muss-man-h-ren").artists.shouldBeEmpty()
    }

    @Test
    fun `takes no label, photo credit or prose sentence for a player`() {
        val night =
            concert(
                title = "Series: A Night",
                description =
                    "<p><strong>Incense</strong>: Frankincense from Yemen<br />Credits: Dovile Sermokas, Nicklas Koppe<br />" +
                        "foto: manuel miethe<br />Text and performance: Alexander Delphinov<br />" +
                        "Peter Ehwald: the saxophonist who won the German Jazz Prize for woodwinds this year brings his quartet " +
                        "to the house for a night of new pieces</p>"
            )

        night.artists.shouldBeEmpty()
    }

    @Test
    fun `reads a credit list split by paragraphs and named once per language`() {
        val night =
            concert(
                title = "Some Title",
                description = "<p>Anna Kaluza: altosax</p><p>Céline Voccia &#8211; piano</p><div>Anna Kaluza: Altsaxophon</div>"
            )

        night.artists.map { it.name } shouldContainExactly listOf("Anna Kaluza", "Céline Voccia")
        night.artists.none { it.titleDerived } shouldBe true
    }

    @Test
    fun `decodes the entities WordPress leaves in a title`() {
        page.events.map { it.title }.forEach { it shouldNotContain "&#" }
    }

    @Test
    fun `returns no events for a body that is not JSON`() {
        scraper.scrapePage("<html>maintenance</html>").events.shouldBeEmpty()
    }

    // Russian is neither of the two languages the schema stores, so the text stays as published (#330).
    @Test
    fun `stores a Russian and English description whole`() {
        val sine = event("god-is-a-perfect-sine-d-arcangelo").toEventEntity(venueId = 1L, venueSlug = "panda-platforma", eventSourceId = 1L)

        sine.descriptionAlt.shouldBeNull()
        sine.description.shouldNotBeNull() shouldContain "[EN]"
    }
}
