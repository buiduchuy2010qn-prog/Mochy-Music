package com.mochyy.mochymusic.api

import org.junit.Assert.assertEquals
import org.junit.Test

class AiTranslationApiTest {

    @Test
    fun resolveChatCompletionsUrl_defaultsPerProviderWhenBlank() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            OpenRouterService.resolveChatCompletionsUrl("", "Gemini"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            OpenRouterService.resolveChatCompletionsUrl("   ", "gemini"),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenRouterService.resolveChatCompletionsUrl("", "OpenAI"),
        )
        assertEquals(
            "https://api.perplexity.ai/chat/completions",
            OpenRouterService.resolveChatCompletionsUrl("", "Perplexity"),
        )
        assertEquals(
            "https://api.x.ai/v1/chat/completions",
            OpenRouterService.resolveChatCompletionsUrl("", "xAI"),
        )
        assertEquals(
            "https://api.mistral.ai/v1/chat/completions",
            OpenRouterService.resolveChatCompletionsUrl("", "Mistral"),
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            OpenRouterService.resolveChatCompletionsUrl("", "OpenRouter"),
        )
    }

    @Test
    fun resolveChatCompletionsUrl_normalizesCustomEndpoints() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            OpenRouterService.resolveChatCompletionsUrl("https://openrouter.ai/api/v1", "OpenRouter"),
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            OpenRouterService.resolveChatCompletionsUrl("https://openrouter.ai/api/v1/", "OpenRouter"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            OpenRouterService.resolveChatCompletionsUrl(
                "  https://generativelanguage.googleapis.com/v1beta/openai/chat/completions/  ",
                "Gemini",
            ),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            OpenRouterService.resolveChatCompletionsUrl(
                "https://generativelanguage.googleapis.com/v1beta/openai",
                "Gemini",
            ),
        )
    }

    @Test
    fun resolveDefaultModel_defaultsGeminiAndPreservesCustomModel() {
        assertEquals(
            "gemini-3.5-flash-lite",
            OpenRouterService.resolveDefaultModel("", "Gemini"),
        )
        assertEquals(
            "gemini-3.5-flash-lite",
            OpenRouterService.resolveDefaultModel("   ", "gemini"),
        )
        assertEquals(
            "",
            OpenRouterService.resolveDefaultModel("", "OpenRouter"),
        )
        assertEquals(
            "gemini-2.5-pro",
            OpenRouterService.resolveDefaultModel("  gemini-2.5-pro  ", "Gemini"),
        )
    }

    @Test
    fun resolveDeepLEndpoint_handlesFreeAndProKeysWithWhitespace() {
        assertEquals(
            "https://api-free.deepl.com/v2/translate",
            DeepLService.resolveDeepLEndpoint("abc123:fx"),
        )
        assertEquals(
            "https://api-free.deepl.com/v2/translate",
            DeepLService.resolveDeepLEndpoint("  abc123:fx  "),
        )
        assertEquals(
            "https://api.deepl.com/v2/translate",
            DeepLService.resolveDeepLEndpoint("abc123-pro-key"),
        )
    }
}
