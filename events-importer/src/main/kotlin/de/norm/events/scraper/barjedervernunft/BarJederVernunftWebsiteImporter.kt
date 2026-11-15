package de.norm.events.scraper.barjedervernunft

import de.norm.events.event.SpokenLanguage
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Bar jeder Vernunft, the Wilmersdorf Spiegelzelt (cabaret / variety
 * theatre). The fetch and the show-page reads are [AbstractBarJederVernunftImporter]'s.
 *
 * @see de.norm.events.scraper.tipiamkanzleramt.TipiAmKanzleramtWebsiteImporter for the sister venue.
 * @see <a href="https://www.bar-jeder-vernunft.de/de/programm/kalender.html">Bar jeder Vernunft calendar</a>
 */
@Component
class BarJederVernunftWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractBarJederVernunftImporter(htmlFetcher, "Bar jeder Vernunft", EventSource.BAR_JEDER_VERNUNFT)

/**
 * German is the house language because the programme is German in practice (#2584). On `2026-10-04` the
 * production API listed 46 upcoming shows and comedy nights, and all 46 descriptions were German. The
 * concerts take no language.
 */
val BAR_JEDER_VERNUNFT_LIMITATIONS =
    VenueLimitations(
        EventSource.BAR_JEDER_VERNUNFT,
        AcceptedLimitation(
            LimitedAspect.TICKET_URL,
            "a date whose presale has not opened shows only a notification form, and a sold-out date shows no shop link",
            verified = "2026-11-15"
        ),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar and the show pages state one start time and never an Einlass", verified = "2026-10-27"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself", verified = "2026-10-13"),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "a musical, a song salon or an award night is billed under the production's name, and the cast section is not read",
            verified = "2026-11-15"
        ),
        houseLanguage = SpokenLanguage.GERMAN
    )
