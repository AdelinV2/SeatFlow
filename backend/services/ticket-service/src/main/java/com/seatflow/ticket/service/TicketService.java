package com.seatflow.ticket.service;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.ticket.model.common.IssueTicketsCommand;
import com.seatflow.ticket.web.dto.request.ValidateTicketRequest;
import com.seatflow.ticket.web.dto.response.TicketDetailResponse;
import com.seatflow.ticket.web.dto.response.TicketResponse;
import com.seatflow.ticket.web.dto.response.ValidationResultResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface TicketService {

    /**
     * Issues digital tickets for a completed payment, generates QR codes, and writes Outbox events.
     */
    List<TicketResponse> issueTickets(IssueTicketsCommand command);

    /**
     * Retrieves paginated tickets for the authenticated customer.
     */
    PagedResult<TicketResponse> getMyTickets(UUID userId, Pageable pageable);

    /**
     * Retrieves detailed ticket information by ID for the owner or administrator.
     */
    TicketDetailResponse getTicketById(UUID ticketId, UUID userId, boolean isAdmin);

    /**
     * Retrieves ticket detail by unique secure ticket code (guest delivery).
     */
    TicketDetailResponse getGuestTicketByCode(String ticketCode);

    /**
     * Retrieves all ticket details belonging to the same reservation bundle by ticket code (ADR-001).
     */
    List<TicketDetailResponse> getGuestTicketBundleByCode(String ticketCode);

    /**
     * Generates a downloadable PDF ticket for authenticated owner or admin.
     */
    byte[] generateTicketPdf(UUID ticketId, UUID userId, boolean isGuestOrAdmin);

    /**
     * Generates a downloadable PDF ticket for guest access by ticket code token (ADR-001).
     */
    byte[] generateGuestTicketPdf(String ticketCode);

    /**
     * Validates a ticket QR code at the venue gate scanner and writes an audit log.
     */
    ValidationResultResponse validateTicket(ValidateTicketRequest request);

    /**
     * Auto-associates historical guest tickets with a newly registered user account (ADR-001).
     */
    int claimGuestTickets(UUID userId, String customerEmail);
}
