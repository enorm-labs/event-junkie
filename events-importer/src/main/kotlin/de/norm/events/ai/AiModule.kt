package de.norm.events.ai

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the one Anthropic client every AI-assisted step calls through (ADR-041 rule 1, ADR-050).
 *
 * It holds no prompt and no result type. Each caller keeps its own properties, prompt and validator, and builds an
 * [AnthropicClient] inside its own bean.
 */
@ApplicationModule(allowedDependencies = [])
class AiModule
