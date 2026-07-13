# Agent Handoff

## Task
Bring the newly-merged sales/CRM post-quotation flow (from `main` PR #163/#164) into the `uat`
branch by merging `main` into `uat`, resolve any conflicts, make the UAT Flyway config tolerate
the new lower-numbered migrations landing on top of the already-seeded UAT DB, and verify backend
+ frontend build/test green with the sales stack enabled. **Scope is UAT only** — `main` and its
demo flag (`VITE_ENABLE_SALES=false` on main) were not touched. **Not pushed, not merged to
remote** — local commits only, per instructions; the orchestrator reviews and pushes.

## Branch
`uat`

## Base Commit
`360085d` (uat tip at start of this task — "fix(frontend): detoken frozen sales/CRM stack styling")

## Current Commit
`b78ac53` (2 new commits on top of the base, not pushed):
```
b78ac53 fix(uat): enable Flyway out-of-order so main's new V37-39 migrations apply over the seeded UAT DB
9e07396 Merge branch 'main' into uat
```

## Agent / Model Used
Claude Sonnet 5 (implementation agent, per orchestrator prompt).

## Scope

### In Scope
- `git switch uat`, confirm clean start, `git merge main`.
- Resolve any merge conflicts, minimally, preserving both sides' functionality.
- Add `spring.flyway.out-of-order: true` to `application-uat.yml` with an explanatory comment.
- Verify no Flyway version collision between real migrations (V37-39) and the UAT seed (V900-904),
  and that the seed doesn't reference the new columns.
- Run backend (`./mvnw -B clean verify`) and frontend (`npm install && npm run lint && npm test &&
  npm run build`) and confirm green, with the frontend build using `.env.production`
  (`VITE_ENABLE_SALES=true`).
- Write this handoff.

### Out of Scope
- Pushing or merging to any remote (explicitly forbidden — stopped after local commits).
- Touching `main`, or any main-side sales flag.
- Any new ERP feature work.

## Pre-flight — the 4 staged files on `main`'s working tree
Before switching branches, `main`'s working tree had 4 files staged (`application-uat.yml`,
`LogMailerTest.java`, `SmtpMailerTest.java`, `frontend/.env.production`) — leftover from an earlier
session's `git stash push --staged` (see `39_feat-sales-post-quotation-flow.md`). Verified
byte-for-byte identical to `uat`'s already-committed versions (`diff` against `git show
uat:<path>` was empty for all four). After `git switch uat`, they disappeared from the working
tree as expected (git status showed "nothing to commit, working tree clean") — nothing was lost,
nothing needed to be committed or restashed.

## Merge: conflicts and how they were resolved
The task brief expected a conflict-free merge (per an earlier `merge-tree` check), but the actual
merge produced **6 conflicts** — because `uat` had since gained its own commits touching the sales
stack (notably `91a16db` "ticket + deposit-notice events now send email too" and `360085d` "detoken
frozen sales/CRM stack styling", both after the point the merge-tree check was presumably run
against). All conflicts were between `uat`'s already-detokened / notification-service-upgraded code
and `main`'s newer sales features (unit-basis toggle, manual price override, dual-track lifecycle,
BOT FX source column). None were business-logic conflicts requiring a judgment call beyond "take the
newer/superset side and reconcile identifier names."

1. **`backend/.../application.yml`** — `app:` block. Both sides added independent unrelated keys
   (`uat`'s `app.mail.*` block vs `main`'s `app.bot.api-token`). Resolution: kept both.

2. **`backend/.../ticket/TicketService.java`** — `proposePrice()`. `uat`'s side used the older
   single-status `PRICE_PROPOSED` event/notify call; `main`'s incoming side added the dual-track
   revision-aware logic (`eventKind`/`currentStatus` computed from `isRevision`, plus a
   revision-aware Thai notification message). Took `main`'s logic, but called through this file's
   existing private `notifyByRole(...)` helper (which wraps `NotificationService`) rather than a
   nonexistent `notifications` field — a straightforward rename, no logic change.

3. **`backend/.../deposit/DepositNoticeService.java`** — constructor. `uat` had already upgraded
   this service from the raw `NotificationRepository` to the new `NotificationService` (email
   backbone work); `main` independently added a `RemainingInvoiceRenderer` constructor param. Field
   declarations had already auto-merged cleanly (both `notificationService` and `remainingRenderer`
   present); only the constructor body needed reconciling — combined both: keeps
   `NotificationService` (uat's superset) and adds `remainingRenderer` (main's addition). No leftover
   `NotificationRepository` field or import remains (verified via grep).

4. **`frontend/.../ceoSettings/CeoSettingsPage.jsx`** — FX rate table (2 conflict blocks). `main`
   added a "แหล่งข้อมูล" (source) column showing BOT-auto vs manual, using the project's
   `.status-badge status-info` / `.status-badge status-neutral` classes (already token-based, no
   inline styles to convert — this is the same resolution the original sales-flow forward-port used
   for this exact file). Took `main`'s side entirely.

5. **`frontend/.../tickets/TicketCreateModal.jsx`** — item quantity fields (1 conflict block).
   `uat`'s side was the old pre-unit-basis dual-qty inputs; `main`'s side is the newer PIECE/SQM
   toggle. Took `main`'s side entirely (strict feature superset, already using Tailwind classes, no
   inline-style conversion needed here).

6. **`frontend/.../tickets/TicketDetailPage.jsx`** — 6 conflict blocks (edit-mode unit-basis toggle,
   radio className, item qty display x2, CEO manual-price-override UI, propose-mode revision warning
   banner). Same pattern as above in every block: `uat`'s side was pre-unit-basis/pre-override/
   pre-dual-track code, `main`'s side is the strict feature superset. Took `main`'s side entirely in
   all 6 blocks. Verified afterward that every identifier referenced by the taken code (`cn`,
   `can.overridePrice`, `overrideDraft`/`setOverrideDraft`, `overrideLoading`, `handleOverridePrice`,
   `st`) already exists elsewhere in the file (auto-merged cleanly, unrelated to these conflict
   blocks) — nothing dangling.

No conflicts required dropping functionality from either side; every resolution kept the union of
both branches' work.

## Files Changed
- `backend/src/main/resources/application-uat.yml` — added `spring.flyway.out-of-order: true` with
  a comment explaining why (see below).
- `backend/src/main/resources/application.yml` — merge-resolved (`app.mail.*` + `app.bot.api-token`
  both kept).
- `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java` — merge-resolved
  (`proposePrice()` dual-track/revision-aware notify, via existing helper).
- `backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeService.java` — merge-resolved
  (constructor: `NotificationService` + `RemainingInvoiceRenderer` both retained).
- `frontend/src/features/ceoSettings/CeoSettingsPage.jsx` — merge-resolved (BOT source column).
- `frontend/src/features/tickets/TicketCreateModal.jsx` — merge-resolved (unit-basis toggle).
- `frontend/src/features/tickets/TicketDetailPage.jsx` — merge-resolved (unit-basis toggle, manual
  price override UI, revision warning banner).
- Plus the full `main`→`uat` merge diff (204 main-side files: V37-V39 migrations, dual-track ticket
  lifecycle, BOT FX service, remaining-invoice renderer, xlsx removal, etc. — see
  `39_feat-sales-post-quotation-flow.md` for that content's own detailed history).

## Commands Run
```bash
git fetch origin
git switch uat
git pull --ff-only origin uat        # already up to date
git merge main --no-edit             # 6 conflicts, resolved as above
git add <6 resolved files>
git commit --no-edit                 # -> 9e07396
# edited application-uat.yml
git add backend/src/main/resources/application-uat.yml
git commit -m "fix(uat): enable Flyway out-of-order ..."   # -> b78ac53
cd backend && ./mvnw -B clean verify
cd frontend && npm install && npm run lint && npm test && npm run build
npm audit --audit-level=moderate
```

## Test / Build Results
- **Backend (`./mvnw -B clean verify`):** **BUILD SUCCESS**. Docker was available, so Testcontainers
  ran — integration tests were **not skipped**. **326 tests, 0 failures, 0 errors.** Flyway applied
  V1..V39 then V900..V904 in natural numeric order (fresh test DB, so out-of-order wasn't exercised
  here — that path only matters against the real, already-seeded UAT Postgres). Jacoco coverage
  check passed.
- **Frontend lint (`npm run lint`):** exit 0, 0 errors, 9 pre-existing `react-hooks/exhaustive-deps`
  warnings (same set noted in handoff #39 — unchanged by this merge).
- **Frontend tests (`npm test`):** 17 files, 84 tests, all passing (~3.1s).
- **Frontend build (`npm run build`):** succeeds, 1979 modules transformed. Confirmed
  `frontend/.env.production` has `VITE_ENABLE_SALES=true` and the build emitted all sales-stack
  chunks (`TicketDetailPage`, `TicketListPage`, `DepositNoticePage`, `CeoSettingsPage`,
  `CommissionPage`, `TicketDashboard`) — i.e. built with sales **enabled**, as required for UAT.
- **`npm audit --audit-level=moderate`:** exit 0, 0 vulnerabilities (xlsx removal from the sales-flow
  branch carried through the merge cleanly).

## Migration-safety check (per task instructions)
- `V37__ticket_item_unit_basis.sql`, `V38__fx_source_and_item_manual_price.sql`,
  `V39__ticket_dual_track_status.sql` (real, from `main`) vs `V900`-`V904` (UAT seed) — version
  ranges are disjoint, confirmed via `find`/`ls`.
- `grep -inE "payment_status|fulfillment_status|unit_basis|manual_price"` against
  `db/migration-uat/V903__uat_sales.sql` returned **no matches** — the UAT seed does not reference
  any of the new columns, so applying V37-39 out-of-order after V900-904 is safe: no seed row
  depends on the new columns being present at seed time, and V37/38/39 are additive
  (V37 backfills `unit_basis` with a `NOT NULL DEFAULT 'PIECE'`, V38/V39 add nullable columns).

## Decisions Made
- Took `main`'s side fully in every frontend conflict rather than attempting to convert `main`'s
  newer sales-flow inline styles to `uat`'s Tailwind detoken conventions, because — unlike the
  original `main`-side forward-port conflict (which mixed old-Tailwind vs new-inline) — every
  conflicting hunk here was `uat`'s **older, pre-feature** code vs `main`'s **newer, already
  Tailwind-classed** code (the unit-basis toggle and BOT-source column were written with Tailwind
  classes from the start in `main`'s `feat/sales-post-quotation-flow` branch). No inline-style
  conversion was needed or performed. The CEO manual-price-override UI and propose-mode revision
  banner do use inline `style`/hex-literal Tailwind arbitrary values (`text-[#7c3aed]` etc.) — these
  are carried over unchanged from `main`, consistent with handoff #39's note that these are
  deliberately left as-is (forward-port, not a design pass).

## Assumptions
- The Flyway `out-of-order: true` setting is scoped to the `uat` profile only (in
  `application-uat.yml`), so it has zero effect on `main`'s demo/prod profiles.
- The real UAT Render Postgres has in fact already applied V900-V904 (per prior session memory /
  task brief) — this was not re-verified against the live DB in this session (no DB access from
  this sandbox); the safety argument rests on the additive-migration + seed-non-reference checks
  above, which were verified.

## Known Risks
1. **Live-UAT-DB migration application was not tested against the real database** (only against a
   fresh local Testcontainers Postgres, where out-of-order wasn't exercised). The safety argument is
   sound (additive schema changes, seed doesn't reference new columns) but the actual out-of-order
   Flyway run against the already-V904'd UAT DB will only be proven on the real next deploy.
2. **Inherits all risks already logged in `39_feat-sales-post-quotation-flow.md`** for the sales
   flow itself (dual-track `close()` business-rule review still pending, `TicketDetailPage.jsx`
   inline-style tokenization still deferred, `BotFxFetchService`'s per-call `RestClient` pattern
   still unreviewed) — none of that was re-litigated here, this task was purely the uat-side merge.
3. **`uat`'s own recent commits** (notification-email backbone, PRODUCT.md/DESIGN.md docs,
   focus-visible ring, touch-target, tabular-figures fixes) are included in this merge base and were
   not re-reviewed here — they were already on `uat` before this task started.

## Things Not Finished
- Nothing outstanding for this task's scope. Push/merge to remote is intentionally left to the
  orchestrator.

## Recommended Next Agent
Claude Opus (orchestrator) — review the merge diff and the two new commits (`9e07396`, `b78ac53`),
then push `uat` to `origin/uat` to trigger the `gl-r-erp-uat` Render + Vercel redeploy. After
deploy, watch the Render deploy logs for the Flyway migration step specifically (should show V37-39
applying out-of-order after V904 without a `validate()` failure) and smoke-test the sales
nav/routes on the UAT frontend (TKT-*/COM-* flows) against the live seeded data.

## Exact Next Prompt
```text
Repo GL-R-ERP, branch uat (commits 9e07396 + b78ac53 on top of 360085d, NOT pushed). Read
CLAUDE.md, docs/agent-handoffs/00_MASTER_CONTEXT.md, and this handoff
(docs/agent-handoffs/40_uat-merge-main-sales-flow.md) first.

Review the main->uat merge commit (9e07396) and the Flyway out-of-order commit (b78ac53) as a
reviewer. Confirm: (1) no sales-stack functionality was dropped in the 6 resolved conflicts
(diff each of the 6 files against both `git show main:<path>` and the pre-merge `uat` tip 360085d
to confirm), (2) the out-of-order Flyway comment's reasoning holds, (3) backend/frontend
build/test results in the handoff look complete. If satisfied, push `uat` to origin to trigger the
gl-r-erp-uat Render + Vercel redeploy, then watch the Render deploy log for the Flyway migration
step (expect V37, V38, V39 applying out-of-order after V904 with no validate() failure) and smoke
test the sales nav on the hosted UAT frontend.
```
