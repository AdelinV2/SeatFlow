package com.seatflow.ai.context;

import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.common.observability.context.CorrelationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Builds the server-side {@link AiRequestContext} for AI tool execution.
 *
 * <p>Reads the validated caller JWT from the Spring Security context (populated by the OAuth2
 * resource-server filter) and the correlation ID from {@link CorrelationContext}. Never mints an
 * identity: when no authenticated principal exists, {@link #requireAuthenticated()} fails with
 * {@code UNAUTHENTICATED} instead of silently switching to an internal privileged identity.
 */
@Slf4j
@Component
public class AiRequestContextFactory {

    /**
     * Captures whatever caller identity exists (possibly anonymous). Prefer
     * {@link #requireAuthenticated()} for customer tools.
     */
    public AiRequestContext current() {
        String bearerToken = null;
        String userId = null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            bearerToken = jwtAuthentication.getToken().getTokenValue();
            userId = jwtAuthentication.getToken().getSubject();
        }
        String correlationId = CorrelationContext.getCorrelationId()
                .orElseGet(() -> UUID.randomUUID().toString());
        return new AiRequestContext(bearerToken, correlationId, userId);
    }

    /**
     * Returns the caller context or fails when no authenticated identity exists, per the Phase 15
     * auth policy (customer AI tools require an authenticated user).
     */
    public AiRequestContext requireAuthenticated() {
        AiRequestContext context = current();
        if (!context.isAuthenticated()) {
            log.warn("AI tool call rejected: no authenticated identity, correlationId={}",
                    context.correlationId());
            throw new AiToolException(AiToolError.UNAUTHENTICATED,
                    "Authentication is required to use the AI assistant tools.");
        }
        return context;
    }
}
