package de.norm.events.scraper.brotfabrik

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class BrotfabrikApiScraperTest {
    private val scraper = BrotfabrikApiScraper()

    private val fixture by lazy {
        javaClass.classLoader
            .getResourceAsStream("scraper/brotfabrik/brotfabrik-events.json")!!
            .bufferedReader()
            .readText()
    }

    private val page by lazy { scraper.scrapePage(fixture) }

    private fun event(path: String) = page.events.single { it.sourceId == "brotfabrik:$path" }

    /** The fixture's `glanz` night alone, with [category] as its only category and [title] in place of its own. */
    private fun glanzAs(
        category: String,
        title: String = "Glanz"
    ) = JsonMapper().let { mapper ->
        val root = mapper.readTree(fixture) as ObjectNode
        val events = root.withArray("events")
        val night = events.first { it.path("slug").asString() == "glanz" } as ObjectNode
        night.put("title", title)
        night.withArray("categories").apply {
            removeAll()
            addObject().put("name", category)
        }
        events.removeAll()
        events.add(night)
        scraper.scrapePage(mapper.writeValueAsString(root)).events
    }

    @Test
    fun `reads the stage and the readings without the children's shows`() {
        page.events shouldHaveSize 18
        page.nextPageUrl.shouldBeNull()
        page.events.map { it.title }.shouldNotContainAnyOf(
            "Die Wimpernzwerge",
            "Weihnachten im Hexenwald",
            "Es wird Ernst! Puppe besetzt Brotfabrik//KINDERSHOWS!"
        )
    }

    @Test
    fun `maps a theatre night`() {
        val glanz = event("glanz")

        glanz.title shouldBe "Glanz"
        glanz.eventType shouldBe "SHOW"
        glanz.eventDate shouldBe LocalDate.of(2026, 10, 22)
        glanz.startTime shouldBe LocalTime.of(20, 0)
        glanz.endDate.shouldBeNull()
        glanz.endTime.shouldBeNull()
        glanz.sourceUrl shouldBe "https://brotfabrik-berlin.de/veranstaltung/glanz/"
        glanz.imageUrl shouldBe "https://brotfabrik-berlin.de/wp-content/uploads/2026/09/Glanz–Copyright.Johanna_Maria_Dietz_011.jpg"
        glanz.ticketUrl shouldBe "https://www.eventbrite.de/e/glanz-tickets-2000411933944?aff=oddtdtcreator"
        glanz.pricePresale.shouldBeNull()
        glanz.priceNote shouldBe "€12 – €18"
        glanz.free shouldBe false
        glanz.artists.shouldBeEmpty()
        glanz.description!!.startsWith("22.10.26 | 20 Uhr Ein Vorsprechen") shouldBe true
    }

    @Test
    fun `keys each date of a run on its own path`() {
        page.events.map { it.sourceId } shouldContainAll
            listOf(
                "brotfabrik:frankenstein-ein-fest-zu-haloween/2026-10-29",
                "brotfabrik:frankenstein-ein-fest-zu-haloween/2026-10-30",
                "brotfabrik:frankenstein-ein-fest-zu-haloween/2026-10-31"
            )
        page.events.map { it.sourceId }.distinct() shouldHaveSize page.events.size
    }

    @Test
    fun `types a reading by its category`() {
        val reading = event("berliner-literaturwerkstatt-queer-vol-3")

        reading.eventType shouldBe "READING"
        reading.free shouldBe true
        reading.pricePresale.shouldBeNull()
        page.events.single { it.title.startsWith("»Jüdisches Weißensee«") }.eventType shouldBe "READING"
    }

    @Test
    fun `keeps a donation as the price note and a show for teenagers`() {
        val night = event("einer-von-euch-sein/2026-11-10")

        night.priceNote shouldBe "Spende"
        night.pricePresale.shouldBeNull()
    }

    @Test
    fun `drops every category but the stage and literature`() {
        glanzAs("Kino").shouldBeEmpty()
        glanzAs("Kurs").shouldBeEmpty()
        glanzAs("Galerie").shouldBeEmpty()
        glanzAs("Literatur").single().eventType shouldBe "READING"
    }

    @Test
    fun `types a stage night by a format its title names`() {
        glanzAs("Bühne", "Poetry Slam im Herbst").single().eventType shouldBe "READING"
    }

    @Test
    fun `reads a single price as the presale price`() {
        val single =
            JsonMapper().let { mapper ->
                val root = mapper.readTree(fixture) as ObjectNode
                val events = root.withArray("events")
                val night = events.first { it.path("slug").asString() == "glanz" } as ObjectNode
                night.withObject("cost_details").withArray("values").apply {
                    removeAll()
                    add("15")
                }
                events.removeAll()
                events.add(night)
                scraper.scrapePage(mapper.writeValueAsString(root)).events.single()
            }

        single.pricePresale shouldBe BigDecimal("15")
        single.priceNote.shouldBeNull()
    }
}
