package de.norm.events.scraper

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

// Focused tests for NonArtistNames — one test per behaviour.
class NonArtistNamesTest {
    // --- isPlaceholderName ---

    @Test
    fun `isPlaceholderName returns true for TBA variants`() {
        isPlaceholderName("TBA") shouldBe true
        isPlaceholderName("tba") shouldBe true
        isPlaceholderName("TBA.") shouldBe true
        isPlaceholderName("T.B.A.") shouldBe true
        isPlaceholderName("t.b.a.") shouldBe true
    }

    @Test
    fun `isPlaceholderName returns true for TBD variants`() {
        isPlaceholderName("TBD") shouldBe true
        isPlaceholderName("tbd") shouldBe true
        isPlaceholderName("TBD.") shouldBe true
    }

    @Test
    fun `isPlaceholderName returns true for TBC variants`() {
        isPlaceholderName("TBC") shouldBe true
        isPlaceholderName("tbc") shouldBe true
        isPlaceholderName("TBC.") shouldBe true
    }

    @Test
    fun `isPlaceholderName returns true for NN variants`() {
        isPlaceholderName("N.N.") shouldBe true
        isPlaceholderName("NN") shouldBe true
        isPlaceholderName("nn") shouldBe true
        isPlaceholderName("NN.") shouldBe true
    }

    @Test
    fun `isPlaceholderName trims whitespace`() {
        isPlaceholderName("  TBA  ") shouldBe true
    }

    @Test
    fun `isPlaceholderName returns false for real artist names`() {
        isPlaceholderName("Aska") shouldBe false
        isPlaceholderName("The Adicts") shouldBe false
        isPlaceholderName("DJ Shadow") shouldBe false
    }

    // Audit T-6: Kater's lineup parser split on "+" and handed the venue's unfinished-billing
    // marker over as the next act, storing "+ more" and "+ more Tba" as artists.
    @Test
    fun `isPlaceholderName returns true for a more-to-come lineup continuation`() {
        isPlaceholderName("+ more") shouldBe true
        isPlaceholderName("+ more tba") shouldBe true
        isPlaceholderName("+ more Tba") shouldBe true
        isPlaceholderName("& more") shouldBe true
        isPlaceholderName("and more") shouldBe true
        isPlaceholderName("und mehr") shouldBe true
        isPlaceholderName("more tba") shouldBe true
        isPlaceholderName("more to be announced") shouldBe true
    }

    @Test
    fun `isPlaceholderName keeps a bare More, which is a real band name`() {
        // The NWOBHM act "More" must survive; only a lead-in or a trailing TBA marks a placeholder.
        isPlaceholderName("More") shouldBe false
        isPlaceholderName("More Than Life") shouldBe false
        isPlaceholderName("Moremore") shouldBe false
    }

    @Test
    fun `isPlaceholderName reads a placeholder inside one pair of enclosing brackets`() {
        // KitKatClub bills its weekly Unity night as `Line up: (tba)` (#3202).
        isPlaceholderName("(tba)") shouldBe true
        isPlaceholderName("[TBA]") shouldBe true
        isPlaceholderName("(t.b.a.)") shouldBe true
        isNonArtistName("(tba)") shouldBe true
    }

    @Test
    fun `isPlaceholderName keeps a bracketed name and a bracketed suffix`() {
        isPlaceholderName("(Unauffinbar)") shouldBe false
        isNonArtistName("(Unauffinbar)") shouldBe false
        isNonArtistName("Name (Live)") shouldBe false
        isNonArtistName("Name (tba)") shouldBe false
    }

    // --- isNonArtistLabel ---

    @Test
    fun `isNonArtistLabel returns true for bare role labels`() {
        isNonArtistLabel("Special Guest") shouldBe true
        isNonArtistLabel("Special Guests") shouldBe true
        isNonArtistLabel("Support") shouldBe true
        isNonArtistLabel("div. Supports") shouldBe true
        isNonArtistLabel("SPECIAL GUEST") shouldBe true
        isNonArtistLabel("Support:") shouldBe true
    }

    @Test
    fun `isNonArtistLabel returns false for real names that merely contain a label word`() {
        isNonArtistLabel("Green Lung") shouldBe false
        isNonArtistLabel("Special Guest Stars") shouldBe false
        isNonArtistLabel("") shouldBe false
    }

