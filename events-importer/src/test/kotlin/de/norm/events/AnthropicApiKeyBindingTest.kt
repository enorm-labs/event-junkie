package de.norm.events

import de.norm.events.dataquality.classification.ClassificationProperties
import de.norm.events.translation.TranslationProperties
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource

/** Both Anthropic callers read the one `app.anthropic.api-key`, which `APP_ANTHROPIC_API_KEY` fills (#3247). */
class AnthropicApiKeyBindingTest {
    @Test
    fun `the one variable reaches translation and the classification check`() {
        val binder = binder(mapOf("APP_ANTHROPIC_API_KEY" to "test-key"))

        binder.bind("app.translation", TranslationProperties::class.java).get().apiKey shouldBe "test-key"
        binder.bind("app.data-quality.classification", ClassificationProperties::class.java).get().apiKey shouldBe "test-key"
    }

    @Test
    fun `without the variable both callers read an empty key`() {
        val binder = binder(emptyMap())

        binder.bind("app.translation", TranslationProperties::class.java).get().apiKey shouldBe ""
        binder.bind("app.data-quality.classification", ClassificationProperties::class.java).get().apiKey shouldBe ""
    }

    private fun binder(variables: Map<String, Any>): Binder {
        val environment = StandardEnvironment()
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        environment.propertySources.addFirst(MapPropertySource("variables", variables))
        val yaml = FileSystemResource("src/main/resources/application.yaml")
        environment.propertySources.addLast(YamlPropertySourceLoader().load("application", yaml).first())
        return Binder.get(environment)
    }
}
