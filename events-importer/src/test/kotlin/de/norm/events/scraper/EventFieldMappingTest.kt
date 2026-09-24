package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

// Focused tests for EventFieldMapping — one test per behaviour.
class EventFieldMappingTest {
    // --- cleanEventTitle ---

    @Test
    fun `cleanEventTitle strips a trailing reschedule note and stray dash`() {
        cleanEventTitle("Iggi Kelly Nachholtermin vom 28.04.26-") shouldBe "Iggi Kelly"
        cleanEventTitle("The Dear Hunter -Nachholtermin vom 30.09.2025.") shouldBe "The Dear Hunter"
        cleanEventTitle("Some Show -") shouldBe "Some Show"
    }

    @Test
    fun `cleanEventTitle strips a trailing sold-out annotation`() {
        // The status suffix is dropped so "… (ausverkauft)" and its non-sold-out twin
        // collapse to the same title (and the same title-derived headliner artist).
        cleanEventTitle("Singalong -Das große Mitsing-Event (ausverkauft)") shouldBe "Singalong -Das große Mitsing-Event"
        cleanEventTitle("Singalong -Das große Mitsing-Event") shouldBe "Singalong -Das große Mitsing-Event"
        cleanEventTitle("Some Show ausverkauft") shouldBe "Some Show"
        cleanEventTitle("Some Show - AUSVERKAUFT!") shouldBe "Some Show"
        // Frannz sets the note between two dashes (#1840).
        cleanEventTitle("Haken -ausverkauft-") shouldBe "Haken"
    }

    @Test
    fun `cleanEventTitle strips a relocation note written destination first`() {
        // Frannz writes both orders: "-verlegt ins Gretchen-" and "-ins Lido verlegt-" (#1840).
        cleanEventTitle("Georgia Cavallo -ins Lido verlegt-") shouldBe "Georgia Cavallo"
        cleanEventTitle("MAD TSAI -verlegt ins Gretchen-") shouldBe "MAD TSAI"
        // Without a leading dash the words are the title, not a note.
        cleanEventTitle("Alles ins Blaue verlegt") shouldBe "Alles ins Blaue verlegt"
    }

    @Test
    fun `cleanEventTitle leaves a clean title and mid-title dash untouched`() {
        cleanEventTitle("Freshlyground") shouldBe "Freshlyground"
        cleanEventTitle("Tannz im Frannz -auf 2 Floors") shouldBe "Tannz im Frannz -auf 2 Floors"
        // "ausverkauft" only mid-title (never a real case, but proves the end-anchor) is kept.
        cleanEventTitle("Ausverkauft Tour Show") shouldBe "Ausverkauft Tour Show"
    }

    // Audit T-13: a venue's own markup decides how much space lands between two words — a stray
    // double space in the CMS ("Adventurous Juan  (DJ-Set)") or a line break inside the heading.
    @Test
    fun `cleanEventTitle collapses runs of whitespace`() {
        cleanEventTitle("Adventurous Juan  (DJ-Set)") shouldBe "Adventurous Juan (DJ-Set)"
        cleanEventTitle("Lucas Lauriente –  Stand Up 2026") shouldBe "Lucas Lauriente – Stand Up 2026"
        cleanEventTitle("Some\nAct\tName") shouldBe "Some Act Name"
        // Collapsing runs first keeps the tail patterns keyed on a single space.
        cleanEventTitle("Iggi Kelly  Nachholtermin vom 28.04.26-") shouldBe "Iggi Kelly"
    }

    // Java's `\s` matches ASCII whitespace only, so a non-breaking space a CMS editor produced
    // without meaning to (Colosseum's "JOSH. Solo - Wer singt dann Lieder für dich?") would
    // otherwise survive the collapse: the title looks right but stops matching a word search.
    @Test
    fun `cleanEventTitle collapses non-breaking spaces`() {
        cleanEventTitle("Wer singt\u00A0dann Lieder?") shouldBe "Wer singt dann Lieder?"
        cleanEventTitle("babywho\u00A0CONNECT") shouldBe "babywho CONNECT"
        // The narrow no-break space too, which a German CMS emits before a unit or an abbreviation.
        cleanEventTitle("Some\u202FAct") shouldBe "Some Act"
        // A non-breaking space adjacent to an ordinary one collapses to a single space, not two.
        cleanEventTitle("Some \u00A0 Act") shouldBe "Some Act"
    }

