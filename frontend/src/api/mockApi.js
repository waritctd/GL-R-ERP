// Mock backend for VITE_USE_MOCKS=true — the default verification surface for the
// `frontend-mock` launch config that devs, QA and coding agents drive.
//
// THE CONTRACT (see CLAUDE.md "Mock API contract", issue #201):
//   - Endpoints and DTO shapes ARE meant to be a faithful stand-in for the Spring
//     backend. `contract.test.js` enforces the method surface against hrApi.js.
//   - Authorization is NOT authoritative. The gates below approximate the Java
//     services and are known to diverge in places. Never read a permission rule
//     off this file — verify it against the Java service. A mock more permissive
//     than production is the dangerous direction: you only find out in prod.
//
// Each namespace below names the Java class it mirrors. Keep those pointers
// accurate when editing — they are how the next reader finds the source of truth.

import { createDemoDatabase } from '../data/demoData.js';
// Deal pipeline (V50, widened by V143). The mock serves the stage CATALOG as canned data — the
// shape of GET /api/meta/deal-stages — and nothing more.
//
// It deliberately no longer imports a stage RULE from the pages, because there is no longer a
// stage rule in the pages to import. The three it used to (canSetStage, canMarkLost,
// isRoutineBackwardMove) were copies of TicketService gates that had all gone stale; see
// mockStageDecisions below for what replaced them and what the mock now refuses to decide at all.
import { DEAL_STAGE_CATALOG } from '../data/dealStageCatalog.js';
// Deal tracking (V83, Slice B1/B2 "kill the weekly report" — handoff 103): win%
// defaults, the activity-kind taxonomy, and the stage-advance-gate readiness
// check, shared with the UI so the mock's numbers/gate can't drift from
// WinProbabilityDefaults.java / DealActivityKind.java / TicketService.updateStage.
import {
  computeStale as dealComputeStale,
  effectiveWinProbability as dealEffectiveWinProbability,
  hasActivitySince as dealHasActivitySince,
  isReadyToAdvance as dealIsReadyToAdvance,
  isValidActivityKind as dealIsValidActivityKind,
  lastStageChangeAt as dealLastStageChangeAt,
  STAGE_ADVANCE_GATE_MESSAGE as DEAL_STAGE_ADVANCE_GATE_MESSAGE,
} from '../features/tickets/dealTrackingMeta.js';
// PricingRequest (commit 6): status transition table + option lists shared
// with the UI so the mock's gates can't drift from PricingRequestService's —
// the authoritative rules live in the backend pricingrequest/ package.
import {
  canTransition as pricingRequestCanTransition,
  QUANTITY_TYPE_OPTIONS as PRICING_REQUEST_QUANTITY_TYPE_OPTIONS,
  RECIPIENT_OPTIONS as PRICING_REQUEST_RECIPIENT_OPTIONS,
  UNIT_BASIS_OPTIONS as PRICING_REQUEST_UNIT_BASIS_OPTIONS,
} from '../features/pricingRequests/pricingRequestMeta.js';
// fix/commission-figures-from-backend: mock mode no longer imports the commission tier math —
// see the fenced MOCK COMMISSION FIXTURES block near the `commissions` namespace below for why,
// and for the small local `round2`/`mockInvoiceCalculation` helpers that replace this import
// (neither is policy: they are generic 2dp rounding and "sum the caller's own input fields",
// not a tier/rate/floor table).
// Payroll/commission seed data (chore/mock-demo-seed-state-matrix) — the genuinely
// fake-able stores only (see demoPayroll.js's own header for what's deliberately excluded).
import {
  buildDemoCommissions, buildDemoTaxAllowanceDeclarations, buildDemoTaxAllowanceAttachments,
  buildDemoEmployeeTaxAllowances, buildDemoDeductionObligations, buildDemoPayrollInputDrafts,
} from '../data/demoPayroll.js';

const db = createDemoDatabase();

function normalizeQuotation(q, ticket, index = 0) {
  return {
    ...q,
    ticketId: q.ticketId ?? ticket.id,
    quotationVersion: q.quotationVersion ?? index + 1,
    docStatus: q.docStatus ?? 'ISSUED',
    recipientType: q.recipientType ?? 'UNSPECIFIED',
    recipientLabel: q.recipientLabel ?? null,
    paymentTerms: q.paymentTerms ?? null,
    leadTime: q.leadTime ?? null,
    deliveryTerms: q.deliveryTerms ?? null,
    validityDate: q.validityDate ?? null,
    sentAt: q.sentAt ?? null,
    acceptedAt: q.acceptedAt ?? null,
    rejectedAt: q.rejectedAt ?? null,
    parentQuotationId: q.parentQuotationId ?? null,
  };
}

// Deal pipeline backfill — mirrors V50__deal_sales_pipeline.sql's UPDATE so the
// demo deals always land exactly where the real migration would put them.
for (const t of db.tickets) {
  if (t.salesStage) continue;
  if (t.paymentStatus === 'FULLY_PAID' || t.status === 'closed') t.salesStage = 'CLOSED_PAID';
  else if (t.fulfillmentStatus != null) t.salesStage = 'PROCUREMENT';
  else if (['DEPOSIT_PAID', 'AWAITING_FINAL_PAYMENT'].includes(t.paymentStatus)) t.salesStage = 'DEPOSIT_RECEIVED';
  else if (['CUSTOMER_CONFIRMED', 'DEPOSIT_NOTICE_ISSUED'].includes(t.paymentStatus)) t.salesStage = 'ORDER_RECEIVED';
  else if (['quotation_issued', 'document_issued'].includes(t.status)) t.salesStage = 'QUOTE_BUYER';
  else t.salesStage = 'QUOTE_DESIGN_SIDE';
  t.lostReason = t.lostReason ?? null;
  t.lostAt = t.lostAt ?? null;
  t.stageUpdatedAt = t.stageUpdatedAt ?? t.updatedAt;
  t.lifecycle = t.lifecycle ?? (t.lostReason ? 'CLOSED_LOST'
    : t.status === 'cancelled' ? 'CANCELLED'
      : t.status === 'closed' ? 'COMPLETED'
        : 'ACTIVE');
  t.tenderRequirement = t.tenderRequirement ?? 'UNKNOWN';
  t.depositPolicy = t.depositPolicy ?? 'REQUIRED';
  t.depositPolicyReason = t.depositPolicyReason ?? null;
  t.entryChannel = t.entryChannel ?? 'UNSPECIFIED';
  const existingQuotations = t.quotations ?? (t.quotation ? [t.quotation] : []);
  t.quotations = existingQuotations.map((q, index) => normalizeQuotation(q, t, index));
  if (t.id === 6 && t.quotations.length === 1) {
    t.quotations[0] = { ...t.quotations[0], recipientType: 'DESIGNER', recipientLabel: 'Premium Design Group' };
    t.quotations.unshift(normalizeQuotation({
      ...t.quotations[0],
      id: 101,
      number: 'QT-2026-0101',
      issuedAt: '2026-06-17T09:30:00Z',
      recipientType: 'OWNER',
      recipientLabel: t.customerName,
      quotationVersion: 1,
      docStatus: 'SENT',
      sentAt: '2026-06-17T10:00:00Z',
      parentQuotationId: null,
    }, t, 0));
  }
  t.quotation = t.quotations[0] ?? null;
  t.items = (t.items ?? []).map((item) => ({
    ...item,
    qtyDelivered: item.qtyDelivered ?? 0,
    qtyFromStock: item.qtyFromStock ?? 0,
    stockNote: item.stockNote ?? null,
  }));
}
db.paymentReceipts = db.paymentReceipts || [
  { receiptId: 1, ticketId: 12, kind: 'DEPOSIT', amount: 65000, currency: 'THB', receivedAt: '2026-06-18T08:00:00Z', recordedById: 11, recordedByName: 'คุณบัญชี การเงิน', note: 'รับมัดจำจากใบแจ้งยอด', depositNoticeId: null, receiptRef: 'MOCK-12-DEP', createdAt: '2026-06-18T08:00:00Z' },
  { receiptId: 2, ticketId: 13, kind: 'DEPOSIT', amount: 66250, currency: 'THB', receivedAt: '2026-06-02T08:00:00Z', recordedById: 11, recordedByName: 'คุณบัญชี การเงิน', note: 'รับมัดจำ', depositNoticeId: null, receiptRef: 'MOCK-13-DEP', createdAt: '2026-06-02T08:00:00Z' },
  { receiptId: 3, ticketId: 14, kind: 'DEPOSIT', amount: 312000, currency: 'THB', receivedAt: '2026-05-20T08:00:00Z', recordedById: 11, recordedByName: 'คุณบัญชี การเงิน', note: 'รับมัดจำ', depositNoticeId: null, receiptRef: 'MOCK-14-DEP', createdAt: '2026-05-20T08:00:00Z' },
  { receiptId: 4, ticketId: 14, kind: 'BALANCE', amount: 312000, currency: 'THB', receivedAt: '2026-07-05T08:00:00Z', recordedById: 11, recordedByName: 'คุณบัญชี การเงิน', note: 'รับชำระส่วนที่เหลือ', depositNoticeId: null, receiptRef: 'MOCK-14-BAL', createdAt: '2026-07-05T08:00:00Z' },
];
const partialDeliveryDemo = db.tickets.find((t) => t.id === 13);
if (partialDeliveryDemo?.items?.[0]) {
  partialDeliveryDemo.items[0].qtyDelivered = 200;
  partialDeliveryDemo.fulfillmentStatus = 'PARTIALLY_DELIVERED';
}
const onHoldDemoTicket = db.tickets.find((t) => t.id === 15);
if (onHoldDemoTicket) {
  onHoldDemoTicket.lifecycle = 'ON_HOLD';
}
const dormantDemoTicket = db.tickets.find((t) => t.id === 11);
if (dormantDemoTicket) {
  dormantDemoTicket.lifecycle = 'DORMANT';
}
db.deliveryRecords = db.deliveryRecords || [
  {
    deliveryId: 1,
    ticketId: 13,
    source: 'WAREHOUSE',
    deliveredAt: '2026-07-08T08:00:00Z',
    deliveredById: 2,
    deliveredByName: 'คุณนำเข้า ต่างประเทศ',
    note: 'ส่งมอบบางส่วน',
    createdAt: '2026-07-08T08:00:00Z',
    items: [{ deliveryItemId: 1, itemId: 16, qty: 200 }],
  },
];
const creditDemoTicket = db.tickets.find((t) => t.id === 6);
if (creditDemoTicket) {
  creditDemoTicket.depositPolicy = 'CREDIT_CUSTOMER';
  creditDemoTicket.depositPolicyReason = creditDemoTicket.depositPolicyReason ?? 'ลูกค้าเครดิตตามข้อตกลง';
  creditDemoTicket.billingDate = creditDemoTicket.billingDate ?? '2026-06-20';
  creditDemoTicket.dueDate = creditDemoTicket.dueDate ?? '2026-07-01';
  creditDemoTicket.creditTermDays = creditDemoTicket.creditTermDays ?? 30;
  creditDemoTicket.nextFollowUpAt = creditDemoTicket.nextFollowUpAt ?? '2026-07-18';
  creditDemoTicket.fulfillmentStatus = creditDemoTicket.fulfillmentStatus ?? 'FROM_STOCK';
  creditDemoTicket.salesStage = 'PROCUREMENT';
  if (creditDemoTicket.items?.[0]) {
    creditDemoTicket.items[0].qtyFromStock = creditDemoTicket.items[0].qty;
    creditDemoTicket.items[0].stockNote = 'พร้อมส่งจากสต็อก';
  }
}
// Commission state matrix (chore/mock-demo-seed-state-matrix) — was `db.commissions ||
// []` here, permanently empty: the old demoData.js `commissionRecords` export used a
// mismatched key/shape and was never actually wired to this store. See demoPayroll.js.
db.commissions = db.commissions?.length ? db.commissions : buildDemoCommissions();
// Payroll input draft (2026-07-30): unlike preview/process below, saving a draft performs no
// payroll/tax calculation at all -- it is a raw store of whatever HR typed -- so, unlike those,
// it CAN be faked genuinely here rather than throwing "not supported in mock mode". Keyed on
// `${employeeId}-${payrollMonth}`, mirroring hr.payroll_input_draft's (employee_id, payroll_month)
// uniqueness. Each stored row also carries a `version` (issue #422 follow-up, optimistic
// concurrency) -- see computeDraftETag below for why.
db.payrollInputDrafts = db.payrollInputDrafts || new Map();
if (db.payrollInputDrafts.size === 0) {
  for (const { employeeId, payrollMonth, input } of buildDemoPayrollInputDrafts(db.employees)) {
    // `version: 0` matches what saveDraft writes for a brand-new row (priorVersion -1 + 1), so a
    // seeded draft is shaped exactly like a saved one and computeDraftEtag below sees a real
    // version rather than falling through its `?? 0`.
    db.payrollInputDrafts.set(`${employeeId}-${payrollMonth}`, { payrollMonth, input, version: 0 });
  }
}

// Optimistic concurrency (issue #422 follow-up): mirrors PayrollDraftETag.compute in spirit (fold
// every row's (employeeId, version) into one token that changes if ANY row's version changes, or a
// row is added/removed) but NOT byte-for-byte, and NOT the real material -- the mock has no reason
// to reproduce the real SHA-256 algorithm, only the same observable behaviour, since a mock token
// is never compared against a real backend token. `rows` must already be the month's rows; ordering
// is forced here (unlike the real repository, which orders via SQL) so the token is stable across
// two reads of the same content regardless of Map iteration order.
//
// Honesty note (Opus review NIT-7): unlike the real PayrollDraftETag, this deliberately omits
// draftId -- the real one needs it to break an ABA collision across a delete-and-reinsert cycle
// (process() clearing a month's drafts, then a fresh save landing back at version 0). That cycle
// is UNREACHABLE here: this mock's `process()` always throws "not supported in mock mode" and
// never touches `db.payrollInputDrafts` (see that method's own comment), so no mock-mode draft row
// is ever deleted-and-reinserted in the first place. "Same observable behaviour" is true for every
// path this mock can actually reach, not a claim that the ABA fix itself is mirrored.
function computeDraftEtag(rows) {
  if (!rows.length) return 'empty-v1';
  return [...rows]
    .sort((a, b) => Number(a.input.employeeId) - Number(b.input.employeeId))
    .map((row) => `${row.input.employeeId}:${row.version ?? 0}`)
    .join('|');
}
// Tax-allowance DECLARATION workflow (PR A, 2026-08-01): unlike getTaxAllowances/saveTaxAllowances
// above, the workflow itself (submit/withdraw/approve/reject/on-behalf) performs no tax
// calculation -- it only moves a row between PENDING/APPROVED/REJECTED/SUPERSEDED/WITHDRAWN -- so
// it CAN be faked genuinely here, same reasoning as payrollInputDrafts. applyTaxAllowanceDeclaration
// is the exception: it promotes into hr.employee_tax_allowance and changes real withholding, so
// mock mode surfaces "not supported" for that one call, same as saveTaxAllowances.
db.taxAllowanceDeclarations = db.taxAllowanceDeclarations?.length
  ? db.taxAllowanceDeclarations : buildDemoTaxAllowanceDeclarations(db.employees);
// Evidence attachments (decision #5, 2026-08-01): genuinely fake-able (file metadata + access
// scoping, no tax math) -- unlike applyTaxAllowanceDeclaration/estimateMyTaxAllowanceDeclaration,
// which are NOT.
db.taxAllowanceAttachments = db.taxAllowanceAttachments?.length
  ? db.taxAllowanceAttachments : buildDemoTaxAllowanceAttachments();
// hr.employee_tax_allowance (C1 stored allowance -- "register shows what payroll actually uses",
// 2026-08): a SEPARATE store from taxAllowanceDeclarations above -- see buildDemoEmployeeTaxAllowances'
// own header comment in demoPayroll.js for why this is genuinely fake-able and what the four seeded
// rows cover.
db.employeeTaxAllowances = db.employeeTaxAllowances?.length
  ? db.employeeTaxAllowances : buildDemoEmployeeTaxAllowances(db.employees);
// Deduction obligation tracking (issue #373): the record + status transitions themselves perform
// no payroll/tax calculation -- they only track an instruction and its lifecycle -- so, like
// taxAllowanceDeclarations above, this CAN be faked genuinely here. The remittance ledger is the
// exception: real remittance rows are only ever written by PayrollService#process (mocked as
// "not supported"), so db.deductionObligationRemittances stays permanently empty in mock mode and
// every progress read reports zero paid-to-date -- that is the honest mock answer, not a bug.
db.deductionObligations = db.deductionObligations?.length
  ? db.deductionObligations : buildDemoDeductionObligations(db.employees);
db.deductionObligationRemittances = db.deductionObligationRemittances || [];
// §5 leave-rules-as-data (V116, extended V119/V120): paidDaysCap/advanceNoticeDays/
// minServiceMonths/maxConsecutiveDays/oncePerEmployment/dayCountBasis/proratedFirstYear/
// firstYearMaxDays mirror the hr.leave_type columns for SHAPE parity only (contract.test.js checks
// method surface + arity, not field-level DTO shape, but a leaveTypes() response missing these
// fields would still be a lie about what the real endpoint returns). Per CLAUDE.md ("mock
// authz/behaviour is NOT authoritative" -- and see the file-level note above on mirroring a backend
// computation being the dangerous direction): the mock's create() flow below does NOT enforce
// minServiceMonths, maxConsecutiveDays, oncePerEmployment, proratedFirstYear, or firstYearMaxDays,
// and does not replicate the paid_days_cap split -- those are real per-request eligibility/
// business-rule decisions the mock has never modelled fully (it already predates the paid/unpaid
// quota-split redesign; it still auto-rejects on insufficient quota outright rather than
// approving-with-split, a PRE-EXISTING gap this migration does not attempt to fix).
// advanceNoticeDays IS read below (mechanical 1:1 mirror of the column), since leaving the old
// hardcoded 7-day check in place would have positively contradicted the real per-type values this
// migration introduces.
//
// dayCountBasis (V119, 2026-08-02): §5.4 MATERNITY calendar-day counting -- SHAPE parity only, same
// as the rest of this block. create() below still ALWAYS calls workingDaysBetween() for a whole-day
// request regardless of this field (see workingDaysBetween's own call site) -- switching MATERNITY
// to calendar-day counting is exactly the kind of business-rule computation this mock deliberately
// does not reimplement (see the file-level note on why: a shared algorithmic error would be
// invisible on both sides). Anyone testing the §5.4 calendar-day behaviour itself must do so against
// the real backend, not VITE_USE_MOCKS=true.
//
// certificateFilingWindowDays / noCertificateMonthlyTolerance (V124, §5.1 SICK): SHAPE parity only,
// same "not enforced" caveat as every other field in this block. create() below still uses the OLD
// unconditional "SICK + no attachment -> reject" rule -- it does NOT grant the 3-times-a-month
// no-certificate tolerance, nor the certificate filing-window deadline. This is a genuine, real,
// money-moving business-rule decision (see LeaveService#sickCertificateNote's combined decision
// table on the real backend) exactly the kind this mock deliberately declines to reimplement (a
// shared algorithmic error between mock and backend would be invisible on both sides -- see the
// file-level note). Anyone testing the §5.1 tolerance/filing-window behaviour itself must do so
// against the real backend, not VITE_USE_MOCKS=true. Note this is the SAFE divergence direction
// (mock is now STRICTER than production, not more permissive -- CLAUDE.md's "more permissive than
// prod" is the dangerous one).
//
// §5.3.2/§5.3.3/§5.3.4 relational rules (2026-08, backend-only): "whole department not absent at
// once", "no PERSONAL/VACATION back to back", and "no VACATION/PERSONAL after a submitted
// resignation" are pure LeaveService#submit gates with no DTO/method-surface change (systemNote is
// already a free-text field), so contract.test.js needs no update. They are NOT modelled here --
// each depends on OTHER employees' schedules/leave/hr.resignation rows, exactly the "real
// per-request eligibility decision" category this block already declines to reimplement. Testing
// them requires the real backend.
db.leaveTypes = db.leaveTypes || [
  // PERSONAL quota fix (2026-07-25): seeded at 3, company rule (§5.2) grants 7 paid personal
  // days/year -- see V90__leave_subday_and_contact.sql for the backend-side correction.
  //
  // minServiceMonths: 0 (review fix, V116) -- PERSONAL's real eligibility floor is "passed
  // probation" (hire_date + hr.employee.probation_days, falling back to
  // SpecialMoneyPolicyEvaluator.DEFAULT_PROBATION_DAYS=119 when NULL), NOT a fixed months-of-
  // service number. That per-employee resolution is one of the things this mock does not
  // replicate (see the file-level note above) -- db.employees has no probation_days field to
  // resolve it from, so leaving PERSONAL unrestricted here is the honest "not supported in mock
  // mode" option rather than inventing a different, wrong approximation.
  //
  // maxConsecutiveDays: null / firstYearMaxDays: 3 (V120, defect 3 fix) -- the old blanket
  // 3-CONSECUTIVE-day rule for everyone is gone; the real 3-day figure is now a first-year-only
  // TOTAL annual cap (see LeaveService#autoRejectNote's Javadoc on the real backend). Not enforced
  // in mock mode, same "shape only" caveat as every other field here.
  // emergencyMonthlyAllowance: 3 (V125) -- §5.2's emergency-filing exception ("อนุโลมให้ได้ไม่เกิน
  // เดือนละ 3 ครั้ง โดยไม่หักเงิน"). SHAPE parity only, same caveat as every other field in this
  // block: create() below does not implement the notice-bypass/monthly-tolerance decision at all
  // (see the leave.create() comment further down) -- a late PERSONAL request is auto-rejected in
  // mock mode exactly as it always was, regardless of purposeCode/requestedAsEmergency.
  {
    code: 'PERSONAL', nameTh: 'ลากิจ', nameEn: 'Personal leave', annualQuotaDays: 7, requiresAttachment: false,
    paidDaysCap: null, advanceNoticeDays: 1, minServiceMonths: 0, maxConsecutiveDays: null, oncePerEmployment: false,
    dayCountBasis: 'WORKING_DAYS', proratedFirstYear: true, firstYearMaxDays: 3,
    certificateFilingWindowDays: null, noCertificateMonthlyTolerance: 0, emergencyMonthlyAllowance: 3,
    carriesForward: false,
  },
  // certificateFilingWindowDays: 3 / noCertificateMonthlyTolerance: 3 (V124, §5.1) -- SHAPE parity
  // only, see the file-level note above this array: create() below does NOT enforce either of these.
  {
    code: 'SICK', nameTh: 'ลาป่วย', nameEn: 'Sick leave', annualQuotaDays: 30, requiresAttachment: true,
    paidDaysCap: null, advanceNoticeDays: 0, minServiceMonths: 0, maxConsecutiveDays: null, oncePerEmployment: false,
    dayCountBasis: 'WORKING_DAYS', proratedFirstYear: false, firstYearMaxDays: null,
    certificateFilingWindowDays: 3, noCertificateMonthlyTolerance: 3, emergencyMonthlyAllowance: null,
    carriesForward: false,
  },
  // minServiceMonths: 0 / proratedFirstYear: true (V120, defect 1 fix) -- V116's original
  // min_service_months=12 refused ALL vacation leave under a year of service outright, contradicting
  // §5.3's pro-rated entitlement; the real backend now scales the quota instead (see
  // LeaveService#employeeAnnualQuota). Not enforced in mock mode.
  //
  // carriesForward: true (V127, §5.3.5) -- SHAPE parity only, same caveat as every other field in
  // this block. The mock's leaveBalance() below always reports carriedInDays: 0 -- computing the
  // real grant needs hr.leave_carryover's year-end memoization (LeaveService#ensureCarryoverGrant),
  // a genuine business computation this mock deliberately does not reimplement (see the file-level
  // note: a shared algorithmic error in a mirrored computation is invisible on both sides). Anyone
  // testing carry-forward itself must do so against the real backend, not VITE_USE_MOCKS=true.
  {
    code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation leave', annualQuotaDays: 6, requiresAttachment: false,
    paidDaysCap: null, advanceNoticeDays: 3, minServiceMonths: 0, maxConsecutiveDays: null, oncePerEmployment: false,
    dayCountBasis: 'WORKING_DAYS', proratedFirstYear: true, firstYearMaxDays: null,
    certificateFilingWindowDays: null, noCertificateMonthlyTolerance: 0, emergencyMonthlyAllowance: null,
    carriesForward: true,
  },
  {
    code: 'MATERNITY', nameTh: 'ลาคลอดบุตร', nameEn: 'Maternity leave', annualQuotaDays: 98, requiresAttachment: true,
    paidDaysCap: 45, advanceNoticeDays: 0, minServiceMonths: 0, maxConsecutiveDays: null, oncePerEmployment: false,
    dayCountBasis: 'CALENDAR_DAYS', proratedFirstYear: false, firstYearMaxDays: null,
    certificateFilingWindowDays: null, noCertificateMonthlyTolerance: 0, emergencyMonthlyAllowance: null,
    carriesForward: false,
  },
  // annualQuotaDays: 366 (sentinel, not a real policy number) / paidDaysCap: 60 (V120, defect 2 fix)
  // -- V116 wrongly capped the LEAVE ITSELF at 60 days; §5.5 only caps the PAY. See
  // V120__leave_type_proration_and_military_cap_fix.sql for the full writeup.
  {
    code: 'MILITARY', nameTh: 'ลารับราชการทหาร', nameEn: 'Military service leave', annualQuotaDays: 366, requiresAttachment: true,
    paidDaysCap: 60, advanceNoticeDays: 0, minServiceMonths: 0, maxConsecutiveDays: null, oncePerEmployment: false,
    dayCountBasis: 'WORKING_DAYS', proratedFirstYear: false, firstYearMaxDays: null,
    certificateFilingWindowDays: null, noCertificateMonthlyTolerance: 0, emergencyMonthlyAllowance: null,
    carriesForward: false,
  },
  {
    code: 'ORDINATION', nameTh: 'ลาอุปสมบท', nameEn: 'Ordination leave', annualQuotaDays: 60, requiresAttachment: false,
    paidDaysCap: 15, advanceNoticeDays: 0, minServiceMonths: 12, maxConsecutiveDays: null, oncePerEmployment: true,
    dayCountBasis: 'WORKING_DAYS', proratedFirstYear: false, firstYearMaxDays: null,
    certificateFilingWindowDays: null, noCertificateMonthlyTolerance: 0, emergencyMonthlyAllowance: null,
    carriesForward: false,
  },
];
// leaveRequests/overtimeRequests/specialMoneyRequests are seeded by demoHr.js, wired
// through createDemoDatabase()'s return (chore/mock-demo-seed-state-matrix). db.leaveRequests
// and db.overtimeRequests were already non-empty here (createDemoDatabase() returned both), so
// their `if (db.X.length === 0) {...}` seed blocks that used to sit at this exact spot were
// genuinely dead code (never reachable) and have been deleted. db.specialMoneyRequests was
// DIFFERENT (review fix, 2026-08-02): createDemoDatabase() never returned it — confirm with
// `git show HEAD:frontend/src/data/demoData.js | grep specialMoneyRequests` on the parent
// commit — so `db.specialMoneyRequests` was `undefined` here, `undefined.length === 0` was
// true, and that block WAS live: it was the sole source of the 5 special-money rows before
// this branch. Those exact 5 rows are preserved verbatim in demoHr.js, so there is no
// functional regression — only the earlier "all three were dead code" claim was wrong.
db.leaveRequests = db.leaveRequests || [];
db.overtimeRequests = db.overtimeRequests || [];
// Was `|| []` — i.e. empty for every persona, so /attendance's correction section rendered its
// empty state for HR, CEO, the division manager and the employee alike and nothing on that surface
// could be judged. Now seeded from demoHr.js with the full
// AttendanceCorrectionStatus x AttendanceCorrectionType matrix; see that builder's comment.
db.attendanceCorrectionRequests = db.attendanceCorrectionRequests?.length
  ? db.attendanceCorrectionRequests : [];
db.specialMoneyRequests = db.specialMoneyRequests || [];
// Evidence uploads live only for the life of the mock session -- there is no file store here.
db.specialMoneyAttachments = db.specialMoneyAttachments || [];
let sessionUser = null;

// ── Mock in-memory document store ─────────────────────────────────────────────
const mockCustomers = [
  { id: 1, name: 'บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด',  taxId: '0105565012345', address: '123 ถนนสุขุมวิท แขวงคลองเตย กรุงเทพฯ 10110', branch: 'สำนักงานใหญ่', phone: '02-123-4567' },
  { id: 2, name: 'บริษัท ไทยแลนด์ ดีเวลลอปเมนท์ จำกัด', taxId: '0105556789012', address: '456 ถนนรัชดาภิเษก แขวงลาดยาว กรุงเทพฯ 10900',  branch: 'สำนักงานใหญ่', phone: '02-234-5678' },
  { id: 3, name: 'บริษัท พรีเมียม ดีไซน์ กรุ๊ป จำกัด',   taxId: '0105578901234', address: '789 ถนนพระราม 4 แขวงพระโขนง กรุงเทพฯ 10260',    branch: 'สำนักงานใหญ่', phone: '02-345-6789' },
  { id: 4, name: 'บริษัท เรืองแสง พร็อพเพอร์ตี้ จำกัด',  taxId: '0105591234567', address: '321 ถนนนวมินทร์ แขวงคลองกุ่ม กรุงเทพฯ 10240',  branch: 'สำนักงานใหญ่', phone: '02-456-7890' },
];
let mockCustomerSeq = mockCustomers.length + 1;

const mockContacts = [
  { id: 1, customerId: 1, firstName: 'วิภา',   lastName: 'สมิทธ์',   position: 'ผู้จัดการโครงการ', email: 'wipa@kaona.co.th',     phone: '081-111-2222' },
  { id: 2, customerId: 1, firstName: 'ธนพล',   lastName: 'อภิชัย',   position: 'วิศวกรโยธา',       email: 'thanaphon@kaona.co.th', phone: '082-333-4444' },
  { id: 3, customerId: 2, firstName: 'ปรีชา',  lastName: 'วงศ์สกุล', position: 'จัดซื้อ',          email: 'preecha@tld.co.th',     phone: '083-555-6666' },
  { id: 4, customerId: 3, firstName: 'สุภาพร', lastName: 'ทองดี',    position: 'ผู้อำนวยการ',       email: 'supaporn@pdg.co.th',    phone: '084-777-8888' },
  { id: 5, customerId: 4, firstName: 'กมล',    lastName: 'เรืองศรี', position: 'ผู้จัดการ',         email: 'kamol@rp.co.th',        phone: '085-999-0000' },
];
let mockContactSeq = mockContacts.length + 1;

const mockProjects = [
  { id: 1, customerId: 1, name: 'โครงการ Central Ladprao ชั้น B1' },
  { id: 2, customerId: 1, name: 'โครงการ The Mall Bangkapi' },
  { id: 3, customerId: 2, name: 'โครงการ Asoke Tower ชั้น 12-15' },
  { id: 4, customerId: 3, name: 'โครงการ PDG HQ Renovation' },
  { id: 5, customerId: 4, name: 'โครงการ Rueangchat Condo Phase 2' },
];
let mockProjectSeq = mockProjects.length + 1;

// R4: FX rates + price calc configs
const mockFxRates = [
  { id: 1, currency: 'CNY', rateToThb: 4.85,  effectiveDate: '2026-07-01', updatedAt: new Date().toISOString(), source: 'BOT',    fetchedAt: new Date().toISOString() },
  { id: 2, currency: 'EUR', rateToThb: 38.50, effectiveDate: '2026-07-01', updatedAt: new Date().toISOString(), source: 'BOT',    fetchedAt: new Date().toISOString() },
  { id: 3, currency: 'GBP', rateToThb: 44.80, effectiveDate: '2026-07-10', updatedAt: new Date().toISOString(), source: 'BOT',    fetchedAt: new Date().toISOString() },
  { id: 4, currency: 'JPY', rateToThb: 0.24,  effectiveDate: '2026-07-01', updatedAt: new Date().toISOString(), source: 'BOT',    fetchedAt: new Date().toISOString() },
  { id: 5, currency: 'THB', rateToThb: 1.00,  effectiveDate: '2026-07-01', updatedAt: new Date().toISOString(), source: 'MANUAL', fetchedAt: null },
  { id: 6, currency: 'USD', rateToThb: 33.6264, effectiveDate: '2026-07-10', updatedAt: new Date().toISOString(), source: 'BOT',    fetchedAt: new Date().toISOString() },
];

const mockPriceCalcConfigs = [
  {
    configId: 1, version: 1, country: 'Italy',
    freightPerSqm: 120, insurancePerSqm: 15,
    inlandFactoryToPortPerSqm: 30, inlandPortToWarehousePerSqm: 50,
    importDutyPct: 0.05, marginPct: 0.25,
    isCurrent: true, effectiveFrom: '2026-01-01', updatedAt: new Date().toISOString(),
  },
  {
    configId: 2, version: 1, country: 'Thailand',
    freightPerSqm: 0, insurancePerSqm: 0,
    inlandFactoryToPortPerSqm: 0, inlandPortToWarehousePerSqm: 50,
    importDutyPct: 0, marginPct: 0.20,
    isCurrent: true, effectiveFrom: '2026-01-01', updatedAt: new Date().toISOString(),
  },
];
let mockPriceConfigSeq = mockPriceCalcConfigs.length + 1;

// BRANCH 1 of the sales pricing-formula redesign (config storage + CEO editing UI only). Mirrors
// V109__pricing_formula_config.sql's seed data exactly -- see that migration's comment for the
// formula this config parameterizes and why deliberately-blank sheet cells are ABSENT rows here,
// never a zero-amount row. One parent object owns three child arrays; a new version always
// replaces the whole object (never mutates children of an existing version in place).
//
// BAND CONVENTION: freight thickness/qty bands and clearance qty bands are HALF-OPEN [min, max) --
// min inclusive, max EXCLUSIVE, null max = +infinity. Bands are contiguous (band N's max == band
// N+1's min) so every quantity/thickness value, including fractional sqm/mm, falls in exactly one
// band. See V109's BAND CONVENTION comment for the full rationale.
let mockFormulaFreightRateSeq = 1;
let mockFormulaDutyRateSeq = 1;
let mockFormulaClearanceFeeSeq = 1;
let mockFormulaConfigSeq = 1;

function formulaFreightRow(originCountry, thicknessMinMm, thicknessMaxMm, qtyMinSqm, qtyMaxSqm, amountThb) {
  return { freightRateId: mockFormulaFreightRateSeq++, originCountry, thicknessMinMm, thicknessMaxMm, qtyMinSqm, qtyMaxSqm, amountThb };
}
function formulaDutyRow(productType, productLabel, dutyPct) {
  return { dutyRateId: mockFormulaDutyRateSeq++, productType, productLabel, dutyPct };
}
function formulaClearanceRow(qtyMinSqm, qtyMaxSqm, amountThb) {
  return { clearanceFeeId: mockFormulaClearanceFeeSeq++, qtyMinSqm, qtyMaxSqm, amountThb };
}

function buildSeedFormulaConfig() {
  return {
    formulaConfigId: mockFormulaConfigSeq++,
    version: 1,
    insuranceValueFactor: 1.15,
    insuranceRate: 0.0045,
    insuranceBuffer: 1.07,
    costBuffer: 1.07,
    sellingBuffer: 1.07,
    defaultMarginPct: 0.2,
    sellingPriceRoundUpTo: 10,
    isCurrent: true,
    effectiveFrom: '2026-01-01',
    updatedAt: new Date().toISOString(),
    freightRates: [
      // Italy: thickness [3,8) mm
      formulaFreightRow('Italy', 3, 8, 1, 101, 80000),
      formulaFreightRow('Italy', 3, 8, 101, 451, 90000),
      formulaFreightRow('Italy', 3, 8, 451, 801, 100000),
      formulaFreightRow('Italy', 3, 8, 801, null, 100000),
      // Italy: thickness [8,12) mm
      formulaFreightRow('Italy', 8, 12, 1, 101, 50000),
      formulaFreightRow('Italy', 8, 12, 101, 451, 80000),
      formulaFreightRow('Italy', 8, 12, 451, 801, 90000),
      formulaFreightRow('Italy', 8, 12, 801, null, 100000),
      // Italy: thickness [12,17) mm (band4 blank in sheet -- no row)
      formulaFreightRow('Italy', 12, 17, 1, 101, 50000),
      formulaFreightRow('Italy', 12, 17, 101, 451, 90000),
      formulaFreightRow('Italy', 12, 17, 451, 801, 100000),
      // Italy: thickness [17,21) mm (band3/band4 blank in sheet -- no rows)
      formulaFreightRow('Italy', 17, 21, 1, 101, 60000),
      formulaFreightRow('Italy', 17, 21, 101, 451, 100000),

      // Spain: identical values to Italy (the sheet groups them; separate rows let the CEO
      // diverge them later without a schema change).
      formulaFreightRow('Spain', 3, 8, 1, 101, 80000),
      formulaFreightRow('Spain', 3, 8, 101, 451, 90000),
      formulaFreightRow('Spain', 3, 8, 451, 801, 100000),
      formulaFreightRow('Spain', 3, 8, 801, null, 100000),
      formulaFreightRow('Spain', 8, 12, 1, 101, 50000),
      formulaFreightRow('Spain', 8, 12, 101, 451, 80000),
      formulaFreightRow('Spain', 8, 12, 451, 801, 90000),
      formulaFreightRow('Spain', 8, 12, 801, null, 100000),
      formulaFreightRow('Spain', 12, 17, 1, 101, 50000),
      formulaFreightRow('Spain', 12, 17, 101, 451, 90000),
      formulaFreightRow('Spain', 12, 17, 451, 801, 100000),
      formulaFreightRow('Spain', 17, 21, 1, 101, 60000),
      formulaFreightRow('Spain', 17, 21, 101, 451, 100000),

      // China
      formulaFreightRow('China', 3, 8, 1, 101, 60000),
      formulaFreightRow('China', 3, 8, 101, 451, 60000),
      formulaFreightRow('China', 3, 8, 451, 801, 50000),
      formulaFreightRow('China', 3, 8, 801, null, 50000),
      formulaFreightRow('China', 8, 12, 1, 101, 30000),
      formulaFreightRow('China', 8, 12, 101, 451, 50000),
      formulaFreightRow('China', 8, 12, 451, 801, 70000),
      formulaFreightRow('China', 8, 12, 801, null, 50000),
      formulaFreightRow('China', 12, 17, 1, 101, 30000),
      formulaFreightRow('China', 12, 17, 101, 451, 50000),
      formulaFreightRow('China', 12, 17, 451, 801, 70000),
      // China 12-17 band4 (801+) is blank in the sheet -- no row.
      formulaFreightRow('China', 17, 21, 1, 101, 40000),
      formulaFreightRow('China', 17, 21, 101, 451, 50000),
      // China 17-21 band3/band4 are blank in the sheet -- no rows.
    ],
    dutyRates: [
      // ก็อกน้ำ 20% / ยาแนว 10% are on the CEO's sheet but confirmed OUT OF SCOPE for this
      // flow -- deliberately not seeded.
      formulaDutyRow('TILE', 'กระเบื้อง', 0.30),
      formulaDutyRow('GLASS_MOSAIC', 'โมเสคแก้ว', 0.10),
    ],
    clearanceFees: [
      formulaClearanceRow(1, 101, 8000),
      formulaClearanceRow(101, 451, 12000),
      formulaClearanceRow(451, 801, 15000),
      formulaClearanceRow(801, null, 20000),
    ],
  };
}

// All versions ever created, oldest first -- mirrors the "never UPDATE/DELETE an old version's
// rows" rule: createNewVersion below always pushes a brand-new object, never mutates an existing
// one in place.
const mockPricingFormulaConfigVersions = [buildSeedFormulaConfig()];

function currentFormulaConfig() {
  return mockPricingFormulaConfigVersions.find((c) => c.isCurrent);
}

// Mirrors PricingFormulaConfigRepository's deterministic ORDER BY: freight by
// (origin_country, thickness_min_mm, qty_min_sqm), duty by product_type, clearance by qty_min_sqm.
function sortedFormulaConfig(config) {
  return {
    ...config,
    freightRates: [...config.freightRates].sort((a, b) =>
      a.originCountry.localeCompare(b.originCountry)
      || a.thicknessMinMm - b.thicknessMinMm
      || a.qtyMinSqm - b.qtyMinSqm),
    dutyRates: [...config.dutyRates].sort((a, b) => a.productType.localeCompare(b.productType)),
    clearanceFees: [...config.clearanceFees].sort((a, b) => a.qtyMinSqm - b.qtyMinSqm),
  };
}

// R5: Attachments
const mockAttachments = [];
let mockAttachSeq = 1;

// Deal tracking (V83, Slice B1/B2 "kill the weekly report" — handoff 103): the
// deal_activity log. Its own store, not on the ticket, mirrors sales.deal_activity
// being its own table (same convention as mockAttachments above).
const mockDealActivities = [];
let mockDealActivitySeq = 1;

const mockFactoryConfigs = [
  { id: 1, factoryName: 'SCG Ceramics',      email: 'sales@scg.co.th',         currency: 'THB', unit: 'piece', country: 'Thailand' },
  { id: 2, factoryName: 'Cotto Industry',    email: 'orders@cotto.co.th',       currency: 'THB', unit: 'piece', country: 'Thailand' },
  { id: 3, factoryName: 'Duragres Thailand', email: 'sales@duragres.co.th',     currency: 'THB', unit: 'piece', country: 'Thailand' },
  { id: 4, factoryName: 'Panaria SpA',       email: 'export@panaria.it',        currency: 'EUR', unit: 'sqm',   country: 'Italy' },
];

const mockCatalog = [
  { id: 1,  brand: 'SCG',      collection: 'Elegance Series',   color: 'ขาวนวล',      surface: 'ด้าน',         size: '60x60 ซม.',  factory: 'SCG Ceramics',      sqmPerPiece: 0.36 },
  { id: 2,  brand: 'SCG',      collection: 'Elegance Series',   color: 'เทาอ่อน',     surface: 'ด้าน',         size: '60x60 ซม.',  factory: 'SCG Ceramics',      sqmPerPiece: 0.36 },
  { id: 3,  brand: 'SCG',      collection: 'Natura Collection', color: 'เบจธรรมชาติ', surface: 'หยาบ',         size: '30x60 ซม.',  factory: 'SCG Ceramics',      sqmPerPiece: 0.18 },
  { id: 4,  brand: 'SCG',      collection: 'Natura Collection', color: 'น้ำตาลไม้',   surface: 'หยาบ',         size: '20x100 ซม.', factory: 'SCG Ceramics',      sqmPerPiece: 0.20 },
  { id: 5,  brand: 'SCG',      collection: 'Crystal White',     color: 'ขาวมุก',      surface: 'มัน',          size: '60x120 ซม.', factory: 'SCG Ceramics',      sqmPerPiece: 0.72 },
  { id: 6,  brand: 'Cotto',    collection: 'Metro Square',      color: 'ขาว',         surface: 'ด้าน',         size: '30x30 ซม.',  factory: 'Cotto Industry',    sqmPerPiece: 0.09 },
  { id: 7,  brand: 'Cotto',    collection: 'Metro Square',      color: 'ครีม',        surface: 'ด้าน',         size: '30x30 ซม.',  factory: 'Cotto Industry',    sqmPerPiece: 0.09 },
  { id: 8,  brand: 'Cotto',    collection: 'Stone Series',      color: 'เทาเข้ม',     surface: 'หยาบ',         size: '60x60 ซม.',  factory: 'Cotto Industry',    sqmPerPiece: 0.36 },
  { id: 9,  brand: 'Cotto',    collection: 'Timber Line',       color: 'น้ำตาลอ่อน', surface: 'ลายไม้',       size: '20x120 ซม.', factory: 'Cotto Industry',    sqmPerPiece: 0.24 },
  { id: 10, brand: 'Duragres', collection: 'Granite Plus',      color: 'เทากลาง',     surface: 'หยาบกึ่งมัน', size: '60x60 ซม.',  factory: 'Duragres Thailand', sqmPerPiece: 0.36 },
  { id: 11, brand: 'Duragres', collection: 'Granite Plus',      color: 'ดำ',          surface: 'หยาบกึ่งมัน', size: '60x60 ซม.',  factory: 'Duragres Thailand', sqmPerPiece: 0.36 },
  { id: 12, brand: 'Duragres', collection: 'Porcelain Pro',     color: 'ขาวเนียน',    surface: 'มัน',          size: '80x80 ซม.',  factory: 'Duragres Thailand', sqmPerPiece: 0.64 },
  { id: 13, brand: 'Panaria',  collection: 'Trilogy',           color: 'Ivory',       surface: 'Lappato',      size: '60x120 cm',  factory: 'Panaria SpA',       sqmPerPiece: 0.72 },
  { id: 14, brand: 'Panaria',  collection: 'Frame',             color: 'Ash',         surface: 'Naturale',     size: '80x80 cm',   factory: 'Panaria SpA',       sqmPerPiece: 0.64 },
];

// Bumped past the three domestic factories added below. This counter mints ids for
// factories a user creates at runtime, so leaving it at 10 would have re-issued 10/11/12
// and collided with them on the first `priceImport` factory creation.
let mockPriceImportFactorySeq = 13;
const mockPriceImportFactories = [
  { factoryId: 1, name: 'Panaria SpA',    country: 'Italy',   numberFormat: 'eu' },
  { factoryId: 2, name: 'REFIN',          country: 'Italy',   numberFormat: 'eu' },
  { factoryId: 3, name: 'Equipe',         country: 'Spain',   numberFormat: 'eu' },
  { factoryId: 4, name: 'Vives',          country: 'Spain',   numberFormat: 'eu' },
  { factoryId: 5, name: 'Bode',           country: 'Germany', numberFormat: 'us' },
  { factoryId: 6, name: 'CDE',            country: 'Italy',   numberFormat: 'eu' },
  { factoryId: 7, name: 'Padana Marmi',   country: 'Italy',   numberFormat: 'eu' },
  { factoryId: 8, name: 'LEA',            country: 'Italy',   numberFormat: 'eu' },
  { factoryId: 9, name: 'CITY Ceramica',  country: 'Italy',   numberFormat: 'eu' },
  // The three DOMESTIC factories every seeded deal actually names. Their absence here is
  // why /catalog was unusable: the page reads `catalog.prices` (mockProductPrices below),
  // whose rows were all European imports, while every factory quote in demoSales.js is from
  // SCG / Cotto / Duragres. A rep searching the brand on their own deal got
  // "ไม่พบสินค้าที่ตรงกัน". `numberFormat: 'us'` — Thai price lists use a decimal point.
  { factoryId: 10, name: 'SCG Ceramics',      country: 'Thailand', numberFormat: 'us' },
  { factoryId: 11, name: 'Cotto Industry',    country: 'Thailand', numberFormat: 'us' },
  { factoryId: 12, name: 'Duragres Thailand', country: 'Thailand', numberFormat: 'us' },
];

// Two separate ID spaces, deliberately. priceImport.upload() used to mint version
// IDs from mockProductPriceSeq — the *product price* counter — which collides once
// catalog.addProduct/priceImport.uploadAndCommit also consume it for real price rows.
let mockProductPriceSeq = 100;
let mockPriceVersionSeq = 100;
const mockProductPrices = [
  { priceId: 1, factoryId: 1, factoryName: 'Panaria SpA',  productCode: 'PAN-T600-IVO', grade: null,  collection: 'Trilogy',      productName: 'Ivory Lappato',    color: 'Ivory',   surface: 'Lappato',   sizeRaw: '60x120', price: 43.00, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: 0.72 },
  { priceId: 2, factoryId: 1, factoryName: 'Panaria SpA',  productCode: 'PAN-T600-GRY', grade: null,  collection: 'Trilogy',      productName: 'Grigio Naturale',  color: 'Grigio',  surface: 'Naturale',  sizeRaw: '60x120', price: 43.00, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: 0.72 },
  { priceId: 3, factoryId: 1, factoryName: 'Panaria SpA',  productCode: 'PAN-F800-ASH', grade: null,  collection: 'Frame',        productName: 'Ash',              color: 'Ash',     surface: 'Naturale',  sizeRaw: '80x80',  price: 38.50, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: 0.64 },
  { priceId: 4, factoryId: 2, factoryName: 'REFIN',        productCode: null,           grade: null,  collection: 'Terraço',      productName: 'L-Trim',           color: null,      surface: null,        sizeRaw: '10x60',  price: 38.00, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: null },
  { priceId: 5, factoryId: 2, factoryName: 'REFIN',        productCode: null,           grade: null,  collection: 'Terraço',      productName: 'Corner',           color: null,      surface: null,        sizeRaw: '10x10',  price: 55.00, currency: 'EUR', priceUnit: 'per_piece', sqmPerPiece: null },
  { priceId: 6, factoryId: 2, factoryName: 'REFIN',        productCode: 'RF-BAL-6060',  grade: null,  collection: 'Balneo',       productName: 'Floor Tile',       color: 'White',   surface: 'Lappato',   sizeRaw: '60x60',  price: 42.00, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: 0.36 },
  { priceId: 7, factoryId: 3, factoryName: 'Equipe',       productCode: 'EQ-001',       grade: null,  collection: 'Stromboli',    productName: '1.2X20 Jolly Ash', color: 'Ash',     surface: 'Mate',      sizeRaw: '1.2x20', price: 25.50, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: null },
  { priceId: 8, factoryId: 4, factoryName: 'Vives',        productCode: 'VV-001',       grade: null,  collection: 'Masia',        productName: 'Tile A',           color: 'Beige',   surface: 'Mate',      sizeRaw: "15'8X31'6", price: 5.50, currency: 'EUR', priceUnit: 'per_piece', sqmPerPiece: 0.05 },
  { priceId: 9, factoryId: 5, factoryName: 'Bode',         productCode: 'BVLE10426KGA', grade: null,  collection: 'Limestone',    productName: null,               color: null,      surface: 'Honed',     sizeRaw: '600x600',price: 23.50, currency: 'USD', priceUnit: 'per_sqm',   sqmPerPiece: 0.36 },
  { priceId: 10,factoryId: 7, factoryName: 'Padana Marmi', productCode: '0400012',      grade: 'A01', collection: 'Stone',        productName: null,               color: 'Beige',   surface: 'Lucidato',  sizeRaw: '60x120', price: 43.00, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: 0.72 },
  { priceId: 11,factoryId: 7, factoryName: 'Padana Marmi', productCode: '0400012',      grade: 'A02', collection: 'Stone',        productName: null,               color: 'Beige',   surface: 'Lucidato',  sizeRaw: '60x120', price: 21.50, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: 0.72 },
  // ── Domestic price list (THB) ───────────────────────────────────────────────────────────
  //
  // One row per SCG / Cotto / Duragres product in `mockCatalog` above, so the two fixtures
  // finally describe the same world. They did not: `mockCatalog` carried the Thai brands and
  // nothing reads it, while this list — which /catalog actually queries via `catalog.prices`
  // — held only European imports. Every factory quote in demoSales.js names one of these
  // three factories, so a rep searching "SCG" from a deal they own got no results at all.
  //
  // THB rather than EUR/USD on purpose: these are domestic suppliers quoting in baht, and it
  // keeps the FX-conversion path exercised by the import rows above rather than everywhere.
  // Prices are plausible retail-tier Thai porcelain rates for the size, not round numbers, so
  // a costing built on them looks like a real one.
  { priceId: 12,factoryId: 10,factoryName: 'SCG Ceramics',      productCode: 'SCG-ELG-6060-WH', grade: 'A',   collection: 'Elegance Series',   productName: 'Elegance ขาวนวล',   color: 'ขาวนวล',      surface: 'ด้าน',        sizeRaw: '60x60',  price: 420.00, currency: 'THB', priceUnit: 'per_sqm',   sqmPerPiece: 0.36 },
  { priceId: 13,factoryId: 10,factoryName: 'SCG Ceramics',      productCode: 'SCG-ELG-6060-GY', grade: 'A',   collection: 'Elegance Series',   productName: 'Elegance เทาอ่อน',  color: 'เทาอ่อน',     surface: 'ด้าน',        sizeRaw: '60x60',  price: 420.00, currency: 'THB', priceUnit: 'per_sqm',   sqmPerPiece: 0.36 },
  { priceId: 14,factoryId: 10,factoryName: 'SCG Ceramics',      productCode: 'SCG-NAT-3060-BG', grade: 'A',   collection: 'Natura Collection', productName: 'Natura เบจธรรมชาติ', color: 'เบจธรรมชาติ', surface: 'หยาบ',        sizeRaw: '30x60',  price: 385.00, currency: 'THB', priceUnit: 'per_sqm',   sqmPerPiece: 0.18 },
  { priceId: 15,factoryId: 10,factoryName: 'SCG Ceramics',      productCode: 'SCG-NAT-20100-WD',grade: 'B',   collection: 'Natura Collection', productName: 'Natura น้ำตาลไม้',  color: 'น้ำตาลไม้',   surface: 'หยาบ',        sizeRaw: '20x100', price: 465.00, currency: 'THB', priceUnit: 'per_sqm',   sqmPerPiece: 0.20 },
  { priceId: 16,factoryId: 10,factoryName: 'SCG Ceramics',      productCode: 'SCG-CRW-60120-PL',grade: 'A',   collection: 'Crystal White',     productName: 'Crystal ขาวมุก',    color: 'ขาวมุก',      surface: 'มัน',         sizeRaw: '60x120', price: 890.00, currency: 'THB', priceUnit: 'per_sqm',   sqmPerPiece: 0.72 },
  { priceId: 17,factoryId: 11,factoryName: 'Cotto Industry',    productCode: 'CT-MTS-3030-WH',  grade: 'A',   collection: 'Metro Square',      productName: 'Metro ขาว',         color: 'ขาว',         surface: 'ด้าน',        sizeRaw: '30x30',  price: 245.00, currency: 'THB', priceUnit: 'per_sqm',   sqmPerPiece: 0.09 },
  { priceId: 18,factoryId: 11,factoryName: 'Cotto Industry',    productCode: 'CT-MTS-3030-CR',  grade: 'A',   collection: 'Metro Square',      productName: 'Metro ครีม',        color: 'ครีม',        surface: 'ด้าน',        sizeRaw: '30x30',  price: 245.00, currency: 'THB', priceUnit: 'per_sqm',   sqmPerPiece: 0.09 },
  { priceId: 19,factoryId: 11,factoryName: 'Cotto Industry',    productCode: 'CT-STN-6060-DG',  grade: 'A',   collection: 'Stone Series',      productName: 'Stone เทาเข้ม',     color: 'เทาเข้ม',     surface: 'หยาบ',        sizeRaw: '60x60',  price: 505.00, currency: 'THB', priceUnit: 'per_sqm',   sqmPerPiece: 0.36 },
  { priceId: 20,factoryId: 11,factoryName: 'Cotto Industry',    productCode: 'CT-TMB-20120-LB', grade: 'A',   collection: 'Timber Line',       productName: 'Timber น้ำตาลอ่อน', color: 'น้ำตาลอ่อน',  surface: 'ลายไม้',      sizeRaw: '20x120', price: 610.00, currency: 'THB', priceUnit: 'per_sqm',   sqmPerPiece: 0.24 },
  // Same product at two grades — the domestic mirror of Padana Marmi's A01/A02 rows above,
  // so grade handling is exercised on a THB list too and a search on the code returns two rows.
  { priceId: 21,factoryId: 12,factoryName: 'Duragres Thailand', productCode: 'DG-GRP-6060-MG',  grade: 'A',   collection: 'Granite Plus',      productName: 'Granite เทากลาง',   color: 'เทากลาง',     surface: 'หยาบกึ่งมัน', sizeRaw: '60x60',  price: 470.00, currency: 'THB', priceUnit: 'per_sqm',   sqmPerPiece: 0.36 },
  { priceId: 22,factoryId: 12,factoryName: 'Duragres Thailand', productCode: 'DG-GRP-6060-MG',  grade: 'B',   collection: 'Granite Plus',      productName: 'Granite เทากลาง',   color: 'เทากลาง',     surface: 'หยาบกึ่งมัน', sizeRaw: '60x60',  price: 395.00, currency: 'THB', priceUnit: 'per_sqm',   sqmPerPiece: 0.36 },
  { priceId: 23,factoryId: 12,factoryName: 'Duragres Thailand', productCode: 'DG-GRP-6060-BK',  grade: 'A',   collection: 'Granite Plus',      productName: 'Granite ดำ',        color: 'ดำ',          surface: 'หยาบกึ่งมัน', sizeRaw: '60x60',  price: 470.00, currency: 'THB', priceUnit: 'per_sqm',   sqmPerPiece: 0.36 },
  // Priced per PIECE, not per sqm — the only domestic row that is, so the unit-basis branch
  // (and the per-piece -> per-sqm conversion a costing has to do) is reachable in THB.
  { priceId: 24,factoryId: 12,factoryName: 'Duragres Thailand', productCode: 'DG-POR-8080-WH',  grade: 'A',   collection: 'Porcelain Pro',     productName: 'Porcelain ขาวเนียน',color: 'ขาวเนียน',    surface: 'มัน',         sizeRaw: '80x80',  price: 545.00, currency: 'THB', priceUnit: 'per_piece', sqmPerPiece: 0.64 },
  // A row with NULL optional fields, mirroring REFIN's above: no product code, no colour, no
  // surface. /catalog must render it without holes, and it must not match a code search.
  { priceId: 25,factoryId: 11,factoryName: 'Cotto Industry',    productCode: null,              grade: null,  collection: 'Trim & Accessories',productName: 'บัวเชิงผนัง',       color: null,          surface: null,          sizeRaw: '10x60',  price: 175.00, currency: 'THB', priceUnit: 'per_piece', sqmPerPiece: null },
];

const mockPriceImportVersions = [
  { versionId: 1, factoryId: 1, label: 'Price List 2026 Q1', status: 'ACTIVE',   createdAt: '2026-01-10T09:00:00Z', uploadedByName: 'Admin' },
  { versionId: 2, factoryId: 2, label: 'REFIN 2026',         status: 'ACTIVE',   createdAt: '2026-02-01T11:00:00Z', uploadedByName: 'Admin' },
  { versionId: 3, factoryId: 5, label: 'Bode USD 2026',      status: 'ARCHIVED', createdAt: '2025-12-01T08:00:00Z', uploadedByName: 'Admin' },
];

const mockNoteTemplates = [
  { id: 1, text: 'ราคารวมค่าขนส่งถึงชั้น 1 ของหน่วยงานในเขต กทม. แต่ไม่รวมค่าตัด/ติดตั้ง', defaultSelected: true, sortOrder: 1 },
  { id: 2, text: 'จ่ายเช็คในนาม บจก. จี แอล แอนด์ อาร์ฯ / โอนเข้า กสิกรไทย 003-1-15914-8 (กระแสรายวัน สาขาสุขุมวิท 33)', defaultSelected: true, sortOrder: 2 },
  { id: 3, text: 'กรณีโอนเงินส่ง Pay-in มาที่ e-mail : info@glr.co.th', defaultSelected: true, sortOrder: 3 },
];

const mockDepositNotices = []; // used by both depositNotices and documents API groups
let mockDocSeq = 1;
let mockDocNumberSeq = 1;

// PricingRequest (commit 6): one deal may have several pricing requests (one
// per recipient / re-quote round). Stored as full detail records (summary
// fields + items + its own event log) so buildPricingRequestDetail never has
// to join across a second array.
const mockPricingRequests = [];
let mockPricingRequestSeq = 1;
let mockPricingRequestItemSeq = 1;
let mockPricingRequestEventSeq = 1;
const mockFactoryQuotes = [];
const mockPricingCostings = [];
const mockFactoryQuoteResponseReceipts = [];
// clientRequestId -> quoteId, for sendFactoryQuote()'s idempotency replay (mirrors
// sales.factory_quote_email_dispatch's unique (created_by, client_request_id) index).
const mockFactoryQuoteDispatchClientRequests = [];
let mockFactoryQuoteSeq = 1;
let mockFactoryQuoteItemSeq = 1;
let mockFactoryQuoteAttachmentSeq = 1;
let mockPricingRequestAttachmentSeq = 1;
let mockPricingCostingSeq = 1;
// Step 3: CEO Selling Price Decision (sales.pricing_decision / pricing_decision_item).
const mockPricingDecisions = [];
let mockPricingDecisionSeq = 1;
let mockPricingDecisionItemSeq = 1;
// Step 4: Customer Quotation Generation and Issuance — extends sales.quotation/quotation_item
// (owner's decision: ONE quotation aggregate, not a parallel table). Kept as its own flat mock
// array (like mockPricingDecisions) rather than nested inside a ticket's `.quotations`, since a
// Step 4 quotation is keyed by pricingRequestId first — the legacy ticket-item-driven
// `ticket.quotations` array is untouched by this section.
const mockCustomerQuotations = [];
let mockCustomerQuotationSeq = 1;
let mockCustomerQuotationItemSeq = 1;

// Sales/CRM state-matrix seed (chore/mock-demo-seed-state-matrix): this whole aggregate
// (mockPricingRequests through mockCustomerQuotations, plus mockDealActivities and
// mockAttachments above) starts completely empty every session — demoSales.js is the only
// source of rows for it. Every counter below is advanced past the highest seeded id so the
// first user-created row after boot can never collide with one of these.
{
  const salesSeed = db.salesSeed;
  mockAttachments.push(...salesSeed.attachments);
  mockAttachSeq = salesSeed.nextSeq.attach;
  mockDealActivities.push(...salesSeed.dealActivities);
  mockDealActivitySeq = salesSeed.nextSeq.dealActivity;
  mockDepositNotices.push(...salesSeed.depositNotices);
  mockDocSeq = salesSeed.nextSeq.doc;
  mockDocNumberSeq = salesSeed.nextSeq.docNumber;
  mockPricingRequests.push(...salesSeed.pricingRequests);
  mockPricingRequestSeq = salesSeed.nextSeq.pricingRequest;
  mockPricingRequestItemSeq = salesSeed.nextSeq.pricingRequestItem;
  mockPricingRequestEventSeq = salesSeed.nextSeq.pricingRequestEvent;
  mockPricingRequestAttachmentSeq = salesSeed.nextSeq.pricingRequestAttachment;
  mockFactoryQuotes.push(...salesSeed.factoryQuotes);
  mockFactoryQuoteSeq = salesSeed.nextSeq.factoryQuote;
  mockFactoryQuoteItemSeq = salesSeed.nextSeq.factoryQuoteItem;
  mockPricingCostings.push(...salesSeed.pricingCostings);
  mockPricingCostingSeq = salesSeed.nextSeq.pricingCosting;
  mockPricingDecisions.push(...salesSeed.pricingDecisions);
  mockPricingDecisionSeq = salesSeed.nextSeq.pricingDecision;
  mockPricingDecisionItemSeq = salesSeed.nextSeq.pricingDecisionItem;
  mockCustomerQuotations.push(...salesSeed.customerQuotations);
  mockCustomerQuotationSeq = salesSeed.nextSeq.customerQuotation;
  mockCustomerQuotationItemSeq = salesSeed.nextSeq.customerQuotationItem;
  // Deal-tracking readiness gate (dealIsReadyToAdvance) needs `ticket.nextFollowUpAt` set —
  // not part of the ticket's own literal object in demoSales.js since it's normally written
  // by tickets.setBilling, not ticket creation.
  for (const { ticketId, nextFollowUpAt } of salesSeed.followUpOverrides) {
    const ticket = db.tickets.find((t) => t.id === ticketId);
    if (ticket) ticket.nextFollowUpAt = nextFollowUpAt;
  }
  delete db.salesSeed;
}

// Mirrors CustomerService.VIEWER_ROLES, which itself aliases TicketAccessPolicy.VIEWER_ROLES
// rather than hand-copying it (issue #389 records a real divergence bug from hand-copying this
// exact set). Named here for the same reason: three customer reads share it, and an inline copy
// per call site is how the Java side drifted before. `requireTicketViewer` below wraps the same
// list but cannot be reused -- it takes a ticket id and applies a sales-ownership check.
const CUSTOMER_VIEWER_ROLES = ['sales', 'import', 'ceo', 'account', 'sales_manager'];
const PRICING_REQUEST_VIEWER_ROLES = ['sales', 'import', 'ceo', 'sales_manager'];
const PRICING_REQUEST_RECIPIENT_VALUES = PRICING_REQUEST_RECIPIENT_OPTIONS.map((o) => o.code);
const PRICING_REQUEST_QUANTITY_TYPE_VALUES = PRICING_REQUEST_QUANTITY_TYPE_OPTIONS.map((o) => o.code);
// Mirrors th.co.glr.hr.pricingrequest.UnitBasis.VALUES (financial-integrity review Finding B).
const UNIT_BASIS_VALUES = PRICING_REQUEST_UNIT_BASIS_OPTIONS.map((o) => o.code);

function nextPricingRequestCode() {
  return `PCR-2026-${String(mockPricingRequestSeq).padStart(4, '0')}`;
}

function findPricingRequestRaw(id) {
  const pr = mockPricingRequests.find((p) => p.id === Number(id));
  if (!pr) fail('ไม่พบคำขอราคานี้', 404);
  return pr;
}

// Mock stand-in for FactoryQuoteEmailDispatchWorker: send() only enqueues (dispatchStatus:
// 'PENDING'); this simulates the background worker claiming it (-> 'SENDING') and finalizing it
// (-> 'SENT', quote -> REQUESTED, pricing request status transition, FACTORY_EMAIL_SENT event) a
// short delay later, so PricingRequestDetailPage's polling has something real to observe.
function scheduleMockFactoryQuoteDispatch(quote, actor) {
  quote.dispatchStatus = 'SENDING';
  quote.dispatchAttemptCount = 1;
  setTimeout(() => {
    const current = mockFactoryQuotes.find((q) => q.id === quote.id);
    // Guard against a quote that moved on (e.g. was cancelled) while "in flight" — the closest
    // mock equivalent of the real worker's guarded, idempotent finalize.
    if (!current || current.status !== 'DRAFT' || current.dispatchStatus !== 'SENDING') return;
    current.status = 'REQUESTED';
    current.emailSentAt = new Date().toISOString();
    current.requestedAt = current.emailSentAt;
    current.sentBy = actor.id;
    current.updatedAt = current.emailSentAt;
    current.dispatchStatus = 'SENT';
    const pr = findPricingRequestRaw(current.pricingRequestId);
    const fromStatus = pr.status;
    // Mirrors FactoryQuoteService.attemptSend: V140 merged COSTING_IN_PROGRESS into
    // AWAITING_FACTORY_RESPONSE, so IMPORT_REVIEWING is the only status still needing promotion.
    if (pr.status === 'IMPORT_REVIEWING') pr.status = 'AWAITING_FACTORY_RESPONSE';
    pushPricingRequestEvent(pr, actor, 'FACTORY_EMAIL_SENT', fromStatus, pr.status);
  }, 700);
}

// Step 3, design correction 3 ("freeze factory mutations from CEO_REVIEWING"): mirrors
// FactoryQuoteService's RESPONSE_STATUSES/MUTABLE_STATUSES/DRAFT_STATUSES all deliberately
// excluding CEO_REVIEWING (the CEO owns the request from here until approve/return). Called at
// the top of every factory-quote mutation this branch touches.
function mockRequireNotCeoReviewing(pr) {
  if (pr.status === 'CEO_REVIEWING') {
    fail('คำขอราคานี้อยู่ระหว่างการพิจารณาของ CEO — ไม่สามารถแก้ไขราคาโรงงานได้ในขณะนี้', 409);
  }
}

function pushPricingRequestEvent(pr, actor, eventKind, fromStatus, toStatus, message = null, metadata = null) {
  pr.events.push({
    id: mockPricingRequestEventSeq++,
    pricingRequestId: pr.id,
    ticketId: pr.ticketId,
    actorId: actor.id,
    actorName: actor.name,
    eventKind,
    fromStatus,
    toStatus,
    message,
    metadata,
    createdAt: new Date().toISOString(),
  });
}

function round2(n) {
  return Math.round((Number(n) + Number.EPSILON) * 100) / 100;
}

// "Today" in the business zone, as "YYYY-MM-DD" -- the mock stand-in for the backend's
// `LocalDate.now(ZoneId.of("Asia/Bangkok"))`.
//
// Deliberately NOT `new Date().toISOString().slice(0, 10)`, which is the convention elsewhere in
// this file: that is UTC, and UTC runs up to 7 hours behind Bangkok. Any endpoint whose default
// window is derived from "today" (see specialMoney.list) would otherwise pick a different day --
// and on the 1st of a month before 07:00 Bangkok, a different MONTH -- than the service it mirrors.
// Uses en-CA because it formats as ISO "YYYY-MM-DD".
function bangkokTodayIso() {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
}

// Mirrors LeaveService.DEFAULT_WINDOW_MONTHS -- see leave.list's own comment for why this default
// exists and what it fixes. A plain number here rather than an import: this file has no build-time
// link to the Java source, so the two are kept in step by the cross-references in both comments.
// CLAUDE.md's warning applies -- contract.test.js checks the method surface and arity, never a
// constant's VALUE, so nothing fails if these two drift.
const LEAVE_DEFAULT_WINDOW_MONTHS = 12;

/**
 * "YYYY-MM-DD" shifted by whole months, clamping to the target month's last day.
 *
 * Clamping is the point, and it is why this is not `new Date(y, m + n, d)`: JS OVERFLOWS a
 * too-large day into the following month (2028-02-29 shifted -12 months would give 2027-03-01),
 * whereas Java's LocalDate.plusMonths/minusMonths -- the thing this mirrors -- CLAMPS to the last
 * valid day (2027-02-28). Only reachable on a leap day at the current +/-12-month usage, but the
 * helper is written to match the semantics it claims to mirror rather than to match today's
 * single call site.
 */
function shiftMonthsIso(iso, months) {
  const [year, month, day] = iso.split('-').map(Number);
  const target = new Date(Date.UTC(year, month - 1 + months, 1));
  const lastDayOfTargetMonth = new Date(Date.UTC(target.getUTCFullYear(), target.getUTCMonth() + 1, 0)).getUTCDate();
  target.setUTCDate(Math.min(day, lastDayOfTargetMonth));
  return target.toISOString().slice(0, 10);
}

// Step 6: mirrors OrderConfirmationService's own private unitLabel(), used when building a
// deposit-notice item from a customer-quotation item's requestedUnitBasis.
function mockUnitBasisLabel(unitBasis) {
  switch (unitBasis) {
    case 'PER_SQM': return 'ตร.ม.';
    case 'PER_PIECE': return 'แผ่น';
    case 'PER_BOX': return 'กล่อง';
    case 'PER_LINEAR_M': return 'เมตร';
    default: return unitBasis ?? 'หน่วย';
  }
}

// Branch fix (deposit-notice autofill): mirrors DepositNoticeService.itemsFromQuotation — the
// single shared mapping used by both depositNotices.createDraft's new-chain item fallback below
// and pricingRequests.createDepositNoticeFromQuotation, so the two paths can never drift apart.
function mockDepositNoticeItemsFromQuotation(items) {
  return items.map((item, idx) => ({
    seq: item.seq ?? idx + 1,
    description: item.description?.trim() || 'รายการสินค้า',
    qty: item.requestedQuantity,
    unit: mockUnitBasisLabel(item.requestedUnitBasis),
    unitPrice: item.approvedUnitPrice,
    discountLabel: item.salesDiscount > 0 ? `ส่วนลด ${item.salesDiscount} ต่อหน่วย` : null,
    netUnitPrice: item.finalUnitPrice,
  }));
}

// Branch fix: mirrors DepositNoticeService.pickQuotation — the latest ACCEPTED revision for
// this ticket, or (if none has been accepted yet) the latest ISSUED one. mockCustomerQuotations
// carries no guaranteed ordering, so sort ascending by (quotationRevisionNo, id) first — same
// contract as CustomerQuotationRepository.findByTicket — then a single forward scan overwriting
// "latest seen" per status is equivalent to sorting descending and taking the first match.
function mockPickTicketQuotationForDepositNotice(ticketId) {
  const candidates = mockCustomerQuotations
    .filter((q) => q.ticketId === Number(ticketId) && q.pricingRequestId != null)
    .sort((a, b) => (a.quotationRevisionNo - b.quotationRevisionNo) || (a.id - b.id));
  let latestAccepted = null;
  let latestIssued = null;
  for (const q of candidates) {
    if (q.docStatus === 'ACCEPTED') latestAccepted = q;
    else if (q.docStatus === 'ISSUED') latestIssued = q;
  }
  return latestAccepted ?? latestIssued ?? null;
}

// Branch fix: mirrors DepositNoticeService.createDraft's header autofill — customerTaxId/
// customerAddress/projectName were never populated for a deal created through the pricing-
// request chain. A caller-supplied non-blank value always wins; ticket.customerId == null
// safely leaves the customer-sourced fields blank.
function mockDepositNoticeHeaderAutofill(ticket, payload) {
  const blankToNull = (v) => (v == null || String(v).trim() === '' ? null : v);
  let customerTaxId = blankToNull(payload.customerTaxId);
  let customerAddress = blankToNull(payload.customerAddress);
  let projectName = blankToNull(payload.projectName);
  if ((customerTaxId == null || customerAddress == null) && ticket?.customerId != null) {
    const customer = mockCustomers.find((c) => c.id === ticket.customerId);
    if (customer) {
      if (customerTaxId == null) customerTaxId = blankToNull(customer.taxId);
      if (customerAddress == null) {
        customerAddress = blankToNull([customer.address, customer.branch].filter(Boolean).join(' '));
      }
    }
  }
  if (projectName == null) {
    const project = ticket?.projectId ? mockProjects.find((p) => p.id === ticket.projectId) : null;
    projectName = blankToNull(project?.name ?? null);
  }
  return { customerTaxId, customerAddress, projectName };
}

/**
 * Step 8: mirrors OrderConfirmationService.reconcileTicketItems — sales.ticket_item.qty is set
 * once at ticket creation and never touched by the PricingRequest chain, so it can drift from
 * what the chain actually settled on (both on a first submission and further after a
 * cost-affecting customer-change revision, which is reachable even from QUOTATION_ACCEPTED — see
 * that Java method's own Javadoc for the full account). Called once from confirmOrder, before any
 * delivery machinery becomes reachable, exactly like the real bridge.
 */
function reconcileTicketItemsFromPricingRequest(ticket, pr, user) {
  let anyChange = false;
  for (const item of pr.items ?? []) {
    const newQty = Number(item.requestedQty);
    if (item.sourceTicketItemId != null) {
      const ticketItem = ticket.items.find((ti) => ti.id === item.sourceTicketItemId);
      if (!ticketItem) continue;
      const qtyDelivered = Number(ticketItem.qtyDelivered ?? 0);
      const qtyFromStock = Number(ticketItem.qtyFromStock ?? 0);
      if (newQty < qtyDelivered || newQty < qtyFromStock) {
        // Mirrors the DB's chk_ticket_item_qty_delivered/chk_ticket_item_qty_from_stock CHECK
        // constraints (V54) that back the real reconcileItemQty call — a downward reconciliation
        // that would drop qty below an already-recorded delivered/reserved amount is refused, not
        // silently applied.
        fail(`ไม่สามารถปรับจำนวนสินค้า (item ${item.sourceTicketItemId}) ให้ตรงกับคำขอราคาที่ยืนยันคำสั่งซื้อได้ `
          + 'เนื่องจากมีการส่งมอบหรือจองสต็อกไปแล้วเกินจำนวนใหม่', 409);
      }
      if (Number(ticketItem.qty) !== newQty) {
        ticketItem.qty = newQty;
        ticketItem.qtySqm = item.requestedQtySqm ?? null;
        anyChange = true;
      }
    } else {
      // A wholly new line added by a customer-change revision — no original ticket_item to
      // reconcile against, so one is created now.
      const nextId = Math.max(0, ...ticket.items.map((ti) => ti.id)) + 1;
      ticket.items.push({
        id: nextId, ticketId: ticket.id,
        brand: item.brand || item.model || item.productDescription || 'รายการใหม่จากคำขอราคา',
        model: item.model ?? null, color: item.color ?? null, texture: item.texture ?? null,
        size: item.size ?? null, factory: item.factory ?? null,
        qty: newQty, qtySqm: item.requestedQtySqm ?? null,
        proposedPrice: null, approvedPrice: null,
        currency: 'THB', sortOrder: ticket.items.length,
        qtyDelivered: 0, qtyFromStock: 0,
        unitBasis: item.requestedUnitBasis === 'PER_SQM' ? 'SQM' : 'PIECE',
      });
      anyChange = true;
    }
  }
  // Bug fix (found on review, not deferred): close out any ticket_item this pricing-request
  // CHAIN used to reference but the currently-accepted revision dropped entirely — mirrors
  // TicketRepository.closeOutDroppedChainItems. Without this, a stale ticket_item with
  // qty > qtyDelivered permanently blocks completeDelivery's "no open quantities" check, since
  // nobody can ever deliver units of a product the customer no longer ordered.
  //
  // Walk to the root of pr's own revision chain (mirrors root_pricing_request_id), scoped to
  // THIS chain only — a ticket can carry independent pricing requests for other recipients
  // (designer/owner/buyer), which must never be touched just because they're absent here.
  let root = pr;
  while (root.parentPricingRequestId != null) {
    const parent = mockPricingRequests.find((p) => p.id === root.parentPricingRequestId);
    if (!parent) break;
    root = parent;
  }
  const chain = mockPricingRequests.filter((p) => {
    let walk = p;
    while (walk) {
      if (walk.id === root.id) return true;
      walk = walk.parentPricingRequestId != null
        ? mockPricingRequests.find((x) => x.id === walk.parentPricingRequestId)
        : null;
    }
    return false;
  });
  const everReferencedIds = new Set(
    chain.flatMap((p) => (p.items ?? []).map((i) => i.sourceTicketItemId)).filter((id) => id != null));
  const currentIds = new Set((pr.items ?? []).map((i) => i.sourceTicketItemId).filter((id) => id != null));
  for (const droppedId of everReferencedIds) {
    if (currentIds.has(droppedId)) continue;
    const ticketItem = ticket.items.find((ti) => ti.id === droppedId);
    if (!ticketItem) continue;
    const qtyDelivered = Number(ticketItem.qtyDelivered ?? 0);
    if (Number(ticketItem.qty) !== qtyDelivered) {
      ticketItem.qty = qtyDelivered;
      anyChange = true;
    }
  }
  if (anyChange) {
    pushPricingRequestEvent(pr, user, 'TICKET_ITEMS_RECONCILED', null, null,
      'ปรับจำนวนสินค้าในรายการดีลให้ตรงกับคำขอราคาที่ยืนยันคำสั่งซื้อแล้ว');
  }
}

/** Builds a fresh Step 4 DRAFT quotation, snapshotting prices from the current APPROVED
 * pricing_decision — mirrors CustomerQuotationService.create/createRevision's item-building
 * (buildItem). `priorDiscounts` (keyed by pricingRequestItemId) is empty for a first-ever
 * create and carries forward each line's discount for a revision. */
function buildMockCustomerQuotationDraft(pr, decision, ticket, user, payload, parentQuotationId, revisionNo, priorDiscounts) {
  const customer = ticket?.customerId ? mockCustomers.find((c) => c.id === ticket.customerId) : null;
  const project = ticket?.projectId ? mockProjects.find((p) => p.id === ticket.projectId) : null;
  const id = mockCustomerQuotationSeq++;
  const items = decision.items
    .filter((di) => di.approvedSellingPricePerRequestedUnit != null)
    .map((di, idx) => {
      const discount = Number(priorDiscounts?.[di.pricingRequestItemId] ?? 0);
      const approvedUnitPrice = Number(di.approvedSellingPricePerRequestedUnit);
      const finalUnitPrice = approvedUnitPrice - discount;
      const lineSubtotal = round2(finalUnitPrice * Number(di.requestedQuantity));
      const vat = round2(lineSubtotal * 0.07);
      return {
        id: mockCustomerQuotationItemSeq++,
        seq: idx + 1,
        pricingRequestItemId: di.pricingRequestItemId,
        pricingDecisionItemId: di.id,
        description: di.productDescription || [di.brand, di.model].filter(Boolean).join(' '),
        itemNotes: null,
        requestedUnitBasis: di.requestedUnitBasis,
        requestedQuantity: Number(di.requestedQuantity),
        approvedUnitPrice,
        salesDiscount: discount,
        finalUnitPrice,
        minimumSellingPricePerRequestedUnit: di.minimumSellingPricePerRequestedUnit ?? null,
        lineSubtotal,
        vat,
        lineTotal: round2(lineSubtotal + vat),
      };
    });
  const quotation = {
    id,
    number: `QT-2026-${String(id).padStart(4, '0')}`,
    ticketId: pr.ticketId,
    pricingRequestId: pr.id,
    pricingDecisionId: decision.id,
    recipientType: pr.recipientType,
    recipientLabel: pr.recipientLabel ?? null,
    docStatus: 'DRAFT',
    quotationVersion: revisionNo,
    quotationRevisionNo: revisionNo,
    parentQuotationId: parentQuotationId ?? null,
    issuedById: user.id,
    issuedByName: user.name,
    issuedAt: null,
    subtotalAmount: 0,
    vatAmount: 0,
    grandTotal: 0,
    currency: decision.currency || 'THB',
    paymentTerms: payload.paymentTerms || null,
    leadTime: payload.leadTime || null,
    deliveryTerms: payload.deliveryTerms || null,
    validityDate: payload.validityDate || null,
    customerNotes: payload.customerNotes || null,
    sentAt: null,
    acceptedAt: null,
    rejectedAt: null,
    createdAt: new Date().toISOString(),
    clientRequestId: payload.clientRequestId ?? null,
    issueClientRequestId: null,
    customerName: ticket?.customerName ?? (customer ? customer.name : null),
    customerAddress: customer ? customer.address : null,
    customerTaxId: customer ? customer.taxId : null,
    customerPhone: customer ? customer.phone : null,
    projectName: project ? project.name : null,
    items,
  };
  recalcMockCustomerQuotationTotals(quotation);
  return quotation;
}

function recalcMockCustomerQuotationTotals(quotation) {
  quotation.subtotalAmount = round2(quotation.items.reduce((sum, it) => sum + it.lineSubtotal, 0));
  quotation.vatAmount = round2(quotation.items.reduce((sum, it) => sum + it.vat, 0));
  quotation.grandTotal = round2(quotation.items.reduce((sum, it) => sum + it.lineTotal, 0));
}

// Read access: sales/sales_manager/ceo/import, sales scoped to their own deal. account is
// deliberately excluded end-to-end (task brief: "account role: no quotation editing", and
// Step 3's own precedent excludes account from every raw-pricing-adjacent view on this chain).
function mockCustomerQuotationViewAccess(id) {
  const user = hasRole('sales', 'sales_manager', 'ceo', 'import');
  const quotation = mockCustomerQuotations.find((q) => q.id === Number(id));
  if (!quotation) fail('ไม่พบใบเสนอราคาลูกค้านี้', 404);
  if (user.role === 'sales') {
    const pr = findPricingRequestRaw(quotation.pricingRequestId);
    const ticket = db.tickets.find((t) => t.id === pr.ticketId);
    if (ticket?.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
  }
  return quotation;
}

// Write access: sales, ticket owner only — ceo/import/sales_manager are read-only everywhere
// on this aggregate (caller must already have called hasRole('sales') for `user`).
function mockCustomerQuotationEditAccess(id, user) {
  const quotation = mockCustomerQuotations.find((q) => q.id === Number(id));
  if (!quotation) fail('ไม่พบใบเสนอราคาลูกค้านี้', 404);
  const pr = findPricingRequestRaw(quotation.pricingRequestId);
  const ticket = db.tickets.find((t) => t.id === pr.ticketId);
  if (ticket?.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
  return quotation;
}

// Demo-mode file preview for a Step 4 quotation — mirrors buildMockQuotationXlsx/
// buildMockQuotationHtml's own placeholder pattern (same styling/colors, same "real file comes
// from the server template" banner) rather than inventing a second preview style.
function buildMockCustomerQuotationDocument(quotation, format) {
  if (format === 'xlsx') {
    const lines = [
      `ใบเสนอราคา  เลขที่ ${quotation.number ?? ''} (revision ${quotation.quotationRevisionNo})`,
      `วันที่: ${mockThaiDate(quotation.issuedAt ? new Date(quotation.issuedAt) : new Date())}`,
      `ลูกค้า: ${quotation.customerName ?? ''}`,
      ...(quotation.projectName ? [`Project: ${quotation.projectName}`] : []),
      '',
      ...quotation.items.map((it, i) => `${i + 1}. ${it.description} — ${it.requestedQuantity} × ${it.finalUnitPrice}`),
    ];
    return Promise.resolve(mockDocPlaceholderBlob(lines));
  }
  const fmtNum = (n) => Number(n).toLocaleString('th-TH', { minimumFractionDigits: 2 });
  const rowsHtml = quotation.items.map((it, i) =>
    `<tr><td>${i + 1}</td><td>${it.description}</td><td style="text-align:right">${it.requestedQuantity}</td><td style="text-align:right">${fmtNum(it.finalUnitPrice)}</td><td style="text-align:right">${fmtNum(it.lineSubtotal)}</td></tr>`
  ).join('');
  const html = `<!DOCTYPE html><html lang="th"><head><meta charset="utf-8"/><title>ใบเสนอราคา ${quotation.number}</title>
<style>body{font-family:sans-serif;padding:40px;color:#1e293b;max-width:900px;margin:auto}
h2{margin:0 0 4px}.meta{color:#64748b;font-size:13px;margin-bottom:24px}
table{width:100%;border-collapse:collapse;margin-top:16px}
th{background:#f1f5f9;border:1px solid #cbd5e1;padding:8px 10px;text-align:left;font-size:13px}
td{border:1px solid #e2e8f0;padding:8px 10px;font-size:13px}
.banner{background:#fef3c7;border:1px solid #f59e0b;border-radius:6px;padding:10px 14px;margin-bottom:20px;font-size:13px;color:#92400e}
.total{font-weight:700;font-size:15px;text-align:right;margin-top:16px}</style></head>
<body><div class="banner">⚠ Demo Mode — PDF จริงสร้างจาก template บน server</div>
<h2>ใบเสนอราคา</h2>
<div class="meta">เลขที่: <strong>${quotation.number}</strong> revision ${quotation.quotationRevisionNo} &nbsp;|&nbsp; ลูกค้า: <strong>${quotation.customerName ?? ''}</strong></div>
<table><thead><tr><th>#</th><th>รายละเอียด</th><th>จำนวน</th><th>ราคา/หน่วย</th><th>เป็นเงิน (บาท)</th></tr></thead>
<tbody>${rowsHtml}</tbody>
<tfoot><tr><td colspan="4" style="text-align:right;font-weight:700">รวมเป็นเงิน</td><td style="text-align:right;font-weight:700">${fmtNum(quotation.subtotalAmount)}</td></tr></tfoot></table>
<div class="total">VAT 7%: ${fmtNum(quotation.vatAmount)} บาท &nbsp;|&nbsp; รวมทั้งสิ้น: ${fmtNum(quotation.grandTotal)} บาท</div></body></html>`;
  return Promise.resolve(new Blob([html], { type: 'text/html;charset=utf-8' }));
}

// Mirrors PricingRequestService.detail()'s join: the ticket a request belongs
// to is looked up fresh every time (never cached on the request record), same
// as PricingRequestSummaryDto's ticketCode/projectName/customerName/
// ticketCreatedById fields.
function buildPricingRequestSummary(pr) {
  const ticket = db.tickets.find((t) => t.id === pr.ticketId);
  return {
    id: pr.id,
    requestCode: pr.requestCode,
    ticketId: pr.ticketId,
    ticketCode: ticket?.code ?? null,
    projectName: ticket?.projectId ? (mockProjects.find((p) => p.id === ticket.projectId)?.name ?? null) : null,
    customerName: ticket?.customerName ?? null,
    ticketCreatedById: ticket?.createdById ?? null,
    recipientType: pr.recipientType,
    recipientContactId: pr.recipientContactId ?? null,
    recipientLabel: pr.recipientLabel ?? null,
    status: pr.status,
    requestedById: pr.requestedById,
    requestedByName: pr.requestedByName,
    assignedImportId: pr.assignedImportId ?? null,
    assignedImportName: pr.assignedImportName ?? null,
    requiredDate: pr.requiredDate ?? null,
    customerTargetPrice: pr.customerTargetPrice ?? null,
    targetCurrency: pr.targetCurrency ?? null,
    note: pr.note ?? null,
    itemCount: pr.items.length,
    revisionNo: pr.revisionNo ?? 1,
    parentPricingRequestId: pr.parentPricingRequestId ?? null,
    submittedAt: pr.submittedAt ?? null,
    pickedUpAt: pr.pickedUpAt ?? null,
    cancelledAt: pr.cancelledAt ?? null,
    createdAt: pr.createdAt,
    updatedAt: pr.updatedAt,
    // Step 6: non-null once pricingRequests.confirmOrder has bridged this (terminal,
    // QUOTATION_ACCEPTED) request into the legacy ticket payment/deposit pipeline.
    orderConfirmedAt: pr.orderConfirmedAt ?? null,
  };
}

function buildPricingRequestDetail(pr) {
  return { summary: buildPricingRequestSummary(pr), items: pr.items, events: pr.events };
}

function requirePricingRequestViewable(id, user) {
  if (!PRICING_REQUEST_VIEWER_ROLES.includes(user.role)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
  const pr = findPricingRequestRaw(id);
  const ticket = db.tickets.find((t) => t.id === pr.ticketId);
  const draftOversight = user.role === 'ceo' || user.role === 'sales_manager';
  if (pr.status === 'DRAFT' && !draftOversight && ticket?.createdById !== user.id) {
    fail('ไม่พบคำขอราคานี้', 404);
  }
  if (user.role === 'sales' && ticket?.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
  return pr;
}

function requirePricingRequestDealActive(ticket) {
  if ((ticket?.lifecycle ?? 'ACTIVE') !== 'ACTIVE') {
    fail(`ดีลไม่ได้อยู่ในสถานะ ACTIVE (${ticket?.lifecycle}) จึงสร้าง/แก้ไขคำขอราคาไม่ได้`, 409);
  }
}

// Mirrors PricingRequestRepository.normalizeCurrency: trim + uppercase,
// blank collapses to null. Applied on both insert and update so the mock
// never stores a raw/mixed-case currency the real column wouldn't have.
function normalizePricingRequestCurrency(targetCurrency) {
  if (targetCurrency == null || targetCurrency.trim() === '') return null;
  return targetCurrency.trim().toUpperCase();
}

// Mirrors PricingRequestRequests.PricingRequestItemRequest's Bean Validation
// annotations, which run BEFORE PricingRequestService even sees the request
// (@NotNull @DecimalMin("0.0001") requestedQty, @NotBlank requestedUnit,
// @NotBlank quantityType — quantityType's enum-membership is checked
// separately by the callers of this helper). A mock that skips this is the
// dangerous direction (issue #199): it would accept a blank unit / zero qty
// that the real backend 400s on.
function requirePricingRequestItemFieldsValid(items) {
  items.forEach((item, index) => {
    if (item.requestedQty == null || !(Number(item.requestedQty) >= 0.0001)) {
      fail('requestedQty ต้องมากกว่าหรือเท่ากับ 0.0001', 400);
    }
    if (!item.requestedUnit?.trim()) {
      fail('requestedUnit ต้องไม่เว้นว่าง', 400);
    }
    // Mirrors PricingRequestItemRequest's @NotBlank requestedUnitBasis (V68,
    // financial-integrity review Finding B) — the machine-readable basis
    // PricingCostingService now normalizes the requested quantity against.
    if (!item.requestedUnitBasis?.trim()) {
      fail('requestedUnitBasis ต้องไม่เว้นว่าง', 400);
    }
    if (!item.quantityType?.trim()) {
      fail('quantityType ต้องไม่เว้นว่าง', 400);
    }
    // Mirrors PricingRequestService.validateItems: an item must actually name
    // a product somehow — a link to an existing deal line, a catalog
    // product, a model name, or a dedicated product description. Brand alone
    // is deliberately NOT sufficient (a brand with no model does not
    // identify a product), so Import never receives a request for a line
    // nobody can actually source.
    const identified = item.sourceTicketItemId != null || item.productId != null
      || Boolean(item.model?.trim()) || Boolean(item.productDescription?.trim());
    if (!identified) {
      fail(`รายการที่ ${index + 1}: ต้องระบุสินค้าที่ต้องการเสนอราคา (เลือกจากรายการในดีล หรือระบุรุ่น/รายละเอียด)`, 400);
    }
  });
}

// Mirrors PricingRequestRepository.snapshotCatalogSelections: for each item whose productId
// matches an ACTIVE catalog price, populates the catalog snapshot fields the same way the real
// UPDATE...FROM does — including catalog_brand = COALESCE(pri.brand, pp.grade) and
// factory = COALESCE(NULLIF(BTRIM(pri.factory), ''), f.name). An item whose productId does not
// resolve (null productId, or no ACTIVE price_list_version for that product's factory) is left
// untouched — submitPricingRequestCatalogGate below is what rejects those.
function snapshotPricingRequestCatalogSelections(pr) {
  for (const item of pr.items) {
    if (item.productId == null) continue;
    const product = mockProductPrices.find((p) => p.priceId === item.productId);
    if (!product) continue;
    const activeVersion = mockPriceImportVersions.find(
      (v) => v.factoryId === product.factoryId && v.status === 'ACTIVE');
    if (!activeVersion) continue;
    const factory = mockPriceImportFactories.find((f) => f.factoryId === product.factoryId);
    item.priceListVersionId = activeVersion.versionId;
    item.catalogPriceId = product.priceId;
    item.catalogBasePrice = product.price;
    item.catalogCurrency = product.currency;
    item.catalogEffectiveDate = activeVersion.createdAt ?? null;
    item.resolvedFactoryId = product.factoryId;
    item.resolvedFactoryName = factory?.name ?? product.factoryName ?? null;
    item.catalogProductCode = product.productCode ?? null;
    item.catalogBrand = item.brand?.trim() ? item.brand : (product.grade ?? null);
    item.catalogCollection = product.collection ?? null;
    item.catalogModel = product.productName ?? null;
    item.factory = item.factory?.trim() ? item.factory : (factory?.name ?? product.factoryName ?? item.factory);
  }
}

// Mirrors PricingCostingService.requireFactor/missingFactor (financial-integrity review
// Finding B): 422 naming both the item and the missing conversion factor, rather than letting
// a null factor silently propagate into a wrong number (or a NaN).
function mockRequireConversionFactor(value, pricingRequestItemId, factorName) {
  const numeric = Number(value);
  if (value == null || !(numeric > 0)) {
    fail(`รายการที่ ${pricingRequestItemId} ในคำขอราคายังไม่มีค่าแปลงหน่วย ${factorName} ที่จำเป็นสำหรับคำนวณราคา/จำนวน`, 422);
  }
  return numeric;
}

// Mirrors PricingCostingService.pricePerPiece: converts a raw factory-quoted price to a
// per-PIECE figure using the QUOTE's own unit basis.
function mockPricePerPiece(rawPrice, quotedUnitBasis, factors, pricingRequestItemId) {
  switch (quotedUnitBasis) {
    case 'PER_PIECE': return Number(rawPrice);
    case 'PER_BOX': return Number(rawPrice) / mockRequireConversionFactor(factors.piecesPerBox, pricingRequestItemId, 'piecesPerBox');
    case 'PER_SQM': return Number(rawPrice) * mockRequireConversionFactor(factors.sqmPerUnit, pricingRequestItemId, 'sqmPerUnit');
    case 'PER_LINEAR_M': return Number(rawPrice) * mockRequireConversionFactor(factors.linearMPerUnit, pricingRequestItemId, 'linearMPerUnit');
    default: fail(`ไม่รองรับหน่วยนับของใบเสนอราคาโรงงาน '${quotedUnitBasis}'`, 422); return null;
  }
}

// Mirrors PricingCostingService.quantityToPieces: converts a requested quantity to a PIECE
// count using the REQUEST's own unit basis — independent of the quote's own basis above.
function mockQuantityToPieces(requestedQty, requestedUnitBasis, factors, pricingRequestItemId) {
  switch (requestedUnitBasis) {
    case 'PER_PIECE': return Number(requestedQty);
    case 'PER_BOX': return Number(requestedQty) * mockRequireConversionFactor(factors.piecesPerBox, pricingRequestItemId, 'piecesPerBox');
    case 'PER_SQM': return Number(requestedQty) / mockRequireConversionFactor(factors.sqmPerUnit, pricingRequestItemId, 'sqmPerUnit');
    case 'PER_LINEAR_M': return Number(requestedQty) / mockRequireConversionFactor(factors.linearMPerUnit, pricingRequestItemId, 'linearMPerUnit');
    default: fail(`ไม่รองรับหน่วยนับที่ขอ '${requestedUnitBasis}'`, 422); return null;
  }
}

function buildMockDoc(doc) {
  const items = doc.items ?? [];
  const depositPct = doc.depositPercent ?? 0.5;
  const subtotal = items.reduce((s, it) => s + (Number(it.netUnitPrice) || 0) * (Number(it.qty) || 0), 0);
  const deposit  = Math.round(subtotal * depositPct * 100) / 100;
  const vat      = Math.round(deposit * 0.07 * 100) / 100;
  const total    = Math.round((deposit + vat) * 100) / 100;
  return { ...doc, subtotal, depositAmount: deposit, vatAmount: vat, totalPayable: total };
}

function mockPreviewHtml(doc) {
  const money = (v) => v == null ? '—' : Number(v).toLocaleString('th-TH', { minimumFractionDigits: 2 });
  const depositPct = Math.round((doc.depositPercent ?? 0.5) * 100);
  let rows = (doc.items ?? []).map((it, i) =>
    `<tr><td>${i+1}</td><td>${it.description??''}</td><td style="text-align:right">${it.qty}</td><td>${it.unit??'แผ่น'}</td><td style="text-align:right">${money(it.unitPrice)}</td><td style="text-align:right">${money(it.netUnitPrice??it.unitPrice)}</td><td style="text-align:right">${money((it.netUnitPrice??it.unitPrice)*it.qty)}</td></tr>`
  ).join('');
  return `<!DOCTYPE html><html><head><meta charset="UTF-8"><style>body{font-family:sans-serif;padding:20px;font-size:13px}table{width:100%;border-collapse:collapse;margin:12px 0}th{background:#1e3a5f;color:#fff;padding:6px 8px;text-align:left}td{padding:5px 8px;border-bottom:1px solid #eee}.sum{text-align:right;width:300px;float:right;margin-top:12px}.sum td{padding:4px 8px}</style></head><body>
<div style="display:flex;justify-content:space-between;border-bottom:2px solid #1e3a5f;padding-bottom:12px;margin-bottom:16px">
  <div><strong>บริษัท จี แอล แอนด์ อาร์ จำกัด</strong><br><small>เลขภาษี 0105542026329</small></div>
  <div style="text-align:right"><strong style="font-size:16px">ใบแจ้งยอด / เงินรับมัดจำ</strong><br><code>${doc.docNumber??'DRAFT'}</code></div>
</div>
<div>เรียน: <strong>${doc.customerName??''}</strong></div>
<div style="color:#666;font-size:12px">${doc.customerAddress??''}</div>
${doc.projectName ? `<div>โครงการ: ${doc.projectName}</div>` : ''}
<table><thead><tr><th>ลำดับ</th><th>รายละเอียด</th><th>จำนวน</th><th>หน่วย</th><th>ราคา/หน่วย</th><th>ราคาสุทธิ</th><th>เป็นเงิน</th></tr></thead><tbody>${rows}</tbody></table>
<table class="sum"><tr><td>รวมเป็นเงิน</td><td style="text-align:right">${money(doc.subtotal)} บาท</td></tr>
<tr><td>มัดจำ ${depositPct}%</td><td style="text-align:right">${money(doc.depositAmount)} บาท</td></tr>
<tr><td>VAT 7% (จากมัดจำ)</td><td style="text-align:right">${money(doc.vatAmount)} บาท</td></tr>
<tr style="font-weight:bold;border-top:2px solid #1e3a5f"><td>รวมต้องชำระ</td><td style="text-align:right">${money(doc.totalPayable)} บาท</td></tr></table>
<div style="clear:both"></div>
${doc.notes?.length ? `<div style="margin-top:20px;font-size:12px"><strong>หมายเหตุ:</strong><ol>${doc.notes.map(n=>`<li>${n}</li>`).join('')}</ol></div>` : ''}
<div style="margin-top:30px;font-size:12px;color:#666">ผู้จัดทำ: จินตนา หาญมนตรี</div>
</body></html>`;
}

function delay(value) {
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(structuredClone(value)), 140);
  });
}

function fail(message, status = 400) {
  const error = new Error(message);
  error.status = status;
  throw error;
}

function publicUser(user) {
  if (!user) return null;
  const { password, ...safe } = user;
  const employee = employeeForUser(user);
  return {
    ...safe,
    divisionId: safe.divisionId ?? employee?.divisionId ?? null,
    manager: safe.manager ?? dashboardManager(user),
  };
}

function requireSession() {
  if (!sessionUser) fail('กรุณาเข้าสู่ระบบก่อนใช้งาน', 401);
  return sessionUser;
}

function hasRole(...roles) {
  const user = requireSession();
  if (!roles.includes(user.role)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
  return user;
}

// --- tax-allowance declaration helpers (PR A, 2026-08-01) ---
// Mirrors TaxAllowanceDeclarationDtos/TaxAllowanceDeclarationService/TaxAllowanceCapCatalog.

function taxAllowanceAllowancesFromBody(body = {}) {
  return {
    spouseAllowance: body.spouseAllowance ?? 0,
    childAllowance: body.childAllowance ?? 0,
    parentCareAllowance: body.parentCareAllowance ?? 0,
    disabledCareAllowance: body.disabledCareAllowance ?? 0,
    maternityAllowance: body.maternityAllowance ?? 0,
    lifeInsuranceAllowance: body.lifeInsuranceAllowance ?? 0,
    healthInsuranceAllowance: body.healthInsuranceAllowance ?? 0,
    parentHealthInsuranceAllowance: body.parentHealthInsuranceAllowance ?? 0,
    rmfAllowance: body.rmfAllowance ?? 0,
    ssfAllowance: body.ssfAllowance ?? 0,
    pensionInsuranceAllowance: body.pensionInsuranceAllowance ?? 0,
    thaiEsgAllowance: body.thaiEsgAllowance ?? 0,
    homeLoanInterestAllowance: body.homeLoanInterestAllowance ?? 0,
    educationDonation: body.educationDonation ?? 0,
    generalDonation: body.generalDonation ?? 0,
    politicalDonation: body.politicalDonation ?? 0,
    childCount: body.childCount ?? 0,
    childCountDouble: body.childCountDouble ?? 0,
    disabledCareCount: body.disabledCareCount ?? 0,
    disabilityCardHolder: Boolean(body.disabilityCardHolder),
    parentCareCount: body.parentCareCount ?? 0,
  };
}

/**
 * The non-amount half of แบบ ล.ย.01 — mirrors {@code TaxAllowanceDeclarationDtos.LorYor01Details},
 * whose javadoc says it is "never null on a read — empty() when the row predates the form".
 *
 * ⚠️ This exists because the mock used to DROP the whole block. `newTaxAllowanceDeclarationRow`
 * never copied `body.lorYor01`, and the seeded rows carried no `lorYor01` at all, so under
 * `VITE_USE_MOCKS=true` every filed declaration read back with a blank header, no ข้อ 1/2/4/6 ticks
 * and three sub-amounts (childExtra / spouseParentCare / providentFund) silently zeroed — including
 * one the employee had just typed and submitted in the same session. That is the third failure
 * shape in CLAUDE.md's table ("mock OMITS a field the feature keys on"): the read-only view of a
 * filed ล.ย.01 could never show what was filed, and no mock-driven test noticed because the fixture
 * never had the field either.
 *
 * Nulls mean "not answered", exactly as the Java record documents — a blank box and a ticked "no"
 * are different statements, so nothing here defaults an unanswered question to false.
 */
function taxAllowanceLorYor01FromBody(body = {}) {
  const detail = body.lorYor01 ?? {};
  const address = detail.address ?? {};
  const text = (value) => (value == null || String(value).trim() === '' ? null : String(value).trim());
  const num = (value) => (value == null || value === '' ? null : Number(value));
  const tick = (value) => (value == null ? null : Boolean(value));
  return {
    taxpayerId: text(detail.taxpayerId),
    firstNameTh: text(detail.firstNameTh),
    lastNameTh: text(detail.lastNameTh),
    address: {
      building: text(address.building), roomNo: text(address.roomNo), floor: text(address.floor),
      village: text(address.village), houseNo: text(address.houseNo), moo: text(address.moo),
      soi: text(address.soi), junction: text(address.junction), road: text(address.road),
      subDistrict: text(address.subDistrict), district: text(address.district),
      province: text(address.province), postalCode: text(address.postalCode),
    },
    maritalState: text(detail.maritalState),
    spousalStatus: text(detail.spousalStatus),
    spouseHasIncome: detail.spouseHasIncome == null ? null : Boolean(detail.spouseHasIncome),
    childrenTotal: num(detail.childrenTotal),
    childExtraAllowance: num(detail.childExtraAllowance) ?? 0,
    ownFatherSupported: tick(detail.ownFatherSupported),
    ownMotherSupported: tick(detail.ownMotherSupported),
    spouseFatherSupported: tick(detail.spouseFatherSupported),
    spouseMotherSupported: tick(detail.spouseMotherSupported),
    spouseParentCareAllowance: num(detail.spouseParentCareAllowance) ?? 0,
    ownFatherHealthInsured: tick(detail.ownFatherHealthInsured),
    ownMotherHealthInsured: tick(detail.ownMotherHealthInsured),
    spouseFatherHealthInsured: tick(detail.spouseFatherHealthInsured),
    spouseMotherHealthInsured: tick(detail.spouseMotherHealthInsured),
    providentFundAllowance: num(detail.providentFundAllowance) ?? 0,
    rmfSellerName: text(detail.rmfSellerName),
    otherDonationNote: text(detail.otherDonationNote),
  };
}

function newTaxAllowanceDeclarationRow({ employeeId, taxYear, effectiveMonth, allowances, lorYor01, documentReference, status, submittedById, onBehalf }) {
  const declarationId = Math.max(0, ...db.taxAllowanceDeclarations.map((row) => row.declarationId)) + 1;
  return {
    declarationId,
    employeeId,
    taxYear,
    effectiveMonth,
    allowances,
    lorYor01: lorYor01 ?? taxAllowanceLorYor01FromBody(),
    documentReference,
    status,
    submittedById,
    submittedAt: new Date().toISOString(),
    onBehalf,
    reviewedById: null,
    reviewedAt: null,
    reviewerNote: null,
    appliedAt: null,
    appliedById: null,
    appliedEffectiveMonth: null,
    expiresOn: null,
    expiredAt: null,
    reverifiedAt: null,
    reverifiedById: null,
    supersededById: null,
  };
}

/** Mirrors TaxAllowanceDeclarationRepository#supersedeApproved -- must run BEFORE the new row becomes APPROVED. */
function supersedeApprovedTaxAllowanceDeclarations(employeeId, taxYear, supersededById) {
  db.taxAllowanceDeclarations
    .filter((row) => row.employeeId === employeeId && row.taxYear === taxYear
      && row.status === 'APPROVED' && row.declarationId !== supersededById)
    .forEach((row) => {
      row.status = 'SUPERSEDED';
      row.supersededById = supersededById;
    });
}

function taxAllowanceDeclarationPublic(row) {
  const employee = db.employees.find((item) => item.id === row.employeeId);
  return {
    ...row,
    employeeCode: employee?.code ?? null,
    employeeName: employee?.nameTh ?? null,
  };
}

/**
 * ล.ย.01 header prefill. Mirrors TaxAllowanceDeclarationService#headerPrefill +
 * EmployeeRepository#findLorYor01HeaderSource.
 *
 * ⚠️ SHAPE ONLY — this is not evidence about the real prefill, and three things differ:
 *
 *  - **The real read is scoped by SQL** (`WHERE e.employee_id = :employeeId` against
 *    `hr_restricted.employee_pii`). CLAUDE.md is explicit that this mock's authorization is not
 *    authoritative; `TaxAllowanceHeaderPrefillIntegrationTest` is where the scoping is proven,
 *    wrong-way-round, against real Postgres.
 *  - **`hr_restricted` has no analogue here.** The mock employee's `sensitive` block is `{}`, so a
 *    faithful mirror would return a null `taxpayerId` and mock-mode click-through would show the
 *    one field this feature exists for as permanently empty — indistinguishable from the bug.
 *    A fabricated, obviously-fake demo tax ID is returned instead, and it is fake on purpose.
 *  - **`employee_address` has thirteen columns here and four.** The real query selects all
 *    thirteen; the mock employee record can only hold `line1`/`district`/`province`/`postalCode`,
 *    and `line1` is itself a CONCAT of four of them on the real side. Mapping it onto `houseNo` is
 *    the closest honest approximation; the remaining eight slots are null, not invented.
 */
function lorYor01HeaderPrefillFor(employeeId) {
  const employee = db.employees.find((item) => item.id === employeeId);
  const emptyAddress = {
    building: null, roomNo: null, floor: null, village: null, houseNo: null, moo: null,
    soi: null, junction: null, road: null, subDistrict: null, district: null, province: null,
    postalCode: null,
  };
  if (!employee) {
    return { taxpayerId: null, firstNameTh: null, lastNameTh: null, maritalState: null, address: emptyAddress };
  }
  // The backend splits stored first/last name columns; this mock only has the joined `nameTh`.
  const [firstNameTh, ...rest] = String(employee.nameTh ?? '').trim().split(/\s+/);
  const address = employee.currentAddress ?? {};
  const blank = (value) => (value == null || String(value).trim() === '' || value === '-' ? null : value);
  return {
    taxpayerId: '1100000000001',
    firstNameTh: blank(firstNameTh),
    lastNameTh: blank(rest.join(' ')),
    // Mirrors maritalStateFromMaster: only the two values the write-back can produce are mapped,
    // and anything else leaves ข้อ 1 un-ticked rather than guessing a legal status.
    maritalState: { 'โสด': 'SINGLE', 'สมรส': 'MARRIED' }[String(employee.maritalStatus ?? '').trim()] ?? null,
    address: {
      ...emptyAddress,
      houseNo: blank(address.line1),
      district: blank(address.district),
      province: blank(address.province),
      postalCode: blank(address.postalCode),
    },
  };
}

/**
 * Deduction obligation tracking (issue #373). Mirrors DeductionObligationRepository#insert +
 * mapRow's column set exactly, so a DTO round-tripped through this mock has the same shape the
 * real backend returns.
 */
function newDeductionObligationRow({ employeeId, kind, monthlyInstructedAmount, instructedTotal, authorityReference, startDate, notes, createdById }) {
  const id = Math.max(0, ...db.deductionObligations.map((row) => row.id)) + 1;
  const now = new Date().toISOString();
  return {
    id,
    employeeId,
    kind,
    monthlyInstructedAmount,
    instructedTotal: instructedTotal ?? null,
    authorityReference,
    startDate,
    status: 'ACTIVE',
    completedAt: null,
    completionAcknowledgedById: null,
    completionAcknowledgedAt: null,
    overrideContinuePastTotal: false,
    overrideById: null,
    overrideAt: null,
    overrideReason: null,
    notes: notes ?? null,
    createdById,
    createdAt: now,
    updatedById: createdById,
    updatedAt: now,
  };
}

function deductionObligationPublic(row) {
  const employee = db.employees.find((item) => item.id === row.employeeId);
  return {
    ...row,
    employeeCode: employee?.code ?? null,
    employeeName: employee?.nameTh ?? null,
  };
}

/**
 * Mirrors DeductionObligationService#buildProgress. totalRemitted is always 0 here -- see
 * db.deductionObligationRemittances' own comment on why mock mode never populates real remittance
 * rows -- so remaining always equals instructedTotal verbatim and the estimate is computed off
 * that. instructedTotal === null (no stated total, see V106's header) correctly leaves
 * remaining/estimatedPeriodsRemaining/estimatedEndMonth all null, same as the real service.
 */
function deductionObligationProgressPublic(row) {
  const totalRemitted = 0;
  const obligation = deductionObligationPublic(row);
  let remaining = null;
  let estimatedPeriodsRemaining = null;
  let estimatedEndMonth = null;
  if (row.instructedTotal != null) {
    remaining = Math.max(0, row.instructedTotal - totalRemitted);
    if (row.monthlyInstructedAmount > 0) {
      estimatedPeriodsRemaining = Math.ceil(remaining / row.monthlyInstructedAmount);
      const end = new Date();
      end.setDate(1);
      end.setMonth(end.getMonth() + estimatedPeriodsRemaining);
      estimatedEndMonth = end.toISOString().slice(0, 10);
    } else if (remaining === 0) {
      estimatedPeriodsRemaining = 0;
    }
  }
  return {
    obligation,
    totalRemitted,
    remaining,
    periodsRemitted: 0,
    estimatedPeriodsRemaining,
    estimatedEndMonth,
    ledger: [],
  };
}

/**
 * Evidence access rule (decision #5) -- mirrors TaxAllowanceDeclarationService#requireOwnerOrHr
 * exactly: owning employee OR hr, re-checked fresh on every call, never the uploader (no
 * AttachmentController#requireAttachmentAccess-style short-circuit), never ceo (evidence is a
 * personal medical/insurance/family document; ceo's read of the declaration REGISTER's amounts via
 * getTaxAllowanceDeclarations is unaffected). 404, never 403, so a foreign id does not leak.
 */
function requireTaxAllowanceAttachmentAccess(declaration, user) {
  const isOwner = user.employeeId != null && user.employeeId === declaration.employeeId;
  const isHr = user.role === 'hr';
  if (!isOwner && !isHr) fail('ไม่พบไฟล์แนบนี้', 404);
}

// Mirrors TaxAllowanceDeclarationService#EVIDENCE_SECTION_KEYS, which itself mirrors
// LOR_YOR_01_SECTIONS' keys in frontend/src/features/taxAllowance/taxAllowanceSchema.js. Kept in
// sync BY HAND in all three places — nothing enforces it, so a key added to the form and not here
// uploads fine in mock mode and 400s against the real service.
//
// The five invented category keys (family/insurance/savings/housing/donation) were replaced by one
// key per ข้อ when the page was restructured to follow the government form, plus `signed_form` for
// the signed scan the employee returns.
const TAX_ALLOWANCE_SECTION_KEYS = new Set([
  'item3', 'item4', 'item5', 'item6', 'item7', 'item8', 'item9', 'item10',
  'item12', 'item14', 'item15', 'signed_form',
]);

// Mirrors TaxAllowanceCapCatalog#capsFor exactly -- a lookup TABLE, not a computation, so this is a
// faithful stand-in rather than the "mock reimplements the algorithm" trap CLAUDE.md warns about.
// Both year conditions below have a named counterpart in the Java catalogue AND in PayrollCalculator;
// all three are kept in sync by hand, and TaxAllowanceCapCatalogTest drives the real calculator so
// the two Java copies cannot drift silently. This third copy has no such guard -- when a cap changes,
// change it here too or mock-mode UI quietly shows a different number than production.
function taxAllowanceCapsFor(taxYear) {
  const ssfDeductible = taxYear < 2025;
  // Thai ESG's enhanced ฿300,000 ceiling covers units bought 1 Jan 2024 - 31 Dec 2026 (ปีภาษี
  // 2567-2569); ฿100,000 outside that window on EITHER side. Unlike SSF this is not a sunset to
  // zero -- the deduction continues, only the ceiling steps down, and the 30% income rate is
  // unchanged either way. Mirrors THAI_ESG_ENHANCED_CAP_FIRST_TAX_YEAR / _LAST_TAX_YEAR.
  // The closed range is deliberate: SSF's cutoff is a one-way sunset so one comparison suffices,
  // but this enhancement is temporary at both ends -- don't "simplify" it to a single inequality.
  const thaiEsgEnhanced = taxYear >= 2024 && taxYear <= 2026;
  const thaiEsgCeiling = thaiEsgEnhanced ? 300000 : 100000;
  return [
    { category: 'personal', kind: 'FLAT', groupId: null, ownCap: 60000, groupCap: null, maxTotal: null, incomeRate: null, multiplier: null, declarable: false },
    { category: 'spouse', kind: 'FLAT', groupId: null, ownCap: 60000, groupCap: null, maxTotal: null, incomeRate: null, multiplier: null, declarable: true },
    { category: 'child', kind: 'PER_HEAD', groupId: null, ownCap: 30000, groupCap: null, maxTotal: null, incomeRate: null, multiplier: null, declarable: true },
    { category: 'child_double', kind: 'PER_HEAD', groupId: null, ownCap: 30000, groupCap: null, maxTotal: null, incomeRate: null, multiplier: null, declarable: true },
    { category: 'disabled_care', kind: 'PER_HEAD', groupId: null, ownCap: 60000, groupCap: null, maxTotal: null, incomeRate: null, multiplier: null, declarable: true },
    { category: 'parent_care', kind: 'PER_HEAD', groupId: null, ownCap: 30000, groupCap: null, maxTotal: 120000, incomeRate: null, multiplier: null, declarable: true },
    { category: 'maternity', kind: 'FLAT', groupId: null, ownCap: 60000, groupCap: null, maxTotal: null, incomeRate: null, multiplier: null, declarable: true },
    { category: 'life_insurance', kind: 'SHARED_GROUP', groupId: 'life_health', ownCap: 100000, groupCap: 100000, maxTotal: null, incomeRate: null, multiplier: null, declarable: true },
    { category: 'health_insurance', kind: 'SHARED_GROUP', groupId: 'life_health', ownCap: 25000, groupCap: 100000, maxTotal: null, incomeRate: null, multiplier: null, declarable: true },
    { category: 'parent_health_insurance', kind: 'FLAT', groupId: null, ownCap: 15000, groupCap: null, maxTotal: null, incomeRate: null, multiplier: null, declarable: true },
    // ข้อ 9 ล.ย.01 (V137). ownCap is กอช.'s 30,000, NOT the provident fund's 15%-of-wages-to-500,000
    // -- the box covers four funds and the engine takes the tightest, see
    // PayrollCalculator#retirementAllowance. Mirrors TaxAllowanceCapCatalog; contract.test.js checks
    // method surface and arity only, so nothing guards these VALUES -- change both together by hand.
    { category: 'provident_fund', kind: 'FLAT', groupId: 'retirement', ownCap: 30000, groupCap: 500000, maxTotal: null, incomeRate: null, multiplier: null, declarable: true },
    { category: 'rmf', kind: 'PERCENT_OF_INCOME', groupId: 'retirement', ownCap: 500000, groupCap: 500000, maxTotal: null, incomeRate: 0.30, multiplier: null, declarable: true },
    { category: 'ssf', kind: 'PERCENT_OF_INCOME', groupId: 'retirement', ownCap: ssfDeductible ? 200000 : 0, groupCap: 500000, maxTotal: null, incomeRate: ssfDeductible ? 0.30 : 0, multiplier: null, declarable: true },
    { category: 'pension', kind: 'PERCENT_OF_INCOME', groupId: 'retirement', ownCap: 200000, groupCap: 500000, maxTotal: null, incomeRate: 0.15, multiplier: null, declarable: true },
    { category: 'thai_esg', kind: 'PERCENT_OF_INCOME', groupId: null, ownCap: thaiEsgCeiling, groupCap: null, maxTotal: null, incomeRate: 0.30, multiplier: null, declarable: true },
    { category: 'home_loan_interest', kind: 'FLAT', groupId: null, ownCap: 100000, groupCap: null, maxTotal: null, incomeRate: null, multiplier: null, declarable: true },
    { category: 'education_donation', kind: 'PERCENT_OF_INCOME', groupId: 'donation', ownCap: null, groupCap: null, maxTotal: null, incomeRate: 0.10, multiplier: 2, declarable: true },
    { category: 'general_donation', kind: 'PERCENT_OF_INCOME', groupId: 'donation', ownCap: null, groupCap: null, maxTotal: null, incomeRate: 0.10, multiplier: null, declarable: true },
    { category: 'political_donation', kind: 'FLAT', groupId: null, ownCap: 10000, groupCap: null, maxTotal: null, incomeRate: null, multiplier: null, declarable: true },
  ];
}

// Issue #422 A6: a fabricated PayrollLineDto-shaped row for `payroll.current()`'s mock period --
// see that method's own comment for why a null period made the draft path unreachable. Deliberately
// simple round numbers rather than reproducing real Thai tax/SSO math (that stays "not supported in
// mock mode" on preview()/process() below) -- this only needs to be plausible enough to populate the
// table and exercise the draft save/restore round trip, not payroll-accurate.
function mockPayrollLine(employee) {
  // FIX 6 (adversarial review, issue #422): PayrollCalculator.SSO_MAX_BASE is 17500.00 (Royal
  // Gazette, 1 Jan 2026), not 15000 -- the pre-2026 ceiling. 15000 fed a fabricated ~750/month
  // SSO figure into totalDeductions/totalNet/totalGross and the stat strip, contradicting this
  // file's own "not payroll-accurate" boundary two paragraphs above with a wrong-by-construction
  // number rather than an honestly-approximate one.
  const ssoWageBase = Math.min(Number(employee.salary || 0), 17500);
  const socialSecurity = Math.round(ssoWageBase * 0.05);
  const specialPays = Array.from({ length: 9 }, (_, index) => ({
    key: `specialPay${index + 1}`,
    label: `พิเศษ ${index + 1}`,
    amount: 0,
  }));
  return {
    id: null,
    employeeId: employee.id,
    employeeCode: employee.code,
    employeeName: employee.nameTh,
    departmentName: employee.departmentTh,
    bankName: employee.bank,
    bankAccount: employee.bankAccount,
    baseSalary: employee.salary,
    dailyRate: 0,
    hourlyRate: 0,
    specialPays,
    specialPayTotal: 0,
    overtimePay: 0,
    commissionPay: 0,
    grossEarnings: employee.salary,
    nonTaxableIncome: 0,
    unpaidLeaveDays: 0,
    unpaidLeaveDeduction: 0,
    grossTaxableIncome: employee.salary,
    ssoWageBase,
    socialSecurity,
    projectedAnnualIncome: employee.salary * 12,
    taxExpenseDeduction: 0,
    taxAllowanceTotal: 100000,
    taxableAnnualIncome: employee.salary * 12,
    annualTax: 0,
    withholdingTax: 0,
    studentLoanDeduction: 0,
    legalExecutionDeduction: 0,
    otherPostTaxDeductions: 0,
    totalDeductions: socialSecurity,
    netPay: employee.salary - socialSecurity,
    calculationNote: null,
    directorRemuneration: 0,
    warningLetterDeduction: 0,
    customerReturnDeduction: 0,
    otherPretaxDeduction: 0,
    leaveRefundDays: 0,
    leaveDeductionRefund: 0,
    withholdingTaxOverride: null,
    mealAllowance: 0,
    // V128: auto-fed from approved welfare in production (PayrollRepository
    // #findApprovedWelfarePayByEmployee). The mock has no welfare->payroll pipeline, so this stays
    // 0 rather than pretending to compute it -- see this file's header on not mirroring backend math.
    welfarePay: 0,
    perDiemExempt: 0,
    perDiemTaxable: 0,
    perDiemBasis: null,
    bonusPay: 0,
    otherOneOffPay: 0,
    customerReturnAlreadyEarned: false,
    garnishmentType: null,
    customerReturnRequested: 0,
    daysWorked: null,
    payType: 'M',
  };
}

// --- ticket helpers ---

// Mirrors TicketService.requireViewAccess: viewer role required, sales reps
// only see their own tickets. Used by every read/render path. sales_manager is
// read+comment-only oversight — never add it to a write-action role list.
function requireTicketViewer(id) {
  const user = hasRole('sales', 'import', 'ceo', 'account', 'sales_manager');
  const ticket = findTicketRaw(Number(id));
  if (user.role === 'sales' && ticket.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
  return { user, ticket };
}

// Phase B (role-scoped views): deposit notices are a customer financial document
// end to end — mirrors DepositNoticeService.requireTicketViewer, which denies
// import outright (unlike requireTicketViewer above, which still lets import see
// the rest of the ticket). Not just a "same gate, plus one check" wrapper: keep
// this separate from requireTicketViewer so listDeliveries/actions (which import
// DOES need) never accidentally inherit the deposit-notice denial.
function requireDepositNoticeViewer(ticketId) {
  const result = requireTicketViewer(ticketId);
  if (result.user.role === 'import') fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
  return result;
}

// Phase B: import has no business reading the quotation chain — mirrors
// TicketService.projectForRole. A projection, not a wholesale redaction: every
// other field of the ticket detail response is untouched.
function projectTicketDetailForRole(detail, role) {
  if (role !== 'import') return detail;
  return { ...detail, quotation: null, quotations: [] };
}

// Phase B: role-scoped list membership — mirrors TicketRepository.appendRoleScope
// (backend ticket/ package), the actual enforcement; salesViewScope.js#dealInScope
// is presentation-only. import: an active (non-terminal) pricing request exists for
// the deal, OR the deal has reached PROCUREMENT or later. A closed/lost/cancelled
// deal is never in scope, even at a late stage.
const IMPORT_STAGE_SCOPE_START = dealStageIndex('PROCUREMENT');
const PRICING_REQUEST_TERMINAL_STATUSES = ['CANCELLED', 'SUPERSEDED', 'QUOTATION_ACCEPTED'];
const ACCOUNT_PENDING_PAYMENT_STATUSES = ['DEPOSIT_NOTICE_ISSUED', 'AWAITING_FINAL_PAYMENT'];

function isDealClosedOrLost(ticket) {
  return ['CLOSED_LOST', 'CANCELLED'].includes(ticket.lifecycle)
    || ['closed', 'cancelled'].includes(ticket.status);
}

function importListScopeIncludes(ticket) {
  if (isDealClosedOrLost(ticket)) return false;
  const hasActivePricingRequest = mockPricingRequests.some((pr) => pr.ticketId === ticket.id
    && !PRICING_REQUEST_TERMINAL_STATUSES.includes(pr.status));
  return hasActivePricingRequest || dealStageIndex(ticket.salesStage) >= IMPORT_STAGE_SCOPE_START;
}

// account: a deposit or final-payment confirmation is awaited, or the deal is
// overdue on an outstanding balance — same fields derivePaymentFields already
// computes for every list row (paymentStatus / overdue), so this just filters on them.
function accountListScopeIncludes(ticket) {
  if (ACCOUNT_PENDING_PAYMENT_STATUSES.includes(ticket.paymentStatus)) return true;
  return Boolean(derivePaymentFields(ticket).overdue);
}

function findTicketRaw(id) {
  const ticket = db.tickets.find((t) => t.id === id);
  if (!ticket) fail('ไม่พบดีลนี้', 404);
  return ticket;
}

// Mirrors AttachmentController + TicketAccessPolicy (ticket/TicketAccessPolicy.java). Issue #389
// split one blanket MANAGER_ROLES {hr, sales_manager, ceo} — wrong in both directions — into two
// questions:
//
//   READ  (canViewDocuments): participant (creator or assignee) OR a deal VIEWER_ROLE, with both
//         `sales` and `import` scoped to deals they participate in. So `hr` — which reads no deal,
//         quotation, ledger or activity feed anywhere — reads no deal document either, and
//         `account`, the role that confirms money against these files, finally can. `import` stays
//         out: AttachType spans SIGNED_QUOTATION/INVOICE, which carry the approved customer price
//         that projectForRole/loadQuotationContext/listPayments/DepositNoticeService all withhold
//         from it — reading the deal shell is deliberately not enough.
//   WRITE (canManageDocuments): participant OR {sales_manager, ceo}. Deliberately NOT `account`:
//         ฝ่ายบัญชี records the closing tax invoice through CommissionService.createFromDeal, which
//         dual-writes the INVOICE ticket attachment AND the rep's commission in one transaction. A
//         second account-writable upload path would satisfy the close gate's invoiceOnFile check
//         while the rep silently loses their commission.
//
// This namespace had NO role gate at all until 2026-07-30 — `requireSession()` and nothing else —
// which is why the same defect was misdiagnosed twice: `deposit-fulfilment-close.spec.js` drove
// `account` through an attachment upload that 403s against the real service, and passed. That is the
// issue-#199 shape CLAUDE.md names ("a mock more permissive than production is the dangerous
// direction"). Keep this in step with the Java gate — and note authz here is still an approximation,
// never the evidence for a permission claim.
// Note: NOT TicketAccessPolicy.VIEWER_ROLES — `import` is absent by design (see above), and
// `sales` is filtered by the participant check rather than by membership.
const ATTACHMENT_VIEWER_ROLES = ['ceo', 'account', 'sales_manager'];
const ATTACHMENT_WRITER_ROLES = ['sales_manager', 'ceo'];

function requireAttachmentTicketAccess(ticketId, { write = false } = {}) {
  const user = requireSession();
  const ticket = findTicketRaw(Number(ticketId));
  const isParticipant = ticket.createdById === user.id
    || (ticket.assignedToId != null && ticket.assignedToId === user.id);
  const allowed = isParticipant || (write
    ? ATTACHMENT_WRITER_ROLES.includes(user.role)
    : ATTACHMENT_VIEWER_ROLES.includes(user.role));
  if (!allowed) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
  return { user, ticket };
}

// Deal tracking (V83): activities for one ticket, oldest first (matches
// TicketRepository.findActivitiesByTicket's ORDER BY activity_date, created_at).
function dealActivitiesForTicket(ticketId) {
  return mockDealActivities
    .filter((a) => a.ticketId === Number(ticketId))
    .sort((a, b) => new Date(a.activityDate) - new Date(b.activityDate) || a.id - b.id)
    .map((a) => structuredClone(a));
}

function requireActive(ticket) {
  if ((ticket.lifecycle ?? 'ACTIVE') !== 'ACTIVE') {
    fail(`ดีลไม่ได้อยู่ในสถานะ ACTIVE (${ticket.lifecycle}) จึงแก้ไขขั้นตอนนี้ไม่ได้`, 409);
  }
}

function depositBypassesNotice(ticket) {
  return ['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'].includes(ticket.depositPolicy);
}

function moneyValue(value) {
  return Math.round((Number(value) || 0) * 100) / 100;
}

function payableAmount(ticket) {
  const quotations = ticket.quotations ?? (ticket.quotation ? [ticket.quotation] : []);
  const recipientRank = (recipient) => recipient === 'BUYER' ? 0 : recipient === 'OWNER' ? 1 : 2;
  const pickQuotation = (statuses) => [...quotations]
    .filter((q) => statuses.includes(q.docStatus ?? 'ISSUED'))
    .sort((a, b) => {
      const rank = recipientRank(a.recipientType) - recipientRank(b.recipientType);
      if (rank !== 0) return rank;
      return new Date(b.acceptedAt ?? b.issuedAt ?? 0) - new Date(a.acceptedAt ?? a.issuedAt ?? 0)
        || Number(b.id ?? 0) - Number(a.id ?? 0);
    })[0];
  const accepted = pickQuotation(['ACCEPTED']);
  if (accepted?.totalAmount != null) return moneyValue(accepted.totalAmount);
  const issued = pickQuotation(['ISSUED', 'SENT']);
  if (issued?.totalAmount != null) return moneyValue(issued.totalAmount);
  const notice = [...mockDepositNotices]
    .filter((d) => d.ticketId === ticket.id && d.status === 'ISSUED')
    .sort((a, b) => Number(b.version ?? 0) - Number(a.version ?? 0) || Number(b.id ?? 0) - Number(a.id ?? 0))[0];
  if (notice?.totalPayable != null) return moneyValue(notice.totalPayable);
  return moneyValue((ticket.items ?? []).reduce((sum, item) =>
    sum + (Number(item.approvedPrice) || 0) * (Number(item.qty) || 0), 0));
}

function receiptsForTicket(ticketId) {
  return (db.paymentReceipts ?? [])
    .filter((r) => r.ticketId === Number(ticketId))
    .sort((a, b) => new Date(a.receivedAt) - new Date(b.receivedAt) || a.receiptId - b.receiptId);
}

function sumPaid(ticketId) {
  return moneyValue(receiptsForTicket(ticketId).reduce((sum, receipt) =>
    sum + (receipt.kind === 'ADJUSTMENT' ? -Number(receipt.amount) : Number(receipt.amount)), 0));
}

function derivePaymentFields(ticket) {
  const payable = payableAmount(ticket);
  const paid = sumPaid(ticket.id);
  const outstanding = moneyValue(Math.max(0, payable - paid));
  const hasBalance = receiptsForTicket(ticket.id).some((receipt) => receipt.kind === 'BALANCE');
  let paymentStage = 'NOT_REQUIRED';
  if (payable > 0 && paid >= payable) paymentStage = 'FULLY_PAID';
  else if (payable > 0 && paid > 0) paymentStage = hasBalance ? 'PARTIALLY_PAID' : 'DEPOSIT_RECEIVED';
  else if (payable > 0 && !depositBypassesNotice(ticket)
      && ['CUSTOMER_CONFIRMED', 'DEPOSIT_NOTICE_ISSUED'].includes(ticket.paymentStatus)) paymentStage = 'DEPOSIT_PENDING';
  else if (payable > 0 && outstanding > 0) paymentStage = 'BALANCE_PENDING';
  const overdue = Boolean(ticket.dueDate && new Date(`${ticket.dueDate}T00:00:00`) < new Date() && outstanding > 0);
  return {
    billingDate: ticket.billingDate ?? null,
    dueDate: ticket.dueDate ?? null,
    creditTermDays: ticket.creditTermDays ?? null,
    lastFollowUpAt: ticket.lastFollowUpAt ?? null,
    nextFollowUpAt: ticket.nextFollowUpAt ?? null,
    paymentStage,
    amountPayable: payable,
    amountPaid: paid,
    amountOutstanding: outstanding,
    overdue,
  };
}

function latestIssuedDepositNotice(ticketId) {
  return [...mockDepositNotices]
    .filter((d) => d.ticketId === Number(ticketId) && d.status === 'ISSUED')
    .sort((a, b) => Number(b.version ?? 0) - Number(a.version ?? 0) || Number(b.id ?? 0) - Number(a.id ?? 0))[0] ?? null;
}

function reconcilePaymentStatus(ticket, user) {
  const payable = payableAmount(ticket);
  const paid = sumPaid(ticket.id);
  if (payable > 0 && paid >= payable) {
    if (ticket.paymentStatus !== 'FULLY_PAID') {
      ticket.paymentStatus = 'FULLY_PAID';
      pushEvent(ticket, user, 'FULLY_PAID', ticket.status, ticket.status, null);
      maybeAdvanceClosedPaid(ticket, user);
    }
    return;
  }
  if (paid <= 0 || ticket.paymentStatus === 'FULLY_PAID') return;
  const eligible = ticket.paymentStatus == null
    || ticket.paymentStatus === 'CUSTOMER_CONFIRMED'
    || ticket.paymentStatus === 'DEPOSIT_NOTICE_ISSUED'
    || depositBypassesNotice(ticket);
  if (eligible && ticket.paymentStatus !== 'DEPOSIT_PAID') {
    ticket.paymentStatus = 'DEPOSIT_PAID';
    pushEvent(ticket, user, 'DEPOSIT_PAID', ticket.status, ticket.status, null);
    autoAdvanceStage(ticket, 'DEPOSIT_RECEIVED', user);
    if (ticket.fulfillmentStatus === 'GOODS_RECEIVED') {
      ticket.paymentStatus = 'AWAITING_FINAL_PAYMENT';
      pushEvent(ticket, user, 'AWAITING_FINAL_PAYMENT', ticket.status, ticket.status, null);
    }
  }
}

function deliveryRecordsForTicket(ticketId) {
  return (db.deliveryRecords ?? [])
    .filter((record) => record.ticketId === Number(ticketId))
    .sort((a, b) => new Date(a.deliveredAt) - new Date(b.deliveredAt) || a.deliveryId - b.deliveryId)
    .map((record) => structuredClone(record));
}

// Mirrors TicketService.deliveryGateComplete. Previously this also accepted
// GOODS_RECEIVED with no delivery records; that concession was justified as a
// legacy allowance but legacy tickets close via the DOCUMENT_ISSUED branch and
// never reach this predicate, so it only ever loosened modern dual-track deals —
// letting a fully-paid deal close with the goods still in GLR's own warehouse.
function deliveryComplete(status) {
  return status === 'FULLY_DELIVERED';
}

// Attachments live in their own store (mockAttachments), not on the ticket —
// mirrors sales.attachment being its own table.
function hasInvoiceAttachment(ticket) {
  return mockAttachments.some((a) => a.ticketId === ticket.id && a.attachType === 'INVOICE');
}

// Mirrors TicketService.requireClosePrerequisites. Legacy document_issued deals
// predate the delivery and invoice tracks, so those two are waived for them —
// requiring either would strand old data permanently.
function requireClosePrerequisites(ticket) {
  const legacyOk = ticket.status === 'document_issued'
    && (ticket.paymentStatus == null || ticket.paymentStatus === 'FULLY_PAID');
  const dualTrackOk = ticket.status === 'quotation_issued'
    && ticket.paymentStatus === 'FULLY_PAID'
    && deliveryComplete(ticket.fulfillmentStatus);
  if (!legacyOk && !dualTrackOk) {
    fail('ปิดงานไม่ได้: ต้องรับเงินครบและส่งมอบสินค้าครบก่อน', 409);
  }
  if (derivePaymentFields(ticket).amountOutstanding > 0) {
    fail('ปิดงานไม่ได้: ยังมียอดค้างชำระ', 409);
  }
  if (dualTrackOk && !hasInvoiceAttachment(ticket)) {
    fail('ปิดงานไม่ได้: ยังไม่ได้แนบใบกำกับภาษี (ฝ่ายบัญชีต้องอัปโหลดก่อน)', 409);
  }
}

// Mirrors TicketService.maybeAdvanceClosedPaid: CLOSED_PAID (S20) requires BOTH
// gates — payment fully paid AND goods actually delivered (FULLY_DELIVERED).
// Now the same rule the manual close uses; the two agree on "delivered".
function maybeAdvanceClosedPaid(ticket, user) {
  if (ticket.paymentStatus === 'FULLY_PAID' && ticket.fulfillmentStatus === 'FULLY_DELIVERED') {
    autoAdvanceStage(ticket, 'CLOSED_PAID', user);
  }
}

function hasRemainingDelivery(ticket) {
  return (ticket.items ?? []).some((item) => Number(item.qtyDelivered ?? 0) < Number(item.qty ?? 0));
}

// Goods reaching the warehouse is a permanent fact (the GOODS_RECEIVED event), not the
// mutable fulfillmentStatus — so a stock-first partial delivery can't lock out the
// warehouse remainder (mirrors TicketService.warehouseDeliveryAvailable — Case 8 fix).
function hasReceivedGoods(ticketId) {
  const ticket = db.tickets.find((t) => t.id === Number(ticketId));
  return (ticket?.events ?? []).some((ev) => ev.kind === 'GOODS_RECEIVED');
}

function warehouseAvailableFor(ticket) {
  return ticket.fulfillmentStatus === 'GOODS_RECEIVED' || hasReceivedGoods(ticket.id);
}

function deliveryAvailable(ticket) {
  const stockAvailable = (ticket.items ?? []).some((item) => Number(item.qtyFromStock ?? 0) > Number(item.qtyDelivered ?? 0));
  return ticket.fulfillmentStatus === 'FROM_STOCK' || stockAvailable || warehouseAvailableFor(ticket);
}

function reserveStockForTicket(ticket, user, payload = {}) {
  const lines = payload.lines ?? [];
  if (!lines.length) fail('ต้องระบุรายการสินค้า', 400);
  let total = 0;
  for (const line of lines) {
    const item = ticket.items.find((it) => it.id === Number(line.itemId));
    if (!item) fail('ไม่พบรายการนี้ในดีล', 404);
    const qty = moneyValue(line.qtyFromStock);
    if (qty < 0 || qty > Number(item.qty || 0)) fail('จำนวนสินค้าจากสต็อกต้องไม่เกินจำนวนที่สั่ง', 400);
    item.qtyFromStock = qty;
    item.stockNote = line.note ?? null;
    total += qty;
  }
  pushEvent(ticket, user, 'STOCK_RESERVED', ticket.status, ticket.status, `qty_from_stock=${total}`);
  const allCovered = (ticket.items ?? []).length > 0
    && ticket.items.every((item) => Number(item.qtyFromStock ?? 0) >= Number(item.qty ?? 0));
  if (allCovered && (ticket.fulfillmentStatus == null || ticket.fulfillmentStatus === 'FROM_STOCK')) {
    ticket.fulfillmentStatus = 'FROM_STOCK';
    // Full stock coverage has no import journey — goods are ready now, so advance
    // straight to DELIVERY_SCHEDULING (S18) rather than PROCUREMENT.
    autoAdvanceStage(ticket, 'DELIVERY_SCHEDULING', user);
  }
}

function recordDeliveryForTicket(ticket, user, payload = {}, completing = false) {
  const source = String(payload.source ?? '').trim().toUpperCase();
  if (!['WAREHOUSE', 'STOCK'].includes(source)) fail('source ต้องเป็น WAREHOUSE หรือ STOCK', 400);
  const lines = payload.lines ?? [];
  if (!lines.length) fail('ต้องระบุรายการส่งสินค้า', 400);
  const combined = new Map();
  for (const line of lines) {
    const item = ticket.items.find((it) => it.id === Number(line.itemId));
    if (!item) fail('ไม่พบรายการนี้ในดีล', 404);
    const qty = moneyValue(line.qty);
    if (qty <= 0) fail('จำนวนส่งมอบต้องมากกว่า 0', 400);
    combined.set(item.id, moneyValue((combined.get(item.id) ?? 0) + qty));
  }
  for (const [itemId, qty] of combined.entries()) {
    const item = ticket.items.find((it) => it.id === itemId);
    const newDelivered = moneyValue(Number(item.qtyDelivered ?? 0) + qty);
    if (newDelivered > Number(item.qty ?? 0)) fail('จำนวนส่งมอบเกินจำนวนที่สั่ง', 409);
    if (source === 'STOCK' && newDelivered > Number(item.qtyFromStock ?? 0)) {
      fail('ส่งจากสต็อกได้ไม่เกินจำนวนที่ประกาศว่าพร้อมจากสต็อก', 409);
    }
  }
  if (source === 'WAREHOUSE' && !warehouseAvailableFor(ticket)) {
    fail('ต้องรับสินค้าเข้าโกดังก่อนส่งจาก WAREHOUSE', 409);
  }
  const now = new Date().toISOString();
  const nextId = Math.max(0, ...(db.deliveryRecords ?? []).map((record) => record.deliveryId)) + 1;
  let itemSeq = Math.max(0, ...(db.deliveryRecords ?? []).flatMap((record) => record.items ?? []).map((item) => item.deliveryItemId)) + 1;
  const recordItems = [];
  for (const [itemId, qty] of combined.entries()) {
    const item = ticket.items.find((it) => it.id === itemId);
    item.qtyDelivered = moneyValue(Number(item.qtyDelivered ?? 0) + qty);
    recordItems.push({ deliveryItemId: itemSeq++, itemId, qty });
  }
  db.deliveryRecords.push({
    deliveryId: nextId,
    ticketId: ticket.id,
    source,
    deliveredAt: now,
    deliveredById: user.id,
    deliveredByName: user.name,
    note: payload.note ?? null,
    // Step 8: who on the customer's side received/confirmed this delivery — optional, mirrors
    // TicketRepository.insertDeliveryRecord's new recipientName column (V78).
    recipientName: payload.recipientName ?? null,
    createdAt: now,
    items: recordItems,
  });
  const message = recordItems.map((line) => {
    const item = ticket.items.find((it) => it.id === line.itemId);
    return `${line.itemId}: ${Number(item.qtyDelivered).toLocaleString('en-US')}/${Number(item.qty).toLocaleString('en-US')}`;
  }).join(', ');
  pushEvent(ticket, user, 'DELIVERY_RECORDED', ticket.status, ticket.status, message);
  const full = ticket.items.every((item) => Number(item.qtyDelivered ?? 0) >= Number(item.qty ?? 0));
  if (full) {
    ticket.fulfillmentStatus = 'FULLY_DELIVERED';
    pushEvent(ticket, user, 'DELIVERY_COMPLETED', ticket.status, ticket.status, completing ? 'ส่งมอบครบ' : message);
    autoAdvanceStage(ticket, 'DELIVERED', user);
    // Second CLOSED_PAID gate: a deal paid in full before delivery closes exactly
    // when delivery completes.
    maybeAdvanceClosedPaid(ticket, user);
  } else {
    ticket.fulfillmentStatus = 'PARTIALLY_DELIVERED';
  }
}

function recordPaymentForTicket(ticket, user, payload) {
  const kind = String(payload.kind ?? '').trim().toUpperCase();
  if (!['DEPOSIT', 'BALANCE', 'ADJUSTMENT'].includes(kind)) fail(`ไม่รองรับประเภทการรับชำระเงิน '${payload.kind}'`, 400);
  const amount = moneyValue(payload.amount);
  if (amount <= 0) fail('ยอดรับชำระต้องมากกว่า 0', 400);
  const payable = payableAmount(ticket);
  const paid = sumPaid(ticket.id);
  const signed = kind === 'ADJUSTMENT' ? -amount : amount;
  const newPaid = moneyValue(paid + signed);
  if (newPaid < 0) fail('ยอดรับชำระสุทธิห้ามติดลบ', 400);
  const note = (payload.note ?? '').trim() || null;
  if (newPaid > payable && !payload.allowOverpayment) fail('ยอดรับชำระเกินยอดที่ต้องชำระ กรุณายืนยัน overpayment พร้อมเหตุผล', 400);
  if (newPaid > payable && !note) fail('การรับชำระเกินยอดต้องระบุเหตุผล', 400);
  const receiptRef = (payload.receiptRef ?? '').trim() || null;
  if (receiptRef && (db.paymentReceipts ?? []).some((r) => r.ticketId === ticket.id && r.receiptRef === receiptRef)) {
    fail('เลขอ้างอิงรับชำระซ้ำ', 409);
  }
  const nextId = Math.max(0, ...(db.paymentReceipts ?? []).map((r) => r.receiptId)) + 1;
  const now = new Date().toISOString();
  db.paymentReceipts.push({
    receiptId: nextId,
    ticketId: ticket.id,
    kind,
    amount,
    currency: 'THB',
    receivedAt: payload.receivedAt || now,
    recordedById: user.id,
    recordedByName: user.name,
    note,
    depositNoticeId: payload.depositNoticeId ?? null,
    receiptRef,
    createdAt: now,
  });
  pushEvent(ticket, user, 'PAYMENT_RECORDED', ticket.status, ticket.status,
    `kind=${kind}, amount=${amount}, paid=${newPaid}, payable=${payable}${note ? ` — ${note}` : ''}`);
  reconcilePaymentStatus(ticket, user);
  ticket.updatedAt = now.slice(0, 10);
}

// ── Thai date helper (mirrors QuotationRenderer.java thaiDate) ───────────────
const MOCK_THAI_MONTHS = ['มกราคม','กุมภาพันธ์','มีนาคม','เมษายน','พฤษภาคม','มิถุนายน','กรกฎาคม','สิงหาคม','กันยายน','ตุลาคม','พฤศจิกายน','ธันวาคม'];
function mockThaiDate(d) {
  if (!d) return '';
  const date = d instanceof Date ? d : new Date(d);
  return `${date.getDate()} ${MOCK_THAI_MONTHS[date.getMonth()]} ${date.getFullYear() + 543}`;
}

function mockItemDesc(it) {
  return [it.brand, it.model, it.color, it.texture, it.size].filter(Boolean).join(' ');
}

// Demo-mode placeholder blob. The real xlsx is rendered server-side by Apache POI
// (QuotationRenderer/RemainingInvoiceRenderer/DepositNoticeRenderer) and streamed to the
// client; mock mode only needs to return a valid Blob so download callers stay happy without
// pulling in the SheetJS (`xlsx`) dependency, which carries an unpatched high-severity advisory.
function mockDocPlaceholderBlob(lines) {
  const body = ['⚠ Demo Mode — ไฟล์จริงสร้างจาก template บน server (Apache POI)', '', ...lines].join('\n');
  return new Blob([body], { type: 'text/plain;charset=utf-8' });
}

// ── Quotation XLSX (demo placeholder) — real file from QuotationRenderer.java ───
// Mirrors TicketService.loadQuotationContext (V49): if this quotation has a snapshot
// (items + customer/project header frozen at issue time), render from that — never from
// today's live ticket data. Older mock quotations (created before this change, or a
// freshly-loaded page that never re-ran quotation()) have no `items` array and fall back
// to live data, matching the backend's legacy-quotation fallback.
async function buildMockQuotationXlsx(ticketId, quotationId) {
  const ticket = findTicketRaw(Number(ticketId));
  const quotation = (ticket.quotations ?? []).find((q) => q.id === Number(quotationId));
  if (!quotation) fail('ไม่พบใบเสนอราคานี้', 404);

  const hasSnapshot = Array.isArray(quotation.items) && quotation.items.length > 0;
  const issueDate = quotation.issuedAt ? new Date(quotation.issuedAt) : new Date();
  const priceItems = hasSnapshot ? quotation.items : ticket.items.filter((it) => it.approvedPrice != null);
  const customerName = hasSnapshot ? (quotation.customerName ?? '') : (ticket.customerName ?? '');
  const projectName = hasSnapshot ? quotation.projectName : ticket.projectName;
  const lines = [
    `ใบเสนอราคา  เลขที่ ${quotation.number ?? ''}`,
    `วันที่: ${mockThaiDate(issueDate)}`,
    `ลูกค้า: ${customerName}`,
    ...(projectName ? [`Project: ${projectName}`] : []),
    '',
    ...priceItems.map((it, i) => {
      const qty = Number(it.qty) || 0;
      const price = Number(it.approvedPrice) || 0;
      return `${i + 1}. ${mockItemDesc(it)} — ${qty} ${it.rawUnit ?? 'แผ่น'} × ${price}`;
    }),
  ];
  return mockDocPlaceholderBlob(lines);
}

// ── Quotation HTML preview — shown when "PDF" is clicked in demo mode ────────
// Same snapshot-first / live-data-fallback rule as buildMockQuotationXlsx above.
function buildMockQuotationHtml(ticketId, quotationId) {
  const ticket = findTicketRaw(Number(ticketId));
  const quotation = (ticket.quotations ?? []).find((q) => q.id === Number(quotationId));
  if (!quotation) fail('ไม่พบใบเสนอราคานี้', 404);
  const hasSnapshot = Array.isArray(quotation.items) && quotation.items.length > 0;
  const priceItems = hasSnapshot ? quotation.items : ticket.items.filter((it) => it.approvedPrice != null);
  const customerName = hasSnapshot ? (quotation.customerName ?? '') : (ticket.customerName ?? '');
  const fmtNum = (n) => Number(n).toLocaleString('th-TH', { minimumFractionDigits: 2 });
  const rowsHtml = priceItems.map((it, i) => {
    const amt = Number(it.approvedPrice) * Number(it.qty);
    return `<tr><td>${i+1}</td><td>${mockItemDesc(it)}</td><td style="text-align:right">${Number(it.qty).toLocaleString('th-TH')}</td><td>${it.rawUnit ?? 'แผ่น'}</td><td style="text-align:right">${fmtNum(it.approvedPrice)}</td><td style="text-align:right">${fmtNum(amt)}</td></tr>`;
  }).join('');
  const total = fmtNum(quotation.totalAmount ?? 0);
  const html = `<!DOCTYPE html><html lang="th"><head><meta charset="utf-8"/><title>ใบเสนอราคา ${quotation.number}</title>
<style>body{font-family:sans-serif;padding:40px;color:#1e293b;max-width:900px;margin:auto}
h2{margin:0 0 4px}.meta{color:#64748b;font-size:13px;margin-bottom:24px}
table{width:100%;border-collapse:collapse;margin-top:16px}
th{background:#f1f5f9;border:1px solid #cbd5e1;padding:8px 10px;text-align:left;font-size:13px}
td{border:1px solid #e2e8f0;padding:8px 10px;font-size:13px}
.banner{background:#fef3c7;border:1px solid #f59e0b;border-radius:6px;padding:10px 14px;margin-bottom:20px;font-size:13px;color:#92400e}
.total{font-weight:700;font-size:15px;text-align:right;margin-top:16px}</style></head>
<body><div class="banner">⚠ Demo Mode — PDF จริงสร้างจาก template บน server</div>
<h2>ใบเสนอราคา</h2>
<div class="meta">เลขที่: <strong>${quotation.number}</strong> &nbsp;|&nbsp; ลูกค้า: <strong>${customerName}</strong> &nbsp;|&nbsp; วันที่: ${mockThaiDate(new Date(quotation.issuedAt))}</div>
<table><thead><tr><th>#</th><th>รายละเอียด</th><th>จำนวน</th><th>หน่วย</th><th>ราคา/หน่วย</th><th>เป็นเงิน (บาท)</th></tr></thead>
<tbody>${rowsHtml}</tbody>
<tfoot><tr><td colspan="5" style="text-align:right;font-weight:700">รวมเป็นเงิน</td><td style="text-align:right;font-weight:700">${total}</td></tr></tfoot></table>
<div class="total">ยอดรวมทั้งสิ้น: ${total} บาท</div></body></html>`;
  return new Blob([html], { type: 'text/html;charset=utf-8' });
}

// ── Remaining invoice XLSX (demo placeholder) — real file from RemainingInvoiceRenderer.java ─
async function buildMockRemainingInvoiceXlsx(ticketId) {
  const ticket = findTicketRaw(Number(ticketId));
  if (!ticket) fail('ไม่พบดีลนี้', 404);

  const today = new Date();
  const thaiYear2 = String(today.getFullYear() + 543).slice(-2);
  const docNumber = `GLR${thaiYear2}${String(ticketId).padStart(3, '0')}`;
  const firstQ = (ticket.quotations ?? [])[0];
  const priceItems = ticket.items.filter((it) => it.approvedPrice != null);
  const lines = [
    `ใบแจ้งหนี้ส่วนที่เหลือ  เลขที่ ${docNumber}`,
    `วันที่: ${mockThaiDate(today)}`,
    `ลูกค้า: ${ticket.customerName ?? ''}`,
    ...(firstQ ? [`อ้างอิง: ${firstQ.number}`] : []),
    ...(ticket.projectName ? [`Project: ${ticket.projectName}`] : []),
    '',
    ...priceItems.map((it, i) => {
      const qty = Number(it.qty) || 0;
      return `${i + 1}. ${mockItemDesc(it)} — ${qty} ${it.rawUnit ?? 'แผ่น'} × ${Number(it.approvedPrice) || 0}`;
    }),
    `หัก  มัดจำ${firstQ ? '  ' + firstQ.number : ''}`,
  ];
  return mockDocPlaceholderBlob(lines);
}

function pushEvent(ticket, actor, kind, fromStatus, toStatus, message, itemSnapshot = null) {
  const nextId = Math.max(...db.tickets.flatMap((t) => t.events.map((e) => e.id)), 0) + 1;
  ticket.events.push({ id: nextId, ticketId: ticket.id, actorId: actor.id, actorName: actor.name, kind, fromStatus, toStatus, message, createdAt: new Date().toISOString(), itemSnapshot });
}

function addNotification(userId, ticketId, ticketCode, type, message) {
  const nextId = Math.max(...db.notifications.map((n) => n.id), 0) + 1;
  db.notifications.unshift({ id: nextId, userId, ticketId, ticketCode, type, message, read: false, createdAt: new Date().toISOString() });
}

// ── Deal pipeline: what this mock will and will not decide ────────────────────────────────────
//
// Read this before adding anything stage-shaped below.
//
// The catalog (DEAL_STAGE_CATALOG) is canned DATA — the shape of GET /api/meta/deal-stages, kept
// honest against DealStage.java by features/tickets/stageCatalog.test.js, which parses the Java.
// Index lookups over it are lookups, not rules.
//
// The DECISIONS are a different matter, and the mock is deliberately incomplete about them:
//
//   * ROLE GATE — approximated, as every namespace in this file approximates its service's authz.
//     Driven by the catalog's own `gate` field (which comes from TicketService's three
//     *_TARGET_STAGES sets) rather than a second list. NOT AUTHORITATIVE; verify against
//     StageDecisionIntegrationTest.
//   * FACT GATES (TicketService.requireStageFactsHold, #710) — NOT reimplemented. The four
//     fact-gated stages are simply closed to a manual move in mock mode, which is STRICTER than
//     production, never looser. They are still reachable here the way they are reached in real
//     life: through the operational action that records the fact (confirmCustomer, deposit-paid,
//     complete-delivery, final-payment), each of which calls autoAdvanceStage below. Before this,
//     the mock let a manual move into all four unconditionally — more permissive than production,
//     which is the dangerous direction and is the exact hazard CLAUDE.md names.
//   * THE NOTE RULE (DealStage.requiresJustification) — NOT reimplemented, and deliberately not
//     approximated either. The mock's old copy was the pre-#704 rule and demanded a written reason
//     for three of the business's four normal routes; a corrected copy would just be a fresh
//     mirror with no guard. `requiresReason` is therefore always false in mock mode, so mock-mode
//     QA never exercises the note requirement. That is a stated gap, not an oversight — the rule
//     is covered by DealStageTest and StageDecisionIntegrationTest on the backend, and the modal's
//     rendering of it is covered by UpdateStageModal.test.jsx driving a hand-built payload.
//
// Mirrors TicketService.stageDecisions / requireStageMoveAllowed (backend ticket/).
const MOCK_FACT_GATED_STAGES = new Set(
  DEAL_STAGE_CATALOG.stages.filter((stage) => stage.auto).map((stage) => stage.code),
);

function dealStageIndex(code) {
  return DEAL_STAGE_CATALOG.stages.findIndex((stage) => stage.code === code);
}

/** Mirrors TicketService.requireStageWriteAccess, keyed off the catalog's gate. NOT authoritative. */
function mockCanWriteStage(user, ticket, code) {
  const gate = DEAL_STAGE_CATALOG.stages.find((stage) => stage.code === code)?.gate;
  if (!gate || !user) return false;
  if (user.role === 'ceo') return true;
  if (gate === 'sales') {
    return user.role === 'sales_manager'
      || (user.role === 'sales' && Number(ticket?.createdById) === Number(user.id));
  }
  return user.role === gate;
}

/**
 * Mirrors TicketService.entryChannelIsStated — "a channel was actually CHOSEN on this deal".
 *
 * Both DESIGNER_LED and UNSPECIFIED count as UNSTATED and neither needs a reason to move off:
 * UNSPECIFIED is the V144 column default, DESIGNER_LED is the pre-V144 default that was never
 * backfilled, so an untouched deal reads one or the other purely by age. Dropping UNSPECIFIED here
 * would make the FIRST statement of a channel on every new deal demand a reason.
 *
 * Read by BOTH the action advertisement and setEntryChannel's own 400, the same single-definition
 * discipline the Java service uses — so the mock cannot advertise a note-free change and then
 * refuse it.
 */
function mockEntryChannelIsStated(ticket) {
  return Boolean(ticket?.entryChannel)
    && ticket.entryChannel !== 'DESIGNER_LED'
    && ticket.entryChannel !== 'UNSPECIFIED';
}

/** Mirrors TicketService.requireDealOwnership (lost / reopen / hold / tracking). NOT authoritative. */
function mockCanDealOwnership(user, ticket) {
  return user?.role === 'ceo' || user?.role === 'sales_manager'
    || (user?.role === 'sales' && Number(ticket?.createdById) === Number(user.id));
}

/**
 * The mock's answer to GET /api/tickets/{id}/actions -> stageDecisions. One entry per stage, in
 * pipeline order, matching the real StageDecisionDto shape exactly.
 *
 * The single place the mock decides anything stage-shaped: mockApi.updateStage consults this
 * rather than re-deriving, so the mock cannot advertise an option it would then refuse.
 */
function mockStageDecisions(ticket, user) {
  return DEAL_STAGE_CATALOG.stages.map(({ code, no }) => {
    let blockedReason = null;
    if (!mockCanWriteStage(user, ticket, code)) {
      blockedReason = 'ไม่มีสิทธิ์เข้าถึงรายการนี้';
    } else if (ticket.lifecycle === 'CLOSED_LOST') {
      blockedReason = 'ดีลถูกทำเครื่องหมายเสียงานแล้ว — เปิดดีลใหม่ก่อนแก้ไขสถานะ';
    } else if ((ticket.lifecycle ?? 'ACTIVE') !== 'ACTIVE') {
      blockedReason = `ดีลไม่ได้อยู่ในสถานะ ACTIVE (${ticket.lifecycle}) จึงแก้ไขขั้นตอนนี้ไม่ได้`;
    } else if (code === ticket.salesStage) {
      blockedReason = `ดีลนี้อยู่ในขั้นตอน ${code} อยู่แล้ว`;
    } else if (MOCK_FACT_GATED_STAGES.has(code)) {
      // The stub, not a copy of requireStageFactsHold — see the block comment above.
      blockedReason = `เลื่อนไปขั้นตอน ${code} ไม่ได้: ขั้นตอนนี้อัปเดตอัตโนมัติจากขั้นตอนของดีล`
        + ' (โหมดจำลองไม่รองรับการตั้งค่าด้วยมือ)';
    } else if (dealStageIndex(code) > dealStageIndex(ticket.salesStage)) {
      const sinceIso = dealLastStageChangeAt(ticket.events, ticket.createdAt);
      const hasRecentActivity = dealHasActivitySince(dealActivitiesForTicket(ticket.id), sinceIso);
      if (!dealIsReadyToAdvance(ticket, hasRecentActivity)) {
        blockedReason = DEAL_STAGE_ADVANCE_GATE_MESSAGE;
      }
    }
    // requiresReason is deliberately always false here — see the block comment above.
    return { stage: code, no, allowed: blockedReason === null, requiresReason: false, blockedReason };
  });
}

// Deal pipeline (V50): mirrors TicketService.autoAdvanceStage — monotonic
// forward-only, no-op while lost. Called from the 4 milestone transitions.
function autoAdvanceStage(ticket, targetStage, user) {
  // ACTIVE is the whole test — since V57 lost_reason SURVIVES a reopen, so keying
  // on it would silently disable auto-advance on every reopened deal.
  if ((ticket.lifecycle ?? 'ACTIVE') !== 'ACTIVE') return;
  if (dealStageIndex(targetStage) <= dealStageIndex(ticket.salesStage)) return;
  const fromStage = ticket.salesStage;
  ticket.salesStage = targetStage;
  ticket.stageUpdatedAt = new Date().toISOString();
  pushEvent(ticket, user, 'STAGE_CHANGED', fromStage, targetStage, 'อัตโนมัติจากขั้นตอนของดีล');
}

function buildTicketDetail(ticket) {
  const project = ticket.projectId ? mockProjects.find((p) => p.id === ticket.projectId) : null;
  const contact = ticket.contactId ? mockContacts.find((c) => c.id === ticket.contactId) : null;
  const paymentFields = derivePaymentFields(ticket);
  return {
    summary: {
      id: ticket.id, code: ticket.code, type: ticket.type, title: ticket.title,
      status: ticket.status, priority: ticket.priority,
      createdById: ticket.createdById, createdByName: ticket.createdByName,
      assignedToId: ticket.assignedToId, assignedToName: ticket.assignedToName,
      customerName: ticket.customerName,
      customerId: ticket.customerId ?? null,
      projectId: ticket.projectId ?? null,
      projectName: project?.name ?? null,
      contactId: ticket.contactId ?? null,
      contactName: contact ? `${contact.firstName} ${contact.lastName ?? ''}`.trim() : null,
      note: ticket.note,
      createdAt: ticket.createdAt, updatedAt: ticket.updatedAt, closedAt: ticket.closedAt,
      itemCount: ticket.items.length, hasEdits: ticket.hasEdits ?? false,
      paymentStatus: ticket.paymentStatus ?? null,
      fulfillmentStatus: ticket.fulfillmentStatus ?? null,
      salesStage: ticket.salesStage, lostReason: ticket.lostReason ?? null,
      reopenedAt: ticket.reopenedAt ?? null, reopenCount: ticket.reopenCount ?? 0,
      lostAt: ticket.lostAt ?? null, stageUpdatedAt: ticket.stageUpdatedAt ?? ticket.updatedAt,
      lifecycle: ticket.lifecycle ?? 'ACTIVE',
      tenderRequirement: ticket.tenderRequirement ?? 'UNKNOWN',
      depositPolicy: ticket.depositPolicy ?? 'REQUIRED',
      depositPolicyReason: ticket.depositPolicyReason ?? null,
      entryChannel: ticket.entryChannel ?? 'UNSPECIFIED',
      cancelReason: ticket.cancelReason ?? null,
      cancelledAt: ticket.cancelledAt ?? null,
      closeConfirmedAt: ticket.closeConfirmedAt ?? null,
      closeConfirmedByName: ticket.closeConfirmedByName ?? null,
      invoiceOnFile: hasInvoiceAttachment(ticket),
      // Deal tracking fields (V83, Slice B1/B2 — handoff 103).
      //
      // effectiveWinProbability IS served now (issue #738): the real TicketSummaryDto computes it
      // and, since it was made a Jackson property, sends it. It used to be omitted here on the
      // correct grounds that the backend did not serialize it either — which meant the UI had to
      // re-derive it from a copied table, and #714 is what that cost. Computed via
      // dealTrackingMeta's mirror, which stageCatalog-style guards keep honest against
      // WinProbabilityDefaults.java; per CLAUDE.md that makes mock-driven tests evidence about
      // plumbing here, never about the number itself.
      winProbabilityOverride: ticket.winProbabilityOverride ?? null,
      effectiveWinProbability: dealEffectiveWinProbability(
        ticket.winProbabilityOverride ?? null, ticket.salesStage,
      ),
      designerName: ticket.designerName ?? null,
      ownerName: ticket.ownerName ?? null,
      buyerName: ticket.buyerName ?? null,
      stale: dealComputeStale(ticket.lifecycle ?? 'ACTIVE', dealActivitiesForTicket(ticket.id)),
      ...paymentFields,
    },
    items: ticket.items, events: ticket.events,
    quotation: ticket.quotations ? ticket.quotations[0] ?? null : ticket.quotation ?? null,
    quotations: ticket.quotations ?? (ticket.quotation ? [ticket.quotation] : []),
  };
}

function commissionMonth(value) {
  return (value || new Date().toISOString()).slice(0, 7);
}

// ─────────────────────────────────────────────────────────────────────────────
// MOCK COMMISSION FIXTURES — NOT THE SOURCE OF TRUTH, NOT EVIDENCE.
// Mock mode does NOT compute commission. The real figures come from
// CommissionService (tiers/incentive read from sales.tier_config and
// sales.commission_incentive_tier at runtime) and are served by
// GET /api/commissions/monthly-summary and POST /api/commissions/simulator.
// Nothing here tracks a DB tier change, by design: this file used to import
// the frontend's own commission math, which pinned the mock to the display
// layer and guaranteed both would drift from the backend together (the V81
// tier-13 rate correction is the case on record). A green test under
// VITE_USE_MOCKS=true says NOTHING about any commission figure.
// ─────────────────────────────────────────────────────────────────────────────

// Thai VAT strip used ONLY to fabricate a plausible commissionableBase column on demo/mock
// records below -- named distinctly from a "policy" constant on purpose: it is not read from
// sales.tier_config, does not represent any CEO-configurable rate, and nothing else in this file
// may add a tier table, rate, floor, or incentive/stock-bonus config alongside it.
const MOCK_VAT_DIVISOR = 1.07;

// round2(n) already exists above (generic 2dp rounding, not commission-specific) -- reused here
// rather than redeclared.

// Mirrors ONLY CommissionCalculator.calculateInvoice's subtraction formula -- arithmetic over the
// caller's OWN input fields (gross minus each deduction, plus overpayment), not policy. The VAT
// strip below uses MOCK_VAT_DIVISOR (see above), not a re-import of the deleted commissionCalc.js.
function mockInvoiceCalculation(payload) {
  const actualReceived = round2(
    Number(payload.grossAmount || 0)
    - Number(payload.bankFees || 0)
    - Number(payload.suspenseVat || 0)
    - Number(payload.transportFee || 0)
    - Number(payload.cutFee || 0)
    - Number(payload.shortfall || 0)
    - Number(payload.withholdingTax || 0)
    + Number(payload.overpayment || 0)
  );
  return {
    actualReceived,
    commissionableBase: round2(actualReceived / MOCK_VAT_DIVISOR),
  };
}

// Canned monthlySummary() figures -- a frozen snapshot of what the OLD client-side tier math used
// to produce for the demo seed's mock sales user (sales@glr.co.th, August 2026), so before/after
// screenshots of the UI stay comparable across this change; only the SOURCE of the figure moved,
// from client math to (in mock mode) this fixture, and from real usage to the real
// CommissionService. These three numbers will NOT move if the demo seed or the real DB tier
// config changes -- by design, since mock mode cannot read either.
const MOCK_MONTHLY_SUMMARY_FIXTURE = { commissionableBase: 116822.43, tierCommission: 292.06, incentiveAmount: 0 };

// Canned simulate() monthly-aggregate fields -- arbitrary, clearly-fixture numbers (not derived
// from any tier table). actualReceived/commissionableBase above them are NOT canned: those are
// mockInvoiceCalculation's honest arithmetic over the caller's own typed-in invoice fields.
const MOCK_SIMULATION_FIXTURE = {
  existingMonthlyBase: 500000.00,
  projectedMonthlyBase: 650000.00,
  projectedMonthlyCommission: 1625.00,
  incrementalCommission: 375.00,
};

// Canned payrollReady() per-rep tier/incentive/stock-bonus figures -- WHICH reps appear and their
// manualAdjustmentAmount stay real (grouped from db.commissions, summed honestly; "who has
// activity this month" and "sum their manual amounts" are not policy). commissionableBase and the
// tier/incentive/stock-bonus portions of commissionAmount are this one fixed fixture, reused for
// every rep -- the real per-rep math lives only in CommissionService#computeRepPayrollCommissions.
const MOCK_PAYROLL_READY_TIER_FIXTURE = { commissionableBase: 87654.32, tierCommission: 219.14, incentiveAmount: 0, stockBonusAmount: 0 };

// Step 9 cross-check threshold: flag (never block) when the hand-typed grossAmount diverges from
// the linked deal's actual payableAmount by more than this fraction. Mirrors
// CommissionService.MISMATCH_THRESHOLD.
const COMMISSION_MISMATCH_THRESHOLD = 0.05;

function commissionDealMismatch(grossAmount, payable) {
  const gross = Number(grossAmount) || 0;
  if (!payable) return gross > 0;
  return Math.abs(gross - payable) / Math.abs(payable) > COMMISSION_MISMATCH_THRESHOLD;
}

// Manual commission entries (feat/commission-manual-adjustments, V84): a sales_manager/CEO
// hand-typed amount, never run through the commission tier calculation, with no invoice behind
// it. Mirrors backend/.../commission/CommissionKind.java's four manual kinds exactly — ALL FOUR
// are hand-typed for now (owner decision: manual across the UI until the CEO-confirmed
// auto-config lands to prefill suggestions for specific ones later — not implemented here).
const MANUAL_COMMISSION_KINDS = ['ADJUSTMENT', 'MANAGER', 'STOCK_BONUS', 'INCENTIVE'];

function isManualCommissionKind(kind) {
  return MANUAL_COMMISSION_KINDS.includes(kind);
}

function buildCommissionRecord(record) {
  // A manual-kind record has no invoice_id on the real backend, so RECORD_SELECT's LEFT JOIN
  // yields a null CommissionRecord.invoiceDetails() — mirror that exactly rather than defaulting
  // to a stub invoice object, so CommissionPage's manual-vs-SALE branches can key off `null`.
  const isManual = isManualCommissionKind(record.kind);
  return structuredClone({
    // Commission redesign calc-refine (V82): 1x is the default weight for any record created
    // before this field existed (or omitted in a test fixture) — matches the column's DB default.
    weightMultiplier: 1,
    managerApprovedBy: null,
    managerApprovedByName: null,
    managerApprovedAt: null,
    ceoApprovedBy: null,
    ceoApprovedByName: null,
    ceoApprovedAt: null,
    rejectedById: null,
    rejectedByName: null,
    rejectedAt: null,
    rejectionReason: null,
    dealPayableAmountSnapshot: null,
    dealAmountMismatch: false,
    manualAmount: null,
    manualReason: null,
    ...record,
    invoiceDetails: isManual ? null : {
      invoiceAttachmentId: null,
      invoiceAttachmentFileName: null,
      ...record.invoiceDetails,
    },
  });
}

function employeeForUser(user) {
  return user?.employeeId ? db.employees.find((employee) => employee.id === user.employeeId) : null;
}

function dashboardManager(user) {
  const employee = employeeForUser(user);
  return Boolean(
    user?.manager
    || user?.role === 'sales_manager'
    || employee?.positionTh === 'ผู้จัดการฝ่าย'
  );
}

function dashboardDivisionId(user) {
  return user?.divisionId ?? employeeForUser(user)?.divisionId ?? null;
}

function dashboardEmployeeScope(user) {
  const employee = employeeForUser(user);
  if (['hr', 'ceo'].includes(user.role)) return { label: 'all', employees: db.employees };
  if (dashboardManager(user) && dashboardDivisionId(user)) {
    return {
      label: 'division',
      employees: db.employees.filter((item) => item.divisionId === dashboardDivisionId(user)),
    };
  }
  return { label: employee ? 'self' : 'none', employees: employee ? [employee] : [] };
}

/**
 * Who this caller may see, mirroring AttendanceService.resolveScope. A non-manager asking for
 * someone else is a 403; a manager is silently narrowed to their division instead.
 */
function mockAttendanceScope(user, params = {}) {
  if (['hr', 'ceo'].includes(user.role)) {
    const requested = params.employeeId ? Number(params.employeeId) : null;
    // divisionId is a convenience filter for roles that already see everything — never a grant.
    // Compared as a string: the demo dataset keys divisions by code ('HRD'), while the real schema
    // uses an integer id. Number() would turn 'HRD' into NaN and silently disable the filter.
    const requestedDivision = params.divisionId ? String(params.divisionId) : null;
    let employees = db.employees;
    if (requestedDivision) {
      employees = employees.filter((employee) => String(employee.divisionId) === requestedDivision);
    }
    if (requested) {
      employees = employees.filter((employee) => employee.id === requested);
    }
    return { employees };
  }
  if (!user.employeeId) fail('บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล', 400);
  if (dashboardManager(user) && dashboardDivisionId(user)) {
    const division = db.employees.filter(
      (employee) => employee.divisionId === dashboardDivisionId(user),
    );
    const requested = params.employeeId ? Number(params.employeeId) : null;
    // Both predicates AND, so an out-of-division id matches nothing rather than leaking.
    return { employees: requested ? division.filter((e) => e.id === requested) : division };
  }
  if (params.employeeId && Number(params.employeeId) !== user.employeeId) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
  const self = employeeForUser(user);
  return { employees: self ? [self] : [] };
}

const MOCK_UNMAPPED_BADGES = [
  {
    badge_code: '8801',
    punch_count: 12,
    first_seen: '2026-07-02T08:11:00+07:00',
    last_seen: '2026-07-17T17:48:00+07:00',
    site_code: 'SHOWROOM',
  },
  {
    badge_code: '9042',
    punch_count: 3,
    first_seen: '2026-07-09T08:31:00+07:00',
    last_seen: '2026-07-11T17:22:00+07:00',
    site_code: 'WAREHOUSE',
  },
];

function isoDate(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function bangkokStamp(date, hour, minute) {
  return `${isoDate(date)}T${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}:00+07:00`;
}

/**
 * The eight shapes the day table has to render, cycled deterministically by (employee, day) so a
 * given date always looks the same and every badge is reachable without hunting.
 */
const MOCK_DAY_SHAPES = [
  { kind: 'present', inAt: [8, 24], outAt: [17, 35] },
  { kind: 'late', inAt: [8, 47], outAt: [17, 32], late: 17, midAt: [[12, 3], [13, 10], [15, 30]] },
  { kind: 'early', inAt: [8, 28], outAt: [16, 20], early: 70 },
  { kind: 'missingOut', inAt: [8, 31], outAt: null },
  { kind: 'overtime', inAt: [8, 20], outAt: [19, 0], overtime: 90, midAt: [[12, 15]] },
  { kind: 'present', inAt: [8, 12], outAt: [17, 40] },
  { kind: 'workedLate', inAt: [8, 26], outAt: [18, 40] },
  { kind: 'missingIn', inAt: null, outAt: [17, 25] },
  { kind: 'wfh' },
  { kind: 'none' },
];

/**
 * Keyed on the employee's own id, never their position in the array. The drill-down fetches punches
 * for a single employee, so a position-based key would pick a different shape there than the day
 * list used and the expanded scans would contradict the row they came from.
 */
function mockDayShape(employee, date) {
  return MOCK_DAY_SHAPES[(date.getDate() + Number(employee.id)) % MOCK_DAY_SHAPES.length];
}

function mockAttendanceDays(employees, from, to) {
  const days = [];
  for (let cursor = new Date(to); cursor >= from; cursor.setDate(cursor.getDate() - 1)) {
    const date = new Date(cursor);
    const weekend = date.getDay() === 0 || date.getDay() === 6;
    employees.forEach((employee) => {
      const shape = weekend ? { kind: 'nonWorkday' } : mockDayShape(employee, date);
      days.push(mockAttendanceDay(employee, date, shape, weekend));
    });
  }
  return days;
}

function mockAttendanceDay(employee, date, shape, weekend) {
  const base = {
    employee_id: employee.id,
    employee_code: employee.code,
    employee_name: employee.nameTh,
    nick_name: employee.nickname ?? null,
    position_th: employee.positionTh ?? null,
    work_date: isoDate(date),
    is_workday: !weekend,
    check_in: null,
    check_out: null,
    total_minutes: null,
    late_minutes: 0,
    early_leave_minutes: 0,
    overtime_minutes: 0,
    punch_count: 0,
    site_code: null,
    status: 'NO_RECORD',
    flags: [],
    is_manual_override: false,
    notes: null,
  };

  if (shape.kind === 'none') return base;
  if (shape.kind === 'nonWorkday') {
    return { ...base, status: 'NON_WORKDAY', flags: ['NON_WORKDAY'] };
  }
  // A CEO/HR "marked present" day: no scans, site_code 'WFH', manual override — this is how the
  // backend's upsertWfhPresent + toDto render, so the source column has a WFH row to show.
  if (shape.kind === 'wfh') {
    return { ...base, site_code: 'WFH', is_manual_override: true, status: 'WFH', flags: ['WFH'] };
  }

  const checkIn = shape.inAt ? bangkokStamp(date, shape.inAt[0], shape.inAt[1]) : null;
  const checkOut = shape.outAt ? bangkokStamp(date, shape.outAt[0], shape.outAt[1]) : null;
  const flags = [];
  let status = 'PRESENT';

  if (!checkIn) {
    status = 'MISSING_CHECK_IN';
    flags.push('MISSING_CHECK_IN');
  } else if (!checkOut) {
    status = 'MISSING_CHECK_OUT';
    flags.push('MISSING_CHECK_OUT');
  }
  if (shape.late) {
    status = 'LATE';
    flags.push('LATE');
  }
  if (shape.early) flags.push('EARLY_LEAVE');
  if (shape.overtime) flags.push('OVERTIME_APPROVED');
  if (shape.kind === 'workedLate') flags.push('WORKED_LATE_UNAPPROVED');

  const totalMinutes = checkIn && checkOut
    ? (shape.outAt[0] * 60 + shape.outAt[1]) - (shape.inAt[0] * 60 + shape.inAt[1])
    : null;

  return {
    ...base,
    check_in: checkIn,
    check_out: checkOut,
    total_minutes: totalMinutes,
    late_minutes: shape.late ?? 0,
    early_leave_minutes: shape.early ?? 0,
    overtime_minutes: shape.overtime ?? 0,
    // Counts every scan, not just the two that became check-in/out — that difference is exactly
    // what the day row's "N ครั้ง" affordance surfaces.
    punch_count: (checkIn ? 1 : 0) + (checkOut ? 1 : 0) + (shape.midAt?.length ?? 0),
    // Alternate the scanner site by employee so the source column shows both Showroom and Warehouse.
    site_code: Number(employee.id) % 2 === 0 ? 'WAREHOUSE' : 'SHOWROOM',
    status,
    flags,
  };
}

/** The raw scans behind one day, for the drill-down. */
function mockAttendancePunches(employees, params) {
  const workDate = params.from || params.to;
  if (!workDate || employees.length === 0) return [];
  const date = new Date(workDate);
  const weekend = date.getDay() === 0 || date.getDay() === 6;
  if (weekend) return [];

  return employees.flatMap((employee) => {
    const shape = mockDayShape(employee, date);
    // WFH marks and empty days carry no scans, so the drill-down has nothing to show.
    if (shape.kind === 'none' || shape.kind === 'nonWorkday' || shape.kind === 'wfh') return [];
    // Match the daily row's site (see mockAttendanceDay) so the expanded scans agree with the row.
    const warehouse = Number(employee.id) % 2 === 0;
    const site = warehouse
      ? { site_code: 'WAREHOUSE', device_code: 'WAREHOUSE_ZMM220', device_name: 'เครื่องสแกนคลังสินค้า' }
      : { site_code: 'SHOWROOM', device_code: 'SHOWROOM_SC700', device_name: 'เครื่องสแกนโชว์รูม' };
    // Chronological, so the consumer sees the same first/last the backend derived by MIN/MAX.
    const stamps = [shape.inAt, ...(shape.midAt ?? []), shape.outAt].filter(Boolean);
    return stamps.map((stamp, position) => ({
      punch_id: employee.id * 1000 + date.getDate() * 10 + position,
      employee_id: employee.id,
      employee_code: employee.code,
      employee_name: employee.nameTh,
      nick_name: employee.nickname ?? null,
      position_th: employee.positionTh ?? null,
      punch_time: bangkokStamp(date, stamp[0], stamp[1]),
      work_date: isoDate(date),
      ...site,
    }));
  });
}

function dashboardHeadcount(user) {
  const company = ['hr', 'ceo'].includes(user.role);
  const manager = dashboardManager(user);
  const divisionId = dashboardDivisionId(user);
  const employees = company
    ? db.employees
    : manager && divisionId
      ? db.employees.filter((employee) => employee.divisionId === divisionId)
      : [];
  if (employees.length === 0) return { scope: 'none', active: null, inactive: null, total: null, byDivision: [] };

  const byDivision = [...employees.reduce((groups, employee) => {
    const key = employee.divisionId || 'unknown';
    const current = groups.get(key) || {
      divisionId: employee.divisionId ?? null,
      divisionCode: employee.divisionId ?? null,
      divisionName: employee.divisionTh || 'ไม่ระบุฝ่าย',
      active: 0,
      inactive: 0,
      total: 0,
    };
    if (employee.active) current.active += 1;
    else current.inactive += 1;
    current.total += 1;
    groups.set(key, current);
    return groups;
  }, new Map()).values()];

  return {
    scope: company ? 'all' : 'division',
    active: employees.filter((employee) => employee.active).length,
    inactive: employees.filter((employee) => !employee.active).length,
    total: employees.length,
    byDivision,
  };
}

function dashboardTickets(user) {
  const allVisible = ['import', 'ceo'].includes(user.role);
  const ownVisible = user.role === 'sales';
  const list = allVisible
    ? db.tickets
    : ownVisible
      ? db.tickets.filter((ticket) => ticket.createdById === user.id || (user.employeeId && ticket.createdById === user.employeeId))
      : [];
  const now = new Date();
  const monthStart = new Date(now.getFullYear(), now.getMonth(), 1).toISOString().slice(0, 10);
  const threeDaysAgo = new Date(now - 3 * 86400000).toISOString().slice(0, 10);
  return {
    scope: allVisible ? 'all' : ownVisible ? 'self' : 'none',
    draft: list.filter((ticket) => ticket.status === 'draft').length,
    submitted: list.filter((ticket) => ticket.status === 'submitted').length,
    inReview: list.filter((ticket) => ticket.status === 'in_review').length,
    priceProposed: list.filter((ticket) => ticket.status === 'price_proposed').length,
    approved: list.filter((ticket) => ticket.status === 'approved').length,
    quotationIssued: list.filter((ticket) => ticket.status === 'quotation_issued').length,
    documentIssued: list.filter((ticket) => ticket.status === 'document_issued').length,
    closed: list.filter((ticket) => ticket.status === 'closed').length,
    cancelled: list.filter((ticket) => ticket.status === 'cancelled').length,
    total: list.length,
    totalOpen: list.filter((ticket) => !['closed', 'cancelled'].includes(ticket.status)).length,
    closedThisMonth: list.filter((ticket) => ticket.status === 'closed' && ticket.closedAt >= monthStart).length,
    cancelledThisMonth: list.filter((ticket) => ticket.status === 'cancelled' && ticket.updatedAt >= monthStart).length,
    overdueOver3Days: list.filter((ticket) => !['closed', 'cancelled', 'draft'].includes(ticket.status) && ticket.createdAt < threeDaysAgo).length,
    onHold: list.filter((ticket) => ticket.lifecycle === 'ON_HOLD').length,
    dormant: list.filter((ticket) => ticket.lifecycle === 'DORMANT').length,
    paymentOverdue: list.filter((ticket) => derivePaymentFields(ticket).overdue).length,
    partiallyDelivered: list.filter((ticket) => ticket.fulfillmentStatus === 'PARTIALLY_DELIVERED').length,
  };
}

function dashboardPending(user, ticketSummary) {
  const employeeScope = dashboardEmployeeScope(user);
  const employeeIds = new Set(employeeScope.employees.map((employee) => employee.id));
  const employeeSelf = employeeScope.label === 'self';
  const manager = employeeScope.label === 'division';
  const isHr = user.role === 'hr';
  const profileRequests = isHr || employeeSelf
    ? db.profileRequests.filter((request) => employeeIds.has(request.employeeId) && request.status === 'pending').length
    : 0;
  // OT and leave share one gate and one predicate in DashboardRepository
  // (`pendingVisibility` gives both `isHr || manager || employeeSelf`, and both
  // countOvertime/countLeave match `status = 'SUBMITTED'` only). MANAGER_APPROVED
  // is deliberately NOT counted: it is awaiting the CEO, not the viewer, and the
  // Java query excludes it — counting it here would make the mock read higher
  // than production.
  const overtime = isHr || manager || employeeSelf
    ? db.overtimeRequests.filter((request) => employeeIds.has(request.employeeId) && request.status === 'SUBMITTED').length
    : 0;
  const leave = isHr || manager || employeeSelf
    ? db.leaveRequests.filter((request) => employeeIds.has(request.employeeId) && request.status === 'SUBMITTED').length
    : 0;
  const commissions = ['sales_manager', 'ceo'].includes(user.role)
    ? db.commissions.filter((record) => ['SUBMITTED', 'MANAGER_APPROVED'].includes(record.status)).length
    : user.role === 'sales'
      ? db.commissions.filter((record) => record.salesRepId === user.id && record.status === 'SUBMITTED').length
      : 0;
  const tickets = ['sales', 'import', 'ceo'].includes(user.role)
    ? ticketSummary.submitted + ticketSummary.inReview + ticketSummary.priceProposed
    : 0;
  return {
    scope: employeeScope.label,
    profileRequests,
    overtime,
    leave,
    commissions,
    tickets,
    // Same addends as PendingApprovalsSummaryDto.of — overtime included.
    total: profileRequests + overtime + leave + commissions + tickets,
  };
}

function dashboardAttendance(user) {
  if (['hr', 'ceo'].includes(user.role)) {
    return { scope: 'all', todayPresent: 0, lateToday: 0, missingCheckout: 0, punchCountToday: 0, monthlyAttendanceDays: 0 };
  }
  if (dashboardManager(user) && dashboardDivisionId(user)) {
    return { scope: 'division', todayPresent: 0, lateToday: 0, missingCheckout: 0, punchCountToday: 0, monthlyAttendanceDays: 0 };
  }
  return { scope: 'self', monthlyAttendanceDays: 0, todayStatus: 'NO_RECORD', firstIn: null, lastOut: null, lateMinutesToday: 0 };
}

function dashboardNotifications(user) {
  const ids = new Set([user.id, user.employeeId].filter(Boolean));
  const list = db.notifications.filter((notification) => ids.has(notification.userId));
  return {
    unread: list.filter((notification) => !notification.read).length,
    read: list.filter((notification) => notification.read).length,
    total: list.length,
  };
}

// Reads the stored reports-to link — mirrors hr.employee.reports_to_employee_id
// (the self-FK), not a live org-chart scan. Division managers carry managerId:
// null in the seed (no row above them), same as the real NULL-FK state.
function managerIdForEmployee(employee) {
  return employee?.managerId ?? null;
}

// Mirrors LeaveService.canReviewEmployee()/isDirectManager() — hr bypass, else
// stored-FK match on an *active* target employee. No division fallback: unlike
// overtime, a ฝ่าย manager cannot review a colleague's leave just by sharing a
// division.
function canReviewLeave(user, employeeId) {
  if (user.role === 'hr') return true;
  const employee = findEmployee(employeeId);
  return Boolean(employee?.active && user.employeeId
    && managerIdForEmployee(employee) === user.employeeId);
}

// Mirrors OvertimeService.managesEmployee() — direct report (stored FK) OR
// division manager (position-derived user.manager() sharing the employee's
// division, excluding self). Deliberately has NO hr/admin bypass (HR may review
// leave but never overtime) and NO active() check (Java has none here either).
// Overtime must not reuse canReviewLeave() — that was the #199 bug, where the
// mock let HR approve OT while the real backend returns 403.
//
// These two gates look similar but encode genuinely different Java models
// (active-check + no division term vs. no active-check + division term). Do
// NOT merge them "for DRY" — that reintroduces exactly the #199 bug class.
// Mirrors OvertimeService.managesEmployee(): ฝ่าย manager sharing the division, self excluded.
// reports_to is deliberately NOT a branch here any more -- it stopped granting approval rights when
// overtime moved to the division-only rule AttendanceService.resolveScope already used.
function canReviewOvertime(user, employeeId) {
  if (!user.employeeId || employeeId === user.employeeId) return false;
  const employee = findEmployee(employeeId);
  return Boolean(dashboardManager(user)
    && dashboardDivisionId(user) != null
    && dashboardDivisionId(user) === employee.divisionId);
}

// Mirrors ManagerApproverRepository.hasManagerApproverSql(). Two rules: a ผู้จัดการ's own request
// has no manager stage, and otherwise there must be an ACTIVE ผู้จัดการ in the same ฝ่าย.
// Position matching mirrors DivisionAccessPolicy.isManager -- strip whitespace, substring-match.
function isManagerPosition(employee) {
  return String(employee?.positionTh || '').replace(/\s+/g, '').includes('ผู้จัดการ');
}

function hasManagerApproverFor(employeeId) {
  const employee = findEmployee(employeeId);
  if (!employee) return true; // fail closed: withhold the CEO bypass rather than grant it
  if (isManagerPosition(employee)) return false;
  if (employee.divisionId == null) return false;
  return db.employees.some((peer) => peer.divisionId === employee.divisionId
    && peer.isActive !== false
    && isManagerPosition(peer));
}

// feat/pending-approver-info: mirrors PendingApproverSql on the backend -- "who this is waiting on"
// for a SUBMITTED/MANAGER_APPROVED leave/overtime/special-money request, computed READ-ONLY
// (never gates an approval decision here, same as the backend resolvers). Simplified but not
// misleadingly different: the real backend derives the "ceo"/"hr" ROLE from division+position
// (DivisionAccessPolicy.roleFor), but this mock already has each account's role stored directly on
// db.users -- reading that field is the honest mock-mode equivalent, not a separate reimplementation
// of the derivation itself.
//
// Ambiguity handling matches the backend exactly: a name is shown only when there is EXACTLY ONE
// active account holding that role; with zero or more than one, the name is omitted (role shown
// alone). See PendingApproverSql's Javadoc for the backend-side reasoning this mirrors.
function activeUsersWithRole(role) {
  return db.users.filter((candidate) => candidate.role === role && candidate.active !== false);
}

// Name preference: nickname, falling back to a first-name-shaped stand-in, mirroring the backend's
// "nickname, else first_name_th, never blank" preference (PendingApproverSql). db.users has no
// separate first-name field, so the user's own `name` (already a full display name, e.g. "คุณวิชัย
// ธนาคาร") is the fallback here -- a simplification, not a shape mismatch, since it is used only
// when nickName is missing.
function approverDisplayName(userAccount) {
  if (!userAccount) return null;
  const employee = userAccount.employeeId ? findEmployee(userAccount.employeeId) : null;
  return employee?.nickName || userAccount.name || null;
}

function singleActiveApproverName(role) {
  const candidates = activeUsersWithRole(role);
  return candidates.length === 1 ? approverDisplayName(candidates[0]) : null;
}

// The SAME peer set hasManagerApproverFor's own EXISTS check counts (division match + not-inactive
// + manager position) -- kept literally identical so the two can never disagree about who the
// division-manager candidates are.
function divisionManagerPeers(employeeId) {
  const employee = findEmployee(employeeId);
  if (!employee || employee.divisionId == null) return [];
  return db.employees.filter((peer) => peer.divisionId === employee.divisionId
    && peer.isActive !== false
    && isManagerPosition(peer));
}

function singleDivisionManagerName(employeeId) {
  const peers = divisionManagerPeers(employeeId);
  return peers.length === 1 ? (peers[0].nickName || peers[0].nameTh || null) : null;
}

// Leave: mirrors LeaveRepository#resolvePendingApproverRole/Name -- SUBMITTED only (leave has no
// CEO stage). An active direct manager (managerIdForEmployee) if present; otherwise "hr"
// generically.
function pendingApproverForLeave(record, managerEmployeeId) {
  if (record.status !== 'SUBMITTED') return { pendingApproverRole: null, pendingApproverName: null };
  if (managerEmployeeId) {
    const manager = findEmployee(managerEmployeeId);
    const managerActive = manager?.active !== false;
    if (managerActive) {
      return { pendingApproverRole: 'manager', pendingApproverName: manager?.nickName || manager?.nameTh || null };
    }
  }
  return { pendingApproverRole: 'hr', pendingApproverName: singleActiveApproverName('hr') };
}

// Overtime: mirrors OvertimeRepository#resolvePendingApproverRole/Name -- SUBMITTED with a manager
// stage (hasManagerApproverFor) routes to "manager"; SUBMITTED with none, or MANAGER_APPROVED,
// routes to "ceo".
function pendingApproverForOvertime(record) {
  if (record.status === 'SUBMITTED') {
    return hasManagerApproverFor(record.employeeId)
      ? { pendingApproverRole: 'manager', pendingApproverName: singleDivisionManagerName(record.employeeId) }
      : { pendingApproverRole: 'ceo', pendingApproverName: singleActiveApproverName('ceo') };
  }
  if (record.status === 'MANAGER_APPROVED') {
    return { pendingApproverRole: 'ceo', pendingApproverName: singleActiveApproverName('ceo') };
  }
  return { pendingApproverRole: null, pendingApproverName: null };
}

// Special money: mirrors SpecialMoneyRepository#resolvePendingApproverRole/Name -- welfare is
// CEO-only, single-stage (SpecialMoneyService's class Javadoc), so both pending statuses resolve
// to "ceo".
function pendingApproverForSpecialMoney(record) {
  if (record.status === 'SUBMITTED' || record.status === 'MANAGER_APPROVED') {
    return { pendingApproverRole: 'ceo', pendingApproverName: singleActiveApproverName('ceo') };
  }
  return { pendingApproverRole: null, pendingApproverName: null };
}

function leaveTypeByCode(code) {
  const type = db.leaveTypes.find((item) => item.code === String(code || '').toUpperCase());
  if (!type) fail('ประเภทการลาไม่ถูกต้อง', 400);
  return type;
}

// V118 cross-year quota fix (2026-08-02): mirrors LeaveService#validateDateRange -- the
// "start/end must fall in the same calendar year" rejection is gone, matching the real backend (a
// 98-day MATERNITY request starting after roughly mid-September no longer 400s). NOT mirrored here:
// the real per-calendar-year quota/paid-cap split (hr.leave_request_quota_year, LeaveQuotaYearSplit)
// -- this mock predates that redesign already (see the db.leaveTypes comment above: it still
// auto-rejects on insufficient quota outright rather than approving-with-split, a pre-existing,
// documented gap this migration does not attempt to close). create() below still keys
// leaveUsedDays/quotaAvailable off a single quotaYear (the start year only), so a cross-year request
// in mock mode is checked against just that one year's remaining quota, not the true per-year split
// -- "not supported in mock mode" for the multi-year figure, same honesty option already taken for
// the paid-cap gap.
function workingDaysBetween(startDate, endDate) {
  const start = new Date(`${startDate}T00:00:00`);
  const end = new Date(`${endDate}T00:00:00`);
  if (end < start) fail('วันที่สิ้นสุดการลาต้องไม่มาก่อนวันที่เริ่มต้น', 400);
  let days = 0;
  const cursor = new Date(start);
  while (cursor <= end) {
    const day = cursor.getDay();
    if (day !== 0 && day !== 6) days += 1;
    cursor.setDate(cursor.getDate() + 1);
  }
  if (days <= 0) fail('ช่วงวันลาต้องมีวันทำงานอย่างน้อย 1 วัน', 400);
  return days;
}

const LEAVE_WORKDAY_START = '08:30';
const LEAVE_WORKDAY_END = '17:30';

// Sub-day leave (2026-07-25): mirrors LeaveService#computeTotalDays -- clock-hours(start,end) / 8,
// no lunch subtraction (decided rule), rounded to 2dp, capped at 1.00 (a sub-day request can never
// exceed one whole day). Times must fall within the standard workday (08:30-17:30), and the date
// itself must be a weekday -- mirrors LeaveService#validateSubDayTimes: without this a
// Saturday/Sunday half-day would slip through while the identical whole-day request is rejected by
// workingDaysBetween.
function workingDayFraction(startDate, startTime, endTime) {
  if (!startTime || !endTime) fail('การลาแบบระบุช่วงเวลาต้องระบุเวลาเริ่มต้นและเวลาสิ้นสุด', 400);
  const dayOfWeek = new Date(`${startDate}T00:00:00`).getDay();
  if (dayOfWeek === 0 || dayOfWeek === 6) fail('ช่วงวันลาต้องมีวันทำงานอย่างน้อย 1 วัน', 400);
  if (startTime < LEAVE_WORKDAY_START || startTime > LEAVE_WORKDAY_END
    || endTime < LEAVE_WORKDAY_START || endTime > LEAVE_WORKDAY_END) {
    fail('เวลาลาต้องอยู่ในช่วงเวลาทำงาน (08:30-17:30)', 400);
  }
  const [startH, startM] = startTime.split(':').map(Number);
  const [endH, endM] = endTime.split(':').map(Number);
  const minutes = (endH * 60 + endM) - (startH * 60 + startM);
  if (minutes <= 0) fail('เวลาสิ้นสุดการลาต้องอยู่หลังเวลาเริ่มต้น', 400);
  const fraction = Math.round((minutes / (8 * 60)) * 100) / 100;
  return Math.min(1, fraction);
}

function leaveUsedDays(employeeId, leaveTypeCode, quotaYear, statuses) {
  return db.leaveRequests
    .filter((request) => request.employeeId === employeeId
      && request.leaveTypeCode === leaveTypeCode
      && request.quotaYear === quotaYear
      && statuses.includes(request.status))
    .reduce((sum, request) => sum + Number(request.totalDays || 0), 0);
}

function leaveBalance(employeeId, type, quotaYear) {
  const approvedDays = leaveUsedDays(employeeId, type.code, quotaYear, ['APPROVED']);
  const pendingDays = leaveUsedDays(employeeId, type.code, quotaYear, ['SUBMITTED']);
  // §5.3.5 VACATION carry-forward (V127): carriedInDays is SHAPE parity only, always 0 in mock
  // mode -- see the db.leaveTypes carriesForward comment above for why (the real grant needs
  // hr.leave_carryover's year-end memoization, a business computation this mock does not
  // reimplement). remainingDays below is therefore also always the un-boosted figure in mock mode,
  // never reflecting a real carry-in even for VACATION.
  return {
    leaveTypeCode: type.code,
    leaveTypeNameTh: type.nameTh,
    leaveTypeNameEn: type.nameEn,
    annualQuotaDays: type.annualQuotaDays,
    approvedDays,
    pendingDays,
    remainingDays: Math.max(0, Number(type.annualQuotaDays || 0) - approvedDays - pendingDays),
    requiresAttachment: type.requiresAttachment,
    carriedInDays: 0,
  };
}

// Paper-form (ใบลาหยุด F-HR-020) contact-during-leave autofill. Coarser than the real
// hr.employee_address schema (no house-number/subdistrict split) -- an accepted mock fidelity gap,
// see LeaveRepository#findContactDefaults for the real (backend) shape.
function leaveContactDefaults(employee) {
  return {
    employeeId: employee.id,
    positionTh: employee.positionTh || null,
    departmentTh: employee.departmentTh || null,
    divisionTh: employee.divisionTh || null,
    contactHouseNo: employee.currentAddress?.line1 || null,
    contactSubdistrict: null,
    contactDistrict: employee.currentAddress?.district || null,
    contactProvince: employee.currentAddress?.province || null,
    contactPhone: employee.phone || null,
  };
}

// `user` is the acting caller (not the request's own employee) -- mirrors LeaveService's
// withCanReviewFlag(dto, user), which stamps canReview per-caller onto every returned DTO in
// list/create/approve/reject/cancel. Reuses canReviewLeave(), the same hr-bypass-or-direct-manager
// predicate the mock already gates approve/reject/cancel on, so the flag and the actual gate can
// never diverge.
function buildLeaveRecord(record, user) {
  const employee = db.employees.find((item) => item.id === record.employeeId);
  const managerEmployeeId = managerIdForEmployee(employee);
  const manager = managerEmployeeId ? db.employees.find((item) => item.id === managerEmployeeId) : null;
  const leaveType = leaveTypeByCode(record.leaveTypeCode);
  return {
    ...structuredClone(record),
    employeeCode: employee?.code || null,
    employeeName: employee?.nameTh || null,
    managerEmployeeId,
    managerName: manager?.nameTh || null,
    leaveTypeNameTh: leaveType.nameTh,
    leaveTypeNameEn: leaveType.nameEn,
    canReview: canReviewLeave(user, record.employeeId),
    ...pendingApproverForLeave(record, managerEmployeeId),
  };
}

function overtimeMinutesBetween(startAt, endAt) {
  const start = new Date(startAt);
  const end = new Date(endAt);
  const diff = Math.round((end.getTime() - start.getTime()) / 60000);
  if (Number.isNaN(diff) || diff <= 0) fail('เวลาสิ้นสุดการทำงานล่วงเวลาต้องอยู่หลังเวลาเริ่มต้น', 400);
  return diff;
}

// Bangkok-local calendar date of an ISO instant string, formatted "YYYY-MM-DD" -- mirrors
// OvertimeService#validatePlannedWindow's own zone (BUSINESS_ZONE = Asia/Bangkok) for comparing an
// OffsetDateTime against a LocalDate.
function bangkokDateOf(isoValue) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(isoValue));
}

// Pure calendar-day arithmetic on a "YYYY-MM-DD" string -- same technique as utils/format.js's
// addDaysIso (Date.UTC used purely as neutral day-count math, never as a timezone conversion,
// since a bare calendar date has no zone attached).
function addCalendarDaysIso(iso, deltaDays) {
  const [year, month, day] = iso.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  date.setUTCDate(date.getUTCDate() + deltaDays);
  return date.toISOString().slice(0, 10);
}

// A2 (OT UAT defect #3): mirrors OvertimeService#validatePlannedWindow's new ≤24h guard --
// plannedEndAt's Bangkok-local calendar date must be workDate or workDate+1. The redesigned form
// can never produce anything longer, but a caller bypassing it still could -- CLAUDE.md is explicit
// that a mock more permissive than production is the dangerous direction.
function validateOvertimePlannedWindowSpan(payload) {
  const endDateIso = bangkokDateOf(payload.plannedEndAt);
  if (endDateIso < payload.workDate || endDateIso > addCalendarDaysIso(payload.workDate, 1)) {
    fail('เวลาสิ้นสุดการทำงานล่วงเวลาต้องอยู่ในวันที่ทำงานหรือวันถัดไปเท่านั้น', 400);
  }
}

// Mirrors OvertimeService.validateRetroactiveWindow(). Advance notice was removed, so same-day and
// backdated requests are accepted — bounded by how far back they reach and by a reason that
// explains the delay.
//
// KNOWN GAP: the Java service also refuses a work date whose payroll month is already PROCESSED
// (OvertimeService.requirePayrollMonthOpen). There is no payroll_period collection in this mock, so
// that rule is absent here. The mock is therefore more permissive than prod on that one case — the
// dangerous direction, so do not read a successful mock submit as proof the backend would accept it.
const OT_RETROACTIVE_WINDOW_DAYS = 60;
const OT_BACKDATED_REASON_MIN_LENGTH = 20;

function validateOvertimeRetroactiveWindow(payload) {
  const workDate = payload.workDate;
  if (!workDate) return;
  const today = new Date().toISOString().slice(0, 10);
  if (workDate >= today) return;
  const earliest = new Date(Date.now() - OT_RETROACTIVE_WINDOW_DAYS * 86400000)
    .toISOString().slice(0, 10);
  if (workDate < earliest) {
    fail(`ยื่นคำขอทำงานล่วงเวลาย้อนหลังได้ไม่เกิน ${OT_RETROACTIVE_WINDOW_DAYS} วันหลังวันที่ทำงาน`, 400);
  }
  const reason = (payload.reason || '').trim();
  if (reason.length < OT_BACKDATED_REASON_MIN_LENGTH) {
    fail('คำขอทำงานล่วงเวลาย้อนหลังต้องระบุเหตุผลที่ยื่นล่าช้าอย่างชัดเจน', 400);
  }
}

// Mirrors DbHolidayCalendar / hr.holiday (V115) -- deliberately NOT the same store as the
// `holidays` mock namespace below (HolidayController's admin CRUD), which stays an honest
// "nothing persisted" stub per its own comment (list() always returns [], every write throws).
// This is a separate, fixed, read-only calendar: the mock-appropriate equivalent of "HR has
// already loaded the calendar for these years". Letting overtime's day-type derivation depend on
// the admin-CRUD store would make it impossible to ever populate from the mock UI at all, since
// that store's create/update/remove all reject. Extend the entries below if a demo/test needs
// another corroborated holiday.
//
// A Map (date -> nameTh), not a bare Set: `leave.calendarContext` below
// (#ot-holiday-visibility, PR 2) needs the same NAME `LeaveCalendarHolidayDto` carries, and a
// second, separate name lookup risked drifting out of sync with this one (a date added here
// without a matching name there). `.has()` -- all `suggestOvertimeDayType`/
// `resolveOvertimeDayTypeSubmitNote` below have ever needed -- means exactly the same thing on a
// Map as it did on the Set it replaces, so neither of those changes at all.
// COPIED VERBATIM FROM PRODUCTION, 2026-08-08: all 19 rows of `hr.holiday` (every one source=BANK,
// i.e. fetched from the BOT financial-institutions-holidays feed). Do not "tidy" these names.
//
// Three things a hand-written fixture got wrong here, each of which hid a real problem:
//
//  1. COVERAGE. `UpcomingHolidays` shows a rolling ~90-day forward window. An earlier fixture
//     clustered in Jan-May + December, leaving a 7-month hole, so the panel rendered its EMPTY
//     STATE for most of the year -- including "today". That is not a visible failure: an empty
//     state is a legitimate render, so the feature's headline surface read as working while never
//     once showing a holiday.
//  2. THE DATES WERE WRONG. The hand-written version had 2026-12-05 (วันพ่อแห่งชาติ). Production
//     does NOT: 5 Dec 2026 falls on a Saturday, so the observed bank holiday is the SUBSTITUTE on
//     Mon 2026-12-07, and 2026-12-10 (วันรัฐธรรมนูญ) was missing entirely. Thai holidays shift for
//     weekends; guessing them is how a fixture drifts from the thing it stands in for.
//  3. THE NAMES WERE FAR TOO SHORT. The hand-written names ran 9-17 chars. Production's LONGEST is
//     **149 characters** (2026-12-07 below) and four exceed 60. This is why V129 had to widen
//     `name_th` from VARCHAR(120) to TEXT -- a real 2026 BOT response overflowed the column. Short
//     fixture names would let a layout that cannot survive a 149-char label pass every mock-mode
//     check and then break on first contact with production data. `UpcomingHolidays` and the OT
//     verdict badge both render this field: keep the long ones here so the UI is always exercised
//     against the worst case that actually exists.
const MOCK_HOLIDAY_DATES = new Map([
  ['2026-01-01', 'วันขึ้นปีใหม่'],
  ['2026-01-02', 'วันหยุดทำการเพิ่มเป็นกรณีพิเศษ'],
  ['2026-03-03', 'วันมาฆบูชา'],
  ['2026-04-06', 'วันพระบาทสมเด็จพระพุทธยอดฟ้าจุฬาโลกมหาราช และวันที่ระลึกมหาจักรีบรมราชวงศ์'],
  ['2026-04-13', 'วันสงกรานต์'],
  ['2026-04-14', 'วันสงกรานต์'],
  ['2026-04-15', 'วันสงกรานต์'],
  ['2026-05-01', 'วันแรงงานแห่งชาติ'],
  ['2026-05-04', 'วันฉัตรมงคล'],
  ['2026-06-01', 'ชดเชยวันวิสาขบูชา (วันอาทิตย์ที่ 31 พฤษภาคม 2569)'],
  ['2026-06-03', 'วันเฉลิมพระชนมพรรษาสมเด็จพระนางเจ้าสุทิดา พัชรสุธาพิมลลักษณ พระบรมราชินี'],
  ['2026-07-28', 'วันเฉลิมพระชนมพรรษาพระบาทสมเด็จพระเจ้าอยู่หัว'],
  ['2026-07-29', 'วันอาสาฬหบูชา'],
  ['2026-08-12', 'วันเฉลิมพระชนมพรรษาสมเด็จพระนางเจ้าสิริกิติ์ พระบรมราชินีนาถ พระบรมราชชนนีพันปีหลวง และวันแม่แห่งชาติ'],
  ['2026-10-13', 'วันนวมินทรมหาราช'],
  ['2026-10-23', 'วันปิยมหาราช'],
  // The 149-char worst case. If a label breaks the layout, it breaks here first.
  ['2026-12-07', 'ชดเชยวันคล้ายวันพระบรมราชสมภพ พระบาทสมเด็จพระบรมชนกาธิเบศร มหาภูมิพลอดุลยเดชมหาราช บรมนาถบพิตร วันชาติ และวันพ่อแห่งชาติ (วันเสาร์ที่ 5 ธันวาคม 2569)'],
  ['2026-12-10', 'วันรัฐธรรมนูญ'],
  ['2026-12-31', 'วันสิ้นปี'],
]);
// Years the mock calendar is considered "loaded" for -- distinct from a date simply being absent
// from the set above, same distinction HolidayCalendar#hasHolidaysForYear's Javadoc draws in the
// real service (an empty-for-this-date calendar vs. a calendar nobody has loaded yet).
const MOCK_HOLIDAY_LOADED_YEARS = new Set([2026]);

// Mirrors the plain Sat/Sun arithmetic `leave.calendarContext` above already uses for
// `nonWorkingDates` -- NOT schedule-aware (no OPS_6D six-day awareness, no
// hr.work_schedule_assignment equivalent). Reproduced here rather than shared, because
// suggestOvertimeDayType below takes a single ISO date, not a [from, to] range. LOCAL date
// components via `new Date(...).getDay()` on a bare (no offset) ISO string -- same technique,
// same caveat as calendarContext's own loop above.
function isWeekendIso(dateIso) {
  const day = new Date(`${dateIso}T00:00:00`).getDay();
  return day === 0 || day === 6;
}

// Mirrors OvertimeService#suggestDayType -- the system's SUGGESTION only, consulted at create()
// and as approve()'s fallback when the approver supplies no override below. NEVER read a
// caller-supplied dayType directly for pay; see resolveOvertimeDayTypeSubmitNote for how the
// employee's field is used instead (compared against this suggestion, never a pay input by
// itself), and approve()'s own payload.dayType handling for the ONE field that may set pay.
//
// P0 THIS CLOSES IN MOCK MODE TOO (issue #199's "mock more permissive than production" shape --
// see CLAUDE.md "Mock API contract"): this file used to do `dayType: payload.dayType || 'WORKDAY'`
// at create() and `request.dayType === 'HOLIDAY' ? 3 : 1.5` at approve(), so under
// VITE_USE_MOCKS=true a caller could self-declare HOLIDAY (3.00x) on an ordinary day and be paid
// double, with no way to reproduce the real service's derivation. This mock is NOT authoritative
// -- verify day-type behaviour against OvertimeService, never this file (CLAUDE.md) -- but it
// must not be MORE permissive than the service it stands in for, either.
//
// ⚠️ WIDENED (feat/ot-nonworkday-rate-suggestion, renamed from deriveOvertimeDayType) to also
// suggest HOLIDAY for a weekend, folding in a non-workday alongside a recorded holiday -- but
// ONLY as plain Sat/Sun arithmetic (isWeekendIso above), NOT TieredWorkScheduleResolver's real
// EMPLOYEE > DEPARTMENT > DIVISION > company-default tiering. This mock has no six-day (OPS_6D)
// schedule concept at all -- an OPS_6D employee's Saturday is a WORKDAY in production but reads
// as a suggested HOLIDAY here. Per CLAUDE.md ("where the mock mirrors a backend computation"),
// mock-driven tests are NOT independent evidence for this rule -- do not read a mock-mode render
// of this as proof for an OPS_6D employee (e.g. จำเนียร, 10051); verify against
// OvertimeDayTypeDerivedFromCalendarIntegrationTest's real TieredWorkScheduleResolver-backed
// cases instead.
function suggestOvertimeDayType(workDate) {
  return MOCK_HOLIDAY_DATES.has(workDate) || isWeekendIso(workDate) ? 'HOLIDAY' : 'WORKDAY';
}

// Parses `value` as WORKDAY/HOLIDAY (case-insensitive), or null for "nothing supplied"
// (blank/undefined/null). Throws 400 for anything non-blank that isn't recognised -- a 400 about
// SYNTAX, not about the value being wrong. Pure parsing shared by BOTH the submit-time claim
// (resolveOvertimeDayTypeSubmitNote, untrusted for pay) and the approve-time override (approve()
// below, trusted AS the pay input) -- mirrors OvertimeService#parseOvertimeDayType's identical
// dual-use: the trust boundary lives at the CALL SITE, not in this parser.
function parseOvertimeDayTypeValue(value) {
  if (!value) return null;
  const normalized = String(value).trim().toUpperCase();
  if (!['WORKDAY', 'HOLIDAY'].includes(normalized)) {
    fail('ประเภทวันทำงานล่วงเวลาไม่ถูกต้อง', 400);
  }
  return normalized;
}

function overtimeDayTypeLabel(value) {
  return value === 'HOLIDAY' ? 'วันหยุด (3x)' : 'วันทำงานปกติ (1.5x)';
}

// Mirrors OvertimeService#resolveDayTypeSubmitNote's decision table (feat/ot-nonworkday-rate-
// suggestion narrowed and extended this from the original P0 fix's shape -- see git history for
// the removed outright refusal of a HOLIDAY claim the calendar could actively disprove; owner
// ruling 2026-08-08 replaced that refusal with a flag). Two independent flags can fire, neither a
// rejection:
//  1. the suggestion is WORKDAY (i.e. not a recorded holiday AND not a weekend) AND the calendar
//     year is not "loaded" -- an unrecorded public holiday could still be hiding on this date. A
//     suggestion of HOLIDAY is never unverified in this sense (schedule/holiday already certain);
//  2. the claim (if any) disagrees with the suggestion, in EITHER direction -- accepted either
//     way, never refused.
// Either way the claim never reaches dayType/multiplier -- suggestOvertimeDayType (or the
// approver's own override at approve()) alone decides those.
function resolveOvertimeDayTypeSubmitNote(claim, workDate) {
  const claimed = parseOvertimeDayTypeValue(claim);
  const suggested = suggestOvertimeDayType(workDate);
  const notes = [];
  if (suggested === 'WORKDAY' && !MOCK_HOLIDAY_LOADED_YEARS.has(Number(workDate.slice(0, 4)))) {
    notes.push(`[รอตรวจสอบ] ปฏิทินวันหยุดปี ${workDate.slice(0, 4)} ยังไม่ได้โหลด อัตรา OT อาจไม่ถูกต้อง โปรดตรวจสอบ`);
  }
  if (claimed && claimed !== suggested) {
    notes.push(`[ไม่ตรงกับที่ระบบแนะนำ] พนักงานระบุ ${overtimeDayTypeLabel(claimed)} แต่ระบบแนะนำ ${overtimeDayTypeLabel(suggested)} โปรดตรวจสอบก่อนอนุมัติ`);
  }
  return notes.length === 0 ? null : notes.join(' ');
}

function buildOvertimeRecord(record) {
  const employee = db.employees.find((item) => item.id === record.employeeId);
  const managerEmployeeId = managerIdForEmployee(employee);
  const manager = managerEmployeeId ? db.employees.find((item) => item.id === managerEmployeeId) : null;
  const managerApprover = record.managerApprovedBy ? db.employees.find((item) => item.id === record.managerApprovedBy) : null;
  const ceoApprover = record.ceoApprovedBy ? db.employees.find((item) => item.id === record.ceoApprovedBy) : null;
  return {
    ...structuredClone(record),
    employeeCode: employee?.code || null,
    employeeName: employee?.nameTh || null,
    managerEmployeeId,
    managerName: manager?.nameTh || null,
    managerApprovedByName: managerApprover?.nameTh || null,
    ceoApprovedByName: ceoApprover?.nameTh || null,
    // Projected per row by OvertimeRepository.baseSelect(); the panel keys its approve button off
    // it. Omitting it here would leave the field undefined, which the panel reads as "has a manager
    // stage" -- the CEO would then never see the button on a manager-less request under mocks.
    hasManagerApprover: hasManagerApproverFor(record.employeeId),
    // feat/ot-nonworkday-rate-suggestion: computed fresh on every read, mirroring
    // OvertimeRequestDto#suggestedDayType -- never persisted, never a pay input by itself. See
    // suggestOvertimeDayType's own comment for the OPS_6D caveat this mock cannot honour.
    suggestedDayType: suggestOvertimeDayType(record.workDate),
    ...pendingApproverForOvertime(record),
  };
}

// Mirrors SpecialMoneyType.java (name -> label/bucket/evidence). Kept as a plain
// object here (not derived from db) since GET /api/special-money/types is a
// static catalog, same as the Java controller's `Arrays.stream(SpecialMoneyType.values())`.
const SPECIAL_MONEY_TYPES = [
  { requestType: 'UNIFORM_ANNUAL', thaiLabel: 'ชุดฟอร์มประจำปี', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'UNIFORM_NEW_STAFF', thaiLabel: 'ชุดฟอร์มพนักงานใหม่', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'UNIFORM_PREPROBATION_KIT', thaiLabel: 'ชุดพนักงานก่อนผ่านทดลองงาน', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'TRAVEL_PER_DIEM', thaiLabel: 'เบี้ยเลี้ยงเดินทาง', payrollBucket: 'PER_DIEM', evidenceRequired: false },
  { requestType: 'TRAVEL_LODGING', thaiLabel: 'ค่าที่พัก', payrollBucket: 'PER_DIEM', evidenceRequired: true },
  { requestType: 'MEDICAL', thaiLabel: 'ค่ารักษาพยาบาล', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'AID_WEDDING', thaiLabel: 'เงินช่วยเหลืองานแต่งงาน', payrollBucket: 'AID', evidenceRequired: true },
  { requestType: 'AID_ORDINATION', thaiLabel: 'เงินช่วยเหลืองานบวช', payrollBucket: 'AID', evidenceRequired: true },
  { requestType: 'AID_CHILDBIRTH', thaiLabel: 'เงินช่วยเหลือคลอดบุตร', payrollBucket: 'AID', evidenceRequired: true },
  { requestType: 'AID_FUNERAL', thaiLabel: 'เงินช่วยเหลืองานศพ', payrollBucket: 'AID', evidenceRequired: true },
  { requestType: 'TRAINING', thaiLabel: 'สนับสนุนการฝึกอบรม', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'OTHER', thaiLabel: 'อื่นๆ', payrollBucket: 'AID', evidenceRequired: true },
];

function specialMoneyType(requestType) {
  return SPECIAL_MONEY_TYPES.find((item) => item.requestType === requestType) || null;
}

// Mirrors SpecialMoneyService.managesEmployee(): ฝ่าย manager sharing the employee's division,
// self excluded. reports_to is deliberately NOT a branch (dropped with the division-only rule).
//
// NOTE THE NAME IS NOW A MISNOMER IN ONE DIRECTION: this grants NO approval rights. Welfare is
// CEO-only, so a manager passing this can only file on a team member's behalf and read their
// requests and quota. Kept separate from canReviewOvertime on purpose -- these encode distinct
// Java classes whose rules have now genuinely diverged, and merging them would re-couple them.
function canReviewSpecialMoney(user, employeeId) {
  if (!user.employeeId || employeeId === user.employeeId) return false;
  const employee = findEmployee(employeeId);
  return Boolean(dashboardManager(user)
    && dashboardDivisionId(user) != null
    && dashboardDivisionId(user) === employee.divisionId);
}

function canViewAllSpecialMoney(user) {
  return ['hr', 'ceo'].includes(user.role);
}

function canAccessSpecialMoneyEmployee(user, employeeId) {
  return canViewAllSpecialMoney(user)
    || employeeId === user.employeeId
    || canReviewSpecialMoney(user, employeeId);
}

// Mirrors AttendanceCorrectionService: CEO-only, single stage, NO manager routing at all (unlike
// overtime's manager -> CEO pipeline and unlike specialMoney's manager-can-file-on-behalf-but-not-
// approve shape). Submit is always self-only -- there is no employeeId-on-behalf branch anywhere
// in this feature, so there is nothing here for a manager (or HR) to be granted.
function canViewAllAttendanceCorrection(user) {
  return user.role === 'ceo';
}

function buildAttendanceCorrectionRecord(record, user) {
  const employee = db.employees.find((item) => item.id === record.employeeId);
  const requestedBy = record.requestedById ? db.employees.find((item) => item.id === record.requestedById) : null;
  const reviewedBy = record.reviewedById ? db.employees.find((item) => item.id === record.reviewedById) : null;
  return {
    ...structuredClone(record),
    employeeCode: employee?.code || null,
    employeeName: employee?.nameTh || null,
    requestedByName: requestedBy?.nameTh || null,
    reviewedByName: reviewedBy?.nameTh || null,
    // Mirrors AttendanceCorrectionService#withCanReviewFlag: true only for the CEO role, only
    // while the request is still open (status must be SUBMITTED). Role-only, like requireCeo
    // itself -- there is no self-exclusion, so a CEO reviewing their OWN correction request also
    // gets canReview: true here (review #attendance-correction-request; matches the rest of this
    // app's convention of role-only approval gates with no self-check).
    canReview: Boolean(user) && canViewAllAttendanceCorrection(user) && record.status === 'SUBMITTED',
  };
}

function specialMoneyAttachmentsFor(requestId) {
  return db.specialMoneyAttachments.filter((item) => item.specialMoneyRequestId === requestId);
}

function buildSpecialMoneyRecord(record) {
  const employee = db.employees.find((item) => item.id === record.employeeId);
  const managerEmployeeId = managerIdForEmployee(employee);
  const manager = managerEmployeeId ? db.employees.find((item) => item.id === managerEmployeeId) : null;
  const requestedBy = record.requestedById ? db.employees.find((item) => item.id === record.requestedById) : null;
  const managerApprover = record.managerApprovedBy ? db.employees.find((item) => item.id === record.managerApprovedBy) : null;
  const ceoApprover = record.ceoApprovedBy ? db.employees.find((item) => item.id === record.ceoApprovedBy) : null;
  const reviewer = record.reviewedById ? db.employees.find((item) => item.id === record.reviewedById) : null;
  return {
    ...structuredClone(record),
    employeeCode: employee?.code || null,
    employeeName: employee?.nameTh || null,
    managerEmployeeId,
    managerName: manager?.nameTh || null,
    requestedByName: requestedBy?.nameTh || null,
    managerApprovedByName: managerApprover?.nameTh || null,
    ceoApprovedByName: ceoApprover?.nameTh || null,
    reviewedByName: reviewer?.nameTh || null,
    // Projected per row by SpecialMoneyRepository.baseSelect(): a reviewer sees the document trail
    // before opening the request, and the panel can warn before an approval the server will refuse.
    attachmentCount: specialMoneyAttachmentsFor(record.id).length,
    ...pendingApproverForSpecialMoney(record),
  };
}

function specialMoneyPayrollMonth() {
  // Mirrors SpecialMoneyService#ceoApprove's 25th-of-month cutoff
  // (app.special-money.payroll-cutoff-day, default 25): approved on/before the
  // 25th lands in the current month, after rolls to next month. The mock has
  // no payroll-period table to roll further past, so it stops there.
  const now = new Date();
  const cutoffDay = 25;
  const target = now.getDate() <= cutoffDay
    ? new Date(now.getFullYear(), now.getMonth(), 1)
    : new Date(now.getFullYear(), now.getMonth() + 1, 1);
  return target.toISOString().slice(0, 10);
}

function employeeWithRequestMeta(employee) {
  return {
    ...employee,
    pendingRequestCount: db.profileRequests.filter((request) => request.employeeId === employee.id && request.status === 'pending').length,
  };
}

function findEmployee(id) {
  const employee = db.employees.find((item) => item.id === Number(id));
  if (!employee) fail('ไม่พบข้อมูลพนักงาน', 404);
  return employee;
}

function applyApprovedProfileRequest(request) {
  const employee = findEmployee(request.employeeId);
  if (request.fieldKey === 'phone') employee.phone = request.newValue;
  if (request.fieldKey === 'email') employee.email = request.newValue;
  if (request.fieldKey === 'address') employee.currentAddress = { ...employee.currentAddress, line1: request.newValue };
  if (request.fieldKey === 'emergency') {
    const [name, phone] = request.newValue.split('·').map((part) => part.trim());
    employee.emergencyContact = { ...employee.emergencyContact, name: name || request.newValue, phone: phone || employee.emergencyContact.phone };
  }
}

function createEmployeeRecord(payload) {
  const id = Math.max(...db.employees.map((employee) => employee.id)) + 1;
  const division = payload.divisionId || 'SAL';
  const department = payload.departmentTh || 'ขายปลีก';
  const statusId = payload.statusId || 'ACT';
  const statusMap = {
    ACT: ['ทำงานปกติ', 'success', true],
    PRB: ['ทดลองงาน', 'warning', true],
    RSG: ['ลาออก', 'danger', false],
  };
  const [statusTh, statusTone, active] = statusMap[statusId] ?? statusMap.ACT;

  return {
    id,
    code: payload.code || `GLR-${1000 + id}`,
    badge: payload.badge || `BC-${Date.now().toString().slice(-8)}`,
    nameTh: payload.nameTh,
    nameEn: payload.nameEn || payload.nameTh,
    nickName: payload.nickName || '',
    initials: payload.nameEn ? payload.nameEn.split(' ').map((part) => part[0]).join('').slice(0, 2).toUpperCase() : 'GL',
    avatarBg: '#e0e7ff',
    avatarFg: '#4338ca',
    titleTh: payload.titleTh || 'นาย',
    genderTh: payload.genderTh || 'ไม่ระบุ',
    birthDate: payload.birthDate || '1995-01-01',
    age: 31,
    nationality: 'ไทย',
    maritalStatus: 'โสด',
    email: payload.email,
    phone: payload.phone,
    divisionId: division,
    divisionTh: payload.divisionTh || division,
    divisionEn: '',
    departmentTh: department,
    positionTh: payload.positionTh || 'เจ้าหน้าที่',
    positionEn: '',
    level: payload.level || 'O2',
    locationTh: payload.locationTh || 'สำนักงานใหญ่ กรุงเทพฯ',
    statusId,
    statusTh,
    statusTone,
    active,
    payType: 'รายเดือน',
    salary: Number(payload.salary || 0),
    directorRemuneration: Number(payload.directorRemuneration || 0),
    // Standing withholding-tax override (V88). Nullable/meaningful: null = compute normally, a number
    // (incl. 0) = fixed override. NOT `Number(x || 0)` — that would collapse "no override" into a 0.
    withholdingTaxOverride: payload.withholdingTaxOverride == null ? null : Number(payload.withholdingTaxOverride),
    hireDate: payload.hireDate || new Date().toISOString().slice(0, 10),
    confirmationDate: payload.confirmationDate || null,
    reportsTo: payload.reportsTo || '-',
    bank: '',
    bankAccount: '',
    currentAddress: { line1: payload.address || '-', district: '', province: '', postalCode: '' },
    emergencyContact: { name: payload.emergencyName || '-', relationship: payload.emergencyRelationship || '-', phone: payload.emergencyPhone || '-' },
    education: [],
    assignments: [],
    salaryHistory: [],
    sensitive: {},
  };
}

// --- price import helpers ---

// Mirrors the version transition in PriceImportService.commit(): the committed
// version becomes ACTIVE and the factory's previous ACTIVE version is ARCHIVED —
// kept for history, never deleted. Shared by priceImport.commit and
// priceImport.uploadAndCommit so the two cannot drift apart.
// Returns the number of versions archived.
function activateVersion(versionId) {
  const version = mockPriceImportVersions.find((item) => item.versionId === versionId);
  if (!version) return 0;
  const superseded = mockPriceImportVersions
    .filter((item) => item.factoryId === version.factoryId && item.status === 'ACTIVE' && item.versionId !== versionId);
  superseded.forEach((item) => { item.status = 'ARCHIVED'; });
  version.status = 'ACTIVE';
  return superseded.length;
}

function factoryNameFor(factoryId) {
  return mockPriceImportFactories.find((item) => item.factoryId === factoryId)?.name ?? null;
}

// Demo credentials for each role — mirrors V21__demo_seed_accounts.sql
const DEMO_ROLE_EMAIL = {
  sales:         'demo.sales@demo.invalid',
  hr:            'demo.hr@demo.invalid',
  ceo:           'demo.ceo@demo.invalid',
  import:        'demo.import@demo.invalid',
  sales_manager: 'demo.salesmanager@demo.invalid',
  employee:      'demo.employee@demo.invalid',
  account:       'demo.import@demo.invalid', // no dedicated account demo
};

/**
 * Ascending comparator that mirrors PostgreSQL's default `ORDER BY <col> ASC`.
 *
 * The point is NULL placement: PostgreSQL sorts NULLs **last** on an ascending sort (the explicit
 * `NULLS LAST` written on some of CatalogRepository's sort columns is that default spelled out).
 * Coercing a null to '' — which this file used to do — sorts it FIRST instead, so a mock that
 * truncates to `LIMIT :n` would keep a different set of rows than the real query. That is the
 * issue #434 failure shape: same limit, different order, so truncation drops different rows and
 * mock-driven verification never sees the real one. An empty string is NOT null in PostgreSQL, so
 * only null/undefined count as missing here.
 */
function pgAsc(a, b) {
  const aMissing = a === null || a === undefined;
  const bMissing = b === null || b === undefined;
  if (aMissing || bMissing) return aMissing === bMissing ? 0 : (aMissing ? 1 : -1);
  return String(a).localeCompare(String(b));
}

// Row caps that are HARDCODED in the Java repositories — no caller-supplied limit reaches them,
// so the mock must apply the same constant or it hands callers a larger set than production ever
// would. See CatalogRepository.search, CustomerRepository.search, NotificationRepository.
const CATALOG_SEARCH_LIMIT = 30;
const CUSTOMER_SEARCH_LIMIT = 30;
// CatalogController clamps the caller's ?limit to [1, 200] and defaults it to 50.
const CATALOG_PRICES_DEFAULT_LIMIT = 50;
const CATALOG_PRICES_MAX_LIMIT = 200;

// Try a real backend fetch (credentials included) and return the Blob, or null on failure.
async function tryBackendBlob(url) {
  try {
    const res = await fetch(url, { credentials: 'include' });
    if (res.ok) return res.blob();
  } catch { /* backend offline or not authed */ }
  return null;
}

// Leave-request composer (Phase A2, #485): fixed fixtures for POST /api/leave/preview.
// Not a rule engine — see CLAUDE.md "Mock API contract". This does NOT evaluate any of the 17
// real gates LeaveService#autoRejectNote runs (probation, quota, attachment, notice window,
// department coverage, etc.) — every one of those is a genuine per-request eligibility decision
// this mock has never reimplemented (same stance as leave.create() below: see the db.leaveTypes
// comment for why). Each entry here is a SMALL, FIXED, keyed-by-leaveTypeCode-only fixture: it
// does not read the caller's employee, dates, attachment flag, or purpose — a real employee whose
// actual hire date/probation/quota would legitimately block ORDINATION but is exempt from every
// other type still sees the identical canned verdict below. Composer screens exercised only under
// VITE_USE_MOCKS=true are demonstrating the UI's PLUMBING (does a blocked step 1 card render, does
// a counter show up next to the right field) — never evidence that the real gate order, the real
// 17 rejection reasons, or the real coverage/quota math behave a given way. Verify all of that
// against the real backend (LeaveService#preview), per CLAUDE.md.
const LEAVE_PREVIEW_COUNTERS_FIXTURE = {
  // SICK: pretend 1 of 3 no-certificate occasions already used this month.
  SICK: { emergencyFilingsRemaining: 0, noCertificateOccasionsRemaining: 2 },
  // PERSONAL: pretend 1 of 3 emergency filings already used this month.
  PERSONAL: { emergencyFilingsRemaining: 2, noCertificateOccasionsRemaining: 0 },
};
const LEAVE_PREVIEW_DEFAULT_COUNTERS = { emergencyFilingsRemaining: 0, noCertificateOccasionsRemaining: 0 };

// Exactly one type renders "blocked before you type anything" in mock mode, so step 1 of the
// composer has something to demonstrate — every other type's fixture is `null` (no gate hit),
// which illustrates only "not blocked in this fixture", never a real verdict. messageTh below is
// copied VERBATIM from LeaveRuleMessages' real ONCE_PER_EMPLOYMENT template (not re-worded here),
// for the same reason CLAUDE.md gives for never hand-translating backend copy.
const LEAVE_PREVIEW_BLOCKING_FIXTURE = {
  ORDINATION: {
    code: 'ONCE_PER_EMPLOYMENT',
    params: { leaveTypeNameTh: 'ลาอุปสมบท' },
    messageTh: 'การลาอุปสมบทสามารถใช้สิทธิ์ได้เพียงครั้งเดียวตลอดระยะเวลาที่เป็นพนักงาน '
      + 'และมีคำขอที่ใช้สิทธิ์นี้ไปแล้ว กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น',
  },
};

export const api = {
  // Mirrors AuthController + AuthService (auth/).
  auth: {
    async login(payload) {
      const email = payload?.email?.trim().toLowerCase();
      const requestedRole = payload?.role;
      const user = requestedRole
        ? db.users.find((item) => item.role === requestedRole && item.active)
        : db.users.find((item) => item.email.toLowerCase() === email && item.active);

      // Collapsed to one message (matches AuthService.INVALID_CREDENTIALS): must not
      // reveal whether the email exists, only whether the credential pair is valid.
      if (!user) fail('อีเมลหรือรหัสผ่านไม่ถูกต้อง', 401);
      if (!requestedRole && payload?.password && payload.password !== user.password) fail('อีเมลหรือรหัสผ่านไม่ถูกต้อง', 401);

      sessionUser = user;

      // Fire-and-forget: also create a real backend session so document downloads
      // (quotation XLS/PDF, deposit notice, remaining invoice) return real files.
      const demoEmail = DEMO_ROLE_EMAIL[requestedRole ?? user.role];
      if (demoEmail) {
        fetch('/api/auth/login', {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ email: demoEmail, password: 'Demo@2026' }),
        }).catch(() => {});
      }

      return delay({ user: publicUser(user) });
    },
    async logout() {
      sessionUser = null;
      return delay({ ok: true });
    },
    async me() {
      return delay({ user: publicUser(requireSession()) });
    },
    async changePassword(payload) {
      const user = requireSession();
      if (!payload?.currentPassword || payload.currentPassword !== user.password) {
        fail('รหัสผ่านปัจจุบันไม่ถูกต้อง', 401);
      }
      if (!payload?.newPassword || payload.newPassword.length < 8) {
        fail('รหัสผ่านใหม่ต้องมีอย่างน้อย 8 ตัวอักษร', 400);
      }
      if (payload.newPassword === user.password) {
        fail('รหัสผ่านใหม่ต้องไม่ซ้ำกับรหัสผ่านเดิม', 400);
      }
      user.password = payload.newPassword;
      user.mustChangePassword = false;
      return delay({ user: publicUser(user) });
    },
  },
  // Mirrors EmployeeController + EmployeeService (employee/).
  employees: {
    async list(params = {}) {
      hasRole('hr');
      let employees = db.employees.map(employeeWithRequestMeta);
      if (params.search) {
        const query = params.search.toLowerCase();
        employees = employees.filter((employee) => (
          employee.nameTh.includes(params.search)
          || employee.nameEn.toLowerCase().includes(query)
          || employee.code.toLowerCase().includes(query)
          || employee.nickName.includes(params.search)
        ));
      }
      if (params.divisionId) employees = employees.filter((employee) => employee.divisionId === params.divisionId);
      if (params.departmentTh) employees = employees.filter((employee) => employee.departmentTh === params.departmentTh);
      if (params.statusId) employees = employees.filter((employee) => employee.statusId === params.statusId);
      if (params.active === 'true') employees = employees.filter((employee) => employee.active);
      if (params.active === 'false') employees = employees.filter((employee) => !employee.active);
      return delay({ employees });
    },
    async create(payload) {
      hasRole('hr');
      const employee = createEmployeeRecord(payload);
      db.employees.unshift(employee);
      return delay({ employee });
    },
    async get(id) {
      const user = requireSession();
      const employee = findEmployee(id);
      // Mirrors EmployeeService.get(): hr, or the employee viewing themselves — no other role.
      if (user.role !== 'hr' && user.employeeId !== employee.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      return delay({ employee: employeeWithRequestMeta(employee) });
    },
    async update(id, payload) {
      hasRole('hr');
      const employee = findEmployee(id);
      Object.assign(employee, payload);
      if (payload.statusId) {
        const status = { ACT: ['ทำงานปกติ', 'success', true], PRB: ['ทดลองงาน', 'warning', true], RSG: ['ลาออก', 'danger', false] }[payload.statusId];
        if (status) {
          employee.statusTh = status[0];
          employee.statusTone = status[1];
          employee.active = status[2];
        }
      }
      return delay({ employee: employeeWithRequestMeta(employee) });
    },
  },
  // Mirrors ProfileRequestController + ProfileRequestService (profile/).
  profileRequests: {
    async list() {
      const user = requireSession();
      const rows = user.role === 'hr'
        ? db.profileRequests
        : db.profileRequests.filter((request) => request.employeeId === user.employeeId);
      const profileRequests = rows.map((request) => ({
        ...request,
        employee: db.employees.find((employee) => employee.id === request.employeeId),
      }));
      return delay({ profileRequests });
    },
    async create(payload) {
      const user = hasRole('employee');
      const employee = findEmployee(user.employeeId);
      const request = {
        id: Math.max(...db.profileRequests.map((item) => item.id)) + 1,
        employeeId: employee.id,
        fieldKey: payload.fieldKey,
        fieldLabel: payload.fieldLabel,
        oldValue: payload.oldValue,
        newValue: payload.newValue,
        requestedBy: employee.nameTh,
        requestedAt: new Date().toISOString().slice(0, 10),
        status: 'pending',
      };
      db.profileRequests.unshift(request);
      return delay({ profileRequest: { ...request, employee } });
    },
    async update(id, payload) {
      hasRole('hr');
      const request = db.profileRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอแก้ไขข้อมูลส่วนตัวนี้', 404);
      request.status = payload.status;
      request.reviewedAt = new Date().toISOString().slice(0, 10);
      if (payload.reviewerNote !== undefined) request.reviewerNote = payload.reviewerNote;
      if (request.status === 'approved') applyApprovedProfileRequest(request);
      return delay({ profileRequest: { ...request, employee: findEmployee(request.employeeId) } });
    },
  },
  // Mirrors TicketController + TicketService (ticket/).
  tickets: {
    async list(params = {}) {
      const user = requireSession();
      // sales_manager: read+comment oversight only — kept here (not routed through
      // requireTicketViewer) to match the existing inline-array pattern; must move
      // in lockstep with requireTicketViewer/get() and TicketService.VIEWER_ROLES.
      if (!['sales', 'import', 'ceo', 'account', 'sales_manager'].includes(user.role)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      let list = structuredClone(db.tickets);
      if (user.role === 'sales') list = list.filter((t) => t.createdById === user.id);
      // Phase B (role-scoped views): import/account only see the slice of the deal
      // pipeline relevant to their own worklist — see importListScopeIncludes /
      // accountListScopeIncludes above. ceo/sales_manager/sales are unaffected.
      if (user.role === 'import') list = list.filter(importListScopeIncludes);
      if (user.role === 'account') list = list.filter(accountListScopeIncludes);
      if (params.status) list = list.filter((t) => t.status === params.status);
      // Step 9 addition: additive filter on the deal pipeline stage (e.g. CLOSED_PAID) — mirrors
      // TicketController's new `salesStage` query param, used by the commission "Linked Deal" picker.
      if (params.salesStage) list = list.filter((t) => t.salesStage === params.salesStage);
      const tickets = list.map((t) => ({
        id: t.id, code: t.code, type: t.type, title: t.title,
        status: t.status, priority: t.priority,
        createdById: t.createdById, createdByName: t.createdByName,
        assignedToId: t.assignedToId, assignedToName: t.assignedToName,
        customerName: t.customerName, note: t.note,
        projectId: t.projectId ?? null,
        projectName: t.projectId ? (mockProjects.find((p) => p.id === t.projectId)?.name ?? null) : null,
        createdAt: t.createdAt, updatedAt: t.updatedAt, closedAt: t.closedAt,
        itemCount: t.items.length,
        paymentStatus: t.paymentStatus ?? null,
        fulfillmentStatus: t.fulfillmentStatus ?? null,
        salesStage: t.salesStage, lostReason: t.lostReason ?? null,
        lostAt: t.lostAt ?? null, stageUpdatedAt: t.stageUpdatedAt ?? t.updatedAt,
        lifecycle: t.lifecycle ?? 'ACTIVE',
        tenderRequirement: t.tenderRequirement ?? 'UNKNOWN',
        depositPolicy: t.depositPolicy ?? 'REQUIRED',
        depositPolicyReason: t.depositPolicyReason ?? null,
        entryChannel: t.entryChannel ?? 'UNSPECIFIED',
        // Mock-parity fix (role-views-account + role-views-ceo, same underlying
        // gap, landed independently on both branches — collapsed here into one):
        // the real TicketSummaryDto (see TicketService.java / TicketRepository.java)
        // already carries these three fields on the LIST projection, not just the
        // single-ticket detail one — this mock's list() was dropping them, which is
        // exactly the "mock is MORE limited than prod" direction that hides real
        // capability rather than fabricating fake permissiveness. AccountOverview's
        // nextAccountAction() needs closeConfirmedAt/invoiceOnFile to compute the
        // close-ready bucket from list rows alone; CeoOverview needs
        // closeConfirmedAt/closeConfirmedByName at list-scale (which tickets are
        // already confirmed by ฝ่ายบัญชี and awaiting CEO verifyClose) — both
        // without an N+1 detail fetch per ticket.
        closeConfirmedAt: t.closeConfirmedAt ?? null,
        closeConfirmedByName: t.closeConfirmedByName ?? null,
        invoiceOnFile: hasInvoiceAttachment(t),
        // Deal tracking fields (V83, Slice B1/B2 — handoff 103) — same fields as
        // buildTicketDetail's summary, so the manager pipeline view (TicketListPage)
        // has win%/stale without a per-row detail fetch. effectiveWinProbability must be
        // here and not only on the detail projection: TicketListPage's win-weighted
        // forecast sums it across LIST rows (issue #738).
        winProbabilityOverride: t.winProbabilityOverride ?? null,
        effectiveWinProbability: dealEffectiveWinProbability(
          t.winProbabilityOverride ?? null, t.salesStage,
        ),
        designerName: t.designerName ?? null,
        ownerName: t.ownerName ?? null,
        buyerName: t.buyerName ?? null,
        stale: dealComputeStale(t.lifecycle ?? 'ACTIVE', dealActivitiesForTicket(t.id)),
        ...derivePaymentFields(t),
      }));
      return delay({ tickets });
    },

    async get(id) {
      const user = requireSession();
      if (!['sales', 'import', 'ceo', 'account', 'sales_manager'].includes(user.role)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      const ticket = structuredClone(db.tickets.find((t) => t.id === Number(id)));
      if (!ticket) fail('ไม่พบดีลนี้', 404);
      if (user.role === 'sales' && ticket.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      return delay({ ticket: projectTicketDetailForRole(buildTicketDetail(ticket), user.role) });
    },

    async listPayments(id) {
      // Phase B: the payment ledger is ฝ่ายบัญชี's own document — import has no
      // business reading it (mirrors TicketService.listPayments).
      const { user } = requireTicketViewer(id);
      if (user.role === 'import') fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      return delay({ items: receiptsForTicket(id) });
    },

    async listDeliveries(id) {
      requireTicketViewer(id);
      return delay({ items: deliveryRecordsForTicket(id) });
    },

    async recordPayment(id, payload) {
      const user = hasRole('account', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      recordPaymentForTicket(ticket, user, payload ?? {});
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async reserveStock(id, payload) {
      const user = hasRole('import', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      reserveStockForTicket(ticket, user, payload ?? {});
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async recordDelivery(id, payload) {
      const user = hasRole('import', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      recordDeliveryForTicket(ticket, user, payload ?? {});
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async completeDelivery(id, payload = {}) {
      const user = hasRole('import', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      const remaining = (ticket.items ?? [])
        .map((item) => ({ itemId: item.id, qty: moneyValue(Number(item.qty ?? 0) - Number(item.qtyDelivered ?? 0)) }))
        .filter((line) => line.qty > 0);
      if (!remaining.length) fail('ไม่มีจำนวนค้างส่ง', 409);
      const allRemainingCoveredByStock = (ticket.items ?? []).every((item) => {
        const remainingQty = moneyValue(Number(item.qty ?? 0) - Number(item.qtyDelivered ?? 0));
        if (remainingQty <= 0) return true;
        return moneyValue(Number(item.qtyDelivered ?? 0) + remainingQty) <= Number(item.qtyFromStock ?? 0);
      });
      recordDeliveryForTicket(ticket, user, {
        source: allRemainingCoveredByStock ? 'STOCK' : 'WAREHOUSE',
        note: payload.note ?? null,
        recipientName: payload.recipientName ?? null,
        lines: remaining,
      }, true);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async setBilling(id, payload) {
      const user = hasRole('account', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      ticket.billingDate = payload.billingDate ?? null;
      ticket.dueDate = payload.dueDate ?? null;
      ticket.creditTermDays = payload.creditTermDays ?? null;
      ticket.lastFollowUpAt = payload.lastFollowUpAt ?? null;
      ticket.nextFollowUpAt = payload.nextFollowUpAt ?? null;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'BILLING_UPDATED', ticket.status, ticket.status,
        `billing_date=${ticket.billingDate}, due_date=${ticket.dueDate}`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async actions(id) {
      const { user, ticket } = requireTicketViewer(id);
      const active = (ticket.lifecycle ?? 'ACTIVE') === 'ACTIVE';
      // Computed once and used for both halves of the response: the ADVANCE_STAGE/UPDATE_STAGE
      // verbs below are derived from it, and it ships to the client as stageDecisions.
      const stageDecisions = mockStageDecisions(ticket, user);
      const availableActions = [];
      const add = (action, kind, label, extra = {}) => availableActions.push({ action, kind, label, ...extra });
      const owner = user.role === 'sales' && ticket.createdById === user.id;
      const dealOwner = owner || user.role === 'sales_manager' || user.role === 'ceo';
      const canIssueIr = ticket.status === 'quotation_issued'
        && ticket.fulfillmentStatus == null
        && (['DEPOSIT_NOTICE_ISSUED', 'DEPOSIT_PAID'].includes(ticket.paymentStatus)
          || (depositBypassesNotice(ticket) && (ticket.paymentStatus == null || ticket.paymentStatus === 'CUSTOMER_CONFIRMED')));

      if (active) {
        // Ticket-level SUBMIT/PICKUP/PROPOSE_PRICE/CALCULATE_PRICES/OVERRIDE_ITEM_PRICE/
        // APPROVE/REJECT/GENERATE_QUOTATION/MARK_QUOTATION_SENT/ACCEPTED/REJECTED are retired
        // (Phase 2 Slice S1/S2 "engine collapse" — mirrors TicketController/TicketService's own
        // pruning of these verbs from actions(), see docs/agent-handoffs/104): never advertised,
        // so the UI never shows a button that would now 404 on click. Pricing/quotation runs
        // through the PricingRequest chain instead (api.pricingRequests.*).
        if (owner && ticket.status === 'quotation_issued' && (ticket.paymentStatus == null || ticket.paymentStatus === 'CUSTOMER_CONFIRMED')) add('CONFIRM_CUSTOMER', 'payment', 'ลูกค้ายืนยัน');
        if (owner && ticket.status === 'quotation_issued' && ticket.paymentStatus === 'CUSTOMER_CONFIRMED' && !depositBypassesNotice(ticket)) add('ISSUE_DEPOSIT_NOTICE', 'doc', 'ออกใบแจ้งมัดจำ');
        if (['account', 'ceo'].includes(user.role) && ticket.paymentStatus === 'DEPOSIT_NOTICE_ISSUED') add('DEPOSIT_PAID', 'payment', 'รับมัดจำ');
        const paymentFields = derivePaymentFields(ticket);
        if (['account', 'ceo'].includes(user.role) && paymentFields.amountPayable > 0 && paymentFields.paymentStage !== 'FULLY_PAID') {
          add('RECORD_PAYMENT', 'payment', 'บันทึกรับชำระเงิน', { requiredFields: ['kind', 'amount'] });
        }
        if (['account', 'ceo'].includes(user.role)) add('SET_BILLING', 'payment', 'ตั้งค่าการวางบิล', { requiredFields: ['dueDate'] });
        if (['import', 'ceo'].includes(user.role) && canIssueIr) add('ISSUE_IMPORT_REQUEST', 'fulfillment', 'ออกคำขอนำเข้า');
        if (['import', 'ceo'].includes(user.role) && ticket.fulfillmentStatus === 'IR_ISSUED') add('IR_SENT', 'fulfillment', 'ส่งคำขอนำเข้าแล้ว');
        if (['import', 'ceo'].includes(user.role) && ticket.fulfillmentStatus === 'IR_SENT') add('SHIPPING', 'fulfillment', 'สินค้าเดินทาง');
        if (['import', 'ceo'].includes(user.role) && ticket.fulfillmentStatus === 'SHIPPING') add('GOODS_RECEIVED', 'fulfillment', 'รับสินค้า');
        if (['import', 'ceo'].includes(user.role) && (ticket.items ?? []).length > 0 && hasRemainingDelivery(ticket)
            && ticket.fulfillmentStatus !== 'FULLY_DELIVERED') {
          add('RESERVE_STOCK', 'fulfillment', 'จองสินค้าจากสต็อก', { requiredFields: ['lines'] });
        }
        if (['import', 'ceo'].includes(user.role) && hasRemainingDelivery(ticket) && deliveryAvailable(ticket)) {
          add('RECORD_PARTIAL_DELIVERY', 'fulfillment', 'บันทึกการส่งสินค้า', { requiredFields: ['source', 'lines'] });
          add('COMPLETE_DELIVERY', 'fulfillment', 'ส่งมอบครบ');
        }
        const finalPaymentAllowed = ['AWAITING_FINAL_PAYMENT', 'DEPOSIT_PAID'].includes(ticket.paymentStatus)
          || (depositBypassesNotice(ticket) && (ticket.paymentStatus == null || ticket.paymentStatus === 'CUSTOMER_CONFIRMED'));
        if (['account', 'ceo'].includes(user.role) && finalPaymentAllowed) add('FINAL_PAYMENT', 'payment', 'รับเงินครบ');
        // Three-party close: account confirms, CEO verifies. Sales is not involved.
        let closeReady = true;
        try { requireClosePrerequisites(ticket); } catch { closeReady = false; }
        if (user.role === 'account' && !ticket.closeConfirmedAt && closeReady) {
          add('CONFIRM_CLOSE', 'operational', 'ยืนยันพร้อมปิดงาน');
        }
        if (['account', 'ceo'].includes(user.role) && ticket.closeConfirmedAt) {
          add('REVOKE_CLOSE_CONFIRM', 'operational', 'ยกเลิกการยืนยันปิดงาน');
        }
        if (user.role === 'ceo' && ticket.closeConfirmedAt && closeReady) {
          add('VERIFY_CLOSE', 'operational', 'ตรวจสอบและปิดงาน');
        }
        if (ticket.createdById === user.id && !['closed', 'cancelled'].includes(ticket.status)) add('CANCEL', 'operational', 'ยกเลิก');
        if (owner && ['draft', 'submitted', 'in_review', 'price_proposed'].includes(ticket.status)) add('EDIT_ITEMS', 'operational', 'แก้ไขรายการ');
        // Mirrors TicketService.addStageActions: derived from the decisions, so the mock can
        // never advertise a stage it would then refuse. The hardcoded 14-stage array that used to
        // live here was the third copy of DealStage.ORDER and had gone stale on QUOTE_OWNER.
        for (const decision of stageDecisions.filter((d) => d.allowed)) {
          add('ADVANCE_STAGE', 'stage', 'เลื่อนสถานะ', { targetStage: decision.stage });
        }
        if (availableActions.some((a) => a.kind === 'stage')) add('UPDATE_STAGE', 'stage', 'แก้ไขสถานะ', { requiredFields: ['stage'] });
        if (dealOwner) {
          add('MARK_LOST', 'lifecycle', 'เสียงาน', { requiredFields: ['reason'] });
          add('PLACE_ON_HOLD', 'lifecycle', 'พักดีลไว้');
          add('MARK_DORMANT', 'lifecycle', 'พัก dormant');
          add('SET_TENDER_REQUIREMENT', 'policy', 'ตั้งค่าสถานะประมูล', { requiredFields: ['value'] });
          // requiredFields carries the note rule, exactly as TicketService.addPolicyActions does:
          // a channel that was actually STATED needs a reason to change, an unstated one (the V144
          // UNSPECIFIED default, or the never-backfilled pre-V144 DESIGNER_LED) does not. Same
          // predicate as setEntryChannel below — see TicketService.entryChannelIsStated.
          add('SET_ENTRY_CHANNEL', 'policy', 'ตั้งค่า entry channel', {
            requiredFields: mockEntryChannelIsStated(ticket) ? ['value', 'note'] : ['value'],
          });
        }
        if (['account', 'ceo'].includes(user.role)) add('WAIVE_DEPOSIT', 'policy', 'นโยบายมัดจำ', { requiredFields: ['policy', 'reason'] });
      } else if (['ON_HOLD', 'DORMANT'].includes(ticket.lifecycle) && dealOwner) {
        add('RESUME', 'lifecycle', 'ดำเนินการต่อ');
        if (ticket.lifecycle === 'ON_HOLD') add('MARK_DORMANT', 'lifecycle', 'พัก dormant');
      } else if (ticket.lifecycle === 'CLOSED_LOST' && dealOwner) {
        add('REOPEN', 'lifecycle', 'เปิดดีลใหม่');
      }
      return delay({
        currentState: {
          lifecycle: ticket.lifecycle ?? 'ACTIVE',
          salesStage: ticket.salesStage,
          paymentStatus: ticket.paymentStatus ?? null,
          fulfillmentStatus: ticket.fulfillmentStatus ?? null,
          status: ticket.status,
        },
        availableActions,
        stageDecisions,
      });
    },

    async create(payload) {
      const user = hasRole('sales');
      // Mirrors TicketService.create (V50): every new deal belongs to a โครงการ.
      if (payload.projectId == null) fail('ต้องเลือกโครงการก่อนสร้างดีล', 400);
      const nextId = Math.max(...db.tickets.map((t) => t.id)) + 1;
      const code = `PR-2026-${String(nextId).padStart(4, '0')}`;
      const now = new Date().toISOString();
      // Every deal begins as a DRAFT at the lead stage, regardless of whether
      // products were attached at creation time — pricing no longer starts at
      // ticket creation (commit 5). Items attached here are preliminary deal
      // products only; nothing reaches Import (no notification, no status
      // change) until a PricingRequest is created and submitted separately.
      const ticket = {
        id: nextId, code, type: 'PRICE_REQUEST',
        title: payload.title, status: 'draft',
        priority: payload.priority || 'NORMAL',
        createdById: user.id, createdByName: user.name,
        assignedToId: null, assignedToName: null,
        customerName: payload.customerName || null,
        customerId: payload.customerId ?? null,
        projectId: payload.projectId ?? null,
        contactId: payload.contactId ?? null,
        note: payload.note || null,
        salesStage: 'LEAD_APPROACH', lostReason: null, lostAt: null, stageUpdatedAt: now,
        lifecycle: 'ACTIVE',
        tenderRequirement: 'UNKNOWN',
        depositPolicy: 'REQUIRED',
        depositPolicyReason: null,
        // Mirrors TicketRepository.create (V144): an omitted channel means "nobody said", not
        // "designer-led". Defaulting to a real route made every unattended row assert one, so a
        // deliberate DESIGNER_LED was indistinguishable from silence. UNSPECIFIED is legal as
        // STORED but never as a setEntryChannel INPUT — see th.co.glr.hr.ticket.EntryChannel.
        entryChannel: payload.entryChannel || 'UNSPECIFIED',
        createdAt: now.slice(0, 10), updatedAt: now.slice(0, 10), closedAt: null,
        items: (payload.items || []).map((item, i) => ({
          id: nextId * 100 + i, ticketId: nextId,
          brand: item.brand, model: item.model,
          color: item.color, texture: item.texture, size: item.size,
          factory: item.factory || null,
          qty: item.qty, qtySqm: item.qtySqm ?? null,
          proposedPrice: null, approvedPrice: null,
          currency: item.currency || 'THB', sortOrder: i,
          // Fix for "สร้างคำขอราคาไม่ควรต้องกรอกหาจาก catalog ซ้ำ" (V110): the catalog product
          // picked in TicketCreateModal's catalog picker, so PricingRequestCreateModal.
          // emptyItemFromTicketItem can seed productId/catalogProductCode without re-searching.
          catalogPriceId: item.catalogPriceId ?? null,
          catalogProductCode: item.catalogProductCode ?? null,
        })),
        events: [{ id: nextId * 1000, ticketId: nextId, actorId: user.id, actorName: user.name, kind: 'CREATED', fromStatus: null, toStatus: 'draft', message: null, createdAt: now }],
        quotation: null,
      };
      db.tickets.unshift(ticket);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    // Ticket-native submit/pickup/propose-price/calculate-prices/approve/reject/quotation/
    // mark-quotation-*/price-override are retired (Phase 2 Slice S1/S2 "engine collapse" —
    // docs/agent-handoffs/104_feat-deal-workspace-unification.md): TicketController no longer
    // routes any of them, so hrApi.js has no method calling them either. Pricing/quotation now
    // runs through the PricingRequest chain (api.pricingRequests.* below). Quotation
    // READ/download (downloadQuotationXlsx/Pdf) and every operational handler below are
    // untouched, so the 3 legacy pre-redesign tickets/quotations stay readable.

    async editItems(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      const st = ticket.status;
      const isOwner = user.id === ticket.createdById;
      // 'draft' included since V50: a lightweight lead-stage deal gets its product
      // items here before submit().
      const salesCanEdit = user.role === 'sales' && isOwner
        && ['draft', 'submitted', 'in_review', 'price_proposed'].includes(st);
      const importCanEdit = user.role === 'import'
        && ['in_review', 'price_proposed'].includes(st);
      if (!salesCanEdit && !importCanEdit) fail('ไม่มีสิทธิ์แก้ไขรายการสินค้าในสถานะนี้', 403);
      // Mirrors TicketService.editItems: sales/import editing descriptive fields must
      // never silently overwrite import's proposed price or CEO's approved/manual price —
      // pricing fields always come from the existing item at this position, never the
      // request (2026-07-16 pricing-integrity audit, finding #4). Only proposePrice is
      // allowed to replace proposedPrice wholesale.
      ticket.items = (payload.items || []).map((item, i) => ({
        ...ticket.items[i],
        brand: item.brand, model: item.model,
        color: item.color, texture: item.texture, size: item.size,
        factory: item.factory ?? ticket.items[i]?.factory ?? null,
        qty: item.qty, qtySqm: item.qtySqm ?? ticket.items[i]?.qtySqm ?? null,
        rawPrice: item.rawPrice ?? ticket.items[i]?.rawPrice ?? null,
        rawCurrency: item.rawCurrency ?? ticket.items[i]?.rawCurrency ?? null,
        rawUnit: item.rawUnit ?? ticket.items[i]?.rawUnit ?? null,
        proposedPrice: ticket.items[i]?.proposedPrice ?? null,
        id: ticket.items[i]?.id ?? ticket.id * 100 + i,
        ticketId: ticket.id, sortOrder: i,
        // Fix for "สร้างคำขอราคาไม่ควรต้องกรอกหาจาก catalog ซ้ำ" (V110): request wins OUTRIGHT,
        // same as brand/model above. This deliberately does NOT fall back to the prior row when
        // the request omits the field: TicketService.mergeEditedItemsPreservingPricing passes
        // `r.catalogPriceId()` straight through, so on the real backend an omitted field
        // deserializes to null and CLEARS the stored link. A mock that preserved it instead
        // would be more forgiving than production — the one direction CLAUDE.md calls dangerous,
        // because you only discover it in prod.
        catalogPriceId: item.catalogPriceId ?? null,
        catalogProductCode: item.catalogProductCode ?? null,
      }));
      ticket.hasEdits = true;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'EDITED', st, st, payload.note || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    // calculatePrices/overrideItemPrice/approve/reject/quotation (generate)/markQuotationSent/
    // markQuotationAccepted/markQuotationRejected are retired along with their routes — see the
    // comment above editItems(). Download stays; both legacy and PCR-issued quotations render
    // from the same TicketService.loadQuotationContext path.
    async downloadQuotationXlsx(ticketId, quotationId) {
      // Phase B: mirrors TicketService.loadQuotationContext's explicit import denial —
      // a permission question, not a lookup miss, so it must not silently 404 instead.
      const { user } = requireTicketViewer(ticketId);
      if (user.role === 'import') fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      const blob = await tryBackendBlob(`/api/tickets/${ticketId}/quotations/${quotationId}/file?format=xlsx`);
      return blob ?? buildMockQuotationXlsx(ticketId, quotationId);
    },

    async downloadQuotationPdf(ticketId, quotationId) {
      const { user } = requireTicketViewer(ticketId);
      if (user.role === 'import') fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      const blob = await tryBackendBlob(`/api/tickets/${ticketId}/quotations/${quotationId}/file?format=pdf`);
      return blob ?? buildMockQuotationHtml(ticketId, quotationId);
    },

    // Three-party close (V55). Mirrors TicketService.confirmCloseReady /
    // revokeCloseConfirmation / verifyClose. Sales is not part of the sequence.
    async confirmCloseReady(id) {
      const user = requireSession();
      hasRole('account'); // NOT ceo — the CEO signs the second half
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (ticket.closeConfirmedAt) fail('ยืนยันปิดงานไปแล้ว — รอ CEO ตรวจสอบ', 409);
      requireClosePrerequisites(ticket);
      ticket.closeConfirmedAt = new Date().toISOString();
      ticket.closeConfirmedByName = user.name;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'CLOSE_CONFIRMED', ticket.status, ticket.status,
        'ฝ่ายบัญชียืนยันพร้อมปิดงาน — รอ CEO ตรวจสอบ');
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async revokeCloseConfirmation(id, payload = {}) {
      const user = requireSession();
      hasRole('account', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (!ticket.closeConfirmedAt) fail('ดีลนี้ยังไม่ได้ยืนยันปิดงาน', 409);
      ticket.closeConfirmedAt = null;
      ticket.closeConfirmedByName = null;
      pushEvent(ticket, user, 'CLOSE_CONFIRM_REVOKED', ticket.status, ticket.status,
        (payload.note || '').trim() || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async verifyClose(id) {
      const user = requireSession();
      hasRole('ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (!ticket.closeConfirmedAt) fail('ปิดงานไม่ได้: ต้องให้ฝ่ายบัญชียืนยันก่อน', 409);
      // Re-checked here too: the CEO verifies, never overrides.
      requireClosePrerequisites(ticket);
      const prev = ticket.status;
      ticket.status = 'closed';
      ticket.closedAt = new Date().toISOString().slice(0, 10);
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'CLOSED', prev, 'closed', 'CEO ตรวจสอบและปิดงาน');
      ticket.lifecycle = 'COMPLETED';
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    // Mirrors TicketService.cancel. The reason is mandatory (V56) — a cancelled
    // deal used to carry no explanation at all, unlike its CLOSED_LOST sibling.
    async cancel(id, payload = {}) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!DEAL_STAGE_CATALOG.cancelReasons.includes(payload.reason)) {
        fail(`ไม่รองรับเหตุผลการยกเลิก '${payload.reason}'`, 400);
      }
      if (ticket.status === 'closed' || ticket.status === 'cancelled') fail('ไม่สามารถยกเลิกได้', 409);
      // Ownership gate — the Java service has always had this; the mock did not,
      // which made it MORE permissive than production (the dangerous direction).
      if (ticket.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      const prev = ticket.status;
      ticket.status = 'cancelled';
      ticket.lifecycle = 'CANCELLED';
      ticket.cancelReason = payload.reason;
      ticket.cancelledAt = new Date().toISOString();
      ticket.closedAt = new Date().toISOString().slice(0, 10);
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      const note = (payload.note || '').trim();
      pushEvent(ticket, user, 'CANCELLED', prev, 'cancelled',
        note ? `ยกเลิกดีล (${payload.reason}) — ${note}` : `ยกเลิกดีล (${payload.reason})`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async comment(id, payload) {
      // Mirrors TicketService.comment: same read gate as get() — commenting
      // returns the full ticket, so it must not be a side door around the read
      // scoping (nor, per Phase B, around the import quotation projection).
      const { user, ticket } = requireTicketViewer(id);
      pushEvent(ticket, user, 'COMMENTED', null, null, payload.message);
      return delay({ ticket: projectTicketDetailForRole(buildTicketDetail(ticket), user.role) });
    },

    async createDocDraft(ticketId, payload) {
      // delegate to depositNotices.createDraft (defined below — works at call time)
      return api.depositNotices.createDraft(ticketId, payload);
    },

    async listDocs(ticketId) {
      return api.depositNotices.listByTicket(ticketId);
    },

    async revision(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      // Phase 1 lifecycle gate (mirrors DepositNoticeService.requestRevision).
      requireActive(ticket);
      if (!['approved', 'document_issued'].includes(ticket.status)) fail('ไม่สามารถขอแก้ไขในสถานะนี้', 409);

      const toStatus = {
        QTY_OR_NOTE:  'approved',
        PRICE_CHANGE: 'price_proposed',
        NEW_ITEM:     'in_review',
      }[payload.scope] ?? ticket.status;

      ticket.status = toStatus;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'REVISION_REQUESTED', ticket.status, toStatus, `[${payload.scope}] ${payload.reason}`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    // ── Dual-track post-quotation (ข้อ 13) ──────────────────────────────────

    async downloadRemainingInvoice(id) {
      // Mirrors DepositNoticeService.getRemainingInvoiceXlsx: read gate first — and,
      // per Phase B, that gate now denies import outright (a financial document).
      const { ticket } = requireDepositNoticeViewer(id);
      if (ticket.status !== 'quotation_issued') fail('ต้องออกใบเสนอราคาแล้วก่อนจึงจะดำเนินการขั้นตอนนี้ได้', 409);
      const blob = await tryBackendBlob(`/api/tickets/${id}/remaining-invoice/file`);
      return blob ?? buildMockRemainingInvoiceXlsx(Number(id));
    },

    async confirmCustomer(id) {
      const user = hasRole('sales');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      // Mirrors TicketService.confirmCustomer: owner-only.
      if (ticket.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (ticket.status !== 'quotation_issued') fail('ต้องออกใบเสนอราคาแล้วก่อนจึงจะดำเนินการขั้นตอนนี้ได้', 409);
      // Never downgrade the payment track (mirrors TicketService.confirmCustomer).
      if (ticket.paymentStatus != null && ticket.paymentStatus !== 'CUSTOMER_CONFIRMED') {
        fail('ขั้นตอนการรับชำระเงินผ่านสถานะ CUSTOMER_CONFIRMED ไปแล้ว', 409);
      }
      ticket.paymentStatus = 'CUSTOMER_CONFIRMED';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'CUSTOMER_CONFIRMED', ticket.status, ticket.status, null);
      autoAdvanceStage(ticket, 'ORDER_RECEIVED', user);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    // (issueDepositNotice removed — depositNotices.issue is now the single action
    //  that advances the payment track to DEPOSIT_NOTICE_ISSUED.)

    async confirmDepositPaid(id) {
      // Money receipts are confirmed by ฝ่ายบัญชี (CEO fallback) — mirrors
      // TicketService.ACCOUNT_ROLES.
      const user = hasRole('account', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (ticket.paymentStatus !== 'DEPOSIT_NOTICE_ISSUED') fail('ต้องออกใบแจ้งรับมัดจำก่อนจึงจะยืนยันรับชำระมัดจำได้', 409);
      const notice = latestIssuedDepositNotice(ticket.id);
      const amount = notice?.depositAmount ?? moneyValue(payableAmount(ticket) * 0.5);
      if (amount <= 0) fail('ไม่พบยอดมัดจำสำหรับบันทึกรับชำระ', 409);
      recordPaymentForTicket(ticket, user, {
        kind: 'DEPOSIT',
        amount,
        note: 'ยืนยันรับมัดจำ',
        depositNoticeId: notice?.id ?? null,
      });
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async issueImportRequest(id) {
      const user = hasRole('import');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      // DEPOSIT_PAID also qualifies — the deposit is often confirmed before the IR
      // (mirrors TicketService.issueImportRequest).
      const waivedReady = depositBypassesNotice(ticket)
        && (ticket.paymentStatus == null || ticket.paymentStatus === 'CUSTOMER_CONFIRMED');
      if (ticket.status !== 'quotation_issued'
          || (!['DEPOSIT_NOTICE_ISSUED', 'DEPOSIT_PAID'].includes(ticket.paymentStatus) && !waivedReady)) {
        fail('ต้องออกใบเสนอราคาแล้วและรับชำระหรือแจ้งมัดจำแล้วก่อนจึงจะดำเนินการขั้นตอนนี้ได้', 409);
      }
      if (ticket.fulfillmentStatus != null) fail('คำขอนำเข้านี้ถูกออกไปแล้ว', 409);
      ticket.fulfillmentStatus = 'IR_ISSUED';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'IR_ISSUED', ticket.status, ticket.status, null);
      autoAdvanceStage(ticket, 'PROCUREMENT', user);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async markIrSent(id) {
      const user = hasRole('import');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (ticket.fulfillmentStatus !== 'IR_ISSUED') fail('ต้องออกใบขอนำเข้า (IR) ก่อนจึงจะทำเครื่องหมายว่าส่งคำขอนำเข้าแล้วได้', 409);
      ticket.fulfillmentStatus = 'IR_SENT';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'IR_SENT', ticket.status, ticket.status, null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async markShipping(id) {
      const user = hasRole('import');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (ticket.fulfillmentStatus !== 'IR_SENT') fail('ต้องส่งใบขอนำเข้า (IR) ก่อนจึงจะทำเครื่องหมายว่าเริ่มจัดส่งได้', 409);
      ticket.fulfillmentStatus = 'SHIPPING';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'SHIPPING', ticket.status, ticket.status, null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async markGoodsReceived(id) {
      const user = hasRole('import');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (ticket.fulfillmentStatus !== 'SHIPPING') fail('ดีลต้องอยู่ในขั้นตอนจัดส่งก่อนจึงจะทำเครื่องหมายว่าได้รับสินค้าได้', 409);
      ticket.fulfillmentStatus = 'GOODS_RECEIVED';
      if (ticket.paymentStatus === 'DEPOSIT_PAID') {
        ticket.paymentStatus = 'AWAITING_FINAL_PAYMENT';
        pushEvent(ticket, user, 'AWAITING_FINAL_PAYMENT', ticket.status, ticket.status, null);
      }
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'GOODS_RECEIVED', ticket.status, ticket.status, null);
      // Goods are at the warehouse (S17) — advance to DELIVERY_SCHEDULING (S18) so
      // the "schedule delivery / collect balance" step is reached before DELIVERED.
      autoAdvanceStage(ticket, 'DELIVERY_SCHEDULING', user);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async confirmFinalPayment(id) {
      // Mirrors TicketService.ACCOUNT_ROLES (ฝ่ายบัญชี + CEO fallback).
      const user = hasRole('account', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      const allowed = ['AWAITING_FINAL_PAYMENT', 'DEPOSIT_PAID'].includes(ticket.paymentStatus)
        || (depositBypassesNotice(ticket) && (ticket.paymentStatus == null || ticket.paymentStatus === 'CUSTOMER_CONFIRMED'));
      if (!allowed) fail('ต้องรับชำระมัดจำแล้วหรือรอชำระเงินงวดสุดท้าย (หรือดีลนี้ได้รับการยกเว้นมัดจำ) ก่อนจึงจะยืนยันรับชำระเงินครบถ้วนได้', 409);
      const outstanding = moneyValue(payableAmount(ticket) - sumPaid(ticket.id));
      if (outstanding <= 0) {
        if (ticket.paymentStatus !== 'FULLY_PAID') {
          ticket.paymentStatus = 'FULLY_PAID';
          ticket.updatedAt = new Date().toISOString().slice(0, 10);
          pushEvent(ticket, user, 'FULLY_PAID', ticket.status, ticket.status, null);
          maybeAdvanceClosedPaid(ticket, user);
        }
      } else {
        recordPaymentForTicket(ticket, user, {
          kind: 'BALANCE',
          amount: outstanding,
          note: 'ยืนยันชำระส่วนที่เหลือ',
        });
      }
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    // ── Deal pipeline (V50): mirrors TicketService.updateStage/markLost/reopenDeal.
    // Gates come from stageMeta (shared with the pages); authz here approximates
    // the Java service and is NOT authoritative (CLAUDE.md).

    async updateStage(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (dealStageIndex(payload.stage) < 0) fail(`ไม่รองรับสถานะขั้นตอนการขาย '${payload.stage}'`, 400);
      // Enforced from the SAME decision list actions() serves, never re-derived — so an option the
      // mock offered is an option the mock accepts, and there is one place to read. The status is
      // approximated (403 for the permission message, 409 otherwise) because the mock's decisions
      // carry a reason, not a status code; the real service's codes are pinned by
      // StageDecisionIntegrationTest.
      const decision = mockStageDecisions(ticket, user).find((d) => d.stage === payload.stage);
      if (!decision.allowed) {
        fail(decision.blockedReason, decision.blockedReason === 'ไม่มีสิทธิ์เข้าถึงรายการนี้' ? 403 : 409);
      }
      // NO note requirement here. DealStage.requiresJustification is a backend decision and the
      // mock does not mirror it — see the "what this mock will and will not decide" block above.
      const fromStage = ticket.salesStage;
      ticket.salesStage = payload.stage;
      ticket.stageUpdatedAt = new Date().toISOString();
      pushEvent(ticket, user, 'STAGE_CHANGED', fromStage, payload.stage, (payload.note || '').trim() || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async markLost(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (!DEAL_STAGE_CATALOG.lostReasons.includes(payload.reason)) fail(`ไม่รองรับเหตุผลการเสียงาน '${payload.reason}'`, 400);
      if (!mockCanDealOwnership(user, ticket)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (ticket.lifecycle === 'CLOSED_LOST') fail('ดีลนี้ถูกทำเครื่องหมายเสียงานไปแล้ว', 409);
      ticket.lostReason = payload.reason;
      ticket.lostAt = new Date().toISOString();
      ticket.lifecycle = 'CLOSED_LOST';
      ticket.stageUpdatedAt = ticket.lostAt;
      // Stage untouched by design: reopening resumes exactly where the deal was.
      pushEvent(ticket, user, 'MARKED_LOST', ticket.salesStage, ticket.salesStage,
        `เสียงาน (${payload.reason})${(payload.note || '').trim() ? ` — ${payload.note.trim()}` : ''}`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async reopen(id, payload = {}) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!mockCanDealOwnership(user, ticket)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (ticket.lifecycle !== 'CLOSED_LOST' || ticket.lostReason == null) fail('ดีลนี้ยังไม่ได้ถูกทำเครื่องหมายเสียงาน', 409);
      // lostReason/lostAt deliberately PRESERVED (V57): erasing them left the row
      // indistinguishable from one never lost, so "why did we lose this before we
      // reopened it" needed parsing Thai free text out of an event message.
      ticket.lifecycle = 'ACTIVE';
      ticket.reopenedAt = new Date().toISOString();
      ticket.reopenCount = (ticket.reopenCount ?? 0) + 1;
      ticket.stageUpdatedAt = new Date().toISOString();
      pushEvent(ticket, user, 'REOPENED', ticket.salesStage, ticket.salesStage, (payload.note || '').trim() || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async hold(id, payload = {}) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!mockCanDealOwnership(user, ticket)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      requireActive(ticket);
      ticket.lifecycle = 'ON_HOLD';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'ON_HOLD', ticket.salesStage, ticket.salesStage, (payload.note || '').trim() || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async dormant(id, payload = {}) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!mockCanDealOwnership(user, ticket)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (!['ACTIVE', 'ON_HOLD'].includes(ticket.lifecycle ?? 'ACTIVE')) fail('พัก dormant ได้เฉพาะดีลที่ active หรือ on hold', 409);
      ticket.lifecycle = 'DORMANT';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'DORMANT', ticket.salesStage, ticket.salesStage, (payload.note || '').trim() || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async resume(id, payload = {}) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!mockCanDealOwnership(user, ticket)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (!['ON_HOLD', 'DORMANT'].includes(ticket.lifecycle)) fail('ดำเนินการต่อได้เฉพาะดีลที่พักไว้', 409);
      ticket.lifecycle = 'ACTIVE';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'RESUMED', ticket.salesStage, ticket.salesStage, (payload.note || '').trim() || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async setTenderRequirement(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!mockCanDealOwnership(user, ticket)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      requireActive(ticket);
      if (!['REQUIRED', 'NOT_REQUIRED', 'UNKNOWN'].includes(payload.value)) fail(`ไม่รองรับเงื่อนไขการประมูล '${payload.value}'`, 400);
      ticket.tenderRequirement = payload.value;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'POLICY_CHANGED', ticket.salesStage, ticket.salesStage, `tender_requirement → ${payload.value}`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async setEntryChannel(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!mockCanDealOwnership(user, ticket)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      requireActive(ticket);
      if (!['DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT'].includes(payload.value)) fail(`ไม่รองรับช่องทางรับงาน '${payload.value}'`, 400);
      if (mockEntryChannelIsStated(ticket) && ticket.entryChannel !== payload.value && !(payload.note || '').trim()) {
        fail('การเปลี่ยน entry channel ต้องระบุเหตุผล', 400);
      }
      ticket.entryChannel = payload.value;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'POLICY_CHANGED', ticket.salesStage, ticket.salesStage,
        `entry_channel → ${payload.value}${(payload.note || '').trim() ? ` — ${payload.note.trim()}` : ''}`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async setDepositPolicy(id, payload) {
      const user = hasRole('account', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (!['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'].includes(payload.policy)) fail(`ไม่รองรับนโยบายยกเว้นมัดจำ '${payload.policy}'`, 400);
      if (!(payload.reason || '').trim()) fail('ต้องระบุเหตุผลนโยบายมัดจำ', 400);
      ticket.depositPolicy = payload.policy;
      ticket.depositPolicyReason = payload.reason.trim();
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'POLICY_CHANGED', ticket.salesStage, ticket.salesStage,
        `deposit_policy → ${payload.policy} — ${payload.reason.trim()}`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    // ── Deal tracking + activity (V83, Slice B1/B2 "kill the weekly report" —
    // handoff 103). Mirrors TicketController's addActivity/activities/updateTracking
    // and TicketService's requireDealOwnership gate (reuses mockCanDealOwnership — the
    // same check backing markLost/reopen/hold/dormant/resume above, since the real
    // service's requireDealOwnership is one shared method too).

    async addActivity(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      // Deliberately NOT requireActive: a rep can still log why a deal went quiet
      // on a non-ACTIVE deal (mirrors TicketService.addActivity — see handoff 103).
      if (!mockCanDealOwnership(user, ticket)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (!payload?.activityDate) fail('ต้องระบุวันที่ทำกิจกรรม', 400);
      if (!dealIsValidActivityKind(payload?.kind)) fail(`ไม่รองรับประเภทกิจกรรม '${payload?.kind}'`, 400);
      const activity = {
        id: mockDealActivitySeq++,
        ticketId: ticket.id,
        activityDate: payload.activityDate,
        kind: payload.kind,
        note: (payload.note || '').trim() || null,
        createdById: user.id,
        createdByName: user.name,
        createdAt: new Date().toISOString(),
      };
      mockDealActivities.push(activity);
      return delay(structuredClone(activity));
    },

    async listActivities(id) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!mockCanDealOwnership(user, ticket)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      return delay({ items: dealActivitiesForTicket(ticket.id) });
    },

    async updateTracking(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!mockCanDealOwnership(user, ticket)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      requireActive(ticket);
      if (payload?.winProbability != null
        && (Number(payload.winProbability) < 0 || Number(payload.winProbability) > 100)) {
        fail('win probability ต้องอยู่ระหว่าง 0-100', 400);
      }
      // Full-replace semantics (PUT), not a merge — mirrors TrackingUpdateRequest:
      // an omitted/null field CLEARS it (null winProbability = "fall back to the
      // stage default", the intended way to clear an override).
      ticket.winProbabilityOverride = payload?.winProbability ?? null;
      ticket.designerName = (payload?.designerName || '').trim() || null;
      ticket.ownerName = (payload?.ownerName || '').trim() || null;
      ticket.buyerName = (payload?.buyerName || '').trim() || null;
      ticket.nextFollowUpAt = payload?.nextFollowUpAt || null;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'POLICY_CHANGED', ticket.salesStage, ticket.salesStage, 'อัปเดตข้อมูลติดตามดีล');
      return delay({ ticket: buildTicketDetail(ticket) });
    },
  },

  // Mirrors LeaveController + LeaveService (leave/).
  leave: {
    // Mirrors LeaveRepository.findEmployeeOptions() — scope is self + stored-FK
    // reports only (reports_to_employee_id). Deliberately NO division term:
    // unlike overtime, a ฝ่าย manager's leave dropdown does not widen to their
    // whole division.
    async employees() {
      const user = requireSession();
      const includeAll = ['hr', 'ceo'].includes(user.role);
      const rows = db.employees
        .filter((employee) => employee.active)
        .filter((employee) => includeAll || employee.id === user.employeeId || managerIdForEmployee(employee) === user.employeeId)
        .map((employee) => ({
          employeeId: employee.id,
          employeeCode: employee.code,
          employeeName: employee.nameTh,
          departmentName: employee.departmentTh,
          self: employee.id === user.employeeId,
          directReport: managerIdForEmployee(employee) === user.employeeId,
        }));
      return delay({ employees: rows });
    },

    async types() {
      requireSession();
      return delay({ leaveTypes: db.leaveTypes });
    },

    async balances(params = {}) {
      const user = requireSession();
      const employeeId = params.employeeId ? Number(params.employeeId) : user.employeeId;
      if (!employeeId) fail('บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล', 400);
      if (!['hr', 'ceo'].includes(user.role) && employeeId !== user.employeeId && !canReviewLeave(user, employeeId)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      findEmployee(employeeId);
      const year = Number(params.year || new Date().getFullYear());
      return delay({ balances: db.leaveTypes.map((type) => leaveBalance(employeeId, type, year)) });
    },

    // Sub-day leave + paper-form contact block (2026-07-25): same access predicate as balances().
    async contactDefaults(params = {}) {
      const user = requireSession();
      const employeeId = params.employeeId ? Number(params.employeeId) : user.employeeId;
      if (!employeeId) fail('บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล', 400);
      if (!['hr', 'ceo'].includes(user.role) && employeeId !== user.employeeId && !canReviewLeave(user, employeeId)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      const employee = findEmployee(employeeId);
      return delay({ contactDefaults: leaveContactDefaults(employee) });
    },

    // Mirrors LeaveService#list + LeaveRepository#findRequests.
    //
    // Two things here are mirrors of the Java side, NOT mock conveniences, and both were missing
    // until the 2026-08-10 leave-surface restructure -- each is a documented way for this file to
    // produce a green suite that says nothing about production (see CLAUDE.md's mock-contract
    // table):
    //
    //  1. THE NULL-DATE DEFAULT. LeaveService#list defaults each missing bound to
    //     today -/+ DEFAULT_WINDOW_MONTHS (12). This mock previously applied NO default at all, so
    //     `list({})` returned every seeded row -- strictly MORE permissive than production, the
    //     dangerous direction. Five callers pass no dates (ReviewQueueTab, LeaveSurfacePage's
    //     tab-visibility signal, CeoOverview, DivisionManagerOverview, EmployeeSelfService), so
    //     under mocks they saw rows the real backend would have filtered out.
    //  2. THE SORT. LeaveRepository#findRequests ends `ORDER BY lr.start_date DESC,
    //     lr.leave_request_id DESC`. This mock returned db.leaveRequests in seed-insertion order.
    //     Ordering is not cosmetic here: "ประวัติการลา" shows page 1 of a paginated table, so a
    //     different order truncates a DIFFERENT SET of rows -- the exact mechanism of #434.
    //
    // Keep both in step with LeaveService.DEFAULT_WINDOW_MONTHS and that ORDER BY.
    async list(params = {}) {
      const user = requireSession();
      let list = db.leaveRequests;
      const includeAll = ['hr', 'ceo'].includes(user.role);
      if (!includeAll) list = list.filter((item) => item.employeeId === user.employeeId || canReviewLeave(user, item.employeeId));
      if (params.employeeId) list = list.filter((item) => item.employeeId === Number(params.employeeId));
      if (params.status) list = list.filter((item) => item.status === params.status);
      // Each bound defaults independently, exactly as LeaveService#list does -- passing only
      // `from` must still get the default `to`.
      const from = params.from || shiftMonthsIso(bangkokTodayIso(), -LEAVE_DEFAULT_WINDOW_MONTHS);
      const to = params.to || shiftMonthsIso(bangkokTodayIso(), LEAVE_DEFAULT_WINDOW_MONTHS);
      // Overlap, not containment -- mirrors `start_date <= :toDate AND end_date >= :fromDate`.
      list = list.filter((item) => item.endDate >= from && item.startDate <= to);
      const sorted = list.slice().sort((first, second) => (
        second.startDate.localeCompare(first.startDate) || Number(second.id) - Number(first.id)
      ));
      return delay({ requests: sorted.map((item) => buildLeaveRecord(item, user)) });
    },

    async create(payload) {
      const user = requireSession();
      const employeeId = payload.employeeId ? Number(payload.employeeId) : user.employeeId;
      if (!employeeId) fail('บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล', 400);
      if (employeeId !== user.employeeId && !canReviewLeave(user, employeeId)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      const employee = findEmployee(employeeId);
      const leaveType = leaveTypeByCode(payload.leaveTypeCode);
      // Sub-day leave (2026-07-25): times present -> fractional day, single-date only (mirrors
      // LeaveService#computeTotalDays / #validateSubDayTimes). No times -> existing whole-day count.
      const hasSubDayTimes = Boolean(payload.startTime && payload.endTime);
      if (hasSubDayTimes && payload.startDate !== payload.endDate) {
        fail('การลาแบบระบุช่วงเวลาต้องเริ่มต้นและสิ้นสุดในวันเดียวกัน', 400);
      }
      const totalDays = hasSubDayTimes
        ? workingDayFraction(payload.startDate, payload.startTime, payload.endTime)
        : workingDaysBetween(payload.startDate, payload.endDate);
      const quotaYear = Number(payload.startDate.slice(0, 4));
      const used = leaveUsedDays(employeeId, leaveType.code, quotaYear, ['SUBMITTED', 'APPROVED']);
      const remainingBefore = Math.max(0, leaveType.annualQuotaDays - used);
      const quotaAvailable = remainingBefore >= totalDays;
      const today = new Date().toISOString().slice(0, 10);
      // §5 leave-rules-as-data (V116): per-type advanceNoticeDays (mechanical 1:1 mirror of the new
      // hr.leave_type column), replacing the old hardcoded 7-day-for-everyone-but-SICK check --
      // mirrors LeaveService#autoRejectNote's notice branch, in CALENDAR days, same as the real
      // service. minServiceMonths/maxConsecutiveDays/oncePerEmployment are NOT enforced here -- see
      // the db.leaveTypes comment above for why.
      //
      // §5.2 purpose/emergency-filing (V125): purposeCode is stored verbatim below for SHAPE parity
      // (plain passthrough, not a computation -- safe to mirror). The wedding-leave 3-day cap and
      // the emergency-filing monthly-tolerance exception are NOT implemented here, same "not
      // reimplementing a per-request eligibility decision" stance as every other gap in this
      // function: a late PERSONAL request is auto-rejected below exactly as it always was,
      // regardless of purposeCode or requestedAsEmergency, and a WEDDING-purpose request longer than
      // 3 days is NOT refused in mock mode. Do not read a mock-mode APPROVED as proof either rule
      // was honoured -- test both against the real backend.
      const noticeDays = Math.max(0, Number(leaveType.advanceNoticeDays || 0));
      const noticeCutoff = new Date(Date.now() + noticeDays * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
      const hasAttachment = Boolean(payload.attachmentFile);
      let systemNote = null;
      if (!quotaAvailable) {
        systemNote = `โควตาคงเหลือ ${remainingBefore} วัน ไม่พอสำหรับคำขอ ${totalDays} วัน กรุณาติดต่อ HR เพื่อปรับโควตาหรือดำเนินการลาไม่รับค่าจ้าง`;
      } else if (leaveType.code === 'SICK' && !hasAttachment) {
        systemNote = 'ลาป่วยต้องแนบใบรับรองแพทย์ กรุณาแนบเอกสารหรือติดต่อ HR';
      } else if (noticeDays > 0 && payload.startDate < noticeCutoff && payload.startDate >= today) {
        systemNote = `ต้องยื่นคำขอลาล่วงหน้าอย่างน้อย ${noticeDays} วัน กรุณาติดต่อหัวหน้าหรือ HR หากเป็นเหตุเร่งด่วน`;
      }
      const status = systemNote ? 'AUTO_REJECTED' : 'APPROVED';
      const remainingAfter = status === 'APPROVED' ? remainingBefore - totalDays : remainingBefore;
      const id = Math.max(0, ...db.leaveRequests.map((item) => item.id)) + 1;
      const now = new Date().toISOString();
      // Paper-form contact-during-leave block: request value if non-blank, else the employee's
      // profile default (mirrors LeaveService#resolveContact).
      const contactDefaults = leaveContactDefaults(employee);
      const pickContact = (value, fallback) => (value && String(value).trim() ? String(value).trim() : fallback);
      const request = {
        id,
        employeeId,
        leaveTypeCode: leaveType.code,
        startDate: payload.startDate,
        endDate: payload.endDate,
        startTime: hasSubDayTimes ? payload.startTime : null,
        endTime: hasSubDayTimes ? payload.endTime : null,
        totalDays,
        quotaYear,
        reason: payload.reason,
        attachmentId: hasAttachment ? id : null,
        attachmentFileName: payload.attachmentFile?.name || null,
        status,
        quotaRemainingBefore: remainingBefore,
        quotaRemainingAfter: remainingAfter,
        systemNote,
        requestedById: user.employeeId,
        requestedByName: user.name,
        requestedAt: now,
        reviewedById: null,
        reviewedByName: null,
        reviewedAt: null,
        reviewerNote: null,
        cancelledAt: null,
        createdAt: now,
        updatedAt: now,
        contactHouseNo: pickContact(payload.contactHouseNo, contactDefaults.contactHouseNo),
        contactSubdistrict: pickContact(payload.contactSubdistrict, contactDefaults.contactSubdistrict),
        contactDistrict: pickContact(payload.contactDistrict, contactDefaults.contactDistrict),
        contactProvince: pickContact(payload.contactProvince, contactDefaults.contactProvince),
        contactPhone: pickContact(payload.contactPhone, contactDefaults.contactPhone),
        // §5.2 purpose/emergency-filing (V125): purposeCode passthrough (shape parity only -- see
        // the comment above). emergencyFiling is always false here -- the mock never grants the
        // emergency exception, so it can never legitimately be true; a late request stays
        // AUTO_REJECTED above regardless of requestedAsEmergency.
        purposeCode: payload.purposeCode || null,
        emergencyFiling: false,
      };
      request.employeeCode = employee.code;
      request.employeeName = employee.nameTh;
      db.leaveRequests.unshift(request);
      return delay({ request: buildLeaveRecord(request, user) });
    },

    async approve(id, payload = {}) {
      const user = requireSession();
      const request = db.leaveRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอลานี้', 404);
      if (!canReviewLeave(user, request.employeeId)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (request.status !== 'SUBMITTED') fail('คำขอลานี้ได้รับการพิจารณาไปแล้ว', 409);
      const now = new Date().toISOString();
      request.status = 'APPROVED';
      request.reviewedById = user.employeeId;
      request.reviewedByName = user.name;
      request.reviewedAt = now;
      request.reviewerNote = payload.reviewerNote || null;
      request.updatedAt = now;
      return delay({ request: buildLeaveRecord(request, user) });
    },

    async reject(id, payload = {}) {
      const user = requireSession();
      const request = db.leaveRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอลานี้', 404);
      if (!canReviewLeave(user, request.employeeId)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (request.status !== 'SUBMITTED') fail('คำขอลานี้ได้รับการพิจารณาไปแล้ว', 409);
      const now = new Date().toISOString();
      request.status = 'REJECTED';
      request.reviewedById = user.employeeId;
      request.reviewedByName = user.name;
      request.reviewedAt = now;
      request.reviewerNote = payload.reviewerNote || null;
      request.updatedAt = now;
      return delay({ request: buildLeaveRecord(request, user) });
    },

    async cancel(id, payload = {}) {
      const user = requireSession();
      const request = db.leaveRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอลานี้', 404);
      const approver = canReviewLeave(user, request.employeeId);
      if (!approver && request.employeeId !== user.employeeId) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (!approver && request.status !== 'SUBMITTED') fail('พนักงานยกเลิกได้เฉพาะคำขอลาที่ยังไม่ได้รับการพิจารณาเท่านั้น', 409);
      if (!['SUBMITTED', 'APPROVED'].includes(request.status)) fail('ยกเลิกได้เฉพาะคำขอลาที่ยังอยู่ระหว่างพิจารณาเท่านั้น', 409);
      const now = new Date().toISOString();
      request.status = 'CANCELLED';
      request.cancelledAt = now;
      request.reviewerNote = payload.reviewerNote || request.reviewerNote;
      request.updatedAt = now;
      return delay({ request: buildLeaveRecord(request, user) });
    },

    // Phase A4: certificate-download button in ReviewQueueTab.jsx/MyLeaveTab.jsx. Mirrors
    // LeaveController#downloadAttachment's real access predicate
    // (LeaveService#resolveAttachmentForDownload: the owning employee, or a canReviewEmployee
    // reviewer of them). attachmentId is 1:1 with the owning leave_request's own id in this mock
    // (see create() above, `attachmentId: hasAttachment ? id : null`) — there is no separate
    // hr.leave_attachment table here. No real file bytes are ever kept (only attachmentFileName,
    // the name string, for shape parity), so this returns the same kind of demo placeholder Blob
    // every other document-download endpoint in this file already does (see
    // mockDocPlaceholderBlob's own comment) rather than the honest-404 "not supported" stance a
    // couple of newer attachment endpoints take (e.g. downloadTaxAllowanceAttachment) — the real
    // backend always has real bytes once attachmentId is non-null, so a reviewer opening a
    // legitimate certificate should see the download SUCCEED under mocks, not fail every time. Do
    // not read a mock-mode download as proof the real file/mime type streams correctly — verify
    // against the real Java service.
    async downloadAttachment(attachmentId) {
      const user = requireSession();
      const request = db.leaveRequests.find((item) => item.attachmentId === Number(attachmentId));
      if (!request) fail('ไม่พบเอกสารนี้', 404);
      const allowed = request.employeeId === user.employeeId || canReviewLeave(user, request.employeeId);
      if (!allowed) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      return mockDocPlaceholderBlob([
        `เอกสารแนบคำขอลา #${request.id}`,
        `ไฟล์: ${request.attachmentFileName || '-'}`,
      ]);
    },

    // Leave-surface IA rebuild, Phase A0 (not yet landed on the real backend): will back the
    // "รอพิจารณา" tab's badge/count with a per-manager summary of requests awaiting THIS user's
    // decision. Added here (Phase A1) ONLY so contract.test.js's hrApi<->mockApi method-surface
    // parity check stays green ahead of that work landing -- no page in this phase calls it yet,
    // and `review.isVisible` (leaveSurfaceTabs.js) still derives visibility from the already-loaded
    // `list()` response, not from this endpoint. This is a small FIXED fixture, not a rule engine:
    // it does not recompute canReviewLeave() or scan db.leaveRequests, so its shape AND its
    // authorization are not authoritative -- verify the real endpoint against LeaveService once A0
    // actually lands.
    async reviewSummary() {
      requireSession();
      return delay({ pendingCount: 0, requests: [] });
    },

    // Leave-request composer (Phase A2, #485). See LEAVE_PREVIEW_BLOCKING_FIXTURE /
    // LEAVE_PREVIEW_COUNTERS_FIXTURE above this namespace for the "not a rule engine" contract --
    // this reads only `leaveTypeCode` (to pick a fixture) and whether dates were supplied (to
    // decide `datesEvaluated`/`coverageEvaluated`, structural booleans the real DTO always
    // carries, not a rule verdict). `options` is accepted for signature parity with hrApi's
    // `(payload, options)` (real callers pass an AbortSignal there) -- honoured only for a
    // caller that is ALREADY aborted at call time, since this mock's fixed `delay()` has no
    // mechanism to reject mid-flight the way a real aborted fetch would.
    async preview(payload = {}, options = {}) {
      requireSession();
      if (options.signal?.aborted) {
        throw new DOMException('Aborted', 'AbortError');
      }
      const leaveType = leaveTypeByCode(payload.leaveTypeCode);
      const counters = LEAVE_PREVIEW_COUNTERS_FIXTURE[leaveType.code] ?? LEAVE_PREVIEW_DEFAULT_COUNTERS;
      const datesEvaluated = Boolean(payload.startDate && payload.endDate);
      if (!datesEvaluated) {
        return delay({
          preview: {
            blocking: LEAVE_PREVIEW_BLOCKING_FIXTURE[leaveType.code] ?? null,
            datesEvaluated: false,
            coverageEvaluated: false,
            totalDays: null,
            paidDays: null,
            unpaidDays: null,
            quotaYearSplits: [],
            counters,
          },
        });
      }
      const blocking = LEAVE_PREVIEW_BLOCKING_FIXTURE[leaveType.code] ?? null;
      const depth = payload.depth === 'QUICK' ? 'QUICK' : 'FULL';
      const totalDays = workingDaysBetween(payload.startDate, payload.endDate);
      const quotaYear = Number(String(payload.startDate).slice(0, 4));
      return delay({
        preview: {
          blocking,
          datesEvaluated: true,
          // Mirrors the real coverageEvaluated contract structurally (false under QUICK, false
          // once an earlier gate already blocked) without running any department-coverage fan-out
          // of its own -- there is nothing here TO run; see this namespace's header comment.
          coverageEvaluated: depth === 'FULL' && !blocking,
          totalDays: blocking ? null : totalDays,
          paidDays: blocking ? null : totalDays,
          unpaidDays: blocking ? null : 0,
          quotaYearSplits: blocking ? [] : [{
            quotaYear,
            totalDays,
            paidDays: totalDays,
            unpaidDays: 0,
            quotaRemainingBefore: leaveType.annualQuotaDays,
            quotaRemainingAfter: Math.max(0, leaveType.annualQuotaDays - totalDays),
          }],
          counters,
        },
      });
    },

    // Leave-surface IA rebuild, Phase A3: mirrors LeaveController#policyDocument's SHAPE only, not
    // its authority. The real endpoint's answer depends on server-side storage this mock has no
    // equivalent of and no file to actually serve — "not supported in mock mode" is the honest
    // answer here (CLAUDE.md: prefer that over inventing a fake success path), so this always
    // reports "not uploaded", exactly the state a fresh/unconfigured real deployment is in too. Do
    // not read an "available" render under mocks as evidence the real endpoint works — verify a
    // configured deployment against the real backend.
    async policyDocumentAvailable() {
      requireSession();
      return delay(false);
    },
    async downloadPolicyDocument() {
      requireSession();
      fail('ยังไม่มีการอัปโหลดเอกสารประกาศฉบับนี้ กรุณาติดต่อฝ่ายบุคคล', 404);
    },

    // Leave-request composer, Phase C: mirrors LeaveController#calendarContext's SHAPE only, NOT
    // real schedule resolution. This is a SMALL FIXED FIXTURE -- Mon-Fri/08:30-17:30, no six-day
    // (OPS_6D) awareness -- it does NOT call TieredWorkScheduleResolver or read
    // hr.work_schedule_assignment. `nonWorkingDates` here is plain Sat/Sun arithmetic, not
    // LeaveDayMath's schedule/holiday-aware predicate (it does NOT fold `holidays` in, unlike the
    // real LeaveCalendarContextService#get -> LeaveRepository#workingDayPredicate).
    //
    // #ot-holiday-visibility (PR 2): `holidays` USED TO be unconditionally `[]` -- that made the
    // OT verdict badge, UpcomingHolidays, and the leave composer's own holiday note all
    // unverifiable under VITE_USE_MOCKS=true, since every one of them reads this field. Now
    // sourced from MOCK_HOLIDAY_DATES (the same fixed calendar suggestOvertimeDayType/
    // resolveOvertimeDayTypeSubmitNote already read), filtered to [from, to] -- still NOT the real
    // persisted hr.holiday store (that honesty belongs to the holidays.list() admin-CRUD stub
    // below, which stays an empty stub on purpose; see its own comment).
    // Do NOT read a mock-mode render of this as evidence a six-day employee or a real holiday
    // shows up correctly -- verify against the real backend (LeaveCalendarContextIntegrationTest).
    async calendarContext(params = {}) {
      requireSession();
      const { from, to } = params;
      if (!from || !to) fail('ต้องระบุวันที่เริ่มต้นและวันที่สิ้นสุด', 400);
      if (to < from) fail('วันที่สิ้นสุดต้องไม่มาก่อนวันที่เริ่มต้น', 400);
      const holidays = [...MOCK_HOLIDAY_DATES.entries()]
        .filter(([date]) => date >= from && date <= to)
        .map(([holidayDate, nameTh]) => ({ holidayDate, nameTh }))
        .sort((a, b) => (a.holidayDate < b.holidayDate ? -1 : a.holidayDate > b.holidayDate ? 1 : 0));
      const nonWorkingDates = [];
      const cursor = new Date(`${from}T00:00:00`);
      const end = new Date(`${to}T00:00:00`);
      while (cursor <= end) {
        const day = cursor.getDay();
        // LOCAL date components, not toISOString() (UTC) -- see bangkokTodayIso's comment above
        // on why that conversion silently shifts a date backward for any positive UTC offset
        // (Bangkok is UTC+7): local midnight becomes the previous UTC day, which would report the
        // wrong non-working dates here.
        if (day === 0 || day === 6) {
          const y = cursor.getFullYear();
          const m = String(cursor.getMonth() + 1).padStart(2, '0');
          const d = String(cursor.getDate()).padStart(2, '0');
          nonWorkingDates.push(`${y}-${m}-${d}`);
        }
        cursor.setDate(cursor.getDate() + 1);
      }
      return delay({
        calendarContext: {
          from,
          to,
          holidays,
          schedule: {
            workStart: LEAVE_WORKDAY_START,
            workEnd: LEAVE_WORKDAY_END,
            graceMinutes: 5,
            requiresCheckOut: true,
            workdays: [1, 2, 3, 4, 5],
          },
          nonWorkingDates,
        },
      });
    },
  },

  // Mirrors OvertimeController + OvertimeService (overtime/) — see
  // requireManager()/managesEmployee() for the review gate, and
  // submit() -> resolveTargetEmployee() for filing on another employee's behalf.
  // Neither has an hr/admin bypass; use canReviewOvertime(), never canReviewLeave().
  overtime: {
    // Mirrors OvertimeRepository.findEmployeeOptions() — unlike leave, scope AND
    // directReport both add a division term (self + FK reports + same-division,
    // when the actor is a position-derived division manager): a ฝ่าย manager's OT
    // dropdown flags their whole division as ลูกทีม, matching prod.
    async employees() {
      const user = requireSession();
      const includeAll = ['hr', 'ceo'].includes(user.role);
      const isManager = dashboardManager(user);
      const managerDivisionId = isManager ? dashboardDivisionId(user) : null;
      const rows = db.employees
        .filter((employee) => employee.active)
        .filter((employee) => includeAll
          || employee.id === user.employeeId
          || managerIdForEmployee(employee) === user.employeeId
          || (managerDivisionId != null && employee.divisionId === managerDivisionId))
        .map((employee) => {
          const self = employee.id === user.employeeId;
          const directReport = managerIdForEmployee(employee) === user.employeeId
            || (managerDivisionId != null && employee.divisionId === managerDivisionId && !self);
          return {
            employeeId: employee.id,
            employeeCode: employee.code,
            employeeName: employee.nameTh,
            departmentName: employee.departmentTh,
            self,
            directReport,
          };
        });
      return delay({ employees: rows });
    },

    async list(params = {}) {
      const user = requireSession();
      let list = db.overtimeRequests;
      const includeAll = ['hr', 'ceo'].includes(user.role);
      if (!includeAll) list = list.filter((item) => item.employeeId === user.employeeId || canReviewOvertime(user, item.employeeId));
      if (params.employeeId) list = list.filter((item) => item.employeeId === Number(params.employeeId));
      if (params.status) list = list.filter((item) => item.status === params.status);
      if (params.from) list = list.filter((item) => item.workDate >= params.from);
      if (params.to) list = list.filter((item) => item.workDate <= params.to);
      return delay({ requests: list.map(buildOvertimeRecord) });
    },

    async create(payload) {
      const user = requireSession();
      const employeeId = payload.employeeId ? Number(payload.employeeId) : user.employeeId;
      if (!employeeId) fail('บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล', 400);
      // Filing OT on another employee's behalf is manager-only, not HR. Verified
      // against OvertimeService.submit() → resolveTargetEmployee(), which calls the
      // same managesEmployee() helper as requireManager() and has no hr/admin bypass
      // ("Employees can only request their own overtime").
      if (employeeId !== user.employeeId && !canReviewOvertime(user, employeeId)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      findEmployee(employeeId);
      validateOvertimeRetroactiveWindow(payload);
      const plannedMinutes = overtimeMinutesBetween(payload.plannedStartAt, payload.plannedEndAt);
      validateOvertimePlannedWindowSpan(payload);
      // SECURITY: payload.dayType is the employee's REQUEST, unauthenticated client input, and is
      // deliberately never used to set pay -- mirrors OvertimeService#submit's identical comment.
      // day_type/multiplier at create() are always DERIVED (suggestOvertimeDayType), never
      // DECLARED by the caller, at every stage -- feat/ot-nonworkday-rate-suggestion changed WHO
      // may later override the suggestion (the APPROVER, at approve() below, from an actor already
      // authorized to approve), never this field. The claim is still compared against the
      // suggestion (resolveOvertimeDayTypeSubmitNote) to flag a disagreement for the approver --
      // never to refuse the submit (that refusal was REMOVED, owner ruling 2026-08-08) and never
      // to feed dayType directly.
      const submitTimeNote = resolveOvertimeDayTypeSubmitNote(payload.dayType, payload.workDate);
      const dayType = suggestOvertimeDayType(payload.workDate);
      const id = Math.max(0, ...db.overtimeRequests.map((item) => item.id)) + 1;
      const now = new Date().toISOString();
      const request = {
        id,
        employeeId,
        workDate: payload.workDate,
        plannedStartAt: payload.plannedStartAt,
        plannedEndAt: payload.plannedEndAt,
        plannedMinutes,
        dayType,
        reason: payload.reason,
        status: 'SUBMITTED',
        actualMinutes: null,
        payableMinutes: null,
        calculationNote: submitTimeNote,
        requestedById: user.employeeId,
        requestedByName: user.name,
        requestedAt: now,
        managerApprovedBy: null,
        managerApprovedAt: null,
        ceoApprovedBy: null,
        ceoApprovedAt: null,
        reviewedById: null,
        reviewedByName: null,
        reviewedAt: null,
        reviewerNote: null,
        cancelledAt: null,
        createdAt: now,
        updatedAt: now,
      };
      db.overtimeRequests.unshift(request);
      return delay({ request: buildOvertimeRecord(request) });
    },

    async approve(id, payload = {}) {
      const user = requireSession();
      const request = db.overtimeRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอทำงานล่วงเวลานี้', 404);
      const now = new Date().toISOString();
      if (request.status === 'SUBMITTED') {
        // Manager-less route: SUBMITTED straight to APPROVED, doing the manager step's minute
        // calculation as well as the CEO's status flip. Mirrors OvertimeService.ceoDirectApprove.
        if (!hasManagerApproverFor(request.employeeId)) {
          if (user.role !== 'ceo') {
            // A3 (OT UAT defect #4): mirrors OvertimeService#requireCeoForManagerlessRequest --
            // state the outcome positively rather than naming the missing manager stage.
            fail('คำขอนี้ต้องให้ CEO พิจารณาเท่านั้น', 403);
          }
          // The APPROVER's decision (feat/ot-nonworkday-rate-suggestion) -- parsed only AFTER the
          // authorization check above, mirroring OvertimeService#ceoDirectApprove's own ordering
          // (calculate() runs AFTER requireCeoForManagerlessRequest), so a REJECTED approve
          // attempt never mutates dayType as a side effect. Falls back to the suggestion when
          // absent/blank -- see suggestOvertimeDayType's comment for that default source.
          const approverDayType = parseOvertimeDayTypeValue(payload.dayType);
          request.dayType = approverDayType || suggestOvertimeDayType(request.workDate);
          const multiplier = request.dayType === 'HOLIDAY' ? 3 : 1.5;
          request.status = 'APPROVED';
          request.actualMinutes = request.actualMinutes ?? request.plannedMinutes;
          request.payableMinutes = Math.round(request.actualMinutes * multiplier);
          // managerApprovedBy stays null: no manager reviewed this.
          request.ceoApprovedBy = user.employeeId;
          request.ceoApprovedAt = now;
          request.reviewedById = user.employeeId;
          request.reviewedByName = user.name;
          request.reviewedAt = now;
          request.reviewerNote = payload.reviewerNote || null;
          request.updatedAt = now;
          return delay({ request: buildOvertimeRecord(request) });
        }
        if (!canReviewOvertime(user, request.employeeId)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
        // Same authorized-decision ordering as the manager-less branch above, matching
        // OvertimeService.managerApprove calling calculate() after requireManager().
        const approverDayType = parseOvertimeDayTypeValue(payload.dayType);
        request.dayType = approverDayType || suggestOvertimeDayType(request.workDate);
        const multiplier = request.dayType === 'HOLIDAY' ? 3 : 1.5;
        request.status = 'MANAGER_APPROVED';
        request.actualMinutes = request.actualMinutes ?? request.plannedMinutes;
        request.payableMinutes = Math.round(request.actualMinutes * multiplier);
        request.managerApprovedBy = user.employeeId;
        request.managerApprovedAt = now;
        request.reviewedById = user.employeeId;
        request.reviewedByName = user.name;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || null;
        request.updatedAt = now;
        return delay({ request: buildOvertimeRecord(request) });
      }
      if (request.status === 'MANAGER_APPROVED') {
        if (user.role !== 'ceo') fail('เฉพาะ CEO เท่านั้นที่สามารถอนุมัติคำขอทำงานล่วงเวลาที่หัวหน้างานอนุมัติแล้วได้', 403);
        // Freeze point does not move (feat/ot-nonworkday-rate-suggestion): payload.dayType is
        // DELIBERATELY never read on this branch -- day_type/multiplier were already frozen at
        // the SUBMITTED->MANAGER_APPROVED step above, and this final CEO sign-off inherits that
        // decision, mirroring OvertimeService#ceoApprove (which never re-derives either).
        request.status = 'APPROVED';
        request.ceoApprovedBy = user.employeeId;
        request.ceoApprovedAt = now;
        request.reviewedById = user.employeeId;
        request.reviewedByName = user.name;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || request.reviewerNote;
        request.updatedAt = now;
        return delay({ request: buildOvertimeRecord(request) });
      }
      fail('คำขอทำงานล่วงเวลานี้ได้รับการพิจารณาไปแล้ว', 409);
    },

    async reject(id, payload = {}) {
      const user = requireSession();
      const request = db.overtimeRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอทำงานล่วงเวลานี้', 404);
      const now = new Date().toISOString();
      if (request.status === 'SUBMITTED') {
        // Symmetric with approve(): the sole reviewer must be able to refuse as well as accept.
        if (!hasManagerApproverFor(request.employeeId)) {
          if (user.role !== 'ceo') {
            // A3 (OT UAT defect #4): mirrors OvertimeService#requireCeoForManagerlessRequest --
            // state the outcome positively rather than naming the missing manager stage.
            fail('คำขอนี้ต้องให้ CEO พิจารณาเท่านั้น', 403);
          }
        } else if (!canReviewOvertime(user, request.employeeId)) {
          fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
        }
        request.status = 'REJECTED';
        request.reviewedById = user.employeeId;
        request.reviewedByName = user.name;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || null;
        request.updatedAt = now;
        return delay({ request: buildOvertimeRecord(request) });
      }
      if (request.status === 'MANAGER_APPROVED') {
        if (user.role !== 'ceo') fail('เฉพาะ CEO เท่านั้นที่สามารถอนุมัติคำขอทำงานล่วงเวลาที่หัวหน้างานอนุมัติแล้วได้', 403);
        request.status = 'REJECTED';
        request.reviewedById = user.employeeId;
        request.reviewedByName = user.name;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || null;
        request.updatedAt = now;
        return delay({ request: buildOvertimeRecord(request) });
      }
      fail('คำขอทำงานล่วงเวลานี้ได้รับการพิจารณาไปแล้ว', 409);
    },

    async cancel(id, payload = {}) {
      const user = requireSession();
      const request = db.overtimeRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอทำงานล่วงเวลานี้', 404);
      const approver = canReviewOvertime(user, request.employeeId);
      if (!approver && request.employeeId !== user.employeeId) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (!approver && request.status !== 'SUBMITTED') fail('พนักงานยกเลิกได้เฉพาะคำขอทำงานล่วงเวลาที่ยังไม่ได้รับการพิจารณาเท่านั้น', 409);
      if (!['SUBMITTED', 'MANAGER_APPROVED', 'APPROVED'].includes(request.status)) fail('ยกเลิกได้เฉพาะคำขอทำงานล่วงเวลาที่ยังอยู่ระหว่างพิจารณาเท่านั้น', 409);
      const now = new Date().toISOString();
      request.status = 'CANCELLED';
      request.cancelledAt = now;
      request.reviewerNote = payload.reviewerNote || request.reviewerNote;
      request.updatedAt = now;
      return delay({ request: buildOvertimeRecord(request) });
    },
  },

  // Mirrors AttendanceCorrectionController + AttendanceCorrectionService
  // (attendance/correction/) -- an employee who missed a clock-in/clock-out scan requests the
  // correct time; CEO approves or rejects. NO manager stage at all (simpler than overtime's
  // manager -> CEO pipeline and simpler than specialMoney's "manager can file on behalf" shape --
  // submit here is always self-only). Approving in the real backend also writes a
  // hr.attendance_punch row and flips hr.attendance_daily.is_manual_override; this mock does NOT
  // reimplement that write (there is no mock attendance_daily table to write into) -- it only
  // flips status/reviewer fields, same as every other request-review mock in this file. Never
  // treat a mock "approved" attendance correction as evidence the attendance-side write happened;
  // that is backend-only and covered by AttendanceCorrectionScopeIntegrationTest.
  attendanceCorrection: {
    async list(params = {}) {
      const user = requireSession();
      let list = db.attendanceCorrectionRequests;
      const viewAll = canViewAllAttendanceCorrection(user);
      if (!viewAll) {
        if (!user.employeeId) fail('บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล', 400);
        if (params.employeeId && Number(params.employeeId) !== user.employeeId) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
        list = list.filter((item) => item.employeeId === user.employeeId);
      } else if (params.employeeId) {
        list = list.filter((item) => item.employeeId === Number(params.employeeId));
      }
      if (params.status) list = list.filter((item) => item.status === params.status);
      const sorted = [...list].sort((a, b) => (
        (a.workDate < b.workDate ? 1 : a.workDate > b.workDate ? -1 : 0) || (b.id - a.id)
      ));
      return delay({ requests: sorted.map((item) => buildAttendanceCorrectionRecord(item, user)) });
    },

    async create(payload) {
      const user = requireSession();
      const employeeId = user.employeeId;
      if (!employeeId) fail('บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล', 400);
      findEmployee(employeeId);
      if (!payload.workDate) fail('ต้องระบุวันที่', 400);
      if (payload.workDate > bangkokTodayIso()) fail('ไม่สามารถขอแก้ไขเวลาสำหรับวันที่ในอนาคตได้', 400);
      const type = payload.correctionType;
      if (!['CHECK_IN', 'CHECK_OUT', 'BOTH'].includes(type)) fail('ประเภทการแก้ไขไม่ถูกต้อง', 400);
      const checkIn = payload.requestedCheckIn || null;
      const checkOut = payload.requestedCheckOut || null;
      // Mirrors chk_attendance_correction_fields_match_type (V135) + AttendanceCorrectionService
      // #validateRequestShape -- see that method's javadoc for why this is validated ahead of a
      // (mock-mode-nonexistent) DB constraint too, not just in the real backend.
      if (type === 'CHECK_IN' && (!checkIn || checkOut)) fail('กรุณาระบุเวลาเข้างานที่ถูกต้อง', 400);
      if (type === 'CHECK_OUT' && (!checkOut || checkIn)) fail('กรุณาระบุเวลาออกงานที่ถูกต้อง', 400);
      if (type === 'BOTH' && (!checkIn || !checkOut)) fail('กรุณาระบุทั้งเวลาเข้างานและเวลาออกงาน', 400);
      if (checkIn && checkOut && checkOut < checkIn) fail('เวลาออกงานต้องไม่อยู่ก่อนเวลาเข้างาน', 400);
      if (!payload.reason || !payload.reason.trim()) fail('ต้องระบุเหตุผล', 400);
      const hasOpenRequest = db.attendanceCorrectionRequests.some((item) => (
        item.employeeId === employeeId && item.workDate === payload.workDate && item.status === 'SUBMITTED'
      ));
      if (hasOpenRequest) fail('มีคำขอแก้ไขเวลาสำหรับวันนี้ที่ยังไม่ได้รับการพิจารณาอยู่แล้ว', 409);

      const id = Math.max(0, ...db.attendanceCorrectionRequests.map((item) => item.id)) + 1;
      const now = new Date().toISOString();
      const request = {
        id,
        employeeId,
        workDate: payload.workDate,
        correctionType: type,
        requestedCheckIn: checkIn,
        requestedCheckOut: checkOut,
        reason: payload.reason.trim(),
        status: 'SUBMITTED',
        requestedById: employeeId,
        requestedAt: now,
        reviewedById: null,
        reviewedAt: null,
        reviewerNote: null,
        cancelledAt: null,
        createdAt: now,
        updatedAt: now,
      };
      db.attendanceCorrectionRequests.unshift(request);
      return delay({ request: buildAttendanceCorrectionRecord(request, user) });
    },

    async approve(id, payload = {}) {
      const user = requireSession();
      const request = db.attendanceCorrectionRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอแก้ไขเวลานี้', 404);
      if (request.status !== 'SUBMITTED') fail('คำขอแก้ไขเวลานี้ได้รับการพิจารณาไปแล้ว', 409);
      // CEO-only, no self-approval carve-out -- mirrors AttendanceCorrectionService#requireCeo
      // exactly (a plain role check, not "unless it's your own request").
      if (user.role !== 'ceo') fail('เฉพาะ CEO เท่านั้นที่สามารถพิจารณาคำขอแก้ไขเวลาเข้า-ออกงานได้', 403);
      const now = new Date().toISOString();
      request.status = 'APPROVED';
      request.reviewedById = user.employeeId;
      request.reviewedAt = now;
      request.reviewerNote = payload.reviewerNote || null;
      request.updatedAt = now;
      return delay({ request: buildAttendanceCorrectionRecord(request, user) });
    },

    async reject(id, payload = {}) {
      const user = requireSession();
      const request = db.attendanceCorrectionRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอแก้ไขเวลานี้', 404);
      if (request.status !== 'SUBMITTED') fail('คำขอแก้ไขเวลานี้ได้รับการพิจารณาไปแล้ว', 409);
      if (user.role !== 'ceo') fail('เฉพาะ CEO เท่านั้นที่สามารถพิจารณาคำขอแก้ไขเวลาเข้า-ออกงานได้', 403);
      const now = new Date().toISOString();
      request.status = 'REJECTED';
      request.reviewedById = user.employeeId;
      request.reviewedAt = now;
      request.reviewerNote = payload.reviewerNote || null;
      request.updatedAt = now;
      return delay({ request: buildAttendanceCorrectionRecord(request, user) });
    },

    // Requester-only -- unlike overtime/specialMoney there is no reviewer-side cancel (the CEO's
    // only actions on an open request are approve/reject). Mirrors
    // AttendanceCorrectionService#cancel.
    async cancel(id) {
      const user = requireSession();
      const request = db.attendanceCorrectionRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอแก้ไขเวลานี้', 404);
      if (request.employeeId !== user.employeeId) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (request.status !== 'SUBMITTED') fail('คำขอแก้ไขเวลานี้ไม่สามารถยกเลิกได้แล้ว', 409);
      const now = new Date().toISOString();
      request.status = 'CANCELLED';
      request.cancelledAt = now;
      request.updatedAt = now;
      return delay({ request: buildAttendanceCorrectionRecord(request, user) });
    },
  },

  // Mirrors SpecialMoneyController + SpecialMoneyService (specialmoney/). Approval is CEO-only in
  // a SINGLE stage for every employee -- unlike overtime, which keeps a manager -> CEO pipeline
  // wherever the employee's ฝ่าย has a ผู้จัดการ. canReviewSpecialMoney therefore gates only
  // read-scoping and submit-on-behalf here, never approval. cancel is
  // stricter: only the employee or the person who filed on their behalf, and
  // only while still SUBMITTED (no manager-cancel across every active status
  // the way overtime allows). This mock does NOT reimplement the full policy
  // cap/eligibility engine (SpecialMoneyPolicyEvaluator) -- it approximates
  // authorization and status transitions faithfully, but `create` accepts
  // whatever requestedAmount the caller sends without recomputing/clamping it
  // against the policy caps. That enforcement is Java-only; never treat a mock
  // "successful" submit as proof the real cap logic was exercised.
  specialMoney: {
    async employees() {
      const user = requireSession();
      const includeAll = canViewAllSpecialMoney(user);
      const isManager = dashboardManager(user);
      const managerDivisionId = isManager ? dashboardDivisionId(user) : null;
      const rows = db.employees
        .filter((employee) => employee.active)
        .filter((employee) => includeAll
          || employee.id === user.employeeId
          || managerIdForEmployee(employee) === user.employeeId
          || (managerDivisionId != null && employee.divisionId === managerDivisionId))
        .map((employee) => {
          const self = employee.id === user.employeeId;
          const directReport = managerIdForEmployee(employee) === user.employeeId
            || (managerDivisionId != null && employee.divisionId === managerDivisionId && !self);
          return {
            employeeId: employee.id,
            employeeCode: employee.code,
            employeeName: employee.nameTh,
            departmentName: employee.departmentTh,
            self,
            directReport,
          };
        });
      return delay({ employees: rows });
    },

    async types() {
      requireSession();
      return delay({ types: SPECIAL_MONEY_TYPES });
    },

    async usage(params = {}) {
      const user = requireSession();
      const employeeId = params.employeeId ? Number(params.employeeId) : null;
      if (!employeeId) fail('ต้องระบุรหัสพนักงาน', 400);
      if (!canAccessSpecialMoneyEmployee(user, employeeId)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      const year = params.year ? Number(params.year) : new Date().getFullYear();
      // Mirrors SpecialMoneyRepository#findUsage. The three maps are counted over DIFFERENT status
      // sets and that difference is the whole point -- see UsageSnapshot's javadoc:
      //   amounts -> APPROVED only (money; an undecided request has consumed no balance)
      //   counts  -> SUBMITTED + MANAGER_APPROVED + APPROVED (the once-per-lifetime / once-per-year
      //              guards must see in-flight rows, or the same claim can be filed twice before
      //              either is decided and both become approvable)
      // This mock previously filtered `status === 'APPROVED'` for BOTH maps, which is the dangerous
      // direction: it under-reports usage, so mock-mode UI says "you may still claim" on a type the
      // real backend refuses. `approvedCountLifetimeByType` is a misnomer on the DTO too -- it has
      // always carried the in-flight-inclusive count. Do not "fix" it to match its name.
      //
      // P0 fix (fix/welfare-cap-year-bypass): findUsage's two year-scoped maps ALSO no longer key
      // on eventDate -- an employee-supplied, unbounded field that let the annual cap be defeated by
      // filing against a year nothing had been approved against yet. They key on the same two
      // server-stamped columns the real findUsage now does (see that method's Javadoc): payrollMonth
      // for the APPROVED amount sum (assigned by approve(), never by the client), and requestedAt
      // for the in-flight-inclusive count (stamped at create(), never by the client). Both already
      // exist on every mock row -- this mirrors which column the real query reads, not the cap
      // ENFORCEMENT itself: create() below still accepts any requestedAmount uncapped, unchanged.
      const ACTIVE_STATUSES = ['SUBMITTED', 'MANAGER_APPROVED', 'APPROVED'];
      const approvedAmountThisYearByType = {};
      const approvedCountLifetimeByType = {};
      const activeCountThisYearByType = {};
      db.specialMoneyRequests
        .filter((item) => item.employeeId === employeeId && ACTIVE_STATUSES.includes(item.status))
        .forEach((item) => {
          approvedCountLifetimeByType[item.requestType] = (approvedCountLifetimeByType[item.requestType] || 0) + 1;
          if (new Date(item.requestedAt).getFullYear() === year) {
            activeCountThisYearByType[item.requestType] = (activeCountThisYearByType[item.requestType] || 0) + 1;
          }
          if (item.status === 'APPROVED' && item.payrollMonth && new Date(item.payrollMonth).getFullYear() === year) {
            approvedAmountThisYearByType[item.requestType] =
              (approvedAmountThisYearByType[item.requestType] || 0) + Number(item.approvedAmount || 0);
          }
        });
      return delay({
        usage: {
          employeeId,
          year,
          approvedAmountThisYearByType,
          approvedCountLifetimeByType,
          activeCountThisYearByType,
        },
      });
    },

    // Mirrors SpecialMoneyService.list() + SpecialMoneyRepository.findRequests().
    //
    // The date window is NOT optional on the real backend: omitting `from`/`to` does not mean
    // "everything", it means "this calendar month". SpecialMoneyService.list() computes
    //     effectiveTo   = to   ?? LocalDate.now(Asia/Bangkok)
    //     effectiveFrom = from ?? effectiveTo.withDayOfMonth(1)
    // and findRequests filters `WHERE s.event_date BETWEEN :fromDate AND :toDate`.
    //
    // This mock previously applied NO window when the params were absent, which is the dangerous
    // direction CLAUDE.md names: it returned MORE rows than production, so a screen that silently
    // depends on the month scoping looks correct in mock mode and is empty in prod. That is exactly
    // how the CEO review queue shipped scoped to the current month while claiming "ไม่มีคำขอรออนุมัติ"
    // for anything dated outside it.
    async list(params = {}) {
      const user = requireSession();
      let list = db.specialMoneyRequests;
      const includeAll = canViewAllSpecialMoney(user);
      if (!includeAll) {
        if (params.employeeId && !canAccessSpecialMoneyEmployee(user, Number(params.employeeId))) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
        list = list.filter((item) => item.employeeId === user.employeeId || canReviewSpecialMoney(user, item.employeeId));
      }

      // Asia/Bangkok, not `new Date().toISOString()`: the backend reads the business zone, and UTC
      // runs up to 7 hours behind it. On the 1st of a month before 07:00 Bangkok the two disagree
      // about which month "today" is in, so a UTC default would silently window a different month
      // than production.
      const effectiveTo = params.to || bangkokTodayIso();
      const effectiveFrom = params.from || `${effectiveTo.slice(0, 7)}-01`;
      // SpecialMoneyService.list() throws 400 before touching the repository.
      if (effectiveTo < effectiveFrom) fail('วันที่สิ้นสุดต้องไม่มาก่อนวันที่เริ่มต้น', 400);

      if (params.employeeId) list = list.filter((item) => item.employeeId === Number(params.employeeId));
      if (params.status) list = list.filter((item) => item.status === params.status);
      if (params.type) list = list.filter((item) => item.requestType === params.type);
      list = list.filter((item) => item.eventDate >= effectiveFrom && item.eventDate <= effectiveTo);

      // Mirrors findRequests' trailing
      //   ORDER BY s.event_date DESC, s.requested_at DESC, s.special_money_request_id DESC
      // Ordering is part of the contract, not a detail: `contract.test.js` compares parameter
      // COUNTS only and cannot see it, and the same rows in a different order is how #434's
      // truncation bug hid. Sorted on a copy -- `db.specialMoneyRequests` is the live store and
      // create() relies on its own unshift order.
      const sorted = [...list].sort((a, b) => (
        (a.eventDate < b.eventDate ? 1 : a.eventDate > b.eventDate ? -1 : 0)
        || (String(a.requestedAt) < String(b.requestedAt) ? 1 : String(a.requestedAt) > String(b.requestedAt) ? -1 : 0)
        || (b.id - a.id)
      ));
      return delay({ requests: sorted.map(buildSpecialMoneyRecord) });
    },

    async create(payload) {
      const user = requireSession();
      const actorEmployeeId = user.employeeId;
      if (!actorEmployeeId) fail('บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล', 400);
      const employeeId = payload.employeeId ? Number(payload.employeeId) : actorEmployeeId;
      // Filing on another employee's behalf is manager-only, not HR -- mirrors
      // SpecialMoneyService.resolveTargetEmployee(), which has no hr/admin
      // bypass ("Employees can only submit their own special-money requests").
      if (employeeId !== actorEmployeeId && !canReviewSpecialMoney(user, employeeId)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      findEmployee(employeeId);
      const type = specialMoneyType(payload.requestType);
      if (!type) fail('ประเภทคำขอเงินพิเศษไม่ถูกต้อง', 400);
      if (!payload.eventDate) fail('ต้องระบุวันที่เกิดเหตุ', 400);
      if (!payload.requestedAmount || Number(payload.requestedAmount) <= 0) fail('requestedAmount ต้องมากกว่า 0', 400);
      if (!payload.reason || !payload.reason.trim()) fail('ต้องระบุเหตุผล', 400);

      const id = Math.max(0, ...db.specialMoneyRequests.map((item) => item.id)) + 1;
      const now = new Date().toISOString();
      const request = {
        id,
        employeeId,
        requestType: payload.requestType,
        eventDate: payload.eventDate,
        eventEndDate: payload.eventEndDate || null,
        receiptDate: payload.receiptDate || null,
        quantity: payload.quantity ?? 1,
        requestedAmount: Number(payload.requestedAmount),
        approvedAmount: null,
        payrollBucket: type.payrollBucket,
        policyVersion: 1,
        reason: payload.reason.trim(),
        detail: payload.detail || {},
        status: 'SUBMITTED',
        payrollMonth: null,
        capOverrideReason: null,
        requestedById: actorEmployeeId,
        requestedAt: now,
        managerApprovedBy: null,
        managerApprovedAt: null,
        ceoApprovedBy: null,
        ceoApprovedAt: null,
        reviewedById: null,
        reviewedAt: null,
        reviewerNote: null,
        cancelledAt: null,
        createdAt: now,
        updatedAt: now,
      };
      db.specialMoneyRequests.unshift(request);
      return delay({ request: buildSpecialMoneyRecord(request) });
    },

    // Mirrors SpecialMoneyController's attachment endpoints + SpecialMoneyService.requireCanAttach.
    async attachments(id) {
      const user = requireSession();
      const request = db.specialMoneyRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอเงินพิเศษนี้', 404);
      if (!canAccessSpecialMoneyEmployee(user, request.employeeId)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      return delay({ attachments: specialMoneyAttachmentsFor(request.id) });
    },

    async addAttachment(id, file) {
      const user = requireSession();
      const request = db.specialMoneyRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอเงินพิเศษนี้', 404);
      const isEmployee = request.employeeId === user.employeeId;
      const isRequester = request.requestedById != null && request.requestedById === user.employeeId;
      if (!isEmployee && !isRequester) fail('เฉพาะผู้ยื่นคำขอเท่านั้นที่แนบเอกสารได้', 403);
      if (request.status !== 'SUBMITTED') {
        fail('แนบเอกสารได้เฉพาะคำขอที่ยังไม่ได้รับการพิจารณาเท่านั้น', 409);
      }
      const attachment = {
        id: Math.max(0, ...db.specialMoneyAttachments.map((item) => item.id)) + 1,
        specialMoneyRequestId: request.id,
        fileName: file?.name || 'evidence.pdf',
        mimeType: file?.type || 'application/pdf',
        sizeBytes: file?.size ?? 0,
        uploadedById: user.employeeId,
        uploadedByName: user.name,
        uploadedAt: new Date().toISOString(),
      };
      db.specialMoneyAttachments.push(attachment);
      return delay({ attachment });
    },

    attachmentDownloadUrl(attachmentId) {
      return `/api/special-money/attachments/${attachmentId}`;
    },

    async approve(id, payload = {}) {
      const user = requireSession();
      const request = db.specialMoneyRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอเงินพิเศษนี้', 404);
      const now = new Date().toISOString();
      // Welfare is CEO-only in ONE stage for every employee -- no manager stage. MANAGER_APPROVED
      // is still accepted so rows written before that rule can be cleared. Mirrors
      // SpecialMoneyService.approve().
      if (['SUBMITTED', 'MANAGER_APPROVED'].includes(request.status)) {
        if (user.role !== 'ceo') fail('คำขอสวัสดิการทุกประเภทต้องได้รับการพิจารณาจาก CEO เท่านั้น', 403);
        // Mirrors SpecialMoneyService.requireEvidence(): an evidence-required type cannot be
        // approved with an empty document trail.
        const typeMeta = SPECIAL_MONEY_TYPES.find((item) => item.requestType === request.requestType);
        if (typeMeta?.evidenceRequired && specialMoneyAttachmentsFor(request.id).length === 0) {
          fail(`คำขอประเภท ${typeMeta.thaiLabel} ต้องแนบเอกสารหลักฐานก่อนจึงจะอนุมัติได้`, 400);
        }
        request.status = 'APPROVED';
        request.approvedAmount = payload.approvedAmount != null ? Number(payload.approvedAmount) : request.requestedAmount;
        request.capOverrideReason = payload.capOverrideReason || null;
        request.payrollMonth = specialMoneyPayrollMonth();
        request.ceoApprovedBy = user.employeeId;
        request.ceoApprovedAt = now;
        request.reviewedById = user.employeeId;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || request.reviewerNote;
        request.updatedAt = now;
        return delay({ request: buildSpecialMoneyRecord(request) });
      }
      fail('คำขอเงินพิเศษนี้ได้รับการพิจารณาไปแล้ว', 409);
    },

    async reject(id, payload = {}) {
      const user = requireSession();
      const request = db.specialMoneyRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอเงินพิเศษนี้', 404);
      const now = new Date().toISOString();
      if (['SUBMITTED', 'MANAGER_APPROVED'].includes(request.status)) {
        if (user.role !== 'ceo') fail('คำขอสวัสดิการทุกประเภทต้องได้รับการพิจารณาจาก CEO เท่านั้น', 403);
        request.status = 'REJECTED';
        request.reviewedById = user.employeeId;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || null;
        request.updatedAt = now;
        return delay({ request: buildSpecialMoneyRecord(request) });
      }
      fail('คำขอเงินพิเศษนี้ได้รับการพิจารณาไปแล้ว', 409);
    },

    // Stricter than overtime.cancel: only the employee themselves or whoever
    // filed on their behalf (requestedById), and only while still SUBMITTED --
    // mirrors SpecialMoneyService.cancel(), which has no manager-cancel path
    // for MANAGER_APPROVED/APPROVED the way OvertimeService does.
    async cancel(id, payload = {}) {
      const user = requireSession();
      const request = db.specialMoneyRequests.find((item) => item.id === Number(id));
      if (!request) fail('ไม่พบคำขอเงินพิเศษนี้', 404);
      const isEmployee = request.employeeId === user.employeeId;
      const isRequester = request.requestedById != null && request.requestedById === user.employeeId;
      if (!isEmployee && !isRequester) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (request.status !== 'SUBMITTED') fail('ยกเลิกได้เฉพาะคำขอเงินพิเศษที่ยังไม่ได้รับการพิจารณาเท่านั้น', 409);
      const now = new Date().toISOString();
      request.status = 'CANCELLED';
      request.cancelledAt = now;
      request.reviewerNote = payload.reviewerNote || request.reviewerNote;
      request.updatedAt = now;
      return delay({ request: buildSpecialMoneyRecord(request) });
    },
  },

  // Mirrors CommissionController + CommissionService (commission/).
  commissions: {
    // Mirrors CommissionController#list PreAuthorize exactly: SALES, SALES_MANAGER, CEO only.
    // Neither ACCOUNT nor HR may call this — account only ever gets createFromDeal, hr reads via
    // payrollReady instead. Do not loosen this to match ROLE_PERMISSIONS.canViewCommissions
    // (route access), which is deliberately broader for the two roles above.
    async list(params = {}) {
      const user = requireSession();
      if (!['sales', 'sales_manager', 'ceo'].includes(user.role)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      let list = db.commissions;
      if (user.role === 'sales') list = list.filter((item) => item.salesRepId === user.id);
      if (params.payrollMonth) list = list.filter((item) => commissionMonth(item.payrollMonth) === params.payrollMonth.slice(0, 7));
      return delay({ commissions: list.map(buildCommissionRecord) });
    },

    // Slice A2 (AUTHZ CHANGE): sales removed from SUBMIT_ROLES — account replaces it as the
    // day-to-day creator here too (this JSON/multipart path stays for sales_manager/ceo manual
    // corrections; CommissionPage's UI only calls createFromDeal for the day-to-day account
    // flow — this method is not wired into any control in this slice, kept for API parity).
    async create(payload) {
      const user = hasRole('account', 'sales_manager', 'ceo');
      if (!payload.invoiceAttachment) fail('ต้องแนบไฟล์ใบกำกับภาษี', 400);
      // `?.` and the truthiness check are both load-bearing. createManualCommission() sets
      // `invoiceDetails: null` — the faithful shape for a manual entry (V84) — so the moment ANY
      // manual commission exists this line used to throw a TypeError and take every subsequent
      // create() down with it, in the live mock app as well as in tests. That is why the four
      // MANUAL_COMMISSION_KINDS could not be seeded at all until now.
      //
      // Skipping null rows also matches the real backend rather than merely avoiding the crash:
      // CommissionService's duplicate check is a SQL comparison, and in SQL a NULL invoice number
      // is never equal to anything.
      //
      // The truthiness half is what a bare `?.` would get wrong. This method does NOT require
      // `payload.invoiceNumber` (only the attachment is checked above), so a call that omits it
      // would compare `undefined === undefined`, match the manual row, and be refused as a
      // duplicate of an invoice that does not exist. Crash traded for a spurious 409.
      if (db.commissions.some((item) => (
        item.invoiceDetails?.invoiceNumber && item.invoiceDetails.invoiceNumber === payload.invoiceNumber
      ))) {
        fail('เลขที่ใบกำกับภาษีนี้มีอยู่ในระบบแล้ว', 409);
      }
      // Step 9 gate + cross-check — mirrors CommissionService#resolveDealLinkage exactly. Unlinked
      // (sourceTicketId absent) commissions are unaffected: snapshot stays null, mismatch false.
      let dealPayableAmountSnapshot = null;
      let dealAmountMismatch = false;
      if (payload.sourceTicketId != null) {
        const linkedTicket = db.tickets.find((t) => t.id === Number(payload.sourceTicketId));
        if (!linkedTicket) fail('ไม่พบดีลนี้', 404);
        if (linkedTicket.salesStage !== 'CLOSED_PAID') {
          fail('ดีลนี้ยังไม่ถึงขั้นตอนรับชำระเงินครบถ้วน (CLOSED_PAID) จึงยังยื่นค่าคอมมิชชั่นไม่ได้', 422);
        }
        dealPayableAmountSnapshot = payableAmount(linkedTicket);
        dealAmountMismatch = commissionDealMismatch(payload.grossAmount, dealPayableAmountSnapshot);
      }
      const id = Math.max(0, ...db.commissions.map((item) => item.id)) + 1;
      const salesRepId = Number(payload.salesRepId || user.id);
      const calc = mockInvoiceCalculation(payload);
      const record = {
        id,
        sourceTicketId: payload.sourceTicketId ?? null,
        salesRepId,
        salesRepName: db.users.find((item) => item.id === salesRepId)?.name || user.name,
        submittedById: user.id,
        kind: 'SALE',
        status: 'SUBMITTED',
        payrollMonth: `${commissionMonth(payload.invoiceDate)}-01`,
        actualReceived: calc.actualReceived,
        commissionableBase: calc.commissionableBase,
        weightMultiplier: 1,
        approvedById: null,
        approvedAt: null,
        managerApprovedBy: null,
        managerApprovedByName: null,
        managerApprovedAt: null,
        ceoApprovedBy: null,
        ceoApprovedByName: null,
        ceoApprovedAt: null,
        rejectedById: null,
        rejectedByName: null,
        rejectedAt: null,
        rejectionReason: null,
        cancellationOfId: null,
        cancellationReason: null,
        dealPayableAmountSnapshot,
        dealAmountMismatch,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        invoiceDetails: {
          id,
          invoiceNumber: payload.invoiceNumber,
          invoiceDate: payload.invoiceDate,
          grossAmount: Number(payload.grossAmount || 0),
          bankFees: Number(payload.bankFees || 0),
          suspenseVat: Number(payload.suspenseVat || 0),
          transportFee: Number(payload.transportFee || 0),
          cutFee: Number(payload.cutFee || 0),
          shortfall: Number(payload.shortfall || 0),
          withholdingTax: Number(payload.withholdingTax || 0),
          overpayment: Number(payload.overpayment || 0),
          invoiceAttachmentId: id,
          invoiceAttachmentFileName: payload.invoiceAttachment?.name || 'tax-invoice.pdf',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      };
      db.commissions.unshift(record);
      return delay({ commission: buildCommissionRecord(record) });
    },

    /**
     * Slice A2 auto-create trigger: the accountant records the tax invoice for a
     * close-eligible deal and the commission is created for the deal's owner automatically —
     * sales does nothing. Mirrors CommissionService#createFromDeal, including its
     * hasActiveCommissionForTicket duplicate guard (a deal may only ever have one live SALE
     * commission) and the same CLOSED_PAID/cross-check gate `create` uses. Also records the
     * same file as an INVOICE-type ticket attachment, so `hasInvoiceAttachment` (the ticket's
     * close-gate "invoice on file" flag) becomes true from this one upload, same as the real
     * service's dual write.
     *
     * NOTE (mock-only limitation, not a backend gap): the real backend's account-role ticket
     * LIST scoping (TicketRepository#appendRoleScope / this mock's accountListScopeIncludes)
     * narrows account's GET /api/tickets to deals with money still pending — a CLOSED_PAID deal
     * (final payment already confirmed) normally drops out of that list right when the invoice
     * still needs recording. CommissionPage therefore looks up the deal by ticket id directly
     * (api.tickets.get, unaffected by that list-scoping) rather than relying solely on the
     * scoped list dropdown — see the "record invoice" panel's ticket-lookup field.
     */
    async createFromDeal(payload) {
      const user = hasRole('account');
      if (!payload.invoiceAttachment) fail('ต้องแนบไฟล์ใบกำกับภาษี', 400);
      const ticketId = Number(payload.ticketId);
      const ticket = db.tickets.find((t) => t.id === ticketId);
      if (!ticket) fail('ไม่พบดีลนี้', 404);
      const salesRepId = ticket.createdById;
      if (db.commissions.some((item) => item.sourceTicketId === ticketId
        && item.kind === 'SALE'
        && !['VOID', 'REJECTED'].includes(item.status))) {
        fail('มีรายการค่าคอมมิชชั่นสำหรับดีลนี้อยู่แล้ว', 409);
      }
      // Same null guard, same reason as commissions.create() above — a manual commission's
      // `invoiceDetails` is null and both call sites walked into it.
      if (db.commissions.some((item) => (
        item.invoiceDetails?.invoiceNumber && item.invoiceDetails.invoiceNumber === payload.invoiceNumber
      ))) {
        fail('เลขที่ใบกำกับภาษีนี้มีอยู่ในระบบแล้ว', 409);
      }
      if (ticket.salesStage !== 'CLOSED_PAID') {
        fail('ดีลนี้ยังไม่ถึงขั้นตอนรับชำระเงินครบถ้วน (CLOSED_PAID) จึงยังยื่นค่าคอมมิชชั่นไม่ได้', 422);
      }
      const dealPayableAmountSnapshot = payableAmount(ticket);
      const effectiveGrossAmount = payload.grossAmount != null && payload.grossAmount !== ''
        ? Number(payload.grossAmount)
        : dealPayableAmountSnapshot;
      const dealAmountMismatch = commissionDealMismatch(effectiveGrossAmount, dealPayableAmountSnapshot);
      const id = Math.max(0, ...db.commissions.map((item) => item.id)) + 1;
      const calc = mockInvoiceCalculation({ ...payload, grossAmount: effectiveGrossAmount });
      const record = {
        id,
        sourceTicketId: ticketId,
        salesRepId,
        salesRepName: db.users.find((item) => item.id === salesRepId)?.name || ticket.createdByName || null,
        submittedById: user.id,
        kind: 'SALE',
        status: 'SUBMITTED',
        payrollMonth: `${commissionMonth(payload.invoiceDate)}-01`,
        actualReceived: calc.actualReceived,
        commissionableBase: calc.commissionableBase,
        weightMultiplier: 1,
        approvedById: null,
        approvedAt: null,
        managerApprovedBy: null,
        managerApprovedByName: null,
        managerApprovedAt: null,
        ceoApprovedBy: null,
        ceoApprovedByName: null,
        ceoApprovedAt: null,
        rejectedById: null,
        rejectedByName: null,
        rejectedAt: null,
        rejectionReason: null,
        cancellationOfId: null,
        cancellationReason: null,
        dealPayableAmountSnapshot,
        dealAmountMismatch,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        invoiceDetails: {
          id,
          invoiceNumber: payload.invoiceNumber,
          invoiceDate: payload.invoiceDate,
          grossAmount: effectiveGrossAmount,
          bankFees: Number(payload.bankFees || 0),
          suspenseVat: Number(payload.suspenseVat || 0),
          transportFee: Number(payload.transportFee || 0),
          cutFee: Number(payload.cutFee || 0),
          shortfall: Number(payload.shortfall || 0),
          withholdingTax: Number(payload.withholdingTax || 0),
          overpayment: Number(payload.overpayment || 0),
          invoiceAttachmentId: id,
          invoiceAttachmentFileName: payload.invoiceAttachment?.name || 'tax-invoice.pdf',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      };
      db.commissions.unshift(record);
      // Dual write, mirrors CommissionService#createFromDeal saving the same file as an
      // AttachType.INVOICE ticket attachment so hasInvoiceAttachment(ticket) becomes true.
      mockAttachments.push({
        id: mockAttachSeq++,
        ticketId,
        quotationId: null,
        fileName: payload.invoiceAttachment?.name || 'tax-invoice.pdf',
        attachType: 'INVOICE',
        mimeType: payload.invoiceAttachment?.type || 'application/pdf',
        fileSize: payload.invoiceAttachment?.size || 0,
        uploadedBy: user.id,
        uploadedAt: new Date().toISOString(),
      });
      return delay({ commission: buildCommissionRecord(record) });
    },

    // Slice A2: the sales-manager/CEO review step may edit any invoice input (not just the
    // three deduction fields it always could) plus the calc-refine weightMultiplier (1/2/3; only
    // 2x is owner-confirmed policy — see handoff 102's "3x-unconfirmed note", recoverable from git
    // history per CLAUDE.md's "Where the old docs went"). Every field is value-or-existing (null
    // leaves it unchanged), and a non-blank `reason` is required on every call, mirroring
    // UpdateCommissionDeductionsRequest exactly. The final commission is ALWAYS recomputed here —
    // there is no path that sets it directly.
    async updateDeductions(id, payload) {
      hasRole('sales_manager', 'ceo');
      if (!payload.reason || !String(payload.reason).trim()) {
        fail('กรุณาระบุเหตุผลในการแก้ไข', 400);
      }
      const record = db.commissions.find((item) => item.id === Number(id));
      if (!record) fail('ไม่พบรายการค่าคอมมิชชั่นนี้', 404);
      if (['VOID', 'REJECTED'].includes(record.status)) {
        fail('ไม่สามารถแก้ไขรายการค่าคอมมิชชั่นที่ถูกยกเลิกแล้วได้', 409);
      }
      if (isManualCommissionKind(record.kind)) {
        fail('รายการค่าคอมมิชชั่นแบบกรอกเองไม่มีรายการหักจากใบกำกับภาษีให้แก้ไข', 409);
      }
      // P0 fix (fix/commission-approved-record-immutable): mirrors CommissionService
      // #updateDeductions's two new guards exactly (same order, same Thai text) -- a CLAWBACK
      // shares invoice_id with the original sale it reverses (createClawback below), so editing
      // one through its own id would silently rewrite the ORIGINAL's invoiceDetails/amounts too;
      // an APPROVED record already fed payrollReadySummary and has no route back to
      // SUBMITTED/MANAGER_APPROVED for re-review -- createClawback is the only sanctioned
      // correction. Checked before the APPROVED check for the same reason as the backend: a
      // clawback is always created APPROVED, so the status check alone would also catch it, but
      // would name the wrong reason.
      if (record.kind === 'CLAWBACK') {
        fail('รายการเรียกคืนค่าคอมมิชชั่นคำนวณจากรายการต้นทางโดยอัตโนมัติ ไม่สามารถแก้ไขได้โดยตรง', 409);
      }
      if (record.status === 'APPROVED') {
        fail('รายการค่าคอมมิชชั่นที่อนุมัติแล้วไม่สามารถแก้ไขได้ กรุณาใช้การเรียกคืนค่าคอมมิชชั่นแทน', 409);
      }
      // KNOWN GAP (same shape as the OvertimeService one near OT_RETROACTIVE_WINDOW_DAYS above):
      // the Java service also refuses this write once the record's payroll month is already
      // PROCESSED or seed-covered (CommissionService#requireCommissionPayrollMonthOpen). There is
      // no payroll_period collection in this mock, and none of the other six commission call
      // sites that guard is called from (createManualCommission/submit/createFromDeal/
      // createClawback/managerApprove/ceoApprove, all below) mirror it here either -- this is not
      // a new gap, just the existing one restated for a seventh site. The mock is therefore more
      // permissive than prod on a record whose month has already closed -- do not read a
      // successful mock edit as proof the backend would accept it.
      const valueOrExisting = (value, existing) => (value === null || value === undefined || value === '' ? existing : Number(value));
      Object.assign(record.invoiceDetails, {
        grossAmount: valueOrExisting(payload.grossAmount, record.invoiceDetails.grossAmount),
        bankFees: valueOrExisting(payload.bankFees, record.invoiceDetails.bankFees),
        suspenseVat: valueOrExisting(payload.suspenseVat, record.invoiceDetails.suspenseVat),
        transportFee: valueOrExisting(payload.transportFee, record.invoiceDetails.transportFee),
        cutFee: valueOrExisting(payload.cutFee, record.invoiceDetails.cutFee),
        shortfall: valueOrExisting(payload.shortfall, record.invoiceDetails.shortfall),
        withholdingTax: valueOrExisting(payload.withholdingTax, record.invoiceDetails.withholdingTax),
        overpayment: valueOrExisting(payload.overpayment, record.invoiceDetails.overpayment),
        updatedAt: new Date().toISOString(),
      });
      // weightMultiplier lives on commission_record, not invoice_details, and is scoped to only
      // THIS commission (never shared across other records on the same invoice) — mirrors
      // CommissionRepository#updateWeightMultiplier being keyed on commission_id, unlike the
      // amount fields below which key on invoice_id and can touch a shared clawback pair.
      if (payload.weightMultiplier !== null && payload.weightMultiplier !== undefined && payload.weightMultiplier !== '') {
        const weight = Number(payload.weightMultiplier);
        if (![1, 2, 3].includes(weight)) fail('weightMultiplier ต้องเป็น 1, 2 หรือ 3', 400);
        record.weightMultiplier = weight;
      }
      const calc = mockInvoiceCalculation(record.invoiceDetails);
      db.commissions
        .filter((item) => item.invoiceDetails.id === record.invoiceDetails.id && !['VOID', 'REJECTED'].includes(item.status))
        .forEach((item) => {
          item.actualReceived = item.kind === 'CLAWBACK' ? -Math.abs(calc.actualReceived) : calc.actualReceived;
          item.commissionableBase = item.kind === 'CLAWBACK' ? -Math.abs(calc.commissionableBase) : calc.commissionableBase;
          item.updatedAt = new Date().toISOString();
        });
      return delay({ commission: buildCommissionRecord(record) });
    },

    async approve(id) {
      const user = hasRole('sales_manager', 'ceo');
      const record = db.commissions.find((item) => item.id === Number(id));
      if (!record) fail('ไม่พบรายการค่าคอมมิชชั่นนี้', 404);
      const now = new Date().toISOString();
      if (record.status === 'SUBMITTED') {
        if (user.role !== 'sales_manager') fail('เฉพาะผู้จัดการฝ่ายขายเท่านั้นที่สามารถพิจารณาค่าคอมมิชชั่นที่ยื่นเข้ามาได้', 403);
        record.status = 'MANAGER_APPROVED';
        record.managerApprovedBy = user.employeeId || user.id;
        record.managerApprovedByName = user.name;
        record.managerApprovedAt = now;
        record.approvedById = user.id;
        record.approvedAt = now;
        record.updatedAt = now;
        return delay({ commission: buildCommissionRecord(record) });
      }
      if (record.status === 'MANAGER_APPROVED') {
        if (user.role !== 'ceo') fail('เฉพาะ CEO เท่านั้นที่สามารถพิจารณาค่าคอมมิชชั่นที่ผู้จัดการฝ่ายขายอนุมัติแล้วได้', 403);
        record.status = 'APPROVED';
        record.ceoApprovedBy = user.employeeId || user.id;
        record.ceoApprovedByName = user.name;
        record.ceoApprovedAt = now;
        record.approvedById = user.id;
        record.approvedAt = now;
        record.updatedAt = now;
        return delay({ commission: buildCommissionRecord(record) });
      }
      fail('รายการค่าคอมมิชชั่นนี้ได้รับการพิจารณาไปแล้ว', 409);
    },

    async reject(id, payload = {}) {
      const user = hasRole('sales_manager', 'ceo');
      const record = db.commissions.find((item) => item.id === Number(id));
      if (!record) fail('ไม่พบรายการค่าคอมมิชชั่นนี้', 404);
      const now = new Date().toISOString();
      if (record.status === 'SUBMITTED') {
        if (user.role !== 'sales_manager') fail('เฉพาะผู้จัดการฝ่ายขายเท่านั้นที่สามารถพิจารณาค่าคอมมิชชั่นที่ยื่นเข้ามาได้', 403);
      } else if (record.status === 'MANAGER_APPROVED') {
        if (user.role !== 'ceo') fail('เฉพาะ CEO เท่านั้นที่สามารถพิจารณาค่าคอมมิชชั่นที่ผู้จัดการฝ่ายขายอนุมัติแล้วได้', 403);
      } else {
        fail('รายการค่าคอมมิชชั่นนี้ได้รับการพิจารณาไปแล้ว', 409);
      }
      record.status = 'REJECTED';
      record.rejectedById = user.employeeId || user.id;
      record.rejectedByName = user.name;
      record.rejectedAt = now;
      record.rejectionReason = payload.reviewerNote || null;
      record.approvedById = user.id;
      record.approvedAt = now;
      record.updatedAt = now;
      return delay({ commission: buildCommissionRecord(record) });
    },

    async clawback(id, payload) {
      const user = hasRole('sales_manager', 'ceo');
      const original = db.commissions.find((item) => item.id === Number(id));
      if (!original) fail('ไม่พบรายการค่าคอมมิชชั่นนี้', 404);
      if (original.kind !== 'SALE' || original.status !== 'APPROVED') fail('เรียกคืนได้เฉพาะค่าคอมมิชชั่นประเภทการขายที่อนุมัติแล้วเท่านั้น', 409);
      if (db.commissions.some((item) => item.cancellationOfId === original.id && item.status !== 'VOID')) fail('รายการค่าคอมมิชชั่นนี้มีการเรียกคืนที่ยังดำเนินการอยู่แล้ว', 409);
      const nextId = Math.max(0, ...db.commissions.map((item) => item.id)) + 1;
      // The structuredClone below carries `weightMultiplier` over from the original
      // automatically (not overridden in the object below) — mirrors CommissionRepository
      // #createClawback's explicit copy, a correctness fix from the calc-refine slice: without
      // it, a clawback of a 2x-weighted sale would only reverse 1x of the original's
      // contribution to the monthly tier base.
      const record = {
        ...structuredClone(original),
        id: nextId,
        kind: 'CLAWBACK',
        status: 'APPROVED',
        payrollMonth: `${commissionMonth(new Date().toISOString())}-01`,
        actualReceived: -Math.abs(original.actualReceived),
        commissionableBase: -Math.abs(original.commissionableBase),
        submittedById: user.id,
        approvedById: user.id,
        approvedAt: new Date().toISOString(),
        cancellationOfId: original.id,
        cancellationReason: payload.reason,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      db.commissions.unshift(record);
      return delay({ commission: buildCommissionRecord(record) });
    },

    /**
     * Manual commission entries (feat/commission-manual-adjustments): sales_manager/CEO adds a
     * hand-typed, signed amount for kind ADJUSTMENT/MANAGER/STOCK_BONUS/INCENTIVE against
     * salesRepId's payrollMonth — no invoice, never touches the commission tier calculation.
     * Mirrors CommissionService#createManualCommission +
     * CommissionRepository#createManualCommission's two INSERT branches exactly.
     *
     * Authz here only APPROXIMATES the Java service (CLAUDE.md "Mock API contract") — the real
     * boundary (only sales_manager/ceo, zero rows for anyone else) is proven by
     * ManualCommissionIntegrationTest against real Postgres, not by this check.
     */
    async createManualCommission(payload) {
      const user = hasRole('sales_manager', 'ceo');
      const salesRepId = Number(payload.salesRepId);
      if (!payload.salesRepId || !salesRepId) fail('ต้องระบุรหัสพนักงานขาย', 400);
      if (!isManualCommissionKind(payload.kind)) {
        fail('kind ต้องเป็น ADJUSTMENT, MANAGER, STOCK_BONUS หรือ INCENTIVE', 400);
      }
      if (payload.amount === null || payload.amount === undefined || payload.amount === '' || Number.isNaN(Number(payload.amount))) {
        fail('ต้องระบุจำนวนเงิน', 400);
      }
      const amount = round2(Number(payload.amount));
      if (!payload.reason || !String(payload.reason).trim()) {
        fail('ต้องระบุเหตุผลสำหรับรายการค่าคอมมิชชั่นแบบกรอกเอง', 400);
      }
      // A MANAGER-kind entry represents team/manager commission earned, not a correction — never
      // negative. An ADJUSTMENT may legitimately be negative (a deduction/clawback-style
      // correction). Mirrors CommissionService's sign check exactly (STOCK_BONUS/INCENTIVE have
      // no backend sign check either — the form keeps them non-negative client-side only).
      if (payload.kind === 'MANAGER' && amount < 0) {
        fail('รายการค่าคอมมิชชั่นประเภท MANAGER ต้องไม่ติดลบ', 400);
      }
      const month = commissionMonth(payload.payrollMonth || new Date().toISOString());
      const ceoCreated = user.role === 'ceo';
      const now = new Date().toISOString();
      const id = Math.max(0, ...db.commissions.map((item) => item.id)) + 1;
      const rep = db.users.find((item) => item.id === salesRepId);
      const record = {
        id,
        sourceTicketId: null,
        salesRepId,
        salesRepName: rep?.name || null,
        submittedById: user.id,
        kind: payload.kind,
        status: ceoCreated ? 'APPROVED' : 'MANAGER_APPROVED',
        payrollMonth: `${month}-01`,
        actualReceived: 0,
        commissionableBase: 0,
        weightMultiplier: 1,
        approvedById: user.id,
        approvedAt: now,
        managerApprovedBy: ceoCreated ? null : (user.employeeId || user.id),
        managerApprovedByName: ceoCreated ? null : user.name,
        managerApprovedAt: ceoCreated ? null : now,
        ceoApprovedBy: ceoCreated ? (user.employeeId || user.id) : null,
        ceoApprovedByName: ceoCreated ? user.name : null,
        ceoApprovedAt: ceoCreated ? now : null,
        rejectedById: null,
        rejectedByName: null,
        rejectedAt: null,
        rejectionReason: null,
        cancellationOfId: null,
        cancellationReason: null,
        dealPayableAmountSnapshot: null,
        dealAmountMismatch: false,
        manualAmount: amount,
        manualReason: String(payload.reason).trim(),
        createdAt: now,
        updatedAt: now,
        invoiceDetails: null,
      };
      db.commissions.unshift(record);
      return delay({ commission: buildCommissionRecord(record) });
    },

    // MOCK COMMISSION FIXTURE (see the header above `commissions`): actualReceived/
    // commissionableBase below are honest arithmetic over the caller's own typed-in invoice
    // fields (mockInvoiceCalculation). existingMonthlyBase/projectedMonthlyBase/
    // projectedMonthlyCommission/incrementalCommission are NOT -- mock mode has no tier config to
    // compute them from, so they are MOCK_SIMULATION_FIXTURE's canned numbers regardless of the
    // caller's rep/month/history. The real figures come from CommissionService#simulate.
    async simulate(payload) {
      const user = requireSession();
      if (!['sales', 'sales_manager', 'ceo'].includes(user.role)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (user.role === 'sales' && (Number(payload.transportFee || 0) > 0 || Number(payload.cutFee || 0) > 0 || Number(payload.shortfall || 0) > 0)) {
        fail('ฝ่ายขายไม่มีสิทธิ์แก้ไขช่องรายการหักเงิน', 403);
      }
      const month = commissionMonth(payload.payrollMonth || new Date().toISOString());
      const calc = mockInvoiceCalculation({
        ...payload,
        transportFee: user.role === 'sales' ? 0 : payload.transportFee,
        cutFee: user.role === 'sales' ? 0 : payload.cutFee,
        shortfall: user.role === 'sales' ? 0 : payload.shortfall,
      });
      return delay({
        simulation: {
          payrollMonth: `${month}-01`,
          actualReceived: calc.actualReceived,
          commissionableBase: calc.commissionableBase,
          ...MOCK_SIMULATION_FIXTURE,
        },
      });
    },

    // MOCK COMMISSION FIXTURE (see the header above `commissions`): WHICH reps appear, and their
    // manualAdjustmentAmount, are real -- grouped from db.commissions and summed honestly ("who
    // has approved activity this month" and "sum their manual amounts" are not policy). Every
    // tier/incentive/stock-bonus figure is MOCK_PAYROLL_READY_TIER_FIXTURE, the one fixed fixture
    // reused for every rep -- the real per-rep math lives only in
    // CommissionService#computeRepPayrollCommissions.
    async payrollReady(params = {}) {
      hasRole('hr');
      const month = commissionMonth(params.payrollMonth || new Date().toISOString());
      const approved = db.commissions.filter((item) => item.status === 'APPROVED' && commissionMonth(item.payrollMonth) === month);
      const reps = new Map();
      // Manual entries (ADJUSTMENT/MANAGER/STOCK_BONUS/INCENTIVE, feat/commission-manual-
      // adjustments) never feed the tier calc — accumulated separately and added to each rep's
      // FINAL commission total only, on top of the (canned) tier commission. Only APPROVED
      // records reach this point (the `approved` filter above), so a manual entry still sitting
      // at MANAGER_APPROVED correctly does not count yet.
      const manualTotals = new Map();
      approved.forEach((item) => {
        if (isManualCommissionKind(item.kind)) {
          manualTotals.set(item.salesRepId, {
            salesRepName: item.salesRepName,
            amount: (manualTotals.get(item.salesRepId)?.amount || 0) + Number(item.manualAmount || 0),
          });
          return;
        }
        if (!reps.has(item.salesRepId)) {
          reps.set(item.salesRepId, { salesRepId: item.salesRepId, salesRepName: item.salesRepName });
        }
      });
      const { commissionableBase, tierCommission, incentiveAmount, stockBonusAmount } = MOCK_PAYROLL_READY_TIER_FIXTURE;
      const salesReps = [...reps.values()].map((rep) => {
        const manualAmount = manualTotals.get(rep.salesRepId)?.amount || 0;
        manualTotals.delete(rep.salesRepId);
        return {
          salesRepId: rep.salesRepId,
          salesRepName: rep.salesRepName,
          commissionableBase,
          commissionAmount: round2(tierCommission + incentiveAmount + stockBonusAmount + manualAmount),
          manualAdjustmentAmount: round2(manualAmount),
          incentiveAmount,
          stockBonusAmount,
        };
      });
      // A rep whose ONLY approved commission this month is a manual entry (e.g. a MANAGER
      // commission for someone with no SALE commission yet) still needs a summary row: no tier
      // activity, so commissionableBase/incentive/stockBonus are all zero and the manual amount
      // is the whole total — mirrors CommissionService#payrollReadySummary's second loop exactly.
      manualTotals.forEach((entry, salesRepId) => {
        salesReps.push({
          salesRepId,
          salesRepName: entry.salesRepName,
          commissionableBase: 0,
          commissionAmount: round2(entry.amount),
          manualAdjustmentAmount: round2(entry.amount),
          incentiveAmount: 0,
          stockBonusAmount: 0,
        });
      });
      salesReps.sort((a, b) => String(a.salesRepName || '').localeCompare(String(b.salesRepName || ''), 'th'));
      return delay({
        summary: {
          payrollMonth: `${month}-01`,
          status: 'PAYROLL_READY',
          totalCommissionableBase: salesReps.reduce((sum, item) => sum + item.commissionableBase, 0),
          totalCommissionAmount: salesReps.reduce((sum, item) => sum + item.commissionAmount, 0),
          totalIncentiveAmount: salesReps.reduce((sum, item) => sum + item.incentiveAmount, 0),
          totalStockBonusAmount: salesReps.reduce((sum, item) => sum + item.stockBonusAmount, 0),
          salesReps,
        },
      });
    },

    /**
     * fix/commission-figures-from-backend: a rep's own live monthly commission estimate. Mirrors
     * CommissionController#monthlySummary's role gate exactly (SALES, SALES_MANAGER, CEO — same
     * as list() above). MOCK COMMISSION FIXTURE (see the header above `commissions`):
     * commissionableBase/tierCommission/incentiveAmount/belowFloor/tiers are ALL canned —
     * MOCK_MONTHLY_SUMMARY_FIXTURE, `false`, and `[]` respectively — mock mode has no DB tier
     * config to compute a real per-tier breakdown from, so CommissionPage's MonthlyTierPanel shows
     * its empty-state line instead of a fabricated table. manualTotal is the one honestly-summed
     * figure: a plain sum of this rep/month's approved manual-kind demo records, no policy
     * involved. totalCommission is computed from these (not itself canned), so it tracks
     * manualTotal correctly even though two of its three inputs are frozen.
     */
    async monthlySummary(params = {}) {
      const user = requireSession();
      if (!['sales', 'sales_manager', 'ceo'].includes(user.role)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      const salesRepId = user.role === 'sales' ? user.id : Number(params.salesRepId || user.id);
      const month = commissionMonth(params.payrollMonth || new Date().toISOString());
      const manualTotal = round2(db.commissions
        .filter((item) => item.salesRepId === salesRepId
          && commissionMonth(item.payrollMonth) === month
          && isManualCommissionKind(item.kind)
          && item.status === 'APPROVED')
        .reduce((sum, item) => sum + Number(item.manualAmount || 0), 0));
      const { commissionableBase, tierCommission, incentiveAmount } = MOCK_MONTHLY_SUMMARY_FIXTURE;
      return delay({
        summary: {
          payrollMonth: `${month}-01`,
          salesRepId,
          commissionableBase,
          tierCommission,
          incentiveAmount,
          manualTotal,
          totalCommission: round2(tierCommission + incentiveAmount + manualTotal),
          belowFloor: false,
          tiers: [],
        },
      });
    },
  },

  // Deduction obligation tracking (issue #373): mirrors DeductionObligationRepository/Service.
  // See db.deductionObligations' own comment above for why the record CAN be faked genuinely here.
  //
  // No process()-populated remittance data ever exists in mock mode (see db.deductionObligationRemittances'
  // comment), so every progress read below reports totalRemitted = 0 -- an honest reflection of
  // "mock mode never runs real payroll", not a bug to fix.

  // Issue #422 A6 fix: `current` used to return `{ period: null }` unconditionally, which made
  // the draft path (getInputDraft/saveInputDraft below -- both genuine in-memory
  // implementations, unlike preview/process) totally unreachable under VITE_USE_MOCKS=true:
  // PayrollPage's canSaveDraft requires a real period, so nothing in mock mode could ever
  // exercise a draft save/restore, and anyone verifying on the mock/demo build would wrongly
  // conclude "drafts don't work" (see mockPayrollLine's own comment). The mutating actions
  // (preview/process/exportFile) stay explicit user-triggered calculations that would require
  // reproducing real payroll/tax logic to fake convincingly, so they still surface a clear "not
  // supported in mock mode" error instead of fabricating financial figures (real backend
  // implementation is in hrApi.js) -- clicking the บันทึกร่าง button under mocks therefore saves
  // the draft successfully and then shows the preview error, which is honest for mock mode, not
  // a bug.
  // Mirrors PayrollController + PayrollService (payroll/): view/export/payslip
  // reads are hr/ceo; process + distributePayslips are hr-only; downloadOwnPayslip
  // stays open to any authenticated user (Java is isAuthenticated()) so the
  // employee dashboard's "My payslip" button keeps working.
  payroll: {
    async current(params = {}) {
      hasRole('hr', 'ceo');
      const payrollMonth = params.payrollMonth
        ? `${params.payrollMonth}-01`
        : `${new Date().toISOString().slice(0, 7)}-01`;
      // A6 fix (issue #422): a few real seeded, active employees -- always PREVIEW, never
      // persisted (id: null, matching PayrollService#currentOrPreview's fallback shape for a
      // month with no saved row yet) -- so canSaveDraft (PayrollPage.jsx) can be true and the
      // draft round trip is actually reachable in the default verification surface.
      const lines = db.employees.filter((employee) => employee.active).slice(0, 3).map(mockPayrollLine);
      const sum = (pick) => lines.reduce((total, line) => total + pick(line), 0);
      return delay({
        period: {
          id: null,
          payrollMonth,
          periodStart: payrollMonth,
          periodEnd: payrollMonth,
          payDate: null,
          status: 'PREVIEW',
          processedAt: null,
          processedById: null,
          lineCount: lines.length,
          totalGross: sum((line) => line.grossEarnings),
          totalDeductions: sum((line) => line.totalDeductions),
          totalNet: sum((line) => line.netPay),
          totalSocialSecurity: sum((line) => line.socialSecurity),
          totalWithholdingTax: sum((line) => line.withholdingTax),
          lines,
        },
      });
    },
    // Special-pay carry-forward (2026-07-23): no seeded prior payroll_line data in mock mode, so
    // there is nothing to carry forward — return an empty suggestions list rather than fabricating
    // figures, same spirit as `current` returning a null period above.
    async suggestedInputs(params = {}) {
      hasRole('hr', 'ceo');
      return delay({ payrollMonth: params.payrollMonth ? `${params.payrollMonth}-01` : null, suggestions: [] });
    },
    async preview() {
      hasRole('hr', 'ceo');
      throw new Error('คำนวณเงินเดือนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async process() {
      hasRole('hr');
      throw new Error('ประมวลผลเงินเดือนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async exportFile() {
      hasRole('hr', 'ceo');
      throw new Error('ดาวน์โหลดไฟล์เงินเดือนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    // Preview-time detail xlsx export (2026-07-30) -- same "not supported in mock mode" spirit as
    // exportFile above (real tax/SSO figures, not worth fabricating).
    async exportPreviewFile() {
      hasRole('hr', 'ceo');
      throw new Error('ดาวน์โหลดไฟล์เงินเดือนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async downloadPayslip() {
      hasRole('hr', 'ceo');
      throw new Error('ดาวน์โหลดสลิปเงินเดือนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    // Bulk payslip ZIP (2026-07-30) -- same spirit as downloadPayslip above.
    async downloadPayslipsZip() {
      hasRole('hr', 'ceo');
      throw new Error('ดาวน์โหลดสลิปเงินเดือนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async downloadOwnPayslip() {
      requireSession();
      throw new Error('ดาวน์โหลดสลิปเงินเดือนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async distributePayslips() {
      hasRole('hr');
      throw new Error('ส่งอีเมลสลิปเงินเดือนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    // C1/C2 reconciliation additions (2026-07-21): same "view broader than edit" split as the rest
    // of this namespace (GET is hr/ceo, PUT is hr-only). Like preview/process above, PUT carries
    // real payroll numbers (tax allowances), so mock mode surfaces a clear "not supported" error on
    // that write rather than fabricating figures.
    //
    // GET used to return an empty list unconditionally, regardless of `year` -- documented as a
    // deliberate gap in contract.test.js's ARITY_EXEMPTIONS. "Register shows what payroll actually
    // uses" (2026-08) gave this endpoint its first UI caller (TaxAllowanceReviewPage.jsx), so an
    // empty fixture is no longer an honest stand-in -- it would be exactly CLAUDE.md's "mock omits a
    // field the feature keys on" shape, where the register's join against this endpoint would never
    // see a row under VITE_USE_MOCKS=true. Now genuinely reads db.employeeTaxAllowances (seeded by
    // buildDemoEmployeeTaxAllowances, demoPayroll.js) filtered by `year`, same shape
    // getTaxAllowanceDeclarations below already uses. Mirrors PayrollRepository#findTaxAllowanceRows'
    // `ORDER BY e.employee_code, eta.effective_month` (one row per effective_month, not one per
    // employee -- see that repository method's own doc comment).
    async getTaxAllowances(year) {
      hasRole('hr', 'ceo');
      const taxYear = year ? Number(year) : new Date().getFullYear();
      const items = db.employeeTaxAllowances
        .filter((row) => row.taxYear === taxYear)
        .sort((a, b) => (a.employeeCode || '').localeCompare(b.employeeCode || '') || a.effectiveMonth - b.effectiveMonth);
      return delay({ taxYear, items });
    },
    async saveTaxAllowances() {
      hasRole('hr');
      throw new Error('บันทึกค่าลดหย่อนภาษีไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    // Tax-allowance DECLARATION workflow (PR A, 2026-08-01) -- see db.taxAllowanceDeclarations'
    // own comment above for why this can be a genuine in-memory implementation (unlike
    // getTaxAllowances/saveTaxAllowances above), except applyTaxAllowanceDeclaration.
    // Takes a plain `year`, mirroring hrApi's `getMyTaxAllowanceDeclarations(year)` — NOT a params
    // bag. It read `params.year` until 2026-08-10, which meant the number both real callers pass
    // (TaxAllowancePage and useHrData) had no `.year`, so every request silently collapsed to the
    // current year and the tax-year selector did nothing under mocks.
    //
    // contract.test.js could not catch it: the arity check compares parameter COUNTS, and (params)
    // and (year) are both 1. This is the "mock drops an argument the real API honours" shape
    // CLAUDE.md documents — the same mechanism as the `limit` defect in #434.
    async getMyTaxAllowanceDeclarations(year) {
      const user = requireSession();
      if (!user.employeeId) fail('บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล', 400);
      const taxYear = year ? Number(year) : new Date().getFullYear();
      const items = db.taxAllowanceDeclarations
        .filter((row) => row.employeeId === user.employeeId && row.taxYear === taxYear)
        .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt))
        .map(taxAllowanceDeclarationPublic);
      // `headerPrefill` rides on this envelope and NOT on the declaration DTO, matching the real
      // response — see MyTaxAllowanceDeclarationsResponse. getTaxAllowanceDeclarations (HR's
      // register, below) must therefore NEVER grow one: that would be a bulk tax-ID export.
      return delay({ taxYear, items, headerPrefill: lorYor01HeaderPrefillFor(user.employeeId) });
    },
    async submitMyTaxAllowanceDeclaration(body = {}) {
      const user = requireSession();
      if (!user.employeeId) fail('บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล', 400);
      if (!body.taxYear) fail('ต้องระบุปีภาษี', 400);
      const taxYear = Number(body.taxYear);
      const alreadyPending = db.taxAllowanceDeclarations.some(
        (row) => row.employeeId === user.employeeId && row.taxYear === taxYear && row.status === 'PENDING'
      );
      if (alreadyPending) {
        fail('มีแบบแจ้งค่าลดหย่อนที่รอการอนุมัติสำหรับปีนี้อยู่แล้ว กรุณายกเลิกรายการเดิมก่อนยื่นใหม่', 409);
      }
      const row = newTaxAllowanceDeclarationRow({
        employeeId: user.employeeId,
        taxYear,
        effectiveMonth: body.effectiveMonth ?? 1,
        allowances: taxAllowanceAllowancesFromBody(body),
        lorYor01: taxAllowanceLorYor01FromBody(body),
        documentReference: body.documentReference ?? null,
        status: 'PENDING',
        submittedById: user.employeeId,
        onBehalf: false,
      });
      db.taxAllowanceDeclarations.push(row);
      return delay(taxAllowanceDeclarationPublic(row));
    },
    async withdrawMyTaxAllowanceDeclaration(id) {
      const user = requireSession();
      const row = db.taxAllowanceDeclarations.find((item) => item.declarationId === Number(id));
      // 404, not 403, on a foreign row -- mirrors TaxAllowanceDeclarationService#withdrawOwn.
      if (!row || row.employeeId !== user.employeeId) fail('ไม่พบแบบแจ้งค่าลดหย่อนนี้', 404);
      if (row.status !== 'PENDING') fail('เฉพาะรายการที่รออนุมัติเท่านั้นที่ยกเลิกได้', 409);
      row.status = 'WITHDRAWN';
      return delay(null);
    },
    // Tax-effect estimate (decision #4, 2026-08-01): REAL Thai tax math, run server-side through
    // PayrollCalculator twice (baseline vs proposed) -- there is no calculator in this mock file to
    // run it against, and reimplementing one here would be exactly the "never reimplement Thai tax
    // math" mistake CLAUDE.md and the tax-allowance plan doc both warn against. Not supported in
    // mock mode, same reasoning as applyTaxAllowanceDeclaration/saveTaxAllowances above.
    async estimateMyTaxAllowanceDeclaration() {
      requireSession();
      throw new Error('การประมาณการผลของค่าลดหย่อนต่อภาษีไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    // Both ล.ย.01 PDF endpoints are honest stubs rather than a fake Blob.
    //
    // The real ones fill the Revenue Department's own AcroForm with an embedded Thai font in
    // PDFBox — there is no faithful browser-side stand-in, and returning a placeholder PDF would
    // make mock-mode click-through look like evidence that the generated form is correct. It is
    // exactly the "mock mirrors a backend computation" trap CLAUDE.md documents: verify the output
    // against the real Java service, never against this file.
    async renderMyTaxAllowanceForm(declaration) {  // eslint-disable-line no-unused-vars
      requireSession();
      throw new Error('การสร้างไฟล์ PDF แบบ ล.ย.01 ไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async downloadTaxAllowanceForm(declarationId) {  // eslint-disable-line no-unused-vars
      requireSession();
      throw new Error('การดาวน์โหลดแบบ ล.ย.01 ไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async getTaxAllowanceDeclarations(params = {}) {
      hasRole('hr', 'ceo');
      const taxYear = params.year ? Number(params.year) : null;
      const items = db.taxAllowanceDeclarations
        .filter((row) => (taxYear ? row.taxYear === taxYear : true) && (params.status ? row.status === params.status : true))
        .map(taxAllowanceDeclarationPublic);
      return delay({ items });
    },
    async createTaxAllowanceDeclarationOnBehalf(body = {}) {
      const user = hasRole('hr');
      if (!body.employeeId || !body.taxYear) fail('ต้องระบุรหัสพนักงานและปีภาษี', 400);
      const employeeId = Number(body.employeeId);
      const taxYear = Number(body.taxYear);
      if (!db.employees.some((employee) => employee.id === employeeId)) fail('ไม่พบข้อมูลพนักงาน', 404);
      // Clear the way, mirroring TaxAllowanceDeclarationService#createOnBehalf.
      db.taxAllowanceDeclarations
        .filter((row) => row.employeeId === employeeId && row.taxYear === taxYear && row.status === 'PENDING')
        .forEach((row) => { row.status = 'WITHDRAWN'; });
      const row = newTaxAllowanceDeclarationRow({
        employeeId,
        taxYear,
        effectiveMonth: body.effectiveMonth ?? 1,
        allowances: taxAllowanceAllowancesFromBody(body),
        lorYor01: taxAllowanceLorYor01FromBody(body),
        documentReference: body.documentReference ?? null,
        status: 'PENDING',
        submittedById: user.employeeId,
        onBehalf: true,
      });
      supersedeApprovedTaxAllowanceDeclarations(employeeId, taxYear, row.declarationId);
      row.status = 'APPROVED';
      row.reviewedById = user.employeeId;
      row.reviewedAt = new Date().toISOString();
      row.reviewerNote = 'สร้างและอนุมัติโดยฝ่ายบุคคลในนามพนักงาน';
      db.taxAllowanceDeclarations.push(row);
      return delay(taxAllowanceDeclarationPublic(row));
    },
    // Contract fix (issue #387 live-verification): hrApi.js's wrapper calls this as
    // `(id, reviewerNote)` — a plain string, matching every other reviewer-note call site in this
    // file (e.g. leave/overtime approve/reject) — then wraps it into `{ reviewerNote }` itself only
    // for the real HTTP body. This mock previously destructured the second argument as `body = {}`
    // and read `body.reviewerNote`, which is `undefined` for a plain string: approve silently
    // dropped every note (never validated, so it never surfaced), and reject's mandatory-reason
    // check failed 400 unconditionally, making "ปฏิเสธ" impossible to drive under mocks at all.
    async approveTaxAllowanceDeclaration(id, reviewerNote) {
      const user = hasRole('hr');
      const row = db.taxAllowanceDeclarations.find((item) => item.declarationId === Number(id));
      if (!row) fail('ไม่พบแบบแจ้งค่าลดหย่อนนี้', 404);
      if (row.status !== 'PENDING') fail('รายการนี้ได้รับการพิจารณาไปแล้ว', 409);
      supersedeApprovedTaxAllowanceDeclarations(row.employeeId, row.taxYear, row.declarationId);
      row.status = 'APPROVED';
      row.reviewedById = user.employeeId;
      row.reviewedAt = new Date().toISOString();
      row.reviewerNote = reviewerNote ?? null;
      return delay(taxAllowanceDeclarationPublic(row));
    },
    async rejectTaxAllowanceDeclaration(id, reviewerNote) {
      const user = hasRole('hr');
      if (!reviewerNote?.trim()) fail('ต้องระบุเหตุผลในการปฏิเสธ', 400);
      const row = db.taxAllowanceDeclarations.find((item) => item.declarationId === Number(id));
      if (!row) fail('ไม่พบแบบแจ้งค่าลดหย่อนนี้', 404);
      if (row.status !== 'PENDING') fail('รายการนี้ได้รับการพิจารณาไปแล้ว', 409);
      row.status = 'REJECTED';
      row.reviewedById = user.employeeId;
      row.reviewedAt = new Date().toISOString();
      row.reviewerNote = reviewerNote;
      return delay(taxAllowanceDeclarationPublic(row));
    },
    // Applying promotes into hr.employee_tax_allowance and changes real withholding tax -- not
    // faked here, same "not supported in mock mode" reasoning as saveTaxAllowances above.
    async applyTaxAllowanceDeclaration() {
      hasRole('hr');
      throw new Error('การนำแบบแจ้งค่าลดหย่อนไปใช้ไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    // Yearly expiry (decision #10, 2026-08-01): the mirror of the scheduled expiry sweep.
    // Re-verifying restores hr.employee_tax_allowance's verification_status for the WHOLE year
    // (PayrollRepository#markTaxAllowanceVerified) -- real payroll-affecting state, same
    // "not supported in mock mode" reasoning as applyTaxAllowanceDeclaration above.
    async reverifyTaxAllowanceDeclaration() {
      hasRole('hr');
      throw new Error('การยืนยันแบบแจ้งค่าลดหย่อนที่หมดอายุใหม่ไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    // Caps metadata, mirroring TaxAllowanceCapCatalog -- approximate for the UI, NOT authoritative;
    // verify every cap figure against the Java service before trusting it (CLAUDE.md).
    async getTaxAllowanceCaps(params = {}) {
      requireSession();
      const taxYear = params.year ? Number(params.year) : new Date().getFullYear();
      return delay({ taxYear, caps: taxAllowanceCapsFor(taxYear) });
    },
    // Evidence attachments (decision #5, 2026-08-01): file metadata + access scoping only, no tax
    // math -- genuinely fake-able. Mirrors TaxAllowanceDeclarationService#requireOwnerOrHr's rule
    // exactly: owning employee or hr, re-checked on every call, never the uploader, never ceo.
    //
    // sectionKey (V135, feat/tax-allowance-sections): mirrors
    // TaxAllowanceDeclarationService#EVIDENCE_SECTION_KEYS/#normalizeSectionKey exactly -- blank/
    // omitted normalizes to null ("general/uncategorized"), anything outside the five known keys
    // is rejected the same way the real service rejects it (400), not silently accepted.
    async uploadTaxAllowanceAttachment(declarationId, file, sectionKey) {
      const user = requireSession();
      const declaration = db.taxAllowanceDeclarations.find((item) => item.declarationId === Number(declarationId));
      if (!declaration) fail('ไม่พบแบบแจ้งค่าลดหย่อนนี้', 404);
      requireTaxAllowanceAttachmentAccess(declaration, user);
      const normalizedSectionKey = sectionKey && String(sectionKey).trim() ? String(sectionKey).trim() : null;
      if (normalizedSectionKey && !TAX_ALLOWANCE_SECTION_KEYS.has(normalizedSectionKey)) {
        fail('sectionKey ไม่ถูกต้อง', 400);
      }
      const attachmentId = Math.max(0, ...db.taxAllowanceAttachments.map((row) => row.attachmentId)) + 1;
      const row = {
        attachmentId,
        declarationId: declaration.declarationId,
        fileName: file?.name ?? 'evidence.pdf',
        mimeType: file?.type ?? 'application/pdf',
        fileSize: file?.size ?? 0,
        uploadedBy: user.employeeId,
        uploadedAt: new Date().toISOString(),
        deletedAt: null,
        deletedBy: null,
        deleteReason: null,
        sectionKey: normalizedSectionKey,
      };
      db.taxAllowanceAttachments.push(row);
      return delay({ attachment: row });
    },
    async listTaxAllowanceAttachments(declarationId) {
      const user = requireSession();
      const declaration = db.taxAllowanceDeclarations.find((item) => item.declarationId === Number(declarationId));
      if (!declaration) fail('ไม่พบแบบแจ้งค่าลดหย่อนนี้', 404);
      requireTaxAllowanceAttachmentAccess(declaration, user);
      const items = db.taxAllowanceAttachments.filter((row) => row.declarationId === declaration.declarationId);
      return delay({ items });
    },
    // No real bytes to serve in mock mode (there is no server-side file store here) -- this exists
    // only so the contract surface matches; a mock caller gets a rejected promise rather than a
    // fabricated blob. Access is still checked first, so a scoping bug surfaces even here.
    async downloadTaxAllowanceAttachment(attachmentId) {
      const user = requireSession();
      const attachment = db.taxAllowanceAttachments.find((row) => row.attachmentId === Number(attachmentId));
      if (!attachment) fail('ไม่พบไฟล์แนบนี้', 404);
      const declaration = db.taxAllowanceDeclarations.find((item) => item.declarationId === attachment.declarationId);
      if (!declaration) fail('ไม่พบไฟล์แนบนี้', 404);
      requireTaxAllowanceAttachmentAccess(declaration, user);
      if (attachment.deletedAt) fail('ไฟล์นี้ถูกลบแล้ว', 404);
      throw new Error('การดาวน์โหลดไฟล์หลักฐานไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async deleteTaxAllowanceAttachment(attachmentId, reason) {
      const user = requireSession();
      const attachment = db.taxAllowanceAttachments.find((row) => row.attachmentId === Number(attachmentId));
      if (!attachment) fail('ไม่พบไฟล์แนบนี้', 404);
      const declaration = db.taxAllowanceDeclarations.find((item) => item.declarationId === attachment.declarationId);
      if (!declaration) fail('ไม่พบไฟล์แนบนี้', 404);
      requireTaxAllowanceAttachmentAccess(declaration, user);
      if (attachment.deletedAt) fail('ไฟล์นี้ถูกลบไปแล้ว', 409);
      attachment.deletedAt = new Date().toISOString();
      attachment.deletedBy = user.employeeId;
      attachment.deleteReason = reason ?? null;
      return delay({ ok: true });
    },
    async getYtdSeed() {
      hasRole('hr', 'ceo');
      return delay({ taxYear: new Date().getFullYear(), items: [] });
    },
    async saveYtdSeed() {
      hasRole('hr');
      throw new Error('บันทึกยอดสะสมต้นปีไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    // P0 fix (Opus review, 2026-07-30): mirrors PayrollController's component-tax-treatments mapping.
    // Same "carries real payroll figures, so mock mode surfaces a clear not-supported error on writes
    // rather than fabricating a classification matrix" reasoning as tax-allowances/ytd-seed above.
    async getComponentTaxTreatments() {
      hasRole('hr', 'ceo');
      return delay({ taxYear: new Date().getFullYear(), items: [] });
    },
    async saveComponentTaxTreatments() {
      hasRole('hr');
      throw new Error('บันทึกการจัดประเภทภาษีหัก ณ ที่จ่ายไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    // Payroll input draft (2026-07-30): a genuine in-memory implementation, unlike
    // preview/process/exportFile above -- saving a draft performs no payroll/tax calculation at
    // all (it is a raw store of whatever HR typed), so it can be faked correctly rather than
    // fabricating financial figures. Same view/edit split as the rest of this namespace.
    //
    // Optimistic concurrency (issue #422 follow-up): genuinely enforced here, not just shaped --
    // saveInputDraft requires `ifMatch` and compares it against computeDraftEtag(existing rows)
    // exactly like the real PayrollService#saveInputDraft compares against PayrollDraftETag, so
    // the conflict path (428 missing header, 409 stale token) is exercisable under
    // VITE_USE_MOCKS=true: call getInputDraft to read the current etag, save once (which advances
    // it), then attempt a second save with the FIRST etag -- that reproduces a real 409 without any
    // special test-only hook, because this mock's own state genuinely moved on, same as the real
    // table would.
    async getInputDraft(params = {}) {
      hasRole('hr', 'ceo');
      const month = params.payrollMonth ? `${params.payrollMonth}-01` : null;
      const drafts = month
        ? [...db.payrollInputDrafts.values()].filter((row) => row.payrollMonth === month)
        : [];
      return delay({ payrollMonth: month, drafts: drafts.map((row) => row.input), etag: computeDraftEtag(drafts) });
    },
    async saveInputDraft(payload = {}, { ifMatch } = {}) {
      hasRole('hr');
      if (!ifMatch) {
        // Mirrors PayrollService#saveInputDraft's message verbatim (Opus review NIT-8: no header
        // name leaked to HR).
        fail('ไม่พบข้อมูลอ้างอิงสำหรับบันทึกร่างเงินเดือน กรุณาโหลดหน้าใหม่แล้วลองอีกครั้ง', 428);
      }
      const month = payload.payrollMonth ? `${payload.payrollMonth}`.slice(0, 7) + '-01' : null;
      const existing = month
        ? [...db.payrollInputDrafts.values()].filter((row) => row.payrollMonth === month)
        : [];
      if (ifMatch !== computeDraftEtag(existing)) {
        fail('มีผู้ใช้อื่นบันทึกร่างเงินเดือนของงวดนี้ไปแล้ว กรุณาโหลดข้อมูลใหม่ก่อนบันทึกอีกครั้ง', 409);
      }
      (payload.inputs || []).forEach((input) => {
        if (input?.employeeId == null || !month) return;
        const key = `${input.employeeId}-${month}`;
        const priorVersion = db.payrollInputDrafts.get(key)?.version ?? -1;
        db.payrollInputDrafts.set(key, { payrollMonth: month, input, version: priorVersion + 1 });
      });
      return this.getInputDraft({ payrollMonth: month ? month.slice(0, 7) : null });
    },
    // Deduction obligation tracking (issue #373). Mirrors DeductionObligationController +
    // DeductionObligationService (payroll/obligation/) -- see db.deductionObligations' own comment
    // above for why the record/lifecycle CAN be faked genuinely here.
    async getMyDeductionObligations() {
      const user = requireSession();
      if (!user.employeeId) fail('บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล', 400);
      const items = db.deductionObligations
        .filter((row) => row.employeeId === user.employeeId)
        .sort((a, b) => new Date(b.startDate) - new Date(a.startDate))
        .map(deductionObligationProgressPublic);
      return delay({ items });
    },
    async getDeductionObligations(params = {}) {
      hasRole('hr', 'ceo');
      let items = db.deductionObligations;
      if (params.employeeId) items = items.filter((row) => row.employeeId === Number(params.employeeId));
      if (params.kind) items = items.filter((row) => row.kind === params.kind);
      if (params.status) items = items.filter((row) => row.status === params.status);
      items = [...items].sort((a, b) => new Date(b.startDate) - new Date(a.startDate));
      return delay({ items: items.map(deductionObligationPublic) });
    },
    async getDeductionObligationProgress(id) {
      hasRole('hr', 'ceo');
      const row = db.deductionObligations.find((item) => item.id === Number(id));
      if (!row) fail('ไม่พบรายการภาระผูกพันนี้', 404);
      return delay(deductionObligationProgressPublic(row));
    },
    async createDeductionObligation(body = {}) {
      const user = hasRole('hr');
      if (!body.employeeId || !body.kind || !body.authorityReference || !body.startDate) {
        fail('ต้องระบุ employeeId, kind, authorityReference และ startDate', 400);
      }
      const employee = db.employees.find((item) => item.id === Number(body.employeeId));
      if (!employee) fail('ไม่พบข้อมูลพนักงาน', 404);
      const alreadyActive = db.deductionObligations.some(
        (row) => row.employeeId === Number(body.employeeId) && row.kind === body.kind && row.status === 'ACTIVE'
      );
      if (alreadyActive) {
        fail('พนักงานคนนี้มีรายการหักที่ยังดำเนินการอยู่ (ACTIVE) สำหรับประเภทนี้แล้ว กรุณาแก้ไขรายการเดิม หรือปิดรายการเดิมก่อนสร้างใหม่', 409);
      }
      const row = newDeductionObligationRow({
        employeeId: Number(body.employeeId),
        kind: body.kind,
        monthlyInstructedAmount: Number(body.monthlyInstructedAmount) || 0,
        instructedTotal: body.instructedTotal == null || body.instructedTotal === '' ? null : Number(body.instructedTotal),
        authorityReference: body.authorityReference,
        startDate: body.startDate,
        notes: body.notes ?? null,
        createdById: user.employeeId,
      });
      db.deductionObligations.push(row);
      return delay(deductionObligationPublic(row));
    },
    async updateDeductionObligation(id, body = {}) {
      const user = hasRole('hr');
      const row = db.deductionObligations.find((item) => item.id === Number(id));
      if (!row) fail('ไม่พบรายการภาระผูกพันนี้', 404);
      if (row.status === 'STOPPED') fail('ไม่สามารถแก้ไขรายการที่ปิดแล้ว (STOPPED) ได้', 409);
      row.monthlyInstructedAmount = Number(body.monthlyInstructedAmount) || 0;
      row.instructedTotal = body.instructedTotal == null || body.instructedTotal === '' ? null : Number(body.instructedTotal);
      row.authorityReference = body.authorityReference ?? row.authorityReference;
      row.notes = body.notes ?? null;
      row.updatedById = user.employeeId;
      row.updatedAt = new Date().toISOString();
      return delay(deductionObligationPublic(row));
    },
    async stopDeductionObligation(id) {
      const user = hasRole('hr');
      const row = db.deductionObligations.find((item) => item.id === Number(id));
      if (!row) fail('ไม่พบรายการภาระผูกพันนี้', 404);
      if (row.status === 'STOPPED') fail('รายการนี้ถูกปิดไปแล้ว', 409);
      row.status = 'STOPPED';
      row.updatedById = user.employeeId;
      row.updatedAt = new Date().toISOString();
      return delay(deductionObligationPublic(row));
    },
    async acknowledgeDeductionObligationCompletion(id) {
      const user = hasRole('hr');
      const row = db.deductionObligations.find((item) => item.id === Number(id));
      if (!row) fail('ไม่พบรายการภาระผูกพันนี้', 404);
      if (row.status !== 'COMPLETED') fail('รายการนี้ยังไม่ครบยอดตามที่หน่วยงานแจ้ง', 409);
      row.completionAcknowledgedById = user.employeeId;
      row.completionAcknowledgedAt = new Date().toISOString();
      row.updatedById = user.employeeId;
      row.updatedAt = new Date().toISOString();
      return delay(deductionObligationPublic(row));
    },
    async overrideDeductionObligationContinue(id, body = {}) {
      const user = hasRole('hr');
      const row = db.deductionObligations.find((item) => item.id === Number(id));
      if (!row) fail('ไม่พบรายการภาระผูกพันนี้', 404);
      if (row.status !== 'COMPLETED') fail('รายการนี้ยังไม่ครบยอด จึงยังไม่มีอะไรให้ override', 409);
      if (!body.reason) fail('ต้องระบุเหตุผล', 400);
      row.overrideContinuePastTotal = true;
      row.overrideById = user.employeeId;
      row.overrideAt = new Date().toISOString();
      row.overrideReason = body.reason;
      row.updatedById = user.employeeId;
      row.updatedAt = new Date().toISOString();
      return delay(deductionObligationPublic(row));
    },
    async clearDeductionObligationOverride(id) {
      const user = hasRole('hr');
      const row = db.deductionObligations.find((item) => item.id === Number(id));
      if (!row) fail('ไม่พบรายการภาระผูกพันนี้', 404);
      if (row.status !== 'COMPLETED') fail('รายการนี้ไม่ได้อยู่ในสถานะ override', 409);
      row.overrideContinuePastTotal = false;
      row.overrideById = null;
      row.overrideAt = null;
      row.overrideReason = null;
      row.updatedById = user.employeeId;
      row.updatedAt = new Date().toISOString();
      return delay(deductionObligationPublic(row));
    },
  },

  // Mirrors AttendanceController + AttendanceService (attendance/).
  //
  // `daily` and `list` have no top-level role gate — AttendanceService.resolveScope
  // scopes by role instead (hr/ceo see all; a ฝ่าย manager — dashboardManager() —
  // is scoped to their division with no 403; everyone else is 403'd for requesting
  // another employeeId). `unmapped`, `devices`, `importDat` and `backfillCards` are
  // hr/ceo-only at the controller (AttendanceController.java).
  //
  // Days are generated rather than stored so the fixture never goes stale, and the
  // pattern deliberately produces every flag the UI renders — on time, late, early
  // out, missing each scan, approved OT, unapproved late stay, non-workday and
  // no-record — so the whole status vocabulary is reachable in mock mode.
  //
  // AUTHZ IS NOT AUTHORITATIVE HERE: verify permission behaviour against the Java
  // service, never against this file.
  attendance: {
    async daily(params = {}) {
      const user = requireSession();
      const scope = mockAttendanceScope(user, params);
      const to = params.to ? new Date(params.to) : new Date();
      const from = params.from ? new Date(params.from) : new Date(to.getFullYear(), to.getMonth(), 1);
      return delay({ days: mockAttendanceDays(scope.employees, from, to) });
    },
    // Backs the per-day drill-down: the raw scans behind one day's first/last.
    async list(params = {}) {
      const user = requireSession();
      const scope = mockAttendanceScope(user, params);
      return delay({ punches: mockAttendancePunches(scope.employees, params) });
    },
    async unmapped() {
      hasRole('hr', 'ceo');
      return delay({ badges: MOCK_UNMAPPED_BADGES });
    },
    async employees() {
      const user = requireSession();
      return delay({
        employees: mockAttendanceScope(user, {}).employees.map((employee) => ({
          employee_id: employee.id,
          employee_code: employee.code,
          employee_name: employee.nameTh,
          nick_name: employee.nickname ?? null,
          department_name: employee.departmentTh ?? null,
          division_id: employee.divisionId ?? null,
          division_name: employee.divisionTh ?? null,
        })),
      });
    },
    async recalculate() {
      hasRole('hr', 'ceo');
      // The mock generates days on the fly, so there is nothing to roll up — report zero rather
      // than pretending work happened. The real endpoint returns the count actually written.
      return delay({ recalculatedDays: 0 });
    },
    // Mirrors AttendanceController.markPresent — hr/ceo only. Not implemented here because the mock
    // has no persisted attendance_daily store to write into (days are generated on the fly, see the
    // block comment above); a fake "marked" count would look verified without proving anything about
    // the real write path or the real role gate. AUTHZ IS NOT AUTHORITATIVE HERE regardless — see the
    // block comment above.
    async markPresent() {
      hasRole('hr', 'ceo');
      throw new Error('ทำเครื่องหมายเข้างานไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async backfillCards() {
      hasRole('hr', 'ceo');
      throw new Error('แก้ไขการแมปบัตรไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async devices() {
      hasRole('hr', 'ceo');
      return delay({ devices: [] });
    },
    async importDat() {
      hasRole('hr', 'ceo');
      throw new Error('นำเข้าข้อมูลจากเครื่องสแกนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
  },

  // Mirrors HolidayController (attendance/schedule/) -- hr.holiday admin CRUD, HR/CEO only.
  // AUTHZ IS NOT AUTHORITATIVE HERE -- see CLAUDE.md "Mock API contract". No persisted hr.holiday
  // store exists in the mock, so reads return empty rather than faking data that was never written,
  // and writes throw the same "not supported in mock mode" stub already used above for
  // markPresent/backfillCards/importDat -- a fake success would look verified without proving
  // anything about the real write path or the real role gate.
  holidays: {
    async list(params) {
      void params;
      hasRole('hr', 'ceo');
      return delay({ holidays: [] });
    },
    async create(payload) {
      void payload;
      hasRole('hr', 'ceo');
      throw new Error('เพิ่มวันหยุดไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async update(date, payload) {
      void date; void payload;
      hasRole('hr', 'ceo');
      throw new Error('แก้ไขวันหยุดไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async remove(date) {
      void date;
      hasRole('hr', 'ceo');
      throw new Error('ลบวันหยุดไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async fetch() {
      hasRole('hr', 'ceo');
      return delay({ outcomes: [] });
    },
  },

  // Mirrors WorkScheduleController (attendance/schedule/) -- read-only hr.work_schedule catalogue.
  workSchedules: {
    async list() {
      hasRole('hr', 'ceo');
      return delay({ schedules: [] });
    },
  },

  // Mirrors WorkScheduleAssignmentController (attendance/schedule/) -- hr.work_schedule_assignment
  // admin CRUD, HR/CEO only. Same "no persisted store, honest stub" rationale as holidays above.
  workScheduleAssignments: {
    async list() {
      hasRole('hr', 'ceo');
      return delay({ assignments: [] });
    },
    async create(payload) {
      void payload;
      hasRole('hr', 'ceo');
      throw new Error('กำหนดตารางเวลาทำงานไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async end(assignmentId, payload) {
      void assignmentId; void payload;
      hasRole('hr', 'ceo');
      throw new Error('สิ้นสุดการกำหนดตารางเวลาทำงานไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
  },

  // Mirrors DashboardController + DashboardService (dashboard/).
  dashboard: {
    async summary() {
      const user = requireSession();
      const tickets = dashboardTickets(user);
      const pendingApprovals = dashboardPending(user, tickets);
      const notifications = dashboardNotifications(user);
      return delay({
        summary: {
          role: user.role,
          employeeId: user.employeeId ?? null,
          divisionId: dashboardDivisionId(user),
          manager: dashboardManager(user),
          generatedAt: new Date().toISOString(),
          headcount: dashboardHeadcount(user),
          pendingApprovals,
          attendance: dashboardAttendance(user),
          latestPayrollPeriodId: null,
          tickets,
          notifications,
          totalOpen: tickets.totalOpen,
          submitted: tickets.submitted,
          inReview: tickets.inReview,
          priceProposed: tickets.priceProposed,
          approved: tickets.approved,
          quotationIssued: tickets.quotationIssued,
          closedThisMonth: tickets.closedThisMonth,
          cancelledThisMonth: tickets.cancelledThisMonth,
          overdueOver3Days: tickets.overdueOver3Days,
          onHold: tickets.onHold,
          dormant: tickets.dormant,
          paymentOverdue: tickets.paymentOverdue,
          partiallyDelivered: tickets.partiallyDelivered,
        },
      });
    },
  },

  // Mirrors NotificationController + NotificationService (notification/).
  notifications: {
    async list() {
      const user = requireSession();
      const items = db.notifications
        .filter((n) => n.userId === user.id)
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        .slice(0, 50);
      return delay({ notifications: structuredClone(items) });
    },
    async markRead(id) {
      requireSession();
      const n = db.notifications.find((item) => item.id === Number(id));
      if (n) n.read = true;
      return delay({ ok: true });
    },
  },

  // Mirrors CatalogController (catalog/) — product CRUD delegates to
  // PriceImportService.addProductManual()/updateProduct()/deleteProduct().
  // Both reads stay requireSession() — open to any logged-in user, matching the
  // Java exactly. That is a product decision, not a missing gate: #205 (product
  // owner, 2026-07-16) for search(), and the owner's 2026-08-01 ruling closing
  // #388 for prices(), which confirmed #205's "browsable by any logged-in user"
  // covers the supplier purchase price. #388's actual fix is on factoryConfigs /
  // fxRates / priceCalcConfigs below.
  catalog: {
    // Ordering + truncation mirror CatalogRepository.search: `ORDER BY brand, collection, color
    // LIMIT 30`. The 30 is HARDCODED in the Java (no caller-supplied limit exists on this
    // endpoint), and it applies to *every* call — including a non-blank query. This mock used to
    // slice 30 only on the blank-query branch and return the full unsorted match set otherwise,
    // i.e. it was systematically MORE forgiving than production exactly where a caller might
    // reason about "how many matches came back" (issue #434, same shape as the `prices` bug).
    async search(q) {
      requireSession();
      const lower = (q ?? '').toLowerCase();
      const results = lower
        ? mockCatalog.filter((c) =>
            c.brand.toLowerCase().includes(lower) ||
            c.collection.toLowerCase().includes(lower) ||
            c.color.toLowerCase().includes(lower) ||
            (c.factory ?? '').toLowerCase().includes(lower))
        : [...mockCatalog];
      const ordered = [...results].sort((a, b) => (
        pgAsc(a.brand, b.brand) || pgAsc(a.collection, b.collection) || pgAsc(a.color, b.color)
      ));
      return delay({ items: ordered.slice(0, CATALOG_SEARCH_LIMIT) });
    },
    // `limit` was previously accepted by hrApi but silently DROPPED here, so the mock always
    // returned up to 50 rows regardless of what the caller asked for — a divergence that hid
    // truncation behaviour entirely from mock-driven verification (it is exactly what made
    // PricingRequestCreateModal's fuzzy catalog fallback look safe under mocks while the real
    // `LIMIT :limit` could hand it a truncated, factory-alphabetical slice). Now honoured, and
    // ordered the same way CatalogRepository orders: f.name, then collection, then productCode.
    async prices(q, factoryId, limit) {
      requireSession();
      const lower = (q ?? '').toLowerCase();
      const fid = factoryId ? Number(factoryId) : null;
      // Mirrors CatalogController: default 50, then clamped to [1, 200]. Without the upper clamp
      // the mock would happily honour ?limit=5000 and return 5000 rows where production caps at
      // 200 — the "mock is more forgiving" direction again (issue #434).
      const requested = Number(limit) > 0 ? Number(limit) : CATALOG_PRICES_DEFAULT_LIMIT;
      const cap = Math.min(Math.max(Math.trunc(requested), 1), CATALOG_PRICES_MAX_LIMIT);
      let results = mockProductPrices.filter((p) => {
        if (fid && p.factoryId !== fid) return false;
        if (!lower) return true;
        return (
          (p.productCode   ?? '').toLowerCase().includes(lower) ||
          (p.collection    ?? '').toLowerCase().includes(lower) ||
          (p.productName   ?? '').toLowerCase().includes(lower) ||
          (p.color         ?? '').toLowerCase().includes(lower) ||
          (p.surface       ?? '').toLowerCase().includes(lower) ||
          (p.factoryName   ?? '').toLowerCase().includes(lower)
        );
      });
      // `ORDER BY f.name, pp.collection NULLS LAST, pp.product_code NULLS LAST` — pgAsc keeps a
      // null AFTER every present value, which coercing to '' did not: '' sorts first, so a
      // null-collection row used to survive truncation that the real query would have dropped.
      results = [...results].sort((a, b) => (
        pgAsc(a.factoryName, b.factoryName)
        || pgAsc(a.collection, b.collection)
        || pgAsc(a.productCode, b.productCode)
      ));
      return delay({ items: results.slice(0, cap) });
    },
    // addProduct/updateProduct/deleteProduct are ceo/import only (#205), mirroring
    // CatalogController.requireCatalogEditor. The reads above are open by decision;
    // only the writes are role-gated on this resource.
    async addProduct(input = {}) {
      hasRole('ceo', 'import');
      if (input.factoryId == null) fail('factoryId จำเป็น', 400);
      if (input.price == null) fail('price จำเป็น', 400);
      const fid = Number(input.factoryId);
      const product = {
        priceId: mockProductPriceSeq++,
        factoryId: fid,
        factoryName: factoryNameFor(fid),
        productCode: input.productCode ?? null,
        grade: input.grade ?? null,
        collection: input.collection ?? null,
        productName: input.productName ?? null,
        color: input.color ?? null,
        surface: input.surface ?? null,
        sizeRaw: input.sizeRaw ?? null,
        price: Number(input.price),
        currency: input.currency ?? 'EUR',
        priceUnit: input.priceUnit ?? 'per_sqm',
        sqmPerPiece: null,
      };
      mockProductPrices.push(product);
      return delay({ priceId: product.priceId, status: 'added' });
    },
    async updateProduct(priceId, input = {}) {
      hasRole('ceo', 'import');
      if (input.price == null) fail('price จำเป็น', 400);
      const pid = Number(priceId);
      const product = mockProductPrices.find((p) => p.priceId === pid);
      if (!product) fail(`ไม่พบสินค้า price_id=${pid}`, 404);
      // factoryId is deliberately not reassignable — PriceImportService.updateProduct
      // does not touch it either.
      Object.assign(product, {
        productCode: input.productCode ?? null,
        grade: input.grade ?? null,
        collection: input.collection ?? null,
        productName: input.productName ?? null,
        color: input.color ?? null,
        surface: input.surface ?? null,
        sizeRaw: input.sizeRaw ?? null,
        price: Number(input.price),
        currency: input.currency ?? null,
        priceUnit: input.priceUnit ?? null,
      });
      return delay({ status: 'updated' });
    },
    async deleteProduct(priceId) {
      hasRole('ceo', 'import');
      const pid = Number(priceId);
      const index = mockProductPrices.findIndex((p) => p.priceId === pid);
      if (index === -1) fail(`ไม่พบสินค้า price_id=${pid}`, 404);
      mockProductPrices.splice(index, 1);
      return delay({ status: 'deleted' });
    },
  },

  // Mirrors DealStageMetaController (ticket/). Canned data, deliberately: the catalog is the same
  // fifteen constants for every caller, and data/dealStageCatalog.js is checked against
  // DealStage.java by features/tickets/stageCatalog.test.js so this fixture cannot go stale the
  // way the frontend's old hand-written stage list did. Authenticated-only on the real endpoint,
  // no role gate — the payload carries no business data.
  meta: {
    async dealStages() {
      requireSession();
      return delay(DEAL_STAGE_CATALOG);
    },
  },

  // Mirrors FactoryConfigController + FactoryEmailService (factory/).
  // #388: list() mirrors FactoryConfigController.READ_ROLES = ceo/import — the
  // supplier directory is procurement data. It was requireSession() before.
  factoryConfigs: {
    async list() {
      hasRole('ceo', 'import');
      return delay({ factories: mockFactoryConfigs });
    },
    async sendEmail(ticketId, payload) {
      // Mirrors TicketService.assertFactoryEmailAllowed: import role + real ticket.
      hasRole('import');
      findTicketRaw(Number(ticketId));
      console.log(`[mock] Factory email sent | ticket=${ticketId} factory=${payload.factory} to=${payload.to}`);
      return delay({ status: 'sent' });
    },
  },

  // Mirrors FxRateController + BotFxFetchService (pricing/).
  // Owner ruling 2026-08-02: list() widened from #388's ceo/import gate by exactly one role, to
  // sales, so the deal-create modal's ราคาตั้ง estimate can convert a catalog price to THB for a
  // plain rep. NOT opened to every authenticated session — the ruling was "should only be to sale".
  // See FxRateController.READ_ROLES for the full reasoning; it does NOT extend to priceCalcConfigs
  // below, which is the real margin policy. upsert stays CEO-only, unchanged.
  // Reminder: this mock's authz is NOT authoritative — the Java service is (see CLAUDE.md).
  fxRates: {
    async list() {
      hasRole('ceo', 'import', 'sales');
      return delay({ fxRates: structuredClone(mockFxRates) });
    },
    async upsert(currency, payload) {
      hasRole('ceo');
      const existing = mockFxRates.find((r) => r.currency === currency.toUpperCase());
      if (existing) {
        existing.rateToThb = payload.rateToThb;
        existing.effectiveDate = payload.effectiveDate ?? new Date().toISOString().slice(0, 10);
        existing.updatedAt = new Date().toISOString();
        existing.source = 'MANUAL';
        existing.fetchedAt = null;
        return delay({ fxRate: structuredClone(existing) });
      }
      const newRate = {
        id: mockFxRates.length + 1, currency: currency.toUpperCase(),
        rateToThb: payload.rateToThb,
        effectiveDate: payload.effectiveDate ?? new Date().toISOString().slice(0, 10),
        updatedAt: new Date().toISOString(),
        source: 'MANUAL', fetchedAt: null,
      };
      mockFxRates.push(newRate);
      return delay({ fxRate: structuredClone(newRate) });
    },
  },

  // Mirrors PriceCalcConfigController + PriceCalcService (pricing/).
  // #388: list() mirrors PriceCalcConfigController.READ_ROLES = ceo/import — this
  // config IS the margin policy (marginPct/importDutyPct). update stays CEO-only.
  priceCalcConfigs: {
    async list() {
      hasRole('ceo', 'import');
      return delay({ configs: structuredClone(mockPriceCalcConfigs.filter((c) => c.isCurrent)) });
    },
    async update(payload) {
      hasRole('ceo');
      mockPriceCalcConfigs
        .filter((c) => c.country === payload.country && c.isCurrent)
        .forEach((c) => { c.isCurrent = false; });
      const maxVer = Math.max(0, ...mockPriceCalcConfigs.filter((c) => c.country === payload.country).map((c) => c.version));
      const newCfg = {
        configId: mockPriceConfigSeq++, version: maxVer + 1, country: payload.country,
        freightPerSqm: Number(payload.freightPerSqm),
        insurancePerSqm: Number(payload.insurancePerSqm),
        inlandFactoryToPortPerSqm: Number(payload.inlandFactoryToPortPerSqm),
        inlandPortToWarehousePerSqm: Number(payload.inlandPortToWarehousePerSqm),
        importDutyPct: Number(payload.importDutyPct),
        marginPct: Number(payload.marginPct),
        isCurrent: true,
        effectiveFrom: payload.effectiveFrom ?? new Date().toISOString().slice(0, 10),
        updatedAt: new Date().toISOString(),
      };
      mockPriceCalcConfigs.push(newCfg);
      return delay({ config: structuredClone(newCfg) });
    },
  },

  // BRANCH 1 of the sales pricing-formula redesign (config storage + CEO editing UI only).
  // Mirrors PricingFormulaConfigController + PricingFormulaConfigRepository (pricing/) -- a NEW
  // endpoint, distinct from priceCalcConfigs above (which keeps serving the separate catalog
  // price calculator, untouched). get() mirrors READ_ROLES = ceo/import (same #388-style
  // rationale: this config IS the margin policy). update stays CEO-only.
  pricingFormulaConfig: {
    async get() {
      hasRole('ceo', 'import');
      const current = currentFormulaConfig();
      if (!current) fail('ไม่พบสูตรคำนวณราคาขาย', 404);
      return delay({ formulaConfig: sortedFormulaConfig(current) });
    },
    async update(payload) {
      hasRole('ceo');
      const maxVersion = Math.max(0, ...mockPricingFormulaConfigVersions.map((c) => c.version));
      mockPricingFormulaConfigVersions.forEach((c) => { c.isCurrent = false; });
      const newConfig = {
        formulaConfigId: mockFormulaConfigSeq++,
        version: maxVersion + 1,
        insuranceValueFactor: Number(payload.insuranceValueFactor),
        insuranceRate: Number(payload.insuranceRate),
        insuranceBuffer: Number(payload.insuranceBuffer),
        costBuffer: Number(payload.costBuffer),
        sellingBuffer: Number(payload.sellingBuffer),
        defaultMarginPct: Number(payload.defaultMarginPct),
        sellingPriceRoundUpTo: Number(payload.sellingPriceRoundUpTo),
        isCurrent: true,
        effectiveFrom: payload.effectiveFrom ?? new Date().toISOString().slice(0, 10),
        updatedAt: new Date().toISOString(),
        freightRates: (payload.freightRates ?? []).map((r) => formulaFreightRow(
          r.originCountry, Number(r.thicknessMinMm), Number(r.thicknessMaxMm),
          Number(r.qtyMinSqm), r.qtyMaxSqm == null ? null : Number(r.qtyMaxSqm), Number(r.amountThb))),
        dutyRates: (payload.dutyRates ?? []).map((r) => formulaDutyRow(r.productType, r.productLabel, Number(r.dutyPct))),
        clearanceFees: (payload.clearanceFees ?? []).map((r) => formulaClearanceRow(
          Number(r.qtyMinSqm), r.qtyMaxSqm == null ? null : Number(r.qtyMaxSqm), Number(r.amountThb))),
      };
      mockPricingFormulaConfigVersions.push(newConfig);
      return delay({ formulaConfig: sortedFormulaConfig(newConfig) });
    },
  },

  // Mirrors AttachmentController + FileStorageService (attachment/).
  attachments: {
    async list(ticketId) {
      requireAttachmentTicketAccess(ticketId);
      return delay({ attachments: structuredClone(mockAttachments.filter((a) => a.ticketId === Number(ticketId))) });
    },
    // `quotationId` was accepted by hrApi (it is sent as a multipart field when truthy) but
    // DROPPED here, with the stored record hardcoding `quotationId: null` — so no mock-driven run
    // could ever produce a quotation-scoped attachment, and any caller branching on that field
    // silently took the "unscoped" path forever (issue #434, third shape). Now threaded through.
    async upload(ticketId, file, attachType, quotationId) {
      const { user } = requireAttachmentTicketAccess(ticketId, { write: true });
      const attachment = {
        id: mockAttachSeq++, ticketId: Number(ticketId),
        quotationId: quotationId ? Number(quotationId) : null,
        fileName: file?.name ?? 'file.pdf',
        attachType: (attachType ?? 'OTHER').toUpperCase(),
        mimeType: file?.type ?? 'application/pdf',
        fileSize: file?.size ?? 0,
        uploadedBy: user.id,
        uploadedAt: new Date().toISOString(),
      };
      mockAttachments.push(attachment);
      return delay({ attachment: structuredClone(attachment) });
    },
    fileUrl: (id) => `#mock-file-${id}`,
    async delete(id) {
      const user = requireSession();
      const idx = mockAttachments.findIndex((a) => a.id === Number(id));
      if (idx < 0) fail('ไม่พบไฟล์', 404);
      // Mirrors AttachmentController.requireAttachmentWriteAccess: the uploader may always remove
      // their own file; everyone else goes through the deal's WRITE gate.
      if (mockAttachments[idx].uploadedBy !== user.id) {
        requireAttachmentTicketAccess(mockAttachments[idx].ticketId, { write: true });
      }
      mockAttachments.splice(idx, 1);
      return delay({ ok: true });
    },
  },

  // Mirrors CustomerController (customer/).
  //
  // P0 fix (customer master read gate): the three reads below used to be requireSession() only
  // — authenticated, not authorized, same bug the real CustomerController had. Now gated to
  // CustomerService.VIEWER_ROLES (an alias of TicketAccessPolicy.VIEWER_ROLES — the same set
  // requireTicketViewer above uses), derived from the two real callers: TicketCreateModal's
  // picker (sales only ever reaches it — canCreateTickets) and DepositNoticePage's customer
  // search (the full canViewTickets audience). Leaving this open while the real backend now
  // 403s employee/warehouse/qc/hr would make VITE_USE_MOCKS=true lie about the permission —
  // exactly the "mock more permissive than production" direction CLAUDE.md warns about.
  customers: {
    async create(payload) {
      hasRole('sales'); // deal-entry flow; mirrors CustomerController's requireAnyRole('sales')
      const customer = { id: mockCustomerSeq++, name: payload.name, taxId: payload.taxId || null, address: payload.address || null, branch: payload.branch || 'สำนักงานใหญ่', phone: payload.phone || null };
      mockCustomers.push(customer);
      return delay({ customer });
    },
    // Ordering + truncation mirror CustomerRepository.search: `ORDER BY name LIMIT 30`, with the
    // 30 hardcoded in the Java (no caller-supplied limit). This mock previously returned every
    // match in insertion order — unbounded and unsorted — so a caller counting results, or
    // reading "the first customer", saw something production would never return (issue #434).
    async search(q) {
      hasRole(...CUSTOMER_VIEWER_ROLES);
      const lower = (q ?? '').toLowerCase();
      const results = lower
        ? mockCustomers.filter((c) => c.name.toLowerCase().includes(lower) || (c.taxId ?? '').includes(lower))
        : [...mockCustomers];
      const ordered = [...results].sort((a, b) => pgAsc(a.name, b.name));
      return delay({ customers: ordered.slice(0, CUSTOMER_SEARCH_LIMIT) });
    },
    async contacts(customerId) {
      hasRole(...CUSTOMER_VIEWER_ROLES);
      return delay({ contacts: mockContacts.filter((c) => c.customerId === Number(customerId)) });
    },
    async createContact(customerId, payload) {
      hasRole('sales'); // mirrors CustomerController's requireAnyRole('sales')
      const contact = { id: mockContactSeq++, customerId: Number(customerId), ...payload };
      mockContacts.push(contact);
      return delay({ contact });
    },
    async projects(customerId) {
      hasRole(...CUSTOMER_VIEWER_ROLES);
      return delay({ projects: mockProjects.filter((p) => p.customerId === Number(customerId)) });
    },
    async createProject(customerId, payload) {
      hasRole('sales'); // mirrors CustomerController's requireAnyRole('sales')
      const project = { id: mockProjectSeq++, customerId: Number(customerId), name: payload.name };
      mockProjects.push(project);
      return delay({ project });
    },
  },

  // Mirrors DepositNoticeController + DepositNoticeService (deposit/).
  depositNotices: {
    async noteTemplates() {
      requireSession();
      return delay({ templates: mockNoteTemplates });
    },

    async createDraft(ticketId, payload) {
      requireSession();
      const ticket = findTicketRaw(Number(ticketId));
      // Phase 1 lifecycle gate (mirrors DepositNoticeService.createDraft).
      requireActive(ticket);
      if (!['approved', 'quotation_issued', 'document_issued'].includes(ticket.status)) fail('ดีลต้องได้รับการอนุมัติก่อน', 409);

      // Legacy path: auto-build from approved ticket items (sales.ticket_item.approved_price —
      // written only by the @Deprecated, routeless TicketService.approve).
      const legacyItems = ticket.items
        .filter((it) => it.approvedPrice != null)
        .map((it, idx) => {
          const desc = [it.brand, it.model, it.color, it.texture, it.size].filter(Boolean).join(' ');
          return { seq: idx + 1, description: desc, qty: Number(it.qty), unit: 'แผ่น', unitPrice: Number(it.approvedPrice), discountLabel: null, netUnitPrice: Number(it.approvedPrice) };
        });
      // Branch fix (deposit-notice autofill): every deal created through the pricing-request
      // chain has approved_price = NULL on every ticket_item, so legacyItems above is always
      // empty for it — mirrors DepositNoticeService.buildItemsFromRequest's own new-chain
      // fallback to the ticket's own customer quotation (latest ACCEPTED, else latest ISSUED).
      let items = payload.items?.length ? payload.items : legacyItems;
      if (!items.length) {
        const quotation = mockPickTicketQuotationForDepositNotice(ticketId);
        if (quotation) items = mockDepositNoticeItemsFromQuotation(quotation.items);
      }

      // Branch fix: header autofill (mirrors DepositNoticeService.createDraft) — a caller-
      // supplied non-blank value always wins; a ticket with no linked customer master row
      // safely leaves customerTaxId/customerAddress blank.
      const { customerTaxId, customerAddress, projectName } = mockDepositNoticeHeaderAutofill(ticket, payload);

      const notes = payload.notes ?? mockNoteTemplates.filter((t) => t.defaultSelected).map((t) => t.text);
      const nextVer = mockDepositNotices.filter((d) => d.ticketId === Number(ticketId)).length + 1;

      const doc = buildMockDoc({
        id: mockDocSeq++, ticketId: Number(ticketId), docType: 'DEPOSIT_NOTICE',
        version: nextVer, docNumber: null, issueDate: null, status: 'DRAFT',
        customerName: payload.customerName ?? ticket.customerName ?? '',
        customerTaxId: customerTaxId ?? '', customerAddress: customerAddress ?? '',
        projectName: projectName ?? '', reference: payload.reference ?? '',
        depositPercent: payload.depositPercent ?? 0.5, vatPercent: 0.07,
        notes, items, issuedByName: null, preparerName: 'จินตนา หาญมนตรี',
        hasPdf: false, hasXlsx: false,
        createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
      });
      mockDepositNotices.push(doc);
      return delay({ depositNotice: structuredClone(doc) });
    },

    async listByTicket(ticketId) {
      // Mirrors DepositNoticeService.listByTicket: viewer role + sales owner scoping
      // (and, per Phase B, import denied outright — a customer financial document).
      requireDepositNoticeViewer(ticketId);
      return delay({ depositNotices: structuredClone(mockDepositNotices.filter((d) => d.ticketId === Number(ticketId))) });
    },

    async get(docId) {
      const doc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!doc) fail('ไม่พบใบแจ้งรับมัดจำนี้', 404);
      // Mirrors DepositNoticeService.getById: read gate on the owning ticket.
      requireDepositNoticeViewer(doc.ticketId);
      return delay({ depositNotice: structuredClone(doc) });
    },

    async update(docId, payload) {
      requireSession();
      const doc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!doc) fail('ไม่พบใบแจ้งรับมัดจำนี้', 404);
      if (doc.status !== 'DRAFT') fail('ใบแจ้งรับมัดจำนี้ไม่ได้อยู่ในสถานะร่าง (DRAFT)', 409);
      Object.assign(doc, {
        customerName:    payload.customerName    ?? doc.customerName,
        customerTaxId:   payload.customerTaxId   ?? doc.customerTaxId,
        customerAddress: payload.customerAddress ?? doc.customerAddress,
        projectName:     payload.projectName     ?? doc.projectName,
        reference:       payload.reference       ?? doc.reference,
        depositPercent:  payload.depositPercent  ?? doc.depositPercent,
        notes:           payload.notes           ?? doc.notes,
        items:           payload.items?.length ? payload.items : doc.items,
        updatedAt:       new Date().toISOString(),
      });
      const updated = buildMockDoc(doc);
      Object.assign(doc, updated);
      return delay({ depositNotice: structuredClone(doc) });
    },

    async preview(docId) {
      const doc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!doc) fail('ไม่พบใบแจ้งรับมัดจำนี้', 404);
      // Mirrors DepositNoticeService.preview: read gate on the owning ticket.
      requireDepositNoticeViewer(doc.ticketId);
      // Return HTML string directly (not wrapped in JSON)
      return mockPreviewHtml(buildMockDoc(doc));
    },

    async issue(docId) {
      const user = requireSession();
      const doc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!doc) fail('ไม่พบใบแจ้งรับมัดจำนี้', 404);
      if (doc.status !== 'DRAFT') fail('ไม่ได้อยู่ในสถานะร่าง (DRAFT)', 409);
      const ticket = findTicketRaw(doc.ticketId);

      // Mirrors DepositNoticeService.issue: the document IS the payment-track step.
      // Requires a customer-confirmed quotation; advances paymentStatus and leaves
      // the main status at quotation_issued (no more document_issued flip).
      if (ticket.status !== 'quotation_issued' || ticket.paymentStatus !== 'CUSTOMER_CONFIRMED') {
        fail('ออกใบแจ้งรับมัดจำได้เฉพาะเมื่อออกใบเสนอราคาแล้วและลูกค้ายืนยันคำสั่งซื้อแล้วเท่านั้น', 409);
      }
      // Phase 1 lifecycle gate (mirrors DepositNoticeService.requireActiveLifecycle).
      requireActive(ticket);

      // Supersede previous issued docs
      mockDepositNotices.forEach((d) => { if (d.ticketId === doc.ticketId && d.id !== doc.id && d.status === 'ISSUED') d.status = 'SUPERSEDED'; });

      const thaiYear = new Date().getFullYear() + 543;
      doc.docNumber = `GLRD${String(thaiYear).slice(-2)}${String(mockDocNumberSeq++).padStart(3,'0')}`;
      doc.issueDate = new Date().toISOString().slice(0, 10);
      doc.status = 'ISSUED';
      doc.issuedByName = user.name;
      doc.updatedAt = new Date().toISOString();

      ticket.paymentStatus = 'DEPOSIT_NOTICE_ISSUED';
      ticket.updatedAt = doc.updatedAt;
      pushEvent(ticket, user, 'DEPOSIT_NOTICE_ISSUED', ticket.status, ticket.status, `เอกสาร ${doc.docNumber} ออกแล้ว`);

      return delay({ depositNotice: structuredClone(doc) });
    },

    async downloadXlsx(docId) {
      const rawDoc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!rawDoc) fail('ไม่พบใบแจ้งรับมัดจำนี้', 404);
      // Mirrors DepositNoticeService.getXlsx: read gate on the owning ticket.
      requireDepositNoticeViewer(rawDoc.ticketId);
      const backendBlob = await tryBackendBlob(`/api/deposit-notices/${docId}/file?format=xlsx`);
      if (backendBlob) return backendBlob;
      const doc = buildMockDoc(rawDoc);

      // Demo placeholder — real xlsx from DepositNoticeRenderer.java (server, Apache POI)
      const items = (doc.items ?? []).map((it, i) => {
        const net = Number(it.netUnitPrice ?? it.unitPrice) || 0;
        const qty = Number(it.qty) || 0;
        return `${i + 1}. ${it.description ?? ''} — ${qty} ${it.unit ?? 'แผ่น'} × ${Number(it.unitPrice) || 0} = ${net * qty}`;
      });
      const lines = [
        `ใบแจ้งยอดมัดจำ  เลขที่ ${doc.docNumber ?? 'DRAFT'}`,
        `วันที่: ${mockThaiDate(doc.issueDate ? new Date(doc.issueDate) : new Date())}`,
        `เรียน ${doc.customerName ?? ''}`,
        ...(doc.reference ? [`อ้างอิง: ${doc.reference}`] : []),
        ...(doc.projectName ? [`Project: ${doc.projectName}`] : []),
        '',
        ...items,
        '',
        `ยอดก่อนภาษี: ${doc.subtotal ?? 0}`,
        `มัดจำ ${((doc.depositPercent ?? 0.5) * 100)}%: ${doc.depositAmount ?? 0}`,
        `ภาษี 7%: ${doc.vatAmount ?? 0}`,
        `ยอดชำระ: ${doc.totalPayable ?? 0}`,
      ];
      return mockDocPlaceholderBlob(lines);
    },

    async downloadPdf(docId) {
      const rawDoc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!rawDoc) fail('ไม่พบใบแจ้งรับมัดจำนี้', 404);
      // Mirrors DepositNoticeService.getPdf: read gate on the owning ticket.
      requireDepositNoticeViewer(rawDoc.ticketId);
      const blob = await tryBackendBlob(`/api/deposit-notices/${docId}/file?format=pdf`);
      if (blob) return blob;
      const html = mockPreviewHtml(buildMockDoc(rawDoc));
      return new Blob([html], { type: 'text/html;charset=utf-8' });
    },
  },

  // Mirrors PriceImportController + PriceImportService (catalog/importer/).
  priceImport: {
    async factories() {
      hasRole('ceo', 'import');
      return delay(mockPriceImportFactories);
    },
    async createFactory(name, country, defaultCurrency) {
      // #205: PriceImportController now gates every endpoint (including reads) to
      // ceo/import via requireImporter(session) — mirror that here.
      hasRole('ceo', 'import');
      if (!name || !String(name).trim()) fail('ชื่อโรงงานห้ามว่าง', 400);
      const factory = {
        factoryId: mockPriceImportFactorySeq++,
        name: String(name).trim(),
        country: country && String(country).trim() ? String(country).trim().toUpperCase() : null,
        defaultCurrency: defaultCurrency && String(defaultCurrency).trim()
          ? String(defaultCurrency).trim().toUpperCase()
          : 'EUR',
        numberFormat: 'eu',
      };
      mockPriceImportFactories.push(factory);
      return delay(factory);
    },
    async versions(factoryId) {
      hasRole('ceo', 'import');
      const fid = Number(factoryId);
      return delay(mockPriceImportVersions.filter((v) => v.factoryId === fid));
    },
    // Mirrors PriceImportService.uploadAndCommit — parse → stage → validate → commit
    // in one shot, returning UploadCommitResult(versionId, parsedRows, committedRows,
    // retainedRows, errorCount, errors). Gated ceo/import (#205), matching
    // PriceImportController.uploadAndCommit's requireImporter(session) gate.
    //
    // The mock cannot parse a real .xlsx in the browser, so the parsed batch is
    // fabricated — the same simplification upload() already makes with its fixed
    // parsedRows: 12. retainedRows counts the factory's pre-existing products, which
    // is the closest honest stand-in for commit()'s incremental merge (old products
    // not matched by the new file are carried forward, not dropped).
    async uploadAndCommit(factoryId, file, label) {
      hasRole('ceo', 'import');
      const fid = Number(factoryId);
      if (!mockPriceImportFactories.some((f) => f.factoryId === fid)) {
        fail(`ไม่พบ import profile สำหรับ factory id=${fid}`, 404);
      }

      const retainedRows = mockProductPrices.filter((p) => p.factoryId === fid).length;

      const versionId = mockPriceVersionSeq++;
      mockPriceImportVersions.push({
        versionId,
        factoryId: fid,
        label: label || file?.name || `Version ${versionId}`,
        status: 'DRAFT',
        createdAt: new Date().toISOString(),
        uploadedByName: 'Admin',
      });

      const factoryName = factoryNameFor(fid);
      const parsed = [
        { productCode: 'IMP-60120-WHT', collection: 'Imported Series', productName: 'White Lappato', color: 'White', surface: 'Lappato',  sizeRaw: '60x120', price: 41.50, sqmPerPiece: 0.72 },
        { productCode: 'IMP-60120-GRY', collection: 'Imported Series', productName: 'Grey Naturale', color: 'Grey',  surface: 'Naturale', sizeRaw: '60x120', price: 39.90, sqmPerPiece: 0.72 },
        { productCode: 'IMP-8080-BEI',  collection: 'Imported Series', productName: 'Beige Matt',    color: 'Beige', surface: 'Matt',     sizeRaw: '80x80',  price: 36.00, sqmPerPiece: 0.64 },
      ];
      parsed.forEach((row) => {
        mockProductPrices.push({
          priceId: mockProductPriceSeq++,
          factoryId: fid,
          factoryName,
          grade: null,
          currency: 'EUR',
          priceUnit: 'per_sqm',
          ...row,
        });
      });

      activateVersion(versionId);

      return delay({
        versionId,
        parsedRows: parsed.length,
        committedRows: parsed.length,
        retainedRows,
        errorCount: 0,
        errors: [],
      });
    },
    // Field names below mirror PriceImportService.UploadReport/StagingReport/CommitResult
    // exactly (#206) — the mock previously invented stagedRows/parseErrors,
    // totalRows/errorRows/newRows/changedRows/removedRows/unchangedRows, and
    // inserted/updated/archived, none of which exist on the real DTOs.
    async upload(factoryId, file, label) {
      hasRole('ceo', 'import');
      const fid = Number(factoryId);
      const versionId = mockPriceVersionSeq++;
      mockPriceImportVersions.push({
        versionId, factoryId: fid,
        label: label || file?.name || `Version ${versionId}`,
        status: 'DRAFT',
        createdAt: new Date().toISOString(),
        uploadedByName: 'Admin',
      });
      return delay({
        versionId,
        sessionId: crypto.randomUUID(),
        parsedRows: 12,
        errorCount: 0,
        errors: [],
      });
    },
    async validate(versionId) {
      hasRole('ceo', 'import');
      return delay({ status: 'validated', versionId: Number(versionId) });
    },
    async staging(versionId) {
      hasRole('ceo', 'import');
      return delay({
        versionId: Number(versionId),
        totalStaged: 12,
        validCount: 11,
        invalidCount: 1,
        newProducts: 10,
        removedProducts: 1,
        priceChanged: 2,
        prevVersionId: null,
        sampleErrors: [],
      });
    },
    async commit(versionId) {
      hasRole('ceo', 'import');
      const vid = Number(versionId);
      // Mirrors PriceImportService.commit()'s requireDraft() — re-committing an
      // already-ACTIVE/ARCHIVED version is a 409, not a silent re-activation.
      const version = mockPriceImportVersions.find((item) => item.versionId === vid);
      if (version && version.status !== 'DRAFT') {
        fail(`version ${vid} สถานะ ${version.status} (ต้อง DRAFT)`, 409);
      }
      const versionsArchived = activateVersion(vid);
      return delay({ versionId: vid, committed: 10, retained: 2, versionsArchived });
    },
    async getProfile(factoryId) {
      hasRole('ceo', 'import');
      void factoryId;
      return delay(JSON.stringify({ number_format: 'eu', sheets: [{ name: 'Sheet1', header_row: 1 }], columns: {} }));
    },
    async updateProfile(factoryId, json) {
      hasRole('ceo', 'import');
      void json;
      return delay({ status: 'updated', factoryId: Number(factoryId) });
    },
  },

  // Mirrors PricingRequestController + PricingRequestService (pricingrequest/).
  pricingRequests: {
    async listForTicket(ticketId) {
      const user = requireSession();
      if (!PRICING_REQUEST_VIEWER_ROLES.includes(user.role)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      const ticket = db.tickets.find((t) => t.id === Number(ticketId));
      if (!ticket) fail('ไม่พบดีลนี้', 404);
      // Mirrors PricingRequestService.listForTicket: sales may only see requests
      // on tickets they created.
      if (user.role === 'sales' && ticket.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      const draftOversight = user.role === 'ceo' || user.role === 'sales_manager';
      const items = mockPricingRequests
        .filter((pr) => pr.ticketId === Number(ticketId))
        .filter((pr) => pr.status !== 'DRAFT' || draftOversight || ticket.createdById === user.id)
        .map(buildPricingRequestSummary);
      return delay({ items });
    },

    async queue(params = {}) {
      const user = requireSession();
      // Mirrors PricingRequestService.list: same viewer roles as a single
      // request, plus sales is scoped to only its own created tickets.
      if (!PRICING_REQUEST_VIEWER_ROLES.includes(user.role)) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      // The real gate is PricingRequestStatus.isValid (PricingRequestService.list 400s anything
      // else). V140 removed COSTING_IN_PROGRESS and MORE_INFO_REQUIRED from that set, so they are
      // removed here too — a mock that still accepted them would be MORE permissive than
      // production, which is the direction CLAUDE.md calls out as the dangerous one. This list is
      // still a strict SUBSET of PricingRequestStatus.VALUES (the Step 3/4/5 statuses —
      // CEO_REVIEWING, APPROVED_FOR_QUOTATION, COSTING_REVISION_REQUIRED, QUOTATION_ISSUED,
      // QUOTATION_ACCEPTED — were never mirrored here); stricter than production is the safe
      // direction, and closing that pre-existing gap is out of scope for V140.
      if (params.status && ![
        'DRAFT',
        'SUBMITTED',
        'IMPORT_REVIEWING',
        'AWAITING_FACTORY_RESPONSE',
        'READY_FOR_CEO_REVIEW',
        'CANCELLED',
        'SUPERSEDED',
      ].includes(params.status)) {
        fail(`ไม่รองรับสถานะ '${params.status}'`, 400);
      }
      let list = mockPricingRequests;
      if (user.role === 'sales') {
        list = list.filter((pr) => db.tickets.find((t) => t.id === pr.ticketId)?.createdById === user.id);
      }
      // Mirrors PricingRequestService.list's draft-privacy clause: a DRAFT is the
      // owning rep's private scratchpad, visible only to them plus ceo/sales_manager
      // oversight. import must not see it in the queue even though it sees
      // every other status. Without this the mock is MORE permissive than the Java
      // service, which is the direction that only surfaces in production (#199).
      const draftOversight = user.role === 'ceo' || user.role === 'sales_manager';
      list = list.filter((pr) => pr.status !== 'DRAFT'
        || draftOversight
        || db.tickets.find((t) => t.id === pr.ticketId)?.createdById === user.id);
      if (params.status) {
        list = list.filter((pr) => pr.status === params.status);
      } else {
        // Mirrors the same method's default-queue branch: dead rows do not pollute it.
        list = list.filter((pr) => pr.status !== 'CANCELLED');
      }
      if (params.assignedImportId) list = list.filter((pr) => pr.assignedImportId === Number(params.assignedImportId));
      const activeOnly = params.activeOnly === undefined || params.activeOnly === true || params.activeOnly === 'true';
      if (activeOnly) {
        list = list.filter((pr) => (db.tickets.find((t) => t.id === pr.ticketId)?.lifecycle ?? 'ACTIVE') === 'ACTIVE');
      }
      return delay({ items: list.map(buildPricingRequestSummary) });
    },

    async get(id) {
      const user = requireSession();
      const pr = requirePricingRequestViewable(id, user);
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async create(ticketId, payload) {
      // Mirrors PricingRequestService.createDraft: sales (deal owner), deal
      // must be ACTIVE, and every field is validated BEFORE persisting.
      const user = hasRole('sales');
      const ticket = db.tickets.find((t) => t.id === Number(ticketId));
      if (!ticket) fail('ไม่พบดีลนี้', 404);
      requirePricingRequestDealActive(ticket);
      if (ticket.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (!payload.clientRequestId || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(payload.clientRequestId)) {
        fail('clientRequestId ต้องเป็น UUID', 400);
      }
      const existing = mockPricingRequests.find((pr) => (
        pr.requestedById === user.id && pr.clientRequestId === payload.clientRequestId
      ));
      if (existing) {
        if (existing.ticketId !== Number(ticketId)) fail('clientRequestId นี้ถูกใช้ไปแล้วกับดีลอื่น', 409);
        return delay({ pricingRequest: buildPricingRequestDetail(existing) });
      }
      if (!PRICING_REQUEST_RECIPIENT_VALUES.includes(payload.recipientType)) {
        fail(`ไม่รองรับประเภทผู้รับ '${payload.recipientType}'`, 400);
      }
      if (!payload.items?.length) fail('ต้องมีรายการอย่างน้อย 1 รายการ', 400);
      requirePricingRequestItemFieldsValid(payload.items);
      for (const item of payload.items) {
        if (!PRICING_REQUEST_QUANTITY_TYPE_VALUES.includes(item.quantityType)) {
          fail(`ไม่รองรับประเภทจำนวน '${item.quantityType}'`, 400);
        }
        // Mirrors PricingRequestService.validateItems's UnitBasis.isValid check.
        if (!UNIT_BASIS_VALUES.includes(item.requestedUnitBasis)) {
          fail(`ไม่รองรับ requestedUnitBasis '${item.requestedUnitBasis}'`, 400);
        }
      }
      if (payload.targetCurrency && payload.targetCurrency.trim().length !== 3) {
        fail('targetCurrency ต้องเป็นรหัสสกุลเงิน 3 ตัวอักษร', 400);
      }
      if (payload.recipientContactId == null && !payload.recipientLabel?.trim()) {
        fail('ต้องระบุผู้รับคำขอราคา (recipientContactId หรือ recipientLabel)', 400);
      }
      const validSourceItemIds = new Set((ticket.items ?? []).map((i) => i.id));
      for (const item of payload.items) {
        if (item.sourceTicketItemId != null && !validSourceItemIds.has(item.sourceTicketItemId)) {
          fail(`sourceTicketItemId ${item.sourceTicketItemId} ไม่ได้เป็นของดีล ${ticketId}`, 400);
        }
      }

      const now = new Date().toISOString();
      const id = mockPricingRequestSeq++;
      const requestCode = nextPricingRequestCode();
      const items = payload.items.map((item, i) => ({
        id: mockPricingRequestItemSeq++,
        pricingRequestId: id,
        sourceTicketItemId: item.sourceTicketItemId ?? null,
        productId: item.productId ?? null,
        variantId: item.variantId ?? null,
        brand: item.brand ?? null,
        model: item.model ?? null,
        productDescription: item.productDescription ?? null,
        color: item.color ?? null,
        texture: item.texture ?? null,
        size: item.size ?? null,
        factory: item.factory ?? null,
        requestedQty: item.requestedQty,
        requestedQtySqm: item.requestedQtySqm ?? null,
        requestedUnit: item.requestedUnit,
        requestedUnitBasis: item.requestedUnitBasis,
        quantityType: item.quantityType,
        targetDeliveryDate: item.targetDeliveryDate ?? null,
        deliveryLocation: item.deliveryLocation ?? null,
        specialRequirement: item.specialRequirement ?? null,
        sortOrder: i,
        // Catalog snapshot (Finding A, financial-integrity review commit 3) — populated only
        // by submit()'s snapshotCatalogSelections mirror below, never at create/update time,
        // matching PricingRequestRepository.create/updateDraft/snapshotCatalogSelections.
        priceListVersionId: null,
        catalogPriceId: null,
        catalogBasePrice: null,
        catalogCurrency: null,
        catalogEffectiveDate: null,
        resolvedFactoryId: null,
        resolvedFactoryName: null,
        catalogProductCode: null,
        catalogBrand: null,
        catalogCollection: null,
        catalogModel: null,
      }));
      const pr = {
        id, requestCode, ticketId: Number(ticketId),
        recipientType: payload.recipientType,
        recipientContactId: payload.recipientContactId ?? null,
        recipientLabel: payload.recipientLabel ?? null,
        status: 'DRAFT',
        requestedById: user.id, requestedByName: user.name,
        assignedImportId: null, assignedImportName: null,
        requiredDate: payload.requiredDate ?? null,
        customerTargetPrice: payload.customerTargetPrice ?? null,
        targetCurrency: normalizePricingRequestCurrency(payload.targetCurrency),
        note: payload.note ?? null,
        clientRequestId: payload.clientRequestId,
        // DB column is `revision_no INTEGER NOT NULL DEFAULT 1` with
        // `chk_pricing_request_revision CHECK (revision_no >= 1)` (V58) — a
        // mock row starting at 0 would violate that constraint in production.
        revisionNo: 1, parentPricingRequestId: null,
        submittedAt: null, pickedUpAt: null, cancelledAt: null,
        createdAt: now, updatedAt: now,
        items, events: [],
        // Sales-level supporting attachments (V69, review remediation COMMIT 4) — distinct from
        // a factory quote's own `attachments` array above.
        attachments: [],
      };
      // Deliberately no notification, no ticket status change — a draft is the
      // rep's private scratchpad until submit() (mirrors createDraft's Javadoc).
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_CREATED', null, 'DRAFT');
      mockPricingRequests.push(pr);
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async update(id, payload) {
      // Mirrors PricingRequestService.updateDraft: owner sales, DRAFT only.
      // Review-remediation plan Fix 2 made this a FULL-REPLACEMENT PUT (in
      // sync with PricingRequestRepository.updateDraft dropping its COALESCE)
      // — every editable scalar field is validated and applied unconditionally
      // against the payload exactly as sent, never merged with the existing
      // row first. This mock used to distinguish `undefined` (field omitted,
      // "leave unchanged") from `null` (field explicitly cleared) for these
      // columns, which was actually MORE permissive than the real
      // UpdatePricingRequestRequest Java record ever was: Jackson deserializes
      // a missing JSON key the same way it deserializes an explicit `null` —
      // there is no wire-level way to distinguish the two on the real
      // backend — so "omitted" and "null" must collapse to the same outcome
      // here too. recipientType is additionally required (recipient_type is
      // NOT NULL in the real schema), validated unconditionally like create().
      const user = hasRole('sales');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (ticket?.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (pr.status !== 'DRAFT') fail(`ต้องเป็นคำขอราคาที่อยู่ในสถานะ 'DRAFT' เท่านั้น (สถานะปัจจุบัน: '${pr.status}')`, 409);
      if (!payload.recipientType?.trim()) fail('recipientType ต้องไม่เว้นว่าง', 400);
      if (!PRICING_REQUEST_RECIPIENT_VALUES.includes(payload.recipientType)) {
        fail(`ไม่รองรับประเภทผู้รับ '${payload.recipientType}'`, 400);
      }
      if (payload.recipientContactId == null && !payload.recipientLabel?.trim()) {
        fail('ต้องระบุผู้รับคำขอราคา (recipientContactId หรือ recipientLabel)', 400);
      }
      if (payload.items != null) {
        requirePricingRequestItemFieldsValid(payload.items);
        for (const item of payload.items) {
          if (!PRICING_REQUEST_QUANTITY_TYPE_VALUES.includes(item.quantityType)) {
            fail(`ไม่รองรับประเภทจำนวน '${item.quantityType}'`, 400);
          }
          if (!UNIT_BASIS_VALUES.includes(item.requestedUnitBasis)) {
            fail(`ไม่รองรับ requestedUnitBasis '${item.requestedUnitBasis}'`, 400);
          }
        }
        const validSourceItemIds = new Set((ticket?.items ?? []).map((i) => i.id));
        for (const item of payload.items) {
          if (item.sourceTicketItemId != null && !validSourceItemIds.has(item.sourceTicketItemId)) {
            fail(`sourceTicketItemId ${item.sourceTicketItemId} ไม่ได้เป็นของดีล ${pr.ticketId}`, 400);
          }
        }
      }
      if (payload.targetCurrency != null && payload.targetCurrency.trim().length !== 3) {
        fail('targetCurrency ต้องเป็นรหัสสกุลเงิน 3 ตัวอักษร', 400);
      }

      pr.recipientType = payload.recipientType;
      pr.recipientContactId = payload.recipientContactId ?? null;
      pr.recipientLabel = payload.recipientLabel ?? null;
      pr.requiredDate = payload.requiredDate ?? null;
      pr.customerTargetPrice = payload.customerTargetPrice ?? null;
      pr.targetCurrency = normalizePricingRequestCurrency(payload.targetCurrency);
      pr.note = payload.note ?? null;
      if (payload.items != null) {
        pr.items = payload.items.map((item, i) => ({
          id: mockPricingRequestItemSeq++,
          pricingRequestId: pr.id,
          sourceTicketItemId: item.sourceTicketItemId ?? null,
          productId: item.productId ?? null,
          variantId: item.variantId ?? null,
          brand: item.brand ?? null,
          model: item.model ?? null,
          productDescription: item.productDescription ?? null,
          color: item.color ?? null,
          texture: item.texture ?? null,
          size: item.size ?? null,
          factory: item.factory ?? null,
          requestedQty: item.requestedQty,
          requestedQtySqm: item.requestedQtySqm ?? null,
          requestedUnit: item.requestedUnit,
          requestedUnitBasis: item.requestedUnitBasis,
          quantityType: item.quantityType,
          targetDeliveryDate: item.targetDeliveryDate ?? null,
          deliveryLocation: item.deliveryLocation ?? null,
          specialRequirement: item.specialRequirement ?? null,
          sortOrder: i,
          // See create()'s identical block above for why these start null.
          priceListVersionId: null,
          catalogPriceId: null,
          catalogBasePrice: null,
          catalogCurrency: null,
          catalogEffectiveDate: null,
          resolvedFactoryId: null,
          resolvedFactoryName: null,
          catalogProductCode: null,
          catalogBrand: null,
          catalogCollection: null,
          catalogModel: null,
        }));
      }
      pr.updatedAt = new Date().toISOString();
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_UPDATED', 'DRAFT', 'DRAFT');
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async generateFactoryEmailDrafts(id) {
      const user = hasRole('import');
      const pr = findPricingRequestRaw(id);
      // Mirrors FactoryQuoteService.DRAFT_STATUSES (V140 dropped COSTING_IN_PROGRESS from it).
      if (!['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE'].includes(pr.status)) {
        fail('คำขอราคาต้องอยู่ระหว่างการตรวจสอบของฝ่ายนำเข้าก่อนจึงจะสร้างร่างอีเมลราคาโรงงานได้', 409);
      }
      const byFactory = new Map();
      for (const item of pr.items) {
        if (!item.factory) fail(`รายการที่ ${item.id} ในคำขอราคายังไม่ได้ระบุโรงงาน`, 422);
        byFactory.set(item.factory, [...(byFactory.get(item.factory) ?? []), item]);
      }
      for (const [factoryName, items] of byFactory) {
        const exists = mockFactoryQuotes.some((q) => q.pricingRequestId === pr.id && q.factoryName === factoryName && q.current);
        if (exists) continue;
        const quoteId = mockFactoryQuoteSeq++;
        mockFactoryQuotes.push({
          id: quoteId,
          quoteCode: `FQ-2026-${String(quoteId).padStart(4, '0')}`,
          pricingRequestId: pr.id,
          factoryId: null,
          factoryName,
          status: 'DRAFT',
          emailTo: null,
          emailSubject: `Pricing request ${pr.requestCode}`,
          emailBody: items.map((item) => `${item.brand ?? ''} ${item.model ?? item.productDescription ?? ''}`).join('\n'),
          emailSentAt: null,
          sentBy: null,
          supplierQuoteRef: null,
          defaultCurrency: 'THB',
          paymentTerms: null,
          leadTimeText: null,
          note: null,
          negotiationNote: null,
          requestedAt: null,
          receivedAt: null,
          rootFactoryQuoteId: quoteId,
          parentFactoryQuoteId: null,
          revisionNo: 1,
          revisionReason: null,
          current: true,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          attachments: [],
          // Mirrors FactoryQuoteDto's dispatchStatus/dispatchAttemptCount/dispatchFailureMessage/
          // dispatchNextAttemptAt: the outbox worker's state for this quote's most recent send.
          // Null until sendFactoryQuote() enqueues one.
          dispatchStatus: null,
          dispatchAttemptCount: 0,
          dispatchFailureMessage: null,
          dispatchNextAttemptAt: null,
          items: items.map((item, i) => ({
            id: mockFactoryQuoteItemSeq++,
            factoryQuoteId: quoteId,
            pricingRequestItemId: item.id,
            quotedQuantity: item.requestedQty,
            // Bug fix (found while writing Stage K2 Phase 2's pcr-chain.spec.js):
            // this used to default to item.requestedUnit — a human display LABEL
            // ("ตร.ม.", "แผ่น") — not item.requestedUnitBasis, the canonical code
            // ('PER_SQM'/'PER_PIECE'/...) the response form's <select> options and
            // recalculateCosting's mockPricePerPiece/mockQuantityToPieces both key
            // on. Any import officer who filled in just the raw price without
            // re-touching the (seemingly-already-selected) unit-basis dropdown
            // would submit an unrecognised unitBasis, and recalculateCosting would
            // 422 with "Unsupported factory quote unit basis '<label>'" — silently
            // blocking Submit to CEO with no visible cause. quotedUnit and unitBasis
            // are always written together as the same code by the response form's
            // own onChange (see the unitBasis <select> below), so both are seeded
            // from requestedUnitBasis here too.
            quotedUnit: item.requestedUnitBasis,
            unitBasis: item.requestedUnitBasis,
            rawUnitPrice: null,
            currency: null,
            sortOrder: i,
          })),
        });
      }
      pushPricingRequestEvent(pr, user, 'FACTORY_EMAIL_READY', pr.status, pr.status);
      return delay({ items: mockFactoryQuotes.filter((q) => q.pricingRequestId === pr.id) });
    },

    async listFactoryQuotes(id) {
      hasRole('import', 'ceo');
      findPricingRequestRaw(id);
      return delay({ items: mockFactoryQuotes.filter((q) => q.pricingRequestId === Number(id)) });
    },

    async getFactoryQuote(id) {
      hasRole('import', 'ceo');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('ไม่พบใบเสนอราคาโรงงานนี้', 404);
      return delay({ factoryQuote: quote });
    },

    async updateFactoryQuote(id, payload) {
      hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('ไม่พบใบเสนอราคาโรงงานนี้', 404);
      if (quote.status !== 'DRAFT') fail('แก้ไขได้เฉพาะอีเมลราคาโรงงานที่ยังเป็นฉบับร่างเท่านั้น', 409);
      Object.assign(quote, {
        emailTo: payload.emailTo ?? quote.emailTo,
        emailSubject: payload.emailSubject ?? quote.emailSubject,
        emailBody: payload.emailBody ?? quote.emailBody,
        note: payload.note ?? quote.note,
        updatedAt: new Date().toISOString(),
      });
      return delay({ factoryQuote: quote });
    },

    // Mirrors FactoryQuoteService.send(): enqueue-only. The actual "send + finalize" (quote ->
    // REQUESTED, pricing request status transition, FACTORY_EMAIL_SENT event) happens out-of-band
    // a moment later via scheduleMockFactoryQuoteDispatch(), the mock stand-in for
    // FactoryQuoteEmailDispatchWorker, so the frontend can exercise the same
    // pending/sending/sent-with-a-delay UX it will see against the real backend.
    async sendFactoryQuote(id, payload = {}) {
      const user = hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('ไม่พบใบเสนอราคาโรงงานนี้', 404);
      if (!payload.clientRequestId) fail('clientRequestId ต้องเป็น UUID', 400);
      if (quote.status === 'REQUESTED') return delay({ factoryQuote: quote });
      if (quote.status !== 'DRAFT') fail('ส่งได้เฉพาะอีเมลราคาโรงงานที่ยังเป็นฉบับร่างเท่านั้น', 409);
      const existingForClient = mockFactoryQuoteDispatchClientRequests.find(
        (d) => d.clientRequestId === payload.clientRequestId
      );
      if (existingForClient) {
        if (existingForClient.quoteId !== quote.id) {
          fail('clientRequestId นี้ถูกใช้ไปแล้วกับใบเสนอราคาโรงงานอื่น', 409);
        }
        return delay({ factoryQuote: quote });
      }
      if (quote.dispatchStatus && ['PENDING', 'SENDING', 'SENT'].includes(quote.dispatchStatus)) {
        return delay({ factoryQuote: quote });
      }
      quote.emailTo = payload.emailTo ?? quote.emailTo;
      quote.emailSubject = payload.emailSubject ?? quote.emailSubject;
      quote.emailBody = payload.emailBody ?? quote.emailBody;
      quote.updatedAt = new Date().toISOString();
      mockFactoryQuoteDispatchClientRequests.push({ clientRequestId: payload.clientRequestId, quoteId: quote.id });
      quote.dispatchStatus = 'PENDING';
      quote.dispatchAttemptCount = 0;
      quote.dispatchFailureMessage = null;
      quote.dispatchNextAttemptAt = null;
      scheduleMockFactoryQuoteDispatch(quote, user);
      return delay({ factoryQuote: quote });
    },

    async receiveFactoryQuote(id, payload) {
      const user = hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('ไม่พบใบเสนอราคาโรงงานนี้', 404);
      if (!payload.clientRequestId) fail('clientRequestId ต้องเป็น UUID', 400);
      // Idempotency replay: a lost-response retry (same actor + clientRequestId)
      // must not be treated as a new commercial revision. Look this up BEFORE any
      // mutation and short-circuit with the quote the original call landed on.
      const chainId = (q) => q.rootFactoryQuoteId ?? q.id;
      const existingReceipt = mockFactoryQuoteResponseReceipts.find(
        (r) => r.createdBy === user.id && r.clientRequestId === payload.clientRequestId
      );
      if (existingReceipt) {
        const receiptQuote = mockFactoryQuotes.find((q) => q.id === existingReceipt.factoryQuoteId);
        if (!receiptQuote) fail('ไม่พบใบเสนอราคาโรงงานนี้', 404);
        // Compare the QUOTE CHAIN, not the pricing request: a pricing request has one quote
        // per factory, so reusing a clientRequestId against a DIFFERENT factory's quote in the
        // same pricing request must 409, not silently return the wrong factory's quote.
        if (chainId(receiptQuote) !== chainId(quote)) {
          fail('clientRequestId นี้ถูกใช้ไปแล้วกับใบเสนอราคาโรงงานอื่น', 409);
        }
        return delay({ factoryQuote: receiptQuote });
      }
      if (!quote.current) fail('รับคำตอบได้เฉพาะ revision ล่าสุดของใบเสนอราคาโรงงานเท่านั้น', 409);
      const pr = findPricingRequestRaw(quote.pricingRequestId);
      mockRequireNotCeoReviewing(pr);
      const applyResponse = (target) => {
        target.status = 'RESPONSE_RECEIVED';
        target.supplierQuoteRef = payload.supplierQuoteRef ?? null;
        target.defaultCurrency = payload.defaultCurrency ?? 'THB';
        target.paymentTerms = payload.paymentTerms ?? null;
        target.leadTimeText = payload.leadTimeText ?? null;
        target.revisionReason = payload.revisionReason ?? null;
        target.negotiationNote = payload.negotiationNote ?? null;
        target.receivedAt = new Date().toISOString();
        target.updatedAt = target.receivedAt;
        target.items = payload.items.map((item, i) => ({
          id: mockFactoryQuoteItemSeq++,
          factoryQuoteId: target.id,
          pricingRequestItemId: item.pricingRequestItemId,
          quotedQuantity: item.quotedQuantity,
          quotedUnit: item.quotedUnit,
          unitBasis: item.unitBasis,
          rawUnitPrice: item.rawUnitPrice,
          currency: item.currency,
          minimumOrderQuantity: item.minimumOrderQuantity ?? null,
          sqmPerUnit: item.sqmPerUnit ?? null,
          piecesPerBox: item.piecesPerBox ?? null,
          // Mirrors FactoryQuoteItemDto.linearMPerUnit (V68, financial-integrity review
          // Finding B) — the PER_LINEAR_M conversion factor, same role as sqmPerUnit/
          // piecesPerBox above for PER_SQM/PER_BOX.
          linearMPerUnit: item.linearMPerUnit ?? null,
          sortOrder: i,
        }));
      };
      if (['DRAFT', 'REQUESTED'].includes(quote.status)) {
        applyResponse(quote);
        // First/partial factory response only confirms the request is awaiting
        // (or still awaiting) factory replies. COSTING_IN_PROGRESS is entered only
        // by createCosting(), once every request item's factory has a current
        // READY_FOR_COSTING quote — see PricingCostingService.resolveSources.
        if (pr.status === 'IMPORT_REVIEWING') pr.status = 'AWAITING_FACTORY_RESPONSE';
        pushPricingRequestEvent(pr, user, 'FACTORY_RESPONSE_RECEIVED', pr.status, pr.status);
        mockFactoryQuoteResponseReceipts.push({ factoryQuoteId: quote.id, createdBy: user.id, clientRequestId: payload.clientRequestId });
        return delay({ factoryQuote: quote });
      }
      if (!['RESPONSE_RECEIVED', 'NEGOTIATING', 'READY_FOR_COSTING'].includes(quote.status)) {
        fail(`ใบเสนอราคาโรงงานนี้ไม่สามารถรับคำตอบได้ในสถานะ ${quote.status}`, 409);
      }
      quote.status = 'SUPERSEDED';
      quote.current = false;
      const revision = { ...quote, id: mockFactoryQuoteSeq++, quoteCode: `FQ-2026-${String(mockFactoryQuoteSeq).padStart(4, '0')}`, status: 'RESPONSE_RECEIVED', parentFactoryQuoteId: quote.id, revisionNo: quote.revisionNo + 1, current: true, createdAt: new Date().toISOString() };
      applyResponse(revision);
      mockFactoryQuotes.push(revision);
      for (const costing of mockPricingCostings.filter((c) => c.pricingRequestId === pr.id && ['DRAFT', 'CALCULATED'].includes(c.status))) {
        costing.stale = true;
        costing.staleReason = 'Factory quote revision changed';
      }
      pushPricingRequestEvent(pr, user, 'FACTORY_RESPONSE_REVISED', pr.status, pr.status);
      mockFactoryQuoteResponseReceipts.push({ factoryQuoteId: revision.id, createdBy: user.id, clientRequestId: payload.clientRequestId });
      return delay({ factoryQuote: revision });
    },

    async startFactoryNegotiation(id, payload) {
      const user = hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('ไม่พบใบเสนอราคาโรงงานนี้', 404);
      mockRequireNotCeoReviewing(findPricingRequestRaw(quote.pricingRequestId));
      if (quote.status !== 'RESPONSE_RECEIVED' || !quote.current) fail('เข้าสู่ขั้นตอนต่อรองได้เฉพาะคำตอบล่าสุดที่ได้รับเท่านั้น', 409);
      quote.status = 'NEGOTIATING';
      quote.negotiationNote = payload.note;
      quote.updatedAt = new Date().toISOString();
      pushPricingRequestEvent(findPricingRequestRaw(quote.pricingRequestId), user, 'FACTORY_NEGOTIATION_STARTED', null, null, payload.note);
      return delay({ factoryQuote: quote });
    },

    async markFactoryQuoteReady(id) {
      const user = hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('ไม่พบใบเสนอราคาโรงงานนี้', 404);
      mockRequireNotCeoReviewing(findPricingRequestRaw(quote.pricingRequestId));
      if (!['RESPONSE_RECEIVED', 'NEGOTIATING'].includes(quote.status) || !quote.current) fail('คำตอบล่าสุดต้องมีราคาต้นทางก่อนจึงจะทำเครื่องหมายว่าพร้อมได้', 409);
      quote.status = 'READY_FOR_COSTING';
      quote.updatedAt = new Date().toISOString();
      pushPricingRequestEvent(findPricingRequestRaw(quote.pricingRequestId), user, 'FACTORY_RESPONSE_READY_FOR_COSTING', null, null);
      return delay({ factoryQuote: quote });
    },

    async markFactoryQuoteNotAvailable(id, payload) {
      const user = hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('ไม่พบใบเสนอราคาโรงงานนี้', 404);
      mockRequireNotCeoReviewing(findPricingRequestRaw(quote.pricingRequestId));
      quote.status = 'NOT_AVAILABLE';
      quote.note = payload.reason;
      pushPricingRequestEvent(findPricingRequestRaw(quote.pricingRequestId), user, 'FACTORY_NOT_AVAILABLE', null, null, payload.reason);
      return delay({ factoryQuote: quote });
    },

    async uploadFactoryQuoteAttachment(id, file) {
      hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('ไม่พบใบเสนอราคาโรงงานนี้', 404);
      mockRequireNotCeoReviewing(findPricingRequestRaw(quote.pricingRequestId));
      const attachment = {
        id: mockFactoryQuoteAttachmentSeq++,
        factoryQuoteId: quote.id,
        fileName: file?.name ?? 'attachment',
        mimeType: file?.type ?? null,
        fileSize: file?.size ?? null,
        uploadedBy: sessionUser.id,
        uploadedAt: new Date().toISOString(),
      };
      quote.attachments = [attachment, ...(quote.attachments ?? [])];
      return delay({ attachment });
    },

    factoryQuoteAttachmentUrl(id) {
      return `#mock-factory-quote-attachment-${id}`;
    },

    async deleteFactoryQuoteAttachment(id) {
      hasRole('import');
      for (const quote of mockFactoryQuotes) {
        quote.attachments = (quote.attachments ?? []).filter((attachment) => attachment.id !== Number(id));
      }
      return delay({ ok: true });
    },

    async createCosting(id, payload = {}) {
      const user = hasRole('import');
      const pr = findPricingRequestRaw(id);
      // Mirrors PricingCostingService.COSTING_CREATE_STATUSES (Step 3, design corrections 3+4):
      // READY_FOR_CEO_REVIEW/CEO_REVIEWING are deliberately excluded — a submitted costing is
      // frozen until the CEO explicitly returns the request (-> COSTING_REVISION_REQUIRED).
      // V140 also dropped COSTING_IN_PROGRESS from this set — costing no longer has a status of
      // its own, so the status Import creates a costing FROM is now also the status it stays in.
      if (!['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_REVISION_REQUIRED'].includes(pr.status)) {
        fail('คำขอราคานี้ยังไม่พร้อมสำหรับการคำนวณต้นทุน', 409);
      }
      const readyFactories = new Set(mockFactoryQuotes.filter((q) => q.pricingRequestId === pr.id && q.current && q.status === 'READY_FOR_COSTING').map((q) => q.factoryName));
      for (const item of pr.items) if (!readyFactories.has(item.factory)) fail(`ใบเสนอราคาของโรงงาน ${item.factory} ยังไม่พร้อมสำหรับการคำนวณต้นทุน`, 422);
      const existing = mockPricingCostings.find((c) => c.pricingRequestId === pr.id && ['DRAFT', 'CALCULATED'].includes(c.status));
      if (existing) return delay({ costing: existing });
      const costing = { id: mockPricingCostingSeq++, costingCode: `PCO-2026-${String(mockPricingCostingSeq).padStart(4, '0')}`, pricingRequestId: pr.id, versionNo: mockPricingCostingSeq, status: 'DRAFT', stale: false, staleReason: null, note: payload.note ?? null, createdBy: user.id, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), calculatedAt: null, submittedBy: null, submittedAt: null, totalLandedCostThb: null, items: [] };
      mockPricingCostings.push(costing);
      // Mirrors PricingCostingService.createDraft after V140: starting a costing settles the
      // request into AWAITING_FACTORY_RESPONSE when it is not already there (Import creating one
      // straight from IMPORT_REVIEWING, or reopening one the CEO returned via
      // COSTING_REVISION_REQUIRED). It never moves a request that is already there.
      const costingFromStatus = pr.status;
      if (pr.status !== 'AWAITING_FACTORY_RESPONSE') pr.status = 'AWAITING_FACTORY_RESPONSE';
      pushPricingRequestEvent(pr, user, 'PRICING_COSTING_STARTED', costingFromStatus, 'AWAITING_FACTORY_RESPONSE');
      return delay({ costing });
    },

    async listCostings(id) {
      hasRole('import', 'ceo');
      return delay({ items: mockPricingCostings.filter((c) => c.pricingRequestId === Number(id)) });
    },

    async getCosting(id) {
      hasRole('import', 'ceo');
      const costing = mockPricingCostings.find((c) => c.id === Number(id));
      if (!costing) fail('ไม่พบการคำนวณต้นทุนนี้', 404);
      return delay({ costing });
    },

    async recalculateCosting(id, payload = {}) {
      const user = hasRole('import');
      const costing = mockPricingCostings.find((c) => c.id === Number(id));
      if (!costing) fail('ไม่พบการคำนวณต้นทุนนี้', 404);
      if (costing.status === 'SUBMITTED') fail('การคำนวณต้นทุนที่ส่งไปแล้วไม่สามารถแก้ไขได้', 409);
      const pr = findPricingRequestRaw(costing.pricingRequestId);
      costing.items = pr.items.map((item) => {
        const quote = mockFactoryQuotes.find((q) => q.pricingRequestId === pr.id && q.factoryName === item.factory && q.current && q.status === 'READY_FOR_COSTING');
        const quoteItem = quote?.items.find((qi) => qi.pricingRequestItemId === item.id);
        if (!quote || !quoteItem) fail(`ใบเสนอราคาของโรงงาน ${item.factory} ยังไม่พร้อมสำหรับการคำนวณต้นทุน`, 422);
        // Finding B (financial-integrity review, commit 3): normalize BOTH the quoted price
        // and the requested quantity onto a common basis (physical pieces) before multiplying
        // — see mockPricePerPiece/mockQuantityToPieces above, mirroring
        // PricingCostingService.calculate(). The pre-fix mock multiplied raw price by the raw
        // (un-normalized) requestedQty, the same bug the real backend had.
        const factors = { sqmPerUnit: quoteItem.sqmPerUnit, piecesPerBox: quoteItem.piecesPerBox, linearMPerUnit: quoteItem.linearMPerUnit };
        const raw = Number(quoteItem.rawUnitPrice ?? 0);
        const pricePerPiece = mockPricePerPiece(raw, quoteItem.unitBasis, factors, item.id);
        const normalizedQuantityPieces = mockQuantityToPieces(item.requestedQty, item.requestedUnitBasis, factors, item.id);
        return {
          pricingRequestItemId: item.id,
          factoryQuoteId: quote.id,
          factoryQuoteItemId: quoteItem.id,
          factoryQuoteRevisionNo: quote.revisionNo,
          factoryName: quote.factoryName,
          rawUnitPrice: raw,
          rawCurrency: quoteItem.currency,
          rawUnit: quoteItem.quotedUnit,
          unitBasis: quoteItem.unitBasis,
          requestedQuantity: item.requestedQty,
          requestedUnit: item.requestedUnit,
          requestedUnitBasis: item.requestedUnitBasis,
          normalizedQuantityPieces,
          sqmPerUnit: quoteItem.sqmPerUnit ?? null,
          piecesPerBox: quoteItem.piecesPerBox ?? null,
          linearMPerUnit: quoteItem.linearMPerUnit ?? null,
          goodsCostThb: pricePerPiece,
          landedCostPerUnitThb: pricePerPiece,
          totalLandedCostThb: pricePerPiece * normalizedQuantityPieces,
        };
      });
      costing.totalLandedCostThb = costing.items.reduce((sum, item) => sum + item.totalLandedCostThb, 0);
      costing.status = 'CALCULATED';
      costing.stale = false;
      costing.staleReason = null;
      costing.note = payload.note ?? costing.note;
      costing.calculatedAt = new Date().toISOString();
      pushPricingRequestEvent(pr, user, 'PRICING_COSTING_CALCULATED', null, null);
      return delay({ costing });
    },

    async submitCosting(id, payload = {}) {
      const user = hasRole('import');
      const costing = mockPricingCostings.find((c) => c.id === Number(id));
      if (!costing) fail('ไม่พบการคำนวณต้นทุนนี้', 404);
      if (costing.stale) fail('ข้อมูลต้นทุนล้าสมัยแล้ว ต้องคำนวณใหม่ก่อนส่ง', 409);
      if (costing.status !== 'CALCULATED') fail('ส่งได้เฉพาะการคำนวณต้นทุนที่คำนวณเสร็จแล้วเท่านั้น', 409);
      const pr = findPricingRequestRaw(costing.pricingRequestId);
      costing.status = 'SUBMITTED';
      costing.submittedBy = user.id;
      costing.submittedAt = new Date().toISOString();
      costing.note = payload.note ?? costing.note;
      pr.status = 'READY_FOR_CEO_REVIEW';
      // Mirrors PricingCostingService.submit after V140: the request leaves
      // AWAITING_FACTORY_RESPONSE (not the retired COSTING_IN_PROGRESS) for READY_FOR_CEO_REVIEW.
      pushPricingRequestEvent(pr, user, 'PRICING_COSTING_SUBMITTED', 'AWAITING_FACTORY_RESPONSE', 'READY_FOR_CEO_REVIEW');
      return delay({ costing });
    },

    // ── Step 3: CEO Selling Price Decision — mirrors PricingDecisionController +
    // PricingDecisionService (pricingdecision/). Authorization is NOT authoritative (CLAUDE.md);
    // verify role/scope behavior against the real Java service.
    //
    // Simplifications vs the real backend (documented, not silent): FX is always THB (rate 1) —
    // the same simplification the costing mock above already makes (no fxRate/fxSource fields on
    // a mock costing item, no BOT validation); selling price is computed in THB only.

    async startPricingDecision(id, payload = {}) {
      const user = hasRole('ceo');
      const pr = findPricingRequestRaw(id);
      if (pr.status !== 'READY_FOR_CEO_REVIEW') fail('คำขอราคานี้ยังไม่พร้อมส่งให้ CEO พิจารณา', 409);
      const openDraft = mockPricingDecisions.find((d) => d.pricingRequestId === pr.id && d.status === 'DRAFT');
      if (openDraft) return delay({ decision: openDraft });
      const submittedCostings = mockPricingCostings.filter((c) => c.pricingRequestId === pr.id && c.status === 'SUBMITTED');
      const costing = submittedCostings[submittedCostings.length - 1];
      if (!costing) fail('คำขอราคานี้ยังไม่มีการคำนวณต้นทุนที่ส่งเข้ามา', 409);
      const currency = (payload.currency || pr.targetCurrency || 'THB').toUpperCase();
      const defaultMarginPct = payload.defaultMarginPct ?? null;
      const decisionVersionNo = mockPricingDecisions.filter((d) => d.pricingRequestId === pr.id).length + 1;
      const decision = {
        id: mockPricingDecisionSeq++,
        decisionCode: `PCD-2026-${String(mockPricingDecisionSeq).padStart(4, '0')}`,
        pricingRequestId: pr.id,
        pricingCostingId: costing.id,
        decisionVersionNo,
        status: 'DRAFT',
        defaultMarginPct,
        currency,
        fxRateUsed: 1,
        fxSource: 'THB',
        fxEffectiveDate: new Date().toISOString().slice(0, 10),
        ceoNote: payload.ceoNote ?? null,
        returnReason: null,
        approveClientRequestId: null,
        createdBy: user.id,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        approvedBy: null,
        approvedAt: null,
        returnedAt: null,
        items: costing.items.map((costingItem) => {
          const prItem = pr.items.find((i) => i.id === costingItem.pricingRequestItemId);
          const frozenPerPiece = Number(costingItem.landedCostPerUnitThb ?? 0);
          const requestedQuantity = Number(costingItem.requestedQuantity ?? prItem?.requestedQty ?? 0);
          const frozenPerRequestedUnit = requestedQuantity > 0
            ? Number(costingItem.totalLandedCostThb ?? 0) / requestedQuantity : frozenPerPiece;
          const proposedSellingPricePerRequestedUnit = defaultMarginPct != null
            ? frozenPerRequestedUnit * (1 + Number(defaultMarginPct)) : null;
          return {
            id: mockPricingDecisionItemSeq++,
            // Bug fix (found while writing Stage K2 Phase 2's pcr-chain.spec.js):
            // this used to read `decision?.id` here — but `decision` is the const
            // THIS object literal is itself initializing (`items: costing.items.map(...)`
            // runs synchronously as part of constructing `decision`), so referencing
            // `decision` from inside its own initializer is a temporal-dead-zone
            // violation. It threw "Cannot access 'decision' before initialization"
            // on every single call, 100% reproducible, unconditionally breaking
            // startPricingDecision (CEO "เริ่มพิจารณาราคาขาย") for every PCR. Left
            // null here; the forEach right below (already written for exactly this
            // reason — see its own comment) fixes it up once decision.id is known.
            pricingDecisionId: null,
            pricingRequestItemId: costingItem.pricingRequestItemId,
            pricingCostingItemId: costingItem.pricingRequestItemId,
            brand: prItem?.brand ?? null,
            model: prItem?.model ?? null,
            productDescription: prItem?.productDescription ?? null,
            factoryName: costingItem.factoryName ?? null,
            requestedUnitBasis: costingItem.requestedUnitBasis,
            requestedQuantity,
            normalizedQuantityPieces: costingItem.normalizedQuantityPieces,
            frozenLandedCostPerPieceThb: frozenPerPiece,
            frozenLandedCostPerRequestedUnitThb: frozenPerRequestedUnit,
            currency,
            proposedMarginPct: defaultMarginPct,
            approvedMarginPct: null,
            proposedSellingPricePerRequestedUnit,
            approvedSellingPricePerRequestedUnit: null,
            discountCeilingPct: null,
            minimumSellingPricePerRequestedUnit: null,
            decisionNote: null,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
          };
        }),
      };
      // Fix up the self-reference now that decision.id is known (items were built before push).
      decision.items.forEach((item) => { item.pricingDecisionId = decision.id; });
      mockPricingDecisions.push(decision);
      pr.status = 'CEO_REVIEWING';
      pushPricingRequestEvent(pr, user, 'PRICING_DECISION_STARTED', 'READY_FOR_CEO_REVIEW', 'CEO_REVIEWING');
      return delay({ decision });
    },

    async listPricingDecisions(id) {
      hasRole('import', 'ceo');
      return delay({ items: mockPricingDecisions.filter((d) => d.pricingRequestId === Number(id)) });
    },

    async getPricingDecisionSalesView(id) {
      const user = hasRole('sales', 'sales_manager', 'ceo', 'import');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (user.role === 'sales' && ticket?.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      const decision = mockPricingDecisions.find((d) => d.pricingRequestId === pr.id && d.status === 'APPROVED');
      if (!decision) fail('ยังไม่มีมติราคาที่ได้รับอนุมัติ', 404);
      // Design correction 2 ("never leak cost to Sales"): a fresh object literal per item with
      // ONLY these fields — never a spread of the raw (cost/margin-bearing) decision item.
      return delay({
        decision: {
          pricingRequestId: pr.id,
          pricingDecisionId: decision.id,
          currency: decision.currency,
          approvedAt: decision.approvedAt,
          items: decision.items.map((item) => ({
            pricingRequestItemId: item.pricingRequestItemId,
            brand: item.brand,
            model: item.model,
            productDescription: item.productDescription,
            requestedUnitBasis: item.requestedUnitBasis,
            requestedQuantity: item.requestedQuantity,
            approvedSellingPricePerRequestedUnit: item.approvedSellingPricePerRequestedUnit,
            discountCeilingPct: item.discountCeilingPct,
            minimumSellingPricePerRequestedUnit: item.minimumSellingPricePerRequestedUnit,
          })),
        },
      });
    },

    async getPricingDecision(id) {
      hasRole('import', 'ceo');
      const decision = mockPricingDecisions.find((d) => d.id === Number(id));
      if (!decision) fail('ไม่พบมติราคานี้', 404);
      return delay({ decision });
    },

    async updatePricingDecision(id, payload) {
      hasRole('ceo');
      const decision = mockPricingDecisions.find((d) => d.id === Number(id));
      if (!decision) fail('ไม่พบมติราคานี้', 404);
      if (decision.status !== 'DRAFT') fail('มติราคานี้ไม่ได้อยู่ในสถานะที่แก้ไขได้', 409);
      if (payload.ceoNote != null) decision.ceoNote = payload.ceoNote;
      for (const itemPayload of payload.items ?? []) {
        const item = decision.items.find((i) => i.id === Number(itemPayload.pricingDecisionItemId));
        if (!item) fail(`รายการที่ ${itemPayload.pricingDecisionItemId} ไม่ได้เป็นของมติราคานี้`, 400);
        if (itemPayload.marginPct != null) {
          item.proposedMarginPct = itemPayload.marginPct;
          item.proposedSellingPricePerRequestedUnit =
            item.frozenLandedCostPerRequestedUnitThb * (1 + Number(itemPayload.marginPct));
        }
        if (itemPayload.discountCeilingPct != null) item.discountCeilingPct = itemPayload.discountCeilingPct;
        if (itemPayload.minimumSellingPrice != null) item.minimumSellingPricePerRequestedUnit = itemPayload.minimumSellingPrice;
        if (itemPayload.decisionNote != null) item.decisionNote = itemPayload.decisionNote;
        item.updatedAt = new Date().toISOString();
      }
      decision.updatedAt = new Date().toISOString();
      return delay({ decision });
    },

    async recalculatePricingDecision(id, payload = {}) {
      hasRole('ceo');
      const decision = mockPricingDecisions.find((d) => d.id === Number(id));
      if (!decision) fail('ไม่พบมติราคานี้', 404);
      if (decision.status !== 'DRAFT') fail('มติราคานี้ไม่ได้อยู่ในสถานะที่แก้ไขได้', 409);
      if (payload.defaultMarginPct != null) decision.defaultMarginPct = payload.defaultMarginPct;
      for (const item of decision.items) {
        const margin = payload.defaultMarginPct != null ? payload.defaultMarginPct : item.proposedMarginPct;
        if (margin == null) continue;
        item.proposedMarginPct = margin;
        item.proposedSellingPricePerRequestedUnit = item.frozenLandedCostPerRequestedUnitThb * (1 + Number(margin));
        item.updatedAt = new Date().toISOString();
      }
      decision.updatedAt = new Date().toISOString();
      return delay({ decision });
    },

    async approvePricingDecision(id, payload = {}) {
      const user = hasRole('ceo');
      const decision = mockPricingDecisions.find((d) => d.id === Number(id));
      if (!decision) fail('ไม่พบมติราคานี้', 404);
      if (payload.clientRequestId && decision.approveClientRequestId === payload.clientRequestId
          && decision.status === 'APPROVED') {
        return delay({ decision });
      }
      if (decision.status !== 'DRAFT') fail('มติราคานี้ไม่ได้อยู่ในสถานะที่รออนุมัติ', 409);
      const pr = findPricingRequestRaw(decision.pricingRequestId);
      if (pr.status !== 'CEO_REVIEWING') fail('คำขอราคานี้ไม่ได้อยู่ระหว่างการพิจารณาของ CEO', 409);
      const missingMargin = decision.items.filter((i) => i.proposedMarginPct == null).map((i) => i.id);
      const missingMinimum = decision.items.filter((i) => i.minimumSellingPricePerRequestedUnit == null).map((i) => i.id);
      if (missingMargin.length || missingMinimum.length) {
        fail(`ทุกรายการต้องระบุ margin และราคาขายขั้นต่ำก่อนอนุมัติ — รายการที่ยังไม่มี margin: [${missingMargin}], รายการที่ยังไม่มีราคาขายขั้นต่ำ: [${missingMinimum}]`, 422);
      }
      // Never trust a stored/client-supplied selling price — recompute from frozen cost + margin.
      for (const item of decision.items) {
        item.approvedMarginPct = item.proposedMarginPct;
        item.approvedSellingPricePerRequestedUnit =
          item.frozenLandedCostPerRequestedUnitThb * (1 + Number(item.proposedMarginPct));
        item.updatedAt = new Date().toISOString();
      }
      decision.status = 'APPROVED';
      decision.approvedBy = user.id;
      decision.approvedAt = new Date().toISOString();
      decision.approveClientRequestId = payload.clientRequestId ?? null;
      if (payload.ceoNote != null) decision.ceoNote = payload.ceoNote;
      decision.updatedAt = new Date().toISOString();
      pr.status = 'APPROVED_FOR_QUOTATION';
      pushPricingRequestEvent(pr, user, 'PRICING_DECISION_APPROVED', 'CEO_REVIEWING', 'APPROVED_FOR_QUOTATION');
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      addNotification(pr.requestedById, pr.ticketId, ticket?.code, 'PRICING_DECISION_APPROVED',
        `คำขอราคา ${pr.requestCode} ได้รับอนุมัติราคาขายแล้ว`);
      return delay({ decision });
    },

    async returnPricingDecisionToImport(id, payload) {
      const user = hasRole('ceo');
      if (!payload?.returnReason?.trim()) fail('ต้องระบุเหตุผลการตีกลับ', 400);
      const decision = mockPricingDecisions.find((d) => d.id === Number(id));
      if (!decision) fail('ไม่พบมติราคานี้', 404);
      if (decision.status !== 'DRAFT') fail('มติราคานี้ไม่ได้อยู่ในสถานะที่แก้ไขได้', 409);
      const pr = findPricingRequestRaw(decision.pricingRequestId);
      if (pr.status !== 'CEO_REVIEWING') fail('คำขอราคานี้ไม่ได้อยู่ระหว่างการพิจารณาของ CEO', 409);
      decision.status = 'RETURNED';
      decision.returnReason = payload.returnReason;
      decision.returnedAt = new Date().toISOString();
      decision.updatedAt = new Date().toISOString();
      pr.status = 'COSTING_REVISION_REQUIRED';
      pushPricingRequestEvent(pr, user, 'PRICING_DECISION_RETURNED', 'CEO_REVIEWING', 'COSTING_REVISION_REQUIRED', payload.returnReason);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (pr.assignedImportId != null) {
        addNotification(pr.assignedImportId, pr.ticketId, ticket?.code, 'PRICING_DECISION_RETURNED',
          `คำขอราคา ${pr.requestCode} ถูก CEO ตีกลับให้แก้ไขต้นทุน`);
      }
      return delay({ decision });
    },

    // ── Step 4: Customer Quotation Generation and Issuance — mirrors
    // CustomerQuotationController + CustomerQuotationService (customerquotation/).
    // Authorization is NOT authoritative (CLAUDE.md); verify against the real Java service.
    // Simplification vs the real backend (documented, not silent): FX/currency mirrors Step 3's
    // own mock simplification — THB only, rate 1 (no fxRate/fxSource fields anywhere on this
    // aggregate either, same as the pricing-decision mock above).

    async createCustomerQuotation(id, payload = {}) {
      const user = hasRole('sales');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (ticket?.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (payload.clientRequestId) {
        const replay = mockCustomerQuotations.find(
          (q) => q.issuedById === user.id && q.clientRequestId === payload.clientRequestId);
        if (replay) return delay({ quotation: replay });
      }
      if (pr.status !== 'APPROVED_FOR_QUOTATION') {
        fail(`คำขอราคาต้องอยู่ในสถานะ 'อนุมัติราคาขายแล้ว' ก่อนจึงจะออกใบเสนอราคาลูกค้าได้ (ปัจจุบัน: ${pr.status})`, 409);
      }
      const decision = mockPricingDecisions.find((d) => d.pricingRequestId === pr.id && d.status === 'APPROVED');
      if (!decision) fail('ยังไม่มีราคาขายที่ CEO อนุมัติสำหรับคำขอราคานี้', 409);
      const quotation = buildMockCustomerQuotationDraft(pr, decision, ticket, user, payload, null, 1, {});
      mockCustomerQuotations.push(quotation);
      pushPricingRequestEvent(pr, user, 'CUSTOMER_QUOTATION_CREATED', pr.status, pr.status, 'สร้างร่างใบเสนอราคาลูกค้า');
      return delay({ quotation });
    },

    async listCustomerQuotations(id) {
      const user = hasRole('sales', 'sales_manager', 'ceo', 'import');
      const pr = findPricingRequestRaw(id);
      if (user.role === 'sales') {
        const ticket = db.tickets.find((t) => t.id === pr.ticketId);
        if (ticket?.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      }
      return delay({
        items: mockCustomerQuotations
          .filter((q) => q.pricingRequestId === pr.id)
          .sort((a, b) => a.quotationRevisionNo - b.quotationRevisionNo),
      });
    },

    async getCustomerQuotation(id) {
      const quotation = mockCustomerQuotationViewAccess(id);
      return delay({ quotation });
    },

    async updateCustomerQuotation(id, payload = {}) {
      const user = hasRole('sales');
      const quotation = mockCustomerQuotationEditAccess(id, user);
      if (!['DRAFT', 'READY_TO_ISSUE'].includes(quotation.docStatus)) {
        fail(`แก้ไขได้เฉพาะใบเสนอราคาที่ยังเป็นร่างเท่านั้น (ปัจจุบัน: ${quotation.docStatus})`, 409);
      }
      if (payload.paymentTerms != null) quotation.paymentTerms = payload.paymentTerms;
      if (payload.leadTime != null) quotation.leadTime = payload.leadTime;
      if (payload.deliveryTerms != null) quotation.deliveryTerms = payload.deliveryTerms;
      if (payload.validityDate != null) quotation.validityDate = payload.validityDate;
      if (payload.customerNotes != null) quotation.customerNotes = payload.customerNotes;
      for (const itemPayload of payload.items ?? []) {
        const item = quotation.items.find((i) => i.id === Number(itemPayload.quotationItemId));
        if (!item) fail(`รายการ ${itemPayload.quotationItemId} ไม่ได้อยู่ในใบเสนอราคานี้`, 400);
        const discount = itemPayload.salesDiscount != null ? Number(itemPayload.salesDiscount) : (item.salesDiscount ?? 0);
        if (discount < 0) fail('ส่วนลดต้องไม่ติดลบ', 400);
        const finalUnitPrice = item.approvedUnitPrice - discount;
        if (finalUnitPrice < 0) fail('ส่วนลดต้องไม่เกินราคาที่อนุมัติ', 400);
        // Discount Policy B (owner's decision): never below the CEO-approved minimum — hard
        // 422, no auto-escalation.
        if (item.minimumSellingPricePerRequestedUnit != null && finalUnitPrice < item.minimumSellingPricePerRequestedUnit) {
          fail(`ราคาหลังหักส่วนลดของรายการ ${itemPayload.quotationItemId} ต่ำกว่าราคาขั้นต่ำที่ CEO อนุมัติ`, 422);
        }
        if (itemPayload.description != null) item.description = itemPayload.description;
        if (itemPayload.itemNotes != null) item.itemNotes = itemPayload.itemNotes;
        item.salesDiscount = discount;
        item.finalUnitPrice = finalUnitPrice;
        item.lineSubtotal = round2(finalUnitPrice * item.requestedQuantity);
        item.vat = round2(item.lineSubtotal * 0.07);
        item.lineTotal = round2(item.lineSubtotal + item.vat);
      }
      recalcMockCustomerQuotationTotals(quotation);
      pushPricingRequestEvent(findPricingRequestRaw(quotation.pricingRequestId), user, 'CUSTOMER_QUOTATION_UPDATED',
        null, null, `แก้ไขใบเสนอราคาลูกค้า ${quotation.number}`);
      return delay({ quotation });
    },

    async previewCustomerQuotation(id) {
      // Rule 12: pure read, zero writes — same object the last create/update already computed.
      const quotation = mockCustomerQuotationViewAccess(id);
      return delay({ quotation });
    },

    async issueCustomerQuotation(id, payload = {}) {
      const user = hasRole('sales');
      const preview = mockCustomerQuotationEditAccess(id, user);
      if (payload.clientRequestId) {
        const replay = mockCustomerQuotations.find(
          (q) => q.issuedById === user.id && q.issueClientRequestId === payload.clientRequestId);
        if (replay) {
          if (replay.id !== preview.id) fail('clientRequestId ถูกใช้ไปแล้วกับใบเสนอราคาอื่น', 409);
          return delay({ quotation: replay });
        }
      }
      if (!['DRAFT', 'READY_TO_ISSUE'].includes(preview.docStatus)) {
        if (preview.docStatus === 'ISSUED') fail('ใบเสนอราคานี้ออกไปแล้ว', 409);
        fail(`ใบเสนอราคาไม่ได้อยู่ในสถานะที่ออกได้ (${preview.docStatus})`, 409);
      }
      for (const item of preview.items) {
        if (item.minimumSellingPricePerRequestedUnit != null && item.finalUnitPrice < item.minimumSellingPricePerRequestedUnit) {
          fail(`ไม่สามารถออกใบเสนอราคาได้ — รายการ ${item.id} ต่ำกว่าราคาขั้นต่ำ`, 422);
        }
      }
      preview.docStatus = 'ISSUED';
      preview.issuedAt = new Date().toISOString();
      preview.issueClientRequestId = payload.clientRequestId ?? null;

      const pr = findPricingRequestRaw(preview.pricingRequestId);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      // Only the FIRST issue moves the pricing request — a revision's re-issue is a no-op
      // transition (mirrors PricingRequestStatus.QUOTATION_ISSUED being terminal).
      if (pr.status === 'APPROVED_FOR_QUOTATION') {
        pr.status = 'QUOTATION_ISSUED';
      }
      // Rule 7: reuse the EXACT SAME stage-advance the legacy quotation() mock action already
      // performs — not a second path.
      // Mirrors TicketService.stageForQuotationRecipient. V143 split the recipients that used to
      // collapse onto one stage: DESIGNER -> S4, OWNER -> S5, BUYER -> S8. This line routed OWNER
      // to QUOTE_DESIGN_SIDE until now, so issuing an owner quotation in mock mode advanced the
      // deal to the designer's milestone and "has the owner been quoted?" stayed unanswerable.
      if (preview.recipientType === 'DESIGNER') autoAdvanceStage(ticket, 'QUOTE_DESIGN_SIDE', user);
      if (preview.recipientType === 'OWNER') autoAdvanceStage(ticket, 'QUOTE_OWNER', user);
      if (preview.recipientType === 'BUYER') autoAdvanceStage(ticket, 'QUOTE_BUYER', user);
      pushEvent(ticket, user, 'QUOTATION_ISSUED', null, null,
        `ออกใบเสนอราคาลูกค้า ${preview.number} (revision ${preview.quotationRevisionNo})`);
      pushPricingRequestEvent(pr, user, 'CUSTOMER_QUOTATION_ISSUED', null, null, `ออกใบเสนอราคาลูกค้า ${preview.number}`);
      // "Customer notification" — no customer user account exists in this system to notify
      // in-app, so (matching the real service) the closest equivalent is a CEO-visibility
      // notification. Documented, not silently substituted.
      const ceoUsers = db.users.filter((u) => u.role === 'ceo');
      ceoUsers.forEach((ceo) => addNotification(ceo.id, pr.ticketId, ticket?.code, 'CUSTOMER_QUOTATION_ISSUED',
        `ใบเสนอราคาลูกค้า ${preview.number} ถูกออกแล้ว`));
      return delay({ quotation: preview });
    },

    async cancelCustomerQuotation(id, payload = {}) {
      const user = hasRole('sales');
      const quotation = mockCustomerQuotationEditAccess(id, user);
      if (!['DRAFT', 'READY_TO_ISSUE'].includes(quotation.docStatus)) {
        fail('ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงยกเลิกไม่ได้', 409);
      }
      quotation.docStatus = 'CANCELLED';
      pushPricingRequestEvent(findPricingRequestRaw(quotation.pricingRequestId), user, 'CUSTOMER_QUOTATION_CANCELLED',
        null, null, `ยกเลิกร่างใบเสนอราคาลูกค้า ${quotation.number}${payload.reason ? ` — ${payload.reason}` : ''}`);
      return delay({ quotation });
    },

    async createCustomerQuotationRevision(id, payload = {}) {
      const user = hasRole('sales');
      const source = mockCustomerQuotationEditAccess(id, user);
      // Step 5 (design correction 3): a commercial-only correction is reachable from ISSUED OR
      // REVISION_REQUESTED (recordOutcome writes ISSUED -> REVISION_REQUESTED immediately, before
      // Sales picks commercial-only vs cost-affecting) — nothing else.
      if (!['ISSUED', 'REVISION_REQUESTED'].includes(source.docStatus)) {
        fail('แก้ไข revision ใหม่ได้จากใบเสนอราคาที่ออกแล้วเท่านั้น', 409);
      }
      if (payload.clientRequestId) {
        const replay = mockCustomerQuotations.find(
          (q) => q.issuedById === user.id && q.clientRequestId === payload.clientRequestId);
        if (replay) return delay({ quotation: replay });
      }
      const pr = findPricingRequestRaw(source.pricingRequestId);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      const decision = mockPricingDecisions.find((d) => d.pricingRequestId === pr.id && d.status === 'APPROVED');
      if (!decision) fail('ยังไม่มีราคาขายที่ CEO อนุมัติ', 409);
      // Preserve each prior line's discount by pricingRequestItemId, same as the real service.
      const priorDiscounts = {};
      source.items.forEach((item) => { priorDiscounts[item.pricingRequestItemId] = item.salesDiscount; });
      const revision = buildMockCustomerQuotationDraft(pr, decision, ticket, user, {
        paymentTerms: source.paymentTerms, leadTime: source.leadTime, deliveryTerms: source.deliveryTerms,
        validityDate: source.validityDate, customerNotes: source.customerNotes,
        clientRequestId: payload.clientRequestId,
      }, source.id, source.quotationRevisionNo + 1, priorDiscounts);
      mockCustomerQuotations.push(revision);
      source.docStatus = 'SUPERSEDED';
      pushPricingRequestEvent(pr, user, 'CUSTOMER_QUOTATION_REVISED', null, null,
        `สร้างใบเสนอราคาลูกค้า revision ${revision.quotationRevisionNo}${payload.reason ? ` — ${payload.reason}` : ''}`);
      return delay({ quotation: revision });
    },

    // ── Step 5: Customer Decision and Commercial Revisions — mirrors
    // CustomerQuotationController.recordOutcome + CustomerQuotationService.recordOutcome.
    // Authorization is NOT authoritative (CLAUDE.md); verify against the real Java service.

    async recordCustomerQuotationOutcome(id, payload = {}) {
      const user = hasRole('sales');
      const quotation = mockCustomerQuotationEditAccess(id, user);
      if (payload.outcome === 'EXPIRED') {
        fail('EXPIRED ไม่สามารถบันทึกผ่าน API นี้ได้ — ระบบตั้งเป็นอัตโนมัติเท่านั้น', 400);
      }
      if (!['ACCEPTED', 'REJECTED', 'REVISION_REQUESTED'].includes(payload.outcome)) {
        fail('outcome ไม่ถูกต้อง', 400);
      }
      if (payload.clientRequestId) {
        const replay = mockCustomerQuotations.find(
          (q) => q.issuedById === user.id && q.outcomeClientRequestId === payload.clientRequestId);
        if (replay) return delay({ quotation: replay });
      }
      if (quotation.docStatus !== 'ISSUED') {
        fail(`บันทึกผลได้เฉพาะใบเสนอราคาที่ออกแล้วเท่านั้น (ปัจจุบัน: ${quotation.docStatus})`, 409);
      }
      const now = new Date().toISOString();
      quotation.docStatus = payload.outcome;
      quotation.outcomeNote = payload.customerNote ?? null;
      quotation.outcomeRecordedAt = now;
      quotation.outcomeClientRequestId = payload.clientRequestId ?? null;
      if (payload.outcome === 'ACCEPTED') quotation.acceptedAt = now;
      if (payload.outcome === 'REJECTED') quotation.rejectedAt = now;

      const pr = findPricingRequestRaw(quotation.pricingRequestId);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      const eventKind = payload.outcome === 'ACCEPTED' ? 'CUSTOMER_QUOTATION_ACCEPTED'
        : payload.outcome === 'REJECTED' ? 'CUSTOMER_QUOTATION_REJECTED' : 'CUSTOMER_QUOTATION_REVISION_REQUESTED';
      const label = payload.outcome === 'ACCEPTED' ? 'ลูกค้ายอมรับแล้ว'
        : payload.outcome === 'REJECTED' ? 'ถูกลูกค้าปฏิเสธ' : 'ลูกค้าขอแก้ไข';
      pushPricingRequestEvent(pr, user, eventKind, null, null,
        `บันทึกผลใบเสนอราคาลูกค้า ${quotation.number}: ${payload.outcome}${payload.customerNote ? ` — ${payload.customerNote}` : ''}`);
      const ceoUsers = db.users.filter((u) => u.role === 'ceo');
      ceoUsers.forEach((ceo) => addNotification(ceo.id, pr.ticketId, ticket?.code, eventKind,
        `ใบเสนอราคาลูกค้า ${quotation.number} ${label}`));

      // Design correction 2: ACCEPTED is the ONE forward exit from QUOTATION_ISSUED.
      // REJECTED/REVISION_REQUESTED/EXPIRED deliberately never change pr.status.
      if (payload.outcome === 'ACCEPTED' && pr.status === 'QUOTATION_ISSUED') {
        pr.status = 'QUOTATION_ACCEPTED';
      }
      return delay({ quotation });
    },

    // ── Step 6: Deposit, Payment, and Order Confirmation — mirrors
    // OrderConfirmationController/OrderConfirmationService. Authorization is NOT authoritative
    // (CLAUDE.md); verify against the real Java service.

    async confirmOrder(id, payload = {}) {
      const user = hasRole('sales');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      // Owner-only, mirrors OrderConfirmationService.requireOwner (ticket owner, not merely the
      // pricing request's own requestedById).
      if (ticket?.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);

      if (pr.orderConfirmedAt) {
        if (payload.clientRequestId && payload.clientRequestId === pr.orderConfirmClientRequestId) {
          return delay({ result: { ticket: buildTicketDetail(ticket), pricingRequest: buildPricingRequestSummary(pr) } });
        }
        fail('คำสั่งซื้อนี้ได้รับการยืนยันไปแล้ว', 409);
      }
      if (pr.status !== 'QUOTATION_ACCEPTED') {
        fail(`ยืนยันคำสั่งซื้อได้เฉพาะคำขอราคาที่ลูกค้ายอมรับใบเสนอราคาแล้วเท่านั้น (ปัจจุบัน: ${pr.status})`, 409);
      }

      const now = new Date().toISOString();
      pr.orderConfirmedAt = now;
      pr.orderConfirmedBy = user.id;
      pr.orderConfirmClientRequestId = payload.clientRequestId ?? null;

      // The one deliberate bridge write (mirrors TicketRepository
      // .markQuotationIssuedForOrderConfirmation): guarded FROM 'draft' only.
      if (ticket.status === 'draft') {
        const fromStatus = ticket.status;
        ticket.status = 'quotation_issued';
        ticket.updatedAt = now;
        pushEvent(ticket, user, 'ORDER_CONFIRMED_FROM_QUOTATION', fromStatus, 'quotation_issued',
          `ยืนยันคำสั่งซื้อจากใบเสนอราคาลูกค้าที่ยอมรับแล้ว (คำขอราคา ${pr.requestCode})`);
      }

      // Step 8: reconcile ticket_item.qty to what THIS pricing request actually settled on,
      // before any delivery machinery becomes reachable — mirrors OrderConfirmationService
      // .reconcileTicketItems, called at the same point in the real bridge.
      reconcileTicketItemsFromPricingRequest(ticket, pr, user);

      // Bug fix (found on review, not deferred): confirmOrder runs once PER ACCEPTED PRICING
      // REQUEST — reconciliation above must happen for every accepted revision, not just the
      // deal's first one — but confirmCustomer is a ONE-TIME, ticket-level action whose guard
      // correctly refuses a second call once payment has progressed. Calling it unconditionally
      // meant confirming a later revision (deposit already paid, stock already reserved) always
      // threw, rolling back this whole transaction INCLUDING the reconciliation that just ran.
      // Only run the confirmCustomer mirror when the ticket hasn't been customer-confirmed yet.
      if (ticket.paymentStatus == null) {
        ticket.paymentStatus = 'CUSTOMER_CONFIRMED';
        ticket.updatedAt = now;
        pushEvent(ticket, user, 'CUSTOMER_CONFIRMED', ticket.status, ticket.status, null);
        autoAdvanceStage(ticket, 'ORDER_RECEIVED', user);
      }

      pushPricingRequestEvent(pr, user, 'ORDER_CONFIRMED', pr.status, pr.status, 'ยืนยันคำสั่งซื้อแล้ว');
      const ceoUsers = db.users.filter((u) => u.role === 'ceo');
      ceoUsers.forEach((ceo) => addNotification(ceo.id, pr.ticketId, ticket?.code, 'ORDER_CONFIRMED',
        `คำขอราคา ${pr.requestCode} ยืนยันคำสั่งซื้อแล้ว`));

      return delay({ result: { ticket: buildTicketDetail(ticket), pricingRequest: buildPricingRequestSummary(pr) } });
    },

    async createDepositNoticeFromQuotation(id, payload = {}) {
      const user = hasRole('sales');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (ticket?.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);

      const accepted = mockCustomerQuotations.find(
        (q) => q.pricingRequestId === pr.id && q.docStatus === 'ACCEPTED');
      if (!accepted) fail('ยังไม่มีใบเสนอราคาที่ลูกค้ายอมรับสำหรับคำขอราคานี้', 409);

      // Mirrors OrderConfirmationService.createDepositNoticeFromQuotation, which delegates the
      // mapping to DepositNoticeService.itemsFromQuotation (shared with depositNotices
      // .createDraft's own new-chain fallback below) — items/amounts trace to the quotation,
      // never to any sales.ticket_item row.
      const items = mockDepositNoticeItemsFromQuotation(accepted.items);

      // Requires quotation_issued (one of DepositNoticeService.requireApprovedTicket's three
      // accepted statuses) — mirrors what confirmOrder above already left in place; calling this
      // BEFORE confirmOrder fails here exactly like the real backend's requireApprovedTicket.
      requireActive(ticket);
      if (!['approved', 'quotation_issued', 'document_issued'].includes(ticket.status)) {
        fail('สร้างใบแจ้งรับมัดจำได้เฉพาะดีลที่อนุมัติแล้วเท่านั้น', 409);
      }

      // Branch fix: header autofill — this entry point never received an explicit customer/
      // project from its caller, so it always sourced the header from the (blank) defaults
      // below. Mirrors DepositNoticeService.createDraft, which this real endpoint delegates to.
      const { customerTaxId, customerAddress, projectName } = mockDepositNoticeHeaderAutofill(ticket, {});

      const notes = mockNoteTemplates.filter((t) => t.defaultSelected).map((t) => t.text);
      const nextVer = mockDepositNotices.filter((d) => d.ticketId === pr.ticketId).length + 1;
      const doc = buildMockDoc({
        id: mockDocSeq++, ticketId: pr.ticketId, docType: 'DEPOSIT_NOTICE',
        version: nextVer, docNumber: null, issueDate: null, status: 'DRAFT',
        customerName: ticket.customerName ?? '', customerTaxId: customerTaxId ?? '',
        customerAddress: customerAddress ?? '',
        projectName: projectName ?? '', reference: accepted.number,
        depositPercent: payload.depositPercent ?? 0.5, vatPercent: 0.07,
        notes, items, issuedByName: null, preparerName: 'จินตนา หาญมนตรี',
        hasPdf: false, hasXlsx: false,
        createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
      });
      mockDepositNotices.push(doc);

      pushPricingRequestEvent(pr, user, 'DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION', pr.status, pr.status,
        `สร้างร่างใบแจ้งยอดเงินรับมัดจำจากใบเสนอราคา ${accepted.number}`);
      return delay({ depositNotice: structuredClone(doc) });
    },

    async downloadCustomerQuotationPdf(id) {
      const quotation = mockCustomerQuotationViewAccess(id);
      return buildMockCustomerQuotationDocument(quotation, 'pdf');
    },

    async downloadCustomerQuotationXlsx(id) {
      const quotation = mockCustomerQuotationViewAccess(id);
      return buildMockCustomerQuotationDocument(quotation, 'xlsx');
    },

    async submit(id) {
      // Mirrors PricingRequestService.submit: owner sales, DRAFT only, deal
      // ACTIVE, >=1 item, recipient identifiable, requiredDate not in the past,
      // no duplicate sourceTicketItemId across lines.
      const user = hasRole('sales');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (ticket?.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (pr.status !== 'DRAFT') fail(`ต้องเป็นคำขอราคาที่อยู่ในสถานะ 'DRAFT' เท่านั้น (สถานะปัจจุบัน: '${pr.status}')`, 409);
      requirePricingRequestDealActive(ticket);
      if (pr.items.length === 0) fail('ต้องมีรายการสินค้าอย่างน้อย 1 รายการก่อนส่งคำขอราคา', 400);
      if (pr.recipientContactId == null && !pr.recipientLabel?.trim()) fail('ต้องระบุผู้รับคำขอราคา', 400);
      if (pr.requiredDate && new Date(pr.requiredDate) < new Date(new Date().toDateString())) {
        fail('วันที่ต้องการต้องไม่ใช่วันที่ผ่านมาแล้ว', 400);
      }
      // Re-check item identity against the PERSISTED items, not just at
      // create()/update() time — a draft saved before this rule existed (or
      // one whose items were never touched again) must still be blocked here.
      requirePricingRequestItemFieldsValid(pr.items);
      // Snapshot the catalog selection for every catalog-backed item — mirrors
      // PricingRequestService.submit calling snapshotCatalogSelections. The "Finding A" gate that
      // used to follow this (reject unless EVERY item resolved) was removed on 2026-08-11 with
      // its Java counterpart: an item naming a product that is not in the catalogue yet is now a
      // valid submit, and just carries a null snapshot through to Import.
      snapshotPricingRequestCatalogSelections(pr);
      const seenSourceItemIds = new Set();
      for (const item of pr.items) {
        if (item.sourceTicketItemId != null) {
          if (seenSourceItemIds.has(item.sourceTicketItemId)) fail('มีรายการอ้างอิงสินค้าเดิมซ้ำกัน', 400);
          seenSourceItemIds.add(item.sourceTicketItemId);
        }
      }

      const now = new Date().toISOString();
      pr.status = 'SUBMITTED';
      pr.submittedAt = now;
      pr.updatedAt = now;
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED');
      // NotificationRepository.notifyByRole('import', ...) has no single mock
      // equivalent (no per-role broadcast helper here) — hardcoded to the demo
      // import user (id 7), same convention as the existing ceo hardcode
      // (id 8) elsewhere in this file for PRICE_PROPOSED.
      addNotification(7, pr.ticketId, ticket?.code, 'PRICING_REQUEST_SUBMITTED', `คำขอราคา ${pr.requestCode} รอการรับเรื่อง`);
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async pickup(id) {
      // Mirrors PricingRequestService.pickup: any import user, SUBMITTED only.
      // Assigns the PRICING REQUEST only — never sales.ticket.assigned_to (two
      // pricing requests on the same deal may go to two different Import users).
      const user = hasRole('import');
      const pr = findPricingRequestRaw(id);
      if (pr.status !== 'SUBMITTED') fail('รับเรื่องได้เฉพาะคำขอราคาที่ถูกยื่นแล้วเท่านั้น', 409);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      requirePricingRequestDealActive(ticket);
      const now = new Date().toISOString();
      pr.status = 'IMPORT_REVIEWING';
      pr.assignedImportId = user.id;
      pr.assignedImportName = user.name;
      pr.pickedUpAt = now;
      pr.updatedAt = now;
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING');
      addNotification(pr.requestedById, pr.ticketId, ticket?.code, 'PRICING_REQUEST_PICKED_UP', `คำขอราคา ${pr.requestCode} ถูกรับเรื่องแล้ว`);
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async createCustomerChangeRevision(id, payload) {
      const user = hasRole('sales');
      const parent = requirePricingRequestViewable(id, user);
      const ticket = db.tickets.find((t) => t.id === parent.ticketId);
      if (ticket?.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (['DRAFT', 'CANCELLED', 'SUPERSEDED'].includes(parent.status)) {
        fail('สร้างรอบแก้ไขจากการเปลี่ยนแปลงของลูกค้าได้เฉพาะจากคำขอราคาที่ยื่นและยังดำเนินการอยู่เท่านั้น', 409);
      }
      if (!payload.revisionReason?.trim()) fail('revisionReason ต้องไม่เว้นว่าง', 400);
      if (!payload.clientRequestId) fail('clientRequestId ต้องเป็น UUID', 400);
      if (!payload.items?.length) fail('ต้องมีรายการอย่างน้อย 1 รายการ', 400);
      requirePricingRequestItemFieldsValid(payload.items ?? []);
      const parentStatus = parent.status;
      const revisionId = mockPricingRequestSeq++;
      parent.status = 'SUPERSEDED';
      parent.updatedAt = new Date().toISOString();
      const now = new Date().toISOString();
      const revision = {
        ...parent,
        id: revisionId,
        requestCode: nextPricingRequestCode(),
        status: 'DRAFT',
        revisionNo: (parent.revisionNo ?? 1) + 1,
        parentPricingRequestId: parent.id,
        clientRequestId: payload.clientRequestId,
        recipientType: payload.recipientType,
        recipientContactId: payload.recipientContactId ?? null,
        recipientLabel: payload.recipientLabel ?? null,
        requiredDate: payload.requiredDate ?? null,
        customerTargetPrice: payload.customerTargetPrice ?? null,
        targetCurrency: normalizePricingRequestCurrency(payload.targetCurrency),
        note: payload.note ?? null,
        submittedAt: null,
        pickedUpAt: null,
        cancelledAt: null,
        createdAt: now,
        updatedAt: now,
        items: (payload.items ?? []).map((item, i) => ({
          id: mockPricingRequestItemSeq++,
          pricingRequestId: revisionId,
          ...item,
          sortOrder: i,
        })),
        events: [],
        // A revision starts with no attachments of its own — the `...parent` spread above must
        // NOT carry the parent's attachments array forward.
        attachments: [],
      };
      mockPricingRequests.push(revision);
      // Step 5 (design correction 1, "the cascade gap"): also supersede any DRAFT/APPROVED
      // pricing_decision and any non-terminal quotation left over from Steps 3/4 — without this,
      // both stayed silently readable as current after the parent pricing request superseded.
      mockPricingDecisions
        .filter((d) => d.pricingRequestId === parent.id && ['DRAFT', 'APPROVED'].includes(d.status))
        .forEach((d) => { d.status = 'SUPERSEDED'; });
      mockCustomerQuotations
        .filter((q) => q.pricingRequestId === parent.id
          && ['ISSUED', 'READY_TO_ISSUE', 'SENT', 'REVISION_REQUESTED'].includes(q.docStatus))
        .forEach((q) => { q.docStatus = 'SUPERSEDED'; });
      pushPricingRequestEvent(parent, user, 'PRICING_REQUEST_REVISED', parentStatus, 'SUPERSEDED', payload.revisionReason);
      pushPricingRequestEvent(revision, user, 'PRICING_REQUEST_CREATED', null, 'DRAFT', payload.revisionReason);
      return delay({ pricingRequest: buildPricingRequestDetail(revision) });
    },

    async cancel(id, payload) {
      // Mirrors PricingRequestService.cancel: owner sales OR ceo (an explicit
      // override — unlike TicketService.cancel, which has none), any status the
      // transition table allows into CANCELLED.
      const user = requireSession();
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      const isOwnerOrCeo = user.role === 'ceo' || ticket?.createdById === user.id;
      if (!isOwnerOrCeo) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (!pricingRequestCanTransition(pr.status, 'CANCELLED')) {
        fail(`ไม่สามารถยกเลิกคำขอราคาที่อยู่ในสถานะ '${pr.status}' ได้`, 409);
      }
      const now = new Date().toISOString();
      const fromStatus = pr.status;
      pr.status = 'CANCELLED';
      pr.cancelledAt = now;
      pr.updatedAt = now;
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_CANCELLED', fromStatus, 'CANCELLED', payload.reason, JSON.stringify({ reason: payload.reason }));
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    // ── Pricing Request attachments (V69, review remediation COMMIT 4) ──────
    // Sales-level supporting attachments on the Pricing Request itself — distinct from the raw
    // factory-quote attachments above (uploadFactoryQuoteAttachment etc.), which stay
    // Import/CEO-only. Mirrors PricingRequestService's new uploadAttachment/listAttachments/
    // attachmentFilePath/deleteAttachment/setAttachmentIncludeInFactoryEmail.

    async uploadAttachment(id, file) {
      // Mirrors PricingRequestService.uploadAttachment: owner sales only, and
      // ATTACHMENT_EDITABLE_STATUSES — which V140 narrowed to {DRAFT} alone when the
      // ขอข้อมูลเพิ่มเติม round-trip left the product. requirePricingRequestViewable already 404s a
      // non-owner on a DRAFT (draft privacy) and 403s a non-owner once the request is
      // visible-but-not-owned — see that helper.
      const user = hasRole('sales');
      const pr = requirePricingRequestViewable(id, user);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (ticket?.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (pr.status !== 'DRAFT') {
        fail('แนบไฟล์ได้เฉพาะเมื่อคำขอราคายังเป็นแบบร่างเท่านั้น', 409);
      }
      requirePricingRequestDealActive(ticket);
      const attachment = {
        id: mockPricingRequestAttachmentSeq++,
        pricingRequestId: pr.id,
        fileName: file?.name ?? 'attachment',
        mimeType: file?.type ?? null,
        fileSize: file?.size ?? null,
        includeInFactoryEmail: false,
        uploadedBy: user.id,
        uploadedAt: new Date().toISOString(),
      };
      pr.attachments = [attachment, ...(pr.attachments ?? [])];
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_UPDATED', pr.status, pr.status, `Attachment uploaded: ${attachment.fileName}`);
      return delay({ attachment });
    },

    async listAttachments(id) {
      const user = requireSession();
      const pr = requirePricingRequestViewable(id, user);
      return delay({ items: pr.attachments ?? [] });
    },

    attachmentUrl(id) {
      return `#mock-pricing-request-attachment-${id}`;
    },

    async deleteAttachment(id) {
      // Mirrors PricingRequestService.deleteAttachment: owner sales only, and
      // ATTACHMENT_EDITABLE_STATUSES = {DRAFT} after V140.
      const user = hasRole('sales');
      const owningPr = mockPricingRequests.find((p) => (p.attachments ?? []).some((a) => a.id === Number(id)));
      if (!owningPr) fail('ไม่พบไฟล์แนบของคำขอราคานี้', 404);
      const ticket = db.tickets.find((t) => t.id === owningPr.ticketId);
      if (ticket?.createdById !== user.id) fail('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403);
      if (owningPr.status !== 'DRAFT') {
        fail('ลบไฟล์แนบได้เฉพาะเมื่อคำขอราคายังเป็นแบบร่างเท่านั้น', 409);
      }
      owningPr.attachments = (owningPr.attachments ?? []).filter((a) => a.id !== Number(id));
      return delay({ ok: true });
    },

    async setAttachmentIncludeInFactoryEmail(id, includeInFactoryEmail) {
      // Mirrors PricingRequestService.setAttachmentIncludeInFactoryEmail: import only. No extra
      // status gate — requirePricingRequestViewable already makes a DRAFT request invisible to
      // import entirely, so import can only ever reach an attachment on an already-submitted
      // request.
      const user = hasRole('import');
      const owningPr = mockPricingRequests.find((p) => (p.attachments ?? []).some((a) => a.id === Number(id)));
      if (!owningPr) fail('ไม่พบไฟล์แนบของคำขอราคานี้', 404);
      requirePricingRequestViewable(owningPr.id, user);
      const attachment = owningPr.attachments.find((a) => a.id === Number(id));
      attachment.includeInFactoryEmail = Boolean(includeInFactoryEmail);
      return delay({ attachment });
    },
  },

};
