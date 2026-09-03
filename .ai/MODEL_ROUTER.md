# MODEL_ROUTER.md

## Purpose

Use this file to choose the best AI model and reasoning effort for a SeatFlow software-development task.

Optimize for **code quality and successful task completion per unit of usage, latency, context, retry effort, and failure risk**. Do not select the strongest model by default. SeatFlow's workflow deliberately separates planning, implementation, review, bug fixing, and verification, so each stage should use the cheapest model that still clears the required reliability threshold.

The execution behavior of each stage is defined in `.ai/workflows/`; model routing does not replace those protocols.

Current preferred subscriptions/providers:

- **Codex Plus:** GPT-5.6 Luna / Terra / Sol
- **Antigravity Pro:** Gemini 3.8 Flash Medium / High; Gemini 3.1 Pro High; Claude Sonnet 4.6 Thinking; Claude Opus 4.6 Thinking
- **OpenCode Go:** Muse Spark 1.3 Contributor, Hy4 preview, GLM-5.3 and other secondary models

If a named effort/variant is unavailable in the current harness, use the nearest supported configuration. OpenCode variants are model-specific; do not assume every model exposes every effort name.

---

## 1. Classify the Task First

Estimate:

- **Complexity:** 1-5
- **Failure risk:** Low / Medium / High / Critical
- **Context:** Small (<20K), Medium (20-100K), Large (100-300K), Very Large (300K+)
- **Agentic demand:** Low / Medium / High
- **Verification strength:** Strong / Partial / Weak
- **Dominant requirement:** speed, deterministic implementation, backend reasoning, frontend/visual quality, long-context, debugging, or critical correctness

Do **not** increase reasoning effort merely because a task is long. Increase it when ambiguity, hidden correctness risk, cross-service reasoning, or expensive failure justifies the extra compute.

---

## 2. Primary SeatFlow Model Roles

### Muse Spark 1.3 Contributor — Default Implementation Worker

Use Muse Spark 1.3 for the majority of **well-specified, test-verifiable implementation work**.

**Default effort:** `High` when exposed by the current OpenCode catalog.  
**Large/agentic effort:** `xHigh` when exposed.  
If these names are not available, use the highest non-Max built-in reasoning variant appropriate for the task.

Best for:

- implementing a complete SeatFlow task file
- CRUD / DTO / mapper / repository / controller work
- unit and integration tests
- routine Flyway migrations
- deterministic backend features
- mechanical refactors
- reproducible bug fixes
- Docker / CI / routine observability
- larger cross-file implementation
- repo exploration and edit/test/fix loops

Use **High** for normal bounded tasks. Use **xHigh** only when the task is large, cross-file, long-context, or strongly agentic.

Important limitation: the published Muse Spark 1.3 DeepSWE evaluation does **not include Java**. Treat the excellent coding benchmark evidence as strong agentic evidence, not direct proof of Spring/JPA correctness. For subtle Java transaction, concurrency, payment, or distributed-state reasoning, use Terra/Sol as the decision authority.

**Privacy rule:** Muse Spark 1.3 Contributor is discounted because prompts/completions may be used to train future Meta models. Never send secrets, credentials, private production data, or proprietary material to the Contributor route. SeatFlow public source code is acceptable; secrets are not.

---

### Gemini 3.8 Flash High — Frontend / Visual / Tool-Heavy Worker

Use especially for:

- Angular / TypeScript / Tailwind implementation
- screenshot/mock-driven UI work
- responsive layout and visual iteration
- browser-heavy debugging
- autonomous tool loops
- frontend state/UI exploration
- large-context work where speed matters

**Default serious frontend:** `Gemini 3.8 Flash High`  
**Simple/low-risk frontend:** `Gemini 3.8 Flash Medium`

Prefer High for a feature or bug where visual quality, browser behavior, or multi-step reasoning matters. Prefer Medium for simple components, styling tweaks, extraction, or quick exploration.

Use Terra instead when the dominant problem is backend architecture, transactional semantics, or cross-service correctness.

---

### GPT-5.6 Terra — Default Senior Engineer / Quality Gate

