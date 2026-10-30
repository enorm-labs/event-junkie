package de.norm.events.scraper

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// Focused tests for ArtistNameCleanup — one test per behaviour.
class ArtistNameCleanupTest {
    // --- stripArtistSuffix ---

    @Test
    fun `stripArtistSuffix drops a soft hyphen from any act`() {
        stripArtistSuffix("Jean-\u00ADLuc Guionnet") shouldBe "Jean-Luc Guionnet"
        stripArtistSuffix("Kraft\u00ADwerk live", fromTitle = true) shouldBe "Kraftwerk"
    }

    @Test
    fun `stripArtistSuffix takes a title's closing mark off a title-derived act`() {
        stripArtistSuffix("MC UNCUT GEM!", fromTitle = true) shouldBe "MC UNCUT GEM"
        stripArtistSuffix("ARTig?", fromTitle = true) shouldBe "ARTig"
        stripArtistSuffix("Sinkane live!", fromTitle = true) shouldBe "Sinkane"
        stripArtistSuffix("Lacrimosa mit Orchester - Einzige Orchester-Show in Europa!", fromTitle = true) shouldBe "Lacrimosa"
        // A line-up names the act itself, so its mark stays.
        stripArtistSuffix("DALOY!") shouldBe "DALOY!"
        stripArtistSuffix("Agent!") shouldBe "Agent!"
    }

    @Test
    fun `stripArtistSuffix keeps an act whose own name ends in a mark`() {
        // CC:DISCO! is a MusicBrainz EXACT row on production.
        stripArtistSuffix("CC:DISCO!", fromTitle = true) shouldBe "CC:DISCO!"
        stripArtistSuffix("Wham!", fromTitle = true) shouldBe "Wham!"
        stripArtistSuffix("Ludique!", fromTitle = true) shouldBe "Ludique!"
        stripArtistSuffix("!!!", fromTitle = true) shouldBe "!!!"
        stripArtistSuffix("¡Ya voy!", fromTitle = true) shouldBe "¡Ya voy!"
        stripArtistSuffix("Yeah! Yeah! Yeah !", fromTitle = true) shouldBe "Yeah! Yeah! Yeah !"
    }

    @Test
    fun `stripArtistSuffix drops a footnote star`() {
        // Production stored all of these as written (#1845).
        stripArtistSuffix("Sinkane live*") shouldBe "Sinkane"
        stripArtistSuffix("Sweely live*") shouldBe "Sweely"
        stripArtistSuffix("Jonny*") shouldBe "Jonny"
        // A star inside the name stays.
        stripArtistSuffix("*n8") shouldBe "*n8"
        stripArtistSuffix("Sean Steinfeger (OHSHITF*CKYES") shouldBe "Sean Steinfeger"
        // A `(` right after a `)` belongs to a stylised name (#3748).
        stripArtistSuffix("Vibrant as Fu)(K") shouldBe "Vibrant as Fu)(K"
    }

    @Test
    fun `stripArtistSuffix drops show, tour and anniversary words a title glues on without a dash`() {
        // Production titles that minted these as acts (#1841).
        stripArtistSuffix("SIDOS WEIHNACHTSSHOW 2026") shouldBe "SIDO"
        stripArtistSuffix("Mutabor 35 Jahre Jubiläum") shouldBe "Mutabor"
        stripArtistSuffix("COOGANS BLUFF 10 Years Of Flying To The Stars") shouldBe "COOGANS BLUFF"
        stripArtistSuffix("Bernhard Brink - \"Danke für die Zeit\" Die Abschiedstour") shouldBe "Bernhard Brink"
        stripArtistSuffix("Reg Meuross – Sonic Morgue – Zusatzshow") shouldBe "Reg Meuross – Sonic Morgue"
        stripArtistSuffix("Jan Becker „HYPNOTIZE THE WORLD“") shouldBe "Jan Becker"
        stripArtistSuffix("Zascha HOT MESS Debut at Lark") shouldBe "Zascha"
        stripArtistSuffix("WISBORG Phantomschmerz Tour") shouldBe "WISBORG"
        stripArtistSuffix("YASUHARU TAKANASHI TOUR") shouldBe "YASUHARU TAKANASHI"
        // A denylisted package tour stays whole, so the denylist still drops it.
        stripArtistSuffix("THE REVIVAL TOUR") shouldBe "THE REVIVAL TOUR"
        stripArtistSuffix("Tour") shouldBe "Tour"
        stripArtistSuffix("schluma Heulen am Wasser 2026") shouldBe "schluma"
        stripArtistSuffix("Exofa - Altern.RockPop") shouldBe "Exofa"
        // Real names in the same shapes stay.
        stripArtistSuffix("10 Years") shouldBe "10 Years"
        stripArtistSuffix("Ship Happens Aftershow") shouldBe "Ship Happens Aftershow"
        stripArtistSuffix("Voodoo Jürgens und die \"Ansa Panier\"") shouldBe "Voodoo Jürgens und die \"Ansa Panier\""
        stripArtistSuffix("The Rolling Stones Tour") shouldBe "The Rolling Stones Tour"
        stripArtistSuffix("Class of 1984") shouldBe "Class of 1984"
        stripArtistSuffix("Kwam.E") shouldBe "Kwam.E"
    }

