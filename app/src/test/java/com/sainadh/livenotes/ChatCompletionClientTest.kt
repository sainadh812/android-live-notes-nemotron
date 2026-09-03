package com.sainadh.livenotes

import com.sainadh.livenotes.ai.ChatCompletionClient
import com.sainadh.livenotes.ai.LlmProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionClientTest {
    @Test
    fun parseStructuredContent_handlesJsonFence() {
        val client = ChatCompletionClient()
        val parsed = client.parseStructuredContent(
            """
            ```json
            {
              "summary": "Discussed release scope",
              "runningContext": "Release is targeted for Friday",
              "actionItems": ["Alice finalize QA", "Bob deploy the API"]
            }
            ```
            """.trimIndent()
        )

        assertEquals("Discussed release scope", parsed.summary)
        assertEquals("Release is targeted for Friday", parsed.runningContext)
        assertEquals(listOf("Alice finalize QA", "Bob deploy the API"), parsed.actionItems)
    }

    @Test
    fun openAiProvider_isAvailable() {
        assertEquals("https://api.openai.com/v1/chat/completions", LlmProvider.OPENAI.baseUrl)
        assertEquals("gpt-5.4", LlmProvider.OPENAI.defaultModel)
        assertTrue(LlmProvider.OPENAI.displayName.contains("OpenAI"))
    }
}
