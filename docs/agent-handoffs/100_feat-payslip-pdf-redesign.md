# Agent Handoff

## Task
Redesign the downloadable payslip PDF to match the company HR template (`slip.xls`, provided by
the user) so every employee — including HR — can download a clean, employee-facing payslip. The
download plumbing already existed; the ask was purely the visual/layout redesign.

## Branch
`feat/payslip-pdf-redesign`

## Base Commit
`c3749a62a9dd0bfe61e44b8d67b8172fed1e14d2` (origin/main)

## Current Commit
Not committed (no commit/push requested yet).

## Agent / Model Used
Claude Opus 4.8

## Scope

### In Scope
- Rewrite `PayslipRenderer.toPdf` to the two-column employer layout from `slip.xls`.
- Add three small, additive layout primitives to the shared `PdfDocumentWriter`.
- Update the payslip renderer tests + the persisted-payslip integration test to the new layout.

### Out of Scope
- No payroll math, DTO fields, endpoints, authorization, or DB schema changed — **rendering only**.
- No frontend change: the self-service "สลิปเงินเดือน" tile, HR per-row download, `/payslip/me`
  endpoint, and email distribution already exist and are untouched.
- Pre-existing `downloadBlob` duplication in the frontend was left alone.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/common/PdfDocumentWriter.java` — added `textCenter(...)`,
  `cursorY()`, and `moveTo(y)`. All additive; existing callers/behavior (deposit, quotation,
  remaining-invoice renderers) unchanged. (A `line(x1,y1,x2,y2)` primitive was added and then
  removed in the same session — see "borderless" below.)
- `backend/src/main/java/th/co/glr/hr/payroll/PayslipRenderer.java` — full rewrite of `toPdf` to the
  `slip.xls` layout: centered company header (`บริษัท จีแอลแอนด์อาร์แทปส์แอนด์ไทลส์ จำกัด`) +
  `สลิปเงินเดือน`; ID/date + name/department rows; a **borderless** (no box/rule lines — user
  explicitly asked to remove the black line border) two-column `รายการได้ / รายการหัก` layout
  separated by whitespace (`COL_GAP`); per-side totals; bold `เงินรับสุทธิ` line; `ผู้รับเงิน / วันที่`
  signature. Date renders `31/พ.ค./2569` (period-end, Thai abbreviated month + B.E. year). Only
  non-zero lines print; OT/commission/director surface when non-zero.
- `backend/src/test/java/th/co/glr/hr/payroll/PayslipRendererTest.java` — replaced old-format
  assertions with the two-column layout + footing checks; added a second test covering non-taxable
  income and the leave-refund credit line.
- `backend/src/test/java/th/co/glr/hr/payroll/PayrollPersistedPayslipIntegrationTest.java` — updated
  the DB-reread assertions from the old single-column text to the new layout/totals.

## Footing contract (the correctness property)
Figures are read verbatim from `PayrollLineDto`. The printed totals are defined so the columns
always foot to net:
- `รวมรายได้` = `grossEarnings + nonTaxableIncome` (every money-in component gets an earnings row)
- `รวมรายหัก` = `totalDeductions` (every deduction gets a row; `leaveDeductionRefund` prints as a
  negative credit line so the column still sums to `totalDeductions`)
- `เงินรับสุทธิ` = `รวมรายได้ − รวมรายหัก` = `netPay`, exact by construction.

## Commands Run
```bash
cd backend
./mvnw -B test -Dtest=PayslipRendererTest
./mvnw -B test -Dtest="PayslipRendererTest,PayrollServiceTest,PayrollControllerTest,PayslipDistributionServiceTest,DepositNoticeRendererTest,RemainingInvoiceRendererTest,QuotationRendererTest"
# persisted integration test against a throwaway local Postgres (created + dropped):
TEST_DB_URL="jdbc:postgresql://localhost:5432/<throwaway>?user=$USER" \
  ./mvnw -B test -Dtest=PayrollPersistedPayslipIntegrationTest -Dsurefire.fork.count=1
```
Also rendered a sample PDF from the built classes and eyeballed it as a PNG — matches `slip.xls`.

## Tests / Build Results
- `PayslipRendererTest` — 2/2 pass.
- Renderer-adjacent batch (payslip, payroll service/controller, distribution, deposit, quotation,
  remaining-invoice) — 40/40 pass. Confirms the shared `PdfDocumentWriter` change broke no other
  renderer.
- `PayrollPersistedPayslipIntegrationTest` — 1/1 pass against **real Postgres** (throwaway DB, then
  dropped). Proves the new layout renders from the actually-persisted `hr.payroll_line` row and foots.
- Full `./mvnw -B clean verify` — **not run end-to-end here**; recommend running before merge.

## Authz Evidence
**No authorization change** — this is a rendering-only change. Endpoints, role gates, and the
`/payslip/me` ownership filter are untouched.

## Known Risks
- Special-pay rows print the system labels (e.g. `พิเศษ 4 (เบี้ยขยันประจำ)`) rather than the shorter
  `เบี้ยขยัน` in `slip.xls`. Cosmetic; the parenthetical conveys the same meaning. Left as-is to avoid
  changing business labels used elsewhere (`PayrollService.specialPayDtos`).
- The two-column grid is laid out with absolute coordinates and does not auto-paginate; a payslip
  with an unusually large number of line items (well beyond real data) could overflow one A4 page.
- Very long custom department names or special-pay labels could visually crowd the amount column
  (no wrapping in the grid cells).

## Next Steps / Exact Next Prompt
> Run `cd backend && ./mvnw -B clean verify` on branch `feat/payslip-pdf-redesign` (rebased on latest
> origin/main) and confirm the full suite is green. Then commit as
> `feat(payroll): redesign payslip PDF to match the HR slip.xls template`, open a PR to `main`, and
> do a real-backend UI spot-check: HR downloads a line payslip from `/payroll`, and an employee
> downloads their own via the self-service "สลิปเงินเดือน" tile — confirm the rendered PDF matches
> `slip.xls`. (Mock build stubs the download, so use the real backend / demo profile.)
