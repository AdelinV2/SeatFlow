# TASK-P03-002: Event Domain Entities, Repositories, DTOs & Mappers

## 1. Task Metadata
- **Task ID:** `TASK-P03-002`
- **Git Branch:** `feat/p03-002-domain-entities-repositories-and-dtos`
- **Target Module:** `backend/services/event-service`
- **Phase:** `Phase 03 - Event Catalog Service`
- **Related Specs:** `.ai/architecture/03-database-models.md` (Sections 1 and 2.3), `.ai/architecture/06-api-contracts.md` (Section 2.3)
- **Related ADRs:** `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the persistence model and explicit boundary contracts for catalog events and section prices. This task establishes validated records and repository queries only; services, controllers, Kafka publishing, and HTTP integration remain later tasks.

### Critical Invariants to Enforce:
- [ ] Entities use explicit Lombok `@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, and `@AllArgsConstructor`; never use `@Data` or Lombok equality generation.
- [ ] `Event` has `@Version Long version`, string enum mapping, `@DynamicUpdate`, and Hibernate-safe equality.
- [ ] A price tier belongs to exactly one persisted event; `(event_id, section_id, category_name)` is unique in both DDL and entity metadata.
- [ ] Monetary values are `BigDecimal`, never floating point; all incoming prices have at most eight integer digits and two fractional digits.
- [ ] Repository public-catalog queries return only `PUBLISHED` events and are paginated/sortable.
- [ ] DTOs never expose entities and use Jakarta validation plus OpenAPI `@Schema` descriptions.
- [ ] MapStruct mappers use `componentModel = "spring"` and `unmappedTargetPolicy = ReportingPolicy.ERROR`.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/model/enums/EventStatus.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/model/enums/EventCategory.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/model/entity/Event.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/model/entity/EventPricingTier.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/model/entity/OutboxEvent.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/repository/EventRepository.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/repository/EventPricingTierRepository.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/repository/OutboxEventRepository.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/repository/projection/PriceRangeProjection.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/repository/projection/EventPriceRangeSummaryProjection.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/web/dto/request/CreateEventRequest.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/web/dto/request/UpdateEventRequest.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/web/dto/request/ConfigurePricingRequest.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/web/dto/request/PricingTierItemRequest.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/web/dto/response/EventSummaryResponse.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/web/dto/response/EventDetailResponse.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/web/dto/response/PricingTierResponse.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/web/dto/response/EventSeatMapResponse.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/web/dto/response/SeatMapSectionResponse.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/web/dto/response/SeatMapSeatResponse.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/mapper/EventMapper.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/mapper/EventPricingTierMapper.java`
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/repository/EventRepositoryTest.java`
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/repository/EventPricingTierRepositoryTest.java`
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/mapper/EventMapperTest.java`
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/mapper/EventPricingTierMapperTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Enums and Entity Mapping
Create `EventStatus { DRAFT, PUBLISHED, CANCELLED, COMPLETED }` and `EventCategory { CONCERT, THEATRE, SPORTS, CONFERENCE, OTHER }`.

`Event` maps table `events`, declares the four database indexes, has fields `UUID id`, `UUID venueId`, `String title`, `String description`, `EventCategory category`, `String bannerUrl`, `Instant eventDate`, `EventStatus status`, `Long version`, `Instant createdAt`, `Instant updatedAt`, and `List<EventPricingTier> pricingTiers`. Use `@Enumerated(EnumType.STRING)`, a lazy `@OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)`, and `@Version` on `version`. It must use `@DynamicUpdate`, explicit immutable identifier mapping, and `@ToString(onlyExplicitlyIncluded = true)` without the collection. Implement equality as: reference equality first; null/`Hibernate.getClass(this)` mismatch is false; two non-null ids are equal. `hashCode()` returns `getClass().hashCode()`.

`EventPricingTier` maps `event_pricing_tiers`, declares unique constraint `uq_event_section_tier` and both indexes, and has `UUID id`, lazy non-null `Event event`, `UUID sectionId`, `String categoryName`, `BigDecimal price`, `String currency`, `Instant createdAt`, and `Instant updatedAt`. Map price with `precision = 10, scale = 2`; use the same explicit Lombok, safe equality, and relationship-safe `toString` rules.

