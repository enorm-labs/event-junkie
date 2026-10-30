package de.norm.events.scraper

import de.norm.events.event.ArtistRole
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// Focused tests for SupportLines — one test per behaviour.
class SupportLinesTest {
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

    // --- supportSubtitleLines ---

    @Test
    fun `supportSubtitleLines picks the line carrying a support marker`() {
        supportSubtitleLines(listOf("Tour 2026", "+ Support: Jeff Clarke")) shouldBe "+ Support: Jeff Clarke"
        supportSubtitleLines(listOf("Opener: Warwolf")) shouldBe "Opener: Warwolf"
        supportSubtitleLines(listOf("Special Guest: Motorjesus")) shouldBe "Special Guest: Motorjesus"
    }

    @Test
    fun `supportSubtitleLines returns null when no line carries a marker`() {
        supportSubtitleLines(listOf("Tour 2026")) shouldBe null
        supportSubtitleLines(emptyList()) shouldBe null
    }

    // The reason the function exists: `.text()` flattens a trailing notice onto its own line, and
    // handing the whole subtitle to extractSupportFromSubtitle would capture the notice as an act.
    @Test
    fun `supportSubtitleLines isolates the support line from a trailing cancellation notice`() {
        val lines = listOf("+ Support: Jeff Clarke", "ABGESAGT. Bereits gekaufte Tickets behalten ihre Gültigkeit.")
        supportSubtitleLines(lines) shouldBe "+ Support: Jeff Clarke"
        extractSupportFromSubtitle(supportSubtitleLines(lines)) shouldContainExactly listOf("Jeff Clarke")
    }

    // Lido's XANDRIA subtitle bills a special guest and a support act on two <br> lines (#3104).
    @Test
    fun `supportSubtitleLines keeps every support line and drops the notice between them`() {
        val lines =
            listOf(
                "+ Special Guest: SEVEN SPIRES",
                "VERSCHOBEN vom 12.03.2026",
                "+ Support: SECRET RULE"
            )
        extractSupportFromSubtitle(supportSubtitleLines(lines)) shouldContainExactly listOf("SEVEN SPIRES", "SECRET RULE")
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
    fun `reads every support label in a subtitle and strips it off the act`() {
        extractSupportFromSubtitle("Vorband: Kaos") shouldContainExactly listOf("Kaos")
        extractSupportFromSubtitle("div. Support: Kaos") shouldContainExactly listOf("Kaos")
        stripArtistPrefix("Vorband: Kaos") shouldBe "Kaos"
        headlinersFromTitle("Chelsea Wolfe + Vorband: A.A. Williams").map { it.name to it.role } shouldContainExactly
            listOf("Chelsea Wolfe" to ArtistRole.HEADLINER, "A.A. Williams" to ArtistRole.SUPPORT)
    }
}