    @Test
    fun `lineup notes are no acts`() {
        // Production stored each of these as a DJ or support act (#1843).
        stripArtistSuffix("lisa tba") shouldBe "lisa"
        isNonArtistName("Lineup Tba") shouldBe true
        isNonArtistName(stripArtistSuffix("Line Up tba.")) shouldBe true
        isNonArtistName("All night long") shouldBe true
        isNonArtistName("Secret Guest") shouldBe true
        isNonArtistName("Surprise Guest") shouldBe true
        isNonArtistName("Eine Veranstaltung im Rahmen vom Tag Der Clubkultur") shouldBe true
        isNonArtistName("Eine Veranstaltung des Landesdenkmalamts Berlin und der Architektenkammer Berlin") shouldBe true
        stripArtistPrefix("Live: Mnglxmplr") shouldBe "Mnglxmplr"
        // Names in the same shapes stay.
        isNonArtistName("Special Guest Foo") shouldBe false
        isNonArtistName("Lisa") shouldBe false
        stripArtistSuffix("Tabata") shouldBe "Tabata"
        // `more tba` stays whole, so the placeholder check still sees it; `More` alone is a band.
        stripArtistSuffix("more tba") shouldBe "more tba"
        stripArtistPrefix("Live Electronic Showcase") shouldBe "Live Electronic Showcase"
    }

    @Test
    fun `stripArtistSuffix recovers the act from tour and live suffixes`() {
        stripArtistSuffix("DOMINIUM - NIGHT IS CALLING TOUR 2026") shouldBe "DOMINIUM"
        stripArtistSuffix("AZ LIVE IN BERLIN") shouldBe "AZ"
        stripArtistSuffix("HGICH.T LIVE") shouldBe "HGICH.T"
    }

    @Test
    fun `stripArtistSuffix recovers the act from an anniversary suffix`() {
        stripArtistSuffix("THE BUTLERS - 40 YEARS, SKA & SOULPOWER -") shouldBe "THE BUTLERS"
        stripArtistSuffix("SELIG - 30 JAHRE") shouldBe "SELIG"
    }

    @Test
    fun `stripArtistSuffix recovers the act from a hyphenated tour tail ending in a year`() {
        // Tour labels that name a route or season instead of saying "Tour".
        stripArtistSuffix("Jawdropped - USA UK EU FALL 2026") shouldBe "Jawdropped"
        stripArtistSuffix("Some Band - European Winter 1999") shouldBe "Some Band"
    }

    @Test
    fun `stripArtistSuffix keeps a stylised number that is part of the name`() {
        // Only a four-digit 19xx/20xx year at the very end opens the tail.
        stripArtistSuffix("Blink - 182") shouldBe "Blink - 182"
        stripArtistSuffix("Front 242") shouldBe "Front 242"
        stripArtistSuffix("Sum 41 - Berlin 2026 Show") shouldBe "Sum 41 - Berlin 2026 Show"
    }

    @Test
    fun `stripArtistSuffix recovers the act from a shouted tour or album tail`() {
        stripArtistSuffix("Tigercub - NETS TO CATCH THE WIND") shouldBe "Tigercub"
        stripArtistSuffix("The Notwist - VERTIGO DAYS TOUR") shouldBe "The Notwist"
    }

    @Test
    fun `stripArtistSuffix strips a tribute descriptor after any dash and in any case`() {
        stripArtistSuffix("Always Amy - A tribute to Amy Winehouse") shouldBe "Always Amy"
        stripArtistSuffix("Always Amy — Tribute to Amy Winehouse") shouldBe "Always Amy"
        stripArtistSuffix("THREE SIDES – A TRIBUTE TO GENESIS") shouldBe "THREE SIDES"
        stripArtistSuffix("The AC/DCs - Tributeband") shouldBe "The AC/DCs"
        stripArtistSuffix("The AC/DCs — TRIBUTE BAND") shouldBe "The AC/DCs"
        stripArtistSuffix("MY'TALLICA TRIBUTE BAND") shouldBe "MY'TALLICA"
        stripArtistSuffix("Always Amy – A tribute to Amy Winehouse, Back to Black and the Frank years 2003–2011 – Live in Berlin") shouldBe
            "Always Amy"
    }

