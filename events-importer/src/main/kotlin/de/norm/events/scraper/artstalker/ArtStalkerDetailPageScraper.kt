package de.norm.events.scraper.artstalker

import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.knownGenresInStyleTail
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.parseClockPrefix
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseLabelledPrices
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import org.jsoup.nodes.Document

/**
 * Pure parser for one shop event page, `/tickets-<slug>/e<id>`. The venue writes one free-text
 * block: the blurb, then a practical block opened by the date or a "Beginn" / "Einlass" line with
 * the prices ("VVK 12,-€ / AK 15,-€", "Ticket 7 €", "Eintritt Frei"), then house rules and the
 * address. The blurb ends where the practical block starts; doors, prices and free entry are read
 * from the practical block only, so a blurb's "ab 10 Personen" is never one.
 */
class ArtStalkerDetailPageScraper {
    fun scrape(
        document: Document,
        url: String
    ): ScrapedEvent? {
        val id = SHOP_EVENT_ID.find(url)?.groupValues?.get(1)
        val rawTitle = document.textAt("h1")
        val dateTime = document.selectFirst("time.c-event-date[datetime]")?.attr("datetime").orEmpty()
        val date = parseIsoDate(dateTime)
        if (id == null || rawTitle == null || date == null) return null
        val lines = document.selectFirst(".c-compact-info__event-text .c-text__paragraph")?.textLines().orEmpty()
        val practicalAt = lines.indexOfFirst { PRACTICAL_LINE.containsMatchIn(it) }
        val blurb = if (practicalAt >= 0) lines.take(practicalAt) else lines
        val practical = if (practicalAt >= 0) lines.drop(practicalAt).joinToString("\n") else ""
        val labelled = parseLabelledPrices(practical)
        val single = SINGLE_PRICE.find(practical)?.let { euroAmounts(it.value).firstOrNull() }
        val presale = labelled.presale ?: single
        val boxOffice = labelled.boxOffice ?: single
        val (title, subtitle) = splitTagline(rawTitle)
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            genre = knownGenresInStyleTail(subtitle),
            description =
                blurb
                    .filterNot { CREDIT_OR_LINK.matches(it) }
                    .joinToString("\n")
                    .trim()
                    .ifEmpty { null },
            eventDate = date,
            doorsTime = labelledClock(practical, DOORS_LABELS),
            startTime = parseClockPrefix(dateTime.substringAfter("T")),
            imageUrl = document.imgSrcAt(".c-ticket-fan img"),
            sourceUrl = url,
            sourceId = "${EventSource.ART_STALKER.sourceIdPrefix}$id",
            ticketUrl = url,
            pricePresale = presale,
            priceBoxOffice = boxOffice,
            // A priced night still says "Auftretende Musiker haben freien Eintritt" for its players.
            free = presale == null && boxOffice == null && hasFreeEntryPhrase(practical)
        )
    }

    private companion object {
        /** The first line of the practical block: a weekday date, or a time label at the start of the line. */
        val PRACTICAL_LINE =
            Regex(
                """^(?:(?:montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\s+\d|(?:beginn|start|einlass)\b)""",
                RegexOption.IGNORE_CASE
            )

        /** One price with no presale or box-office label: `Ticket 7 €`, `Ticket: 5,-€`, `Eintritt 10 €`. */
        val SINGLE_PRICE = Regex("""(?:tickets?|eintritt)\s*:?\s*\d+(?:,\d{2}|,-)?\s*(?:€|euro)""", RegexOption.IGNORE_CASE)

        /** A photo credit or a bare link, which the blurb carries and a reader of ours does not need. */
        val CREDIT_OR_LINK = Regex("""^(?:fotos?\s*:.*|https?://\S+)$""", RegexOption.IGNORE_CASE)
    }
}
