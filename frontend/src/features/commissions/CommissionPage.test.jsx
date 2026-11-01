import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

// Issue #405: covers the new HR payroll-ready table columns (อินเซนทีฟ / โบนัสขายของในสต๊อค) and
// the sales rep's own monthly-summary incentive line, including the manual-INCENTIVE suppression
// guard. Mirrors the real DTO shape CommissionService#payrollReadySummary now returns
// (incentiveAmount/stockBonusAmount, additive fields) and the real CommissionRecord shape list()
// returns, so this test exercises CommissionPage exactly as the real API would drive it.
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
        // Default empty, same reasoning as tickets.list below: every sales_manager/ceo render
        // (canCreateManual) fires this effect on mount regardless of what a given test is
        // actually checking, so an unconfigured vi.fn() (resolving to undefined) would throw on
        // `.then()` in tests that never touch the rep picker at all.
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

// browser-image-compression genuinely returns a plain Blob with no `.name` -- this mock
// reproduces that faithfully rather than a File, which is exactly the shape that exposed the
// "blob" filename bug in #498/#504. A mock that quietly upgrades the library's real return type
// would make this test pass whether or not the component re-wraps it.
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

// The ×1/×2/×3 segmented control may be native radios or role="radio" buttons -- the contract is
// the ARIA state, not the element type.
const isDisabled = (el) => el.disabled === true || el.getAttribute('aria-disabled') === 'true';

const hrUser = { id: 900, employeeId: 900, name: 'HR Test', role: 'hr' };
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

describe('CommissionPage — HR payroll-ready table (issue #405)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the อินเซนทีฟ and โบนัสขายของในสต๊อค columns with the DTO values', async () => {
    api.commissions.payrollReady.mockResolvedValue({
      summary: {
        payrollMonth: '2026-08-01',
        status: 'PAYROLL_READY',
        totalCommissionableBase: 3246381.33,
        totalCommissionAmount: 73757.39,
        totalIncentiveAmount: 15000,
        totalStockBonusAmount: 2000,
        salesReps: [{
          salesRepId: 10,
          salesRepName: 'เจนเนตร',
          commissionableBase: 3246381.33,
          commissionAmount: 73757.39,
          manualAdjustmentAmount: 0,
          incentiveAmount: 15000,
          stockBonusAmount: 2000,
        }],
      },
    });

    renderPage(hrUser);

    expect(await screen.findByText('อินเซนทีฟ')).not.toBeNull();
    expect(screen.getByText('โบนัสขายของในสต๊อค')).not.toBeNull();
    expect(await screen.findByText('เจนเนตร')).not.toBeNull();
    expect(screen.getByText('฿15,000.00')).not.toBeNull();
    expect(screen.getByText('฿2,000.00')).not.toBeNull();
  });

  it('shows a zero stock bonus (all-zero column, not hidden) when the feature is config-gated off', async () => {
    api.commissions.payrollReady.mockResolvedValue({
      summary: {
        payrollMonth: '2026-08-01',
        status: 'PAYROLL_READY',
        totalCommissionableBase: 3246381.33,
        totalCommissionAmount: 71757.39,
        totalIncentiveAmount: 15000,
        totalStockBonusAmount: 0,
        salesReps: [{
          salesRepId: 10,
          salesRepName: 'เจนเนตร',
          commissionableBase: 3246381.33,
          commissionAmount: 71757.39,
          manualAdjustmentAmount: 0,
          incentiveAmount: 15000,
          stockBonusAmount: 0,
        }],
      },
    });

    renderPage(hrUser);

    expect(await screen.findByText('โบนัสขายของในสต๊อค')).not.toBeNull();
    // The column header renders even though every value is zero -- not conditionally hidden.
    expect(screen.getByText('฿0.00')).not.toBeNull();
  });
});

// fix/commission-figures-from-backend: the incentive line's SUPPRESSION rule (an approved manual
// INCENTIVE, or a payroll month before the 2026-08-01 fix-forward effective date, both zero the
// auto-computed limb) used to be re-implemented client-side and was exercised here by feeding raw
// commission records through it. That computation now lives entirely in
// CommissionService#monthlySummary, proven by the real-DB CommissionMonthlySummaryIntegrationTest
// (backend) and, for the underlying ladder/suppression math itself, by
// CommissionIncentiveStockBonusIntegrationTest and CommissionCalculatorTest. What remains here is
// purely a RENDERING contract: the panel shows the incentive line when the server reports a
// positive incentiveAmount, and hides it when the server reports zero (for any reason).
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

    // Redesign: the incentive is one limb of the "ค่าคอมของฉัน" statement ("+ Incentive ข้อ 12"),
    // not a free-floating "อินเซนทีฟ (นอกขั้นบันได)" line. Scoped to the row because ฿15,000.00
    // can legitimately also appear elsewhere (a receipt row, the tier panel).
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

    // Wait for the statement (proves records loaded and the month change took effect) before
    // asserting the suppressed limb is absent.
    expect(await screen.findByTestId('statement-total')).not.toBeNull();
    expect(screen.queryByTestId('statement-incentive')).toBeNull();
  });
});

