package com.seatflow.ai.orchestration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Version-controlled system prompt owned by {@code ai-service} (TASK-P15-004 section 5).
 *
 * <p>Exactly one prompt template is used; inline strings scattered through controllers are
 * forbidden. Prompt instructions are defense-in-depth only; application code is the security
 * boundary.
 */
@Component
public class AssistantPromptFactory {

    public static final String PROMPT_VERSION = "p15-004-v1";

    private final String systemPrompt;

    public AssistantPromptFactory(
            @Value("classpath:ai/system-prompt-p15-004-v1.txt") Resource promptResource) throws IOException {
        try (var in = promptResource.getInputStream()) {
            this.systemPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        if (this.systemPrompt.isBlank()) {
            throw new IllegalStateException("Assistant system prompt must not be blank");
        }
    }

    /** Returns the single versioned system prompt text. */
    public String systemPrompt() {
        return systemPrompt;
    }

    public String version() {
        return PROMPT_VERSION;
    }
}
