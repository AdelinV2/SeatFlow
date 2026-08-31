package com.seatflow.common.observability.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets while preserving the surrounding diagnostic text.
 *
 * <p>The converter is used by readable local/dev pattern appenders. Production
 * JSON appenders use {@link LogstashSensitiveValueMasker}, which delegates to
 * the same staged {@link #mask(String)} implementation.</p>
 */
public class SensitiveDataMaskingConverter extends CompositeConverter<ILoggingEvent> {

    private static final Pattern JWT_PATTERN = Pattern.compile(
            "(?i)\\bBearer\\s+[^\\s,;\\\"']+"
    );

    private static final Pattern STRIPE_SECRET_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:sk_[A-Za-z0-9_]+|rk_[A-Za-z0-9_]+|whsec_[A-Za-z0-9_]+)(?![A-Za-z0-9_])"
    );

    private static final Pattern PAN_PATTERN = Pattern.compile(
            "\\b(?:\\d[ -]*?){13,19}\\b"
    );

    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile(
            "(?i)([\\\"']?(?:password|passwd|authorization|client_secret|clientSecret|secret|token|apiKey|api_key|accessToken|access_token|refreshToken|refresh_token|verificationToken|verification_token|resetToken|reset_token|idempotencyKey|idempotency_key|cvv|cvc|cvc2|securityCode|security_code)[\\\"']?\\s*[:=]\\s*)(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'[^']*'|[^\\\"'\\s,}&]+)"
    );

    @Override
    protected String transform(ILoggingEvent event, String in) {
        return mask(in);
    }

    /**
     * Apply all masking substitutions to a diagnostic value.
     *
     * @param input message, exception text, or another log value
     * @return the original value when it is null/empty or has no sensitive data;
     *         otherwise the value with only sensitive portions replaced
     */
    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        // Keep this order stable: the JSON and pattern appenders must produce
        // the same replacement values for the same input.
        String result = JWT_PATTERN.matcher(input).replaceAll("Bearer [MASKED_JWT]");
        result = STRIPE_SECRET_PATTERN.matcher(result).replaceAll("[MASKED_STRIPE_SECRET]");
        result = maskPanNumbers(result);
        return maskKeyValueSecrets(result);
    }

    private static String maskPanNumbers(String input) {
        Matcher matcher = PAN_PATTERN.matcher(input);
        StringBuilder masked = new StringBuilder(input.length());

        while (matcher.find()) {
            String candidate = matcher.group();
            String digits = candidate.replaceAll("[\\s-]", "");
            if (digits.length() >= 13
                    && digits.length() <= 19
                    && digits.chars().allMatch(Character::isDigit)) {
                String lastFour = digits.substring(digits.length() - 4);
                matcher.appendReplacement(
                        masked,
                        Matcher.quoteReplacement("****-****-****-" + lastFour)
                );
            } else {
                matcher.appendReplacement(masked, Matcher.quoteReplacement(candidate));
            }
        }

        matcher.appendTail(masked);
        return masked.toString();
    }

    private static String maskKeyValueSecrets(String input) {
        Matcher matcher = KEY_VALUE_SECRET_PATTERN.matcher(input);
        StringBuilder masked = new StringBuilder(input.length());

        while (matcher.find()) {
            String value = matcher.group(2);
            if (isMaskReplacement(matcher.group(1), value)) {
                matcher.appendReplacement(masked, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(
                        masked,
                        Matcher.quoteReplacement(matcher.group(1) + maskKeyValue(value))
                );
            }
        }

        matcher.appendTail(masked);
        return masked.toString();
    }

    private static String maskKeyValue(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.charAt(0) + "[MASKED]" + value.charAt(value.length() - 1);
        }
        return "[MASKED]";
    }

    private static boolean isMaskReplacement(String key, String value) {
        String unquotedValue = stripWrappingQuotes(value);
        return "[MASKED]".equals(unquotedValue)
                || "[MASKED_JWT]".equals(unquotedValue)
                || "[MASKED_STRIPE_SECRET]".equals(unquotedValue)
                // The JWT stage leaves the "Authorization: Bearer" prefix in
                // place. Do not mask that prefix in the key/value stage.
                || (key.toLowerCase().contains("authorization") && "Bearer".equalsIgnoreCase(unquotedValue));
    }

    private static String stripWrappingQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
