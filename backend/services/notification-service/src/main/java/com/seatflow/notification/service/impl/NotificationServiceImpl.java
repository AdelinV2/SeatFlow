package com.seatflow.notification.service.impl;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.notification.client.TicketServiceClient;
import com.seatflow.notification.mapper.NotificationMapper;
import com.seatflow.notification.messaging.event.PaymentFailedEvent;
import com.seatflow.notification.messaging.event.ReservationHeldEvent;
import com.seatflow.notification.messaging.event.TicketIssuedEvent;
import com.seatflow.notification.model.entity.NotificationLog;
import com.seatflow.notification.model.enums.NotificationStatus;
import com.seatflow.notification.model.enums.NotificationTemplateType;
import com.seatflow.notification.repository.NotificationLogRepository;
import com.seatflow.notification.service.EmailService;
import com.seatflow.notification.service.EmailTemplateRenderer;
import com.seatflow.notification.service.NotificationService;
import com.seatflow.notification.web.dto.common.EmailAttachmentDto;
import com.seatflow.notification.web.dto.response.NotificationLogResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneId.of("UTC"));

    private final NotificationLogRepository notificationLogRepository;
    private final NotificationMapper notificationMapper;
    private final EmailService emailService;
    private final EmailTemplateRenderer emailTemplateRenderer;
    private final TicketServiceClient ticketServiceClient;
    private final MeterRegistry meterRegistry;

    @Override
    public void sendTicketIssuedNotification(TicketIssuedEvent event) {
        String idempotencyKey = "ticket-issued-" + event.ticketId();

        if (notificationLogRepository.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Skipping duplicate TicketIssued notification: ticketId={}, idempotencyKey={}",
                    event.ticketId(), idempotencyKey);
            return;
        }

        log.info("Processing TicketIssued notification: ticketId={}, recipient={}, ticketCode={}",
                event.ticketId(), event.customerEmail(), event.ticketCode());

        // Fetch PDF attachment from ticket-service (Eureka + LoadBalancer)
        List<EmailAttachmentDto> attachments = new ArrayList<>();
        try {
            byte[] pdfBytes = ticketServiceClient.fetchTicketPdf(event.ticketId());
            if (pdfBytes != null && pdfBytes.length > 0) {
                attachments.add(new EmailAttachmentDto(
                        "ticket-" + event.ticketCode() + ".pdf",
                        "application/pdf",
                        pdfBytes
                ));
            }
        } catch (Exception ex) {
            log.warn("Could not fetch ticket PDF for ticketId={}: {}. Proceeding with email confirmation without attachment.",
                    event.ticketId(), ex.getMessage());
        }

        Map<String, Object> variables = new HashMap<>();
        variables.put("attendeeName", event.attendeeName() != null ? event.attendeeName() : "Valued Customer");
        variables.put("ticketCode", event.ticketCode());
        variables.put("ticketId", event.ticketId() != null ? event.ticketId().toString() : "");
        variables.put("netAmount", event.netAmount());
        variables.put("taxAmount", event.taxAmount());
        variables.put("totalAmount", event.price());

        String subject = "Your SeatFlow Ticket Confirmation — " + event.ticketCode();

        dispatchAndRecordLog(
                event.customerEmail(),
                subject,
                NotificationTemplateType.TICKET_ISSUED,
                idempotencyKey,
                variables,
                attachments
        );
    }

    @Override
    public void sendPaymentFailedNotification(PaymentFailedEvent event) {
        String idempotencyKey = "payment-failed-" + event.paymentId();

        if (notificationLogRepository.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Skipping duplicate PaymentFailed notification: paymentId={}, idempotencyKey={}",
                    event.paymentId(), idempotencyKey);
            return;
        }

        log.info("Processing PaymentFailed notification: paymentId={}, reservationId={}, recipient={}",
                event.paymentId(), event.reservationId(), event.customerEmail());

        Map<String, Object> variables = new HashMap<>();
        variables.put("customerEmail", event.customerEmail());
        variables.put("reservationId", event.reservationId() != null ? event.reservationId().toString() : "");
        variables.put("amount", event.amount());
        variables.put("currency", event.currency() != null ? event.currency() : "USD");
        variables.put("failureReason", event.failureReason() != null ? event.failureReason() : "Payment was declined by issuing bank.");
        variables.put("stripePaymentId", event.stripePaymentId());

        String subject = "Action Required: SeatFlow Payment Unsuccessful";

        dispatchAndRecordLog(
                event.customerEmail(),
                subject,
                NotificationTemplateType.PAYMENT_FAILED,
                idempotencyKey,
                variables,
                List.of()
        );
    }

    @Override
    public void sendReservationHeldNotification(ReservationHeldEvent event) {
        String idempotencyKey = "reservation-held-" + event.reservationId();

        if (notificationLogRepository.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Skipping duplicate ReservationHeld notification: reservationId={}, idempotencyKey={}",
                    event.reservationId(), idempotencyKey);
            return;
        }

        log.info("Processing ReservationHeld notification: reservationId={}, recipient={}, seatCount={}",
                event.reservationId(), event.customerEmail(), event.seatIds() != null ? event.seatIds().size() : 0);

        String formattedExpiresAt = event.expiresAt() != null ? ISO_FORMATTER.format(event.expiresAt()) : "15 minutes";

        Map<String, Object> variables = new HashMap<>();
        variables.put("customerEmail", event.customerEmail());
        variables.put("reservationId", event.reservationId() != null ? event.reservationId().toString() : "");
        variables.put("seatCount", event.seatIds() != null ? event.seatIds().size() : 1);
        variables.put("totalAmount", event.totalAmount());
        variables.put("formattedExpiresAt", formattedExpiresAt);

        String subject = "Your SeatFlow Seats are on Hold (15-Minute Expiration)";

        dispatchAndRecordLog(
                event.customerEmail(),
                subject,
                NotificationTemplateType.RESERVATION_HELD,
                idempotencyKey,
                variables,
                List.of()
        );
    }

    private void dispatchAndRecordLog(
            String recipientEmail,
            String subject,
            NotificationTemplateType templateType,
            String idempotencyKey,
            Map<String, Object> variables,
            List<EmailAttachmentDto> attachments
    ) {
        String htmlContent = null;
        try {
            htmlContent = emailTemplateRenderer.renderTemplate(templateType, variables);
        } catch (Exception ex) {
            log.error("Failed to render email template for templateType={}, idempotencyKey={}: {}",
                    templateType, idempotencyKey, ex.getMessage(), ex);
        }

        NotificationLog notificationLog = NotificationLog.builder()
                .recipientEmail(recipientEmail)
                .templateType(templateType)
                .subject(subject)
                .idempotencyKey(idempotencyKey)
                .renderedContent(htmlContent)
                .status(NotificationStatus.PENDING)
                .retryCount(0)
                .build();

        try {
            if (htmlContent == null) {
                throw new IllegalStateException("Email template rendering failed for " + templateType);
            }
            emailService.sendEmail(recipientEmail, subject, htmlContent, attachments);

            notificationLog.setStatus(NotificationStatus.SENT);
            notificationLog.setSentAt(Instant.now());
            notificationLog.setErrorMessage(null);

            meterRegistry.counter(
                    "seatflow.notifications.sent.total",
                    "templateType", templateType.name(),
                    "status", "SUCCESS"
            ).increment();

            log.info("Notification successfully delivered: recipient={}, templateType={}, idempotencyKey={}",
                    recipientEmail, templateType, idempotencyKey);
        } catch (Exception ex) {
            notificationLog.setStatus(NotificationStatus.FAILED);
            notificationLog.setErrorMessage(ex.getMessage());

            meterRegistry.counter(
                    "seatflow.notifications.failed.total",
                    "templateType", templateType.name(),
                    "reason", ex.getClass().getSimpleName()
            ).increment();

            log.error("Failed to deliver notification: recipient={}, templateType={}, idempotencyKey={}: {}",
                    recipientEmail, templateType, idempotencyKey, ex.getMessage(), ex);
        }

        notificationLogRepository.save(notificationLog);
    }

    @Override
    @Transactional
    public void processFailedNotificationRetry(NotificationLog logEntry) {
        log.info("Retrying failed notification dispatch: id={}, recipient={}, templateType={}, retryCount={}",
                logEntry.getId(), logEntry.getRecipientEmail(), logEntry.getTemplateType(), logEntry.getRetryCount());

        try {
            String htmlContent = logEntry.getRenderedContent();
            if (htmlContent == null || htmlContent.isBlank()) {
                Map<String, Object> variables = Map.of(
                        "customerEmail", logEntry.getRecipientEmail(),
                        "attendeeName", "Valued Customer"
                );
                htmlContent = emailTemplateRenderer.renderTemplate(logEntry.getTemplateType(), variables);
                logEntry.setRenderedContent(htmlContent);
            }

            emailService.sendEmail(logEntry.getRecipientEmail(), logEntry.getSubject(), htmlContent, List.of());

            logEntry.setStatus(NotificationStatus.SENT);
            logEntry.setSentAt(Instant.now());
            logEntry.setErrorMessage(null);

            meterRegistry.counter(
                    "seatflow.notifications.sent.total",
                    "templateType", logEntry.getTemplateType().name(),
                    "status", "RETRY_SUCCESS"
            ).increment();

            log.info("Retry succeeded for notification: id={}", logEntry.getId());
        } catch (Exception ex) {
            logEntry.setRetryCount(logEntry.getRetryCount() + 1);
            logEntry.setErrorMessage(ex.getMessage());

            meterRegistry.counter(
                    "seatflow.notifications.failed.total",
                    "templateType", logEntry.getTemplateType().name(),
                    "reason", "RETRY_FAILURE"
            ).increment();

            log.warn("Retry attempt failed for notification: id={}, newRetryCount={}: {}",
                    logEntry.getId(), logEntry.getRetryCount(), ex.getMessage());
        }

        notificationLogRepository.save(logEntry);
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationLogResponse getNotificationById(UUID id) {
        NotificationLog logEntry = notificationLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification record not found with id: " + id));
        return notificationMapper.toResponse(logEntry);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResult<NotificationLogResponse> getNotifications(
            String recipientEmail, NotificationStatus status, Pageable pageable) {

        Page<NotificationLog> page;
        if (recipientEmail != null && !recipientEmail.isBlank() && status != null) {
            page = notificationLogRepository.findByRecipientEmailAndStatusOrderByCreatedAtDesc(
                    recipientEmail.trim(), status, pageable);
        } else if (recipientEmail != null && !recipientEmail.isBlank()) {
            page = notificationLogRepository.findByRecipientEmailOrderByCreatedAtDesc(
                    recipientEmail.trim(), pageable);
        } else if (status != null) {
            page = notificationLogRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else {
            page = notificationLogRepository.findAll(pageable);
        }

        List<NotificationLogResponse> content = notificationMapper.toResponseList(page.getContent());
        return PagedResult.of(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()
        );
    }
}