    // --- isEventSegmentLabel ---

    @Test
    fun `isEventSegmentLabel returns true for aftershow and warm-up segments`() {
        isEventSegmentLabel("ACID AFTERSHOW") shouldBe true
        isEventSegmentLabel("Aftershow") shouldBe true
        isEventSegmentLabel("Aftershow Party") shouldBe true
        isEventSegmentLabel("Techno Afterparty") shouldBe true
        isEventSegmentLabel("Warm Up") shouldBe true
        isEventSegmentLabel("warm-up") shouldBe true
        // Morphine Raum's set line on a listening session, whose time is the start and whose name is none.
        isEventSegmentLabel("Event start") shouldBe true
    }

    @Test
    fun `isEventSegmentLabel is fully anchored so real names survive`() {
        // A real band whose name resembles a segment word.
        isEventSegmentLabel("AFTERHOURS") shouldBe false
        // A qualified/named slot that carries more than the bare segment phrase.
        isEventSegmentLabel("Warm Up im Franken") shouldBe false
        isEventSegmentLabel("The Muppet Show") shouldBe false
        isEventSegmentLabel("Green Lung") shouldBe false
    }

    // --- isNonArtistEvent ---

    @Test
    fun `isNonArtistEvent returns true for festival and festival-ticket labels`() {
        isNonArtistEvent("SHRED FEST") shouldBe true
        isNonArtistEvent("GROBES FEST 2026") shouldBe true
        isNonArtistEvent("CANARIAS CALLING FESTIVAL") shouldBe true
        isNonArtistEvent("GROSSSTADTWAHNSINN 2026 - FESTIVALTICKET") shouldBe true
    }

    @Test
    fun `isNonArtistEvent returns true for a festival slot or edition with trailing text`() {
        isNonArtistEvent("Grey City Fest Opener") shouldBe true
        isNonArtistEvent("Sommer Festival Special") shouldBe true
    }

    @Test
    fun `isNonArtistEvent keeps one-word names and compounds that merely contain fest`() {
        isNonArtistEvent("Infest") shouldBe false
        isNonArtistEvent("Manifest") shouldBe false
        isNonArtistEvent("Sommerfest") shouldBe false
        isNonArtistEvent("Green Lung") shouldBe false
    }

    @Test
    fun `isNonArtistEvent returns true for a Hoffest and a leading anniversary phrase`() {
        isNonArtistEvent("36 Jahre Schokoladen - Hoffest") shouldBe true
        isNonArtistEvent("Hoffest") shouldBe true
        isNonArtistEvent("40 Jahre SO36") shouldBe true
        isNonArtistEvent("10 Years Anniversary") shouldBe true
        isNonArtistEvent("1 Year Anniversary") shouldBe true
        // A singular `Year` after any other number is a band name, not an anniversary (Neue Zukunft, event 3847).
        isNonArtistEvent("13 Year Cicada") shouldBe false
        // A real act whose name merely contains "Jahre" mid-title is kept.
        isNonArtistEvent("Fettes Brot") shouldBe false
    }

    @Test
    fun `isNonArtistEvent keeps acts that start with a number, contain vol or sing, or name a tribute`() {
        listOf(
            "65daysofstatic",
            "808 State",
            "2raumwohnung",
            "Blink-182",
            "Volbeat",
            "Vol. Dealers",
            "Singen",
            "Sing Sing",
            "Rolling Stones Tribute Band \"Dirty Work\"",
            "Heroes - David Bowie Tribute",
            "A Tribute to Lemmy"
        ).forEach { isNonArtistEvent(it) shouldBe false }
    }

    @Test
    fun `isNonArtistEvent answers a long title without backtracking`() {
        val long = "Berliner Jazztreff Vol ".repeat(400) + "and more singing"

        val (matched, took) = measureTimedValue { isNonArtistEvent(long) }

        matched shouldBe false
        took shouldBeLessThan 1.seconds
    }

    // --- isScoreConcertTitle (#1829) ---

