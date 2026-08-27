# TASK-P06-003: ZXing QR Code Generation, OpenPDF Ticket Renderer & Inter-Service REST Clients

## 1. Task Metadata
- **Task ID:** `TASK-P06-003`
- **Git Branch:** `feat/p06-003-qr-code-and-pdf-generation-services`
- **Target Module:** `backend/services/ticket-service`
- **Phase:** `Phase 06 - Ticket & QR Code Service`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md` (Section 8: Ticket Service, Section 11: Synchronous Inter-Service Communication), `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** `.ai/decisions/ADR-004-stripe-tax-and-tax-inclusive-pricing.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the cryptographic QR code generation service using **ZXing**, the downloadable PDF ticket rendering engine using **OpenPDF**, the Spring Cloud LoadBalancer REST client configuration with mandatory `@Primary` plain builder, and the resilient inter-service clients for querying `event-service`, `reservation-service`, and `seat-map-service`.

### Critical Invariants to Enforce:
- [ ] **ZXing QR Code Generation:** Generate standard high-density PNG byte arrays and Base64 data URLs (`data:image/png;base64,...`) using `QRCodeWriter`, `BarcodeFormat.QR_CODE`, UTF-8 charset, and ErrorCorrectionLevel `H` (or `M`).
- [ ] **Fiscal Breakdown Invariant (ADR-004):** The rendered PDF ticket must display an official fiscal breakdown box detailing:
  1. `Net Base Amount` (e.g., `$81.00`)
  2. `Tax / VAT Amount (Included)` (e.g., `$19.00`)
  3. `Total Gross Paid` (e.g., `$100.00`)
- [ ] **Professional PDF Ticket Structure:** Render official SeatFlow PDF tickets with header branding, event metadata (title, category, date/time), venue/section/row/seat coordinates, attendee details, ticket code, embedded ZXing QR code image, and gate scanner verification notice.
- [ ] **Inter-Service REST Standard (Section 11):** Inter-service REST calls MUST use `@LoadBalanced RestClient.Builder` with target URIs `http://<service-name>` (e.g. `http://event-service`, `http://reservation-service`, `http://seat-map-service`).
- [ ] **Mandatory `@Primary` Plain Builder:** `config/RestClientConfig.java` must declare an un-annotated `@Primary` `RestClient.Builder` bean to preserve Eureka client's internal registration mechanism alongside the `@LoadBalanced` qualified builder.
- [ ] **Resilience & Tracing:** Inter-service REST clients must forward `X-Correlation-Id` using `CorrelationContext.getCorrelationId()` and be protected with Resilience4j circuit breakers and connection/read timeouts (3s connect / 5s read).

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/config/RestClientConfig.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/service/QrCodeGeneratorService.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/service/impl/QrCodeGeneratorServiceImpl.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/model/common/PdfTicketData.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/service/PdfTicketGeneratorService.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/service/impl/PdfTicketGeneratorServiceImpl.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/client/dto/EventClientResponse.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/client/dto/EventSeatMapClientResponse.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/client/dto/ReservationClientResponse.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/client/dto/VenueClientResponse.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/client/dto/VenueSeatMapLayoutClientResponse.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/client/EventServiceClient.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/client/impl/EventServiceClientImpl.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/client/ReservationServiceClient.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/client/impl/ReservationServiceClientImpl.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/client/SeatMapServiceClient.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/client/impl/SeatMapServiceClientImpl.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/service/QrCodeGeneratorServiceTest.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/service/PdfTicketGeneratorServiceTest.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/client/EventServiceClientTest.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/client/ReservationServiceClientTest.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/client/SeatMapServiceClientTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 REST Client Configuration
`config/RestClientConfig.java`:
```java
package com.seatflow.ticket.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Un-annotated @Primary RestClient.Builder.
     * CRITICAL: Required for Eureka Client internal registration without LoadBalancer recursion.
     */
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    /**
     * @LoadBalanced RestClient.Builder for inter-service communication across microservices.
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
```

---

### 4.2 QR Code Generator Service Contract
`service/QrCodeGeneratorService.java`:
```java
package com.seatflow.ticket.service;

public interface QrCodeGeneratorService {

    /**
     * Generates a PNG byte array for a QR code from text payload.
     */
    byte[] generateQrCodePng(String payload, int width, int height);

    /**
     * Generates a Base64 data URL (data:image/png;base64,...) for embedding in HTML/JSON.
     */
    String generateQrCodeBase64(String payload, int width, int height);
}
```

