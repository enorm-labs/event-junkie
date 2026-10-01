package de.norm.events.scraper.sisyphos

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * Website importer for Sisyphos: the club's own calendar, its Shopify ticket shop, and on weekends
 * the fan-run timetable at sisy.fan (ADR-036, ADR-038).
 *
 * The calendar is the programme: one JSON feed, read by [SisyphosCalendarScraper], with every
 * night's opening and closing. The shop's `TICKETS` collection, read by [SisyphosApiScraper],
 * adds the presale price, the sold-out state and the product link, joined by the calendar's
 * `ticketLink`, else by date. A shop night the calendar does not list yet stays an event of its
 * own. No validators are read: a 304 on the calendar would also skip the shop and sisy.fan.
 *
 * The weekend line-up comes from sisy.fan, whose developer allows it on two conditions: the site
 * is credited, and it is read as little as possible. So sisy.fan is read only inside
 * [inSisyfanWindow], whichever path started the import — scheduler, manual, forced or retry. A
 * night that contains a sisy.fan weekend's first set takes its line-up; any other weekend is an
 * event of its own.
 *
 * A run that did not read sisy.fan leaves out the nights dated today or earlier. Otherwise a run
 * early on Sunday would store a merged weekend again without its line-up, because the line-up is
 * replaced by the scrape's (`AssociationSyncService`). A run that lost the calendar or the shop is
 * incomplete, so the stale cleanup cannot remove the nights it did not read. A failed shop is
 * replaced by its nights from the last run that read them; after a restart there are none, and
 * the calendar nights go without a price until the shop answers again. The run fails only when
 * the calendar, the shop and sisy.fan all deliver nothing.
 *
 * The shop's bot protection answers the importer `429` from a hosting address, while
 * curl from the same node gets `200` (#2199). That is a block on our client, and we do not disguise the
 * client (`docs/SCRAPING_POSITION.md` §3.4), so the source stays enabled and recovers if it is lifted.
 */
@Component
class SisyphosWebsiteImporter(
    private val apiClient: ApiClient,
    private val htmlFetcher: HtmlFetcher,
    private val clock: Clock = Clock.systemUTC()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.SISYPHOS

    private val calendarScraper = SisyphosCalendarScraper()
    private val apiScraper = SisyphosApiScraper()
    private val timetableScraper = SisyfanTimetableScraper()

    /** The shop's nights from the last run that read them, joined into the calendar when the shop fails. */
    @Volatile
    private var lastShopNights: List<ScrapedEvent> = emptyList()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val now = ZonedDateTime.ofInstant(clock.instant(), BERLIN)
        val calendar = attempt("calendar") { calendarScraper.scrape(apiClient.fetchJson(SisyphosCalendarScraper.FEED_URL)) }
        val shop = attempt("ticket shop") { apiScraper.scrape(apiClient.fetchJson(url), url).also { lastShopNights = it } }
        val weekends = if (inSisyfanWindow(now)) fetchWeekends() else null
        if (calendar.isFailure && shop.isFailure && weekends == null) throw checkNotNull(shop.exceptionOrNull())

        val nights = joinShop(calendar.getOrDefault(emptyList()), shop.getOrDefault(lastShopNights))
        val events = weekends?.let { mergeWeekends(nights, it) } ?: nights.filter { it.eventDate.isAfter(now.toLocalDate()) }
        logger.info {
            "Scraped ${calendar.getOrNull()?.size ?: "no"} calendar night(s), ${shop.getOrNull()?.size ?: "no"} shop night(s) " +
                "and ${weekends?.size ?: "no"} sisy.fan weekend(s) for Sisyphos; importing ${events.size} event(s)"
        }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = calendar.isSuccess && shop.isSuccess)
    }

    /** One site's nights, or its failure, logged: the other sites are still worth importing without it. */
    @Suppress("TooGenericExceptionCaught") // A failed site must not cost the others; the caller decides whether the run fails
    private suspend fun attempt(
        site: String,
        fetch: suspend () -> List<ScrapedEvent>
    ): Result<List<ScrapedEvent>> =
        try {
            Result.success(fetch())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read the Sisyphos $site; importing the other sites" }
            Result.failure(e)
        }

    /** The weekends on sisy.fan, or null when the page failed. */
    @Suppress("TooGenericExceptionCaught") // A failed sisy.fan fetch must not cost the calendar and the shop
    private suspend fun fetchWeekends(): List<ScrapedEvent>? =
        try {
            timetableScraper.scrape(htmlFetcher.fetchDocument(SISYFAN_URL))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read sisy.fan; importing without the weekend line-ups" }
            null
        }

    private companion object {
        const val SISYFAN_URL = "${SisyfanTimetableScraper.BASE_URL}/"
    }
}

/**
 * The calendar's nights, each with the shop night it sells joined in, then the shop nights the
 * calendar does not list. A shop night joins the calendar night whose `ticketLink` names the same
 * product, else the one on the same date. The calendar keeps its title and times, and takes the
 * shop's price, sold-out state and product link.
 */
