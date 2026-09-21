package de.norm.events.scraper.columbiatheater

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure HTML parser for Columbia Theater Berlin's homepage, which **is** the programme listing.
 *
 * Every upcoming night is one `a.item[data-id]` card linking to its `/event/YYYYMMDD-<slug>/`
 * detail page: poster (`.item-image-wrapper img`), `.item-title` act, optional `.item-tour-text`
 * tour name, one `.item-support-row` per billing line, `.item-date-day`/`.item-date-month`, and
 * the venue's `data-c` / `data-m` / `data-p` status flags. The date block has a German month
 * abbreviation and usually no year, so the date is read from the `YYYYMMDD` permalink prefix
 * instead — canonical and unambiguous (see [parseColumbiaTheaterSlugDate]).
 *
 * The overview carries every field except times, description, ticket URL and presenters, which
 * only the detail page has. [ColumbiaTheaterWebsiteImporter] falls back to this data when a detail
 * page fails, so each card is parsed as completely as the listing allows.
 *
 * Dropped on purpose: the `a.boycott-item` campaign banner (off-site link, excluded by the
 * `/event/` href filter) and the `X`-prefixed `data-id` placeholder a rescheduled show leaves at
 * its *original* date (see [isRescheduledPlaceholder]).
 *
 * @see ColumbiaTheaterDetailPageScraper for the detail-page data source (times, blurb, tickets).
 * @see ColumbiaTheaterWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://columbia-theater.de/">Columbia Theater programme</a>
 */
class ColumbiaTheaterOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event cards from the homepage document.
     *
     * @param baseUrl the URL the document was fetched from, for detail links and `sourceId` values.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val cards = document.select("a.item[href*='/event/']").filterNot(::isRescheduledPlaceholder)
        logger.info { "Found ${cards.size} event card(s) on Columbia Theater overview" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed cards without aborting the whole import
        return cards.mapNotNull { card ->
            try {
                parseCard(card, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse event card, skipping" }
                null
            }
        }
    }

    /** Parses one `a.item` card into a [ScrapedEvent], or `null` when it has no href or title. */
    @Suppress("ReturnCount") // Guard clauses for the required href/title are clearer than nesting
    private fun parseCard(
        card: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val href = card.attr("href").takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, EVENT_PATH_PREFIX)

        val title = card.textAt("h2.item-title")?.let(::cleanEventTitle) ?: return null
        val supportRows = supportRowTexts(card)
        val eventType = inferConcertVenueType(title)

        return ScrapedEvent(
            title = title,
            subtitle = columbiaTheaterSubtitle(card.textAt(".item-tour-text"), supportRows),
            eventType = eventType,
            // The permalink's YYYYMMDD prefix is the only year-bearing date on the card.
            eventDate = parseColumbiaTheaterSlugDate(slug) ?: UNRESOLVED_EVENT_DATE,
            imageUrl = card.imgSrcAt(".item-image-wrapper img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.COLUMBIA_THEATER.sourceIdPrefix}$slug",
            status = parseColumbiaTheaterStatus(card, card.textAt(".item-status")),
            artists = columbiaTheaterArtists(title, supportRows, eventType)
        )
    }
}

/** Path prefix of an event permalink, stripped to obtain the `YYYYMMDD-<slug>` identity. */
internal const val EVENT_PATH_PREFIX = "/event/"

/**
 * True when [card] is the placeholder a **rescheduled** show leaves at its *original* date.
 *
 * Such a show renders twice: an `X`-prefixed `data-id` in the old slot (badged "Verschoben /
 * Rescheduled", naming the new date) and the real entry at the new date. Both anchors point at
 * the same `/event/YYYYMMDD-<slug>/` URL — the *new* date — so keeping the placeholder would fetch
 * one detail page twice and mint two events on one `sourceId`. The real entry carries the same
 * `data-p` flag, so nothing is lost.
 */
private fun isRescheduledPlaceholder(card: Element): Boolean = card.attr("data-id").startsWith(RESCHEDULED_ID_PREFIX)

