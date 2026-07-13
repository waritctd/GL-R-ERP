# Agent Handoff

## Task
Forward-port the new features/fixes from `origin/yang/ticket` tip commit `d9bedef`
(":Fix flow for sales and add flow after generate quotation") onto a fresh branch off `main`,
targeting `main` as next-release work. This is the "post-quotation sales flow": BOT FX auto-fetch,
CEO manual price override, PIECE/SQM unit basis, dual-track post-quotation lifecycle
(`paymentStatus` + `fulfillmentStatus`), xlsx quotation rendering, remaining-invoice document,
migrations V37–V39. `yang/ticket` itself was NOT merged — it is heavily diverged (134/185 commits
apart from `main`) and carries HR-scope commits already independently shipped on `main`. Only this
one commit's sales-stack delta was cherry-picked.

## Branch
`feat/sales-post-quotation-flow`

## Base Commit
`f655b77` (main tip at start of this task — PR #162 merge)

## Current Commit
Branch tip = the `security(sales): drop xlsx dep …` commit below (5 commits on the branch,
not pushed, not merged).

```
<tip>   security(sales): drop xlsx dep + dead public templates, stub mock-mode doc downloads to placeholder blobs
e0c962b docs(handoffs): add handoff for feat/sales-post-quotation-flow
cb99680 fix(sales): keep xlsx dependency — mockApi.js imports it at runtime   (superseded by the tip commit)
116705b fix(sales): repair cherry-pick fallout in TicketStatus and BotFxFetchService
a466e43 :Fix flow for sales and add flow after generate quotation   (cherry-pick of d9bedef)
```

> **Post-review follow-up (branch-tip commit):** Opus reviewed the port and approved it, but flagged
> that `frontend-ci.yml` runs `npm audit --audit-level=moderate` as a hard gate, so keeping the
> high-severity `xlsx` dependency would turn `main`'s CI red. Per the user's decision, `xlsx` was
> **removed** and the now-unused `frontend/public/templates/*.xlsx` deleted (see the updated xlsx
> section below). Commit `cb99680`'s "keep xlsx" decision is therefore superseded — it is left in
> history for traceability but its conclusion no longer holds.

## Agent / Model Used
Claude Opus (orchestrator prompt) running as Sonnet 4.5 implementation agent, plus one
sub-agent (Sonnet) delegated to resolve the `TicketDetailPage.jsx` conflict block-by-block.

## Scope

### In Scope
- Cherry-picking `d9bedef` onto a fresh branch off `main`, resolving conflicts confined to the
  sales/CRM stack.
- Fixing anything needed to make the cherry-pick actually compile/build/test green.
- Deciding whether the new `xlsx` frontend dependency is needed.
- Writing this handoff.

### Out of Scope
- Merging or pushing (explicitly forbidden by the task).
- Any other `yang/ticket` commit or HR-scope feature.
- Flipping `VITE_ENABLE_SALES` (confirmed still `false` by default in `frontend/src/app/features.js`
  — untouched).
- Fixing the pre-existing, unrelated `OvertimeServiceTest.employeesCanSubmitOwnOvertime` date-relative
  test failure (confirmed to fail identically on a clean `main` checkout — flagged as a separate
  spawned task instead, see "Known Risks").

## Pre-flight
Stashed 4 pre-existing staged files unrelated to this task before branching, per instructions:
```
git stash push --staged -m "prefork-uat-mail"
```
Stash ref: `stash@{0}: On main: prefork-uat-mail` (contains `application-uat.yml`,
`LogMailerTest.java`, `SmtpMailerTest.java`, `frontend/.env.production`). **Left stashed, not
restored** — the orchestrator/user handles it. Confirmed present at task end via `git stash list`.

## Cherry-pick: conflicts and how they were resolved

`git cherry-pick d9bedef` produced 4 conflicted files. All conflicts were confined to the sales
stack as predicted; resolved by keeping `main`'s current Tailwind/structural conventions and
layering the new functionality on top (never dropping incoming logic).

1. **`backend/.../ticket/TicketService.java`** — `close()` method. `main` used
   `loadAndVerifyStatus(ticketId, DOCUMENT_ISSUED)` (single-status legacy check); incoming added an
   admin bypass and dual-track close condition (`QUOTATION_ISSUED` + `paymentStatus=FULLY_PAID` +
   `fulfillmentStatus=GOODS_RECEIVED`, alongside the legacy `DOCUMENT_ISSUED` path). Kept incoming's
   dual-path logic (it strictly extends the legacy check), using `requireTicket(...).summary()` plus
   the `isAdmin(actor)` bypass.
2. **`frontend/.../ceoSettings/CeoSettingsPage.jsx`** — FX rate table. `main` already converted this
   table to Tailwind classes (frozen-stack detoken pass); incoming added a new "source" column
   (BOT auto-fetch vs manual) using raw inline styles. Converted the new column to the existing
   design-system `.status-badge status-info` / `.status-badge status-neutral` CSS classes (exact
   color match to the incoming inline hex values) instead of adding new inline styles — this
   actually *fixed* drift rather than adding to it.
3. **`frontend/.../tickets/TicketCreateModal.jsx`** — item quantity fields. Incoming added the new
   PIECE/SQM "unit basis" radio toggle (order by piece count or by square meters) with inline styles;
   converted the whole block to Tailwind utility classes matching the file's existing conventions
   (`m-0`, `text-xs`, arbitrary-bracket values).
4. **`frontend/.../tickets/TicketDetailPage.jsx`** — 9 conflict regions (permissions object, revise
   radio styling, edit-items unit-basis toggle, factory-email header layout, qty/sqm dual display
   x2, CEO price-override UI, propose-mode warning banner). Delegated to a sub-agent with explicit
   instructions to keep `main`'s Tailwind conventions and reuse the `TicketCreateModal.jsx`
   conversion pattern for the analogous "edit" unit-basis toggle. Manually reviewed the full diff
   afterward — verified no incoming functionality (BOT FX display, override-price UI, dual-track
   action buttons/progress steppers, remaining-invoice download) was dropped, and no stray inline
   styles were introduced beyond what the source commit already had for one-off accent colors
   (purple override, amber warning, dual-track step colors) that have no existing design-system
   token equivalent — left as inline styles consistent with how the upstream commit shipped them
   (not a full design pass; out of scope for a forward-port).

Zero conflict markers remained after resolution (verified via `grep` across all touched files).

## Bugs found and fixed post-cherry-pick (compile/build breakage)

The cherry-pick applied but did not build cleanly. Three real issues were found and fixed:

1. **`TicketStatus.DOCUMENT_ISSUED` silently deleted.** The upstream `d9bedef` diff for
   `TicketStatus.java` only removed a *duplicate* declaration that existed on the yang/ticket branch
   (it had `DOCUMENT_ISSUED` declared twice). `main` had exactly one (non-duplicate) copy in the same
   diff context, so the 3-way merge auto-applied the removal to `main`'s only copy — a false-clean
   apply. Broke compilation in `DepositNoticeService.java` and `TicketService.java` (4 errors:
   "cannot find symbol DOCUMENT_ISSUED"). **Fix:** restored the constant.
2. **`BotFxFetchService` constructor-injected `RestClient.Builder`,** which is not auto-configured
   under this project's Spring Boot 4.1 module layout (`RestClientAutoConfiguration` does not exist
   in `spring-boot-autoconfigure-4.1.0.jar`; confirmed via `unzip -l`; no other module provides it
   either — checked `spring-boot-webmvc`, `spring-boot-http-converter`, etc.). This broke **every**
   `@SpringBootTest` context load (`ActuatorHealthIntegrationTest`, `OpenApiDocsIntegrationTest`,
   `SecurityAuthorizationIntegrationTest` — 8 test methods across 3 classes). **Fix:** build the
   `RestClient` directly (`RestClient.builder().build()`) inside the service instead of depending on
   an injected builder bean; no other file in this codebase needs an HTTP client bean, so this is
   self-contained and low-risk.
