import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CommissionPage } from './CommissionPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Only `Date` is faked (timers stay real so waitFor/findBy keep working). The manager/CEO page must
// read "now" at render time, in Asia/Bangkok -- a module-level constant or a UTC slice fails these.
function setClock(iso) {
  vi.useRealTimers();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(iso));
}
afterEach(() => { vi.useRealTimers(); });

// covers the new HR payroll-ready table columns (อินเซนทีฟ / โบนัสขายของในสต๊อค) and the sales rep's own
// monthly-summary incentive line, including the manual-INCENTIVE suppression guard.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      commissions: {
        list: vi.fn(),
        payrollReady: vi.fn(),
        createFromDeal: vi.fn(),
        monthlySummary: vi.fn(),
        simulate: vi.fn(),
        pendingApproval: vi.fn(),
        adjustItemWeights: vi.fn(),
        approve: vi.fn(),
        reject: vi.fn(),
        clawback: vi.fn(),
        updateDeductions: vi.fn(),
        // Default empty, same reasoning as tickets.list below: every sales_manager/ceo render fires this effect on
        // mount regardless of what a given test is actually checking, so an unconfigured vi.fn would throw on `.then`
        // in tests that never touch the rep picker at all.
        reps: vi.fn().mockResolvedValue({ reps: [] }),
      },
      tickets: {
        list: vi.fn().mockResolvedValue({ tickets: [] }),
        get: vi.fn(),
      },
      finance: {
        getDeal: vi.fn(),
      },
    },
  };
});

// browser-image-compression genuinely returns a plain Blob with no `.name` -- this mock reproduces that
// faithfully rather than a File, which is exactly the shape that exposed the "blob" filename bug in #498/#504.
vi.mock('browser-image-compression', () => ({
  default: vi.fn((file) => Promise.resolve(new Blob([file], { type: file.type }))),
}));

function renderPage(user) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <CommissionPage user={user} showToast={vi.fn()} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

// The ×1/×2/×3 segmented control may be native radios or role="radio" buttons -- the contract is the ARIA
// state, not the element type.
const isDisabled = (el) => el.disabled === true || el.getAttribute('aria-disabled') === 'true';

const salesUser = { id: 10, employeeId: 10, name: 'พนักงานขาย ทดสอบ', role: 'sales' };
const salesManagerUser = { id: 30, employeeId: 30, name: 'ผู้จัดการฝ่ายขาย ทดสอบ', role: 'sales_manager' };
const ceoUser = { id: 50, employeeId: 50, name: 'CEO ทดสอบ', role: 'ceo' };

function invoiceDetails(overrides = {}) {
  return {
    id: 1,
    invoiceNumber: 'INV-405-0001',
    invoiceDate: '2026-08-01',
    grossAmount: 3210000,
    bankFees: 0,
    suspenseVat: 0,
    transportFee: 0,
    cutFee: 0,
    shortfall: 0,
    withholdingTax: 0,
    overpayment: 0,
    invoiceAttachmentId: 1,
    invoiceAttachmentFileName: 'invoice.pdf',
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
    ...overrides,
  };
}

function saleRecord(overrides = {}) {
  return {
    id: 501,
    kind: 'SALE',
    status: 'APPROVED',
    salesRepId: 10,
    salesRepName: 'พนักงานขาย ทดสอบ',
    submittedById: 10,
    payrollMonth: '2026-08-01',
    // 3,210,000.00 / 1.07 = 3,000,000.00 exactly -- lands on the first INCENTIVE threshold.
    actualReceived: 3210000.0,
    commissionableBase: 3000000.0,
    weightMultiplier: 1,
    approvedById: 2,
    approvedAt: '2026-08-05T00:00:00Z',
    managerApprovedBy: 2,
    managerApprovedByName: 'ผู้จัดการฝ่ายขาย',
    managerApprovedAt: '2026-08-05T00:00:00Z',
    ceoApprovedBy: 3,
    ceoApprovedByName: 'CEO',
    ceoApprovedAt: '2026-08-05T00:00:00Z',
    rejectedById: null,
    rejectedByName: null,
    rejectedAt: null,
    rejectionReason: null,
    cancellationOfId: null,
    cancellationReason: null,
    dealPayableAmountSnapshot: null,
    dealAmountMismatch: false,
    manualAmount: null,
    manualReason: null,
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
    invoiceDetails: invoiceDetails(),
    ...overrides,
  };
}

