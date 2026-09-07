package com.seatflow.ai.context;

import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.common.observability.context.CorrelationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiRequestContextFactoryTest {

    private final AiRequestContextFactory factory = new AiRequestContextFactory();

    @AfterEach
    void clearState() {
        SecurityContextHolder.clearContext();
        CorrelationContext.clear();
    }

    @Test
    @DisplayName("authenticated callers receive bearer, correlation, and subject")
    void authenticatedContext() {
        Jwt jwt = Jwt.withTokenValue("secret-token-value")
                .header("alg", "none")
                .subject("user-123")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        CorrelationContext.setCorrelationId("corr-abc");

        AiRequestContext context = factory.requireAuthenticated();

        assertThat(context.bearerToken()).isEqualTo("secret-token-value");
        assertThat(context.correlationId()).isEqualTo("corr-abc");
        assertThat(context.userId()).isEqualTo("user-123");
        assertThat(context.isAuthenticated()).isTrue();
        // The raw token must never be printable through routine logging.
        assertThat(context.toString()).doesNotContain("secret-token-value");
    }

    @Test
    @DisplayName("missing identity fails instead of escalating to a privileged identity")
    void anonymousFails() {
        CorrelationContext.setCorrelationId("corr-abc");

        assertThatThrownBy(factory::requireAuthenticated)
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("missing correlation ID is generated so propagation never breaks")
    void correlationGeneratedWhenAbsent() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-123")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        AiRequestContext context = factory.requireAuthenticated();

        assertThat(context.correlationId()).isNotBlank();
    }
}
