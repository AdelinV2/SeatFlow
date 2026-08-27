# TASK-P08-003: Resend Email Gateway Client & Thymeleaf Template Engine

## 1. Task Metadata
- **Task ID:** `TASK-P08-003`
- **Git Branch:** `feat/p08-001-notification-service`
- **Target Module:** `backend/services/notification-service`
- **Phase:** `Phase 08 - Notification Service`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md` (Section 10)
- **Related ADRs:** `ADR-004: Stripe Tax and Tax-Inclusive Pricing`
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Implement the external Resend email gateway integration via Spring `RestClient` with Bearer token authentication, configuration properties (`ResendProperties`), dynamic HTML email templating via Thymeleaf (`SpringTemplateEngine`), and email service layer abstraction (`EmailService`). Create 3 HTML email templates:
1. `ticket-issued.html`: Complete ticket confirmation with attendee name, seat details, ADR-004 fiscal tax breakdown (Net Price + Tax/VAT = Total Paid), and QR link.
2. `payment-failed.html`: Alert banner with failure reason and payment retry link.
3. `reservation-held.html`: 15-minute countdown reminder banner with seats summary and direct checkout payment link.

### Critical Invariants to Enforce:
- [x] **Resend API Standard:** `POST https://api.resend.com/emails` with Bearer token authorization header and JSON payload schema.
- [x] **Attachments Encoding:** Base64-encoded file payloads for PDF attachments.
- [x] **ADR-004 Tax Breakdown in Ticket Email:** Net Price + Tax Amount = Total Paid displayed clearly in email receipt.
- [x] **Resilience & Timeouts:** Connect timeout 3s, Read timeout 5s on Resend HTTP client.
- [x] **Mock Testing:** Thorough unit testing using `MockRestServiceServer` and Thymeleaf rendering validation.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/config/ResendProperties.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/client/resend/dto/ResendAttachment.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/client/resend/dto/ResendEmailRequest.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/client/resend/dto/ResendEmailResponse.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/client/resend/dto/ResendErrorResponse.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/client/resend/exception/ResendClientException.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/client/resend/ResendEmailClient.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/client/resend/impl/ResendEmailClientImpl.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/service/EmailTemplateRenderer.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/service/impl/ThymeleafEmailTemplateRendererImpl.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/service/EmailService.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/service/impl/ResendEmailServiceImpl.java`
- `[NEW]` `backend/services/notification-service/src/main/resources/templates/mail/ticket-issued.html`
- `[NEW]` `backend/services/notification-service/src/main/resources/templates/mail/payment-failed.html`
- `[NEW]` `backend/services/notification-service/src/main/resources/templates/mail/reservation-held.html`
- `[NEW]` `backend/services/notification-service/src/test/java/com/seatflow/notification/client/resend/ResendEmailClientTest.java`
- `[NEW]` `backend/services/notification-service/src/test/java/com/seatflow/notification/service/EmailTemplateRendererTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Resend Request Schema
```json
{
  "from": "SeatFlow <onboarding@resend.dev>",
  "to": ["customer@example.com"],
  "subject": "Your SeatFlow Ticket Confirmation",
  "html": "<html>...</html>",
  "attachments": [
    {
      "filename": "ticket-SF-TKT-1234.pdf",
      "content": "<base64_encoded_pdf_bytes>"
    }
  ]
}
```

---

## 5. Step-by-Step Implementation Sequence
1. Create `ResendProperties` (`@ConfigurationProperties(prefix = "seatflow.resend")`).
2. Create DTO records for Resend request/response/attachments.
3. Implement `ResendEmailClient` using Spring `RestClient` with Bearer auth and custom error mapping.
4. Implement `EmailTemplateRenderer` using Thymeleaf `SpringTemplateEngine`.
5. Create HTML templates: `ticket-issued.html`, `payment-failed.html`, `reservation-held.html`.
6. Implement `EmailService` and `ResendEmailServiceImpl`.
7. Write unit tests for `ResendEmailClient` (`MockRestServiceServer`) and `EmailTemplateRenderer`.

---

## 6. Definition of Done & Verification Command
```bash
mvn clean test -pl backend/services/notification-service -Dtest=ResendEmailClientTest,EmailTemplateRendererTest
```
