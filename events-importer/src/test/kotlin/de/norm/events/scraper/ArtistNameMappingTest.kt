package de.norm.events.scraper

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// Focused tests for ArtistNameMapping — one test per behaviour.
@Suppress("LargeClass")
class ArtistNameMappingTest {
    // --- extractSupportFromSubtitle ---

    @Test
    fun `extractSupportFromSubtitle returns empty when no support line`() {
        extractSupportFromSubtitle("Tour 2026").shouldBeEmpty()
        extractSupportFromSubtitle(null).shouldBeEmpty()
        extractSupportFromSubtitle("").shouldBeEmpty()
    }

    @Test
    fun `extractSupportFromSubtitle extracts a single support act`() {
        extractSupportFromSubtitle("Tour 2026 | Support: Luana") shouldContainExactly listOf("Luana")
    }

    @Test
    fun `extractSupportFromSubtitle splits multiple acts on common delimiters`() {
        extractSupportFromSubtitle("Tour + Support: High On Fire & Gnome, Aska") shouldContainExactly
            listOf("High On Fire", "Gnome", "Aska")
    }

    @Test
    fun `extractSupportFromSubtitle keeps a backing-band tail attached to its act`() {
        extractSupportFromSubtitle("Support: Scott Hepple & The Sun Band") shouldContainExactly
            listOf("Scott Hepple & The Sun Band")
    }

    @Test
    fun `extractSupportFromSubtitle recognizes Opener and Special Guest markers`() {
        extractSupportFromSubtitle("+ Special Guest: Motorowl") shouldContainExactly listOf("Motorowl")
        extractSupportFromSubtitle("Opener: Warwolf") shouldContainExactly listOf("Warwolf")
    }

    @Test
    fun `extractSupportFromSubtitle splits a subtitle stacking two markers and strips the second`() {
        // "Opener: Warwolf + Special Guest: Motorjesus": the `+` separates the two acts;
        // the leading "Special Guest:" on the second act is stripped to its bare name.
        extractSupportFromSubtitle("Opener: Warwolf + Special Guest: Motorjesus") shouldContainExactly
            listOf("Warwolf", "Motorjesus")
    }

    // --- splitSupportActs ---

    @Test
    fun `splitSupportActs cuts on hard separators and guarded conjunctions`() {
        splitSupportActs("GUM + CLAVV") shouldContainExactly listOf("GUM", "CLAVV")
        splitSupportActs("High On Fire & Gnome, Aska") shouldContainExactly
            listOf("High On Fire", "Gnome", "Aska")
    }

    @Test
    fun `splitSupportActs splits the und conjunction but keeps the and-the-Ys tail attached`() {
        splitSupportActs("Earth Tongue und Scott Hepple & The Sun Band") shouldContainExactly
            listOf("Earth Tongue", "Scott Hepple & The Sun Band")
    }

    // --- supportSubtitleLine ---

    @Test
    fun `supportSubtitleLine picks the line carrying a support marker`() {
        supportSubtitleLine(listOf("Tour 2026", "+ Support: Jeff Clarke")) shouldBe "+ Support: Jeff Clarke"
        supportSubtitleLine(listOf("Opener: Warwolf")) shouldBe "Opener: Warwolf"
        supportSubtitleLine(listOf("Special Guest: Motorjesus")) shouldBe "Special Guest: Motorjesus"
    }

    @Test
    fun `supportSubtitleLine returns null when no line carries a marker`() {
        supportSubtitleLine(listOf("Tour 2026")) shouldBe null
        supportSubtitleLine(emptyList()) shouldBe null
    }

