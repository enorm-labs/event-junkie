package de.norm.events.wikimedia

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CommonsCreditTest {
    @Test
    fun `strips the link Commons wraps the author in, and a Photo label`() {
        CommonsCredit.plain("<a rel=\"nofollow\" class=\"external text\" href=\"https://www.flickr.com/people/x\">Paul Hudson</a> from United Kingdom") shouldBe
            "Paul Hudson from United Kingdom"
        CommonsCredit.plain("Photo: Andreas Praefcke") shouldBe "Andreas Praefcke"
        CommonsCredit.plain("Foto: <b>Sven &amp; Ute</b>") shouldBe "Sven & Ute"
        CommonsCredit.plain(null) shouldBe ""
    }

    @Test
    fun `only the one boilerplate phrase names nobody`() {
        CommonsCredit.namesAnAuthor("No machine-readable author provided. Foo~commonswiki assumed (based on copyright claims).") shouldBe false
        CommonsCredit.namesAnAuthor("") shouldBe false
        // A real photographer's Flickr handle; a wider list would refuse them.
        CommonsCredit.namesAnAuthor("Uploaded") shouldBe true
    }

    @Test
    fun `the licence map is written out, never derived`() {
        CommonsLicences.spdxOf("CC BY-SA 4.0") shouldBe "CC-BY-SA-4.0"
        CommonsLicences.spdxOf("CC BY 3.0 de") shouldBe "CC-BY-3.0-DE"
        CommonsLicences.spdxOf("Public domain") shouldBe "PD"
        CommonsLicences.spdxOf("FAL") shouldBe "LAL-1.3"
        CommonsLicences.spdxOf("Attribution") shouldBe "LicenseRef-Commons-Attribution"
        // A template Creative Commons never published must not become an identifier by pattern.
        CommonsLicences.spdxOf("CC BY-SA 5.0").shouldBeNull()
        CommonsLicences.spdxOf("GFDL").shouldBeNull()
        CommonsLicences.spdxOf(null).shouldBeNull()
    }
}
