# Workflow 03: Bug Fixing & Debugging Protocol

**Role:** Fixer / Debugger

---

## 1. Goal & Principles

The Fixer role resolves compiler errors, test failures, concurrency race conditions, and edge-case exceptions while preventing regressions.

### Regression Prevention Rules:
1. **Never delete or disable a failing test:** Fix the underlying production code to satisfy the test contract.
2. **Minimal surgical edits:** Do not rewrite whole classes or refactor unrelated code. Fix only the root cause.
3. **Reproduce before fixing:** If fixing a bug reported without an existing test, write a failing unit/slice test first.

---

## 2. Debugging & Resolution Sequence

```
1. Receive error trace / console output + the relevant source files.
2. Classify the issue:
   - Compilation / Type Error (e.g. MapStruct missing mapping, Spring Boot 4 annotation change)
   - Runtime Exception (e.g. NullPointerException, LazyInitializationException, ConstraintViolation)
   - Business Logic Failure (e.g. Invariant not checked, wrong HTTP status returned)
   - Concurrency Race Condition (e.g. Double booking under parallel load)
3. Check authoritative rules in backend/AGENTS.md or frontend/AGENTS.md.
4. **Write a regression test** reproducing the exact failure (verify the test fails before applying the fix).
5. Apply the targeted fix.
6. Rerun the test suite to verify:
   - The failing test now passes.
   - All existing tests continue to pass.
```
