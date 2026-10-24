package de.norm.events.scraper

import de.norm.events.event.EventFeature

/** Whether the scraper marked this event [EventFeature.FOR_CHILDREN] (#3796). */
val ScrapedEvent.isForChildren: Boolean get() = features.any { it.feature == EventFeature.FOR_CHILDREN }

/** The phrase the scraper marked this event [EventFeature.FOR_CHILDREN] with, or null. */
val ScrapedEvent.forChildrenPhrase: String? get() = features.firstOrNull { it.feature == EventFeature.FOR_CHILDREN }?.phrase
