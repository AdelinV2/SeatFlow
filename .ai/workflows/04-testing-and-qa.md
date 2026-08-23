# Workflow 04: Testing & QA Protocol

**Role:** QA & Test Engineer

---

## 1. Testing Pyramid for SeatFlow

Every microservice in SeatFlow must have a multi-layered automated test suite:

```
          ┌────────────────────────────────┐
          │   E2E / Integration Tests      │  <-- Testcontainers (Postgres, Kafka, Redis)
          ├────────────────────────────────┤
          │   Concurrency & Stress Tests   │  <-- Multi-threaded CountDownLatch
          ├────────────────────────────────┤
          │   Service Slice Tests          │  <-- @DataJpaTest, @WebMvcTest
          ├────────────────────────────────┤
          │   Unit Tests (Fast)            │  <-- JUnit 5 + Mockito / Signal Tests
          └────────────────────────────────┘
```

---

## 2. Testing Guidelines & Annotations

### 2.1 Backend Unit Tests
- `@ExtendWith(MockitoExtension.class)`
- Isolate pure domain logic, mappers, and validations.
- Mock all repositories, external REST clients, and Kafka producers.

### 2.2 Backend Integration Tests
- `@SpringBootTest` with `@Testcontainers` and `@ActiveProfiles("test")`.
- Real PostgreSQL container (`postgres:16-alpine`).
- Real Kafka container (`apache/kafka-native:3.8.0`).
- Use `@DynamicPropertySource` to inject dynamic container ports.

### 2.3 Concurrency Tests (Mandatory for Reservation Service)
- Must simulate at least **50-100 concurrent threads** attempting to hold the same seat simultaneously.
- Assert that exactly **1** thread succeeds and **N-1** threads receive a conflict exception.
- Never use `@Disabled` on concurrency tests.

### 2.4 Frontend Unit Tests
- Angular `ComponentFixture` with `provideHttpClientTesting()`.
- Test Signal updates and computed calculations.
- Test user interactions (button clicks, seat selection toggle).
