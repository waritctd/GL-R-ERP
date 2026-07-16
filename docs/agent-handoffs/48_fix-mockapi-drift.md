# Agent Handoff

## Task
Fix `frontend/src/api/mockApi.js` drift from the real Spring backend (issue #201 and its sub-issue
#199), sections 1–4 of the approved plan:
1. Give overtime its own permission gate (`canReviewOvertime`) with no hr/admin bypass.
2. Implement the five missing `priceImport`/`catalog` mock methods + fix the shared sequence collision.
3. Add `frontend/src/api/contract.test.js` as a regression guard on the mock's method surface.
4. Write the mock's contract down (mockApi headers, CLAUDE.md, this handoff).

Section 5 (filing follow-up issues) was explicitly **out of scope** — retained by the requester.
Those issues are now filed: **#205** (lock down price import to `ceo`+`import` — unguarded at nav,
URL route, and backend) and **#206** (the ~18 remaining mock authz divergences).

## Branch
`claude/item-201-885dff`

## Base Commit
`1a535df0297f9da3cfc106171921736451d869e9`

## Current Commit
Not committed — changes left in the working tree for review, as instructed.

## Agent / Model Used
Claude Sonnet (implementation) — orchestrated and reviewed by Claude Opus 4.8.

## Scope

### In Scope
- The overtime permission gate in `mockApi.js` (five call sites).
- The five missing mock methods + `mockPriceVersionSeq` / `mockPriceImportFactorySeq`.
- The contract test.
- Documentation of the mock contract.
- Deleting the dead `users` namespace from `mockApi.js`.

### Out of Scope (deliberately untouched)
- **Any permission change in either direction beyond the overtime gate.** The new catalog/priceImport
  mutation mocks are `requireSession()` only, mirroring the backend **as it is today** — verified by
  reading `CatalogController.java` and `PriceImportController.java` in full: every mutation endpoint
  calls only `sessions.requireUser(session)` with no role gate anywhere in the Java call chain.
  A mock stricter than production is still drift. §5a of the plan tightens mock and backend together
  in a later branch.
- The ~18 other known mock authz divergences (`admin`/`director`/`supervisor` roles that don't exist
  in `ApplicationRoles.java`; `payroll.*`/`attendance.*` gating on session only; FX config).
- Backend code — no Java was changed. #199 is a mock-fidelity bug; the backend is already correct.
- Pre-existing `design-system-color` hook findings in `mockPreviewHtml()` (`mockApi.js` ~L266-286):
  inline hex in a generated document-preview fixture string, untouched by this work, out of scope.