    @Test
    fun `isScoreConcertTitle recognises a film or game score concert`() {
        listOf(
            "William Shakespeare’s Romeo + Juliet Film in Concert",
            "Disney in Concert",
            "MY HERO ACADEMIA - In Concert",
            "Top Gun: Maverick – in Concert",
            "STUDIO GHIBLI IN CONCERT – A SYMPHONIC HOMAGE TO THE BEST OF JAPANESE ANIME",
            "The Music of STAR WARS - Live in Concert",
            "Der König der Löwen - The Music live in Concert",
            "Der Herr der Ringe: Die zwei Türme – in Concert Live to Film",
            "UNDERTALE: The Determination Symphony",
            "CLAIR OBSCUR: EXPEDITION 33 - A PAINTED SYMPHONY",
            "David Bowie Symphony - The Celebration Concert",
            "Nosferatu. Eine Symphonie des Grauens",
            "Hans Zimmer Live in Concert"
        ).forEach { isScoreConcertTitle(it) shouldBe true }
    }

    @Test
    fun `isScoreConcertTitle keeps an orchestra, a band and a plain live billing`() {
        listOf(
            "Roncalli und Deutsches Symphonie-Orchester Berlin",
            "Deutsches Symphonieorchester Berlin",
            "London Symphony Orchestra",
            "Symphony X",
            "Chanel Beads + urika's bedroom",
            "Green Lung Live",
            "Concert for Ukraine"
        ).forEach { isScoreConcertTitle(it) shouldBe false }
    }

    // --- isNonArtistName slugless (#1553) ---

    @Test
    fun `isNonArtistName drops a name that slugs to nothing`() {
        isNonArtistName("-") shouldBe true
        isNonArtistName("--") shouldBe true
        isNonArtistName("...") shouldBe true
        isNonArtistName("?!") shouldBe true
        isNonArtistName("/") shouldBe true
        isNonArtistName("+") shouldBe true
        isNonArtistName("–") shouldBe true
        isNonArtistName("Mittelalter-Irish Folk") shouldBe false
    }

    // The duo `@` is spoken "At", and slugs to `at` (#3793).
    @Test
    fun `isNonArtistName keeps an act whose name is an at sign`() {
        isSlugless("@") shouldBe false
        isNonArtistName("@") shouldBe false
        stripArtistSuffix("@ (US, 4AD)") shouldBe "@"
    }

    // --- isNonArtistName bare number (#1556) ---

    @Test
    fun `isNonArtistName drops a digits-only name`() {
        isNonArtistName("2") shouldBe true
        isNonArtistName(" 2027 ") shouldBe true
        isNonArtistName("100 Kilo Herz") shouldBe false
        isNonArtistName("1-800-Mikey") shouldBe false
        // Known acts whose name is a number (#1953, #2207).
        isNonArtistName("255") shouldBe false
        isNonArtistName("234") shouldBe false
    }

    // --- isNonArtistName title fragment (#1494) ---

    @Test
    fun `isNonArtistName drops a slice of a title that still carries its pipe separator`() {
        isNonArtistName("Sketchy Sessions | jazz") shouldBe true
        // A long or many-worded name is still a name.
        isNonArtistName("...And You Will Know Us by the Trail of Dead") shouldBe false
    }

    // --- isNonArtistName curated denylist ---

