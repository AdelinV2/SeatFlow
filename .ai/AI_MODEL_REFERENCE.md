# AI_MODEL_REFERENCE.md

## Purpose

Detailed evidence and rationale for `.ai/MODEL_ROUTER.md`.

Data snapshot: **September 3, 2026**.

Benchmark numbers are directional, not guarantees. Harness, prompts, tools, repository structure, reasoning configuration, caching, provider implementation, and task mix can materially change real-world results. Prefer benchmark families that resemble SeatFlow's actual workflow: long-horizon code changes, terminal/tool use, codebase understanding, debugging, and strong verification.

---

## 1. SeatFlow Context

SeatFlow is a Java/Spring + Angular system with multiple services and explicit AI workflow files. The project separates:

`planning -> implementation -> review -> bug fixing -> verification`

That structure is important: it allows a strong model to make decisions and a cheaper/high-throughput model to execute a deterministic task.

Critical hidden-failure areas include:

- reservation hold/concurrency behavior
- zero-double-booking guarantees
- PostgreSQL source-of-truth semantics
- Kafka + transactional outbox behavior
- payment/refund/idempotency/Stripe state transitions
- authentication/authorization
- realtime/distributed consistency
- destructive or production-sensitive migrations

The target is **very good code quality**, not maximum model strength on every prompt.

---

## 2. Active Model Set

### Codex Plus

Primary models:

- GPT-5.6 Luna
- GPT-5.6 Terra
- GPT-5.6 Sol

GPT-5.6 supports reasoning efforts `none`, `low`, `medium`, `high`, `xhigh`, and `max` in the official model family. In SeatFlow, the normal useful range is Low/Medium for utility work, High for serious work, and xHigh only for genuinely difficult problems.

### Antigravity Pro

Relevant current choices:

- Gemini 3.8 Flash Medium
- Gemini 3.8 Flash High
- Gemini 3.1 Pro High
- Claude Sonnet 4.6 Thinking
- Claude Opus 4.6 Thinking

Gemini 3.8 Flash is the default Antigravity coding choice. Older/alternative frontier models are retained mainly for independent second opinions.

### OpenCode Go

Relevant current choices:

- Muse Spark 1.3 Contributor
- Hy4 preview
- GLM-5.3
- other models available in the catalog

OpenCode says model availability may change. Run `/models` and use the current catalog instead of assuming a variant exists.

---

## 3. Benchmark Snapshot

### GPT-5.6 family

OpenAI's published software-engineering results:

| Benchmark | Luna | Terra | Sol |
|---|---:|---:|---:|
| SWE-Bench Pro | 62.7% | 63.4% | 64.6% |
| DeepSWE v1.1 | 67.2% | 69.6% | 72.7% |
| Terminal-Bench 2.1 | 84.7% | 87.4% | 88.8% |

Operational interpretation:

- Sol is strongest, but the gap from Terra on these coding-agent benchmarks is relatively small.
- Terra therefore has the best default role for planning/review/debugging when failure risk is serious but not critical.
- Sol's main justification is **lower expected failure cost on hidden critical bugs**, not a need to maximize every benchmark point.
- Luna remains capable for routine coding but is not the preferred hard-debugging/architecture authority.

### Muse Spark 1.3

Meta's release reports strong improvements in long-horizon coding and agentic work. Published release figures include:

| Benchmark | Muse Spark 1.3 |
|---|---:|
| DeepSWE v1.1 | 75.4% |
| SWE-Atlas Codebase QnA | 59.4% |
| Terminal-Bench 2.1 | 88.8% |
| MRCR 256K-512K | 98.5% |
| MRCR 512K-1M | 98.1% |

Meta also reports that 1.3 used approximately:

- **20% fewer tool calls**
- **25% fewer tokens**

than Muse Spark 1.2 in comparisons by Meta engineers.

This is a major upgrade from the previous SeatFlow router, where Muse 1.2 was only a large-context implementation option.

