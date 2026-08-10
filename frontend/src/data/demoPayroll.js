// Demo seed data for the payroll/commission stores that are genuinely fake-able
// in mock mode (see CLAUDE.md — payroll/tax calculation itself stays untouched;
// payroll.preview/process, saveTaxAllowances and applyTaxAllowanceDeclaration
// all still throw "not supported in mock mode", unchanged by this file).
//
// db.commissions / db.taxAllowanceDeclarations / db.taxAllowanceAttachments /
// db.deductionObligations / db.payrollInputDrafts are workflow/tracking state —
// moving a row between statuses performs no money math — so they can be seeded
// directly. db.deductionObligationRemittances is NOT included here: real
// remittance rows are only ever written by PayrollService#process, mocked as
// "not supported", so that store must stay permanently empty (see its own
// comment in mockApi.js).

// ── Commissions (db.commissions) — mirrors CommissionService. kind: SALE
// (auto, invoice-driven) / CLAWBACK (reversal) / the four MANUAL_COMMISSION_KINDS
// (ADJUSTMENT, MANAGER, STOCK_BONUS, INCENTIVE). status: SUBMITTED ->
// MANAGER_APPROVED -> APPROVED, or REJECTED at either stage. VOID is a real
// backend status no mock handler ever produces — seeded directly here so the
// UI has a row to render for it.
export function buildDemoCommissions() {
  const now = new Date().toISOString();
  const invoice = (overrides) => ({
    id: overrides.id,
    invoiceNumber: overrides.invoiceNumber,
    invoiceDate: overrides.invoiceDate,
    grossAmount: overrides.grossAmount,
    bankFees: overrides.bankFees ?? 0,
    suspenseVat: overrides.suspenseVat ?? 0,
    transportFee: overrides.transportFee ?? 0,
    cutFee: overrides.cutFee ?? 0,
    shortfall: overrides.shortfall ?? 0,
    withholdingTax: overrides.withholdingTax ?? 0,
    overpayment: overrides.overpayment ?? 0,
    invoiceAttachmentId: overrides.invoiceAttachmentId ?? null,
    invoiceAttachmentFileName: overrides.invoiceAttachmentFileName ?? null,
    createdAt: now,
    updatedAt: now,
  });
  const row = (overrides) => ({
    submittedById: null,
    weightMultiplier: 1,
    approvedById: null, approvedAt: null,
    managerApprovedBy: null, managerApprovedByName: null, managerApprovedAt: null,
    ceoApprovedBy: null, ceoApprovedByName: null, ceoApprovedAt: null,
    rejectedById: null, rejectedByName: null, rejectedAt: null, rejectionReason: null,
    cancellationOfId: null, cancellationReason: null,
    dealPayableAmountSnapshot: null, dealAmountMismatch: false,
    manualAmount: null, manualReason: null,
    invoiceDetails: null,
    createdAt: now, updatedAt: now,
    ...overrides,
  });

  // A MANUAL commission (ADJUSTMENT / MANAGER / STOCK_BONUS / INCENTIVE). Separate from `row`
  // because the two shapes genuinely differ rather than overlapping: a manual entry carries
  // `manualAmount` + `manualReason` and NO invoice, where an invoice-backed SALE carries
  // `invoiceDetails` and a computed `commissionableBase`. Building both from one helper would
  // mean a pile of nulls at every call site and would hide which fields each kind actually has.
  //
  // Field-for-field with createManualCommission()'s own record, including the parts that look
  // odd out of context: `actualReceived`/`commissionableBase` are 0 (there is no invoice to
  // derive them from), `approvedById`/`approvedAt` are always set (the creator approves at the
  // level they act on), and the manager/ceo approval pair is mutually exclusive — a
  // sales_manager entry lands MANAGER_APPROVED, a CEO entry lands APPROVED.
  const manualRow = (overrides) => ({
    sourceTicketId: null,
    weightMultiplier: 1,
    actualReceived: 0,
    commissionableBase: 0,
    payrollMonth: '2026-08-01',
    approvedById: overrides.submittedById,
    approvedAt: now,
    managerApprovedBy: null, managerApprovedByName: null, managerApprovedAt: null,
    ceoApprovedBy: null, ceoApprovedByName: null, ceoApprovedAt: null,
    rejectedById: null, rejectedByName: null, rejectedAt: null, rejectionReason: null,
    cancellationOfId: null, cancellationReason: null,
    dealPayableAmountSnapshot: null, dealAmountMismatch: false,
    invoiceDetails: null,
    createdAt: now,
    updatedAt: now,
    ...overrides,
    // Timestamps belong to whichever approval the caller named, so they are applied AFTER the
    // spread rather than being repeated at every call site.
    ...(overrides.managerApprovedBy ? { managerApprovedAt: now } : {}),
    ...(overrides.ceoApprovedBy ? { ceoApprovedAt: now } : {}),
  });

  // D1 fix (review pass): db.tickets only has TWO tickets whose salesStage derives to
  // CLOSED_PAID — ticket 9 (status 'closed') and ticket 14 (paymentStatus 'FULLY_PAID'); see
  // the derivation loop at the top of this file's companion mockApi.js (the `if (t.salesStage)
  // continue; ... CLOSED_PAID` block). Both handlers that create a linked commission
  // (commissions.create / commissions.createFromDeal, mockApi.js) 422 unless
  // `ticket.salesStage === 'CLOSED_PAID'`, so every commission below that isn't legitimately
  // tied to ticket 9 or 14 is now unlinked (`sourceTicketId: null`, matching snapshot fields
  // nulled too — both handlers leave dealPayableAmountSnapshot/dealAmountMismatch at their
  // null/false defaults for an unlinked commission). Commission #4 already pointed at ticket
  // 14 and is left untouched.
  return [
    // SUBMITTED — auto SALE, pending manager review. Unlinked (D1): ticket 1 never reaches
    // CLOSED_PAID, and both CLOSED_PAID tickets are already spoken for by #3/#4 below.
    row({
      id: 1, sourceTicketId: null, salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี',
      kind: 'SALE', status: 'SUBMITTED', payrollMonth: '2026-08-01',
      actualReceived: 125000, commissionableBase: 116822.43,
      dealPayableAmountSnapshot: null, dealAmountMismatch: false,
      invoiceDetails: invoice({ id: 1, invoiceNumber: 'INV-2026-08001', invoiceDate: '2026-08-01', grossAmount: 125000 }),
    }),
    // MANAGER_APPROVED — waiting on CEO. Unlinked (D1): ticket 3 never reaches CLOSED_PAID.
    row({
      id: 2, sourceTicketId: null, salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี',
      kind: 'SALE', status: 'MANAGER_APPROVED', payrollMonth: '2026-07-01',
      actualReceived: 600000, commissionableBase: 560747.66,
      dealPayableAmountSnapshot: null, dealAmountMismatch: false,
      managerApprovedBy: 9, managerApprovedByName: 'คุณมณี ผู้จัดการฝ่ายขาย', managerApprovedAt: now,
      invoiceDetails: invoice({ id: 2, invoiceNumber: 'INV-2026-07050', invoiceDate: '2026-07-14', grossAmount: 600000 }),
    }),
    // APPROVED — normal, in payroll-ready pool. Unlinked (D1): ticket 4 never reaches
    // CLOSED_PAID (both real CLOSED_PAID tickets, 9 and 14, are single-use per the
    // hasActiveCommissionForTicket guard in createFromDeal, and 14 is already #4's).
    row({
      id: 3, sourceTicketId: null, salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี',
      kind: 'SALE', status: 'APPROVED', payrollMonth: '2026-07-01',
      actualReceived: 1200000, commissionableBase: 1121495.33,
      dealPayableAmountSnapshot: null, dealAmountMismatch: false,
      managerApprovedBy: 9, managerApprovedByName: 'คุณมณี ผู้จัดการฝ่ายขาย', managerApprovedAt: now,
      ceoApprovedBy: 8, ceoApprovedByName: 'คุณวิชัย ธนาคาร', ceoApprovedAt: now,
      approvedById: 8, approvedAt: now,
      invoiceDetails: invoice({ id: 3, invoiceNumber: 'INV-2026-07051', invoiceDate: '2026-07-10', grossAmount: 1200000 }),
    }),
    // APPROVED with a deal-amount mismatch flag (gross vs. deal payable diverges > 5%). Linked
    // to ticket 14 — the one real CLOSED_PAID deal owned by this rep. Legal as-is (D1: this was
    // the sole pre-existing commission whose sourceTicketId already satisfied the gate).
    row({
      id: 4, sourceTicketId: 14, salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี',
      kind: 'SALE', status: 'APPROVED', payrollMonth: '2026-07-01',
      actualReceived: 560000, commissionableBase: 523364.49,
      dealPayableAmountSnapshot: 624000, dealAmountMismatch: true,
      managerApprovedBy: 9, managerApprovedByName: 'คุณมณี ผู้จัดการฝ่ายขาย', managerApprovedAt: now,
      ceoApprovedBy: 8, ceoApprovedByName: 'คุณวิชัย ธนาคาร', ceoApprovedAt: now,
      approvedById: 8, approvedAt: now,
      invoiceDetails: invoice({ id: 4, invoiceNumber: 'INV-2026-07052', invoiceDate: '2026-07-05', grossAmount: 560000 }),
    }),
    // REJECTED. Unlinked (D1): ticket 2 never reaches CLOSED_PAID.
    row({
      id: 5, sourceTicketId: null, salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี',
      kind: 'SALE', status: 'REJECTED', payrollMonth: '2026-07-01',
      actualReceived: 95000, commissionableBase: 88785.05,
      dealPayableAmountSnapshot: null, dealAmountMismatch: false,
      rejectedById: 9, rejectedByName: 'คุณมณี ผู้จัดการฝ่ายขาย', rejectedAt: now,
      rejectionReason: 'เลขที่ใบแจ้งหนี้ซ้ำกับรายการที่บันทึกไว้แล้ว',
      invoiceDetails: invoice({ id: 5, invoiceNumber: 'INV-2026-06090', invoiceDate: '2026-06-25', grossAmount: 95000 }),
    }),
    // VOID — hand-set (no mock handler reaches this status; a legitimate backend state).
    // Unlinked (D1): ticket 8 derives to QUOTE_BUYER, never CLOSED_PAID.
    row({
      id: 6, sourceTicketId: null, salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี',
      kind: 'SALE', status: 'VOID', payrollMonth: '2026-05-01',
      actualReceived: 52000, commissionableBase: 48598.13,
      dealPayableAmountSnapshot: null, dealAmountMismatch: false,
      managerApprovedBy: 9, managerApprovedByName: 'คุณมณี ผู้จัดการฝ่ายขาย', managerApprovedAt: now,
      ceoApprovedBy: 8, ceoApprovedByName: 'คุณวิชัย ธนาคาร', ceoApprovedAt: now,
      approvedById: 8, approvedAt: now,
      invoiceDetails: invoice({ id: 6, invoiceNumber: 'INV-2026-05010', invoiceDate: '2026-05-08', grossAmount: 52000 }),
    }),
    // CLAWBACK — reversal of commission #3. Mirrors the real clawback() handler's
    // `...structuredClone(original)`, which carries the original SALE's invoiceDetails AND
    // sourceTicketId (now null, D1) over verbatim (a clawback is never invoice-less). D2: kept
    // in July (alongside the sale it reverses) instead of August, so August isn't left with a
    // lone negative row.
    row({
      id: 7, sourceTicketId: null, salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี',
      kind: 'CLAWBACK', status: 'APPROVED', payrollMonth: '2026-07-01',
      actualReceived: -1200000, commissionableBase: -1121495.33,
      cancellationOfId: 3, cancellationReason: 'ลูกค้าคืนสินค้าบางส่วน ยอดใบแจ้งหนี้ถูกยกเลิก',
      approvedById: 8, approvedAt: now,
      invoiceDetails: invoice({ id: 8, invoiceNumber: 'INV-2026-07051', invoiceDate: '2026-07-10', grossAmount: 1200000 }),
    }),
    // MANUAL_COMMISSION_KINDS (ADJUSTMENT/MANAGER/STOCK_BONUS/INCENTIVE) are seeded below.
    //
    // They used to be impossible to seed: createManualCommission() sets `invoiceDetails: null`
    // (the faithful shape for a manual entry — V84), and BOTH commissions.create() and
    // commissions.createFromDeal() did an unguarded
    // `db.commissions.some(item => item.invoiceDetails.invoiceNumber === ...)`. One such row
    // and every subsequent create threw a TypeError — in the live mock app, not only in tests.
    // The previous pass recorded that as "could not seed" under a seed-data-only constraint.
    // Both call sites now null-guard (mockApi.js), matching the real backend rather than merely
    // dodging the crash: the Java duplicate check is a SQL comparison, and a NULL invoice number
    // is never equal to anything in SQL.
    //
    // Four kinds x the two statuses createManualCommission itself can produce — a
    // sales_manager entry lands MANAGER_APPROVED, a CEO entry lands APPROVED — so the manual
    // path's own branch is visible in the list rather than only its happy end state. Kept in
    // 2026-08 (the current month) because these are the four kinds a UAT tester has no other
    // way to see: they have no auto-generated equivalent to find in another month.
    // SALE for the second sales rep. Unlinked (D1): ticket 17 sits at QUOTE_DESIGN_SIDE with no
    // quotation at all, and rep2 (id 12) doesn't own either real CLOSED_PAID ticket (both are
    // rep1's), so null is the only option that keeps this "genuinely tied to a rep pointing at
    // a deal that rep owns." D2: promoted MANAGER_APPROVED->APPROVED (ceoApproved*/approvedBy
    // fields added) so 2026-08 has a real, nonzero payrollReady pool instead of only the
    // clawback. #2 stays MANAGER_APPROVED so that status still has matrix coverage.
    row({
      id: 8, sourceTicketId: null, salesRepId: 12, salesRepName: 'คุณอรุณี ขายเก่ง',
      kind: 'SALE', status: 'APPROVED', payrollMonth: '2026-08-01',
      actualReceived: 210000, commissionableBase: 196261.68,
      dealPayableAmountSnapshot: null, dealAmountMismatch: false,
      managerApprovedBy: 9, managerApprovedByName: 'คุณมณี ผู้จัดการฝ่ายขาย', managerApprovedAt: now,
      ceoApprovedBy: 8, ceoApprovedByName: 'คุณวิชัย ธนาคาร', ceoApprovedAt: now,
      approvedById: 8, approvedAt: now,
      invoiceDetails: invoice({ id: 7, invoiceNumber: 'INV-2026-08002', invoiceDate: '2026-08-01', grossAmount: 210000 }),
    }),
    // ── Manual entries. `invoiceDetails: null` and `actualReceived/commissionableBase: 0` are
    // not omissions: a manual commission has no invoice behind it and no progressive
    // calculation, so the amount lives in `manualAmount` and the justification in
    // `manualReason`. Mirrors createManualCommission()'s record shape field for field.
    //
    // ADJUSTMENT is the one kind the backend permits to be NEGATIVE (a correction); MANAGER is
    // explicitly sign-checked against it. Seeding a negative ADJUSTMENT is what makes that
    // asymmetry visible instead of merely asserted.
    manualRow({
      id: 9, salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', submittedById: 9,
      kind: 'ADJUSTMENT', status: 'MANAGER_APPROVED',
      manualAmount: -4500, manualReason: 'ปรับลดจากค่าขนส่งที่เรียกเก็บลูกค้าเกิน งวดก่อน',
      managerApprovedBy: 9, managerApprovedByName: 'คุณมณี ผู้จัดการฝ่ายขาย',
    }),
    manualRow({
      id: 10, salesRepId: 9, salesRepName: 'คุณมณี ผู้จัดการฝ่ายขาย', submittedById: 8,
      kind: 'MANAGER', status: 'APPROVED',
      manualAmount: 18000, manualReason: 'ค่าคอมมิชชั่นผู้จัดการทีมขาย ประจำงวด',
      ceoApprovedBy: 8, ceoApprovedByName: 'คุณวิชัย ธนาคาร',
    }),
    manualRow({
      id: 11, salesRepId: 12, salesRepName: 'คุณอรุณี ขายเก่ง', submittedById: 9,
      kind: 'STOCK_BONUS', status: 'MANAGER_APPROVED',
      manualAmount: 7500, manualReason: 'โบนัสระบายสต็อกค้างคลัง รุ่น Metro Square',
      managerApprovedBy: 9, managerApprovedByName: 'คุณมณี ผู้จัดการฝ่ายขาย',
    }),
    manualRow({
      id: 12, salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', submittedById: 8,
      kind: 'INCENTIVE', status: 'APPROVED',
      manualAmount: 12000, manualReason: 'อินเซนทีฟพิเศษ ปิดยอดไตรมาส 3 เกินเป้า',
      ceoApprovedBy: 8, ceoApprovedByName: 'คุณวิชัย ธนาคาร',
    }),
  ];
}

