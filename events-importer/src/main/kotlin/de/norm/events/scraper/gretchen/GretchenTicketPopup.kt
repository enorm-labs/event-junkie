package de.norm.events.scraper.gretchen

import org.jsoup.Jsoup

/**
 * The ticket shop a Gretchen night's `TICKETS` popup names, or `null` when it names none.
 *
 * The popup is the answer to a form POST (`list_id=<id>`), one `.ticket_item` per shop with a link,
 * a price and a condition. The club lists its own presale shop first (tixforgigs on most nights), so
 * the first link that is not Resident Advisor wins, and Resident Advisor only when it is the one shop.
 */
internal fun parseTicketPopup(html: String): String? {
    val links =
        Jsoup
            .parseBodyFragment(html)
            .select(".ticket_item a[href]")
            .map { it.attr("href").trim() }
            .filter { it.startsWith("http") }
    return links.firstOrNull { !RESIDENT_ADVISOR.containsMatchIn(it) } ?: links.firstOrNull()
}

private val RESIDENT_ADVISOR = Regex("""^https?://(?:[\w-]+\.)?ra\.co/""", RegexOption.IGNORE_CASE)
