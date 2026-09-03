# TASK-P11-003: Define Typed Layout Contracts and Deterministic Validation

## 1. Task Metadata

- **Task ID:** `TASK-P11-003`
- **Git Branch:** `feat/p11-003-layout-contracts-validation`
- **Target Module:** `backend/services/seat-map-service`
- **Phase:** `Phase 11 - Advanced Venue & Seat Map Designer`
- **Related Specs:** `.ai/architecture/06-api-contracts.md` §1 and §2.2; `.ai/architecture/09-post-mvp-evolution.md` §3.3
- **Related ADRs:** `.ai/decisions/ADR-010-advanced-seat-layout-model.md`
- **Status:** `READY FOR IMPLEMENTATION`
- **Complexity:** 4/5
- **Failure Risk:** High
- **Verification Strength:** Strong
- **Affected Invariants:** stable identities; venue capacity; normalized ownership; duplicate row/seat prevention; active-position uniqueness; bounded visual geometry; legacy API compatibility
- **Primary Failure Modes:** untyped opaque payloads; duplicate IDs/labels/positions; out-of-bounds seats; capacity overflow; accepting foreign seat/section IDs; unsupported element data; additive response fields breaking legacy fields
- **Required Review Depth:** Substantive

## 2. Objective & Critical Invariants

Define one typed editor snapshot request and additive read response, then validate every structural and cross-record rule before persistence code mutates an entity.

- [ ] Request records are typed Java records; no `Map<String,Object>` layout payload is allowed.
- [ ] Existing seat/section IDs are optional only to distinguish new records from updates; supplied IDs must belong to the target venue.
- [ ] Active-seat count never exceeds `venues.capacity`.
- [ ] `grid_x/grid_y`, row label, and seat number remain required for compatibility.
- [ ] Continuous positions and transforms remain within the V5 bounds.
- [ ] Public response fields already consumed by event-service remain present and unchanged in type.

## 3. Dependencies

- `TASK-P11-002` entity/repository model.
- Accepted ADR-010 with the exact coordinate, element, version, and deactivation decisions.
- Existing `ApiErrorResponse`, `ValidationException`, `ErrorCode.INVALID_REQUEST`, and `ConflictException` from common modules; do not create a service error DTO.

## 4. Exact File Inventory

- `[NEW]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/web/dto/request/SaveVenueLayoutRequest.java`
- `[NEW]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/web/dto/response/LayoutElementResponse.java`
- `[MODIFY]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/web/dto/response/SeatResponse.java`
- `[MODIFY]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/web/dto/response/SectionLayoutResponse.java`
- `[MODIFY]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/web/dto/response/VenueSeatMapLayoutResponse.java`
- `[NEW]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/service/LayoutValidationService.java`
- `[NEW]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/service/impl/LayoutValidationServiceImpl.java`
- `[NEW]` `backend/services/seat-map-service/src/test/java/com/seatflow/seatmap/service/LayoutValidationServiceImplTest.java`

## 5. DTO and Validation Contracts

### 5.1 Request Record

`SaveVenueLayoutRequest` must contain nested typed records so the entire request contract remains in one bounded file:

```java
public record SaveVenueLayoutRequest(
    @NotNull @PositiveOrZero Long layoutVersion,
    @NotNull List<@Valid SectionUpsert> sections,
    @NotNull List<@Valid LayoutElementUpsert> elements
) {
    public record SectionUpsert(
        UUID sectionId,
        @NotBlank @Size(max = 100) String name,
        @NotNull @Min(1) Integer rowCount,
        @NotNull @Min(1) Integer colCount,
        @NotNull Boolean isActive,
        @NotNull BigDecimal positionX,
        @NotNull BigDecimal positionY,
        @NotNull BigDecimal width,
        @NotNull BigDecimal height,
        @NotNull BigDecimal rotationDeg,
        @NotNull Integer zIndex,
        JsonNode shapeMetadata,
        @NotNull List<@Valid SeatUpsert> seats
    ) {}

    public record SeatUpsert(
        UUID seatId,
        @NotBlank @Size(max = 10) String rowLabel,
        @NotNull @Positive Integer seatNumber,
        @NotNull @PositiveOrZero Integer gridX,
        @NotNull @PositiveOrZero Integer gridY,
        @NotNull BigDecimal positionX,
        @NotNull BigDecimal positionY,
        @NotNull Boolean isActive
    ) {}

    public record LayoutElementUpsert(
        UUID elementId,
        @NotNull LayoutElementType type,
        @Size(max = 255) String label,
        @NotNull @Valid Geometry geometry,
        @NotNull Integer zIndex
    ) {}

    public record Geometry(
        @NotNull BigDecimal x,
        @NotNull BigDecimal y,
        @NotNull BigDecimal width,
        @NotNull BigDecimal height,
        @NotNull BigDecimal rotationDeg
    ) {}
}
```

All records require `@Schema` documentation. No request field may accept arbitrary business attributes in JSONB.

### 5.2 Additive Response Contract