// ── Tax allowance declarations (db.taxAllowanceDeclarations / db.taxAllowanceAttachments) —
// mirrors the DECLARATION workflow only (submit/withdraw/approve/reject/on-behalf never run
// real tax math). status: PENDING / APPROVED / REJECTED / SUPERSEDED / WITHDRAWN.
//
// `effectiveMonth`/`appliedEffectiveMonth` are plain 1-12 month NUMBERS, never a 'YYYY-MM' string —
// TaxAllowanceDeclarationDto declares them `int` / `Integer` (backend/.../declaration/
// TaxAllowanceDeclarationDtos.java), and `taxYear` alongside already carries the year. These were
// seeded as strings until 2026-08; contract.test.js compares the method surface and arity only,
// never field value types, so nothing caught it. Three UI sites read the month raw and all three
// were wrong under VITE_USE_MOCKS=true: TaxAllowanceReviewPage's applied column and
// taxAllowanceStatus.js's APPLIED badge both rendered "ตั้งแต่เดือน 2026-07", and ApplyDialog seeds
// its <select> from `declaration.effectiveMonth` against options 1-12, which a string matches none
// of. Keep these integers — see buildDemoEmployeeTaxAllowances below, which seeds the same field.
function blankAllowances(overrides = {}) {
  return {
    spouseAllowance: 0, childAllowance: 0, parentCareAllowance: 0, disabledCareAllowance: 0,
    maternityAllowance: 0, lifeInsuranceAllowance: 0, healthInsuranceAllowance: 0,
    parentHealthInsuranceAllowance: 0, rmfAllowance: 0, ssfAllowance: 0,
    pensionInsuranceAllowance: 0, thaiEsgAllowance: 0, homeLoanInterestAllowance: 0,
    educationDonation: 0, generalDonation: 0, politicalDonation: 0,
    childCount: 0, childCountDouble: 0, disabledCareCount: 0,
    disabilityCardHolder: false, parentCareCount: 0,
    ...overrides,
  };
}

