# AI_MODEL_REFERENCE.md

## Purpose

Detailed evidence and rationale for `.ai/MODEL_ROUTER.md`.

Data snapshot: **September 4, 2026**.

This file is intentionally not part of the normal per-task context. Read it only when model selection is ambiguous, disputed, cost-sensitive, high-risk, or requires quantitative justification.

A crucial interpretation rule:

> **Model capability != harness capability.**

The same base model can behave differently in Muse Code, OpenCode, Codex, Antigravity, or another agent runtime because prompts, tool APIs, context handling, caching, retries, and provider serving differ.

SeatFlow therefore optimizes the **whole pipeline** rather than ranking isolated models.

---

## 1. SeatFlow Workload

SeatFlow is primarily:

- Java 21 / Spring Boot 4 backend;
- Angular 22 / TypeScript frontend;
- PostgreSQL, Kafka transactional outbox, Redis, Stripe, Supabase Auth;
- microservice and cross-service contract work;
- explicit task files and architecture docs;
- long-horizon agentic edit/test/fix loops;
- independent review and deterministic QA.

Critical hidden-failure areas include:

- zero double booking;
- 15-minute hold semantics under concurrency;
- PostgreSQL source-of-truth behavior;
- Kafka/outbox delivery and idempotency;
- payment/refund/webhook state transitions;
- authorization/security;
- distributed consistency/order;
- destructive or hard-to-repair migrations.

The target is **very good code quality with sustainable quota usage**, not maximum model strength on every prompt.

---

## 2. Current Harness Set

### 2.1 Codex Plus

SeatFlow uses Codex primarily for independent engineering judgment:

- GPT-5.6 Luna — utility/bounded work;
- GPT-5.6 Terra — normal backend architecture, difficult debugging, substantive review;
- GPT-5.6 Sol — critical money/security/data/concurrency authority.

Operational rule: **Fast mode stays OFF by default.** Fast is a separate acceleration decision and should not be silently enabled by an orchestrator.

### 2.2 Antigravity Pro

SeatFlow uses Antigravity primarily for:

- supervisor/orchestration;
- routine planning;
- large-context repository exploration;
- Angular/browser/visual work;
- normal QA and independent analysis.

Preferred general family: Gemini 3.8 Flash Medium/High.

### 2.3 OpenCode Zen / Go

OpenCode is the preferred high-volume implementation harness.

Current official OpenCode Go documentation lists **Muse Spark 1.3 Contributor** among Go models and documents the model ID `muse-spark-1.3-contributor`; OpenCode configuration uses the `opencode-go/` prefix.

Current OpenCode Zen documentation lists **Muse Spark 1.3 Contributor Free** with model ID `muse-spark-1.3-contributor-free`.

SeatFlow's verified Poracode selections are therefore:

```text
Free: opencode/muse-spark-1.3-contributor-free
Go:   opencode-go/muse-spark-1.3-contributor
```

Official references:

- OpenCode Go docs: `https://opencode.ai/docs/go/`
- OpenCode Go product/limits: `https://dev.opencode.ai/go`
- OpenCode Zen docs/free models: `https://dev.opencode.ai/docs/zen`

The catalog changes over time; `/models` or the current harness model picker is authoritative for availability.

---

## 3. Why Muse Spark 1.3 Is the Default Implementation Route

The current evidence supports treating Muse Spark 1.3 as a frontier-class coding model rather than a budget-only model.

The prior SeatFlow benchmark snapshot recorded:

- Muse Spark 1.3 xHigh around the top tier of Artificial Analysis' broad Intelligence Index;
- Muse Code + Muse Spark 1.3 xHigh competitive with flagship Codex coding-agent results on current agentic/coding benchmarks;
- especially strong terminal/tool-oriented behavior.

These numbers are directional, not guarantees, and the strongest public Muse results may be measured in Muse Code rather than OpenCode. Harness differences therefore prevent copying a first-party score directly onto OpenCode.

Operationally, Muse fits SeatFlow because tasks are usually:

- explicitly specified before coding;
- bounded by architecture/ADRs;
- test-verifiable;
- multi-file and tool-heavy;
- suitable for autonomous repo exploration/edit/test loops.

This makes Muse a good place to spend **volume**, while Terra/Sol are better places to spend scarce premium reasoning on planning/review/critical judgment.

---

## 4. Java Evidence and the Correct Inference

