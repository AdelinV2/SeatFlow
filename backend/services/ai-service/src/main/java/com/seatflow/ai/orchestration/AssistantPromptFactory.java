package com.seatflow.ai.orchestration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Version-controlled system prompt owned by {@code ai-service} (TASK-P15-004 section 5).
 *
 * <p>Exactly one prompt template is used; inline strings scattered through controllers are
 * forbidden. Prompt instructions are defense-in-depth only; application code is the security
 * boundary.
 *
 * <p>The served prompt appends the current UTC date so the model resolves relative dates
 * ("this weekend", "Sep 17") against reality instead of guessing.
 */
@Component
public class AssistantPromptFactory {

    public static final String PROMPT_VERSION = "p15-007-v1";

    private final String systemPrompt;
    private final Clock clock;

    public AssistantPromptFactory(
            @Value("classpath:ai/system-prompt-p15-007-v1.txt") Resource promptResource,
            Clock clock) throws IOException {
        try (var in = promptResource.getInputStream()) {
            this.systemPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        if (this.systemPrompt.isBlank()) {
            throw new IllegalStateException("Assistant system prompt must not be blank");
        }
        this.clock = clock;
    }

    /** Returns the single versioned system prompt text plus the current UTC date. */
    public String systemPrompt() {
        return systemPrompt + "\n\nCurrent UTC date: " + LocalDate.now(clock)
                + " (use it to resolve relative dates like \"this weekend\" or \"Sep 17\").";
    }

    public String version() {
        return PROMPT_VERSION;
    }
}
