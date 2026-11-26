package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class DisclosurePanelTest {
    private val buttonSelector = "button.acc-button[aria-controls]"

    @Test
    fun `the labelled button names the region it opens`() {
        val document =
            Jsoup.parse(
                """
                <button class="acc-button" aria-controls="rules">Hinweis zur Einlasskontrolle</button>
                <div id="rules">Taschen bleiben draußen.</div>
                <button class="acc-button" aria-controls="blurb">Beschreibung</button>
                <div id="blurb">Die Band kommt zurück.</div>
                """.trimIndent()
            )

        document.disclosurePanel(buttonSelector, "Beschreibung")?.text() shouldBe "Die Band kommt zurück."
    }

    @Test
    fun `the label is compared without case, and a nested badge stays out of it`() {
        val document =
            Jsoup.parse(
                """
                <button class="acc-button" aria-controls="blurb">  BESCHREIBUNG <span>(3)</span></button>
                <div id="blurb">Der Abend.</div>
                """.trimIndent()
            )

        document.disclosurePanel(buttonSelector, "Beschreibung")?.text() shouldBe "Der Abend."
    }

    @Test
    fun `a page with no such button, a blank target and a dangling id all read as nothing`() {
        Jsoup
            .parse("""<button class="acc-button" aria-controls="rules">Hinweis</button><div id="rules">x</div>""")
            .disclosurePanel(buttonSelector, "Beschreibung")
            .shouldBeNull()
        Jsoup
            .parse("""<button class="acc-button" aria-controls=" ">Beschreibung</button>""")
            .disclosurePanel(buttonSelector, "Beschreibung")
            .shouldBeNull()
        Jsoup
            .parse("""<button class="acc-button" aria-controls="gone">Beschreibung</button>""")
            .disclosurePanel(buttonSelector, "Beschreibung")
            .shouldBeNull()
    }
}