Implementation notes (`QrCodeGeneratorServiceImpl.java`):
- Uses `QRCodeWriter` from `com.google.zxing.qrcode`.
- Configures hints: `Map.of(EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H, EncodeHintType.MARGIN, 1)`.
- Converts `BitMatrix` to PNG bytes using `MatrixToImageWriter.writeToStream(matrix, "PNG", baos)`.
- Encodes bytes to `data:image/png;base64,` + Base64 string for `generateQrCodeBase64`.

---

### 4.3 PDF Ticket Generator Data & Service Contract

#### `model/common/PdfTicketData.java`
```java
package com.seatflow.ticket.model.common;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PdfTicketData(
    UUID ticketId,
    String ticketCode,
    String status,
    String eventTitle,
    String eventCategory,
    Instant eventDate,
    String venueName,
    String venueCity,
    String sectionName,
    String rowLabel,
    Integer seatNumber,
    String attendeeName,
    String customerEmail,
    BigDecimal price,       // Gross total paid
    BigDecimal taxAmount,   // Tax / VAT portion
    BigDecimal netAmount,   // Net base price
    String currency,
    byte[] qrCodeImagePng   // Embedded QR code image bytes
) {}
```

#### `service/PdfTicketGeneratorService.java`
```java
package com.seatflow.ticket.service;

import com.seatflow.ticket.model.common.PdfTicketData;

public interface PdfTicketGeneratorService {

    /**
     * Renders a professional, downloadable PDF ticket containing full fiscal breakdown and QR code.
     */
    byte[] generatePdf(PdfTicketData ticketData);
}
```

Implementation notes (`PdfTicketGeneratorServiceImpl.java`):
- Uses OpenPDF `com.lowagie.text.Document`, `PdfWriter`, `Table`, `Cell`, `Paragraph`, `Font`, `Image`, `Color`.
- Document page size: `PageSize.A5.rotate()` or `PageSize.A4`.
- Header: Brand title "SeatFlow Digital Ticket", ticket code header.
- Event Details Table:
  - Event title, Category, Formatted Date (UTC / localized), Venue & City.
- Seating & Attendee Details:
  - Section, Row, Seat Number.
  - Attendee Name, Customer Email.
- **Fiscal Breakdown Box (ADR-004):**
  - "Net Base Price: " + `netAmount` + " " + `currency`
  - "Tax / VAT Included: " + `taxAmount` + " " + `currency`
  - "Total Paid: " + `price` + " " + `currency`
- QR Code Section:
  - Embeds `Image.getInstance(ticketData.qrCodeImagePng())` scaled appropriately (e.g. 140x140).
  - Caption: "Scan this QR code at entry gate".

---

### 4.4 Inter-Service Client DTOs & Contracts

#### Inter-Service DTOs:
```java
package com.seatflow.ticket.client.dto;

import java.time.Instant;
import java.util.UUID;

public record EventClientResponse(
    UUID id,
    UUID venueId,
    String title,
    String category,
    Instant eventDate,
    String status,
    String bannerUrl
) {}
```

```java
package com.seatflow.ticket.client.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventSeatMapClientResponse(
    UUID eventId,
    UUID venueId,
    String eventTitle,
    Instant eventDate,
    String venueName,
    Integer venueCapacity,
    Long totalConfiguredSeats,
    List<SeatMapSectionClientDto> sections
) {
    public record SeatMapSectionClientDto(
        UUID sectionId,
        String name,
        Integer rowCount,
        Integer colCount,
        List<SeatMapSeatClientDto> seats
    ) {}

    public record SeatMapSeatClientDto(
        UUID seatId,
        String rowLabel,
        Integer seatNumber,
        Integer gridX,
        Integer gridY,
        String status
    ) {}
}
```

```java
package com.seatflow.ticket.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationClientResponse(
    UUID id,
    UUID eventId,
    UUID userId,
    String customerEmail,
    String status,
    BigDecimal totalAmount,
    Integer seatCount,
    List<HeldSeatClientDto> seats,
    Instant expiresAt,
    Instant createdAt
) {
    public record HeldSeatClientDto(
        UUID id,
        UUID seatId,
        String status,
        BigDecimal price
    ) {}
}
```

```java
package com.seatflow.ticket.client.dto;

import java.util.List;
import java.util.UUID;

public record VenueClientResponse(
    UUID id,
    String name,
    String address,
    String city,
    String country,
    Integer capacity
) {}
```