    @Test
    fun `stripArtistSuffix keeps a tribute act's own name`() {
        listOf(
            "Tribute to Nothing",
            "Nirvana Tribute",
            "Luke Combs UK Tribute",
            "MORGAN WALLEN TRIBUTE UK",
            "Chris Stapleton Roadshow Tribute",
            "A Tribute to Lemmy",
            "Tribute Band",
            "Rolling Stones Tribute Band \"Dirty Work\"",
            // A dash with no space on one side is a name, as with every other dash tail.
            "Tribute-Band",
            "Mojo-Tribute Band"
        ).forEach { stripArtistSuffix(it) shouldBe it }
        // Already the act before the dash, through an older rule.
        headlinersFromTitle("Heroes - David Bowie Tribute").map { it.name } shouldBe listOf("Heroes")
    }

    @Test
    fun `stripArtistSuffix keeps a hyphenated name the shouted-tail rule must not cut`() {
        // Tail carries lowercase — it is a name, not a shouted tour title.
        stripArtistSuffix("BAD COMPANY LEGACY - Dave Colwell") shouldBe "BAD COMPANY LEGACY - Dave Colwell"
        stripArtistSuffix("Sinem - Hatun") shouldBe "Sinem - Hatun"
        // Head is all-caps, so a name the venue wrote with a dash (Urban Spree's spelling of DZ
        // Deathrays) is never cut down to its first token.
        stripArtistSuffix("DZ - DEATHRAY") shouldBe "DZ - DEATHRAY"
        // A one-word shouted tail could be an alias or initialism, so it is left alone.
        stripArtistSuffix("Someone - ALIEN") shouldBe "Someone - ALIEN"
    }

    @Test
    fun `stripArtistSuffix recovers the act from a hyphenated Releaseshow tail`() {
        stripArtistSuffix("Sinem - Hatun - Releaseshow") shouldBe "Sinem - Hatun"
        stripArtistSuffix("Some Band - Release Show") shouldBe "Some Band"
        // A whole name is left alone — "Releaseshow" could be the act.
        stripArtistSuffix("Releaseshow") shouldBe "Releaseshow"
    }

    @Test
    fun `stripArtistSuffix recovers the act from a bare Releaseshow tail`() {
        // Urban Spree, production 2026-11-13 (#3161).
        stripArtistSuffix("FRIBERTY Releaseshow") shouldBe "FRIBERTY"
        stripArtistSuffix("Some Band Release Show") shouldBe "Some Band"
        stripArtistSuffix("Some Band Release Party") shouldBe "Some Band"
    }

    @Test
    fun `stripArtistSuffix recovers the act from an in Berlin tail`() {
        // Columbia Theater, production 2026-11-17 (#3161).
        stripArtistSuffix("Evgeny Khavtan in Berlin") shouldBe "Evgeny Khavtan"
        headlinersFromTitle("Evgeny Khavtan in Berlin").map { it.name } shouldBe listOf("Evgeny Khavtan")
        // No "in", or a show named "Made in Berlin": the name stays whole.
        stripArtistSuffix("Isolation Berlin") shouldBe "Isolation Berlin"
        stripArtistSuffix("Berlin Berlin") shouldBe "Berlin Berlin"
        stripArtistSuffix("Samba Made in Berlin") shouldBe "Samba Made in Berlin"
        stripArtistSuffix("in Berlin") shouldBe "in Berlin"
    }

    @Test
    fun `stripArtistSuffix recovers the act from a set-count note`() {
        stripArtistSuffix("Toshìn & The Teleporters - 2 Sets!") shouldBe "Toshìn & The Teleporters"
        stripArtistSuffix("Some Band - 3 Sets") shouldBe "Some Band"
        // Requires the " - <n> Set(s)" shape, so an undecorated hyphenated name is left intact.
        stripArtistSuffix("BAD COMPANY LEGACY - Dave Colwell") shouldBe "BAD COMPANY LEGACY - Dave Colwell"
    }

    @Test
    fun `stripArtistSuffix strips a parenthesized performance-format annotation`() {
        stripArtistSuffix("Avangelic (DJ-Set)") shouldBe "Avangelic"
        stripArtistSuffix("Someone (DJ Set)") shouldBe "Someone"
        stripArtistSuffix("Band (Acoustic)") shouldBe "Band"
    }

