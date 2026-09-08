package com.seatflow.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Groq tool-calling compatibility contract: gpt-oss reasoning must stay
 * {@code hidden} so follow-up turns never echo {@code reasoning_content} back to Groq's
 * OpenAI-compatible endpoint (rejected with 400, surfacing as a generic provider outage
 * after tools already ran). Removing this property re-breaks every tool-using chat turn.
 */
@SpringBootTest(properties = {
        "seatflow.ai.enabled=false",
        "GROQ_API_KEY=",
        "GROQ_MODEL=openai/gpt-oss-20b"
})
@ActiveProfiles("test")
@MockitoBean(types = JwtDecoder.class)
class AiGroqCompatibilityTest {

    private final OpenAiChatProperties chatProperties;

    @Autowired
    AiGroqCompatibilityTest(OpenAiChatProperties chatProperties) {
        this.chatProperties = chatProperties;
    }

    @Test
    @DisplayName("Groq reasoning stays hidden so tool follow-up turns are accepted")
    void reasoningFormatIsHidden() {
        assertThat(chatProperties.getExtraBody())
                .containsEntry("reasoning_format", "hidden");
    }

    @Test
    @DisplayName("Groq reasoning effort stays low to respect free-tier rate limits")
    void reasoningEffortIsLow() {
        assertThat(chatProperties.getExtraBody())
                .containsEntry("reasoning_effort", "low");
    }
}
