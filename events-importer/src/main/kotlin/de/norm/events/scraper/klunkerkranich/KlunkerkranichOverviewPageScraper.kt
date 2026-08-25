package de.norm.events.scraper.klunkerkranich

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ISO_DATE_LENGTH
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/** Time zone the venue programmes in — used to infer the year of its year-less listing dates. */
private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * Pure HTML parser for Klunkerkranich's `/events/` programme listing.
 *
 * The WordPress theme groups the coming nights by day, each an `article.o-card` inside a
 * `.c-events-overview__event` carrying its `/events/<slug>` link, title, a German date line, an
 * opening-hours range and a thumbnail. The same day-list renders the soonest event a second time as
 * an `article.o-page-header` hero, which is why the card selector is scoped to that wrapper rather
 * than matching `article` anywhere on the page.
 *
 * 1. **The rendered date carries no year** ("Mittwoch, 05. August"), but the venue bakes the ISO date
 *    into every slug (`/events/2026-08-05-wochenmitte-w-pascale-project/`). The slug is read first as
 *    the canonical spelling, the German date is the fallback, and its year is inferred from the
 *    stated weekday ([inferYearForWeekday]).
 * 2. **The time range is opening hours, not doors and start.** "17:00 — 00:00" is when the roof is
 *    open, so only its first time is stored, as the start.
 * 3. **The title packs the whole billing** — `<series> w. <DJ lineup>`, occasionally `<promoter>
 *    presents: <acts>` — with acts separated by commas, `&` and `b2b`, and a `*live` marker on the
 *    ones that play rather than spin. [parseLineup] reads the acts out of the tail and uses that
 *    marker to tell a live act (`HEADLINER`) from a DJ, the same split Club der Visionäre makes. The
 *    series name before the marker stays in the title and is not minted as an artist: it is a night's
 *    name ("WOCHENMITTE", "MONDAY ROAST"), not a performer.
 *
 * Every night is stored as a [PARTY][EventType.PARTY], like ÆDEN, OHM and gART.n: 1034 of the 1200
 * events in the sitemap are `… w. <DJ lineup>` nights. That mislabels the occasional concert.
 *
 * @see KLUNKERKRANICH_LIMITATIONS for what the venue does not publish.
 * @see KlunkerkranichDetailPageScraper for the per-event page, which adds the blurb and the price.
 * @see KlunkerkranichWebsiteImporter for the HTTP fetch orchestrator.
 */