There is still no clean apples-to-apples public Java benchmark between Muse Spark 1.3 and GPT-5.6 in the same harness/provider setup.

The previous SeatFlow evidence set noted that Muse's published DeepSWE methodology did not include Java, while independent Java evaluations showed GPT-5.6 Terra/Sol to be strong Java generators.

Correct inference:

> Muse has an evidence gap for directly comparable Java benchmarking.

Incorrect inference:

> Muse is weak at Java, therefore every Java implementation should use Terra/Sol.

SeatFlow therefore keeps this invariant:

**Java/Spring alone is not a reason to route deterministic implementation away from Muse.**

For subtle transactions, concurrency, payments, security, distributed consistency, or irreversible data changes, use Terra/Sol for the **decision and review layer** even if Muse performs the final deterministic implementation.

---

## 5. Free Muse vs OpenCode Go Muse

This is an availability/economics distinction, not a quality-tier distinction in SeatFlow's workflow.

### Free route

`opencode/muse-spark-1.3-contributor-free`

Use first whenever:

- it is present in the current catalog;
- provider capacity/rate limits allow it;
- the task's privacy policy permits it.

### Go route

`opencode-go/muse-spark-1.3-contributor`

OpenCode Go currently documents Muse Spark 1.3 Contributor as part of the $10/month Go model set, though availability is region-dependent.

Use automatically when the Free route is unavailable/rate-limited/disabled.

### Why the fallback should be automatic

A user paying for OpenCode Go already has the paid fallback for reliability. Requiring confirmation every time Free temporarily disappears adds friction without improving engineering judgment.

### What Free -> Go does not mean

A buggy Free attempt should not automatically be re-run on paid Contributor merely because paid sounds stronger. If the model produced a quality failure, use the normal fix/diagnosis/escalation pipeline.

---

## 6. OpenCode Go Economics

Current OpenCode Go product information advertises very generous relative allowance for Muse Spark 1.3 Contributor compared with many other Go models.

The September 4 product page currently shows approximately:

- Muse Spark 1.3 Contributor: ~45,300 typical requests / 5h reference window;
- GPT-5.6 Luna: ~2,050;
- Hy4 preview: ~1,350;
- other heavier models substantially lower than Muse.

These figures are **relative product estimates**, not guarantees for SeatFlow-sized prompts. A long agentic run can consume far more than an average request.

The useful conclusion is robust:

> Muse is cheap enough inside Go that lowering effort on meaningful implementation merely to save tiny amounts of Muse allowance is usually false economy if it creates extra Terra/Sol repair passes.

Hence `xHigh` remains the normal substantive Muse effort when exposed.

---

## 7. Privacy / Data Retention

OpenCode's current Go documentation marks **Muse Spark 1.3 Contributor** as potentially used for model training and not ZDR.

SeatFlow therefore applies the conservative policy to both Muse Contributor routes unless current provider metadata proves otherwise:

Do not send:

- secrets/API keys/tokens/private keys;
- real `.env` values;
- customer/production dumps;
- payment credentials;
- sensitive PII;
- confidential data prohibited by project policy.

Public SeatFlow source and synthetic/test data are acceptable under the current project policy.

Do not assume the paid Go route is automatically more private than the Free route.

---

## 8. Why Gemini 3.8 Flash Is Used More Aggressively

Gemini 3.8 Flash High is not only a frontend styling model. Current evidence and SeatFlow experience support it as a strong general tool-using model with very large context and high speed.

Best operational roles:

- Antigravity supervisor;
- routine planning/task decomposition;
- frontend/browser/visual implementation;
- broad repository exploration;
- normal final QA;
- independent alternative when OpenCode/Codex quota or availability is inconvenient.

This also reduces unnecessary Codex consumption for orchestration and routine planning.

For subtle backend transaction/JPA/distributed semantics, Terra remains the preferred judgment layer because role specialization and direct backend reasoning are more valuable than raw speed.

---

## 9. Why Terra Is the Default Substantive Reviewer

Terra is not selected because it must be better than Muse at every coding benchmark.

It is selected because SeatFlow needs a stable independent senior-engineer layer for:

- task/contract interpretation;
- JPA transaction/locking reasoning;
- migrations and API/event contracts;
- hard root-cause analysis;
- skeptical code review;
- interpreting weak/incomplete verification.

`Muse implementation -> Terra review`

