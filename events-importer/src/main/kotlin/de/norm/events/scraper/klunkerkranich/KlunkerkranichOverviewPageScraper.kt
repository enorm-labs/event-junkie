package de.norm.events.scraper.klunkerkranich

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ISO_DATE_LENGTH
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.endOn
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isKnownSingleAct
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
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Pure HTML parser for Klunkerkranich's `/events/` programme listing.
 *
 * The WordPress theme groups the coming nights by day, each an `article.o-card` inside a
 * `.c-events-overview__event` with its `/events/<slug>` link, title, German date line,
 * opening-hours range and thumbnail. The soonest event renders a second time as an
 * `article.o-page-header` hero, so the card selector is scoped to that wrapper, not `article`.
 *
 * 1. **No year in the rendered date** ("Mittwoch, 05. August"), but the ISO date is in every
 * slug (`/events/2026-08-05-wochenmitte-w-pascale-project/`). The slug is read first; the
 * German date is the fallback, its year inferred from the weekday ([inferYearForWeekday]).
 * 2. **The time range is opening hours, not doors and start.** "17:00 — 00:00": the first time
 * is the start, the second the end, next day when past midnight.
 * 3. **The title packs the whole billing** — `<series> w. <DJ lineup>`, occasionally
 * `<promoter> presents: <acts>` — acts separated by commas, `&` and `b2b`, `*live` on the ones
 * that play rather than spin. [parseLineup] reads the acts from the tail and uses the marker
 * to tell a live act (`HEADLINER`) from a DJ, as Club der Visionäre does. The series name
 * before the marker stays in the title and is no artist: a night's name ("WOCHENMITTE",
 * "MONDAY ROAST"), not a performer.
 *
 * Every night is a [PARTY][EventType.PARTY], like ÆDEN, OHM and gART.n: 1034 of the 1200
 * sitemap events are `… w. <DJ lineup>` nights. That mislabels the occasional concert.
 *
 * @see KLUNKERKRANICH_LIMITATIONS for what the venue does not publish.
 * @see KlunkerkranichDetailPageScraper for the per-event page, which adds the blurb and the price.
 * @see KlunkerkranichWebsiteImporter for the HTTP fetch orchestrator.
 */
