# MODEL_ROUTER.md

## Purpose

Use this file to choose the best AI model and reasoning effort for a SeatFlow software-development task.

Optimize for **successful task completion per unit of cost, latency, context, retry effort, and failure risk** — not for maximum model strength.

Available choices:
- GPT-5.6 Luna — Low / Medium / High / xHigh / Max
- GPT-5.6 Terra — Low / Medium / High / xHigh / Max
- GPT-5.6 Sol — Low / Medium / High / xHigh / Max
- Gemini 3.7 Flash — Low / Medium / High
- Hy3 — High
- Muse Spark 1.2 — xHigh

If a model/effort is unavailable in the current tool, choose the closest available configuration.

## 1. Classify the Task

Before recommending a model, estimate:

- **Complexity:** 1-5
- **Failure risk:** Low / Medium / High
- **Context:** Small (<20K), Medium (20-100K), Large (100-300K), Very Large (300K+)
- **Agentic demand:** Low / Medium / High
- **Verification:** Strong (compiler/tests/lint/browser) or Weak
- **Dominant requirement:** speed, deterministic implementation, reasoning, frontend, long-context, or reliability

Do not increase reasoning effort merely because a task is long. Increase it when the task is genuinely difficult, ambiguous, or costly to get wrong.

## 2. SeatFlow Model Roles

### Hy3 High — Free Deterministic Implementation Worker

Prefer for bounded, well-specified, strongly verifiable work:
- implementing an already-complete task file
- CRUD / DTO / mapper / repository / controller work
- unit and integration tests
- mechanical refactors
- reproducible bug fixes
- Docker / GitHub Actions / routine observability
- straightforward backend and frontend tasks

Use **Hy3 High first** when the task has clear acceptance criteria and compiler/tests can act as an objective oracle.

Do not make Hy3 the final authority for hidden high-cost correctness such as reservation concurrency, payment/idempotency, security, distributed consistency, or architecture.

### Muse Spark 1.2 xHigh — Free Large / Agentic Implementation Worker

Prefer for:
- larger cross-file implementation
- repository exploration
- long-horizon agentic edit/test/fix loops
- repo-wide refactors
- tasks requiring substantially more context than Hy3's ~256K window
- implementation where a ~1M context window is useful

Muse xHigh can be verbose and agent-harness dependent. Use it when the work is large and verifiable, not as final authority for critical invariants.

### Luna — Cheap Premium Worker

Use for deterministic, bounded, easily verified work when a paid but consistent fallback is useful:
- documentation
- tests
- boilerplate
- simple fixes
- mechanical refactors
- cheap premium implementation

Default: **Luna Medium**  
Bounded debugging/implementation: **Luna High**

Avoid **Luna Max** as an interactive default. Measured evaluations show it can consume dramatically more reasoning/output tokens and have much higher time-to-first-token than Terra High. More reasoning does not erase base-model limitations.

### Gemini 3.7 Flash High — Frontend / Agentic / Large-Context Premium Worker

Prefer especially for:
- Angular / Tailwind / UI implementation
- screenshot/mock-driven work
- visual iteration
- autonomous tool-heavy workflows
- large-context repository exploration
- frontend debugging where browser/tool loops dominate

Default serious mode: **Gemini 3.7 Flash High**.

Gemini High can consume many reasoning tokens, but its price/performance and agentic/frontend benchmark profile make that acceptable for appropriate tasks.

### Terra — Default Senior Engineer

Use for serious reasoning:
- feature planning
- backend architecture
- difficult debugging
- code review
- complex refactors
- JPA / transactions
- Kafka/outbox reasoning
- migrations
- API design
- cross-service reasoning

Default implementation: **Terra Medium**  
Default planning/debugging/review: **Terra High**  
Use xHigh for unusually difficult multi-service reasoning.

### Sol — Critical Escalation / Principal Engineer

Use when failure is expensive or difficult to detect:
- reservation locking / zero-double-booking
- payment / Stripe / idempotency
- authentication / authorization / security
- distributed consistency
- race conditions
- production incidents
- dangerous migrations
- data integrity
- critical architecture

Default difficult mode: **Sol High**  
Critical/high-risk mode: **Sol xHigh**  
Use **Sol Max only as final escalation**.

## 3. SeatFlow Routing Table

