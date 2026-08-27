package com.seatflow.ticket.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.ticket.client.EventServiceClient;
import com.seatflow.ticket.client.SeatMapServiceClient;
import com.seatflow.ticket.client.dto.EventSeatMapClientResponse;
import com.seatflow.ticket.client.dto.VenueClientResponse;
import com.seatflow.ticket.messaging.event.TicketIssuedEvent;
import com.seatflow.ticket.model.common.IssueTicketsCommand;
import com.seatflow.ticket.model.common.PdfTicketData;
import com.seatflow.ticket.model.entity.OutboxEvent;
import com.seatflow.ticket.model.entity.Ticket;
import com.seatflow.ticket.model.entity.TicketValidation;
import com.seatflow.ticket.model.enums.TicketStatus;
import com.seatflow.ticket.model.enums.ValidationResult;
import com.seatflow.ticket.repository.OutboxEventRepository;
import com.seatflow.ticket.repository.TicketRepository;
import com.seatflow.ticket.repository.TicketValidationRepository;
import com.seatflow.ticket.mapper.TicketMapper;
import com.seatflow.ticket.service.PdfTicketGeneratorService;
import com.seatflow.ticket.service.QrCodeGeneratorService;
import com.seatflow.ticket.service.TicketService;
import com.seatflow.ticket.web.dto.request.ValidateTicketRequest;
import com.seatflow.ticket.web.dto.response.TicketDetailResponse;
import com.seatflow.ticket.web.dto.response.TicketResponse;
import com.seatflow.ticket.web.dto.response.ValidationResultResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private static final String TICKET_CODE_PREFIX = "SF-TKT-";
    private static final String QR_PAYLOAD_BASE = "https://seatflow.app/tickets/guest/";
    private static final String SECURE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SECURE_CODE_LENGTH = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final TicketRepository ticketRepository;
    private final TicketValidationRepository validationRepository;
    private final OutboxEventRepository outboxRepository;
    private final TicketMapper ticketMapper;
    private final QrCodeGeneratorService qrCodeGeneratorService;
    private final PdfTicketGeneratorService pdfTicketGeneratorService;
    private final EventServiceClient eventServiceClient;
    private final SeatMapServiceClient seatMapServiceClient;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public List<TicketResponse> issueTickets(IssueTicketsCommand command) {
        List<Ticket> savedTickets = new ArrayList<>();

        for (IssueTicketsCommand.SeatTicketItem seat : command.seats()) {
            String ticketCode = TICKET_CODE_PREFIX + generateSecureRandomString(SECURE_CODE_LENGTH);
            String qrPayload = QR_PAYLOAD_BASE + ticketCode;

            Ticket ticket = Ticket.builder()
                    .reservationId(command.reservationId())
                    .paymentId(command.paymentId())
                    .userId(command.userId())
                    .customerEmail(command.customerEmail())
                    .attendeeName(command.attendeeName())
                    .eventId(command.eventId())
                    .seatId(seat.seatId())
                    .price(seat.price())
                    .taxAmount(seat.taxAmount() == null ? BigDecimal.ZERO : seat.taxAmount())
                    .netAmount(seat.netAmount() == null ? BigDecimal.ZERO : seat.netAmount())
                    .ticketCode(ticketCode)
                    .qrCodeData(qrPayload)
                    .status(TicketStatus.VALID)
                    .build();

            Ticket savedTicket = ticketRepository.save(ticket);
            savedTickets.add(savedTicket);

            TicketIssuedEvent event = new TicketIssuedEvent(
                    savedTicket.getId(),
                    command.reservationId(),
                    command.userId(),
                    command.customerEmail(),
                    command.attendeeName(),
                    command.eventId(),
                    seat.seatId(),
                    seat.price(),
                    seat.taxAmount(),
                    seat.netAmount(),
                    ticketCode,
                    qrPayload,
                    Instant.now()
            );

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateId(savedTicket.getId())
                    .eventType("TicketIssued")
                    .payload(serialize(event))
                    .build();
            outboxRepository.save(outboxEvent);

            log.info("Issued ticket ticketId={}, eventId={}, seatId={}, paymentId={}",
                    savedTicket.getId(), command.eventId(), seat.seatId(), command.paymentId());
        }

        return ticketMapper.toResponseList(savedTickets);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResult<TicketResponse> getMyTickets(UUID userId, org.springframework.data.domain.Pageable pageable) {
        Page<Ticket> page = ticketRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        List<TicketResponse> content = ticketMapper.toResponseList(page.getContent());
        return PagedResult.of(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public TicketDetailResponse getTicketById(UUID ticketId, UUID userId, boolean isAdmin) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));

        if (!isAdmin && (ticket.getUserId() == null || !ticket.getUserId().equals(userId))) {
            throw new BusinessException("Access denied to ticket", ErrorCode.FORBIDDEN, 403);
        }

        return ticketMapper.toDetailResponse(ticket);
    }

    @Override
    @Transactional(readOnly = true)
    public TicketDetailResponse getGuestTicketByCode(String ticketCode) {
        Ticket ticket = ticketRepository.findByTicketCode(ticketCode)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found for code", ticketCode));
        return ticketMapper.toDetailResponse(ticket);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateTicketPdf(UUID ticketId, UUID userId, boolean isGuestOrAdmin) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));

        if (ticket.getUserId() != null) {
            boolean isOwner = userId != null && ticket.getUserId().equals(userId);
            boolean isAdminAccess = isGuestOrAdmin && userId != null;
            if (!isOwner && !isAdminAccess) {
                throw new BusinessException("Access denied to ticket PDF", ErrorCode.FORBIDDEN, 403);
            }
        }

        EventEnrichment enrichment = enrichEvent(ticket.getEventId(), ticket.getSeatId());

        byte[] qrBytes = qrCodeGeneratorService.generateQrCodePng(ticket.getQrCodeData(), 200, 200);

        PdfTicketData data = new PdfTicketData(
                ticket.getId(),
                ticket.getTicketCode(),
                ticket.getStatus().name(),
                enrichment.eventTitle(),
                null,
                enrichment.eventDate(),
                enrichment.venueName(),
                enrichment.venueCity(),
                enrichment.sectionName(),
                enrichment.rowLabel(),
                enrichment.seatNumber(),
                ticket.getAttendeeName(),
                ticket.getCustomerEmail(),
                ticket.getPrice(),
                ticket.getTaxAmount(),
                ticket.getNetAmount(),
                null,
                qrBytes
        );

        return pdfTicketGeneratorService.generatePdf(data);
    }

    @Override
    @Transactional
    public ValidationResultResponse validateTicket(ValidateTicketRequest request) {
        Instant scanTime = Instant.now();
        Optional<Ticket> ticketOpt = ticketRepository.findByTicketCodeForUpdate(request.ticketCode());

        if (ticketOpt.isEmpty()) {
            validationRepository.save(TicketValidation.builder()
                    .ticketId(null)
                    .scannerDeviceId(request.scannerDeviceId())
                    .scanResult(ValidationResult.INVALID)
                    .details("Ticket code not recognized: " + request.ticketCode())
                    .build());
            return new ValidationResultResponse(false, null, request.ticketCode(), ValidationResult.INVALID,
                    null, null, null, null, null, null, scanTime, "Invalid ticket: code does not exist");
        }

        Ticket ticket = ticketOpt.get();

        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            validationRepository.save(TicketValidation.builder()
                    .ticketId(ticket.getId())
                    .scannerDeviceId(request.scannerDeviceId())
                    .scanResult(ValidationResult.CANCELLED)
                    .details("Ticket was cancelled")
                    .build());
            return new ValidationResultResponse(false, ticket.getId(), ticket.getTicketCode(), ValidationResult.CANCELLED,
                    null, null, ticket.getAttendeeName(), null, null, null, scanTime, "Ticket has been cancelled");
        }

        if (ticket.getStatus() == TicketStatus.USED) {
            validationRepository.save(TicketValidation.builder()
                    .ticketId(ticket.getId())
                    .scannerDeviceId(request.scannerDeviceId())
                    .scanResult(ValidationResult.ALREADY_USED)
                    .details("Duplicate entry attempt")
                    .build());
            return new ValidationResultResponse(false, ticket.getId(), ticket.getTicketCode(), ValidationResult.ALREADY_USED,
                    null, null, ticket.getAttendeeName(), null, null, null, scanTime, "Ticket has already been used for entry");
        }

        // Status is VALID -> grant entry, transition to USED, and audit.
        ticket.setStatus(TicketStatus.USED);
        ticketRepository.save(ticket);

        validationRepository.save(TicketValidation.builder()
                .ticketId(ticket.getId())
                .scannerDeviceId(request.scannerDeviceId())
                .scanResult(ValidationResult.SUCCESS)
                .details("Entry granted")
                .build());

        EventEnrichment enrichment = enrichEvent(ticket.getEventId(), ticket.getSeatId());

        return new ValidationResultResponse(true, ticket.getId(), ticket.getTicketCode(), ValidationResult.SUCCESS,
                enrichment.eventTitle(), enrichment.eventDate(), ticket.getAttendeeName(),
                enrichment.sectionName(), enrichment.rowLabel(), enrichment.seatNumber(),
                scanTime, "Entry granted successfully");
    }

    @Override
    @Transactional
    public int claimGuestTickets(UUID userId, String customerEmail) {
        String normalizedEmail = normalizeEmail(customerEmail);
        if (normalizedEmail == null) {
            log.warn("Cannot claim guest tickets: email is null or blank for userId={}", userId);
            return 0;
        }
        int updatedCount = ticketRepository.updateUserIdByNormalizedEmail(userId, normalizedEmail);
        log.info("Claimed {} historical guest tickets for userId={}, normalizedEmail={}", updatedCount, userId, normalizedEmail);
        return updatedCount;
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }

    private EventEnrichment enrichEvent(UUID eventId, UUID seatId) {
        Optional<EventSeatMapClientResponse> seatMapOpt = eventServiceClient.getEventSeatMap(eventId);

        String eventTitle = seatMapOpt.map(EventSeatMapClientResponse::eventTitle).orElse(null);
        Instant eventDate = seatMapOpt.map(EventSeatMapClientResponse::eventDate).orElse(null);
        String venueName = seatMapOpt.map(EventSeatMapClientResponse::venueName).orElse(null);
        String venueCity = null;
        String sectionName = null;
        String rowLabel = null;
        Integer seatNumber = null;

        if (seatMapOpt.isPresent()) {
            EventSeatMapClientResponse esm = seatMapOpt.get();
            for (EventSeatMapClientResponse.SeatMapSectionClientDto section : esm.sections()) {
                for (EventSeatMapClientResponse.SeatMapSeatClientDto seat : section.seats()) {
                    if (seat.seatId().equals(seatId)) {
                        sectionName = section.name();
                        rowLabel = seat.rowLabel();
                        seatNumber = seat.seatNumber();
                        break;
                    }
                }
            }
            if (esm.venueId() != null) {
                venueCity = seatMapServiceClient.getVenueById(esm.venueId())
                        .map(VenueClientResponse::city)
                        .orElse(null);
            }
        }

        return new EventEnrichment(eventTitle, eventDate, venueName, venueCity, sectionName, rowLabel, seatNumber);
    }

    private String generateSecureRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(SECURE_ALPHABET.charAt(SECURE_RANDOM.nextInt(SECURE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize TicketIssuedEvent", e);
        }
    }

    private record EventEnrichment(
        String eventTitle,
        Instant eventDate,
        String venueName,
        String venueCity,
        String sectionName,
        String rowLabel,
        Integer seatNumber
    ) {}
}
