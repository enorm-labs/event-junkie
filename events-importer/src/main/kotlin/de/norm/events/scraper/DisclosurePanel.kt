package de.norm.events.scraper

import org.jsoup.nodes.Element

/**
 * The region a labelled disclosure button opens: the element whose `id` the first button matching
 * [buttonSelector] and labelled [label] names in its `aria-controls`.
 *
 * A page that collapses several regions tells them apart by their buttons' labels alone, because the
 * regions themselves carry no class of their own. Only the button's own text counts, so a nested
 * icon or badge never joins the label.
 *
 * @return the region, or `null` when no button carries [label], or its `aria-controls` is blank or
 *   names no element.
 */
fun Element.disclosurePanel(
    buttonSelector: String,
    label: String
): Element? =
    select(buttonSelector)
        .firstOrNull { it.ownText().trim().equals(label, ignoreCase = true) }
        ?.attr("aria-controls")
        ?.takeIf { it.isNotBlank() }
        ?.let { getElementById(it) }
