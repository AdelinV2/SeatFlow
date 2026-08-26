package com.seatflow.reservation.mapper;

import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import com.seatflow.reservation.web.dto.response.ReservationResponse;
import com.seatflow.reservation.web.dto.response.SeatHoldResponse;
import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ReservationMapperTest.MapperConfig.class)
class ReservationMapperTest {

    @Configuration
    @ComponentScan("com.seatflow.reservation.mapper")
    static class MapperConfig {
    }

    @Autowired
    private ReservationMapper mapper;

    @Test
    void toEntityShouldMapRequestAndApplyDefaults() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        CreateReservationRequest request = new CreateReservationRequest(
                eventId,
                "guest@example.com",
                java.util.List.of(UUID.randomUUID(), UUID.randomUUID()),
                java.util.List.of(new BigDecimal("40.00"), new BigDecimal("60.00")),
                "idem-1"
        );

        Reservation entity = mapper.toEntity(request, userId);

        assertThat(entity.getEventId()).isEqualTo(eventId);
        assertThat(entity.getCustomerEmail()).isEqualTo("guest@example.com");
        assertThat(entity.getUserId()).isEqualTo(userId);
        assertThat(entity.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(entity.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(entity.getSeatCount()).isEqualTo(2);
        assertThat(entity.getTotalAmount()).isNull();
        assertThat(entity.getExpiresAt()).isNull();
        assertThat(entity.getSeatHolds()).isEmpty();
        assertThat(entity.getId()).isNull();
    }

    @Test
    void toResponseShouldMapEntityAndSeatHolds() {
        UUID reservationId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID seatHoldId = UUID.randomUUID();
        SeatHold hold = SeatHold.builder()
                .id(seatHoldId)
                .eventId(reservationId)
                .seatId(seatId)
                .status(SeatHoldStatus.HELD)
                .price(new BigDecimal("25.00"))
                .build();
        Reservation reservation = Reservation.builder()
                .id(reservationId)
                .eventId(UUID.randomUUID())
                .customerEmail("guest@example.com")
                .status(ReservationStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(900))
                .idempotencyKey("idem-2")
                .totalAmount(new BigDecimal("25.00"))
                .seatCount(1)
                .seatHolds(Set.of(hold))
                .build();

        ReservationResponse response = mapper.toResponse(reservation);

        assertThat(response.id()).isEqualTo(reservationId);
        assertThat(response.customerEmail()).isEqualTo("guest@example.com");
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(response.seatCount()).isEqualTo(1);
        assertThat(response.seats()).hasSize(1);
        SeatHoldResponse seat = response.seats().getFirst();
        assertThat(seat.id()).isEqualTo(seatHoldId);
        assertThat(seat.seatId()).isEqualTo(seatId);
        assertThat(seat.status()).isEqualTo(SeatHoldStatus.HELD);
        assertThat(seat.price()).isEqualByComparingTo("25.00");
    }
}
