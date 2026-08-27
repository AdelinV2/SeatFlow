package com.seatflow.payment.mapper;

import com.seatflow.payment.model.entity.Payment;
import com.seatflow.payment.model.enums.PaymentStatus;
import com.seatflow.payment.web.dto.response.PaymentIntentResponse;
import com.seatflow.payment.web.dto.response.PaymentResponse;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMapperTest {

    private final PaymentMapper mapper = Mappers.getMapper(PaymentMapper.class);

    private Payment payment() {
        return Payment.builder()
                .id(UUID.randomUUID())
                .reservationId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .customerEmail("buyer@example.com")
                .eventId(UUID.randomUUID())
                .stripePaymentIntentId("pi_123")
                .idempotencyKey("idem-1")
                .amount(new BigDecimal("99.99"))
                .currency("USD")
                .status(PaymentStatus.SUCCESS)
                .failureReason(null)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void shouldMapToPaymentResponse() {
        Payment payment = payment();
        PaymentResponse response = mapper.toResponse(payment);

        assertThat(response.id()).isEqualTo(payment.getId());
        assertThat(response.reservationId()).isEqualTo(payment.getReservationId());
        assertThat(response.userId()).isEqualTo(payment.getUserId());
        assertThat(response.customerEmail()).isEqualTo(payment.getCustomerEmail());
        assertThat(response.eventId()).isEqualTo(payment.getEventId());
        assertThat(response.stripePaymentIntentId()).isEqualTo(payment.getStripePaymentIntentId());
        assertThat(response.amount()).isEqualTo(payment.getAmount());
        assertThat(response.currency()).isEqualTo(payment.getCurrency());
        assertThat(response.status()).isEqualTo(payment.getStatus());
        assertThat(response.failureReason()).isNull();
        assertThat(response.createdAt()).isEqualTo(payment.getCreatedAt());
        assertThat(response.updatedAt()).isEqualTo(payment.getUpdatedAt());
    }

    @Test
    void shouldMapToPaymentIntentResponse() {
        Payment payment = payment();
        PaymentIntentResponse response = mapper.toIntentResponse(payment, "secret_xyz");

        assertThat(response.paymentId()).isEqualTo(payment.getId());
        assertThat(response.clientSecret()).isEqualTo("secret_xyz");
        assertThat(response.amount()).isEqualTo(payment.getAmount());
        assertThat(response.currency()).isEqualTo(payment.getCurrency());
        assertThat(response.status()).isEqualTo(payment.getStatus());
    }
}
