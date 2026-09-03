# MODEL_ROUTER.md

## Purpose

Use this file to choose the best AI model and reasoning effort for a SeatFlow software-development task.

Optimize for **code quality and successful task completion per unit of usage, latency, context, retry effort, and failure risk**. Do not select the strongest model by default. SeatFlow deliberately separates planning, implementation, review, bug fixing, and verification so each stage can use the model that best fits that stage.

The execution behavior of each stage is defined in `.ai/workflows/`; model routing does not replace those protocols.

Current preferred subscriptions/providers:

- **Codex Plus:** GPT-5.6 Luna / Terra / Sol
- **Antigravity Pro:** Gemini 3.8 Flash Medium / High; Gemini 3.1 Pro High; Claude Sonnet 4.6 Thinking; Claude Opus 4.6 Thinking
- **OpenCode Go:** Muse Spark 1.3 Contributor, Hy4 preview, GLM-5.3 and other secondary models

If a named effort/variant is unavailable in the current harness, use the nearest supported configuration. OpenCode variants are model-specific; never invent a variant name that is not present in the current catalog.

---

## 1. Classify the Task First

Estimate:

- **Complexity:** 1-5
- **Failure risk:** Low / Medium / High / Critical
- **Context:** Small (<20K), Medium (20-100K), Large (100-300K), Very Large (300K+)
- **Agentic demand:** Low / Medium / High
- **Verification strength:** Strong / Partial / Weak
- **Dominant requirement:** speed, deterministic implementation, frontend/visual quality, architecture, debugging, review, long-context, or critical correctness

Do **not** increase reasoning effort merely because a task is long. Increase it when ambiguity, hidden correctness risk, cross-service reasoning, or expensive failure justifies the extra compute.

### Recommendation vocabulary

- **Recommended** = best default route for the task as currently understood.
- **Alternative** = a peer route that is still a good choice when quota, provider availability, privacy, harness behavior, latency, or model-family diversity makes the recommendation inconvenient. An alternative is **not** necessarily stronger.
- **Escalate to** = a stronger/more expensive route used only after a concrete trigger such as unresolved ambiguity, a failed meaningful repair loop, weak verification, or critical risk.

Always provide an **Alternative** when recommending a model.

---

## 2. Primary SeatFlow Model Roles

### Muse Spark 1.3 Contributor — Default Implementation Worker

Muse Spark 1.3 is the default model for the majority of **substantive, well-specified, test-verifiable implementation work**, including Java/Spring backend work.

**Substantive implementation default:** `xHigh` when exposed by the current OpenCode catalog.  
**Small/mechanical implementation:** `High` when exposed.  
If these names are unavailable, use the strongest practical non-Max variant exposed by the provider for substantive work.

Best for:

- implementing complete SeatFlow task files
- Java 21 / Spring Boot service implementation
- repositories, DTOs, mappers, controllers and service layers
- Flyway migrations from an already-defined schema
- Kafka/outbox implementation from an already-defined contract
- unit, slice, integration and concurrency tests
- Angular/TypeScript implementation when Gemini is unavailable or quota-constrained
- deterministic refactors
- reproducible bug fixes
- Docker / CI / routine observability
- large cross-file implementation
- autonomous repo exploration and edit/test/fix loops

### Java/Spring routing rule

**Java or Spring by itself is NOT a reason to route implementation away from Muse.**

The published Muse Spark 1.3 DeepSWE methodology does not contain Java, so there is no apples-to-apples public Java benchmark versus GPT-5.6. That is an evidence limitation, not evidence that Muse is weak at Java. SeatFlow should therefore use Muse for Java implementation when the architecture and invariants are already specified and verification is reasonably strong.

For subtle transaction semantics, concurrency, payments, security, distributed consistency, or irreversible data changes, use Terra/Sol for the **decision/review layer**. The implementation may still be Muse xHigh when the task is explicit and testable.

