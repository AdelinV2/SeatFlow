# TASK-P11-004: Implement Atomic Versioned Layout Read, Validate, and Save APIs

## 1. Task Metadata

- **Task ID:** `TASK-P11-004`
- **Git Branch:** `feat/p11-004-atomic-versioned-layout-api`
- **Target Module:** `backend/services/seat-map-service`
- **Phase:** `Phase 11 - Advanced Venue & Seat Map Designer`
- **Related Specs:** `.ai/architecture/06-api-contracts.md` §1 and §2.2; `.ai/architecture/09-post-mvp-evolution.md` §3.3
- **Related ADRs:** `.ai/decisions/ADR-010-advanced-seat-layout-model.md`
- **Status:** `READY FOR IMPLEMENTATION`
- **Complexity:** 5/5
- **Failure Risk:** Critical
- **Verification Strength:** Strong
- **Affected Invariants:** atomic PostgreSQL save; stable seat/section IDs; version conflict prevention; capacity; normalized ownership; soft deactivation; server-side ADMIN authorization; public legacy compatibility
- **Primary Failure Modes:** partial save; lost update; seat UUID regeneration; hard deletion of referenced seats; version increment on failure; unauthorized mutation; public endpoint leaking inactive sections; element replacement outside the transaction
- **Required Review Depth:** Critical

## 2. Objective & Critical Invariants

Expose an ADMIN editor read endpoint, a no-write validation endpoint, and one atomic full-layout save endpoint guarded by `layout_version`. Convert the legacy section delete action to soft deactivation.

- [ ] Exactly one database transaction covers validation, all section/seat/element changes, and the layout-version increment.
- [ ] A stale version returns `409` with existing stable code `SF_409_CONFLICT` (`ErrorCode.CONFLICT`).
- [ ] Failed validation or persistence rolls back every mutation and leaves `layout_version` unchanged.
- [ ] Existing seat/section IDs are updated in place and never replaced.
- [ ] Existing sections/seats omitted from a full snapshot are deactivated, never deleted.
- [ ] Layout elements omitted from a full snapshot may be deleted because they are non-bookable and have no external references.
- [ ] All `/api/admin/**` endpoints enforce `ROLE_ADMIN` server-side.
- [ ] Public `GET /api/venues/{venueId}/layout` remains available and retains legacy fields.

## 3. Dependencies

- TASK-P11-001 through TASK-P11-003.
- ADR-010 accepted, including full-snapshot omission semantics and use of `SF_409_CONFLICT` for stale editor saves.
- Existing global `ApiErrorResponse`; do not add local exception advice.

## 4. Exact File Inventory

- `[NEW]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/service/VenueLayoutService.java`
- `[NEW]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/service/impl/VenueLayoutServiceImpl.java`
- `[MODIFY]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/service/impl/SeatMapLayoutServiceImpl.java`
- `[MODIFY]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/service/VenueSectionService.java`
- `[MODIFY]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/service/impl/VenueSectionServiceImpl.java`
- `[MODIFY]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/web/controller/AdminVenueController.java`
- `[MODIFY]` `backend/services/seat-map-service/src/test/java/com/seatflow/seatmap/web/controller/AdminVenueControllerTest.java`
- `[NEW]` `backend/services/seat-map-service/src/test/java/com/seatflow/seatmap/service/VenueLayoutServiceImplTest.java`
- `[MODIFY]` `backend/services/seat-map-service/src/test/java/com/seatflow/seatmap/service/VenueSectionServiceImplTest.java`
- `[MODIFY]` `backend/services/seat-map-service/src/test/java/com/seatflow/seatmap/integration/SeatMapServiceIntegrationTest.java`

## 5. API, Transaction, and State Contracts

### 5.1 Endpoints

| Method | Path | Success | Failure contracts |
|---|---|---:|---|
| `GET` | `/api/admin/venues/{venueId}/layout` | `200` complete editor layout including inactive rows and all elements | `401`, `403`, `404` |
| `POST` | `/api/admin/venues/{venueId}/layout/validation` | `204` when the submitted snapshot is valid; no write | `400 SF_400_INVALID_REQUEST`, `401`, `403`, `404` |
| `PUT` | `/api/admin/venues/{venueId}/layout` | `200` saved complete editor layout with `layoutVersion = request.layoutVersion + 1` | `400 SF_400_INVALID_REQUEST`, `401`, `403`, `404`, `409 SF_409_CONFLICT` |

All request bodies use `SaveVenueLayoutRequest`; successful GET/PUT bodies use `VenueSeatMapLayoutResponse`. Annotate controller methods with `@PreAuthorize("hasRole('ADMIN')")` even though the class and filter chain already guard the route, so method-level tests prove authorization.

### 5.2 Service Signatures

```java
VenueSeatMapLayoutResponse getEditableLayout(UUID venueId);
void validateLayout(UUID venueId, SaveVenueLayoutRequest request);
VenueSeatMapLayoutResponse saveLayout(UUID venueId, SaveVenueLayoutRequest request);
```

`saveLayout` and legacy `deactivateSection` are `@Transactional`; reads/validation are `@Transactional(readOnly = true)`.

### 5.3 Concurrency and Save Algorithm