// fix/commission-figures-from-backend (#548-style V81 regression guard): the monthly tier panel
// is now driven entirely by the SERVER-computed monthly summary (CommissionService
// #monthlySummary), replacing a former client-side re-implementation of the tier math that could
// silently desynchronise from a DB tier-config change (the V81 tier-13 rate correction is the
// case on record — see CLAUDE.md). These two cases stub figures deliberately unreachable by any
// tier table (no combination of the seeded 0.25%-3.25% bands on any base yields exactly
// 99,999.99), and assert the panel renders that exact server figure — not one recomputed
// client-side from `records`. On unmodified (pre-refactor) code these failed: the panel rendered
// a JS-recomputed figure derived from `saleRecord()` instead — see the C1 baseline evidence in
// the PR.
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

    // Scoped to statement rows: 99,999.99 is BOTH the tier limb and the total here, so a bare
    // getByText would now match twice. The point is unchanged -- the exact SERVER figure renders,
    // not one recomputed from `records`.
    expect((await screen.findByTestId('statement-tier')).textContent).toContain('฿99,999.99');
    expect(screen.getByTestId('statement-total').textContent).toContain('฿99,999.99');
    expect(screen.getByTestId('statement-base').textContent).toContain('฿1,200,000.00');
  });

  it('follows the server figure when it changes -- proves the render is wired to the DTO, not a frozen snapshot', async () => {
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord()] });
    api.commissions.monthlySummary.mockResolvedValue({
      summary: {
        payrollMonth: '2026-08-01',
        salesRepId: 10,
        commissionableBase: 654321.09,
        tierCommission: 4567.89,
        incentiveAmount: 0,
        manualTotal: 0,
        totalCommission: 4567.89,
        belowFloor: false,
        tiers: [],
      },
    });

    renderPage(salesUser);

    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    await setMonthInput('2026-08');

    expect((await screen.findByTestId('statement-tier')).textContent).toContain('฿4,567.89');
    expect(screen.getByTestId('statement-total').textContent).toContain('฿4,567.89');
    expect(screen.getByTestId('statement-base').textContent).toContain('฿654,321.09');
  });

  // The three cases above all stub `tiers: []` (what mock mode returns, since it has no DB tier
  // config), which means they never exercise the per-tier TABLE itself. Against the real backend
  // `tiers` is always populated from sales.tier_config, so that render path needs its own cover --
  // in particular `Number(row.ratePercent).toFixed(2)`, which exists because a Java BigDecimal can
  // reach JSON as either a number or a string, and the string form would throw on a bare
  // `.toFixed`. Both forms are asserted here on purpose.
  it('renders the server-supplied per-tier rows, with a numeric AND a string ratePercent', async () => {
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord()] });
    api.commissions.monthlySummary.mockResolvedValue({
      summary: {
        payrollMonth: '2026-08-01',
        salesRepId: 10,
        commissionableBase: 300000,
        tierCommission: 875.5,
        incentiveAmount: 0,
        manualTotal: 0,
        totalCommission: 875.5,
        belowFloor: false,
        tiers: [
          { tierNumber: 1, lowerBound: 0, upperBound: 250000, ratePercent: 0.25, highRoller: false, commission: 625 },
          // ratePercent as a STRING, and the open-ended top tier (upperBound null -> "ขึ้นไป").
          { tierNumber: 2, lowerBound: 250000, upperBound: null, ratePercent: '0.5000', highRoller: true, commission: 250.5 },
          // A band the base never reached: commission 0 -> not listed in the disclosure.
          { tierNumber: 3, lowerBound: 500000, upperBound: 750000, ratePercent: 0.75, highRoller: false, commission: 0 },
        ],
      },
    });

    renderPage(salesUser);

    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    await setMonthInput('2026-08');

    // Redesign: the bands are a disclosure under the statement's "ค่าคอมตามขั้นบันได" limb, and it
    // lists only the bands actually reached (commission != 0) -- hence the zero-commission band
    // added to the fixture below, which must NOT appear.
    const disclosure = await screen.findByRole('button', { name: /ขั้นบันไดที่ได้รับ/ });
    expect(disclosure.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(disclosure);
    expect(disclosure.getAttribute('aria-expanded')).toBe('true');

    // Rates come straight from the server rows, formatted but never recomputed.
    expect(await screen.findByText('0.25%')).not.toBeNull();
    expect(screen.getByText('0.50%')).not.toBeNull();
    expect(screen.getByText('฿625.00')).not.toBeNull();
    expect(screen.getByText('฿250.50')).not.toBeNull();
    // The open-ended top tier renders its bound as "ขึ้นไป", not "null".
    expect(screen.getByText(/ขึ้นไป/)).not.toBeNull();
    // Only bands actually reached are listed.
    expect(screen.queryByText('0.75%')).toBeNull();
    // The mock-mode empty state must NOT appear when the server did supply rows.
    expect(screen.queryByText('ไม่มีรายละเอียดขั้นบันไดค่าคอมให้แสดงในขณะนี้')).toBeNull();
  });
});

const accountUser = { id: 20, employeeId: 20, name: 'บัญชี ทดสอบ', role: 'account' };

// The FinanceDealDto shape (backend finance/FinanceDealDto.java): the amount lives under `money`.
function financeDeal(overrides = {}) {
  return {
    id: 42,
    code: 'TCK-0042',
    customerName: 'บริษัท ทดสอบ จำกัด',
    salesStage: 'CLOSED_PAID',
    money: { amountPayable: 3210000, amountPayableExVat: 3000000 },
    ...overrides,
  };
}

async function loadEligibleDeal(ticketId = '42') {
  fireEvent.change(screen.getByLabelText(/เลขที่ Ticket ID/), { target: { value: ticketId } });
  fireEvent.click(screen.getByRole('button', { name: /โหลดข้อมูลดีล/ }));
  await screen.findByText('TCK-0042');
}

