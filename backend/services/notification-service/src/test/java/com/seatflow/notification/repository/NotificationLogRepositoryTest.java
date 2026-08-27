package com.seatflow.notification.repository;

import com.seatflow.notification.model.entity.NotificationLog;
import com.seatflow.notification.model.enums.NotificationStatus;
import com.seatflow.notification.model.enums.NotificationTemplateType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class NotificationLogRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_notification_repo_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Test
    @DisplayName("Should persist and retrieve NotificationLog by ID")
    void shouldPersistAndRetrieveNotificationLog() {
        NotificationLog log = NotificationLog.builder()
                .recipientEmail("user@example.com")
                .templateType(NotificationTemplateType.TICKET_ISSUED)
                .subject("Your Ticket Confirmation")
                .idempotencyKey("ticket-issued-" + UUID.randomUUID())
                .renderedContent("<html><body>Ticket 123</body></html>")
                .status(NotificationStatus.SENT)
                .sentAt(Instant.now())
                .retryCount(0)
                .build();

        NotificationLog saved = notificationLogRepository.saveAndFlush(log);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Optional<NotificationLog> found = notificationLogRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getRecipientEmail()).isEqualTo("user@example.com");
        assertThat(found.get().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(found.get().getRenderedContent()).isEqualTo("<html><body>Ticket 123</body></html>");
    }

    @Test
    @DisplayName("Should check idempotency key existence and find by key")
    void shouldCheckIdempotencyKey() {
        String key = "payment-failed-" + UUID.randomUUID();
        NotificationLog log = NotificationLog.builder()
                .recipientEmail("customer@example.com")
                .templateType(NotificationTemplateType.PAYMENT_FAILED)
                .subject("Payment Failed")
                .idempotencyKey(key)
                .status(NotificationStatus.SENT)
                .sentAt(Instant.now())
                .build();

        notificationLogRepository.saveAndFlush(log);

        assertThat(notificationLogRepository.existsByIdempotencyKey(key)).isTrue();
        assertThat(notificationLogRepository.existsByIdempotencyKey("non-existent-key")).isFalse();

        Optional<NotificationLog> found = notificationLogRepository.findByIdempotencyKey(key);
        assertThat(found).isPresent();
        assertThat(found.get().getIdempotencyKey()).isEqualTo(key);
    }

    @Test
    @DisplayName("Should reject duplicate idempotency key via unique constraint")
    void shouldRejectDuplicateIdempotencyKey() {
        String key = "unique-key-" + UUID.randomUUID();
        NotificationLog log1 = NotificationLog.builder()
                .recipientEmail("user1@example.com")
                .templateType(NotificationTemplateType.RESERVATION_HELD)
                .subject("Hold 1")
                .idempotencyKey(key)
                .status(NotificationStatus.SENT)
                .build();
        notificationLogRepository.saveAndFlush(log1);

        NotificationLog log2 = NotificationLog.builder()
                .recipientEmail("user2@example.com")
                .templateType(NotificationTemplateType.RESERVATION_HELD)
                .subject("Hold 2")
                .idempotencyKey(key)
                .status(NotificationStatus.SENT)
                .build();

        assertThatThrownBy(() -> notificationLogRepository.saveAndFlush(log2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should paginate notifications by recipient email ordered by created_at desc")
    void shouldPaginateByRecipientEmail() {
        String email = "multi@example.com";
        for (int i = 0; i < 5; i++) {
            notificationLogRepository.save(NotificationLog.builder()
                    .recipientEmail(email)
                    .templateType(NotificationTemplateType.TICKET_ISSUED)
                    .subject("Ticket " + i)
                    .idempotencyKey("ticket-" + i + "-" + UUID.randomUUID())
                    .status(NotificationStatus.SENT)
                    .build());
        }
        notificationLogRepository.flush();

        Page<NotificationLog> page = notificationLogRepository.findByRecipientEmailOrderByCreatedAtDesc(
                email, PageRequest.of(0, 3));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).hasSize(3);
    }

    @Test
    @DisplayName("Should find failed notifications for retry using FOR UPDATE SKIP LOCKED")
    void shouldFindFailedNotificationsForRetry() {
        NotificationLog failed1 = NotificationLog.builder()
                .recipientEmail("fail1@example.com")
                .templateType(NotificationTemplateType.TICKET_ISSUED)
                .subject("Failed Ticket 1")
                .idempotencyKey("fail-1-" + UUID.randomUUID())
                .status(NotificationStatus.FAILED)
                .errorMessage("Timeout")
                .retryCount(0)
                .build();

        NotificationLog failed2 = NotificationLog.builder()
                .recipientEmail("fail2@example.com")
                .templateType(NotificationTemplateType.PAYMENT_FAILED)
                .subject("Failed Payment")
                .idempotencyKey("fail-2-" + UUID.randomUUID())
                .status(NotificationStatus.FAILED)
                .errorMessage("Connection refused")
                .retryCount(1)
                .build();

        NotificationLog exhausted = NotificationLog.builder()
                .recipientEmail("fail3@example.com")
                .templateType(NotificationTemplateType.RESERVATION_HELD)
                .subject("Max retries exceeded")
                .idempotencyKey("fail-3-" + UUID.randomUUID())
                .status(NotificationStatus.FAILED)
                .errorMessage("Persistent error")
                .retryCount(3)
                .build();

        NotificationLog success = NotificationLog.builder()
                .recipientEmail("success@example.com")
                .templateType(NotificationTemplateType.TICKET_ISSUED)
                .subject("Success Ticket")
                .idempotencyKey("success-" + UUID.randomUUID())
                .status(NotificationStatus.SENT)
                .retryCount(0)
                .build();

        notificationLogRepository.saveAllAndFlush(List.of(failed1, failed2, exhausted, success));

        List<NotificationLog> retryCandidates = notificationLogRepository.findFailedNotificationsForRetry(3, 10);

        assertThat(retryCandidates).extracting(NotificationLog::getIdempotencyKey)
                .contains(failed1.getIdempotencyKey(), failed2.getIdempotencyKey())
                .doesNotContain(exhausted.getIdempotencyKey(), success.getIdempotencyKey());
    }

    @Test
    @DisplayName("Should enforce email check constraint on invalid email format")
    void shouldRejectInvalidEmailFormat() {
        NotificationLog invalidLog = NotificationLog.builder()
                .recipientEmail("not-an-email")
                .templateType(NotificationTemplateType.TICKET_ISSUED)
                .subject("Invalid Email Test")
                .idempotencyKey("invalid-email-" + UUID.randomUUID())
                .status(NotificationStatus.PENDING)
                .build();

        assertThatThrownBy(() -> notificationLogRepository.saveAndFlush(invalidLog))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
