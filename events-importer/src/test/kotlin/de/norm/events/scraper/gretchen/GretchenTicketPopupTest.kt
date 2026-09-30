package de.norm.events.scraper.gretchen

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GretchenTicketPopupTest {
    private fun item(href: String) = """<div class="ticket_item"><div class="vvk_button popupitem"><p><a href="$href">shop</a></p></div></div>"""

    @Test
    fun `takes the first shop, and Resident Advisor only when it is the one shop`() {
        parseTicketPopup(item("https://ra.co/events/1") + item("https://www.tixforgigs.com/Event/2")) shouldBe
            "https://www.tixforgigs.com/Event/2"
        parseTicketPopup(item("https://www.eventim.de/event/3") + item("https://dice.fm/event/4")) shouldBe "https://www.eventim.de/event/3"
        parseTicketPopup(item("https://de.ra.co/events/5")) shouldBe "https://de.ra.co/events/5"
    }

    @Test
    fun `names no shop for an empty popup or a link that goes nowhere`() {
        parseTicketPopup("""<div id="popup"><div class="popupcontainer"></div></div>""").shouldBeNull()
        parseTicketPopup(item("#")).shouldBeNull()
        parseTicketPopup("").shouldBeNull()
    }
}
