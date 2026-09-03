package de.norm.events.scraper

import de.norm.events.event.EventStatus
import de.norm.events.scraper.WixEventsWarmupData.WIX_EVENTS_APP_DEF_ID
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

// Shared reader for the Wix Events payload that Wix server-side-injects into every page
// hosting a Wix Events widget (currently Loge's /event-list, Maxxim's /partys and Colosseum's
// /event). Although the widget renders client-side, the full event data is emitted as strict JSON in a
// `<script type="application/json" id="wix-warmup-data">` block — a stable, machine-readable
// source (ADR-007 §"Prefer a JSON / API Source") that survives visual redesigns and carries
// clean ISO timestamps rather than the German date text ("17. Juli 2026") in the rendered cards.
// Venue-specific field mapping stays in each venue's scraper; only the payload location and
// the two field readers every Wix venue needs live here.

/**
 * Locates the Wix Events data payload inside a page's `wix-warmup-data` block.
 *
 * The events live at `appsWarmupData → <Wix Events appDefId> → <widget key> →
 * events.events`. The app-definition id [WIX_EVENTS_APP_DEF_ID] is a global Wix
 * constant (identical across every Wix site), while the widget key
 * (`widgetTPASection_…`, `widgetcomp-…`) is per-page, so the widget node is
 * located by looking for the child that actually carries an `events.events`
 * array rather than hard-coding the key.
 */
internal object WixEventsWarmupData {
    private val logger = KotlinLogging.logger {}

    /** The Wix Events app definition id — a global Wix constant shared by every Wix site. */
    private const val WIX_EVENTS_APP_DEF_ID = "140603ad-af8d-84a5-2c80-a0f60cb47351"

    /** Id of the `<script>` element Wix injects its server-side warmup state into. */
    private const val WARMUP_SCRIPT_ID = "wix-warmup-data"

    private val jsonMapper: JsonMapper = JsonMapper.builder().build()

    /**
     * Returns the `events.events` array node from the page's warmup data, or
     * `null` when the warmup script is absent, unparseable, or carries no Wix
     * Events widget (e.g. an empty program).
     *
     * @param source the venue whose page is being read, used only for log context.
     */
    @Suppress(
        "TooGenericExceptionCaught", // A malformed/absent payload must degrade to null, never abort the import
        "ReturnCount" // Sequential null-guards for each extraction step are clearer than nesting
    )
    fun events(
        document: Document,
        source: EventSource
    ): JsonNode? {
        val script = document.getElementById(WARMUP_SCRIPT_ID)
        if (script == null) {
            logger.warn { "No '$WARMUP_SCRIPT_ID' script found on $source overview page" }
            return null
        }
        val root =
            try {
                jsonMapper.readTree(script.data())
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse $source wix-warmup-data JSON" }
                return null
            }
        val appNode = root.path("appsWarmupData").path(WIX_EVENTS_APP_DEF_ID)
        if (!appNode.isObject) {
            logger.warn { "No Wix Events app data in $source warmup payload" }
            return null
        }
        // The widget key (widgetTPASection_…) is per-page, so find the child that carries the events
        // array. Iterating a JsonNode object yields its property values (same as an array yields elements).
        val events = appNode.firstNotNullOfOrNull { widget -> widget.path("events").path("events").takeIf { it.isArray } }
        if (events == null) {
            logger.warn { "No events array in $source Wix Events warmup payload" }
        }
        return events
    }
}

/**
 * Parses the Berlin-local date and start time from a Wix `scheduling.config`
 * node. The `startDate` is a UTC instant (`2026-07-17T17:00:00.000Z`) paired
 * with a `timeZoneId` (`Europe/Berlin`), so it is converted to that zone to
 * recover the wall-clock date/time (19:00, not the 17:00 UTC value). Falls back
 * to [FALLBACK_ZONE] when the zone is absent or unknown. Returns `(null, null)`
 * for a missing/unparseable `startDate` or a to-be-decided (`scheduleTbd`) event.
 */
@Suppress("ReturnCount") // Guard clauses for the missing/unparseable startDate are clearer than nesting
internal fun parseWixSchedule(config: JsonNode): Pair<LocalDate?, LocalTime?> {
    val startDate = config.stringOrNull("startDate") ?: return null to null
    val instant =
        try {
            Instant.parse(startDate)
        } catch (_: DateTimeParseException) {
            return null to null
        }
    val zone =
        config.stringOrNull("timeZoneId")?.let { id ->
            runCatching { ZoneId.of(id) }.getOrNull()
        } ?: FALLBACK_ZONE
    val zoned = instant.atZone(zone)
    return zoned.toLocalDate() to zoned.toLocalTime()
}

/** Default zone for Wix schedules missing a usable `timeZoneId` — every scraped venue is in Berlin. */
private val FALLBACK_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * Maps Wix's numeric event `status` to a domain [EventStatus] name.
 *
 * Wix serialises its `EventStatus` enum as an ordinal in the warmup payload:
 * `0` SCHEDULED, `1` STARTED, `2` ENDED, `3` CANCELED, `4` DRAFT. Only the
 * cancellation is meaningful here — a started/ended night is simply in the past,
 * and a draft is never published to the widget — so everything else keeps the
 * `SCHEDULED` default rather than inventing statuses the model has no place for.
 */
internal fun mapWixEventStatus(status: JsonNode): String =
    if (status.asInt(WIX_STATUS_SCHEDULED) == WIX_STATUS_CANCELED) EventStatus.CANCELLED.name else EventStatus.SCHEDULED.name

private const val WIX_STATUS_SCHEDULED = 0
private const val WIX_STATUS_CANCELED = 3

/**
 * Wix `registration.type` ordinals: `1` RSVP, `2` TICKETS, `3` EXTERNAL, `4` NO_REGISTRATION.
 *
 * Only `TICKETS` means Wix itself sells the tickets, and it is the **only** value for which the
 * `registration.ticketing` block describes reality. An event whose tickets are sold elsewhere
 * (`EXTERNAL`) still carries a `ticketing` node, but one that has no ticket definitions and
 * therefore reports `"soldOut": true` for an event that is on sale at the promoter's shop — see
 * [de.norm.events.scraper.colosseum.ColosseumOverviewPageScraper], where three of eighteen events
 * are external. Gate every read of `ticketing` on this value.
 */
internal const val WIX_REGISTRATION_TICKETS = 2

/**
 * Reads a Wix ticket price node (`{"amount": "19.30", "currency": "EUR"}`), or `null` when the
 * amount is absent or is not a number.
 *
 * The figure is Wix's checkout total: the face value plus the service fee Wix adds on top
 * (`wixFeeConfig.type: 2`), which is what a buyer pays. A ticket the venue names
 * "Standard (25€ + 2,5€ Gebühr)" is listed here at €28.19, and the face value alone is only on
 * the detail page.
 */
internal fun parseWixTicketPrice(price: JsonNode): BigDecimal? = price.stringOrNull("amount")?.toBigDecimalOrNull()

/**
 * Builds a price note only when an event has several ticket tiers, i.e. when the lowest and
 * highest formatted prices differ (`"€12.00 – €30.00"`).
 *
 * A single-tier event — the normal case — needs no note: [ScrapedEvent.pricePresale] already
 * says everything.
 */
internal fun wixPriceRangeNote(ticketing: JsonNode): String? {
    val lowest = ticketing.stringOrNull("lowestTicketPriceFormatted")
    val highest = ticketing.stringOrNull("highestTicketPriceFormatted")
    return if (lowest != null && highest != null && lowest != highest) "$lowest – $highest" else null
}
