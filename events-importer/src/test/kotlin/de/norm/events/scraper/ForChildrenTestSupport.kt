package de.norm.events.scraper

import de.norm.events.event.PartyFeature

/** Whether the scraper marked this event [PartyFeature.FOR_CHILDREN] (#3796). */
val ScrapedEvent.isForChildren: Boolean get() = features.any { it.feature == PartyFeature.FOR_CHILDREN }

/** The phrase the scraper marked this event [PartyFeature.FOR_CHILDREN] with, or null. */
val ScrapedEvent.forChildrenPhrase: String? get() = features.firstOrNull { it.feature == PartyFeature.FOR_CHILDREN }?.phrase