`OutboxEvent` maps `outbox_events` with `UUID id`, `UUID aggregateId`, `String eventType`, `String payload`, `Instant createdAt`, `Instant publishedAt`, and `Integer retryCount`. Use `@JdbcTypeCode(SqlTypes.JSON)` and `@Column(nullable = false, columnDefinition = "jsonb", updatable = false)` for `payload`, and the same safe entity conventions matching `seat-map-service`. No outbox entity may contain a KafkaTemplate or publish method.

### 4.2 Repository Contracts
```java
public interface EventRepository extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {
    @EntityGraph(attributePaths = "pricingTiers")
    Optional<Event> findWithPricingTiersById(UUID id);
}

public interface EventPricingTierRepository extends JpaRepository<EventPricingTier, UUID> {
    List<EventPricingTier> findByEvent_IdOrderByPriceAsc(UUID eventId);
    boolean existsByEvent_Id(UUID eventId);
    Optional<EventPricingTier> findByEvent_IdAndSectionIdAndCategoryName(UUID eventId, UUID sectionId, String categoryName);

    @Query("SELECT MIN(p.price) AS minPrice, MAX(p.price) AS maxPrice, MIN(p.currency) AS currency FROM EventPricingTier p WHERE p.event.id = :eventId")
    PriceRangeProjection findPriceRangeByEventId(@Param("eventId") UUID eventId);

    @Query("SELECT p.event.id AS eventId, MIN(p.price) AS minPrice, MAX(p.price) AS maxPrice, MIN(p.currency) AS currency FROM EventPricingTier p WHERE p.event.id IN :eventIds GROUP BY p.event.id")
    List<EventPriceRangeSummaryProjection> findPriceRangesByEventIds(@Param("eventIds") Collection<UUID> eventIds);
}

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
    @Query(value = "SELECT * FROM outbox_events WHERE published_at IS NULL AND retry_count < :maxRetry ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findUnpublishedForUpdate(@Param("maxRetry") int maxRetry, @Param("limit") int limit);
    @Modifying(clearAutomatically = true)
    @Query("update OutboxEvent o set o.publishedAt = :publishedAt where o.id = :id and o.publishedAt is null")
    int markPublished(@Param("id") UUID id, @Param("publishedAt") Instant publishedAt);
    @Modifying(clearAutomatically = true)
    @Query("update OutboxEvent o set o.retryCount = o.retryCount + 1 where o.id = :id and o.retryCount < :maxRetry")
    int incrementRetryCount(@Param("id") UUID id, @Param("maxRetry") int maxRetry);
}
```

Implement filtering with the inherited `findAll(Specification<Event>, Pageable)` contract. The `Specification<Event>` predicates are: status fixed to `PUBLISHED`, optional equality on `category`, optional lower-case `title` or `description` contains search, and an upcoming `eventDate >= Instant.now()` predicate. Apply a default `PageRequest` sort of `eventDate,ASC`; permit only `eventDate`, `title`, or `createdAt` sort properties at the controller boundary in Task 004.

### 4.3 Complete DTO & Projection Records
```java
public interface PriceRangeProjection {
    BigDecimal getMinPrice();
    BigDecimal getMaxPrice();
    String getCurrency();
}

public interface EventPriceRangeSummaryProjection {
    UUID getEventId();
    BigDecimal getMinPrice();
    BigDecimal getMaxPrice();
    String getCurrency();
}

public record CreateEventRequest(
    @NotNull @Schema(description = "Existing venue UUID", requiredMode = REQUIRED) UUID venueId,
    @NotBlank @Size(max = 255) @Schema(description = "Event title", example = "Hamlet") String title,
    @NotBlank @Schema(description = "Full event description") String description,
    @NotNull @Schema(description = "Catalog category") EventCategory category,
    @Size(max = 1000) @Pattern(regexp = "^https?://.+$", message = "bannerUrl must be an HTTP(S) URL") String bannerUrl,
    @NotNull @Future @Schema(description = "UTC start time", example = "2027-05-01T19:30:00Z") Instant eventDate
) {}

public record UpdateEventRequest(
    @Size(min = 1, max = 255) String title,
    @Size(min = 1) String description,
    EventCategory category,
    @Size(max = 1000) @Pattern(regexp = "^https?://.+$", message = "bannerUrl must be an HTTP(S) URL") String bannerUrl,
    @Future Instant eventDate,
    EventStatus status
) {}

public record ConfigurePricingRequest(
    @NotEmpty @Size(max = 200) List<@Valid PricingTierItemRequest> pricingTiers
) {}

public record PricingTierItemRequest(
    @NotNull UUID sectionId,
    @NotBlank @Size(max = 100) String categoryName,
    @NotNull @DecimalMin(value = "0.00") @Digits(integer = 8, fraction = 2) BigDecimal price,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an uppercase ISO-4217 code") String currency
) {}

public record EventSummaryResponse(
    UUID id, String title, EventCategory category, String bannerUrl, Instant eventDate,
    BigDecimal minPrice, BigDecimal maxPrice, String currency
) {}

public record EventDetailResponse(
    UUID id, UUID venueId, String title, String description, EventCategory category, String bannerUrl,
    Instant eventDate, EventStatus status, List<PricingTierResponse> pricingTiers, Instant createdAt, Instant updatedAt
) {}

public record PricingTierResponse(
    UUID id, UUID sectionId, String categoryName, BigDecimal price, String currency
) {}

public record SeatMapSeatResponse(
    UUID seatId, String rowLabel, Integer seatNumber, Integer gridX, Integer gridY, Boolean isActive
) {}

public record SeatMapSectionResponse(
    UUID sectionId, String name, Integer rowCount, Integer colCount, List<SeatMapSeatResponse> seats,
    List<PricingTierResponse> pricingTiers
) {}

public record EventSeatMapResponse(
    UUID eventId, UUID venueId, String eventTitle, Instant eventDate, String venueName, Integer venueCapacity,
    Long totalConfiguredSeats, List<SeatMapSectionResponse> sections
) {}
```

