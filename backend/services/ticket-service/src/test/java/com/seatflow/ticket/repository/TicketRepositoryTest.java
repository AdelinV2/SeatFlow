package com.seatflow.ticket.repository;

import com.seatflow.ticket.model.entity.Ticket;
import com.seatflow.ticket.model.enums.TicketStatus;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class TicketRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_ticket_test")
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
    private TicketRepository ticketRepository;

    private Ticket sampleTicket(UUID eventId, UUID seatId, String email, UUID userId, String ticketCode, String paymentId) {
        return Ticket.builder()
                .reservationId(UUID.randomUUID())
                .paymentId(UUID.fromString(paymentId))
                .userId(userId)
                .customerEmail(email)
                .attendeeName("Jane Doe")
                .eventId(eventId)
                .seatId(seatId)
                .price(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("10.00"))
                .netAmount(new BigDecimal("90.00"))
                .ticketCode(ticketCode)
                .qrCodeData("data:image/png;base64,abc")
                .status(TicketStatus.VALID)
                .build();
    }

    @Test
    void shouldSaveTicketWithNullableUserId() {
        Ticket saved = ticketRepository.saveAndFlush(
                sampleTicket(UUID.randomUUID(), UUID.randomUUID(), "guest@example.com", null, "CODE-1", "11111111-1111-1111-1111-111111111111"));

        Ticket found = ticketRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getUserId()).isNull();
        assertThat(found.getCustomerEmail()).isEqualTo("guest@example.com");
        assertThat(found.getStatus()).isEqualTo(TicketStatus.VALID);
    }

    @Test
    void shouldUpdateUserIdForGuestCustomerEmail() {
        UUID event = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        ticketRepository.saveAndFlush(
                sampleTicket(event, seat, "claimer@example.com", null, "CODE-2", "22222222-2222-2222-2222-222222222222"));
        ticketRepository.saveAndFlush(
                sampleTicket(event, UUID.randomUUID(), "claimer@example.com", UUID.randomUUID(), "CODE-3", "33333333-3333-3333-3333-333333333333"));
        ticketRepository.saveAndFlush(
                sampleTicket(event, UUID.randomUUID(), "other@example.com", null, "CODE-4", "44444444-4444-4444-4444-444444444444"));

        UUID newUserId = UUID.randomUUID();
        int updated = ticketRepository.updateUserIdByCustomerEmailAndUserIdIsNull(newUserId, "claimer@example.com");

        assertThat(updated).isEqualTo(1);
        // Guest ticket (userId was null) is now linked to the registered user.
        Ticket claimedGuest = ticketRepository.findByTicketCode("CODE-2").orElseThrow();
        assertThat(claimedGuest.getUserId()).isEqualTo(newUserId);
        // Already-registered ticket with same email must remain untouched.
        Ticket registered = ticketRepository.findByTicketCode("CODE-3").orElseThrow();
        assertThat(registered.getUserId()).isNotNull().isNotEqualTo(newUserId);
        // Unrelated email remains a guest.
        Ticket other = ticketRepository.findByTicketCode("CODE-4").orElseThrow();
        assertThat(other.getUserId()).isNull();
    }

    @Test
    void shouldFindByTicketCode() {
        Ticket saved = ticketRepository.saveAndFlush(
                sampleTicket(UUID.randomUUID(), UUID.randomUUID(), "buyer@example.com", UUID.randomUUID(), "CODE-5", "55555555-5555-5555-5555-555555555555"));

        Optional<Ticket> found = ticketRepository.findByTicketCode("CODE-5");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void shouldFindByUserIdOrderByCreatedAtDesc() {
        UUID userId = UUID.randomUUID();
        ticketRepository.saveAndFlush(
                sampleTicket(UUID.randomUUID(), UUID.randomUUID(), "u@example.com", userId, "CODE-6", "66666666-6666-6666-6666-666666666666"));
        ticketRepository.saveAndFlush(
                sampleTicket(UUID.randomUUID(), UUID.randomUUID(), "u@example.com", userId, "CODE-7", "77777777-7777-7777-7777-777777777777"));

        assertThat(ticketRepository.findByUserIdOrderByCreatedAtDesc(userId, org.springframework.data.domain.Pageable.ofSize(10)).getTotalElements())
                .isEqualTo(2);
    }

    @Test
    void shouldExistByPaymentId() {
        UUID paymentId = UUID.fromString("88888888-8888-8888-8888-888888888888");
        ticketRepository.saveAndFlush(
                sampleTicket(UUID.randomUUID(), UUID.randomUUID(), "p@example.com", null, "CODE-8", paymentId.toString()));

        assertThat(ticketRepository.existsByPaymentId(paymentId)).isTrue();
    }

    @Test
    void shouldRejectDuplicateValidTicketForSameSeat() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        ticketRepository.saveAndFlush(
                sampleTicket(eventId, seatId, "dup@example.com", null, "CODE-9", "99999999-9999-9999-9999-999999999999"));

        Ticket duplicate = sampleTicket(eventId, seatId, "dup2@example.com", null, "CODE-10", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        assertThatThrownBy(() -> ticketRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