    @Test
    fun `stripArtistSuffix strips a bare non-parenthesized DJ-Set tail`() {
        stripArtistSuffix("Acid Arab DJ-Set") shouldBe "Acid Arab"
        stripArtistSuffix("Paty Vapor DJ Set") shouldBe "Paty Vapor"
        stripArtistSuffix("LA RONY acoustic set") shouldBe "LA RONY"
        stripArtistSuffix("Some Band Akustik-Set") shouldBe "Some Band"
        // An acoustic act's own name keeps its word.
        stripArtistSuffix("Current Swell Acoustic Trio") shouldBe "Current Swell Acoustic Trio"
        // A bare "DJ-Set" with no preceding name is left for the non-artist filter to drop.
        stripArtistSuffix("DJ-Set") shouldBe "DJ-Set"
        // "DJ <handle>" acts are not a DJ-Set tail and stay intact.
        stripArtistSuffix("DJ Koze") shouldBe "DJ Koze"
    }

    @Test
    fun `stripArtistSuffix strips a German Nachholtermin rescheduled-date tail`() {
        // With a leading dash directly on the marker (Frannz), with a space-dash (Astra) …
        stripArtistSuffix("The Dear Hunter -Nachholtermin vom 30.09.2025.") shouldBe "The Dear Hunter"
        stripArtistSuffix("Pohlmann -Nachholtermin vom 10.01.-") shouldBe "Pohlmann"
        // … and with no dash at all (Astra).
        stripArtistSuffix("Iggi Kelly Nachholtermin vom 28.04.26-") shouldBe "Iggi Kelly"
    }

    @Test
    fun `stripArtistSuffix strips a German Hochverlegung relocation tail`() {
        // en-dash (Frannz) — the parenthetical alias before the note is preserved.
        stripArtistSuffix("OCT (On Company Time) – Hochverlegung") shouldBe "OCT (On Company Time)"
        stripArtistSuffix("Some Act Hochverlegung") shouldBe "Some Act"
    }

    @Test
    fun `stripArtistSuffix strips a singt or sings tribute framing and a release promo tag`() {
        stripArtistSuffix("Tex singt Leoanard Cohen") shouldBe "Tex"
        stripArtistSuffix("Max Raabe singt Weihnachtslieder") shouldBe "Max Raabe"
        stripArtistSuffix("Thomas Anders sings MODERN TALKING") shouldBe "Thomas Anders"
        stripArtistSuffix("Hawt Coco Album Release") shouldBe "Hawt Coco"
        stripArtistSuffix("Some Band EP Release Show") shouldBe "Some Band"
        // Schokoladen, production 2026-12-02.
        stripArtistSuffix("Saba Lou Rec Release") shouldBe "Saba Lou"
        stripArtistSuffix("Some Band Release Party") shouldBe "Some Band"
    }

    @Test
    fun `stripArtistSuffix leaves plain names, a bare Live band and a parenthesized alias untouched`() {
        stripArtistSuffix("The Adicts") shouldBe "The Adicts"
        stripArtistSuffix("Live") shouldBe "Live"
        // No tour/anniversary marker in the hyphenated tail, so it is not a suffix.
        stripArtistSuffix("BAD COMPANY LEGACY - Dave Colwell") shouldBe "BAD COMPANY LEGACY - Dave Colwell"
        // The parenthetical is an alias, not a format word, so it is kept.
        stripArtistSuffix("Sickboyrari (Black Kray)") shouldBe "Sickboyrari (Black Kray)"
        // "Release" without a format word / Party·Show tail is a plausible band name — kept.
        stripArtistSuffix("Release") shouldBe "Release"
    }

    @Test
    fun `stripArtistSuffix strips an unnamed backing band after the act`() {
        // Each spelling production stores (#3325).
        assertSoftly {
            mapOf(
                "Matze Rossi & Band" to "Matze Rossi",
                "KLAUS IGNATZEK und BAND" to "KLAUS IGNATZEK",
                "CLEO UND BAND" to "CLEO",
                "Keith Tynes And Band" to "Keith Tynes",
                "JACK KAYS AND HIS BAND" to "JACK KAYS",
                "Hieke Hoffmann Solo & Band" to "Hieke Hoffmann",
                "Max Mustermann und seine Band" to "Max Mustermann",
                "Joe Jackson & Band - Hope and Fury Tour 2026" to "Joe Jackson",
                // The same act as the bare `Wenzel` on production, though MusicBrainz lists both.
                "Wenzel & Band" to "Wenzel",
                "Wenzel und Band" to "Wenzel"
            ).forEach { (name, act) -> stripArtistSuffix(name) shouldBe act }
        }
    }

