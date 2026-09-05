# Poracode Integration — First-Class Threads and Crossagents

## Purpose

This file maps SeatFlow's harness-agnostic orchestration policy to **Poracode**.

For required autonomous workflow stages, Poracode **first-class app threads** are the canonical delegation mechanism. They are created and managed through the always-on `poracode` MCP thread tools and appear as normal worker threads in the Poracode sidebar.

Poracode **Crossagents** remain available only for small, lightweight, ephemeral delegation where failure does not control the SeatFlow stage pipeline.

Read in this order when Poracode is the active harness:

1. `AGENTS.md`
2. `.ai/ORCHESTRATOR.md`
3. active `.ai/workflows/*.md` protocol
4. `.ai/MODEL_ROUTER.md`
5. this file for Poracode-specific execution details

Poracode is the execution layer; repository policy remains authoritative.

---

## 1. Canonical Delegation Mechanism — First-Class Poracode Threads

Required SeatFlow stages such as PLAN, IMPLEMENT, REVIEW, FIX, RE-REVIEW, and QA must use Poracode first-class worker threads when the `poracode` MCP thread tools are available.

Canonical lifecycle:

```text
supervisor
  -> create_thread
  -> wait_for_thread
  -> read_thread
  -> validate structured handoff
  -> continue to the next stage
```

The worker thread should be visible in the Poracode sidebar and should receive an exact, self-contained stage prompt.

Use these tools as follows:

- `create_thread` — create the worker with the selected `agentKind`, model, effort, title, and optional isolated worktree;
- `wait_for_thread` — wait until the worker leaves its active working state; a bounded wait is not itself a failure;
- `read_thread` — collect the worker's final answer/handoff after it settles;
- `send_to_thread` — continue or clarify work in that same worker when its result is incomplete but the session is resumable;
- `steer_thread` — add non-interrupting guidance to a running worker when needed;
- `get_thread` / `list_threads` — inspect state before deciding whether to retry or replace a worker;
- `stop_thread` — free the worker runtime only after its result is no longer needed.

The supervisor must not ask the user to type `continue`, relay prompts manually, or reopen a worker merely because a delegated turn settled without a complete handoff. If the worker is still usable, the supervisor should call `send_to_thread` itself with the missing continuation/context and keep owning the pipeline.

### 1.1 Main Supervisor May Be OpenCode / Muse

The SeatFlow supervisor may be **OpenCode / Muse Spark 1.3**. It is fully allowed to create other first-class Poracode threads that also use OpenCode/Muse, as well as Gemini, Codex, Qwen, GLM, or another route allowed by `.ai/MODEL_ROUTER.md`.

Allowed examples:

```text
OpenCode/Muse supervisor
  -> create_thread -> OpenCode/Muse worker
  -> create_thread -> Gemini worker
  -> create_thread -> Codex worker
```

Multiple first-class worker threads may run concurrently when their work is genuinely independent and workspace isolation rules are satisfied.

Provider identity does not make first-class Poracode worker threads recursively nested. The orchestration authority remains the supervisor thread plus the `poracode` MCP thread lifecycle.

### 1.2 OpenCode Native Subagents Remain Disabled

OpenCode's own native `task` / subagent delegation is not part of the SeatFlow orchestration model.

The repository-level `opencode.json` denies the native OpenCode `task` tool. Do not remove or bypass that rule during normal SeatFlow execution.

Forbidden topology:

```text
OpenCode supervisor/worker
  -> native OpenCode task/subagent
      -> child
```

All required inter-agent stage delegation must go through first-class Poracode worker threads instead.

---

## 2. Crossagents — Narrow, Non-Authoritative Use Only

Crossagents may still be used for very small, ephemeral, preferably read-only work when all of the following are true:

- the result is not a required stage gate;
- failure will not stop IMPLEMENT/REVIEW/FIX/QA progression;
- no persistent sidebar worker is useful;
- no concurrent writes to the same mutable workspace are involved.

