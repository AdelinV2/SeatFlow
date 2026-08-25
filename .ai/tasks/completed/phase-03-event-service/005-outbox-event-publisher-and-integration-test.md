# TASK-P03-005: Event Outbox Publisher, Kafka Configuration & Integration Test

## 1. Task Metadata
- **Task ID:** `TASK-P03-005`
- **Git Branch:** `feat/p03-005-outbox-event-publisher-and-integration-test`
- **Target Module:** `backend/services/event-service`
- **Phase:** `Phase 03 - Event Catalog Service`
- **Related Specs:** `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/03-database-models.md` (Section 2.3), `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Complete the transactional-outbox delivery pipeline and prove the catalog’s full lifecycle against PostgreSQL 16. The publisher delivers existing durable envelopes only after the service transaction has committed; it is retry-safe across concurrent scheduler instances.

### Critical Invariants to Enforce:
- [ ] Kafka messages contain the pre-serialized `EventEnvelope` JSON string from `OutboxEvent.payload`, keyed by aggregate UUID string; event payloads are never sent directly by a service mutation.
- [ ] The publisher polls only `published_at IS NULL AND retry_count < 5` rows via `FOR UPDATE SKIP LOCKED` ordered by oldest first.
- [ ] A successful broker acknowledgement marks exactly that row published; a failed send increments retry count only while it remains below five.
- [ ] Duplicate scheduler invocations cannot publish a claimed row concurrently and cannot overwrite an already-set `published_at`.
- [ ] The default topic is `seatflow.event.events`, configurable through `outbox.publisher.topic`; do not modify `common-events` merely to add a topic constant.
- [ ] JSON serialization retains the full EventEnvelope metadata: event id, type, occurred time, correlation id, causation id, aggregate id, schema version, and payload.
- [ ] The end-to-end test uses `@SpringBootTest` and PostgreSQL Testcontainers, creates a draft, configures tiers, publishes it, and proves the durable outbox record is marked published.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/config/KafkaProducerConfig.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/messaging/producer/OutboxEventPublisher.java`
- `[MODIFY]` `backend/services/event-service/src/main/resources/application-local.yaml` — configure Kafka producer serialization.
- `[MODIFY]` `backend/services/event-service/src/main/resources/application-docker.yaml` — configure Kafka producer serialization.
- `[MODIFY]` `backend/services/event-service/src/main/resources/application-prod.yaml` — configure Kafka producer serialization with `acks: all` and retries.
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/messaging/producer/OutboxEventPublisherTest.java`
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/integration/EventServiceIntegrationTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Kafka Producer Configuration
Create a configuration class aligned with `seat-map-service` providing a `String, String` producer factory and template:

```java
@Configuration
public class KafkaProducerConfig {
    @Bean
    public ProducerFactory<String, String> producerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.producer.acks:all}") String acks,
            @Value("${spring.kafka.producer.retries:3}") int retries) {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configuration.put(ProducerConfig.ACKS_CONFIG, acks);
        configuration.put(ProducerConfig.RETRIES_CONFIG, retries);
        configuration.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(configuration);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(
            ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
```

Local, Docker, and production profile producer settings name `org.apache.kafka.common.serialization.StringSerializer` for both keys and values. Production additionally declares `acks: all`, `retries: 3`, and `properties.enable.idempotence: true`.

### 4.2 Scheduled Publisher Contract
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisher {
    private static final int MAX_RETRY_COUNT = 5;
    private static final int SEND_TIMEOUT_SECONDS = 30;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${outbox.publisher.topic:seatflow.event.events}")
    private String topic = "seatflow.event.events";

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize = 50;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:3000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findUnpublishedForUpdate(MAX_RETRY_COUNT, batchSize);
        if (events.isEmpty()) {
            return;
        }

        log.debug("Outbox publisher polling. unpublishedCount={}", events.size());

        for (OutboxEvent event : events) {
            try {
                CompletableFuture<SendResult<String, String>> sendFuture = kafkaTemplate.send(
                        topic,
                        event.getAggregateId().toString(),
                        event.getPayload()
                );
                sendFuture.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                int updated = outboxEventRepository.markPublished(event.getId(), Instant.now());
                if (updated == 0) {
                    log.debug("Outbox event already published (possibly by another instance). outboxEventId={}", event.getId());
                } else {
                    log.info("Outbox event published. outboxEventId={}, eventType={}, aggregateId={}, topic={}",
                            event.getId(), event.getEventType(), event.getAggregateId(), topic);
                }
            } catch (Exception ex) {
                int updated = outboxEventRepository.incrementRetryCount(event.getId(), MAX_RETRY_COUNT);
                if (updated == 0) {
                    log.error("Outbox event at max retry count or already published; parking. outboxEventId={}, eventType={}, aggregateId={}",
                            event.getId(), event.getEventType(), event.getAggregateId(), ex);
                } else {
                    log.warn("Failed to publish outbox event. outboxEventId={}, eventType={}, aggregateId={}, retryCount={}",
                            event.getId(), event.getEventType(), event.getAggregateId(), event.getRetryCount() + 1, ex);
                }
            }
        }
    }
}
```

### 4.3 Publisher Unit Test Contract
`OutboxEventPublisherTest` uses `@ExtendWith(MockitoExtension.class)`. It must cover: no rows means no Kafka interaction; successful one-row delivery sends the event envelope with the expected topic/key and marks it published; a Kafka future failure increments retry but does not mark published; a max-retry row is never claimed; and a zero `markPublished` result is tolerated. Stub a completed/failed `CompletableFuture<SendResult<String, String>>`; do not require Docker, PostgreSQL, or embedded Kafka.

### 4.4 End-to-End Integration Test Contract
`EventServiceIntegrationTest` uses `@SpringBootTest`, `@ActiveProfiles("test")`, `@Testcontainers`, and a static `PostgreSQLContainer<>("postgres:16-alpine")` wired through `@DynamicPropertySource`. Disable scheduled background runs by setting `outbox.publisher.fixed-delay-ms=60000`. Use `@MockitoBean KafkaTemplate<String, String>` that returns an already-completed `SendResult`; use a test `VenueValidationPort`/`SeatMapClient` bean or Mockito stub returning `true` for the event venue and supplied sections. It must not replace PostgreSQL with H2.

In one deterministic test, perform this lifecycle through real services (or the secured HTTP endpoints with mocked authentication):

1. Create a draft event whose future date and venue id are valid.
2. Configure a non-empty list of valid, same-currency section tiers.
3. Transition the event to `PUBLISHED`.
4. Assert PostgreSQL contains both `EventCreated` and `EventPublished` outbox rows before publisher execution, both with `publishedAt == null`, correctly typed envelope JSON, and aggregate id equal to the event id.
5. Invoke `outboxEventPublisher.publishPendingEvents()`.
6. Assert Kafka is called once per outbox row with key equal to the event id; reload rows and assert every row has non-null `publishedAt`, retry count zero, and unchanged payload/type.

The test cleanup deletes outbox rows, pricing tiers, then events in foreign-key-safe order. It must separately assert that a cancelled event emits `EventCancelled` through the durable outbox, but it need not publish it in the main lifecycle assertion.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p03-005-outbox-event-publisher-and-integration-test` from `develop`.
2. Configure an idempotent JSON `EventEnvelope<Object>` Kafka producer for every runtime profile.
3. Implement the bounded, locked, scheduled outbox publisher and its successful/failure pathways.
4. Write publisher Mockito tests first, including malformed payload and retry ceiling behavior.
5. Add the PostgreSQL Testcontainers integration lifecycle and invoke the publisher against a mocked acknowledged broker send.
6. Run the verification command and inspect failures before completion.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/event-service -Dtest=OutboxEventPublisherTest,EventServiceIntegrationTest
```

- [ ] All Kafka sends use `EventEnvelope<Object>` JSON and an event-id key.
- [ ] Polling is bounded, lock-safe, retry-limited, and never loses an outbox record on failure.
- [ ] The integration test proves create → pricing → publish produces committed durable records that the publisher marks sent.
- [ ] No shared common module, direct publish path, or local `@RestControllerAdvice` was added.
- [ ] Task file is moved to `.ai/tasks/completed/phase-03-event-service/005-outbox-event-publisher-and-integration-test.md` when complete.