    @Test
    fun `stripArtistSuffix keeps a band name and a guest list`() {
        assertSoftly {
            listOf(
                // A named backing band, or one that is not a band (#3325).
                "Brian Fallon & The Painkillers",
                "Tom Gaebel & His Orchestra",
                "Charlotte Wessels & The Obsession",
                "Ann & the Band",
                "Dennis & Jesko Band",
                // `& Friends` names guests who play (#2888).
                "The Hamburg Blues Band & Friends",
                "Taylor & Friends",
                // `Band` in the name itself, with no conjunction in front.
                "The Band",
                "Big Band",
                "Bigband",
                "SWR Big Band",
                "Band",
                "& Band"
            ).forEach { stripArtistSuffix(it) shouldBe it }
        }
    }

    @Test
    fun `a straight quote inside the name, or one that does not close the title, stays`() {
        assertSoftly {
            stripArtistSuffix("Michael \"Mike\" Smith") shouldBe "Michael \"Mike\" Smith"
            headlinersFromTitle("Michael \"Mike\" Smith").map { it.name } shouldBe listOf("Michael \"Mike\" Smith")
            headlinersFromTitle("SPIELHAGEN - \"HARMONY + ECSTASY\" + GUESTS").map { it.name } shouldContain "SPIELHAGEN - \"HARMONY"
            headlinersFromTitle("SPIELHAGEN - \"HARMONY + ECSTASY").map { it.name } shouldContain "SPIELHAGEN - \"HARMONY"
            // One quoted word can finish the act's own name.
            stripArtistSuffix("DZ - \"DEATHRAY\"") shouldBe "DZ - \"DEATHRAY\""
        }
    }

    // --- dash variants on the act/tour boundary ---

    @Test
    fun `stripArtistSuffix cuts a tour tail on an en or em dash, not only a hyphen`() {
        // LARK writes its tour tails with an en dash, so the ASCII-only boundary left the whole
        // tail on the act — the workaround that used to live in LarkApiScraper.
        stripArtistSuffix("Greg Mendez – BEAUTY LAND TOUR") shouldBe "Greg Mendez"
        stripArtistSuffix("Hello Hannes – Sober doesnt save me tour Berlin") shouldBe "Hello Hannes"
        stripArtistSuffix("Lucas Lauriente – Stand Up 2026") shouldBe "Lucas Lauriente"
        stripArtistSuffix("TURBOPAOLO — IL POLIZIOTTO DEL FORMAGGIO 2026") shouldBe "TURBOPAOLO"
        // The hyphen spelling keeps working.
        stripArtistSuffix("DOMINIUM - NIGHT IS CALLING TOUR 2026") shouldBe "DOMINIUM"
    }

    @Test
    fun `stripArtistSuffix keeps a dashed name whose tail is not a tour`() {
        // The shouted-tail guards apply to every dash equally: a mixed-case tail is a name.
        stripArtistSuffix("BAD COMPANY LEGACY – Dave Colwell") shouldBe "BAD COMPANY LEGACY – Dave Colwell"
        stripArtistSuffix("Sinem – Hatun") shouldBe "Sinem – Hatun"
        // An all-caps head means the dash is a co-bill, not an act/tour boundary.
        stripArtistSuffix("DZ – DEATHRAY") shouldBe "DZ – DEATHRAY"
    }

    // #1585 — a work title glued to the act with `:` or ` - ` is not part of the name.
    @Test
    fun `stripArtistSuffix drops a work title after a dash or a colon, and a separator left behind`() {
        stripArtistSuffix("Transllusion - The Opening of the Cerebral Gate") shouldBe "Transllusion"
        stripArtistSuffix("Jon Rose: Hinterland!") shouldBe "Jon Rose"
        stripArtistSuffix("Stevie Cox -") shouldBe "Stevie Cox"
        // Two words after the dash are not enough to call, and an all-caps head is left to the shouted-tail rule.
        stripArtistSuffix("BAD COMPANY LEGACY - Dave Colwell") shouldBe "BAD COMPANY LEGACY - Dave Colwell"
        stripArtistSuffix("DZ - DEATHRAY") shouldBe "DZ - DEATHRAY"
        stripArtistSuffix("Kat Frankie - B O D I E S") shouldBe "Kat Frankie"
        stripArtistSuffix("Drone Art Show: Harry Potter") shouldBe "Drone Art Show: Harry Potter"
        // A presenter is not an act, and `9:3` has no boundary.
        stripArtistSuffix("Analogue Foundation presents: David August With A Band") shouldBe "Analogue Foundation presents: David August With A Band"
        stripArtistSuffix("Bleech 9:3") shouldBe "Bleech 9:3"
    }

