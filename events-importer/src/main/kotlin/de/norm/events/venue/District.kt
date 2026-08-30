package de.norm.events.venue

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * One of Berlin's twelve boroughs, which is the only thing `venue.district` may hold.
 *
 * **The field was a free-form `String?` and that is how two wrong values got in** (#329). Two venues
 * on the RAW-Gelände said `friedrichshain`, which is an *Ortsteil* and not a borough, while nine
 * venues at the same postal code said `friedrichshain-kreuzberg`. A filter on the borough dropped
 * those two and reported success. Nothing was wrong enough to fail, which is why nobody saw it.
 *
 * **A borough, never an Ortsteil.** Friedrichshain, Kreuzberg, Prenzlauer Berg, Treptow and Köpenick
 * are the names people use and none of them is a value here. The map and the radius search both work
 * on boroughs, so a mix of the two silently splits one area into two.
 *
 * **The wire form is the kebab-case name**, because that is what the database already holds and what
 * the frontend already sends. `V009` adds the matching CHECK constraint, so a hand-edited row cannot
 * introduce a thirteenth borough either.
 *
 * **Here rather than in `events-core` beside `SourceLicence`, and that is not an oversight.** The
 * kebab-case wire form needs Jackson's `@JsonValue` to survive a round trip, and `events-core`
 * carries no Jackson. Only the importer writes a venue, so only the importer needs to parse one. The
 * BFF still filters on the raw column, which is why `V009` carries the constraint as well.
 */
enum class District(
    @get:JsonValue val value: String
) {
    CHARLOTTENBURG_WILMERSDORF("charlottenburg-wilmersdorf"),
    FRIEDRICHSHAIN_KREUZBERG("friedrichshain-kreuzberg"),
    LICHTENBERG("lichtenberg"),
    MARZAHN_HELLERSDORF("marzahn-hellersdorf"),
    MITTE("mitte"),
    NEUKOELLN("neukoelln"),
    PANKOW("pankow"),
    REINICKENDORF("reinickendorf"),
    SPANDAU("spandau"),
    STEGLITZ_ZEHLENDORF("steglitz-zehlendorf"),
    TEMPELHOF_SCHOENEBERG("tempelhof-schoeneberg"),
    TREPTOW_KOEPENICK("treptow-koepenick");

    companion object {
        /**
         * Parses the wire form, rejecting anything else.
         *
         * **Unlike `SourceLicence.parseOrProhibited` there is no lenient fallback, because there
         * is no safe one.** A licence has a conservative answer to fall back
         * to. A borough does not: guessing puts a venue in the wrong place on the map, or removes it
         * from a search, and both are worse than refusing the write.
         */
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String): District =
            entries.find { it.value.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown district '$value'. Expected one of: ${entries.joinToString(", ") { it.value }}"
                )
    }
}
