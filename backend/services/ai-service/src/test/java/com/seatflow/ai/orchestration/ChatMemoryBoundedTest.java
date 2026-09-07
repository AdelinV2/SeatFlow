package com.seatflow.ai.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chat-memory bound tests (TASK-P15-004 section 4; mandatory 16).
 */
class ChatMemoryBoundedTest {

    @Test
    @DisplayName("16: AI_CHAT_MAX_MESSAGES=24 keeps memory bounded while preserving whole turns")
    void memoryBoundedAt24() {
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(24)
                .build();
        String conversationId = "conv-bounded-test";
        for (int turn = 0; turn < 20; turn++) {
            memory.add(conversationId, List.of(new UserMessage("user turn " + turn)));
            memory.add(conversationId, List.of(
                    new org.springframework.ai.chat.messages.AssistantMessage("assistant turn " + turn)));
        }
        var stored = memory.get(conversationId);
        assertThat(stored.size()).isLessThanOrEqualTo(24);
        // 24 is even: whole user/assistant pairs are preserved, never a dangling half-turn at the head.
        assertThat(stored.size() % 2).isZero();
    }

    @Test
    @DisplayName("REV-006: TTL bounds are exact (1m..24h, no truncation)")
    void ttlBoundsExact() {
        assertThat(new AssistantConversationProperties(24,
                java.time.Duration.ofMinutes(1), 500).ttl())
                .isEqualTo(java.time.Duration.ofMinutes(1));
        assertThat(new AssistantConversationProperties(24,
                java.time.Duration.ofHours(24), 500).ttl())
                .isEqualTo(java.time.Duration.ofHours(24));
        assertThatThrownBy(() -> new AssistantConversationProperties(24,
                java.time.Duration.ofHours(24).plusMinutes(30), 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantConversationProperties(24,
                java.time.Duration.ofSeconds(30), 500))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
