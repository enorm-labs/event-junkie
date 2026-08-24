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
 * detail page. A card carries the poster (`.item-image-wrapper img`), the `.item-title` act, an
 * optional `.item-tour-text` tour name, one `.item-support-row` per billing line, a
 * `.item-date-day`/`.item-date-month` block, and the venue's `data-c` / `data-m` / `data-p`
 * status flags. The rendered date block has a German month abbreviation and usually no year, so
 * the date is read from the `YYYYMMDD` prefix the venue bakes into every permalink instead — the
 * canonical, unambiguous rendering (see [parseColumbiaTheaterSlugDate]).
 *
 * The overview is the discovery list plus every field except the ones only the detail page
 * carries (times, description, ticket URL, presenters). Because [ColumbiaTheaterWebsiteImporter]
 * falls back to this data when a detail page fails to fetch, each card is parsed as completely as
 * the listing allows.
 *
 * Two cards are deliberately dropped: the venue's `a.boycott-item` campaign banner (an off-site
 * link, excluded by the `/event/` href filter) and the `X`-prefixed `data-id` placeholder a
 * rescheduled show leaves behind at its *original* date (see [isRescheduledPlaceholder]).
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
     * @param baseUrl the URL the document was fetched from, used to resolve detail links and build
     *   `sourceId` values.
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

    /** Parses a single `a.item` card into a [ScrapedEvent], or `null` when it has no href or title. */
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

/** Path prefix of a Columbia Theater event permalink, stripped to obtain the `YYYYMMDD-<slug>` identity. */
internal const val EVENT_PATH_PREFIX = "/event/"

/**
 * True when [card] is the placeholder a **rescheduled** show leaves at its *original* date.
 *
 * The venue renders such a show twice: once with an `X`-prefixed `data-id` in the old date's slot
 * (badged "Verschoben / Rescheduled" and naming the new date), and once as the real entry at its
 * new date. Both anchors point at the same `/event/YYYYMMDD-<slug>/` URL — the *new* date — so
 * keeping the placeholder would fetch one detail page twice and mint two events colliding on the
 * same `sourceId`. The real entry carries the same `data-p` flag, so dropping the placeholder
 * loses no information.
 */
private fun isRescheduledPlaceholder(card: Element): Boolean = card.attr("data-id").startsWith(RESCHEDULED_ID_PREFIX)

/** The `data-id` prefix marking a rescheduled show's stale-date placeholder card. */
private const val RESCHEDULED_ID_PREFIX = "X"

/** The venue's per-event status flags, in the order they are checked; `"1"` means set. */
private const val CANCELLED_FLAG = "data-c"
private const val RELOCATED_FLAG = "data-m"
private const val POSTPONED_FLAG = "data-p"
private const val FLAG_SET = "1"

/**
 * Reads an event's scheduling status from the venue's own machine-readable flags, falling back to
 * the bilingual badge text in [statusText].
 *
 * Columbia Theater stamps `data-c` (abgesagt / canceled), `data-m` (verlegt / relocated) and
 * `data-p` (verschoben / rescheduled) on both the overview card and the detail page's
 * `.event-content`, which is more durable than the rendered wording. The badge is only rendered
 * for some of the flagged events — a show quietly moved to a new date carries `data-p` with no
 * badge at all — so the flags lead and [parseEventStatus] handles the unflagged majority
 * (returning `SCHEDULED` for the empty text).
 *
 * [root] is the flag-carrying element: the `a.item` card on the overview, the `.event-content`
 * wrapper on a detail page.
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
 * Reads the event date from the `YYYYMMDD` prefix of an event permalink slug
 * (`20260816-nathan-evans-the-saint-phnx-band` → `2026-08-16`), or `null` when the slug carries no
 * such prefix.
 *
 * This is the only unambiguous date the site publishes: both the overview card
 * (`03` / `Aug`) and the detail header (`So. 16.08. um 20:00`) render a German month with the year
 * omitted unless the event is more than a season away. Callers substitute
 * [UNRESOLVED_EVENT_DATE][de.norm.events.scraper.UNRESOLVED_EVENT_DATE] on `null` so
 * [AbstractTwoPageWebsiteImporter][de.norm.events.scraper.AbstractTwoPageWebsiteImporter] drops the
 * event rather than guessing a year.
 */
