# AI_MODEL_REFERENCE.md

## Purpose

Detailed evidence and rationale for `.ai/MODEL_ROUTER.md`.

Data snapshot: **September 3, 2026**.

Benchmark numbers are directional, not guarantees. Harness, prompts, tools, repository structure, reasoning configuration, caching, provider implementation, safety filtering, and task mix can materially change real-world results. Prefer benchmark families that resemble SeatFlow's actual workflow: long-horizon code changes, terminal/tool use, codebase understanding, debugging, and deterministic verification.

A crucial rule for interpreting these numbers:

> **Model score != harness score.**

A model running in Muse Code, Codex, OpenCode, Gemini CLI or Antigravity can behave differently. Never compare two harness-level numbers as if they measure only the base model.

---

## 1. SeatFlow Context

SeatFlow is a Java 21 / Spring Boot 4 + Angular system with explicit workflow files and strong task specifications. The project separates:

`planning -> implementation -> review -> bug fixing -> verification`

That separation matters because the best implementation model does not need to be the same as the best planner or final reviewer.

Critical hidden-failure areas include:

- reservation hold/concurrency behavior
- zero-double-booking guarantees
- PostgreSQL source-of-truth semantics
- Kafka + transactional outbox behavior
- payment/refund/idempotency/Stripe state transitions
- authentication/authorization
- realtime/distributed consistency
- destructive or production-sensitive migrations

The target is **very good code quality with sustainable quota usage**, not maximum model strength on every prompt.

---

## 2. Active Model Set

### Codex Plus

Primary models:

- GPT-5.6 Luna
- GPT-5.6 Terra
- GPT-5.6 Sol

SeatFlow uses Luna for utility work, Terra for planning/review/debugging, and Sol for critical decision/review authority.

### Antigravity Pro

Current relevant models include:

- Gemini 3.8 Flash Medium / High
- Gemini 3.1 Pro
- Claude Sonnet 4.6 Thinking
- Claude Opus 4.6 Thinking

Gemini 3.8 Flash is the preferred Antigravity coding model because it combines frontier-level current capability, very high speed, 1M context and strong agentic/tool evidence.

### OpenCode Go

Current relevant choices include:

- Muse Spark 1.3 Contributor
- Hy4 preview
- GLM-5.3
- GPT-5.6 Luna
- Qwen3.8 variants
- DeepSeek V4 variants
- other catalog models

OpenCode says it tests model/provider combinations before adding them to Go. Availability changes; run `/models` instead of assuming an entry or variant exists.

---

## 3. Current Independent Benchmark Snapshot

### 3.1 Artificial Analysis Intelligence Index

Current live Artificial Analysis results around the models most relevant to SeatFlow:

| Model / effort | Intelligence Index |
|---|---:|
| Muse Spark 1.3 max | 62 |
| **Muse Spark 1.3 xHigh** | **61** |
| GPT-5.6 Sol max | 61 |
| GPT-5.6 Sol xHigh | 59 |
| **Gemini 3.8 Flash High** | **59** |
| GPT-5.6 Sol High | 57 |
| GPT-5.6 Terra max | 57 |
| GPT-5.6 Terra xHigh | 53 |
| GPT-5.6 Terra High | 50 |

This index is broad — it includes coding/terminal work but is not a Java/Spring benchmark and is not a dedicated code-review benchmark.

Operational conclusion:

- Muse 1.3 xHigh is a genuine frontier model, not merely a cheap implementation model.
- It is not evidence-based to assume Terra High is inherently a stronger implementation choice than Muse xHigh.
- Gemini 3.8 High is also frontier-level and very close to Muse on the broad index.
- Sol remains valuable because risk authority depends on more than one aggregate benchmark, especially when direct Java evidence and hidden-failure cost matter.

### 3.2 Artificial Analysis Coding Agent Index v1.4

Live harness-level results relevant to the current workflow:

| Agent + model | Index | DeepSWE | Terminal-Bench 2.1 | SWE-Atlas-QnA |
|---|---:|---:|---:|---:|
| Muse Code + Muse Spark 1.3 max | 68 | 68% | 84% | 52% |
| Codex + GPT-5.6 Sol max | 65 | 69% | 83% | 43% |
| **Muse Code + Muse Spark 1.3 xHigh** | **64** | **67%** | **82%** | **44%** |
| **Codex + GPT-5.6 Sol High** | **64** | **65%** | **82%** | **45%** |
| Codex + GPT-5.6 Sol xHigh | 63 | 67% | 80% | 43% |
| OpenCode + Gemini 3.8 Flash High | 61 | 62% | 84% | 38% |
| Codex + GPT-5.6 Terra max | 60 | 67% | 78% | 36% |

Important caveats:

1. **Muse 1.3 xHigh is currently measured in Muse Code, not OpenCode.** Do not copy its 64 score directly onto OpenCode Go.
2. OpenCode's currently published comparison still includes Muse 1.2 xHigh rather than 1.3 in the harness table. Muse 1.2 xHigh scored 59 in OpenCode, versus 62 in Muse Code, demonstrating that harness differences matter.
3. OpenCode + Gemini 3.8 High already scores 61 in the live v1.4 index, making Gemini a credible implementation peer — not only a frontend styling model.
4. GPT-5.6 vendor launch numbers used an earlier Coding Agent Index version and should not be numerically compared to the current v1.4 values.

Operational conclusion:

- Muse 1.3 xHigh has enough evidence to be SeatFlow's default substantive implementation worker.
- Gemini 3.8 High is the best broad alternative when Muse/provider/quota/privacy is undesirable.
- Sol is not automatically better at implementation just because it is the flagship model.
- Terra's strongest SeatFlow value remains planning/review/debugging and backend judgment rather than bulk deterministic implementation.

---

## 4. Java Evidence: What We Know and What We Do Not

### 4.1 Direct GPT-5.6 Java evaluation

Sonar evaluated GPT-5.6 Sol and Terra on the same **4,444 Java tasks** at medium reasoning:

| Model | Functional pass rate |
|---|---:|
| GPT-5.6 Sol | 81.99% |
| GPT-5.6 Terra | 79.96% |

This is useful direct evidence that both GPT-5.6 tiers are strong Java code generators.

Sonar also found substantial concurrency/threading and security findings in generated output, reinforcing SeatFlow's policy that strong generation still requires targeted verification and review.

### 4.2 Muse Java caveat

Meta's published DeepSWE v1.1 methodology for Muse Spark 1.3 covers TypeScript, Go, Python, JavaScript and Rust — **not Java**.

The correct inference is:

> There is no directly comparable public Java benchmark for Muse 1.3 in this evidence set.

The incorrect inference is:

> Muse is weak at Java, therefore Java implementation should default to Terra.

Absence of Java from one benchmark is an evidence gap, not negative evidence.

SeatFlow therefore uses this rule:

**Java/Spring alone is NOT a reason to route implementation away from Muse.**

For a complete task with explicit contracts and strong tests, Muse xHigh remains the default implementation model. For subtle transaction/concurrency/payment/security semantics, Terra/Sol becomes the decision/review layer.

---

## 5. Muse Spark 1.3 Evaluation

Artificial Analysis reports Muse Spark 1.3 xHigh at **61** on the Intelligence Index, tied with GPT-5.6 Sol max on the broad index, while the limited-preview Muse max reaches 62.

Its gains versus Muse 1.2 are concentrated in agentic evaluations. Artificial Analysis reports notable improvements in Terminal-Bench and tool/agentic work, while Meta's own release also emphasizes long-horizon coding efficiency.

Why this matters for SeatFlow:

- task files are explicit
- architecture is normally decided before implementation
- file inventory is known
- deterministic verification commands exist
- the implementation agent can run edit/test/fix loops
- large tasks span many files and benefit from strong agentic persistence

### Recommended effort

For SeatFlow's typical substantive task, prefer **xHigh** if exposed. Use High for genuinely small/mechanical implementation.

This reverses the older router's conservative bias where High was the normal default and xHigh was reserved only for very large tasks. Given current capability and OpenCode Go economics, xHigh is the better default for meaningful implementation work.

### Contributor privacy

OpenCode currently states:

- Muse Spark 1.3 Contributor may be used for model training
- it is not ZDR

Never send secrets, private customer data, production dumps or sensitive proprietary context through this route.

---

## 6. Gemini 3.8 Flash Evaluation

Artificial Analysis currently reports:

- Gemini 3.8 Flash High: **59 Intelligence Index**
- Gemini 3.8 Flash Medium: **57**
- High output speed around **300 tokens/s** in current measurements
- 1M context

OpenCode + Gemini 3.8 Flash High currently reaches **61** on Coding Agent Index v1.4 with 62% DeepSWE and 84% Terminal-Bench 2.1.

Operational interpretation:

- default frontend/visual/browser worker
- excellent large-context/tool-heavy worker
- credible backend implementation alternative to Muse for well-tested work
- excellent independent model-family alternative for debugging/review
- Medium for simple frontend/quick exploration
- High for substantive work

Gemini should no longer be treated as only a frontend-specialist model. Frontend remains its strongest SeatFlow default because Antigravity's browser/visual workflow compounds its model capability.

---

## 7. Why Terra Remains the Default Planner/Reviewer

The revised router deliberately does **not** claim Terra High is more intelligent or better at implementation than Muse xHigh.

Terra remains valuable because SeatFlow needs a stable senior-engineer layer for:

- turning architecture into atomic deterministic task specs
- transaction/JPA reasoning
- API and migration design
- code review
- difficult root-cause diagnosis
- interpreting weak/flaky verification
- deciding whether an implementation is consistent with architecture and invariants

The direct Java evidence from Sonar supports Terra's backend competence, but the main reason to use Terra here is **role specialization and independent review**, not a blanket Java preference.

For non-critical substantive changes:

`Muse/Gemini implementation -> Terra High review`

is usually stronger operationally than:

`Terra implementation -> Terra review`

because the first pipeline separates execution from judgment and reduces correlated mistakes.

---

## 8. Why Sol Is a Risk Override

Sol's best SeatFlow use is where a plausible error can survive ordinary tests and have expensive consequences:

- race conditions / double booking
- payment/refund state transitions
- idempotency
- authorization/security
- distributed ordering
- data corruption
- dangerous migrations

Use Sol High for architecture/risk analysis and final review of these areas.

The implementation itself can usually still be Muse xHigh when the task has become explicit and testable:

`Sol High risk analysis -> deterministic spec -> Muse xHigh execution -> exhaustive tests -> Sol High review`

This spends premium quota where it changes expected failure risk instead of using Sol as a bulk code writer.

---

## 9. Alternatives Are Not Escalations

The router now requires both concepts.

### Alternative

Use when the recommended model is unavailable, quota-constrained, privacy-incompatible, slow in the current harness, or when model-family diversity is useful.

Examples:

- Muse xHigh implementation -> **Alternative: Gemini 3.8 High**
- Gemini 3.8 High frontend -> **Alternative: Muse xHigh**
- Terra High review -> **Alternative: Gemini 3.8 High**
- Sol High critical review -> **Alternative: Terra xHigh** with lower confidence and a clear trade-off

### Escalation

Use only after a concrete trigger:

- meaningful repair loop failed
- ambiguity remains after inspection
- verification is weak
- risk changed from normal to critical
- P0/P1 finding remains unresolved

Examples:

- Muse xHigh implementation failure -> Terra High diagnosis/re-plan
- Terra High hard debugging unresolved -> Terra xHigh / Sol High
- Sol High critical review unresolved -> Sol xHigh

---

## 10. OpenCode Go Economics

OpenCode Go currently defines:

- **5-hour limit:** $12 usage value
- **weekly limit:** $30
- **monthly limit:** $60

Current OpenCode estimates include:

| Model | Estimated requests / 5h | / week | / month |
|---|---:|---:|---:|
| Muse Spark 1.3 Contributor | 45,300 | 113,300 | 226,600 |
| Hy4 preview | 1,350 | 3,380 | 6,770 |
| GLM-5.3 | 220 | 540 | 1,080 |
| GPT-5.6 Luna | 2,050 | 5,100 | 10,250 |

These request counts reflect OpenCode's typical request assumptions, **not SeatFlow-sized prompt guarantees**. A multi-million-token agentic task can consume much more than an average request.

The useful conclusion is relative: Muse Contributor is exceptionally cheap inside Go, so using xHigh on substantive tasks is economically reasonable. Do not save tiny amounts of Muse allowance by dropping reasoning quality and then spend scarce Terra/Sol quota repairing avoidable failures.

---

## 11. Reasoning-Effort Policy

### Muse Spark 1.3

- **High:** small/mechanical implementation
- **xHigh:** default substantive implementation, cross-file work, full task execution, serious bug fixes
- **Max:** do not assume it exists in OpenCode Go; limited-preview first-party results do not justify inventing an unavailable variant

### Gemini 3.8 Flash

- **Medium:** simple UI, quick exploration, low-risk change
- **High:** substantive frontend/backend implementation, browser/tool loops, complex state, large-context work

### GPT-5.6 Terra

- **Medium:** small review / bounded judgment
- **High:** default planning, review, difficult debugging
- **xHigh:** unusually hard architecture or multi-service diagnosis
- **Max:** not a normal path

### GPT-5.6 Sol

- **High:** critical decision/review authority
- **xHigh:** unresolved/severe critical issue
- **Max:** exceptional final escalation, not routine implementation

### GPT-5.6 Luna

- **Low/Medium:** docs, extraction, utility
- **High:** bounded coding fallback

### Hy4 / GLM

Use provider-supported configurations only. Do not invent effort names.

---

## 12. Recommended SeatFlow Pipelines

### Standard backend task

`Terra High planning -> Muse xHigh implementation -> tests -> Terra High review -> Muse fixes -> final QA`

Alternative implementation: `Gemini 3.8 High`.

### Small backend task

`Muse High -> targeted tests -> optional Terra Medium review`

Alternative: `Gemini 3.8 Medium/High`.

### Large backend task

`Terra High planning -> Muse xHigh implementation -> full verification -> Terra High review -> re-review if needed -> final QA`

