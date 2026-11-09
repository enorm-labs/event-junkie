package de.norm.events

/**
 * The kind of client behind a `User-Agent`, for the access line (#4783): `browser`, `none`, or the agent's own name
 * without its version, so `/traffic` can tell a crawler that runs the SPA from a visitor.
 *
 * A crawler that sends a browser agent reads `browser`. The name is cut to a short token, which keeps the column's
 * values few on a line that fires per request.
 */
object ClientKind {
    const val BROWSER = "browser"
    const val NONE = "none"
    private const val MAX_LENGTH = 40

    // The crawler words of `NOT_A_PERSON` in scripts/o2-query.sh, plus the two headless tools that send a browser agent.
    private val NAMED_AGENT = Regex("""[A-Za-z0-9._-]*(?:bot|spider|crawl|slurp|headless|lighthouse)[A-Za-z0-9._-]*""", RegexOption.IGNORE_CASE)
    private val FIRST_TOKEN = Regex("""[A-Za-z0-9._-]+""")

    fun of(userAgent: String?): String {
        if (userAgent.isNullOrBlank()) return NONE
        val named = NAMED_AGENT.find(userAgent)?.value
        val kind =
            when {
                named != null -> named
                userAgent.startsWith("Mozilla/") -> BROWSER
                else -> FIRST_TOKEN.find(userAgent)?.value ?: NONE
            }
        return kind.take(MAX_LENGTH)
    }
}
