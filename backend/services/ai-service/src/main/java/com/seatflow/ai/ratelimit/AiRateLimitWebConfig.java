package com.seatflow.ai.ratelimit;

import com.seatflow.ai.service.AiMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Servlet registration for the AI abuse guard (TASK-P15-007 section 11).
 *
 * <p>The filter is a {@code @Bean} (not a scanned component) so {@code @WebMvcTest} controller
 * slices do not pick it up, while production and full-context tests wire the real per-user
 * limiter. The limiter parameter is required: a missing limiter must fail startup loudly
 * (fail-closed) rather than silently leave AI endpoints unguarded. Ordering after Spring Security
 * is the Boot default for plain filter beans, so the security chain still rejects
 * unauthenticated callers with {@code 401} first.
 */
@Configuration
public class AiRateLimitWebConfig {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public AiRateLimitFilter aiRateLimitFilter(AiRateLimiter limiter, AiMetrics metrics,
                                               ObjectMapper objectMapper) {
        return new AiRateLimitFilter(limiter, metrics, objectMapper);
    }
}
