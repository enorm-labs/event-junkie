package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SubtitleNoticeTest {
    // Subtitles from Lido's home page on 2026-10-14 (#3282).
    @Test
    fun `splits a relocation banner after a blank line from the support line`() {
        splitSubtitleNotice(
            listOf("+ Support: PORTRAYAL OF GUILT", "", "HOCHVERLEGT INS KESSELHAUS. BEREITS ERWORBENE TICKETS BEHALTEN IHRE GÜLTIGKEIT!", "", "")
        ) shouldBe
            ("+ Support: PORTRAYAL OF GUILT" to "HOCHVERLEGT INS KESSELHAUS. BEREITS ERWORBENE TICKETS BEHALTEN IHRE GÜLTIGKEIT!")
    }

    @Test
    fun `keeps a shouted subtitle that carries no notice word`() {
        splitSubtitleNotice(listOf("HEAVY ROCK DISCOTHEQUE")) shouldBe ("HEAVY ROCK DISCOTHEQUE" to null)
        splitSubtitleNotice(listOf("„ULTRA RARE TOUR 2027\"")) shouldBe ("„ULTRA RARE TOUR 2027\"" to null)
    }

    @Test
    fun `takes a shouted line with a notice word as the notice without a blank line before it`() {
        splitSubtitleNotice(listOf("DIE SHOW IST RESTLOS AUSVERKAUFT UND ES WIRD KEINE TICKETS AN DER ABENDKASSE GEBEN!")) shouldBe
            (null to "DIE SHOW IST RESTLOS AUSVERKAUFT UND ES WIRD KEINE TICKETS AN DER ABENDKASSE GEBEN!")
    }

    @Test
    fun `keeps a support line that names a notice word in lower case`() {
        splitSubtitleNotice(listOf("+ Support: Verlegt Band")) shouldBe ("+ Support: Verlegt Band" to null)
    }
}
