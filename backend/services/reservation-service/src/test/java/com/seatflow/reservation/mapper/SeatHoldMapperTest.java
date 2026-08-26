package com.seatflow.reservation.mapper;

import com.seatflow.reservation.web.dto.response.SeatHoldResponse;
import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SeatHoldMapperTest.MapperConfig.class)
class SeatHoldMapperTest {

    @Configuration
    @ComponentScan("com.seatflow.reservation.mapper")
    static class MapperConfig {
    }

    @Autowired
    private SeatHoldMapper mapper;

    @Test
    void toResponseShouldMapAllFields() {
        SeatHold hold = SeatHold.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .seatId(UUID.randomUUID())
                .status(SeatHoldStatus.SOLD)
                .price(new BigDecimal("75.50"))
                .build();

        SeatHoldResponse response = mapper.toResponse(hold);

        assertThat(response.id()).isEqualTo(hold.getId());
        assertThat(response.seatId()).isEqualTo(hold.getSeatId());
        assertThat(response.status()).isEqualTo(SeatHoldStatus.SOLD);
        assertThat(response.price()).isEqualByComparingTo("75.50");
    }

    @Test
    void toResponseListShouldMapEachElement() {
        SeatHold first = SeatHold.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .seatId(UUID.randomUUID())
                .status(SeatHoldStatus.HELD)
                .price(new BigDecimal("10.00"))
                .build();
        SeatHold second = SeatHold.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .seatId(UUID.randomUUID())
                .status(SeatHoldStatus.RELEASED)
                .price(new BigDecimal("20.00"))
                .build();

        var responses = mapper.toResponseList(java.util.List.of(first, second));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).status()).isEqualTo(SeatHoldStatus.HELD);
        assertThat(responses.get(1).status()).isEqualTo(SeatHoldStatus.RELEASED);
    }
}
