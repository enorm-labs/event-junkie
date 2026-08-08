package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// Focused tests for EventTypeMapping — one test per behaviour.
class EventTypeMappingTest {
    // --- mapEventType ---

    @Test
    fun `mapEventType maps base German and English labels`() {
        mapEventType("Konzert") shouldBe "CONCERT"
        mapEventType("Concert") shouldBe "CONCERT"
        mapEventType("Festival") shouldBe "FESTIVAL"
        mapEventType("Party") shouldBe "PARTY"
        mapEventType("Sonstiges") shouldBe "OTHER"
    }

    @Test
    fun `mapEventType is case-insensitive and trims whitespace`() {
        mapEventType("KONZERT") shouldBe "CONCERT"
        mapEventType("  Konzert  ") shouldBe "CONCERT"
    }

    @Test
    fun `mapEventType returns null for null, blank and unknown labels`() {
        mapEventType(null).shouldBeNull()
        mapEventType("").shouldBeNull()
        mapEventType("   ").shouldBeNull()
        mapEventType("Workshop").shouldBeNull()
    }

    @Test
    fun `mapEventType prefers venue-specific synonyms over the base table`() {
        mapEventType("Live", mapOf("live" to "CONCERT")) shouldBe "CONCERT"
        // extra synonyms take precedence over the base mapping
        mapEventType("Party", mapOf("party" to "CLUB_NIGHT")) shouldBe "CLUB_NIGHT"
    }

    @Test
    fun `mapEventType maps a public-viewing label to SCREENING`() {
        mapEventType("Public Viewing") shouldBe "SCREENING"
    }

    @Test
    fun `mapEventType maps reading and exhibition labels`() {
        mapEventType("Lesung") shouldBe "READING"
        mapEventType("Ausstellung") shouldBe "EXHIBITION"
        mapEventType("Vernissage") shouldBe "EXHIBITION"
    }

    // --- inferConcertVenueType ---

    @Test
    fun `inferConcertVenueType defaults a plain artist title to CONCERT`() {
        // Band names a concert-venue left uncategorised (Astra Green Lung, So36 NOFX,
        // Lido Pomplamoose/Dea Matrona/South Arcade) — the title names the act.
        inferConcertVenueType("GREEN LUNG") shouldBe "CONCERT"
        inferConcertVenueType("NOFX - 40 YEARS OF FUCKING UP") shouldBe "CONCERT"
        inferConcertVenueType("POMPLAMOOSE") shouldBe "CONCERT"
    }

    @Test
    fun `inferConcertVenueType detects a quiz`() {
        inferConcertVenueType("Quiz Night Show") shouldBe "QUIZ"
    }

    @Test
    fun `inferConcertVenueType detects a wrestling or burlesque show`() {
        inferConcertVenueType("QUEER WRESTLING CIRCUS") shouldBe "SHOW"
        inferConcertVenueType("BERLIN FREAK BURLESQUE CIRCUS") shouldBe "SHOW"
    }

    @Test
    fun `inferConcertVenueType maps football and cinema formats to SCREENING`() {
        inferConcertVenueType("11FREUNDE WM-QUARTIER") shouldBe "SCREENING"
        inferConcertVenueType("Fußball Weltmeisterschaft") shouldBe "SCREENING"
        inferConcertVenueType("World Cup 2026 Live Screening") shouldBe "SCREENING"
        inferConcertVenueType("KENNEN SIE KINO?") shouldBe "SCREENING"
    }

    @Test
    fun `inferConcertVenueType maps readings and poetry slams to READING`() {
        inferConcertVenueType("DAV JURA SLAM") shouldBe "READING"
        inferConcertVenueType("LESEDÜNE") shouldBe "READING"
        inferConcertVenueType("Lesung mit Autor") shouldBe "READING"
    }

    @Test
    fun `inferConcertVenueType keeps a songslam out of READING`() {
        // "slam" is a whole-word reading marker, so a musical "Songslam" is not a reading.
        inferConcertVenueType("Songslam Kreuzberg") shouldBe "CONCERT"
    }

    @Test
    fun `inferConcertVenueType maps art exhibitions to EXHIBITION`() {
        inferConcertVenueType("VERNISSAGE: NEW WORKS") shouldBe "EXHIBITION"
        inferConcertVenueType("Ausstellungseröffnung") shouldBe "EXHIBITION"
    }

    @Test
    fun `inferConcertVenueType maps other non-music formats to OTHER`() {
        inferConcertVenueType("Flohmarkt im Hof") shouldBe "OTHER"
    }

    @Test
    fun `inferConcertVenueType keeps an act whose name merely contains kino as a substring`() {
        // "kino" is only a whole-word cinema marker, so "AlKINOos" stays a CONCERT.
        inferConcertVenueType("ALKINOOS IOANNIDIS") shouldBe "CONCERT"
    }

    // --- isScreeningTitle ---

    @Test
    fun `isScreeningTitle detects football public-viewings and cinema nights`() {
        isScreeningTitle("11FREUNDE WM-QUARTIER") shouldBe true
        isScreeningTitle("World Cup 2026 Live Screening") shouldBe true
        isScreeningTitle("Fußball Weltmeisterschaft") shouldBe true
        isScreeningTitle("KENNEN SIE KINO?") shouldBe true
        isScreeningTitle("SHORTIES FILMS SCREENING #28") shouldBe true
    }

    @Test
    fun `isScreeningTitle keeps a plain act, including a kino substring`() {
        isScreeningTitle("GREEN LUNG") shouldBe false
        isScreeningTitle("ALKINOOS IOANNIDIS") shouldBe false
    }