    // The reason the function exists: `.text()` flattens a trailing notice onto its own line, and
    // handing the whole subtitle to extractSupportFromSubtitle would capture the notice as an act.
    @Test
    fun `supportSubtitleLine isolates the support line from a trailing cancellation notice`() {
        val lines = listOf("+ Support: Jeff Clarke", "ABGESAGT. Bereits gekaufte Tickets behalten ihre Gültigkeit.")
        supportSubtitleLine(lines) shouldBe "+ Support: Jeff Clarke"
        extractSupportFromSubtitle(supportSubtitleLine(lines)) shouldContainExactly listOf("Jeff Clarke")
    }

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
    }

    @Test
    fun `isPlaceholderName keeps a bare More, which is a real band name`() {
        // The NWOBHM act "More" must survive; only a lead-in or a trailing TBA marks a placeholder.
        isPlaceholderName("More") shouldBe false
        isPlaceholderName("More Than Life") shouldBe false
        isPlaceholderName("Moremore") shouldBe false
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
        // A real act whose name merely contains "Jahre" mid-title is kept.
        isNonArtistEvent("Fettes Brot") shouldBe false
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
        // Recurring series: any edition number matches — both the plain and the N°<n> form.
        isNonArtistName("FEMALE-FRONTED IS NOT A GENRE 5") shouldBe true
        isNonArtistName("FEMALE-FRONTED IS NOT A GENRE 6") shouldBe true
        isNonArtistName("Boheme Sauvage N°141") shouldBe true
        isNonArtistName("Boheme Sauvage N°142") shouldBe true
        // Neue Zukunft recurring themed nights its widget calendar lists as the event title.
        isNonArtistName("Jazz After Dark") shouldBe true
        isNonArtistName("Future Bash Reloaded") shouldBe true
        isNonArtistName("A Dead Moon Night") shouldBe true
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
    fun `isGuestSlotLabel keeps a real act whose name only contains guest`() {
        // Anchored: a real band is untouched even when a guest word appears inside the name.
        isGuestSlotLabel("Guns N' Roses") shouldBe false
        isGuestSlotLabel("Special Guest DJ Foo") shouldBe false
        isNonArtistName("Guns N' Roses") shouldBe false
    }

    // --- splitSegmentOnConjunctions ---

    @Test
    fun `splitSegmentOnConjunctions splits guarded conjunctions but never a slash`() {
        splitSegmentOnConjunctions("Lichene & Neue K") shouldBe listOf("Lichene", "Neue K")
        // A "/" inside a single act name is preserved (not a co-bill separator here).
        splitSegmentOnConjunctions("Morimoto / Wong duo") shouldBe listOf("Morimoto / Wong duo")
        // Backing-band article tail stays joined.
        splitSegmentOnConjunctions("Scott Hepple & The Sun Band") shouldBe listOf("Scott Hepple & The Sun Band")
    }

    // Audit T-5: a conjunction inside a parenthetical belongs to that act's own affiliation list,
    // never to a co-bill. Splitting there tore one act into fragments and left an unbalanced
    // bracket behind — Frannz Club stored "David J (Bauhaus", "Love" and "Rockets)".
    @Test
    fun `splitSegmentOnConjunctions never cuts inside brackets`() {
        splitSegmentOnConjunctions("Los Refrescos (Dandy Jack & Argenis Brito)") shouldBe
            listOf("Los Refrescos (Dandy Jack & Argenis Brito)")
        splitSegmentOnConjunctions("Gum [Hofkonzert & Support]") shouldBe listOf("Gum [Hofkonzert & Support]")
        // A conjunction outside the bracket still cuts.
        splitSegmentOnConjunctions("Anemone (NL) & Foo") shouldBe listOf("Anemone (NL)", "Foo")
    }

    // Audit T-7: a role or event-format label in front of the act became part of the artist name —
    // Admiralspalast stored "Support: A.A. Williams", Loge "Record Release: Pair".
    @Test
    fun `stripArtistPrefix removes role and event-format labels`() {
        stripArtistPrefix("Support: A.A. Williams") shouldBe "A.A. Williams"
        stripArtistPrefix("Opener: Warwolf") shouldBe "Warwolf"
        stripArtistPrefix("Record Release: Margot Erkner") shouldBe "Margot Erkner"
        stripArtistPrefix("RECORD RELEASE: PAIR") shouldBe "PAIR"
        stripArtistPrefix("Listening Session: Drexciya") shouldBe "Drexciya"
    }

    @Test
    fun `stripArtistPrefix leaves a real name that merely opens with such a word`() {
        // The colon is required, so these are untouched.
        stripArtistPrefix("Recording Angels") shouldBe "Recording Angels"
        stripArtistPrefix("Session Victim") shouldBe "Session Victim"
        stripArtistPrefix("Support Lesbiens") shouldBe "Support Lesbiens"
        // Stripping that would leave nothing keeps the input.
        stripArtistPrefix("Support:") shouldBe "Support:"
    }

    @Test
    fun `headlinersFromTitle bills a labelled support act as SUPPORT and drops the label`() {
        headlinersFromTitle("Chelsea Wolfe + Support: A.A. Williams") shouldBe
            listOf(
                ScrapedArtist("Chelsea Wolfe", "HEADLINER"),
                ScrapedArtist("A.A. Williams", "SUPPORT")
            )
        // An event-format lead-in is stripped without changing the role.
        headlinersFromTitle("RECORD RELEASE: PAIR + WESTHAFEN") shouldBe
            listOf(
                ScrapedArtist("PAIR", "HEADLINER"),
                ScrapedArtist("WESTHAFEN", "HEADLINER")
            )
    }

    @Test
    fun `splitHeadlinerTitle keeps a parenthesised affiliation list whole`() {
        splitHeadlinerTitle("David J (Bauhaus / Love & Rockets)") shouldBe
            listOf("David J (Bauhaus / Love & Rockets)")
        splitHeadlinerTitle("Budgie (Siouxsie & The Banshees, The Slits)") shouldBe
            listOf("Budgie (Siouxsie & The Banshees, The Slits)")
        // A genuine co-bill outside the brackets still splits.
        splitHeadlinerTitle("David J (Bauhaus) + Tom Verlaine") shouldBe
            listOf("David J (Bauhaus)", "Tom Verlaine")
    }

    // --- stripArtistSuffix ---

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
    fun `stripArtistSuffix keeps a hyphenated name the shouted-tail rule must not cut`() {
        // Tail carries lowercase — it is a name, not a shouted tour title.
        stripArtistSuffix("BAD COMPANY LEGACY - Dave Colwell") shouldBe "BAD COMPANY LEGACY - Dave Colwell"
        stripArtistSuffix("Sinem - Hatun") shouldBe "Sinem - Hatun"
        // Head is all-caps, so an all-caps co-bill is never cut down to its first token.
        stripArtistSuffix("DZ - DEATHRAY") shouldBe "DZ - DEATHRAY"
        // A one-word shouted tail could be an alias or initialism, so it is left alone.
        stripArtistSuffix("Someone - ALIEN") shouldBe "Someone - ALIEN"
    }

    @Test
    fun `stripArtistSuffix recovers the act from a hyphenated Releaseshow tail`() {
        stripArtistSuffix("Sinem - Hatun - Releaseshow") shouldBe "Sinem - Hatun"
        stripArtistSuffix("Some Band - Release Show") shouldBe "Some Band"
        // Without the dash it is left alone — "Releaseshow" could be part of a name.
        stripArtistSuffix("Releaseshow") shouldBe "Releaseshow"
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
    fun `stripArtistSuffix strips a singt tribute framing and a release promo tag`() {
        stripArtistSuffix("Tex singt Leoanard Cohen") shouldBe "Tex"
        stripArtistSuffix("Max Raabe singt Weihnachtslieder") shouldBe "Max Raabe"
        stripArtistSuffix("Hawt Coco Album Release") shouldBe "Hawt Coco"
        stripArtistSuffix("Some Band EP Release Show") shouldBe "Some Band"
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

    // --- buildArtistList ---

    @Test
    fun `buildArtistList returns empty when supportNames is empty`() {
        buildArtistList("Headliner", emptyList()).shouldBeEmpty()
    }

    @Test
    fun `buildArtistList returns headliner and supports`() {
        val result = buildArtistList("The Adicts", listOf("Maid of Ace", "Kaos"))
        result shouldHaveSize 3
        result[0] shouldBe ScrapedArtist(name = "The Adicts", role = "HEADLINER")
        result[1] shouldBe ScrapedArtist(name = "Maid of Ace", role = "SUPPORT")
        result[2] shouldBe ScrapedArtist(name = "Kaos", role = "SUPPORT")
    }

    @Test
    fun `buildArtistList excludes placeholder headliner`() {
        val result = buildArtistList("TBA", listOf("Support Act"))
        result shouldHaveSize 1
        result[0] shouldBe ScrapedArtist(name = "Support Act", role = "SUPPORT")
    }

    @Test
    fun `buildArtistList excludes placeholder support names`() {
        val result = buildArtistList("The Adicts", listOf("TBA", "Maid of Ace"))
        result shouldHaveSize 2
        result[0] shouldBe ScrapedArtist(name = "The Adicts", role = "HEADLINER")
        result[1] shouldBe ScrapedArtist(name = "Maid of Ace", role = "SUPPORT")
    }

    @Test
    fun `buildArtistList with all placeholder supports returns only headliner`() {
        val result = buildArtistList("The Adicts", listOf("TBA", "TBD"))
        result shouldHaveSize 1
        result[0] shouldBe ScrapedArtist(name = "The Adicts", role = "HEADLINER")
    }

    @Test
    fun `buildArtistList drops a bare role-label support but keeps the headliner`() {
        // A "Support: Special Guest" line still signals the title-as-headliner
        // convention, but the label itself must not become a support artist.
        val result = buildArtistList("Green Lung", listOf("Special Guest"))
        result shouldContainExactly listOf(ScrapedArtist(name = "Green Lung", role = "HEADLINER"))
    }

    @Test
    fun `buildArtistList splits a multi-artist title into co-headliners`() {
        val result = buildArtistList("TOTAL CHAOS + RUMKICKS", listOf("The Dollheads"))
        result shouldContainExactly
            listOf(
                ScrapedArtist(name = "TOTAL CHAOS", role = "HEADLINER"),
                ScrapedArtist(name = "RUMKICKS", role = "HEADLINER"),
                ScrapedArtist(name = "The Dollheads", role = "SUPPORT")
            )
    }

    // --- buildArtistsForEventType ---

    @Test
    fun `buildArtistsForEventType treats a concert title as the headliner without a support line`() {
        buildArtistsForEventType("Green Lung", subtitle = null, eventType = "CONCERT") shouldContainExactly
            listOf(ScrapedArtist(name = "Green Lung", role = "HEADLINER"))
    }

    @Test
    fun `buildArtistsForEventType adds the subtitle's support acts after the headliners`() {
        buildArtistsForEventType("TOTAL CHAOS", subtitle = "+ Support: The Dollheads", eventType = "CONCERT") shouldContainExactly
            listOf(
                ScrapedArtist(name = "TOTAL CHAOS", role = "HEADLINER"),
                ScrapedArtist(name = "The Dollheads", role = "SUPPORT")
            )
    }

    @Test
    fun `buildArtistsForEventType stays conservative for an unclassified event`() {
        // No type to confirm the title names an act, so only a support line unlocks it.
        buildArtistsForEventType("Vinyl Thursdays", subtitle = null, eventType = null).shouldBeEmpty()
        buildArtistsForEventType("Green Lung", subtitle = "Support: Kaos", eventType = "OTHER") shouldContainExactly
            listOf(
                ScrapedArtist(name = "Green Lung", role = "HEADLINER"),
                ScrapedArtist(name = "Kaos", role = "SUPPORT")
            )
    }

    @Test
    fun `buildArtistsForEventType derives no lineup from a party or festival title`() {
        // Real titles from the seeded database. A club night's title is the night's name,
        // so deriving a headliner from it invents an act rather than recovering one — see
        // the measurement in buildArtistsForEventType's KDoc.
        buildArtistsForEventType("Vinyl Thursdays", subtitle = null, eventType = "PARTY").shouldBeEmpty()
        buildArtistsForEventType(
            "THE EARLY DAYS • LET'S DANCE TO JOY DIVISION",
            subtitle = null,
            eventType = "PARTY"
        ).shouldBeEmpty()
        buildArtistsForEventType("OUT OF LINE WEEKENDER 2027", subtitle = null, eventType = "FESTIVAL").shouldBeEmpty()
    }

    @Test
    fun `buildArtistsForEventType keeps a tribute night from minting the act it covers`() {
        // The worst shape the guard prevents: the `+` splits like a co-bill, so without it
        // Frannz's post-punk tribute would store a Nick Cave artist row that resolves, by
        // slug, onto the real Nick Cave.
        val tribute = "Friday I'm in Love – A Tribute to Post-Punk · Dark 80s + Nick Cave"
        headlinersFromTitle(tribute).map { it.name } shouldContain "Nick Cave"
        buildArtistsForEventType(tribute, subtitle = null, eventType = "PARTY").shouldBeEmpty()
    }

    @Test
    fun `buildArtistsForEventType ignores a support line on a party`() {
        // The guard runs before the subtitle is read: a "Support:" line on a night typed
        // PARTY does not reopen the title as a headliner, and drops the support act too.
        buildArtistsForEventType("Soul Explosion", subtitle = "Support: Kaos", eventType = "PARTY").shouldBeEmpty()
    }

    @Test
    fun `buildArtistsForEventType drops a label showcase's title but keeps its support acts`() {
        // The showcase rule answers "is the title an act", not "does this night have a lineup" —
        // so a support act billed alongside the credit is still stored.
        buildArtistsForEventType(
            "Corrupted Blood Club Show",
            subtitle = "Corrupted Blood Records presents | + Support: Kaos",
            eventType = "CONCERT"
        ) shouldContainExactly listOf(ScrapedArtist(name = "Kaos", role = "SUPPORT"))
    }

    @Test
    fun `buildArtistsForEventType applies the label showcase rule outside CONCERT too`() {
        // The conservative branch needs a support line to read the title as an act at all; the
        // showcase rule must still veto the title when one is present.
        buildArtistsForEventType(
            "Corrupted Blood Club Show",
            subtitle = "Corrupted Blood Records presents | Support: Kaos",
            eventType = "OTHER"
        ) shouldContainExactly listOf(ScrapedArtist(name = "Kaos", role = "SUPPORT"))
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

    // --- the "<night> w/ <acts>" frame ---

    @Test
    fun `headlinersFromTitle leaves a w-slash title alone unless the venue asks`() {
        // Off by default: `w/` joins collaborators at some venues (Zenner's "David August w/ MFO"),
        // where unpacking would delete the headliner.
        headlinersFromTitle("Analogue Foundation presents: David August w/ MFO") shouldContainExactly
            listOf(ScrapedArtist(name = "Analogue Foundation presents: David August w/ MFO", role = "HEADLINER"))
    }

    @Test
    fun `headlinersFromTitle unpacks a w-slash billing into its acts when asked`() {
        // The tail is a lineup list, so a comma delimits acts there — unlike in a title.
        headlinersFromTitle("RIOT ON THE ISLAND w/ Them Spirals, Painted Lox's & AK In Control", unpackWithFrame = true) shouldContainExactly
            listOf(
                ScrapedArtist(name = "Them Spirals", role = "HEADLINER"),
                ScrapedArtist(name = "Painted Lox's", role = "HEADLINER"),
                ScrapedArtist(name = "AK In Control", role = "HEADLINER")
            )
        // The night's own name never becomes a performer.
        headlinersFromTitle("RIPPLES W/ AMINE K", unpackWithFrame = true) shouldContainExactly
            listOf(ScrapedArtist(name = "AMINE K", role = "HEADLINER"))
    }

    @Test
    fun `headlinersFromTitle drops an unfinished-billing tail from a w-slash lineup`() {
        headlinersFromTitle("House of Rave w/ Maceo Plex, Mark Dekoda and many more", unpackWithFrame = true)
            .map { it.name } shouldContainExactly listOf("Maceo Plex", "Mark Dekoda")
    }

    @Test
    fun `headlinersFromTitle falls back to the whole title when a w-slash frame yields nothing`() {
        // "w/ TBA" leaves no usable act, so the title is parsed normally rather than yielding none.
        headlinersFromTitle("Green Lung w/ TBA", unpackWithFrame = true)
            .map { it.name } shouldContainExactly listOf("Green Lung w/ TBA")
    }

    // --- splitHeadlinerTitle ---

    @Test
    fun `splitHeadlinerTitle splits space-padded plus and slash co-bills`() {
        splitHeadlinerTitle("TOTAL CHAOS + RUMKICKS + THE DOLLHEADS") shouldContainExactly
            listOf("TOTAL CHAOS", "RUMKICKS", "THE DOLLHEADS")
        splitHeadlinerTitle("LAGWAGON / THE VIRGINMARYS") shouldContainExactly
            listOf("LAGWAGON", "THE VIRGINMARYS")
    }

    @Test
    fun `splitHeadlinerTitle splits a genuine ampersand co-bill`() {
        splitHeadlinerTitle("BLACK STAR RIDERS & TYKETTO") shouldContainExactly
            listOf("BLACK STAR RIDERS", "TYKETTO")
    }

    @Test
    fun `splitHeadlinerTitle splits guarded and und conjunctions`() {
        splitHeadlinerTitle("Earth Tongue und Scott Hepple") shouldContainExactly
            listOf("Earth Tongue", "Scott Hepple")
        splitHeadlinerTitle("Killswitch Engage and Parkway Drive") shouldContainExactly
            listOf("Killswitch Engage", "Parkway Drive")
    }

    @Test
    fun `splitHeadlinerTitle splits a real co-bill even when another act is an and-the-Ys band`() {
        // Cuts only at the "&"; the " AND THE GREAT BAND" tail stays joined to its act.
        splitHeadlinerTitle("CARL CARLTON & MELANIE WIEGMANN AND THE GREAT BAND") shouldContainExactly
            listOf("CARL CARLTON", "MELANIE WIEGMANN AND THE GREAT BAND")
    }

    @Test
    fun `splitHeadlinerTitle keeps single acts whose name contains a separator`() {
        // No space padding around the slash.
        splitHeadlinerTitle("AC/DC") shouldContainExactly listOf("AC/DC")
        // Denylisted ampersand name.
        splitHeadlinerTitle("Simon & Garfunkel") shouldContainExactly listOf("Simon & Garfunkel")
        // Denylist matches even when the source spells the conjunction as "and".
        splitHeadlinerTitle("Simon and Garfunkel") shouldContainExactly listOf("Simon and Garfunkel")
        splitHeadlinerTitle("BLOOD & SUN") shouldContainExactly listOf("BLOOD & SUN")
        // "X & the Ys" band-name tail, in both & and "and" forms.
        splitHeadlinerTitle("Nick Cave & the Bad Seeds") shouldContainExactly listOf("Nick Cave & the Bad Seeds")
        splitHeadlinerTitle("James and the Cold Gun") shouldContainExactly listOf("James and the Cold Gun")
        // A bare "and" inside a single word must not be split (space-padding).
        splitHeadlinerTitle("Portland") shouldContainExactly listOf("Portland")
        // Comma signals a member-list band name.
        splitHeadlinerTitle("Earth, Wind & Fire") shouldContainExactly listOf("Earth, Wind & Fire")
        // "& Friends" / "& Guests" / "& Band" collective tail names an unnamed cast, not a second act.
        splitHeadlinerTitle("Taylor & Friends") shouldContainExactly listOf("Taylor & Friends")
        splitHeadlinerTitle("Jonny & Guests") shouldContainExactly listOf("Jonny & Guests")
        splitHeadlinerTitle("Andreas Dresen & Band") shouldContainExactly listOf("Andreas Dresen & Band")
        // A real co-bill alongside a collective tail still splits at the real boundary.
        splitHeadlinerTitle("Ann & the Band + Real Act") shouldContainExactly listOf("Ann & the Band", "Real Act")
        // A named act after "&" ("Jesko Band") is still a genuine second act, not a bare "& Band" tail.
        splitHeadlinerTitle("Dennis & Jesko Band") shouldContainExactly listOf("Dennis", "Jesko Band")
    }

    @Test
    fun `splitHeadlinerTitle keeps a denylisted act whole when it co-bills with others`() {
        // The whole-title denylist check cannot fire here, so the guard has to hold at the
        // segment level: split at the "+" boundaries only, never inside "BLOOD & SUN".
        splitHeadlinerTitle("BLOOD & SUN + SOCIETY OF THE SILVER CROSS + LINNEA HJERTÉN") shouldContainExactly
            listOf("BLOOD & SUN", "SOCIETY OF THE SILVER CROSS", "LINNEA HJERTÉN")
        splitHeadlinerTitle("Pure Obsessions & Red Nights + Nico Amara") shouldContainExactly
            listOf("Pure Obsessions & Red Nights", "Nico Amara")
    }

    @Test
    fun `splitSupportActs keeps a denylisted act whole`() {
        splitSupportActs("Simon & Garfunkel, Aska") shouldContainExactly listOf("Simon & Garfunkel", "Aska")
    }

    @Test
    fun `splitHeadlinerTitle keeps a slash inside a single act name when splitOnSlash is false`() {
        // Madame Claude uses "/" inside one act's name, so its co-bills split only on " + ".
        splitHeadlinerTitle("Morimoto / Wong duo", splitOnSlash = false) shouldContainExactly
            listOf("Morimoto / Wong duo")
        splitHeadlinerTitle("Matthew Ryals + Morimoto / Wong duo + Song-Ming Ang", splitOnSlash = false) shouldContainExactly
            listOf("Matthew Ryals", "Morimoto / Wong duo", "Song-Ming Ang")
    }

    @Test
    fun `splitHeadlinerTitle returns a singleton for a plain single-act title`() {
        splitHeadlinerTitle("The Adicts") shouldContainExactly listOf("The Adicts")
        splitHeadlinerTitle("  The Adicts  ") shouldContainExactly listOf("The Adicts")
    }

    // --- headlinersFromTitle ---

    @Test
    fun `headlinersFromTitle extracts no act from a title led by a label's own name`() {
        // The label's fifteen-year night: "Zweiter Akt" is a programme part, not a performer.
        headlinersFromTitle("aufnahme + wiedergabe - Fünfzehn Jahre + Zweiter Akt").shouldBeEmpty()
        headlinersFromTitle("Aufnahme + Wiedergabe").shouldBeEmpty()
    }

    @Test
    fun `headlinersFromTitle extracts no act when the subtitle credits the label the title names`() {
        // Huxleys' label showcase: the title is the night's name, so reading it as an act invents
        // a performer. The label's business name is longer than the one it bills under, hence the
        // descriptor tail ("Records") being dropped before the two are compared.
        headlinersFromTitle("Corrupted Blood Club Show", subtitle = "Corrupted Blood Records presents").shouldBeEmpty()
        headlinersFromTitle("Corrupted Blood Club Show", subtitle = "Corrupted Blood presents").shouldBeEmpty()
        headlinersFromTitle("Corrupted Blood Club Show", subtitle = "Corrupted Blood Records präsentiert").shouldBeEmpty()
        // The credit survives being stacked with a second subtitle line.
        headlinersFromTitle("Corrupted Blood Club Show", subtitle = "Corrupted Blood Records presents | Doors 18:30").shouldBeEmpty()
    }

    @Test
    fun `headlinersFromTitle keeps the act when a presenter credit does not name the title`() {
        // Eight of the nine `presents` subtitles in the seed are this shape: the presenter is a
        // promoter and the title is the booked act.
        headlinersFromTitle("ÜBERDOSIS CRIME", subtitle = "CONTRA CREATE präsentiert").map { it.name } shouldContainExactly
            listOf("ÜBERDOSIS CRIME")
        headlinersFromTitle("The Spitfires", subtitle = "Rudeboys Production presents").map { it.name } shouldContainExactly
            listOf("The Spitfires")
        // A subtitle that continues past the marker is billing a tour, not standing as a credit.
        headlinersFromTitle("Zeppelin Club Show", subtitle = "Zeppelin Entertainment Presents - Joy of Little Things Tour")
            .map { it.name } shouldContainExactly listOf("Zeppelin Club Show")
    }

    @Test
    fun `headlinersFromTitle keeps the act of a title that carries the presents marker itself`() {
        // `<X> presents: <act>` is the opposite billing and the common one — Gretchen alone has 20.
        // Such a title trivially starts with `<X>`, so the marker in the title must veto the rule.
        headlinersFromTitle("Analogue Foundation presents: David August", subtitle = "Analogue Foundation presents")
            .map { it.name } shouldContainExactly listOf("Analogue Foundation presents: David August")
    }

    @Test
    fun `headlinersFromTitle keeps a title that is exactly the presenter's own name`() {
        // An act that runs a label of its own name looks identical to a label night here, and the
        // title adds nothing to tell them apart — so it is left as an act.
        headlinersFromTitle("Corrupted Blood", subtitle = "Corrupted Blood Records presents").map { it.name } shouldContainExactly
            listOf("Corrupted Blood")
        // Nor may a longer name be truncated to a shorter presenter at a word's middle.
        headlinersFromTitle("Corrupted Bloodline", subtitle = "Corrupted Blood presents").map { it.name } shouldContainExactly
            listOf("Corrupted Bloodline")
    }

    @Test
    fun `headlinersFromTitle still extracts acts the label merely promotes`() {
        // The label is only in the promoter field for these, never leading the title.
        headlinersFromTitle("TWIN NOIR + HINFORT").map { it.name } shouldContainExactly listOf("TWIN NOIR", "HINFORT")
        headlinersFromTitle("Escape with Romeo").map { it.name } shouldContainExactly listOf("Escape with Romeo")
    }

    @Test
    fun `headlinersFromTitle drops placeholder fragments from a split title`() {
        headlinersFromTitle("TBA + Real Band") shouldContainExactly
            listOf(ScrapedArtist(name = "Real Band", role = "HEADLINER"))
        headlinersFromTitle("TBA").shouldBeEmpty()
    }

    @Test
    fun `headlinersFromTitle keeps a slashed act name intact when splitOnSlash is false`() {
        // The Madame Claude concert path: "/" belongs to a single act ("Morimoto / Wong duo"),
        // so it must not be torn into two headliners; the trailing "(DJ-Set)" is still stripped.
        headlinersFromTitle("Morimoto / Wong duo + Forrest Gimp (DJ-Set)", splitOnSlash = false) shouldContainExactly
            listOf(
                ScrapedArtist(name = "Morimoto / Wong duo", role = "HEADLINER"),
                ScrapedArtist(name = "Forrest Gimp", role = "HEADLINER")
            )
    }

    @Test
    fun `headlinersFromTitle strips tour and live suffixes to recover the act`() {
        headlinersFromTitle("DOMINIUM - NIGHT IS CALLING TOUR 2026") shouldContainExactly
            listOf(ScrapedArtist(name = "DOMINIUM", role = "HEADLINER"))
        headlinersFromTitle("HGICH.T LIVE") shouldContainExactly
            listOf(ScrapedArtist(name = "HGICH.T", role = "HEADLINER"))
    }

    @Test
    fun `headlinersFromTitle strips an event-framing prefix to recover the act`() {
        headlinersFromTitle("A night with GULVØSS II") shouldContainExactly
            listOf(ScrapedArtist(name = "GULVØSS II", role = "HEADLINER"))
        headlinersFromTitle("An Evening with Nick Cave") shouldContainExactly
            listOf(ScrapedArtist(name = "Nick Cave", role = "HEADLINER"))
        // The framing phrase must be a leading whole prefix — a band with "night" mid-name is untouched.
        headlinersFromTitle("Last Night With You") shouldContainExactly
            listOf(ScrapedArtist(name = "Last Night With You", role = "HEADLINER"))
    }

    @Test
    fun `headlinersFromTitle recovers a single act from an anniversary title`() {
        // The comma in the tail keeps the title unsplit; the suffix strip then recovers the band.
        headlinersFromTitle("THE BUTLERS - 40 YEARS, SKA & SOULPOWER -") shouldContainExactly
            listOf(ScrapedArtist(name = "THE BUTLERS", role = "HEADLINER"))
    }

    @Test
    fun `headlinersFromTitle drops festival and ticket titles`() {
        headlinersFromTitle("SHRED FEST").shouldBeEmpty()
        headlinersFromTitle("Grey City Fest Opener").shouldBeEmpty()
        headlinersFromTitle("GROSSSTADTWAHNSINN 2026 - FESTIVALTICKET").shouldBeEmpty()
    }

    @Test
    fun `headlinersFromTitle strips a recurring-series prefix and keeps the billed acts`() {
        // The series label ("OFF THE RAILS #5:") is dropped; the acts after the colon remain.
        headlinersFromTitle("OFF THE RAILS #5: Blake Harley & Superior Motive") shouldContainExactly
            listOf(
                ScrapedArtist(name = "Blake Harley", role = "HEADLINER"),
                ScrapedArtist(name = "Superior Motive", role = "HEADLINER")
            )
    }

    @Test
    fun `headlinersFromTitle keeps a name with a colon but no series edition marker`() {
        // No "#<n>:" marker, so nothing is stripped (guards a real "9:3"-style name).
        headlinersFromTitle("Bleech 9:3") shouldContainExactly
            listOf(ScrapedArtist(name = "Bleech 9:3", role = "HEADLINER"))
    }
}
