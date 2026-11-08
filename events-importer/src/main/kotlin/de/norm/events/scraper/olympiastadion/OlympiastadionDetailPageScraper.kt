package de.norm.events.scraper.olympiastadion

import de.norm.events.scraper.paragraphText
import de.norm.events.scraper.parseClockPrefix
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalTime

/** What an event page adds to its listing card. */
data class OlympiastadionDetail(
    val sport: Boolean,
    val description: String?,
    val startTime: LocalTime?,
    val doorsTime: LocalTime?,
    val ticketUrl: String?
)

/**
 * Pure parser for one `/events/<slug>/` page.
 *
 * The hero box holds labelled values: `Datum:`, the start as `Beginn:`, `Uhrzeit:` or `Anstoß:`, and
 * `Stadionöffnung:` for the doors. A value not yet set reads `folgt`. A sport page says so in its kick-off
 * label, in a league under the title, or in the kind heading above the text (`Länderspiel`,
 * `Laufveranstaltung`); a concert's heading is a sentence such as `U2 im Olympiastadion Berlin`.
 * The ticket cards link the outside shop first and the stadium's own VIP shop after it. An `hreflang`
 * link names the English page, whose blurb [description] reads the same way.
 */
class OlympiastadionDetailPageScraper {
    fun scrape(document: Document): OlympiastadionDetail {
        val box = document.select(".heroEvents__box__item").associate { label(it) to value(it) }
        val kind =
            document
                .selectFirst(".twoColTickets__left h2")
                ?.text()
                ?.trim()
                .orEmpty()
        val league = document.selectFirst(".heroEvents__titles h1 > span")?.text()?.trim()
        return OlympiastadionDetail(
            sport = KICK_OFF in box || SPORT_KIND.containsMatchIn(kind) || !league.isNullOrEmpty(),
            description = description(document),
            startTime = START_LABELS.firstNotNullOfOrNull { box[it] }?.let(::parseClockPrefix),
            doorsTime = box[DOORS_LABEL]?.let(::parseClockPrefix),
            ticketUrl =
                document
                    .select(".twoColTickets__right a[href]")
                    .map { it.absUrl("href") }
                    .firstOrNull { it.startsWith("http") && URI(it).host?.endsWith(OWN_HOST) == false }
        )
    }

    /** The blurb beside the ticket cards, on the German or the English page. */
    fun description(document: Document): String? = document.select(".twoColTickets__left .text-m p").paragraphText()

    private fun label(item: Element): String =
        item
            .selectFirst("span")
            ?.text()
            ?.trim()
            ?.removeSuffix(":")
            ?.lowercase()
            .orEmpty()

    private fun value(item: Element): String = item.ownText().trim()

    private companion object {
        const val OWN_HOST = "olympiastadion.berlin"
        const val KICK_OFF = "anstoß"
        const val DOORS_LABEL = "stadionöffnung"
        val START_LABELS = listOf("beginn", "uhrzeit", "showbeginn")

        /** The kind headings of a match or a run; a concert's heading never names one. */
        val SPORT_KIND =
            Regex("""(?<!\p{L})\p{L}*(?:spiel|lauf|marathon|fu(?:ß|ss)ball|football|sport|pokal|finale)\p{L}*""", RegexOption.IGNORE_CASE)
    }
}
