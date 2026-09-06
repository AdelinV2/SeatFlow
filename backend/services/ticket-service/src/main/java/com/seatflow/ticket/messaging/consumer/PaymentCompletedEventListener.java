package com.seatflow.ticket.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.tracing.KafkaListenerTraceScope;
import com.seatflow.ticket.client.ReservationServiceClient;
import com.seatflow.ticket.client.dto.ReservationClientResponse;
import com.seatflow.ticket.messaging.event.PaymentCompletedEvent;
import com.seatflow.ticket.model.common.IssueTicketsCommand;
import com.seatflow.ticket.repository.TicketRepository;
import com.seatflow.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCompletedEventListener {

    private final TicketRepository ticketRepository;
    private final TicketService ticketService;
    private final ReservationServiceClient reservationServiceClient;
    private final ObjectMapper objectMapper;
    private final KafkaListenerTraceScope kafkaListenerTraceScope;

    @KafkaListener(
        topics = EventTopics.PAYMENT_EVENTS,
        groupId = "ticket-service-payment",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onPaymentCompleted(EventEnvelope<Object> envelope) {
        try (KafkaListenerTraceScope ignored = kafkaListenerTraceScope.open(envelope, EventTopics.PAYMENT_EVENTS)) {
            handlePaymentCompletedInternal(envelope);
        }
    }

    private void handlePaymentCompletedInternal(EventEnvelope<Object> envelope) {
        if (!"PaymentCompleted".equals(envelope.eventType())) {
            log.debug("Ignoring irrelevant payment event type: {}", envelope.eventType());
            return;
        }

        PaymentCompletedEvent payload = objectMapper.convertValue(envelope.payload(), PaymentCompletedEvent.class);
        UUID paymentId = payload.paymentId();

        log.info("Received PaymentCompleted event. paymentId={}, reservationId={}, eventSessionId={}, amount={}",
                paymentId, payload.reservationId(), payload.eventSessionId(), payload.amount());

        // 1. Strict Idempotency Check
        if (ticketRepository.existsByPaymentId(paymentId)) {
            log.info("Duplicate PaymentCompleted event skipped. Tickets already exist for paymentId={}", paymentId);
            return;
        }

        // 2. Session cutover guard (P12-004): a missing eventSessionId is malformed
        // after cutover. Reject/park the message (throw -> retry/DLT) and NEVER
        // infer the showing from eventId.
        if (payload.eventSessionId() == null) {
            log.warn("Rejecting PaymentCompleted without eventSessionId. paymentId={}, reservationId={}, eventId={}: parking for inspection, never inferring session from eventId",
                    paymentId, payload.reservationId(), payload.eventId());
            throw new IllegalStateException("PaymentCompleted missing required eventSessionId for payment: "
                    + paymentId);
        }

        // 3. Fetch reservation details from reservation-service
        ReservationClientResponse reservation = reservationServiceClient.getReservationById(payload.reservationId(), payload.customerEmail())
                .orElseThrow(() -> new IllegalStateException("Reservation not found for payment: " + payload.reservationId()));

        // 4. Session cross-check (P12-004): the payment event and the stored
        // reservation must agree on the exact showing. Session B must never leak
        // onto a session-A ticket. Mismatch is rejected, never reconciled.
        if (reservation.eventSessionId() == null) {
            log.warn("Rejecting PaymentCompleted: stored reservation has no eventSessionId. paymentId={}, reservationId={}",
                    paymentId, payload.reservationId());
            throw new IllegalStateException("Stored reservation missing eventSessionId for payment: " + paymentId);
        }
        if (!payload.eventSessionId().equals(reservation.eventSessionId())) {
            log.warn("Rejecting PaymentCompleted: session mismatch. paymentId={}, payloadSessionId={}, reservationSessionId={}",
                    paymentId, payload.eventSessionId(), reservation.eventSessionId());
            throw new IllegalStateException("PaymentCompleted eventSessionId does not match stored reservation session for payment: "
                    + paymentId);
        }

        // 5. Effective immutable snapshot: payload times first (captured at payment
        // time from trusted state), reservation snapshot as fallback. Both refer to
        // the same verified session above, so they cannot describe different showings.
        java.time.Instant sessionStartsAt = payload.sessionStartsAt() != null
                ? payload.sessionStartsAt() : reservation.sessionStartsAt();
        java.time.Instant sessionEndsAt = payload.sessionEndsAt() != null
                ? payload.sessionEndsAt() : reservation.sessionEndsAt();
        String sessionTimezone = payload.sessionTimezone() != null
                ? payload.sessionTimezone() : reservation.sessionTimezone();

        List<ReservationClientResponse.HeldSeatClientDto> seats = reservation.seats();
        if (seats == null || seats.isEmpty()) {
            throw new IllegalStateException("No seats associated with reservation: " + payload.reservationId());
        }

        int seatCount = seats.size();
        BigDecimal totalAmount = payload.amount() != null ? payload.amount() : BigDecimal.ZERO;
        BigDecimal totalTax = payload.taxAmount() != null ? payload.taxAmount() : BigDecimal.ZERO;
        BigDecimal totalNet = payload.netAmount() != null ? payload.netAmount() : totalAmount.subtract(totalTax);

        // Reconcile fiscal amounts: totalNet + totalTax must equal totalAmount
        if (totalNet.add(totalTax).compareTo(totalAmount) != 0) {
            if (totalTax.signum() == 0 && totalNet.compareTo(totalAmount) < 0) {
                totalTax = totalAmount.subtract(totalNet);
            } else {
                totalNet = totalAmount.subtract(totalTax);
            }
        }

        BigDecimal baseSeatTax = seatCount > 0 ? totalTax.divide(BigDecimal.valueOf(seatCount), 2, RoundingMode.FLOOR) : BigDecimal.ZERO;
        BigDecimal baseSeatNet = seatCount > 0 ? totalNet.divide(BigDecimal.valueOf(seatCount), 2, RoundingMode.FLOOR) : BigDecimal.ZERO;
        BigDecimal taxRemainder = totalTax.subtract(baseSeatTax.multiply(BigDecimal.valueOf(seatCount)));
        BigDecimal netRemainder = totalNet.subtract(baseSeatNet.multiply(BigDecimal.valueOf(seatCount)));

        List<IssueTicketsCommand.SeatTicketItem> ticketItems = new ArrayList<>();
        for (int i = 0; i < seatCount; i++) {
            ReservationClientResponse.HeldSeatClientDto seat = seats.get(i);
            boolean isLast = (i == seatCount - 1);
            BigDecimal seatTax = isLast ? baseSeatTax.add(taxRemainder) : baseSeatTax;
            BigDecimal seatNet = isLast ? baseSeatNet.add(netRemainder) : baseSeatNet;

            ticketItems.add(new IssueTicketsCommand.SeatTicketItem(
                    seat.seatId(),
                    seat.price(),
                    seatTax,
                    seatNet,
                    seat.ticketType() != null && !seat.ticketType().isBlank() ? seat.ticketType() : "Standard"
            ));
        }

        // 3. Issue digital tickets (idempotent via paymentId unique guard + DB constraints)
        IssueTicketsCommand command = new IssueTicketsCommand(
                payload.paymentId(),
                payload.reservationId(),
                payload.userId(),
                payload.customerEmail(),
                payload.customerEmail(),
                payload.eventSessionId(),
                payload.eventId(),
                sessionStartsAt,
                sessionEndsAt,
                sessionTimezone,
                ticketItems,
                payload.currency()
        );

        try {
            ticketService.issueTickets(command);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent duplicate delivery: another instance already issued for this payment
            if (ticketRepository.existsByPaymentId(paymentId)) {
                log.info("Duplicate PaymentCompleted handled via constraint: paymentId={} already issued, suppressing error", paymentId);
                return;
            }
            throw ex;
        }

        log.info("Digital tickets issued successfully for paymentId={}, eventSessionId={}, seatCount={}",
                paymentId, payload.eventSessionId(), seatCount);
    }
}
