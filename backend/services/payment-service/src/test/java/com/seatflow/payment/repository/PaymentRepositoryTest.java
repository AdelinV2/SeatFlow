package com.seatflow.payment.repository;

import com.seatflow.payment.model.entity.Payment;
import com.seatflow.payment.model.enums.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class PaymentRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_payment_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private PaymentRepository paymentRepository;

    private Payment payment(UUID reservationId, String idempotencyKey, String email, UUID userId, String stripeIntentId) {
        return Payment.builder()
                .reservationId(reservationId)
                .customerEmail(email)
                .eventId(UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .amount(new BigDecimal("120.00"))
                .currency("USD")
                .status(PaymentStatus.INITIATED)
                .userId(userId)
                .stripePaymentIntentId(stripeIntentId)
                .build();
    }

    @Test
    void shouldFindByIdempotencyKey() {
        Payment saved = paymentRepository.saveAndFlush(
                payment(UUID.randomUUID(), "idem-1", "buyer@example.com", null, null));

        Optional<Payment> found = paymentRepository.findByIdempotencyKey("idem-1");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void shouldRejectDuplicateIdempotencyKey() {
        paymentRepository.saveAndFlush(
                payment(UUID.randomUUID(), "idem-dup", "buyer@example.com", null, null));
        Payment duplicate = payment(UUID.randomUUID(), "idem-dup", "buyer@example.com", null, null);

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectDuplicateReservationId() {
        UUID reservationId = UUID.randomUUID();
        paymentRepository.saveAndFlush(
                payment(reservationId, "idem-res-1", "buyer@example.com", null, null));
        Payment duplicate = payment(reservationId, "idem-res-2", "buyer@example.com", null, null);

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldFindByStripePaymentIntentId() {
        Payment saved = paymentRepository.saveAndFlush(
                payment(UUID.randomUUID(), "idem-pi-1", "buyer@example.com", null, "pi_abc123"));

        Optional<Payment> found = paymentRepository.findByStripePaymentIntentId("pi_abc123");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void shouldRejectDuplicateStripePaymentIntentId() {
        paymentRepository.saveAndFlush(
                payment(UUID.randomUUID(), "idem-pi-1", "buyer@example.com", null, "pi_abc123"));
        Payment conflict = payment(UUID.randomUUID(), "idem-pi-2", "buyer@example.com", null, "pi_abc123");

        // Same non-null Stripe intent id must violate the partial unique index uq_payments_stripe_intent
        assertThatThrownBy(() -> paymentRepository.saveAndFlush(conflict))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldAllowMultipleNullStripePaymentIntentIds() {
        // The partial unique index uq_payments_stripe_intent only covers non-null values,
        // so multiple NULL Stripe intent ids must NOT conflict.
        paymentRepository.saveAndFlush(
                payment(UUID.randomUUID(), "idem-pi-3", "a@example.com", null, null));
        paymentRepository.saveAndFlush(
                payment(UUID.randomUUID(), "idem-pi-4", "b@example.com", null, null));

        assertThat(paymentRepository.count()).isEqualTo(2);
    }

    @Test
    void shouldUpdateUserIdForGuestCustomerEmail() {
        paymentRepository.saveAndFlush(
                payment(UUID.randomUUID(), "idem-guest-1", "guest@example.com", null, null));
        paymentRepository.saveAndFlush(
                payment(UUID.randomUUID(), "idem-guest-2", "guest@example.com", UUID.randomUUID(), null));
        paymentRepository.saveAndFlush(
                payment(UUID.randomUUID(), "idem-other", "other@example.com", null, null));

        UUID newUserId = UUID.randomUUID();
        int updated = paymentRepository.updateUserIdForCustomerEmail(newUserId, "guest@example.com", Instant.now());

        assertThat(updated).isEqualTo(1);
        Payment guest = paymentRepository.findByIdempotencyKey("idem-guest-1").orElseThrow();
        assertThat(guest.getUserId()).isEqualTo(newUserId);
        Payment registered = paymentRepository.findByIdempotencyKey("idem-guest-2").orElseThrow();
        assertThat(registered.getUserId()).isNotNull();
        Payment other = paymentRepository.findByIdempotencyKey("idem-other").orElseThrow();
        assertThat(other.getUserId()).isNull();
    }
}