## Files Changed
- `frontend/src/data/demoData.js`
  - Added one demo user (`id: 10`, `warehouse.manager@glr.co.th`, role `employee`, employeeId 6 =
    WHL division manager `employees[5]`, ผู้จัดการฝ่าย). Resolves Known Risk 1: the seeded stage-1 OT
    approval (OT#1) is demoable again. Role is `employee` on purpose — a division manager's OT-review
    authority is derived from the org chart (`positionTh === 'ผู้จัดการฝ่าย'` → `publicUser` sets
    `manager: true`), not from a special role. Not `supervisor`/`director` (phantom roles #206 removes).
- `frontend/src/api/mockApi.js`
  - Added a file-level contract banner (shapes faithful / authz **not** authoritative).
  - Added `canReviewOvertime()` next to `canReviewLeave()` — mirrors `OvertimeService.managesEmployee()`,
    no hr/admin bypass.
  - Swapped `canReviewLeave` → `canReviewOvertime` at **five** overtime call sites: `overtime.list`
    scoping, `overtime.create`, `overtime.approve`, `overtime.reject`, `overtime.cancel`. Leave's own
    six `canReviewLeave` call sites are untouched.
  - Added a comment at `overtime.create` citing `OvertimeService.submit()` → `resolveTargetEmployee()`.
  - Deleted the dead `users` namespace (3 methods; no hrApi counterpart, zero call sites).
  - Added `mockPriceVersionSeq` (versions) separate from `mockProductPriceSeq` (price rows), and
    pointed `priceImport.upload` at it — it previously minted version IDs from the *product price*
    counter.
  - Added `mockPriceImportFactorySeq`.
  - Extracted `activateVersion()` from `priceImport.commit` (ACTIVE/ARCHIVED transition) so
    `commit` and `uploadAndCommit` share one implementation; added `factoryNameFor()`.
  - Implemented `priceImport.createFactory`, `priceImport.uploadAndCommit`, `catalog.addProduct`,
    `catalog.updateProduct`, `catalog.deleteProduct`.
  - Added a `// Mirrors <JavaClass>` source-of-truth header above all 19 namespaces.
- `frontend/src/api/contract.test.js` (new)
  - Asserts every `hrApi` method exists in `mockApi` (minus a documented `KNOWN_GAPS` entry for
    `documents`), that `mockApi` has no dead methods, and that every `KNOWN_GAPS` entry is real and
    carries a reason.
- `CLAUDE.md`
  - New "Mock API contract — shapes are faithful, authz is not" section.
- `docs/agent-handoffs/48_fix-mockapi-drift.md` (new) — this file.

## Commands Run
```bash
cd frontend && npm ci                                  # node_modules absent in this worktree
cd frontend && npx vitest run src/api/contract.test.js # BEFORE §2 — expected to fail
cd frontend && npx vitest run src/api/contract.test.js # AFTER §2 — expected to pass
cd frontend && npm run lint
cd frontend && npm test
cd frontend && npm run build
```

## Test / Build Results
All observed, not assumed.

- **Contract test is a genuine guard — confirmed both directions:**
  - **Before** §2: `FAIL — 2 failed | 1 passed`. Caught exactly the real gaps:
    `catalog.addProduct`, `catalog.updateProduct`, `catalog.deleteProduct`,
    `priceImport.createFactory`, `priceImport.uploadAndCommit` missing; and
    `users.list`, `users.create`, `users.update` dead.
  - **After** §2 + `users` deletion: `PASS — 3 passed`.
- **Lint:** pass — exit 0, 0 errors, 10 warnings. All 10 are pre-existing
  `react-hooks/exhaustive-deps` warnings in files this branch does not touch
  (TicketDashboard, DepositNoticePage, DocumentPage, PayrollPage, TicketDetailPage, TicketListPage).
- **Frontend tests:** pass — 19 files, 91 tests, 0 failures (2.92s).
- **Frontend build:** pass — `✓ built in 138ms`.
- **Backend tests:** not run — no Java changed.
- **Typecheck:** N/A — plain JS project, no `typecheck` script. No typecheck was run or claimed.

### Behavioural verification (throwaway script, run then deleted — not in the diff)
Driven directly against `mockApi.js`, since `OvertimePage.jsx`'s own `managesRequest()` gate already
hides the approve button from HR and so the UI alone cannot exercise the API rule:
- HR (`hr@glr.co.th`, employeeId 21) approving the seeded SUBMITTED OT request → **403 Forbidden**
  (was ALLOWED before — the #199 bug, now matching production).
- HR filing OT on another employee's behalf → **403 Forbidden** (the newly-verified call site).
- Division manager (`director@glr.co.th`, employeeId 1, positionTh ผู้จัดการฝ่าย) files OT on behalf of
  a same-division employee → OK `SUBMITTED`; approves → OK `MANAGER_APPROVED`; CEO approves →
  OK `APPROVED`; manager still sees the request in `overtime.list`. **The gate is not a blanket deny.**
- `priceImport.createFactory('Scratch Ceramica','it','eur')` → `{factoryId:10, country:'IT',
  defaultCurrency:'EUR'}`; blank name → 400 `ชื่อโรงงานห้ามว่าง`.
- `priceImport.uploadAndCommit(1, file, 'Q3 2026')` → `{versionId:100, parsedRows:3, committedRows:3,
  retainedRows:3, errorCount:0, errors:[]}`. **`retainedRows` is non-zero**, so the "คงราคาเดิมไว้" tile
  in `UploadResultCard` is now exercisable — it never could be before. Factory 1's versions went
  `[[1,'ACTIVE']]` → `[[1,'ARCHIVED'],[100,'ACTIVE']]`, exactly one ACTIVE.
- Sequence collision fixed: versionId 100 vs max priceId 102 — drawn from separate counters.
- `catalog.addProduct/updateProduct/deleteProduct` → `added`/`updated`/`deleted`; missing `factoryId`
  or `price` → 400; unknown `priceId` → 404; `updateProduct` leaves `factoryId` unchanged (matching
  `PriceImportService.updateProduct`, which does not touch it).

**Not done: browser verification in mock mode.** Per the repo memory note, `preview_start` reads the
**primary** repo's tracked `.claude/launch.json`, not the worktree's, so previewing this branch means
editing a tracked file in the primary repo — outside this worktree, which the task scoped me to. The
direct-API verification above covers the same assertions the browser pass would have (it is in fact
stricter for the overtime rule, which the UI hides). A reviewer wanting the visual pass should point a
temp `frontend-mock` config at this worktree path.

## Decisions Made
- **`overtime.create` is in scope — five call sites, not four.** The plan asked me to verify rather
  than guess. `OvertimeService.submit()` → `resolveTargetEmployee()` (OvertimeService.java:262-269)
  calls the same `managesEmployee()` helper as `requireManager()` and has **no hr/admin bypass**
  ("Employees can only request their own overtime"). So HR cannot file OT on someone's behalf in
  production either. Swapped, with a comment citing the Java method. This was folded into the
  approved plan.
- **Left the `includeAll` (`hr`/`ceo`/`admin`) check above `overtime.list` alone** — that is list
  *visibility*, a separate concern from review authority, and matches `OvertimeService.canViewAll()`
  (`VIEW_ALL_ROLES = {hr, ceo}`). Note the mock also grants `admin` there; that is one of the ~18
  known divergences deferred to the §5b sweep, not introduced here.
- **Deleted the `users` namespace rather than exempting it** — per the plan, this means the reverse
  assertion needs no exemption list at all.
- **`uploadAndCommit` fabricates its parsed rows.** The mock cannot parse a real `.xlsx` in the
  browser; `upload()` already makes the same simplification with a hardcoded `parsedRows: 12`.
  `retainedRows` counts the factory's pre-existing products, which is the closest honest stand-in for
  `commit()`'s incremental merge (old products not matched by the new file are carried forward).
- **`priceImport.commit` now returns a real `archived` count** instead of a hardcoded `1`, as a
  natural consequence of extracting `activateVersion()`. Minor fidelity improvement; no consumer
  reads that field.
- Both sequences start at 100 and can therefore both yield e.g. `100`. That is correct: versions and
  prices are independent ID spaces in the real DB too (`price_list_versions.version_id` vs
  `product_prices.price_id`), and the seed already has version 1 and price 1 coexisting.

## Assumptions
- The mock's `managerIdForEmployee()` (division scan for `positionTh === 'ผู้จัดการฝ่าย'`) is an
  acceptable approximation of Java's direct-report-or-division-manager rule. Per the plan: "don't
  rebuild it here."
- `KNOWN_GAPS.documents` is correct: `DocumentPage.jsx` is the namespace's only consumer and is
  never imported or routed anywhere in `frontend/src` (verified by grep).

## Resolved
- **Known Risk 1 (seeded OT stage-1 approval was un-demoable) — RESOLVED.** The product owner chose to
  add a demo login for the WHL division manager. `frontend/src/data/demoData.js` now seeds
  `warehouse.manager@glr.co.th` (id 10, role `employee`, employeeId 6 = `employees[5]`, ผู้จัดการฝ่าย,
  division WHL). Verified end to end (throwaway vitest, run then deleted): the new user's
  `publicUser.manager` is `true` (derived from positionTh, not role); `overtime.approve(OT#1)` moves
  **SUBMITTED → MANAGER_APPROVED without a 403**; CEO then completes stage 2 **→ APPROVED**; and HR
  approving OT#1 **still returns 403** (unchanged). `permissions.test.js` (19 tests) still passes and no
  test asserts a demo-user count, so the extra persona is safe.

## Known Risks
1. `mockProductPrices` / `mockPriceImportFactories` / `mockPriceImportVersions` are module-level
   arrays reset on reload — `addProduct`/`createFactory`/`uploadAndCommit` do not persist across a
   refresh. Consistent with the rest of the mock; not a defect.
2. The contract test asserts **names only**. It cannot catch a mock whose DTO *shape* or *authz* has
   drifted — which is exactly the #199 class of bug. That is why the CLAUDE.md wording matters: the
   test is not a substitute for reading the Java service.
3. `catalog.updateProduct` nulls `currency`/`priceUnit` when the caller omits them. This is faithful
   to `PriceImportService.updateProduct` (plain `SET`, no `COALESCE`), and `ProductFormModal` always
   sends both, so it is not reachable from the UI — but it is a sharp edge if called directly.

## Things Not Finished
- Browser/visual verification in mock mode (see Test Results for why, and how a reviewer can do it).
- Section 5 of the plan (follow-up issues) — deliberately left to the requester.
- Nothing committed or pushed, per instructions.

## Recommended Next Agent
Claude Opus review, then the requester files the §5 issues.

## Exact Next Prompt
```
Review the uncommitted working-tree changes on branch `claude/item-201-885dff` (worktree
.claude/worktrees/item-201-885dff) against docs/agent-handoffs/48_fix-mockapi-drift.md and the
plan at ~/.claude/plans/take-a-look-at-dreamy-honey.md (sections 1-4 only; section 5 is not
this branch's work).

Focus your review on:
1. The five overtime call sites in mockApi.js now use canReviewOvertime and the six leave call
   sites still use canReviewLeave — confirm none crossed over.
2. That no permission moved in any direction other than the overtime gate. In particular confirm
   catalog.addProduct/updateProduct/deleteProduct and priceImport.createFactory/uploadAndCommit are
   requireSession() only, matching CatalogController.java and PriceImportController.java as they
   are today.
3. Known Risk 1 in the handoff: the seeded OT demo flow can no longer be approved by any demo
   persona, because the seeded request's manager (employeeId 6) has no user account. Decide whether
   the demo data should gain a user for employee 6, or whether the OT demo should be re-scripted
   around director@/ceo@ and a SAL-division employee. This is a product-data decision, not a code bug.
4. Whether contract.test.js's KNOWN_GAPS exemption for `documents` should instead be closed by
   implementing the 8 missing mock methods now.

Do not commit or push without the requester's say-so.
```
