package com.seatflow.ticket.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import com.seatflow.ticket.client.EventServiceClient;
import com.seatflow.ticket.client.SeatMapServiceClient;
import com.seatflow.ticket.client.dto.EventSeatMapClientResponse;
import com.seatflow.ticket.client.dto.VenueClientResponse;
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
import com.seatflow.ticket.web.dto.request.ValidateTicketRequest;
import com.seatflow.ticket.web.dto.response.TicketDetailResponse;
import com.seatflow.ticket.web.dto.response.TicketResponse;
import com.seatflow.ticket.web.dto.response.ValidationResultResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketServiceImplTest {

    @Mock private TicketRepository ticketRepository;
    @Mock private TicketValidationRepository validationRepository;
    @Mock private OutboxEventRepository outboxRepository;
    @Mock private TicketMapper ticketMapper;
    @Mock private QrCodeGeneratorService qrCodeGeneratorService;
    @Mock private PdfTicketGeneratorService pdfTicketGeneratorService;
    @Mock private EventServiceClient eventServiceClient;
    @Mock private SeatMapServiceClient seatMapServiceClient;

    private TicketServiceImpl ticketService;

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID seatId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ticketService = new TicketServiceImpl(ticketRepository, validationRepository, outboxRepository,
                ticketMapper, qrCodeGeneratorService, pdfTicketGeneratorService, eventServiceClient,
                seatMapServiceClient, new ObjectMapper().registerModule(new JavaTimeModule()),
                mock(W3cTraceContextPropagator.class));

        when(ticketRepository.save(any(Ticket.class))).thenAnswer(a -> a.getArgument(0));
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(a -> a.getArgument(0));
        when(qrCodeGeneratorService.generateQrCodeBase64(anyString(), anyInt(), anyInt())).thenReturn("data:image/png;base64,xxx");
        when(qrCodeGeneratorService.generateQrCodePng(anyString(), anyInt(), anyInt())).thenReturn(new byte[]{1, 2, 3});
        when(pdfTicketGeneratorService.generatePdf(any(PdfTicketData.class))).thenReturn(new byte[]{9, 9});
        when(ticketMapper.toResponseList(anyList())).thenAnswer(a -> {
            List<?> input = a.getArgument(0);
            return input.stream().map(x -> mock(TicketResponse.class)).toList();
        });
    }

    private Ticket sampleTicket(TicketStatus status, UUID ownerId) {
        return Ticket.builder()
                .id(ticketId)
                .reservationId(reservationId)
                .paymentId(paymentId)
                .userId(ownerId)
                .customerEmail("buyer@example.com")
                .attendeeName("Jane Doe")
                .ticketType("VIP")
                .eventId(eventId)
                .seatId(seatId)
                .price(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("19.00"))
                .netAmount(new BigDecimal("81.00"))
                .ticketCode("SF-TKT-ABCDEFGHIJKL")
                .qrCodeData("https://seatflow.app/tickets/guest/SF-TKT-ABCDEFGHIJKL")
                .status(status)
                .build();
    }

    @Test
    void shouldIssueTicketsAndSaveOutboxEvents() {
        IssueTicketsCommand.SeatTicketItem seat1 = new IssueTicketsCommand.SeatTicketItem(seatId, new BigDecimal("100.00"), new BigDecimal("19.00"), new BigDecimal("81.00"));
        IssueTicketsCommand.SeatTicketItem seat2 = new IssueTicketsCommand.SeatTicketItem(UUID.randomUUID(), new BigDecimal("50.00"), new BigDecimal("9.50"), new BigDecimal("40.50"));
        IssueTicketsCommand command = new IssueTicketsCommand(paymentId, reservationId, userId, "buyer@example.com",
                "Jane Doe", eventId, List.of(seat1, seat2), "USD");

        List<TicketResponse> result = ticketService.issueTickets(command);

        assertThat(result).hasSize(2);
        verify(ticketRepository, times(2)).save(any(Ticket.class));
        verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
    }

    @Test
    void shouldGetMyTicketsPaginated() {
        Pageable pageable = Pageable.ofSize(10);
        Page<Ticket> page = mock(Page.class);
        when(page.getContent()).thenReturn(List.of(sampleTicket(TicketStatus.VALID, userId)));
        when(page.getTotalElements()).thenReturn(5L);
        when(page.getNumber()).thenReturn(0);
        when(page.getSize()).thenReturn(10);
        when(ticketRepository.findByUserIdOrderByCreatedAtDesc(any(UUID.class), any(Pageable.class)))
                .thenReturn(page);
        when(ticketMapper.toResponseList(anyList())).thenReturn(List.of(mock(TicketResponse.class)));

        PagedResult<TicketResponse> result = ticketService.getMyTickets(userId, pageable);

        assertThat(result.totalElements()).isEqualTo(5);
        assertThat(result.content()).hasSize(1);
    }

    @Test
    void shouldGetTicketByIdWhenOwner() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(sampleTicket(TicketStatus.VALID, userId)));
        when(ticketMapper.toDetailResponse(any(Ticket.class))).thenReturn(mock(TicketDetailResponse.class));

        TicketDetailResponse result = ticketService.getTicketById(ticketId, userId, false);

        assertThat(result).isNotNull();
    }

    @Test
    void shouldThrowForbiddenWhenNonOwnerAccessesTicket() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(sampleTicket(TicketStatus.VALID, otherUserId)));

        assertThatThrownBy(() -> ticketService.getTicketById(ticketId, userId, false))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    void shouldGetGuestTicketByCode() {
        when(ticketRepository.findByTicketCode("SF-TKT-ABCDEFGHIJKL")).thenReturn(Optional.of(sampleTicket(TicketStatus.VALID, null)));
        when(ticketMapper.toDetailResponse(any(Ticket.class))).thenReturn(mock(TicketDetailResponse.class));

        TicketDetailResponse result = ticketService.getGuestTicketByCode("SF-TKT-ABCDEFGHIJKL");

        assertThat(result).isNotNull();
    }

    @Test
    void shouldThrowNotFoundWhenGuestCodeDoesNotExist() {
        when(ticketRepository.findByTicketCode("MISSING")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.getGuestTicketByCode("MISSING"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shouldGeneratePdfTicketWithFiscalBreakdown() {
        Ticket ticket = sampleTicket(TicketStatus.VALID, userId);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
        EventSeatMapClientResponse esm = new EventSeatMapClientResponse(eventId, UUID.randomUUID(), "Concert",
                Instant.now(), "Sky Arena", 5000, 100L, List.of());
        when(eventServiceClient.getEventSeatMap(eventId)).thenReturn(Optional.of(esm));
        when(eventServiceClient.getEventById(eventId)).thenReturn(Optional.of(
                new com.seatflow.ticket.client.dto.EventClientResponse(eventId, UUID.randomUUID(), "Concert", "CONCERT", Instant.now(), "ACTIVE", "http://banner")));
        when(seatMapServiceClient.getVenueById(any())).thenReturn(Optional.of(new VenueClientResponse(UUID.randomUUID(), "Sky Arena", "Main St", "Berlin", "DE", 5000)));
        when(ticketMapper.toDetailResponse(any())).thenReturn(mock(TicketDetailResponse.class));

        byte[] pdf = ticketService.generateTicketPdf(ticketId, userId, false);

        assertThat(pdf).isNotNull().isNotEmpty();
        ArgumentCaptor<PdfTicketData> captor = ArgumentCaptor.forClass(PdfTicketData.class);
        verify(pdfTicketGeneratorService).generatePdf(captor.capture());
        PdfTicketData data = captor.getValue();
        assertThat(data.price()).isEqualByComparingTo("100.00");
        assertThat(data.taxAmount()).isEqualByComparingTo("19.00");
        assertThat(data.netAmount()).isEqualByComparingTo("81.00");
        assertThat(data.eventCategory()).isEqualTo("CONCERT");
        assertThat(data.venueCity()).isEqualTo("Berlin");
        assertThat(data.currency()).isEqualTo("USD");
    }

    @Test
    void shouldGetGuestTicketBundleByCode() {
        Ticket guestTicket1 = sampleTicket(TicketStatus.VALID, null);
        Ticket guestTicket2 = sampleTicket(TicketStatus.VALID, null);
        when(ticketRepository.findByTicketCode("SF-TKT-ABCDEFGHIJKL")).thenReturn(Optional.of(guestTicket1));
        when(ticketRepository.findByReservationId(reservationId)).thenReturn(List.of(guestTicket1, guestTicket2));
        when(ticketMapper.toDetailResponse(any(Ticket.class))).thenReturn(mock(TicketDetailResponse.class));

        List<TicketDetailResponse> bundle = ticketService.getGuestTicketBundleByCode("SF-TKT-ABCDEFGHIJKL");

        assertThat(bundle).hasSize(2);
        verify(ticketRepository).findByReservationId(reservationId);
    }

    @Test
    void shouldGenerateGuestTicketPdfByTicketCode() {
        Ticket guestTicket = sampleTicket(TicketStatus.VALID, null);
        when(ticketRepository.findByTicketCode("SF-TKT-ABCDEFGHIJKL")).thenReturn(Optional.of(guestTicket));
        when(eventServiceClient.getEventSeatMap(eventId)).thenReturn(Optional.empty());
        when(eventServiceClient.getEventById(eventId)).thenReturn(Optional.of(
                new com.seatflow.ticket.client.dto.EventClientResponse(eventId, UUID.randomUUID(), "Festival", "FESTIVAL", Instant.now(), "ACTIVE", "http://banner")));

        byte[] pdf = ticketService.generateGuestTicketPdf("SF-TKT-ABCDEFGHIJKL");

        assertThat(pdf).isNotNull().isNotEmpty();
    }

    @Test
    void shouldGeneratePdfTicketForAdminWhenGuestTicket() {
        Ticket guestTicket = sampleTicket(TicketStatus.VALID, null);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(guestTicket));
        when(eventServiceClient.getEventSeatMap(eventId)).thenReturn(Optional.empty());
        when(eventServiceClient.getEventById(eventId)).thenReturn(Optional.of(
                new com.seatflow.ticket.client.dto.EventClientResponse(eventId, UUID.randomUUID(), "Festival", "FESTIVAL", Instant.now(), "ACTIVE", "http://banner")));

        byte[] pdf = ticketService.generateTicketPdf(ticketId, userId, true);

        assertThat(pdf).isNotNull().isNotEmpty();
    }

    @Test
    void shouldThrowForbiddenWhenNonOwnerGeneratesPdf() {
        Ticket userTicket = sampleTicket(TicketStatus.VALID, otherUserId);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(userTicket));

        assertThatThrownBy(() -> ticketService.generateTicketPdf(ticketId, userId, false))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    void shouldValidateTicketSuccessfullyWhenValid() {
        Ticket ticket = sampleTicket(TicketStatus.VALID, userId);
        when(ticketRepository.findByTicketCodeForUpdate("SF-TKT-ABCDEFGHIJKL")).thenReturn(Optional.of(ticket));
        when(eventServiceClient.getEventSeatMap(eventId)).thenReturn(Optional.of(
                new EventSeatMapClientResponse(eventId, UUID.randomUUID(), "Concert", Instant.now(), "Sky Arena", 5000, 100L, List.of())));

        ValidationResultResponse result = ticketService.validateTicket(
                new ValidateTicketRequest("SF-TKT-ABCDEFGHIJKL", "GATE-1"));

        assertThat(result.valid()).isTrue();
        assertThat(result.result()).isEqualTo(ValidationResult.SUCCESS);
        assertThat(result.ticketId()).isEqualTo(ticketId);
        assertThat(result.ticketType()).isEqualTo("VIP");
        verify(ticketRepository).save(any(Ticket.class));
        ArgumentCaptor<TicketValidation> captor = ArgumentCaptor.forClass(TicketValidation.class);
        verify(validationRepository).save(captor.capture());
        assertThat(captor.getValue().getScanResult()).isEqualTo(ValidationResult.SUCCESS);
    }

    @Test
    void shouldRejectValidationWhenTicketAlreadyUsed() {
        when(ticketRepository.findByTicketCodeForUpdate("SF-TKT-ABCDEFGHIJKL")).thenReturn(Optional.of(sampleTicket(TicketStatus.USED, userId)));

        ValidationResultResponse result = ticketService.validateTicket(
                new ValidateTicketRequest("SF-TKT-ABCDEFGHIJKL", "GATE-1"));

        assertThat(result.valid()).isFalse();
        assertThat(result.result()).isEqualTo(ValidationResult.ALREADY_USED);
        verify(ticketRepository, times(0)).save(any(Ticket.class));
    }

    @Test
    void shouldRejectValidationWhenTicketCancelled() {
        when(ticketRepository.findByTicketCodeForUpdate("SF-TKT-ABCDEFGHIJKL")).thenReturn(Optional.of(sampleTicket(TicketStatus.CANCELLED, userId)));

        ValidationResultResponse result = ticketService.validateTicket(
                new ValidateTicketRequest("SF-TKT-ABCDEFGHIJKL", "GATE-1"));

        assertThat(result.valid()).isFalse();
        assertThat(result.result()).isEqualTo(ValidationResult.CANCELLED);
        verify(ticketRepository, times(0)).save(any(Ticket.class));
    }

    @Test
    void shouldRejectValidationWhenTicketCodeNotFound() {
        when(ticketRepository.findByTicketCodeForUpdate("MISSING")).thenReturn(Optional.empty());

        ValidationResultResponse result = ticketService.validateTicket(
                new ValidateTicketRequest("MISSING", "GATE-1"));

        assertThat(result.valid()).isFalse();
        assertThat(result.result()).isEqualTo(ValidationResult.INVALID);
        assertThat(result.ticketId()).isNull();
        ArgumentCaptor<TicketValidation> captor = ArgumentCaptor.forClass(TicketValidation.class);
        verify(validationRepository).save(captor.capture());
        assertThat(captor.getValue().getScanResult()).isEqualTo(ValidationResult.INVALID);
    }

    @Test
    void shouldClaimHistoricalGuestTickets() {
        when(ticketRepository.updateUserIdByNormalizedEmail(userId, "buyer@example.com")).thenReturn(3);

        int claimed = ticketService.claimGuestTickets(userId, "buyer@example.com");

        assertThat(claimed).isEqualTo(3);
        verify(ticketRepository).updateUserIdByNormalizedEmail(userId, "buyer@example.com");
    }
}
