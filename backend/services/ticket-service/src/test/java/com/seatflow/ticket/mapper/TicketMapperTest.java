package com.seatflow.ticket.mapper;

import com.seatflow.ticket.model.entity.Ticket;
import com.seatflow.ticket.model.enums.TicketStatus;
import com.seatflow.ticket.web.dto.response.TicketDetailResponse;
import com.seatflow.ticket.web.dto.response.TicketResponse;
import com.seatflow.ticket.web.dto.response.TicketSummaryResponse;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TicketMapperTest {

    private final TicketMapper mapper = Mappers.getMapper(TicketMapper.class);

    private Ticket sampleTicket() {
        return Ticket.builder()
                .id(UUID.randomUUID())
                .reservationId(UUID.randomUUID())
                .paymentId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .customerEmail("buyer@example.com")
                .attendeeName("Jane Doe")
                .eventId(UUID.randomUUID())
                .seatId(UUID.randomUUID())
                .price(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("10.00"))
                .netAmount(new BigDecimal("90.00"))
                .ticketCode("CODE-XYZ")
                .qrCodeData("data:image/png;base64,abc")
                .status(TicketStatus.VALID)
                .version(0L)
                .createdAt(Instant.parse("2026-01-01T10:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T10:05:00Z"))
                .build();
    }

    @Test
    void shouldMapToResponse() {
        Ticket ticket = sampleTicket();

        TicketResponse response = mapper.toResponse(ticket);

        assertThat(response.id()).isEqualTo(ticket.getId());
        assertThat(response.reservationId()).isEqualTo(ticket.getReservationId());
        assertThat(response.paymentId()).isEqualTo(ticket.getPaymentId());
        assertThat(response.userId()).isEqualTo(ticket.getUserId());
        assertThat(response.customerEmail()).isEqualTo(ticket.getCustomerEmail());
        assertThat(response.attendeeName()).isEqualTo(ticket.getAttendeeName());
        assertThat(response.eventId()).isEqualTo(ticket.getEventId());
        assertThat(response.seatId()).isEqualTo(ticket.getSeatId());
        assertThat(response.price()).isEqualTo(ticket.getPrice());
        assertThat(response.taxAmount()).isEqualTo(ticket.getTaxAmount());
        assertThat(response.netAmount()).isEqualTo(ticket.getNetAmount());
        assertThat(response.ticketCode()).isEqualTo(ticket.getTicketCode());
        assertThat(response.status()).isEqualTo(ticket.getStatus());
        assertThat(response.createdAt()).isEqualTo(ticket.getCreatedAt());
    }

    @Test
    void shouldMapToDetailResponseIncludingQrAndTimestamps() {
        Ticket ticket = sampleTicket();

        TicketDetailResponse response = mapper.toDetailResponse(ticket);

        assertThat(response.qrCodeData()).isEqualTo(ticket.getQrCodeData());
        assertThat(response.updatedAt()).isEqualTo(ticket.getUpdatedAt());
        assertThat(response.ticketCode()).isEqualTo(ticket.getTicketCode());
    }

    @Test
    void shouldMapToSummaryResponse() {
        Ticket ticket = sampleTicket();

        TicketSummaryResponse response = mapper.toSummaryResponse(ticket);

        assertThat(response.id()).isEqualTo(ticket.getId());
        assertThat(response.ticketCode()).isEqualTo(ticket.getTicketCode());
        assertThat(response.eventId()).isEqualTo(ticket.getEventId());
        assertThat(response.seatId()).isEqualTo(ticket.getSeatId());
        assertThat(response.status()).isEqualTo(ticket.getStatus());
        assertThat(response.price()).isEqualTo(ticket.getPrice());
        assertThat(response.createdAt()).isEqualTo(ticket.getCreatedAt());
    }

    @Test
    void shouldMapToList() {
        Ticket ticket = sampleTicket();

        List<TicketResponse> responses = mapper.toResponseList(List.of(ticket));

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().id()).isEqualTo(ticket.getId());
    }
}