/** The `data-id` prefix marking a rescheduled show's stale-date placeholder card. */
private const val RESCHEDULED_ID_PREFIX = "X"

/** The venue's per-event status flags, in check order; `"1"` means set. */
private const val CANCELLED_FLAG = "data-c"
private const val RELOCATED_FLAG = "data-m"
private const val POSTPONED_FLAG = "data-p"
private const val FLAG_SET = "1"

/**
 * Status from the venue's machine-readable flags, else the bilingual badge in [statusText].
 *
 * `data-c` (abgesagt / canceled), `data-m` (verlegt / relocated) and `data-p` (verschoben /
 * rescheduled) sit on both the overview card and the detail page's `.event-content`, more durable
 * than the wording. The badge renders only for some flagged events — a show quietly moved carries
 * `data-p` and no badge — so the flags lead and [parseEventStatus] handles the unflagged majority
 * (`SCHEDULED` for empty text).
 *
 * [root] is the flag-carrying element: `a.item` on the overview, `.event-content` on a detail page.
 */
internal fun parseColumbiaTheaterStatus(
    root: Element,
    statusText: String?
): String =
    when {
        root.attr(CANCELLED_FLAG) == FLAG_SET -> EventStatus.CANCELLED.name
        root.attr(RELOCATED_FLAG) == FLAG_SET -> EventStatus.RELOCATED.name
        root.attr(POSTPONED_FLAG) == FLAG_SET -> EventStatus.POSTPONED.name
        else -> parseEventStatus(statusText.orEmpty())
    }

/** The `YYYYMMDD` date the venue prefixes to every event permalink slug. */
private val SLUG_DATE_PATTERN = Regex("""^(\d{8})-""")

/**
 * The event date from the `YYYYMMDD` permalink prefix
 * (`20260816-nathan-evans-the-saint-phnx-band` → `2026-08-16`), or `null` without one.
 *
 * The only unambiguous date the site publishes: the overview card (`03` / `Aug`) and the detail
 * header (`So. 16.08. um 20:00`) both omit the year unless the event is more than a season away.
 * Callers substitute [UNRESOLVED_EVENT_DATE][de.norm.events.scraper.UNRESOLVED_EVENT_DATE] on
 * `null` so [AbstractTwoPageWebsiteImporter][de.norm.events.scraper.AbstractTwoPageWebsiteImporter]
 * drops the event rather than guessing a year.
 */
internal fun parseColumbiaTheaterSlugDate(slug: String): LocalDate? {
    val digits = SLUG_DATE_PATTERN.find(slug)?.groupValues?.get(1) ?: return null
    return try {
        LocalDate.parse(digits, DateTimeFormatter.BASIC_ISO_DATE)
    } catch (_: DateTimeParseException) {
        null
    }
}

/** The `.item-support-row` billing lines under [root], in listing order. */
internal fun supportRowTexts(root: Element): List<String> =
    root
        .select(".item-support-row")
        .map { it.text().trim() }
        .filter { it.isNotBlank() }

/**
 * Tour name and billing lines as one display subtitle, or `null` with neither — "Angels' Share
 * Tour 2026 | Support: Ewan Mckenna + Connor Skinner". The billing lines keep their labels here
 * (what a reader wants); [columbiaTheaterArtists] extracts the artists from the same rows.
 */
internal fun columbiaTheaterSubtitle(
    tour: String?,
    supportRows: List<String>
): String? = (listOfNotNull(tour) + supportRows).joinToString(SUBTITLE_SEPARATOR).takeIf { it.isNotBlank() }

private const val SUBTITLE_SEPARATOR = " | "

/**
 * The role label opening every billing row — `Support:`, `Opener:`, `Special Guest(s):`, `DJ:`.
 * Plain `Support:`/`Opener:` sit in a CSS-hidden `span.single-only`, the others are inline text,
 * but `.text()` flattens both. The colon is **required** (unlike the shared
 * [ROLE_LABEL_PREFIX][de.norm.events.scraper.ROLE_LABEL_PREFIX]), so an act whose *name* starts
 * with the word — the venue bills a "Support: DJ OSI" — keeps it.
 */
