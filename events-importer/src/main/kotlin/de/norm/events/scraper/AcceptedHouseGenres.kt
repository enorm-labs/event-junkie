package de.norm.events.scraper

import de.norm.events.event.HouseGenres
import org.springframework.stereotype.Component

/** The declared house genres, for the ARTIST derivation to leave out (#5387). */
@Component
class AcceptedHouseGenres : HouseGenres {
    override fun bySourceType(): Map<String, String> = AcceptedLimitations.houseGenres
}
