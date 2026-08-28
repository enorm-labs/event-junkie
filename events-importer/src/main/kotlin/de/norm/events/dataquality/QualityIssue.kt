package de.norm.events.dataquality

import de.norm.events.EVENTS_SCHEMA

/**
 * The metrics this pillar measures, and the single place each one's definition lives.
 *
 * **The `predicate` is interpolated into SQL, and that is safe for exactly one reason:** this is a
 * closed enum, so the only strings that can reach a statement are the ones written here. A caller
 * names an issue by its enum value and nothing a caller controls is ever concatenated. Widening this
 * to accept a free-form predicate would turn the worklist endpoint into an injection point, which is
 * the change to refuse in review.
 *
 * Keeping the predicate next to the metric is also what stops the report and the worklist drifting:
 * a report that counts 72 offenders and a worklist that returns 68 of them is worse than either
 * alone, because it makes both untrustworthy and neither obviously wrong. They are the same string.
 *
 * The `dimension` is from `DATA_QUALITY_STRATEGY.md` §2.1, so a reader can group by what kind of
 * problem each one is rather than by which query produced it.
 */
enum class QualityIssue(
    val dimension: Dimension,
    /** A boolean SQL expression over `event e`. See the class KDoc for why interpolating it is safe. */
    val predicate: String
) {
    /**
     * The ~40% gap, and the headline number for the whole pillar. A concert with no artist is an
     * event nobody can search for by the thing they actually want.
     */
    CONCERTS_WITHOUT_ARTIST(
        Dimension.COMPLETENESS,
        "e.event_type = 'CONCERT' AND NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_artist ea WHERE ea.event_id = e.id)"
    ),

    /** `OTHER` is what the classifier falls back to, so a rising count is a classifier losing ground. */
    EVENTS_TYPED_OTHER(Dimension.VALIDITY, "e.event_type = 'OTHER'"),

    MISSING_GENRE(Dimension.COMPLETENESS, "e.genre IS NULL OR e.genre = ''"),

    MISSING_PROMOTER(
        Dimension.COMPLETENESS,
        "NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_promoter ep WHERE ep.event_id = e.id)"
    ),

    /**
     * **Open decision A, resolved as the plan recommends:** a `free` event is not missing a price,
     * and neither is one carrying a free-form `price_note`. Both are the venue having published
     * something rather than nothing, and counting them as missing would make the metric permanently
     * red for venues that simply price differently — which is how a dashboard stops being read.
     */
    MISSING_PRICE(
        Dimension.COMPLETENESS,
        "e.price_presale IS NULL AND e.price_box_office IS NULL AND e.free = false AND e.price_note IS NULL"
    ),

    MISSING_START_TIME(Dimension.COMPLETENESS, "e.start_time IS NULL"),

    /**
     * Events whose source has never had its copyright position reviewed (#283).
     *
     * **The odd one out here, and deliberately so.** Every other metric measures data the venue
     * published and we mishandled. This measures work of ours that has not happened. It sits in this
     * pillar because the alternative was a second reporting mechanism for one number, and because
     * #790 showed what an evidence gap costs when nothing counts it: three of eighty importers
     * recorded a `robots.txt` check and nobody noticed the other seventy-seven.
     *
     * **Counted in events rather than in sources**, which is what makes it comparable with its
     * neighbours and is also the more honest number. A source with 200 unreviewed events is not the
     * same finding as one with 2, and `docs/SCRAPING_POSITION.md` §3.1 is about material on the site
     * rather than about rows in a config table.
     *
     * Events with no source at all are excluded by the `EXISTS`. Those are hand-created through the
     * admin API and report under the `manual` bucket, and our own data needs no licence review.
     */
    UNREVIEWED_LICENCE(
        Dimension.COMPLETENESS,
        "EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_source es " +
            "WHERE es.id = e.event_source_id AND es.licence_reviewed_at IS NULL)"
    );

    /** The JSON name, and the `?issue=` value: `concertsWithoutArtist`. */
    val key: String =
        name.lowercase().split("_").reduceIndexed { i, acc, part ->
            if (i == 0) acc else acc + part.replaceFirstChar { it.uppercase() }
        }

    /** What kind of problem this is (`DATA_QUALITY_STRATEGY.md` §2.1). */
    enum class Dimension { COMPLETENESS, VALIDITY, ACCURACY }

    companion object {
        private val BY_KEY = entries.associateBy { it.key }

        /** Resolves a `?issue=` value, or `null` — the controller turns that into a 400 naming the valid set. */
        fun byKey(key: String): QualityIssue? = BY_KEY[key]

        /** Every valid `?issue=` value, for the error message and for Swagger. */
        val KEYS: List<String> = entries.map { it.key }
    }
}
