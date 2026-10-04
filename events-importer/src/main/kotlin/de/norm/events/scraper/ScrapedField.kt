package de.norm.events.scraper

/**
 * A field an importer's detail page can own: a good run replaces the listing's value with the page's.
 * [ScrapedEvent.detailPageOwns] lists them, so a failed page keeps the stored value instead (#2505).
 */
enum class ScrapedField {
    TITLE,
    SUBTITLE,
    DESCRIPTION,
    IMAGE,
    GENRE,
    EVENT_TYPE,
    START_TIME,

    /** Presale, box office and the note, as one: the note explains the price it sits beside. */
    PRICES,

    /** The event date with the end date and time, as one run: one without the others is a different run. */
    RUN_DATES,

    /** The lineup: a failed page keeps the stored acts where the listing's roster is coarser (#2542). */
    ARTISTS
}
