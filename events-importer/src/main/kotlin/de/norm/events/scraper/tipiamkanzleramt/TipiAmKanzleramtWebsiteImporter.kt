package de.norm.events.scraper.tipiamkanzleramt

import de.norm.events.event.SpokenLanguage
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.barjedervernunft.AbstractBarJederVernunftImporter
import org.springframework.stereotype.Component

/**
 * Website importer for TIPI am Kanzleramt, the Tiergarten tent theatre. Bar jeder Vernunft runs it,
 * and both sites are one Neos build, so the fetch and the show-page reads are
 * [AbstractBarJederVernunftImporter]'s.
 *
 * @see de.norm.events.scraper.barjedervernunft.BarJederVernunftWebsiteImporter for the sister venue.
 * @see <a href="https://www.tipi-am-kanzleramt.de/de/programm/kalender.html">TIPI am Kanzleramt calendar</a>
 */
@Component
class TipiAmKanzleramtWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractBarJederVernunftImporter(htmlFetcher, "TIPI am Kanzleramt", EventSource.TIPI_AM_KANZLERAMT)

/** German is the house language: the shows, readings and talks are German, the concerts take none. */
val TIPI_AM_KANZLERAMT_LIMITATIONS =
    VenueLimitations(
        EventSource.TIPI_AM_KANZLERAMT,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar and the show pages state one start time and never an Einlass", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself", verified = "2026-10-16"),
        houseLanguage = SpokenLanguage.GERMAN
    )
