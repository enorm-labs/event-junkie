package de.norm.events.scraper

import de.norm.events.genretag.isGenreLabel
import de.norm.events.genretag.normalizeGenre

/**
 * The genres the vocabulary knows in a free-text style tail, as one `genre` string, or `null`.
 *
 * For a venue whose style is prose rather than a field: Arcanoa runs genre words together
 * (`EthnoBluesJazzAfroLatinFolkSession`) beside acts and notes, ART Stalker writes a tagline
 * (`Blues Rock`, `Indie Pop/Rock - Ein Abend für alle …`). Each separated part is looked up whole
 * first, so `PostPunk` stays Post-Punk; only a part the vocabulary does not know is split where a
 * lower-case letter meets a capital. Unknown words are dropped, so no junk tag is seeded (#2397).
 */
fun knownGenresInStyleTail(tail: String?): String? =
    tail
        .orEmpty()
        .split(STYLE_TAIL_SEPARATOR)
        .flatMap { part -> knownGenres(part).ifEmpty { part.split(CASE_CHANGE).flatMap(::knownGenres) } }
        .distinct()
        .joinToString(", ")
        .ifEmpty { null }

private fun knownGenres(text: String): List<String> = normalizeGenre(text).filter(::isGenreLabel)

/** The separators a style tail puts between styles, acts and notes: `/`, `+`, `|`, `, `, `&`, a dash. */
private val STYLE_TAIL_SEPARATOR = Regex("""\s*(?:[/+|,&]|\s-\s|-)\s*""")

/** Where a run-together tail starts its next word: a lower-case letter followed by a capital. */
private val CASE_CHANGE = Regex("""(?<=\p{Ll})(?=\p{Lu})""")
