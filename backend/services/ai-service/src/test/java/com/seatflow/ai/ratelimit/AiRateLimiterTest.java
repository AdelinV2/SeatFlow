package com.seatflow.ai.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-process AI rate limiter tests (TASK-P15-007 section 11).
 */
class AiRateLimiterTest {

    private TestClock clock;
    private AiRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new TestClock(Instant.parse("2026-09-07T10:00:00Z"));
        limiter = new AiRateLimiter(new AiRateLimitProperties(2, 1, 100), clock);
    }

    @Test
    @DisplayName("chat budget allows the limit then sheds within the same window")
    void chatBudgetEnforcedPerWindow() {
        assertThat(limiter.tryAcquireChat("user-1")).isTrue();
        assertThat(limiter.tryAcquireChat("user-1")).isTrue();
        assertThat(limiter.tryAcquireChat("user-1")).isFalse();

        clock.advanceSeconds(61);
        assertThat(limiter.tryAcquireChat("user-1")).isTrue();
    }

    @Test
    @DisplayName("confirmation budget is independent from chat and preserves one retry within budget")
    void confirmBudgetIndependent() {
        assertThat(limiter.tryAcquireConfirm("user-1")).isTrue();
        assertThat(limiter.tryAcquireConfirm("user-1")).isFalse();

        // Chat budget is untouched by confirmation attempts.
        assertThat(limiter.tryAcquireChat("user-1")).isTrue();
        assertThat(limiter.tryAcquireChat("user-1")).isTrue();
    }

    @Test
    @DisplayName("budgets are isolated per user")
    void perUserIsolation() {
        assertThat(limiter.tryAcquireChat("alice")).isTrue();
        assertThat(limiter.tryAcquireChat("alice")).isTrue();
        assertThat(limiter.tryAcquireChat("alice")).isFalse();

        assertThat(limiter.tryAcquireChat("bob")).isTrue();
    }

    @Test
    @DisplayName("blank subjects fail open to security (never 429 from missing identity)")
    void blankSubjectsFailOpen() {
        assertThat(limiter.tryAcquireChat(null)).isTrue();
        assertThat(limiter.tryAcquireChat("  ")).isTrue();
        assertThat(limiter.tryAcquireConfirm(null)).isTrue();
    }

    @Test
    @DisplayName("tracked users stay bounded under many distinct subjects")
    void trackedUsersBounded() {
        for (int i = 0; i < 500; i++) {
            limiter.tryAcquireChat("user-" + i);
        }
        assertThat(limiter.trackedUsers()).isLessThanOrEqualTo(200);

        clock.advanceSeconds(61);
        limiter.tryAcquireChat("fresh-user");
        assertThat(limiter.trackedUsers()).isLessThanOrEqualTo(200);
    }

    @Test
    @DisplayName("concurrent acquires never exceed the window budget unsafely")
    void concurrentAcquiresBounded() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (limiter.tryAcquireChat("shared-user")) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(allowed.get()).isEqualTo(2);
    }

    static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
