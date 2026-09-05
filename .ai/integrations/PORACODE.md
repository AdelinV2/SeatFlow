# Poracode / Crossagents Integration

## Purpose

This file maps SeatFlow's harness-agnostic orchestration policy to **Poracode Crossagents**.

Read in this order when Poracode is the active orchestrator:

1. `AGENTS.md`
2. `.ai/ORCHESTRATOR.md`
3. active `.ai/workflows/*.md` protocol
4. `.ai/MODEL_ROUTER.md`
5. this file for Poracode-specific provider/model execution details

Poracode is the execution layer; repository policy remains authoritative.

---

## 1. Required Poracode Capabilities

For autonomous SeatFlow execution, enable the bundled **Crossagents** plugin for the active thread/project.

The parent supervisor may delegate only after the thread has explicitly authorized Crossagents (for example with `@Crossagents`). Once authorized for that thread, continue delegating without repeatedly asking the user for the same permission.

Preferred installed agents:

- **Antigravity** — planning, frontend/browser work, fast independent analysis
- **OpenCode** — Muse implementation/fixing/refactoring route
- **Codex** — independent review, difficult backend judgment, critical correctness route

Do not silently replace a requested provider with another provider merely because Poracode ranking prefers it.

---

## 1.1 Delegation Exclusivity — Poracode Crossagents Only

All inter-agent delegation in the SeatFlow autonomous workflow must use Poracode Crossagents. OpenCode's native task/subagent delegation must not be used inside this workflow unless explicitly requested. An OpenCode agent may freely delegate through Poracode Crossagents, including to other OpenCode models and multiple OpenCode Crossagents concurrently.

---

## 2. Provider Responsibilities

| Logical role | Preferred harness | Notes |
|---|---|---|
| Supervisor / coordinator | Antigravity | Coordination should not consume premium Codex quota by default |
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

## 3. Muse Spark 1.3 Resolution — Mandatory Priority

Whenever `.ai/MODEL_ROUTER.md` selects the logical `MUSE_1_3` route, Crossagents must resolve it in this exact order.

### Priority 1 — OpenCode Zen Free

- Provider/harness: `OpenCode`
- Display name: `Muse Spark 1.3 Contributor Free`
- Verified Poracode/OpenCode selection ID: `opencode/muse-spark-1.3-contributor-free`
- Cost priority: **first choice whenever available and privacy policy allows it**

### Priority 2 — OpenCode Go

- Provider/harness: `OpenCode`
- Display name: `Muse Spark 1.3 Contributor`
- OpenCode Go model ID: `opencode-go/muse-spark-1.3-contributor`
- Use when the Free route is unavailable, rate-limited, disabled, not listed in the current catalog, or fails with a provider-capacity/availability error.

The Free -> Go fallback is automatic and does **not** require user confirmation.

### Priority 3 — Task-specific alternative

If both Muse 1.3 routes are unavailable or prohibited by privacy constraints, use the **Alternative** route defined by `.ai/MODEL_ROUTER.md` for that task. For most deterministic implementation this is Gemini 3.8 Flash High through Antigravity.

### What counts as unavailable

Treat the Free route as unavailable only when one of these is true:

- the model is absent from Poracode/OpenCode's current model catalog;
- provider reports unavailable/disabled/capacity error;
- Free route is rate-limited/quota-blocked;
- Crossagents cannot select the exact model;
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

## 4. Muse Privacy Guardrail

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

## 5. Codex Standard-Speed Policy — Fast OFF

**Codex Fast mode is disabled by default for every SeatFlow delegation.**

For Codex stages specify:

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
3. Never accept Poracode's learned routing if it silently turns Fast on.
4. Fast may be enabled only when the user explicitly requests it for that run/stage.
5. If Crossagents cannot guarantee `Fast = OFF`, stop that Codex delegation and report the limitation instead of silently consuming accelerated quota.
6. Record `FAST MODE: OFF` in the stage handoff when Codex is used.

