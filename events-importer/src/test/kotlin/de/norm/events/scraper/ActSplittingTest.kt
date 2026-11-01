package de.norm.events.scraper

import de.norm.events.event.ArtistRole
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// Focused tests for ActSplitting — one test per behaviour.
class ActSplittingTest {
    @Test
    fun `splitSupportActs drops a role label from each act, and keeps a band named after one`() {
        // Metropol prints its support line as `Support: THE BAXBYS` (#1678).
        splitSupportActs("Support: THE BAXBYS") shouldContainExactly listOf("THE BAXBYS")
        splitSupportActs("Support: Silent Planet + Opener: Boyish") shouldContainExactly listOf("Silent Planet", "Boyish")
        // The colon is what makes the strip safe: this band keeps its name.
        splitSupportActs("Support Lesbiens") shouldContainExactly listOf("Support Lesbiens")
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

    @Test
    fun `splitSupportActs bills a mid-name featured guest as an act of its own`() {
        splitSupportActs("Bad Sound System feat. Fermin Muguruza") shouldContainExactly listOf("Bad Sound System", "Fermin Muguruza")
        splitSupportActs("Bîdar featuring Fem Jam Collective, Aska") shouldContainExactly listOf("Bîdar", "Fem Jam Collective", "Aska")
        splitSupportActs("Featurecast") shouldContainExactly listOf("Featurecast")
        splitSupportActs("Little Feat & Friends") shouldContainExactly listOf("Little Feat & Friends")
        // A guest in a bracket is splitBracketedGuest's, which splits it once.
        splitSupportActs("Dosenstolz (feat. Tancred)") shouldContainExactly listOf("Dosenstolz (feat. Tancred)")
    }

    // --- splitSegmentOnConjunctions ---

    @Test
    fun `splitBackToBack splits a b2b slot and keeps a bracketed duo whole`() {
        splitBackToBack("Sicion b2b Iman Janes") shouldBe listOf("Sicion", "Iman Janes")
        splitBackToBack("Double Penetration (FLOWWW b2b Joe Cleen)") shouldBe listOf("Double Penetration (FLOWWW b2b Joe Cleen)")
        splitBackToBack("Ab2bc") shouldBe listOf("Ab2bc")
        splitBackToBack("b2b") shouldBe listOf("b2b")
    }

    // #2966: every joined act production stored on 2026-10-09.
    @Test
    fun `splitBackToBack splits every joined act production stored`() {
        val joined =
            mapOf(
                "Jens Schwan b2b Simon Berlin" to listOf("Jens Schwan", "Simon Berlin"),
                "DJ Doorkeeper b2b Extravaganza" to listOf("DJ Doorkeeper", "Extravaganza"),
                "alllone  b2b Karakara (GreyNote, Impulse Basskultur, hng.picnics /D)" to listOf("alllone", "Karakara"),
                "Allynx b2b Sean Steinfeger" to listOf("Allynx", "Sean Steinfeger"),
                "Survey  b2b Phonomat (Invisible, Recycle/D)" to listOf("Survey", "Phonomat"),
                "Slimzee  b2b Grandmixxer (Tower Block, Rinse, SLSA/UK)" to listOf("Slimzee", "Grandmixxer"),
                "Dash B2B Lennart" to listOf("Dash", "Lennart"),
                "Sibil B2B Tau Car" to listOf("Sibil", "Tau Car"),
                "Franz Scala B2B Giulia Gutterer" to listOf("Franz Scala", "Giulia Gutterer"),
                "Woody92 B2B Floid" to listOf("Woody92", "Floid"),
                "Emira B2B Ayham" to listOf("Emira", "Ayham"),
                "Vaccaro B2B The Camel" to listOf("Vaccaro", "The Camel"),
                "Anaté b2b Shira Kela" to listOf("Anaté", "Shira Kela"),
                "Artness B2B Buyooo" to listOf("Artness", "Buyooo")
            )
        assertSoftly {
            joined.forEach { (line, acts) -> withClue(line) { splitBackToBack(line) shouldBe acts } }
        }
    }

    @Test
    fun `splitBackToBack drops a marker with no DJ on one side`() {
        splitBackToBack("B2B Alina Matini") shouldBe listOf("Alina Matini")
        splitBackToBack("Michael Lane b2b") shouldBe listOf("Michael Lane")
        splitBackToBack("Michael Lane\u00A0b2b") shouldBe listOf("Michael Lane")
    }

    @Test
    fun `splitBackToBack keeps a bracket that belongs to one DJ`() {
        splitBackToBack("DJ TC (Blactro) b2b DJ Gus") shouldBe listOf("DJ TC (Blactro)", "DJ Gus")
        splitBackToBack("Makam b2b V. (OPERA live)") shouldBe listOf("Makam", "V. (OPERA live)")
        splitBackToBack("A b2b B b2b C") shouldBe listOf("A", "B", "C")
        splitBackToBack("Ohm (b2b set)") shouldBe listOf("Ohm (b2b set)")
    }

    // #3038: Der Weiße Hase's `Tanzen Hilft` line, as the venue billed it.
    @Test
    fun `splitOnEmoji splits an emoji-separated roster and keeps an ampersand name whole`() {
        val line =
            "🔥 Kopf & Hörer 🔥 🎤 Dr. Sheppat ⚡ EVOX ⚡ 🎶 Alice Rough 🎶 Kodex 🎶 Nico Brix 🎶 Emma (1) 🎶 kies 🎶 Kimstar 🎶 D!Hustler " +
                "🎶 Chris Benzin 🎶 Epuls 🎶 JP Dixen 🎶 Tombish & Horn 🎶 Morris Fitch 🎶 Minimalist 🎶 Mayze 🎶 Dr. Q"
        splitOnEmoji(line) shouldContainExactly
            listOf(
                "Kopf & Hörer",
                "Dr. Sheppat",
                "EVOX",
                "Alice Rough",
                "Kodex",
                "Nico Brix",
                "Emma (1)",
                "kies",
                "Kimstar",
                "D!Hustler",
                "Chris Benzin",
                "Epuls",
                "JP Dixen",
                "Tombish & Horn",
                "Morris Fitch",
                "Minimalist",
                "Mayze",
                "Dr. Q"
            )
    }

    @Test
    fun `splitOnEmoji keeps a name with no emoji or a wrapping emoji whole`() {
        splitOnEmoji("Drauf & Dran DJ Team") shouldBe listOf("Drauf & Dran DJ Team")
        splitOnEmoji("🔥 One Name 🔥") shouldBe listOf("One Name")
        splitOnEmoji("🔥🔥") shouldBe listOf("🔥🔥")
    }

    @Test
    fun `splitOnEmoji takes a variation selector and a zero-width joiner as part of the run`() {
        splitOnEmoji("Alpha ⚡️ Beta 👩‍🎤 Gamma") shouldBe listOf("Alpha", "Beta", "Gamma")
    }

    @Test
    fun `splitOnEmoji keeps each act on the slot's role and stage`() {
        val slot = ScrapedArtist(name = "A 🎶 B", role = ArtistRole.DJ, stage = "Floor")
        splitOnEmoji(slot) shouldBe listOf(slot.copy(name = "A"), slot.copy(name = "B"))
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
    // Found by ActSplittingPropertyTest: the last act kept its trailing space, and a trailing `und ` billed an empty act.
    @Test
    fun `splitSegmentOnConjunctions trims each act and bills no empty one`() {
        splitSegmentOnConjunctions("Abc Def & Ghi Jkl ") shouldBe listOf("Abc Def", "Ghi Jkl")
        splitSegmentOnConjunctions("Qg Isxmwci und ") shouldBe listOf("Qg Isxmwci")
        splitSegmentOnConjunctions("Abc Def ") shouldBe listOf("Abc Def ")
    }

    @Test
    fun `splitSegmentOnConjunctions keeps a sequel or volume number on its show`() {
        splitSegmentOnConjunctions("Eiskönigin 1 & 2") shouldBe listOf("Eiskönigin 1 & 2")
        splitSegmentOnConjunctions("Vol. 1 und 2") shouldBe listOf("Vol. 1 und 2")
        splitSegmentOnConjunctions("Blink & 182 Tribute") shouldBe listOf("Blink & 182 Tribute")
        splitSegmentOnConjunctions("Lichene & Neue K") shouldBe listOf("Lichene", "Neue K")
    }

    @Test
    fun `splitSegmentOnConjunctions keeps a pinned duo whole in both conjunction forms`() {
        // BKA Theater (#3584) and Morphine Raum (#3565).
        assertSoftly {
            listOf("Simon & Jan", "Jacky & Mahoni", "Evers & Eichhorn", "Mitch & Mitch").forEach { duo ->
                splitSegmentOnConjunctions(duo) shouldBe listOf(duo)
                val und = duo.replace(" & ", " und ")
                splitSegmentOnConjunctions(und) shouldBe listOf(und)
            }
        }
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
                ScrapedArtist("New Candys", ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist("BLKE", ArtistRole.HEADLINER, titleDerived = true)
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
    fun `splitHeadlinerTitle splits a one-comma list that ends in a conjunction into its acts`() {
        // The three stored list rows of #3419.
        splitHeadlinerTitle("Sarah Ninon, The Brahma & Jelle from the Block") shouldContainExactly
            listOf("Sarah Ninon", "The Brahma", "Jelle from the Block")
        splitHeadlinerTitle("Emil, Jorin Hagen & Jhnwndt") shouldContainExactly listOf("Emil", "Jorin Hagen", "Jhnwndt")
        splitHeadlinerTitle("Müge, Ecrin Hazer & Sianza") shouldContainExactly listOf("Müge", "Ecrin Hazer", "Sianza")
        headlinersFromTitle("Myki, Darlene & Nini").map { it.name } shouldBe listOf("Myki", "Darlene", "Nini")
    }

    @Test
    fun `splitHeadlinerTitle keeps a band named as a comma list, and a tail marker after the conjunction`() {
        splitHeadlinerTitle("Kitty, Daisy & Lewis") shouldContainExactly listOf("Kitty, Daisy & Lewis")
        splitHeadlinerTitle("Frost, Pisse & Elend") shouldContainExactly listOf("Frost, Pisse & Elend")
        splitHeadlinerTitle("Andy, Kamran & Hooman") shouldContainExactly listOf("Andy", "Kamran & Hooman")
        splitHeadlinerTitle("Earth, Wind & Fire") shouldContainExactly listOf("Earth, Wind & Fire")
        splitHeadlinerTitle("Ana, Nick Cave & The Bad Seeds") shouldContainExactly listOf("Ana", "Nick Cave & The Bad Seeds")
        // No comma, no list: `A & B` keeps the per-boundary rules.
        splitHeadlinerTitle("High On Fire & Gnome") shouldContainExactly listOf("High On Fire", "Gnome")
        splitHeadlinerTitle("Stella & The Longos") shouldContainExactly listOf("Stella & The Longos")
    }

    @Test
    fun `commaListActs reads only a comma list that ends in a conjunction`() {
        commaListActs("Sarah Ninon, The Brahma & Jelle from the Block") shouldBe listOf("Sarah Ninon", "The Brahma", "Jelle from the Block")
        commaListActs("Kitty, Daisy & Lewis") shouldBe listOf("Kitty, Daisy & Lewis")
        commaListActs("CRIES, Kallaloo, Vio PRG") shouldBe null
        commaListActs("juan and only") shouldBe null
    }

    @Test
    fun `splitHeadlinerTitle keeps a one-comma name whole, whichever it turns out to be`() {
        // Real acts, each with exactly one comma and no conjunction. Nothing in the string says they are not bills.
        splitHeadlinerTitle("Hey, Nothing") shouldContainExactly listOf("Hey, Nothing")
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
        // Huxleys bills the duo as its whole title (#2063).
        headlinersFromTitle("HAUTE & FREDDY").map { it.name } shouldContainExactly listOf("HAUTE & FREDDY")
        // Monarch bills the duo as its whole title (#2952).
        headlinersFromTitle("FERRIS & SYLVESTER").map { it.name } shouldContainExactly listOf("FERRIS & SYLVESTER")
        // Neue Zukunft bills the band as the support after a `+` (#2958).
        headlinersFromTitle("Dazzling Killmen + Prophet & Flesh").map { it.name } shouldContainExactly
            listOf("Dazzling Killmen", "Prophet & Flesh")
        // SO36 bills the punk band as its whole title (#2414); the pin folds case, umlaut included.
        splitHeadlinerTitle("Pöbel & Gesocks") shouldContainExactly listOf("Pöbel & Gesocks")
        splitHeadlinerTitle("PÖBEL & GESOCKS") shouldContainExactly listOf("PÖBEL & GESOCKS")
        // "X & the Ys" band-name tail, in both & and "and" forms.
        splitHeadlinerTitle("Nick Cave & the Bad Seeds") shouldContainExactly listOf("Nick Cave & the Bad Seeds")
        splitHeadlinerTitle("James and the Cold Gun") shouldContainExactly listOf("James and the Cold Gun")
        // A bare "and" inside a single word must not be split (space-padding).
        splitHeadlinerTitle("Portland") shouldContainExactly listOf("Portland")
        // A member-list band name is a known act.
        splitHeadlinerTitle("Earth, Wind & Fire") shouldContainExactly listOf("Earth, Wind & Fire")
        // "& Friends" / "& Guests" / "& Band" collective tail names an unnamed cast, not a second act.
        splitHeadlinerTitle("Taylor & Friends") shouldContainExactly listOf("Taylor & Friends")
        splitHeadlinerTitle("Jonny & Guests") shouldContainExactly listOf("Jonny & Guests")
        splitHeadlinerTitle("Andreas Dresen & Band") shouldContainExactly listOf("Andreas Dresen & Band")
        // A real co-bill alongside a collective tail still splits at the real boundary.
        splitHeadlinerTitle("Ann & the Band + Real Act") shouldContainExactly listOf("Ann & the Band", "Real Act")
        // A named act after "&" ("Jesko Band") is still a genuine second act, not a bare "& Band" tail.
        splitHeadlinerTitle("Dennis & Jessie Band") shouldContainExactly listOf("Dennis", "Jessie Band")
    }

    @Test
    fun `splitHeadlinerTitle leaves a meets title whole when it pairs genres, names a series or carries a tagline`() {
        assertSoftly {
            listOf(
                // A genre on either side is a style pairing.
                "Klassik meets Jazz",
                "Alternative Rock meets Metal",
                "SOUL MEETS R&B",
                "Modern Jazz Meets African Groove",
                "80s meets Antidepressiva",
                // A one-word left side is the venue, a series or a show.
                "Tresor meets DJ Stingray 313",
                "FALCO MEETS AMADEUS",
                "Rainbowbingo meets Wildbingo",
                "Downstairs meets Music",
                // A spaced dash marks the meets phrase as a tagline.
                "Ballett meets Varieté - Premiere",
                "Hogir Göregen - Piano meets Percussion",
                // A dangling or a repeated meets.
                "Two Words meets",
                "Two Words meets Ab meets Cd",
                // Inside a bracket it describes the act before it.
                "Gomez Band (Roots Daughters meets La Comadre)"
            ).forEach { title -> withClue(title) { splitHeadlinerTitle(title) shouldContainExactly listOf(title) } }
        }
        // `meets` inside a word is no separator.
        splitHeadlinerTitle("Gomez Meetsville") shouldContainExactly listOf("Gomez Meetsville")
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

    @Test
    fun `a duo that releases under its x join stays one act`() {
        splitCrossBilled("NOAH X PETTER") shouldBe listOf("NOAH X PETTER")
        splitCrossBilled("Marthe X Pilani Bubu") shouldBe listOf("Marthe", "Pilani Bubu")
        splitSegmentOnConjunctions("Fhionn x Cathal") shouldBe listOf("Fhionn", "Cathal")
        splitCrossBilled("Los Refrescos (A x B)") shouldBe listOf("Los Refrescos (A x B)")
    }
}
