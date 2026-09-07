package com.seatflow.ai.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Bounded metadata/ownership/cleanup tests (TASK-P15-004 sections 4, 12; mandatory 4, 17).
 */
@ExtendWith(MockitoExtension.class)
class ConversationStoreTest {

    @Mock
    private ChatMemory chatMemory;

    private ConversationStore store(Duration ttl, int max, Clock clock) {
        return new ConversationStore(
                new AssistantConversationProperties(24, ttl, max), clock, chatMemory);
    }

    @Test
    @DisplayName("4: expired conversation is removed with its chat memory and reported as expired")
    void expiredConversationRemoved() {
        MutableClock clock = new MutableClock(Instant.now());
        ConversationStore shortTtl = store(Duration.ofMinutes(1), 500, clock);
        var record = shortTtl.create("owner-1");
        clock.advance(Duration.ofMinutes(2));
        assertThat(shortTtl.isExpiredAt(record, clock.instant())).isTrue();
        assertThat(shortTtl.cleanupExpired()).isGreaterThanOrEqualTo(1);
        assertThat(shortTtl.peek(record.conversationId())).isNull();
        verify(chatMemory).clear(record.conversationId().toString());
    }

    @Test
    @DisplayName("REV-001: capacity eviction also clears chat memory (no orphans)")
    void capacityEvictionClearsMemory() throws InterruptedException {
        ConversationStore tiny = store(Duration.ofMinutes(30), 2, Clock.systemUTC());
        var first = tiny.create("owner-a");
        Thread.sleep(5);
        tiny.create("owner-b");
        Thread.sleep(5);
        tiny.create("owner-c");
        assertThat(tiny.size()).isEqualTo(2);
        assertThat(tiny.peek(first.conversationId())).isNull();
        verify(chatMemory).clear(first.conversationId().toString());
    }

    @Test
    @DisplayName("max active conversations evicts deterministically without leaking")
    void maxCapacityEvictsOldest() throws InterruptedException {
        ConversationStore tiny = store(Duration.ofMinutes(30), 2, Clock.systemUTC());
        var first = tiny.create("owner-a");
        Thread.sleep(5);
        tiny.create("owner-b");
        Thread.sleep(5);
        // Third creation evicts the oldest (first) to stay bounded.
        var third = tiny.create("owner-c");
        assertThat(tiny.size()).isEqualTo(2);
        assertThat(tiny.peek(first.conversationId())).isNull();
        assertThat(tiny.getForOwner(third.conversationId(), "owner-c")).isNotNull();
        // Evicted owner's data is gone, never returned to another owner.
        assertThat(tiny.getForOwner(first.conversationId(), "owner-c")).isNull();
    }

    @Test
    @DisplayName("17: TTL/max cleanup is thread-safe and bounded")
    void cleanupThreadSafeAndBounded() throws Exception {
        ConversationStore concurrent = store(Duration.ofMinutes(30), 500, Clock.systemUTC());
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String owner = "owner-" + i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        concurrent.create(owner);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(concurrent.size()).isEqualTo(threads * perThread);
        assertThat(concurrent.cleanupExpiredBounded(10)).isZero();
        assertThat(concurrent.cleanupExpiredBounded(1000)).isZero();
    }

    @Test
    @DisplayName("cross-owner access never returns context (404 semantics)")
    void crossOwnerIsolated() {
        ConversationStore store = store(Duration.ofMinutes(30), 500, Clock.systemUTC());
        var record = store.create("owner-1");
        assertThat(store.getForOwner(record.conversationId(), "owner-2")).isNull();
        assertThat(store.removeOwned(record.conversationId(), "owner-2")).isFalse();
        assertThat(store.peek(record.conversationId())).isNotNull();
    }

    @Test
    @DisplayName("per-conversation lock serializes turns")
    void singleFlightLock() throws Exception {
        ConversationStore store = store(Duration.ofMinutes(30), 500, Clock.systemUTC());
        UUID id = store.create("owner-1").conversationId();
        assertThat(store.tryLock(id, 100)).isTrue();
        // Same thread re-entry succeeds (ReentrantLock); another thread must fail fast.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Boolean> other = pool.submit(() -> store.tryLock(id, 200));
        assertThat(other.get(5, TimeUnit.SECONDS)).isFalse();
        store.unlock(id);
        pool.shutdown();
    }
}
