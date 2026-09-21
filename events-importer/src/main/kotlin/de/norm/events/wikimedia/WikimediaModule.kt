package de.norm.events.wikimedia

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the Wikidata and Wikimedia Commons reads of ADR-031, step C: a Wikidata
 * item's `P18` picture and that file's licence and credit as Commons states them.
 *
 * It knows nothing about artist rows or MusicBrainz. `scraper` hands it a Wikidata id and gets a
 * [CommonsImage] or nothing. It paces its own requests, as `musicbrainz` does, and for the same
 * reason.
 */
@ApplicationModule(allowedDependencies = ["common"])
class WikimediaModule
