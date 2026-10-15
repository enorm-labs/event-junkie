package de.norm.events.scraper.gaertenderwelt

import de.norm.events.event.EventType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Unit tests for [gaertenDerWeltArtists]: the teaser line-up, the production titles and the shared title fallback (#3315). */
class GaertenDerWeltArtistsTest {
    @Test
    fun `bills the acts a concert's teaser lists after mit, not the title`() {
        val artists =
            gaertenDerWeltArtists(
                "Das Mega 90er Live Open Air",
                "5x Berlin, 5x ausverkauft! 🔥 Das MEGA 90er LIVE Open Air ist zurück – mit Haddaway, Loona, East 17 & Mütze Katze. Jetzt Tickets sichern!",
                EventType.CONCERT.name
            )

        artists.map { it.name } shouldBe listOf("Haddaway", "Loona", "East 17", "Mütze Katze")
        artists.map { it.role }.distinct() shouldBe listOf("HEADLINER")
    }

    @Test
    fun `reads a teaser line-up that joins with und and names a doctor`() {
        gaertenDerWeltArtists("Die 90er Party", "Live mit Dr. Alban und Captain Jack", EventType.CONCERT.name)
            .map { it.name } shouldBe listOf("Dr. Alban", "Captain Jack")
    }

    @Test
    fun `bills no act for a concert whose title names a production`() {
        listOf(
            "Drone Art Show: Harry Potter",
            "Drohnenshow",
            "Taschenlampenweihnachtskonzert",
            "Das Mega 90er Live Open Air",
            "EPIC SYMPHONY LIVE — Orchestra, Choir & DJ"
        ).forEach { title ->
            gaertenDerWeltArtists(title, "Ein magischer Abend voller Klang, Licht und moderner Technik.", EventType.CONCERT.name) shouldBe emptyList()
        }
    }

    @Test
    fun `keeps the title act of a concert whose teaser names one act in passing`() {
        // One name after `mit`, mid-sentence: the support act the title already bills, not a line-up.
        gaertenDerWeltArtists(
            "KARAT „45 Jahre Der blaue Planet“ Vorprogramm: Dirk Michaelis",
            "Live in den Gärten der Welt mit Dirk Michaelis im Vorprogramm!",
            EventType.CONCERT.name
        ).map { it.name } shouldBe listOf("KARAT", "Dirk Michaelis")
        gaertenDerWeltArtists("Agnes Obel", "Support: Peter Gregson", EventType.CONCERT.name)
            .map { it.name to it.role } shouldBe listOf("Agnes Obel" to "HEADLINER", "Peter Gregson" to "SUPPORT")
        gaertenDerWeltArtists("Stereoact: Ich liebe das Leben Party 2027 feat. Lena Marie Engel", null, EventType.CONCERT.name)
            .map { it.name } shouldBe listOf("Stereoact", "Lena Marie Engel")
    }

    @Test
    fun `keeps the support act beside a teaser line-up and reads no line-up outside a concert`() {
        gaertenDerWeltArtists("Das Mega 90er Live Open Air", "Mit Haddaway & Loona! — Support: Mütze Katze", EventType.CONCERT.name)
            .map { it.name to it.role } shouldBe listOf("Haddaway" to "HEADLINER", "Loona" to "HEADLINER", "Mütze Katze" to "SUPPORT")
        gaertenDerWeltArtists("Spieleabend", "Spiele mit Freunden, Nachbarn & Familie.", EventType.QUIZ.name) shouldBe emptyList()
    }
}
