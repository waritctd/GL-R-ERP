# Agent Handoff

## Task
Redesign the downloadable payslip PDF to match the company HR template (`slip.xls`, provided by
the user) so every employee — including HR — can download a clean, employee-facing payslip. The
download plumbing already existed; the ask was purely the visual/layout redesign. A follow-up ask
removed the black box/border lines from the two-column grid. A final ask reconfirmed HR's ability
to download each employee's payslip separately, then required an independent Opus validation
before merging to `main` and syncing to `uat`.

## Branch(es)
- `feat/payslip-pdf-redesign` → merged to `main` via PR #302
- `sync/payslip-pdf-redesign-to-uat` → merged to `uat` via PR #304 (cherry-pick of the same commit)

## Base Commit
`c3749a62a9dd0bfe61e44b8d67b8172fed1e14d2` (origin/main, before this work)

## Current Commit
- `main`: `748f94f91d146189ac87f72a7c850a8aab294d60` (merge of PR #302; content commit `1514a5ce`)
- `uat`: `0b3c246aa2c59746539516dc21d0d88eff85b6b2` (merge of PR #304; cherry-picked commit `b6ee45e`)

## Agent / Model Used
Claude Opus 4.8 (design + initial implementation), Claude Sonnet 5 (border-removal follow-up +
merge/sync orchestration), Claude Opus 4.8 (independent validation pass, see below).

## Scope

### In Scope
- Rewrite `PayslipRenderer.toPdf` to a two-column employer layout matching `slip.xls`, then made
  borderless per follow-up request.
- Add small, additive layout primitives to the shared `PdfDocumentWriter` (later trimmed back to
  only what's used once the border was removed).
- Update the payslip renderer tests + the persisted-payslip integration test to the new layout.
- Merge to `main`, scoped sync to `uat`.

### Out of Scope
- No payroll math, DTO fields, endpoints, authorization, or DB schema changed — **rendering only**.
- No frontend change: the self-service "สลิปเงินเดือน" tile, HR per-row download, `/payslip/me`
  endpoint, and email distribution already existed and are untouched — independently reconfirmed
  (see "HR per-employee download" below).
- **Full main↔uat historical reconciliation was explicitly NOT done.** `main` and `uat` had
  diverged by 21 vs 89 commits before this work; only this specific payslip change was
  cherry-picked onto `uat`, not a full sync of all unmerged commits either direction. Flagging this
  in case a full reconciliation was actually wanted — it's a separate, larger task.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/common/PdfDocumentWriter.java` — added `textCenter(...)`,
  `cursorY()`, `moveTo(y)`. All additive; existing callers/behavior (deposit, quotation,
  remaining-invoice renderers) unchanged. (A box-drawing `line(x1,y1,x2,y2)` primitive was added in
  the first revision and then deleted again once the border was removed — no dead code left behind,
  confirmed by grep during the Opus validation pass.)
- `backend/src/main/java/th/co/glr/hr/payroll/PayslipRenderer.java` — full rewrite of `toPdf` to the
  `slip.xls` layout: centered company header (`บริษัท จีแอลแอนด์อาร์แทปส์แอนด์ไทลส์ จำกัด`) +
  `สลิปเงินเดือน`; ID/date + name/department rows; a **borderless** two-column
  `รายการได้ / รายการหัก` layout separated by whitespace (`COL_GAP`); per-side totals; bold
  `เงินรับสุทธิ` line; `ผู้รับเงิน / วันที่` signature. Date renders `31/พ.ค./2569` (period-end, Thai
  abbreviated month + B.E. year). Only non-zero lines print; OT/commission/director surface when
  non-zero.
- `backend/src/test/java/th/co/glr/hr/payroll/PayslipRendererTest.java` — replaced old-format
  assertions with two-column layout + footing checks; added a second test covering non-taxable
  income and the leave-refund credit line.
- `backend/src/test/java/th/co/glr/hr/payroll/PayrollPersistedPayslipIntegrationTest.java` — updated
  the DB-reread assertions from the old single-column text to the new layout/totals.

## Footing contract (the correctness property)
Figures are read verbatim from `PayrollLineDto`; no math happens in the renderer. The printed
totals are defined so the columns always foot to net, independently re-derived from
`PayrollCalculator` during the Opus validation pass:
- `รวมรายได้` = `grossEarnings + nonTaxableIncome` (every money-in component gets an earnings row)
- `รวมรายหัก` = `totalDeductions` (every deduction gets a row; `leaveDeductionRefund` prints as a
  negative credit line so the column still sums to `totalDeductions`)
- `เงินรับสุทธิ` = `รวมรายได้ − รวมรายหัก` = `netPay`, exact by construction.
(One inherent, pre-existing edge noted by the Opus review: `netPay` has a `.max(ZERO)` floor in
`PayrollCalculator`, so in a pathological negative-net scenario the printed columns wouldn't foot
to the clamped zero. Not realistic for a real payslip and not introduced by this change.)

## HR per-employee payslip download — confirmed, not new work
This capability already existed prior to this session and was independently reconfirmed twice
(once during initial implementation, once during the Opus validation pass) to still be intact and
untouched by the diff:
- `PayrollController.java`: `GET /{periodId}/lines/{lineId}/payslip.pdf`, gated
  `@PreAuthorize("hasAnyRole('HR','CEO')")`, takes an explicit `lineId` (HR is not limited to their
  own line).
- `PayrollService.payslipPdf(periodId, lineId, actor)` looks up the specific requested line and
  renders it.
- `frontend/src/features/payroll/PayrollPage.jsx`: every row in the payroll table has its own
  "Download payslip" button wired to that row's `line.id`.
- `PayrollPersistedPayslipIntegrationTest` exercises exactly this path against **real Postgres**.

## Commands Run
```bash
# Implementation-phase tests
cd backend
./mvnw -B test -Dtest=PayslipRendererTest
./mvnw -B test -Dtest="PayslipRendererTest,PayrollServiceTest,PayrollControllerTest,PayslipDistributionServiceTest,DepositNoticeRendererTest,RemainingInvoiceRendererTest,QuotationRendererTest"

# Full verify, IMPORTANT: the pom property is test.fork.count, NOT surefire.fork.count
# (default forkCount=2 races two JVMs against one external DB and produces spurious
# resetSchema/Flyway/deadlock failures across the whole suite -- not a real regression)
createdb -U "$USER" -h localhost -p 5432 <throwaway>
TEST_DB_URL="jdbc:postgresql://localhost:5432/<throwaway>?user=$USER" \
  ./mvnw -B clean verify -Dtest.fork.count=1
dropdb -U "$USER" -h localhost -p 5432 <throwaway>

# Same full verify re-run again on the uat cherry-pick branch before opening PR #304

# GitHub
gh pr create ...        # PR #302 into main
gh pr checks 302         # waited for Backend CI (build-and-test, ~12min), dependency-review, Vercel
gh pr merge 302 --merge --delete-branch=false
gh cherry-pick 1514a5c onto sync/payslip-pdf-redesign-to-uat (branched off origin/uat)
gh pr create --base uat ...   # PR #304 into uat
gh pr checks 304 / gh pr merge 304 --merge --delete-branch=false
```
Also rendered sample PDFs (simple + rich cases) from the built classes at each revision and
eyeballed them as PNGs.

## Tests / Build Results
- `PayslipRendererTest` — 2/2 pass (both border and borderless revisions).
- Renderer-adjacent batch (payslip, payroll service/controller, distribution, deposit, quotation,
  remaining-invoice) — 40/40 pass at each revision.
- `PayrollPersistedPayslipIntegrationTest` — pass against **real Postgres**, re-run after every
  revision (border added, border removed, cherry-picked to uat).
- Full `./mvnw -B clean verify -Dtest.fork.count=1` against real local Postgres:
  - On `feat/payslip-pdf-redesign` (main's codebase): **1194 tests, 0 failures, 0 errors, 2
    skipped** (pre-existing/unrelated, in `IntegrationResetInvariantTest`). BUILD SUCCESS.
  - On `sync/payslip-pdf-redesign-to-uat` (uat's codebase, post cherry-pick): **1211 tests, 0
    failures, 0 errors, 2 skipped**. BUILD SUCCESS. (uat has 17 more tests than main.)
  - Independently re-run a third time by the Opus validation agent (its own throwaway DB, its own
    invocation) with the same result.
- GitHub Actions `build-and-test` CI passed on both PR #302 (~12min) and PR #304 (~13min), plus
  `dependency-review` and Vercel preview checks on both.

## Independent Validation (Opus)
A separate Opus agent, with no memory of the implementing session, was asked to verify everything
from scratch rather than trust the implementer's claims: read the diff itself to confirm no
business-logic/authz/schema files were touched; re-derive the footing math from
`PayrollCalculator`; independently confirm the HR per-employee download path by reading the
controller/service/frontend directly; run the full backend suite itself against real Postgres;
render and visually inspect a sample PDF; and grep for dead code left over from the border removal.

**Verdict: SAFE TO MERGE.** No issues found. This validation ran before PR #302 was opened.

## Authz Evidence
**No authorization change** — this is a rendering-only change. Endpoints, role gates, and the
`/payslip/me` ownership filter are untouched, confirmed by two independent reviewers (implementer +
Opus validator) reading the diff.

## Known Risks
- Special-pay rows print the system labels (e.g. `พิเศษ 4 (เบี้ยขยันประจำ)`) rather than the shorter
  `เบี้ยขยัน` in `slip.xls`. Cosmetic; the parenthetical conveys the same meaning. Left as-is to avoid
  changing business labels used elsewhere (`PayrollService.specialPayDtos`). User has not asked for
  this to change.
- The two-column layout is laid out with absolute coordinates and does not auto-paginate; a payslip
  with an unusually large number of line items (well beyond real data) could overflow one A4 page.
- **`main` and `uat` remain 21 vs 89 commits diverged outside of this change** — this PR only closed
  the gap for the payslip renderer specifically. A full reconciliation, if wanted, is separate,
  larger work that historically has needed manual conflict resolution (see prior handoffs/memory on
  uat syncs).
- Render's auto-deploy behavior on push to `uat` was not independently confirmed post-merge (no
  deploy credentials available in this session) — worth a quick check that the uat environment
  actually picked up the new payslip PDF before telling end users.

## Next Steps / Exact Next Prompt
> Confirm the uat Render deployment picked up commit `0b3c246` (check the Render dashboard or hit
> the uat backend directly) and do a real-backend spot-check on both `main`'s demo/production
> environment and `uat`: HR downloads a specific employee's payslip from `/payroll`, and that
> employee downloads their own via the self-service "สลิปเงินเดือน" tile — confirm the rendered PDF
> matches `slip.xls` in both environments. If a full `main`↔`uat` historical reconciliation (21 vs
> 89 diverged commits) is actually wanted, scope and plan that as its own task.
