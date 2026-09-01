# AI_MODEL_REFERENCE.md

## Purpose

Detailed reference for `.ai/MODEL_ROUTER.md`. Consult this file only when model selection is ambiguous, high-risk, cost-sensitive, or requires quantitative justification.

Data snapshot: **August 31, 2026**.

Benchmark numbers are directional, not guarantees. Harness, prompts, tools, repository structure, reasoning configuration, caching, retry policy, and provider implementation can materially change real-world results.

---

## 1. Model Positioning

### GPT-5.6 Luna
Fast, extremely inexpensive premium worker for bounded, verifiable tasks. Strong ordinary coding performance, but substantially weaker than Terra/Sol on hard debugging and very-long-context reasoning.

### GPT-5.6 Terra
Default serious software-engineering model. Strong balance of planning, backend reasoning, review, debugging, context use, latency, and cost.

### GPT-5.6 Sol
Critical escalation model. Use when hidden mistakes can affect money, security, data integrity, distributed consistency, or production reliability.

### Gemini 3.7 Flash High
High-throughput premium agentic/frontend model with ~1M context. Particularly strong for Angular/web/UI work, tool-heavy workflows, and large repository exploration.

### Hy3 High
Free implementation worker in the current OpenCode/Kilo routes. Best used for deterministic implementation, tests, refactors, reproducible fixes, and CI/CD where external verification is strong.

### Muse Spark 1.2 xHigh
Free large-context/agentic worker in the current OpenCode route. Useful for large cross-file implementation and repo-wide work where ~1M context is beneficial, but it can be verbose and benchmark/harness dependent.

---

## 2. SeatFlow Context

SeatFlow is not a simple CRUD project. The model router must distinguish low-cost visible failures from hidden high-cost failures.

Critical architecture areas include:

- reservation hold/concurrency behavior
- zero-double-booking guarantees
- PostgreSQL source-of-truth semantics
- Kafka + transactional outbox behavior
- payment/idempotency/Stripe state transitions
- authentication/authorization
- realtime/distributed consistency
- database migrations and production reliability

Because SeatFlow already separates planning, implementation, fixing, and verification, the repository is especially well suited to a **strong-model-for-decisions + free-model-for-deterministic-execution** workflow.

---

## 3. Pricing and Economic Reference

### GPT-5.6 standard API pricing

| Model | Input / 1M | Output / 1M |
|---|---:|---:|
| GPT-5.6 Luna | $0.20 | $1.20 |
| GPT-5.6 Terra | $2.00 | $12.00 |
| GPT-5.6 Sol | $4.00 | $20.00 |

GPT-5.6 supports reasoning levels from Low through Max in the relevant model configurations. Reasoning tokens are billed as output tokens.

### Gemini 3.7 Flash

Current introductory pricing around this snapshot:

- $0.75 / 1M input
- $3.75 / 1M output, including reasoning

Gemini 3.7 Flash supports Low / Medium / High thinking and approximately 1,048,576 input tokens with 65,536 maximum output tokens.

### Hy3

Current free routes make model-token cost effectively $0 from the user's perspective.

Relevant model characteristics:

- approximately 295B total parameters
- approximately 21B active parameters
- approximately 256K context
- reasoning/agent orientation

### Muse Spark 1.2

Standard hosted/API pricing has been reported around:

- $1.25 / 1M input
- $4.25 / 1M output

The current free route exposes Muse Spark 1.2 at $0 from the user's perspective.

Context is approximately 1M.

### Economic rule

Never compare only nominal token price.

Use:

`Effective Cost = model bill + retries + latency + developer review time + context churn + expected failure cost`

A free model can still be operationally expensive if it requires repeated repair loops. A premium model can be economically superior when a wrong answer is difficult to detect.

---

## 4. Token-Efficiency and Latency Evidence

Independent measurements around this snapshot demonstrate that reasoning effort can dramatically change real economics.

Representative behavior:

| Configuration | AA Intelligence Index | Approx. output tokens across evaluation | Approx. evaluation cost | Operational takeaway |
|---|---:|---:|---:|---|
| GPT-5.6 Sol High | 57 | ~21M | ~$700 | Expensive/token but comparatively concise |
| GPT-5.6 Terra High | 50 | ~24M | ~$395 | Strong interactive balance |
| GPT-5.6 Luna Max | 52 | ~130M | ~$172 | Very cheap/token but ~5.4x Terra High output volume |
| Muse Spark 1.2 xHigh | 57 | ~95M | ~$639 standard hosted | Attractive list price can be offset by verbosity |
| Hy3 | ~42 | high-output/verbose profile | provider-dependent | Free route removes monetary output penalty but not developer-time penalty |

