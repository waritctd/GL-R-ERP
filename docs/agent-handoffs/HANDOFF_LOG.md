# Handoff Log — completed branches (consolidated index)

This file is the consolidated record of **completed / merged** agent handoffs. The individual
`NN_<branch>.md` files for these were removed on 2026-07-25 to declutter the folder; every one
of them remains recoverable from git history (`git log --diff-filter=D --name-only -- docs/agent-handoffs/`).

**Still-live handoffs kept as individual files** (in-flight or referenced by `CLAUDE.md`):
`85_feat-sales-pricing-request-foundation.md` (PCR foundation — CLAUDE.md worked example),
`102_feat-sales-commission-auto-approval.md`, `104_feat-deal-workspace-unification.md`,
`113_feat-attendance-pyzk-transport.md`, `100_feat-payroll-statutory-export-files.md`,
`100_feat-sales-role-scoped-views.md`. Plus the anchors: `00_MASTER_CONTEXT.md`,
`01_STABILIZATION_AUDIT.md`, `README.md`.

**On numbering:** the `NN_` prefix is chronological-ish, not unique — parallel agents reused
numbers. Collisions existed at 45, 49, 51, 89, 90, 100 (×8), 101, 102, 108, 112; numbers 11 and
72 were never used. The prefix is a rough ordering only; the branch name is the real identifier.

---

