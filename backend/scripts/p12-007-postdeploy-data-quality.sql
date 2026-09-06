-- P12-007 post-deploy data-quality query set (REV-006).
--
-- Every query below must return ZERO rows. Run after the deploy window closes
-- (see the DEPLOY ORDERING / FREEZE notes in event-service V4 and
-- reservation-service V8): the migrate-time gates are point-in-time checks,
-- so a sessionless/orphan row committed concurrently with the deploy (or
-- rolled in late from an old replica) is invisible to them. Runtime
-- consumers are fail-closed (ticket consumer rejects null sessions, payment
-- derives session from trusted reservation state, realtime drops legacy
-- destinations), so any hit here is a data-repair input, never a silent
-- booking semantic.
--
-- Run sections 1-2 against the RESERVATION database, section 3 against the
-- TICKET database, section 4 against the PAYMENT database.

-- 1. Reservation inventory without session identity (reservation DB).
SELECT id FROM reservations WHERE event_session_id IS NULL;

-- 2. Seat holds without session identity (reservation DB).
SELECT id FROM seat_holds WHERE event_session_id IS NULL;

-- 3. Tickets without session identity (ticket DB).
-- Pre-P12-004 rows are NULLABLE BY DESIGN for display of legacy purchases;
-- a hit here means "legacy display row", not a bookable orphan, but each hit
-- must be triaged (backfill display snapshot or confirm pre-cutover origin).
SELECT id FROM tickets WHERE event_session_id IS NULL;

-- 4. Payments without session identity (payment DB).
-- Same nullable-by-design status as tickets (pre-P12-004 rows); triage each
-- hit the same way. Payment authorization never reads this column (it uses
-- server-derived reservation amount/currency), so hits cannot alter money
-- movement; they only affect audit/correlation rendering.
SELECT id FROM payments WHERE event_session_id IS NULL;
