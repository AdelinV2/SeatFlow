package com.seatflow.ai.orchestration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Bounded conversation memory properties for TASK-P15-004 section 4.
 *
 * <p>Defaults: {@code AI_CHAT_MAX_MESSAGES=24}, {@code AI_CONVERSATION_TTL=30m} since last activity,
 * {@code AI_MAX_ACTIVE_CONVERSATIONS=500} for the single-instance portfolio deployment. All three
 * limits are configurable from environment/application properties with validation and safe upper
 * bounds.
 */
@Validated
@ConfigurationProperties(prefix = "seatflow.ai.conversation")
public record AssistantConversationProperties(

        /**
         * Maximum chat-memory messages retained per conversation (whole-turn preserving per Spring AI
         * {@code MessageWindowChatMemory} behavior). Safe upper bound 200.
         */
        @Min(2) @Max(200) int maxMessages,

        /**
         * Idle TTL since last activity. Conversations idle longer are removed lazily and/or by bounded
         * scheduled cleanup. Minimum 1 minute, maximum 24 hours (compared exactly, no truncation).
         */
        Duration ttl,

        /**
         * Maximum active conversations for the single-instance deployment. When reached, the store
         * evicts deterministically (expired first, then oldest activity) and never leaks another
         * user's conversation. Safe upper bound 5000.
         */
        @Min(1) @Max(5000) int maxActiveConversations
) {
    public AssistantConversationProperties {
        if (ttl == null) {
            throw new IllegalArgumentException("Conversation TTL is required");
        }
        if (ttl.compareTo(java.time.Duration.ofMinutes(1)) < 0
                || ttl.compareTo(java.time.Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException(
                    "Conversation TTL must be between 1 minute and 24 hours inclusive");
        }
    }
}
