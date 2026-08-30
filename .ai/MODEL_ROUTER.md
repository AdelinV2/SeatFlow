# MODEL_ROUTER.md

## Purpose

Use this file to choose the best AI model and reasoning effort for a software-development task.

Optimize for **successful task completion per unit of cost, latency, and context**, not for maximum model strength.

Available choices:
- GPT-5.6 Luna
- GPT-5.6 Terra
- GPT-5.6 Sol
- Gemini 3.7 Flash
- GPT reasoning: Low / Medium / High / xhigh / Max
- Gemini reasoning: Low / Medium / High

If a model/effort is unavailable in the current tool, choose the closest available configuration.

## 1. Classify the Task

Before recommending a model, estimate:

- **Complexity:** 1-5
- **Failure risk:** Low / Medium / High
- **Context:** Small (<20K), Medium (20-100K), Large (100-300K), Very Large (300K+)
- **Agentic demand:** Low / Medium / High
- **Verification:** Strong (compiler/tests/lint/browser) or Weak
- **Dominant requirement:** speed, implementation, reasoning, frontend, long-context, or reliability

Do not increase reasoning effort merely because a task is long. Increase it when the task is genuinely difficult, ambiguous, or costly to get wrong.

## 2. Model Roles

### Luna — Cheap Worker
Use for deterministic, bounded, easily verified work:
- boilerplate
- DTOs/mappers
- CRUD
- test generation
- documentation
- mechanical refactors
- simple fixes
- CI/log triage

Default: **Luna Medium**  
Trivial work: **Luna Low**  
Bounded but harder debugging/review: **Luna High**

Do not use Luna Max as a substitute for a stronger base model when architecture, hard debugging, or very-large-context reasoning is the bottleneck.

### Gemini 3.7 Flash — Agentic / Frontend / Large-Context Value
Use especially for:
- autonomous edit/test/fix loops
- repository exploration
- frontend and UI implementation
- Angular/Tailwind/web work
- screenshot/mock-driven implementation
- tool-heavy tasks
- broad multi-file changes
- large-context analysis where cost matters

Default serious mode: **Gemini 3.7 Flash High**  
Use Medium for ordinary bounded implementation and Low for simple work.

Gemini High can consume many reasoning tokens. This is acceptable when its low token price plus strong agentic performance produces a lower cost per successful task.

### Terra — Default Senior Engineer
Use for serious software engineering:
- feature planning
- backend implementation
- debugging
- code review
- refactoring
- Spring Boot/JPA/transactions
- migrations
- API design
- repository reasoning
- architecture

Default implementation: **Terra Medium**  
Default planning/debugging/review: **Terra High**  
Use xhigh only for unusually difficult reasoning or high-risk work.

### Sol — Critical Escalation / Principal Engineer
Use when failure is expensive or the problem is intrinsically difficult:
- production incidents
- race conditions
- distributed systems
- Kafka consistency/ordering
- security
- auth/authz
- payments
- data integrity
- dangerous migrations
- critical architecture
- unresolved hard debugging

Default difficult mode: **Sol High**  
Critical/high-risk mode: **Sol xhigh**  
Use **Sol Max only as final escalation**, not as a normal development preset.

## 3. Routing Table

| Task | Recommended |
|---|---|
| Tiny syntax/mechanical edit | Luna Low |
| Boilerplate / DTO / mapper | Luna Low |
| Unit tests | Luna Medium |
| CRUD / simple bounded feature | Luna Medium |
| Small backend feature | Terra Medium |
| Normal backend feature | Terra Medium |
| Complex Spring feature | Terra High |
| Autonomous feature implementation | Gemini High |
| Frontend / Angular / Tailwind | Gemini High |
| UI from screenshot/mock | Gemini High |
| Repository exploration | Gemini High |
| Very large context exploration | Gemini High |
| Feature planning | Terra High |
| Architecture planning | Terra High |
| Critical architecture | Sol High |
| Clear localized bug | Luna High |
| Normal difficult debugging | Terra High |
| Tool-heavy exploratory debugging | Gemini High |
| Intermittent/concurrency/distributed bug | Sol High/xhigh |
| Normal PR review | Terra High |
| Bulk first-pass review | Luna Medium |
| Mechanical refactor | Luna Medium |
| Complex refactor | Terra High |
| Large autonomous refactor | Gemini High |
| Security/auth/payment review | Sol xhigh |
| DB migration | Terra High/xhigh |
| Dangerous production migration | Sol xhigh |
| CI failure triage | Luna Medium |
| Production incident | Sol High/xhigh |

## 4. Key Comparison Rules

Prefer **Gemini High over Terra High** when the task is strongly agentic, frontend-heavy, tool-heavy, large-context, automatically verifiable, and cost-sensitive.

Prefer **Terra High over Gemini High** when precise backend reasoning, invariants, transactions, architecture, root-cause analysis, or weakly-verifiable correctness dominate.

Prefer **Terra High over Luna Max** for difficult debugging, architecture, broad abstraction-level reasoning, or very-large-context reasoning. More reasoning does not guarantee that a smaller base model catches a stronger one.

For genuinely hard tasks, consider **Sol Medium/High before blindly pushing Terra or Luna to Max**.

Use **Max** only when lower efforts are insufficient or the expected cost of failure clearly outweighs additional latency/compute.

## 5. Verification Rule

Strong automatic verification makes cheaper models more attractive.

If compiler + tests + lint + integration/browser checks can reliably reject bad work:
- favor Luna or Gemini.

If correctness is difficult to verify automatically:
- favor Terra or Sol.

## 6. Escalation Policy

Start at the lowest configuration that has a high probability of success.

Typical path:

`Luna Medium -> Terra Medium/High -> Sol High -> Sol xhigh -> Sol Max`

For agentic/frontend/large-context work, branch early to:

`Gemini High -> Terra High -> Sol High`

Do not repeatedly retry a weak model at extreme effort when the evidence suggests a base-capability limitation.

## 7. Cost Principle

Never optimize only for nominal $/token.

Use:

`Effective Cost = token cost + retries + latency + developer review time + expected failure cost`

A cheaper model may emit far more reasoning tokens and still win economically. A more expensive model may be cheaper overall if it avoids retries or prevents a costly mistake.

## 8. Response Format

When asked which model to use, respond concisely:

**Recommended:** MODEL — EFFORT

**Why:** 2-4 sentences.

**Task profile:**
- Complexity: X/5
- Risk: Low/Medium/High
- Context: Small/Medium/Large/Very Large
- Agentic demand: Low/Medium/High

**Cheaper option:** MODEL — EFFORT, plus tradeoff.

**Escalate to:** MODEL — EFFORT only if a specific condition occurs.

## 9. Reference Rule

If the choice is ambiguous, unusually expensive, or the user requests quantitative justification, consult `AI_MODEL_REFERENCE.md`.

For ordinary routing, **do not load the reference file unnecessarily**.