Measured time-to-first-token also illustrates why Luna Max should not be used as the default interactive reasoning configuration: Luna Max has been measured around ~144 seconds TTFT versus only a few seconds for Terra High in comparable runs.

Interpretation:

- **Luna Max is not a universal cheaper Terra High.**
- **Muse xHigh can be economically inefficient at paid rates despite low list pricing.**
- **Muse being free changes the recommendation substantially for verifiable implementation.**
- **Sol is justified primarily by lower expected failure cost, not cheap inference.**

---

## 5. Development Benchmark Snapshot

### GPT-5.6 family

Published results around this snapshot include:

| Benchmark | Luna | Terra | Sol |
|---|---:|---:|---:|
| SWE-Bench Pro | 62.7% | 63.4% | 64.6% |
| DeepSWE v1.1 | 67.2% | 69.6% | 72.7% |
| Terminal-Bench 2.1 | 84.7% | 87.4% | 88.8% |
| Internal Research Debugging | 50.8% | 67.8% | 68.3% |

The most important SeatFlow signal is debugging: Luna is close on ordinary coding benchmarks but much weaker on the cited hard-debugging evaluation. That supports Luna/Hy3 for bounded implementation and Terra/Sol for root-cause analysis.

### Gemini 3.7 Flash vs Terra / Muse

Published August 2026 comparison data:

| Benchmark | Gemini 3.7 Flash | GPT-5.6 Terra | Muse Spark 1.2 |
|---|---:|---:|---:|
| Artificial Analysis Intelligence Index | 56 | 57 | 57 |
| FrontierCode 1.1 | 43.6% | 41.3% | — |
| DeepSWE v1.1 | 65.3% | 69.6% | 54.9% |
| Code Arena Web Development | 1588 Elo | 1523 | 1535 |
| Terminal-Bench 2.1 | 85.8% | 87.4% | 82.9% |
| Terminal-Bench 3.0 | 14.9% | 20.8% | — |
| AutomationBench | 30.4% | 23.6% | — |
| GDM-MRCR v2 128K avg. | 97.0% | 93.5% | — |

Interpretation:

- Terra remains stronger on several difficult long-horizon software-engineering evaluations.
- Gemini has particularly strong frontend/web and automation evidence.
- Muse's strong composite intelligence score does not imply Terra-level reliability on DeepSWE/Terminal-Bench.

### Hy3

Public directly-comparable evidence is thinner and should be treated more cautiously.

Reported data includes:

- ~295B total parameters, ~21B active
- ~256K context
- reasoning/agent orientation
- 270-expert blind evaluation: Hy3 2.67/4 vs GLM-5.1 2.51/4
- particularly strong relative performance in frontend, data/storage, and CI/CD categories
- Kilo Terminal Bench 2.0 result around 47.6% on its own harness

Do not compare a Terminal-Bench 2.0 result directly to Terminal-Bench 2.1 as if they were identical experiments.

---

## 6. Long-Context Evidence

Nominal context capacity is not the same as context reasoning quality.

OpenAI reports a major long-context distinction inside GPT-5.6:

| MRCR range | Luna | Terra | Sol |
|---|---:|---:|---:|
| 256K-512K | 41.3% | 89.6% | 91.5% |
| 512K-1M | 41.3% | 72.5% | 73.8% |

Therefore, do not route a giant SeatFlow architecture/repository synthesis to Luna merely because Luna nominally accepts a very large context.

For large-context work:

- **Muse xHigh**: free large-context implementation/exploration route
- **Gemini High**: best premium value for large-context agentic/frontend work
- **Terra High**: best default when subtle backend reasoning matters
- **Sol High**: use when critical invariants must be synthesized correctly

Hy3's ~256K context makes it best for bounded tasks rather than full-repository ingestion.

---

## 7. Model Profiles

### Hy3 High

Strengths:

- $0 in current free routes
- good fit for coding agents
- strong value for deterministic implementation
- tests/refactors/CI/CD are good matches
- suitable for straightforward backend/frontend changes

Weaknesses:

- ~256K context rather than ~1M
- weaker overall reasoning evidence than Terra/Sol/Gemini
- public benchmark evidence is thinner
- can be verbose
- provider/harness behavior can vary

Best SeatFlow tasks:

