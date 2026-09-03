# TASK-P11-002: Map Advanced Layout Persistence Without Changing Identity

## 1. Task Metadata

- **Task ID:** `TASK-P11-002`
- **Git Branch:** `feat/p11-002-layout-persistence-model`
- **Target Module:** `backend/services/seat-map-service`
- **Phase:** `Phase 11 - Advanced Venue & Seat Map Designer`
- **Related Specs:** `.ai/architecture/03-database-models.md` §1.3 and §2.2; `.ai/architecture/09-post-mvp-evolution.md` §3.2
- **Related ADRs:** `.ai/decisions/ADR-010-advanced-seat-layout-model.md`
- **Status:** `DONE`
- **Complexity:** 3/5
- **Failure Risk:** High
- **Verification Strength:** Strong
- **Affected Invariants:** stable UUIDs; JPA/Flyway parity; normalized ownership; active-seat uniqueness; JSONB visual-only rule
- **Primary Failure Modes:** entity/DDL nullability mismatch; accidental orphan removal; JSON serialization drift; repository returning inactive sections to public callers; position queries ordered by legacy grid only
- **Required Review Depth:** Substantive

## 2. Objective & Critical Invariants

Map the V5 schema to JPA entities and repository methods while preserving existing entity identity and legacy grid access.

- [ ] Existing `Venue`, `VenueSection`, and `Seat` primary-key mappings remain UUID based.
- [ ] `Venue.version` remains the JPA aggregate version; `Venue.layoutVersion` is a distinct editor token.
- [ ] `VenueSection.isActive` and `Seat.isActive` are scalar relational fields.
- [ ] `VenueLayoutElement` is non-bookable and cannot own seats.
- [ ] JSONB fields use Hibernate native JSON mapping and contain visual data only.
- [ ] Existing section/seat relationships do not gain new `orphanRemoval` behavior beyond the current mapping.

## 3. Dependencies

- `TASK-P11-001` migration must be merged first.
- ADR-010 must be accepted with the schema and soft-deactivation decisions listed in TASK-P11-001.
- Follow `backend/AGENTS.md` entity equality, Lombok, JSONB, and Flyway parity rules.

## 4. Exact File Inventory

- `[MODIFY]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/model/entity/Venue.java`
- `[MODIFY]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/model/entity/VenueSection.java`
- `[MODIFY]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/model/entity/Seat.java`
- `[NEW]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/model/entity/VenueLayoutElement.java`
- `[NEW]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/model/enums/LayoutElementType.java`
- `[NEW]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/repository/VenueLayoutElementRepository.java`
- `[MODIFY]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/repository/VenueRepository.java`
- `[MODIFY]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/repository/VenueSectionRepository.java`
- `[MODIFY]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/repository/SeatRepository.java`
- `[NEW]` `backend/services/seat-map-service/src/test/java/com/seatflow/seatmap/repository/AdvancedLayoutPersistenceTest.java`

## 5. Persistence Contracts

### 5.1 Entity Fields

- `Venue`: `@Column(name="layout_version", nullable=false) Long layoutVersion`, builder default `0L`; retain existing `@Version Long version`.
- `VenueSection`: `Boolean isActive`; `BigDecimal positionX`, `positionY`, `width`, `height`, `rotationDeg`; `Integer zIndex`; nullable `JsonNode shapeMetadata` mapped with `@JdbcTypeCode(SqlTypes.JSON)` and `columnDefinition="jsonb"`.
- `Seat`: `BigDecimal positionX`, `positionY`; `@UpdateTimestamp Instant updatedAt`; retain non-null `gridX/gridY`.
- `VenueLayoutElement`: UUID id, lazy non-updatable `Venue venue`, `LayoutElementType type` with `EnumType.STRING`, nullable 255-character label, non-null `JsonNode geometry`, z-index, creation/update timestamps, Hibernate-safe equality/hash code, and no section/seat association.
- `LayoutElementType`: exactly `STAGE`, `AISLE`, `LABEL`, `BARRIER`, `DECORATION`.

### 5.2 Repository Signatures

