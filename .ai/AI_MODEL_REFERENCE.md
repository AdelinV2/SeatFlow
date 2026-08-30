# AI_MODEL_REFERENCE.md

## Purpose

Detailed reference for `MODEL_ROUTER.md`. Consult this file only when model selection is ambiguous, high-risk, cost-sensitive, or requires quantitative justification.

Data snapshot: **August 30, 2026**.

Benchmark numbers are directional, not guarantees. Harness, prompts, tools, repository structure, reasoning configuration, caching, and retry policy can materially change real-world results.

---

## 1. Model Positioning

### GPT-5.6 Luna
Fastest and most cost-efficient GPT-5.6 tier. Best suited to high-volume, bounded, verifiable work.

### GPT-5.6 Terra
Balanced GPT-5.6 tier. Treat as the default serious software-engineering model when reasoning quality matters but Sol is unnecessary.

### GPT-5.6 Sol
Flagship GPT-5.6 tier. Reserve for difficult or high-risk tasks where stronger capability can reduce retries or failure risk.

### Gemini 3.7 Flash
Google's cost-efficient coding/agent model with configurable thinking and a ~1M-token context window. Particularly attractive for agentic coding, frontend/web work, broad repository exploration, and token-heavy autonomous loops.

---

## 2. Reasoning Effort

GPT-5.6 API supports reasoning settings including:
- none
- low
- medium
- high
- xhigh
- max

Product surfaces may expose different labels/options.

Gemini 3.7 Flash supports configurable thinking. For this policy:
- Low = inexpensive/simple work
- Medium = ordinary implementation
- High = difficult, agentic, exploratory, or long-context work

Higher effort is **not automatically better value**. On bounded tasks, extra reasoning can increase latency/token usage without changing acceptance.

---

## 3. Economic Model

Do not confuse token price with task price.

Use:

`Task Cost ≈ uncached input + cached input + reasoning/output + retries + developer time + expected failure cost`

Reasoning tokens are economically important because long internal reasoning can make a nominally cheap configuration consume much more compute per completed task.

The correct comparison is:

`cost per successful task`, not merely `cost per 1M tokens`.

### Practical implications

A cheaper model can remain cheaper even if it reasons several times longer.

A stronger model can nevertheless be economically superior when:
- the weaker model needs retries,
- debugging paths are misleading,
- human review time increases,
- or failure has meaningful consequences.

---

## 4. Coding Benchmark Snapshot

Published benchmark results around August 2026 show the following broad pattern.

### GPT-5.6

OpenAI reports GPT-5.6 Sol as its strongest coding model, with Terra close behind on many engineering workloads and Luna surprisingly capable for its tier.

Relevant published results include strong performance across:
- DeepSWE
- Terminal-Bench
- coding-agent evaluations
- long-context evaluations

OpenAI's published long-context MRCR results show a major distinction between Luna and the larger tiers:

| Benchmark | Sol | Terra | Luna |
|---|---:|---:|---:|
| MRCR 256K-512K | 91.5% | 89.6% | 41.3% |
| MRCR 512K-1M | 73.8% | 72.5% | 41.3% |

This is one of the strongest reasons not to equate Luna's nominal context-window capacity with Terra/Sol-level reasoning across huge contexts.

### Gemini 3.7 Flash vs GPT-5.6 Terra

Google's August 2026 model card reports:

| Benchmark | Gemini 3.7 Flash | GPT-5.6 Terra |
|---|---:|---:|
| Artificial Analysis Intelligence Index | 56 | 57 |
| FrontierCode 1.1 | 43.6% | 41.3% |
| DeepSWE v1.1 | 65.3% | 69.6% |
| Code Arena Web Development | 1588 Elo | 1523 Elo |
| Terminal-Bench 2.1 | 85.8% | 87.4% |
| Terminal-Bench 3.0 | 14.9% | 20.8% |
| AutomationBench | 30.4% | 23.6% |

Interpretation:
- Terra leads on several difficult long-horizon/general-agent engineering evaluations.
- Gemini is extremely competitive and leads on the cited production-code, web-development, and automation benchmarks.
- This makes Gemini especially attractive for frontend and high-throughput agentic workflows.

---

## 5. Development Profiles

### Luna

Strengths:
- cheap
- fast
- good ordinary coding capability
- ideal for repeated deterministic tasks

Weaknesses:
- hard root-cause debugging
- architectural ambiguity
- very-large-context reasoning
- low-level/system reasoning relative to larger tiers

Best tasks:
- boilerplate
- CRUD
- DTO/mappers
- unit tests
- docs
- simple fixes
- mechanical refactors
- CI/log triage

Effort:
- Low: trivial/mechanical
- Medium: default Luna coding
- High: bounded harder tasks
- xhigh/Max: niche; do not use to compensate for a base-capability mismatch

### Terra

Strengths:
- strong software-engineering reasoning
- good balance of quality, latency, and cost
- strong debugging
- strong repository understanding
- close enough to Sol on many ordinary development tasks that Sol is unnecessary

Best tasks:
- planning
- backend implementation
- Spring Boot
- JPA/transactions
- code review
- debugging
- refactoring
- migrations
- API design
- architecture

Effort:
- Medium: default implementation
- High: planning/debugging/review
- xhigh: hard or risky reasoning
- Max: rare; compare with Sol before selecting it

### Sol

Strengths:
- highest GPT-5.6 capability tier
- best fit for ambiguity and high failure cost
- stronger escalation target than brute-forcing smaller tiers

Best tasks:
- race conditions
- distributed systems
- production incidents
- Kafka/event consistency
- security
- authentication/authorization
- payments
- data integrity
- dangerous migrations
- critical architecture