// Found in #498/#504 (same client-side Blob-vs-File defect, different feature): imageCompression()
// returns a plain Blob, and FormData built from a bare Blob has no filename to send, so the
// multipart part's filename defaults to the literal string "blob" per spec. This one matters more
// than the other instances: createFromDeal dual-writes the uploaded file as the ticket's real tax
// invoice attachment (AttachType.INVOICE), which gates CONFIRM_CLOSE -- so the corrupted filename
// hit an actual business document, not just a photo used for internal reference.
describe('CommissionPage — account create-from-deal tax invoice upload', () => {
  let showToast;

  beforeEach(() => {
    vi.clearAllMocks();
    showToast = vi.fn();
    // H1 lockdown: account reads a deal through the finance view only (api.tickets.get 403s for it).
    api.finance.getDeal.mockResolvedValue({ deal: financeDeal() });
    api.tickets.get.mockRejectedValue(new Error('account must not call api.tickets.get'));
    api.commissions.createFromDeal.mockResolvedValue({ commission: { id: 900, invoiceDetails: invoiceDetails() } });
  });

  function renderAccountPage() {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const utils = render(
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <CommissionPage user={accountUser} showToast={showToast} />
        </QueryClientProvider>
      </MemoryRouter>,
    );
    return { ...utils, queryClient };
  }

  it('the Linked Deal lookup goes through api.finance.getDeal for account, never api.tickets.get', async () => {
    renderAccountPage();
    await loadEligibleDeal('42');
    expect(api.finance.getDeal).toHaveBeenCalledWith(42);
    expect(api.tickets.get).not.toHaveBeenCalled();
    // fields are mapped from the finance view: code, customer, and the amount from `money`
    expect(screen.getByText('TCK-0042')).not.toBeNull();
    expect(screen.getByText(/฿3,210,000.00/)).not.toBeNull();
    expect((screen.getByLabelText(/ยอดรับจริง/)).value).toBe('3210000');
  });

  it('a finance-view deal that has not reached CLOSED_PAID is refused with the stage in the message', async () => {
    api.finance.getDeal.mockResolvedValue({ deal: financeDeal({ salesStage: 'DELIVERY_SCHEDULING' }) });
    renderAccountPage();
    fireEvent.change(screen.getByLabelText(/เลขที่ Ticket ID/), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: /โหลดข้อมูลดีล/ }));
    const msg = (await screen.findByText(/ยังไม่ถึงขั้นตอนปิดงาน/)).textContent;
    // dealStageLabel() returns { label, tone }, never a string: interpolating the call itself
    // renders "[object Object]" where the Thai stage name belongs. Pin the rendered text.
    expect(msg).toContain('นัดส่งสินค้า / นัดรับเงินส่วนที่เหลือ');
    expect(msg).not.toContain('[object Object]');
  });

  it('a stage with no Thai copy falls back to the raw code, still not "[object Object]"', async () => {
    // dealStageLabel()'s fallback limb returns an object too, so a fix that only handles known
    // stages would leak "[object Object]" here.
    api.finance.getDeal.mockResolvedValue({ deal: financeDeal({ salesStage: 'FUTURE_STAGE' }) });
    renderAccountPage();
    fireEvent.change(screen.getByLabelText(/เลขที่ Ticket ID/), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: /โหลดข้อมูลดีล/ }));
    const msg = (await screen.findByText(/ยังไม่ถึงขั้นตอนปิดงาน/)).textContent;
    expect(msg).toContain('FUTURE_STAGE');
    expect(msg).not.toContain('[object Object]');
  });

  it('a successful create-from-deal invalidates the finance deal cache too, so the commission CTA is not stale', async () => {
    const { queryClient } = renderAccountPage();
    const spy = vi.spyOn(queryClient, 'invalidateQueries');
    await loadEligibleDeal();
    fireEvent.change(screen.getByLabelText(/เลขที่ใบกำกับ/), { target: { value: 'INV-0042' } });
    const original = new File(['x'], 'tax-invoice-0042.jpg', { type: 'image/jpeg' });
    fireEvent.change(document.getElementById('commission-invoice-file'), { target: { files: [original] } });
    fireEvent.change(screen.getByLabelText(/ยอดรับจริง/), { target: { value: '3210000' } });
    fireEvent.submit(screen.getByRole('button', { name: 'บันทึกและสร้างคำขอค่าคอม' }).closest('form'));
    await waitFor(() => expect(api.commissions.createFromDeal).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(spy).toHaveBeenCalledWith({ queryKey: ['finance'] }));
  });

  it('re-wraps the compressed invoice image so createFromDeal receives the original filename, not "blob"', async () => {
    renderAccountPage();
    await loadEligibleDeal();

    fireEvent.change(screen.getByLabelText(/เลขที่ใบกำกับ/), { target: { value: 'INV-0042' } });
    const original = new File(['fake-jpeg-bytes'], 'tax-invoice-0042.jpg', { type: 'image/jpeg' });
    fireEvent.change(document.getElementById('commission-invoice-file'), { target: { files: [original] } });
    fireEvent.change(screen.getByLabelText(/ยอดรับจริง/), { target: { value: '3210000' } });

    // fireEvent.click on the submit button runs jsdom's native constraint validation first,
    // which (unlike a real browser) does not reliably see the file input as satisfied after a
    // synthetic fireEvent.change -- dispatching submit directly on the form exercises the same
    // onSubmit={submitFromDeal} handler without that jsdom-only false negative.
    fireEvent.submit(screen.getByRole('button', { name: 'บันทึกและสร้างคำขอค่าคอม' }).closest('form'));

    await waitFor(() => expect(api.commissions.createFromDeal).toHaveBeenCalledTimes(1));
    const { invoiceAttachment } = api.commissions.createFromDeal.mock.calls[0][0];

    // The regression this guards: without the File re-wrap, `invoiceAttachment.name` is undefined
    // (a bare Blob has no `.name`), and FormData/fetch would send "blob" to the real backend --
    // corrupting the filename of the ticket's actual tax invoice attachment, not a cosmetic label.
    expect(invoiceAttachment.name).toBe('tax-invoice-0042.jpg');
    expect(invoiceAttachment).toBeInstanceOf(File);
    expect(invoiceAttachment.type).toBe('image/jpeg');
  });

  it('does not touch PDFs -- they skip compression and keep their name for a different reason', async () => {
    const imageCompression = (await import('browser-image-compression')).default;
    renderAccountPage();
    await loadEligibleDeal();

    fireEvent.change(screen.getByLabelText(/เลขที่ใบกำกับ/), { target: { value: 'INV-0042' } });
    const pdf = new File(['fake-pdf-bytes'], 'tax-invoice-0042.pdf', { type: 'application/pdf' });
    fireEvent.change(document.getElementById('commission-invoice-file'), { target: { files: [pdf] } });
    fireEvent.change(screen.getByLabelText(/ยอดรับจริง/), { target: { value: '3210000' } });

    // fireEvent.click on the submit button runs jsdom's native constraint validation first,
    // which (unlike a real browser) does not reliably see the file input as satisfied after a
    // synthetic fireEvent.change -- dispatching submit directly on the form exercises the same
    // onSubmit={submitFromDeal} handler without that jsdom-only false negative.
    fireEvent.submit(screen.getByRole('button', { name: 'บันทึกและสร้างคำขอค่าคอม' }).closest('form'));

    await waitFor(() => expect(api.commissions.createFromDeal).toHaveBeenCalledTimes(1));
    const { invoiceAttachment } = api.commissions.createFromDeal.mock.calls[0][0];

    expect(imageCompression).not.toHaveBeenCalled();
    expect(invoiceAttachment.name).toBe('tax-invoice-0042.pdf');
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

// Issue #737: the manual-commission rep picker used to be derived from api.tickets.list({})'s
// distinct createdById values, so a rep who owned zero deals -- e.g. a sales manager receiving a
// MANAGER-kind commission -- never appeared, with no message saying the list was partial. It is
// now served by a dedicated endpoint, api.commissions.reps() (CommissionController#reps),
// independent of tickets entirely.
//
// This is a MOCK-DRIVEN test: it proves the frontend wiring (the effect calls the new endpoint
// and renders what it returns), NOT the authorization boundary. Per CLAUDE.md, a stubbed
// api.commissions.reps is not evidence about who the real backend lets call
// GET /api/commissions/reps -- that is CommissionRepLookupIntegrationTest's job, against real
// Postgres.
describe('CommissionPage — manual-commission rep picker (issue #737)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders a rep who owns no deal at all, and selecting it writes the rep\'s id into salesRepId', async () => {
    api.commissions.list.mockResolvedValue({ commissions: [] });
    // No employeeCode: CommissionRepOptionDto dropped it (review fix, #737) -- the option shows
    // ONLY the name now, precisely so there is nothing on screen that could be mistaken for the
    // separate numeric Employee ID field.
    api.commissions.reps.mockResolvedValue({
      reps: [{ id: 777, name: 'ผู้จัดการ ไม่มีดีลเลย' }],
    });

    renderPage(salesManagerUser);

    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    await waitFor(() => expect(api.commissions.reps).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มค่าคอมด้วยตนเอง/ }));

    // Present by name alone -- proves the rep with no deal reached the option list.
    // Scoped to the manual form's picker (kept from when the page had its own rep <select>).
    expect(await within(await screen.findByLabelText(/หรือเลือกจากพนักงานขาย/)).findByText('ผู้จัดการ ไม่มีดีลเลย')).not.toBeNull();

    // api.tickets.list resolves { tickets: [] } by default (top-level mock) -- the OLD
    // ticket-derived picker could never have shown a rep with no deal. This picker never calls
    // it at all any more, for any role: proof the rep list no longer depends on tickets.
    expect(api.tickets.list).not.toHaveBeenCalled();

    // The load-bearing behaviour (review fix, #737): choosing the option must write the rep's
    // real `id` -- the value createManualCommission actually submits as salesRepId -- into the
    // numeric Employee ID field. Falsifiable by construction: this asserts the field equals the
    // STUBBED id (777) verbatim, so a wiring regression that writes anything else (a hardcoded
    // value, an index, undefined) fails this exact assertion rather than a value coincidentally
    // matching it.
    fireEvent.change(screen.getByLabelText(/หรือเลือกจากพนักงานขาย/), { target: { value: '777' } });
    expect(screen.getByLabelText(/รหัสพนักงาน \(Employee ID\)/).value).toBe('777');
  });

  // Scope change (owner ruling 2026-08-14, 2nd ruling): ceo and sales_manager now get the
  // IDENTICAL ฝ่ายขาย list, so there is exactly one picker label -- no more role branching to
  // distinguish. Proven through ceoUser specifically (the other test above already covers
  // salesManagerUser) so both roles are exercised somewhere in this file, even though the label
  // itself no longer varies.
  it('shows the single ฝ่ายขาย picker label, still pointing at the numeric field, for ceo too', async () => {
    api.commissions.list.mockResolvedValue({ commissions: [] });
    api.commissions.reps.mockResolvedValue({
      reps: [{ id: 888, name: 'พนักงานขาย ตัวอย่าง' }],
    });

    renderPage(ceoUser);

    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    await waitFor(() => expect(api.commissions.reps).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มค่าคอมด้วยตนเอง/ }));

    expect(await within(await screen.findByLabelText(/หรือเลือกจากพนักงานขาย/)).findByText('พนักงานขาย ตัวอย่าง')).not.toBeNull();
    // Concise (Ploy's request) but still names the scope (ฝ่ายขาย) and still points at the
    // numeric Employee-ID field for anyone the picker excludes -- dropping either would either
    // overclaim completeness or silently strand a caller with no way to reach someone.
    expect(screen.getByText('หรือเลือกจากพนักงานขาย (นอกฝ่ายขายให้กรอกรหัสพนักงานด้านบน)')).not.toBeNull();
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
  const r704 = saleRecord({ id: 704, salesRepId: REP_B.id, salesRepName: REP_B.name, commissionableBase: 500000, actualReceived: 535000, weightMultiplier: 2, invoiceDetails: inv(704, 'INV-OK-0704', 535000) });
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

  const cycle = () => screen.findByRole('region', { name: 'รอบจ่ายค่าคอม' });
  const money = (n) => new RegExp(`฿${n.toLocaleString('en-US')}(\\.00)?(?![\\d,])`);

  beforeEach(() => {
    vi.clearAllMocks();
    setClock('2026-08-15T05:00:00Z');
    stub(managerPending);
  });

  // ── 1. the payroll month ──────────────────────────────────────────────────────────────────
  it('opens on the current payroll month: stepper header, "รอบจ่ายถัดไป" chip, M-1 meta, manual-add button; every load follows that month', async () => {
    renderAt(salesManagerUser);
    const region = await cycle();

    expect(within(region).getByText('สิงหาคม 2569')).not.toBeNull();
    expect(within(region).getByText('รอบจ่ายถัดไป')).not.toBeNull();
    expect(within(region).getByText('จากยอดรับเงิน ก.ค. 2569 · จ่ายพร้อมเงินเดือน ส.ค. 2569')).not.toBeNull();
    expect(within(region).getByRole('button', { name: 'รอบก่อนหน้า' })).not.toBeNull();
    expect(within(region).getByRole('button', { name: 'รอบถัดไป' })).not.toBeNull();
    expect(within(region).getByRole('button', { name: /เพิ่มค่าคอมด้วยตนเอง/ })).not.toBeNull();
    await waitFor(() => expect(api.commissions.list).toHaveBeenCalledWith({ payrollMonth: '2026-08' }));
    // the old free-form month input and the big "ฐานค่าคอมเดือนนี้" number are gone
    expect(screen.queryByLabelText('รอบเดือน')).toBeNull();
    expect(screen.queryByText('ฐานค่าคอมเดือนนี้')).toBeNull();
  });

  // Today's bug: new Date().toISOString().slice(0, 7) is UTC, so 00:00-07:00 Bangkok on the 1st
  // opens the PREVIOUS month. 2026-10-31T18:30Z is 1 Nov 01:30 in Bangkok; 16:59Z is 31 Oct 23:59.
  it.each([
    ['2026-10-31T18:30:00Z', 'พฤศจิกายน 2569', '2026-11'],
    ['2026-10-31T16:59:00Z', 'ตุลาคม 2569', '2026-10'],
  ])('at %s UTC the page opens on %s -- the Asia/Bangkok month, not the UTC month', async (iso, label, yearMonth) => {
    setClock(iso);
    renderAt(salesManagerUser);
    expect(within(await cycle()).getByText(label)).not.toBeNull();
    await waitFor(() => expect(api.commissions.list).toHaveBeenCalledWith({ payrollMonth: yearMonth }));
  });

  it('‹ › move one month at a time, the chip shows only on the current payroll month, and the year rolls over', async () => {
    setClock('2026-01-10T05:00:00Z');
    renderAt(salesManagerUser);
    let region = await cycle();
    expect(within(region).getByText('มกราคม 2569')).not.toBeNull();
    expect(within(region).queryByText('รอบจ่ายถัดไป')).not.toBeNull();

    fireEvent.click(within(region).getByRole('button', { name: 'รอบก่อนหน้า' }));
    region = await cycle();
    expect(await within(region).findByText('ธันวาคม 2568')).not.toBeNull();
    expect(within(region).queryByText('รอบจ่ายถัดไป')).toBeNull();
    expect(within(region).getByText('จากยอดรับเงิน พ.ย. 2568 · จ่ายพร้อมเงินเดือน ธ.ค. 2568')).not.toBeNull();
    await waitFor(() => expect(api.commissions.list).toHaveBeenCalledWith({ payrollMonth: '2025-12' }));

    fireEvent.click(within(region).getByRole('button', { name: 'รอบถัดไป' }));
    fireEvent.click(within(await cycle()).getByRole('button', { name: 'รอบถัดไป' }));
    expect(await within(await cycle()).findByText('กุมภาพันธ์ 2569')).not.toBeNull();
    expect(within(await cycle()).queryByText('รอบจ่ายถัดไป')).toBeNull();

    fireEvent.click(within(await cycle()).getByRole('button', { name: 'รอบก่อนหน้า' }));
    expect(await within(await cycle()).findByText('มกราคม 2569')).not.toBeNull();
    expect(within(await cycle()).queryByText('รอบจ่ายถัดไป')).not.toBeNull();
  });

  it('?view=pending is not a separate page any more: it lands on the default page (cycle header, queue, rep table, all records)', async () => {
    for (const user of [salesManagerUser, ceoUser]) {
      stub(user.role === 'ceo' ? [dto703] : managerPending);
      const { unmount } = renderAt(user, '/commissions?view=pending');
      expect(await cycle()).not.toBeNull();
      expect(await screen.findByRole('heading', { name: /^รอคุณอนุมัติ\s*\d+$/ })).not.toBeNull();
      // opens on the EARLIEST round holding her own pending items: the manager's July carry-over,
      // the CEO's current August
      const roundName = user.role === 'ceo' ? 'สรุปรายคน · ส.ค. 2569' : 'สรุปรายคน · ก.ค. 2569';
      expect(await screen.findByRole('region', { name: roundName })).not.toBeNull();
      expect(screen.getByRole('region', { name: 'รายการทั้งหมดในรอบนี้' })).not.toBeNull();
      unmount();
    }
  });

  // ── 2. progress strip ─────────────────────────────────────────────────────────────────────
  it.each([
    ['sales_manager', salesManagerUser, 'SUBMITTED', 'MANAGER_APPROVED'],
    ['ceo', ceoUser, 'MANAGER_APPROVED', 'SUBMITTED'],
  ])('%s: the strip counts the month\'s records per stage with summed base, a proportional bar, and highlights only her own stage', async (_role, user, mine, other) => {
    stub(user.role === 'ceo' ? [dto703] : managerPending);
    renderAt(user);
    await cycle();

    // 2 รอผู้จัดการ (701 + 702 = 4.8M + 1M), 1 รอ CEO (703 = 2M), 2 อนุมัติแล้ว (704 x2 = 1M, 705 = 0.3M).
    // The sums are baseContribution(): commissionableBase x recordWeight, display only.
    const expected = {
      SUBMITTED: ['รอผู้จัดการ', '2 รายการ', 5800000],
      MANAGER_APPROVED: ['รอ CEO', '1 รายการ', 2000000],
      APPROVED: ['อนุมัติแล้ว · พร้อมเข้า Payroll', '2 รายการ', 1300000],
    };
    for (const [status, [label, count, sum]] of Object.entries(expected)) {
      const box = await screen.findByTestId(`cycle-box-${status}`);
      await waitFor(() => expect(box.textContent).toContain(count));
      expect(box.textContent).toContain(label);
      expect(box.textContent).toMatch(money(sum));
    }
    expect(screen.getByTestId(`cycle-box-${mine}`).getAttribute('aria-current')).toBe('true');
    expect(screen.getByTestId(`cycle-box-${other}`).getAttribute('aria-current')).toBeNull();
    expect(screen.getByTestId('cycle-box-APPROVED').getAttribute('aria-current')).toBeNull();
    expect(within(await cycle()).getByRole('img', { name: '2 จาก 5 รายการอนุมัติแล้ว' })).not.toBeNull();
  });

  // ── 3. carry-over ─────────────────────────────────────────────────────────────────────────
  it('a pending item of an EARLIER round shows as a carry-over callout (not in this round\'s queue), and "ไปที่รอบ" switches the whole page to it', async () => {
    renderAt(salesManagerUser);
    const callout = await screen.findByTestId('carry-over-callout');
    expect(callout.textContent).toContain('ค้างจากรอบ ก.ค. 2569: 1 รายการ');
    expect(callout.textContent).toContain('INV-JUL-0601 (กมลชนก พูลสุข)');
    expect(callout.textContent).toContain('ยังไม่ได้อนุมัติ');
    // a LATER round's item is neither carried over nor queued; the earlier one is not queued either
    expect(callout.textContent).not.toContain('INV-SEP-0901');
    expect(callout.textContent).not.toContain('INV-PEND-0701');
    expect(screen.queryByTestId('queue-row-601')).toBeNull();
    expect(screen.queryByTestId('queue-row-901')).toBeNull();

    fireEvent.click(within(callout).getByRole('button', { name: 'ไปที่รอบ ก.ค. 2569' }));

    expect(await within(await cycle()).findByText('กรกฎาคม 2569')).not.toBeNull();
    expect(await screen.findByTestId('queue-row-601')).not.toBeNull();
    expect(screen.queryByTestId('queue-row-701')).toBeNull();
    expect(screen.queryByTestId('carry-over-callout')).toBeNull();
    expect(screen.getByRole('region', { name: 'สรุปรายคน · ก.ค. 2569' })).not.toBeNull();
    expect(api.commissions.list).toHaveBeenCalledWith({ payrollMonth: '2026-07' });
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

  it('an empty queue says so for the shown round', async () => {
    stub([]);
    renderAt(salesManagerUser);
    // the prototype appends " · ส่งต่อ CEO แล้ว n รายการ" when records already wait for the CEO (703 does)
    expect(await screen.findByText(/^ไม่มีรายการรอคุณอนุมัติในรอบ ส.ค. 2569 · ส่งต่อ CEO แล้ว 1 รายการ$/)).not.toBeNull();
    expect(await screen.findByRole('heading', { name: /^รอคุณอนุมัติ\s*0$/ })).not.toBeNull();
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

  it('all-records rows at the viewer\'s stage carry approve / reject buttons, so nothing awaiting her is unreachable', async () => {
    stub([dto703]);
    withManual();
    renderAt(ceoUser);
    const all = await screen.findByRole('region', { name: 'รายการทั้งหมดในรอบนี้' });
    await within(all).findByText('INV-MA-0703');
    expect(within(all).getAllByRole('button', { name: 'CEO อนุมัติ' })).toHaveLength(2); // 703 + the manual 801
    expect(within(all).getAllByRole('button', { name: 'CEO ปฏิเสธ' })).toHaveLength(2);
    expect(within(all).queryByRole('button', { name: 'ผู้จัดการอนุมัติ' })).toBeNull();
  });

  // Invoice month + 1 = payroll month, so an item submitted this month sits in NEXT month's round.
  it('an item in a LATER round shows an info callout on the default page, and "ไปที่รอบ" jumps to it', async () => {
    renderAt(salesManagerUser);
    const callout = await screen.findByTestId('upcoming-callout');
    expect(callout.textContent).toContain('รอบถัดไป ก.ย. 2569: 1 รายการรอคุณอนุมัติ');
    fireEvent.click(within(callout).getByRole('button', { name: 'ไปที่รอบ ก.ย. 2569' }));
    expect(await within(await cycle()).findByText('กันยายน 2569')).not.toBeNull();
    expect(await screen.findByTestId('queue-row-901')).not.toBeNull();
    expect(screen.queryByTestId('upcoming-callout')).toBeNull();
  });

  it('?view=pending opens on the earliest round with her own pending items -- here only next month has one', async () => {
    stub([dto901]);
    renderAt(salesManagerUser, '/commissions?view=pending');
    expect(await screen.findByTestId('queue-row-901')).not.toBeNull();
    expect(within(await cycle()).getByText('กันยายน 2569')).not.toBeNull();
  });

  it('?view=pending with nothing pending falls back to the current round', async () => {
    stub([]);
    renderAt(salesManagerUser, '/commissions?view=pending');
    expect(await screen.findByText(/^ไม่มีรายการรอคุณอนุมัติในรอบ ส.ค. 2569/)).not.toBeNull();
    expect(within(await cycle()).getByText('สิงหาคม 2569')).not.toBeNull();
  });

  it('REJECTED records stay findable: a "ไม่อนุมัติ n" chip appears only when n > 0, and they are not counted in the strip', async () => {
    const rejected = submitted(990, 'INV-REJ-0990', REP_A, { status: 'REJECTED', rejectedByName: 'สมหญิง' });
    renderAt(salesManagerUser);
    const all = await screen.findByRole('region', { name: 'รายการทั้งหมดในรอบนี้' });
    await within(all).findByText('INV-OK-0704');
    expect(within(all).queryByRole('button', { name: /^ไม่อนุมัติ \d+$/ })).toBeNull();

    api.commissions.list.mockImplementation(async () => ({ commissions: [...BY_MONTH['2026-08'], rejected] }));
    fireEvent.click(within(await cycle()).getByRole('button', { name: 'รอบถัดไป' }));
    fireEvent.click(within(await cycle()).getByRole('button', { name: 'รอบก่อนหน้า' }));
    const chip = await within(await screen.findByRole('region', { name: 'รายการทั้งหมดในรอบนี้' })).findByRole('button', { name: 'ไม่อนุมัติ 1' });
    expect(within(await cycle()).getByRole('img', { name: '2 จาก 5 รายการอนุมัติแล้ว' })).not.toBeNull();
    fireEvent.click(chip);
    expect(await screen.findByText('INV-REJ-0990')).not.toBeNull();
  });

  it('a slower response for an earlier month cannot overwrite the later month\'s rows', async () => {
    const r951 = submitted(951, 'INV-OCT-0951', REP_A, { payrollMonth: '2026-10-01' });
    const pendingResolvers = {};
    stub([]);
    api.commissions.list.mockImplementation(({ payrollMonth }) => {
      if (payrollMonth === '2026-10') return Promise.resolve({ commissions: [r951] });
      return new Promise((resolve) => { pendingResolvers[payrollMonth] = () => resolve({ commissions: BY_MONTH[payrollMonth] ?? [] }); });
    });
    renderAt(salesManagerUser);
    await waitFor(() => expect(pendingResolvers['2026-08']).toBeDefined());
    fireEvent.click(within(await cycle()).getByRole('button', { name: 'รอบถัดไป' }));
    await waitFor(() => expect(pendingResolvers['2026-09']).toBeDefined());
    fireEvent.click(within(await cycle()).getByRole('button', { name: 'รอบถัดไป' }));
    const all = await screen.findByRole('region', { name: 'รายการทั้งหมดในรอบนี้' });
    expect(await within(all).findByText('INV-OCT-0951')).not.toBeNull();

    await act(async () => { pendingResolvers['2026-09'](); pendingResolvers['2026-08'](); });
    expect(within(all).getByText('INV-OCT-0951')).not.toBeNull();
    expect(within(all).queryByText('INV-OK-0704')).toBeNull();
  });

  // ── 5. the one detail panel ───────────────────────────────────────────────────────────────
  it('expanding a queue row opens ONE panel: money chain, products & weights, approval status with deal / invoice / file', async () => {
    renderAt(salesManagerUser);
    const row = await screen.findByTestId('queue-row-701');
    const toggle = within(row).getByRole('button', { name: 'ดูรายละเอียด INV-PEND-0701' });
    fireEvent.click(toggle);

    const panel = await screen.findByTestId('detail-panel-701');
    expect(screen.getAllByTestId(/^detail-panel-/)).toHaveLength(1);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');

    const calc = within(panel).getByRole('region', { name: 'การคำนวณ' });
    expect(calc.textContent).toContain('ยอดไม่รวม VAT');
    expect(calc.textContent).toContain('฿3,000,000.00');
    expect(calc.textContent).toContain('ที่เข้าฐาน');
    expect(calc.textContent).toContain('฿4,800,000.00');
    expect(within(calc).getByRole('button', { name: 'แก้ไขค่าหัก' })).not.toBeNull();

    const products = within(panel).getByRole('region', { name: 'สินค้าและน้ำหนัก' });
    expect(within(products).getAllByRole('radiogroup')).toHaveLength(2);
    expect(within(products).getByRole('button', { name: 'สต็อกทั้งหมด ×2' })).not.toBeNull();

    const status = within(panel).getByRole('region', { name: 'สถานะและเอกสาร' });
    ['บัญชีบันทึกใบกำกับ', 'ผู้จัดการฝ่ายขาย', 'CEO', 'จ่ายกับเงินเดือน'].forEach((step) => expect(status.textContent).toContain(step));
    expect(status.textContent).toContain('TCK-0077');
    // the deal code is a real link to the deal page; the file name stays plain text
    expect(within(status).getByRole('link', { name: 'TCK-0077' }).getAttribute('href')).toBe('/tickets/77');
    expect(within(status).queryByRole('link', { name: /invoice\.pdf/ })).toBeNull();
    expect(status.textContent).toContain('INV-PEND-0701');
    expect(status.textContent).toContain('invoice.pdf');

    fireEvent.click(toggle); // collapses again
    expect(screen.queryByTestId('detail-panel-701')).toBeNull();
  });

  it('the mismatch badge shows in the row and the money section only -- not a third time in the status section', async () => {
    renderAt(salesManagerUser);
    const row = await screen.findByTestId('queue-row-702');
    fireEvent.click(within(row).getByRole('button', { name: 'ดูรายละเอียด INV-PEND-0702' }));
    const panel = await screen.findByTestId('detail-panel-702');

    expect(within(panel).getAllByText('ยอดต่างจากยอดที่เรียกเก็บ')).toHaveLength(1); // money section only
    expect(within(row).getByText('ยอดต่างจากยอดที่เรียกเก็บ')).not.toBeNull();
    expect(within(within(panel).getByRole('region', { name: 'การคำนวณ' })).getByText('ยอดต่างจากยอดที่เรียกเก็บ')).not.toBeNull();
    expect(within(within(panel).getByRole('region', { name: 'สถานะและเอกสาร' })).queryByText('ยอดต่างจากยอดที่เรียกเก็บ')).toBeNull();
  });

  it('the queue row\'s expand control carries the words "ดูรายละเอียด" (a full-width button on mobile), keeping its aria-label', async () => {
    renderAt(salesManagerUser);
    const toggle = within(await screen.findByTestId('queue-row-701')).getByRole('button', { name: 'ดูรายละเอียด INV-PEND-0701' });
    expect(toggle.textContent).toContain('ดูรายละเอียด');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
  });

  it('a round with no records shows one muted row in สรุปรายคน instead of a header-only table', async () => {
    api.commissions.list.mockResolvedValue({ commissions: [] });
    renderAt(salesManagerUser);
    const region = await screen.findByRole('region', { name: 'สรุปรายคน · ส.ค. 2569' });
    expect(within(region).getByText('ยังไม่มีรายการในรอบ ส.ค. 2569')).not.toBeNull();
  });

  it('the same panel opens from a row of the all-records table', async () => {
    renderAt(salesManagerUser);
    const all = await screen.findByRole('region', { name: 'รายการทั้งหมดในรอบนี้' });
    fireEvent.click(await within(all).findByRole('button', { name: 'ดูรายละเอียด INV-OK-0704' }));

    const panel = await screen.findByTestId('detail-panel-704');
    expect(within(panel).getByRole('region', { name: 'การคำนวณ' })).not.toBeNull();
    expect(within(panel).getByRole('region', { name: 'สถานะและเอกสาร' })).not.toBeNull();
    // APPROVED: nothing to edit or approve at this stage
    expect(within(panel).queryByRole('button', { name: 'แก้ไขค่าหัก' })).toBeNull();
  });

  // ── 6. สรุปรายคน is covered in CommissionPage.statement.test.jsx; 7. all records: ───────────
  it('"รายการทั้งหมดในรอบนี้": status chips with counts filter the table; approver column; clawback stays on APPROVED rows', async () => {
    renderAt(salesManagerUser);
    const all = await screen.findByRole('region', { name: 'รายการทั้งหมดในรอบนี้' });
    await within(all).findByText('INV-OK-0704');

    ['ใบกำกับ', 'พนักงานขาย', 'ฐานค่าคอม', 'สถานะ', 'อนุมัติโดย'].forEach((header) => expect(within(all).getAllByText(header).length).toBeGreaterThan(0));
    const chip = (name) => within(all).getByRole('button', { name });
    expect(chip('ทั้งหมด 5').getAttribute('aria-pressed')).toBe('true');
    expect(chip('รอผู้จัดการ 2')).not.toBeNull();
    expect(chip('อนุมัติแล้ว 2')).not.toBeNull();
    expect(within(all).getAllByRole('button', { name: 'บันทึกหักคืน' })).toHaveLength(2);

    fireEvent.click(chip('รอ CEO 1'));
    expect(chip('รอ CEO 1').getAttribute('aria-pressed')).toBe('true');
    expect(within(all).getByText('INV-MA-0703')).not.toBeNull();
    expect(within(all).queryByText('INV-OK-0704')).toBeNull();
    expect(within(all).queryByText('INV-PEND-0701')).toBeNull();
    expect(all.textContent).toContain('สมหญิง ผจก.'); // อนุมัติโดย
    expect(within(all).queryByRole('button', { name: 'บันทึกหักคืน' })).toBeNull();
  });
});
