package com.seatflow.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.ai.api.dto.AssistantCard;
import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import com.seatflow.ai.api.dto.AssistantChatResponse;
import com.seatflow.ai.api.dto.AssistantError;
import com.seatflow.ai.api.dto.ProposalConfirmationError;
import com.seatflow.ai.api.dto.ReservationCreatedCard;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.orchestration.AssistantState;
import com.seatflow.common.observability.logging.SensitiveDataMaskingConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Secret and logging audit (TASK-P15-007 section 9).
 *
 * <p>Proves that provider keys, JWTs, payment secrets, DB credentials, excess PII, the raw system
 * prompt, and chain-of-thought never appear in API payloads, masked logs, metrics-facing DTOs, or
 * the committed {@code .env.example}. Secret-safe placeholders are used throughout.
 */
class AiSecretAuditTest {

    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "gsk_", "GROQ_API_KEY", "eyJhbGciOi", "Bearer eyJ",
            "sk_test_", "sk_live_", "rk_test_", "whsec_",
            "postgres://", "chain-of-thought", "chainOfThought", "reasoning_content");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static void assertSecretSafe(String artifact, String json) {
        for (String fragment : FORBIDDEN_FRAGMENTS) {
            assertThat(json)
                    .as("%s must not contain %s", artifact, fragment)
                    .doesNotContain(fragment);
        }
    }

    @Test
    @DisplayName("chat responses never carry secrets, keys, tokens, or reasoning")
    void chatResponseSerializationSafe() throws Exception {
        var response = new AssistantChatResponse(UUID.randomUUID(), "Two seats together.",
                AssistantState.CONFIRMATION_REQUIRED,
                List.of(AssistantCard.info("Title", "Message")),
                List.of("Confirm the exact proposal"),
                new AssistantError(AssistantChatErrorCode.AI_RATE_LIMITED, "slow down"));
        assertSecretSafe("AssistantChatResponse", mapper.writeValueAsString(response));
    }

    @Test
    @DisplayName("reservation-created card carries hold data only, no payment internals")
    void reservationCardSerializationSafe() throws Exception {
        var card = new ReservationCreatedCard(UUID.randomUUID(), UUID.randomUUID(),
                List.of(UUID.randomUUID()), List.of("Row A Seat 1"), new BigDecimal("30.00"),
                "EUR", "PENDING", Instant.now(), "/checkout/1");
        String json = mapper.writeValueAsString(card);
        assertSecretSafe("ReservationCreatedCard", json);
        assertThat(json).doesNotContain("paymentIntent").doesNotContain("cardNumber");
    }

    @Test
    @DisplayName("confirmation failures carry stable codes, never downstream bodies")
    void confirmationErrorSerializationSafe() throws Exception {
        var error = new ProposalConfirmationError("PRICE_CHANGED",
                "The price changed. Please request a fresh proposal.");
        assertSecretSafe("ProposalConfirmationError", mapper.writeValueAsString(error));
    }

    @Test
    @DisplayName("request context toString masks the bearer token")
    void requestContextToStringMasked() {
        var context = new AiRequestContext("eyJhbGciOiJIUzI1NiJ9.payload.signature",
                "corr-1", "user-1");
        assertThat(context.toString()).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(context.toString()).contains("corr-1").contains("user-1");
    }

    @Test
    @DisplayName("shared log masking redacts the realistic accidental patterns")
    void logMaskingRedactsRealisticPatterns() {
        assertThat(SensitiveDataMaskingConverter.mask("starting with GROQ_API_KEY=gsk_secret_xyz_123"))
                .doesNotContain("gsk_secret_xyz_123");
        assertThat(SensitiveDataMaskingConverter.mask(
                "call failed Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"))
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(SensitiveDataMaskingConverter.mask("refresh_token=rt_secret_value_9"))
                .doesNotContain("rt_secret_value_9");
        assertThat(SensitiveDataMaskingConverter.mask("stripe key sk_test_51SecretValue"))
                .doesNotContain("sk_test_51SecretValue");
        assertThat(SensitiveDataMaskingConverter.mask("card 4111111111111111 declined"))
                .doesNotContain("4111111111111111");
    }

    @Test
    @DisplayName("logback configuration wires secret masking for console and JSON appenders")
    void logbackWiresMasking() throws Exception {
        String xml;
        try (var stream = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertThat(stream).as("logback-spring.xml on test classpath").isNotNull();
            xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(xml).contains("SensitiveDataMaskingConverter");
        assertThat(xml).contains("LogstashSensitiveValueMasker");
    }

    @Test
    @DisplayName("committed .env.example carries placeholders only, never secret values")
    void envExampleHasPlaceholdersOnly() throws Exception {
        Path envExample = Path.of(".env.example");
        assertThat(envExample).as(".env.example exists in module dir").exists();
        String content = Files.readString(envExample, StandardCharsets.UTF_8);
        // Variable NAMES are documented; secret VALUES must never be committed.
        assertThat(content).doesNotContain("gsk_");
        assertThat(content).doesNotContain("sk_live_");
        assertThat(content).doesNotContain("sk_test_");
        assertThat(content).doesNotContain("postgres://");
        assertThat(content).doesNotContain("eyJhbGciOi");
        // The key slot must stay empty in version control.
        assertThat(content.lines()
                .filter(line -> line.startsWith("GROQ_API_KEY="))
                .findFirst().orElseThrow())
                .isEqualTo("GROQ_API_KEY=");
    }

    @Test
    @DisplayName("metric tag values never include secret-shaped data")
    void metricTagsSecretSafe() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var metrics = new AiMetrics(registry);
        metrics.recordProviderRequest("gsk_secret_xyz");
        metrics.recordToolCall("Bearer eyJhbGciOiJIUzI1NiJ9", true);
        metrics.recordRateLimited("user-1");

        registry.forEachMeter(meter -> meter.getId().getTags().forEach(tag -> {
            assertThat(tag.getValue()).doesNotContain("gsk_secret_xyz");
            assertThat(tag.getValue()).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
            assertThat(tag.getValue()).doesNotContain("user-1");
        }));
    }
}
