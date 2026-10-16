package de.norm.events.ai

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.content.Media
import java.time.Duration

/** What one caller sets on its model. Each caller reads these from its own properties (ADR-041 rule 2). */
data class AnthropicSettings(
    val apiKey: String,
    val model: String,
    val timeout: Duration,
    val maxRetries: Int,
    val maxTokens: Int
)

/**
 * The model's answer to one call.
 *
 * [text] is null on a refusal or an empty answer. [refused] tells the two apart, so the caller can say which
 * request the model declined.
 */
data class AnthropicReply(
    val text: String?,
    val refused: Boolean,
    val promptTokens: Long,
    val completionTokens: Long
)

/**
 * One Spring AI Anthropic model with one caller's settings, and the one call body every caller shares.
 *
 * The temperature is 0, because two runs over one text should give one answer. A caller builds its own client
 * inside its own bean, so a missing key or a disabled feature builds nothing.
 */
class AnthropicClient(
    settings: AnthropicSettings,
    /** The importer's own registry, so token use and latency land beside every other meter (ADR-015). */
    meterRegistry: MeterRegistry,
    private val ioDispatcher: CoroutineDispatcher,
    baseUrl: String = DEFAULT_BASE_URL
) {
    private val chatOptions =
        AnthropicChatOptions
            .builder()
            .baseUrl(baseUrl)
            .apiKey(settings.apiKey)
            .timeout(settings.timeout)
            .maxRetries(settings.maxRetries)
            .model(settings.model)
            .maxTokens(settings.maxTokens)
            .temperature(0.0)
            .build()

    private val chatModel =
        AnthropicChatModel
            .builder()
            .options(chatOptions)
            .meterRegistry(meterRegistry)
            .build()

    /**
     * On an IO thread, because Spring AI's `call` blocks and the importer runs on a Netty event loop (ADR-001).
     *
     * A safety classifier declines with a finish reason rather than an error, so the text is read only after
     * that check. A failed call throws, and the caller decides what a failure means.
     */
    suspend fun call(
        system: String,
        user: String,
        media: List<Media> = emptyList()
    ): AnthropicReply =
        withContext(ioDispatcher) {
            val userMessage =
                UserMessage
                    .builder()
                    .text(user)
                    .media(media)
                    .build()
            val response = chatModel.call(Prompt(listOf(SystemMessage(system), userMessage), chatOptions))
            val refused =
                response.result
                    ?.metadata
                    ?.finishReason
                    .equals(REFUSAL, ignoreCase = true)
            val text =
                if (refused) {
                    null
                } else {
                    response.result
                        ?.output
                        ?.text
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
            val (prompt, completion) = tokensOf(response)
            AnthropicReply(text, refused, prompt, completion)
        }

    private fun tokensOf(response: ChatResponse): Pair<Long, Long> {
        // Typed nullable, because the Java getters promise a value that a mocked response can lack.
        val usage: Usage? = response.metadata.usage
        return (usage?.promptTokens?.toLong() ?: 0) to (usage?.completionTokens?.toLong() ?: 0)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"

        /** What Anthropic reports when a safety classifier declines the request. */
        const val REFUSAL = "refusal"
    }
}