- User/Event/Seat Map straightforward implementation
- tests
- DTO/repository/controller/service work
- mechanical refactors
- reproducible bug fixes
- Docker/GitHub Actions/observability

Escalate when:

- architecture is ambiguous
- one meaningful repair loop fails
- hidden correctness matters
- task reaches reservation/payment/security/distributed-state logic

### Muse Spark 1.2 xHigh

Strengths:

- $0 in current free route
- ~1M context
- strong composite intelligence
- useful for long-horizon/cross-file agentic work
- good free option for repo-wide refactors/exploration

Weaknesses:

- verbose/high token consumption in measured evaluations
- weaker DeepSWE/TB2.1 than Terra in published comparison
- xHigh behavior is provider/harness dependent
- high reasoning does not guarantee high task efficiency

Best SeatFlow tasks:

- large cross-file implementation from an already-complete plan
- repo-wide refactors
- large-context exploration
- autonomous implementation loops

Escalate when:

- repeated tool-loop drift appears
- tests fail after one meaningful repair attempt
- critical correctness is involved

### GPT-5.6 Luna

Strengths:

- extremely low paid API cost
- fast generation once started
- strong ordinary coding for its price
- stable premium fallback

Weaknesses:

- hard debugging gap vs Terra/Sol
- weak demonstrated very-long-context reasoning
- Max can be extremely verbose/slow to start

Best SeatFlow tasks:

- cheap premium fallback after Hy3
- tests/docs/simple fixes
- bounded implementation

Recommended:

- Low: docs/extraction
- Medium: ordinary bounded work
- High: bounded implementation/debugging
- xHigh: niche
- Max: rare/offline, not default interactive mode

### GPT-5.6 Terra

Strengths:

- best overall SeatFlow balance
- strong backend reasoning
- strong debugging
- concise relative to Luna Max/Muse xHigh
- excellent planning/review profile
- strong long-context reasoning

Best SeatFlow tasks:

- task planning
- Spring/JPA/transactions
- Kafka/outbox reasoning
- API design
- complex refactors
- difficult debugging
- PR review
- cross-service reasoning

Recommended:

- Medium: routine implementation/review
- High: default planning/debugging/review
- xHigh: difficult multi-service diagnosis/ADR
- Max: rare; compare with Sol first

### GPT-5.6 Sol

Strengths:

- strongest critical-reasoning tier here
- best fit for hidden high-cost failure
- strong software-engineering and terminal benchmarks

Best SeatFlow tasks:

- reservation concurrency / zero double booking
- payment / Stripe / idempotency
- security/auth
- distributed state/ordering
- production incidents
- critical migration/release review

Recommended:

- High: critical default
- xHigh: severe/high-stakes root cause
- Max: exceptional final escalation

### Gemini 3.7 Flash High

Strengths:

- strong frontend/web benchmark evidence
- high-throughput agentic workflow
- strong automation
- ~1M context
- attractive premium price/performance

Best SeatFlow tasks:

- Angular/Tailwind
- screenshot/mock implementation
- frontend visual debugging
- browser/tool-heavy work
- large-context exploration

Use Terra instead when backend invariants or root-cause precision dominate.

---

## 8. SeatFlow Development-Stage Recommendations

### Planning

Default: **Terra High**

Use Sol High for:

- new distributed invariants
- reservation/payment architecture
- security-sensitive architecture
- decisions expensive to reverse

Do not use free-first for authoritative planning unless the task is trivial.

### Implementation

Bounded, complete task file:

**Hy3 High**

Large/cross-file/agentic implementation:

**Muse xHigh**

Normal backend feature requiring design judgment:

**Terra Medium/High**

Critical reservation/payment/security implementation:

**Terra High or Sol High**

### Review

Routine PR:

**Terra Medium**

Complex PR:

**Terra High**

Security/payment/concurrency/invariant review:

**Sol High/xHigh**

Hy3 can perform a free first pass, but should not be the final authority on critical code.

### Debugging / Fixing

Localized + reproducible:

**Hy3 High**

Cheap premium fallback:

**Luna High**

Hard root cause:

**Terra High**

Concurrency/payment/distributed production bug:

**Sol High/xHigh**

### Testing

Test generation:

**Hy3 High**

Large autonomous test/fix loop:

**Muse xHigh**

Complex failure interpretation:

**Terra High**

Critical failure analysis:

**Sol High**

### Frontend

Default premium:

**Gemini 3.7 Flash High**

Simple deterministic component:

**Hy3 High** is a valid free-first choice.

Large cross-file frontend agent task:

**Muse xHigh** can be tried free-first, with Gemini High as premium escalation.

### DevOps / Observability

Routine:

**Hy3 High**

Large cross-file infra work:

**Muse xHigh**

Hard incident/root cause:

**Terra High / Sol High** depending on severity.

---

## 9. Reliability-Oriented SeatFlow Router

The following reliability bands are engineering estimates derived from public evidence + SeatFlow task structure, not measured SeatFlow pass rates.

| SeatFlow task | Recommended | Effort | Expected model cost | Estimated reliability | Escalation |
|---|---|---:|---:|---:|---|
| Architecture -> atomic task spec | Terra | High | premium | 90-95% | Sol High |
| Distributed consistency/concurrency ADR | Sol | High | premium | 93-97% | Sol xHigh |
| User/Event/Seat Map bounded implementation | Hy3 | High | $0 route | 78-88% | Luna High -> Terra |
| Ticket/Notification large implementation | Muse | xHigh | $0 route | 82-91% | Terra High |
| Reservation locking | Terra | High | premium | 90-95% | Sol High/xHigh |
| Payment/idempotency/Stripe | Sol | High | premium | 93-97% | Sol xHigh |
| Kafka/outbox change | Terra | High | premium | 90-95% | Sol High |
| Realtime cross-service integration | Terra | High | premium | 89-94% | Sol for race conditions |
| Angular UI from clear spec | Gemini | High | low premium | 89-95% | Hy3 if simple / Terra for state issues |
| Unit/integration tests | Hy3 | High | $0 route | 80-92% | Luna Medium/High |
| CI/Docker/observability | Hy3 | High | $0 route | 80-90% | Muse -> Terra |
| Small reproducible bug | Hy3 | High | $0 route | 82-92% | Luna High |
| Hard cross-service debugging | Terra | High | premium | 90-95% | Sol xHigh |
| Repo-wide refactor/exploration | Muse | xHigh | $0 route | 80-90% | Gemini/Terra |
| Routine PR review | Terra | Medium | premium | 88-94% | Terra High |
| Critical final review | Sol | High | premium | 93-97% | Sol xHigh |

---

## 10. Escalation Rules

### Free bounded workflow

`Hy3 High -> one repair loop -> Luna High / Terra Medium -> Terra High`

### Free large-context workflow

`Muse xHigh -> one repair loop -> Gemini High or Terra High -> Sol if critical`

### Planning / review / debugging

`Terra Medium/High -> Terra xHigh -> Sol High -> Sol xHigh -> Sol Max`

### Critical SeatFlow paths

Skip free-first when hidden failure can violate:

- reservation correctness
- payment correctness
- auth/security
- data integrity
- distributed consistency

Start at **Terra High or Sol High**.

---

## 11. Why More Reasoning Is Not Always Better

Reasoning effort is part of the routing decision, not a free quality slider.

Evidence such as Luna Max vs Terra High demonstrates:

- higher effort can massively increase output/reasoning volume
- latency can increase dramatically
- benchmark gains can be modest relative to extra compute
- a stronger base model at lower effort can be operationally superior

Therefore:

- prefer **Terra High over Luna Max** for serious interactive planning/debugging
- compare **Sol Medium/High** before blindly pushing Terra/Luna to Max
- use **Max** only when lower settings have failed or expected failure cost clearly dominates latency/compute

---

## 12. Sources

Primary reference categories used for this policy:

- OpenAI GPT-5.6 model/pricing/benchmark documentation
- Google DeepMind Gemini 3.7 Flash model card and pricing
- Tencent Hy3 official repository/model documentation
- OpenCode/Kilo current model availability
- Artificial Analysis model latency, throughput, token-volume, and agent/coding evaluations

Re-check live vendor documentation when exact current pricing or availability is materially important.

---

## 13. Final Policy

Use models according to their economic role:

```text
HY3 HIGH
= free bounded implementation worker

MUSE SPARK 1.2 XHIGH
= free large-context / agentic implementation worker

LUNA
= cheap premium deterministic fallback

GEMINI 3.7 FLASH HIGH
= premium frontend / agentic / large-context worker

TERRA
= senior engineer for planning / review / debugging / backend reasoning

SOL
= principal engineer for critical correctness
```

The best model is the **cheapest configuration whose expected reliability clears the task's risk threshold**.

Exploit free models when external verification is strong. Escalate when failures are hard to detect. Reserve Sol-class compute for mistakes whose consequences are materially worse than the inference bill.