function manualIncentiveRecord(overrides = {}) {
  return {
    id: 502,
    kind: 'INCENTIVE',
    status: 'APPROVED',
    salesRepId: 10,
    salesRepName: 'พนักงานขาย ทดสอบ',
    submittedById: 3,
    payrollMonth: '2026-08-01',
    actualReceived: 0,
    commissionableBase: 0,
    weightMultiplier: 1,
    approvedById: 3,
    approvedAt: '2026-08-06T00:00:00Z',
    managerApprovedBy: null,
    managerApprovedByName: null,
    managerApprovedAt: null,
    ceoApprovedBy: 3,
    ceoApprovedByName: 'CEO',
    ceoApprovedAt: '2026-08-06T00:00:00Z',
    rejectedById: null,
    rejectedByName: null,
    rejectedAt: null,
    rejectionReason: null,
    cancellationOfId: null,
    cancellationReason: null,
    dealPayableAmountSnapshot: null,
    dealAmountMismatch: false,
    manualAmount: 15000,
    manualReason: 'hand-entered before auto-compute shipped',
    createdAt: '2026-08-06T00:00:00Z',
    updatedAt: '2026-08-06T00:00:00Z',
    invoiceDetails: null,
    ...overrides,
  };
}

async function setMonthInput(value) {
  const input = screen.getByLabelText('รอบเดือน');
  fireEvent.change(input, { target: { value } });
}

// fix/commission-figures-from-backend: the incentive line's SUPPRESSION rule used to be re-implemented
// client-side and was exercised here by feeding raw commission records through it.
describe('CommissionPage — sales rep monthly incentive line renders the server-reported amount (issue #405)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the incentive line when the server reports a positive incentiveAmount', async () => {
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord()] });
    api.commissions.monthlySummary.mockResolvedValue({
      summary: {
        payrollMonth: '2026-08-01',
        salesRepId: 10,
        commissionableBase: 3000000,
        tierCommission: 48750,
        incentiveAmount: 15000,
        manualTotal: 0,
        totalCommission: 63750,
        belowFloor: false,
        tiers: [],
      },
    });

    renderPage(salesUser);

    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    await setMonthInput('2026-08');

    // Redesign: the incentive is one limb of the "ค่าคอมของฉัน" statement ("+ Incentive ข้อ 12"), not a
    // free-floating "อินเซนทีฟ (นอกขั้นบันได)" line. Scoped to the row because ฿15,000.00 can legitimately also
    // appear elsewhere (a receipt row, the tier panel).
    const incentiveRow = await screen.findByTestId('statement-incentive');
    expect(incentiveRow.textContent).toContain('Incentive ข้อ 12');
    expect(incentiveRow.textContent).toContain('฿15,000.00');
  });

  it('hides the incentive line when the server reports incentiveAmount as zero', async () => {
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord(), manualIncentiveRecord()] });
    api.commissions.monthlySummary.mockResolvedValue({
      summary: {
        payrollMonth: '2026-08-01',
        salesRepId: 10,
        commissionableBase: 3000000,
        tierCommission: 48750,
        incentiveAmount: 0,
        manualTotal: 15000,
        totalCommission: 63750,
        belowFloor: false,
        tiers: [],
      },
    });

    renderPage(salesUser);

    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    await setMonthInput('2026-08');

    // Wait for the statement (proves records loaded and the month change took effect) before asserting the
    // suppressed limb is absent.
    expect(await screen.findByTestId('statement-total')).not.toBeNull();
    expect(screen.queryByTestId('statement-incentive')).toBeNull();
  });
});

