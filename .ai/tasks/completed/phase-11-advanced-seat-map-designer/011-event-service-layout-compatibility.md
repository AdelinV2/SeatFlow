# TASK-P11-011: Propagate Advanced Geometry Through Event Seat-Map Contracts

## 1. Task Metadata

- **Task ID:** `TASK-P11-011`
- **Git Branch:** `feat/p11-011-event-layout-compatibility`
- **Target Module:** `backend/services/event-service`
- **Phase:** `Phase 11 - Advanced Venue & Seat Map Designer`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md` §4-5; `.ai/architecture/06-api-contracts.md` §2.2-2.3; `.ai/architecture/09-post-mvp-evolution.md` §3.5
- **Related ADRs:** `.ai/decisions/ADR-010-advanced-seat-layout-model.md`
- **Status:** `READY FOR IMPLEMENTATION`
- **Complexity:** 4/5
- **Failure Risk:** Critical
- **Verification Strength:** Strong
- **Affected Invariants:** Eureka/LoadBalancer seat-map call; stable section/seat IDs; event pricing by section ID; public event-seat-map compatibility; customer booking identifiers; fail-fast published-event rule
- **Primary Failure Modes:** dropping advanced fields at service boundary; changing legacy field names/types; filtering IDs needed by pricing; treating elements as seats; static service URL; customer contract no longer deserializing
- **Required Review Depth:** Critical

## 2. Objective & Critical Invariants

Extend event-service's existing seat-map client and public event seat-map DTOs additively so customer rendering receives section transforms, continuous seat positions, and non-bookable layout elements.

- [ ] `seatId` and `sectionId` values pass through byte-for-byte.
- [ ] Existing `rowCount,colCount,gridX,gridY,rowLabel,seatNumber,isActive` fields remain.
- [ ] Pricing tiers still join only by unchanged `sectionId`.
- [ ] Layout elements remain venue-level non-bookable data and never receive pricing/status.
- [ ] The existing load-balanced `http://seat-map-service` client, timeout, correlation propagation, and circuit breaker remain.
- [ ] Existing ADR-003 published/date guard remains unchanged.

## 3. Dependencies

- TASK-P11-004 public layout response contract.
- Existing `SeatMapClientImpl`, `SeatMapVenue*` records, `EventServiceImpl`, and public DTOs.
- Accepted ADR-010 additive compatibility contract.

## 4. Exact File Inventory

