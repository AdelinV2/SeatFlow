package com.seatflow.common.observability.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logback composite converter and utility for masking sensitive data (Bearer JWTs,
 * Stripe API/webhook secrets, passwords, tokens, and Payment Card PANs) in log output.
 */
public class SensitiveDataMaskingConverter extends CompositeConverter<ILoggingEvent> {

    private static final Pattern JWT_PATTERN = Pattern.compile(
            "(?i)\\bBearer\\s+eyJ[^\\s,;\"']+",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern STRIPE_SECRET_PATTERN = Pattern.compile(
            "\\b(?:(?:sk|rk)_(?:live|test)_[A-Za-z0-9_]+|whsec_[A-Za-z0-9_]+)\\b"
    );

    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile(
            "(?i)([\"']?(?:password|passwd|client_secret|secret|token|apiKey|api_key|refreshToken|refresh_token|verificationToken|resetToken)[\"']?\\s*[:=]\\s*[\"']?)([^\"'\\s,}&]+)([\"']?)"
    );

    private static final Pattern PAN_PATTERN = Pattern.compile(
            "\\b(?:\\d[ -]*?){13,19}\\b"
    );

    @Override
    protected String transform(ILoggingEvent event, String in) {
        if (in == null || in.isEmpty()) {
            return in;
        }
        return mask(in);
    }

    /**
     * Masks sensitive tokens, credentials, and credit card numbers from the provided string.
     *
     * @param input the input log or diagnostic text
     * @return the masked string
     */
    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        // 1. Mask Bearer JWTs
        String result = JWT_PATTERN.matcher(input).replaceAll("Bearer [MASKED_JWT]");

        // 2. Mask Stripe Secrets (sk_live_*, sk_test_*, whsec_*, rk_*)
        result = STRIPE_SECRET_PATTERN.matcher(result).replaceAll("[MASKED_STRIPE_SECRET]");

        // 3. Mask PAN card numbers (preserving last 4 digits)
        result = maskPanNumbers(result);

        // 4. Mask key-value passwords and secret tokens
        result = maskKeyValueSecrets(result);

        return result;
    }

    private static String maskPanNumbers(String text) {
        Matcher matcher = PAN_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (matcher.find()) {
            String matched = matcher.group();
            String digits = matched.replaceAll("[\\s-]", "");
            if (digits.length() >= 13 && digits.length() <= 19 && digits.chars().allMatch(Character::isDigit)) {
                String last4 = digits.substring(digits.length() - 4);
                matcher.appendReplacement(sb, Matcher.quoteReplacement("****-****-****-" + last4));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matched));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String maskKeyValueSecrets(String text) {
        Matcher matcher = KEY_VALUE_SECRET_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (matcher.find()) {
            String prefix = matcher.group(1);
            String val = matcher.group(2);
            String suffix = matcher.group(3);
            if ("[MASKED]".equals(val) || "[MASKED_JWT]".equals(val) || "[MASKED_STRIPE_SECRET]".equals(val)) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(prefix + "[MASKED]" + suffix));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
