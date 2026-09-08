package com.seatflow.ai.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.ai.service.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI rate-limit filter tests (TASK-P15-007 section 11): only AI endpoints are guarded, the 429
 * body is stable and secret-free, and normal APIs pass through untouched.
 */
class AiRateLimitFilterTest {

    private SimpleMeterRegistry registry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        AiRateLimiter limiter = new AiRateLimiter(
                new AiRateLimitProperties(2, 1, 100),
                Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC));
        AiRateLimitFilter filter = new AiRateLimitFilter(limiter, new AiMetrics(registry),
                new ObjectMapper().findAndRegisterModules());
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController()).addFilters(filter).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-1", null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_USER"))));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("chat passes within budget then answers 429 with a stable secret-free body")
    void chatRateLimitedWithStableBody() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("AI_RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.path").value("/api/ai/chat"))
                .andExpect(jsonPath("$.correlationId").value("N/A"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.groqApiKey").doesNotExist())
                .andExpect(jsonPath("$.authorization").doesNotExist());

        assertThat(registry.get(AiMetrics.RATE_LIMITED).tag("endpoint", "chat").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("confirmation endpoint has its own budget and records endpoint=confirm")
    void confirmRateLimitedSeparately() throws Exception {
        UUID proposal = UUID.randomUUID();
        mockMvc.perform(post("/api/ai/proposals/" + proposal + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/ai/proposals/" + proposal + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("AI_RATE_LIMITED"));

        assertThat(registry.get(AiMetrics.RATE_LIMITED).tag("endpoint", "confirm").counter().count())
                .isEqualTo(1.0);
        // Chat budget is independent from confirmation attempts.
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("non-AI paths, GET requests, and unauthenticated calls pass through")
    void nonAiTrafficUnaffected() throws Exception {
        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ai/status"))
                .andExpect(status().isOk());

        SecurityContextHolder.clearContext();
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("confirm path matching requires a proposal id segment")
    void confirmPathRequiresId() {
        assertThat(AiRateLimitFilter.isConfirmPath("/api/ai/proposals/" + UUID.randomUUID() + "/confirm"))
                .isTrue();
        assertThat(AiRateLimitFilter.isConfirmPath("/api/ai/proposals//confirm")).isFalse();
        assertThat(AiRateLimitFilter.isConfirmPath("/api/ai/proposals")).isFalse();
        assertThat(AiRateLimitFilter.isConfirmPath("/api/ai/chat")).isFalse();
        assertThat(AiRateLimitFilter.isConfirmPath("/api/reservations")).isFalse();
        assertThat(AiRateLimitFilter.isConfirmPath(null)).isFalse();
    }

    @RestController
    static class StubController {
        @PostMapping("/api/ai/chat")
        Map<String, String> chat() {
            return Map.of("ok", "true");
        }

        @PostMapping("/api/ai/proposals/{proposalId}/confirm")
        Map<String, String> confirm(@PathVariable String proposalId) {
            return Map.of("ok", proposalId);
        }

        @PostMapping("/api/reservations")
        Map<String, String> reservations() {
            return Map.of("ok", "true");
        }

        @org.springframework.web.bind.annotation.GetMapping("/api/ai/status")
        Map<String, String> status() {
            return Map.of("ok", "true");
        }
    }
}
