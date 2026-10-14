package de.norm.events.dataquality.classification

import mockwebserver3.MockResponse

/** Whole Messages API responses for a `MockWebServer`. The SDK rejects a trimmed body. */
object AnthropicResponses {
    private const val HTTP_OK = 200

    fun textResponse(text: String): MockResponse = messageResponse(""""type":"text","text":${quote(text)}""", "end_turn")

    fun messageResponse(
        contentBlock: String,
        stopReason: String
    ): MockResponse =
        MockResponse
            .Builder()
            .code(HTTP_OK)
            .setHeader("content-type", "application/json")
            .body(
                """{"id":"msg_test","type":"message","role":"assistant","model":"claude-haiku-4-5",""" +
                    """"content":[{$contentBlock}],"stop_reason":"$stopReason","stop_sequence":null,""" +
                    """"usage":{"input_tokens":10,"output_tokens":20}}"""
            ).build()

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
