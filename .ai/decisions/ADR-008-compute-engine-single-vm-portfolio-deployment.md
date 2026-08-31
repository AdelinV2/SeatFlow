# ADR-008: Compute Engine Single-VM Portfolio Deployment

## Status

ACCEPTED

## Context

SeatFlow is a portfolio-grade microservices project intended to demonstrate Java/Spring engineering, distributed-system patterns, Kafka, Redis, PostgreSQL, realtime WebSockets, observability, Docker, CI/CD, Terraform, and cloud deployment. The public deployment only needs to remain available for roughly the Google Cloud Free Trial window while the project is used in job applications and interviews.

The previous plan used ten Cloud Run services plus Cloud SQL, Memorystore, managed Kafka, Cloud Armor, a dedicated load balancer, and a permanent staging environment. That topology is valid for a larger production system, but it adds substantial recurring cost and operational surface without solving a current SeatFlow traffic, availability, or scaling requirement.

## Decision

Deploy the complete SeatFlow production runtime to a single Terraform-managed Google Cloud Compute Engine VM, defaulting to `e2-highmem-2` (2 vCPU, 16 GiB RAM), using Docker Compose v2.

The VM runs independently containerized SeatFlow components:

- Nginx/Angular frontend edge;
- Eureka Server;
- API Gateway;
- eight business microservices;
- PostgreSQL 16;
- Apache Kafka in KRaft mode;
- Redis 7;
- OpenTelemetry Collector;
- Prometheus;
- Grafana;
- Tempo.

Keep these managed GCP capabilities because they provide meaningful cloud engineering value at low cost:

- Artifact Registry;
- Secret Manager;
- IAM;
- Workload Identity Federation for GitHub Actions;
- Compute Engine networking/static IP/persistent disk;
- Cloud Logging/Monitoring where useful.

Terraform provisions the VM and supporting GCP infrastructure. GitHub Actions builds immutable images, pushes them to Artifact Registry, authenticates through WIF, and deploys the production Compose image set to the VM.

`develop` remains the integration branch but does not create a second always-on staging stack by default. It builds and validates staging-tagged images and infrastructure contracts. `main`/approved releases deploy to the single production VM.

## Explicit Non-Decisions for MVP

The MVP does not require:

- Cloud Run;
- Cloud SQL;
- Memorystore;
- managed Kafka / Confluent Cloud;
- GKE/Kubernetes;
- Cloud Armor;
- dedicated Google Cloud HTTPS load balancing or CDN;
- a second permanent staging environment.

These remain future migration options when justified by real traffic, independent scaling, HA/SLA, or operational requirements.

## Rationale

### Cost

A single 16-GiB Compute Engine VM plus disk/IP comfortably fits the intended short deployment window within the trial budget far better than a collection of always-on managed data and messaging services.

### CV / Engineering Value

Running on one VM does not collapse the application into a monolith. Every service remains independently containerized and retains its database ownership, API/event boundaries, Eureka discovery, and Kafka workflows. The deployment still demonstrates:

- Docker and Compose orchestration;
- GCP Compute Engine;
- Terraform IaC;
- Artifact Registry;
- Secret Manager;
- IAM/WIF;
- automated GitHub Actions CI/CD;
- persistent PostgreSQL/Kafka infrastructure;
- Redis;
- Prometheus/Grafana/Tempo/OpenTelemetry;
- production networking and HTTPS.

The ability to explain why Kubernetes or fully managed services were deliberately not chosen is considered preferable to adding infrastructure only for appearance.

### Migration Path

The service containers remain portable. A future migration can move selected workloads to Cloud Run/GKE and stateful infrastructure to managed services without changing core domain boundaries.

## Security & Operations Consequences

- Only the edge proxy exposes public application ports.
- PostgreSQL, Kafka, Redis, Eureka, internal services, and observability endpoints stay private.
- Persistent state requires explicit backup/restore procedures because Cloud SQL managed backups are not used.
- The VM is a single point of failure; HA is deliberately outside MVP scope.
- Resource limits and retention settings must be tuned so all containers fit the VM.
- Deployment rollback is image/Compose based rather than Cloud Run revision traffic splitting.
- Secret values remain outside Git/Terraform state and are accessed through Secret Manager using least-privilege identities.

## Alternatives Considered

1. **Cloud Run + Cloud SQL + Memorystore + managed Kafka** — strongest managed experience, rejected for MVP because cost/complexity exceed the portfolio requirement.
2. **GKE/Kubernetes** — portable and scalable, rejected because orchestration overhead and compute cost solve no current requirement.
3. **Multiple providers/free tiers** — potentially near-zero cost, rejected because operating many accounts/services makes the architecture harder to understand and present.
4. **One unmanaged VM configured manually** — cheap, rejected because it loses Terraform reproducibility, IAM/WIF discipline, and automated deployment value.

## Consequences

Positive:

- one cloud provider and one runtime host;
- predictable cost inside the short trial window;
- preserves the complete SeatFlow engineering stack;
- much simpler incident/debug path;
- still provides strong cloud/IaC/CI-CD material for interviews.

Negative:

- single-host availability;
- manual responsibility for database/Kafka backup and capacity management;
- no independent infrastructure-level autoscaling;
- deployments may have brief service restart windows.

These trade-offs are accepted for the portfolio MVP.

## Implementation Notes

- `.ai/architecture/08-observability-and-deployment.md` is the authoritative deployment specification.
- `TASK-P10-005` creates the production Compose override and resource constraints.
- `TASK-P10-007` implements WIF + Artifact Registry + Compute Engine CD.
- `TASK-P10-008` provisions the Compute Engine/Terraform foundation.
- This ADR supersedes older Cloud Run/Cloud SQL/Memorystore/managed-Kafka wording in the master blueprint and other pre-existing deployment notes where those statements conflict with this decision.