3. **`xlsx` dependency wrongly dropped from `frontend/package.json`.** Initial `git grep` for
   `from 'xlsx'|require('xlsx')|import .*xlsx` against `frontend/src` returned nothing (grep run at
   the wrong point mid-conflict-resolution before `mockApi.js`'s cherry-picked content was staged),
   so xlsx was dropped per the task's stated decision rule. This broke `vite build`
   ("Rolldown failed to resolve import 'xlsx' from mockApi.js"). Re-checked afterward and confirmed
   `frontend/src/api/mockApi.js:1` has `import * as XLSX from 'xlsx'` and calls `XLSX.write(...)` to
   render quotation/deposit-notice/remaining-invoice xlsx blobs client-side in mock/demo mode
   (`VITE_USE_MOCKS=true`). **Fix:** restored `xlsx@^0.18.5` in `package.json`, regenerated
   `package-lock.json`.

All three fixes are isolated in commit `116705b` (backend) and `cb99680` (frontend dep).

## xlsx dependency decision — FINAL: removed client-side, mock downloads stubbed

**Removed (commit `8d4ccf4`).** `xlsx` (SheetJS) carries an unpatched high-severity npm advisory:
```
xlsx  *
Severity: high
Prototype Pollution in sheetJS - GHSA-4r6h-8v6p-xvw6
SheetJS ReDoS - GHSA-5pgg-2g8v-p4x9
No fix available (npm registry; patched build only on SheetJS's own CDN)
```
`frontend-ci.yml` runs `npm audit --audit-level=moderate` as a **hard CI gate**, so keeping the
dependency would have turned `main`'s CI red on merge.

xlsx was used **only** by `frontend/src/api/mockApi.js` to render quotation / remaining-invoice /
deposit-notice files client-side in mock/demo mode (`VITE_USE_MOCKS=true`). The real, production
flow downloads the backend Apache POI-rendered file
(`QuotationRenderer` / `RemainingInvoiceRenderer` / `DepositNoticeRenderer`), so the client-side
xlsx rendering was demo-only and non-load-bearing.

**What changed:**
- Removed `import * as XLSX from 'xlsx'` and the SheetJS-based helpers (`mockSetCell`,
  `loadXlsxTemplate`, `xlsxBlob`) from `mockApi.js`.
- Replaced the three mock download producers (`buildMockQuotationXlsx`,
  `buildMockRemainingInvoiceXlsx`, and the deposit-notice `downloadXlsx`) with a lightweight
  `text/plain` placeholder `Blob` (`mockDocPlaceholderBlob`) that summarizes the document
  (doc number, date, customer, line items). Each producer keeps its **signature, existence checks,
  and Blob return type** so callers are unaffected — a demo download still yields a valid,
  downloadable file, just a plaintext placeholder instead of a real spreadsheet.
- The unrelated `buildMockQuotationHtml` "PDF preview" producer was untouched (it never used xlsx).
- Removed `xlsx` from `package.json`, regenerated `package-lock.json` (9 packages removed).

**Result:** `npm audit --audit-level=moderate` now exits **0** (`found 0 vulnerabilities`). CI's
audit gate passes. No frontend test asserted on xlsx blob content/type (verified via grep), so no
test needed adjusting; the suite stays green (17 files / 84 tests). The production spreadsheet path
is entirely backend/POI and was never touched by this change.

## Files Changed
Cherry-pick commit `a466e43` (47 files, 2248 insertions / 454 deletions) — matches the source
commit's own diff shape exactly (net-new: `RemainingInvoiceDto/ItemDto/Renderer.java`,
`BotFxFetchService.java`, `PriceBreakdownItemDto.java`, `OverridePriceRequest.java`,
`V37__ticket_item_unit_basis.sql`, `V38__fx_source_and_item_manual_price.sql`,
`V39__ticket_dual_track_status.sql`, backend + frontend xlsx templates). Modified: `TicketService`,
`TicketController`, `TicketRepository`, `TicketItemDto/Request`, `TicketStatus`,
`TicketSummaryDto`, `TicketEventKind`, `QuotationRenderer`, `DepositNoticeService/Controller`,
`FxRateDto/Repository`, `PriceCalcService`, `AppProperties`, `application.yml`,
`HrBackendApplication`, 3 backend test files, and the frontend files listed in the task prompt
(`TicketDetailPage.jsx`, `TicketCreateModal.jsx`, `TicketListPage.jsx`, `CeoSettingsPage.jsx`,
`DepositNoticePage.jsx`, `TicketDashboard.jsx`, `mockApi.js`, `hrApi.js`, `routes.js`,
`demoData.js`, `styles.css`, `package.json`/`package-lock.json`).