internal fun parseColumbiaTheaterSlugDate(slug: String): LocalDate? {
    val digits = SLUG_DATE_PATTERN.find(slug)?.groupValues?.get(1) ?: return null
    return try {
        LocalDate.parse(digits, DateTimeFormatter.BASIC_ISO_DATE)
    } catch (_: DateTimeParseException) {
        null
    }
}

/** Reads the `.item-support-row` billing lines under [root], in listing order. */
internal fun supportRowTexts(root: Element): List<String> =
    root
        .select(".item-support-row")
        .map { it.text().trim() }
        .filter { it.isNotBlank() }

/**
 * Joins the tour name and the billing lines into one display subtitle, or `null` when the event
 * has neither — "Angels' Share Tour 2026 | Support: Ewan Mckenna + Connor Skinner". The billing
 * lines keep their labels here (they are what a reader wants to see); the artists are extracted
 * from the same rows separately by [columbiaTheaterArtists].
 */
internal fun columbiaTheaterSubtitle(
    tour: String?,
    supportRows: List<String>
): String? = (listOfNotNull(tour) + supportRows).joinToString(SUBTITLE_SEPARATOR).takeIf { it.isNotBlank() }

private const val SUBTITLE_SEPARATOR = " | "

/**
 * The role label opening every billing row — `Support:`, `Opener:`, `Special Guest(s):`, `DJ:`.
 * The plain `Support:`/`Opener:` labels sit in a CSS-hidden `span.single-only` and the others are
 * inline text, but `.text()` flattens both, so one pattern covers every row. The colon is
 * **required** (unlike the shared
 * [ROLE_LABEL_PREFIX][de.norm.events.scraper.ROLE_LABEL_PREFIX], which makes it optional), so an
 * act whose *name* starts with the word — the venue bills a "Support: DJ OSI" — keeps it.
 */
private val SUPPORT_ROW_LABEL =
    Regex("""^\s*(supports?|openers?|special\s+guests?|djs?)\s*:\s*""", RegexOption.IGNORE_CASE)

/** The `DJ:` billing label, which bills a DJ rather than a support act. */
private const val DJ_LABEL = "dj"

/** The venue's act separator inside a billing row — a space-padded `+`. */
private val ACT_SEPARATOR = Regex("""\s+\+\s+""")

/**
 * Builds the lineup from the event [title] and its billing rows, mirroring
 * [buildArtistsForEventType][de.norm.events.scraper.buildArtistsForEventType]: a festival/party
 * title names an event rather than an act, a concert's title is always its headliner, and any
 * other type only yields artists when a billing row confirms the convention.
 *
 * Headliners come from the title (co-bills split on the venue's ` / ` and ` + `), the billed acts
 * from [supportRows] via [parseSupportRow].
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
 * its acts, typed by the row's own label — a `DJ:` row bills a
 * [DJ][de.norm.events.event.ArtistRole.DJ], everything else a support act. A row with no
 * recognized label is read as support acts.
 *
 * Acts are split on the venue's space-padded `+` and then on safe conjunctions
 * ([splitSegmentOnConjunctions]) — deliberately **not** on commas, unlike the shared
 * [splitSupportActs]: the venue writes a guest's band affiliations in parentheses
 * ("Budgie (SIOUXSIE & THE BANSHEES, THE SLITS)"), and a comma split would tear those into
 * fragments.
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
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filterNot(::isNonArtistName)
        .map { ScrapedArtist(name = it, role = role) }
}

/** The German intro the venue puts before its media presenters, e.g. "präsentiert von Impericon". */
private val PRESENTER_INTRO = Regex("""^\s*präsentiert\s+von\s+""", RegexOption.IGNORE_CASE)

/**
 * Extracts the media presenters from a detail page's `.header-promoters` line
 * ("präsentiert von DIFFUS, Bedroomdisco, MusikBlog, FluxFM & Musikexpress" → five names).
 *
 * Only a line opening with [PRESENTER_INTRO] is read, so a differently-worded credit is skipped
 * rather than stored verbatim as a promoter name. The names are a comma/`&`-delimited list, which
 * is exactly what [splitSupportActs] splits.
 */
@Suppress("ReturnCount") // Guard clauses for the missing / differently-worded credit are clearer than nesting
internal fun parseColumbiaTheaterPresenters(content: Element): List<String> {
    val line = content.textAt(".header-promoters") ?: return emptyList()
    val names = PRESENTER_INTRO.find(line)?.let { line.substring(it.range.last + 1) } ?: return emptyList()
    return splitSupportActs(names)
}