    @Test
    fun `isNonArtistName drops curated one-off non-artist titles`() {
        isNonArtistName("Warm Up im Franken") shouldBe true
        isNonArtistName("THE REVIVAL TOUR") shouldBe true
        isNonArtistName("Music Quiz") shouldBe true
        isNonArtistName("Open Mic L. J. Fox") shouldBe true
        isNonArtistName("Feinster HipHop") shouldBe true
        isNonArtistName("Karrera Klub") shouldBe true
        isNonArtistName("The Swag Jam") shouldBe true
        // Bi Nuu party/DJ series its structured `performers` list names as the act.
        isNonArtistName("GrooveJet Berlin") shouldBe true
        isNonArtistName("Ultra Night") shouldBe true
        isNonArtistName("FUEGO36") shouldBe true
        isNonArtistName("Abraxas Party") shouldBe true
        // Heimathafen's concert format, billed under its own name (#2416).
        isNonArtistName("AUF TAKT! DAS PODCAST-KONZERT") shouldBe true
        // Heimathafen's karaoke format, billed under its own name (#2788).
        isNonArtistName("SING DELA SING") shouldBe true
        // A revue, a songwriter showcase and a numbered hip-hop series (#2709).
        isNonArtistName("Der Blaue Montag") shouldBe true
        isNonArtistName("Berlin Songwood Sessions") shouldBe true
        isNonArtistName("Große Hip Hop Offensive 17") shouldBe true
        isNonArtistName("Große Hip Hop Offensive XVII") shouldBe true
        // Recurring series: any edition number matches — both the plain and the N°<n> form.
        isNonArtistName("FEMALE-FRONTED IS NOT A GENRE 5") shouldBe true
        isNonArtistName("FEMALE-FRONTED IS NOT A GENRE 6") shouldBe true
        isNonArtistName("Boheme Sauvage N°141") shouldBe true
        isNonArtistName("Boheme Sauvage N°142") shouldBe true
        // Neue Zukunft recurring themed nights its widget calendar lists as the event title.
        isNonArtistName("Jazz After Dark") shouldBe true
        isNonArtistName("Future Bash Reloaded") shouldBe true
        isNonArtistName("A Dead Moon Night") shouldBe true
        // A campaign's concert series billed as the act, in the venue's shouted case and with a tour tail.
        isNonArtistName("KEIN BOCK AUF NAZIS") shouldBe true
        isNonArtistName(stripArtistSuffix("Kein Bock auf Nazis - 20 Jahre Tour")) shouldBe true
        // A battle-rap league billed as the act, in the venue's shouted case.
        isNonArtistName("DLTLLY") shouldBe true
        isNonArtistName("Dltlly") shouldBe true
    }

    @Test
    fun `isNonArtistName drops series and festivals billed as their own act`() {
        // The three the 2026-09-25 plausibility check found (#1952), with and without their edition.
        isNonArtistName("Thrash Talk Vol 5") shouldBe true
        isNonArtistName("Thrash Talk") shouldBe true
        isNonArtistName("In The Mountains") shouldBe true
        isNonArtistName("ROCKSTAR GIRLFRIENDS") shouldBe true
        // Any numbered volume is a series edition, not a performer.
        isNonArtistName("Some Night Vol. 12") shouldBe true
        isNonArtistName("Deep Cuts Volume 3") shouldBe true
        // A name that merely contains the letters keeps.
        isNonArtistName("Volbeat") shouldBe false
        isNonArtistName("Revolver") shouldBe false
    }

    @Test
    fun `isNonArtistName keeps real names including those ending in a number`() {
        isNonArtistName("WEDNESDAY 13") shouldBe false
        isNonArtistName("OXO86") shouldBe false
        isNonArtistName("The Adicts") shouldBe false
    }

    @Test
    fun `isNonArtistName normalizes accents and a trailing Berlin before denylist matching`() {
        // Accented, city-suffixed editions of the same series fold onto one entry ("boheme sauvage").
        isNonArtistName("Bohème Sauvage Berlin") shouldBe true
        isNonArtistName("BOHÈME SAUVAGE BERLIN") shouldBe true
        // The city suffix is likewise stripped for GrooveJet (entry is city-free "groovejet").
        isNonArtistName("GrooveJet Berlin") shouldBe true
        // Matching-only: a real act merely ending in "Berlin" loses the suffix too but isn't denylisted.
        isNonArtistName("Isolation Berlin") shouldBe false
    }

    // --- isDjSetFormatLabel ---

    @Test
    fun `isDjSetFormatLabel drops a bare DJ-set format label, with or without a slash-origin tail`() {
        isDjSetFormatLabel("DJ-Set") shouldBe true
        isDjSetFormatLabel("DJ Set") shouldBe true
        isDjSetFormatLabel("DJ-Set / Berlin") shouldBe true
        isDjSetFormatLabel("dj-set / london, uk") shouldBe true
        isNonArtistName("DJ-Set / Berlin") shouldBe true
    }

    @Test
    fun `isDjSetFormatLabel keeps a real DJ act whose name only starts with DJ Set`() {
        // Anchored: a name that merely starts with the label, or any "DJ <handle>", survives.
        isDjSetFormatLabel("DJ Koze") shouldBe false
        isDjSetFormatLabel("DJ Set Sail") shouldBe false
        isNonArtistName("DJ Koze") shouldBe false
    }

