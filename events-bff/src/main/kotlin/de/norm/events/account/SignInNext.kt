package de.norm.events.account

/**
 * The page a sign-in returns to (#3889): a page of this site, so a link in the mail can never send anyone elsewhere. It may
 * carry a closed query (#4969): the action the visitor pressed, as `do`, and the events list's filters and dates for a search
 * to save. The frontend's `signInNext.ts` is the same pattern.
 */
internal object SignInNext {
    val ACTIONS = listOf("follow", "rsvp-going", "rsvp-interested", "save")

    /** The keys a saved search writes, and the list's own dates. */
    val FILTERS =
        listOf(
            "q",
            "eventType",
            "venue",
            "district",
            "venueType",
            "genre",
            "family",
            "minPrice",
            "maxPrice",
            "excludeSoldOut",
            "free",
            "timeOfDay",
            "language",
            "feature",
            "festival",
            "when",
            "from",
            "to"
        )

    // A value as the browser's URLSearchParams encodes it. The cap leaves room for a search SavedSearchQuery saves (1000).
    private val PARAM = "(do=(${ACTIONS.joinToString("|")})|(${FILTERS.joinToString("|")})=[A-Za-z0-9._~%*+-]+)"
    private val PATTERN = Regex("^/(de|en)/[a-z0-9/-]{0,200}(\\?(?=.{1,1100}$)$PARAM(&$PARAM)*)?$")

    fun accepts(next: String) = PATTERN.matches(next)
}
