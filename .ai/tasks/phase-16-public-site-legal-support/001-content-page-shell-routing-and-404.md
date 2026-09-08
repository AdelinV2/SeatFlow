# TASK-P16-001: Build Public Content Page Shell, Routing Foundation, and Real 404

## 1. Task Metadata

- **Task ID:** `TASK-P16-001`
- **Git Branch:** `feat/p16-001-public-content-shell-404`
- **Target Module:** `frontend`
- **Phase:** `Phase 16 - Public Site Completion, Legal & Support`
- **Depends On:** Phase 15 complete enough that public navigation/routes on `develop` are stable
- **Related Specs:** `.ai/tasks/phase-16-public-site-legal-support/000-phase-overview.md`, `.ai/architecture/09-post-mvp-evolution.md`, `.ai/architecture/07-frontend-specification.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `3`
- **Failure Risk:** `Medium`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Standard`
- **Preferred Workflow:** `standard`
- **Affected Critical Invariants:** `public-route reachability; no auth regression; no silent wildcard redirect; reusable visual language; no broken router build`

---

## 2. Objective

Create the reusable public informational-page shell that Phase 16 legal/support/status/API-doc pages will use, then replace SeatFlow's current wildcard redirect-to-home behavior with a real not-found route and component.

This task must leave the application buildable and usable by itself. It must **not** add route entries for legal/support pages whose components do not exist yet; P16-002 through P16-004 add those routes atomically with their pages.

---

## 3. Current-State Problem

`frontend/src/app/app.routes.ts` currently ends with:

```ts
{
  path: '**',
  redirectTo: '',
}
```

This causes malformed links, removed pages, and unimplemented footer destinations to appear as successful navigation to the home page. That behavior is especially harmful for legal/privacy URLs because users cannot distinguish a missing disclosure from a valid route.

Phase 16 needs a real public content system with consistent typography, accessible structure, and deliberate navigation.

---

## 4. Scope

### 4.1 Reusable informational content shell

Create a standalone reusable shell/layout for long-form public pages.

Recommended inventory:

- `[NEW]` `frontend/src/app/shared/layout/content-page/content-page.component.ts`
- `[NEW]` `frontend/src/app/shared/layout/content-page/content-page.component.html`
- `[NEW]` `frontend/src/app/shared/layout/content-page/content-page.component.spec.ts`
- `[NEW]` `frontend/src/app/shared/layout/content-page/content-page.model.ts` only if typed navigation/metadata warrants a separate model

The shell must support:

- page eyebrow/category label;
- `h1` title;
- concise lead/summary;
- optional `lastUpdated` display;
- projected body content;
- optional in-page table of contents / anchor links;
- optional callout panel for demo/legal caveats;
- consistent max width and readable line length;
- light/dark/system theme through the existing design tokens;
- responsive spacing without a separate legal-site theme.

Do not build a Markdown renderer, CMS, dynamic HTML injection, or remote content system. Phase 16 content is version-controlled Angular content.

### 4.2 Real 404

Create:

- `[NEW]` `frontend/src/app/features/public/not-found/not-found.component.ts`
- `[NEW]` `frontend/src/app/features/public/not-found/not-found.component.html`
- `[NEW]` `frontend/src/app/features/public/not-found/not-found.component.spec.ts`
- `[MODIFY]` `frontend/src/app/app.routes.ts`

Replace the wildcard redirect with a lazy-loaded not-found component.

The page should:

- clearly state that the requested page was not found;
- avoid displaying the full URL if it could expose sensitive query/path data in screenshots;
- provide deliberate router links to `/` and `/events`;
- preserve the global header/footer where the app shell normally renders them;
- be usable by signed-out users;
- not auto-redirect after a timeout.

---

## 5. Routing Contract

At the end of this task:

- existing valid routes retain their current guards and behavior;
- `**` renders the not-found component;
- no legal/support/status/API-doc route is added unless its component is implemented in the same task;
- no informational route is put behind `authGuard`, `adminGuard`, or `staffGuard`;
- route ordering continues to protect concrete routes from wildcard capture.

Do not move or weaken existing admin/staff/profile guards while reorganizing route declarations.

