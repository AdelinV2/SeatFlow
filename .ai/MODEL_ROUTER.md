# MODEL_ROUTER.md

## Purpose

This file is the **single source of truth for AI provider, model, reasoning-effort, fallback, and escalation selection in SeatFlow**.

Optimize for:

1. correctness and escaped-defect risk;
2. implementation success per unit of quota/cost;
3. independence between implementation and review;
4. harness/tool fit;
5. deterministic verification strength;
6. latency only after the above are acceptable.

Do **not** choose the strongest or most expensive model by default. SeatFlow deliberately separates planning, implementation, review, fixing, and QA so premium reasoning is spent where it changes expected correctness rather than on every line of code.

Workflow behavior lives in `.ai/workflows/`. Autonomous sequencing lives in `.ai/ORCHESTRATOR.md`. Poracode-specific execution details live in `.ai/integrations/PORACODE.md`.

---

## 1. Active Harnesses and Preferred Model Families

### Codex Plus

Primary roles: independent backend judgment, difficult debugging, substantive review, critical correctness.

- GPT-5.6 Luna
- GPT-5.6 Terra
- GPT-5.6 Sol

**Global Codex rule:** standard speed only. `Fast mode = OFF` unless the user explicitly requests Fast for that run.

### Antigravity Pro

Primary roles: supervisor/coordinator, routine planning, frontend/browser/visual work, fast independent analysis, normal final QA.

- Gemini 3.8 Flash Medium
- Gemini 3.8 Flash High
- Gemini 3.1 Pro High
- Claude Sonnet 4.6 Thinking
- Claude Opus 4.6 Thinking

Gemini 3.8 Flash is the preferred Antigravity family for SeatFlow because speed, large context, tool/browser fit, and coding capability make it efficient for orchestration and frontend-heavy work.

### OpenCode Zen / OpenCode Go

Primary role: high-volume implementation, repair, tests, deterministic refactors, terminal-heavy execution.

Preferred logical route:

`MUSE_1_3 = Muse Spark 1.3 Free -> Muse Spark 1.3 Contributor (Go) -> task alternative`

Current exact selections:

- Free: `opencode/muse-spark-1.3-contributor-free`
- Go: `opencode-go/muse-spark-1.3-contributor`

No other OpenCode / OpenCode Go model is recommended. Do not route OpenCode work to GLM 5.3 / GLM-Flash, Hy3, Hy4 (including preview variants), Kimi, Qwen, DeepSeek, MiniMax, Grok, or any other OpenCode catalog model. The only permitted OpenCode routes are the two Muse Spark 1.3 routes above. If both Muse routes are unavailable or prohibited, use the task-specific Alternative from this router (normally a non-OpenCode harness), not another OpenCode model.

---

## 2. Classify Before Routing

For each stage estimate:

- **Complexity:** `1-5`
- **Failure risk:** `Low | Medium | High | Critical`
- **Context:** `Small (<20K) | Medium (20-100K) | Large (100-300K) | Very Large (300K+)`
- **Agentic demand:** `Low | Medium | High`
- **Verification strength:** `Strong | Partial | Weak`
- **Dominant requirement:** one or more of:
  - deterministic implementation
  - architecture/design judgment
  - backend transaction/concurrency reasoning
  - frontend/browser/visual work
  - difficult debugging
  - independent review
  - critical correctness
  - large-context exploration
  - low-risk utility/documentation

Do not raise effort simply because a task is long. Raise effort when ambiguity, hidden failure risk, cross-service reasoning, weak verification, or expensive failure justifies it.

---

## 3. Recommendation Vocabulary

### Recommended

Best default route for the current stage.

### Alternative

A peer route usable immediately when quota, provider availability, privacy, harness behavior, latency, or model-family diversity makes the recommendation inconvenient.

An Alternative is **not** automatically stronger.

### Fallback

A route used because the preferred provider/model is unavailable or disallowed, not because its previous output was wrong.

### Escalation

A stronger/more expensive reasoning route justified by a concrete quality/risk trigger such as:

- unresolved ambiguity;
- meaningful repair loop failed;
- verification is weak/inconclusive;
- task risk changed materially;
- P0/P1 finding remains unresolved;
- hidden-failure cost is critical.

Never confuse availability fallback with quality escalation.

---

## 4. Mandatory Muse Spark 1.3 Resolver

Whenever a table or workflow below says `Muse 1.3 High` or `Muse 1.3 xHigh`, resolve the **provider route first**:

```text
1. OpenCode / Muse Spark 1.3 Contributor Free
   model: opencode/muse-spark-1.3-contributor-free

      if unavailable / rate-limited / disabled / privacy-prohibited
                         |
                         v
2. OpenCode Go / Muse Spark 1.3 Contributor
   model: opencode-go/muse-spark-1.3-contributor

      if unavailable / region-restricted / privacy-prohibited
                         |
                         v
3. Use the task-specific Alternative from this router
```

### 4.1 Free-first invariant

If Muse is selected for the stage and the Free route is available, **use Free first**.

Do not consume OpenCode Go Muse allowance while the Free route is healthy unless:

- user explicitly asks for Go Contributor;
- Free is unavailable/rate-limited/provider-blocked;
- a current provider limitation makes the Go route materially more reliable;
- privacy policy prohibits the route and the Go route is independently verified to satisfy that policy.

### 4.2 Free -> Go is not escalation

Switching from Free Muse 1.3 to Go Muse 1.3 is an **availability fallback**, not a quality escalation.

A buggy result, failed test, or reviewer finding does not by itself justify Free -> Go. Follow the normal repair/debugging route.

### 4.3 Muse effort

When exposed by the selected OpenCode catalog:

- `High` — small/mechanical implementation, narrow fixes, routine migrations from a fixed spec
- `xHigh` — default substantive implementation, multi-file tasks, serious fixes, repo exploration/edit/test loops, larger refactors

Do not invent an effort/variant not exposed by the provider. If `xHigh` is absent, use the strongest practical non-Max effort and report the actual selection.

---

## 5. Primary Role Policy

### 5.1 Supervisor / Orchestration

**Recommended:** `Gemini 3.8 Flash Medium` through Antigravity for pure coordination.

Use `Gemini 3.8 Flash High` when the supervisor itself must perform meaningful repository analysis, resolve stage ambiguity, or coordinate a large cross-service task.

Do not use Terra/Sol merely to keep track of stages.

### 5.2 Routine Planning

**Recommended:** `Gemini 3.8 Flash High`.

Best for:

- turning an already-known architecture into an implementation sequence;
- broad repo exploration;
- frontend planning;
- large-context task decomposition;
- quick plan validation before Muse execution.

**Alternative:** `GPT-5.6 Terra High`.

### 5.3 Backend Architecture / ADR / Subtle Design Judgment

**Recommended:** `GPT-5.6 Terra High`, Fast OFF.

Use for:

- JPA transaction/locking semantics;
- API/data model design with trade-offs;
- Kafka/outbox semantics;
- cross-service contract design;
- migration strategy;
- difficult architecture contradictions.

**Alternative:** `Gemini 3.8 Flash High`.

**Escalate:** `Terra xHigh` only for unusually difficult unresolved architecture; `Sol High` when the design directly controls critical money/security/data-integrity/concurrency invariants.

### 5.4 Deterministic Backend / General Implementation

**Recommended:** `Muse 1.3 xHigh` using the mandatory Free -> Go resolver.

Java/Spring alone is **not** a reason to route away from Muse.

Use Muse for:

- complete well-specified task implementation;
- Java 21 / Spring Boot services;
- DTOs, mappers, repositories, controllers, services;
- Flyway migrations from approved DDL/contracts;
- Kafka/outbox code from an approved contract;
- unit/integration/concurrency test implementation;
- Docker/CI/observability work;
- deterministic refactoring;
- large multi-file edit/test/fix loops.

**Alternative:** `Gemini 3.8 Flash High`.

Use Terra/Sol primarily as decision/review layers, not bulk code writers, unless implementation still contains unresolved design judgment.

### 5.5 Frontend / Browser / Visual Implementation

**Recommended:** `Gemini 3.8 Flash High` through Antigravity.

Best for:

- Angular / TypeScript / Tailwind;
- screenshot/mock-driven work;
- responsive interaction;
- browser-heavy debugging;
- frontend signals/state/UI exploration;
- visual iteration.

**Alternative:** `Muse 1.3 xHigh` via the Free -> Go resolver.

### 5.6 Clear Localized Bug Fix

**Recommended:** `Muse 1.3 High` or `xHigh` according to scope, Free -> Go resolver.

Require reproduction/regression evidence when practical.

**Alternative:** `Gemini 3.8 Flash High`.

After one meaningful failed repair where root cause remains unclear, **escalate diagnosis** to Terra High rather than blindly repeating Muse.

### 5.7 Difficult Root-Cause Debugging