**Privacy rule:** Muse Spark 1.3 Contributor may use prompts/completions for model training and is not ZDR. Never send secrets, credentials, private production data, customer data, or proprietary/sensitive material through the Contributor route. Public SeatFlow source code is acceptable; secrets are not.

---

### Gemini 3.8 Flash High — Frontend Default / Fast Frontier Alternative

Gemini 3.8 Flash High is the default for frontend, browser-heavy and visual work, and is the strongest general alternative to Muse for implementation when speed, Antigravity tooling, privacy/provider constraints, or model-family diversity matter.

**Serious work:** `Gemini 3.8 Flash High`  
**Simple/low-risk work:** `Gemini 3.8 Flash Medium`

Best for:

- Angular / TypeScript / Tailwind implementation
- screenshot/mock-driven UI work
- responsive layout and visual iteration
- browser-heavy debugging
- frontend state/UI exploration
- autonomous tool loops
- large-context repo exploration
- fast independent implementation attempts
- backend implementation when Muse is unavailable or undesirable and the task is strongly test-verifiable

Use High for substantive work. Use Medium for simple components, styling tweaks, extraction, quick exploration, or bounded low-risk changes.

Gemini is also a valuable independent second model for review/debugging because correlated mistakes are less likely when the implementer used Muse or GPT-5.6.

---

### GPT-5.6 Terra — Default Planner / Reviewer / Difficult Debugger

Terra is the normal SeatFlow authority for **planning, substantive review, difficult root-cause debugging, architecture judgment, and subtle backend reasoning**. It is not the default implementation model merely because the code is Java.

**Small review / bounded judgment:** `Terra Medium`  
**Default planning / review / difficult debugging:** `Terra High`  
**Unusually difficult multi-service diagnosis / ADR:** `Terra xHigh`

Best for:

- feature/task planning
- ADRs
- backend architecture
- Spring/JPA transaction reasoning
- Kafka/outbox reasoning
- API design
- difficult debugging
- code review
- migration design/review
- cross-service root-cause analysis
- interpreting flaky or incomplete verification

For the project's “very good code quality” target, `Terra High` is the normal final reviewer for non-critical substantive backend changes. Use `Terra Medium` only for small/mechanical reviews.

Do not route a complete deterministic Java task to Terra High for implementation when Muse xHigh can execute it and Terra can be preserved for the higher-value planning/review layer.

---

### GPT-5.6 Sol — Critical Correctness Authority

Sol is **not** the normal implementation default. Use it when hidden mistakes can materially affect money, security, data integrity, reservation correctness, or production safety.

**Critical default:** `Sol High`  
**Severe / unresolved critical issue:** `Sol xHigh`  
**Final exceptional escalation:** `Sol Max`

Use for:

- zero-double-booking and reservation locking
- 15-minute hold semantics under concurrency
- Stripe / payment / refund / idempotency state machines
- authentication / authorization / security-sensitive changes
- distributed consistency / ordering / race conditions
- dangerous migrations with data-integrity risk
- critical production incidents
- final review of the above areas

Preferred critical workflow:

`Sol High risk/architecture analysis -> explicit task spec -> Muse 1.3 xHigh implementation -> exhaustive tests -> Sol High final review`

Use Terra High as the implementation alternative when the task still requires substantial design judgment while coding.

---

### GPT-5.6 Luna — Utility / Small Low-Risk Worker

Use for:

- documentation
- extraction/status
- boilerplate
- tiny deterministic fixes
- quick low-risk checks

**Default:** `Luna Low/Medium`  
**Bounded coding fallback:** `Luna High`

Do not use Luna Max as a routine path. For hard reasoning, move to Terra High rather than trying to compensate with extreme effort.

---

## 3. Secondary / Diversity Models

### Hy4 preview

Use as an OpenCode Go fallback when Muse behaves poorly on a long-horizon/tool-heavy task or when an independent open-model-family attempt is useful before spending premium quota.

Good fit:

