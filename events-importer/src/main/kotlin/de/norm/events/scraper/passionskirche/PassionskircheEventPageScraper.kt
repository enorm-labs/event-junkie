package de.norm.events.scraper.passionskirche

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.GERMAN_MONTH_NAMES
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.dateCheckedAgainstWeekday
import de.norm.events.scraper.extractSupportFromSubtitle
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isFestivalTitle
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.parseGermanWeekday
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate

/**
 * Pure parser for one Passionskirche event page: an `h1`, the text, then an `Ablauf:` or `Konzertablauf:` paragraph
 * with the date (`Sonntag, 20. Dezember 2026`), `Einlass:` and `Beginn:` lines and the ticket link. The listing's
 * caption can disagree with this block (`20. November` for a concert on 20 December), so the page wins.
 *
 * An act comes from the head of the title only when the page's text names it too: `Wintertales - from around the world`
 * names a programme, and `Maggie Riley` misspells the Maggie Reilly the text describes. A festival title bills no act; the
 * import types it a festival.
 */
class PassionskircheEventPageScraper {
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val title =
            document
                .selectFirst("main h1")
                ?.text()
                ?.let(::plainText)
                .blankToNull() ?: return null
        val paragraphs = document.select("main .text-root p")
        val anchor = paragraphs.indexOfFirst { SCHEDULE_HEADING.matches(plainText(it.text())) }
        val text = if (anchor < 0) paragraphs else paragraphs.subList(0, anchor)
        val schedule = if (anchor < 0) emptyList() else paragraphs.subList(anchor + 1, paragraphs.size)
        val description = text.mapNotNull { htmlParagraphText(it.html()) }.joinToString("\n").blankToNull()
        val scheduleText = schedule.joinToString("\n") { plainText(it.text()) }
        val eventType = inferConcertVenueType(title)
        return ScrapedEvent(
            title = title,
            eventType = eventType,
            typeIsFallback = true,
            eventDate = schedule.firstNotNullOfOrNull { scheduleDate(plainText(it.text())) } ?: UNRESOLVED_EVENT_DATE,
            doorsTime = labelledClock(scheduleText, DOORS_LABELS),
            startTime = labelledClock(scheduleText, START_LABELS),
            description = description,
            imageUrl = document.selectFirst("main img.image-img[src]")?.absUrl("src").blankToNull(),
            ticketUrl = ticketUrl(schedule, sourceUrl),
            sourceUrl = sourceUrl,
            sourceId = passionskircheSourceId(sourceUrl),
            artists = if (eventType == EventType.CONCERT.name && !isFestivalTitle(title)) namedActs(title, description) else emptyList()
        )
    }

    private fun ticketUrl(
        schedule: List<Element>,
        sourceUrl: String
    ): String? {
        val host = URI(sourceUrl).host
        return schedule
            .flatMap { it.select("a[href]") }
            .map { it.absUrl("href") }
            .firstOrNull { it.startsWith("http") && URI(it).host != host }
    }

    private fun namedActs(
        title: String,
        description: String?
    ): List<ScrapedArtist> {
        val text = description?.lowercase() ?: return emptyList()
        val heads = headlinersFromTitle(title.split(TITLE_TAIL, limit = 2).first())
        val support = extractSupportFromSubtitle(title).map { ScrapedArtist(name = it, role = ArtistRole.SUPPORT, titleDerived = true) }
        return (heads + support).filter { it.name.lowercase() in text }
    }

    private companion object {
        val SCHEDULE_HEADING = Regex("""\p{L}*ablauf\s*:?""", RegexOption.IGNORE_CASE)

        /** Where the tour or programme name begins: a spaced dash, or a quote. */
        val TITLE_TAIL = Regex("""\s+[–—-]\s+|\s*[„“"]""")

        val SCHEDULE_DATE =
            Regex(
                """^(?:(?<weekday>\p{L}+),\s*)?(?<day>\d{1,2})\.\s*(?<month>${GERMAN_MONTH_NAMES.joinToString("|")})\s+(?<year>\d{4})""",
                RegexOption.IGNORE_CASE
            )

        fun plainText(text: String): String = text.replace(' ', ' ').replace(WHITESPACE, " ").trim()

        fun scheduleDate(line: String): LocalDate? {
            val match = SCHEDULE_DATE.find(line) ?: return null
            val month = GERMAN_MONTH_NAMES.indexOfFirst { it.equals(match.groups["month"]?.value, ignoreCase = true) } + 1
            val date =
                runCatching {
                    LocalDate.of(
                        match.groups["year"]
                            ?.value
                            .orEmpty()
                            .toInt(),
                        month,
                        match.groups["day"]
                            ?.value
                            .orEmpty()
                            .toInt()
                    )
                }.getOrNull()
            return date?.let { dateCheckedAgainstWeekday(it, parseGermanWeekday(match.groups["weekday"]?.value), line) }
        }
    }
}
