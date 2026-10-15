package de.norm.events.common

import java.text.Normalizer
import java.util.Locale

/** Checks a passage a model says it quoted against the text it was given. A model's quote is never trusted as is. */
object QuotedText {
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val APOSTROPHES = Regex("[‘’‛ʼ´`]")
    private val QUOTES = Regex("[“”„‟«»]")
    private val DASHES = Regex("[‐-―]")
    private val WHITESPACE = Regex("\\s+")

    /** Case, accents, quote and dash shapes, and line breaks: what a faithful quote can still change. */
    fun normalise(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .replace(APOSTROPHES, "'")
            .replace(QUOTES, "\"")
            .replace(DASHES, "-")
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase(Locale.ROOT)

    fun occursIn(
        quote: String,
        text: String
    ): Boolean = normalise(quote) in normalise(text)
}
