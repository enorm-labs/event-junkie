package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The rules of [splitBilingualDescription]. Each source's own text is asserted in its scraper test;
 * the cases here are the ones no fixture holds.
 */
class BilingualDescriptionTest {
    @Test
    fun `cuts at DE and EN headings and drops both`() {
        // Speakeazy's own markup, from an event page in its fixture.
        val split = splitBilingualDescription("DE\n$GERMAN\nEN\n$ENGLISH").shouldNotBeNull()

        split.original shouldBe GERMAN
        split.alt shouldBe ENGLISH
    }

    @Test
    fun `cuts at a dashed english-version heading`() {
        // Privatclub's marker, as the audit in docs/EVENT_DATA_SOURCES.md records it.
        val split = splitBilingualDescription("$GERMAN\n— english version —\n$ENGLISH").shouldNotBeNull()

        split.original shouldBe GERMAN
        split.alt shouldBe ENGLISH
    }

    // Humboldthain's live page on 2026-10-05 opens with a heading and a pointer on one line.
    @Test
    fun `reads a heading left on a line once its pointer is removed`() {
        val split = splitBilingualDescription("DE [scroll down for English]\n$GERMAN\nEN\n$ENGLISH").shouldNotBeNull()

        split.original shouldBe GERMAN
        split.alt shouldBe ENGLISH
    }

    @Test
    fun `leaves a text with no marker alone, whatever languages it holds`() {
        splitBilingualDescription("$GERMAN\n$ENGLISH").shouldBeNull()
        splitBilingualDescription(GERMAN).shouldBeNull()
        splitBilingualDescription("  ").shouldBeNull()
        splitBilingualDescription(null).shouldBeNull()
    }

    @Test
    fun `leaves a text whose marker heads only one language alone`() {
        splitBilingualDescription("$RUSSIAN\n[EN]\n$ENGLISH").shouldBeNull()
        splitBilingualDescription("EN\n$ENGLISH").shouldBeNull()
        splitBilingualDescription("$GERMAN\nEN\nSee you there!").shouldBeNull()
    }

    @Test
    fun `leaves a pointed text alone when its languages alternate`() {
        splitBilingualDescription("(for English please scroll down)\n$GERMAN\n$ENGLISH\n$GERMAN").shouldBeNull()
    }

    @Test
    fun `a single closing line stays with its half, a run of two goes to both`() {
        val signOff = splitBilingualDescription("$GERMAN\nEN\n$ENGLISH\nSee you!").shouldNotBeNull()
        val lineup = splitBilingualDescription("$GERMAN\nEN\n$ENGLISH\nLex Ludlow\nKaldera").shouldNotBeNull()

        signOff.original shouldBe GERMAN
        signOff.alt shouldBe "$ENGLISH\nSee you!"
        lineup.original shouldBe "$GERMAN\nLex Ludlow\nKaldera"
    }

    // Klunkerkranich's live page on 2026-10-05: an English line-up line spoils the German count of the opening text.
    @Test
    fun `peels an opening line-up off the first half and gives it to both`() {
        val lineup =
            "w. anamorphotic, aqwapi, cee_ohh, Dela Nesto, Lisatrix, Magdifique, MELLA MARA, Myzelia, Pilar Jordan, Rave d’Amor, so:fein, " +
                "südstern* +Drag and Whacking activation curated by GAY PANIC, Pole Dance by Purrtastic, Live Visuals by be.bab, " +
                "Opening and Networking by Lena Brecht"
        val german =
            "Mit „FLINTA* CLUB DECK x friends – STAY CORE“ schafft die Crew hinter FLINTA* Club Deck einen Raum für Netzwerk und " +
                "Mitmachen über die Grenzen die Crew hinaus. Dazu gibt es ein Community Networking Event für FLINTA* Artists – " +
                "Moderiert von Lena Brecht."
        val english =
            "With “FLINTA CLUB DECK x friends – STAY CORE” the crew behind FLINTA Club Deck creates a space for networking and " +
                "participation that extends beyond the crew itself. The event also features a community networking event for " +
                "FLINTA artists*, hosted by Lena Brecht."

        val split = splitBilingualDescription("$lineup\n$german\n[EN]\n$english").shouldNotBeNull()

        split.original shouldBe "$lineup\n$german"
        split.alt shouldBe "$lineup\n$english"
    }

    @Test
    fun `a line that only names a language is a marker, a sentence about one is not`() {
        hasLanguageMarker("[EN]") shouldBe true
        hasLanguageMarker("DEUTSCH:") shouldBe true
        hasLanguageMarker("- - - English version above - - -") shouldBe true
        hasLanguageMarker("AoxoToxoA: Truckin' Cross Germany [English below]") shouldBe true
        hasLanguageMarker("Deutschland Premiere / German Premiere") shouldBe false
        hasLanguageMarker("The panel is held in English.") shouldBe false
        hasLanguageMarker(null) shouldBe false
    }

    @Test
    fun `withBilingualDescriptionSplit fills the alt text and leaves a scraper's own alone`() {
        val event =
            ScrapedEvent(
                title = "Strings Attached",
                description = "DE\n$GERMAN\nEN\n$ENGLISH",
                eventDate = java.time.LocalDate.of(2026, 11, 7),
                sourceUrl = "https://example.org",
                sourceId = "x:1"
            )

        val split = event.withBilingualDescriptionSplit()
        split.description shouldBe GERMAN
        split.descriptionAlt shouldBe ENGLISH
        event.copy(descriptionAlt = "Set by the scraper").withBilingualDescriptionSplit().description shouldBe event.description
    }

    private companion object {
        const val GERMAN =
            "Wenn ein klassischer Geiger plötzlich Nirvana und Britney Spears mit der Virtuosität von Bach und Mozart spielt, " +
                "passiert etwas Spannendes. Das Duo Strings Attached bringt legendäre Rock Hymnen und unvergessliche Pop Hits auf " +
                "Geige und Gitarre auf die Bühne und verschmilzt Nostalgie und Energie zu einem einzigartigen Live-Erlebnis."
        const val ENGLISH =
            "What happens when a classical violinist suddenly plays Nirvana and Britney Spears? The new Berlin-based violin and " +
                "guitar duo Strings Attached answers that question by bringing legendary pop and rock hits to the stage with a classy twist."
        const val RUSSIAN = "Спектакль о памяти, голосе и городе, в котором каждый звук становится частью общей истории."
    }
}