    // --- programme item slot ---

    @Test
    fun `isNonArtistName drops a plus-led programme item`() {
        // Klunkerkranich adds a workshop to its line-up, and Kater a darkroom (#3202, #1564).
        isNonArtistName("+ DJ Workshop (Workshop Sold Out)") shouldBe true
        isNonArtistName("+ Darkroom") shouldBe true
        isNonArtistName("+Workshop") shouldBe true
    }

    @Test
    fun `isNonArtistName keeps an act beside a programme item`() {
        isNonArtistName("Laura Sí B2B Lea Chuga") shouldBe false
        isNonArtistName("Darkroom Dubs") shouldBe false
        isNonArtistName("Workshop") shouldBe false
        isNonArtistName("DJ Koze + Darkroom Dubs") shouldBe false
    }

    // --- isGuestSlotLabel ---

    @Test
    fun `isGuestSlotLabel drops an unannounced guest slot, with or without a leading plus`() {
        // Wild at Heart lists a yet-unnamed support act as "+ Guest" in the lineup.
        isGuestSlotLabel("+Guest") shouldBe true
        isGuestSlotLabel("+ Guest") shouldBe true
        isGuestSlotLabel("Guest") shouldBe true
        isGuestSlotLabel("Guests") shouldBe true
        isGuestSlotLabel("Gäste") shouldBe true
        // Club der Visionäre names the format the unbooked slot fills.
        isGuestSlotLabel("Guest DJs") shouldBe true
        isGuestSlotLabel("Guest DJ") shouldBe true
        isNonArtistName("+Guest") shouldBe true
        isNonArtistName("Guest DJs") shouldBe true
    }

    @Test
    fun `isGuestSlotLabel drops a secret lineup and a guest slot with a tba tail`() {
        // OHM's spelling, "tab" for "tba" (#2439).
        isNonArtistName("+ Secret Lineup") shouldBe true
        isNonArtistName("+ Secret Guest tab") shouldBe true
        isGuestSlotLabel("Secret Line-Up") shouldBe true
        isGuestSlotLabel("Mystery Guest TBA") shouldBe true
        isGuestSlotLabel("Guests tbc.") shouldBe true
        // KitKatClub puts one word between the two (#2901).
        isGuestSlotLabel("Secret Music Line up") shouldBe true
        isNonArtistName("Secret Music Line up") shouldBe true
        isGuestSlotLabel("Surprise DJ Lineup TBA") shouldBe true
    }

    @Test
    fun `isGuestSlotLabel keeps a real act whose name only contains guest`() {
        // Anchored: a real band is untouched even when a guest word appears inside the name.
        isGuestSlotLabel("Guns N' Roses") shouldBe false
        isGuestSlotLabel("Special Guest DJ Foo") shouldBe false
        isGuestSlotLabel("Secret Rhythms") shouldBe false
        isGuestSlotLabel("Lineup") shouldBe false
        // One word at most between `secret` and `line up`, so a longer name is still an act.
        isGuestSlotLabel("Secret Garden Music Lineup") shouldBe false
        isGuestSlotLabel("Secret Music") shouldBe false
        isGuestSlotLabel("Music Line Up") shouldBe false
        isGuestSlotLabel("Secret Music Line up Orchestra") shouldBe false
        isNonArtistName("Secret Garden Music Lineup") shouldBe false
        isNonArtistName("Guns N' Roses") shouldBe false
    }

    // #3214 — Urban Spree bills `UNS + GÄSTE BERLIN`: a guest slot with a place after it.
    @Test
    fun `isGuestSlotLabel drops a guest slot with one place after it`() {
        isGuestSlotLabel("GÄSTE BERLIN") shouldBe true
        isGuestSlotLabel("+ Gäste Berlin") shouldBe true
        isGuestSlotLabel("Guests London") shouldBe true
        isNonArtistName("GÄSTE BERLIN") shouldBe true
        headlinersFromTitle("UNS + GÄSTE BERLIN").map { it.name } shouldBe listOf("UNS")
        // The place must stand alone after the guest word.
        isGuestSlotLabel("Gäste Berlin Mitte") shouldBe false
        isGuestSlotLabel("Guests Of Berlin") shouldBe false
        isGuestSlotLabel("Berlin Gäste") shouldBe false
    }