    @Test
    fun `stripArtistSuffix reads a quoted tail in any of the three quote styles as a work`() {
        stripArtistSuffix("Nobel-Popel: \"Zebrastreifen\"") shouldBe "Nobel-Popel"
        stripArtistSuffix("Die Band - „Hurra“") shouldBe "Die Band"
        stripArtistSuffix("Die Band: “Hurra”") shouldBe "Die Band"
        // Only a whole tail in quotes counts; a quoted word inside a longer tail is the two-word case #1585 keeps.
        stripArtistSuffix("Die Band: “Hurra” Tour") shouldBe "Die Band: “Hurra” Tour"
        // An all-caps head before a dash is still left alone.
        stripArtistSuffix("DZ - \"DEATHRAY\"") shouldBe "DZ - \"DEATHRAY\""
    }

    @Test
    fun `a place without a dash, and a plays with no capital after it, stay in the name`() {
        stripArtistSuffix("Isolation Berlin") shouldBe "Isolation Berlin"
        stripArtistSuffix("Dead Phoenix - Berlin Calling") shouldBe "Dead Phoenix - Berlin Calling"
        stripArtistSuffix("Nobody plays alone") shouldBe "Nobody plays alone"
        stripArtistSuffix("Kid Plays Ellington") shouldBe "Kid Plays Ellington"
        stripArtistSuffix("Stereo Total - paris") shouldBe "Stereo Total"
    }

    // #314 — an origin tag comes off; a parenthesised alias stays.
    @Test
    fun `stripArtistSuffix drops an origin tag in codes or in full and keeps an alias`() {
        stripArtistSuffix("Ipkiss (NL)") shouldBe "Ipkiss"
        stripArtistSuffix("Marta Warelis (PL/USA)") shouldBe "Marta Warelis"
        stripArtistSuffix("NIGHT NAIL (Dark Wave US/DE)") shouldBe "NIGHT NAIL"
        stripArtistSuffix("Apichat Pakwan (Thailand-Live)") shouldBe "Apichat Pakwan"
        stripArtistSuffix("WhatWhy Art (Seoul)") shouldBe "WhatWhy Art"
        headlinersFromTitle("Solistenensemble Kaleidoskop x WhatWhy Art (Seoul) x Konstantin Heuer").map { it.name } shouldBe
            listOf("Solistenensemble Kaleidoskop", "WhatWhy Art", "Konstantin Heuer")
        stripArtistSuffix("Sickboyrari (Black Kray)") shouldBe "Sickboyrari (Black Kray)"
        stripArtistSuffix("Brazen Barbie (Hip Hop aus Australien)") shouldBe "Brazen Barbie"
        stripArtistSuffix("Kofi (from Ghana)") shouldBe "Kofi"
        // `aus` outside a bracket is part of the name.
        stripArtistSuffix("Cindy aus Marzahn") shouldBe "Cindy aus Marzahn"
    }

    // #3827 — a tour tail with no dash: any `Tournee`, and a `Tour` after a region word.
    @Test
    fun `stripArtistSuffix drops a Tournee tail and a region tour tail written without a dash`() {
        stripArtistSuffix("Brazen Barbie (Hip Hop aus Australien) Europa Tournee") shouldBe "Brazen Barbie"
        stripArtistSuffix("Clueso Abschiedstournee") shouldBe "Clueso"
        stripArtistSuffix("Clueso Tournee") shouldBe "Clueso"
        stripArtistSuffix("Max Raabe Deutschland-Tournee") shouldBe "Max Raabe"
        stripArtistSuffix("Wanda – Europa Tournee") shouldBe "Wanda"
        stripArtistSuffix("Kraftklub Europe Tour") shouldBe "Kraftklub"
        // A city is no region, and a bare `Tour` can close a band's name.
        stripArtistSuffix("Isolation Berlin Tour") shouldBe "Isolation Berlin Tour"
        stripArtistSuffix("Grand Tour") shouldBe "Grand Tour"
        stripArtistSuffix("Tournee") shouldBe "Tournee"
    }

    // #1561 — a band affiliation is a comma list or an `ex-` opener; a single bare name may be an alias.
    @Test
    fun `stripArtistSuffix drops a band affiliation and keeps a single parenthesised name`() {
        stripArtistSuffix("Colin Newman (WIRE, IMMERSION)") shouldBe "Colin Newman"
        stripArtistSuffix("Budgie (SIOUXSIE & THE BANSHEES, THE SLITS)") shouldBe "Budgie"
        stripArtistSuffix("Alexander Hacke (ex-EINSTÜRZENDE NEUBAUTEN, HACKEDEPICCIOTTO)") shouldBe "Alexander Hacke"
        stripArtistSuffix("Pauline Murray (PENETRATION)") shouldBe "Pauline Murray (PENETRATION)"
    }