/**
 * The non-amount half of แบบ ล.ย.01 — mirrors {@code TaxAllowanceDeclarationDtos.LorYor01Details},
 * which the DTO documents as "never null on a read". Every row below therefore carries one.
 *
 * Seeding it is not decoration. `defaultAllowanceValues` reads `declaration.lorYor01` for the whole
 * header block, ข้อ 1/2 สถานภาพ, the eight ข้อ 4/ข้อ 6 ticks and three sub-amounts; with the field
 * absent — as it was until 2026-08-10 — every filed declaration rendered read-only with a blank
 * header and no ticks under `VITE_USE_MOCKS=true`, which is indistinguishable from the feature being
 * broken. See mockApi.js's `taxAllowanceLorYor01FromBody` for the write half of the same gap.
 *
 * `null` means "not answered" here exactly as it does on the wire: a blank box is not a ticked "no".
 */
function blankLorYor01(overrides = {}) {
  const { address, ...rest } = overrides;
  return {
    taxpayerId: null, firstNameTh: null, lastNameTh: null,
    address: {
      building: null, roomNo: null, floor: null, village: null, houseNo: null, moo: null,
      soi: null, junction: null, road: null, subDistrict: null, district: null, province: null,
      postalCode: null,
      ...address,
    },
    maritalState: null, spousalStatus: null, spouseHasIncome: null,
    childrenTotal: null, childExtraAllowance: 0,
    ownFatherSupported: null, ownMotherSupported: null,
    spouseFatherSupported: null, spouseMotherSupported: null, spouseParentCareAllowance: 0,
    ownFatherHealthInsured: null, ownMotherHealthInsured: null,
    spouseFatherHealthInsured: null, spouseMotherHealthInsured: null,
    providentFundAllowance: 0, rmfSellerName: null, otherDonationNote: null,
    ...rest,
  };
}