- `SeatResponse` retains `seatId,rowLabel,seatNumber,gridX,gridY,isActive` and adds non-null `positionX,positionY`.
- `SectionLayoutResponse` retains `sectionId,name,rowCount,colCount,seats` and adds `isActive,positionX,positionY,width,height,rotationDeg,zIndex,shapeMetadata`.
- `VenueSeatMapLayoutResponse` retains `venueId,name,capacity,totalConfiguredSeats,sections` and adds non-null `layoutVersion` plus `elements`.
- `LayoutElementResponse` is `elementId,type,label,geometry,zIndex`; `geometry` uses the same typed `x,y,width,height,rotationDeg` fields as the request.
- All additions are JSON-additive. No existing field is renamed, removed, or changed in scalar type.

### 5.3 Exact Cross-Record Validation Rules

`LayoutValidationService.validate(Venue venue, SaveVenueLayoutRequest request, ExistingLayoutIds ids)` returns normally or throws `ValidationException(..., ErrorCode.INVALID_REQUEST)` before mutation.

1. Trim section names and row labels for comparison and persistence; reject blank-after-trim values.
2. Section names are case-insensitively unique across active sections.
3. Non-null section IDs are unique in the request and must be in the target venue's existing section-ID set.
4. Non-null seat IDs are unique across the complete request and must be in the matching existing section; moving an existing seat between sections is rejected.
5. Within every section, `(upper(rowLabel), seatNumber)` and `(gridX,gridY)` are unique across all submitted seats.
6. `(positionX,positionY)` is unique across active submitted seats in that section after `stripTrailingZeros()` normalization.
7. Section `positionX/positionY` are `0..100000`; width/height are `>0..100000`; rotation is `-180..180`; z-index is `-1000..1000`.
8. Seat `positionX/positionY` are `0..section.width` and `0..section.height`; `gridX < colCount`; `gridY < rowCount`.
9. `shapeMetadata` is null or a JSON object.
10. Total active seats in active sections is `<= venue.capacity`; active seats inside an inactive section are rejected.
11. Element geometry x/y is `0..100000`, width/height is `>0..100000`, rotation is `-180..180`, and z-index is `-1000..1000`.
12. `LABEL` requires a non-blank label; other types permit null/blank labels; all labels are trimmed and limited to 255 characters.
13. Element IDs are unique and any non-null ID must belong to the target venue.
14. Requests may contain zero sections/elements; they may not contain null list entries.

`ExistingLayoutIds` is a service-local immutable record, not a web DTO.

## 6. Implementation Sequence

1. Add request and response record fields with Jakarta validation and OpenAPI schemas.
2. Update mapper compilation targets only as required to compile; persistence mapping remains in TASK-P11-004.
3. Add the validation service interface and immutable existing-ID snapshot.
4. Implement validation in the numbered order above and emit messages naming the rejected field/index without echoing full JSON.
5. Add parameterized boundary and duplicate tests.
6. Add an additive JSON serialization test proving all legacy fields and new typed fields round-trip.

## 7. Negative and Edge Cases

- `0`, `100000`, `-180`, and `180` accepted where inclusive; values just beyond rejected.
- Scale variants such as `1.0` and `1.000` count as the same active position.
- Duplicate row labels differing only by case/outer whitespace are rejected.
- Same row/seat label in two different sections is accepted.
- Inactive seats may share continuous positions but still may not duplicate row/seat or grid identity because legacy database constraints cover all rows.
- Existing foreign IDs and moved seat IDs are rejected.
- Active count exactly equal to capacity is accepted; capacity + 1 is rejected.
- Empty typed layout is accepted as a request shape; TASK-P11-004 defines resulting deactivation semantics.

## 8. Security and Observability

- Controller authorization is implemented in TASK-P11-004; validation must never trust a frontend ADMIN guard.
- Validation logs may include venue ID, layout version, section/seat counts, and rule name; they must not log full geometry JSON or JWT data.
- Use common exceptions only; no local `@RestControllerAdvice`.

## 9. Tests Mapped to Risks

| Risk | Required proof |
|---|---|
| Capacity violation | Exact-capacity succeeds; one extra active seat fails. |
| Duplicate identity | Parameterized tests cover section ID/name, seat ID, row/number, grid, position, and element ID. |
| Foreign/moved IDs | IDs from another venue/section fail. |
| Bounds drift | Inclusive boundaries and just-outside values for every numeric field. |
| Opaque geometry | JSON serialization shows typed geometry and rejects non-object section metadata. |
| Legacy contract drift | Round-trip assertion includes unchanged legacy field names/types. |

## 10. Exact Verification Commands

```bash
cd backend
mvn -pl services/seat-map-service -am -Dtest=LayoutValidationServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl services/seat-map-service -am test
```

Expected result: both commands exit 0; every numbered validation rule has a named test.

## 11. Review Focus

- Typed records versus opaque JSON/maps.
- Exact duplicate normalization and inclusive numeric boundaries.
- Capacity calculation excludes inactive seats and rejects active seats under inactive sections.
- Existing ID ownership checks occur before persistence.
- Additive compatibility of existing response fields.

## 12. Acceptance Criteria

- [ ] Every request branch has an exact validation outcome and error code.
- [ ] All existing response fields remain source- and JSON-compatible.
- [ ] Invalid layouts fail before entity mutation.
- [ ] Duplicate and boundary test matrix passes.
- [ ] Serialization round-trip test passes.
- [ ] Targeted and module verification commands exit 0.