- terminal-heavy implementation
- large-context debugging
- independent verification/repair attempt

Known preview behavior includes overthinking/over-verification, so Hy4 is not the final authority for critical SeatFlow invariants.

### GLM-5.3

Credible terminal-heavy fallback. It is materially more expensive in OpenCode Go allowance than Muse and does not currently justify replacing Muse as the default implementation worker.

### Gemini 3.1 Pro / Claude Sonnet 4.6 Thinking / Claude Opus 4.6 Thinking

Use mainly for independent second opinions when model-family diversity is valuable. Do not add extra passes merely for ceremony.

---

## 4. SeatFlow Routing Table

| SeatFlow task | Recommended | Alternative | Review / escalation |
|---|---|---|---|
| Documentation / extraction / status | Luna Low/Medium | Gemini 3.8 Medium | Terra High only if reasoning becomes non-trivial |
| Create normal atomic task `.md` | Terra High | Gemini 3.8 High | Sol High if critical invariant/ADR |
| Architecture / ADR | Terra High | Gemini 3.8 High | Terra xHigh; Sol High if critical |
| Tiny mechanical backend change | Muse 1.3 High | Gemini 3.8 Medium/High | Terra Medium if review needed |
| Normal substantive backend implementation | Muse 1.3 xHigh | Gemini 3.8 High | Terra High after failed repair/ambiguity; Terra High review |
| CRUD / DTO / mapper / repository / controller task | Muse 1.3 xHigh | Gemini 3.8 High | Terra High review if substantive |
| Unit/integration/concurrency test implementation | Muse 1.3 xHigh | Gemini 3.8 High | Terra High for weak oracle/test strategy |
| Large cross-file backend implementation | Muse 1.3 xHigh | Gemini 3.8 High | Terra High review; Sol High if critical risk |
| Complex Spring/JPA implementation from complete plan | Muse 1.3 xHigh | Terra High | Terra High review; Sol High for money/data-integrity/concurrency |
| Kafka/outbox implementation from complete contract | Muse 1.3 xHigh | Terra High | Terra High review; Sol High if delivery/ordering invariant changes |
| Autonomous repo-wide implementation | Muse 1.3 xHigh | Gemini 3.8 High | Terra High review |
| Angular / TypeScript / Tailwind | Gemini 3.8 High | Muse 1.3 xHigh | Terra High for non-trivial architecture/state/contracts |
| Simple frontend component/styling | Gemini 3.8 Medium | Muse 1.3 High | Gemini High if complexity grows |
| Screenshot / visual UI implementation | Gemini 3.8 High | Muse 1.3 xHigh | Terra High only for contract/state risk |
| Large-context repo exploration | Muse 1.3 xHigh | Gemini 3.8 High | Terra High when subtle backend conclusions matter |
| Clear localized reproducible bug | Muse 1.3 High/xHigh | Gemini 3.8 High | Terra High after one meaningful failed repair |
| Normal difficult root-cause debugging | Terra High | Gemini 3.8 High | Terra xHigh; Sol High if critical risk appears |
| Tool-heavy/browser-heavy debugging | Gemini 3.8 High | Muse 1.3 xHigh | Terra High for ambiguous root cause |
| Cross-service root-cause debugging | Terra High/xHigh | Gemini 3.8 High | Sol High if critical invariant involved |
| Intermittent concurrency/distributed bug | Sol High | Terra xHigh | Sol xHigh if unresolved |
| Routine small PR review | Terra Medium | Gemini 3.8 High | Terra High if meaningful findings appear |
| Substantive PR review | Terra High | Gemini 3.8 High | Sol High if critical domain touched |
| Security/payment/concurrency final review | Sol High | Terra xHigh | Sol xHigh if unresolved ambiguity remains |
| Mechanical refactor | Muse 1.3 High | Gemini 3.8 Medium/High | Terra Medium if behavior risk appears |
| Large repo-wide refactor | Muse 1.3 xHigh | Gemini 3.8 High | Terra High review |
| Routine DB migration implementation from approved schema | Muse 1.3 High/xHigh | Terra High | Terra High review |
| Dangerous production/data migration | Sol High for design/review + Muse xHigh execution | Terra xHigh for design/review | Sol xHigh if unresolved |
| CI / Docker / Grafana / Loki / Prometheus | Muse 1.3 xHigh | Gemini 3.8 High | Terra High for hard root cause |
| Hard production incident | Terra High | Gemini 3.8 High | Sol High/xHigh if money/security/data integrity affected |

