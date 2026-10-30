package de.norm.events.scraper

import de.norm.events.event.ArtistRole
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// Focused tests for ArtistNameMapping — one test per behaviour.
@Suppress("LargeClass")
class ArtistNameMappingTest {
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
    fun `a presents frame reads the prsts contraction and a night name before the colon`() {
        // Schokoladen contracts the verb, LARK names the night between the verb and the colon (#3213).
        // The Schokoladen title arrives without its origin annotations, which its scraper strips first.
        headlinersFromTitle("M:Soundtrack prsts: Dark Selbo Release Show + CHEEKY").map { it.name } shouldContainExactly
            listOf("Dark Selbo", "CHEEKY")
        headlinersFromTitle("old & fast prsnts: Pisse").map { it.name } shouldContainExactly listOf("Pisse")
        headlinersFromTitle("Projektbau presents TOSKA: dj poolboi, DJ Cinema Quartier Latin, Projektbau").map { it.name } shouldContainExactly
            listOf("dj poolboi", "DJ Cinema Quartier Latin", "Projektbau")
        headlinersFromTitle("Trinity presents: SKELER - Nightfall World Tour").map { it.name } shouldContainExactly listOf("SKELER")
        // Without a colon the verb stays ambiguous: an act presenting its work, a band named for the word.
        headlinersFromTitle("Robert Henke presents Dust").map { it.name } shouldContainExactly
            listOf("Robert Henke presents Dust")
        headlinersFromTitle("The Wedding Present").map { it.name } shouldContainExactly listOf("The Wedding Present")
        // A night of more than three words is prose, not a name: the frame does not read it.
        headlinersFromTitle("Projektbau presents a very long night out: dj poolboi").map { it.name } shouldNotContain "dj poolboi"
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

    // #2851 — a programme names its edition or occasion. Real titles: Kesselhaus, plus two stored at other venues.
    @Test
    fun `headlinersFromTitle reads a numbered edition, a volume, a tribute concert and a singen compound as no act`() {
        listOf(
            "41. Berliner Jazztreff",
            "30. Traditioneller Neujahrs-Blues",
            "7. Berliner Demokratietag",
            "Anime Rap Revolution Vol. I",
            "Unholy Alliance Vol. VII",
            "Tribute concert: In memory of Lemmy ",
            "Weihnachtssingen im Kiez",
            "Adventssingen"
        ).forEach { headlinersFromTitle(it).shouldBeEmpty() }
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

    @Test
    fun `a conjoined guest tail stays with its act`() {
        headlinersFromTitle("Michael Mittermeier & Gäste").map { it.name } shouldBe listOf("Michael Mittermeier & Gäste")
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
        headlinersFromTitle("Joe Jackson & Band - Hope and Fury Tour 2026").map { it.name } shouldBe listOf("Joe Jackson")
        headlinersFromTitle("LYAPIS TRUBETSKOY - the best & new songs").map { it.name } shouldBe listOf("LYAPIS TRUBETSKOY")
        headlinersFromTitle("Peter Kraus & Mike Kraus live 2027").map { it.name } shouldBe listOf("Peter Kraus", "Mike Kraus")
        // A `mit` tail names the performers, so it is not cut as prose.
        headlinersFromTitle("Schund und Asche - mit Moritz Neumeier und Till Reiners").map { it.name } shouldContain "Till Reiners"
        // A conjoined tail that is neither a suffix nor prose can be a co-bill, and still splits.
        headlinersFromTitle("Night Series - Wendy Eisenberg & Mary Halvorson").map { it.name } shouldBe
            listOf("Night Series - Wendy Eisenberg", "Mary Halvorson")
    }

    // #3637: Lido's band billed with two of its members. The split fused the band with the first member,
    // and the operator's rename of that fused artist hid the member from the line-up.
    @Test
    fun `headlinersFromTitle bills the head and each act of a co-billed dash tail`() {
        headlinersFromTitle("BAD COMPANY LEGACY – Dave Colwell & Robert Hart").map { it.name } shouldBe
            listOf("BAD COMPANY LEGACY", "Dave Colwell", "Robert Hart")
        headlinersFromTitle("RONNIE ROMERO & GUS G").map { it.name } shouldBe listOf("RONNIE ROMERO", "GUS G")
        // Only a shouted head billed with mixed-case names: a genre, a show or a shouted tail keeps the title as it was.
        headlinersFromTitle("FLEEZE - FLIRT RECORDS & BREEZE").map { it.name } shouldNotContain "FLEEZE"
        headlinersFromTitle("DJ Kassettendeck – Soul & RnB").map { it.name } shouldNotContain "DJ Kassettendeck"
        headlinersFromTitle("REPLAY – 2000s & 2010s Party").map { it.name } shouldNotContain "REPLAY"
        headlinersFromTitle("LIEFERS + BROICHER - LIVE: Große und kleine Fragen").map { it.name } shouldNotContain "BROICHER"
        headlinersFromTitle("REVEREND HARDY HARDON & THE CHURCH OF ELVIS").map { it.name } shouldBe
            listOf("REVEREND HARDY HARDON & THE CHURCH OF ELVIS")
        headlinersFromTitle("Reverend Hardy Hardon & The Church of Elvis").map { it.name } shouldBe
            listOf("Reverend Hardy Hardon & The Church of Elvis")
    }

    // #1560: the marker comes off the title at persistence, after the acts were built from it.
    @Test
    fun `headlinersFromTitle bills the act, not the cancellation glued to it`() {
        headlinersFromTitle("ABSAGE: Green Lung") shouldBe listOf(ScrapedArtist("Green Lung", ArtistRole.HEADLINER, titleDerived = true))
        headlinersFromTitle("Olga Myko - Abgesagt") shouldBe listOf(ScrapedArtist("Olga Myko", ArtistRole.HEADLINER, titleDerived = true))
        headlinersFromTitle("Canceled: Modern English") shouldBe listOf(ScrapedArtist("Modern English", ArtistRole.HEADLINER, titleDerived = true))
        headlinersFromTitle("----ABGESAGT----- Jeff Lorber Fusion") shouldBe
            listOf(ScrapedArtist("Jeff Lorber Fusion", ArtistRole.HEADLINER, titleDerived = true))
    }

    @Test
    fun `headlinersFromTitle bills a labelled support act as SUPPORT and drops the label`() {
        headlinersFromTitle("Chelsea Wolfe + Support: A.A. Williams") shouldBe
            listOf(
                ScrapedArtist("Chelsea Wolfe", ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist("A.A. Williams", ArtistRole.SUPPORT, titleDerived = true)
            )
        // An event-format lead-in is stripped without changing the role.
        headlinersFromTitle("RECORD RELEASE: PAIR + WESTHAFEN") shouldBe
            listOf(
                ScrapedArtist("PAIR", ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist("WESTHAFEN", ArtistRole.HEADLINER, titleDerived = true)
            )
    }

    // --- a description corroborating a single comma (#1832) ---

    @Test
    fun `headlinersFromTitle splits a one-comma bill the description names act by act`() {
        headlinersFromTitle(
            "D-Block Europe, French Montana",
            description = "D-Block Europe und French Montana machen mit ihrer gemeinsamen \"Worldwide Wave\"-Tour Halt in Berlin."
        ) shouldBe
            listOf(
                ScrapedArtist("D-Block Europe", ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist("French Montana", ArtistRole.HEADLINER, titleDerived = true)
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

    @Test
    fun `headlinersFromTitle reads a festival lead, a Vorprogramm and a clock range`() {
        headlinersFromTitle("Jazzfest Berlin – Wendy Eisenberg").map { it.name } shouldBe listOf("Wendy Eisenberg")
        headlinersFromTitle("KARAT „45 Jahre Der blaue Planet“ Vorprogramm: Dirk Michaelis") shouldBe
            listOf(ScrapedArtist("KARAT", ArtistRole.HEADLINER, titleDerived = true), ScrapedArtist("Dirk Michaelis", ArtistRole.SUPPORT, titleDerived = true))
        headlinersFromTitle("19-21Uhr") shouldBe emptyList()
        // The frame goes; a `with` billing stays one act, as everywhere else in a title.
        headlinersFromTitle("Celebrating Meat Loaf' - The Neverland Express with Andrew Polec").map { it.name } shouldBe
            listOf("The Neverland Express with Andrew Polec")
    }

    // #1905 — an anniversary title that quotes a name bills that name, and the rest is the occasion.
    @Test
    fun `headlinersFromTitle bills the act an anniversary title quotes`() {
        headlinersFromTitle("10 Jahre \"The Big Brassers\" – Jubiläumskonzert & Party") shouldBe
            listOf(ScrapedArtist("The Big Brassers", ArtistRole.HEADLINER, titleDerived = true))
        headlinersFromTitle("25 Years of „Die Ärzte“").map { it.name } shouldBe listOf("Die Ärzte")
        // Without quotes the name can be the venue's own, so the anniversary still bills nothing.
        headlinersFromTitle("22 JAHRE CLASH").shouldBeEmpty()
        headlinersFromTitle("10 Jahre \"Festival\"").shouldBeEmpty()
    }

    // #2882 — the live spellings: Quasimodo, Admiralspalast, Kesselhaus.
    @Test
    fun `headlinersFromTitle bills a tribute act without its descriptor`() {
        mapOf(
            "Always Amy – A tribute to Amy Winehouse" to "Always Amy",
            "THREE SIDES – A Tribute to Genesis" to "THREE SIDES",
            "The AC/DCs – Tribute Band" to "The AC/DCs",
            "My'tallica Tribute Band" to "My'tallica"
        ).forEach { (title, act) -> headlinersFromTitle(title).map { it.name } shouldBe listOf(act) }
    }

    @Test
    fun `a show name in straight quotes after a dash is cut before the title is split`() {
        // Säälchen (#3622): the `+` inside the quotes split the show into two acts.
        assertSoftly {
            headlinersFromTitle("SPIELHAGEN - \"HARMONY + ECSTASY\"").map { it.name } shouldBe listOf("SPIELHAGEN")
            headlinersFromTitle("SPIELHAGEN – \"HARMONY + ECSTASY\"").map { it.name } shouldBe listOf("SPIELHAGEN")
            headlinersFromTitle("SPIELHAGEN — \"HARMONY + ECSTASY\"").map { it.name } shouldBe listOf("SPIELHAGEN")
            stripArtistSuffix("Spielhagen - \"Harmony und Ecstasy\"") shouldBe "Spielhagen"
        }
    }

    @Test
    fun `a quoted show after a dash bills the act before it, and any other title bills none`() {
        assertSoftly {
            dashedQuotedShowActs("FIL - \"Lieder of the world\"") shouldBe listOf(ScrapedArtist("FIL", ArtistRole.HEADLINER, titleDerived = true))
            dashedQuotedShowActs("SPIELHAGEN – \"HARMONY + ECSTASY\"").map { it.name } shouldBe listOf("SPIELHAGEN")
            dashedQuotedShowActs("DZ - \"DEATHRAY\"").shouldBeEmpty()
            dashedQuotedShowActs("Tanztee im PETER EDEL").shouldBeEmpty()
            dashedQuotedShowActs("Talkshow \"Woher Wohin - Veronika Fischer persönlich\"").shouldBeEmpty()
        }
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
        result[0] shouldBe ScrapedArtist(name = "The Adicts", role = ArtistRole.HEADLINER, titleDerived = true)
        result[1] shouldBe ScrapedArtist(name = "Maid of Ace", role = ArtistRole.SUPPORT)
        result[2] shouldBe ScrapedArtist(name = "Kaos", role = ArtistRole.SUPPORT)
    }

    @Test
    fun `buildArtistList excludes placeholder headliner`() {
        val result = buildArtistList("TBA", listOf("Maid of Ace"))
        result shouldHaveSize 1
        result[0] shouldBe ScrapedArtist(name = "Maid of Ace", role = ArtistRole.SUPPORT)
    }

    @Test
    fun `buildArtistList excludes placeholder support names`() {
        val result = buildArtistList("The Adicts", listOf("TBA", "Maid of Ace"))
        result shouldHaveSize 2
        result[0] shouldBe ScrapedArtist(name = "The Adicts", role = ArtistRole.HEADLINER, titleDerived = true)
        result[1] shouldBe ScrapedArtist(name = "Maid of Ace", role = ArtistRole.SUPPORT)
    }

    @Test
    fun `buildArtistList with all placeholder supports returns only headliner`() {
        val result = buildArtistList("The Adicts", listOf("TBA", "TBD"))
        result shouldHaveSize 1
        result[0] shouldBe ScrapedArtist(name = "The Adicts", role = ArtistRole.HEADLINER, titleDerived = true)
    }

    @Test
    fun `buildArtistList drops a bare role-label support but keeps the headliner`() {
        // A "Support: Special Guest" line still signals the title-as-headliner
        // convention, but the label itself must not become a support artist.
        val result = buildArtistList("Green Lung", listOf("Special Guest"))
        result shouldContainExactly listOf(ScrapedArtist(name = "Green Lung", role = ArtistRole.HEADLINER, titleDerived = true))
    }

    @Test
    fun `buildArtistList splits a multi-artist title into co-headliners`() {
        val result = buildArtistList("TOTAL CHAOS + RUMKICKS", listOf("The Dollheads"))
        result shouldContainExactly
            listOf(
                ScrapedArtist(name = "TOTAL CHAOS", role = ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist(name = "RUMKICKS", role = ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist(name = "The Dollheads", role = ArtistRole.SUPPORT)
            )
    }

    // --- buildArtistsForEventType ---

    @Test
    fun `buildArtistsForEventType treats a concert title as the headliner without a support line`() {
        buildArtistsForEventType("Green Lung", subtitle = null, eventType = "CONCERT") shouldContainExactly
            listOf(ScrapedArtist(name = "Green Lung", role = ArtistRole.HEADLINER, titleDerived = true))
    }

    @Test
    fun `buildArtistsForEventType adds the subtitle's support acts after the headliners`() {
        buildArtistsForEventType("TOTAL CHAOS", subtitle = "+ Support: The Dollheads", eventType = "CONCERT") shouldContainExactly
            listOf(
                ScrapedArtist(name = "TOTAL CHAOS", role = ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist(name = "The Dollheads", role = ArtistRole.SUPPORT)
            )
    }

    @Test
    fun `buildArtistsForEventType bills no show, series or tour name as the act`() {
        // Wühlmäuse, ufaFabrik and Mikropol bill a revue, a showcase and a series; SO36 a tour (#2709).
        buildArtistsForEventType("Der Blaue Montag – Die ganze Stadt in einer Show", null, "COMEDY") shouldBe emptyList()
        buildArtistsForEventType("Berlin Songwood Sessions", "Hosted by Scott Hildebrand", "CONCERT").map { it.name } shouldNotContain
            "Berlin Songwood Sessions"
        buildArtistsForEventType("Große Hip Hop Offensive 17", null, "CONCERT") shouldBe emptyList()
        headlinersFromTitle("YASUHARU TAKANASHI TOUR", unpackWithFrame = true).map { it.name } shouldContainExactly listOf("YASUHARU TAKANASHI")
        headlinersFromTitle("THE REVIVAL TOUR", unpackWithFrame = true) shouldBe emptyList()
    }

    @Test
    fun `buildArtistsForEventType bills no artist for the SPH Music Masters contest`() {
        buildArtistsForEventType("SPH Music Masters", "Halbfinale Berlin", "CONCERT").shouldBeEmpty()
        isNonArtistName("SPH Music Masters All Stars") shouldBe false
    }

    @Test
    fun `buildArtistsForEventType reads a numeric support act from the Huxleys merged subtitle`() {
        // The detail page's tour name and the listing's support line, joined by " | " (#2207).
        buildArtistsForEventType("Sampagne", subtitle = "TV TOUR 2026 | + Support: 234", eventType = "CONCERT") shouldContainExactly
            listOf(
                ScrapedArtist(name = "Sampagne", role = ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist(name = "234", role = ArtistRole.SUPPORT)
            )
    }

    @Test
    fun `buildArtistsForEventType stays conservative for an unclassified event`() {
        // No type to confirm the title names an act, so only a support line unlocks it.
        buildArtistsForEventType("Vinyl Thursdays", subtitle = null, eventType = null).shouldBeEmpty()
        buildArtistsForEventType("Green Lung", subtitle = "Support: Kaos", eventType = "OTHER") shouldContainExactly
            listOf(
                ScrapedArtist(name = "Green Lung", role = ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist(name = "Kaos", role = ArtistRole.SUPPORT)
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
        ) shouldContainExactly listOf(ScrapedArtist(name = "Kaos", role = ArtistRole.SUPPORT))
    }

    @Test
    fun `buildArtistsForEventType applies the label showcase rule outside CONCERT too`() {
        // The conservative branch needs a support line to read the title as an act at all; the
        // showcase rule must still veto the title when one is present.
        buildArtistsForEventType(
            "Corrupted Blood Club Show",
            subtitle = "Corrupted Blood Records presents | Support: Kaos",
            eventType = "OTHER"
        ) shouldContainExactly listOf(ScrapedArtist(name = "Kaos", role = ArtistRole.SUPPORT))
    }

    // --- the "<night> w/ <acts>" frame ---

    @Test
    fun `headlinersFromTitle leaves a w-slash title alone unless the venue asks`() {
        // Off by default: `w/` joins collaborators at some venues (Zenner's "David August w/ MFO"),
        // where unpacking would delete the headliner. The `presents:` frame comes off either way.
        headlinersFromTitle("Analogue Foundation presents: David August w/ MFO") shouldContainExactly
            listOf(ScrapedArtist(name = "David August w/ MFO", role = ArtistRole.HEADLINER, titleDerived = true))
    }

    @Test
    fun `headlinersFromTitle unpacks a w-slash billing into its acts when asked`() {
        // The tail is a lineup list, so a comma delimits acts there — unlike in a title.
        headlinersFromTitle("RIOT ON THE ISLAND w/ Them Spirals, Painted Lox's & AK In Control", unpackWithFrame = true) shouldContainExactly
            listOf(
                ScrapedArtist(name = "Them Spirals", role = ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist(name = "Painted Lox's", role = ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist(name = "AK In Control", role = ArtistRole.HEADLINER, titleDerived = true)
            )
        // The night's own name never becomes a performer.
        headlinersFromTitle("RIPPLES W/ AMINE K", unpackWithFrame = true) shouldContainExactly
            listOf(ScrapedArtist(name = "AMINE K", role = ArtistRole.HEADLINER, titleDerived = true))
    }

    @Test
    fun `headlinersFromTitle reads the German mit spelling of the frame when asked`() {
        // SO36's "SADTEMBER mit TAHA, JOHNBOY M.IKARUS": the night on the left, the acts on the right (#1132).
        headlinersFromTitle("SADTEMBER mit TAHA, JOHNBOY M.IKARUS", unpackWithFrame = true) shouldContainExactly
            listOf(
                ScrapedArtist(name = "TAHA", role = ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist(name = "JOHNBOY M.IKARUS", role = ArtistRole.HEADLINER, titleDerived = true)
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

    @Test
    fun `headlinersFromTitle keeps the duos production bills as their whole title`() {
        // The #3159 worklist: each title is one act, and `&` / `und` is part of its name.
        assertSoftly {
            listOf(
                "Dicht & Ergreifend",
                "Dennis & Jesko Band",
                "Anchors & Hearts",
                "Maël & Jonas",
                "Mael & Jonas",
                // Max-Schmeling-Halle spells it with a macron (event 2386).
                "Maēl & Jonas",
                "Nie und Nimmer",
                // Morphine Raum (#3565).
                "Mitch & Mitch"
            ).forEach { title -> headlinersFromTitle(title).map { it.name } shouldContainExactly listOf(title) }
        }
    }

    @Test
    fun `headlinersFromTitle keeps a German possessive tail with its act`() {
        // `X und seine Freunde` is one billing, as `X & Friends` is (#3159).
        headlinersFromTitle("OLAF SCHUBERT UND SEINE FREUNDE").map { it.name } shouldContainExactly
            listOf("OLAF SCHUBERT UND SEINE FREUNDE")
        splitHeadlinerTitle("Ina Müller und ihre Band") shouldContainExactly listOf("Ina Müller und ihre Band")
        splitHeadlinerTitle("Max Mutzke & seinen Gästen") shouldContainExactly listOf("Max Mutzke & seinen Gästen")
        splitHeadlinerTitle("Anna Loos und ihren Freunden") shouldContainExactly listOf("Anna Loos und ihren Freunden")
        // A named second act still splits.
        splitHeadlinerTitle("Olaf Schubert und Ina Müller") shouldContainExactly listOf("Olaf Schubert", "Ina Müller")
    }

    @Test
    fun `headlinersFromTitle splits a meets co-bill into its two acts`() {
        headlinersFromTitle("Pauken & Planeten meets Nevio Passaro").map { it.name } shouldContainExactly
            listOf("Pauken & Planeten", "Nevio Passaro")
        headlinersFromTitle("Roots Daughters meets La Comadre Soundsystem").map { it.name } shouldContainExactly
            listOf("Roots Daughters", "La Comadre Soundsystem")
        headlinersFromTitle("DESNEY BAILEY MEETS DES").map { it.name } shouldContainExactly listOf("DESNEY BAILEY", "DES")
        headlinersFromTitle("CHRISTIAN VON DER GOLTZ MEETS RONJA ANDERSSON").map { it.name } shouldContainExactly
            listOf("CHRISTIAN VON DER GOLTZ", "RONJA ANDERSSON")
        // Beside a hard separator, each segment is asked on its own.
        splitHeadlinerTitle("Roots Daughters meets La Comadre Soundsystem + Gomez") shouldContainExactly
            listOf("Roots Daughters", "La Comadre Soundsystem", "Gomez")
    }

    @Test
    fun `splitHeadlinerTitle keeps a known single act with meets in its name whole`() {
        // Morphine Raum bills the project led by Jung-Jae Kim as its whole title (#3573).
        headlinersFromTitle("Where the River Meets the Sea").map { it.name } shouldContainExactly
            listOf("Where the River Meets the Sea")
        splitHeadlinerTitle("WHERE THE RIVER MEETS THE SEA") shouldContainExactly listOf("WHERE THE RIVER MEETS THE SEA")
        // Beside a hard separator the segment is protected too.
        splitHeadlinerTitle("Where the River Meets the Sea + Gomez") shouldContainExactly
            listOf("Where the River Meets the Sea", "Gomez")
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
            listOf(ScrapedArtist(name = "Real Band", role = ArtistRole.HEADLINER, titleDerived = true))
        headlinersFromTitle("TBA").shouldBeEmpty()
    }

    @Test
    fun `headlinersFromTitle keeps a slashed act name intact when splitOnSlash is false`() {
        // The Madame Claude concert path: "/" belongs to a single act ("Morimoto / Wong duo"),
        // so it must not be torn into two headliners; the trailing "(DJ-Set)" is still stripped.
        headlinersFromTitle("Morimoto / Wong duo + Forrest Gimp (DJ-Set)", splitOnSlash = false) shouldContainExactly
            listOf(
                ScrapedArtist(name = "Morimoto / Wong duo", role = ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist(name = "Forrest Gimp", role = ArtistRole.HEADLINER, titleDerived = true)
            )
    }

    @Test
    fun `headlinersFromTitle strips tour and live suffixes to recover the act`() {
        headlinersFromTitle("DOMINIUM - NIGHT IS CALLING TOUR 2026") shouldContainExactly
            listOf(ScrapedArtist(name = "DOMINIUM", role = ArtistRole.HEADLINER, titleDerived = true))
        headlinersFromTitle("HGICH.T LIVE") shouldContainExactly
            listOf(ScrapedArtist(name = "HGICH.T", role = ArtistRole.HEADLINER, titleDerived = true))
    }

    @Test
    fun `headlinersFromTitle strips an event-framing prefix to recover the act`() {
        headlinersFromTitle("A night with GULVØSS II") shouldContainExactly
            listOf(ScrapedArtist(name = "GULVØSS II", role = ArtistRole.HEADLINER, titleDerived = true))
        headlinersFromTitle("An Evening with Nick Cave") shouldContainExactly
            listOf(ScrapedArtist(name = "Nick Cave", role = ArtistRole.HEADLINER, titleDerived = true))
        // The framing phrase must be a leading whole prefix — a band with "night" mid-name is untouched.
        headlinersFromTitle("Last Night With You") shouldContainExactly
            listOf(ScrapedArtist(name = "Last Night With You", role = ArtistRole.HEADLINER, titleDerived = true))
    }

    @Test
    fun `headlinersFromTitle recovers a single act from an anniversary title`() {
        // The comma in the tail keeps the title unsplit; the suffix strip then recovers the band.
        headlinersFromTitle("THE BUTLERS - 40 YEARS, SKA & SOULPOWER -") shouldContainExactly
            listOf(ScrapedArtist(name = "THE BUTLERS", role = ArtistRole.HEADLINER, titleDerived = true))
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
                ScrapedArtist(name = "Blake Harley", role = ArtistRole.HEADLINER, titleDerived = true),
                ScrapedArtist(name = "Superior Motive", role = ArtistRole.HEADLINER, titleDerived = true)
            )
    }

    @Test
    fun `headlinersFromTitle drops the Indie in Town series lead before the comma split`() {
        // Urban Spree's title fused the series onto the first act as `Indie in Town - Vence` (#2959).
        headlinersFromTitle("Indie in Town - Vence, M. Lucky, Mirrors For Princes, Yunni").map { it.name } shouldContainExactly
            listOf("Vence", "M. Lucky", "Mirrors For Princes", "Yunni")
    }

    @Test
    fun `headlinersFromTitle keeps a name with a colon but no series edition marker`() {
        // No "#<n>:" marker, so nothing is stripped (guards a real "9:3"-style name).
        headlinersFromTitle("Bleech 9:3") shouldContainExactly
            listOf(ScrapedArtist(name = "Bleech 9:3", role = ArtistRole.HEADLINER, titleDerived = true))
    }

    // --- stripSeriesPrefix ---

    @Test
    fun `stripSeriesPrefix keeps the acts billed after the series label`() {
        stripSeriesPrefix("OFF THE RAILS #5: Blake Harley & Superior Motive") shouldBe "Blake Harley & Superior Motive"
        stripSeriesPrefix("Off the Rails #4: Some Act") shouldBe "Some Act"
        stripSeriesPrefix("Series # 12 : Act") shouldBe "Act"
        stripSeriesPrefix("INDIE IN TOWN – Vence") shouldBe "Vence"
    }

    @Test
    fun `stripSeriesPrefix leaves a title whose colon is not a series marker`() {
        stripSeriesPrefix("9:3") shouldBe "9:3"
        stripSeriesPrefix("H2:O") shouldBe "H2:O"
        stripSeriesPrefix("Just A Band") shouldBe "Just A Band"
        stripSeriesPrefix("Indie in Town") shouldBe "Indie in Town"
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

    // #3211 — a quoted work, a `plays <composer>` tail and a `- <place>` tail are not part of the act.
    @Test
    fun `headlinersFromTitle drops a quoted work, a plays tail and a dashed place from an act`() {
        headlinersFromTitle("Adam Goodwin: “Grumsin” + Kole Galbraith + Bile Bear + Forrest Gimp (DJ-Set)", splitOnSlash = false)
            .map { it.name } shouldContainExactly listOf("Adam Goodwin", "Kole Galbraith", "Bile Bear", "Forrest Gimp")
        headlinersFromTitle("Acephalic Headspace pres. ladr ache + Lésions Graves Sextet plays Julius Eastman").map { it.name } shouldContainExactly
            listOf("ladr ache", "Lésions Graves Sextet")
        headlinersFromTitle("Dead Phoenix - BERLIN").map { it.name } shouldContainExactly listOf("Dead Phoenix")
    }

    @Test
    fun `a Maschinenhaus show with a quoted work still bills no act`() {
        buildArtistsForEventType("Nobel-Popel: \"Zebrastreifen\"", "Musiktheater für Kinder aus Berlin", "SHOW").shouldBeEmpty()
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

    // #2350 — a partner co-brands a series night or the venue with ` x `; neither side is an act.
    @Test
    fun `a title co-branded with a series or the venue after x yields no artist`() {
        headlinersFromTitle("Human Tree x Urban Spree Klubnacht").shouldBeEmpty()
        headlinersFromTitle("Human Tree x Urban Spree KLUBNACHT 006").shouldBeEmpty()
        headlinersFromTitle("aufnahme + wiedergabe X Urban Spree").shouldBeEmpty()
    }

    @Test
    fun `a co-bill joined with x that ends in no series is still read as acts`() {
        headlinersFromTitle("Pyrexia x Relics of Humanity").map { it.name } shouldBe listOf("Pyrexia", "Relics of Humanity")
        headlinersFromTitle("Urban Spree Klubnacht x Some Act").map { it.name } shouldBe listOf("Some Act")
    }

    // #2365 — ` x ` joins two acts the way ` + ` does; each side is billed on its own.
    @Test
    fun `a title joining two acts with x bills both`() {
        headlinersFromTitle("Hatebreed x Life Of Agony").map { it.name to it.role } shouldBe
            listOf("Hatebreed" to ArtistRole.HEADLINER, "Life Of Agony" to ArtistRole.HEADLINER)
        headlinersFromTitle("TMARO x PapaRaZzle").map { it.name } shouldBe listOf("TMARO", "PapaRaZzle")
        headlinersFromTitle("IFA x Never Back Down").map { it.name } shouldBe listOf("IFA", "Never Back Down")
        headlinersFromTitle("Schwindel x Nancy & the Jam Fancys").map { it.name } shouldBe listOf("Schwindel", "Nancy & the Jam Fancys")
    }

    @Test
    fun `an X that ends a name is not a join`() {
        headlinersFromTitle("Malcolm X").map { it.name } shouldBe listOf("Malcolm X")
        headlinersFromTitle("Symphony X + Firewind").map { it.name } shouldBe listOf("Symphony X", "Firewind")
        headlinersFromTitle("Sadat X & Grand Agent").map { it.name } shouldBe listOf("Sadat X", "Grand Agent")
        headlinersFromTitle("Sadat X and Grand Agent").map { it.name } shouldBe listOf("Sadat X", "Grand Agent")
        headlinersFromTitle("X-Ray Spex").map { it.name } shouldBe listOf("X-Ray Spex")
        headlinersFromTitle("Max x Y").map { it.name } shouldBe listOf("Max x Y")
    }

    @Test
    fun `two promoters presenting a night bill only the acts after with`() {
        headlinersFromTitle("Process Party x Effetto Notte: Hall of Bats with Lovataraxx, Hysteric Helen, Olgha").map { it.name } shouldBe
            listOf("Lovataraxx", "Hysteric Helen", "Olgha")
    }

    // #305 — a `feat.` guest mid-title is support, and the act keeps its year-ended night name off.
    @Test
    fun `headlinersFromTitle bills a featured guest as support and keeps the act`() {
        headlinersFromTitle("Stereoact: Ich liebe das Leben Party 2027 feat. Lena Marie Engel").map { it.name to it.role } shouldBe
            listOf("Stereoact" to ArtistRole.HEADLINER, "Lena Marie Engel" to ArtistRole.SUPPORT)
        headlinersFromTitle("Kraftklub feat. Tokio Hotel & Casper").map { it.name to it.role } shouldBe
            listOf("Kraftklub" to ArtistRole.HEADLINER, "Tokio Hotel" to ArtistRole.SUPPORT, "Casper" to ArtistRole.SUPPORT)
    }

    // #3160 — production titles: a night or series name is no act.
    @Test
    fun `headlinersFromTitle reads a gala night and a numbered series as no act`() {
        listOf(
            "Die Nacht der Filmmusik 2027",
            "Die Nacht der Musicals",
            "Nacht der Gitarren",
            "Lange Nacht der Wissenschaft",
            "Die Schlagernacht des Jahres",
            "Im Kreise der Familia 3"
        ).forEach { headlinersFromTitle(it).shouldBeEmpty() }
        headlinersFromTitle("Uats Live + Jam").map { it.name to it.role } shouldBe listOf("Uats" to ArtistRole.HEADLINER)
    }

    // #3212 — production titles: a night name ending in a year, Edition or Special, or carrying Label Night, is no act.
    @Test
    fun `headlinersFromTitle reads a night name with a year, edition, special or label night as no act`() {
        listOf(
            "Autumn Bash 2026",
            "BERLIN BREAKOUT 2027",
            "HANDS LABEL NIGHT BERLIN 2027",
            "UNRELEASED BERLIN – XMAS SPECIAL",
            "Unreleased Berlin – Xmas Special",
            "TuneUp Session // Herbst Edition",
            "OMLJF Special: Let’s Get Political!",
            "NEW WAVES DAY CLUB EDITION",
            "Berlin Salsacongress 2027",
            "Hands Label Night"
        ).forEach { title -> withClue(title) { headlinersFromTitle(title).shouldBeEmpty() } }
    }

    // #3212 — the suffix rules strip an act's tour or show year before the night rule runs.
    @Test
    fun `headlinersFromTitle keeps an act whose tour or show carries a year`() {
        headlinersFromTitle("SIDOs Weihnachtsshow 2026").map { it.name } shouldBe listOf("SIDO")
        headlinersFromTitle("Gregorian - The Magical Christmas Tour 2026").map { it.name } shouldBe listOf("Gregorian")
        headlinersFromTitle("Kraftklub Live 2027").map { it.name } shouldBe listOf("Kraftklub")
        headlinersFromTitle("The Specials").map { it.name } shouldBe listOf("The Specials")
        stripArtistSuffix("Helmut Lotti goes Classic - 30th Anniversary Edition") shouldBe "Helmut Lotti goes Classic"
    }

    // #3160 — with the night's name dropped, the `ft.` guests are the whole bill.
    @Test
    fun `headlinersFromTitle bills the featured guests of a dropped night name as headliners`() {
        headlinersFromTitle("Jazz Night ft. Wonder Marino 4 & Silke Eberhard").map { it.name to it.role } shouldBe
            listOf("Wonder Marino 4" to ArtistRole.HEADLINER, "Silke Eberhard" to ArtistRole.HEADLINER)
        headlinersFromTitle("Jazz Night ft. Flow Rea (Est)").map { it.name to it.role } shouldBe listOf("Flow Rea" to ArtistRole.HEADLINER)
    }

    // #339 — the DJ a night is named for, when the venue publishes no line-up.
    @Test
    fun `hostedActsFromTitle reads the acts after a curated-by, hosted-by or by-person marker`() {
        hostedActsFromTitle("FOREVER 25 curated by Mila Stern & Esther Silex").map { it.name } shouldBe listOf("Mila Stern", "Esther Silex")
        hostedActsFromTitle("Tresor New Faces hosted by Secret Keywords", role = ArtistRole.DJ).map { it.name to it.role } shouldBe
            listOf("Secret Keywords" to ArtistRole.DJ)
        hostedActsFromTitle("Antina's Spookhouse by Antina Christ").map { it.name } shouldBe listOf("Antina Christ")
        // A bare `by` needs a person-shaped tail; a night name or a lowercase phrase is not one.
        hostedActsFromTitle("Stand By Me").shouldBeEmpty()
        hostedActsFromTitle("Klubnacht by night").shouldBeEmpty()
        hostedActsFromTitle("FOREVER 25 curated by TBA").shouldBeEmpty()
    }

    @Test
    fun `buildArtistsForEventType reads a solo bill off a comedy title as it does a show's`() {
        buildArtistsForEventType("Arnd Schimkat - LACHEN MACHEN", null, "COMEDY").map { it.name } shouldBe listOf("Arnd Schimkat")
        buildArtistsForEventType("Comedy, Pizza and Shots – SHOWCASE FRIDAY", null, "COMEDY").shouldBeEmpty()
    }

    // #315 — a variety or comedy house bills a solo act as `<Performer> – <Show>`.
    @Test
    fun `buildArtistsForEventType reads a solo bill off a show title and leaves a production alone`() {
        buildArtistsForEventType("Bülent Ceylan - \"Diktatürk\"", null, "SHOW").map { it.name } shouldBe listOf("Bülent Ceylan")
        buildArtistsForEventType("Torsten Sträter: Schnelle Nummer", null, "SHOW").map { it.name } shouldBe listOf("Torsten Sträter")
        buildArtistsForEventType("DIE KLIMA-MONOLOGE", null, "SHOW").shouldBeEmpty()
        buildArtistsForEventType("Berlin Burlesque Festival - Gala", null, "SHOW").shouldBeEmpty()
        buildArtistsForEventType("FOTZENSCHLEIMPOWER GEGEN RAUBTIER – Ein Abend", null, "SHOW").shouldBeEmpty()
        // A staging format after the dash makes the head the production (Bar jeder Vernunft, Uber Eats Music Hall).
        buildArtistsForEventType("Die Bettwurst – Das Musical!", null, "SHOW").shouldBeEmpty()
        buildArtistsForEventType("Der Nussknacker – Grand Classic Ballet mit Orchester", null, "SHOW").shouldBeEmpty()
        buildArtistsForEventType("Der Tod: Geisterstunde", null, "SHOW").map { it.name } shouldBe listOf("Der Tod")
        // A `Support:` line still wins, and a concert keeps its own path.
        buildArtistsForEventType("Some Act - Summer Tour", "Support: Other Act", "SHOW").map { it.name } shouldBe listOf("Some Act", "Other Act")
    }

    @Test
    fun `buildArtistsForEventType reads the performers a show title names after mit`() {
        buildArtistsForEventType("Schund und Asche - mit Moritz Neumeier und Till Reiners", null, "COMEDY").map { it.name } shouldBe
            listOf("Moritz Neumeier", "Till Reiners")
        buildArtistsForEventType("FUN FACTS mit Victoria Reichelt & Maik Martschinkowsky", null, "COMEDY").map { it.name } shouldBe
            listOf("Victoria Reichelt", "Maik Martschinkowsky")
        // The show's own format beside the host is dropped, not billed.
        buildArtistsForEventType("FUN FACTS mit Pinar Atalay & Live Show", null, "COMEDY").map { it.name } shouldBe listOf("Pinar Atalay")
        buildArtistsForEventType("FUN FACTS mit Ruth Moschner & Comedy Mix Show", null, "COMEDY").map { it.name } shouldBe listOf("Ruth Moschner")
        buildArtistsForEventType("Comedy & Chill mit Pierre Hover", null, "COMEDY").map { it.name } shouldBe listOf("Pierre Hover")
    }

    @Test
    fun `buildArtistsForEventType bills nobody when a part after mit is not a name`() {
        buildArtistsForEventType("Was ist los mit der Welt?!", null, "SHOW").shouldBeEmpty()
        buildArtistsForEventType("Komödie mit Maria Hartmann, Heinrich Schafmeister u.a.", null, "SHOW").shouldBeEmpty()
        buildArtistsForEventType("GöREN am Montag mit Kalle Zilske — Stand-Up Comedy Show", null, "COMEDY").shouldBeEmpty()
        buildArtistsForEventType("Lesung mit Musik", null, "SHOW").shouldBeEmpty()
    }

    @Test
    fun `performersBilledWith reads a subtitle and ignores a line without mit`() {
        performersBilledWith("Qualitätspodcast mit Till Reiners und Moritz Neumeier").map { it.name } shouldBe listOf("Till Reiners", "Moritz Neumeier")
        performersBilledWith("Komödie mit Hans-Joachim Heist").map { it.name } shouldBe listOf("Hans-Joachim Heist")
        performersBilledWith("Die Indie-Pop Party").shouldBeEmpty()
        performersBilledWith(null).shouldBeEmpty()
    }

    @Test
    fun `buildArtistsForEventType bills a bare-name show title only when the description introduces the performer`() {
        val copy = "Mit seiner Tour kommt der Comedian Till Reiners am 6. November 2027 in die Uber Arena nach Berlin."
        buildArtistsForEventType("Till Reiners", null, "COMEDY", description = copy).map { it.name } shouldBe listOf("Till Reiners")
        buildArtistsForEventType("TILL REINERS", null, "COMEDY", description = copy).map { it.name } shouldBe listOf("Till Reiners")
        buildArtistsForEventType("Till Reiners", null, "COMEDY").shouldBeEmpty()
        buildArtistsForEventType("Der Kirschgarten", null, "SHOW", description = "Tschechows Der Kirschgarten in neuer Regie.").shouldBeEmpty()
    }

    @Test
    fun `knownSoloPerformer bills a pinned one-word name at the head of a title and nothing else`() {
        knownSoloPerformer("FIL - MIT NEUER SHOW").map { it.name } shouldBe listOf("FIL")
        knownSoloPerformer("FIL'S TESTSHOWS").map { it.name } shouldBe listOf("FIL")
        knownSoloPerformer("FIL").map { it.name } shouldBe listOf("FIL")
        knownSoloPerformer("Hamlet – Ein Abend").shouldBeEmpty()
        knownSoloPerformer("Filow - Keine Tour macht arm").shouldBeEmpty()
        // Through the shared builder, as Mehringhof hands it `Fil mit Lieder of the World` with the `mit` made a dash.
        buildArtistsForEventType("Fil – Lieder of the World (Neues Programm)", null, "COMEDY").map { it.name } shouldBe listOf("Fil")
    }

    // #2637 — an ice show's title names a production, whatever type the venue gives it.
    @Test
    fun `buildArtistsForEventType reads no act off an ice show's title`() {
        buildArtistsForEventType("Disney On Ice präsentiert Mickys Magische Momente", null, "SHOW").shouldBeEmpty()
        buildArtistsForEventType("Holiday on Ice – Atlantis", null, "SHOW").shouldBeEmpty()
        buildArtistsForEventType("Disney On Ice: Mickys Magische Momente", null, "CONCERT").shouldBeEmpty()
        buildArtistsForEventType("Eiskönigin 1 & 2", "Musik-Show auf Eis", "CONCERT").shouldBeEmpty()
        buildArtistsForEventType("Holiday on Ice", null, null).shouldBeEmpty()
    }

    @Test
    fun `buildArtistsForEventType keeps an ice show's support acts and every other show's solo bill`() {
        buildArtistsForEventType("Holiday on Ice – Atlantis", "Support: Some Skater", "SHOW").map { it.name to it.role } shouldBe
            listOf("Some Skater" to ArtistRole.SUPPORT)
        buildArtistsForEventType("Farid Kahl – Die Magie Show", null, "SHOW").map { it.name } shouldBe listOf("Farid Kahl")
        buildArtistsForEventType("Lemon Ice", null, "CONCERT").map { it.name } shouldBe listOf("Lemon Ice")
        buildArtistsForEventType("Eisbrecher", "Kaiserwerk Tour 2026", "CONCERT").map { it.name } shouldBe listOf("Eisbrecher")
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

    // #2103: a venue's sold-out marker is never billed as part of an act.
    @Test
    fun `headlinersFromTitle bills no sold-out marker`() {
        headlinersFromTitle("!!AUSVERKAUFT!! Tim Vantol").map { it.name } shouldBe listOf("Tim Vantol")
        headlinersFromTitle("SukOne [AUSVERKAUFT!]").map { it.name } shouldBe listOf("SukOne")
        headlinersFromTitle("The Blood Arm + Please Louise [pre-sale sold out, 10 tix on the doors]").map { it.name } shouldBe
            listOf("The Blood Arm", "Please Louise")
    }
}