    // #3214 — a night named for its hosts, with no act after the verb.
    @Test
    fun `isNonArtistEvent drops a name that ends on invites`() {
        isNonArtistEvent("Not Stereo Invites") shouldBe true
        isNonArtistEvent("Sesh Clara Cuve invites") shouldBe true
        isNonArtistEvent("Hosts Invite") shouldBe true
        headlinersFromTitle("Not Stereo Invites").shouldBeEmpty()
        headlinersFromTitle("Tresor invites Surgeon").map { it.name } shouldBe listOf("Tresor invites Surgeon")
    }

    @Test
    fun `isNonArtistEvent keeps a name with an act after invites, and the title splits host from guest`() {
        isNonArtistEvent("Tony Hurdle Invites TC The 3RD") shouldBe false
        isNonArtistEvent("FLUXO INVITES EBONY") shouldBe false
        isNonArtistEvent("Invitees") shouldBe false
        isNonArtistEvent("Uninvited") shouldBe false
        headlinersFromTitle("Tony Hurdle Invites TC The 3RD").map { it.name } shouldBe listOf("Tony Hurdle", "TC The 3RD")
        headlinersFromTitle("Not Stereo Invites").shouldBeEmpty()
        headlinersFromTitle("Tresor invites Surgeon").map { it.name } shouldBe listOf("Tresor invites Surgeon")
        headlinersFromTitle("Invitees").map { it.name } shouldBe listOf("Invitees")
    }

    @Test
    fun `every support label is a role label, never an act`() {
        listOf("Support Act", "Opening Act", "div. Support", "Special Guests", "Vorband", "Opener:").forEach {
            isNonArtistName(it) shouldBe true
        }
        isNonArtistName("Opening") shouldBe false
    }

    // #3326 — production titles: a production or series whose cast is only in the page text.
    @Test
    fun `buildArtistsForEventType bills no production or series name as the act`() {
        listOf("Pater Brown", "Die Weihnachtsengel", "Generation XY", "Unter meinem Bett", "NightWash", "Nightwash", "Smash Comedy Deluxe")
            .forEach { withClue(it) { isNonArtistName(it) shouldBe true } }
        buildArtistsForEventType("Unter meinem Bett", null, "CONCERT").shouldBeEmpty()
        buildArtistsForEventType("NightWash – Das Sing Ding", null, "CONCERT").shouldBeEmpty()
        buildArtistsForEventType("Smash Comedy Deluxe: Chaya Time!", null, "COMEDY").shouldBeEmpty()
        listOf("Generation XY", "Pater Brown", "Die Weihnachtsengel").forEach { withClue(it) { headlinersFromTitle(it).shouldBeEmpty() } }
        // An exact match only: an act whose name merely contains the brand is kept.
        listOf("NightWash Allstars", "Nightwasher", "Mr. Nightwash").forEach { withClue(it) { isNonArtistName(it) shouldBe false } }
    }

    @Test
    fun `isNonArtistEvent keeps an act whose name only contains nacht`() {
        listOf("Nachtblut", "Die Nacht", "Nacht", "Jazz Nacht Trio", "Nachtschatten des Jahres").forEach { isNonArtistEvent(it) shouldBe false }
    }

    @Test
    fun `isNonArtistName keeps a name that only contains a year, edition, special or night word`() {
        listOf("The Specials", "Specials", "Special", "Edition", "Special Edition Band", "Front 242", "Blink-182", "Label", "Night Label Club")
            .forEach { name -> withClue(name) { isNonArtistName(name) shouldBe false } }
    }

    @Test
    fun `a head-count is not an act, a name that starts with a number is`() {
        isNonArtistName("20 DJ's") shouldBe true
        isNonArtistName("20 DJs") shouldBe true
        isNonArtistName("3 Bands") shouldBe true
        isNonArtistName("12 Künstlerinnen") shouldBe true
        isNonArtistName("2 Many DJs") shouldBe false
        isNonArtistName("2raumwohnung") shouldBe false
        isNonArtistName("UFO 361") shouldBe false
    }
}
