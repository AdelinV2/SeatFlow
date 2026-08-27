# ADR-004: Automated Stripe Tax Integration with Tax-Inclusive Pricing Model

- **Date:** 2026-08-26
- **Author(s):** SeatFlow Architecture Team
- **Driven by Task:** Fiscal Compliance, Tax Calculation & Automated Invoicing Architecture
- **Related ADRs:** ADR-001 (Hybrid Guest Checkout), ADR-002 (Database Indexing and Integrity Standards)
- **Supersedes:** N/A

## 1. Status
`ACCEPTED`

## 2. Context
Event ticketing platforms operating in the European Union (EU) and international jurisdictions must comply with strict fiscal transparency and tax regulations:
1. **Tax-Inclusive Display (B2C Standard):** In consumer ticketing, advertised and checkout prices must be tax-inclusive (e.g. a customer selecting a $10.00 or 100 RON ticket pays exactly that amount at checkout, with all taxes and VAT included).
2. **Fiscal Breakdown Transparency:** While the customer pays the gross amount, official receipts, digital tickets (PDF), and accounting records require an explicit fiscal breakdown: Net Amount (Base) + Tax/VAT Amount = Total Gross Paid.
3. **Cross-Border Tax Complexity:** Tax rates vary dynamically based on buyer jurisdiction, venue location, and event classification (e.g. 19% standard VAT in Romania vs. reduced 5%/9% rates for cultural/theatrical performances, or state-specific sales tax in the US). Maintaining hardcoded tax tables in Java microservices is error-prone, violates separation of concerns, and creates ongoing maintenance overhead.

## 3. Decision
We adopt **Stripe Tax** as the authoritative tax calculation engine paired with a **Tax-Inclusive Pricing Model** across all SeatFlow microservices:

1. **Automatic Stripe Tax Activation (`automatic_tax`):**
   - In `payment-service` (`StripePaymentGatewayImpl`), all Stripe `PaymentIntent` creation requests explicitly enable automatic tax calculation:
     ```java
     paramsBuilder.putExtraParam("automatic_tax", Map.of("enabled", true));
     ```
   - Stripe computes jurisdiction-specific taxes automatically without requiring custom tax calculation microservices or manual tax rates in the database.

2. **Tax-Inclusive Invariant Across Domains:**
   - Pre-configured tier prices in `event-service` (`event_pricing_tiers.price`) and calculated reservation totals in `reservation-service` (`reservations.total_amount`) represent the **gross amount** paid by the customer.
   - Example: For a $10.00 ticket with an applicable 19% tax rate, the customer pays exactly **$10.00**. Stripe Tax calculates the included tax as **$1.90**, leaving a net merchant revenue of **$8.10** (`net_amount = amount - tax_amount`).

3. **Webhook Extraction & Database Persistence:**
   - When Stripe delivers the `payment_intent.succeeded` webhook to `POST /api/payments/webhook`, `payment-service` extracts the computed tax from the PaymentIntent amount details:
     - `taxAmount = paymentIntent.getAmountDetails().getTax()` (converted from cents to currency units).
     - `netAmount = amount.subtract(taxAmount)`.
   - The `payments` table persists:
     - `amount NUMERIC(10,2) NOT NULL` (Gross total charged)
     - `tax_amount NUMERIC(10,2) NOT NULL DEFAULT 0.00` (Tax portion calculated by Stripe)
     - `net_amount NUMERIC(10,2) NOT NULL DEFAULT 0.00` (Net revenue portion)

4. **Event-Driven Propagation via Kafka:**
   - `PaymentCompletedEvent` published to `seatflow.payment.events` carries `amount`, `taxAmount`, and `netAmount`.

5. **Downstream Rendering (Tickets & Notifications):**
   - **`ticket-service` (Phase 06):** Persists `tax_amount` and `net_amount` on `tickets` and renders the fiscal breakdown on downloadable PDF tickets (e.g. `Net: $8.10 | VAT (19% incl.): $1.90 | Total Paid: $10.00`).
   - **`notification-service` (Phase 08):** Injects the fiscal tax breakdown into confirmation HTML emails as a digital receipt.

6. **Frontend Stripe Elements Experience (Phase 09):**
   - The Angular client integrates Stripe `PaymentElement` / `AddressElement`. Stripe Elements automatically renders the real-time tax breakdown to the user prior to final authorization.

## 4. Alternatives Considered

1. **Custom In-House Tax Engine Microservice:**
   - *Pros:* Fully independent from third-party vendor logic.
   - *Cons:* Extremely expensive to maintain; requires continuous updates for global VAT rules, state taxes, threshold exemptions, and municipal cultural levies.
   - *Reason for rejection:* Unnecessary engineering complexity when Stripe already provides enterprise-grade tax calculation.

2. **Tax-Exclusive Pricing (Adding Tax on Top at Checkout):**
   - *Pros:* Simpler backend net calculation (`net = base_price`).
   - *Cons:* Violates EU B2C pricing regulations; induces sticker shock and checkout cart abandonment when the price jumps from $10.00 to $11.90 at the final payment step.
   - *Reason for rejection:* Tax-inclusive pricing provides superior user experience and satisfies European e-commerce standards.

## 5. Consequences
### Positive:
- 100% compliance with international tax transparency and invoicing standards.
- Zero manual tax table maintenance in backend microservices.
- Clear, predictable checkout experience for customers (the advertised ticket price is the exact amount charged).
- Complete fiscal breakdown available for PDF tickets, email receipts, and financial reporting.

### Negative / Trade-offs:
- Requires enabling Stripe Tax and configuring a test business tax registration in the Stripe Dashboard (Test Mode).
- Stripe Elements on the frontend collects billing country / postal code to allow Stripe to resolve tax jurisdictions.

## 6. Implementation Notes
- **Impacted Microservices:** `payment-service`, `ticket-service`, `notification-service`.
- **Impacted Shared Modules:** `common-events` (adding `taxAmount` and `netAmount` to `PaymentCompletedEvent`).
- **Database Schema Updates:** `payments` table (`tax_amount`, `net_amount`), `tickets` table (`tax_amount`, `net_amount`).
- **Documentation Updates:** `00-system-overview.md`, `02-microservices-spec.md`, `03-database-models.md`, `05-messaging-and-outbox.md`, `06-api-contracts.md`, `07-frontend-specification.md`, `SeatFlow-Architecture-and-Implementation-Spec.md`.