is operationally stronger than using the same model for both generation and approval because it separates execution from judgment and reduces correlated mistakes.

For small/mechanical reviews, Terra Medium is enough. Terra High is the normal substantive review route.

---

## 10. Why Sol Is a Risk Override

Sol is reserved for places where a plausible error can survive ordinary tests and have expensive consequences:

- double booking/races;
- payment/refund/idempotency state;
- auth/security;
- persistent data corruption;
- distributed ordering;
- dangerous migrations.

Preferred critical pipeline:

```text
Sol High risk analysis
-> explicit deterministic task/spec
-> Muse xHigh implementation (Free -> Go)
-> exhaustive deterministic tests
-> Sol High independent final review
```

This spends premium quota where it changes expected escaped-defect risk instead of using Sol as a bulk code writer.

Sol xHigh is for unresolved severe ambiguity. Sol Max is exceptional.

---

## 11. Why Luna Is Not the Default Reviewer

Luna is useful for:

- documentation/extraction;
- low-risk utility work;
- tiny deterministic fixes;
- quick bounded checks.

For substantive review, Terra Medium/High is a better allocation of Codex quota than trying to compensate with Luna High.

Most importantly for Poracode:

**Luna High Fast is not a default SeatFlow route.**

Fast mode is explicitly disabled by repository policy unless the user asks for it.

---

## 12. Secondary OpenCode Models

### Hy4 preview

Useful for:

- independent open-model-family attempts;
- long-horizon/tool-heavy tasks when Muse behaves poorly;
- terminal-heavy debugging.

Because it is a preview route and materially more expensive in Go allowance than Muse, it is not the default final authority for critical invariants.

### GLM-5.3 / GLM-5.3-Flash

Credible terminal-heavy alternatives, but current Go economics do not justify replacing Muse as the normal implementation worker.

Use when:

- Muse is unavailable;
- an independent open-model-family attempt is specifically useful;
- provider behavior makes GLM materially better for the current task.

### Other Go models

Kimi, Qwen, DeepSeek, MiniMax, Grok and others may be capable. They remain secondary until a SeatFlow-specific reason or updated evidence justifies promoting them into the default route.

Avoid churn in the router merely because a new model appears in the catalog.

---

## 13. Poracode / Crossagents Evidence

Current Poracode documentation/changelog states that Crossagents can:

- delegate from one installed coding agent to another;
- run child agents in the background;
- stream child output into the parent thread;
- configure routing by provider usage, favorites/tags, pins, paused providers, hidden models, and learned routing data;
- operate with Codex, OpenCode, Antigravity/ACP and other installed agents;
- control Git worktrees/PR workflows through Poracode's MCP tooling.

Current official reference:

- `https://poracode.com/`
- `https://poracode.com/changelog`

Poracode therefore fits SeatFlow as an execution/orchestration harness, while `.ai/ORCHESTRATOR.md` and `.ai/MODEL_ROUTER.md` remain the policy layer.

---

## 14. Operational Decision Summary

### Routine backend

`Gemini High plan when needed -> Muse xHigh Free -> Muse xHigh Go fallback -> tests -> Terra High review -> Muse fixes -> Gemini High QA`

### Frontend

`Gemini High implementation -> browser/tests -> Terra High review when state/contracts are substantive -> Gemini High QA`

### Clear bug

`Muse High/xHigh -> regression test -> fix -> review if substantive`

### Hard backend bug

`Terra High diagnosis -> Muse xHigh repair -> Terra review`

### Critical reservation/payment/security

`Sol High risk analysis -> Muse xHigh implementation -> exhaustive tests -> Sol High final review`

### Standard speed policy

Every Codex stage: `Fast = OFF` unless explicitly requested.

### Muse availability policy

Every Muse stage: `Free -> Go Contributor -> task-specific alternative`.

---

## 15. When to Re-evaluate This Router

Update the router/reference when one of these changes materially:

- OpenCode removes/adds Muse Free or Contributor;
- provider/model IDs change;
- Go allowance/economics change enough to affect routing;
- Poracode changes Crossagents selection semantics;
- new independent coding/agent benchmarks materially reorder relevant models;
- a new model demonstrates clearly better SeatFlow-specific performance;
- repeated real SeatFlow outcomes contradict the current routing policy.

Prefer actual SeatFlow task outcomes plus deterministic review evidence over benchmark-chasing.