Follow-up commit `116705b`: `TicketStatus.java` (+1 line), `BotFxFetchService.java`
(constructor signature + comment).

Follow-up commit `cb99680`: `package.json` (+1 line, restore `xlsx`), `package-lock.json`
(regenerated, +105 lines).

Migrations added: `V37__ticket_item_unit_basis.sql`, `V38__fx_source_and_item_manual_price.sql`,
`V39__ticket_dual_track_status.sql` — confirmed no collision with `db/migration-demo`
(which tops out at `V32__link_demo_ticket_customers.sql`).

## Commands Run
```bash
git stash push --staged -m "prefork-uat-mail"
git switch -c feat/sales-post-quotation-flow
git cherry-pick d9bedef            # 4 conflicts, resolved as above
git cherry-pick --continue         # -> a466e43
# fixed TicketStatus.java + BotFxFetchService.java, rebuilt, committed -> 116705b
# fixed package.json xlsx, npm install, rebuilt, committed -> cb99680
cd backend && ./mvnw -B clean verify     # run 3x across the fix cycle
cd frontend && npm run lint && npm test && npm run build   # run repeatedly across the fix cycle
```

## Test / Build Results
- **Backend (`./mvnw -B clean verify`):** Compiles clean. Testcontainers Postgres ran locally
  (Docker available), migrations apply cleanly through **V39**. **304 tests total, 0 failures,
  1 error** — `OvertimeServiceTest.employeesCanSubmitOwnOvertime` (date-relative pre-existing bug,
  reproduced identically on a clean `main` checkout via `git worktree`; unrelated to this branch,
  see Known Risks). All sales-stack tests pass: `TicketServiceTest` (46/46), `TicketRepositoryIntegrationTest`
  (5/5). Integration tests were **not skipped** — Docker was available locally so the full
  Testcontainers suite ran (contrary to the "likely skipped" expectation in the task prompt).
