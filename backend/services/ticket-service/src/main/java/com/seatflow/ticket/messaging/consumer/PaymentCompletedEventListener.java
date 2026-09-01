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

        log.info("Received PaymentCompleted event. paymentId={}, reservationId={}, amount={}",
                paymentId, payload.reservationId(), payload.amount());

        // 1. Strict Idempotency Check
        if (ticketRepository.existsByPaymentId(paymentId)) {
            log.info("Duplicate PaymentCompleted event skipped. Tickets already exist for paymentId={}", paymentId);
            return;
        }

        // 2. Fetch reservation details from reservation-service
        ReservationClientResponse reservation = reservationServiceClient.getReservationById(payload.reservationId(), payload.customerEmail())
                .orElseThrow(() -> new IllegalStateException("Reservation not found for payment: " + payload.reservationId()));

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
                payload.eventId(),
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

        log.info("Digital tickets issued successfully for paymentId={}, seatCount={}", paymentId, seatCount);
    }
}