// fix/commission-figures-from-backend: the monthly tier panel is now driven entirely by the SERVER-computed
// monthly summary, replacing a former client-side re-implementation of the tier math that could silently
// desynchronise from a DB tier-config change.
describe('CommissionPage — monthly tier summary comes from the server, not client math (regression guard)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the server-computed monthly summary, not a client-recomputed figure', async () => {
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord()] });
    api.commissions.monthlySummary.mockResolvedValue({
      summary: {
        payrollMonth: '2026-08-01',
        salesRepId: 10,
        commissionableBase: 1200000,
        tierCommission: 99999.99,
        incentiveAmount: 0,
        manualTotal: 0,
        totalCommission: 99999.99,
        belowFloor: false,
        tiers: [],
      },
    });

    renderPage(salesUser);

    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    await setMonthInput('2026-08');

    // Scoped to statement rows: 99,999.99 is BOTH the tier limb and the total here, so a bare getByText would now
    // match twice. The point is unchanged -- the exact SERVER figure renders, not one recomputed from `records`.
    expect((await screen.findByTestId('statement-tier')).textContent).toContain('฿99,999.99');
    expect(screen.getByTestId('statement-total').textContent).toContain('฿99,999.99');
    expect(screen.getByTestId('statement-base').textContent).toContain('฿1,200,000.00');
  });
});

