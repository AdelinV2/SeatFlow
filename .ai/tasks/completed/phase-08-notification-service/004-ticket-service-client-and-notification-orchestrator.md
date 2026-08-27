# TASK-P08-004: TicketServiceClient & Core Notification Service Orchestrator

## 1. Task Metadata
- **Task ID:** `TASK-P08-004`
- **Git Branch:** `feat/p08-001-notification-service`
- **Target Module:** `backend/services/notification-service`
- **Phase:** `Phase 08 - Notification Service`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md` (Section 11), `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** `ADR-002: Database Indexing and Integrity Standards`
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Implement synchronous inter-service communication to fetch ticket PDF documents from `ticket-service` (`GET http://ticket-service/api/tickets/{ticketId}/pdf`) using Eureka Service Discovery and Spring Cloud LoadBalancer with Resilience4j circuit breaking. Implement the core `NotificationService` business logic with strict idempotency verification (`existsByIdempotencyKey`), status tracking in `notification_logs`, metric reporting, and the multi-instance `NotificationRetryScheduler` background worker sweeping failed dispatches using `SELECT ... FOR UPDATE SKIP LOCKED`.

### Critical Invariants to Enforce:
- [x] **Load-Balanced RestClient Standards:** `config/RestClientConfig.java` defines an un-annotated `@Primary` `RestClient.Builder` alongside a qualified `@Bean @LoadBalanced RestClient.Builder`.
- [x] **Strict Idempotency:** Must verify `notificationLogRepository.existsByIdempotencyKey(...)` before triggering email dispatch.
- [x] **Multi-Instance Retry Safety:** Scheduler must query failed notifications using `SELECT ... FOR UPDATE SKIP LOCKED` and limit retries to maximum 3 attempts (`retry_count < 3`).
- [x] **Observability & Metrics:** Increment Prometheus counters `seatflow.notifications.sent.total` and `seatflow.notifications.failed.total` with template type and status tags.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/config/RestClientConfig.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/client/TicketServiceClient.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/client/impl/TicketServiceClientImpl.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/client/exception/TicketClientUnavailableException.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/service/NotificationService.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/service/impl/NotificationServiceImpl.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/scheduler/NotificationRetryScheduler.java`
- `[NEW]` `backend/services/notification-service/src/test/java/com/seatflow/notification/client/impl/TicketServiceClientImplTest.java`
- `[NEW]` `backend/services/notification-service/src/test/java/com/seatflow/notification/service/NotificationServiceImplTest.java`
- `[NEW]` `backend/services/notification-service/src/test/java/com/seatflow/notification/scheduler/NotificationRetrySchedulerTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 RestClient Configuration (`RestClientConfig.java`)
```java
@Configuration
public class RestClientConfig {

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder ticketServiceLoadBalancedBuilder() {
        return RestClient.builder();
    }
}
```

### 4.2 NotificationService Contract
```java
public interface NotificationService {
    void sendTicketIssuedNotification(TicketIssuedEvent event);
    void sendPaymentFailedNotification(PaymentFailedEvent event);
    void sendReservationHeldNotification(ReservationHeldEvent event);
    NotificationLogResponse getNotificationById(UUID id);
    PagedResult<NotificationLogResponse> getNotifications(String recipientEmail, Pageable pageable);
}
```

---

## 5. Step-by-Step Implementation Sequence
1. Create `RestClientConfig.java` with `@Primary` and `@LoadBalanced` builders.
2. Implement `TicketServiceClient` and `TicketServiceClientImpl` with Resilience4j circuit breaking.
3. Implement `NotificationService` and `NotificationServiceImpl` with idempotency, metrics, and log recording.
4. Implement `NotificationRetryScheduler` with `@Scheduled` and `FOR UPDATE SKIP LOCKED` query execution.
5. Write unit tests for `TicketServiceClientImpl`, `NotificationServiceImpl`, and `NotificationRetryScheduler`.

---

## 6. Definition of Done & Verification Command
```bash
mvn clean test -pl backend/services/notification-service -Dtest=TicketServiceClientImplTest,NotificationServiceImplTest,NotificationRetrySchedulerTest
```
