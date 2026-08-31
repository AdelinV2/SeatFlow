package com.seatflow.common.observability.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SensitiveDataMaskingConverterTest {

    private final SensitiveDataMaskingConverter converter = new SensitiveDataMaskingConverter();

    @Test
    @DisplayName("Should mask Bearer JWT tokens in various contexts")
    void shouldMaskBearerJwtTokens() {
        String input = "User logged in with header: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotLeakThisSignature";
        String masked = SensitiveDataMaskingConverter.mask(input);

        assertThat(masked).isEqualTo("User logged in with header: Bearer [MASKED_JWT]");
        assertThat(masked).doesNotContain("eyJhbGci");
    }

    @Test
    @DisplayName("Should mask Stripe test and live API keys and webhook secrets")
    void shouldMaskStripeSecrets() {
        String input = "Stripe client initialized with apiKey=sk_test_51Mz9876543210abcdef and webhook=whsec_abc123456789xyz";
        String masked = SensitiveDataMaskingConverter.mask(input);

        assertThat(masked).isEqualTo("Stripe client initialized with apiKey=[MASKED_STRIPE_SECRET] and webhook=[MASKED_STRIPE_SECRET]");
        assertThat(masked).doesNotContain("sk_test_51Mz");
        assertThat(masked).doesNotContain("whsec_abc123");
    }

    @Test
    @DisplayName("Should mask live Stripe secret keys and restricted keys")
    void shouldMaskStripeLiveAndRestrictedKeys() {
        String input = "keys: sk_live_abc12345def678 and rk_test_987654321xyz";
        String masked = SensitiveDataMaskingConverter.mask(input);

        assertThat(masked).contains("[MASKED_STRIPE_SECRET]");
        assertThat(masked).doesNotContain("sk_live_abc");
        assertThat(masked).doesNotContain("rk_test_987");
    }

    @Test
    @DisplayName("Should mask passwords and tokens in key-value format and JSON")
    void shouldMaskPasswordsAndTokens() {
        String input1 = "Auth request: password=SuperSecretP@ss! and resetToken=rst_999888777";
        String masked1 = SensitiveDataMaskingConverter.mask(input1);
        assertThat(masked1).isEqualTo("Auth request: password=[MASKED] and resetToken=[MASKED]");

        String input2 = "JSON payload: {\"password\": \"MyPass123!\", \"token\": \"tok_abc\"}";
        String masked2 = SensitiveDataMaskingConverter.mask(input2);
        assertThat(masked2).contains("\"password\": \"[MASKED]\"");
        assertThat(masked2).contains("\"token\": \"[MASKED]\"");
    }

    @Test
    @DisplayName("Should mask 13-19 digit PAN card numbers while retaining last 4 digits")
    void shouldMaskPanCardNumbers() {
        // Hyphenated 16 digits
        String card1 = "Payment failed for card 4111-2222-3333-4444 on gateway";
        assertThat(SensitiveDataMaskingConverter.mask(card1))
                .isEqualTo("Payment failed for card ****-****-****-4444 on gateway");

        // Space-separated 16 digits
        String card2 = "Customer entered 5500 0000 0000 0004 at checkout";
        assertThat(SensitiveDataMaskingConverter.mask(card2))
                .isEqualTo("Customer entered ****-****-****-0004 at checkout");

        // Raw unformatted 16 digits
        String card3 = "Processed charge for card 4111222233334444";
        assertThat(SensitiveDataMaskingConverter.mask(card3))
                .isEqualTo("Processed charge for card ****-****-****-4444");

        // 15-digit Amex
        String card4 = "Amex card 3782-822463-10005 charged";
        assertThat(SensitiveDataMaskingConverter.mask(card4))
                .isEqualTo("Amex card ****-****-****-0005 charged");
    }

    @Test
    @DisplayName("Should leave benign text, timestamps, and UUIDs untouched")
    void shouldLeaveBenignTextUntouched() {
        String benign = "Event 123e4567-e89b-12d3-a456-426614174000 processed at 2026-08-31T15:24:11.123Z with durationMs=42";
        String masked = SensitiveDataMaskingConverter.mask(benign);

        assertThat(masked).isEqualTo(benign);
    }

    @Test
    @DisplayName("Should mask multiple sensitive items in a combined log / exception message")
    void shouldMaskCombinedSensitiveContent() {
        String combined = "Exception in auth: Bearer eyJhbGciOiJIUzI1NiJ9.test.sig failed with password=badpass for card 4111-2222-3333-9999 and stripe=sk_test_12345";
        String masked = SensitiveDataMaskingConverter.mask(combined);

        assertThat(masked).contains("Bearer [MASKED_JWT]");
        assertThat(masked).contains("password=[MASKED]");
        assertThat(masked).contains("****-****-****-9999");
        assertThat(masked).contains("stripe=[MASKED_STRIPE_SECRET]");
    }

    @Test
    @DisplayName("Should safely handle null and empty strings")
    void shouldHandleNullAndEmpty() {
        assertThat(SensitiveDataMaskingConverter.mask(null)).isNull();
        assertThat(SensitiveDataMaskingConverter.mask("")).isEmpty();
    }

    @Test
    @DisplayName("Should work as Logback CompositeConverter transform")
    void shouldWorkAsLogbackConverter() {
        ILoggingEvent mockEvent = mock(ILoggingEvent.class);
        String transformed = converter.transform(mockEvent, "token=secretToken123");
        assertThat(transformed).isEqualTo("token=[MASKED]");
    }
}
