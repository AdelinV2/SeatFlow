package com.seatflow.ticket.repository;

import com.seatflow.ticket.model.entity.Ticket;
import com.seatflow.ticket.model.entity.TicketValidation;
import com.seatflow.ticket.model.enums.TicketStatus;
import com.seatflow.ticket.model.enums.ValidationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class TicketValidationRepositoryTest {

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

    @Autowired
    private TicketValidationRepository ticketValidationRepository;

    private UUID persistedTicketId() {
        Ticket ticket = Ticket.builder()
                .reservationId(UUID.randomUUID())
                .paymentId(UUID.randomUUID())
                .customerEmail("owner@example.com")
                .eventId(UUID.randomUUID())
                .seatId(UUID.randomUUID())
                .price(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("10.00"))
                .netAmount(new BigDecimal("90.00"))
                .ticketCode("OWNER-CODE")
                .qrCodeData("data:image/png;base64,abc")
                .status(TicketStatus.VALID)
                .build();
        return ticketRepository.saveAndFlush(ticket).getId();
    }

    private TicketValidation validation(UUID ticketId, String device, ValidationResult result) {
        return TicketValidation.builder()
                .ticketId(ticketId)
                .scannerDeviceId(device)
                .scanResult(result)
                .details("ok")
                .build();
    }

    @Test
    void shouldPersistValidationWithNullableTicketId() {
        TicketValidation saved = ticketValidationRepository.saveAndFlush(
                validation(null, "GATE-1", ValidationResult.INVALID));

        TicketValidation found = ticketValidationRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getTicketId()).isNull();
        assertThat(found.getScanResult()).isEqualTo(ValidationResult.INVALID);
    }

    @Test
    void shouldFindByTicketIdOrderedByScannedAtDesc() {
        UUID ticketId = persistedTicketId();
        ticketValidationRepository.saveAndFlush(validation(ticketId, "GATE-1", ValidationResult.SUCCESS));
        ticketValidationRepository.saveAndFlush(validation(ticketId, "GATE-1", ValidationResult.ALREADY_USED));

        List<TicketValidation> found = ticketValidationRepository.findByTicketIdOrderByScannedAtDesc(ticketId);

        assertThat(found).hasSize(2);
    }

    @Test
    void shouldFindByScannerDeviceIdOrderedByScannedAtDesc() {
        UUID ticketId = persistedTicketId();
        ticketValidationRepository.saveAndFlush(validation(ticketId, "GATE-A", ValidationResult.SUCCESS));
        ticketValidationRepository.saveAndFlush(validation(ticketId, "GATE-A", ValidationResult.SUCCESS));
        ticketValidationRepository.saveAndFlush(validation(ticketId, "GATE-B", ValidationResult.INVALID));

        List<TicketValidation> found = ticketValidationRepository.findByScannerDeviceIdOrderByScannedAtDesc(
                "GATE-A", PageRequest.of(0, 10));

        assertThat(found).hasSize(2);
    }
}