    @Test
    fun `inferConcertVenueType detects a party or club night`() {
        inferConcertVenueType("THE CURE AFTERSHOW PARTY") shouldBe "PARTY"
        inferConcertVenueType("PARANOID CLUB NIGHT") shouldBe "PARTY"
        inferConcertVenueType("HARD TECHNO RAVE") shouldBe "PARTY"
    }

    @Test
    fun `inferConcertVenueType keeps an act whose name merely ends in Club as a concert`() {
        // The trade made when the bare `club` keyword was dropped, stated as a test so it is a
        // decision rather than a drift. At a dedicated live-music venue a title ending in the word
        // is overwhelmingly a band — and typing it PARTY cost it its lineup as well as its type.
        inferConcertVenueType("Two Door Cinema Club") shouldBe "CONCERT"
        inferConcertVenueType("Teenage Fanclub") shouldBe "CONCERT"
        inferConcertVenueType("Savana Funk - Club Tour 2026") shouldBe "CONCERT"
        // What it costs: a night named for its club, at a venue that supplies no category of its
        // own, is now a CONCERT. No such title exists in the seed — every resident night ending in
        // the word is at a venue that types its events itself — but the shape is real, so it is
        // named here rather than discovered later.
        inferConcertVenueType("P ▲ R ▲ N ● I ► (PARANOID CLUB)") shouldBe "CONCERT"
        // A venue that marks its own concerts is unaffected in the way that matters: an unmarked
        // title falls to OTHER, which mints no artists, rather than to CONCERT.
        inferUnmarkedTitleType("Soda Social Club") shouldBe "OTHER"
    }

    @Test
    fun `inferConcertVenueType keeps an act whose name merely contains rave as a substring`() {
        // "rave" is only a whole-word party marker, so "GRAVE DIGGER" (a metal band) stays a CONCERT.
        inferConcertVenueType("GRAVE DIGGER") shouldBe "CONCERT"
        inferConcertVenueType("The Brave") shouldBe "CONCERT"
    }

    // --- classifyByGenreKeyword ---

    @Test
    fun `classifyByGenreKeyword recovers reading exhibition and screening format cues`() {
        classifyByGenreKeyword("Lesung") shouldBe "READING"
        classifyByGenreKeyword("Immersive Ausstellung") shouldBe "EXHIBITION"
        classifyByGenreKeyword("Public Viewing") shouldBe "SCREENING"
    }

    @Test
    fun `classifyByGenreKeyword ignores genuine music genres`() {
        classifyByGenreKeyword("Spoken Word, Electronica, Jazz, Fusion") shouldBe null
        classifyByGenreKeyword("Indie Rock, Pop") shouldBe null
        // The whole-word guards keep a "slam"/"kino" substring in a genre from matching.
        classifyByGenreKeyword("Songslam Pop") shouldBe null
    }

    // --- refineConcertVenueType ---

    @Test
    fun `refineConcertVenueType defaults an unclassified event to CONCERT`() {
        // No category (null) → the title names the act at a live-music venue.
        refineConcertVenueType(null, "GREEN LUNG") shouldBe "CONCERT"
    }

    @Test
    fun `refineConcertVenueType trusts a specific venue category`() {
        refineConcertVenueType("PARTY", "Some DJ Night") shouldBe "PARTY"
        refineConcertVenueType("FESTIVAL", "Some Weekender") shouldBe "FESTIVAL"
    }

    @Test
    fun `refineConcertVenueType reclassifies a generic OTHER only on a keyword`() {
        // Astra tags its wrestling show with the generic "Other" kind (→ OTHER); a
        // keyword recovers it, but a signal-less catch-all stays OTHER rather than
        // being force-promoted to CONCERT.
        refineConcertVenueType("OTHER", "QUEER WRESTLING CIRCUS") shouldBe "SHOW"
        refineConcertVenueType("OTHER", "11FREUNDE WM-QUARTIER") shouldBe "SCREENING"
        refineConcertVenueType("OTHER", "GWF Summer Smash 2026") shouldBe "OTHER"
    }

    @Test
    fun `inferUnmarkedTitleType types by keyword but never defaults to CONCERT`() {
        // A positive keyword flips the type…
        inferUnmarkedTitleType("Kotti Karaoke Party") shouldBe "PARTY"
        inferUnmarkedTitleType("Monarch Music Quiz") shouldBe "QUIZ"
        // …but an unmarked event with no cue stays OTHER (not force-promoted to CONCERT),
        // so a party's event name is never minted as a headliner.
        inferUnmarkedTitleType("OFF BEAT: SUMMER SESSIONS") shouldBe "OTHER"
        inferUnmarkedTitleType("DAS LUNSENTRIO") shouldBe "OTHER"
    }

    // --- isFestivalTitle ---

    @Test
    fun `isFestivalTitle matches word-anchored festival and festival-ticket markers`() {
        isFestivalTitle("CANARIAS CALLING FESTIVAL") shouldBe true
        isFestivalTitle("TANGO OR NONTANGO FESTIVAL") shouldBe true
        isFestivalTitle("GROSSSTADTWAHNSINN 2026 - FESTIVALTICKET") shouldBe true
    }

    @Test
    fun `isFestivalTitle ignores a bare fest and plain titles`() {
        // Tighter than isNonArtistEvent: a bare "Fest" is too weak a signal to retype an event.
        isFestivalTitle("GROBES FEST 2026") shouldBe false
        isFestivalTitle("Manifest") shouldBe false
        isFestivalTitle("Berliner Weisse") shouldBe false
    }
}