Terra is the default authoritative model for **planning, review, difficult debugging, and subtle backend reasoning**.

**Default implementation with design judgment:** `Terra Medium`  
**Default planning / review / difficult debugging:** `Terra High`  
**Unusually difficult multi-service diagnosis / ADR:** `Terra xHigh`

Best for:

- feature/task planning
- ADRs
- backend architecture
- Spring/JPA/transaction reasoning
- Kafka/outbox reasoning
- API design
- difficult debugging
- code review
- complex refactors
- migrations
- cross-service root-cause analysis
- interpreting flaky or incomplete verification

For the project's “very good code quality” target, **Terra High is the normal final review model for non-critical substantive changes**. Use Terra Medium only for small, mechanical reviews.

---

### GPT-5.6 Sol — Critical Correctness / Principal Engineer

Sol is **not** the default SeatFlow model. Use it only when hidden mistakes can materially affect money, security, data integrity, or production correctness.

**Critical default:** `Sol High`  
**Severe / unresolved critical issue:** `Sol xHigh`  
**Final escalation only:** `Sol Max`

Use for:

- zero-double-booking and reservation locking
- 15-minute hold semantics when race conditions are involved
- payment / Stripe / refund / idempotency state machines
- authentication / authorization / security-sensitive changes
- distributed consistency / ordering / race conditions
- dangerous migrations with data-integrity risk
- critical production incidents
- final review of the above areas

Preferred critical workflow:

`Sol High plan/reasoning -> Terra High implementation or tightly specified Muse implementation -> Sol High final review`

This keeps Sol focused on the stages where its extra capability changes risk, rather than spending Sol usage on routine coding.

---

### GPT-5.6 Luna — Cheap Utility / Small-Task Worker

Use for:

- documentation
- status/extraction
- boilerplate
- small deterministic fixes
- quick low-risk checks

**Default:** `Luna Low/Medium`  
**Bounded coding/debug fallback:** `Luna High`

Do not use Luna Max as a normal path. For hard reasoning, move to Terra High instead of compensating for a weaker base model with extreme effort.

---

## 3. Secondary / Diversity Models

These are useful, but are not part of the default path.

### Hy4 preview

Use as an **OpenCode Go independent fallback** when Muse repeatedly fails on a tool-heavy implementation or when a second model family is useful before spending premium quota.

Best fit:

- long-horizon implementation
- terminal/tool workflows
- large-context debugging
- independent verification of a Muse patch

Known limitation: Hy4 is a preview model and its vendor explicitly notes overthinking / over-verification. Do not make it the final authority for critical SeatFlow invariants.

### GLM-5.3

Optional fallback for terminal-heavy / agentic work when Muse or Hy4 behaves poorly. It has strong published Terminal-Bench results, but is much more expensive in OpenCode Go allowance than Muse Spark 1.3, so it should not be the normal implementation worker.

### Gemini 3.1 Pro High / Claude Sonnet 4.6 Thinking / Claude Opus 4.6 Thinking

Use for **independent second opinions**, not as routine defaults.

Good cases:

- a difficult frontend/state design where Gemini 3.8 and Terra disagree
- an architecture decision where model-family diversity is valuable
- a critical review where you want a second independent perspective after Sol/Terra

Do not add an extra model pass merely for ceremony. Use diversity when disagreement could reveal a hidden bug or architecture flaw.

---

## 4. SeatFlow Routing Table

