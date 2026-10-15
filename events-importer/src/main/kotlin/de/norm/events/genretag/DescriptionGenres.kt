package de.norm.events.genretag

/**
 * The canonical genres an event's [description] names, in order of first mention, at most [limit] (ADR-047).
 *
 * Only the [GENRE_SYNONYMS] vocabulary counts, one word or two adjacent words at a time, so prose never mints a tag.
 * A sentence loses everything from a support or guest marker on, so "support for a metal band" tags nothing.
 * A key that is also an everyday word ([AMBIGUOUS_IN_PROSE]) is skipped, and so is a quoted title: "Whiskey Tango Foxtrot" is an album, not Tango.
 */
fun descriptionGenres(
    description: String?,
    limit: Int = MAX_DESCRIPTION_GENRES
): List<String> {
    if (description.isNullOrBlank()) return emptyList()
    return preNormalize(QUOTED_TITLE.replace(description, " "))
        .split(SENTENCE_BREAK)
        .map { sentence -> SUPPORT_CLAUSE.find(sentence)?.let { sentence.take(it.range.first) } ?: sentence }
        .flatMap { sentence -> sentenceGenres(sentence) }
        .distinct()
        .take(limit)
}

/** Two adjacent words first, so "hip hop" is one genre and "punk rock" is not also Rock. */
private fun sentenceGenres(sentence: String): List<String> {
    val words = sentence.split(WORD_BREAK).map(::lookupKey).filter { it.isNotEmpty() }
    val genres = mutableListOf<String>()
    var i = 0
    while (i < words.size) {
        val pair = words.getOrNull(i + 1)?.let { vocabularyGenre(words[i] + it) }
        if (pair != null) {
            genres += pair
            i += 2
        } else {
            vocabularyGenre(words[i])?.let { genres += it }
            i++
        }
    }
    return genres
}

private fun vocabularyGenre(key: String): String? = if (key in AMBIGUOUS_IN_PROSE) null else GENRE_SYNONYMS[key]

/** How many genres one description may give an event. */
const val MAX_DESCRIPTION_GENRES = 3

private val SENTENCE_BREAK = Regex("""[.!?;\n]+""")

/** Splits on whitespace and on punctuation that is not part of a key; `&`, `-` and `'` stay, so `R&B`, `Hip-Hop` and `Drum'n'Bass` stay one word. */
private val WORD_BREAK = Regex("""[\s,:()\[\]"„“”/|]+""")

/** A short quoted span, mostly an album, song or tour title; the closing mark may differ from the opening one ("…”). */
private val QUOTED_TITLE = Regex("""["„“‘«»][^"„“”‘’«»\n]{1,80}["“”’«»]""")

/** A support act or a guest; their genre is not the night's. */
private val SUPPORT_CLAUSE =
    Regex("""\b(?:support|supported|vorband|special\s+guests?|guests?|gäste|plus)\b|\+""", RegexOption.IGNORE_CASE)

/** Vocabulary keys that prose uses in another sense ("around the world", "in full swing", "the 90s"). */
private val AMBIGUOUS_IN_PROSE =
    setOf(
        "house",
        "world",
        "global",
        "indian",
        "wave",
        "swing",
        "mod",
        "hc",
        "synth",
        "alternative",
        "urban",
        "trap",
        "afro",
        "latin",
        "goth",
        "gothic",
        "emo",
        "80s",
        "90s",
        "2000s",
        "karaoke",
        "oldschool",
        "newschool"
    )
