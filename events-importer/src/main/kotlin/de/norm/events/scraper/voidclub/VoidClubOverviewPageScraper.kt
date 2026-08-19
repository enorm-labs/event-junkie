package de.norm.events.scraper.voidclub

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/** Time zone the venue programmes in — used to infer the year of its year-less dates. */
private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * Pure HTML parser for VOID Club's homepage programme.
 *
 * Every night is an `article.void-event-card` carrying a `.void-event-date` calendar block, the
 * room(s) in use (`.void-event-venue`), one or more `.void-event-genre` tags, a
 * `.void-event-title`, a `.void-event-lineup` paragraph per note and per DJ billing, and a
 * Resident Advisor `a.void-event-button`. The venue publishes no times, no prices, no descriptions
 * and no per-event page, so an event's identity is its date plus its slugified title, and every
 * night stores a bare date.
 *
 * Three things about this page shape the parser:
 *
 * 1. **The date carries no year** and the programme runs across the turn of the year, so the year
 *    is inferred from the stated weekday ([inferYearForWeekday]). The venue states the date twice —
 *    once as an `aria-label` on the calendar block, once as the rendered day/number/month spans —
 *    and the accessible label is read first (ADR-007 puts ARIA above class names) with the spans as
 *    the fallback.
 * 2. **`.void-event-lineup` is used for two different things**: the DJ billing, introduced by a
 *    `WITH` / `LINEUP` / `LINE-UP` label, and a standalone note naming what the night is part of
 *    ("RAVE THE PLANET AFTER PARTY"). Only the labelled billing is read as acts; the note becomes
 *    the subtitle.
 * 3. **A night may carry two buttons**: the ticket link and the venue's own guestlist raffle
 *    (`/guestlistNN.html`). Only the former is a ticket URL, and its label is also the page's only
 *    free-entry signal — the venue writes "Free Tickets & Info" for a free night and
 *    "Tickets & Info" otherwise.
 *
 * @see VoidClubWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.void-club.de/">VOID Club Berlin</a>
 */
class VoidClubOverviewPageScraper(
    /** Clock for the year inference. Defaults to the venue's own time zone; override in tests for determinism. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event cards from the homepage document.
     *
     * @param baseUrl the URL the document was fetched from, stored as each event's
     *   [ScrapedEvent.sourceUrl] — the venue publishes no per-event page.
     * @return a list of [ScrapedEvent] instances, in listing order.
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

    /** Parses one card into a [ScrapedEvent], or `null` when it has no title or usable date. */
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
            // VOID is a techno club and states no category; `.void-event-genre` names the music,
            // and `.void-event-venue` the room(s) in use — neither is a kind of event.
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            imageUrl = ticketUrl?.let(teasers::get),
            // No per-event page exists, so every night points at the programme and takes its
            // identity from the date plus the slugified title.
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
            // The venue prefixes the ticket button with "Free" for a free night and prints no
            // prices anywhere, so the button's own wording is the only free-entry signal.
            free = ticketLink?.text()?.startsWith("Free", ignoreCase = true) == true,
            artists = parseLineup(billings.firstOrNull(), roomOf(card))
        )
    }

    /** Whether a `.void-event-lineup` paragraph opens with a billing label rather than being a note. */
    private fun isBilling(paragraph: Element): Boolean = BILLING_LABEL.matches(paragraph.textAt(".void-event-label").orEmpty())

    /**
     * Reads the card's date, preferring the calendar block's `aria-label` ("Friday, August 7") over
     * the rendered `FRI` / `07` / `AUG` spans.
     *
     * Both spell the same year-less date, so either resolves it; the accessible label is tried
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

    /** Parses a year-less weekday + month + day rendering, inferring the year from the weekday. */
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
     * Reads the night's DJs from its labelled `.void-event-lineup` paragraph.
     *
     * The venue separates acts with commas, so a comma is the only act boundary honoured — a `&`
     * inside one comma segment belongs to the act's own name ("Skulder & Mully", billed on its own
     * elsewhere in the same programme), which is why this does not split on conjunctions the way
     * Kater's and Renate's per-floor lines do. A `b2b` marker *within* a segment does open a second
     * slot, unless the segment is parenthesised — there the brackets hold a duo's members rather
     * than a co-bill, the same guard Kater applies.
     *
     * An act billed twice on one night would produce two `event_artist` rows for the same
     * (event, artist) pair and hit that table's unique constraint, failing the whole import, so the
     * first billing wins.
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
     * The room the night's acts play in, or `null` when it runs across both.
     *
     * `.void-event-venue` names the room(s) in use — `VOID CLUB`, `VOID HALL`, or `VOID CLUB &
     * HALL`. A single-room night puts every listed act in that room; a two-room night does not say
     * who plays where, so no act is attributed to either.
     */
    private fun roomOf(card: Element): String? = card.textAt(".void-event-venue")?.takeIf { '&' !in it }

    /**
     * Splits one comma segment into the acts it bills, dropping placeholders.
     *
     * The venue closes an unfinished billing with a continuation phrase rather than a name
     * ("… Lepido and more", "… DaSoMaZo — more to be announced"), and states a whole unannounced
     * lineup as "TBA" or "To be announced" — [UNANNOUNCED_TAIL] cuts all of those off, leaving
     * nothing behind when the segment was only the placeholder.
     */
    private fun splitActs(segment: String): List<String> {
        val act = segment.replace(UNANNOUNCED_TAIL, "").trim()
        return (if ('(' in act) listOf(act) else act.split(B2B_SEPARATOR))
            .map { it.trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) }
    }

    /**
     * Per-event teaser images from the hero slider, keyed by the Resident Advisor URL each slide
     * links to.
     *
     * The cards themselves carry no image; the slider above them re-links the soonest few events by
     * their ticket URL, which is what lets a slide's `img` be matched back to its card.
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
         * The label that opens the DJ billing, in each spelling the venue uses (`WITH`, `LINEUP`,
         * `LINE-UP`). Any other label — `RAVE THE PLANET AFTER PARTY` — makes the paragraph a note.
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