export function buildDemoTaxAllowanceDeclarations(employees) {
  const now = new Date().toISOString();
  // The self-service page (/tax-allowance) only ever shows the SIGNED-IN employee's own rows, and
  // in mock mode that is always employees[8] (users.js: employee@glr.co.th). Rows 1/7/8 below are
  // therefore the only ones that screen can reach — one per selectable tax year (2026/2025/2024),
  // so every state the year picker can land on has something in it. Everything else here belongs to
  // other employees and is only visible from HR's register.
  const self = employees[8];
  const hr = employees[20];
  // นายภาคภูมิ ศรีสุข's own ล.ย.01 header, consistent with his employee record (demoData.js).
  const selfHeader = {
    taxpayerId: '1100000000001',
    firstNameTh: 'ภาคภูมิ',
    lastNameTh: 'ศรีสุข',
    address: {
      houseNo: '18/9', soi: 'ซอย 9', road: 'ถ.สุขุมวิท',
      subDistrict: 'บางนาเหนือ', district: 'บางนา', province: 'กรุงเทพมหานคร', postalCode: '10888',
    },
  };
  return [
    {
      declarationId: 1, employeeId: self.id, employeeCode: self.code, employeeName: self.nameTh,
      taxYear: 2026, effectiveMonth: 8,
      // Deliberately spans ข้อ 3-15 rather than the two amounts it used to carry: the section rows
      // on /tax-allowance show a per-ข้อ subtotal, so a sparse declaration leaves eleven of them
      // reading "ไม่ได้ประกาศ" and the collapsed page can't be judged against a real one.
      allowances: blankAllowances({
        spouseAllowance: 60000,
        childAllowance: 30000, childCount: 1, childCountDouble: 1,
        parentCareAllowance: 60000, parentCareCount: 2,
        disabledCareAllowance: 60000, disabledCareCount: 1, disabilityCardHolder: true,
        parentHealthInsuranceAllowance: 15000,
        lifeInsuranceAllowance: 100000, healthInsuranceAllowance: 25000,
        rmfAllowance: 50000.5, homeLoanInterestAllowance: 75000.25,
        educationDonation: 12000, generalDonation: 8000,
      }),
      lorYor01: blankLorYor01({
        ...selfHeader,
        maritalState: 'MARRIED', spousalStatus: 'MARRIED_WHOLE_YEAR', spouseHasIncome: false,
        childrenTotal: 2, childExtraAllowance: 60000,
        ownFatherSupported: true, ownMotherSupported: true,
        spouseFatherSupported: false, spouseMotherSupported: true, spouseParentCareAllowance: 30000,
        ownFatherHealthInsured: true, ownMotherHealthInsured: false,
        spouseFatherHealthInsured: false, spouseMotherHealthInsured: true,
        providentFundAllowance: 45000,
        rmfSellerName: 'บลจ. กรุงไทย จำกัด (มหาชน)',
        otherDonationNote: 'บริจาคทั่วไป มูลนิธิรามาธิบดี',
      }),
      documentReference: 'ล.ย.01-2026-0009', status: 'PENDING',
      submittedById: self.id, submittedAt: now, onBehalf: false,
      reviewedById: null, reviewedAt: null, reviewerNote: null,
      appliedAt: null, appliedById: null, appliedEffectiveMonth: null,
      expiresOn: null, expiredAt: null, reverifiedAt: null, reverifiedById: null,
      supersededById: null,
    },
    // ── Prior years for the same employee, so the ปีภาษี picker is not two empty options. ──
    {
      declarationId: 7, employeeId: self.id, employeeCode: self.code, employeeName: self.nameTh,
      taxYear: 2025, effectiveMonth: 1,
      allowances: blankAllowances({
        spouseAllowance: 60000, childAllowance: 30000, childCount: 1,
        parentCareAllowance: 30000, parentCareCount: 1,
        lifeInsuranceAllowance: 80000, rmfAllowance: 40000,
        homeLoanInterestAllowance: 60000, generalDonation: 5000,
      }),
      lorYor01: blankLorYor01({
        ...selfHeader,
        maritalState: 'MARRIED', spousalStatus: 'MARRIED_WHOLE_YEAR', spouseHasIncome: false,
        childrenTotal: 1,
        ownFatherSupported: true, ownMotherSupported: false,
        spouseFatherSupported: false, spouseMotherSupported: false,
        providentFundAllowance: 36000,
        rmfSellerName: 'บลจ. กรุงไทย จำกัด (มหาชน)',
      }),
      documentReference: 'ล.ย.01-2025-0004', status: 'APPROVED',
      submittedById: self.id, submittedAt: '2025-01-14T03:20:00.000Z', onBehalf: false,
      reviewedById: hr.id, reviewedAt: '2025-01-17T07:05:00.000Z', reviewerNote: null,
      appliedAt: '2025-01-20T02:10:00.000Z', appliedById: hr.id, appliedEffectiveMonth: 1,
      expiresOn: '2025-12-31', expiredAt: '2026-01-01T00:00:00.000Z',
      reverifiedAt: null, reverifiedById: null, supersededById: null,
    },
    {
      declarationId: 8, employeeId: self.id, employeeCode: self.code, employeeName: self.nameTh,
      taxYear: 2024, effectiveMonth: 3,
      allowances: blankAllowances({ lifeInsuranceAllowance: 60000, generalDonation: 3000 }),
      lorYor01: blankLorYor01({
        ...selfHeader,
        maritalState: 'SINGLE', spouseHasIncome: null, childrenTotal: null,
      }),
      documentReference: 'ล.ย.01-2024-0031', status: 'REJECTED',
      submittedById: self.id, submittedAt: '2024-03-04T04:00:00.000Z', onBehalf: false,
      reviewedById: hr.id, reviewedAt: '2024-03-08T06:30:00.000Z',
      reviewerNote: 'เบี้ยประกันชีวิตเกินวงเงิน 100,000 บาท และยังไม่ได้แนบหนังสือรับรองการชำระเบี้ยประกันจากบริษัทประกัน',
      appliedAt: null, appliedById: null, appliedEffectiveMonth: null,
      expiresOn: null, expiredAt: null, reverifiedAt: null, reverifiedById: null,
      supersededById: null,
    },
    {
      declarationId: 2, employeeId: employees[12].id, employeeCode: employees[12].code, employeeName: employees[12].nameTh,
      taxYear: 2026, effectiveMonth: 7,
      allowances: blankAllowances({ childAllowance: 30000, childCount: 1, rmfAllowance: 50000 }),
      lorYor01: blankLorYor01({
        maritalState: 'SINGLE', childrenTotal: 1,
        rmfSellerName: 'บลจ. ไทยพาณิชย์ จำกัด',
      }),
      documentReference: 'ล.ย.01-2026-0010', status: 'APPROVED',
      submittedById: employees[12].id, submittedAt: now, onBehalf: false,
      reviewedById: employees[20].id, reviewedAt: now, reviewerNote: null,
      appliedAt: now, appliedById: employees[20].id, appliedEffectiveMonth: 7,
      expiresOn: '2026-12-31', expiredAt: null, reverifiedAt: null, reverifiedById: null,
      supersededById: null,
    },
    {
      declarationId: 3, employeeId: employees[2].id, employeeCode: employees[2].code, employeeName: employees[2].nameTh,
      taxYear: 2026, effectiveMonth: 7,
      allowances: blankAllowances({ homeLoanInterestAllowance: 40000 }),
      lorYor01: blankLorYor01({ maritalState: 'SINGLE' }),
      documentReference: 'ล.ย.01-2026-0011', status: 'REJECTED',
      submittedById: employees[2].id, submittedAt: now, onBehalf: false,
      reviewedById: employees[20].id, reviewedAt: now,
      reviewerNote: 'เอกสารดอกเบี้ยเงินกู้บ้านไม่ครบ กรุณาแนบหนังสือรับรองจากธนาคาร',
      appliedAt: null, appliedById: null, appliedEffectiveMonth: null,
      expiresOn: null, expiredAt: null, reverifiedAt: null, reverifiedById: null,
      supersededById: null,
    },
    {
      declarationId: 4, employeeId: employees[19].id, employeeCode: employees[19].code, employeeName: employees[19].nameTh,
      taxYear: 2026, effectiveMonth: 1,
      allowances: blankAllowances({ spouseAllowance: 60000, parentCareAllowance: 30000, parentCareCount: 1 }),
      lorYor01: blankLorYor01({
        maritalState: 'MARRIED', spousalStatus: 'MARRIED_WHOLE_YEAR', spouseHasIncome: false,
        ownMotherSupported: true,
      }),
      documentReference: 'ล.ย.01-2026-0002', status: 'SUPERSEDED',
      submittedById: employees[19].id, submittedAt: now, onBehalf: false,
      reviewedById: employees[20].id, reviewedAt: now, reviewerNote: null,
      appliedAt: now, appliedById: employees[20].id, appliedEffectiveMonth: 1,
      expiresOn: null, expiredAt: null, reverifiedAt: null, reverifiedById: null,
      supersededById: 5,
    },
    {
      declarationId: 5, employeeId: employees[19].id, employeeCode: employees[19].code, employeeName: employees[19].nameTh,
      taxYear: 2026, effectiveMonth: 8,
      allowances: blankAllowances({ spouseAllowance: 60000, parentCareAllowance: 60000, parentCareCount: 2 }),
      lorYor01: blankLorYor01({
        maritalState: 'MARRIED', spousalStatus: 'MARRIED_WHOLE_YEAR', spouseHasIncome: false,
        ownFatherSupported: true, ownMotherSupported: true,
      }),
      documentReference: 'ล.ย.01-2026-0012', status: 'APPROVED',
      submittedById: employees[19].id, submittedAt: now, onBehalf: false,
      reviewedById: employees[20].id, reviewedAt: now, reviewerNote: null,
      appliedAt: now, appliedById: employees[20].id, appliedEffectiveMonth: 8,
      expiresOn: '2026-12-31', expiredAt: null, reverifiedAt: null, reverifiedById: null,
      supersededById: null,
    },
    {
      declarationId: 6, employeeId: employees[15].id, employeeCode: employees[15].code, employeeName: employees[15].nameTh,
      taxYear: 2026, effectiveMonth: 6,
      allowances: blankAllowances({ ssfAllowance: 20000 }),
      lorYor01: blankLorYor01({ maritalState: 'SINGLE' }),
      documentReference: 'ล.ย.01-2026-0007', status: 'WITHDRAWN',
      submittedById: employees[15].id, submittedAt: now, onBehalf: false,
      reviewedById: null, reviewedAt: null, reviewerNote: null,
      appliedAt: null, appliedById: null, appliedEffectiveMonth: null,
      expiresOn: null, expiredAt: null, reverifiedAt: null, reverifiedById: null,
      supersededById: null,
    },
  ];
}

