package de.norm.events.scraper.eschschloraque

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.orderDoorsBeforeStart
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Pure HTML parser for Eschschloraque Rümschrümp's Drupal 7 home-page programme.
 *
 * The home page is the whole source: its front-page view renders every upcoming event as a
 * **full node** (`.node-veranstaltung`), not a teaser, so date, title, poster, billing line and
 * the complete prose arrive in one fetch. Each node's own page is byte-identical, so no detail
 * fetch. `/rss.xml` was rejected: the same teasers, ordered by *authoring* date, not event date.
 *
 * **The date comes from RDFa, not the German rendering.** Drupal's date field emits both
 * `content="2026-08-12T21:00:00+02:00"` and the human "Mittwoch, 12. August 2026 ab 21Uhr" in
 * one `span.date-display-single`; the attribute has a four-digit year and needs no German month
 * table, so it is the only date source and an event without it is skipped.
 *
 * Typing goes through [inferUnmarkedTitleType], so an event is `OTHER` unless its *title* names
 * an unambiguous format. A `<host> presents <act> - live!` title is one: it bills a live act, so
 * the night is a `CONCERT`. Deliberately **not**
 * [inferConcertVenueType][de.norm.events.scraper.inferConcertVenueType]: defaulting a bar's DJ
 * nights to `CONCERT` would also mint each event name ("Hot Tunes for Cool Cats") as a
 * headliner. Free entry stated in prose is flagged via [FREE_ENTRY_PHRASE]; every other night
 * keeps an unknown price rather than a guessed one.
 *
 * @see ESCHSCHLORAQUE_LIMITATIONS for what the venue does not publish.
 * @see EschschloraqueWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.eschschloraque.de/">Eschschloraque Rümschrümp</a>
 */
class EschschloraqueOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every event node on the home page, in page order (the view sorts by event date).
     *
     * @param baseUrl the URL the document was fetched from, for resolving each node's relative path.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val nodes = document.select(".node-veranstaltung")
        logger.info { "Found ${nodes.size} event node(s) on Eschschloraque overview" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed nodes without aborting the import
        return nodes.mapNotNull { node ->
            try {
                parseNode(node, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Eschschloraque event node, skipping" }
                null
            }
        }
    }

    /** Parses one `.node-veranstaltung` into a [ScrapedEvent], or `null` without title, path or date. */
    @Suppress("ReturnCount") // Guard clauses for the required title/path/date are clearer than nesting
    private fun parseNode(
        node: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val rawTitle = node.textAt(".veranstaltung-title")
        if (rawTitle == null) {
            logger.warn { "Eschschloraque event node has no title, skipping" }
            return null
        }
        val title = cleanEventTitle(rawTitle)

        // Drupal stamps the node's path alias on the wrapper's RDFa `about` attribute. It is the
        // identity even for the first node, whose title the front-page view renders without a link,
        // so it is preferred over hunting for an anchor.
        val path = node.attr("about").takeIf { it.isNotBlank() }
        if (path == null) {
            logger.warn { "Eschschloraque event '$title' has no node path, skipping" }
            return null
        }

        val startDateTime = parseStartDateTime(node)
        if (startDateTime == null) {
            logger.warn { "Eschschloraque event '$title' has no parseable date, skipping" }
            return null
        }

        val billingParagraphs = findBillingParagraphs(node)
        val description = parseDescription(node, billingParagraphs)
        val (doorsTime, startTime) = resolveTimes(startDateTime.second, node.select(PROSE_PARAGRAPHS).text())
        val presentedActs = presentedLiveActs(title)

        return ScrapedEvent(
            title = title,
            // The first billing line is the subtitle; a later one heads an act's own blurb.
            subtitle =
                billingParagraphs
                    .firstOrNull()
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() },
            description = description,
            eventType = if (presentedActs.isEmpty()) inferUnmarkedTitleType(title) else EventType.CONCERT.name,
            eventDate = startDateTime.first,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = node.attrAt(".field-type-image img", "src")?.takeIf { it.startsWith("http") },
            sourceUrl = resolveUrl(baseUrl, path),
            // URI.getPath() decodes the alias's percent escapes, so the id reads
            // "eschschloraque:20-jahre-missvergnügen-12082026" rather than "…missvergn%C3%BCgen…".
            sourceId = "${EventSource.ESCHSCHLORAQUE.sourceIdPrefix}${extractSlug(path)}",
            free = description?.let { FREE_ENTRY_PHRASE.containsMatchIn(it) } == true,
            artists = (presentedActs + parseLineup(billingParagraphs)).distinctBy { it.name.lowercase() }
        )
    }

    /**
     * The doors and the start, from the one structured time and the prose (#318). The date field
     * carries one time; where a night has two, only the description says so — `Einlass: 19:00 /
     * Beginn: 19:30`, `Doors: / Starts:`, `DJs ab 21 Uhr, Showtime ab 22 Uhr`, `ab 21uhr SHOWTIME 22uhr`. **Prose fills, it
     * never overrides**: the structured time keeps its value, and a labelled pair that names it
     * decides which of the two it is, the other value filling the empty slot. A pair naming neither
     * leaves the structured time as the start and fills the doors only from before it. One time
     * alone stays the start, never a doors time.
     */
    private fun resolveTimes(
        structured: LocalTime,
        prose: String
    ): Pair<LocalTime?, LocalTime?> {
        val proseStart = proseTime(prose, START_LABELS)
        // A bare `ab 21uhr` beside a labelled show start is the doors; alone it is the one time the venue announces.
        val proseDoors = proseTime(prose, DOORS_LABELS) ?: proseStart?.let { proseTime(prose, BARE_AB) }
        return when {
            proseDoors == null && proseStart == null -> null to structured
            proseDoors == structured -> structured to (proseStart ?: structured)
            proseStart == structured -> proseDoors to structured
            else -> orderDoorsBeforeStart(proseDoors?.takeIf { it < structured }, structured)
        }
    }

    /** The first clock a label in [labels] introduces — `Einlass: 19:00`, `DJs ab 21 Uhr` — or `null`. */
    private fun proseTime(
        prose: String,
        labels: Regex
    ): LocalTime? =
        labels.find(prose)?.let { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].ifBlank { "0" }.toInt()
            runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }

    /**
     * The event's start from the RDFa `content` attribute (`2026-08-12T21:00:00+02:00`): date and
     * local time. Parsed as an [OffsetDateTime] rather than the shared
     * [parseIsoDate][de.norm.events.scraper.parseIsoDate] / `parseIsoTime` pair, which expect the
     * bare `…T20:00` form of schema.org JSON-LD — the seconds and `+02:00` offset make the time
     * half unparseable for them. `null` when absent or malformed.
     */
    private fun parseStartDateTime(node: Element): Pair<LocalDate, LocalTime>? {
        val content = node.attrAt(".date-display-single", "content") ?: return null
        return try {
            val startsAt = OffsetDateTime.parse(content.trim())
            startsAt.toLocalDate() to startsAt.toLocalTime()
        } catch (_: DateTimeParseException) {
            logger.warn { "Unparseable Eschschloraque date attribute '$content'" }
            null
        }
    }

    /**
     * The paragraphs carrying the night's billing lines — those holding the venue's `.redsubtitle`
     * spans naming the DJs and live acts ("on the couch: Holly Hunted & MissVergnügen", "Live:
     * Nostalgican | ear def").
     *
     * The billing sits in the optional `field-intro-text` or the first body paragraph — but a
     * two-DJ night can bill each act at the head of its own blurb, one `.redsubtitle` per section
     * ("Krawallwitz" in the intro, "Simon Eickenboom" further down, #1136), so every such
     * paragraph is read in document order. A multi-act night repeating an act's name above its
     * blurb is covered by [parseLineup] billing each name once. Empty for a night billed in plain
     * prose with no `.redsubtitle`.
     */
    private fun findBillingParagraphs(node: Element): List<Element> = node.select(BILLING_PARAGRAPH)

    /**
     * The node's prose as a description, one paragraph per line: every text field — intro text,
     * body, and the `field-body-N` blurbs a multi-act night adds per act — selected by Drupal's
     * stable *field-type* classes rather than name, so a new `field-body-4` is picked up without a
     * code change. Image-caption `blockquote`s are excluded for free: they live inside the image
     * fields, not text fields. The [billingParagraphs] are dropped: the first is already the
     * subtitle, the rest only name an act billed by the lineup.
     */
    private fun parseDescription(
        node: Element,
        billingParagraphs: List<Element>
    ): String? =
        node
            .select(PROSE_PARAGRAPHS)
            .filterNot { paragraph -> billingParagraphs.any { it === paragraph } }
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    /**
     * The lineup from the `.redsubtitle` spans of the [billingParagraphs], each act once. Each span
     * is one billing line and its leading label decides the role: `Live:` bills headliners, every
     * other line (`Dj:`, `on the couch:`, or an unlabelled list of DJ names) bills DJs. The label
     * is stripped so it cannot enter a name.
     *
     * The event *title* is not read here: it names the night ("Hot Tunes for Cool Cats"), and
     * [presentedLiveActs] reads the one title form that bills a performer.
     */
    private fun parseLineup(billingParagraphs: List<Element>): List<ScrapedArtist> =
        billingParagraphs
            .flatMap { it.select(".redsubtitle") }
            .flatMap { line ->
                val text = line.text().trim()
                val role = if (LIVE_LABEL.containsMatchIn(text)) "HEADLINER" else "DJ"
                splitActs(text.replaceFirst(BILLING_LABEL, "")).map { ScrapedArtist(name = it, role = role) }
            }.distinctBy { it.name.lowercase() }

    /**
     * Splits one billing line into act names: the venue's `|` separator first, then each segment
     * through the shared [splitSupportActs] for `, ` / `+` / `/` / `&`. An `a.k.a.` alias names
     * one performer twice ("Dinah Richten a.k.a. Seraphim"), so only the part before it is kept.
     */
    private fun splitActs(line: String): List<String> =
        line
            .split(PIPE_SEPARATOR)
            .flatMap { splitSupportActs(it) }
            .map { it.split(ALIAS_SEPARATOR).first().trim() }
            .filter { it.isNotBlank() }
            .filterNot { isNonArtistName(it) }

    /** The slug from a node path alias, percent escapes decoded: `/cool-tunes-hot-cats-19082026` → `cool-tunes-hot-cats-19082026`. */
    private fun extractSlug(path: String): String = URI(path).path.trim('/')

    private companion object {
        /**
         * The paragraphs of the Drupal text fields holding a node's prose, matched by *field type*
         * rather than name so the per-act `field-body-2`/`-3`/… series needs no enumeration.
         * `text-with-summary` is the body field's type; `text-long` covers the intro text and extra
         * blurbs. Spelled out per type because a comma in a Jsoup selector separates two complete
         * selectors — the ` p` would bind to the last branch only. Photo credits are excluded for
         * free: a `blockquote` inside the *image* fields.
         */
        const val PROSE_PARAGRAPHS = ".field-type-text-with-summary p, .field-type-text-long p"

        /** The prose paragraphs carrying a `.redsubtitle` billing line. See [PROSE_PARAGRAPHS] on the repetition. */
        const val BILLING_PARAGRAPH =
            ".field-type-text-with-summary p:has(.redsubtitle), .field-type-text-long p:has(.redsubtitle)"

        /**
         * A leading role/format label on a billing line — the slot, not the performer, stripped
         * before the names are read. "on the couch" is the venue's phrase for the DJ seat in its
         * front room. Anchored and **colon-terminated**, so the unlabelled "DJ VELA & DJ Sky Deep"
         * keeps the `DJ` that is part of each name. `djs` precedes `dj` so the longer label wins.
         */
        val BILLING_LABEL = Regex("""^(?:live|djs|dj|on\s+the\s+couch)\s*:\s*""", RegexOption.IGNORE_CASE)

        /** A clock after a label: `19:00`, `19.00`, `21 Uhr`, `19Uhr`. Group 1 the hour, group 2 the minutes or blank. */
        private const val CLOCK = """\s*:?\s*(?:ab\s+)?(\d{1,2})(?:[:.](\d{2}))?\s*(?:uhr)?\b"""

        /** The doors as the prose labels them, in both of the venue's languages. */
        val DOORS_LABELS = Regex("""\b(?:einlass|doors?|djs)$CLOCK""", RegexOption.IGNORE_CASE)

        /** The unlabelled `ab HH Uhr` the venue writes before a `SHOWTIME`; read only beside a labelled start. */
        val BARE_AB = Regex("""\bab$CLOCK""", RegexOption.IGNORE_CASE)

        /** The start as the prose labels it; `Showtime` is the venue's word for a show's start after the DJs. */
        val START_LABELS = Regex("""\b(?:beginn|starts?|start|showtime)$CLOCK""", RegexOption.IGNORE_CASE)

        /** The `Live:` label, which bills the line's acts as headliners rather than DJs. */
        val LIVE_LABEL = Regex("""^live\s*:""", RegexOption.IGNORE_CASE)

        /** The venue's own act separator on a billing line, alongside the shared `, ` / `+` / `/` / `&` forms. */
        val PIPE_SEPARATOR = Regex("""\s*\|\s*""")

        /**
         * An `a.k.a.` alias introducing a performer's second name. Written with and without the
         * trailing space ("Dinah Richten a.k.a.Seraphim"), so nothing is required on the right — but
         * leading whitespace **is**, and the dotted form must keep its dots, so a real act opening
         * with those letters ("Akatombo") is not torn in half.
         */
        val ALIAS_SEPARATOR = Regex("""\s+a\.k\.a\.?\s*|\s+aka\s+""", RegexOption.IGNORE_CASE)

        /**
         * The free-entry phrases in the venue's prose ("Eintritt frei"). The lookahead rejects a
         * time-limited offer ("Eintritt frei bis 22 Uhr"), not a free event.
         */
        val FREE_ENTRY_PHRASE =
            Regex("""(?:eintritt frei|freier eintritt|free entry)(?!\s+(?:till|until|before|bis|ab)\b)""", RegexOption.IGNORE_CASE)
    }
}

/**
 * The live acts a `<host> presents <act> - live!` title bills as headliners (#1907). The host is
 * the resident DJ, billed by the subtitle. Empty for any other title, so a night name never enters
 * the lineup.
 */
private fun presentedLiveActs(title: String): List<ScrapedArtist> {
    val act = PRESENTED_LIVE_ACT.find(title)?.groupValues?.get(1) ?: return emptyList()
    return splitSupportActs(act)
        .map { it.trim() }
        .filterNot { it.isBlank() || isNonArtistName(it) }
        .map { ScrapedArtist(name = it, role = "HEADLINER", titleDerived = true) }
}

/**
 * `MissVergnügen presents FRAUKE 400 - live!`, group 1 the act. The `live` suffix is required, so a
 * presented series name is never billed.
 */
private val PRESENTED_LIVE_ACT = Regex("""^.+?\s+presents\s+(.+?)\s*[-–—]\s*live!*$""", RegexOption.IGNORE_CASE)
