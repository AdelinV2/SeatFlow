package com.seatflow.notification.service;

import com.seatflow.notification.client.TicketServiceClient;
import com.seatflow.notification.mapper.NotificationMapper;
import com.seatflow.notification.messaging.event.PaymentFailedEvent;
import com.seatflow.notification.messaging.event.ReservationHeldEvent;
import com.seatflow.notification.messaging.event.TicketIssuedEvent;
import com.seatflow.notification.model.entity.NotificationLog;
import com.seatflow.notification.model.enums.NotificationStatus;
import com.seatflow.notification.model.enums.NotificationTemplateType;
import com.seatflow.notification.repository.NotificationLogRepository;
import com.seatflow.notification.service.impl.NotificationServiceImpl;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationLogRepository notificationLogRepository;

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private EmailService emailService;

    @Mock
    private EmailTemplateRenderer emailTemplateRenderer;

    @Mock
    private TicketServiceClient ticketServiceClient;

    @Mock
    private QrCodeGeneratorService qrCodeGeneratorService;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Counter counter;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        lenient().when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
    }

    @Test
    @DisplayName("Should send TicketIssued notification and record SENT in database")
    void shouldSendTicketIssuedNotification() {
        UUID ticketId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        TicketIssuedEvent event = new TicketIssuedEvent(
                ticketId,
                reservationId,
                userId,
                "alice@example.com",
                "Alice Smith",
                eventId,
                seatId,
                new BigDecimal("100.00"),
                new BigDecimal("20.00"),
                new BigDecimal("80.00"),
                "SF-TKT-112233",
                "SF:QR:112233",
                Instant.now()
        );

        String expectedIdempotencyKey = "ticket-issued-" + ticketId;
        when(notificationLogRepository.existsByIdempotencyKey(expectedIdempotencyKey)).thenReturn(false);

        byte[] samplePdf = new byte[]{1, 2, 3, 4};
        when(ticketServiceClient.fetchTicketPdf(ticketId)).thenReturn(samplePdf);
        when(qrCodeGeneratorService.generateQrCodeBase64(eq("SF:QR:112233"), eq(200), eq(200)))
                .thenReturn("data:image/png;base64,sampleQrBase64");
        when(emailTemplateRenderer.renderTemplate(eq(NotificationTemplateType.TICKET_ISSUED), anyMap()))
                .thenReturn("<html>Ticket HTML</html>");
        when(emailService.sendEmail(eq("alice@example.com"), anyString(), anyString(), anyList()))
                .thenReturn("msg_12345");

        notificationService.sendTicketIssuedNotification(event);

        verify(notificationLogRepository).existsByIdempotencyKey(expectedIdempotencyKey);
        verify(ticketServiceClient).fetchTicketPdf(ticketId);
        verify(qrCodeGeneratorService).generateQrCodeBase64("SF:QR:112233", 200, 200);
        verify(emailTemplateRenderer).renderTemplate(eq(NotificationTemplateType.TICKET_ISSUED), anyMap());
        verify(emailService).sendEmail(eq("alice@example.com"), contains("SF-TKT-112233"), eq("<html>Ticket HTML</html>"), anyList());

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(logCaptor.capture());
        NotificationLog savedLog = logCaptor.getValue();

        assertThat(savedLog.getRecipientEmail()).isEqualTo("alice@example.com");
        assertThat(savedLog.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(savedLog.getIdempotencyKey()).isEqualTo(expectedIdempotencyKey);
        assertThat(savedLog.getRenderedContent()).isEqualTo("<html>Ticket HTML</html>");
        assertThat(savedLog.getSentAt()).isNotNull();
        assertThat(savedLog.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("Should retry failed notification using stored renderedContent")
    void shouldRetryFailedNotificationUsingStoredRenderedContent() {
        UUID logId = UUID.randomUUID();
        NotificationLog failedLog = NotificationLog.builder()
                .id(logId)
                .recipientEmail("retry@example.com")
                .templateType(NotificationTemplateType.TICKET_ISSUED)
                .subject("Your Ticket")
                .renderedContent("<html>Original Rendered Body</html>")
                .status(NotificationStatus.FAILED)
                .retryCount(1)
                .build();

        when(emailService.sendEmail(eq("retry@example.com"), eq("Your Ticket"), eq("<html>Original Rendered Body</html>"), anyList()))
                .thenReturn("msg_retry_123");

        notificationService.processFailedNotificationRetry(failedLog);

        verify(emailService).sendEmail(eq("retry@example.com"), eq("Your Ticket"), eq("<html>Original Rendered Body</html>"), eq(List.of()));
        verifyNoInteractions(emailTemplateRenderer);

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        NotificationLog updated = captor.getValue();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(updated.getSentAt()).isNotNull();
        assertThat(updated.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("Should retry failed notification with fallback template rendering when renderedContent is null")
    void shouldRetryFailedNotificationWithFallbackWhenRenderedContentIsNull() {
        UUID logId = UUID.randomUUID();
        NotificationLog failedLog = NotificationLog.builder()
                .id(logId)
                .recipientEmail("fallback@example.com")
                .templateType(NotificationTemplateType.PAYMENT_FAILED)
                .subject("Payment Failed")
                .renderedContent(null)
                .status(NotificationStatus.FAILED)
                .retryCount(0)
                .build();

        when(emailTemplateRenderer.renderTemplate(eq(NotificationTemplateType.PAYMENT_FAILED), anyMap()))
                .thenReturn("<html>Fallback HTML</html>");
        when(emailService.sendEmail(eq("fallback@example.com"), eq("Payment Failed"), eq("<html>Fallback HTML</html>"), anyList()))
                .thenReturn("msg_fallback_123");

        notificationService.processFailedNotificationRetry(failedLog);

        verify(emailTemplateRenderer).renderTemplate(eq(NotificationTemplateType.PAYMENT_FAILED), anyMap());
        verify(emailService).sendEmail(eq("fallback@example.com"), eq("Payment Failed"), eq("<html>Fallback HTML</html>"), eq(List.of()));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        NotificationLog updated = captor.getValue();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(updated.getRenderedContent()).isEqualTo("<html>Fallback HTML</html>");
    }

    @Test
    @DisplayName("Should skip TicketIssued notification if idempotency key already exists")
    void shouldSkipDuplicateTicketIssuedNotification() {
        UUID ticketId = UUID.randomUUID();
        TicketIssuedEvent event = new TicketIssuedEvent(
                ticketId, UUID.randomUUID(), UUID.randomUUID(),
                "bob@example.com", "Bob", UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("50.00"), new BigDecimal("10.00"), new BigDecimal("40.00"),
                "SF-TKT-9999", "QR", Instant.now()
        );

        when(notificationLogRepository.existsByIdempotencyKey("ticket-issued-" + ticketId)).thenReturn(true);

        notificationService.sendTicketIssuedNotification(event);

        verify(notificationLogRepository).existsByIdempotencyKey("ticket-issued-" + ticketId);
        verifyNoInteractions(ticketServiceClient);
        verifyNoInteractions(emailService);
        verify(notificationLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should send PaymentFailed notification and record FAILED in database when Resend fails")
    void shouldRecordFailedWhenResendFails() {
        UUID paymentId = UUID.randomUUID();
        PaymentFailedEvent event = new PaymentFailedEvent(
                paymentId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "carol@example.com",
                UUID.randomUUID(),
                new BigDecimal("75.00"),
                "USD",
                "pi_failed_123",
                "Card declined",
                Instant.now()
        );

        String expectedIdempotencyKey = "payment-failed-" + paymentId;
        when(notificationLogRepository.existsByIdempotencyKey(expectedIdempotencyKey)).thenReturn(false);
        when(emailTemplateRenderer.renderTemplate(eq(NotificationTemplateType.PAYMENT_FAILED), anyMap()))
                .thenReturn("<html>Failed HTML</html>");
        when(emailService.sendEmail(eq("carol@example.com"), anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("Resend connection timeout"));

        notificationService.sendPaymentFailedNotification(event);

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(logCaptor.capture());
        NotificationLog savedLog = logCaptor.getValue();

        assertThat(savedLog.getRecipientEmail()).isEqualTo("carol@example.com");
        assertThat(savedLog.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(savedLog.getErrorMessage()).contains("Resend connection timeout");
    }

    @Test
    @DisplayName("Should send ReservationHeld notification with 15-minute expiration info")
    void shouldSendReservationHeldNotification() {
        UUID resId = UUID.randomUUID();
        ReservationHeldEvent event = new ReservationHeldEvent(
                resId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "dave@example.com",
                List.of(UUID.randomUUID(), UUID.randomUUID()),
                Instant.now().plusSeconds(900),
                new BigDecimal("120.00"),
                Instant.now()
        );

        String expectedKey = "reservation-held-" + resId;
        when(notificationLogRepository.existsByIdempotencyKey(expectedKey)).thenReturn(false);
        when(emailTemplateRenderer.renderTemplate(eq(NotificationTemplateType.RESERVATION_HELD), anyMap()))
                .thenReturn("<html>Held HTML</html>");

        notificationService.sendReservationHeldNotification(event);

        verify(emailService).sendEmail(eq("dave@example.com"), contains("15-Minute"), eq("<html>Held HTML</html>"), eq(List.of()));
        verify(notificationLogRepository).save(argThat(log -> log.getStatus() == NotificationStatus.SENT));
    }
}
