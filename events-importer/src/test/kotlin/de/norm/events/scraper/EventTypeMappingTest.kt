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
        mapEventType("Party", mapOf("party" to "SHOW")) shouldBe "SHOW"
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

    @Test
    fun `mapEventType maps comedy, stand-up and Kabarett labels to COMEDY`() {
        mapEventType("Comedy") shouldBe "COMEDY"
        mapEventType("Stand-up") shouldBe "COMEDY"
        mapEventType("Standup") shouldBe "COMEDY"
        mapEventType("Kabarett") shouldBe "COMEDY"
        mapEventType("Musik-Kabarett") shouldBe "COMEDY"
    }

    @Test
    fun `a stand-up or comedy-night phrase in a title is COMEDY, a bare comedy is not`() {
        inferConcertVenueType("Stand-Up Night mit Freunden") shouldBe "COMEDY"
        inferConcertVenueType("KAMISI - Die 80er Jahre Comedy Show") shouldBe "COMEDY"
        inferConcertVenueType("English Comedy Night") shouldBe "COMEDY"
        inferConcertVenueType("The Divine Comedy") shouldBe "CONCERT"
        inferConcertVenueType("Stand Up - Tribute Night") shouldBe "CONCERT"
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
    fun `inferConcertVenueType detects an ice show`() {
        inferConcertVenueType("Holiday on Ice") shouldBe "SHOW"
        inferConcertVenueType("Disney On Ice präsentiert Mickys Magische Momente") shouldBe "SHOW"
        inferConcertVenueType("Die große Eisrevue") shouldBe "SHOW"
        inferConcertVenueType("Weihnachts-Eisshow 2026") shouldBe "SHOW"
    }

    @Test
    fun `inferConcertVenueType keeps a concert whose title only holds the letters of an ice cue`() {
        inferConcertVenueType("Lemon Ice") shouldBe "CONCERT"
        inferConcertVenueType("Vanilla Ice") shouldBe "CONCERT"
        inferConcertVenueType("Die Preisshow der Liedermacher") shouldBe "CONCERT"
    }

    @Test
    fun `isIceShow reads auf Eis as a phrase in the title or the subtitle`() {
        isIceShow("Eiskönigin 1 & 2", subtitle = "Musik-Show auf Eis") shouldBe true
        isIceShow("Märchen auf Eis") shouldBe true
        isIceShow("Disney on Ice", subtitle = null) shouldBe true
        inferConcertVenueType("Die Schöne und das Biest AUF EIS") shouldBe "SHOW"
    }

    @Test
    fun `isIceShow leaves Eis inside another word untouched`() {
        isIceShow("Eiskönigin 1 & 2") shouldBe false
        isIceShow("Eisbrecher", subtitle = "Kaiserwerk Tour 2026") shouldBe false
        isIceShow("Lauf Eis", subtitle = null) shouldBe false
        isIceShow("Die Fahrt auf Eisenbahnschienen") shouldBe false
        isIceShow("Konzert", subtitle = "Lieder auf Eisgrund") shouldBe false
        isIceShow("Tanz auf Eiszapfen", subtitle = "Ein Abend mit Heiß und Eis") shouldBe false
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
        isScreeningTitle("EM Italien - Albanien") shouldBe true
        isScreeningTitle("Bundesliga live im Biergarten") shouldBe true
        isScreeningTitle("Übertragung: Deutschland vs Spanien") shouldBe true
        isScreeningTitle("KENNEN SIE KINO?") shouldBe true
        isScreeningTitle("SHORTIES FILMS SCREENING #28") shouldBe true
        // A German compound ends in the cinema word (#310).
        isScreeningTitle("Nomadenkino") shouldBe true
        isScreeningTitle("Freiluftkino Spezial") shouldBe true
    }

    @Test
    fun `isScreeningTitle keeps a plain act, including a kino substring`() {
        isScreeningTitle("GREEN LUNG") shouldBe false
        isScreeningTitle("ALKINOOS IOANNIDIS") shouldBe false
        // A sport word alone is a talk, not a viewing (#311).
        isScreeningTitle("Der Fussball mein Leben & Ich") shouldBe false
        isScreeningTitle("Football Nights") shouldBe false
    }

    @Test
    fun `inferConcertVenueType detects a party or club night`() {
        inferConcertVenueType("THE CURE AFTERSHOW PARTY") shouldBe "PARTY"
        inferConcertVenueType("PARANOID CLUB NIGHT") shouldBe "PARTY"
        inferConcertVenueType("HARD TECHNO RAVE") shouldBe "PARTY"
    }

    @Test
    fun `inferConcertVenueType keeps a band whose name holds rave or night as a concert`() {
        val venueKeywords = mapOf("night" to "PARTY")
        inferConcertVenueType("GRAVE DIGGER", null, venueKeywords) shouldBe "CONCERT"
        inferConcertVenueType("Luca Ravenna", null, venueKeywords) shouldBe "CONCERT"
        inferConcertVenueType("Nightwish", null, venueKeywords) shouldBe "CONCERT"
        inferConcertVenueType("SKELER - Nightfall World Tour", null, venueKeywords) shouldBe "CONCERT"
    }

    @Test
    fun `inferConcertVenueType types a venue keyword as a whole word`() {
        val venueKeywords = mapOf("night" to "PARTY", "90s" to "PARTY", "world cup" to "SCREENING")
        inferConcertVenueType("Golden Era Dancehall Night", null, venueKeywords) shouldBe "PARTY"
        // A keyword starting with a digit may touch a word on that side.
        inferConcertVenueType("TOP90s * Bad Taste Special *", null, venueKeywords) shouldBe "PARTY"
        inferConcertVenueType("World Cup Final", null, venueKeywords) shouldBe "SCREENING"
        // The shared cues come first.
        inferConcertVenueType("Quiz Night", null, venueKeywords) shouldBe "QUIZ"
    }

    @Test
    fun `inferConcertVenueType reads only a party or quiz cue from the subtitle`() {
        inferConcertVenueType("Erobique", "NEUJAHRS PARTY") shouldBe "PARTY"
        inferConcertVenueType("Some Night", "DJ Set till late", DJ_SET_PARTY_KEYWORDS) shouldBe "PARTY"
        inferConcertVenueType("KENNEN SIE KINO", "Das 1. Berliner Filmtablequiz") shouldBe "SCREENING"
        // A show or market word in the subtitle describes a real act's evening.
        inferConcertVenueType("¡Wepa! Bunny", "Open Air", mapOf("open air" to "OTHER")) shouldBe "CONCERT"
        inferConcertVenueType("ANDREAS KÜMMERT", "with circus artists") shouldBe "CONCERT"
    }

    @Test
    fun `inferConcertVenueType ignores a tour name or an after-show in the subtitle`() {
        inferConcertVenueType("Kytes", "Indie Rave Tour 2026") shouldBe "CONCERT"
        inferConcertVenueType("GOLDIE BOUTILIER", "Party Tour ‘26") shouldBe "CONCERT"
        inferConcertVenueType("DIE VERLIERER + SCHIMMEL ÜBER BERLIN", "+ Aftershow: FISH'N'CANDY") shouldBe "CONCERT"
        inferConcertVenueType("The Valkyrians", "Ska Matinee. After-Party with DJ Selekta Bebek") shouldBe "CONCERT"
    }

    @Test
    fun `inferConcertVenueType reads a subtitle only up to its after-show`() {
        // Festsaal's party cues; its keyword map is private to the scraper.
        val festsaal = DJ_SET_PARTY_KEYWORDS + mapOf("festa" to "PARTY", "legt auf" to "PARTY", "legen auf" to "PARTY")
        inferConcertVenueType("Elena Rose", "+ Aftershow: Arnim legt auf", festsaal) shouldBe "CONCERT"
        inferConcertVenueType("Elena Rose", "+ Aftershow: Party mit DJ Set", festsaal) shouldBe "CONCERT"
        // Production subtitles from four venues, 2026-10-09: each bills a concert.
        listOf(
            "+ Aftershow: FISH'N'CANDY",
            "Ska & Rocksteady Matinee ab 17 Uhr Warm Up mit DJ Selekta Bebek. 18 Uhr Buster Beat, 19 Uhr The Valkyrians. " +
                "Aftershow w DJ Selekta Bebek",
            "+ aftershow bar-dj-set w/ psychbergs very own Dark´s Second!",
            "& Aftershow Party \"Rebel Yell Love the 80s -auf 3 Floors- \""
        ).forEach { subtitle ->
            inferConcertVenueType("Some Band", subtitle) shouldBe "CONCERT"
            inferConcertVenueType("Some Band", subtitle, festsaal) shouldBe "CONCERT"
        }
        // A party cue before the after-show still types the night.
        inferConcertVenueType("Flugmodus", "Arnim legt auf + Aftershow", festsaal) shouldBe "PARTY"
        inferConcertVenueType("Some Night", "DJ Set", DJ_SET_PARTY_KEYWORDS) shouldBe "PARTY"
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

    // --- inferVenueFormatType ---

    // The two houses' own format vocabularies, copied so this test does not reach into a scraper.
    private val colosseumFormats =
        linkedMapOf(
            "film:" to "SCREENING",
            "kinoevent" to "SCREENING",
            "buchpremiere" to "READING",
            "podcast" to "SHOW"
        )

    private val peterEdelFormats = linkedMapOf("talkshow" to "SHOW", "stummfilm" to "SCREENING", "tanztee" to "PARTY")

    @Test
    fun `inferVenueFormatType prefers a house format over the shared cues`() {
        // Peter Edel's "Tanztee" is a party the shared classifier has no cue for.
        inferVenueFormatType("Tanztee im PETER EDEL", null, peterEdelFormats) shouldBe "PARTY"
        inferVenueFormatType("Stummfilm mit Live-Musik", null, peterEdelFormats) shouldBe "SCREENING"
    }

    @Test
    fun `inferVenueFormatType reads the subtitle as part of the haystack`() {
        inferVenueFormatType("Irvine Welsh", "Buchpremiere", colosseumFormats) shouldBe "READING"
    }

    @Test
    fun `inferVenueFormatType keeps the first matching entry when two keywords hit`() {
        // A film night presented by a podcast is a screening: the earlier entry wins.
        inferVenueFormatType("Kinoevents 2026, presented by a Podcast", null, colosseumFormats) shouldBe "SCREENING"
    }

    @Test
    fun `inferVenueFormatType matches a keyword regardless of case`() {
        inferVenueFormatType("TALKSHOW MIT GÄSTEN", null, peterEdelFormats) shouldBe "SHOW"
    }

    @Test
    fun `inferVenueFormatType falls through to the shared cues and then OTHER`() {
        inferVenueFormatType("Kotti Karaoke Party", null, peterEdelFormats) shouldBe "PARTY"
        // No house format and no shared cue stays OTHER, never CONCERT.
        inferVenueFormatType("Das Betreute Singen September", null, colosseumFormats) shouldBe "OTHER"
        inferVenueFormatType("Das Betreute Singen September", null, emptyMap()) shouldBe "OTHER"
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
