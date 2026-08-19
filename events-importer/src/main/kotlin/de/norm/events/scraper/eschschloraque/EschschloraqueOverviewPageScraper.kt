package de.norm.events.scraper.eschschloraque

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
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
 * **full node** (`.node-veranstaltung`), not a teaser, so the date, title, poster, billing line
 * and the complete prose all arrive in one fetch. Each node's per-event page carries byte-identical
 * markup, so no detail fetch is made. The site's `/rss.xml` was rejected as the source: it wraps
 * the same teasers but orders them by *authoring* date rather than event date.
 *
 * **The date comes from RDFa, not the German rendering.** Drupal's date field emits both
 * `content="2026-08-12T21:00:00+02:00"` and the human "Mittwoch, 12. August 2026 ab 21Uhr" in the
 * same `span.date-display-single`; the machine attribute carries a four-digit year and needs no
 * German month table, so it is the only date source and an event without it is skipped.
 *
 * Accepted limitations — the venue publishes none of this, so there is nothing to repair:
 * - **No event categories.** The programme mixes DJ nights, live sets, bingo and theatre with no
 *   kind/genre field anywhere, so [inferUnmarkedTitleType] applies: an event is `OTHER` unless its
 *   *title* names an unambiguous format. Deliberately **not** [inferConcertVenueType]
 *   [de.norm.events.scraper.inferConcertVenueType] — defaulting a bar's DJ nights to `CONCERT`
 *   would also mint each event name ("Hot Tunes for Cool Cats") as a headliner.
 * - **No prices and no ticket shop.** Entry is settled at the door; a night that says so in prose
 *   is flagged via [FREE_ENTRY_PHRASE], and everything else keeps an unknown price rather than a
 *   guessed one.
 * - **One time, not two.** The venue publishes a single "ab HH Uhr" start and never a separate
 *   doors time, so [ScrapedEvent.doorsTime] stays null.
 * - **No sold-out or cancellation flags**, so every event is `SCHEDULED`.
 *
 * @see EschschloraqueWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.eschschloraque.de/">Eschschloraque Rümschrümp</a>
 */
class EschschloraqueOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every event node on the home page.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve each node's relative path.
     * @return a list of [ScrapedEvent] instances in page order (the view sorts by event date).
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

    /** Parses a single `.node-veranstaltung` into a [ScrapedEvent], or `null` when it has no title, path or date. */
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
        // event's identity even for the first node, whose title the front-page view renders
        // without a link, so it is preferred over hunting for an anchor.
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

        val billingParagraph = findBillingParagraph(node)
        val description = parseDescription(node, billingParagraph)

        return ScrapedEvent(
            title = title,
            subtitle = billingParagraph?.text()?.trim()?.takeIf { it.isNotBlank() },
            description = description,
            eventType = inferUnmarkedTitleType(title),
            eventDate = startDateTime.first,
            // The venue announces one "ab HH Uhr" time; it is the start, never a separate doors time.
            startTime = startDateTime.second,
            imageUrl = node.attrAt(".field-type-image img", "src")?.takeIf { it.startsWith("http") },
            sourceUrl = resolveUrl(baseUrl, path),
            // URI.getPath() decodes the alias's percent escapes, so the id reads
            // "eschschloraque:20-jahre-missvergnügen-12082026" rather than "…missvergn%C3%BCgen…".
            sourceId = "${EventSource.ESCHSCHLORAQUE.sourceIdPrefix}${extractSlug(path)}",
            free = description?.let { FREE_ENTRY_PHRASE.containsMatchIn(it) } == true,
            artists = parseLineup(billingParagraph)
        )
    }

    /**
     * Reads the event's start from the RDFa `content` attribute Drupal's date field emits
     * (`2026-08-12T21:00:00+02:00`), returning its date and local time.
     *
     * Parsed as an [OffsetDateTime] rather than via the shared
     * [parseIsoDate][de.norm.events.scraper.parseIsoDate] / `parseIsoTime` pair, because those
     * expect the bare `…T20:00` form that schema.org JSON-LD carries — the seconds and the
     * `+02:00` zone offset here make the time half unparseable for them. Returns `null` when the
     * attribute is absent or malformed.
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
     * Finds the paragraph carrying the night's billing line — the one holding the venue's own
     * `.redsubtitle` spans, in which it names the DJs and live acts ("on the couch: Holly Hunted &
     * MissVergnügen", "Live: Nostalgican | ear def").
     *
     * The venue puts it either in the optional `field-intro-text` or, when that field is unused,
     * as the first paragraph of the body — so the search runs over the prose fields in document
     * order and takes the first match. Only the *first* is taken: a multi-act night repeats
     * `.redsubtitle` inside each act's own blurb further down (`field-body-2`, `field-body-3`),
     * which is prose about an act already billed above, not a second lineup.
     *
     * Returns `null` for a night billed in plain prose with no `.redsubtitle` at all.
     */
    private fun findBillingParagraph(node: Element): Element? = node.select(BILLING_PARAGRAPH).firstOrNull()

    /**
     * Joins the node's prose into a description, one paragraph per line.
     *
     * Collects the paragraphs of every text field — the intro text, the body, and the
     * `field-body-N` blurbs a multi-act night adds per act — selected by Drupal's stable
     * *field-type* classes rather than by name, so a night that grows a `field-body-4` is picked
     * up without a code change. Image-caption `blockquote`s are excluded for free: they live
     * inside the image fields, which are not text fields.
     *
     * The [billingParagraph] is dropped, since it is already stored as the subtitle.
     */
    private fun parseDescription(
        node: Element,
        billingParagraph: Element?
    ): String? =
        node
            .select(PROSE_PARAGRAPHS)
            .filterNot { it === billingParagraph }
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    /**
     * Builds the lineup from the `.redsubtitle` spans of the [billingParagraph].
     *
     * Each span is one billing line, and its leading label decides the role: a `Live:` line bills
     * live acts as headliners, every other line (`Dj:`, `on the couch:`, or an unlabelled list of
     * DJ names) bills DJs. The label is then stripped so it cannot become part of a name.
     *
     * The event *title* is never minted as an artist: at this venue it names the night or the
     * hosting series ("Hot Tunes for Cool Cats", "MissVergnügen presents RESITANT – live"), not
     * the performer — the performers are exactly what these lines list.
     */
    private fun parseLineup(billingParagraph: Element?): List<ScrapedArtist> =
        billingParagraph
            ?.select(".redsubtitle")
            .orEmpty()
            .flatMap { line ->
                val text = line.text().trim()
                val role = if (LIVE_LABEL.containsMatchIn(text)) "HEADLINER" else "DJ"
                splitActs(text.replaceFirst(BILLING_LABEL, "")).map { ScrapedArtist(name = it, role = role) }
            }

    /**
     * Splits one billing line into act names.
     *
     * The venue's own `|` separator is cut first, then each segment goes through the shared
     * [splitSupportActs] for the usual `, ` / `+` / `/` / `&` forms. An `a.k.a.` alias names one
     * performer twice ("Dinah Richten a.k.a. Seraphim"), so only the part before it is kept rather
     * than storing two artists for one person.
     */
    private fun splitActs(line: String): List<String> =
        line
            .split(PIPE_SEPARATOR)
            .flatMap { splitSupportActs(it) }
            .map { it.split(ALIAS_SEPARATOR).first().trim() }
            .filter { it.isNotBlank() }
            .filterNot { isNonArtistName(it) }

    /** Extracts the slug from a node path alias, decoding its percent escapes: `/cool-tunes-hot-cats-19082026` → `cool-tunes-hot-cats-19082026`. */
    private fun extractSlug(path: String): String = URI(path).path.trim('/')

    private companion object {
        /**
         * The paragraphs of the Drupal text fields holding a node's prose, matched by *field type*
         * rather than field name so the per-act `field-body-2`/`-3`/… series needs no enumeration.
         * `text-with-summary` is the body field's type; `text-long` covers the intro text and the
         * extra blurbs. Spelled out per field type because a comma in a Jsoup selector separates
         * two complete selectors — the ` p` would otherwise bind to the last branch only. Photo
         * credits are excluded for free: they sit in a `blockquote` inside the *image* fields.
         */
        const val PROSE_PARAGRAPHS = ".field-type-text-with-summary p, .field-type-text-long p"

        /** The prose paragraphs carrying a `.redsubtitle` billing line. See [PROSE_PARAGRAPHS] on the repetition. */
        const val BILLING_PARAGRAPH =
            ".field-type-text-with-summary p:has(.redsubtitle), .field-type-text-long p:has(.redsubtitle)"

        /**
         * A leading role/format label on a billing line — the slot, not the performer, so it is
         * stripped before the names are read. "on the couch" is the venue's own phrase for the DJ
         * seat in its front room. Anchored and **colon-terminated**, so the unlabelled "DJ VELA &
         * DJ Sky Deep" form keeps the `DJ` that is part of each act's name. `djs` precedes `dj` so
         * the longer label wins the alternation.
         */
        val BILLING_LABEL = Regex("""^(?:live|djs|dj|on\s+the\s+couch)\s*:\s*""", RegexOption.IGNORE_CASE)

        /** The `Live:` label, which bills the line's acts as headliners rather than DJs. */
        val LIVE_LABEL = Regex("""^live\s*:""", RegexOption.IGNORE_CASE)

        /** The venue's own act separator on a billing line, alongside the shared `, ` / `+` / `/` / `&` forms. */
        val PIPE_SEPARATOR = Regex("""\s*\|\s*""")

        /**
         * An `a.k.a.` alias introducing a performer's second name. The venue writes it with and
         * without the trailing space ("Dinah Richten a.k.a.Seraphim"), so nothing is required on
         * the right — but leading whitespace **is**, and the dotted form must keep its dots, so a
         * real act whose name merely opens with those letters ("Akatombo") is not torn in half.
         */
        val ALIAS_SEPARATOR = Regex("""\s+a\.k\.a\.?\s*|\s+aka\s+""", RegexOption.IGNORE_CASE)

        /**
         * The free-entry phrases the venue writes into its prose ("Eintritt frei"). The lookahead
         * rejects a time-limited offer ("Eintritt frei bis 22 Uhr"), which is not a free event.
         */
        val FREE_ENTRY_PHRASE =
            Regex("""(?:eintritt frei|freier eintritt|free entry)(?!\s+(?:till|until|before|bis|ab)\b)""", RegexOption.IGNORE_CASE)
    }
}
