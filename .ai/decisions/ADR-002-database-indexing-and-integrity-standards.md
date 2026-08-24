# ADR-002: Database Indexing, Constraint Standards, and Concurrency Integrity

- **Date:** 2026-08-24
- **Author(s):** SeatFlow Architecture Team
- **Driven by Task:** Production Database Hardening & Indexing Architecture
- **Supersedes:** N/A

## 1. Status
`ACCEPTED`

## 2. Context
In a high-concurrency real-time event ticketing platform like SeatFlow, data integrity, zero double-booking, and sub-millisecond query performance under peak traffic are critical requirements. While application-level validation and distributed locks provide defensive layers, the relational database (PostgreSQL 16+) is the ultimate source of truth.

Without formal database-level unique constraints, partial indexes, check constraints, and aligned JPA `@Table` mapping standards:
1. Race conditions during hot-event ticket sales could result in catastrophic double-booking.
2. Background polling processes (e.g. 15-minute hold expiration sweeper, Transactional Outbox publishers) would perform expensive sequential scans across growing tables.
3. Inconsistent constraint and index naming would make database troubleshooting and Flyway migration maintenance error-prone.
4. JPA entity definitions might deviate from the physical database schema, degrading performance or failing to communicate DDL intent.
5. Inappropriate Lombok annotations (such as `@Data` or implicit `@EqualsAndHashCode`) on JPA entities could trigger `LazyInitializationException`, recursive hash code loops, or accidental N+1 queries during logging.

## 3. Decision
We establish a mandatory, platform-wide **Database Indexing, Constraint, and Integrity Architecture** across all PostgreSQL catalogs and Spring Data JPA entities:

### 3.1 Strict DDL Naming Conventions
All database objects must strictly follow explicit prefix conventions:
- **Primary Keys:** `pk_<table>` (e.g. `pk_users`, `pk_seat_holds`)
- **Foreign Keys:** `fk_<table>_<referenced_table>` (e.g. `fk_venue_sections_venues`, `fk_seat_holds_reservations`)
- **Unique Constraints:** `uq_<table>_<columns>` (e.g. `uq_users_email`, `uq_seats_section_row_seat`)
- **Standard & Compound B-Tree Indexes:** `idx_<table>_<columns>` (e.g. `idx_events_status_date`, `idx_res_customer_email`)
- **Partial / Filtered Indexes:** `idx_<table>_<purpose>` with explicit `WHERE` clauses (e.g. `idx_res_pending_expires_at`, `idx_user_outbox_unpub`)
- **Check Constraints:** `chk_<table>_<field_or_rule>` (e.g. `chk_res_seat_count`, `chk_pricing_price`, `chk_events_status`)

### 3.2 Core Architectural Mechanisms & Constraints

1. **Zero Double-Booking Guarantee via Partial Unique Index:**
   A physical seat must never be held or sold concurrently for the same event. We enforce this at the database engine level via a partial unique index on `seat_holds`:
   ```sql
   CREATE UNIQUE INDEX uq_active_seat_hold ON seat_holds(event_id, seat_id)
   WHERE status IN ('HELD', 'SOLD');
   ```
   Concurrent attempts to hold an already held or sold seat immediately trigger PostgreSQL error code `23505` (`unique_violation`), which the application translates to `ConflictException(ErrorCode.SEAT_ALREADY_RESERVED)`.

2. **Hold Expiration Sweeper Performance (Index-Only Scans):**
   The background sweeper continuously polls for expired holds (`WHERE status = 'PENDING' AND expires_at < now()`). We add a filtered index:
   ```sql
   CREATE INDEX idx_res_pending_expires_at ON reservations(expires_at ASC)
   WHERE status = 'PENDING';
   ```
   This ensures \(O(\log N)\) lookups and completely bypasses completed or cancelled reservations.

3. **Domain Invariant Enforcement via Check Constraints:**
   - **Max 10 Seats per Reservation:** `CONSTRAINT chk_res_seat_count CHECK (seat_count >= 1 AND seat_count <= 10)`
   - **Non-negative Prices:** `CONSTRAINT chk_pricing_price CHECK (price >= 0.00)`
   - **Valid Currency Code:** `CONSTRAINT chk_pricing_currency CHECK (length(currency) = 3)`
   - **Email Syntax Validation:** `CONSTRAINT chk_res_email_format CHECK (customer_email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')`
   - **Seat Coordinates:** `CONSTRAINT chk_seats_grid_x CHECK (grid_x >= 0)` and `CONSTRAINT chk_seats_grid_y CHECK (grid_y >= 0)`
   - **Grid Spatial Collision Prevention:** `CONSTRAINT uq_seats_section_grid UNIQUE (section_id, grid_x, grid_y)`

4. **Transactional Outbox Polling Index:**
   All 7 microservices running the Transactional Outbox pattern maintain an identical partial index for fast polling:
   ```sql
   CREATE INDEX idx_<service>_outbox_unpub ON outbox_events(created_at ASC)
   WHERE published_at IS NULL;
   ```

5. **Optimistic Concurrency Control (OCC):**
   All mutable root aggregates (`events`, `reservations`, `payments`, `tickets`, `venues`) must include `version BIGINT NOT NULL DEFAULT 0` mapped to `@Version private Long version;` in JPA to prevent lost updates under race conditions.