/**
 * Evidence files. `sectionKey` is the field that matters and every row below carries one now:
 * /tax-allowance renders ONE evidence panel per ข้อ, each filtered to its own key
 * (`TaxAllowanceEvidencePanel`, `showUncategorized={false}`), so a row with `sectionKey: null` —
 * which is what all three of these were — is invisible on that screen no matter how many there are.
 * Valid keys are `TaxAllowanceDeclarationService#EVIDENCE_SECTION_KEYS`: item3/4/5/6/7/8/9/10/12/
 * 14/15 and signed_form.
 */
export function buildDemoTaxAllowanceAttachments() {
  const now = new Date().toISOString();
  const file = (attachmentId, declarationId, fileName, sectionKey, fileSize, uploadedBy) => ({
    attachmentId, declarationId, fileName, sectionKey, fileSize, uploadedBy,
    mimeType: fileName.endsWith('.pdf') ? 'application/pdf' : 'image/jpeg',
    uploadedAt: now, deletedAt: null, deletedBy: null, deleteReason: null,
  });
  return [
    // Declaration 1 — the signed-in mock employee's PENDING 2026 filing, evidenced across the ข้อ
    // it actually declares, so the per-section panels are not all empty on the page that owns them.
    file(1, 1, 'life-insurance-policy.pdf', 'item7', 184320, 9),
    file(4, 1, 'สูติบัตร-บุตรคนที่-1.pdf', 'item3', 128440, 9),
    file(5, 1, 'ทะเบียนบ้าน-บิดามารดา.pdf', 'item4', 210880, 9),
    file(6, 1, 'บัตรประจำตัวคนพิการ.jpg', 'item5', 742400, 9),
    file(7, 1, 'ใบเสร็จเบี้ยประกันสุขภาพบิดา.pdf', 'item6', 88300, 9),
    file(8, 1, 'health-insurance-receipt-2026.pdf', 'item8', 91750, 9),
    file(9, 1, 'หนังสือรับรองกองทุนสำรองเลี้ยงชีพ.pdf', 'item9', 76800, 9),
    file(10, 1, 'rmf-statement-2026.pdf', 'item10', 143360, 9),
    file(11, 1, 'หนังสือรับรองดอกเบี้ยเงินกู้ยืมเพื่อที่อยู่อาศัย-ธนาคารกรุงเทพ.pdf', 'item12', 165890, 9),
    file(12, 1, 'ใบเสร็จบริจาคเพื่อการศึกษา.pdf', 'item14', 54200, 9),
    file(13, 1, 'ใบเสร็จบริจาค-มูลนิธิรามาธิบดี.pdf', 'item15', 48900, 9),
    file(14, 1, 'ลย01-2026-ลงนามแล้ว-สแกน.pdf', 'signed_form', 1258291, 9),
    // Prior years for the same employee.
    file(15, 7, 'ลย01-2025-ลงนามแล้ว-สแกน.pdf', 'signed_form', 1198372, 9),
    file(16, 7, 'life-insurance-policy-2025.pdf', 'item7', 176128, 9),
    file(17, 8, 'ลย01-2024-ลงนามแล้ว-สแกน.pdf', 'signed_form', 1102848, 9),
    // Other employees' declarations — only reachable from HR's register.
    file(2, 2, 'birth-certificate-child1.pdf', 'item3', 95210, 13),
    file(3, 2, 'rmf-purchase-receipt.pdf', 'item10', 61200, 13),
  ];
}

