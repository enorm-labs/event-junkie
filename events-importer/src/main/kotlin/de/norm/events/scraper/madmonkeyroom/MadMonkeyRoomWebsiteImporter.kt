package de.norm.events.scraper.madmonkeyroom

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Importer for the Mad Monkey Room, read from its rausgegangen ticket shop
 * (`t.rausgegangen.de/tickets/shop/mad-monkey-2`). The club's own Next.js site carries no dates; the
 * shop lists every date on sale, about fourteen months ahead, server-rendered on one page. Each date
 * has its own shop page with the series text and the doors time, but the shop holds some 460 dates,
 * so only the list is read: one request per run.
 *
 * @see MadMonkeyRoomShopPageScraper for the list markup.
 */
@Component
class MadMonkeyRoomWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Mad Monkey Room", MadMonkeyRoomShopPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.MAD_MONKEY_ROOM
    override val listsWholeProgramme: Boolean = true
}

val MAD_MONKEY_ROOM_LIMITATIONS =
    VenueLimitations(
        EventSource.MAD_MONKEY_ROOM,
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the shop list carries no text; each date's shop page repeats its series' text"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the shop list prints only the start; doors appear only in each date's page text"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the shop list prints only the start"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the club names a night's comedians only on social media, shortly before"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the shop list marks no date as sold out"),
        AcceptedLimitation(LimitedAspect.GENRE, "the shop names no genre"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "every show is the club's own")
    )
