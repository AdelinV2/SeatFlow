package com.seatflow.ai.service.seat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalLong;

/**
 * Exact major-to-minor money conversion for AI seat tools (TASK-P15-003 section 4.1).
 *
 * <p>Upstream pricing tiers expose major-unit decimals (for example {@code 150.00}). All budget
 * checks and totals use integral minor units ({@code long}). Conversion is scale-to-2 with
 * {@code HALF_UP} then shift — the same canonical scale as the analytics {@code MoneyMinor}
 * projection helper. There is no shared common-module money helper, so this AI-owned helper
 * documents the scale locally instead of duplicating it ad hoc. No floating-point arithmetic and
 * no FX conversion ever occur here.
 */
public final class MoneyMinor {

    private MoneyMinor() {
    }

    /**
     * Convert a major-unit amount to exact minor units.
     *
     * @return empty when the value is null, negative, unparseable, or overflows {@code long};
     *         callers treat empty as "no resolvable price" instead of failing the whole snapshot.
     */
    public static OptionalLong tryToMinor(BigDecimal major) {
        if (major == null || major.signum() <= 0) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(major.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact());
        } catch (ArithmeticException ex) {
            return OptionalLong.empty();
        }
    }
}
