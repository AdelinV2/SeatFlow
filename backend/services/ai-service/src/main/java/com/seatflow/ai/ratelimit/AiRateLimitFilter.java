package com.seatflow.ai.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.ai.service.AiMetrics;
import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.observability.context.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Local per-user abuse guard for AI endpoints only (TASK-P15-007 section 11).
 *
 * <p>Guards exactly:
 * <ul>
 *   <li>{@code POST /api/ai/chat} (endpoint label {@code chat});</li>
 *   <li>{@code POST /api/ai/proposals/{proposalId}/confirm} (endpoint label {@code confirm}).</li>
 * </ul>
 * Every other path — AI status/reset reads, all normal SeatFlow booking APIs — passes through
 * untouched. The filter runs after Spring Security (default servlet-filter ordering), so
 * unauthenticated callers are still rejected with {@code 401} by the security chain and blank
 * subjects fail open to the controllers (which enforce authentication independently).
 *
 * <p>On exhaustion the filter answers {@code 429} with the shared {@code ApiErrorResponse} envelope
 * and stable {@code AI_RATE_LIMITED} code that the Angular assistant maps to safe disabled/retry
 * UX. The response carries no quota internals, no secrets, and no stack traces. Confirmation
 * retries within budget always pass, preserving idempotent retry semantics; only abusive
 * duplicate-spam rates are shed.
 *
 * <p>The filter is registered by {@link AiRateLimitWebConfig} (not by component scan) so MVC
 * slice tests stay focused on controllers; production and full-context tests wire the real
 * limiter.
 */
@Slf4j
public class AiRateLimitFilter extends OncePerRequestFilter {

    static final String RATE_LIMITED_CODE = "AI_RATE_LIMITED";
    static final String RATE_LIMITED_MESSAGE =
            "AI request limit reached. Please try again shortly. Core booking remains available.";

    private final AiRateLimiter limiter;
    private final AiMetrics metrics;
    private final ObjectMapper objectMapper;

    public AiRateLimitFilter(AiRateLimiter limiter, AiMetrics metrics, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if ("/api/ai/chat".equals(path)) {
            return false;
        }
        return !isConfirmPath(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean confirm = isConfirmPath(path);
        String endpoint = confirm ? "confirm" : "chat";
        String subject = currentSubject();
        boolean allowed = confirm
                ? limiter.tryAcquireConfirm(subject)
                : limiter.tryAcquireChat(subject);
        if (!allowed) {
            metrics.recordRateLimited(endpoint);
            String correlationId = CorrelationContext.getCorrelationId().orElse("N/A");
            log.warn("AI_PROVIDER_RATE_LIMITED scope=local endpoint={} correlationId={}",
                    endpoint, correlationId);
            ApiErrorResponse error = ApiErrorResponse.of(429, "Too Many Requests", RATE_LIMITED_CODE,
                    RATE_LIMITED_MESSAGE, request.getRequestURI(), correlationId);
            byte[] body = objectMapper.writeValueAsBytes(error);
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isConfirmPath(String path) {
        if (path == null || !path.startsWith("/api/ai/proposals/")) {
            return false;
        }
        return path.endsWith("/confirm") && path.length() > "/api/ai/proposals//confirm".length();
    }

    private static String currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String subject = jwt.getToken().getSubject();
            return subject == null ? "" : subject;
        }
        if (authentication != null && authentication.isAuthenticated()) {
            String name = authentication.getName();
            return name == null ? "" : name;
        }
        return "";
    }
}
