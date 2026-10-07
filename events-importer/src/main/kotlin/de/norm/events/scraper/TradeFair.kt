package de.norm.events.scraper

/**
 * Whether a venue bills a trade fair or a conference: a `…messe` (`Karrieremesse`), a `Konferenz` / `Conference`, a
 * `Kongress`, a `B2B` forum. EVENT_SCOPE.md §3.3 keeps these out (#2833).
 *
 * A bare `Messe` is not one: SO36 bills a band of that name. A venue calls this only where its page mixes such a row
 * into its programme.
 */
fun billsTradeFairOrConference(
    title: String,
    subtitle: String?
): Boolean = TRADE_FAIR_OR_CONFERENCE.containsMatchIn("$title ${subtitle.orEmpty()}")

private val TRADE_FAIR_OR_CONFERENCE =
    Regex("""\b\w+messe\b|\bkonferenz\b|\bconference\b|\bkongress\b|\bcongress\b|\bb2b\b""", RegexOption.IGNORE_CASE)