```java
List<VenueSection> findByVenueIdAndIsActiveTrueOrderByZIndexAscNameAsc(UUID venueId);
List<VenueSection> findByVenueIdOrderByZIndexAscNameAsc(UUID venueId);

@Query("""
    SELECT s FROM Seat s
    WHERE s.section.id = :sectionId
    ORDER BY s.positionY ASC, s.positionX ASC, s.id ASC
    """)
List<Seat> findBySectionIdForEditor(UUID sectionId);

@Query("""
    SELECT s FROM Seat s
    WHERE s.section.venue.id = :venueId AND s.section.isActive = true AND s.isActive = true
    ORDER BY s.section.zIndex ASC, s.positionY ASC, s.positionX ASC, s.id ASC
    """)
List<Seat> findActiveSeatsForVenueLayout(UUID venueId);

List<VenueLayoutElement> findByVenueIdOrderByZIndexAscIdAsc(UUID venueId);
void deleteByVenueIdAndIdIn(UUID venueId, Collection<UUID> ids);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT v FROM Venue v WHERE v.id = :venueId")
Optional<Venue> findByIdForLayoutUpdate(UUID venueId);
```

Keep existing repository signatures that current callers still compile against until TASK-P11-004 migrates those callers.

## 6. Implementation Sequence

1. Add the enum and element entity matching V5 column names and constraints.
2. Extend `Venue`, `VenueSection`, and `Seat` with exact V5 fields.
3. Add repository methods for active public reads and complete editor reads.
4. Add persistence tests that save and reload section transforms, seat continuous positions, element geometry, activity flags, and layout version.
5. Assert legacy grid repository methods still return the same seat IDs and ordering.
6. Assert active repository methods exclude deactivated sections/seats while editor methods retain them.

## 7. Negative and Edge Cases

- Null required geometry fields fail before or at flush.
- Null `shapeMetadata` and valid object metadata round-trip.
- Element JSON arrays/strings are rejected by the V5 check.
- A deactivated section and its seats remain retrievable through editor queries.
- Active seats at duplicate continuous positions fail at flush; an inactive seat at the same position remains persistable.
- A layout element cannot reference a venue that does not exist.

## 8. Security and Observability

- Repositories are internal and do not replace controller ADMIN authorization.
- Entity `toString()` must not traverse venue/section collections or print full geometry payloads.
- Tests must not use or log secrets.

## 9. Tests Mapped to Risks

| Risk | Required proof |
|---|---|
| Entity/DDL mismatch | Persist and reload every new field against PostgreSQL Testcontainers. |
| Hidden identity replacement | Update geometry and assert UUIDs and foreign keys are unchanged. |
| Inactive leakage | Compare active-read and editor-read result sets. |
| Invalid JSON/type | Flush non-object geometry and unsupported enum SQL; assert rejection. |
| Ordering instability | Assert `(zIndex, positionY, positionX, id)` order. |

## 10. Exact Verification Commands

```bash
cd backend
mvn -pl services/seat-map-service -am -Dtest=AdvancedLayoutPersistenceTest,SeatRepositoryTest,VenueSectionRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl services/seat-map-service -am test
```

Expected result: both commands exit 0 and the targeted repository tests show unchanged IDs after geometry updates.

## 11. Review Focus

- Exact JPA/Flyway name, type, nullability, index, and enum parity.
- No entity `@Data`, unsafe equality, eager collection, or business relation inside element JSON.
- Public versus editor repository filtering.
- Retention of legacy grid queries until their callers migrate.

## 12. Acceptance Criteria

- [ ] All V5 columns map with matching Java types and nullability.
- [ ] Geometry updates preserve section and seat UUIDs.
- [ ] Editor queries return inactive normalized rows; active queries exclude them.
- [ ] Layout-element geometry round-trips as object JSON.
- [ ] Existing seat-map repository tests remain green.
- [ ] Targeted and module verification commands exit 0.

## 13. Completion Record

- **Completed:** 2026-09-03
- **Review:** APPROVE; REV-001 resolved and independently re-reviewed.
- **QA:** PASS. Targeted verification passed 22 tests; full seat-map module verification passed 63 tests with zero failures or errors.
- **Merged:** `2f74a1b` (`test(seat-map): isolate advanced layout constraint checks`) is included in `develop`.