| SeatFlow task | Recommended |
|---|---|
| Documentation / extraction / status | Luna Low |
| DTO / mapper / boilerplate | Hy3 High |
| Unit/integration test generation | Hy3 High |
| CRUD / bounded backend task from complete spec | Hy3 High |
| Larger cross-file implementation from complete spec | Muse xHigh |
| Normal backend feature with design decisions | Terra Medium |
| Complex Spring/JPA transaction feature | Terra High |
| Autonomous repo-wide implementation | Muse xHigh |
| Frontend / Angular / Tailwind | Gemini High |
| Screenshot / visual UI implementation | Gemini High |
| Large-context repo exploration | Muse xHigh or Gemini High |
| Feature planning | Terra High |
| Architecture planning | Terra High |
| Critical architecture / new invariant | Sol High |
| Clear localized reproducible bug | Hy3 High |
| Cheap premium bug fallback | Luna High |
| Normal difficult debugging | Terra High |
| Tool-heavy frontend/debug exploration | Gemini High |
| Cross-service root-cause debugging | Terra High/xHigh |
| Intermittent/concurrency/distributed bug | Sol High/xHigh |
| Routine PR review | Terra Medium |
| Complex PR review | Terra High |
| Security/payment/concurrency final review | Sol High/xHigh |
| Mechanical refactor | Hy3 High |
| Large repo-wide refactor | Muse xHigh |
| DB migration | Terra High/xHigh |
| Dangerous production migration | Sol High/xHigh |
| CI / Docker / routine observability | Hy3 High |
| Production incident | Sol High/xHigh |

## 4. SeatFlow Risk Overrides

SeatFlow has architectural invariants where hidden mistakes are more expensive than inference cost. For these, **do not use free-first as the final decision layer**:

- zero double-booking
- 15-minute reservation holds
- PostgreSQL source-of-truth semantics
- transactional outbox / Kafka delivery guarantees
- reservation/payment idempotency
- Stripe webhook/payment state transitions
- authentication/authorization/security
- distributed consistency and race conditions

For these tasks, use **Terra High** at minimum for reasoning and **Sol High/xHigh** when failure can cause financial, security, or data-integrity damage.

## 5. Free-First Rule

When all of the following are true:
- task is well specified,
- failure risk is low-to-medium,
- compiler/tests/lint/browser can strongly verify correctness,
- architecture is already decided,

prefer:

1. **Hy3 High** for bounded implementation.
2. **Muse Spark 1.2 xHigh** for larger cross-file / long-context / agentic implementation.
3. Escalate only if verification fails repeatedly or hidden reasoning risk appears.

A free retry is monetarily cheap, but developer time and context are not free. After **one meaningful repair loop**, reassess whether the problem is a model-capability issue rather than repeatedly retrying the same model.

## 6. Key Comparison Rules

Prefer **Hy3 High over Luna/Terra** when the implementation is deterministic, bounded, and test-verifiable.

Prefer **Muse xHigh over Hy3 High** when the task is larger, strongly agentic, cross-file, or needs >256K useful context.

Prefer **Gemini High over Muse/Hy3** when frontend quality, visual iteration, browser/tool automation, or premium agent reliability matters.

Prefer **Terra High over Hy3/Muse/Gemini** when precise backend reasoning, invariants, transactions, architecture, root-cause analysis, or weakly-verifiable correctness dominate.

Prefer **Sol High/xHigh** when payment, security, data integrity, concurrency, distributed consistency, or production-critical behavior dominates.

Prefer **Terra High over Luna Max** for difficult interactive planning/debugging. Luna Max's low nominal token price can be offset operationally by much higher reasoning volume and latency.

## 7. Verification Rule

Strong automatic verification makes free/cheap models more attractive.

If compiler + tests + lint + integration/browser checks can reliably reject bad work:
- favor Hy3, Muse, Luna, or Gemini.

If correctness is difficult to verify automatically:
- favor Terra or Sol.

## 8. Escalation Policy

### Bounded implementation
`Hy3 High -> one repair loop -> Luna High / Terra Medium -> Terra High`

### Large / cross-file / agentic implementation
`Muse xHigh -> one repair loop -> Gemini High or Terra High -> Sol High if critical`

### Frontend
`Gemini High -> Terra High for architecture/state issues -> Sol only if critical`

### Serious backend reasoning
`Terra High -> Terra xHigh -> Sol High -> Sol xHigh -> Sol Max`

Do not repeatedly retry a weaker model at extreme effort when evidence suggests a base-capability limitation.

## 9. Cost Principle

Never optimize only for nominal $/token.

Use:

`Effective Cost = token cost + retries + latency + developer review time + expected failure cost`

Free models have zero model cost but not zero workflow cost. A paid stronger model can be cheaper overall when it prevents repeated failures or a hidden correctness bug.

## 10. Response Format

When asked which model to use, respond concisely:

**Recommended:** MODEL — EFFORT

**Why:** 2-4 sentences.

**Task profile:**
- Complexity: X/5
- Risk: Low/Medium/High
- Context: Small/Medium/Large/Very Large
- Agentic demand: Low/Medium/High

**Free/cheaper option:** MODEL — EFFORT, plus tradeoff.

**Escalate to:** MODEL — EFFORT only if a specific condition occurs.

## 11. Reference Rule

If the choice is ambiguous, unusually expensive, high-risk, or the user requests quantitative justification, consult `.ai/AI_MODEL_REFERENCE.md`.

For ordinary routing, **do not load the reference file unnecessarily**.
