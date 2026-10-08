package de.norm.events.scraper.kesselhaus

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Shared importer for the two Kulturbrauerei stages on the Kesselhaus calendar, `/de/calendar`.
 *
 * Each page shows five months, so the run walks `?part=` windows until one is empty, then reads each
 * upcoming event's own page for its text. Both stages read the same pages and keep their own [room].
 *
 * @see KesselhausCalendarScraper for the transfer state and the window arithmetic.
 */
@Suppress("AbstractClassCanBeConcreteClass") // A base for the room importers below it; an instance of it alone names no venue.
abstract class AbstractKesselhausRoomImporter(
    htmlFetcher: HtmlFetcher,
    private val room: KesselhausRoom,
    private val scraper: KesselhausCalendarScraper = KesselhausCalendarScraper(room)
) : AbstractSinglePageWebsiteImporter(htmlFetcher, room.venueName, scraper::scrape) {
    override val eventSource: EventSource get() = room.eventSource
    override val listsWholeProgramme: Boolean = true
    override val maxListingPages: Int = MAX_WINDOWS
    override val enrichFromEventPage: (ScrapedEvent, Document) -> ScrapedEvent? = scraper::enrich
    override val eventPageOwns: Set<ScrapedField> = setOf(ScrapedField.DESCRIPTION, ScrapedField.IMAGE)

    override fun nextListingPage(
        document: Document,
        url: String
    ): String? = scraper.nextPage(document, url)

    private companion object {
        /** About two years ahead; the venue announces about one. */
        const val MAX_WINDOWS = 6
    }
}

/** The Kesselhaus, with the nights it shares with the Maschinenhaus. */
@Component
class KesselhausWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractKesselhausRoomImporter(htmlFetcher, KesselhausRoom.KESSELHAUS)

/** The Maschinenhaus, the small stage beside it. */
@Component
class MaschinenhausWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractKesselhausRoomImporter(htmlFetcher, KesselhausRoom.MASCHINENHAUS)

val KESSELHAUS_LIMITATIONS =
    VenueLimitations(
        sources = setOf(EventSource.KESSELHAUS, EventSource.MASCHINENHAUS),
        limitations =
            listOf(
                AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar states one time per event; only a few event texts add an Einlass time"),
                AcceptedLimitation(LimitedAspect.GENRE, "only some events carry a style topic; the others name a format such as festival or highlight"),
                AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "most events list an online price and no box-office price")
            )
    )