If route definitions are grouped for readability, preserve lazy imports and guard semantics exactly.

---

## 6. Accessibility Contract

The reusable shell and 404 must satisfy the following:

- exactly one visible primary `h1` per page;
- section headings follow logical hierarchy (`h2` then `h3` where required);
- table-of-contents links are keyboard reachable and have meaningful text;
- anchor targets have focus/scroll behavior that does not hide the heading behind sticky navigation;
- landmarks use semantic elements (`main`, `nav`, `aside`) where appropriate;
- callouts do not rely only on color;
- focus is moved/restored appropriately after client-side route navigation according to the app's existing Angular accessibility pattern;
- text/link contrast continues to use existing design tokens rather than hardcoded legal-page colors;
- `prefers-reduced-motion` is respected if anchor scrolling is animated.

Do not introduce a new accessibility dependency just for this phase unless the existing stack cannot satisfy a concrete requirement.

---

## 7. Visual / UX Rules

- Reuse SeatFlow's existing header, footer, cards, spacing tokens, typography, and theme service.
- Legal/support pages should look intentionally part of SeatFlow, not like a second website.
- Optimize body text for reading rather than dashboard density.
- Long content must remain readable around 320px mobile width and common desktop widths.
- Avoid giant hero sections on legal/support pages.
- Keep public pages functional without authentication state.
- Do not display user profile data in the content shell.

---

## 8. SEO / Metadata Boundary

P16-005 owns the final route-title/meta-description pass for all Phase 16 pages. P16-001 should only create any minimal reusable metadata primitive if needed by the shell/404 implementation.

Do not duplicate competing title/meta services in later tasks.

The 404 should be marked `noindex` if the existing application metadata architecture can do so cleanly; otherwise record it for P16-005 rather than adding a fragile custom DOM hack.

---

## 9. Failure Modes to Prevent

- wildcard still silently redirects to `/`;
- adding Phase 16 routes before their components exist breaks `ng build`;
- informational shell depends on authenticated user context and crashes signed-out;
- route refactor accidentally removes `adminGuard`/`staffGuard`/`authGuard`;
- page body becomes raw `[innerHTML]` with unsanitized content;
- anchor navigation creates duplicate IDs;
- mobile content overflows because examples/URLs do not wrap;
- theme shell writes new browser-storage keys unrelated to the existing theme service;
- 404 leaks query parameters, auth callback fragments, tokens, or raw exception details.

---

## 10. Tests

Mandatory focused tests:

1. unknown route resolves to `NotFoundComponent`, not `/`;
2. `/` and `/events` retain their existing components/behavior;
3. protected route declarations retain their guards after route edits;
4. 404 exposes working router links to Home and Events;
5. content-page shell renders projected sections and optional TOC correctly;
6. TOC anchor IDs are unique for the supplied entries;
7. shell renders without an authenticated user;
8. shell/404 render in both existing light and dark theme modes without creating a new storage key;
9. no `[innerHTML]`-based legal content path is introduced unless content is compile-time trusted and justified;
10. `ng build` succeeds with only P16-001 changes applied.

---

## 11. Acceptance Criteria

- [ ] Reusable public informational page shell exists and is covered by tests.
- [ ] Shell supports title, lead, optional update date, body projection, and optional TOC.
- [ ] Existing SeatFlow design/theme system is reused.
- [ ] Wildcard routing no longer redirects to home.
- [ ] Unknown URLs render a meaningful public 404.
- [ ] Existing guarded routes preserve their security guards.
- [ ] P16-001 does not create broken placeholder legal/support routes.
- [ ] Public shell and 404 are keyboard/mobile friendly.
- [ ] No new cookie/localStorage/sessionStorage state is introduced by the shell.
- [ ] Frontend build and focused tests pass.

---

## 12. Verification

Use the repo's current frontend commands. At minimum:

```bash
cd frontend
npm test -- --watch=false
npm run build
```

Also perform one manual router smoke test:

```text
/does-not-exist -> real 404
/                -> event list/home
/events          -> event list
/admin            -> existing admin guard behavior
/profile/tickets  -> existing auth guard behavior
```

Phase 17 owns the final full-system/E2E regression gate; P16-001 still owns all regressions caused by its route refactor.