**Recommended:** `GPT-5.6 Terra High`, Fast OFF.

Best for ambiguous backend failures, transaction semantics, cross-service root cause, and interpreting weak/flaky evidence.

**Alternative:** `Gemini 3.8 Flash High`, especially when tool/browser/repo exploration dominates.

**Escalate:** `Terra xHigh` or `Sol High` only if the remaining ambiguity is unusually difficult or becomes critical-risk.

### 5.8 Independent Review

**Small/mechanical review:** `GPT-5.6 Terra Medium`, Fast OFF.

**Substantive review:** `GPT-5.6 Terra High`, Fast OFF.

**Critical money/security/concurrency/data-integrity review:** `GPT-5.6 Sol High`, Fast OFF.

**Alternative normal reviewer:** `Gemini 3.8 Flash High` when Codex quota is constrained or independent model-family diversity is desirable.

Reviewer independence matters more than using the most expensive model twice.

### 5.9 Critical Correctness Authority

Use `GPT-5.6 Sol High`, Fast OFF when hidden mistakes can plausibly cause:

- double booking;
- incorrect 15-minute hold semantics under concurrency;
- money loss or broken payment/refund state;
- broken idempotency;
- authorization/security exposure;
- corrupted persistent state;
- distributed ordering/consistency violations;
- unsafe destructive migrations.

Use `Sol xHigh` only after `Sol High` leaves material unresolved ambiguity or the incident is genuinely severe.

`Sol Max` is an exceptional final escalation, not a routine stage.

### 5.10 Utility / Documentation / Status

Prefer `Gemini 3.8 Flash Medium` for routine repository status, documentation drafting, extraction, and simple coordination because it preserves Codex quota.

Use `GPT-5.6 Luna Low/Medium`, Fast OFF when Codex-specific context/tooling is useful.

`Luna High` is a bounded fallback for small coding/judgment tasks, not the normal reviewer for substantive SeatFlow changes.

Do not use Luna Max or Luna High Fast as a default workaround for difficult reasoning. Move to Terra instead.

---

## 6. Routing Table

| SeatFlow stage/task | Recommended | Alternative | Escalation / review |
|---|---|---|---|
| Supervisor only / stage coordination | Gemini 3.8 Medium | Luna Low/Medium | Gemini 3.8 High if supervisor must analyze deeply |
| Routine task planning | Gemini 3.8 High | Terra High | Terra High/xHigh when backend design ambiguity dominates |
| Create atomic task `.md` | Gemini 3.8 High | Terra High | Sol High if critical invariant/ADR decision |
| Backend architecture / ADR | Terra High | Gemini 3.8 High | Terra xHigh; Sol High if critical |
| Tiny mechanical backend change | Muse 1.3 High | Gemini 3.8 Medium/High | Terra Medium review only if meaningful risk |
| Normal substantive backend implementation | Muse 1.3 xHigh | Gemini 3.8 High | Terra High review |
| CRUD / DTO / mapper / repository / controller | Muse 1.3 xHigh | Gemini 3.8 High | Terra High review when substantive |
| Unit/integration/concurrency test implementation | Muse 1.3 xHigh | Gemini 3.8 High | Terra High for weak oracle/test strategy |
| Large cross-file backend implementation | Muse 1.3 xHigh | Gemini 3.8 High | Terra High review; Sol High if critical risk |
| Complex Spring/JPA implementation from complete plan | Muse 1.3 xHigh | Terra High | Terra High review; Sol High critical review |
| Kafka/outbox implementation from complete contract | Muse 1.3 xHigh | Terra High | Terra High review; Sol High if delivery/ordering invariant changes |
| Angular / TypeScript / Tailwind | Gemini 3.8 High | Muse 1.3 xHigh | Terra High review for non-trivial state/contracts |
| Simple frontend styling/component | Gemini 3.8 Medium | Muse 1.3 High | Gemini High if scope grows |
| Screenshot/browser-heavy UI | Gemini 3.8 High | Muse 1.3 xHigh | Terra High only for contract/state risk |
| Large-context repo exploration | Gemini 3.8 High | Muse 1.3 xHigh | Terra High when subtle backend conclusions matter |
| Clear localized reproducible bug | Muse 1.3 High/xHigh | Gemini 3.8 High | Terra High diagnosis after failed meaningful repair |
| Difficult backend root cause | Terra High | Gemini 3.8 High | Terra xHigh; Sol High if critical |
| Browser/tool-heavy debugging | Gemini 3.8 High | Muse 1.3 xHigh | Terra High if root cause ambiguous |
| Cross-service root cause | Terra High | Gemini 3.8 High | Terra xHigh; Sol High if critical |
| Intermittent concurrency/distributed bug | Sol High | Terra xHigh | Sol xHigh if unresolved |
| Routine small review | Terra Medium | Gemini 3.8 High | Terra High if meaningful issue surface appears |
| Substantive code review | Terra High | Gemini 3.8 High | Sol High for critical domain |
| Security/payment/concurrency final review | Sol High | Terra xHigh | Sol xHigh if unresolved |
| Mechanical refactor execution | Muse 1.3 High | Gemini 3.8 Medium/High | Terra Medium review if behavior risk |
| Large repo-wide refactor execution | Muse 1.3 xHigh | Gemini 3.8 High | Terra High planning/review |
| Routine DB migration from approved schema | Muse 1.3 High/xHigh | Terra High | Terra High review |
| Dangerous/destructive migration | Sol High design/review + Muse xHigh execution | Terra xHigh design/review | Sol xHigh if unresolved |
| CI / Docker / observability | Muse 1.3 xHigh | Gemini 3.8 High | Terra High hard diagnosis |
| Normal final QA | Gemini 3.8 High | Terra High | Terra High if hidden backend semantics dominate |
| Critical final QA / hidden-invariant verification | Terra High + required deterministic tests | Gemini 3.8 High | Sol High final judgment when critical |
| Documentation / extraction / status | Gemini 3.8 Medium | Luna Low/Medium | Terra only if reasoning becomes non-trivial |

