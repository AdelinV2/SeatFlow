package com.seatflow.reservation.migration;

import com.seatflow.reservation.repository.ReservationRepository;
import com.seatflow.reservation.repository.SeatHoldRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.when;

/**
 * P12-007 (REV-004, option b): the startup data-quality check reports NULL
 * session rows without ever blocking application boot.
 */
@ExtendWith(MockitoExtension.class)
class SessionIntegrityStartupCheckTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private SeatHoldRepository seatHoldRepository;

    @InjectMocks
    private SessionIntegrityStartupCheck check;

    @Test
    void checkReportsCleanWhenZeroNulls() {
        when(reservationRepository.countReservationsWithNullSession()).thenReturn(0L);
        when(seatHoldRepository.countSeatHoldsWithNullSession()).thenReturn(0L);

        var report = check.check();

        assertThat(report).isPresent();
        assertThat(report.get().clean()).isTrue();
        assertThatNoException().isThrownBy(check::onApplicationReady);
    }

    @Test
    void checkReportsOrphanCountsWithoutBlockingStartup() {
        when(reservationRepository.countReservationsWithNullSession()).thenReturn(2L);
        when(seatHoldRepository.countSeatHoldsWithNullSession()).thenReturn(3L);

        var report = check.check();

        assertThat(report).isPresent();
        assertThat(report.get().clean()).isFalse();
        assertThat(report.get().nullReservations()).isEqualTo(2L);
        assertThat(report.get().nullSeatHolds()).isEqualTo(3L);
        assertThatNoException().isThrownBy(check::onApplicationReady);
    }

    @Test
    void checkIsFailOpenWhenRepositoryQueryFails() {
        when(reservationRepository.countReservationsWithNullSession())
                .thenThrow(new RuntimeException("database unavailable"));

        var report = check.check();

        assertThat(report).isEmpty();
        assertThatNoException().isThrownBy(check::onApplicationReady);
    }
}
