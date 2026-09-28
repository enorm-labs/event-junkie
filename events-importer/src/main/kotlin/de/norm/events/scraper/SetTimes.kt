package de.norm.events.scraper

import de.norm.events.slug.SlugGenerator

/**
 * Each billed act with the start and end of its running-order slot (#2002), for a venue whose
 * listing bills the lineup and whose event page times it. A slot is matched by slug and floor,
 * then by slug alone, so an act on two floors keeps both sets and a floor renamed between the
 * pages still matches. A slot that names no billed act goes to [onUnmatched] and is dropped: the
 * lineup stays the listing's.
 */
fun List<ScrapedArtist>.withSetTimesFrom(
    runningOrder: List<ScrapedArtist>,
    onUnmatched: (ScrapedArtist) -> Unit = {}
): List<ScrapedArtist> {
    if (runningOrder.isEmpty()) return this
    val byFloor = runningOrder.groupBy { SlugGenerator.slugify(it.name) to it.stage }
    val byName = runningOrder.groupBy { SlugGenerator.slugify(it.name) }
    val billed = map { SlugGenerator.slugify(it.name) }.toSet()
    runningOrder.filter { SlugGenerator.slugify(it.name) !in billed }.forEach(onUnmatched)
    return map { act ->
        val slug = SlugGenerator.slugify(act.name)
        val slot = byFloor[slug to act.stage]?.first() ?: byName[slug]?.first()
        slot?.let { act.copy(setStart = it.setStart, setEnd = it.setEnd) } ?: act
    }
}
