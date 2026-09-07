package com.seatflow.ai.service;

import com.seatflow.ai.client.ReservationServiceClient;
import com.seatflow.ai.client.dto.ReservationServiceReservationDto;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.ai.service.impl.ReservationToolServiceImpl;
import com.seatflow.ai.tool.dto.GetReservationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ownership-safe lookup tests (TASK-P15-005 section 3; mandatory 1-2).
 */
@ExtendWith(MockitoExtension.class)
class ReservationToolServiceTest {

    @Mock
    private ReservationServiceClient client;

    private final AiRequestContext context =
            new AiRequestContext("bearer-user-jwt", "corr-1", "owner-1");

    private ReservationToolServiceImpl service() {
        return new ReservationToolServiceImpl(client);
    }

    private ReservationServiceReservationDto dto(UUID id) {
        return new ReservationServiceReservationDto(
                id, UUID.randomUUID(), UUID.randomUUID(), "PENDING",
                Instant.parse("2026-09-07T10:15:00Z"), new BigDecimal("30.00"), 2,
                Instant.parse("2026-09-10T19:00:00Z"), Instant.parse("2026-09-10T21:00:00Z"),
                null, Instant.parse("2026-09-07T10:00:00Z"),
                List.of(new ReservationServiceReservationDto.ReservationServiceSeatDto(
                        UUID.randomUUID(), "HELD", new BigDecimal("15.00"), "A", 1, null, "STD")));
    }

    @Test
    @DisplayName("1: getReservation propagates USER JWT and returns only authorized data")
    void propagatesJwtAndMapsCompact() {
        UUID id = UUID.randomUUID();
        when(client.getReservation(eq(id), any())).thenReturn(dto(id));

        var result = service().getReservation(new GetReservationRequest(id.toString()), context);

        ArgumentCaptor<AiRequestContext> captor = ArgumentCaptor.forClass(AiRequestContext.class);
        verify(client, times(1)).getReservation(eq(id), captor.capture());
        assertThat(captor.getValue().bearerToken()).isEqualTo("bearer-user-jwt");
        assertThat(captor.getValue().userId()).isEqualTo("owner-1");
        assertThat(result.reservationId()).isEqualTo(id);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-09-07T10:15:00Z"));
        assertThat(result.totalAmount()).isEqualTo(new BigDecimal("30.00"));
        assertThat(result.seats()).hasSize(1);
        assertThat(result.sessionStartsAt()).isEqualTo(Instant.parse("2026-09-10T19:00:00Z"));
        // Customer-safe: the record type has no email/name/payment/internal fields by construction.
        assertThat(result.toString()).doesNotContain("owner-1@", "guest-proof", "payment");
    }

    @Test
    @DisplayName("2: 403/404 never retries with a privileged identity (single call)")
    void forbiddenNeverRetriesPrivileged() {
        UUID id = UUID.randomUUID();
        when(client.getReservation(eq(id), any()))
                .thenThrow(new AiToolException(AiToolError.FORBIDDEN, "forbidden"));

        assertThatThrownBy(() -> service().getReservation(new GetReservationRequest(id.toString()), context))
                .isInstanceOf(AiToolException.class)
                .matches(ex -> ((AiToolException) ex).getError() == AiToolError.FORBIDDEN);
        verify(client, times(1)).getReservation(eq(id), any());
        verify(client, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("invalid UUID is rejected before any downstream call")
    void invalidUuidRejected() {
        assertThatThrownBy(() -> service().getReservation(new GetReservationRequest("nope"), context))
                .isInstanceOf(AiToolException.class);
        verify(client, never()).getReservation(any(), any());
    }

    @Test
    @DisplayName("anonymous context cannot look up reservations")
    void anonymousRejected() {
        var anonymous = new AiRequestContext(null, "corr-1", null);
        assertThatThrownBy(() -> service()
                .getReservation(new GetReservationRequest(UUID.randomUUID().toString()), anonymous))
                .isInstanceOf(AiToolException.class);
        verify(client, never()).getReservation(any(), any());
    }
}