| SeatFlow task | Recommended | Effort | Review / escalation |
|---|---|---|---|
| Documentation / extraction / status | Luna | Low/Medium | — |
| Create normal atomic task `.md` | Terra | High | Sol High only if critical invariant/ADR |
| Architecture / ADR | Terra | High | Terra xHigh; Sol High if critical |
| DTO / mapper / repository / controller | Muse 1.3 | High | Terra High if substantive review needed |
| Unit/integration test generation | Muse 1.3 | High | Terra High for test strategy/weak oracle |
| CRUD / bounded backend task from complete spec | Muse 1.3 | High | Terra High review |
| Larger cross-file backend implementation | Muse 1.3 | xHigh | Terra High review |
| Normal backend feature with design decisions | Terra | Medium/High | Terra High review |
| Complex Spring/JPA transaction feature | Terra | High | Sol High if financial/data-integrity impact |
| Kafka/outbox change | Terra | High | Sol High if delivery/ordering invariant changes |
| Autonomous repo-wide implementation | Muse 1.3 | xHigh | Terra High review |
| Angular / TypeScript / Tailwind | Gemini 3.8 Flash | High | Terra High for architecture/state issues |
| Simple frontend component/styling | Gemini 3.8 Flash | Medium | Gemini High if needed |
| Screenshot / visual UI implementation | Gemini 3.8 Flash | High | — |
| Large-context repo exploration | Muse 1.3 | xHigh | Gemini High for visual/frontend; Terra High for subtle backend |
| Clear localized reproducible bug | Muse 1.3 | High | Terra High after one meaningful failed repair |
| Normal difficult debugging | Terra | High | Terra xHigh |
| Cross-service root-cause debugging | Terra | High/xHigh | Sol High if critical invariant involved |
| Intermittent concurrency/distributed bug | Sol | High | Sol xHigh |
| Routine small PR review | Terra | Medium | Terra High if issues found |
| Substantive PR review | Terra | High | — |
| Security/payment/concurrency final review | Sol | High | Sol xHigh |
| Mechanical refactor | Muse 1.3 | High | — |
| Large repo-wide refactor | Muse 1.3 | xHigh | Terra High review |
| Routine DB migration | Terra | High | — |
| Dangerous production/data migration | Sol | High | Sol xHigh |
| CI / Docker / Grafana / Loki / Prometheus | Muse 1.3 | High | Terra High for hard root cause |
| Hard production incident | Terra | High | Sol High/xHigh if money/security/data integrity affected |

---

## 5. SeatFlow Risk Overrides

Do not let a cheap implementation model become the final decision layer for:

- zero double-booking
- 15-minute reservation holds under concurrency
- PostgreSQL source-of-truth semantics
- transactional outbox / Kafka delivery guarantees
- reservation/payment/refund idempotency
- Stripe webhook/payment state transitions
- authentication/authorization/security
- distributed consistency and race conditions
- irreversible or destructive migrations

Rules:

1. **Terra High is the minimum reasoning authority** for subtle backend invariants.
2. Use **Sol High** when a failure can cause money loss, security exposure, corrupted state, double booking, or difficult-to-repair production damage.
3. Use **Sol xHigh** only when High leaves unresolved ambiguity or the bug is genuinely severe.
4. Sol is a **risk override**, not a blanket quality setting.

---

## 6. Verification-First Rule

SeatFlow task files already specify deterministic verification. Exploit that.

When compiler + tests + lint + integration/browser checks are strong:

- favor Muse 1.3 or Gemini 3.8 for implementation
- use Terra as the review/decision layer

When correctness is weakly observable:

- move the decision layer to Terra High
- use Sol High for critical hidden failure modes

For every non-trivial implementation:

1. run the task's deterministic verification command;
2. run relevant regression/integration checks;
3. perform the independent review using `.ai/workflows/05-code-review.md` — review the diff, not only the green test result;
4. when actionable findings exist, use the temporary `.ai/tmp/review-<task-or-branch>.md` ledger defined by the review workflow and resolve issues through `.ai/workflows/03-bug-fixing.md`;
5. re-review P0/P1 and other substantive/high-risk fixes;
6. delete the temporary ledger only after all accepted findings are resolved and required verification/re-review completes;
7. pass `.ai/workflows/04-testing-and-qa.md` as the final quality gate before task completion.

Use a stronger reviewer when the implementation model made architecture decisions outside the task spec or when verification has weak observability.

Passing tests are evidence, not proof, for concurrency, payments, security, migrations, and distributed semantics.

---

## 7. Standard Quality Workflows

The arrows below describe model routing. The actual execution rules come from `.ai/workflows/01-task-planning.md` through `.ai/workflows/06-refactoring.md`.

### Normal bounded backend task

`Terra High task spec -> Muse 1.3 High implementation -> tests -> Terra High review -> fixes if needed -> final QA`

