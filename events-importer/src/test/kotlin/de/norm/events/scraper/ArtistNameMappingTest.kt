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
    fun `extractSupportFromSubtitle reads every line, and the Act spelling of both markers`() {
        // Velomax stacks the two markers on their own lines under the tour name (#1680).
        extractSupportFromSubtitle("Tsunami Sea Tour 2026\nSupport Act: Jinjer\nOpening Act: Dying Wish") shouldContainExactly
            listOf("Jinjer", "Dying Wish")
        extractSupportFromSubtitle("Support Act: Jinjer") shouldContainExactly listOf("Jinjer")
    }

    @Test
    fun `extractSupportFromSubtitle splits two markers a venue wrote on one line`() {
        // Simple Plan at UFO im Velodrom, where both billings share a line (#1730).
        extractSupportFromSubtitle("Bigger Than You Think! Europe Tour 2026 Support: Neck Deep Opener: Charlotte Sands") shouldContainExactly
            listOf("Neck Deep", "Charlotte Sands")
        // The second marker needs its colon, so a line that merely contains the word is one act.
        extractSupportFromSubtitle("Support: Jinjer Opening Wish") shouldContainExactly listOf("Jinjer Opening Wish")
        extractSupportFromSubtitle("Support: Neck Deep, Charlotte Sands Special Guest: Oxis") shouldContainExactly
            listOf("Neck Deep", "Charlotte Sands", "Oxis")
    }

    @Test
    fun `splitSupportActs drops a role label from each act, and keeps a band named after one`() {
        // Metropol prints its support line as `Support: THE BAXBYS` (#1678).
        splitSupportActs("Support: THE BAXBYS") shouldContainExactly listOf("THE BAXBYS")
        splitSupportActs("Support: Silent Planet + Opener: Boyish") shouldContainExactly listOf("Silent Planet", "Boyish")
        // The colon is what makes the strip safe: this band keeps its name.
        splitSupportActs("Support Lesbiens") shouldContainExactly listOf("Support Lesbiens")
    }

    @Test
    fun `a presents frame bills the acts after the colon, comma-separated`() {
        // Delphi writes the whole night into the title (#1690); the list is a bill, not a name.
        headlinersFromTitle(
            "Berlin Confidential präsentiert: Georgy Gusev, Sven Helbig, Ivan Skanavi & Deutsches Kammerorchester Berlin"
        ).map { it.name } shouldContainExactly
            listOf("Georgy Gusev", "Sven Helbig", "Ivan Skanavi", "Deutsches Kammerorchester Berlin")
        headlinersFromTitle("Landstreicher Booking presents: XAVI").map { it.name } shouldContainExactly listOf("XAVI")
        // The abbreviated marker keeps its own rule: a single name on the right is the act's work.
        headlinersFromTitle("Burnt Friedman pres: Secret Rhythms").map { it.name } shouldContainExactly listOf("Burnt Friedman")
    }

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
    fun `a support act named after a role word keeps that word`() {
        // The Czech band billed as the support act, which the colon-less strip maimed (#1732).
        extractSupportFromSubtitle("Support: Support Lesbiens") shouldContainExactly listOf("Support Lesbiens")
        extractSupportFromSubtitle("Special Guest: Special Guest Stars") shouldContainExactly listOf("Special Guest Stars")
        // The abbreviations keep their colon-less form: no venue writes one after them.
        extractSupportFromSubtitle("Support: feat. Kate NV") shouldContainExactly listOf("Kate NV")
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

    // #1902 — a sing-along bills its format, and no performer.
    @Test
    fun `headlinersFromTitle reads a sing-along title as a format with no act`() {
        headlinersFromTitle("SingAlong – Das große Mitsing-Event").shouldBeEmpty()
        headlinersFromTitle("Singalong -Das große Mitsing-Event").shouldBeEmpty()
        headlinersFromTitle("Disney Sing-Along").shouldBeEmpty()
        isNonArtistEvent("Großes Mitsingkonzert") shouldBe true
        // The word must stand alone: a name that merely contains the letters is kept.
        isNonArtistEvent("Singalongs") shouldBe false
        isNonArtistEvent("Sing Sing") shouldBe false
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

    // `+` is the film's own title here, and a split would mint two nonsense artist pages.
    @Test
    fun `a score concert bills no act, and its title is not split`() {
        buildArtistsForEventType(
            "William Shakespeare’s Romeo + Juliet Film in Concert",
            subtitle = null,
            eventType = "CONCERT"
        ).shouldBeEmpty()
        headlinersFromTitle("Disney in Concert").shouldBeEmpty()
        headlinersFromTitle("Roncalli und Deutsches Symphonie-Orchester Berlin").map { it.name } shouldContainExactly
            listOf("Roncalli", "Deutsches Symphonie-Orchester Berlin")
        headlinersFromTitle("Chanel Beads + urika's bedroom").map { it.name } shouldContainExactly
            listOf("Chanel Beads", "urika's bedroom")
    }

    // --- isNonArtistName slugless (#1553) ---

    @Test
    fun `isNonArtistName drops a name that slugs to nothing`() {
        isNonArtistName("-") shouldBe true
        isNonArtistName("--") shouldBe true
        isNonArtistName("...") shouldBe true
        isNonArtistName("?!") shouldBe true
        isNonArtistName("Mittelalter-Irish Folk") shouldBe false
    }

    // --- isNonArtistName bare number (#1556) ---

    @Test
    fun `isNonArtistName drops a digits-only name`() {
        isNonArtistName("2") shouldBe true
        isNonArtistName(" 2027 ") shouldBe true
        isNonArtistName("100 Kilo Herz") shouldBe false
        isNonArtistName("1-800-Mikey") shouldBe false
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
    fun `splitBackToBack splits a b2b slot and keeps a bracketed duo whole`() {
        splitBackToBack("Sicion b2b Iman Janes") shouldBe listOf("Sicion", "Iman Janes")
        splitBackToBack("Emira B2B Ayham") shouldBe listOf("Emira", "Ayham")
        splitBackToBack("Double Penetration (FLOWWW b2b Joe Cleen)") shouldBe listOf("Double Penetration (FLOWWW b2b Joe Cleen)")
        splitBackToBack("Ab2bc") shouldBe listOf("Ab2bc")
    }

    @Test
    fun `splitSegmentOnConjunctions splits guarded conjunctions but never a slash`() {
        splitSegmentOnConjunctions("Lichene & Neue K") shouldBe listOf("Lichene", "Neue K")
        // A "/" inside a single act name is preserved (not a co-bill separator here).
        splitSegmentOnConjunctions("Morimoto / Wong duo") shouldBe listOf("Morimoto / Wong duo")
        // Backing-band article tail stays joined.
        splitSegmentOnConjunctions("Scott Hepple & The Sun Band") shouldBe listOf("Scott Hepple & The Sun Band")
    }

    // #1556: Tempodrom's `Eiskönigin 1 & 2` was cut into `Eiskönigin 1` and `2`.
    @Test
    fun `splitSegmentOnConjunctions keeps a sequel or volume number on its show`() {
        splitSegmentOnConjunctions("Eiskönigin 1 & 2") shouldBe listOf("Eiskönigin 1 & 2")
        splitSegmentOnConjunctions("Vol. 1 und 2") shouldBe listOf("Vol. 1 und 2")
        splitSegmentOnConjunctions("Blink & 182 Tribute") shouldBe listOf("Blink & 182 Tribute")
        splitSegmentOnConjunctions("Lichene & Neue K") shouldBe listOf("Lichene", "Neue K")
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
    fun `headlinersFromTitle cuts a conjoined tour or prose tail before splitting`() {
        // Production minted `Fury Tour 2026`, `new songs` and `Mike Kraus live 2027` (#1842).
        headlinersFromTitle("Joe Jackson & Band - Hope and Fury Tour 2026").map { it.name } shouldBe listOf("Joe Jackson & Band")
        headlinersFromTitle("LYAPIS TRUBETSKOY - the best & new songs").map { it.name } shouldBe listOf("LYAPIS TRUBETSKOY")
        headlinersFromTitle("Peter Kraus & Mike Kraus live 2027").map { it.name } shouldBe listOf("Peter Kraus", "Mike Kraus")
        // A `mit` tail names the performers, so it is not cut as prose.
        headlinersFromTitle("Schund und Asche - mit Moritz Neumeier und Till Reiners").map { it.name } shouldContain "Till Reiners"
        // A conjoined tail that is neither a suffix nor prose can be a co-bill, and still splits.
        headlinersFromTitle("Night Series - Wendy Eisenberg & Mary Halvorson").map { it.name } shouldBe
            listOf("Night Series - Wendy Eisenberg", "Mary Halvorson")
    }

    // #1560: the marker comes off the title at persistence, after the acts were built from it.
    @Test
    fun `headlinersFromTitle bills the act, not the cancellation glued to it`() {
        headlinersFromTitle("ABSAGE: Green Lung") shouldBe listOf(ScrapedArtist("Green Lung", "HEADLINER", titleDerived = true))
        headlinersFromTitle("Olga Myko - Abgesagt") shouldBe listOf(ScrapedArtist("Olga Myko", "HEADLINER", titleDerived = true))
        headlinersFromTitle("Canceled: Modern English") shouldBe listOf(ScrapedArtist("Modern English", "HEADLINER", titleDerived = true))
    }

    @Test
    fun `headlinersFromTitle bills a labelled support act as SUPPORT and drops the label`() {
        headlinersFromTitle("Chelsea Wolfe + Support: A.A. Williams") shouldBe
            listOf(
                ScrapedArtist("Chelsea Wolfe", "HEADLINER", titleDerived = true),
                ScrapedArtist("A.A. Williams", "SUPPORT", titleDerived = true)
            )
        // An event-format lead-in is stripped without changing the role.
        headlinersFromTitle("RECORD RELEASE: PAIR + WESTHAFEN") shouldBe
            listOf(
                ScrapedArtist("PAIR", "HEADLINER", titleDerived = true),
                ScrapedArtist("WESTHAFEN", "HEADLINER", titleDerived = true)
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

    @Test
    fun `splitHeadlinerTitle reads a bullet bill whose every act carries an annotation`() {
        splitHeadlinerTitle("New Candys (IT, Fuzz Club) • BLKE (DE, Tonzonen)") shouldBe
            listOf("New Candys (IT, Fuzz Club)", "BLKE (DE, Tonzonen)")
        // Through the title path, where stripArtistSuffix takes each act's affiliation off (#1818).
        headlinersFromTitle("New Candys (IT, Fuzz Club) • BLKE (DE, Tonzonen)") shouldBe
            listOf(
                ScrapedArtist("New Candys", "HEADLINER", titleDerived = true),
                ScrapedArtist("BLKE", "HEADLINER", titleDerived = true)
            )
    }

    @Test
    fun `splitHeadlinerTitle keeps a night and its strapline whole across a bullet`() {
        // Eight of the nine top-level bullet titles on production are this shape, and none of them
        // annotates every segment. Splitting on the character alone bills the strapline.
        splitHeadlinerTitle("THE EARLY DAYS • THROWBACK INDIE PARTY") shouldBe
            listOf("THE EARLY DAYS • THROWBACK INDIE PARTY")
        // These two are cut at their conjunction, as they are on main — a separate defect, and the
        // point here is that neither is cut at its bullet.
        splitHeadlinerTitle("HEADLESS PARTY • The Home of Core & Alternative Rock") shouldBe
            listOf("HEADLESS PARTY • The Home of Core", "Alternative Rock")
        splitHeadlinerTitle("Call Me Maybe! • 2000s & 2010s – Pop Party") shouldBe
            listOf("Call Me Maybe! • 2000s", "2010s – Pop Party")
    }

    @Test
    fun `splitHeadlinerTitle needs the annotation on every segment, not on one`() {
        // The near miss: two segments are annotated and the third is not, so nothing splits.
        splitHeadlinerTitle("June Cocó • Berlin (Kulturhaus Insel) • EP Release Show") shouldBe
            listOf("June Cocó • Berlin (Kulturhaus Insel) • EP Release Show")
    }

    @Test
    fun `splitHeadlinerTitle never cuts at a bullet inside brackets`() {
        // Migas writes a record's label, year, running time and format in one parenthetical.
        splitHeadlinerTitle("Barker – Utility (Ostgut Ton, 2019 • 43 min • vinyl)") shouldBe
            listOf("Barker – Utility (Ostgut Ton, 2019 • 43 min • vinyl)")
    }

    // --- a description corroborating a single comma (#1832) ---

    @Test
    fun `headlinersFromTitle splits a one-comma bill the description names act by act`() {
        headlinersFromTitle(
            "D-Block Europe, French Montana",
            description = "D-Block Europe und French Montana machen mit ihrer gemeinsamen \"Worldwide Wave\"-Tour Halt in Berlin."
        ) shouldBe
            listOf(
                ScrapedArtist("D-Block Europe", "HEADLINER", titleDerived = true),
                ScrapedArtist("French Montana", "HEADLINER", titleDerived = true)
            )
    }

    @Test
    fun `headlinersFromTitle takes the fuller name the description spells out`() {
        // The whole point of reading the blurb: every act in this title is a short form, so even a
        // correct split of the title alone would mint three names no act carries.
        headlinersFromTitle(
            "Myki, Darlene & Nini",
            description =
                "Straight from the latest season of RuPaul's Drag Race, Myki Meeks, Darlene Mitchell, " +
                    "and Nini Coco bring three powerhouse energies and one unforgettable show."
        ).map { it.name } shouldBe listOf("Myki Meeks", "Darlene Mitchell", "Nini Coco")
    }

    @Test
    fun `headlinersFromTitle corroborates the comma segment and leaves the hard separator alone`() {
        headlinersFromTitle(
            "FLAME PARADE, ALICE GIFT + ANNA GROB",
            description =
                "Live:\nFLAME PARADE (Indie/Dreampop/Alternative, IT)\nALICE GIFT (Indie/Shoegaze/Glam, Berlin)\n" +
                    "ANNA GROB (Indie/Alternative/BaRock)"
        ).map { it.name } shouldBe listOf("FLAME PARADE", "ALICE GIFT", "ANNA GROB")
    }

    @Test
    fun `headlinersFromTitle keeps a name the description states in full`() {
        // The band writes its name with a comma and the blurb without one, and the apostrophes
        // differ; both have to fold away or a real act is split in two.
        headlinersFromTitle(
            "Yes, I\u2019m Very Tired Now",
            description = "Die f\u00fcnfk\u00f6pfige Band um Marc Frischknecht alias Yes I'm Very Tired Now l\u00e4dt zu einem Konzert ein."
        ).map { it.name } shouldBe listOf("Yes, I\u2019m Very Tired Now")
        headlinersFromTitle(
            "Kitty, Daisy & Lewis",
            description = "Kitty, Daisy & Lewis are a British band of siblings."
        ).map { it.name } shouldBe listOf("Kitty, Daisy & Lewis")
    }

    @Test
    fun `headlinersFromTitle keeps a title whose segments the description does not all name`() {
        headlinersFromTitle(
            "Hey, Nothing",
            description = "An evening of quiet songs at the Modus."
        ).map { it.name } shouldBe listOf("Hey, Nothing")
    }

    @Test
    fun `headlinersFromTitle changes nothing without a description`() {
        headlinersFromTitle("D-Block Europe, French Montana").map { it.name } shouldBe
            listOf("D-Block Europe, French Montana")
        headlinersFromTitle("Myki, Darlene & Nini").map { it.name } shouldBe listOf("Myki, Darlene & Nini")
    }

    @Test
    fun `headlinersFromTitle asks the description per segment, after the hard separators`() {
        // Madame Claude's blurb opens with the bill verbatim, so that segment is one act's name —
        // and the `+` acts after it are none of the comma's business.
        headlinersFromTitle(
            "Elshan Ghasimi, Carla Boregas & Joss Turnbull + Orca Eroticae",
            description =
                "Elshan Ghasimi, Carla Boregas & Joss Turnbull\nSoundart, classical iranien, percussion, " +
                    "objects and electronics / Berlin based"
        ).map { it.name } shouldBe listOf("Elshan Ghasimi, Carla Boregas & Joss Turnbull", "Orca Eroticae")
    }

    @Test
    fun `headlinersFromTitle keeps a pair the title and the description both join with a conjunction`() {
        // `GHOSTS & ERRORS` is one act, and only the blurb says so: the title's conjunction alone
        // would split it, and the comma would then look corroborated.
        headlinersFromTitle(
            "Parlour Magic, Fee Aviv und Ghosts & Errors",
            description = "PARLOUR MAGIC ... FEE AVIV ... GHOSTS & ERRORS \u201eTeenage Prose\u201c von GHOSTS & ERRORS ist intensiv."
        ).map { it.name } shouldBe listOf("Parlour Magic, Fee Aviv und Ghosts & Errors")
    }

    @Test
    fun `headlinersFromTitle does not let a description conjunction defeat a comma bill`() {
        // The mirror of the case above, and why both sides are required: the description joins the
        // two acts with `und` where the title separates them with a comma.
        headlinersFromTitle(
            "D-Block Europe, French Montana",
            description = "D-Block Europe und French Montana auf Tour."
        ).map { it.name } shouldBe listOf("D-Block Europe", "French Montana")
    }

    @Test
    fun `headlinersFromTitle never extends an act across a line break`() {
        headlinersFromTitle(
            "Myki, Darlene & Nini",
            description = "Myki Meeks, Darlene Mitchell, and Nini Coco\nSoundart And Percussion"
        ).map { it.name } shouldBe listOf("Myki Meeks", "Darlene Mitchell", "Nini Coco")
    }

    @Test
    fun `headlinersFromTitle never bills an act only the description names`() {
        headlinersFromTitle(
            "D-Block Europe, French Montana",
            description = "D-Block Europe und French Montana, mit einem Gastauftritt von Some Other Act."
        ).map { it.name } shouldBe listOf("D-Block Europe", "French Montana")
    }

    // --- stripArtistSuffix ---

    @Test
    fun `stripArtistSuffix drops a footnote star`() {
        // Production stored all of these as written (#1845).
        stripArtistSuffix("Sinkane live*") shouldBe "Sinkane"
        stripArtistSuffix("Sweely live*") shouldBe "Sweely"
        stripArtistSuffix("Jonny*") shouldBe "Jonny"
        // A star inside the name stays.
        stripArtistSuffix("*n8") shouldBe "*n8"
        stripArtistSuffix("Sean Steinfeger (OHSHITF*CKYES") shouldBe "Sean Steinfeger"
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
    fun `headlinersFromTitle reads a festival lead, a Vorprogramm and a clock range`() {
        headlinersFromTitle("Jazzfest Berlin – Wendy Eisenberg").map { it.name } shouldBe listOf("Wendy Eisenberg")
        headlinersFromTitle("KARAT „45 Jahre Der blaue Planet“ Vorprogramm: Dirk Michaelis") shouldBe
            listOf(ScrapedArtist("KARAT", "HEADLINER", titleDerived = true), ScrapedArtist("Dirk Michaelis", "SUPPORT", titleDerived = true))
        headlinersFromTitle("19-21Uhr") shouldBe emptyList()
        // The frame goes; a `with` billing stays one act, as everywhere else in a title.
        headlinersFromTitle("Celebrating Meat Loaf' - The Neverland Express with Andrew Polec").map { it.name } shouldBe
            listOf("The Neverland Express with Andrew Polec")
    }

    // #1905 — an anniversary title that quotes a name bills that name, and the rest is the occasion.
    @Test
    fun `headlinersFromTitle bills the act an anniversary title quotes`() {
        headlinersFromTitle("10 Jahre \"The Big Brassers\" – Jubiläumskonzert & Party") shouldBe
            listOf(ScrapedArtist("The Big Brassers", "HEADLINER", titleDerived = true))
        headlinersFromTitle("25 Years of „Die Ärzte“").map { it.name } shouldBe listOf("Die Ärzte")
        // Without quotes the name can be the venue's own, so the anniversary still bills nothing.
        headlinersFromTitle("22 JAHRE CLASH").shouldBeEmpty()
        headlinersFromTitle("10 Jahre \"Festival\"").shouldBeEmpty()
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
        result[0] shouldBe ScrapedArtist(name = "The Adicts", role = "HEADLINER", titleDerived = true)
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
        result[0] shouldBe ScrapedArtist(name = "The Adicts", role = "HEADLINER", titleDerived = true)
        result[1] shouldBe ScrapedArtist(name = "Maid of Ace", role = "SUPPORT")
    }

    @Test
    fun `buildArtistList with all placeholder supports returns only headliner`() {
        val result = buildArtistList("The Adicts", listOf("TBA", "TBD"))
        result shouldHaveSize 1
        result[0] shouldBe ScrapedArtist(name = "The Adicts", role = "HEADLINER", titleDerived = true)
    }

    @Test
    fun `buildArtistList drops a bare role-label support but keeps the headliner`() {
        // A "Support: Special Guest" line still signals the title-as-headliner
        // convention, but the label itself must not become a support artist.
        val result = buildArtistList("Green Lung", listOf("Special Guest"))
        result shouldContainExactly listOf(ScrapedArtist(name = "Green Lung", role = "HEADLINER", titleDerived = true))
    }

    @Test
    fun `buildArtistList splits a multi-artist title into co-headliners`() {
        val result = buildArtistList("TOTAL CHAOS + RUMKICKS", listOf("The Dollheads"))
        result shouldContainExactly
            listOf(
                ScrapedArtist(name = "TOTAL CHAOS", role = "HEADLINER", titleDerived = true),
                ScrapedArtist(name = "RUMKICKS", role = "HEADLINER", titleDerived = true),
                ScrapedArtist(name = "The Dollheads", role = "SUPPORT")
            )
    }

    // --- buildArtistsForEventType ---

    @Test
    fun `buildArtistsForEventType treats a concert title as the headliner without a support line`() {
        buildArtistsForEventType("Green Lung", subtitle = null, eventType = "CONCERT") shouldContainExactly
            listOf(ScrapedArtist(name = "Green Lung", role = "HEADLINER", titleDerived = true))
    }

    @Test
    fun `buildArtistsForEventType adds the subtitle's support acts after the headliners`() {
        buildArtistsForEventType("TOTAL CHAOS", subtitle = "+ Support: The Dollheads", eventType = "CONCERT") shouldContainExactly
            listOf(
                ScrapedArtist(name = "TOTAL CHAOS", role = "HEADLINER", titleDerived = true),
                ScrapedArtist(name = "The Dollheads", role = "SUPPORT")
            )
    }

    @Test
    fun `buildArtistsForEventType stays conservative for an unclassified event`() {
        // No type to confirm the title names an act, so only a support line unlocks it.
        buildArtistsForEventType("Vinyl Thursdays", subtitle = null, eventType = null).shouldBeEmpty()
        buildArtistsForEventType("Green Lung", subtitle = "Support: Kaos", eventType = "OTHER") shouldContainExactly
            listOf(
                ScrapedArtist(name = "Green Lung", role = "HEADLINER", titleDerived = true),
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
        // where unpacking would delete the headliner. The `presents:` frame comes off either way.
        headlinersFromTitle("Analogue Foundation presents: David August w/ MFO") shouldContainExactly
            listOf(ScrapedArtist(name = "David August w/ MFO", role = "HEADLINER", titleDerived = true))
    }

    @Test
    fun `headlinersFromTitle unpacks a w-slash billing into its acts when asked`() {
        // The tail is a lineup list, so a comma delimits acts there — unlike in a title.
        headlinersFromTitle("RIOT ON THE ISLAND w/ Them Spirals, Painted Lox's & AK In Control", unpackWithFrame = true) shouldContainExactly
            listOf(
                ScrapedArtist(name = "Them Spirals", role = "HEADLINER", titleDerived = true),
                ScrapedArtist(name = "Painted Lox's", role = "HEADLINER", titleDerived = true),
                ScrapedArtist(name = "AK In Control", role = "HEADLINER", titleDerived = true)
            )
        // The night's own name never becomes a performer.
        headlinersFromTitle("RIPPLES W/ AMINE K", unpackWithFrame = true) shouldContainExactly
            listOf(ScrapedArtist(name = "AMINE K", role = "HEADLINER", titleDerived = true))
    }

    @Test
    fun `headlinersFromTitle reads the German mit spelling of the frame when asked`() {
        // SO36's "SADTEMBER mit TAHA, JOHNBOY M.IKARUS": the night on the left, the acts on the right (#1132).
        headlinersFromTitle("SADTEMBER mit TAHA, JOHNBOY M.IKARUS", unpackWithFrame = true) shouldContainExactly
            listOf(
                ScrapedArtist(name = "TAHA", role = "HEADLINER", titleDerived = true),
                ScrapedArtist(name = "JOHNBOY M.IKARUS", role = "HEADLINER", titleDerived = true)
            )
        // Off by default, like `w/`.
        headlinersFromTitle("SADTEMBER mit TAHA, JOHNBOY M.IKARUS").map { it.name } shouldContainExactly
            listOf("SADTEMBER mit TAHA, JOHNBOY M.IKARUS")
    }

    @Test
    fun `headlinersFromTitle keeps an act billed with its own backing whole`() {
        // "mit Orchester" / "mit Band" is the act's backing, not a guest list; the act stays the
        // headliner, and the backing comes off the name so it lands on the act's own row (#1580).
        headlinersFromTitle("Lacrimosa mit Orchester", unpackWithFrame = true).map { it.name } shouldContainExactly
            listOf("Lacrimosa")
        headlinersFromTitle("Alexander Eder mit Band", unpackWithFrame = true).map { it.name } shouldContainExactly
            listOf("Alexander Eder")
        // The marker needs whitespace on both sides: a name containing the letters is untouched.
        headlinersFromTitle("Mitski", unpackWithFrame = true).map { it.name } shouldContainExactly listOf("Mitski")
    }

    @Test
    fun `headlinersFromTitle drops an unfinished-billing tail from a w-slash lineup`() {
        headlinersFromTitle("House of Rave w/ Maceo Plex, Mark Dekoda and many more", unpackWithFrame = true)
            .map { it.name } shouldContainExactly listOf("Maceo Plex", "Mark Dekoda")
    }

    @Test
    fun `headlinersFromTitle falls back to the whole title when a w-slash frame yields nothing`() {
        // "w/ TBA" leaves no usable act, so the title is parsed normally rather than yielding none,
        // and the placeholder tail comes off the act (#1843).
        headlinersFromTitle("Green Lung w/ TBA", unpackWithFrame = true)
            .map { it.name } shouldContainExactly listOf("Green Lung")
    }

    // --- splitHeadlinerTitle ---

    // The Portuguese and Italian `e` is a co-bill join; a title spelled out letter by letter is not (#1533).
    @Test
    fun `splitHeadlinerTitle splits on a Portuguese e between two names but not inside spelled-out letters`() {
        splitHeadlinerTitle("Cleiton Rasta E Victor Cena") shouldContainExactly listOf("Cleiton Rasta", "Victor Cena")
        splitHeadlinerTitle("KAT FRANKIE - B O D I E S") shouldContainExactly listOf("KAT FRANKIE - B O D I E S")
        // The spelled-out tail is the record, not part of the act, whatever the head's casing;
        // a dash tail of real words under a shouted head stays (`DZ - DEATHRAY`, one band).
        stripArtistSuffix("KAT FRANKIE - B O D I E S") shouldBe "KAT FRANKIE"
        stripArtistSuffix("DZ - DEATHRAY") shouldBe "DZ - DEATHRAY"
    }

    // Two commas make a list; one decides nothing, because a band name carries one as often as a
    // bill does (#1789).
    @Test
    fun `splitHeadlinerTitle reads two or more commas as a bill`() {
        splitHeadlinerTitle("HALF LIGHT \u2013 Abul Mogard, Marja de Sanctis, Rafael Anton Irisarri, Concepci\u00f3n Huerta") shouldContainExactly
            listOf("HALF LIGHT \u2013 Abul Mogard", "Marja de Sanctis", "Rafael Anton Irisarri", "Concepci\u00f3n Huerta")
        splitHeadlinerTitle("Amber Broos, DJ Sexstasy, Sektor69") shouldContainExactly
            listOf("Amber Broos", "DJ Sexstasy", "Sektor69")
    }

    @Test
    fun `splitHeadlinerTitle keeps a one-comma name whole, whichever it turns out to be`() {
        // Four real acts, each with exactly one comma. Nothing in the string says they are not bills.
        splitHeadlinerTitle("Hey, Nothing") shouldContainExactly listOf("Hey, Nothing")
        splitHeadlinerTitle("Kitty, Daisy & Lewis") shouldContainExactly listOf("Kitty, Daisy & Lewis")
        splitHeadlinerTitle("Wracaj, bociemno") shouldContainExactly listOf("Wracaj, bociemno")
        splitHeadlinerTitle("Yes, I\u2019m Very Tired Now") shouldContainExactly listOf("Yes, I\u2019m Very Tired Now")
        // And one that is a bill, kept whole for the same reason. The comma rule does not guess.
        splitHeadlinerTitle("D-Block Europe, French Montana") shouldContainExactly listOf("D-Block Europe, French Montana")
    }

    // A hard separator proves the title is a bill and still does not make its commas separators:
    // Neue Zukunft billed `Wracaj, bociemno` as the third act of a three-act night (#1789).
    @Test
    fun `splitHeadlinerTitle keeps a one-comma act whole inside a plus-separated bill`() {
        splitHeadlinerTitle("Sorry I'm Late + I hate the bouncers + Wracaj, bociemno") shouldContainExactly
            listOf("Sorry I'm Late", "I hate the bouncers", "Wracaj, bociemno")
    }

    // Both commas are parenthetical affiliations, so the title carries none that separates acts.
    @Test
    fun `splitHeadlinerTitle ignores commas inside brackets`() {
        // The bullet is the separator here and the commas are not: each act keeps its own
        // parenthetical, which stripArtistSuffix takes off afterwards (#1789, #1818).
        splitHeadlinerTitle("New Candys (It, Fuzz Club) \u2022 Blke (De, Tonzonen)") shouldContainExactly
            listOf("New Candys (It, Fuzz Club)", "Blke (De, Tonzonen)")
    }

    // A comma list still splits its segments the ordinary way afterwards.
    @Test
    fun `splitHeadlinerTitle splits a comma segment on its own separators`() {
        splitHeadlinerTitle("Georgy Gusev, Sven Helbig, Ivan Skanavi & Deutsches Kammerorchester Berlin") shouldContainExactly
            listOf("Georgy Gusev", "Sven Helbig", "Ivan Skanavi", "Deutsches Kammerorchester Berlin")
    }

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
    fun `headlinersFromTitle bills the act of a title that carries the presents marker itself`() {
        // `<X> presents: <act>` is the opposite billing and the common one — Gretchen alone has 20.
        // Such a title trivially starts with `<X>`, so the marker in the title must veto the rule
        // that reads it as the label's own night, and the act after the colon is what is billed.
        headlinersFromTitle("Analogue Foundation presents: David August", subtitle = "Analogue Foundation presents")
            .map { it.name } shouldContainExactly listOf("David August")
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
            listOf(ScrapedArtist(name = "Real Band", role = "HEADLINER", titleDerived = true))
        headlinersFromTitle("TBA").shouldBeEmpty()
    }

    @Test
    fun `headlinersFromTitle keeps a slashed act name intact when splitOnSlash is false`() {
        // The Madame Claude concert path: "/" belongs to a single act ("Morimoto / Wong duo"),
        // so it must not be torn into two headliners; the trailing "(DJ-Set)" is still stripped.
        headlinersFromTitle("Morimoto / Wong duo + Forrest Gimp (DJ-Set)", splitOnSlash = false) shouldContainExactly
            listOf(
                ScrapedArtist(name = "Morimoto / Wong duo", role = "HEADLINER", titleDerived = true),
                ScrapedArtist(name = "Forrest Gimp", role = "HEADLINER", titleDerived = true)
            )
    }

    @Test
    fun `headlinersFromTitle strips tour and live suffixes to recover the act`() {
        headlinersFromTitle("DOMINIUM - NIGHT IS CALLING TOUR 2026") shouldContainExactly
            listOf(ScrapedArtist(name = "DOMINIUM", role = "HEADLINER", titleDerived = true))
        headlinersFromTitle("HGICH.T LIVE") shouldContainExactly
            listOf(ScrapedArtist(name = "HGICH.T", role = "HEADLINER", titleDerived = true))
    }

    @Test
    fun `headlinersFromTitle strips an event-framing prefix to recover the act`() {
        headlinersFromTitle("A night with GULVØSS II") shouldContainExactly
            listOf(ScrapedArtist(name = "GULVØSS II", role = "HEADLINER", titleDerived = true))
        headlinersFromTitle("An Evening with Nick Cave") shouldContainExactly
            listOf(ScrapedArtist(name = "Nick Cave", role = "HEADLINER", titleDerived = true))
        // The framing phrase must be a leading whole prefix — a band with "night" mid-name is untouched.
        headlinersFromTitle("Last Night With You") shouldContainExactly
            listOf(ScrapedArtist(name = "Last Night With You", role = "HEADLINER", titleDerived = true))
    }

    @Test
    fun `headlinersFromTitle recovers a single act from an anniversary title`() {
        // The comma in the tail keeps the title unsplit; the suffix strip then recovers the band.
        headlinersFromTitle("THE BUTLERS - 40 YEARS, SKA & SOULPOWER -") shouldContainExactly
            listOf(ScrapedArtist(name = "THE BUTLERS", role = "HEADLINER", titleDerived = true))
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
                ScrapedArtist(name = "Blake Harley", role = "HEADLINER", titleDerived = true),
                ScrapedArtist(name = "Superior Motive", role = "HEADLINER", titleDerived = true)
            )
    }

    @Test
    fun `headlinersFromTitle keeps a name with a colon but no series edition marker`() {
        // No "#<n>:" marker, so nothing is stripped (guards a real "9:3"-style name).
        headlinersFromTitle("Bleech 9:3") shouldContainExactly
            listOf(ScrapedArtist(name = "Bleech 9:3", role = "HEADLINER", titleDerived = true))
    }

    // --- stripSeriesPrefix ---

    @Test
    fun `stripSeriesPrefix keeps the acts billed after the series label`() {
        stripSeriesPrefix("OFF THE RAILS #5: Blake Harley & Superior Motive") shouldBe "Blake Harley & Superior Motive"
        stripSeriesPrefix("Off the Rails #4: Some Act") shouldBe "Some Act"
        stripSeriesPrefix("Series # 12 : Act") shouldBe "Act"
    }

    @Test
    fun `stripSeriesPrefix leaves a title whose colon is not a series marker`() {
        stripSeriesPrefix("9:3") shouldBe "9:3"
        stripSeriesPrefix("H2:O") shouldBe "H2:O"
        stripSeriesPrefix("Just A Band") shouldBe "Just A Band"
    }

    @Test
    fun `stripSeriesPrefix returns the input when stripping would leave nothing`() {
        stripSeriesPrefix("OFF THE RAILS #5:") shouldBe "OFF THE RAILS #5:"
    }

    // #1580 — a superlative clause and two format words are not acts.
    @Test
    fun `headlinersFromTitle reads billing prose around a conjunction as one act with a tail`() {
        headlinersFromTitle("Lacrimosa mit Orchester - Einzige und Exklusive Orchester-Show in Europa!").map { it.name } shouldContainExactly
            listOf("Lacrimosa")
        headlinersFromTitle("POETRY & HIP HOP (KONZERT)").shouldBeEmpty()
        // A real co-bill beside a name that merely ends in `!` still splits — the guard is on the whole segment.
        splitHeadlinerTitle("Wham! + Culture Club") shouldContainExactly listOf("Wham!", "Culture Club")
        splitHeadlinerTitle("Panic! At the Disco & Fall Out Boy") shouldContainExactly listOf("Panic! At the Disco", "Fall Out Boy")
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

    // #1581 — `pres:` bills the act on whichever side is not the programme, and a series is not an act.
    @Test
    fun `headlinersFromTitle reads a pres marker by the shape of its right side`() {
        headlinersFromTitle("Burnt Friedman pres: Secret Rhythms").map { it.name } shouldContainExactly listOf("Burnt Friedman")
        headlinersFromTitle("hub pres. Doorman + Franco Franco").map { it.name } shouldContainExactly listOf("Doorman", "Franco Franco")
        headlinersFromTitle("Unguarded pres. Jungstötter + Blurrydog").map { it.name } shouldContainExactly listOf("Jungstötter", "Blurrydog")
    }

    @Test
    fun `a series billed under its own name yields no artist, whatever its edition marker`() {
        headlinersFromTitle("Berlin Beat Invasion No 8").shouldBeEmpty()
        headlinersFromTitle("Berlin Beat Invasion No. 9").shouldBeEmpty()
        headlinersFromTitle("Urban Spree KLUBNACHT 004").shouldBeEmpty()
        headlinersFromTitle("Methods of Dance II").shouldBeEmpty()
    }

    // #305 — a `feat.` guest mid-title is support, and the act keeps its year-ended night name off.
    @Test
    fun `headlinersFromTitle bills a featured guest as support and keeps the act`() {
        headlinersFromTitle("Stereoact: Ich liebe das Leben Party 2027 feat. Lena Marie Engel").map { it.name to it.role } shouldBe
            listOf("Stereoact" to "HEADLINER", "Lena Marie Engel" to "SUPPORT")
        headlinersFromTitle("Kraftklub feat. Tokio Hotel & Casper").map { it.name to it.role } shouldBe
            listOf("Kraftklub" to "HEADLINER", "Tokio Hotel" to "SUPPORT", "Casper" to "SUPPORT")
    }

    // #314 — an origin tag comes off; a parenthesised alias stays.
    @Test
    fun `stripArtistSuffix drops an origin tag in codes or in full and keeps an alias`() {
        stripArtistSuffix("Ipkiss (NL)") shouldBe "Ipkiss"
        stripArtistSuffix("Marta Warelis (PL/USA)") shouldBe "Marta Warelis"
        stripArtistSuffix("NIGHT NAIL (Dark Wave US/DE)") shouldBe "NIGHT NAIL"
        stripArtistSuffix("Apichat Pakwan (Thailand-Live)") shouldBe "Apichat Pakwan"
        stripArtistSuffix("Sickboyrari (Black Kray)") shouldBe "Sickboyrari (Black Kray)"
    }

    // #1561 — a band affiliation is a comma list or an `ex-` opener; a single bare name may be an alias.
    @Test
    fun `stripArtistSuffix drops a band affiliation and keeps a single parenthesised name`() {
        stripArtistSuffix("Colin Newman (WIRE, IMMERSION)") shouldBe "Colin Newman"
        stripArtistSuffix("Budgie (SIOUXSIE & THE BANSHEES, THE SLITS)") shouldBe "Budgie"
        stripArtistSuffix("Alexander Hacke (ex-EINSTÜRZENDE NEUBAUTEN, HACKEDEPICCIOTTO)") shouldBe "Alexander Hacke"
        stripArtistSuffix("Pauline Murray (PENETRATION)") shouldBe "Pauline Murray (PENETRATION)"
    }

    // #1761 — the bracket shapes staging stored on upcoming events, and the brackets that are the name.
    @Test
    fun `stripArtistSuffix drops an annotation bracket and keeps a bracket that is the name`() {
        stripArtistSuffix("Flow Rea (Est)") shouldBe "Flow Rea"
        stripArtistSuffix("goat (jp)") shouldBe "goat"
        stripArtistSuffix("David J (Bauhaus / Love & Rockets)") shouldBe "David J"
        stripArtistSuffix("Steve Norman (von Spandau Ballet) & The Sleevz") shouldBe "Steve Norman & The Sleevz"
        stripArtistSuffix("Paula Paula (Zusatzshow)") shouldBe "Paula Paula"
        stripArtistSuffix("DJ Hell (Vinyl Set)") shouldBe "DJ Hell"
        stripArtistSuffix("Sean Steinfeger (OHSHITF*CKYES") shouldBe "Sean Steinfeger"

        stripArtistSuffix("Maynd (Chi)") shouldBe "Maynd (Chi)"
        stripArtistSuffix("Luca Saporito (Audiofly)") shouldBe "Luca Saporito (Audiofly)"
        stripArtistSuffix("SiSi (2)") shouldBe "SiSi (2)"
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

    // #339 — the DJ a night is named for, when the venue publishes no line-up.
    @Test
    fun `hostedActsFromTitle reads the acts after a curated-by, hosted-by or by-person marker`() {
        hostedActsFromTitle("FOREVER 25 curated by Mila Stern & Esther Silex").map { it.name } shouldBe listOf("Mila Stern", "Esther Silex")
        hostedActsFromTitle("Tresor New Faces hosted by Secret Keywords", role = "DJ").map { it.name to it.role } shouldBe
            listOf("Secret Keywords" to "DJ")
        hostedActsFromTitle("Antina's Spookhouse by Antina Christ").map { it.name } shouldBe listOf("Antina Christ")
        // A bare `by` needs a person-shaped tail; a night name or a lowercase phrase is not one.
        hostedActsFromTitle("Stand By Me").shouldBeEmpty()
        hostedActsFromTitle("Klubnacht by night").shouldBeEmpty()
        hostedActsFromTitle("FOREVER 25 curated by TBA").shouldBeEmpty()
    }

    // #315 — a variety or comedy house bills a solo act as `<Performer> – <Show>`.
    @Test
    fun `buildArtistsForEventType reads a solo bill off a show title and leaves a production alone`() {
        buildArtistsForEventType("Bülent Ceylan - \"Diktatürk\"", null, "SHOW").map { it.name } shouldBe listOf("Bülent Ceylan")
        buildArtistsForEventType("Torsten Sträter: Schnelle Nummer", null, "SHOW").map { it.name } shouldBe listOf("Torsten Sträter")
        buildArtistsForEventType("DIE KLIMA-MONOLOGE", null, "SHOW").shouldBeEmpty()
        buildArtistsForEventType("Berlin Burlesque Festival - Gala", null, "SHOW").shouldBeEmpty()
        buildArtistsForEventType("FOTZENSCHLEIMPOWER GEGEN RAUBTIER – Ein Abend", null, "SHOW").shouldBeEmpty()
        // A `Support:` line still wins, and a concert keeps its own path.
        buildArtistsForEventType("Some Act - Summer Tour", "Support: Other Act", "SHOW").map { it.name } shouldBe listOf("Some Act", "Other Act")
    }

    // Provenance for #1145: only what was read off the title carries the flag.
    @Test
    fun `headlinersFromTitle marks every act it reads off the title as title-derived`() {
        headlinersFromTitle("The Adicts + Maid of Ace").map { it.titleDerived } shouldBe listOf(true, true)
        headlinersFromTitle("House of Rave w/ Maceo Plex", unpackWithFrame = true).map { it.titleDerived } shouldBe listOf(true)
    }

    @Test
    fun `buildArtistList flags the title half and leaves the support acts alone`() {
        val result = buildArtistList("The Adicts", listOf("Maid of Ace", "Kaos"))
        result.map { it.name to it.titleDerived } shouldBe listOf("The Adicts" to true, "Maid of Ace" to false, "Kaos" to false)
        ScrapedArtist("Die Nerven").titleDerived shouldBe false
    }
}