- **Frontend lint (`npm run lint`):** exit 0. 9 pre-existing `react-hooks/exhaustive-deps` warnings
  (same pattern noted in handoff #37), 0 errors.
- **Frontend tests (`npm test`):** 17 files, 84 tests, all passing (~2.6s).
- **Frontend build (`npm run build`):** succeeds, 1980 modules transformed, all chunks emitted
  including `TicketDetailPage`, `TicketListPage`, `DepositNoticePage`, `CeoSettingsPage`.
- **`npm audit --audit-level=moderate`:** exit 0, `found 0 vulnerabilities` after the `xlsx`
  removal (commit `8d4ccf4`). The CI audit gate in `frontend-ci.yml` now passes. (Before removal it
  reported 1 high-severity `xlsx` finding with no fix available — see xlsx section above.)

Note on flakiness during this session: the very first few `npm test` / `npm run build` attempts
hung or produced truncated output due to a large pile of stray/zombie processes on the machine
(a stuck `git add` from an unrelated design-review hook, several hung `eslint` processes, and
duplicate `vitest` worker pools accumulated across retries) — not a defect in this branch's code.
Confirmed by: (a) running with `--no-file-parallelism`, which passed cleanly every time, and
(b) killing the stray processes, after which plain `npm test`/`npm run build` passed cleanly and
quickly on repeated clean runs.

## Known Risks
1. ~~**`xlsx@0.18.5` has an unpatched high-severity npm advisory.**~~ **RESOLVED** in commit
   `8d4ccf4`: `xlsx` removed, mock-mode downloads stubbed to placeholder blobs, real path uses the
   backend POI renderers. `npm audit --audit-level=moderate` now clean (0 vulnerabilities). See the
   "xlsx dependency decision — FINAL" section above. Residual note: mock/demo document downloads now
   produce a plaintext placeholder rather than a real spreadsheet — acceptable, as this path only
   runs under `VITE_USE_MOCKS=true` (never in production, per `frontend/src/api/index.js`'s guard).
2. **`OvertimeServiceTest.employeesCanSubmitOwnOvertime` is flaky-by-date** and already fails on a
   clean `main` (confirmed via `git worktree`), independent of this branch. Flagged as a separate
   background task (not part of this branch's diff) rather than fixed here, to keep this PR's diff
   scoped to the sales forward-port.
3. **`TicketDetailPage.jsx` still has several inline `style={{...}}` blocks** for one-off accent
   colors (purple price-override UI, amber approval-revision warning, dual-track progress-stepper
   colors) that have no existing `styles.css` token equivalent. Not converted to new design-system
   tokens — this is a forward-port, not a design pass. Flag for a future "impeccable" pass if the
   team wants these formalized as tokens (they are currently ad hoc but functionally fine and
   internally consistent with the same commit's own conventions).
4. **`DOCUMENT_ISSUED` / `QUOTATION_ISSUED` dual-path `close()` logic** in `TicketService.java`
   supports both the legacy single-status ticket lifecycle and the new dual-track lifecycle
   side-by-side. This is inherited directly from the upstream commit's design and was not
   reviewed for business-logic correctness beyond "does it compile and pass existing tests" (per
   CLAUDE.md, business logic changes were not requested and were preserved as authored upstream).
5. **Sales stack remains flag-hidden.** `VITE_ENABLE_SALES` default is unchanged (`false` in
   `frontend/src/app/features.js`) — this forward-port is dormant in the shipped app until the flag
   is flipped in a separate, deliberate decision.

## Recommended Next Agent
Claude Opus (reviewer) — review the diff for: (a) the dual-track lifecycle's business-rule
correctness (payment/fulfillment state machine, especially the `close()` dual-path condition),
(b) whether the `TicketDetailPage.jsx` inline-style patches should be tokenized now or deferred,
(c) whether `BotFxFetchService`'s `RestClient.builder().build()` (vs. an injected/shared bean) is
acceptable long-term or should be revisited if more HTTP-calling services are added later. The xlsx
security-risk item is already **resolved** (dependency removed, commit `8d4ccf4`) — no longer an
open question.

## Exact Next Prompt
```text
Repo GL-R-ERP, branch feat/sales-post-quotation-flow (5 commits on top of main f655b77, NOT pushed,
NOT merged). Read docs/agent-handoffs/00_MASTER_CONTEXT.md, CLAUDE.md, and
docs/agent-handoffs/39_feat-sales-post-quotation-flow.md first.

Review this branch's diff (main..feat/sales-post-quotation-flow) as a reviewer — do not implement
beyond tiny safe fixes. Focus on:
1. TicketService.close()'s dual-path status logic (legacy DOCUMENT_ISSUED vs. dual-track
   QUOTATION_ISSUED+FULLY_PAID+GOODS_RECEIVED) — is the business rule correct and are there gaps
   (e.g. can a ticket get stuck between tracks)?
2. The mock-mode xlsx removal (commit 8d4ccf4): confirm the three placeholder producers in
   mockApi.js still return valid Blobs, callers are unaffected, and nothing else in frontend/src
   still depends on SheetJS. `npm audit --audit-level=moderate` should be clean.
3. TicketDetailPage.jsx's remaining inline styles for the new dual-track/override UI — tokenize now
   or defer?
4. Whether BotFxFetchService building its own RestClient (not injecting a shared bean) is the right
   long-term pattern.

Do NOT flip VITE_ENABLE_SALES. Do NOT touch the stashed prefork-uat-mail stash (4 unrelated files —
that's a separate concern for the user/orchestrator). If you find anything beyond a typo-level fix,
write up findings and hand back to an implementation branch rather than fixing directly. Update this
handoff file with your review findings before finishing.
```