For a tiny/mechanical task, Terra review may be Medium.

### Large backend / cross-file task

`Terra High task spec -> Muse 1.3 xHigh implementation -> tests -> Terra High review -> fixes/re-review -> final QA`

If Muse drifts after one meaningful repair loop:

`Hy4 or GLM-5.3 independent attempt -> Terra High review`

### Frontend feature

`Terra High or Gemini High plan -> Gemini 3.8 High implementation -> browser/tests -> Terra High review if state/contracts are non-trivial -> final QA`

Use Gemini Medium for simple UI work.

### Reproducible bug

`Muse 1.3 High reproduce + regression test + fix -> tests -> review when substantive/high-risk -> final QA`

If one meaningful repair loop fails:

`Terra High root-cause analysis -> targeted fix`

### Hard backend bug

`Terra High -> Terra xHigh if needed -> Sol High only when critical risk appears -> targeted fix -> review -> final QA`

### Critical reservation/payment/security task

`Sol High architecture/risk analysis -> Terra High implementation (or Muse only from a fully deterministic spec) -> exhaustive tests -> Sol High final review -> fixes/re-review -> final QA`

Use Sol xHigh only if the final review finds unresolved risk.

### Repo-wide refactor

`Terra High plan -> Muse 1.3 xHigh execution using 06-refactoring -> full verification -> Terra High review -> final QA`

---

## 8. Review Execution Rule

When a model is assigned a code-review stage, it must follow `.ai/workflows/05-code-review.md` rather than giving a generic prose review.

The reviewer must prioritize concrete bugs, errors, invariant violations, security/data-integrity risks, contract mismatches, test gaps, and meaningful improvements. Findings must include a plausible failure scenario/evidence and use P0-P3 severity.

If findings exist, the review workflow creates a temporary ignored ledger under `.ai/tmp/`. That ledger is repair state, **not documentation**: never commit it, never delete it before accepted findings are resolved, and never let a task pass final QA while it remains active.

---

## 9. Escalation Policy

### Bounded implementation

`Muse 1.3 High -> one repair loop -> Terra Medium/High`

### Large / cross-file / agentic implementation

`Muse 1.3 xHigh -> one repair loop -> Hy4 or GLM-5.3 -> Terra High`

### Frontend

`Gemini 3.8 Medium/High -> Terra High for architecture/state/root-cause issues`

### Serious backend reasoning

`Terra High -> Terra xHigh -> Sol High -> Sol xHigh -> Sol Max`

### Critical correctness

Start at `Sol High` for the decision/review layer. Do not waste time retrying weaker models when the expected failure cost is high.

---

## 10. Usage / Privacy Principle

Never optimize only for nominal token price or subscription allowance.

Use:

`Effective Cost = usage + retries + latency + developer review time + context churn + expected failure cost`

OpenCode Go makes Muse Spark 1.3 Contributor exceptionally cheap for high-volume implementation, but this does not remove:

- developer-time cost from bad loops
- the need for deterministic verification
- the need for Terra/Sol on hidden correctness
- the Contributor privacy/training trade-off

Never paste `.env`, API keys, Stripe secrets, production credentials, private user data, or other secrets into any model prompt.

---

## 11. Response Format

When asked which model to use, respond:

**Recommended:** MODEL — EFFORT

**Why:** 2-4 sentences tied to the task's actual failure mode and verification strength.

**Task profile:**
- Complexity: X/5
- Risk: Low/Medium/High/Critical
- Context: Small/Medium/Large/Very Large
- Agentic demand: Low/Medium/High
- Verification: Strong/Partial/Weak

**Implementation model:** only if different from the reasoning/review model.

**Review model:** only if the task needs a separate quality gate.

**Escalate to:** a stronger model only under a concrete condition.

---

## 12. Reference Rule

Consult `.ai/AI_MODEL_REFERENCE.md` when:

- the choice is ambiguous
- the task is expensive or high-risk
- a model has recently changed
- context is very large
- the user asks for benchmark/usage justification

For ordinary routing, do not load the reference file unnecessarily.