Do **not** use Crossagents as the default mechanism for required SeatFlow stage execution.

Current SeatFlow policy intentionally avoids making the autonomous pipeline depend on ephemeral structured-child lifecycle behavior. In observed Poracode/OpenCode runs, an ephemeral Crossagent child may surface `Interrupted: agent session ended before completion.` even though equivalent work succeeds as a first-class Poracode thread. Treat first-class threads as the reliability workaround until the underlying lifecycle behavior is known to be resolved.

If Crossagents are explicitly used for a tiny non-critical task:

1. a bounded `spawn_agent` / `wait_for_agent` window is not the child's execution lifetime;
2. `status: running` means keep waiting, not automatic failure;
3. absence of streamed progress text is not failure evidence by itself;
4. preserve incremental output cursors when available;
5. do not create a duplicate worker while the original run is still alive;
6. Gemini ACP may be silent during reasoning;
7. required workflow stages must still use first-class Poracode threads.

Crossagents authorization (for example `@Crossagents`) is only needed when Crossagents themselves are intentionally used. It is **not** required for the normal first-class-thread SeatFlow workflow.

---

## 3. Thread Lifecycle and Failure Handling

A worker thread's lifecycle is authoritative for the delegated stage.

Rules:

1. Create a dedicated worker thread for each independent specialist stage unless reusing the same worker is clearly appropriate for a continuation of the same stage.
2. Call `wait_for_thread` and continue waiting if the returned state shows the worker is still active. A wait timeout is only a synchronization boundary.
3. When the worker settles, call `read_thread` and validate that the expected structured handoff is actually present.
4. If the worker is `idle`/settled but the handoff is incomplete, use `send_to_thread` to request the missing work. Do not require user intervention.
5. If a worker returns `needs_reply` or `needs_approval`, satisfy the request automatically only when repository policy clearly authorizes the action; otherwise surface the concrete approval need to the user.
6. If a worker is `error` or becomes inactive unexpectedly, inspect `get_thread` / `read_thread` before deciding whether to resume, retry, or use a router fallback.
7. Never start a duplicate writer until the previous worker is known to be terminal or safely abandoned.
8. Apply `.ai/MODEL_ROUTER.md` fallback/escalation only after a real provider/model/session failure or quality trigger, not merely after a long-running wait.
9. The supervisor owns continuation. A child failure must not end the main workflow if an allowed retry/fallback remains.

This policy is specifically intended to eliminate the manual `continue` step from normal autonomous execution.

---

## 4. Provider Responsibilities

| Logical role | Preferred harness | Notes |
|---|---|---|
| Supervisor / coordinator | OpenCode/Muse or Antigravity | User-selected supervisor wins; Muse is fully supported with first-class Poracode worker threads |
| Routine planning / repo analysis | Antigravity | Gemini 3.8 Flash High for substantive work |
| Backend/general implementation | OpenCode | Resolve Muse Free -> Muse Go Contributor before other alternatives |
| Frontend/visual/browser implementation | Antigravity | Gemini 3.8 Flash High by default |
| Independent substantive review | Codex | GPT-5.6 Terra High, standard speed |
| Small bounded review | Codex | GPT-5.6 Terra Medium, standard speed |
| Critical correctness review | Codex | GPT-5.6 Sol High, standard speed |
| Clear review-finding fixes | OpenCode | Muse route first |
| Final QA | Antigravity or Codex | Resolve according to risk and implementer independence |

The table is an execution mapping, not a replacement for `.ai/MODEL_ROUTER.md`.

---

## 5. Muse Spark 1.3 Resolution — Mandatory Priority

Whenever `.ai/MODEL_ROUTER.md` selects the logical `MUSE_1_3` route, the Poracode worker-thread selection must resolve it in this order.

### Priority 1 — OpenCode Zen Free

