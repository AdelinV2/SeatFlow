package com.seatflow.common.observability.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
import net.logstash.logback.stacktrace.ShortenedThrowableConverter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskingConverterTest {

    @Test
    void shouldMaskPanAndPreserveLastFourDigits() {
        String input = "Payment failed for card 4111-2222-3333-4444";

        assertThat(SensitiveDataMaskingConverter.mask(input))
                .isEqualTo("Payment failed for card ****-****-****-4444");
    }

    @Test
    void shouldMaskStripeSecrets() {
        String input = "stripeKey=sk_test_51Mz9876543210abcdef webhook=whsec_abc123456789xyz";

        String masked = SensitiveDataMaskingConverter.mask(input);

        assertThat(masked)
                .isEqualTo("stripeKey=[MASKED_STRIPE_SECRET] webhook=[MASKED_STRIPE_SECRET]")
                .doesNotContain("sk_test_", "whsec_");
    }

    @Test
    void shouldMaskBearerJwt() {
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjMifQ.signature";

        assertThat(SensitiveDataMaskingConverter.mask(input))
                .isEqualTo("Authorization: Bearer [MASKED_JWT]");
    }

    @Test
    void shouldMaskPasswordsAndTokensInMessageTextAndJsonText() {
        String message = "password=SuperSecretP@ss! token=tok_abc refresh_token=refresh-secret cvv=123";
        String json = "{\"password\":\"MyPass123!\",\"token\":\"tok_abc\"}";
        String quotedValueWithSpaces = "{\"password\":\"super secret phrase\",\"token\":\"token with spaces\"}";

        assertThat(SensitiveDataMaskingConverter.mask(message))
                .isEqualTo("password=[MASKED] token=[MASKED] refresh_token=[MASKED] cvv=[MASKED]");
        assertThat(SensitiveDataMaskingConverter.mask(json))
                .isEqualTo("{\"password\":\"[MASKED]\",\"token\":\"[MASKED]\"}");
        assertThat(SensitiveDataMaskingConverter.mask(quotedValueWithSpaces))
                .isEqualTo("{\"password\":\"[MASKED]\",\"token\":\"[MASKED]\"}");
    }

    @Test
    void shouldLeaveBenignDiagnosticTextUntouched() {
        String benign = "Reservation 123e4567-e89b-12d3-a456-426614174000 completed durationMs=42";

        assertThat(SensitiveDataMaskingConverter.mask(benign)).isEqualTo(benign);
        assertThat(SensitiveDataMaskingConverter.mask(null)).isNull();
        assertThat(SensitiveDataMaskingConverter.mask("")).isEmpty();
    }

    @Test
    void shouldMaskMessageAndThrowableWhenEncodedAsProductionJson() {
        String message = "Payment rejected card=4111-2222-3333-4444 password=badpass "
                + "token=tok_abc stripe=sk_live_abc123";
        Throwable throwable = new IllegalStateException(
                "gateway response contained card 5500 0000 0000 0004 and Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature "
                        + "with whsec_secret123"
        );

        String json = encodeProductionJson(message, throwable);

        assertThat(json)
                .contains("****-****-****-4444")
                .contains("password=[MASKED]")
                .contains("token=[MASKED]")
                .contains("[MASKED_STRIPE_SECRET]")
                .contains("\"stack_trace\"")
                .contains("****-****-****-0004")
                .contains("Bearer [MASKED_JWT]")
                .doesNotContain("4111-2222-3333-4444", "5500 0000 0000 0004", "eyJhbGci", "sk_live_", "whsec_");
    }

    private String encodeProductionJson(String message, Throwable throwable) {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("seatflow.test");
        LoggingEvent event = new LoggingEvent(
                SensitiveDataMaskingConverterTest.class.getName(),
                logger,
                Level.ERROR,
                message,
                throwable,
                (Object[]) null
        );
        event.setLoggerContext(context);
        event.setMDCPropertyMap(Map.of());

        MaskingJsonGeneratorDecorator decorator = new MaskingJsonGeneratorDecorator();
        decorator.addValueMasker(new LogstashSensitiveValueMasker());
        decorator.start();

        ShortenedThrowableConverter throwableConverter = new ShortenedThrowableConverter();
        throwableConverter.setMaxDepthPerThrowable(20);
        throwableConverter.setMaxLength(4096);
        throwableConverter.setRootCauseFirst(true);

        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.setJsonGeneratorDecorator(decorator);
        encoder.setThrowableConverter(throwableConverter);
        encoder.start();

        String encoded = new String(encoder.encode(event), StandardCharsets.UTF_8);

        encoder.stop();
        decorator.stop();
        context.stop();
        return encoded;
    }
}