All Muse entries implicitly mean **Free first, then Go Contributor**.
All Codex entries implicitly mean **Fast OFF** unless explicitly requested by the user.

---

## 7. Reviewer Independence Matrix

Default implementation -> review pairing:

| Implementer | Preferred independent reviewer |
|---|---|
| OpenCode / Muse | Codex / Terra (Sol if critical) |
| Antigravity / Gemini | Codex / Terra (Sol if critical) |
| Codex / GPT | Antigravity / Gemini for normal risk; separate Codex critical authority only when risk requires it |

Avoid same-model self-review for substantive work.

A second reviewer is justified when:

- critical invariant is involved;
- first review found P0/P1;
- verification is weak;
- implementation changed materially during fixes;
- model-family diversity addresses a realistic hidden-failure risk.

Do not add extra passes merely to look rigorous.

---

## 8. Risk Overrides

The implementation worker must not become the final decision authority for:

- zero double-booking;
- reservation hold concurrency/expiry;
- PostgreSQL source-of-truth semantics;
- transactional outbox / Kafka guarantees;
- reservation/payment/refund idempotency;
- Stripe state transitions/webhooks;
- authentication/authorization/security;
- distributed consistency/order/races;
- destructive or hard-to-repair migrations.

Rules:

1. Java/Spring alone is not a risk override.
2. Terra High is the normal decision/review floor for subtle non-critical backend invariants.
3. Sol High is required when plausible failure can cause money/security/data-corruption/double-booking severity.
4. Sol xHigh is reserved for unresolved severe ambiguity.
5. Sol is a risk override, not a blanket implementation model.

---

## 9. Verification-First Routing

When compiler + meaningful tests + integration/browser checks provide a strong oracle:

- favor Muse xHigh for substantive backend/general implementation;
- favor Gemini 3.8 High for frontend/browser implementation;
- preserve Terra/Sol for planning, diagnosis, review, and critical judgment.

When correctness is weakly observable:

- raise planning/review authority to Terra High;
- use Sol High for critical hidden failure modes;
- do not compensate for weak verification merely by increasing the implementation model's effort.

For every non-trivial change:

1. run the task's deterministic verification;
2. run relevant regression/integration checks;
3. perform independent review via `.ai/workflows/05-code-review.md`;
4. create `.ai/tmp/review-<task-or-branch>.md` for actionable findings;
5. repair through `.ai/workflows/03-bug-fixing.md`;
6. re-review P0/P1 and other substantive/high-risk fixes;
7. remove the temporary ledger only after resolution/re-review;
8. pass `.ai/workflows/04-testing-and-qa.md`.

---

## 10. Standard Pipelines

### Normal backend task

`Gemini High plan if needed -> Muse xHigh (Free -> Go) implement -> tests -> Terra High review (Fast OFF) -> Muse fixes -> re-review if needed -> Gemini High QA`

### Small mechanical backend task