Important caveat for SeatFlow: Meta's DeepSWE v1.1 methodology covers TypeScript, Go, Python, JavaScript, and Rust — **not Java**. Therefore:

- the benchmark strongly supports Muse as an agentic implementation worker
- it does not directly validate Spring/JPA/transactional correctness
- Terra/Sol remain the authority for subtle Java backend invariants

### Gemini 3.8 Flash

Google positions Gemini 3.8 Flash as its best Flash reasoning/coding model yet and specifically targets:

- long-horizon software engineering
- autonomous agents
- iterative tool use
- production-ready agent workflows

Google states that 3.8 Flash substantially improves over 3.7 and can use extra reasoning/tool steps on difficult tasks. Published/launch benchmark reporting places it near larger frontier models on long-horizon software engineering and exceptionally strong on terminal/tool workflows.

Operational interpretation:

- strong fit for Angular/TypeScript/Tailwind and browser-heavy work
- High is appropriate when quality and multi-step tool use matter
- Medium is better when the task is simple enough that High's extra token/tool usage has little benefit
- backend transaction/concurrency authority still belongs to Terra/Sol

### Hy4 preview

Tencent's published/vendor results include approximately:

| Benchmark | Hy4 preview |
|---|---:|
| SWE-Bench Pro | 65.7% |
| DeepSWE | 64.3% |
| Terminal-Bench 2.1 | 85.4% |

Tencent also describes a ~1M context window and strong gains in planning, debugging, verification, and frontend work.

However, Tencent explicitly lists known preview limitations:

- reasoning longer than necessary
- over-verifying work

Therefore Hy4 is a good **diversity fallback**, not the default SeatFlow implementation model.

### GLM-5.3

Published vendor/model-card reporting includes approximately:

| Benchmark | GLM-5.3 |
|---|---:|
| DeepSWE v1.1 | 66.9% |
| Terminal-Bench 2.1 | 88.2% |
| Terminal-Bench 3.0 | 28.3% |

This makes GLM-5.3 a credible terminal-heavy fallback, but OpenCode Go allowance economics are materially worse than Muse Spark 1.3, while Muse also has stronger published DeepSWE evidence.

---

## 4. Why Muse 1.3 Is the Default Implementer

SeatFlow's task execution protocol is unusually favorable to a model like Muse:

- architecture is pre-decided
- task files provide explicit contracts
- file inventory is known
- deterministic verification commands exist
- tests can reject many implementation failures
- the implementation agent is instructed not to improvise architecture

That changes the economic optimum.

For a complete, non-critical task file, the best workflow is usually:

`Terra High plan -> Muse 1.3 High/xHigh implementation -> deterministic verification -> Terra High review`

This preserves premium reasoning for the stages where it has the highest marginal value.

---

## 5. Why Terra Is the Default Quality Gate

OpenAI's published numbers show Terra close to Sol on long-horizon coding:

- DeepSWE: 69.6 vs 72.7
- Terminal-Bench 2.1: 87.4 vs 88.8
- SWE-Bench Pro: 63.4 vs 64.6

For normal SeatFlow review/planning/debugging, that gap does not justify routing every task to Sol.

Terra High is therefore the normal authority for:

- task planning
- code review
- Spring/JPA reasoning
- Kafka/outbox reasoning
- difficult debugging
- API design
- migrations
- cross-service reasoning

Use Terra xHigh when the problem itself is unusually hard, not because the diff is large.

---

## 6. Why Sol Is a Risk Override, Not a Default

Sol is most valuable where an incorrect answer can look plausible and still pass ordinary verification.

Examples:

- race conditions
- double booking
- payment/refund state transitions
- idempotency
- authorization
- distributed ordering
- data corruption
- dangerous migration behavior

A critical SeatFlow change should often use Sol twice:

1. **before implementation** for architecture/invariant analysis
2. **after implementation** for final critical review

The implementation itself can often be done by Terra High, or by Muse from a fully deterministic task specification.

This is higher quality than using Sol for everything because it preserves Sol quota for the checks where hidden failure risk is highest.

---