    @Test
    fun `stripArtistSuffix drops a country or format tag in square brackets and keeps a bracket that is the name`() {
        stripArtistSuffix("Endica [Esp]") shouldBe "Endica"
        stripArtistSuffix("Marí Kozlovska [Esp]") shouldBe "Marí Kozlovska"
        stripArtistSuffix("Ohnmacht [Live]") shouldBe "Ohnmacht"
        stripArtistSuffix("Somebody [DJ Set]") shouldBe "Somebody"
        stripArtistSuffix("Ø [Phase]") shouldBe "Ø [Phase]"
        stripArtistSuffix("[o1 Temp]") shouldBe "[o1 Temp]"
    }

    // #1761 — the bracket shapes staging stored on upcoming events, and the brackets that are the name.
    @Test
    fun `stripArtistSuffix drops a number that tells same-named acts apart`() {
        stripArtistSuffix("SiSi (2)") shouldBe "SiSi"
        stripArtistSuffix("Emma (8)") shouldBe "Emma"
        stripArtistSuffix("Jakob Seidensticker & Melina (3)") shouldBe "Jakob Seidensticker & Melina"
        stripArtistSuffix("(3)") shouldBe "(3)"
        stripArtistSuffix("Prince (1999)") shouldBe "Prince (1999)"
        stripArtistSuffix("Blink-182") shouldBe "Blink-182"
    }

    @Test
    fun `stripArtistSuffix drops an alias that the venue bills on its own`() {
        stripArtistSuffix("CFL (AKA KRUMELUR)") shouldBe "CFL"
        stripArtistSuffix("Inthelinbo (Aka Anaïs-Lin)") shouldBe "Inthelinbo"
        stripArtistSuffix("Foo (a.k.a.Bar)") shouldBe "Foo"
        stripArtistSuffix("Bar (Akasha)") shouldBe "Bar (Akasha)"
        stripArtistSuffix("U.e. (Ulla)") shouldBe "U.e. (Ulla)"
    }

    @Test
    fun `stripArtistSuffix drops a performance format, a backing band and a show title`() {
        stripArtistSuffix("Nicole M Pikole (Drag)") shouldBe "Nicole M Pikole"
        stripArtistSuffix("Creature (duo set)") shouldBe "Creature"
        stripArtistSuffix("Dice Actors Live on Stage") shouldBe "Dice Actors"
        stripArtistSuffix("Live on Stage") shouldBe "Live on Stage"
        stripArtistSuffix("Guy Offenbach (& Original Karaoke Band)") shouldBe "Guy Offenbach"
        stripArtistSuffix("Los Refrescos (Dandy Jack & Argenis Brito)") shouldBe "Los Refrescos (Dandy Jack & Argenis Brito)"
        stripArtistSuffix("SHADEN (أنا إمرأة تعبانة) Show") shouldBe "SHADEN"
        stripArtistSuffix("The Rocky Horror Show") shouldBe "The Rocky Horror Show"
        stripArtistSuffix("TUTTI BOUNCE - 360 ° (Live) Show") shouldBe "TUTTI BOUNCE - 360 ° (Live) Show"
        stripArtistSuffix("Potsa Lotsa XL - Pizza Lotsa") shouldBe "Potsa Lotsa XL"
        stripArtistSuffix("BAD COMPANY LEGACY - Dave Colwell") shouldBe "BAD COMPANY LEGACY - Dave Colwell"
        stripArtistSuffix("YOK - pocketpunk spielt FUCK 10 CHECK") shouldBe "YOK"
        stripArtistSuffix("Das Kapital spielt Hanns Eisler") shouldBe "Das Kapital"
    }

    @Test
    fun `stripArtistSuffix drops an annotation bracket and keeps a bracket that is the name`() {
        stripArtistSuffix("Flow Rea (Est)") shouldBe "Flow Rea"
        stripArtistSuffix("goat (jp)") shouldBe "goat"
        stripArtistSuffix("David J (Bauhaus / Love & Rockets)") shouldBe "David J"
        stripArtistSuffix("Steve Norman (von Spandau Ballet) & The Sleevz") shouldBe "Steve Norman & The Sleevz"
        stripArtistSuffix("Paula Paula (Zusatzshow)") shouldBe "Paula Paula"
        stripArtistSuffix("DJ Hell (Vinyl Set)") shouldBe "DJ Hell"
        stripArtistSuffix("Sonny Smiles (Album Set)") shouldBe "Sonny Smiles"
        stripArtistSuffix("Aexhy & Sonny Smiles (album-set)") shouldBe "Aexhy & Sonny Smiles"
        stripArtistSuffix("Ethereal Logic (all night long)") shouldBe "Ethereal Logic"
        stripArtistSuffix("Ethereal Logic (Open to Close)") shouldBe "Ethereal Logic"
        stripArtistSuffix("Maria Wildeis TC80 Live (2h)") shouldBe "Maria Wildeis TC80 Live (2h)"
        stripArtistSuffix("Night Tempo") shouldBe "Night Tempo"
        stripArtistSuffix("Sean Steinfeger (OHSHITF*CKYES") shouldBe "Sean Steinfeger"

        stripArtistSuffix("Maynd (Chi)") shouldBe "Maynd (Chi)"
        stripArtistSuffix("Luca Saporito (Audiofly)") shouldBe "Luca Saporito (Audiofly)"
        stripArtistSuffix("All(h)ours") shouldBe "All(h)ours"
        stripArtistSuffix("Go(ø)d Trip") shouldBe "Go(ø)d Trip"
        stripArtistSuffix("Kreisfrequenz ((ω))") shouldBe "Kreisfrequenz ((ω))"
        stripArtistSuffix("(Th)ink About That") shouldBe "(Th)ink About That"
        stripArtistSuffix("Друга Ріка (Druha Rika)") shouldBe "Друга Ріка (Druha Rika)"
    }