Alternative implementation: `Gemini 3.8 High`.

### Frontend feature

`Gemini 3.8 High implementation -> browser/tests -> Terra High review for non-trivial state/contracts -> final QA`

Alternative implementation: `Muse xHigh`.

### Reproducible bug

`Muse High/xHigh regression test + fix -> verification`

Alternative: `Gemini 3.8 High`.

Escalate after one meaningful failed repair: `Terra High root-cause analysis`.

### Hard backend bug

`Terra High diagnosis -> Muse xHigh targeted repair -> review -> QA`

Alternative diagnosis: `Gemini 3.8 High` when tool/repository exploration dominates.

### Critical reservation/payment/security task

`Sol High risk analysis -> deterministic spec -> Muse xHigh implementation -> exhaustive tests -> Sol High final review -> QA`

Alternative implementation: `Terra High` when coding still requires architectural judgment.

### Repo-wide refactor

`Terra High plan -> Muse xHigh execution -> full verification -> Terra High review -> QA`

Alternative execution: `Gemini 3.8 High`.

---

## 13. Models Not in the Default Route

OpenCode Go and Antigravity contain many capable models. Hy4, GLM-5.3, Qwen3.8, DeepSeek V4, Kimi K3, Claude models and others can be useful.

They are intentionally secondary because:

1. too many defaults increase routing friction;
2. Muse 1.3 has unusually strong current frontier/agentic evidence plus extraordinary Go economics;
3. Gemini 3.8 is a strong fast peer and covers visual/browser workflows;
4. Terra/Sol cover the independent backend judgment and critical-risk layers;
5. other models remain available for diversity when a primary route behaves badly.

Do not add a model to the default route because it wins one benchmark.

---

## 14. Sources

Re-check these whenever models, harnesses or subscription limits change:

- Artificial Analysis Muse Spark 1.3 analysis: https://artificialanalysis.ai/articles/muse-spark-1-3
- Artificial Analysis Muse Spark 1.3 xHigh: https://artificialanalysis.ai/models/muse-spark-1-3-xhigh
- Artificial Analysis Muse Code vs OpenCode: https://artificialanalysis.ai/agents/coding-agents/comparisons/muse-code-vs-opencode
- Artificial Analysis Codex comparisons: https://artificialanalysis.ai/agents/coding-agents/comparisons/codex-vs-opencode
- Artificial Analysis Muse xHigh vs Terra High: https://artificialanalysis.ai/models/comparisons/muse-spark-1-3-xhigh-vs-gpt-5-6-terra-high
- Artificial Analysis Muse xHigh vs Sol xHigh: https://artificialanalysis.ai/models/comparisons/muse-spark-1-3-xhigh-vs-gpt-5-6-sol-xhigh
- Artificial Analysis Muse xHigh vs Gemini 3.8 High: https://artificialanalysis.ai/models/comparisons/muse-spark-1-3-xhigh-vs-gemini-3-8-flash
- Sonar GPT-5.6 Java evaluation: https://www.sonarsource.com/blog/openai-gpt-5-6-sol-and-terra/
- OpenAI GPT-5.6 launch/benchmarks: https://openai.com/index/gpt-5-6/
- Meta Muse Spark 1.3 evaluation methodology: https://research.meta.ai/static/muse-spark-1-3-multimodal-evaluation-methodology
- Google Antigravity models: https://antigravity.google/docs/models/
- OpenCode Go models/limits/privacy: https://opencode.ai/docs/go/
- OpenCode model variants: https://opencode.ai/docs/models/

---

## 15. Final Policy

```text
MUSE SPARK 1.3 XHIGH
= default substantive implementation worker, INCLUDING Java/Spring

MUSE SPARK 1.3 HIGH
= small/mechanical implementation worker

GEMINI 3.8 FLASH HIGH
= default frontend/visual/browser worker
= primary alternative implementation model for backend/tool-heavy work

GEMINI 3.8 FLASH MEDIUM
= simple frontend / quick exploration

GPT-5.6 TERRA HIGH
= default planner / reviewer / difficult debugger / backend judgment layer

GPT-5.6 TERRA MEDIUM
= small review / bounded judgment

GPT-5.6 TERRA XHIGH
= unusually hard architecture/root-cause alternative or escalation

GPT-5.6 SOL HIGH
= critical correctness decision/review authority

GPT-5.6 SOL XHIGH
= unresolved severe critical escalation

GPT-5.6 LUNA LOW/MEDIUM
= docs / extraction / low-risk utility

HY4 / GLM-5.3
= secondary independent fallbacks
```

The core SeatFlow strategy is:

**strong specification -> frontier high-throughput implementation -> independent review -> deterministic verification**.

Do not use Terra/Sol implementation merely because a task contains Java. Use them where their independent judgment changes expected failure risk.