class KlunkerkranichOverviewPageScraper(
    /** Clock for the fallback date's year inference. Defaults to the venue's own time zone; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event cards from the programme document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the per-event links.
     * @return a list of [ScrapedEvent] instances, in listing order.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val cards = document.select(EVENT_CARD)
        logger.info { "Found ${cards.size} event card(s) on the Klunkerkranich programme" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed cards without aborting the whole import
        return cards.mapNotNull { card ->
            try {
                parseCard(card, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Klunkerkranich event card, skipping" }
                null
            }
        }
    }

    /** Parses one card into a [ScrapedEvent], or `null` when it has no link, title or usable date. */
    @Suppress("ReturnCount") // Guard clauses for the required link/title/date are clearer than nesting
    private fun parseCard(
        card: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val href = card.selectFirst(EVENT_LINK)?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val eventUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(eventUrl)
        val title = card.textAt(".o-card__title")?.let(::cleanEventTitle) ?: return null

        val eventDate = parseSlugDate(slug) ?: parseRenderedDate(card.textAt(".o-card__meta--primary"))
        if (eventDate == null) {
            logger.warn { "No parseable date for Klunkerkranich event '$title' ($eventUrl), skipping" }
            return null
        }

        return ScrapedEvent(
            title = title,
            // The venue states no category anywhere; see the class KDoc for why every night is a PARTY.
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = parseOpeningTime(card.textAt(".o-card__meta--secondary")),
            imageUrl = card.imgSrcAt("img.o-card__image"),
            sourceUrl = eventUrl,
            sourceId = "${EventSource.KLUNKERKRANICH.sourceIdPrefix}$slug",
            artists = parseLineup(title)
        )
    }

    /** Reads the ISO date the venue bakes into every event slug, or `null` when a slug does not open with one. */
    private fun parseSlugDate(slug: String): LocalDate? = parseIsoDate(slug.take(ISO_DATE_LENGTH))

    /**
     * Parses the card's rendered German date ("Mittwoch, 05. August"), inferring the year from the
     * stated weekday — the listing never prints one.
     */
    @Suppress("ReturnCount") // Guard clauses for the blank / unparseable input are clearer than nesting
    private fun parseRenderedDate(text: String?): LocalDate? {
        if (text.isNullOrBlank()) return null
        val parsed = runCatching { GERMAN_CARD_DATE.parse(text.trim()) }.getOrNull() ?: return null
        return runCatching {
            val monthDay = MonthDay.of(parsed.get(ChronoField.MONTH_OF_YEAR), parsed.get(ChronoField.DAY_OF_MONTH))
            inferYearForWeekday(monthDay, DayOfWeek.of(parsed.get(ChronoField.DAY_OF_WEEK)), clock)
        }.getOrNull()
    }

    /**
     * Reads the start time from the card's opening-hours range ("17:00 — 00:00").
     *
     * Only the opening time is stored: the closing time is when the roof shuts, which the model has
     * no field for, and the venue never states a separate doors time.
     */
    private fun parseOpeningTime(range: String?): LocalTime? = parseTime(range?.substringBefore(TIME_RANGE_SEPARATOR)?.trim())

    /**
     * Reads the billed acts out of a title's `w.` / `presents:` lineup tail.
     *
     * Returns an empty list for a title with no such marker ("LA MAISON x KLUNKERKRANICH") — the
     * whole title is then the night's name and there is no act to mint from it. Otherwise the tail
     * is split on commas into one billing per slot, each parsed by [parseBilling].
     *
     * An act billed twice on one night would produce two `event_artist` rows for the same
     * (event, artist) pair and hit that table's unique constraint, failing the whole import, so the
     * first billing wins.
     */
    private fun parseLineup(title: String): List<ScrapedArtist> {
        val lineup = LINEUP_MARKER.find(title)?.let { title.substring(it.range.last + 1) } ?: return emptyList()
        return lineup
            .split(',')
            .flatMap(::parseBilling)
            .distinctBy { it.name.lowercase() }
    }

    /**
     * Splits one comma-separated billing into the acts it names, all sharing its role.
     *
     * A billing may pair two acts — with `&`/`and`/`und` for a joint project, with `b2b` for a
     * shared slot — and both are stored separately. The `*live` marker qualifies the **billing**,
     * not just the name it trails ("IBAAKU & K'BOKO *live" is one live act pairing), so the role is
     * decided before the split and applies to every act in it.
     */
    private fun parseBilling(billing: String): List<ScrapedArtist> {
        val role = if (LIVE_MARKER.containsMatchIn(billing)) "HEADLINER" else "DJ"
        return billing
            .replace(LIVE_MARKER, "")
            .split(ACT_SEPARATOR)
            .map { it.trim().trim('-', '–', '—').trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = role) }
    }

    private companion object {
        /**
         * An event card in the day list. Scoped to `.c-events-overview__event` so the
         * `article.o-page-header` hero — the soonest night, rendered a second time above the list —
         * is not read as a thirteenth event.
         */
        const val EVENT_CARD = ".c-events-overview__event article.o-card"

        /** The card's link to its own `/events/<slug>` page. */
        const val EVENT_LINK = "a.o-card__wrapper[href]"

        /** The em dash the venue puts between its opening and closing time. */
        const val TIME_RANGE_SEPARATOR = "—"

        /** The card's German date line, e.g. `Mittwoch, 05. August` — no year, hence [inferYearForWeekday]. */
        val GERMAN_CARD_DATE: DateTimeFormatter =
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("EEEE, dd. MMMM")
                .toFormatter(Locale.GERMAN)

        /**
         * The marker introducing a night's billing — `w.` (the venue's usual spelling, also written
         * `w/`) or a promoter's `presents:` / `präsentiert:`. Space-padded on the left so a name
         * ending in "w" is never mistaken for it.
         */
        val LINEUP_MARKER = Regex("""\sw[./]\s|\s(?:presents|präsentiert)\s*:\s*""", RegexOption.IGNORE_CASE)

        /**
         * The act boundaries inside one billing: a space-padded `&` / `and` / `und` joining two
         * separately billed acts, and the `b2b` marker joining two DJs into one slot.
         */
        val ACT_SEPARATOR = Regex("""\s+(?:&|and|und|b2b)\s+""", RegexOption.IGNORE_CASE)

        /** The venue's `*live` annotation, marking an act that plays rather than spins. */
        val LIVE_MARKER = Regex("""\s*\*\s*live\b\.?""", RegexOption.IGNORE_CASE)
    }
}