    // A zero-width character is invisible, so it is never part of a name — an editor pasted it in
    // (MAAYA's "HOMECOMING DJ WORKSHOP"). `\s` does not match it, so it survives both the trim and
    // the collapse unless it is removed outright.
    @Test
    fun `cleanEventTitle drops zero-width characters`() {
        cleanEventTitle("HOMECOMING DJ WORKSHOP\u200B") shouldBe "HOMECOMING DJ WORKSHOP"
        cleanEventTitle("\uFEFFSome Act") shouldBe "Some Act"
        cleanEventTitle("Some\u200COther\u200DAct") shouldBe "SomeOtherAct"
        // Removing it before the collapse leaves a single space, not two.
        cleanEventTitle("Some \u200B Act") shouldBe "Some Act"
    }

    // --- detectFree ---

    @Test
    fun `detectFree is true for an explicit zero presale or box-office price`() {
        detectFree(pricePresale = BigDecimal.ZERO) shouldBe true
        detectFree(pricePresale = BigDecimal("0.00")) shouldBe true
        detectFree(priceBoxOffice = BigDecimal("0.00")) shouldBe true
    }

    @Test
    fun `detectFree is true for free-entry phrases in the price note or title`() {
        detectFree(priceNote = "Eintritt frei") shouldBe true
        detectFree(priceNote = "Freier Eintritt, Spende erwünscht") shouldBe true
        detectFree(priceNote = "Free entry all night") shouldBe true
        detectFree(title = "Sommerfest — Free Admission") shouldBe true
    }

    @Test
    fun `detectFree matches single-word markers only in the price note`() {
        detectFree(priceNote = "Gratis") shouldBe true
        detectFree(priceNote = "kostenlos") shouldBe true
        detectFree(priceNote = "umsonst") shouldBe true
        // A bare token in the title (not the pricing-scoped note) must not trigger.
        detectFree(title = "Gratis Vibes Live") shouldBe false
    }

    @Test
    fun `detectFree does not false-positive on names or word fragments`() {
        detectFree(title = "Freedom Festival") shouldBe false
        detectFree(title = "Freikörperkultur") shouldBe false
        detectFree(priceNote = "freestyle session") shouldBe false
        detectFree(pricePresale = BigDecimal("12.00"), priceBoxOffice = BigDecimal("15.00")) shouldBe false
    }

    @Test
    fun `detectFree is false when nothing is provided`() {
        detectFree() shouldBe false
        detectFree(priceNote = null, title = null) shouldBe false
    }

    // --- endOn ---

    @Test
    fun `endOn rolls an end at or before the start to the next day`() {
        val night = LocalDate.of(2026, 9, 11)
        // Club OST's "11 p.m. → 8 a.m."
        endOn(night, LocalTime.of(23, 0), LocalTime.of(8, 0)) shouldBe night.plusDays(1)
        endOn(night, LocalTime.of(22, 0), LocalTime.of(22, 0)) shouldBe night.plusDays(1)
    }

    @Test
    fun `endOn keeps an end after the start on the day, and a start-less end too`() {
        val night = LocalDate.of(2026, 9, 11)
        endOn(night, LocalTime.of(20, 0), LocalTime.of(23, 30)) shouldBe night
        // No start to compare against: the venue's own day is the only fact.
        endOn(night, null, LocalTime.of(6, 0)) shouldBe night
    }

    // --- presaleAboveDoor ---

    @Test
    fun `presaleAboveDoor fires only when both prices exist and presale is the dearer`() {
        presaleAboveDoor(BigDecimal("16"), BigDecimal("15")) shouldBe true
        presaleAboveDoor(BigDecimal("15.43"), BigDecimal("15.00")) shouldBe true
        presaleAboveDoor(BigDecimal("15"), BigDecimal("15")) shouldBe false
        presaleAboveDoor(BigDecimal("15"), BigDecimal("16")) shouldBe false
        presaleAboveDoor(BigDecimal("16"), null) shouldBe false
        presaleAboveDoor(null, BigDecimal("15")) shouldBe false
    }

    // --- orderDoorsBeforeStart ---

    @Test
    fun `orderDoorsBeforeStart swaps a transposed doors-after-start pair`() {
        // SO36's "Einlass: 19:30, Beginn: 19:00" — labels swapped at the source.
        orderDoorsBeforeStart(LocalTime.of(19, 30), LocalTime.of(19, 0)) shouldBe
            (LocalTime.of(19, 0) to LocalTime.of(19, 30))
    }