- `[MODIFY]` `backend/services/event-service/src/main/java/com/seatflow/event/client/SeatMapVenueLayout.java`
- `[MODIFY]` `backend/services/event-service/src/main/java/com/seatflow/event/client/SeatMapVenueSection.java`
- `[MODIFY]` `backend/services/event-service/src/main/java/com/seatflow/event/client/SeatMapVenueSeat.java`
- `[MODIFY]` `backend/services/event-service/src/main/java/com/seatflow/event/client/impl/SeatMapClientImpl.java`
- `[MODIFY]` `backend/services/event-service/src/main/java/com/seatflow/event/web/dto/response/EventSeatMapResponse.java`
- `[MODIFY]` `backend/services/event-service/src/main/java/com/seatflow/event/web/dto/response/SeatMapSectionResponse.java`
- `[MODIFY]` `backend/services/event-service/src/main/java/com/seatflow/event/web/dto/response/SeatMapSeatResponse.java`
- `[MODIFY]` `backend/services/event-service/src/main/java/com/seatflow/event/service/impl/EventServiceImpl.java`
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/client/SeatMapClientImplTest.java`
- `[MODIFY]` `backend/services/event-service/src/test/java/com/seatflow/event/service/EventServiceImplTest.java`

The venue-level layout-element records must be nested immutable records inside `SeatMapVenueLayout` and `EventSeatMapResponse` to keep this atomic adapter change within ten files.

## 5. Contract Changes

### 5.1 Internal Client Records

- `SeatMapVenueLayout` adds `Long layoutVersion` and `List<LayoutElement> elements`; nested `LayoutElement(UUID elementId,String type,String label,Geometry geometry,Integer zIndex)` and `Geometry(BigDecimal x,BigDecimal y,BigDecimal width,BigDecimal height,BigDecimal rotationDeg)`.
- `SeatMapVenueSection` adds `Boolean isActive`, transform fields, z-index, and nullable `JsonNode shapeMetadata` before its seat list.
- `SeatMapVenueSeat` adds non-null `BigDecimal positionX,positionY` while retaining grid fields.

### 5.2 Public Event Response

- `EventSeatMapResponse` adds venue-level `layoutVersion` and nested typed `layoutElements`.
- `SeatMapSectionResponse` adds section activity/transform/z-index/shape metadata.
- `SeatMapSeatResponse` adds continuous position fields.
- Existing fields stay unchanged in JSON name/type. Add compatibility constructors only where current tests/callers require source compatibility; do not hide missing production mappings with defaults.

### 5.3 Mapping

`SeatMapClientImpl` must deserialize the additive seat-map-service JSON and preserve `totalConfiguredSeats` from the response instead of recomputing it from all seats. `EventServiceImpl` maps every field and pricing tiers; element mapping is separate from section/seat mapping. Null lists become empty lists. Missing advanced fields from a legacy test stub may fall back as follows only at the client boundary: seat position from `grid*44`, section position `0`, width `colCount*44`, height `rowCount*44`, rotation/z-index `0`, active `true`, elements empty, layoutVersion `0`.

## 6. Implementation Sequence

1. Extend internal records with nested typed element/geometry records and explicit legacy fallback rules.
2. Extend client private transport records and mappings.
3. Extend public DTOs additively with OpenAPI schemas.
4. Extend `EventServiceImpl` section/seat/element mapping without altering pricing or publication guards.
5. Update client tests for new payload and legacy-grid fallback.
6. Update service tests for ID preservation, pricing association, geometry, elements, and configured-seat count.
7. Run targeted then full event-service verification.

## 7. Negative and Edge Cases

- Seat-map response with null elements maps to empty list.
- Decimal and rotated geometry survives both mapping hops.
- Legacy payload missing new properties derives deterministic 44-unit geometry.
- Unknown element type is passed as a string for forward-safe rendering but is never treated as a seat; TASK-P11-012 ignores unsupported values.
- Section with no pricing remains disabled by existing frontend price logic, not activated by geometry.
- Inactive sections returned unexpectedly remain marked inactive.
- Published/date guard still returns 404 before exposing past/unpublished event maps.

## 8. Security and Observability

- Public event seat-map remains public by design; no admin editor endpoint is proxied.
- Retain Eureka service discovery, qualified load-balanced builder, circuit breaker, timeouts, and correlation ID.
- Logs may include event/venue/layout version/counts, never the full geometry payload or authorization header.

## 9. Tests Mapped to Risks

| Risk | Required proof |
|---|---|
| ID/price drift | Assert exact IDs and tier association after both mappings. |
| Contract regression | JSON/object assertions include every legacy and advanced field. |
| Legacy breakage | Old grid-only client payload maps to 44-unit geometry and empty elements. |
| Count drift | Preserve source `totalConfiguredSeats`, excluding inactive rows as computed upstream. |
| Discovery regression | Existing client URL/config/circuit-breaker tests remain green. |

## 10. Exact Verification Commands

```bash
cd backend
mvn -pl services/event-service -am -Dtest=SeatMapClientImplTest,EventServiceImplTest,EventControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl services/event-service -am test
```

Expected result: both commands exit 0; advanced and legacy-grid mapping tests pass with unchanged IDs/pricing.

## 11. Review Focus

- Critical review of additive JSON/source compatibility and pricing join.
- Stable IDs and correct configured-seat count.
- Legacy fallback only at the transport boundary.
- No weakening of discovery, resilience, correlation, or ADR-003 guards.

## 12. Acceptance Criteria

- [ ] Event seat-map response carries advanced geometry/elements and every legacy field.
- [ ] Section pricing still attaches to the same section UUIDs.
- [ ] Legacy grid-only payloads map deterministically.
- [ ] Elements remain non-bookable and venue-level.
- [ ] Load balancing/resilience/publication behavior remains unchanged.
- [ ] Targeted and module verification commands exit 0.
