package com.seatflow.ai.service.seat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyMinorTest {

    @Test
    @DisplayName("whole major units convert to exact minor units")
    void convertsWholeUnits() {
        assertThat(MoneyMinor.tryToMinor(new BigDecimal("150.00"))).isEqualTo(OptionalLong.of(15000L));
    }

    @Test
    @DisplayName("exact half-cent rounds half up")
    void roundsHalfUp() {
        assertThat(MoneyMinor.tryToMinor(new BigDecimal("10.005"))).isEqualTo(OptionalLong.of(1001L));
        assertThat(MoneyMinor.tryToMinor(new BigDecimal("10.004"))).isEqualTo(OptionalLong.of(1000L));
    }

    @Test
    @DisplayName("null, zero, and negative amounts have no resolvable price")
    void rejectsNonPositive() {
        assertThat(MoneyMinor.tryToMinor(null)).isEmpty();
        assertThat(MoneyMinor.tryToMinor(BigDecimal.ZERO)).isEmpty();
        assertThat(MoneyMinor.tryToMinor(new BigDecimal("-1.00"))).isEmpty();
    }

    @Test
    @DisplayName("overflowing amounts have no resolvable price instead of wrapping")
    void rejectsOverflow() {
        assertThat(MoneyMinor.tryToMinor(new BigDecimal("92233720368547758.08"))).isEmpty();
    }
}
