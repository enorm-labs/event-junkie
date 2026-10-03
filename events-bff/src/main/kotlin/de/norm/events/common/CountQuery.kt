package de.norm.events.common

/**
 * `COUNT(*)` over [from] filtered by [where]. With a [cap], the count stops after `cap + 1` rows, so a
 * caller can say "more than [cap]" without counting the rest: the header search shows "100+" and
 * never needs the exact figure, and the count was its costliest query (#2533).
 */
fun countQuery(
    from: String,
    where: String,
    cap: Int? = null
): String = if (cap == null) "SELECT COUNT(*) FROM $from $where" else "SELECT COUNT(*) FROM (SELECT 1 FROM $from $where LIMIT ${cap + 1}) capped"