// ── Stored tax allowance (db.employeeTaxAllowances) — mirrors hr.employee_tax_allowance, the table
// PayrollCalculator actually reads to compute withholding (PayrollRepository
// #findTaxAllowancesByEmployee), NOT tax_allowance_declaration above. Its own verification_status
// (V95): VERIFIED / GRANDFATHERED_UNVERIFIED / EXPIRED_UNVERIFIED. Until this seed (2026-08,
// "register shows what payroll actually uses"), GET /api/payroll/tax-allowances returned an empty
// fixture unconditionally regardless of the year argument (contract.test.js's own former note on
// that ARITY_EXEMPTIONS entry) — the exact "mock omits a field the feature keys on" shape CLAUDE.md
// warns about: TaxAllowanceReviewPage's new join would never see a single row under
// VITE_USE_MOCKS=true. Genuinely fake-able for the same reason taxAllowanceDeclarations is: reading
// these rows performs no tax calculation. Writing them (mockApi's saveTaxAllowances, the legacy bulk
// PUT) IS real payroll math and stays a "not supported in mock mode" stub, unchanged by this seed.
//
// Four rows, covering every combination this feature's UI branches on. `effectiveMonth` values are
// chosen to still qualify under `resolvePayrollAllowance` (taxAllowanceStatus.js) for any "today"
// from August 2026 onward -- see that function's own comment on why a past tax year always resolves
// against December instead of drifting out of range.
//  - employees[5] (id 6): GRANDFATHERED_UNVERIFIED, NO declaration at all -- the central case this
//    whole feature exists for ("42 rows imported from personreduce.csv" in prod): still reducing
//    this employee's withholding right now, never reviewed through any declaration. Also carries the
//    F1 review-remediation demonstration case: an rmfAllowance of 600,000, chosen to exceed BOTH
//    PayrollCalculator#retirementAllowance's flat 500,000 retirement-cluster ceiling and its 30%-of-
//    projected-income sub-cap for this employee's salary (~156,000/month here) -- the concrete
//    "stored 600,000, payroll applies far less" example the relabelled panel (TaxAllowanceBreakdown)
//    must not misreport as the applied figure. See that component's own comment on `payrollTotal`.
//    spouseAllowance stays too (60,000, exactly its own flat cap -- a control field that does NOT
//    clamp, so the panel isn't demonstrating a difference on every field at once).
//  - employees[10] (id 11): EXPIRED_UNVERIFIED, NO declaration -- the grace period lapsed;
//    PayrollRepository's WHERE clause now excludes it, so it no longer reduces withholding, but it
//    is a real (lapsed) figure, not "nothing" -- must read distinctly from employees[5] AND from a
//    genuinely-empty employee.
//  - employees[12] (id 13): GRANDFATHERED_UNVERIFIED, alongside declarationId 2 (APPROVED, applied,
//    declared total 80,000) -- but this row's total is 90,000. Models a legacy bulk-PUT edit made
//    after the declaration was applied and never reconciled -- the disagreement case.
//  - employees[19] (id 20): VERIFIED, alongside declarationId 5 (APPROVED, applied, declared total
//    120,000) -- this row totals 120,000 too. The normal, correctly-wired path
//    (TaxAllowanceDeclarationService#apply upserts + verifies in one transaction), included so
//    "disagreement only on disagreement" has a real agreeing case to prove the negative against, not
//    just the absence of the other three rows.
export function buildDemoEmployeeTaxAllowances(employees) {
  const now = new Date().toISOString();
  const row = (overrides) => ({
    taxYear: 2026,
    documentReference: null,
    updatedAt: now,
    verifiedById: null,
    verifiedAt: null,
    verificationDeadline: null,
    ...overrides,
  });

  return [
    row({
      employeeId: employees[5].id, employeeCode: employees[5].code, employeeName: employees[5].nameTh,
      effectiveMonth: 1,
      allowances: blankAllowances({ spouseAllowance: 60000, rmfAllowance: 600000 }),
      verificationStatus: 'GRANDFATHERED_UNVERIFIED',
      verificationDeadline: '2026-12-31',
      documentReference: 'personreduce.csv#6 (นำเข้าก่อน V95)',
      updatedAt: '2026-01-05T00:00:00.000Z',
    }),
    row({
      employeeId: employees[10].id, employeeCode: employees[10].code, employeeName: employees[10].nameTh,
      effectiveMonth: 1,
      allowances: blankAllowances({ lifeInsuranceAllowance: 25000 }),
      verificationStatus: 'EXPIRED_UNVERIFIED',
      verificationDeadline: '2026-06-30',
      documentReference: 'personreduce.csv#11 (นำเข้าก่อน V95)',
      updatedAt: '2026-01-05T00:00:00.000Z',
    }),
    row({
      employeeId: employees[12].id, employeeCode: employees[12].code, employeeName: employees[12].nameTh,
      effectiveMonth: 7,
      allowances: blankAllowances({ childAllowance: 30000, childCount: 1, rmfAllowance: 60000 }),
      verificationStatus: 'GRANDFATHERED_UNVERIFIED',
      verificationDeadline: '2026-12-31',
      documentReference: 'ปรับยอด RMF ผ่านระบบเดิม (ไม่ผ่านแบบแจ้ง)',
      updatedAt: '2026-07-15T00:00:00.000Z',
    }),
    row({
      employeeId: employees[19].id, employeeCode: employees[19].code, employeeName: employees[19].nameTh,
      effectiveMonth: 8,
      allowances: blankAllowances({ spouseAllowance: 60000, parentCareAllowance: 60000, parentCareCount: 2 }),
      verificationStatus: 'VERIFIED',
      verifiedById: employees[20].id,
      verifiedAt: now,
      documentReference: 'ล.ย.01-2026-0012',
      updatedAt: now,
    }),
  ];
}

