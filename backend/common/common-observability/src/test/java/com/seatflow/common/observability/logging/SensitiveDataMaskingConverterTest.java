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
        assertThat(SensitiveDataMaskingConverter.mask("Payment failed for card 4111-2222-3333-4444"))
                .isEqualTo("Payment failed for card ****-****-****-4444");
    }

    @Test
    void shouldMaskJwtStripePasswordsTokensAndCvv() {
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature "
                + "stripe=sk_test_51Mz9876543210abcdef webhook=whsec_abc123456789xyz "
                + "password=SuperSecret token=tok_abc cvv=123 resetToken=rst_abc";

        String masked = SensitiveDataMaskingConverter.mask(input);

        assertThat(masked).contains("Authorization: Bearer [MASKED_JWT]", "[MASKED_STRIPE_SECRET]")
                .contains("password=[MASKED]", "token=[MASKED]", "cvv=[MASKED]", "resetToken=[MASKED]")
                .doesNotContain("eyJhbGci", "sk_test_", "whsec_", "SuperSecret", "tok_abc", "rst_abc");
    }

    @Test
    void shouldPreserveQuotedBearerMaskAndMaskJsonSecrets() {
        String input = "{\"Authorization\":\"Bearer eyJhbGciOiJIUzI1NiJ9.payload\",\"password\":\"secret\"}";

        assertThat(SensitiveDataMaskingConverter.mask(input))
                .isEqualTo("{\"Authorization\":\"Bearer [MASKED_JWT]\",\"password\":\"[MASKED]\"}");
    }

    @Test
    void shouldLeaveBenignDiagnosticTextUntouched() {
        String benign = "Reservation 123e4567-e89b-12d3-a456-426614174000 completed durationMs=42";

        assertThat(SensitiveDataMaskingConverter.mask(benign)).isEqualTo(benign);
        assertThat(SensitiveDataMaskingConverter.mask(null)).isNull();
        assertThat(SensitiveDataMaskingConverter.mask("")).isEmpty();
    }

    @Test
    void shouldReturnNullForUnchangedJsonValuesAndMaskNumericPanValues() {
        LogstashSensitiveValueMasker masker = new LogstashSensitiveValueMasker();

        assertThat(masker.mask(null, "reservationId=123e4567-e89b-12d3-a456-426614174000")).isNull();
        assertThat(masker.mask(null, 4111222233334444L))
                .isEqualTo("****-****-****-4444");
        assertThat(masker.mask(null, 42)).isNull();
    }

    @Test
    void shouldMaskMessageAndThrowableWhenEncodedAsProductionJson() {
        String message = "Payment rejected card=4111-2222-3333-4444 password=badpass token=tok_abc stripe=sk_live_abc123";
        Throwable throwable = new IllegalStateException(
                "gateway response contained card 5500 0000 0000 0004 and Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature with whsec_secret123"
        );

        String json = encodeProductionJson(message, throwable);

        assertThat(json)
                .contains("****-****-****-4444", "password=[MASKED]", "token=[MASKED]", "[MASKED_STRIPE_SECRET]")
                .contains("\"stack_trace\"", "****-****-****-0004", "Bearer [MASKED_JWT]")
                .doesNotContain("4111-2222-3333-4444", "5500 0000 0000 0004", "eyJhbGci", "sk_live_", "whsec_");
    }

    private String encodeProductionJson(String message, Throwable throwable) {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("seatflow.test");
        LoggingEvent event = new LoggingEvent(
                SensitiveDataMaskingConverterTest.class.getName(), logger, Level.ERROR, message, throwable, null
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