    @Test
    fun `splitBracketedGuest splits a featured or added act out of a trailing bracket`() {
        splitBracketedGuest("Dosenstolz (feat. Tancred)") shouldBe listOf("Dosenstolz", "Tancred")
        splitBracketedGuest("Warhammer (+ Corrode)") shouldBe listOf("Warhammer", "Corrode")
        splitBracketedGuest("Clive (Splitting Image) and friends") shouldBe listOf("Clive (Splitting Image) and friends")
        splitBracketedGuest("Sickboyrari (Black Kray)") shouldBe listOf("Sickboyrari (Black Kray)")
    }

    // #1564 — a line-up placeholder glued to the last act.
    @Test
    fun `stripArtistSuffix drops a more-to-come tail`() {
        stripArtistSuffix("DaSoMaZo — more TBA") shouldBe "DaSoMaZo"
        stripArtistSuffix("Peggy Gou + many more") shouldBe "Peggy Gou"
        stripArtistSuffix("Lepido and more") shouldBe "Lepido"
        stripArtistSuffix("Ben Klock more TBA") shouldBe "Ben Klock"
        stripArtistSuffix("Moretti") shouldBe "Moretti"
        // A bare `more` or `mehr` with no dash, conjunction or placeholder is part of the name (#301).
        stripArtistSuffix("Juli N More") shouldBe "Juli N More"
        stripArtistSuffix("Mehr Is Mehr") shouldBe "Mehr Is Mehr"
    }

    // #301 — what the staging re-seed left behind once every line-up entry ran through the rule.
    @Test
    fun `stripArtistSuffix drops a leading relocation note, and the split remnants are non-artists`() {
        stripArtistSuffix("verschoben – Black River Delta") shouldBe "Black River Delta"
        stripArtistSuffix("Nachholtermin: Kraftklub") shouldBe "Kraftklub"
        // A whole `X & Friends` billing stays one act; the bare `Friends` a venue's own split leaves is dropped.
        stripArtistSuffix("Feo & Friends") shouldBe "Feo & Friends"
        isNonArtistName("verschoben") shouldBe true
        isNonArtistName("Panel") shouldBe true
        isNonArtistName("Friends") shouldBe true
        // A band with a conjunction in its name is one act.
        splitHeadlinerTitle("Chase & Status") shouldBe listOf("Chase & Status")
    }

    // #301 — the performance-format suffixes a line-up carries, in the spellings staging held.
    @Test
    fun `stripArtistSuffix drops every spelling of a performance-format suffix`() {
        stripArtistSuffix("Schatz (Live)") shouldBe "Schatz"
        stripArtistSuffix("Joplyn live") shouldBe "Joplyn"
        stripArtistSuffix("Ninsa hybrid live") shouldBe "Ninsa"
        stripArtistSuffix("Cee (hybrid live)") shouldBe "Cee"
        stripArtistSuffix("Oliver Ho Hybrid") shouldBe "Oliver Ho"
        stripArtistSuffix("Pés de Barro (live Band)") shouldBe "Pés de Barro"
        stripArtistSuffix("Regis Live & DJ set") shouldBe "Regis"
        stripArtistSuffix("Tweaken – live –") shouldBe "Tweaken"
        stripArtistSuffix("Sylk (DE) (Malör Records, Surge)") shouldBe "Sylk"
        // A parenthetical that names a project or a practice is not a format word.
        stripArtistSuffix("Gwenan (Phase Space Live)") shouldBe "Gwenan (Phase Space Live)"
        stripArtistSuffix("Teo Clavero (Live Painting)") shouldBe "Teo Clavero (Live Painting)"
    }
}
