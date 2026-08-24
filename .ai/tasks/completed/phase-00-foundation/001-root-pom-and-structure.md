# TASK-001: Root Maven Parent POM & Multi-Module Foundation Setup

## 1. Task Metadata
- **Target Module:** `backend/pom.xml`, `backend/common/pom.xml`, `backend/services/pom.xml`
- **Phase:** `Phase 0 - Foundation`
- **Related Specs:** `.ai/architecture/00-system-overview.md`, `.ai/architecture/01-common-modules.md`, `.ai/architecture/08-observability-and-deployment.md`, `backend/AGENTS.md`
- **Related ADRs:** N/A
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Establish the backend multi-module build structure and root POM configuration for the entire SeatFlow microservices ecosystem. Ensure all dependencies, Java 21 compiler arguments, annotation processors (Lombok + MapStruct), Spring Cloud BOMs, enterprise JSON logging (`logstash-logback-encoder`), and Micrometer Prometheus metrics are declared centrally in dependency management.

### Critical Invariants to Enforce:
- [ ] Java 21 baseline with `-parameters` compiler argument enabled for reflection/reflection-free record handling.
- [ ] Spring Boot 4.1.x and Spring Cloud 2025.1.x (Oakwood) managed through `<dependencyManagement>`.
- [ ] Lombok 1.18.x and MapStruct 1.6.x configured with proper compiler plugin ordering (`lombok-mapstruct-binding`).
- [ ] Multi-module hierarchy: Root parent (`backend/pom.xml`) -> `backend/common/pom.xml` (parent for shared modules) and `backend/services/pom.xml` (parent for microservices).
- [ ] Centralized version management for `logstash-logback-encoder` (8.0+) and `micrometer-registry-prometheus`.
- [ ] Zero business logic or concrete code in parent POMs.

---

## 3. Exact File Inventory
List of all files to create or modify:

- `[NEW]` `backend/pom.xml` (Root Multi-Module Parent POM)
- `[NEW]` `backend/common/pom.xml` (Common Modules Aggregator & Parent POM)
- `[NEW]` `backend/services/pom.xml` (Services Aggregator & Parent POM)
- `[NEW]` `backend/.gitignore` (Backend-specific Git ignore rules)
- `[NEW]` `backend/.env.example` (Template for backend-wide common environment variables)

---

## 4. Technical Specifications & Contracts

### 4.1 Root `backend/pom.xml` Specification
The root POM must:
1. Inherit from `spring-boot-starter-parent` (version `4.1.0` or latest 4.1.x) or declare Spring Boot dependencies in `dependencyManagement`.
2. Define packaging `<packaging>pom</packaging>`.
3. Declare `<modules>`:
   - `<module>common</module>`
   - `<module>services</module>`
4. Declare standard properties:
   - `<java.version>21</java.version>`
   - `<spring-cloud.version>2025.1.0</spring-cloud.version>`
   - `<mapstruct.version>1.6.3</mapstruct.version>`
   - `<lombok.version>1.18.36</lombok.version>`
   - `<lombok-mapstruct-binding.version>0.2.0</lombok-mapstruct-binding.version>`
   - `<springdoc-openapi.version>3.0.0</springdoc-openapi.version>`
   - `<logstash-logback-encoder.version>8.0</logstash-logback-encoder.version>`
   - `<testcontainers.version>1.20.4</testcontainers.version>`
5. Include BOMs & libraries in `<dependencyManagement>`:
   - `spring-cloud-dependencies` (Oakwood)
   - `net.logstash.logback:logstash-logback-encoder`
   - `io.micrometer:micrometer-registry-prometheus`
   - `org.mapstruct:mapstruct` & `org.mapstruct:mapstruct-processor`
6. Configure `maven-compiler-plugin` with:
   - Source/Target: 21
   - `<compilerArgs><arg>-parameters</arg></compilerArgs>`
   - Annotation processors path including `lombok`, `lombok-mapstruct-binding`, and `mapstruct-processor`.

### 4.2 `backend/common/pom.xml` Specification
- Packaging: `pom`
- Modules:
   - `common-domain`
   - `common-events`
   - `common-observability`
   - `common-security`

### 4.3 `backend/services/pom.xml` Specification
- Packaging: `pom`
- Aggregates microservice modules as they are created (`eureka-server`, `api-gateway`, etc.).

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1:** Create `backend/pom.xml` with complete BOM definitions, plugins, compiler configurations, and properties.
2. **Step 2:** Create `backend/common/pom.xml` specifying the parent as `../pom.xml` and declaring the 4 common submodules.
3. **Step 3:** Create `backend/services/pom.xml` specifying the parent as `../pom.xml`.
4. **Step 4:** Create `backend/.gitignore` ignoring `.env`, `target/`, `.idea/`, `*.iml`, `.factorypath`.
5. **Step 5:** Create `backend/.env.example` documenting global database, Kafka, Redis, and Eureka default values.
6. **Step 6:** Run Maven validation to confirm valid XML syntax.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
mvn -f backend/pom.xml validate
```
- [ ] Root and aggregator `pom.xml` files parse without syntax or dependency convergence errors.
- [ ] Compiler plugin has `-parameters` flag and annotation processor binding configured.
- [ ] Task file is moved to `.ai/tasks/completed/`.
