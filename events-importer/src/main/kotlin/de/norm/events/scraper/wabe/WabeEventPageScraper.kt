package de.norm.events.scraper.wabe

import de.norm.events.scraper.ScrapedEvent
import org.jsoup.nodes.Document

/**
 * Parser for a WABE event page (`/okt-2026/3/`): the prose paragraphs as the description and the
 * `Tickets online kaufen` button as the ticket link. The page repeats the date, billing and prices
 * of the month page, which stays the source for those. The house notices every page carries (the
 * stairs, the box office, reservations) are left out.
 */
class WabeEventPageScraper {
    fun scrape(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent? {
        val content = document.selectFirst("#content_area") ?: return null
        val description =
            content
                .select(".j-text p")
                .map { it.text().replace('\u00A0', ' ').trim() }
                .filter { it.length >= MIN_PROSE_LENGTH && !HOUSE_NOTICE.containsMatchIn(it) }
                .distinct()
                .joinToString("\n\n")
                .ifEmpty { null }
        val ticketUrl =
            content
                .select("a.j-calltoaction-link[href^=http]")
                .firstOrNull { it.text().contains("ticket", ignoreCase = true) }
                ?.attr("href")
                ?.replace(GOOGLE_LINKER, "")
        if (description == null && ticketUrl == null) return null
        return event.copy(description = description ?: event.description, ticketUrl = ticketUrl ?: event.ticketUrl)
    }

    private companion object {
        /** Shorter paragraphs are the page's labels and cast lines, not prose. */
        const val MIN_PROSE_LENGTH = 100

        /** The `_gl` parameter Google's cross-domain linker appends to some ticket links. */
        val GOOGLE_LINKER = Regex("""[?&]_gl=[^&]*""")

        val HOUSE_NOTICE = Regex("""barrierefrei|abendkasse|kartenreservierung|preisgestaltung|kultureinrichtung|cookie""", RegexOption.IGNORE_CASE)
    }
}
