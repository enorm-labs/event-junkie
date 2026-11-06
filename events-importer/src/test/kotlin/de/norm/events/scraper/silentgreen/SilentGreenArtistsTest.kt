package de.norm.events.scraper.silentgreen

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SilentGreenArtistsTest {
    private fun actsOf(title: String) = silentGreenArtists(title, "CONCERT").map { it.name }

    @Test
    fun `drops a series set off by an en dash in front of several acts`() {
        // 23 October 2026.
        actsOf("HALF LIGHT – Abul Mogard, Marja de Sanctis, Rafael Anton Irisarri, Concepción Huerta") shouldContainExactly
            listOf("Abul Mogard", "Marja de Sanctis", "Rafael Anton Irisarri", "Concepción Huerta")
    }

    @Test
    fun `drops the house series named after the act`() {
        // 26 and 27 September 2026.
        actsOf("Current 93 + Reg Meuross – Sonic Morgue") shouldContainExactly listOf("Current 93", "Reg Meuross")
    }

    @Test
    fun `bills the lead of a colon title only when the sub-line names it too`() {
        // 31 August 2026, stored as an act until V167 (#3415).
        actsOf("The I in the Mirror: Reflection").shouldBeEmpty()
        silentGreenArtists("The I in the Mirror: Reflection", "CONCERT", "silent green präsentiert").shouldBeEmpty()
        silentGreenArtists("Hanno Leichtmann: OSCILLAZIONI", "CONCERT", "Hanno Leichtmann live mit Ensemble").map { it.name } shouldContainExactly
            listOf("Hanno Leichtmann")
        // Several acts after the colon still lose the series in front of them.
        actsOf("Psychic Liberation Night: Niloofar Asghary + Júlia Koffler") shouldContainExactly listOf("Niloofar Asghary", "Júlia Koffler")
    }

    @Test
    fun `reads the players of a Mit line, without a bracketed instrument, and only at the start of a line`() {
        silentGreenPerformers("Mit: Anna Holm (Cello), Ben Kurz und Cleo Marx.").map { it.name } shouldContainExactly
            listOf("Anna Holm", "Ben Kurz", "Cleo Marx")
        silentGreenPerformers("Ein Konzert mit: Anna Holm").shouldBeEmpty()
        silentGreenPerformers(null).shouldBeEmpty()
    }

    private fun concert(title: String) =
        ScrapedEvent(
            title = title,
            subtitle = "guests: Aude Langlois & Felipe Vareschi",
            eventType = "CONCERT",
            eventDate = LocalDate.of(2027, 3, 5),
            sourceUrl = "https://www.silent-green.net/programm/detail/hainbach-presents-vive",
            sourceId = "silent_green:2027-03-05-hainbach-presents-vive",
            artists = listOf(ScrapedArtist(name = "Vive", role = ArtistRole.HEADLINER))
        )

    @Test
    fun `bills the head of a title whose tail the blurb calls an album, with the guests as support`() {
        // #4511, event 12951.
        val details = SilentGreenEventDetails(description = "Mit seinem neuen Album Vive (Seil Records, Oktober 2026) zeigt sich Hainbach")

        val artists = details.applyTo(concert("HAINBACH presents VIVE")).artists

        artists.map { it.name } shouldContainExactly listOf("Hainbach", "Aude Langlois", "Felipe Vareschi")
        artists.map { it.role } shouldContainExactly listOf(ArtistRole.HEADLINER, ArtistRole.SUPPORT, ArtistRole.SUPPORT)
    }

    @Test
    fun `keeps the row's acts when the blurb does not call the tail an album`() {
        val details = SilentGreenEventDetails(description = "Vive tritt zum ersten Mal in Berlin auf.")

        details.applyTo(concert("HAINBACH presents VIVE")).artists.map { it.name } shouldContainExactly listOf("Vive")
    }
}