## 7. Gemini 3.8 vs Terra vs Muse

Use the dominant requirement, not a global ranking.

| Requirement | Best default |
|---|---|
| Deterministic implementation from a complete task | Muse 1.3 High |
| Large/cross-file autonomous implementation | Muse 1.3 xHigh |
| Angular / Tailwind / visual quality | Gemini 3.8 High |
| Browser/tool-heavy frontend debugging | Gemini 3.8 High |
| Quick/simple frontend change | Gemini 3.8 Medium |
| Backend architecture | Terra High |
| Spring/JPA/transactions | Terra High |
| Code review | Terra High |
| Hard root-cause debugging | Terra High |
| Critical concurrency/payment/security | Sol High |

Muse can score extremely well on agentic coding without being the best final authority for hidden backend invariants. Gemini can be a better frontend agent without replacing Terra for transaction semantics. Sol can be the strongest model without being the economically correct default.

---

## 8. OpenCode Go Economics

OpenCode Go currently defines:

- **5-hour limit:** $12 of usage
- **weekly limit:** $30
- **monthly limit:** $60

Current Go pricing/allowance values include:

| Model | Input / 1M | Output / 1M | Estimated requests / 5h | / week | / month |
|---|---:|---:|---:|---:|---:|
| Muse Spark 1.3 Contributor | $0.10 | $0.20 | 45,300 | 113,300 | 226,600 |
| Hy4 preview | $0.834 | $2.501 | 1,350 | 3,380 | 6,770 |
| GLM-5.3 | $1.40 | $4.40 | 220 | 540 | 1,080 |
| Kimi K3 | $3.00 | $15.00 | 110 | 250 | 490 |

The request counts are OpenCode's estimates from observed request patterns, **not guarantees**. Real SeatFlow prompts can be much larger than the assumed pattern.

The important conclusion is not the exact request count. It is that Muse 1.3 Contributor is so inexpensive inside Go that inference cost should rarely be the reason to choose a weaker implementation model.

---

## 9. Privacy / Data-Handling Constraint

OpenCode's current privacy table says:

- Muse Spark 1.3 Contributor: prompts/completions may be used for model training; not ZDR
- Hy4 preview: not used for training; 0-day retention
- GLM-5.3: not used for training; 0-day retention

Therefore:

- public SeatFlow source code can use Muse Contributor
- do not send `.env`, credentials, Stripe secrets, tokens, production dumps, private customer data, or any sensitive material to Muse Contributor
- when sensitive context is unavoidable, use an appropriate non-Contributor route and still minimize the sensitive data sent

Never commit secrets to SeatFlow regardless of model selection.

---

## 10. Reasoning-Effort Policy

### GPT-5.6

- **Low:** extraction/docs/very small tasks
- **Medium:** bounded implementation or small review
- **High:** default planning, review, difficult debugging
- **xHigh:** unusually difficult multi-service reasoning
- **Max:** final escalation; not routine

### Gemini 3.8 Flash

Antigravity exposes Medium and High.

- **Medium:** simple UI, quick exploration, low-risk changes
- **High:** serious frontend, browser/tool loops, complex state, long-horizon work

### Muse Spark 1.3

Reasoning variants are provider/catalog dependent.

- use **High** for normal implementation if exposed
- use **xHigh** for large/cross-file/agentic implementation if exposed
- avoid choosing Max merely because it exists; use the highest useful non-Max variant unless the task clearly benefits from more test-time compute

OpenCode explicitly warns that variant names are model-specific. Check the current catalog.

### Hy4 / GLM

Use their normal reasoning configuration unless the current provider exposes a documented variant. Do not invent effort names.

---

## 11. Verification and Review Policy

For good code quality, model routing is only half the system.

### Strong oracle tasks

Examples:

- compiler errors
- unit tests
- integration tests
- lint
- deterministic API tests
- browser checks

Use Muse/Gemini aggressively for implementation.

### Weak oracle tasks

Examples:

- race conditions not covered by load tests
- transaction boundaries
- idempotency
- distributed ordering
- authorization gaps
- architecture drift