`Muse High (Free -> Go) -> targeted tests -> optional Terra Medium review -> QA proportional to risk`

### Large backend / cross-file task

`Gemini High or Terra High plan -> Muse xHigh (Free -> Go) -> full verification -> Terra High review -> fixes/re-review -> Gemini High QA`

Use Terra for planning when backend architecture/transaction semantics, not mere size, is the dominant difficulty.

### Frontend feature

`Gemini High plan/implementation -> browser/tests -> Terra High independent review when state/contracts are substantive -> Gemini High QA`

Alternative implementation: Muse xHigh (Free -> Go).

### Reproducible bug

`Muse High/xHigh reproduce + regression test + fix -> verify -> review when substantive/high-risk`

After one meaningful failed repair with unclear root cause: `Terra High diagnosis -> targeted repair`.

### Hard backend bug

`Terra High diagnosis -> Muse xHigh targeted fix -> Terra review -> QA`

Alternative diagnosis: Gemini High when tool/repository exploration dominates.

### Critical reservation/payment/security task

`Sol High risk analysis (Fast OFF) -> explicit deterministic spec -> Muse xHigh (Free -> Go) implementation -> exhaustive tests -> Sol High final review (Fast OFF) -> fixes/re-review -> QA`

If implementation still requires active architecture decisions, use Terra High as implementation alternative rather than asking Muse to invent the design.

### Repo-wide refactor

`Terra High or Gemini High plan -> Muse xHigh execution -> full verification -> Terra High independent review -> QA`

---

## 11. Effort Policy

### Muse Spark 1.3

- High: small/mechanical work
- xHigh: default substantive implementation/fix/refactor
- Max: do not assume; use only if explicitly present and specifically justified

### Gemini 3.8 Flash

- Medium: supervision, simple UI, quick exploration, docs/status
- High: substantive planning, frontend/backend implementation, browser/tool loops, large-context work, normal QA

### GPT-5.6 Terra

- Medium: small bounded review/judgment
- High: substantive review, difficult debugging, backend architecture
- xHigh: unusually hard unresolved architecture/multi-service diagnosis
- Max: not routine

### GPT-5.6 Sol

- High: critical risk analysis/review authority
- xHigh: unresolved/severe critical issue
- Max: exceptional final escalation only

### GPT-5.6 Luna

- Low/Medium: utility/docs/extraction when Codex is useful
- High: bounded fallback only
- Fast: OFF unless user explicitly asks

---

## 12. Privacy Policy

Muse Spark 1.3 Contributor routes are treated conservatively as potentially training/non-ZDR routes.

Do not send:

- secrets/credentials/tokens/private keys;
- real `.env` contents;
- private production/customer dumps;
- payment credentials or sensitive PII;
- other confidential data prohibited by repository policy.

When sensitive context is necessary, select a privacy-compatible alternative based on current provider metadata.

Never assume Free and Go have different privacy guarantees merely because one is paid.

---

## 13. Availability and Catalog Drift

Model availability changes.

- Query the actual harness catalog when uncertain.
- Do not invent model IDs or efforts.
- Do not silently substitute another model.
- Record actual provider/model/effort in orchestrated stage output.
- Update `.ai/integrations/PORACODE.md` when exact Poracode/OpenCode IDs change.

OpenCode Go's currently documented Muse ID is `opencode-go/muse-spark-1.3-contributor`; OpenCode Zen's Free route is `opencode/muse-spark-1.3-contributor-free` as verified in Poracode/OpenCode selection.

---

## 14. Selection Output Contract

When asked which AI should perform a SeatFlow stage, return:

```text
Recommended: <provider / model / effort>
Why: <2-4 sentences tied to risk, task type, verification, and quota>
Fallback: <availability fallback, if relevant>
Alternative: <peer route>
Trade-off: <concise difference>
Review model: <if independent review required>
Escalate to: <model only after a concrete trigger>
Fast mode: OFF | N/A
Task profile: complexity / risk / context / agentic demand / verification
```

If `Muse 1.3` is Recommended, explicitly state:

`Free first -> OpenCode Go Contributor if Free unavailable`.

---

## 15. Detailed Evidence

Consult `.ai/AI_MODEL_REFERENCE.md` only when:

- the route is ambiguous/disputed;
- the user requests benchmarks/economics;
- a provider/model changed materially;
- the task is unusually high-risk;
- quantitative justification is needed.

Do not load the detailed reference for ordinary stage routing. Preserve context for the task itself.
