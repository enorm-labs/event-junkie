package de.norm.events.scraper.urania

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for a Urania `/event/<slug>/` page.
 *
 * The page restates everything the calendar shows and adds the three things it cannot: the poster,
 * the full prose, and the admission line at the foot of that prose
 * (`"Eintritt: 8 €, ermäßigt: 5 €, Mitglieder: 3 €"`, or `"Eintritt frei"`).
 *
 * Two details are specific to this template. The poster is **lazy-loaded**, so its URL is in
 * `data-src` and never in `src` — reading `src` yields nothing. And the page opens with an `h2`
 * intro summarising the evening, which is not repeated in the body, so it is kept at the head of
 * the description rather than dropped.
 *
 * @see UraniaCalendarPageScraper for the calendar (discovery, date, start time).
 * @see UraniaWebsiteImporter for the HTTP fetch orchestrator.
 */
class UraniaEventPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event page into a [ScrapedEvent], or `null` when it carries no heading — the marker
     * that the request did not return a real event page.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and for its `sourceId`.
     */
    @Suppress("ReturnCount") // A guard clause for the missing heading is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val title = document.textAt("h1.c-event-article_content_title")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "Urania event page at $sourceUrl has no heading, skipping" }
            return null
        }
        val slug = extractEventSlug(sourceUrl, "/event/")
        val format = document.textAt("h6.c-event-article_content_format")
        val body = document.textAt(".c-text-box")
        val admissionLine = admissionLineOf(body)
        val price = uraniaPrice(admissionLine)

        return ScrapedEvent(
            title = title,
            subtitle = uraniaSubtitle(series = document.textAt(".c-event-article_content_group"), format = format),
            description = joinIntroAndBody(document.textAt("h2.c-event-article_content_intro"), body),
            eventType = uraniaEventType(format),
            eventDate = parseDateLine(document.textAt("h4.c-event-article_content_date-time")) ?: UNRESOLVED_EVENT_DATE,
            startTime = parseTimeLine(document.textAt("h4.c-event-article_content_date-time")),
            // The poster is lazy-loaded: its URL is in `data-src` and never in `src`.
            imageUrl = document.attrAt(".c-event-article_header .c-image img", "data-src"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.URANIA.sourceIdPrefix}$slug",
            ticketUrl = document.hrefAt("a.c-event-article_content_ticket"),
            pricePresale = price,
            priceNote = admissionLine,
            free = detectFree(pricePresale = price, priceNote = admissionLine, title = title),
            artists = uraniaSpeakers(document.textAt("p.c-event-article_content_artist"))
        )
    }

    /** Reads the date out of the page's `"Do, 03.09.2026 | 19:30 Uhr"` line. */
    private fun parseDateLine(line: String?): LocalDate? = parseGermanDate(DOTTED_DATE.find(line.orEmpty())?.value)

    /** Reads the clock out of the same line. */
    private fun parseTimeLine(line: String?): LocalTime? = parseTime(CLOCK.find(line.orEmpty())?.value)
}

/**
 * The venue's admission line, taken from the foot of the prose where it states one.
 *
 * The line is matched on the `Eintritt` label rather than on a euro sign, so `"Eintritt frei"` —
 * which carries no figure — is captured too and can drive the free-entry flag.
 */
internal fun admissionLineOf(body: String?): String? =
    ADMISSION_LINE
        .find(body.orEmpty())
        ?.value
        ?.trim()
        ?.trimEnd('.', ' ')
        ?.takeIf { it.isNotBlank() }

/** Puts the page's one-line intro ahead of the prose, which does not repeat it. */
private fun joinIntroAndBody(
    intro: String?,
    body: String?
): String? =
    listOfNotNull(intro?.takeIf { it.isNotBlank() }, body?.takeIf { it.isNotBlank() })
        .joinToString("\n\n")
        .takeIf { it.isNotBlank() }

/** `"03.09.2026"` inside the page's date line. */
private val DOTTED_DATE = Regex("""\d{1,2}\.\d{1,2}\.\d{4}""")

/** `"19:30"` inside the same line. */
private val CLOCK = Regex("""\d{1,2}:\d{2}""")

/** The `"Eintritt …"` sentence at the foot of the prose, ending at the next sentence or line. */
private val ADMISSION_LINE = Regex("""Eintritt[^.\n]*""", RegexOption.IGNORE_CASE)
