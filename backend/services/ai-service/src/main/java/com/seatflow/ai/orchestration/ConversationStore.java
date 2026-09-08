package com.seatflow.ai.orchestration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Application-owned bounded conversation metadata, ownership, drafts, and per-conversation
 * single-flight locks (TASK-P15-004 sections 4 and 12).
 *
 * <p>The plain Spring AI {@code InMemoryChatMemoryRepository} does not itself enforce
 * owner/TTL semantics, so this store owns:
 * {@code conversationId, ownerSubject, createdAt, lastActivityAt, state}, plus the single current
 * unconfirmed {@link ProposalDraft} per conversation.
 *
 * <p>Rules:
 * <ul>
 *   <li>server generates UUID conversation IDs; owner is the authenticated subject;</li>
 *   <li>expired conversations (idle longer than TTL) are removed lazily and/or by bounded scheduled
 *       cleanup, always together with their bounded chat history (this store owns memory
 *       reclamation, so sweeps and capacity evictions cannot orphan messages);</li>
 *   <li>when max active conversations is reached, the store first removes expired entries, then
 *       evicts the single oldest by last activity (deterministic LRU) to make room; it never leaks
 *       another user's conversation;</li>
 *   <li>chat memory is context convenience, not durable history; process restart clears memory and
 *       the client receives reset/expired behavior;</li>
 *   <li>concurrent turns for the same conversation are serialized with a per-conversation lock and
 *       bounded wait; a busy conversation answers {@code 409} rather than running parallel model
 *       calls;</li>
 *   <li>no lock key or owner metadata is ever sent to Groq;</li>
 *   <li>no JPA/JDBC/AI chat database is introduced.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationStore {

    private final AssistantConversationProperties properties;
    private final Clock clock;
    private final ChatMemory chatMemory;

    private final ConcurrentHashMap<UUID, ConversationRecord> conversations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public record ConversationRecord(
            UUID conversationId,
            String ownerSubject,
            Instant createdAt,
            Instant lastActivityAt,
            AssistantState state,
            ProposalDraft draft) {
    }

    /** Creates a new owner-bound conversation, evicting deterministically when at capacity. */
    public ConversationRecord create(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("Owner subject is required");
        }
        ensureCapacity();
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        ConversationRecord record =
                new ConversationRecord(id, ownerSubject, now, now, AssistantState.IDLE, null);
        conversations.put(id, record);
        log.info("AI conversation created: conversationId={}, ownerPresent={}, activeCount={}",
                id, true, conversations.size());
        return record;
    }

    /**
     * Returns the conversation only when it exists, is unexpired, and belongs to the owner.
     * Unknown IDs and other owners' IDs both yield {@code null} so callers can answer {@code 404}
     * (anti-enumeration: never confirm another user's conversation exists).
     */
    public ConversationRecord getForOwner(UUID conversationId, String ownerSubject) {
        if (conversationId == null || ownerSubject == null || ownerSubject.isBlank()) {
            return null;
        }
        ConversationRecord record = conversations.get(conversationId);
        if (record == null) {
            return null;
        }
        if (isExpired(record, clock.instant())) {
            remove(conversationId);
            return null;
        }
        if (!record.ownerSubject().equals(ownerSubject)) {
            log.warn("AI conversation cross-owner access denied: conversationId={}", conversationId);
            return null;
        }
        return record;
    }

    public void touch(UUID conversationId) {
        ConversationRecord record = conversations.get(conversationId);
        if (record != null) {
            conversations.computeIfPresent(conversationId, (id, existing) -> new ConversationRecord(
                    existing.conversationId(), existing.ownerSubject(), existing.createdAt(),
                    clock.instant(), existing.state(), existing.draft()));
        }
    }

    public void updateState(UUID conversationId, AssistantState state) {
        conversations.computeIfPresent(conversationId, (id, existing) -> new ConversationRecord(
                existing.conversationId(), existing.ownerSubject(), existing.createdAt(),
                existing.lastActivityAt(), state, existing.draft()));
    }

    public void touchWithState(UUID conversationId, AssistantState state) {
        conversations.computeIfPresent(conversationId, (id, existing) -> new ConversationRecord(
                existing.conversationId(), existing.ownerSubject(), existing.createdAt(),
                clock.instant(), state, existing.draft()));
    }

    /** Replaces the single current unconfirmed draft (supersede semantics). */
    public void putDraft(UUID conversationId, ProposalDraft draft) {
        conversations.computeIfPresent(conversationId, (id, existing) -> new ConversationRecord(
                existing.conversationId(), existing.ownerSubject(), existing.createdAt(),
                clock.instant(), existing.state(), draft));
    }

    public void clearDraft(UUID conversationId) {
        conversations.computeIfPresent(conversationId, (id, existing) -> new ConversationRecord(
                existing.conversationId(), existing.ownerSubject(), existing.createdAt(),
                existing.lastActivityAt(), existing.state(), null));
    }

    /** Owner-safe reset: removes metadata (memory cleared separately by the caller). */
    public boolean removeOwned(UUID conversationId, String ownerSubject) {
        ConversationRecord record = conversations.get(conversationId);
        if (record == null) {
            return false;
        }
        if (!record.ownerSubject().equals(ownerSubject)) {
            log.warn("AI conversation cross-owner reset denied: conversationId={}", conversationId);
            return false;
        }
        remove(conversationId);
        return true;
    }

    public void remove(UUID conversationId) {
        conversations.remove(conversationId);
        locks.remove(conversationId);
        // Single ownership for memory reclamation: every metadata removal path (lazy expiry,
        // scheduled sweep, capacity LRU eviction, explicit reset) also drops the bounded chat
        // history, so InMemoryChatMemoryRepository cannot accumulate orphaned messages.
        try {
            chatMemory.clear(conversationId.toString());
        } catch (Exception ex) {
            log.warn("AI chat memory clear failed, continuing with metadata removal: conversationId={}",
                    conversationId);
        }
    }

    /** Peek without owner check, for expiry handling only (never returns data to callers). */
    public ConversationRecord peek(UUID conversationId) {
        if (conversationId == null) {
            return null;
        }
        return conversations.get(conversationId);
    }

    public boolean isExpired(ConversationRecord record, Instant now) {
        return isExpiredAt(record, now);
    }

    public boolean isExpiredAt(ConversationRecord record, Instant now) {
        return !record.lastActivityAt().plus(properties.ttl()).isAfter(now);
    }

    public int size() {
        return conversations.size();
    }

    /**
     * Bounded, thread-safe expiry sweep. Removes at most {@code maxToRemove} expired entries per
     * call so scheduled cleanup cannot monopolize the store.
     */
    public int cleanupExpiredBounded(int maxToRemove) {
        Instant now = clock.instant();
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, ConversationRecord> entry : conversations.entrySet()) {
            if (expired.size() >= maxToRemove) {
                break;
            }
            if (isExpiredAt(entry.getValue(), now)) {
                expired.add(entry.getKey());
            }
        }
        expired.forEach(this::remove);
        if (!expired.isEmpty()) {
            log.info("AI conversation expiry sweep removed {} conversations, remaining={}",
                    expired.size(), conversations.size());
        }
        return expired.size();
    }

    public int cleanupExpired() {
        return cleanupExpiredBounded(100);
    }

    private void ensureCapacity() {
        if (conversations.size() < properties.maxActiveConversations()) {
            return;
        }
        cleanupExpiredBounded(100);
        if (conversations.size() < properties.maxActiveConversations()) {
            return;
        }
        conversations.entrySet().stream()
                .min(Comparator.comparing(entry -> entry.getValue().lastActivityAt()))
                .map(Map.Entry::getKey)
                .ifPresent(oldest -> {
                    log.warn("AI conversation store at capacity ({}); evicting oldest conversationId={}",
                            properties.maxActiveConversations(), oldest);
                    remove(oldest);
                });
    }

    /**
     * Acquires the per-conversation single-flight lock with a bounded wait.
     *
     * @return {@code true} when the lock was acquired and must be released with
     *     {@link #unlock(UUID)}; {@code false} when the conversation is busy and the caller must
     *     answer {@code 409/429} instead of running a parallel model call.
     */
    public boolean tryLock(UUID conversationId, long timeoutMs) throws InterruptedException {
        ReentrantLock lock = locks.computeIfAbsent(conversationId, id -> new ReentrantLock());
        return lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public void unlock(UUID conversationId) {
        ReentrantLock lock = locks.get(conversationId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
