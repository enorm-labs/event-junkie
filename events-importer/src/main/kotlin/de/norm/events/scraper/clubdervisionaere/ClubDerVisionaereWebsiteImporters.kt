package de.norm.events.scraper.clubdervisionaere

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Shared fetch orchestration for the three rooms that Club der Visionäre lists on one
 * programme page (see [ClubDerVisionaereRoom]).
 *
 * The pipeline is a single request per cycle: fetch the programme page via [HtmlFetcher]
 * and hand it to [ClubDerVisionaereProgrammePageScraper], which returns only the nights
 * belonging to this importer's [room]. There are no detail pages — the listing is the
 * whole programme.
 *
 * The WordPress REST API is not an option despite ADR-007's JSON-first preference: upcoming
 * nights are `future`-status posts, which `/wp-json/wp/v2/posts` omits from its listing and
 * 401s when asked for by id. The rendered page is the only source.
 *
 * Each room is a **separate bean and [EventSource]** rather than one importer serving
 * three source rows, because the rows cannot be told apart by URL: both hosts serve the
 * identical page, and the room lives only in a CSS class on the title. Keeping them
 * separate gives each venue its own `event_source` row, its own `sourceId` prefix and
 * its own import status, all from one parser.
 *
 * Conditional requests are passed through as usual, but the server currently sends
 * neither ETag nor Last-Modified, so every cycle is a full fetch; the idempotent
 * `sourceId` upsert absorbs that.
 *
 * @see ClubDerVisionaereProgrammePageScraper for the HTML parsing logic.
 * @see <a href="https://clubdervisionaere.com/programm/">Club der Visionäre programme</a>
 */
abstract class AbstractClubDerVisionaereRoomImporter(
    private val htmlFetcher: HtmlFetcher,
    /** The room whose nights this importer keeps from the shared listing. */
    private val room: ClubDerVisionaereRoom,
    clock: Clock
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    private val programmePageScraper = ClubDerVisionaereProgrammePageScraper(clock)

    override val eventSource: EventSource get() = room.eventSource

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        when (val fetchResult = htmlFetcher.fetch(url, etag, lastModified)) {
            is FetchResult.NotModified -> {
                ImportResult.NotModified
            }

            is FetchResult.Success -> {
                val events = programmePageScraper.scrape(fetchResult.document, url, room)
                logger.info { "Scraped ${events.size} event(s) for ${room.eventSource.name}" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

/**
 * Website importer for Club der Visionäre itself — the `.cdvRed` nights on the shared
 * programme page. The open-air club runs in summer; in winter the page carries the boat's
 * programme instead and this source legitimately imports nothing.
 */
@Component
class ClubDerVisionaereWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the parser's weekday-based year inference. Defaults to the system clock; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractClubDerVisionaereRoomImporter(htmlFetcher, ClubDerVisionaereRoom.CLUB, clock)

/**
 * Website importer for the Sonnenraum concert space — the `.sonnenraumYellow` nights on
 * the shared Club der Visionäre programme page.
 */
@Component
class SonnenraumWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the parser's weekday-based year inference. Defaults to the system clock; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractClubDerVisionaereRoomImporter(htmlFetcher, ClubDerVisionaereRoom.SONNENRAUM, clock)

/**
 * Website importer for the MS Hoppetosse boat — the `.hoppetosseYellow` nights on the
 * shared Club der Visionäre programme page (`hoppetosse.berlin/program/` serves the same
 * listing). The boat is the winter location, so this source imports nothing in summer.
 */
@Component
class MsHoppetosseWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the parser's weekday-based year inference. Defaults to the system clock; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractClubDerVisionaereRoomImporter(htmlFetcher, ClubDerVisionaereRoom.MS_HOPPETOSSE, clock)

val CLUB_DER_VISIONAERE_LIMITATIONS =
    VenueLimitations(
        sources = setOf(EventSource.CLUB_DER_VISIONAERE, EventSource.SONNENRAUM, EventSource.MS_HOPPETOSSE),
        limitations =
            listOf(
                AcceptedLimitation(LimitedAspect.START_TIME, "the venue never publishes one; a from-HH:mm marker on the act line is that act's set time"),
                AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the venue publishes no category of its own; every listing is a club night"),
                AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the programme page is the source for every night")
            )
    )
