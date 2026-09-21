package de.norm.events.scraper.voidclub

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Pure HTML parser for VOID Club's homepage programme.
 *
 * Every night is an `article.void-event-card`: a `.void-event-date` calendar block, the room(s)
 * in use (`.void-event-venue`), one or more `.void-event-genre` tags, a `.void-event-title`, a
 * `.void-event-lineup` paragraph per note and per DJ billing, and a Resident Advisor
 * `a.void-event-button`. No times, prices, descriptions or per-event page, so an event's
 * identity is its date plus slugified title, and every night stores a bare date.
 *
 * 1. **The date carries no year** and the programme runs across the turn of the year, so the
 * year comes from the stated weekday ([inferYearForWeekday]). The date is stated twice — an
 * `aria-label` on the calendar block and the rendered day/number/month spans — and the
 * accessible label is read first (ADR-007 puts ARIA above class names), the spans as fallback.
 * 2. **`.void-event-lineup` is used for two things**: the DJ billing, introduced by a `WITH` /
 * `LINEUP` / `LINE-UP` label, and a standalone note naming what the night is part of ("RAVE
 * THE PLANET AFTER PARTY"). Only the labelled billing is read as acts; the note is the subtitle.
 * 3. **A night may carry two buttons**: the ticket link and the guestlist raffle
 * (`/guestlistNN.html`). Only the former is a ticket URL, and its label is the page's only
 * free-entry signal — "Free Tickets & Info" for a free night, "Tickets & Info" otherwise.
 *
 * @see VoidClubWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.void-club.de/">VOID Club Berlin</a>
 */
class VoidClubOverviewPageScraper(
    /** Clock for the year inference, in the venue's time zone; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event cards from the homepage, in listing order.
     *
     * @param baseUrl the URL the document was fetched from, stored as each event's
     * [ScrapedEvent.sourceUrl] — no per-event page.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val cards = document.select("article.void-event-card")
        logger.info { "Found ${cards.size} event card(s) on VOID Club homepage" }
        val teasers = teaserImages(document, baseUrl)

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed cards without aborting the whole import
        return cards.mapNotNull { card ->
            try {
                parseCard(card, baseUrl, teasers)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse VOID Club event card, skipping" }
                null
            }
        }
    }

    /** Parses one card into a [ScrapedEvent], or `null` without a title or usable date. */
    @Suppress("ReturnCount") // Guard clauses for the required title/date are clearer than nesting
    private fun parseCard(
        card: Element,
        baseUrl: String,
        teasers: Map<String, String>
    ): ScrapedEvent? {
        val title = card.textAt(".void-event-title")?.let(::cleanEventTitle) ?: return null
        val eventDate = parseCardDate(card)
        if (eventDate == null) {
            logger.warn { "No parseable date for VOID Club event '$title', skipping" }
            return null
        }

        val ticketLink = card.selectFirst(TICKET_LINK)
        val ticketUrl = ticketLink?.attr("href")?.takeIf { it.isNotBlank() }?.let { resolveUrl(baseUrl, it) }
        val (billings, notes) = card.select(".void-event-lineup").partition(::isBilling)

        return ScrapedEvent(
            title = title,
            subtitle =
                notes
                    .firstOrNull()
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() },
            // A techno club stating no category; `.void-event-genre` names the music, `.void-event-venue`
            // the room(s) — neither a kind of event.
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            imageUrl = ticketUrl?.let(teasers::get),
            // No per-event page, so every night points at the programme and takes its identity from date
            // plus slugified title.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.VOID_CLUB.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = ticketUrl,
            genre =
                card
                    .select(".void-event-genre")
                    .eachText()
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                    .ifBlank { null },
            // The ticket button is prefixed "Free" for a free night and no prices print anywhere, so the
            // button's wording is the only free-entry signal.
            free = ticketLink?.text()?.startsWith("Free", ignoreCase = true) == true,
            artists = parseLineup(billings.firstOrNull(), roomOf(card))
        )
    }

    /** Whether a `.void-event-lineup` paragraph opens with a billing label rather than being a note. */
    private fun isBilling(paragraph: Element): Boolean = BILLING_LABEL.matches(paragraph.textAt(".void-event-label").orEmpty())

    /**
     * The card's date, preferring the calendar block's `aria-label` ("Friday, August 7") over the
     * rendered `FRI` / `07` / `AUG` spans. Both spell the same year-less date; the label is tried
     * first because ADR-007 ranks an ARIA attribute above a class name, and the spans keep the
     * scraper working if the label is ever dropped.
     */
    private fun parseCardDate(card: Element): LocalDate? =
        parseWeekdayDate(card.attrAt(".void-event-date", "aria-label"), LABEL_DATE_FORMATTER)
            ?: parseWeekdayDate(renderedDate(card), RENDERED_DATE_FORMATTER)

    /** The rendered calendar block re-joined into one `FRI 07 AUG` string, or `null` if a part is missing. */
    @Suppress("ReturnCount") // Guard clauses for the three missing parts are clearer than nesting
    private fun renderedDate(card: Element): String? {
        val weekday = card.textAt(".void-event-day") ?: return null
        val day = card.textAt(".void-event-number") ?: return null
        val month = card.textAt(".void-event-month") ?: return null
        return "$weekday $day $month"
    }

    /** Parses a year-less weekday + month + day rendering, year from the weekday. */
    @Suppress("ReturnCount") // Guard clauses for the blank / unparseable input are clearer than nesting
    private fun parseWeekdayDate(
        text: String?,
        formatter: DateTimeFormatter
    ): LocalDate? {
        if (text.isNullOrBlank()) return null
        val parsed = runCatching { formatter.parse(text.trim()) }.getOrNull() ?: return null
        return runCatching {
            val monthDay = MonthDay.of(parsed.get(ChronoField.MONTH_OF_YEAR), parsed.get(ChronoField.DAY_OF_MONTH))
            inferYearForWeekday(monthDay, DayOfWeek.of(parsed.get(ChronoField.DAY_OF_WEEK)), clock)
        }.getOrNull()
    }

    /**
     * The night's DJs from its labelled `.void-event-lineup` paragraph.
     *
     * Acts are comma-separated, so a comma is the only boundary honoured — a `&` inside one
     * segment belongs to the act's name ("Skulder & Mully", billed alone elsewhere in the same
     * programme), which is why this does not split on conjunctions the way Kater's and Renate's
     * per-floor lines do. A `b2b` *within* a segment does open a second slot, unless the segment
     * is parenthesised — the brackets hold a duo's members, Kater's guard.
     *
     * An act billed twice would produce two `event_artist` rows for one (event, artist) pair and
     * hit the unique constraint, failing the whole import, so the first billing wins.
     */
    private fun parseLineup(
        paragraph: Element?,
        stage: String?
    ): List<ScrapedArtist> {
        if (paragraph == null) return emptyList()
        return paragraph
            .clone()
            .also { it.select(".void-event-label").remove() }
            .text()
            .split(',')
            .flatMap(::splitActs)
            .distinctBy { it.lowercase() }
            .map { ScrapedArtist(name = it, role = "DJ", stage = stage) }
    }

    /**
     * The room the night's acts play in, or `null` across both. `.void-event-venue` names `VOID
     * CLUB`, `VOID HALL`, or `VOID CLUB & HALL`. A single-room night puts every act in that room;
     * a two-room night does not say who plays where, so no act is attributed.
     */
    private fun roomOf(card: Element): String? = card.textAt(".void-event-venue")?.takeIf { '&' !in it }

    /**
     * Splits one comma segment into its acts, dropping placeholders. An unfinished billing closes
     * with a continuation phrase ("… Lepido and more", "… DaSoMaZo — more to be announced"), and a
     * whole unannounced lineup is "TBA" or "To be announced" — [UNANNOUNCED_TAIL] cuts all of those
     * off, leaving nothing when the segment was only the placeholder. Each act then goes through
     * [stripArtistSuffix] for the spellings the shared rule knows and this one does not: the live
     * page closed a billing with `Seimen Dexter — more TBA` and the placeholder rode along (#1564).
     */
    private fun splitActs(segment: String): List<String> {
        val act = segment.replace(UNANNOUNCED_TAIL, "").trim()
        return (if ('(' in act) listOf(act) else act.split(B2B_SEPARATOR))
            .map { stripArtistSuffix(it) }
            .filter { it.isNotBlank() && !isNonArtistName(it) }
    }

    /**
     * Per-event teaser images from the hero slider, keyed by the Resident Advisor URL each slide
     * links to. The cards carry no image; the slider re-links the soonest few events by ticket
     * URL, which matches a slide's `img` back to its card.
     */
    private fun teaserImages(
        document: Document,
        baseUrl: String
    ): Map<String, String> =
        document
            .select("a.void-top-event-slide[href]")
            .mapNotNull { slide ->
                val image = slide.attrAt("img", "src") ?: return@mapNotNull null
                resolveUrl(baseUrl, slide.attr("href")) to resolveUrl(baseUrl, image)
            }.toMap()

    private companion object {
        /** The calendar block's accessible label, e.g. `Friday, August 7`. */
        val LABEL_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("EEEE, MMMM d")
                .toFormatter(Locale.ENGLISH)

        /** The rendered calendar spans re-joined, e.g. `FRI 07 AUG` — shouted, hence case-insensitive. */
        val RENDERED_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("EEE d MMM")
                .toFormatter(Locale.ENGLISH)

        /** The ticket button — the venue's own guestlist raffle uses the same class and is not one. */
        const val TICKET_LINK = "a.void-event-button:not([href*=guestlist])"

        /**
         * The label opening the DJ billing, in each spelling (`WITH`, `LINEUP`, `LINE-UP`). Any other
         * label — `RAVE THE PLANET AFTER PARTY` — makes the paragraph a note.
         */
        val BILLING_LABEL = Regex("""with|line\s?-?\s?up""", RegexOption.IGNORE_CASE)

        /** The back-to-back marker joining two DJs into one slot. */
        val B2B_SEPARATOR = Regex("""\s+b2b\s+""", RegexOption.IGNORE_CASE)

        /** The venue's "the rest of the lineup isn't fixed yet" phrasings, with any dash lead-in. */
        val UNANNOUNCED_TAIL =
            Regex(
                """\s*[—–-]*\s*\b(?:and\s+more|more\s+to\s+be\s+announced|to\s+be\s+announced)\b.*$""",
                RegexOption.IGNORE_CASE
            )
    }
}