    @Test
    fun `orderDoorsBeforeStart leaves an already-valid pair unchanged`() {
        orderDoorsBeforeStart(LocalTime.of(19, 0), LocalTime.of(20, 0)) shouldBe
            (LocalTime.of(19, 0) to LocalTime.of(20, 0))
    }

    @Test
    fun `orderDoorsBeforeStart leaves equal times unchanged`() {
        orderDoorsBeforeStart(LocalTime.of(20, 0), LocalTime.of(20, 0)) shouldBe
            (LocalTime.of(20, 0) to LocalTime.of(20, 0))
    }

    @Test
    fun `orderDoorsBeforeStart does not reorder when a time is missing`() {
        orderDoorsBeforeStart(null, LocalTime.of(20, 0)) shouldBe (null to LocalTime.of(20, 0))
        orderDoorsBeforeStart(LocalTime.of(19, 0), null) shouldBe (LocalTime.of(19, 0) to null)
        orderDoorsBeforeStart(null, null) shouldBe (null to null)
    }

    // --- parseEventStatus ---

    @Test
    fun `parseEventStatus maps German and English badge text case-insensitively`() {
        parseEventStatus("Abgesagt") shouldBe "CANCELLED"
        parseEventStatus("CANCELLED") shouldBe "CANCELLED"
        parseEventStatus("verschoben") shouldBe "POSTPONED"
        parseEventStatus("Postponed") shouldBe "POSTPONED"
        parseEventStatus("Verlegt") shouldBe "RELOCATED"
        parseEventStatus("RELOCATED") shouldBe "RELOCATED"
    }

    // Sold-out is a separate flag on the event, not a status, so it must leave the status alone.
    @Test
    fun `parseEventStatus leaves a sold-out badge scheduled`() {
        parseEventStatus("Ausverkauft") shouldBe "SCHEDULED"
        parseEventStatus("Sold Out") shouldBe "SCHEDULED"
        parseEventStatus("") shouldBe "SCHEDULED"
    }

    @Test
    fun `parseEventStatus reads the other German cancellation, with or without the umlaut`() {
        parseEventStatus("fällt aus") shouldBe "CANCELLED"
        parseEventStatus("faellt leider aus!") shouldBe "CANCELLED"
        parseEventStatus("Entfällt") shouldBe "CANCELLED"
    }

    // --- parseTitleStatus / stripTitleStatusMarker (#1493) ---

    @Test
    fun `parseEventStatus reads the new-venue badge as a move`() {
        // Lido's label switched from "verlegt" to "new venue" on 2026-09-17; Gretchen prints "neuer Ort".
        parseEventStatus("new venue") shouldBe "RELOCATED"
        parseEventStatus("Neuer Ort") shouldBe "RELOCATED"
    }

    @Test
    fun `parseEventStatus reads a date moved with the relocation verb as postponed`() {
        parseEventStatus("ACHTUNG VERLEGT! Die Show wird auf den 30.05.2027 verlegt.") shouldBe "POSTPONED"
        parseEventStatus("Achtung: Die Show wird vom Huxleys ins Hole44 verlegt!") shouldBe "RELOCATED"
    }

    @Test
    fun `parseTitleStatus reads a status a venue wrote into the title`() {
        parseTitleStatus("Olga Myko - Abgesagt") shouldBe "CANCELLED"
        parseTitleStatus("Da Konzert von Scarfold und Los Mierda faellt leider aus!") shouldBe "CANCELLED"
        parseTitleStatus("CANCELLED: The Act") shouldBe "CANCELLED"
        parseTitleStatus("ABSAGE: MY HERO ACADEMIA - In Concert") shouldBe "CANCELLED"
        parseTitleStatus("The Act (verschoben)") shouldBe "POSTPONED"
        parseTitleStatus("Verlegt ins Bi Nuu – BRKN") shouldBe "RELOCATED"
    }

    @Test
    fun `parseTitleStatus reads nothing into a title that only resembles a notice`() {
        parseTitleStatus("Berliner Weisse").shouldBeNull()
        // Prose, not a badge: the bare "cancel" a badge may carry is not a title marker.
        parseTitleStatus("Cancel Culture – Ein Film").shouldBeNull()
        parseTitleStatus("Ausfall der Sinne").shouldBeNull()
    }

    // --- parseRelocation / resolveRelocation (#1551) ---

