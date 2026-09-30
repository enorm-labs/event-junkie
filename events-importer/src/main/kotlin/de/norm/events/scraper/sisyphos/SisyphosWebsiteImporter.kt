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

/**
 * Website importer for Sisyphos: its Shopify ticket shop, and on weekends the fan-run timetable
 * at sisy.fan (ADR-036).
 *
 * The club publishes no programme. Its only site is the merch shop, whose `TICKETS` collection
 * carries the few nights sold in advance — one `generationS` a month beside the T-shirts. Shopify
 * exposes every collection as JSON at `/collections/<handle>/products.json`, so one [ApiClient]
 * request feeds [SisyphosApiScraper]. The endpoint answers with a weak `ETag` that [ApiClient]
 * does not read, so every import returns [ImportResult.Success] and the idempotent `sourceId`
 * upsert absorbs the repeat.
 *
 * The weekend programme comes from sisy.fan, whose developer allows it on two conditions: the site
 * is credited, and it is read as little as possible. So sisy.fan is read only inside
 * [inSisyfanWindow], whichever path started the import — scheduler, manual, forced or retry. A
 * shop night inside a sisy.fan weekend keeps its own title, date and ticket, and takes the
 * weekend's line-up and end; any other weekend is an event of its own.
 *
 * A shop-only run leaves out the nights dated today or earlier. Otherwise a run early on Sunday
 * would store a merged Saturday again without its line-up, because the line-up is replaced by the
 * scrape's (`AssociationSyncService`).
 */
@Component
class SisyphosWebsiteImporter(
    private val apiClient: ApiClient,
    private val htmlFetcher: HtmlFetcher,
    private val clock: Clock = Clock.systemUTC()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.SISYPHOS

    private val apiScraper = SisyphosApiScraper()
    private val timetableScraper = SisyfanTimetableScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val shop = apiScraper.scrape(apiClient.fetchJson(url), url)
        val now = ZonedDateTime.ofInstant(clock.instant(), BERLIN)
        val weekends = if (inSisyfanWindow(now)) fetchWeekends() else null
        if (weekends == null) {
            logger.info { "Scraped ${shop.size} event(s) from the Sisyphos ticket shop; sisy.fan not read" }
            return ImportResult.Success(events = shop.filter { it.eventDate.isAfter(now.toLocalDate()) }, etag = null, lastModified = null)
        }
        logger.info { "Scraped ${shop.size} event(s) from the Sisyphos ticket shop and ${weekends.size} weekend(s) from sisy.fan" }
        return ImportResult.Success(events = mergeWeekends(shop, weekends), etag = null, lastModified = null)
    }

    /** The weekends on sisy.fan, or null when the page failed, which makes the run shop-only. */
    @Suppress("TooGenericExceptionCaught") // A failed sisy.fan fetch must not cost the shop's nights
    private suspend fun fetchWeekends(): List<ScrapedEvent>? =
        try {
            timetableScraper.scrape(htmlFetcher.fetchDocument(SISYFAN_URL))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read sisy.fan; importing the Sisyphos ticket shop only" }
            null
        }

    private companion object {
        const val SISYFAN_URL = "${SisyfanTimetableScraper.BASE_URL}/"
    }
}

/**
 * The shop's nights with each sisy.fan weekend merged into the night it contains, then the weekends
 * no shop night claimed. A merged night keeps its date, because the slug contains it.
 */
internal fun mergeWeekends(
    shop: List<ScrapedEvent>,
    weekends: List<ScrapedEvent>
): List<ScrapedEvent> {
    val claimed = mutableSetOf<String>()
    val nights =
        shop.map { night ->
            val weekend =
                weekends.firstOrNull { night.eventDate in it.eventDate..(it.endDate ?: it.eventDate) }
                    ?: return@map night
            claimed += weekend.sourceId
            night.copy(
                artists = weekend.artists,
                endDate = weekend.endDate,
                endTime = weekend.endTime,
                lineupSourceUrl = weekend.lineupSourceUrl
            )
        }
    return nights + weekends.filter { it.sourceId !in claimed }
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
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the shop files every night as a ticket product with no category; each is stored as a party"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "a ticket product names a day and never a time, and sisy.fan times only the sets"),
        AcceptedLimitation(
            LimitedAspect.START_TIME,
            "a ticket product names a day and never a time; a shop night keeps no start even when sisy.fan times its weekend's first set"
        ),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "the shop names no DJ anywhere; the line-up comes from sisy.fan, which posts a weekend on Friday night or Saturday and is read only then"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the shop names no musical style; every night takes the club's Techno, House default"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "the shop sells online only and states no door price"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "a cancelled night is removed from the shop rather than marked"),
        houseGenre = "Techno, House"
    )