1. Load the venue through a repository method annotated `@Lock(PESSIMISTIC_WRITE)`.
2. Compare the locked row's `layoutVersion` to `request.layoutVersion` before any mutation.
3. On mismatch, throw `new ConflictException("Layout version ...", ErrorCode.CONFLICT)`; the API error code is exactly `SF_409_CONFLICT`.
4. Load all existing section, seat, and element IDs for that venue and run TASK-P11-003 validation.
5. Update matched sections/seats in place; create only records whose IDs are null.
6. Mark existing omitted sections inactive and mark all their seats inactive. Mark omitted existing seats inactive. Never call `delete` for an existing section or seat.
7. Replace/remove omitted layout elements within the transaction; reject element IDs owned by another venue.
8. Recalculate active-seat count from the post-mutation entity state and reject if it exceeds venue capacity.
9. Increment `layoutVersion` exactly once, flush, and map the committed layout response.
10. Any exception rolls back entity changes, element deletions, and version increment.

New seats use generated UUIDs once. A retry with the stale version returns 409 rather than creating a second set.

### 5.4 Public and Legacy Semantics

- Public layout reads include only active sections but may include inactive seats inside those sections so seat IDs/status remain explicit; the customer renderer continues filtering `isActive`.
- The legacy `DELETE /api/admin/venues/{venueId}/sections/{sectionId}` keeps `204` but now sets the section and all seats inactive and increments `layout_version` under the venue lock. Update its OpenAPI description from deletion to deactivation.
- The legacy section-create and seat-toggle endpoints remain ADMIN-only. Their successful mutations must increment `layout_version` to invalidate stale advanced-editor snapshots.

## 6. Implementation Sequence

1. Use `VenueRepository.findByIdForLayoutUpdate` from TASK-P11-002; do not create a second transaction root.
2. Implement editor/public mapping with explicit inactive filtering differences.
3. Implement read-only validation.
4. Implement the numbered full-snapshot save algorithm.
5. Change legacy delete to deactivation and make legacy create/toggle advance layout version.
6. Add controller endpoints and OpenAPI statuses.
7. Add service tests for every branch, controller authentication/authorization tests, and PostgreSQL integration tests for rollback/concurrency.
8. Run targeted tests, then the entire seat-map module.

## 7. Negative and Edge Cases

- Unknown venue returns 404 for all three endpoints.
- Missing/invalid JWT returns 401; authenticated non-ADMIN returns 403.
- Two saves starting at version 7 yield one 200 at version 8 and one 409; no mixed state is visible.
- A request containing a foreign section/seat/element ID returns 400 without writes.
- Empty sections/elements deactivates all normalized sections/seats and removes elements; IDs remain in the database.
- Duplicate generation or active-position collision returns 400/409 according to whether caught by prevalidation/database constraint, with transaction rollback.
- Serialization failure or late constraint failure leaves the pre-save snapshot and version intact.
- No-op valid save still increments the version once, giving each accepted editor commit a unique token.

## 8. Security and Observability

- Require ADMIN in both security filter rules and controller method annotations.
- Log successful save with venue ID, old/new layout version, section/seat/element counts, and duration; never log the full snapshot or JWT.
- Log stale conflict at WARN with venue ID and expected/current versions.
- No direct Kafka publish is added. Phase 11 layout persistence does not introduce a new domain event in ADR-010.

## 9. Tests Mapped to Risks

| Risk | Required proof |
|---|---|
| Partial write | Force a late unique/check failure and compare every table plus version to pre-save state. |
| Lost update | Real PostgreSQL two-thread test proves one success/one `SF_409_CONFLICT`. |
| Stable IDs | Move, rename, renumber, and deactivate existing seats; assert exact UUIDs remain. |
| Historical deletion | Omit sections/seats and assert rows remain with `is_active=false`. |
| Authorization | MockMvc tests for 401, 403, and ADMIN 2xx on GET/POST/PUT. |
| Capacity/duplicates | Invalid snapshots return 400 and do not increment version. |
| Legacy compatibility | Existing endpoints still work, increment layout version, and public JSON retains grid fields. |

## 10. Exact Verification Commands

```bash
cd backend
mvn -pl services/seat-map-service -am -Dtest=VenueLayoutServiceImplTest,VenueSectionServiceImplTest,AdminVenueControllerTest,SeatMapServiceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl services/seat-map-service -am test
```

Expected result: both commands exit 0; concurrency test observes exactly one 200-equivalent save and one stale conflict, and rollback assertions show zero partial changes.

## 11. Review Focus

- Critical review of lock acquisition, version comparison, transaction boundary, flush timing, and rollback proof.
- Confirm every existing seat update reuses the entity with that UUID.
- Confirm omitted normalized rows are deactivated and no cascade deletes them.
- Confirm ADMIN checks and stable common error envelope.
- Confirm public response compatibility and capacity computation.

## 12. Acceptance Criteria

- [ ] Editor GET, validation POST, and atomic PUT match the exact endpoint/status contracts.
- [ ] Stale saves return 409 with `SF_409_CONFLICT`.
- [ ] One accepted save increments layout version exactly once.
- [ ] Failed saves leave all tables and version unchanged.
- [ ] Existing IDs survive transform, rename, renumber, move, and deactivate operations.
- [ ] Legacy section deletion is now reversible deactivation.
- [ ] Non-ADMIN mutation attempts fail server-side.
- [ ] Targeted and module verification commands exit 0.