    @Test
    fun `parseRelocation reads both houses off the sentence a venue prints on either end of a move`() {
        parseRelocation("Achtung: Die Show wird vom Huxleys ins Hole44 verlegt") shouldBe Relocation(from = "Huxleys", to = "Hole44")
        parseRelocation("Die Show wird aus dem Gretchen in das Frannz VERLEGT") shouldBe Relocation(from = "Gretchen", to = "Frannz")
        parseRelocation("Die Show wird von der Uber Eats Music Hall ins Huxleys verlegt") shouldBe
            Relocation(from = "Uber Eats Music Hall", to = "Huxleys")
        parseRelocation("Hinweis: Das Konzert wurde vom Lido in den Privatclub verlegt! Tickets behalten ihre Gültigkeit") shouldBe
            Relocation(from = "Lido", to = "Privatclub")
        parseRelocation("Das Konzert wurde vom Lido in Cassiopeia verlegt") shouldBe Relocation(from = "Lido", to = "Cassiopeia")
    }

    @Test
    fun `parseRelocation reads a note that names one house, or none`() {
        parseRelocation("Verlegt ins Mikropol") shouldBe Relocation(from = null, to = "Mikropol")
        parseRelocation("Zoh Amba - Verlegt ins Bi Nuu") shouldBe Relocation(from = null, to = "Bi Nuu")
        parseRelocation("Trinity präsentiert: MAD TSAI -verlegt ins Gretchen- **The BITE BACK Tour**") shouldBe Relocation(from = null, to = "Gretchen")
        parseRelocation("HOCHVERLEGT IN DAS COLUMBIA THEATER") shouldBe Relocation(from = null, to = "COLUMBIA THEATER")
        parseRelocation("Mad Tsai (US) *live* verlegt vom Frannz *Vorverkauf 29,45 €") shouldBe Relocation(from = "Frannz", to = null)
        parseRelocation("Verlegt / Relocated") shouldBe Relocation(from = null, to = null)
        parseRelocation("GENESIS OWUSU VERLEGT") shouldBe Relocation(from = null, to = null)
    }

    @Test
    fun `parseRelocation prefers the contracted preposition over the bare in of prose, and reads no act as an origin`() {
        // "in Berlin" comes first in the sentence; "ins Mikropol" is the note.
        val forager = parseRelocation("Aus Termingründen wird das Konzert von Forager in Berlin vom Badehaus ins Mikropol verlegt")
        forager shouldBe Relocation(from = "Badehaus", to = "Mikropol")
        parseRelocation("Support: Joy Forever, 20:00 Uhr").shouldBeNull()
    }

    @Test
    fun `parseRelocation ends a name at a verlegt glued to it, spaced or not`() {
        val glued = parseRelocation("Das Mr. P-Square Konzert am 22.09.2026 in Berlin wird vom Festsaal Kreuzberg ins Bi Nuuverlegt.")
        glued shouldBe Relocation(from = "Festsaal Kreuzberg", to = "Bi Nuu")
        resolveRelocation("RELOCATED", glued, "festsaal-kreuzberg") shouldBe ("RELOCATED" to "Bi Nuu")
        parseRelocation("Das Mr. P-Square Konzert am 22.09.2026 in Berlin wird vom Festsaal Kreuzberg ins Bi Nuu verlegt.") shouldBe
            Relocation(from = "Festsaal Kreuzberg", to = "Bi Nuu")
        parseRelocation("Die Show wird vom Bi Nuu in den Festsaal Kreuzberg verlegt") shouldBe Relocation(from = "Bi Nuu", to = "Festsaal Kreuzberg")
        parseRelocation("Die Show wird ins Columbia Theater hochverlegt") shouldBe Relocation(from = null, to = "Columbia Theater")
        parseRelocation("Das Konzert im Bi Nuu wird um eine Stunde vorverlegt").shouldBeNull()
    }