---

## 5. SeatFlow Risk Overrides

Do not let the implementation worker become the final decision authority for:

- zero double-booking
- 15-minute reservation holds under concurrency
- PostgreSQL source-of-truth semantics
- transactional outbox / Kafka delivery guarantees
- reservation/payment/refund idempotency
- Stripe webhook/payment state transitions
- authentication/authorization/security
- distributed consistency and race conditions
- irreversible/destructive migrations

Rules:

1. **Java/Spring alone is not a risk override.**
2. `Terra High` is the normal minimum decision/review layer for subtle backend invariants.
3. Use `Sol High` when failure can cause money loss, security exposure, corrupted state, double booking, or difficult-to-repair production damage.
4. Use `Sol xHigh` only when High leaves unresolved ambiguity or the issue is genuinely severe.
5. Sol is a **risk override**, not a blanket implementation setting.

---

## 6. Verification-First Rule

When compiler + tests + lint + integration/browser checks are strong:

- favor Muse 1.3 xHigh for substantive implementation;
- favor Gemini 3.8 High for frontend/visual/browser-heavy implementation;
- preserve Terra/Sol for planning, review and ambiguous/critical reasoning.

When correctness is weakly observable:

- move the decision/review layer to Terra High;
- use Sol High for critical hidden failure modes.

For every non-trivial implementation:

1. run the task's deterministic verification command;
2. run relevant regression/integration checks;
3. perform independent review using `.ai/workflows/05-code-review.md`;
4. use `.ai/tmp/review-<task-or-branch>.md` for actionable findings;
5. resolve findings through `.ai/workflows/03-bug-fixing.md`;
6. re-review P0/P1 and other substantive/high-risk fixes;
7. delete the temporary ledger only after accepted findings are resolved and verification/re-review completes;
8. pass `.ai/workflows/04-testing-and-qa.md` before task completion.

Passing tests are evidence, not proof, for concurrency, payments, security, migrations, and distributed semantics.

---

## 7. Standard Quality Workflows

### Normal backend task

`Terra High task spec -> Muse 1.3 xHigh implementation -> tests -> Terra High review -> Muse xHigh fixes if needed -> final QA`

**Alternative implementation:** `Gemini 3.8 High`.

### Small mechanical backend task

`Muse 1.3 High -> targeted tests -> Terra Medium review only if substantive`

**Alternative:** `Gemini 3.8 Medium/High`.

### Large backend / cross-file task

`Terra High task spec -> Muse 1.3 xHigh implementation -> full verification -> Terra High review -> fixes/re-review -> final QA`

**Alternative implementation:** `Gemini 3.8 High`.

### Frontend feature

`Terra High or Gemini High plan -> Gemini 3.8 High implementation -> browser/tests -> Terra High review when state/contracts are non-trivial -> final QA`

**Alternative implementation:** `Muse 1.3 xHigh`.

### Reproducible bug

`Muse 1.3 High/xHigh reproduce + regression test + fix -> tests -> review when substantive/high-risk -> final QA`

**Alternative:** `Gemini 3.8 High`.

If one meaningful repair loop fails: `Terra High root-cause analysis -> targeted repair`.

### Hard backend bug

`Terra High root cause -> Muse xHigh targeted fix -> review -> final QA`

**Alternative diagnosis:** `Gemini 3.8 High` when tool/repo exploration dominates.

