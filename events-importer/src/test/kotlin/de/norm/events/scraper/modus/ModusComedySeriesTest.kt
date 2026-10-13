package de.norm.events.scraper.modus

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
}