```java
package com.seatflow.ticket.client.dto;

import java.util.List;
import java.util.UUID;

public record VenueSeatMapLayoutClientResponse(
    UUID venueId,
    String venueName,
    List<SectionLayoutDto> sections
) {
    public record SectionLayoutDto(
        UUID sectionId,
        String sectionName,
        List<SeatLayoutDto> seats
    ) {}

    public record SeatLayoutDto(
        UUID seatId,
        String rowLabel,
        Integer seatNumber,
        Integer gridX,
        Integer gridY,
        Boolean isActive
    ) {}
}
```

#### Client Interfaces:
```java
package com.seatflow.ticket.client;

import com.seatflow.ticket.client.dto.EventClientResponse;
import com.seatflow.ticket.client.dto.EventSeatMapClientResponse;
import java.util.Optional;
import java.util.UUID;

public interface EventServiceClient {
    Optional<EventClientResponse> getEventById(UUID eventId);
    Optional<EventSeatMapClientResponse> getEventSeatMap(UUID eventId);
}
```

```java
package com.seatflow.ticket.client;

import com.seatflow.ticket.client.dto.ReservationClientResponse;
import java.util.Optional;
import java.util.UUID;

public interface ReservationServiceClient {
    Optional<ReservationClientResponse> getReservationById(UUID reservationId);
}
```

```java
package com.seatflow.ticket.client;

import com.seatflow.ticket.client.dto.VenueClientResponse;
import com.seatflow.ticket.client.dto.VenueSeatMapLayoutClientResponse;
import java.util.Optional;
import java.util.UUID;

public interface SeatMapServiceClient {
    Optional<VenueClientResponse> getVenueById(UUID venueId);
    Optional<VenueSeatMapLayoutClientResponse> getVenueLayout(UUID venueId);
}
```

#### Client Implementations:
All client implementations:
- Inject `@LoadBalanced RestClient.Builder`.
- Set base URL to `http://event-service`, `http://reservation-service`, `http://seat-map-service`.
- Add request interceptor forwarding `X-Correlation-Id: CorrelationContext.getCorrelationId()`.
- Use timeouts via `SimpleClientHttpRequestFactory` (3000ms connect, 5000ms read).
- Annotated with `@CircuitBreaker(name = "eventService", fallbackMethod = "getEventFallback")` returning `Optional.empty()` or safe default fallback on remote outage.

---

### 4.5 Testing Contracts
- `QrCodeGeneratorServiceTest`:
  - Asserts non-null, non-empty PNG byte array.
  - Verifies Base64 output starts with `data:image/png;base64,`.
  - Uses ZXing `MultiFormatReader` and `BinaryBitmap` to read generated PNG bytes and asserts decoded text equals source payload.
- `PdfTicketGeneratorServiceTest`:
  - Builds test `PdfTicketData` with dummy QR bytes and fiscal values ($81.00 Net + $19.00 Tax = $100.00 Total).
  - Invokes `generatePdf(data)`.
  - Asserts generated bytes start with `%PDF-` header and length > 1000 bytes.
- `EventServiceClientTest`, `ReservationServiceClientTest`, `SeatMapServiceClientTest`:
  - Uses `MockRestServiceServer` to simulate remote REST responses and asserts correct deserialization and error handling.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p06-003-qr-code-and-pdf-generation-services` from `develop`.
2. Create `RestClientConfig.java` declaring `@Primary` plain and `@LoadBalanced` qualified `RestClient.Builder` beans.
3. Implement `QrCodeGeneratorService` and `QrCodeGeneratorServiceImpl` using ZXing.
4. Implement `PdfTicketData` record, `PdfTicketGeneratorService`, and `PdfTicketGeneratorServiceImpl` using OpenPDF with fiscal breakdown and QR code embedding.
5. Create inter-service client DTOs in `client/dto/`.
6. Implement `EventServiceClient`, `ReservationServiceClient`, and `SeatMapServiceClient` with Resilience4j circuit breakers and correlation ID forwarding.
7. Write unit tests `QrCodeGeneratorServiceTest`, `PdfTicketGeneratorServiceTest`, and client mock tests.
8. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/ticket-service -Dtest=*GeneratorTest,*ClientTest
```

- [ ] `QrCodeGeneratorService` generates valid PNG and Base64 QR codes that decode correctly with ZXing.
- [ ] `PdfTicketGeneratorService` renders valid PDF tickets containing event info, seat info, embedded QR code, and ADR-004 fiscal breakdown.
- [ ] `@Primary` plain `RestClient.Builder` and `@LoadBalanced` builder configured correctly.
- [ ] Inter-service REST clients pass mock server tests with Resilience4j circuit breakers and timeout handling.
- [ ] Task file is moved to `.ai/tasks/completed/phase-06-ticket-service/003-qr-code-and-pdf-generation-services.md` when complete.