Use Terra/Sol for reasoning and final review.

### Review rule

For substantive code:

`implementation model != final reviewer` whenever practical.

This reduces correlated mistakes and is more valuable than simply increasing the implementation model's thinking effort.

---

## 12. Recommended SeatFlow Pipelines

### Standard backend task

`Terra High planning -> Muse High implementation -> tests -> Terra High review`

### Large backend task

`Terra High planning -> Muse xHigh implementation -> full verification -> Terra High review`

### Frontend task

`Gemini 3.8 High implementation -> browser/tests -> Terra High review for state/contracts if non-trivial`

### Small reproducible bug

`Muse High reproduce/test/fix -> verification`

If one real repair loop fails:

`Terra High root-cause analysis`

### Critical payment/reservation/security task

`Sol High reasoning -> Terra High implementation -> exhaustive tests -> Sol High final review`

Muse may replace Terra for implementation only when the Sol/Terra plan is fully deterministic and the task has strong tests.

### Production incident

`Terra High triage/root cause`

Escalate to `Sol High/xHigh` when money, security, data integrity, concurrency, or distributed correctness is implicated.

---

## 13. Models Not in the Default Route

OpenCode Go contains many other capable models, including Qwen3.8, DeepSeek V4, Kimi K3, GLM variants, MiniMax, and others.

They are intentionally not in the normal SeatFlow route because:

1. more model choices increase routing friction
2. Muse 1.3 has unusually strong coding evidence and allowance economics
3. Terra/Gemini/Sol already cover the main capability gaps
4. secondary models are still available for diversity when a primary route fails

Do not add a model to the default router simply because it tops one benchmark.

---

## 14. Sources

Re-check these when models or subscription limits change:

- OpenAI GPT-5.6 launch / benchmarks: https://openai.com/index/gpt-5-6/
- OpenAI GPT-5.6 model guidance: https://developers.openai.com/api/docs/guides/latest-model
- Meta Muse Spark 1.3 release: https://research.meta.ai/blog/introducing-muse-spark-1-3
- Meta Muse Spark 1.3 evaluation methodology: https://research.meta.ai/static/muse-spark-1-3-multimodal-evaluation-methodology
- Google Gemini 3.8 Flash launch: https://blog.google/innovation-and-ai/models-and-research/gemini-models/3-8-flash-and-3-8-flash-cyber/
- Google Gemini 3.8 Flash model card: https://deepmind.google/models/model-cards/gemini-3-8-flash/
- Google Antigravity models: https://antigravity.google/docs/models/
- Google Antigravity headless/model efforts: https://antigravity.google/docs/cli/headless/
- Tencent Hy4 preview: https://github.com/Tencent-Hunyuan/Hy4-preview
- OpenCode Go models, limits, pricing, privacy: https://opencode.ai/docs/go/
- OpenCode model variants: https://opencode.ai/docs/models/

---

## 15. Final Policy

```text
MUSE SPARK 1.3 HIGH
= default deterministic implementation worker

MUSE SPARK 1.3 XHIGH
= large / cross-file / long-horizon implementation worker

GEMINI 3.8 FLASH HIGH
= frontend / visual / browser / tool-heavy worker

GEMINI 3.8 FLASH MEDIUM
= simple frontend / fast exploration worker

GPT-5.6 TERRA HIGH
= default planner / reviewer / debugger / backend reasoning authority

GPT-5.6 TERRA MEDIUM
= bounded work with some judgment / small review

GPT-5.6 SOL HIGH
= critical correctness authority

GPT-5.6 SOL XHIGH
= severe critical escalation

GPT-5.6 LUNA LOW/MEDIUM
= docs / extraction / low-risk utility

HY4 / GLM-5.3
= independent OpenCode fallback, not default
```

The best SeatFlow workflow is usually **strong decisions + high-throughput execution + independent review + deterministic verification**.

Use Sol where the consequences justify it. Do not use Sol merely to make a normal task feel safer.