Effort:
- Medium: complex but bounded
- High: difficult
- xhigh: critical
- Max: final escalation

### Gemini 3.7 Flash High

Strengths:
- strong price/performance
- excellent web-development results
- strong agentic/tool-use performance
- large context
- multimodal
- attractive for long autonomous loops

Best tasks:
- Angular/Tailwind/frontend
- UI implementation
- screenshot/mock implementation
- autonomous edit/test/fix loops
- repository exploration
- broad multi-file work
- tool-heavy implementation
- large-context cost-sensitive analysis

Caution:
High thinking can be token-hungry. Do not use it for trivial deterministic tasks.

---

## 6. Development-Stage Recommendations

### Planning

Default: **Terra High**

Use Gemini High when planning requires broad repository exploration or frontend/tool-heavy context.

Use Sol High when architecture is difficult to reverse or involves distributed consistency, security, data integrity, or major ambiguity.

### Implementation

Simple: **Luna Medium**

Normal backend: **Terra Medium**

Agentic/autonomous: **Gemini High**

Complex/high-risk backend: **Terra High** or **Sol High**

### Review

Bulk/first pass: **Luna Medium**

Normal PR review: **Terra High**

Repository-wide agentic audit: **Gemini High**

Security/payment/data-integrity review: **Sol xhigh**

### Debugging / Fixing

Localized/reproducible: **Luna High**

Normal difficult bug: **Terra High**

Exploratory/tool-heavy: **Gemini High**

Intermittent/concurrent/distributed/production-critical: **Sol High/xhigh**

### Testing

Test generation: **Luna Medium**

Autonomous test/fix loop: **Gemini High**

Complex failure interpretation: **Terra High**

Critical failure analysis: **Sol High**

### Frontend

Default: **Gemini High**

Use Terra High when architecture/state/data modeling is harder than visual implementation.

Use Sol only for unusually high-risk cross-system constraints.

---

## 7. Context Routing

### <20K relevant tokens
Choose based primarily on task difficulty.

### 20K-100K
- Luna: bounded work
- Gemini: exploratory/agentic
- Terra: reasoning-heavy

### 100K-300K
Prefer Gemini High or Terra High.

### 300K+
- Gemini High: broad exploration/value
- Terra High: precision
- Sol High: critical reasoning

Avoid blindly dumping huge repositories into Luna merely because the context window accepts them.

---

## 8. Risk Routing

### Low risk
Examples:
- boilerplate
- styling
- generated tests
- compile-detectable edits

Prefer Luna/Gemini.

### Medium risk
Examples:
- ordinary feature
- refactor
- integration
- normal bug

Prefer Terra or Gemini High.

### High risk
Examples:
- auth
- security
- payments
- production migrations
- distributed consistency
- data loss
- production incident

Prefer Sol or, where appropriate, Terra xhigh.

---

## 9. Verification Effect

Strong verification changes the economics.

When compiler/tests/lint/browser checks reliably reject bad work, lower-cost/high-throughput models become more attractive.

When correctness depends on subtle reasoning and is difficult to verify automatically, stronger models are economically safer.

Examples:

100 generated unit tests:
**Luna Medium**

Booking/payment transaction architecture:
**Terra High or Sol High**

---

## 10. Why More Reasoning Is Not Always Better

Independent Codex testing published in August 2026 reported bounded common tasks where Medium achieved the same accepted results as higher reasoning settings while consuming less time/context.

Treat this as evidence for an escalation policy, not as proof that Medium always equals High/Max.

Use higher effort when:
- ambiguity increases,
- search space is large,
- evidence conflicts,
- failure cost rises,
- or lower effort has actually failed.

Do not pay for Max preemptively on ordinary coding.

---

## 11. Recommended Practical Presets

If only four presets are desired:

### Cheap Worker
**GPT-5.6 Luna Medium**

### Agentic / Frontend Worker
**Gemini 3.7 Flash High**

### Senior Engineer
**GPT-5.6 Terra High**

### Critical Escalation
**GPT-5.6 Sol xhigh**

This covers the majority of software-development routing without unnecessary complexity.

---

## 12. Decision Rules

Use these rules in order:

1. If trivial + deterministic + verifiable -> Luna Low/Medium.
2. If frontend/UI-heavy -> Gemini High.
3. If strongly agentic/tool-heavy and risk <= medium -> Gemini High.
4. If very-large-context exploration and cost matters -> Gemini High.
5. If ordinary backend implementation -> Terra Medium.
6. If planning/review/debugging/complex refactor -> Terra High.
7. If difficult and high-risk -> Sol High.
8. If security/payments/auth/data integrity/distributed consistency/irreversible production action -> Sol xhigh.
9. If the selected tier repeatedly fails -> escalate base-model capability before blindly maximizing reasoning effort.
10. Sol Max is the last escalation.

---

## 13. Sources

Current primary sources used for this reference:

- OpenAI GPT-5.6 launch and benchmark documentation
- OpenAI GPT-5.6 deployment/system-card updates
- OpenAI GPT-5.6 price-performance update
- Google DeepMind Gemini 3.7 Flash model card
- Google Gemini 3.7 Flash launch documentation

For live pricing or newly released model variants, re-check official vendor documentation before making monetary estimates.

---

## 14. Final Principle

The best development model is not the strongest model.

Choose the cheapest and fastest configuration with a sufficiently high probability of completing the task correctly.

Optimize:

`success probability / (token cost + retries + latency + human review + expected failure cost)`

When uncertainty is high, consult the evidence in this reference and escalate deliberately.