// ── Deduction obligations (db.deductionObligations) — mirrors the tracking-only
// workflow (issue #373). kind: STUDENT_LOAN (กยศ.) / LEGAL_EXECUTION (บังคับคดี).
// status: ACTIVE / STOPPED / COMPLETED (COMPLETED is only ever written by
// PayrollService#process on the real backend, so it's hand-set here for display).
export function buildDemoDeductionObligations(employees) {
  const now = new Date().toISOString();
  const row = (overrides) => ({
    instructedTotal: null,
    completedAt: null, completionAcknowledgedById: null, completionAcknowledgedAt: null,
    overrideContinuePastTotal: false, overrideById: null, overrideAt: null, overrideReason: null,
    notes: null,
    createdById: 2, createdAt: now, updatedById: 2, updatedAt: now,
    ...overrides,
  });

  return [
    row({
      id: 1, employeeId: employees[8].id, kind: 'STUDENT_LOAN',
      monthlyInstructedAmount: 3000, instructedTotal: 150000,
      authorityReference: 'กยศ-2569-000123', startDate: '2026-04-01', status: 'ACTIVE',
      notes: 'หักตามหนังสือแจ้งหนี้ กยศ. ประจำปี 2569',
    }),
    row({
      id: 2, employeeId: employees[12].id, kind: 'STUDENT_LOAN',
      monthlyInstructedAmount: 2500, instructedTotal: 90000,
      authorityReference: 'กยศ-2568-000087', startDate: '2025-01-01', status: 'COMPLETED',
      completedAt: '2026-06-01T00:00:00Z', completionAcknowledgedById: 2, completionAcknowledgedAt: now,
      notes: 'ชำระครบตามยอดที่ได้รับแจ้งแล้ว',
    }),
    row({
      id: 3, employeeId: employees[2].id, kind: 'LEGAL_EXECUTION',
      monthlyInstructedAmount: 5000, instructedTotal: 300000,
      authorityReference: 'บค-2569-004410', startDate: '2026-03-01', status: 'ACTIVE',
      notes: 'หักตามหมายบังคับคดี กรมบังคับคดี',
    }),
    row({
      id: 4, employeeId: employees[19].id, kind: 'LEGAL_EXECUTION',
      monthlyInstructedAmount: 4000, instructedTotal: 120000,
      authorityReference: 'บค-2568-002210', startDate: '2025-06-01', status: 'STOPPED',
      notes: 'หยุดหักชั่วคราวตามคำสั่งศาล รอคำสั่งใหม่',
    }),
    row({
      id: 5, employeeId: employees[16].id, kind: 'STUDENT_LOAN',
      monthlyInstructedAmount: 1800, instructedTotal: 108000,
      authorityReference: 'กยศ-2569-000205', startDate: '2026-01-01', status: 'ACTIVE',
      notes: 'อยู่ระหว่างหักตามงวด',
    }),
    row({
      id: 6, employeeId: employees[24].id, kind: 'LEGAL_EXECUTION',
      monthlyInstructedAmount: 3500, instructedTotal: 84000,
      authorityReference: 'บค-2567-001188', startDate: '2024-09-01', status: 'COMPLETED',
      completedAt: '2026-05-01T00:00:00Z', completionAcknowledgedById: 2, completionAcknowledgedAt: now,
      notes: 'ชำระครบยอดตามหมายบังคับคดีแล้ว',
    }),
  ];
}

// ── Payroll input drafts (db.payrollInputDrafts, a Map keyed `${employeeId}-${payrollMonth}`) —
// saving a draft performs no calculation, so this is genuinely fake-able.
export function buildDemoPayrollInputDrafts(employees) {
  return [
    { employeeId: employees[8].id, payrollMonth: '2026-08-01', input: { specialAllowance: 500, note: 'ค่าตำแหน่งเพิ่มชั่วคราว' } },
    { employeeId: employees[12].id, payrollMonth: '2026-08-01', input: { specialAllowance: 0, note: null } },
    { employeeId: employees[2].id, payrollMonth: '2026-07-01', input: { specialAllowance: 1000, note: 'ค่าล่วงเวลาพิเศษที่ยังไม่ปิดงวด' } },
  ];
}
