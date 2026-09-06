package com.seatflow.analytics.projection;

import com.seatflow.analytics.messaging.AnalyticsEventValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Minor-unit money conversion for analytics projections (TASK-P14-003).
 *
 * <p>Source producers emit major-unit decimals (for example {@code "150.00"} RON). Analytics
 * stores integral minor units ({@code long}) plus ISO currency. Conversion is exact:
 * scale-to-2 with {@code HALF_UP} then shift. Overflow or unparseable input fails the event
 * (DLQ path) instead of clamping or silently rounding money.
 */
public final class MoneyMinor {

    private MoneyMinor() {
    }

    /**
     * Convert a major-unit amount to exact minor units.
     *
     * @throws AnalyticsEventValidationException when the value is unparseable, negative, or
     *         overflows {@code long}.
     */
    public static long toMinor(String rawAmount, String eventId, String eventType, String field) {
        final BigDecimal major;
        try {
            major = new BigDecimal(rawAmount);
        } catch (NumberFormatException | ArithmeticException | NullPointerException ex) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event " + eventType + " field " + field + " must be numeric", ex);
        }
        if (major.signum() < 0) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event " + eventType + " field " + field + " must be >= 0");
        }
        try {
            return major.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        } catch (ArithmeticException ex) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event " + eventType + " field " + field + " overflows minor units", ex);
        }
    }
}
