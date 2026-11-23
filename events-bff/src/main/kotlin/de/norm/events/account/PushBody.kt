package de.norm.events.account

/** The longest push body the three push texts send; ADR-055 rule 3 allows one line. */
private const val MAX_BODY = 120

/** [text] within [MAX_BODY] characters, cut after a whole title and closed with an ellipsis. */
internal fun shortenPushBody(text: String): String = if (text.length <= MAX_BODY) text else text.take(MAX_BODY - 1).trimEnd(' ', ',') + "…"