### Critical reservation/payment/security task

`Sol High risk analysis -> explicit task spec -> Muse 1.3 xHigh implementation -> exhaustive tests -> Sol High final review -> fixes/re-review -> final QA`

**Alternative implementation:** `Terra High` when implementation still requires design judgment.

### Repo-wide refactor

`Terra High plan -> Muse 1.3 xHigh execution using 06-refactoring -> full verification -> Terra High review -> final QA`

**Alternative execution:** `Gemini 3.8 High`.

---

## 8. Review Execution Rule

When assigned code review, follow `.ai/workflows/05-code-review.md` rather than giving a generic prose review.

The reviewer must prioritize concrete bugs, invariant violations, security/data-integrity risks, contract mismatches, test gaps, and meaningful improvements. Findings require plausible evidence/failure scenarios and P0-P3 severity.

Prefer reviewer diversity when practical:

- Muse implementation -> Terra or Gemini review
- Gemini implementation -> Terra review
- Terra implementation -> Gemini or Sol review depending risk
- critical implementation -> Sol final review

---

## 9. Alternative Policy

The alternative should be **usable immediately**, not just a weaker emergency fallback.

Choose the alternative when:

- recommended model quota is constrained;
- provider/harness is unavailable or behaving poorly;
- Contributor privacy rules prohibit Muse;
- latency/tooling makes another harness materially better;
- a second model family is desirable to reduce correlated mistakes.

State the trade-off in one sentence. Example:

`Alternative: Gemini 3.8 Flash High — slightly less preferred for this backend task, but excellent agentic/tool performance and faster execution.`

Do not label a model as an alternative if it would materially violate the task's risk requirements.

---

## 10. Escalation Policy

### Bounded/substantive implementation

`Muse 1.3 xHigh -> one meaningful repair loop -> Terra High diagnosis/re-plan`

Do not escalate merely because the task is Java.

### Frontend/tool-heavy implementation

`Gemini 3.8 High -> one meaningful repair loop -> Terra High root-cause/architecture analysis`

### Serious backend reasoning

`Terra High -> Terra xHigh -> Sol High -> Sol xHigh -> Sol Max`

### Critical correctness

Start at `Sol High` for the decision/review layer. Do not waste time retrying weaker decision models when expected failure cost is high.

---

## 11. Usage / Privacy Principle

Use:

`Effective Cost = usage + retries + latency + developer review time + context churn + expected failure cost`

OpenCode Go makes Muse Spark 1.3 Contributor exceptionally inexpensive for high-volume implementation. This is a reason to use more useful reasoning effort on substantive implementation, not a reason to lower quality.

Never paste `.env`, API keys, Stripe secrets, production credentials, private user data, or other secrets into any model prompt.

---

## 12. Response Format

When asked which model to use, respond:

**Recommended:** MODEL — EFFORT

**Why:** 2-4 sentences tied to the task's real failure mode, agentic demand and verification strength.

**Alternative:** MODEL — EFFORT  
**Trade-off:** one concise sentence explaining why it is a good peer option and what is lost/gained.

**Task profile:**
- Complexity: X/5
- Risk: Low/Medium/High/Critical
- Context: Small/Medium/Large/Very Large
- Agentic demand: Low/Medium/High
- Verification: Strong/Partial/Weak

**Implementation model:** only if different from the recommended reasoning/review model.

**Review model:** only if the task needs a separate quality gate.

**Escalate to:** stronger model only under a concrete trigger condition.

Never use “Java/Spring” alone as justification to recommend Terra/Sol implementation over Muse.

---

## 13. Reference Rule

Consult `.ai/AI_MODEL_REFERENCE.md` when:

- the choice is ambiguous or disputed;
- the task is expensive or high-risk;
- a model or benchmark changed recently;
- context is very large;
- the user asks for benchmark/usage justification.

For ordinary routing, do not load the reference file unnecessarily.