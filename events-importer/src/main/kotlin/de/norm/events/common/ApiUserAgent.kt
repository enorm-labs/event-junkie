package de.norm.events.common

/**
 * The `User-Agent` an open API is asked with: product, version and a contact URL. MusicBrainz and
 * Wikimedia both ask for that form, and MusicBrainz throttles a request without a contact.
 */
object ApiUserAgent {
    /** `event-junkie/<version> ( https://github.com/enorm-labs/event-junkie )`, `dev` when no build stamped one. */
    fun of(version: String?): String = "event-junkie/${version ?: "dev"} ( https://github.com/enorm-labs/event-junkie )"
}
