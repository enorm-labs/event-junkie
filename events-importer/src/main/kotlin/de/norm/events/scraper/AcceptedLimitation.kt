package de.norm.events.scraper

/**
 * Something a venue does not publish, declared where its parser lives, so `/data-quality-audit` can
 * look it up rather than read it out of 213 files of prose — see #715.
 *
 * **The record carries the fact; the KDoc keeps the reasoning** (#714). Which selector reads a
 * field, and the counterexample that made the rule necessary, stay next to the code they govern.
 *
 * **A declaration says the source is silent, not that the column is always null.** Where the parser
 * derives a value anyway, the [reason] says so — the derived value is why the column is populated,
 * and the silence is why it is sometimes wrong.
 */
data class AcceptedLimitation(
    val aspect: LimitedAspect,
    /**
     * One sentence, lowercase, no trailing period: "the site publishes only one time per night" and
     * never "we do not parse the door time". Something the parser *could* do is a defect, not a
     * limitation, and belongs in an issue.
     */
    val reason: String,
    /** The issue tracking a possible repair, when one exists. An accepted limitation usually has none. */
    val issue: Int? = null
)

/**
 * One venue package's declaration: which sources it serves, and what those sources withhold.
 *
 * **[sources] is why this is not a bare list.** An empty [limitations] means the venue publishes
 * everything the model stores; an undeclared venue means the audit has no idea either way. Naming
 * the sources separates the two, and `AcceptedLimitationsTest` makes forgetting one a build failure.
 */
data class VenueLimitations(
    val sources: Set<EventSource>,
    val limitations: List<AcceptedLimitation> = emptyList()
) {
    /** One package, one source. A package serving several uses the primary constructor and names them all. */
    constructor(source: EventSource, vararg limitations: AcceptedLimitation) : this(setOf(source), limitations.toList())
}

/**
 * What a limitation is about.
 *
 * Naming a [TrackedField] is what lets the audit join a declaration onto the coverage series for the
 * same venue: a source declaring [PRICE] should read low or zero on `priceAny`, and one that climbs
 * has gained either a field or a parsing bug. The aspects below without one have no series to join
 * — `soldOut` is a boolean with a meaningful `false`, so there is no absence to measure.
 */
enum class LimitedAspect(
    val trackedField: TrackedField? = null
) {
    SUBTITLE(TrackedField.SUBTITLE),
    DESCRIPTION(TrackedField.DESCRIPTION),
    EVENT_TYPE(TrackedField.EVENT_TYPE),
    DOORS_TIME(TrackedField.DOORS_TIME),
    START_TIME(TrackedField.START_TIME),
    IMAGE(TrackedField.IMAGE_URL),
    TICKET_URL(TrackedField.TICKET_URL),
    GENRE(TrackedField.GENRE),
    PRICE(TrackedField.PRICE_ANY),
    PRICE_PRESALE(TrackedField.PRICE_PRESALE),
    PRICE_BOX_OFFICE(TrackedField.PRICE_BOX_OFFICE),
    PRICE_NOTE(TrackedField.PRICE_NOTE),
    ARTISTS(TrackedField.ARTISTS),
    PROMOTERS(TrackedField.PROMOTERS),

    SOLD_OUT,
    CANCELLATION,

    /** Only part of a paginated programme is read — a partial import, not an absent field. */
    PAGINATION,

    /** Every event points at the listing, because the source publishes no page per event. */
    PER_EVENT_PAGE,

    /**
     * The source names no calendar date, so the stored dates are derived rather than announced.
     * `eventDate` is non-null by construction and reads 100% coverage forever, which is exactly why
     * a venue whose dates are generated is invisible unless it says so here.
     */
    EVENT_DATE
}
