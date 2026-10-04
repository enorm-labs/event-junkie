package de.norm.events.scraper

import de.norm.events.event.EventType
import de.norm.events.event.SpokenLanguage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Unit tests for [detectSpokenLanguages]. Every string is a venue's own, from a local import. */
class SpokenLanguagesTest {
    private fun spoken(
        title: String,
        description: String? = null,
        subtitle: String? = null,
        type: EventType = EventType.COMEDY
    ) = detectSpokenLanguages(title, subtitle, description, type).spoken

    @ParameterizedTest
    @ValueSource(
        strings = [
            "After Party Comedy: Stand-Up in English Sundays 6pm at The Wall",
            "Laughs, Pizza & Shots – English Comedy Night in the Heart of Berlin!",
            "🇬🇧 DOWNSTAIRS Comedy – English Stand Up Show"
        ]
    )
    fun `a title that says English is an English show`(title: String) {
        spoken(title) shouldBe listOf("en")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Hinweis: Die Show findet auf Englisch statt.",
            "note: The show will be held entirely in English.",
            "Find one of the longest running English speaking comedy clubs in Berlin",
            "Berlin’s longest running English-language longform improv team",
            "Don't miss his Berlin debut. Language: English Age restriction: 16+",
            "Panel language: English"
        ]
    )
    fun `a description that says English is an English show`(description: String) {
        spoken("Showcase", description) shouldBe listOf("en")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Diese Show ist nichts für Zartbesaitete – empfohlen ab 16 Jahren! Sprache: Deutsch",
            "Und jetzt zum ersten Mal in deutscher Sprache.",
            "Laughing Matter – auf Deutsch"
        ]
    )
    fun `a text that says German is a German show`(description: String) {
        spoken("Showcase", description) shouldBe listOf("de")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "IT’S CALLED APPARTHEIT – German & English Stand-up Comedy",
            "Einige Acts spielen auf Deutsch, andere auf Englisch.",
            "a bilingual stand-up comedy mixed show",
            "Warning: Contains sexual themes and nudity In English, German and ‘without words’"
        ]
    )
    fun `a text that names both is a bilingual show`(text: String) {
        spoken("Showcase", text) shouldBe listOf("de", "en")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // A scene, a literature or a region, not what is said on stage.
            "Zwei der angesagtesten Newcomer der deutschsprachigen Comedy-Szene gehen wieder auf Tour.",
            "tourt sie bereits durch den gesamten deutschsprachigen Raum",
            "der als Autor und Bühnenleser die deutschsprachige Literaturlandschaft verändert hat",
            // Site chrome and place names.
            "Leichte Sprache",
            "Englischer Garten",
            "Die Musik erzählt in ihrer ganz eigenen Sprache",
            // A performer's nationality.
            "Der englische Musiker und Comedian Bill Bailey kehrt zurück"
        ]
    )
    fun `a language word that describes something else is no marker`(description: String) {
        spoken("Showcase", description) shouldBe null
    }

    @Test
    fun `a quiz that names its languages carries them`() {
        // Badehaus, every Wednesday (#2563).
        detectSpokenLanguages(
            "Pubquiz with Simply Quiz",
            "40+ challenging questions in English and German",
            null,
            EventType.QUIZ
        ).spoken shouldBe listOf("de", "en")
    }

    // The phrase shapes #2563 names. No production quiz bills its language this way yet.
    @ParameterizedTest
    @ValueSource(strings = ["English Pub Quiz", "English quiz night", "Englischsprachiges Kneipenquiz", "Ein englischsprachiges Quiz"])
    fun `a quiz billed as English is English`(title: String) {
        spoken(title, type = EventType.QUIZ) shouldBe listOf("en")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Quiz auf Deutsch", "Das deutschsprachige Pubquiz"])
    fun `a quiz billed as German is German`(title: String) {
        spoken(title, type = EventType.QUIZ) shouldBe listOf("de")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // Production quiz rows that state no language: the text's own language is no marker.
            "Clever & Curious Charlottenburg!\ndas unterhaltsame Quiz mit Eavo im Art Stalker - Kneipenquiz",
            "Music Quiz\nThe music quiz is an old favorite at Madame Claude.",
            "Quiz Night Show\nPubquiz",
            "11FREUNDE KNEIPENQUIZ",
            // A quiz about England, not in English.
            "Quiz rund um die englische Küche"
        ]
    )
    fun `a quiz that states no language has none`(title: String) {
        spoken(title, type = EventType.QUIZ) shouldBe null
    }

    @Test
    fun `the title wins over a description that quotes another show`() {
        spoken("Laughing Matter – auf Deutsch", "The English edition runs on Sundays, in English.") shouldBe listOf("de")
    }

    @Test
    fun `a concert is never tagged, whatever its text says`() {
        spoken("Songs in English", type = EventType.CONCERT) shouldBe null
        spoken("Rave in English", type = EventType.PARTY) shouldBe null
    }

    @Test
    fun `OmU is German subtitles, and the subtitles do not make the show English`() {
        val omu = detectSpokenLanguages("Paris, Texas (OmU)", null, null, EventType.SCREENING)
        omu.subtitle shouldBe "de"
        omu.spoken shouldBe null

        val subtitled = detectSpokenLanguages("Der Himmel über Berlin", null, "with English subtitles", EventType.SCREENING)
        subtitled.subtitle shouldBe "en"
        subtitled.spoken shouldBe null

        detectSpokenLanguages("Fargo (OmeU)", null, null, EventType.SCREENING).subtitle shouldBe "en"
    }

    @Test
    fun `the cinema abbreviation is case-sensitive`() {
        detectSpokenLanguages("Omu Spring Tour", null, null, EventType.SCREENING).subtitle shouldBe null
    }

    @Test
    fun `the house language fills a show whose text names none`() {
        detectSpokenLanguages("Mixed Show", null, "Five comics, one night.", EventType.COMEDY, SpokenLanguage.ENGLISH).spoken shouldBe listOf("en")
    }

    @Test
    fun `an explicit phrase beats the house language`() {
        detectSpokenLanguages("Laughing Matter – auf Deutsch", null, null, EventType.COMEDY, SpokenLanguage.ENGLISH).spoken shouldBe listOf("de")
        detectSpokenLanguages("Showcase", null, "Language: German", EventType.COMEDY, SpokenLanguage.ENGLISH).spoken shouldBe listOf("de")
    }

    @Test
    fun `a concert at a house with a language stays empty`() {
        detectSpokenLanguages("Songs for a Sunday", null, null, EventType.CONCERT, SpokenLanguage.ENGLISH).spoken shouldBe null
    }
}
