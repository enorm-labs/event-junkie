package de.norm.events.scraper.clubdervisionaere

import de.norm.events.common.BERLIN
import de.norm.events.common.rethrowIfCancelled
import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock

/**
 * Shared fetch orchestration for the three rooms Club der Visionäre lists on one programme
 * page (see [ClubDerVisionaereRoom]).
 *
 * Two requests per cycle: [HtmlFetcher] fetches the programme page and
 * [ClubDerVisionaereProgrammePageScraper] returns only this importer's [room]; then, in
 * [afterListing], the homepage, whose NEXT box is the one place the venue prints a start time,
 * joined onto the nights by WordPress post id via [ClubDerVisionaereHomePageScraper]. No detail
 * pages — the listing is the whole programme. An unfetchable homepage costs the nights their time, not the
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
) : AbstractSinglePageWebsiteImporter(
        htmlFetcher,
        room.eventSource.name,
        ClubDerVisionaereProgrammePageScraper(clock).let { scraper -> { document, url -> scraper.scrape(document, url, room) } }
    ) {
    private val logger = KotlinLogging.logger {}

    private val homePageScraper = ClubDerVisionaereHomePageScraper()

    override val eventSource: EventSource get() = room.eventSource
    override val listsWholeProgramme: Boolean = true
    override val fetchesBeyondEntryPage: Boolean = true

    // Logged in afterListing: the base's logger names the room's subclass, this one names this class.
    override val listedLine: ((Int) -> String)? = null

    override suspend fun afterListing(
        events: List<ScrapedEvent>,
        document: Document,
        url: String
    ): List<ScrapedEvent> {
        logger.info { "Scraped ${events.size} event(s) for ${room.eventSource.name}" }
        return if (events.isEmpty()) events else withStartTimes(events, url)
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
                rethrowIfCancelled()
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
    clock: Clock = Clock.system(BERLIN)
) : AbstractClubDerVisionaereRoomImporter(htmlFetcher, ClubDerVisionaereRoom.CLUB, clock)

/**
 * The Sonnenraum concert space — the `.sonnenraumYellow` nights on the shared page.
 */
@Component
class SonnenraumWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for weekday-based year inference; override in tests. */
    clock: Clock = Clock.system(BERLIN)
) : AbstractClubDerVisionaereRoomImporter(htmlFetcher, ClubDerVisionaereRoom.SONNENRAUM, clock)

/**
 * The MS Hoppetosse boat — the `.hoppetosseYellow` nights (`hoppetosse.berlin/program/` serves
 * the same listing). The winter location, so this source imports nothing in summer.
 */
@Component
class MsHoppetosseWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for weekday-based year inference; override in tests. */
    clock: Clock = Clock.system(BERLIN)
) : AbstractClubDerVisionaereRoomImporter(htmlFetcher, ClubDerVisionaereRoom.MS_HOPPETOSSE, clock)

val CLUB_DER_VISIONAERE_LIMITATIONS =
    VenueLimitations(
        sources = setOf(EventSource.CLUB_DER_VISIONAERE, EventSource.SONNENRAUM, EventSource.MS_HOPPETOSSE),
        limitations =
            listOf(
                AcceptedLimitation(
                    LimitedAspect.START_TIME,
                    "the listing prints one only where an act line carries a slot time; the homepage's NEXT box prints the rest, " +
                        "for the ten nights it shows",
                    verified = "2026-09-22"
                ),
                AcceptedLimitation(
                    LimitedAspect.EVENT_TYPE,
                    "the venue publishes no category of its own; every listing is a club night",
                    verified = "2026-08-25"
                ),
                AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the programme page is the source for every night", verified = "2026-09-30"),
                AcceptedLimitation(LimitedAspect.PRICE, "the programme lists times and the line-up only, never an admission price", verified = "2026-09-30"),
                AcceptedLimitation(LimitedAspect.IMAGE, "the programme is text only; the site's only images are its logos", verified = "2026-10-13"),
                AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself", verified = "2026-10-13")
            )
    )
