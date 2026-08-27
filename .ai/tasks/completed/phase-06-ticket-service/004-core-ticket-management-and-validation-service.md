# TASK-P06-004: Core Ticket Management, Issuance & Gate Validation Service Layer

## 1. Task Metadata
- **Task ID:** `TASK-P06-004`
- **Git Branch:** `feat/p06-004-core-ticket-management-and-validation-service`
- **Target Module:** `backend/services/ticket-service`
- **Phase:** `Phase 06 - Ticket & QR Code Service`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md` (Section 8: Ticket Service), `.ai/architecture/03-database-models.md` (Section 2.6: `seatflow_ticket`), `.ai/architecture/06-api-contracts.md` (Section 2.6)
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`, `.ai/decisions/ADR-004-stripe-tax-and-tax-inclusive-pricing.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the core business logic of `ticket-service` in `TicketService` and `TicketServiceImpl`. This includes cryptographic digital ticket issuance with QR generation and transactional outbox persistence, authenticated customer ticket queries, secure guest ticket retrieval via unique ticket codes, on-demand PDF generation with inter-service metadata enrichment, gate scanner ticket validation with complete audit logging in `ticket_validations`, and historical guest ticket claiming upon user account registration.

### Critical Invariants to Enforce:
- [ ] **Cryptographic Ticket Code Invariant:** Generated ticket codes must be unique, URL-safe, and unpredictable (e.g. `SF-TKT-` followed by 12 cryptographically random alphanumeric characters).
- [ ] **Transactional Outbox Pattern:** All ticket creations and status updates MUST commit their corresponding `Ticket` records and `outbox_events` (`TicketIssuedEvent`) within the **same local database transaction**. Never call Kafka directly from `TicketServiceImpl`.
- [ ] **Hybrid Guest Support (ADR-001):** Guest tickets are persisted with `userId = null` and a valid `customerEmail`. Guest ticket retrieval (`getGuestTicketByCode`) requires only the unique `ticketCode` without authentication.
- [ ] **Fiscal Breakdown Invariant (ADR-004):** Persist and calculate `taxAmount` and `netAmount` alongside total `price` for every seat ticket issued.
- [ ] **Gate Scanner Validation State Machine:**
  1. `INVALID` if `ticketCode` does not exist in the database (record audit entry).
  2. `CANCELLED` if ticket status is `CANCELLED` (record audit entry).
  3. `ALREADY_USED` if ticket status is `USED` (record audit entry with previous scan context).
  4. `SUCCESS` if ticket status is `VALID` (transition status to `USED`, save ticket, record audit entry, return full attendee & seat details).
- [ ] **Guest Ticket Auto-Claiming (ADR-001):** `claimGuestTickets(userId, email)` must execute `updateUserIdByCustomerEmailAndUserIdIsNull` to link all past guest purchases to the newly registered profile.
- [ ] **Exception Hierarchy:** Use `ResourceNotFoundException` for missing tickets, `BusinessException(ErrorCode.FORBIDDEN)` for unauthorized access attempts, and `ValidationException` for malformed requests.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/model/common/IssueTicketsCommand.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/service/TicketService.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/service/impl/TicketServiceImpl.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/service/TicketServiceImplTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Command Records
`model/common/IssueTicketsCommand.java`:
```java
package com.seatflow.ticket.model.common;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record IssueTicketsCommand(
    UUID paymentId,
    UUID reservationId,
    UUID userId,              // Null for guest purchasers (ADR-001)
    String customerEmail,
    String attendeeName,
    UUID eventId,
    List<SeatTicketItem> seats,
    String currency
) {
    public record SeatTicketItem(
        UUID seatId,
        BigDecimal price,     // Gross ticket price
        BigDecimal taxAmount, // Tax / VAT portion (ADR-004)
        BigDecimal netAmount  // Net base price (ADR-004)
    ) {}
}
```

---

### 4.2 Ticket Service Interface
`service/TicketService.java`:
```java
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
     * Generates a downloadable PDF ticket with QR code and fiscal breakdown.
     */
    byte[] generateTicketPdf(UUID ticketId, UUID userId, boolean isGuestOrAdmin);

    /**
     * Validates a ticket QR code at the venue gate scanner and writes an audit log.
     */
    ValidationResultResponse validateTicket(ValidateTicketRequest request);

    /**
     * Auto-associates historical guest tickets with a newly registered user account (ADR-001).
     */
    int claimGuestTickets(UUID userId, String customerEmail);
}
```

---

### 4.3 Ticket Service Implementation Details
`service/impl/TicketServiceImpl.java`:

#### Dependencies to Inject:
- `TicketRepository ticketRepository`
- `TicketValidationRepository validationRepository`
- `OutboxEventRepository outboxRepository`
- `TicketMapper ticketMapper`
- `QrCodeGeneratorService qrCodeGeneratorService`
- `PdfTicketGeneratorService pdfTicketGeneratorService`
- `EventServiceClient eventServiceClient`
- `SeatMapServiceClient seatMapServiceClient`
- `ObjectMapper objectMapper`

#### Method Implementation Rules:

1. **`issueTickets(IssueTicketsCommand command)`:**
   - For each `SeatTicketItem` in `command.seats()`:
     - Generate secure ticket code: `String ticketCode = "SF-TKT-" + generateSecureRandomString(12);`
     - Generate QR payload: `String qrPayload = "https://seatflow.app/tickets/guest/" + ticketCode;`
     - Generate QR code Base64 string via `qrCodeGeneratorService.generateQrCodeBase64(qrPayload, 300, 300)`.
     - Build `Ticket` entity with `command.paymentId()`, `command.reservationId()`, `command.userId()`, `command.customerEmail()`, `command.attendeeName()`, `command.eventId()`, `seat.seatId()`, `seat.price()`, `seat.taxAmount()`, `seat.netAmount()`, `ticketCode`, `qrCodeData = qrPayload`, `status = TicketStatus.VALID`.
     - Save ticket: `Ticket savedTicket = ticketRepository.save(ticket);`
     - Create `TicketIssuedEvent` and serialize to JSON.
     - Persist `OutboxEvent` with `aggregateId = savedTicket.getId()`, `eventType = "TicketIssued"`, `payload = jsonPayload`.
   - Returns `ticketMapper.toResponseList(savedTickets)`.

2. **`getMyTickets(UUID userId, Pageable pageable)`:**
   - Query `ticketRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)`.
   - Map `Page<Ticket>` to `PagedResult<TicketResponse>` using `PagedResult.of(...)` from `common-domain`.

3. **`getTicketById(UUID ticketId, UUID userId, boolean isAdmin)`:**
   - Find ticket: `ticketRepository.findById(ticketId).orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId))`.
   - Access check: If `!isAdmin` and `(ticket.getUserId() == null || !ticket.getUserId().equals(userId))`, throw `new BusinessException("Access denied to ticket", ErrorCode.FORBIDDEN, 403)`.
   - Return `ticketMapper.toDetailResponse(ticket)`.

4. **`getGuestTicketByCode(String ticketCode)`:**
   - Find ticket: `ticketRepository.findByTicketCode(ticketCode).orElseThrow(() -> new ResourceNotFoundException("Ticket not found for code: " + ticketCode))`.
   - Return `ticketMapper.toDetailResponse(ticket)`.

5. **`generateTicketPdf(UUID ticketId, UUID userId, boolean isGuestOrAdmin)`:**
   - Retrieve ticket: `Ticket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));`
   - Access check:
     - If `ticket.getUserId() != null`: Ticket belongs to a registered customer. Only the owner (`ticket.getUserId().equals(userId)`) or an administrator (`isGuestOrAdmin && userId != null`) is allowed access. If `userId == null || (!isGuestOrAdmin && !ticket.getUserId().equals(userId))`, throw `new BusinessException("Access denied to ticket PDF", ErrorCode.FORBIDDEN, 403)`.
     - If `ticket.getUserId() == null`: Guest ticket (ADR-001). Access is granted.
   - Query event metadata via `eventServiceClient.getEventById(ticket.getEventId())` or `eventServiceClient.getEventSeatMap(ticket.getEventId())`.
   - Generate QR PNG image bytes via `qrCodeGeneratorService.generateQrCodePng(ticket.getQrCodeData(), 200, 200)`.
   - Build `PdfTicketData` record with ticket details, event details, attendee name, and ADR-004 fiscal breakdown (`ticket.getPrice()`, `ticket.getTaxAmount()`, `ticket.getNetAmount()`).
   - Return `pdfTicketGeneratorService.generatePdf(pdfTicketData)`.

6. **`validateTicket(ValidateTicketRequest request)`:**
   - Instant scanTime = `Instant.now()`.
   - Search: `Optional<Ticket> ticketOpt = ticketRepository.findByTicketCode(request.ticketCode());`
   - Case 1: `ticketOpt.isEmpty()`
     - Record validation: `validationRepository.save(TicketValidation.builder().ticketId(null).scannerDeviceId(request.scannerDeviceId()).scanResult(ValidationResult.INVALID).details("Ticket code not recognized: " + request.ticketCode()).build());`
     - Return `new ValidationResultResponse(false, null, request.ticketCode(), ValidationResult.INVALID, null, null, null, null, null, null, scanTime, "Invalid ticket: code does not exist")`.
   - Case 2: `ticket.getStatus() == TicketStatus.CANCELLED`
     - Record validation: `validationRepository.save(TicketValidation.builder().ticketId(ticket.getId()).scannerDeviceId(request.scannerDeviceId()).scanResult(ValidationResult.CANCELLED).details("Ticket was cancelled").build());`
     - Return `new ValidationResultResponse(false, ticket.getId(), ticket.getTicketCode(), ValidationResult.CANCELLED, null, null, ticket.getAttendeeName(), null, null, null, scanTime, "Ticket has been cancelled")`.
   - Case 3: `ticket.getStatus() == TicketStatus.USED`
     - Record validation: `validationRepository.save(TicketValidation.builder().ticketId(ticket.getId()).scannerDeviceId(request.scannerDeviceId()).scanResult(ValidationResult.ALREADY_USED).details("Duplicate entry attempt").build());`
     - Return `new ValidationResultResponse(false, ticket.getId(), ticket.getTicketCode(), ValidationResult.ALREADY_USED, null, null, ticket.getAttendeeName(), null, null, null, scanTime, "Ticket has already been used for entry")`.
   - Case 4: `ticket.getStatus() == TicketStatus.VALID`
     - Update status: `ticket.setStatus(TicketStatus.USED); ticketRepository.save(ticket);`
     - Record validation: `validationRepository.save(TicketValidation.builder().ticketId(ticket.getId()).scannerDeviceId(request.scannerDeviceId()).scanResult(ValidationResult.SUCCESS).details("Entry granted").build());`
     - Fetch event & seat details via `eventServiceClient.getEventSeatMap(ticket.getEventId())` (or `eventServiceClient.getEventById(ticket.getEventId())`).
     - Return `new ValidationResultResponse(true, ticket.getId(), ticket.getTicketCode(), ValidationResult.SUCCESS, eventTitle, eventDate, ticket.getAttendeeName(), sectionName, rowLabel, seatNumber, scanTime, "Entry granted successfully")`.

7. **`claimGuestTickets(UUID userId, String customerEmail)`:**
   - Execute: `int updatedCount = ticketRepository.updateUserIdByCustomerEmailAndUserIdIsNull(userId, customerEmail);`
   - `log.info("Claimed {} historical guest tickets for userId={}, email={}", updatedCount, userId, customerEmail);`
   - Return `updatedCount`.

---

### 4.4 Unit Testing Contract
`TicketServiceImplTest`:
- Mocks: `TicketRepository`, `TicketValidationRepository`, `OutboxEventRepository`, `TicketMapper`, `QrCodeGeneratorService`, `PdfTicketGeneratorService`, `EventServiceClient`, `SeatMapServiceClient`, `ObjectMapper`.
- Test Cases:
  - `shouldIssueTicketsAndSaveOutboxEvents`
  - `shouldGetMyTicketsPaginated`
  - `shouldGetTicketByIdWhenOwner`
  - `shouldThrowForbiddenWhenNonOwnerAccessesTicket`
  - `shouldGetGuestTicketByCode`
  - `shouldThrowNotFoundWhenGuestCodeDoesNotExist`
  - `shouldGeneratePdfTicketWithFiscalBreakdown`
  - `shouldValidateTicketSuccessfullyWhenValid`
  - `shouldRejectValidationWhenTicketAlreadyUsed`
  - `shouldRejectValidationWhenTicketCancelled`
  - `shouldRejectValidationWhenTicketCodeNotFound`
  - `shouldClaimHistoricalGuestTickets`

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p06-004-core-ticket-management-and-validation-service` from `develop`.
2. Create `IssueTicketsCommand.java` record in `model/common/`.
3. Define `TicketService` interface in `service/`.
4. Implement `TicketServiceImpl` in `service/impl/` covering issuance, outbox event generation, PDF generation, guest lookup, gate validation state machine, and guest claiming.
5. Write unit test `TicketServiceImplTest` with complete Mockito coverage across all validation branches.
6. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/ticket-service -Dtest=TicketServiceImplTest
```

- [ ] `TicketService` compiles cleanly and satisfies all interface contracts.
- [ ] Ticket issuance correctly creates `Ticket` and `OutboxEvent` records within the same transaction.
- [ ] All 4 gate scanner validation states (`SUCCESS`, `ALREADY_USED`, `INVALID`, `CANCELLED`) tested and logged to `ticket_validations`.
- [ ] ADR-001 guest lookup and auto-claiming logic verified in tests.
- [ ] ADR-004 fiscal breakdown passed into PDF data and outbox events.
- [ ] Task file is moved to `.ai/tasks/completed/phase-06-ticket-service/004-core-ticket-management-and-validation-service.md` when complete.
