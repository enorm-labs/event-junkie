package de.norm.events.scraper.modus

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.buildArtistsForEventType
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class ModusComedySeriesTest {
    private fun lineup(html: String): List<String> =
        comedySeriesLineup(Jsoup.parseBodyFragment("""<div class="event-description">$html</div>""").selectFirst(".event-description"))
            .map { it.name }

    @Test
    fun `stops at a line ending in sentence punctuation`() {
        lineup("<p>Host: Anna Bomhard<br>Rebecca Pap<br>Wir sind im Comedy-Modus!<br>Ludwig Benecke</p>") shouldBe
            listOf("Anna Bomhard", "Rebecca Pap")
    }

    @Test
    fun `stops at a label followed by a colon`() {
        lineup("<p>Rebecca Pap</p><p>Modus Comedy heißt: Comedy</p><p>Ludwig Benecke</p>") shouldBe listOf("Rebecca Pap")
    }

    @Test
    fun `stops at a line of more than five words`() {
        lineup("<p>Rebecca Pap<br>Feinster Standup aus der Hauptstadt heute<br>Ludwig Benecke</p>") shouldBe listOf("Rebecca Pap")
    }

    @Test
    fun `keeps a name of up to five words`() {
        lineup("<p>Rebecca Pap<br>Jean Claude van der Berg</p>") shouldBe listOf("Rebecca Pap", "Jean Claude van der Berg")
    }

    @Test
    fun `bills nobody when the description opens with prose`() {
        lineup("<p>Feinster Standup aus der Hauptstadt. Till Reiners</p><p>Rebecca Pap</p>") shouldBe emptyList()
    }

    @Test
    fun `bills a listing tile's night by the acts after its mit`() {
        // A listing tile has no description, so this is the overview's path.
        val title = "Just Signed! | mit Yasola, Victoria West & PARA"

        modusLineup(title, modusEventType(title), description = null) shouldBe
            listOf(
                ScrapedArtist("Yasola", "HEADLINER", titleDerived = true),
                ScrapedArtist("Victoria West", "HEADLINER", titleDerived = true),
                ScrapedArtist("PARA", "HEADLINER", titleDerived = true)
            )
    }

    @Test
    fun `bills an act's own backing as the act alone`() {
        val title = "Lacrimosa mit Orchester"

        modusLineup(title, modusEventType(title), description = null).map { it.name } shouldBe listOf("Lacrimosa")
    }

    @Test
    fun `bills the acts behind a series name and a dash, not the series`() {
        val title = "With a Twist - Goldie333, JnnrHndrxx & ORANYA"

        modusLineup(title, modusEventType(title), description = null).map { it.name } shouldBe
            listOf("Goldie333", "JnnrHndrxx", "ORANYA")
    }

    @Test
    fun `keeps an act's dash-joined tour or record name as before`() {
        listOf("Yorick Thiede - Polygon", "Andy Strauß - Dosenpfandbetrug", "Spree vom Weizen - Poetry Slam & Stand Up Show").forEach { title ->
            val eventType = modusEventType(title)
            modusLineup(title, eventType, description = null) shouldBe
                buildArtistsForEventType(title, subtitle = null, eventType = eventType, unpackWithFrame = true)
        }
    }

    @Test
    fun `bills nobody on a numbered session whose line-up is secret`() {
        val title = "Corrupted Blood Pit Session 004"

        modusLineup(title, modusEventType(title), description = null) shouldBe emptyList()
    }

    @Test
    fun `bills nobody on a comedy series tile`() {
        modusLineup("Modus Comedy", modusEventType("Modus Comedy"), description = null) shouldBe emptyList()
    }
}
