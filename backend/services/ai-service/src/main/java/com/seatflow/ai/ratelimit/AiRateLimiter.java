package com.seatflow.ai.ratelimit;

import com.seatflow.common.observability.context.CorrelationContext;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small bounded in-process per-user AI limiter (TASK-P15-007 section 11).
 *
 * <p>Fixed-window counters per authenticated subject, one window for chat turns and one for
 * confirmation attempts. Memory is bounded by {@code maxTrackedUsers}: expired windows are purged
 * opportunistically and, when still over capacity, a single arbitrary entry is evicted so the maps
 * can never grow without bound. No Redis, no new infrastructure product — suitable for the current
 * single-instance portfolio deployment.
 *
 * <p>Blank/unknown subjects are allowed through (fail-open): authentication is still enforced
 * independently by Spring Security and the controllers, so the limiter can never turn an auth
 * failure into a 429 or block anonymous traffic that security must reject with 401.
 */
@Slf4j
@Service
public class AiRateLimiter {

    private final AiRateLimitProperties properties;
    private final Clock clock;

    private final ConcurrentHashMap<String, Window> chatWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> confirmWindows = new ConcurrentHashMap<>();

    public AiRateLimiter(AiRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Attempts to consume one chat-turn budget for the user. */
    public boolean tryAcquireChat(String userSubject) {
        return tryAcquire(chatWindows, userSubject, properties.chatRequestsPerMinute());
    }

    /** Attempts to consume one confirmation-attempt budget for the user. */
    public boolean tryAcquireConfirm(String userSubject) {
        return tryAcquire(confirmWindows, userSubject, properties.confirmAttemptsPerMinute());
    }

    private boolean tryAcquire(ConcurrentHashMap<String, Window> windows, String userSubject, int limit) {
        if (userSubject == null || userSubject.isBlank()) {
            return true;
        }
        String key = userSubject.trim();
        long window = clock.instant().getEpochSecond() / 60L;
        Window result = windows.compute(key, (ignored, existing) -> {
            if (existing == null || existing.window() != window) {
                return new Window(window, 1);
            }
            return new Window(window, existing.count() + 1);
        });
        if (result.count() > limit) {
            log.warn("AI local rate limit exceeded: windowCount={}, limit={}, correlationId={}",
                    result.count(), limit, CorrelationContext.getCorrelationId().orElse("N/A"));
            return false;
        }
        enforceBound(windows);
        return true;
    }

    private void enforceBound(ConcurrentHashMap<String, Window> windows) {
        int max = properties.maxTrackedUsers();
        if (windows.size() <= max) {
            return;
        }
        long currentWindow = clock.instant().getEpochSecond() / 60L;
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Window> entry = iterator.next();
            if (entry.getValue().window() != currentWindow) {
                iterator.remove();
            }
        }
        if (windows.size() > max) {
            Iterator<String> keys = windows.keySet().iterator();
            if (keys.hasNext()) {
                keys.next();
                keys.remove();
            }
        }
    }

    int trackedUsers() {
        return chatWindows.size() + confirmWindows.size();
    }

    private record Window(long window, long count) {
    }
}