// P0 fix (fix/commission-approved-record-immutable): CommissionService#updateDeductions refuses an
// APPROVED record outright (see CommissionApprovedRecordImmutableIntegrationTest) -- per V102's
// census every one of prod's 1,132 commission records is APPROVED, so an unguarded pencil would 409
// on essentially every row. Redesign: the pencil ("แก้ไขค่าหัก") now lives INSIDE the expanded
// detail panel of an invoice, and is offered only when the viewer can review that record at its
// current stage (sales_manager at SUBMITTED, ceo at MANAGER_APPROVED).
describe('CommissionPage — edit-deductions pencil gated on the viewer\'s stage (fix/commission-approved-record-immutable)', () => {
  const allRecords = () => screen.findByRole('region', { name: 'รายการทั้งหมดในรอบนี้' });
  const openRow = async (invoice) => {
    fireEvent.click(within(await allRecords()).getByRole('button', { name: `ดูรายละเอียด ${invoice}` }));
  };
  const submitted = (id, invoice, over = {}) => saleRecord({
    id,
    status: 'SUBMITTED',
    approvedById: null, approvedAt: null,
    managerApprovedBy: null, managerApprovedByName: null, managerApprovedAt: null,
    ceoApprovedBy: null, ceoApprovedByName: null, ceoApprovedAt: null,
    invoiceDetails: invoiceDetails({ id, invoiceNumber: invoice }),
    ...over,
  });
  const dtoOf = (record) => ({
    commission: record, ticketCode: 'TCK-0001', customerName: 'ลูกค้า', items: [],
    effectiveWeight: 1, weightedCommissionableBase: 3000000, estimatedCommission: 1,
  });

  beforeEach(() => {
    vi.clearAllMocks();
    setClock('2026-08-15T05:00:00Z');
    api.commissions.pendingApproval.mockResolvedValue({ commissions: [] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: null });
    api.commissions.reps.mockResolvedValue({ reps: [] });
  });

  it('an APPROVED record: the expanded panel has NO edit pencil and the table has no approve/reject, but clawback stays', async () => {
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord()] }); // default status: APPROVED
    renderPage(salesManagerUser);

    await openRow('INV-405-0001'); // waits for the row itself, so a slow load can never fake the absences below
    await screen.findByTestId('detail-panel-501');

    expect(screen.queryByRole('button', { name: 'แก้ไขค่าหัก' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'อนุมัติ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ไม่อนุมัติ' })).toBeNull();
    // Not "hide everything": the sanctioned correction path for an APPROVED SALE record stays.
    expect(screen.getByRole('button', { name: 'บันทึกหักคืน' })).not.toBeNull();
  });

  it('a SUBMITTED record reviewed by the sales_manager: the pencil opens the edit form INLINE in that expanded row, not at the page bottom', async () => {
    const rec = submitted(601, 'INV-405-0601');
    api.commissions.list.mockResolvedValue({ commissions: [rec] });
    api.commissions.pendingApproval.mockResolvedValue({ commissions: [dtoOf(rec)] });
    renderPage(salesManagerUser);

    fireEvent.click(within(await screen.findByTestId('queue-row-601')).getByRole('button', { name: 'ดูรายละเอียด INV-405-0601' }));
    const panel = await screen.findByTestId('detail-panel-601');
    fireEvent.click(within(panel).getByRole('button', { name: 'แก้ไขค่าหัก' }));

    const heading = await screen.findByRole('heading', { name: /แก้ไขข้อมูลใบกำกับ INV-405-0601/ });
    expect(panel.contains(heading)).toBe(true);
  });

  it('CANNOT REACH: the CEO gets no pencil on a SUBMITTED (manager-stage) record, only on a MANAGER_APPROVED one', async () => {
    const mgrStage = submitted(601, 'INV-405-0601');
    const ceoStage = submitted(602, 'INV-405-0602', { status: 'MANAGER_APPROVED' });
    api.commissions.list.mockResolvedValue({ commissions: [mgrStage, ceoStage] });
    api.commissions.pendingApproval.mockResolvedValue({ commissions: [dtoOf(ceoStage)] });
    renderPage(ceoUser);

    await openRow('INV-405-0601');
    const panel601 = await screen.findByTestId('detail-panel-601');
    expect(within(panel601).queryByRole('button', { name: 'แก้ไขค่าหัก' })).toBeNull();

    await openRow('INV-405-0602');
    const panel602 = await screen.findByTestId('detail-panel-602');
    expect(within(panel602).getByRole('button', { name: 'แก้ไขค่าหัก' })).not.toBeNull();
  });
});

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Commission approval redesign (sales_manager + ceo; sales/hr/account pages are unchanged).
// Prototype: "หลัง (ใหม่)" of commission-approval-prototype.html. One payroll round at a time:
// cycle header with ‹ › stepper -> progress strip -> carry-over callout -> "รอคุณอนุมัติ" queue ->
// "สรุปรายคน" table -> "รายการทั้งหมดในรอบนี้". Queue data = GET /pending-approval (the backend
// answers per role: manager -> SUBMITTED, ceo -> MANAGER_APPROVED), filtered client-side to the
// shown month. MOCK-DRIVEN: proves plumbing/rendering only; the role split of the endpoint itself is
// CommissionPendingItemWeightIntegrationTest's job (real Postgres). Clock: only Date is faked.
//
// Test hooks the implementation must provide (everything else is found by role / accessible name):
//   data-testid  queue-row-<recordId>   one queue row          detail-panel-<recordId>  the expanded panel
//                cycle-box-<STATUS>     progress-strip boxes   carry-over-callout       the carry-over banner
//   aria-current="true" on the progress-strip box that is the viewer's own stage.
// ─────────────────────────────────────────────────────────────────────────────────────────────
describe('CommissionPage — payroll-round approval page (sales_manager / ceo)', () => {
  const REP_A = { id: 10, name: 'สมชาย ใจดี' };
  const REP_B = { id: 11, name: 'วิภา รัตนกุล' };
  const REP_C = { id: 12, name: 'กมลชนก พูลสุข' };
  const noApprovals = {
    approvedById: null, approvedAt: null,
    managerApprovedBy: null, managerApprovedByName: null, managerApprovedAt: null,
    ceoApprovedBy: null, ceoApprovedByName: null, ceoApprovedAt: null,
  };
  const inv = (id, invoiceNumber, gross = 3210000) => invoiceDetails({ id, invoiceNumber, grossAmount: gross });
  const submitted = (id, invoiceNumber, rep, over = {}) => saleRecord({
    id, status: 'SUBMITTED', salesRepId: rep.id, salesRepName: rep.name, payrollMonth: '2026-08-01',
    ...noApprovals, invoiceDetails: inv(id, invoiceNumber), ...over,
  });

  // August 2026 (the faked "today"). Weighted bases: 701 = 3,000,000 x 1.6 = 4,800,000; 704 = 500,000 x 2.
  const r701 = submitted(701, 'INV-PEND-0701', REP_A, { effectiveWeightMultiplier: 1.6, sourceTicketId: 77 });
  const r702 = submitted(702, 'INV-PEND-0702', REP_B, { commissionableBase: 1000000, actualReceived: 1070000, dealAmountMismatch: true, invoiceDetails: inv(702, 'INV-PEND-0702', 1070000) });
  const r703 = saleRecord({
    id: 703, status: 'MANAGER_APPROVED', salesRepId: REP_A.id, salesRepName: REP_A.name, payrollMonth: '2026-08-01',
    commissionableBase: 2000000, actualReceived: 2140000, ...noApprovals,
    managerApprovedBy: 2, managerApprovedByName: 'สมหญิง ผจก.', managerApprovedAt: '2026-08-05T05:00:00Z',
    invoiceDetails: inv(703, 'INV-MA-0703', 2140000),
  });
  const r704 = saleRecord({ id: 704, sourceTicketId: 88, salesRepId: REP_B.id, salesRepName: REP_B.name, commissionableBase: 500000, actualReceived: 535000, weightMultiplier: 2, invoiceDetails: inv(704, 'INV-OK-0704', 535000) });
  const r705 = saleRecord({ id: 705, salesRepId: REP_A.id, salesRepName: REP_A.name, commissionableBase: 300000, actualReceived: 321000, invoiceDetails: inv(705, 'INV-OK-0705', 321000) });
  const r601 = submitted(601, 'INV-JUL-0601', REP_C, { payrollMonth: '2026-07-01', commissionableBase: 500000 }); // carried over from July
  const r901 = submitted(901, 'INV-SEP-0901', REP_A, { payrollMonth: '2026-09-01' }); // a LATER round: neither queue nor callout in August
  const BY_MONTH = { '2026-08': [r701, r702, r703, r704, r705], '2026-07': [r601], '2026-09': [r901] };

  const stockLine = (itemId, description, qty, qtyFromStock) => ({ itemId, description, qty, qtyFromStock, weightMultiplier: 1 });
  const dtoOf = (record, over = {}) => ({
    commission: record,
    ticketCode: 'TCK-0077',
    customerName: 'บริษัท รออนุมัติ จำกัด',
    items: [stockLine(11, 'PADANA 60x60 ผิวเงา', 120, 30), stockLine(12, 'PADANA 30x60 ผิวด้าน', 40, 0)],
    effectiveWeight: 1,
    weightedCommissionableBase: 3000000,
    estimatedCommission: 1000,
    ...over,
  });
  const dto701 = dtoOf(r701, { effectiveWeight: 1.6, weightedCommissionableBase: 4800000, estimatedCommission: 95000.5 });
  const dto702 = dtoOf(r702, { ticketCode: 'TCK-0078', customerName: 'บริษัท สอง จำกัด', items: [stockLine(21, 'PADANA 80x80 ผิวด้าน', 10, 0)], weightedCommissionableBase: 1000000, estimatedCommission: 1234.5 });
  const dto703 = dtoOf(r703, { ticketCode: 'TCK-0079', customerName: 'บริษัท สาม จำกัด', items: [stockLine(31, 'PADANA 90x90 ผิวเงา', 10, 10)], weightedCommissionableBase: 2000000, estimatedCommission: 4321.09 });
  const dto601 = dtoOf(r601, { ticketCode: 'TCK-0060', weightedCommissionableBase: 500000, estimatedCommission: 100 });
  const dto901 = dtoOf(r901, { ticketCode: 'TCK-0090', weightedCommissionableBase: 3000000, estimatedCommission: 100 });

  const repSummary = (salesRepId) => ({
    payrollMonth: '2026-08-01', salesRepId, rawCommissionableBase: 0, weightUpliftBase: 0,
    commissionableBase: salesRepId === 10 ? 7100000 : 2000000,
    tierCommission: 1, incentiveAmount: 0, stockBonusAmount: 0, teamOverrideAmount: 0,
    companyCommissionableBase: null, teamOverrideThresholdBase: null, teamOverrideRatePercent: null,
    manualTotal: 0, totalCommission: salesRepId === 10 ? 12345.67 : 6789.01, belowFloor: false, tiers: [],
  });

  function stub(pending) {
    api.commissions.list.mockImplementation(async ({ payrollMonth }) => ({ commissions: BY_MONTH[payrollMonth] ?? [] }));
    api.commissions.pendingApproval.mockResolvedValue({ commissions: pending });
    api.commissions.reps.mockResolvedValue({ reps: [REP_A, REP_B, REP_C] });
    api.commissions.monthlySummary.mockImplementation(async ({ salesRepId }) => ({ summary: repSummary(salesRepId) }));
  }
  const managerPending = [dto701, dto702, dto601, dto901];

  function renderAt(user, url = '/commissions') {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    return render(
      <MemoryRouter initialEntries={[url]}>
        <QueryClientProvider client={queryClient}>
          <CommissionPage user={user} showToast={vi.fn()} />
        </QueryClientProvider>
      </MemoryRouter>,
    );
  }

  beforeEach(() => {
    vi.clearAllMocks();
    setClock('2026-08-15T05:00:00Z');
    stub(managerPending);
  });

  // ── 4. the queue ──────────────────────────────────────────────────────────────────────────
  it('sales_manager queue: this round\'s SUBMITTED items only, one compact row each with weighted base, weight badge, estimate and อนุมัติ / ไม่อนุมัติ', async () => {
    renderAt(salesManagerUser);
    const row701 = await screen.findByTestId('queue-row-701');
    const row702 = screen.getByTestId('queue-row-702');

    expect(await screen.findByRole('heading', { name: /^รอคุณอนุมัติ\s*2$/ })).not.toBeNull();
    expect(api.commissions.pendingApproval).toHaveBeenCalledWith(); // month filtering is client-side
    // only the shown month's own-stage items: 703/704/705 belong to other stages
    ['703', '704', '705'].forEach((id) => expect(screen.queryByTestId(`queue-row-${id}`)).toBeNull());

    expect(row701.textContent).toContain('สมชาย ใจดี');
    expect(row701.textContent).toContain('INV-PEND-0701');
    expect(row701.textContent).toContain('TCK-0077');
    expect(row701.textContent).toContain('บริษัท รออนุมัติ จำกัด');
    expect(row701.textContent).toContain('฿4,800,000.00'); // ฐานค่าคอม (หลังถ่วง) = the SERVER's weighted base
    expect(row701.textContent).toContain('×1.60'); // weight badge only when != 1
    expect(row701.textContent).toContain('฿95,000.50'); // ค่าคอมโดยประมาณ = estimatedCommission
    expect(within(row701).getByRole('button', { name: 'ดูรายละเอียด INV-PEND-0701' }).getAttribute('aria-expanded')).toBe('false');
    expect(within(row701).getByRole('button', { name: 'อนุมัติ' })).not.toBeNull();
    expect(within(row701).getByRole('button', { name: 'ไม่อนุมัติ' })).not.toBeNull();

    expect(row702.textContent).toContain('ยอดต่างจากยอดที่เรียกเก็บ'); // mismatch badge
    expect(row702.textContent).not.toMatch(/×\d/); // weight 1 -> no badge

    // removed: CEO tabs, the weighting-desk cards, the rep <select>
    expect(screen.queryByRole('tab')).toBeNull();
    expect(screen.queryByTestId('pending-entry-701')).toBeNull();
    expect(screen.queryByLabelText('เลือกพนักงานขาย')).toBeNull();
    expect(screen.queryAllByRole('combobox')).toHaveLength(0);
  });

  it('ceo queue: the MANAGER_APPROVED items the endpoint returns for her, with "ผู้จัดการอนุมัติ: <name> · <date>" in place of deal/customer; no tabs', async () => {
    stub([dto703]);
    renderAt(ceoUser);
    const row = await screen.findByTestId('queue-row-703');

    expect(await screen.findByRole('heading', { name: /^รอคุณอนุมัติ\s*1$/ })).not.toBeNull();
    expect(row.textContent).toContain('INV-MA-0703');
    expect(row.textContent).toContain('ผู้จัดการอนุมัติ: สมหญิง ผจก.');
    expect(row.textContent).toContain('ส.ค. 2569');
    expect(row.textContent).toContain('฿2,000,000.00');
    expect(row.textContent).toContain('฿4,321.09');
    expect(row.textContent).not.toContain('TCK-0079');
    expect(within(row).getByRole('button', { name: 'อนุมัติ' })).not.toBeNull();
    expect(within(row).getByRole('button', { name: 'ไม่อนุมัติ' })).not.toBeNull();
    expect(screen.queryByRole('tab')).toBeNull(); // ทั้งหมด / รออนุมัติ tabs are gone
  });

  it.each([
    ['sales_manager', salesManagerUser, 701],
    ['ceo', ceoUser, 703],
  ])('%s: อนุมัติ still confirms first, then approves the record and reloads the queue', async (_role, user, id) => {
    stub(user.role === 'ceo' ? [dto703] : managerPending);
    api.commissions.approve.mockResolvedValue({});
    renderAt(user);
    const row = await screen.findByTestId(`queue-row-${id}`);
    const loads = api.commissions.pendingApproval.mock.calls.length;

    fireEvent.click(within(row).getByRole('button', { name: 'อนุมัติ' }));
    expect(api.commissions.approve).not.toHaveBeenCalled(); // nothing before the confirm dialog
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'อนุมัติ' }));

    await waitFor(() => expect(api.commissions.approve).toHaveBeenCalledWith(id));
    await waitFor(() => expect(api.commissions.pendingApproval.mock.calls.length).toBeGreaterThan(loads));
  });

  it('ไม่อนุมัติ still demands a reason before anything is sent', async () => {
    api.commissions.reject.mockResolvedValue({});
    renderAt(salesManagerUser);
    fireEvent.click(within(await screen.findByTestId('queue-row-701')).getByRole('button', { name: 'ไม่อนุมัติ' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByLabelText('เหตุผลการปฏิเสธ')).not.toBeNull();
    expect(isDisabled(within(dialog).getByRole('button', { name: 'ยืนยัน' }))).toBe(true);
    expect(api.commissions.reject).not.toHaveBeenCalled();

    fireEvent.change(within(dialog).getByLabelText('เหตุผลการปฏิเสธ'), { target: { value: 'ยอดไม่ตรง' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยืนยัน' }));
    await waitFor(() => expect(api.commissions.reject).toHaveBeenCalledWith(701, { reviewerNote: 'ยอดไม่ตรง' }));
  });

  // ── review fixes ──────────────────────────────────────────────────────────────────────────
  // A sales_manager's manual commission is inserted MANAGER_APPROVED. pending-approval only carries
  // SALE records, so the CEO's queue also takes the shown month's manual MANAGER_APPROVED records
  // from the month's list -- otherwise nothing could ever approve them.
  const manual801 = saleRecord({
    id: 801, kind: 'ADJUSTMENT', status: 'MANAGER_APPROVED', salesRepId: REP_A.id, salesRepName: REP_A.name,
    payrollMonth: '2026-08-01', commissionableBase: 0, actualReceived: 0, manualAmount: -2500, manualReason: 'หักค่าเสียหาย',
    invoiceDetails: null, ...noApprovals, managerApprovedBy: 2, managerApprovedByName: 'สมหญิง ผจก.', managerApprovedAt: '2026-08-05T05:00:00Z',
  });
  const withManual = () => api.commissions.list.mockImplementation(async ({ payrollMonth }) => ({
    commissions: payrollMonth === '2026-08' ? [...BY_MONTH['2026-08'], manual801] : [],
  }));

  it('the CEO sees a manual MANAGER_APPROVED entry in her queue (no weights), and approves it through the same confirm dialog', async () => {
    stub([dto703]);
    withManual();
    api.commissions.approve.mockResolvedValue({});
    renderAt(ceoUser);
    const row = await screen.findByTestId('queue-row-801');

    expect(await screen.findByRole('heading', { name: /^รอคุณอนุมัติ\s*2$/ })).not.toBeNull();
    expect(row.textContent).toContain('สมชาย ใจดี');
    expect(row.textContent).toContain('ปรับปรุง/รับช่วงงาน · หักค่าเสียหาย');
    expect(row.textContent).toMatch(/2,500\.00/);
    expect(row.querySelector('.text-danger')).not.toBeNull(); // negative amount is red

    fireEvent.click(within(row).getByRole('button', { name: /^ดูรายละเอียด/ }));
    const panel = await screen.findByTestId('detail-panel-801');
    expect(within(panel).queryByRole('region', { name: 'สินค้าและน้ำหนัก' })).toBeNull();
    expect(within(panel).getByRole('region', { name: 'สถานะและเอกสาร' })).not.toBeNull();

    fireEvent.click(within(row).getByRole('button', { name: 'อนุมัติ' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'อนุมัติ' }));
    await waitFor(() => expect(api.commissions.approve).toHaveBeenCalledWith(801));
  });

  it('CANNOT REACH: the sales_manager does not get the manager-approved manual entry in her queue', async () => {
    stub(managerPending);
    withManual();
    renderAt(salesManagerUser);
    await screen.findByTestId('queue-row-701');
    expect(screen.queryByTestId('queue-row-801')).toBeNull();
  });
});

// H1 lockdown: account reads a deal through the finance view only (api.tickets.get 403s for it).
describe('CommissionPage — account create-from-deal: deal must be CLOSED_PAID', () => {
  it('a finance-view deal that has not reached CLOSED_PAID is refused with the stage in the message', async () => {
    api.finance.getDeal.mockResolvedValue({
      deal: { id: 42, code: 'TCK-0042', customerName: 'บริษัท ทดสอบ จำกัด', salesStage: 'DELIVERY_SCHEDULING', money: { amountPayable: 3210000, amountPayableExVat: 3000000 } },
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <CommissionPage user={{ id: 20, employeeId: 20, name: 'บัญชี ทดสอบ', role: 'account' }} showToast={vi.fn()} />
        </QueryClientProvider>
      </MemoryRouter>,
    );
    fireEvent.change(screen.getByLabelText(/เลขที่งาน \(ID\)/), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: /โหลดข้อมูลงาน/ }));
    const msg = (await screen.findByText(/ยังไม่ถึงขั้นตอนปิดงาน/)).textContent;
    expect(msg).toContain('นัดส่งสินค้า / นัดรับเงินส่วนที่เหลือ');
    expect(msg).not.toContain('[object Object]');
  });
});
