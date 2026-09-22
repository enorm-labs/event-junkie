package de.norm.events.scraper.clubdervisionaere

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock

/**
 * Shared fetch orchestration for the three rooms Club der Visionäre lists on one programme
 * page (see [ClubDerVisionaereRoom]).
 *
 * Two requests per cycle: [HtmlFetcher] fetches the programme page and
 * [ClubDerVisionaereProgrammePageScraper] returns only this importer's [room]; then the
 * homepage, whose NEXT box is the one place the venue prints a start time, joined onto the
 * nights by WordPress post id via [ClubDerVisionaereHomePageScraper]. No detail pages — the
 * listing is the whole programme. An unfetchable homepage costs the nights their time, not the
 * import.
 *
 * The WordPress REST API is no option despite ADR-007's JSON-first preference: upcoming nights
 * are `future`-status posts, which `/wp-json/wp/v2/posts` omits and 401s by id. The rendered
 * page is the only source.
 *
 * Each room is a **separate bean and [EventSource]**, not one importer serving three rows,
 * because the rows cannot be told apart by URL: both hosts serve the identical page and the room
 * lives only in a CSS class on the title. Separate rooms give each venue its own `event_source`
 * row, `sourceId` prefix and import status, from one parser.
 *
 * Conditional requests pass through for the programme page, but the server sends neither ETag
 * nor Last-Modified, so every cycle is a full fetch; the idempotent `sourceId` upsert absorbs
 * that. The homepage is fetched unconditionally.
 *
 * @see ClubDerVisionaereProgrammePageScraper for the HTML parsing logic.
 * @see <a href="https://clubdervisionaere.com/programm/">Club der Visionäre programme</a>
 */
@Suppress("AbstractClassCanBeConcreteClass") // A base for the venue importers below it; an instance of it alone names no venue.
abstract class AbstractClubDerVisionaereRoomImporter(
    private val htmlFetcher: HtmlFetcher,
    /** The room whose nights this importer keeps from the shared listing. */
    private val room: ClubDerVisionaereRoom,
    clock: Clock
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    private val programmePageScraper = ClubDerVisionaereProgrammePageScraper(clock)
    private val homePageScraper = ClubDerVisionaereHomePageScraper()

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
                    events = if (events.isEmpty()) events else withStartTimes(events, url),
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    /**
     * Joins the homepage's start times onto [events] by post id. A night the homepage does not
     * list keeps whatever the listing's own slot times gave it, and nothing when they gave none —
     * never a guess; an unreachable homepage leaves every night on the listing's answer, logged as
     * a warning, since the programme imported fine.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: the homepage is the clock, not the listing; degrade rather than fail.
    private suspend fun withStartTimes(
        events: List<ScrapedEvent>,
        programmeUrl: String
    ): List<ScrapedEvent> {
        val homeUrl = URI(programmeUrl).resolve("/").toString()
        val times =
            try {
                homePageScraper.scrape(htmlFetcher.fetchDocument(homeUrl))
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch the Club der Visionäre homepage $homeUrl, storing the nights without a start time" }
                return events
            }
        val prefix = room.eventSource.sourceIdPrefix
        return events.map { event -> event.copy(startTime = times[event.sourceId.removePrefix(prefix)] ?: event.startTime) }
    }
}

/**
 * Club der Visionäre itself — the `.cdvRed` nights. The open-air club runs in summer; in winter
 * the page carries the boat's programme and this source legitimately imports nothing.
 */
@Component
class ClubDerVisionaereWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for weekday-based year inference; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractClubDerVisionaereRoomImporter(htmlFetcher, ClubDerVisionaereRoom.CLUB, clock)

/**
 * The Sonnenraum concert space — the `.sonnenraumYellow` nights on the shared page.
 */
@Component
class SonnenraumWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for weekday-based year inference; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractClubDerVisionaereRoomImporter(htmlFetcher, ClubDerVisionaereRoom.SONNENRAUM, clock)

/**
 * The MS Hoppetosse boat — the `.hoppetosseYellow` nights (`hoppetosse.berlin/program/` serves
 * the same listing). The winter location, so this source imports nothing in summer.
 */
@Component
class MsHoppetosseWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for weekday-based year inference; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractClubDerVisionaereRoomImporter(htmlFetcher, ClubDerVisionaereRoom.MS_HOPPETOSSE, clock)

val CLUB_DER_VISIONAERE_LIMITATIONS =
    VenueLimitations(
        sources = setOf(EventSource.CLUB_DER_VISIONAERE, EventSource.SONNENRAUM, EventSource.MS_HOPPETOSSE),
        limitations =
            listOf(
                AcceptedLimitation(
                    LimitedAspect.START_TIME,
                    "the listing prints one only where an act line carries a slot time; the homepage's NEXT box prints the rest, " +
                        "for the ten nights it shows"
                ),
                AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the venue publishes no category of its own; every listing is a club night"),
                AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the programme page is the source for every night")
            )
    )
