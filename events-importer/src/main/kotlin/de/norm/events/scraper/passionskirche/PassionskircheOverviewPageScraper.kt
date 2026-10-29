package de.norm.events.scraper.passionskirche

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.GERMAN_MONTH_NAMES
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.dateCheckedAgainstWeekday
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanWeekday
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.time.MonthDay

/**
 * Pure parser for `/kommende-events`, an IONOS page with one image gallery per church. Each picture carries a caption
 * in free text, `<title> am <Wochentag>, <date> um <time> Uhr`, a teaser and a link to the event's own page. The date
 * comes as `27. Oktober 2026`, `06. November` or `16.11.2026`; a date without a year takes the nearest one.
 *
 * Halle-Luja also lists the Heilig-Kreuz-Kirche on the page, so a picture counts only under the `PASSIONSKIRCHE`
 * heading. A caption without a readable date is skipped, as is a pop-up choir or a service ([isOutOfScope]).
 */
class PassionskircheOverviewPageScraper(
    private val clock: Clock
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = passionskircheItems(document)
        logger.info { "Found ${items.size} event item(s) under the Passionskirche heading" }
        return items.mapSkippingFailures(logger, "Passionskirche event item") { parseItem(it, baseUrl) }
    }

    /** The gallery pictures after the `PASSIONSKIRCHE` heading and before the next church's heading, in page order. */
    private fun passionskircheItems(document: Document): List<Element> {
        var section: String? = null
        val items = mutableListOf<Element>()
        for (element in document.select("$HEADINGS, .image-gallery-wrapper")) {
            if (element.hasClass("image-gallery-wrapper")) {
                if (section == VENUE_HEADING) items.add(element)
            } else if (element.closest(".image-gallery-wrapper") == null) {
                element
                    .text()
                    .trim()
                    .takeIf { SECTION_HEADING.matches(it) }
                    ?.let { section = it }
            }
        }
        if (section == null) logger.warn { "The Passionskirche listing has no church heading, so no event is read" }
        return items
    }

    private fun parseItem(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val caption = item.selectFirst("p.image-gallery-title")?.text().blankToNull() ?: return null
        val teaser = item.selectFirst(".glightbox-desc p")?.text().blankToNull()
        if (isOutOfScope(caption, teaser)) {
            logger.info { "Passionskirche: '$caption' is a pop-up choir or a service, skipping" }
            return null
        }
        val schedule = CAPTION_DATE.find(caption)
        val date = schedule?.let(::captionDate)
        if (schedule == null || date == null) {
            logger.warn { "Passionskirche: caption '$caption' has no readable date, skipping" }
            return null
        }
        val title = caption.substring(0, schedule.range.first).replace(WHITESPACE, " ").blankToNull() ?: return null
        val url =
            item
                .selectFirst(".glightbox-desc a.custom-link[href]")
                ?.attr("href")
                .blankToNull()
                ?.let { resolveUrl(baseUrl, it) }
        if (url == null) {
            logger.warn { "Passionskirche: '$title' links no event page, skipping" }
            return null
        }
        return ScrapedEvent(
            title = title,
            eventType = inferConcertVenueType(title),
            typeIsFallback = true,
            eventDate = date,
            startTime = labelledClock(caption.substring(schedule.range.last + 1), "um"),
            description = teaser,
            imageUrl = item.selectFirst("a.lightbox[href]")?.absUrl("href").blankToNull(),
            sourceUrl = url,
            sourceId = passionskircheSourceId(url)
        )
    }

    private fun captionDate(match: MatchResult): LocalDate? {
        val groups = match.groups
        val weekday = parseGermanWeekday(groups["weekday"]?.value)
        val month = groups["monthName"]?.value?.let(::germanMonthNumber) ?: groups["month"]?.value?.toInt()
        val monthDay = month?.let { runCatching { MonthDay.of(it, groups["day"]?.value.orEmpty().toInt()) }.getOrNull() } ?: return null
        val year = (groups["year"] ?: groups["year2"])?.value?.toInt()
        val date = if (year == null) inferYearForWeekday(monthDay, weekday, clock) else runCatching { monthDay.atYear(year) }.getOrNull()
        return date?.let { dateCheckedAgainstWeekday(it, weekday, match.value.trim()) }
    }

    private companion object {
        const val VENUE_HEADING = "PASSIONSKIRCHE"

        const val HEADINGS = "h1, h2, h3, h4, h5, h6, p"

        /** A church's heading: one short line in capitals, such as `PASSIONSKIRCHE` or `HEILIG-KREUZ-KIRCHE`. */
        val SECTION_HEADING = Regex("""[\p{Lu}][\p{Lu}\s-]{2,40}""")

        val MONTHS = GERMAN_MONTH_NAMES.joinToString("|")

        /** ` am Dienstag, 27. Oktober 2026`, ` am 06. November`, ` am 16.11.2026`: the caption's date after its title. */
        val CAPTION_DATE =
            Regex(
                """\s+am\s+(?:(?<weekday>\p{L}+),\s*)?(?<day>\d{1,2})\.\s*""" +
                    """(?:(?<monthName>$MONTHS)(?:\s+(?<year>\d{4}))?|(?<month>\d{1,2})\.(?<year2>\d{4})?)""",
                RegexOption.IGNORE_CASE
            )

        fun germanMonthNumber(name: String): Int? = GERMAN_MONTH_NAMES.indexOfFirst { it.equals(name, ignoreCase = true) }.takeIf { it >= 0 }?.plus(1)
    }
}

/**
 * A pop-up choir or a church service, which EVENT_SCOPE.md §3.2 leaves out: things a visitor takes part in. The Yes Choir
 * evening teaches the audience a song to sing together and calls itself a `Pop-up-Chor`. A concert the audience sings
 * along to stays, so `Mitsing` alone does not drop a caption.
 */
internal fun isOutOfScope(
    caption: String,
    teaser: String?
): Boolean = OUT_OF_SCOPE.containsMatchIn("$caption ${teaser.orEmpty()}")

private val OUT_OF_SCOPE = Regex("""pop-?up-?chor|gottesdienst|andacht""", RegexOption.IGNORE_CASE)

/** The event page's path, `/the-high-kings`, the same in the listing link and on the page. */
internal fun passionskircheSourceId(url: String): String = "${EventSource.PASSIONSKIRCHE.sourceIdPrefix}${URI(url).path.trim('/')}"