internal fun joinShop(
    calendar: List<ScrapedEvent>,
    shop: List<ScrapedEvent>
): List<ScrapedEvent> {
    val unclaimed = shop.toMutableList()
    val nights =
        calendar.map { night ->
            val handle = night.ticketUrl?.let(::shopHandle)
            val sold =
                unclaimed.firstOrNull { handle != null && it.ticketUrl?.let(::shopHandle) == handle }
                    ?: unclaimed.firstOrNull { it.eventDate == night.eventDate }
                    ?: return@map night
            unclaimed -= sold
            night.copy(pricePresale = sold.pricePresale, soldOut = sold.soldOut, ticketUrl = sold.ticketUrl)
        }
    val listed = nights.map { it.sourceId }.toSet()
    return nights + unclaimed.filter { it.sourceId !in listed }
}

/** The product handle in a shop link `…/products/<handle>`, or null for any other page. */
private fun shopHandle(url: String): String? = SHOP_PRODUCT.find(url)?.groupValues?.get(1)

private val SHOP_PRODUCT = Regex("""/products/([^/?#]+)""")

/**
 * The nights with each sisy.fan weekend merged into the night with its key or the night that
 * contains its first set, then the weekends no night claimed. A merged night keeps its date and times, because the slug contains
 * the date and the calendar's times are the club's; a night without times, which only the shop
 * sold, takes the weekend's end.
 */
internal fun mergeWeekends(
    nights: List<ScrapedEvent>,
    weekends: List<ScrapedEvent>
): List<ScrapedEvent> {
    val claimed = mutableSetOf<String>()
    val merged =
        nights.map { night ->
            val weekend = weekends.firstOrNull { it.sourceId == night.sourceId || night.contains(it) } ?: return@map night
            claimed += weekend.sourceId
            night.copy(
                artists = weekend.artists,
                endDate = night.endDate ?: weekend.endDate,
                endTime = night.endTime ?: weekend.endTime,
                lineupSourceUrl = weekend.lineupSourceUrl
            )
        }
    val listed = merged.map { it.sourceId }.toSet()
    return merged + weekends.filter { it.sourceId !in claimed && it.sourceId !in listed }
}

/**
 * Whether [weekend]'s first set falls inside this night. A timed night compares the instant, so a
 * Thursday night ending on Friday at 03:00 does not take the Friday weekend; a night without times
 * falls back to its date lying within the weekend's days.
 */
private fun ScrapedEvent.contains(weekend: ScrapedEvent): Boolean {
    val opens = startTime?.let { eventDate.atTime(it) }
    val closes = endTime?.let { (endDate ?: eventDate).atTime(it) }
    val firstSet = weekend.startTime?.let { weekend.eventDate.atTime(it) }
    return if (opens != null && closes != null && firstSet != null) {
        firstSet in opens..closes
    } else {
        eventDate in weekend.eventDate..(weekend.endDate ?: weekend.eventDate)
    }
}

/**
 * Whether [now] is inside the hours the sisy.fan developer allows us to read the site: Friday
 * 22:00 to Sunday 04:00, Berlin time (ADR-036). The line-up usually goes up on Friday night or
 * on Saturday.
 */
internal fun inSisyfanWindow(now: ZonedDateTime): Boolean {
    val local = now.withZoneSameInstant(BERLIN)
    val time = local.toLocalTime()
    return when (local.dayOfWeek) {
        DayOfWeek.FRIDAY -> time >= SISYFAN_WINDOW_OPENS
        DayOfWeek.SATURDAY -> true
        DayOfWeek.SUNDAY -> time < SISYFAN_WINDOW_CLOSES
        else -> false
    }
}

private val SISYFAN_WINDOW_OPENS: LocalTime = LocalTime.of(22, 0)
private val SISYFAN_WINDOW_CLOSES: LocalTime = LocalTime.of(4, 0)

val SISYPHOS_LIMITATIONS =
    VenueLimitations(
        EventSource.SISYPHOS,
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the calendar files nights with no category; each is stored as a party, the market and the open day included"
        ),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar gives the opening, stored as the start, and no separate doors time"),
        AcceptedLimitation(
            LimitedAspect.START_TIME,
            "a night the calendar does not list yet comes from the shop alone, whose product names a day and never a time"
        ),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "the calendar and the shop name no DJ; the line-up comes from sisy.fan, which posts a weekend on Friday night or Saturday and is read only then"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "neither the calendar nor the shop names a musical style; every night takes the club's Techno, House default"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "neither the calendar nor the shop states a door price"),
        AcceptedLimitation(
            LimitedAspect.TICKET_URL,
            "the shop sells few nights, and its bot protection answers the importer 429 from a hosting address; we do not disguise the client"
        ),
        AcceptedLimitation(
            LimitedAspect.PRICE_PRESALE,
            "the shop sells few nights, and its bot protection answers the importer 429 from a hosting address; we do not disguise the client"
        ),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "neither the calendar nor the shop marks a cancelled night"),
        houseGenre = "Techno, House"
    )