This rule applies to Luna, Terra, and Sol.

---

## 6. Exact-Selection / No-Silent-Substitution Rule

Before launching a child agent, the supervisor should request an exact provider/model/effort configuration.

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
```

---

## 7. Recommended Poracode Routing Pins

If Poracode's Crossagents settings support task-type pins, prefer these mappings:

```text
planning / architecture (routine) -> Antigravity
frontend / browser / visual       -> Antigravity
implementation / backend          -> OpenCode
implementation / general          -> OpenCode
bug-fix / repair                   -> OpenCode
refactor execution                 -> OpenCode
code-review                        -> Codex
critical-review                    -> Codex
backend-root-cause                 -> Codex
```

Do not rely on pins alone. The supervisor must still resolve the exact model/effort through `.ai/MODEL_ROUTER.md`.

Keep unrelated providers out of automatic routing unless intentionally used as alternatives/diversity routes.

---

## 8. Parent Supervisor Behavior

Recommended parent harness: **Antigravity**.

The parent should coordinate rather than implement the entire task itself.

For a standard backend task:

```text
Antigravity supervisor
  -> classify task
  -> optional Antigravity planning
  -> OpenCode / Muse Free if available
       -> otherwise OpenCode Go / Muse Contributor
  -> Codex / Terra High / Fast OFF independent review
  -> OpenCode / Muse route for clear fixes
  -> Codex re-review when required
  -> QA route selected by risk/independence
```

For a critical task:

```text
Antigravity supervisor
  -> Codex / Sol High / Fast OFF risk analysis
  -> deterministic task/plan
  -> OpenCode / Muse route implementation when safe/testable
  -> exhaustive verification
  -> Codex / Sol High / Fast OFF final review
  -> repair/re-review
  -> final QA
```

---

## 9. Independence Rules

- An implementation child must not approve its own patch.
- Prefer Codex review after Muse implementation.
- Prefer Codex review after Gemini implementation when the change is substantive.
- If Codex itself implemented a change, prefer an Antigravity/Gemini independent review for normal risk, escalating to a separate Codex critical route only when required and clearly treating it as a second independent pass rather than self-approval.
- Do not add a second reviewer merely for ceremony; add diversity where hidden-failure risk justifies it.

---

## 10. Working Tree and Worktrees

Crossagents must preserve pre-existing local changes.

For one sequential task, agents may share the task branch if only one writing agent is active at a time.

Use Poracode Git worktrees when:

- multiple writing agents run concurrently;
- experiments/alternative implementations are compared;
- a review/fix branch must be isolated from unrelated user work.

Never merge a worktree automatically just because a subagent finished. Completion still requires review and QA.

---

## 11. Minimal Thread Bootstrap

A new Poracode SeatFlow thread can start with:

```text
@Crossagents

Use the SeatFlow autonomous orchestration policy.
Read AGENTS.md, .ai/ORCHESTRATOR.md, .ai/MODEL_ROUTER.md, the relevant workflow files, and .ai/integrations/PORACODE.md.

Implement TASK-PXX-YYY end-to-end.
Do not require manual copy-paste between agents while Crossagents is available.
```

The repository files should provide the remaining routing and workflow behavior.

---

## 12. Availability Is Dynamic

Poracode and OpenCode catalogs change over time.

Do not infer availability from this document alone. Query/inspect the models actually exposed by the installed harness before delegation when selection is uncertain.

Stable logical policy:

```text
Muse-selected work:
Muse Spark 1.3 Free
    -> Muse Spark 1.3 Contributor (OpenCode Go)
    -> MODEL_ROUTER alternative
    -> escalation only after a quality/risk trigger
```

The `MODEL_ROUTER alternative` above is a non-OpenCode harness route. Do not substitute another OpenCode model (GLM, Hy3, Hy4, or any other OpenCode catalog model) for unavailable Muse capacity.

Update exact model IDs here only after they have been verified in the current provider catalog.
