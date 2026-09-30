package de.norm.events.scraper

// Shared presenter vocabulary: the "X presents" credit after a promoter's name and the
// "präsentiert von X" intro before it, so every scraper reads the same spellings.

/**
 * The spelled-out verbs that follow a presenter's name, as a regex alternation: `presents`,
 * `präsentiert`. A title frame that must be unambiguous reads only these.
 */
const val PRESENTS_VERBS = """presents|pr(?:ä|ae)sentiert"""

/** [PRESENTS_VERBS] plus the abbreviations `pres.` and `prs.`, for a credit or a frame that a colon or position already marks. */
const val PRESENTS_WORDS = """$PRESENTS_VERBS|pres\.|prs\."""

/** The phrases that introduce a presenter, as a regex alternation: `präsentiert von`, `presented by`, `eine Veranstaltung von`. */
const val PRESENTED_BY_WORDS = """pr(?:ä|ae)sentiert\s+von|presented\s+by|eine\s+veranstaltung\s+von"""

/**
 * The promoter a credit names, without its presenter verb or intro: `Konzertbüro Schoneberg
 * presents` and `FKP Scorpio präsentiert:` become the company, and so does `präsentiert von: Loft
 * Concerts`. A company whose own name ends in `Presents` ([PRESENTS_COMPANIES]) is kept whole, so
 * `AEG Presents` is never stored as `AEG`. `null` when nothing is left.
 */
fun promoterFromCredit(credit: String?): String? {
    val text = credit?.trim().orEmpty()
    val company = PRESENTS_COMPANIES.find(text)?.value
    val name = company ?: text.replaceFirst(PRESENTED_BY_INTRO, "").replaceFirst(PRESENTS_CREDIT_TAIL, "").trim()
    return name.takeIf { it.isNotBlank() }
}

/** Companies whose own name ends in `Presents`, matched at the start of a credit. */
private val PRESENTS_COMPANIES = Regex("""^(?:AEG|Pansy)\s+Presents(?!\p{L})""", RegexOption.IGNORE_CASE)

/** A leading [PRESENTED_BY_WORDS] intro and its optional colon. */
private val PRESENTED_BY_INTRO = Regex("""^(?:$PRESENTED_BY_WORDS)\s*:?\s*""", RegexOption.IGNORE_CASE)

/** A trailing [PRESENTS_WORDS] verb and its optional colon; the plural `present` is safe here, at the end of a credit. */
private val PRESENTS_CREDIT_TAIL = Regex("""\s*(?<!\p{L})(?:$PRESENTS_WORDS|present)\s*:?\s*$""", RegexOption.IGNORE_CASE)