- Provider/harness: `OpenCode`
- Display name: `Muse Spark 1.3 Contributor Free`
- Verified Poracode/OpenCode selection ID: `opencode/muse-spark-1.3-contributor-free`
- Cost priority: **first choice whenever available and privacy policy allows it**

### Priority 2 — OpenCode Go

- Provider/harness: `OpenCode`
- Display name: `Muse Spark 1.3 Contributor`
- OpenCode Go model ID: `opencode-go/muse-spark-1.3-contributor`
- Use when the Free route is unavailable, rate-limited, disabled, absent from the current catalog, or fails with a provider-capacity/availability error.

The Free -> Go fallback is automatic and does **not** require user confirmation.

### Priority 3 — Task-specific alternative

If both Muse 1.3 routes are unavailable or prohibited by privacy constraints, use the **Alternative** route defined by `.ai/MODEL_ROUTER.md` for that task. For most deterministic implementation this is Gemini 3.8 Flash High through Antigravity.

### What counts as unavailable

Treat the Free route as unavailable only when one of these is true:

- the model is absent from the current Poracode/OpenCode model catalog;
- provider reports unavailable/disabled/capacity error;
- Free route is rate-limited/quota-blocked;
- `create_thread` cannot select the required route/model;
- the route is prohibited by the task's privacy/data policy.

Do **not** classify these as availability failures:

- implementation has a bug;
- a test fails;
- reviewer finds issues;
- the model misunderstood a requirement;
- the first repair attempt fails.

Those are quality/debugging events and must follow the workflow's repair/escalation rules.

### Muse effort

Effort names are provider-exposed metadata, not assumptions.

- small/mechanical work: prefer `High` when exposed;
- substantive implementation/fixes/refactors: prefer `xHigh` when exposed;
- do not invent `Max` or another variant if it is not present in the current OpenCode catalog;
- if the exact named effort is unavailable, use the strongest practical non-Max effort exposed by the selected Muse route and report the actual effort.

---

## 6. Muse Privacy Guardrail

Treat both Muse Contributor routes conservatively as **not suitable for secrets or sensitive/private production data** unless current provider metadata explicitly proves otherwise.

Never delegate to Muse any prompt containing:

- passwords, API keys, OAuth secrets, tokens or private keys;
- real `.env` contents;
- private production/customer dumps;
- payment credentials or sensitive PII;
- other proprietary/sensitive material the repository policy forbids sending to a non-ZDR/training route.

Public SeatFlow source code and synthetic/test data are acceptable under the current project policy.

If sensitive context is required, route to an allowed privacy-compatible alternative from `.ai/MODEL_ROUTER.md` rather than redacting incompletely.

---

## 7. Codex Standard-Speed Policy — Fast OFF

**Codex Fast mode is disabled by default for every SeatFlow delegation.**

For Codex stage threads specify:

```text
Provider: Codex
Model: <Luna | Terra | Sol according to MODEL_ROUTER>
Reasoning effort: <Low | Medium | High | xHigh as selected>
Fast mode: OFF
Speed: standard
```

Rules:

1. Never enable Fast automatically.
2. Never use a `Fast`/accelerated variant merely to reduce latency.
3. Fast may be enabled only when the user explicitly requests it for that run/stage.
4. If the selected Poracode thread route cannot guarantee `Fast = OFF`, do not silently consume accelerated quota; use an allowed standard-speed route or report the limitation.
5. Record `FAST MODE: OFF` in the stage handoff when Codex is used.

This rule applies to Luna, Terra, and Sol.

---

## 8. Exact Selection / No Silent Substitution

Before creating a worker thread, the supervisor should resolve the exact provider/model/effort through `.ai/MODEL_ROUTER.md` and pass that selection to `create_thread` when the Poracode tool exposes the fields.

If the exact route is unavailable:

1. apply only the fallback chain allowed by `.ai/MODEL_ROUTER.md` and this file;
2. report the fallback in the parent thread;
3. never substitute a different model family without saying so;
4. never label a result as if the originally requested model ran when another model actually ran.

