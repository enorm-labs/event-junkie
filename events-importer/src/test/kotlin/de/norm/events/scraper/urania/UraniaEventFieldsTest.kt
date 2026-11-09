package de.norm.events.scraper.urania

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Unit tests for the field mapping in `UraniaEventFields.kt`. */
class UraniaEventFieldsTest {
    @Test
    fun `types the house's talk formats as spoken word`() {
        // Every live talk format as of #1927, plus the missing label.
        listOf(
            "Vortrag",
            "Podiumsdiskussion",
            "Podiumsdiskussion zur Buchpremiere",
            "Podiumsgespräch",
            "Podiumsgespräch in englischer Sprache",
            "Lesung und Diskussion für Schulklassen",
            "Debatte",
            "Schönheitssalon",
            "Live-Podcast",
            null
        ).map { uraniaEventType(it) }
            .toSet() shouldBe setOf(EventType.READING.name)
    }

    @Test
    fun `types a workshop as other wherever the label names it`() {
        // The live formats as of #1906.
        listOf("Workshop im Urania-Garten", "Film und Workshop für Schulklassen", "Workshop für Schulklassen", "Workshop")
            .map { uraniaEventType(it) }
            .toSet() shouldBe setOf(EventType.OTHER.name)
    }

    @Test
    fun `types a guided walk as other`() {
        // Kiezspaziergang is the live format as of #1927.
        listOf("Kiezspaziergang", "Spaziergang durch den Tiergarten", "Rundgang")
            .map { uraniaEventType(it) }
            .toSet() shouldBe setOf(EventType.OTHER.name)
    }

    @Test
    fun `types a workshop by its title when the title names an in-scope format`() {
        uraniaEventType("Workshop", "Das philosophische Pubquiz") shouldBe EventType.QUIZ.name
        uraniaEventType("Workshop", "Ernte und Ausblick") shouldBe EventType.OTHER.name
        isParticipationFormat("Film und Workshop für Schulklassen") shouldBe true
        isParticipationFormat("Einführung und Gespräch") shouldBe false
    }

    @Test
    fun `keeps a talk whose label merely contains führung`() {
        uraniaEventType("Einführung und Gespräch") shouldBe EventType.READING.name
    }

    @Test
    fun `lets the shared table and the house's synonyms decide first`() {
        uraniaEventType("Konzert") shouldBe EventType.CONCERT.name
        uraniaEventType("Film") shouldBe EventType.SCREENING.name
    }

    @Test
    fun `types a film night with a panel as a screening`() {
        // The live formats as of #4759.
        listOf("Film und Podium", "Filmpremiere und Podium", "Kinoabend mit Gespräch")
            .map { uraniaEventType(it) }
            .toSet() shouldBe setOf(EventType.SCREENING.name)
    }

    @Test
    fun `keeps a talk that only mentions a film after its opening word`() {
        uraniaEventType("Vortrag mit Filmausschnitten") shouldBe EventType.READING.name
        uraniaEventType("Podiumsgespräch") shouldBe EventType.READING.name
        uraniaEventType("Film und Workshop für Schulklassen") shouldBe EventType.OTHER.name
    }

    @Test
    fun `bills a film night's panel`() {
        uraniaSpeakers("Ada Beispiel, Max Muster", EventType.SCREENING.name).map { it.name } shouldBe
            listOf("Ada Beispiel", "Max Muster")
    }

    @Test
    fun `bills a talk's speakers and nobody at a workshop`() {
        val billing = "Léna Kútvölgyi, Sara Stenczer"
        uraniaSpeakers(billing, EventType.READING.name).map { it.name } shouldBe listOf("Léna Kútvölgyi", "Sara Stenczer")
        uraniaSpeakers(billing, EventType.OTHER.name).shouldBeEmpty()
    }
}