private val SUPPORT_ROW_LABEL =
    Regex("""^\s*(supports?|openers?|special\s+guests?|djs?)\s*:\s*""", RegexOption.IGNORE_CASE)

/** The `DJ:` billing label, which bills a DJ rather than a support act. */
private const val DJ_LABEL = "dj"

/** The venue's act separator inside a billing row — a space-padded `+`. */
private val ACT_SEPARATOR = Regex("""\s+\+\s+""")

/**
 * The lineup from [title] and billing rows, mirroring
 * [buildArtistsForEventType][de.norm.events.scraper.buildArtistsForEventType]: a festival/party
 * title names an event, a concert's title is its headliner, any other type yields artists only
 * when a billing row confirms the convention. Headliners split on ` / ` and ` + `; billed acts
 * come from [supportRows] via [parseSupportRow].
 */
@Suppress("ReturnCount") // Guard clauses for the event-type branches are clearer than nesting
internal fun columbiaTheaterArtists(
    title: String,
    supportRows: List<String>,
    eventType: String
): List<ScrapedArtist> {
    if (eventType == EventType.FESTIVAL.name || eventType == EventType.PARTY.name) return emptyList()
    val billed = supportRows.flatMap(::parseSupportRow)
    if (eventType != EventType.CONCERT.name && billed.isEmpty()) return emptyList()
    return headlinersFromTitle(title) + billed
}

/**
 * Parses one billing row ("Special Guests: CROWN MAGNETAR + THE ZENITH PASSAGE + ANALEPSY") into
 * acts typed by its label — `DJ:` bills a [DJ][de.norm.events.event.ArtistRole.DJ], everything
 * else (and an unrecognised label) a support act.
 *
 * Split on the space-padded `+`, then safe conjunctions ([splitSegmentOnConjunctions]) — **not**
 * commas, unlike the shared [splitSupportActs]: the venue writes a guest's band affiliations in
 * parentheses ("Budgie (SIOUXSIE & THE BANSHEES, THE SLITS)") and a comma split would tear them
 * apart. [stripArtistSuffix] then removes the affiliation so the guest lands on their own row (#1561).
 */
private fun parseSupportRow(row: String): List<ScrapedArtist> {
    val label = SUPPORT_ROW_LABEL.find(row)
    val role = if (label?.groupValues?.get(1)?.startsWith(DJ_LABEL, ignoreCase = true) == true) "DJ" else "SUPPORT"
    val acts = label?.let { row.substring(it.range.last + 1) } ?: row
    return acts
        .trim()
        .removePrefix("+")
        .split(ACT_SEPARATOR)
        .flatMap(::splitSegmentOnConjunctions)
        .map { stripArtistSuffix(it.trim()) }
        .filter { it.isNotBlank() }
        .filterNot(::isNonArtistName)
        .map { ScrapedArtist(name = it, role = role) }
}

/** The German intro before the media presenters, e.g. "präsentiert von Impericon". */
private val PRESENTER_INTRO = Regex("""^\s*präsentiert\s+von\s+""", RegexOption.IGNORE_CASE)

/**
 * Media presenters from a detail page's `.header-promoters` line ("präsentiert von DIFFUS,
 * Bedroomdisco, MusikBlog, FluxFM & Musikexpress" → five names). Only a line opening with
 * [PRESENTER_INTRO] is read, so a differently-worded credit is skipped rather than stored verbatim.
 * The comma/`&` list is exactly what [splitSupportActs] splits.
 */
@Suppress("ReturnCount") // Guard clauses for the missing / differently-worded credit are clearer than nesting
internal fun parseColumbiaTheaterPresenters(content: Element): List<String> {
    val line = content.textAt(".header-promoters") ?: return emptyList()
    val names = PRESENTER_INTRO.find(line)?.let { line.substring(it.range.last + 1) } ?: return emptyList()
    return splitSupportActs(names)
}