## Era 1 — Stabilization & v0.1.0 (branches 02–48)
The post-audit hardening pass (`01_STABILIZATION_AUDIT.md`): mobile shell, TanStack Query,
routing, security, testing/observability floors, the full Tailwind migration, RHF forms, and the
first HR features. All merged (PRs ~#100–#190).

| # | Branch | Outcome |
|---|--------|---------|
| 02 | fix-mobile-app-shell | Mobile nav drawer + iOS-zoom/100dvh/touch-target fixes for HR-core |
| 03 | fix-mobile-core-list-cards | Card fallback below 720px for the six HR data tables (kills scroll trap) |
| 04 | refactor-tanstack-query-core | Introduced TanStack Query v5; migrated the `useHrData` server-state layer |
| 05 | refactor-query-leave-overtime | Leave + Overtime pages onto TanStack Query; added overtime mock |
| 06 | refactor-frontend-routing | react-router-dom v7 BrowserRouter replaces the App.jsx ternary router |
| 07 | security-auth-hardening | Default-deny SecurityConfig + removed employee-code temp password |
| 08 | backend-testing-floor | Testcontainers, payroll IT, Jacoco coverage ratchet |
| 09 | backend-observability-floor | Actuator health, correlation-ID MDC filter, richer exception logging |
| 10 | frontend-error-boundary | Global + route-level React ErrorBoundary (no new deps) |
| 12 | backend-openapi-docs | springdoc OpenAPI documentation |
| 13 | docs-v0.1-cleanup | Added `docs/README.md` index; archived legacy planning docs |
| 14 | backend-audit-log-coverage | Extended AuditService across mutating methods |
| 15 | frontend-v0.1.0-release-prep | Final two v0.1.0 DoD frontend decisions |
| 16 | tailwind-phase0-setup | Tailwind v4 wired as a visual no-op; tokens into `@theme`; `cn()` helper |
| 17 | tailwind-phase1-button | Shared `<Button>` primitive (CVA); pilot page converted |
| 18 | tailwind-phase1b-button-sweep | Swept raw legacy button classes to `<Button>` |
| 19 | rhf-forms-overtime-pilot | react-hook-form + zod piloted on OvertimePage |
| 20 | datatable-tanstack-table | DataTable re-based on @tanstack/react-table; stickyHeader + CSV export |
| 21 | rhf-forms-leave | LeavePage form → RHF + zod (logic byte-identical) |
| 22 | rhf-forms-employee-modal | EmployeeFormModal → RHF + zod |
| 23 | rhf-forms-change-password | ChangePasswordModal → RHF + zod |
| 24 | rhf-forms-change-request | ChangeRequestModal → RHF + zod |
| 25 | tw-primitives-profile | Missing layout primitives; profile page converted |
| 26 | tw-convert-dashboards | Two dashboards converted to Tailwind |
| 27 | tw-kill-inline-styles | Replaced static hardcoded inline styles |
| 28 | tw-table-grids | Table-grid refactor to Tailwind |
| 29 | tw-convert-overtime-leave | Overtime + Leave layout markup → Tailwind |
| 30 | tw-convert-employees | EmployeeListPage → Tailwind |
| 31 | tw-convert-requests-modals | MyRequestsPage + modals → Tailwind |
| 32 | tw-convert-attendance-payroll | Attendance + Payroll layout → Tailwind |
| 33 | tw-css-cleanup | Deleted now-unused non-frozen CSS (Phase 4 capstone) |
| 34 | feat-notification-email-backbone | Async mail + `hr.notification` + `/api/notifications` |
| 35 | feat-leave-autoapprove-upload | File storage, leave upload, auto-approve/reject, notifications |
| 36 | feat-overtime-ceo-approval | OT flow employee→manager→CEO with notifications |
| 37 | feat-commission-invoice-dual-approval | Commission/invoice dual-approval flow |
| 38 | fix-v32-migration-collision | Fixed Render demo crash-loop from a V32 migration collision |
| 39 | feat-sales-post-quotation-flow | Forward-ported yang/ticket post-quotation features (dual-track, FX, CEO price) |
| 40 | sit-stability-memory-and-nft-handoff | Recorded SIT/stability memory after backend deploy |
| 41 | nft-non-functional-testing | Post-SIT non-functional testing round 1 |
| 42 | docs-exit-stabilization-freeze | Updated CLAUDE.md + 00_MASTER_CONTEXT to exit the stabilization freeze |
| 43 | feat-payslip-pdf | Per-employee payslip PDFs on the Sarabun PdfDocumentWriter |
| 44 | feat-payslip-email | HR-triggered async payslip email with idempotent delivery log |
| 45 | fix-frontend-ci-xlsx-audit | Fixed frontend CI red from the xlsx high-sev audit gate |
| 45 | fix-main-failing-tests | Fixed two backend tests failing on main |
| 46 | fix-document-controller-auth | Restored `requireUser` on two DocumentController read endpoints |
| 47 | ui-tailwind-containment-phase-a-b | Tailwind-first CSS migration + containment (ui-responsive-repair-plan step 4.5) |
| 48 | fix-mockapi-drift | Fixed mockApi drift vs the Spring backend (#201) |

## Era 2 — Sales/CRM: ticket flow + deal-workflow program (branches 49–99)
Ticket authz/pricing hardening, the 14-stage deal pipeline, the 5-phase branching-workflow
program (67–71), the UX audit remediation (79–80), and the sales pricing chain (81–98). Merged
across PRs ~#200–#256.

| # | Branch | Outcome |
|---|--------|---------|
| 49 | fix-leave-service-test-clock | Fixed two clock-dependent LeaveServiceTest failures |
| 49 | price-import-lockdown | Locked price import to ceo+import across nav/API/service (#205) |
| 50 | mock-authz-sweep | Mock authz sweep aligning to Java gates (#206) |
| 51 | fix-ticket-dualtrack-accountant | Fixed the P0 dual-track deadlock in the sales ticket flow |
| 51 | manager-resolution-model | Manager-resolution model (last #206 sub-task) |
| 52 | security-ticket-endpoint-authz | Ticket-endpoint authz hardening (audit item 3) |
| 53 | fix-quotation-pdf-layout | Fixed the quotation PDF layout/format |
| 54 | fix-document-module-removal | Deleted the legacy document module (audit item 5) |
| 55 | fix-deposit-notice-unification | Deposit-notice unification (audit item 4, approved logic change) |
| 56 | fix-pricing-integrity | Six pricing-integrity fixes from the ticket-flow audit |
| 57 | fix-ticket-frontend-seams | Five frontend UX seam fixes |
| 58 | feat-sales-manager-oversight | sales_manager read+comment oversight |
| 59 | feat-quotation-freeze | Freeze issued quotations (legal compliance) |
| 60 | refactor-ticket-design-tokens | Tokenized ad-hoc ticket styles |
| 61 | refactor-styles-css-minimization | Minimized the ~2k-line styles.css |
| 62 | refactor-tickets-query-slice-a | TicketListPage + TicketDashboard → TanStack Query |
| 63 | refactor-tickets-query-slice-b | TicketDetailPage → TanStack Query (the big one) |
| 64 | refactor-tickets-query-slice-c | DepositNoticePage etc. → TanStack Query |
| 65 | feat-project-sales-pipeline-backend | Backend: 1 ticket = 1 deal, 14-stage pipeline |
| 66 | feat-project-sales-pipeline-ui | Frontend for the deal pipeline |
| 67 | feat-deal-workflow-p1-lifecycle | Deal-workflow program Phase 1 — lifecycle (V51) — PR #226/#227 |
| 68 | feat-deal-workflow-p2-quotations | Phase 2 — quotations — PR #228 |
| 69 | feat-deal-workflow-p3-payments | Phase 3 — payments — PR #229 |
| 70 | feat-deal-workflow-p4-fulfilment | Phase 4 — fulfilment — PR #230 |
| 71 | feat-deal-workflow-p5-reports-doc | Phase 5 — reports/docs — PR #231/#232 |
| 73 | fix-ticket-flow-flowchart-alignment | Aligned ticket transitions to the S1–S20 flowchart |
| 74 | db-api-health-audit-and-test-plan | DB + API health audit + regression test plan |
| 75 | live-fire-api-test-uat-main | Live-fire API test vs hosted UAT + main smoke |
| 76 | fix-customer-authz-priority-validation | Customer-create authz + ticket priority validation |
| 77 | functional-db-live-test-uat | Functional DB live test vs hosted UAT (all endpoints) |
| 78 | fix-ux-02-profile-request-confirm | Added confirmation step to HR profile-request approve/reject (UX-02) |
| 79 | ux-audit-phase-1 | UX audit remediation Phase A (blockers/production risk) |
| 80 | ux-audit-phase-2 | UX audit remediation Phase B (core workflow usability) |
| 81 | fix-sales-transition-gates | Fixed sales transition gates |
| 82 | feat-sales-close-verification | Close-verification step (stacked on 81) |
| 83 | feat-sales-cancel-reason | Cancel-reason capture (stacked on 82) |
| 84 | feat-sales-audit-trail | Sales audit trail (stacked on 83) |
| 86 | fix-employee-list-search-and-layout | Repaired HR employee-list filters/search/mobile cards |
| 87 | fix-employee-detail-view | Fixed the employee detail view |
| 88 | feat-sales-factory-quote-costing | Pricing Step 2 — factory quotes + costing (note: a superseded parallel impl exists) |
| 89 | feat-ot-remove-advance-notice | Removed OT advance-notice constraint (CEO instruction) |
| 89 | perf-faster-integration-test-db-reset | Faster IT DB reset to cut backend CI time |
| 90 | feat-special-money-requests | Special-money (welfare) requests, slice 2 |
| 90 | perf-parallel-backend-tests | Parallelized backend test suite (#260) |
| 91 | feat-payroll-reconciliation | Reconciled PayrollCalculator vs the accountant's 2026 workbook |
| 92 | feat-sales-ceo-pricing-decision | Pricing Step 3 — CEO selling-price decision |
| 93 | feat-sales-customer-quotation | Pricing Step 4 — customer quotation generation/issuance |
| 94 | feat-sales-quotation-outcome | Pricing Step 5 — customer decision + commercial revisions (#251) |
| 95 | feat-sales-deposit-order-confirmation | Pricing Step 6 — deposit/payment/order confirmation |
| 96 | feat-procurement-factory-order | Pricing Step 7 — factory PO + import execution |
| 97 | feat-inventory-delivery-fulfilment | Step 8 — receiving, inventory allocation, delivery (#255) |
| 98 | feat-final-payment-closeout-commission | Step 9 — final payment closeout + commission gate |
| 99 | fix-sidebar-menu-polish | Grouped sidebar into collapsible sections; fixed collapse + colors (#256/#257) |

## Era 3 — Recent shipped: payroll, role-views, attendance, tests (branches 100+ merged)
The current release line. Sales/CRM unfrozen; payroll deepened; role-scoped views; attendance
hardware. Merged across PRs ~#265–#314.

| # | Branch | Outcome |
|---|--------|---------|
| 100 | chore-demo-seed-off-real-prod-profile | Stopped the prod profile from seeding demo data into real prod |
| 100 | feat-employee-director-remuneration-editable | Made ค่าตอบแทนกรรมการ an editable per-employee field |
| 100 | feat-payslip-pdf-redesign | Redesigned payslip PDF to the company slip.xls template (#302) |
| 100 | feat-role-views-ceo | CEO role-shaped landing (executive cockpit) — role-scoped views program (#281) |
| 100 | feat-role-views-hr | HR role-shaped landing (people-ops admin console) (#281) |
| 100 | fix-attendance-punch-order-and-source-column | Ascending punch order + source column on /attendance (#314) |
| 101 | feat-add-warehouse-qc-roles | Added warehouse + qc roles (recognition/derivation) (#263-adjacent) |
| 101 | feat-payroll-live-refresh | Payroll Refresh recomputes live without committing (#299) |
| 101 | feat-payroll-special-pay-carryforward | Pre-fill recurring special pay in new payroll runs (#281) |
| 102 | feat-payroll-withholding-tax-override | HR withholding-tax override, clearable to NULL (#303) |
| 103 | feat-weekly-report-elimination | Killed the weekly report; standardized deal-tracking (Track B) |
| 105 | feat-deal-deposit-fulfilment-unify | Unified DEPOSIT UI (Phase 3 slice S3) (#271) |
| 106 | feat-commission-manual-adjustments | Manual signed commission entries for sales_manager/CEO (#272) |
| 107 | feat-deal-creation-hub | Rebuilt the create-deal modal into a 6-section hub |
| 108 | feat-leave-payroll-unpaid-deduction | Leave→payroll unpaid-day deductions (Thai labour law) (#281) |
| 108 | feat-role-views-account | Account (บัญชี/การเงิน) money view (#281) |
| 108 | feat-role-views-division-manager | Division-manager role-shaped landing (#281) |
| 109 | test-stage-L-authz-scope-tests | Real-DB wrong-way-round authz scope ITs; found+fixed CommissionService gate gap |
| 110 | test-stage-K2-phase1-foundation | Playwright e2e foundation (mock frontend) (#292/#293) |
| 111 | test-stage-K2-phase2-flows | Playwright e2e flow specs (#295/#296) |
| 112 | feat-attendance-mark-present-wfh | CEO/HR "mark present" for WFH / stand-up days (#308) |
| 112 | feat-attendance-warehouse-scanner | Registered warehouse ZKTeco SC700 scanner (V89) + rollout runbook (#307) |
| 116 | chore-remove-demo-seed-from-migrations | V91 removes the sample/demo seed V16/V23/V24/V25 wrote into db/migration; V91.1 restores it for the demo showcase |
