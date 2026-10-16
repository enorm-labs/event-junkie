package de.norm.events.translation

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata declaring the translation module's allowed dependencies.
 *
 * Holds the engine contract and its implementations, and nothing about when a translation is
 * allowed — that question belongs to the licence and the scraper (ADR-026). It reads `event` only
 * for [de.norm.events.event.DescriptionLanguage], the pair of languages both sides name.
 * `ai` supplies the Anthropic client (ADR-050).
 */
@ApplicationModule(allowedDependencies = ["event", "ai"])
class TranslationModule