    @Test
    fun `resolveRelocation makes the row at the house the show left the origin, and the other one a plain event`() {
        val move = Relocation(from = "Huxleys", to = "Hole44")
        resolveRelocation("RELOCATED", move, "huxleys-neue-welt") shouldBe ("RELOCATED" to "Hole44")
        resolveRelocation("RELOCATED", move, "hole-44") shouldBe ("SCHEDULED" to null)
        // Only the destination named: the origin is whoever printed it.
        resolveRelocation("RELOCATED", Relocation(null, "Mikropol"), "badehaus") shouldBe ("RELOCATED" to "Mikropol")
        // Only the origin named, and it is another house: the show arrived here.
        resolveRelocation("RELOCATED", Relocation("Frannz", null), "gretchen") shouldBe ("SCHEDULED" to null)
        // "Hole" is how Metropol writes Hole 44.
        resolveRelocation("RELOCATED", Relocation("Hole", "Metropol"), "hole-44") shouldBe ("RELOCATED" to "Metropol")
        resolveRelocation("RELOCATED", Relocation("Hole", "Metropol"), "metropol") shouldBe ("SCHEDULED" to null)
    }

    @Test
    fun `resolveRelocation leaves a nameless note and every other status alone`() {
        resolveRelocation("RELOCATED", Relocation(null, null), "columbia-theater") shouldBe ("RELOCATED" to null)
        resolveRelocation("RELOCATED", null, "columbia-theater") shouldBe ("RELOCATED" to null)
        resolveRelocation("CANCELLED", Relocation("Huxleys", "Hole44"), "huxleys-neue-welt") shouldBe ("CANCELLED" to null)
        resolveRelocation("SCHEDULED", null, "lido") shouldBe ("SCHEDULED" to null)
    }

    @Test
    fun `stripTitleStatusMarker removes a cancellation glued to either end of a name`() {
        stripTitleStatusMarker("Olga Myko - Abgesagt") shouldBe "Olga Myko"
        stripTitleStatusMarker("The Act [ABGESAGT!]") shouldBe "The Act"
        stripTitleStatusMarker("(cancelled) The Act") shouldBe "The Act"
        stripTitleStatusMarker("Cancelled: The Act") shouldBe "The Act"
        stripTitleStatusMarker("ABSAGE: MY HERO ACADEMIA - In Concert") shouldBe "MY HERO ACADEMIA - In Concert"
    }

    @Test
    fun `stripTitleStatusMarker leaves a sentence that is the notice, and a marker-free title, alone`() {
        stripTitleStatusMarker("Da Konzert von Scarfold und Los Mierda faellt leider aus!") shouldBe
            "Da Konzert von Scarfold und Los Mierda faellt leider aus!"
        stripTitleStatusMarker("Berliner Weisse") shouldBe "Berliner Weisse"
        stripTitleStatusMarker("Abgesagt") shouldBe "Abgesagt"
    }

    // --- parseSchemaEventStatus ---

    @Test
    fun `parseSchemaEventStatus reads the term after the last slash, on either scheme`() {
        parseSchemaEventStatus("https://schema.org/EventCancelled") shouldBe "CANCELLED"
        parseSchemaEventStatus("http://schema.org/EventRescheduled") shouldBe "POSTPONED"
        parseSchemaEventStatus("https://schema.org/EventPostponed") shouldBe "POSTPONED"
        parseSchemaEventStatus("https://schema.org/EventMovedOnline") shouldBe "RELOCATED"
    }

    @Test
    fun `parseSchemaEventStatus falls back to scheduled for an absent or unknown term`() {
        parseSchemaEventStatus(null) shouldBe "SCHEDULED"
        parseSchemaEventStatus("https://schema.org/EventScheduled") shouldBe "SCHEDULED"
        parseSchemaEventStatus("nonsense") shouldBe "SCHEDULED"
    }

    // --- stripRelocationPrefix ---

    // Mikropol writes "verlegt in den", Metropol "Verlegt ins".
    @Test
    fun `stripRelocationPrefix accepts both contractions and either dash`() {
        stripRelocationPrefix("Verlegt ins Bi Nuu – BRKN") shouldBe "BRKN"
        stripRelocationPrefix("-verlegt in den Frannz Club – CULTURE WARS") shouldBe "CULTURE WARS"
        stripRelocationPrefix("VERLEGT INS Astra - HOUSE OF PROTECTION") shouldBe "HOUSE OF PROTECTION"
    }

    @Test
    fun `stripRelocationPrefix leaves a title with no relocation prefix`() {
        stripRelocationPrefix("The Adicts") shouldBe "The Adicts"
        stripRelocationPrefix("Verlegt") shouldBe "Verlegt"
    }

    @Test
    fun `stripRelocationPrefix returns the input when stripping would leave nothing`() {
        stripRelocationPrefix("Verlegt ins Bi Nuu –") shouldBe "Verlegt ins Bi Nuu –"
    }
}