At the end of every delegated stage report:

```text
Provider: ...
Model display name: ...
Model ID (when exposed): ...
Effort: ...
Fast mode: OFF | ON | N/A
Fallback used: none | <from -> to + reason>
Worker thread: <title/id when useful>
```

---

## 9. Parent Supervisor Behavior

The parent is a coordinator and may itself be OpenCode/Muse, Antigravity, Codex, or another supported harness selected by the user. Do not force a parent-provider switch merely to delegate to the same provider.

For a standard backend task with a Muse supervisor:

```text
OpenCode / Muse supervisor
  -> classify task
  -> optional planning worker thread
  -> create OpenCode / Muse implementation thread
  -> wait + read result
  -> create Codex / Terra High / Fast OFF review thread
  -> wait + read result
  -> if findings: create/reuse Muse fix thread
  -> create independent re-review thread when required
  -> create QA thread selected by risk/independence
  -> finalize
```

For a critical task:

```text
supervisor
  -> create Codex / Sol High / Fast OFF risk-analysis thread
  -> deterministic task/plan
  -> create implementation thread on selected route
  -> exhaustive verification
  -> create independent Codex / Sol High / Fast OFF final-review thread
  -> repair/re-review as required
  -> final QA
```

The parent must wait for required stage results and continue automatically. It must not terminate the orchestration turn merely because a worker thread is still running.

---

## 10. Independence Rules

- An implementation worker must not approve its own patch.
- Use a separate first-class thread for independent review.
- Prefer Codex review after Muse implementation.
- Prefer Codex review after Gemini implementation when the change is substantive.
- If Codex itself implemented a change, prefer an Antigravity/Gemini independent review for normal risk, escalating to a separate Codex critical route only when required and clearly treating it as a second independent pass rather than self-approval.
- Do not add a second reviewer merely for ceremony; add diversity where hidden-failure risk justifies it.

---

## 11. Working Tree, Worktrees, and Parallelism

Delegated worker threads must preserve pre-existing local changes.

For one sequential task, workers may share the task branch if only one writing worker is active at a time.

Use a dedicated Poracode Git worktree when:

- multiple writing worker threads run concurrently;
- experiments/alternative implementations are compared;
- a review/fix branch must be isolated from unrelated user work.

Good parallel candidates:

- read-only repository research;
- independent review perspectives;
- backend and frontend work isolated by stable contracts and worktrees;
- independent test/contract analysis.

Prefer sequential execution when workers would edit the same files, depend on one another's unfinished contracts, or touch the same migration/state machine.

Never merge a worktree automatically just because a worker finished. Completion still requires review and QA.

---

## 12. Minimal Thread Bootstrap

A normal Poracode SeatFlow thread can start with:

```text
Use the SeatFlow autonomous orchestration policy and existing repository instructions.

Implement TASK-PXX-YYY end-to-end.
Complete all required delegated stages autonomously and do not require manual prompt relay or manual `continue` messages.
```

The supervisor should read `AGENTS.md`, `.ai/ORCHESTRATOR.md`, `.ai/MODEL_ROUTER.md`, the relevant workflow files, and this integration file, then use first-class Poracode worker threads as defined above.

`@Crossagents` is not required for this normal workflow.

---

## 13. Availability Is Dynamic

Poracode and provider catalogs change over time.

Do not infer availability from this document alone. Inspect the models actually exposed by the installed harness before delegation when selection is uncertain.

Stable logical policy:

```text
Muse-selected work:
Muse Spark 1.3 Free
    -> Muse Spark 1.3 Contributor (OpenCode Go)
    -> MODEL_ROUTER alternative
    -> escalation only after a quality/risk trigger
```

The `MODEL_ROUTER alternative` above is a non-OpenCode harness route unless the router explicitly says otherwise. Do not silently substitute another OpenCode catalog model for unavailable Muse capacity.

Update exact model IDs here only after they have been verified in the current provider catalog.
