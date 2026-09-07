package com.seatflow.ai.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bounded in-memory chat memory for Phase 15 (TASK-P15-004 section 4).
 *
 * <p>Uses Spring AI {@code 2.0.1} {@link MessageWindowChatMemory} over
 * {@link InMemoryChatMemoryRepository} with {@code AI_CHAT_MAX_MESSAGES} (default 24). The plain
 * in-memory repository does not itself enforce owner/TTL semantics; those live in
 * {@link ConversationStore}. Chat memory is context convenience, not durable history; a process
 * restart clears memory and the client receives reset/expired behavior. No JPA/JDBC/AI chat
 * database is introduced.
 */
@Configuration
@RequiredArgsConstructor
public class ChatMemoryConfig {

    private final AssistantConversationProperties properties;

    @Bean
    public InMemoryChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(InMemoryChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.maxMessages())
                .build();
    }
}
