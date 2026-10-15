package de.norm.events.instagram

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the Instagram drafts (ADR-048): the Business Discovery client, the model that
 * drafts events from a caption, and the store of drafts a person approves.
 *
 * It writes no event. It reads venues through its own SQL, so it needs no other module.
 */
@ApplicationModule(allowedDependencies = ["common"])
class InstagramModule