All response records must carry `@Schema` on the record and each component. `UpdateEventRequest` permits partial updates but the service must reject an empty request with `ValidationException(ErrorCode.INVALID_REQUEST)`.

### 4.4 Mapper Contracts
```java
@Mapper(componentModel = "spring", uses = {EventPricingTierMapper.class}, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EventMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", constant = "DRAFT")
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "pricingTiers", ignore = true)
    Event toEntity(CreateEventRequest request);

    EventDetailResponse toDetailResponse(Event event);

    @Mapping(target = "id", source = "event.id")
    @Mapping(target = "title", source = "event.title")
    @Mapping(target = "category", source = "event.category")
    @Mapping(target = "bannerUrl", source = "event.bannerUrl")
    @Mapping(target = "eventDate", source = "event.eventDate")
    @Mapping(target = "minPrice", source = "minPrice")
    @Mapping(target = "maxPrice", source = "maxPrice")
    @Mapping(target = "currency", source = "currency")
    EventSummaryResponse toSummaryResponse(Event event, BigDecimal minPrice, BigDecimal maxPrice, String currency);

    void updateEntity(UpdateEventRequest request, @MappingTarget Event event);
}

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EventPricingTierMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "event", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    EventPricingTier toEntity(PricingTierItemRequest request);

    PricingTierResponse toResponse(EventPricingTier tier);
}
```

### 4.5 Test Contracts
`EventRepositoryTest` and `EventPricingTierRepositoryTest` are `@DataJpaTest`, `@ActiveProfiles("test")`, and Testcontainers PostgreSQL 16 tests with `@DynamicPropertySource`; assert schema-backed unique constraints, catalog status/category/search filtering, price-range results, eager detail loading, cascade deletion, and tier ordering. `EventMapperTest` and `EventPricingTierMapperTest` instantiate generated mappers and assert every field conversion, DRAFT defaulting, ignored persistence fields, and partial update null preservation.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p03-002-domain-entities-repositories-and-dtos` from `develop`.
2. Create enums and entities that exactly mirror the Task 001 DDL.
3. Implement repository contracts, including safe `Specification` filtering and locked outbox polling query.
4. Add all validated, documented request/response records and MapStruct mappers.
5. Write mapper unit tests and PostgreSQL-backed repository slice tests.
6. Run the verification command before marking the task complete.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/event-service -Dtest=EventRepositoryTest,EventPricingTierRepositoryTest,EventMapperTest,EventPricingTierMapperTest
```

- [ ] Entity metadata, constraints, and field types match the Flyway schema.
- [ ] Catalog queries cannot return a draft/cancelled/completed event.
- [ ] Every mapper has `ReportingPolicy.ERROR` and no DTO exposes an entity.
- [ ] PostgreSQL Testcontainers repository tests and mapper unit tests pass.
- [ ] Task file is moved to `.ai/tasks/completed/phase-03-event-service/002-domain-entities-repositories-and-dtos.md` when complete.