6. **Ticket Validation & Gate Scanner Audit:**
   Introduce `ticket_validations` table in `seatflow_ticket` to record every scan event at entry gates with scanner device ID, timestamp, and verification outcome (`SUCCESS`, `ALREADY_USED`, `INVALID`, `CANCELLED`).

### 3.3 JPA Entity & Hibernate Standards Checklist
To ensure application code perfectly mirrors database-level guarantees without performance degradation:

1. **Explicit `@Table` Metadata:** Always define `name`, `uniqueConstraints`, and `indexes` matching Flyway DDL:
   ```java
   @Entity
   @Table(
       name = "reservations",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_reservations_idempotency_key", columnNames = {"idempotency_key"})
       },
       indexes = {
           @Index(name = "idx_res_pending_expires_at", columnList = "expires_at"),
           @Index(name = "idx_res_event_status", columnList = "event_id, status"),
           @Index(name = "idx_res_user_status", columnList = "user_id, status"),
           @Index(name = "idx_res_customer_email", columnList = "customer_email"),
           @Index(name = "idx_res_created_at", columnList = "created_at")
       }
   )
   ```
2. **Schema Invariant Reflection (`@Check`):** Reflect database `CHECK` constraints on entities via Hibernate `@Check(constraints = "...")`.
3. **`@DynamicUpdate` for High-Write Aggregates:** Generates targeted SQL `UPDATE` statements containing only dirty columns, reducing row lock contention.
4. **Safe Lombok Equality (`@EqualsAndHashCode`):** Always `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` with `@EqualsAndHashCode.Include` exclusively on the `@Id` field. **NEVER `@Data` on JPA entities.**
5. **Safe Logging (`@ToString`):** Use `@ToString(onlyExplicitlyIncluded = true)` or exclude entity associations to avoid triggering lazy-loading or N+1 queries during logger calls.
6. **PostgreSQL JSONB Type Mapping:** Use `@JdbcTypeCode(SqlTypes.JSON)` for JSONB fields (e.g. `payload` in `OutboxEvent`).
7. **Column Immutability:** Explicitly declare `updatable = false` on immutable identifiers (`id`, `createdAt`, `idempotencyKey`, `eventId`, `userId`).
8. **Monetary Precision:** Use `BigDecimal` with `@Column(precision = 10, scale = 2, nullable = false)`.
9. **Protected Default Constructor:** Always `@NoArgsConstructor(access = AccessLevel.PROTECTED)` as required by JPA.
10. **String Enum Persistence:** Always `@Enumerated(EnumType.STRING)`, never `ORDINAL`.

## 4. Alternatives Considered
1. **Application-Only Validation (e.g. Spring `@Valid` & Service Locks Only):**
   - *Pros:* Simpler initial SQL schema.
   - *Cons:* Vulnerable to distributed race conditions, multi-instance concurrency bugs, and manual DB manipulation.
   - *Reason for rejection:* Unacceptable for financial and ticketing transactions.

2. **Redis Distributed Locks as Sole Concurrency Barrier:**
   - *Pros:* Offloads lock contention from PostgreSQL.
   - *Cons:* Redis is an in-memory transient store; network partitions or failover can allow double booking. PostgreSQL must remain the authoritative barrier.
   - *Reason for rejection:* Redis acts as a fast-fail pre-check, but PostgreSQL partial unique constraints provide the ACID source of truth.

3. **Full Table B-Tree Indexes without `WHERE` Filtering:**
   - *Pros:* Standard syntax supported across all SQL databases.
   - *Cons:* Enormous index bloat on high-volume tables where 95%+ of rows are terminal (`CONFIRMED`, `RELEASED`, `PUBLISHED`).
   - *Reason for rejection:* PostgreSQL partial indexes offer orders of magnitude smaller index size and cache footprint.

## 5. Consequences
### Positive:
- **Zero Double-Booking Guarantee:** Physically impossible for two active holds/sales to coexist for the same seat and event.
- **Ultra-Fast Polling:** Hold sweeper and Outbox publishers scan only pending records, preserving database CPU and memory.
- **Enterprise Consistency:** Uniform naming across all 7 databases accelerates onboarding, debugging, and migration reviews.
- **Schema-Level Invariant Enforcement:** Core rules (10-seat limit, email syntax, non-negative amounts) are enforced even if bypassed at the API layer.
- **Robust JPA Layer:** Elimination of `LazyInitializationException` and unnecessary dirty-checking update overhead.

### Negative / Trade-offs:
- Minor write overhead on inserts/updates due to index maintenance (negligible compared to correctness benefits).
- Developers must maintain `@Table` annotations synchronized with Flyway migration scripts.

## 6. Implementation Notes
- **Impacted Microservices:** All 7 backend microservices (`user-service`, `seat-map-service`, `event-service`, `reservation-service`, `payment-service`, `ticket-service`, `notification-service`).
- **Impacted Shared Modules:** `common-observability` (translating PostgreSQL constraint violations into user-friendly `ApiErrorResponse`).
- **Related Specs:** `.ai/architecture/03-database-models.md`, `backend/AGENTS.md`, `.ai/SeatFlow-Architecture-and-Implementation-Spec.md`.
