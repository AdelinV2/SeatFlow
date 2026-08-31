package com.seatflow.common.observability.metrics;

import io.micrometer.core.instrument.Tags;

import java.util.Currency;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enforces strictly bounded tag sets for all Prometheus meters.
 * <p>
 * High-cardinality identifiers (userId, reservationId, paymentId, ticketId, seatId,
 * eventId, stripePaymentIntentId, raw URLs, trace IDs, client IPs) must NEVER appear
 * as metric tags — they create unbounded time-series cardinality and risk TSDB
 * exhaustion. Use structured logs/traces for those identifiers instead.
 */
public final class MetricTagPolicy {

    private MetricTagPolicy() {}

    // --- Forbidden tag keys (case-insensitive normalized check) ---
    public static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
            "userid", "user_id", "user-id",
            "reservationid", "reservation_id", "reservation-id",
            "paymentid", "payment_id", "payment-id",
            "ticketid", "ticket_id", "ticket-id",
            "seatid", "seat_id", "seat-id",
            "eventid", "event_id", "event-id",
            "stripepaymentintentid", "stripe_payment_intent_id", "stripe-payment-intent-id", "stripepaymentintent_id",
            "traceid", "trace_id", "trace-id",
            "spanid", "span_id", "span-id",
            "correlationid", "correlation_id", "correlation-id",
            "clientip", "client_ip", "client-ip", "ip",
            "url", "path", "rawuri", "raw_uri",
            "email", "customeremail", "customer_email"
    );

    // --- Bounded value sets ---
    public static final Set<String> ALLOWED_STATUSES = Set.of(
            "SUCCESS", "FAILED", "INITIATED", "PENDING", "CANCELLED", "EXPIRED", "UNKNOWN"
    );
    public static final Set<String> ALLOWED_REASONS = Set.of(
            "ALREADY_HELD", "LIMIT_EXCEEDED", "INVALID_STATE",
            "SEAT_ALREADY_RESERVED", "CONFLICT", "DB_UNIQUE_VIOLATION",
            "STRIPE_FAILED", "UNKNOWN"
    );
    public static final Set<String> ALLOWED_OUTCOMES = Set.of(
            "SUCCESS", "FAILED", "TIMEOUT", "RETRY", "EXPIRED", "UNKNOWN"
    );
    public static final Set<String> ALLOWED_CURRENCIES = Set.of(
            "USD", "EUR", "GBP", "JPY", "UNKNOWN"
    );
    public static final Set<String> ALLOWED_PAYMENT_METHODS = Set.of(
            "CARD", "WALLET", "BANK_TRANSFER", "UNKNOWN"
    );
    public static final Set<String> ALLOWED_SERVICES = Set.of(
            "reservation-service", "payment-service", "ticket-service",
            "user-service", "seat-map-service", "event-service",
            "realtime-service", "notification-service",
            "api-gateway", "eureka-server", "unknown"
    );
    public static final Set<String> ALLOWED_EVENT_TYPES = Set.of(
            "ReservationHeldEvent", "ReservationConfirmedEvent", "ReservationCancelledEvent",
            "ReservationExpiredEvent", "PaymentCompleted", "PaymentCompletedEvent",
            "PaymentFailed", "PaymentFailedEvent", "TicketIssued", "TicketIssuedEvent",
            "UserRegisteredEvent", "UNKNOWN"
    );
    public static final Set<String> ALLOWED_SOURCES = Set.of(
            "PAYMENT_COMPLETED", "RESERVATION_CONFIRMED", "API", "KAFKA", "UNKNOWN"
    );

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HIGH_CARDINALITY_VALUE = Pattern.compile(".*(pi_[a-zA-Z0-9]+|sk_[a-zA-Z0-9]+|whsec_[a-zA-Z0-9]+).*");

    // --- Public validation API ---

    /**
     * Validates a tag key is not forbidden. Throws IllegalArgumentException if forbidden.
     */
    public static void validateTagKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Tag key must not be blank");
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        if ("uri".equals(normalized)) {
            return;
        }
        // also remove underscores for comparison
        String compact = normalized.replace("_", "");
        for (String forbidden : FORBIDDEN_TAG_KEYS) {
            String fCompact = forbidden.toLowerCase(Locale.ROOT).replace("-", "_").replace("_", "");
            if (compact.equals(fCompact) || compact.contains(fCompact)) {
                throw new IllegalArgumentException("Forbidden high-cardinality tag key: " + key);
            }
        }
        // additional substring checks for raw URLs/paths that may be combined like rawUrl, rawUri
        if (compact.contains("url") || compact.contains("uri") || compact.contains("path")) {
            // allow only bounded keys explicitly, otherwise reject
            Set<String> allowedUrlKeys = Set.of("uri", "method", "outcome");
            if (!allowedUrlKeys.contains(normalized)) {
                // if key contains url/uri/path but is not exactly an allowed key, consider high-cardinality
                // For this policy we treat any key containing url/uri/path as forbidden unless it's the bounded uri tag sanitized
                // The test expects rawUrl to be rejected
                if (compact.contains("rawurl") || compact.contains("rawuri") || compact.contains("url") || compact.contains("uri")) {
                    // Only allow exact "uri" for http.server.requests already bounded; other combinations are forbidden
                    if (!compact.equals("uri")) {
                        throw new IllegalArgumentException("Forbidden high-cardinality tag key: " + key);
                    }
                }
            }
        }
        // also reject any key that looks like an ID suffix
        if (compact.endsWith("id") && compact.length() > 2 && !compact.equals("correlationid")) {
            // Allow bounded keys like event_type, service, status etc — only block *_id patterns
            // If key ends with "id" and is not in allowed list, consider forbidden
            // Allowed keys are explicitly bounded; anything else ending with id is suspect
            Set<String> allowedKeys = Set.of("status", "reason", "outcome", "currency", "payment_method",
                    "service", "event_type", "source", "application", "environment", "uri", "method", "exception");
            if (!allowedKeys.contains(key.toLowerCase(Locale.ROOT))) {
                // Check if key contains "id" substring that indicates high cardinality
                if (compact.contains("userid") || compact.contains("reservationid") || compact.contains("paymentid")
                        || compact.contains("ticketid") || compact.contains("seatid") || compact.contains("eventid")
                        || compact.contains("stripe")) {
                    throw new IllegalArgumentException("Forbidden high-cardinality tag key: " + key);
                }
            }
        }
    }

    public static void validateTagValue(String value) {
        if (value == null || value.isBlank()) {
            return; // empty will be normalized to UNKNOWN by callers
        }
        String trimmed = value.trim();
        if (UUID_PATTERN.matcher(trimmed).find()) {
            throw new IllegalArgumentException("Tag value appears to be a UUID (high-cardinality): " + trimmed);
        }
        if (HIGH_CARDINALITY_VALUE.matcher(trimmed).find()) {
            throw new IllegalArgumentException("Tag value appears to be a Stripe/high-cardinality identifier: " + trimmed);
        }
        if (trimmed.contains("http://") || trimmed.contains("https://")) {
            throw new IllegalArgumentException("Tag value must not be a raw URL: " + trimmed);
        }
        // Raw paths with multiple UUID-like segments are forbidden
        if (trimmed.length() > 60 && trimmed.contains("/")) {
            throw new IllegalArgumentException("Tag value appears to be a raw path (high-cardinality): " + trimmed);
        }
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException("Tag value too long, likely high-cardinality: " + trimmed);
        }
    }

    /**
     * Validates arbitrary tags map does not contain forbidden keys or high-cardinality values.
     */
    public static void validateTags(Tags tags) {
        if (tags == null) return;
        tags.forEach(tag -> {
            validateTagKey(tag.getKey());
            validateTagValue(tag.getValue());
        });
    }

    // --- Canonical tag builders (all validate and bound the values) ---

    public static Tags reservationCreated(String status) {
        String safeStatus = boundValue(status, ALLOWED_STATUSES, "SUCCESS");
        validateTagKey("status");
        validateTagValue(safeStatus);
        return Tags.of("status", safeStatus);
    }

    public static Tags reservationConflict(String reason) {
        String safeReason = boundValue(reason, ALLOWED_REASONS, "UNKNOWN");
        validateTagKey("reason");
        validateTagValue(safeReason);
        return Tags.of("reason", safeReason);
    }

    public static Tags holdDuration(String outcome) {
        String safeOutcome = boundValue(outcome, ALLOWED_OUTCOMES, "UNKNOWN");
        return Tags.of("outcome", safeOutcome);
    }

    public static Tags reservationExpired(String outcome) {
        String safeOutcome = boundValue(outcome, ALLOWED_OUTCOMES, "EXPIRED");
        return Tags.of("outcome", safeOutcome);
    }

    public static Tags paymentProcessed(String status, String currency, String paymentMethod) {
        String s = boundValue(status, ALLOWED_STATUSES, "UNKNOWN");
        String c = boundCurrency(currency);
        String pm = boundValue(paymentMethod, ALLOWED_PAYMENT_METHODS, "UNKNOWN");
        Tags tags = Tags.of("status", s, "currency", c, "payment_method", pm);
        validateTags(tags);
        return tags;
    }

    public static Tags paymentProcessed(String status, Currency currency, String paymentMethod) {
        String cur = currency != null ? currency.getCurrencyCode() : null;
        return paymentProcessed(status, cur, paymentMethod);
    }

    public static Tags ticketIssued(String source) {
        String s = boundValue(source, ALLOWED_SOURCES, "UNKNOWN");
        return Tags.of("source", s);
    }

    public static Tags outboxPublish(String service, String eventType, String outcome) {
        String svc = boundValue(service, ALLOWED_SERVICES, "unknown");
        String evt = boundValue(eventType, ALLOWED_EVENT_TYPES, "UNKNOWN");
        String out = boundValue(outcome, ALLOWED_OUTCOMES, "UNKNOWN");
        Tags tags = Tags.of("service", svc, "event_type", evt, "outcome", out);
        validateTags(tags);
        return tags;
    }

    public static Tags outboxRetry(String service, String eventType) {
        String svc = boundValue(service, ALLOWED_SERVICES, "unknown");
        String evt = boundValue(eventType, ALLOWED_EVENT_TYPES, "UNKNOWN");
        Tags tags = Tags.of("service", svc, "event_type", evt);
        validateTags(tags);
        return tags;
    }

    public static Tags outboxDeadLetter(String service, String eventType) {
        return outboxRetry(service, eventType);
    }

    public static Tags reservationOutcome(String status) {
        return reservationCreated(status);
    }

    public static Tags paymentOutcome(String status, Currency currency, String paymentMethod) {
        return paymentProcessed(status, currency, paymentMethod);
    }

    public static Tags outboxOutcome(String service, String eventType, String outcome) {
        return outboxPublish(service, eventType, outcome);
    }

    // --- Helpers ---

    public static String boundValue(String value, Set<String> allowed, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        // service names are lower-case, normalize accordingly
        if (allowed.contains(value.trim())) {
            return value.trim();
        }
        if (allowed.contains(upper)) {
            return upper;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (allowed.contains(lower)) {
            return lower;
        }
        return defaultValue;
    }

    private static String boundCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "UNKNOWN";
        }
        String upper = currency.trim().toUpperCase(Locale.ROOT);
        if (ALLOWED_CURRENCIES.contains(upper)) {
            return upper;
        }
        return "UNKNOWN";
    }

    /**
     * Sanitizes a raw URI tag value to a bounded template.
     * If the URI contains UUIDs or numeric IDs where a template would be expected,
     * returns {@code UNKNOWN} instead of the raw value.
     */
    public static String sanitizeUriTag(String uri) {
        if (uri == null || uri.isBlank()) {
            return "UNKNOWN";
        }
        String trimmed = uri.trim();
        if (trimmed.equalsIgnoreCase("UNKNOWN")) {
            return "UNKNOWN";
        }
        // If contains UUID, it's a concrete resource, not a template
        if (UUID_PATTERN.matcher(trimmed).find()) {
            return "UNKNOWN";
        }
        // Known URI templates — allow only these, everything else maps to UNKNOWN
        Set<String> knownTemplates = Set.of(
                "/api/reservations", "/api/reservations/{id}", "/api/reservations/events/{eventId}/availability",
                "/api/payments/intent", "/api/payments/{id}", "/api/payments/reservation/{reservationId}",
                "/api/payments/webhook", "/api/tickets", "/api/tickets/{id}", "/api/tickets/my-tickets",
                "/api/tickets/guest/{code}", "/api/scanner/tickets/validate",
                "/actuator/health", "/actuator/info", "/actuator/prometheus", "/actuator/metrics",
                "/ws/**"
        );
        if (knownTemplates.contains(trimmed)) {
            return trimmed;
        }
        // If URI starts with known prefix but contains path segments that are not templates, map to UNKNOWN
        if (trimmed.matches(".*/[0-9a-fA-F\\-]{10,}.*") || trimmed.matches(".*/\\d+.*")) {
            return "UNKNOWN";
        }
        // Allow exact matches for actuator/swagger
        if (trimmed.startsWith("/actuator/") || trimmed.startsWith("/v3/api-docs") || trimmed.startsWith("/swagger-ui")) {
            return trimmed;
        }
        return "UNKNOWN";
    }
}
