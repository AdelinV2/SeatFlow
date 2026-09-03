# ADR-014: Spring AI Assistant with Controlled Tool Calling and Zero Direct DB Access

- **Date:** 2026-09-02
- **Status:** `ACCEPTED`
- **Driven by:** Phase 15 — AI Assistant & MCP / Controlled Tool Calling

## 1. Context

SeatFlow aims to provide an AI assistant that enables natural language discovery, intelligent seat recommendations, and reservation assistance.

However, giving an LLM unconstrained database access or direct autonomous booking permissions creates severe architectural and operational risks:
1. **Security & Data Isolation:** Prompt injection could trick the model into bypassing row-level security or extracting raw tenant/user data.
2. **Invariant Violations:** The model could bypass server-side rules (10-seat limit, 15-minute hold TTL, session partitioning, optimistic locking).
3. **Financial Safety:** The model must never autonomously charge credit cards or complete payments on behalf of users without manual authorization.

## 2. Decision

Introduce a dedicated `ai-service` (default port `8090`) built with **Spring AI** that operates under strict zero-trust principles:

1. **Zero Direct Database Access:** `ai-service` has no JPA/JDBC connections to domain databases (`seatflow_event`, `seatflow_reservation`, `seatflow_seatmap`, `seatflow_payment`, `seatflow_ticket`).
2. **Structured Synchronous Tool Invocations:** All domain operations execute through Spring AI tool calling functions (`FunctionCallback` / `@Tool`), which call target microservices via Eureka Service Discovery and Spring Cloud LoadBalancer (`@LoadBalanced RestClient`).
3. **Deterministic Seat Ranking:** `findBestSeats` logic is implemented as deterministic algorithmic code (evaluating section tier, budget, proximity to stage, adjacency), returning verifiable recommendations rather than allowing the LLM to invent coordinates or prices.
4. **Two-Step Explicit Confirmation Boundary:**
   - Read-only tools (`searchEvents`, `getEventSessions`, `getAvailableSeats`, `findBestSeats`, `getReservation`) run automatically during conversation turns.
   - State-changing tools (`createReservation`) can only be invoked after presenting a structured proposal card and receiving an explicit confirmation action from the authenticated user.
5. **Payment Exclusion:** Payments remain completely outside the AI domain and must be completed by the user via the existing Stripe checkout flow.
6. **Graceful Feature Degradation:** If AI provider credentials (e.g. OpenAI/Gemini/Anthropic API keys) are missing or invalid, the AI service returns clear disabled states without affecting any other microservice or the core booking engine.

## 3. Alternatives Considered

### Direct SQL access / RAG over operational databases
- **Pros:** Fast to prototype.
- **Cons:** Bypasses business validation layers, risks prompt injection attacks, exposes internal schema details, and breaks microservice boundaries.
- **Rejected:** Unacceptable security and architectural risk.

### Autonomous end-to-end booking (including auto-charging Stripe)
- **Pros:** One-click natural language purchase.
- **Cons:** High risk of unauthorized charges, refund disputes, and user confusion.
- **Rejected:** Payment authorization must remain an explicit human decision.

### Client-side direct LLM API calls from Angular
- **Pros:** No backend `ai-service` required.
- **Cons:** Leaks AI provider API keys to client browsers, cannot leverage Eureka service discovery or internal load balancing, and lacks centralized rate limiting and audit logging.
- **Rejected:** Security and compliance violation.

## 4. Consequences

**Positive:**
- 100% enforcement of domain invariants (10 seats, 15-minute hold, session isolation, server-side JWT authorization).
- Deterministic, hallucination-free seat recommendations.
- Clean isolation: core ticketing operates perfectly even if AI APIs suffer outages or latency spikes.
- Model-agnostic integration through Spring AI abstractions.

**Trade-offs:**
- Adds an additional service runtime footprint (`ai-service` on port `8090`).
- Multi-step confirmation requires conversational context state tracking.

## 5. Implementation Notes

- Service directory: `backend/services/ai-service`.
- Framework: Spring AI (compatible with Spring Boot 4.x / Spring Cloud 2025.1).
- Context propagation: All inter-service REST calls from tools must propagate the user's Bearer JWT and `X-Correlation-Id`.
