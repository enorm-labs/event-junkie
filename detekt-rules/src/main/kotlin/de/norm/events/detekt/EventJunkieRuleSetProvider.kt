package de.norm.events.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/**
 * Registers this repository's own detekt rules, configured under the `event-junkie` key in
 * `detekt.yml`.
 *
 * Found at runtime through `META-INF/services/dev.detekt.api.RuleSetProvider`, so a new rule needs
 * an entry here and nothing else.
 */
class EventJunkieRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("event-junkie")

    override fun instance() = RuleSet(ruleSetId, listOf(::LongComment))
}