class KlunkerkranichOverviewPageScraper(
    /** Clock for the fallback date's year inference, in the venue's time zone; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event cards, in listing order.
     *
     * @param baseUrl the URL the document was fetched from, for resolving the per-event links.
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

    /** Parses one card into a [ScrapedEvent], or `null` without link, title or usable date. */
    @Suppress("ReturnCount") // Guard clauses for the required link/title/date are clearer than nesting
    private fun parseCard(
        card: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val href = card.attrAt(EVENT_LINK, "href") ?: return null
        val eventUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(eventUrl)
        val title = card.textAt(".o-card__title")?.let(::cleanEventTitle) ?: return null

        val eventDate = parseSlugDate(slug) ?: parseRenderedDate(card.textAt(".o-card__meta--primary"))
        if (eventDate == null) {
            logger.warn { "No parseable date for Klunkerkranich event '$title' ($eventUrl), skipping" }
            return null
        }

        val hours = parseOpeningHours(card.textAt(".o-card__meta--secondary"))
        return ScrapedEvent(
            title = title,
            // The venue states no category; see the class KDoc for why every night is a PARTY.
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = hours.first,
            endDate = hours.second?.let { endOn(eventDate, hours.first, it) },
            endTime = hours.second,
            imageUrl = card.imgSrcAt("img.o-card__image"),
            sourceUrl = eventUrl,
            sourceId = "${EventSource.KLUNKERKRANICH.sourceIdPrefix}$slug",
            artists = parseLineup(title)
        )
    }

    /** The ISO date the venue bakes into every event slug, or `null` when a slug lacks one. */
    private fun parseSlugDate(slug: String): LocalDate? = parseIsoDate(slug.take(ISO_DATE_LENGTH))

    /**
     * The card's rendered German date ("Mittwoch, 05. August"), year from the stated weekday —
     * the listing never prints one.
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
     * Opening and closing time from the card's hours range ("17:00 — 00:00"). Opening is the
     * start — the venue never states doors — and closing the end (ADR-029). A closing time at or
     * before the opening is the next day, which `endOn` resolves at the call site.
     */
    private fun parseOpeningHours(range: String?): Pair<LocalTime?, LocalTime?> =
        parseTime(range?.substringBefore(TIME_RANGE_SEPARATOR)?.trim()) to
            parseTime(range?.substringAfter(TIME_RANGE_SEPARATOR, "")?.trim())

    /**
     * The billed acts from a title's `w.` / `presents:` lineup tail.
     *
     * Empty for a title with no marker ("LA MAISON x KLUNKERKRANICH") — the whole title is the
     * night's name, no act to mint. Otherwise the tail splits on commas into one billing per
     * slot, each parsed by [parseBilling].
     *
     * The **last** marker opens the tail. A hosted night carries both — "COUNTERCULT presents:
     * SKETCHY SESSIONS | jazz & draw rooftop sunset jam w. Analog Beats Collective, …" — the
     * promoter's `presents:` names the night, the venue's `w.` the acts; read from the first,
     * the night's name became two prose acts and the real line-up was lost (#1494).
     *
     * An act billed twice would produce two `event_artist` rows for one (event, artist) pair
     * and hit the unique constraint, failing the whole import, so the first billing wins.
     */
    private fun parseLineup(title: String): List<ScrapedArtist> {
        val lineup = LINEUP_MARKER.findAll(title).lastOrNull()?.let { title.substring(it.range.last + 1) } ?: return emptyList()
        return lineup
            .split(',')
            .flatMap(::parseBilling)
            .distinctBy { it.name.lowercase() }
    }

    /**
     * Splits one comma-separated billing into its acts, all sharing its role.
     *
     * A billing may pair two acts — `&`/`and`/`und` for a joint project, `b2b` for a shared slot
     * — stored separately. `*live` qualifies the **billing**, not just the name it trails
     * ("IBAAKU & K'BOKO *live" is one live pairing), so the role is decided before the split.
     *
     * **A conjunction here is ambiguous and the page never resolves it** (#1760). The event page's
     * lineup block prints one act per line, and it puts `Überhaupt & Außerdem` — a duo — on one
     * line exactly as it puts `Ilo Pan & Elmo Lewis`, two residents billed together. So the split
     * stays, and [isKnownSingleAct] is what holds a named duo together; what it does not name is
     * this source's declared `ARTISTS` limitation.
     *
     * A collective may be billed with its members after a colon — "In Limbo Audio: Sven Howland
     * & Niklas Gietmann & Nicolai Toma" — and the page names both, so the collective is an act
     * of its own and the members separate ones; the first member no longer keeps the team's name
     * glued to its own (#1137).
     */
    private fun parseBilling(billing: String): List<ScrapedArtist> {
        val role = if (LIVE_MARKER.containsMatchIn(billing)) "HEADLINER" else "DJ"
        val unmarked = billing.replace(LIVE_MARKER, "").trim()
        if (isKnownSingleAct(unmarked)) return listOf(ScrapedArtist(name = unmarked, role = role))
        val collective = COLLECTIVE_PREFIX.find(unmarked)
        val members = collective?.let { unmarked.substring(it.range.last + 1) } ?: unmarked
        return listOfNotNull(collective?.groupValues?.get(1)?.trim())
            .plus(members.split(ACT_SEPARATOR))
            .map { it.trim().trim('-', '–', '—').trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = role) }
    }

    private companion object {
        /**
         * An event card in the day list, scoped to `.c-events-overview__event` so the
         * `article.o-page-header` hero — the soonest night, rendered again above the list — is not a
         * thirteenth event.
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
         * The marker introducing a night's billing — `w.` (the venue's usual spelling, also `w/`) or a
         * promoter's `presents:` / `präsentiert:`. Space-padded on the left so a name ending in "w"
         * never matches. [parseLineup] takes the last one on the title.
         */
        val LINEUP_MARKER = Regex("""\sw[./]\s|\s(?:presents|präsentiert)\s*:\s*""", RegexOption.IGNORE_CASE)

        /**
         * Act boundaries inside one billing: a space-padded `&` / `and` / `und` joining two separately
         * billed acts, and `b2b` joining two DJs into one slot.
         */
        val ACT_SEPARATOR = Regex("""\s+(?:&|and|und|b2b)\s+""", RegexOption.IGNORE_CASE)

        /**
         * A collective's name before the colon introducing its members. Anchored, and the name may not
         * contain a colon, so a billing without such a prefix stays whole.
         */
        val COLLECTIVE_PREFIX = Regex("""^\s*([^:]+?)\s*:\s*""")

        /** The venue's `*live` annotation, marking an act that plays rather than spins. */
        val LIVE_MARKER = Regex("""\s*\*\s*live\b\.?""", RegexOption.IGNORE_CASE)
    }
}
