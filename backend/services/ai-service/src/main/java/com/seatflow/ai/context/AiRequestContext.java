package com.seatflow.ai.context;

import java.util.Objects;

/**
 * Server-side request context for AI tool execution (TASK-P15-002 section 7).
 *
 * <p>Exposes only what downstream clients require: the caller's bearer token, the correlation ID,
 * and the authenticated subject. The token must never be serialized into tool output, stored in
 * conversation history, sent to Groq, or logged. {@link #toString()} is overridden to guarantee
 * the token value can never leak through routine logging.
 *
 * @param bearerToken raw caller JWT, server-side only
 * @param correlationId end-to-end correlation ID propagated to every downstream call
 * @param userId authenticated subject ({@code null} only when no identity exists; customer tools
 *     must then fail instead of escalating to a privileged identity)
 */
public record AiRequestContext(String bearerToken, String correlationId, String userId) {

    public AiRequestContext {
        Objects.requireNonNull(correlationId, "correlationId is required");
    }

    public boolean isAuthenticated() {
        return bearerToken != null && !bearerToken.isBlank() && userId != null && !userId.isBlank();
    }

    @Override
    public String toString() {
        return "AiRequestContext{bearerToken=[MASKED_JWT], correlationId='" + correlationId
                + "', userId='" + userId + "'}";
    }
}
