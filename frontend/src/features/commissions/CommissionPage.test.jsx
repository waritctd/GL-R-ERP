import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CommissionPage } from './CommissionPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

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
const isChecked = (el) => el.checked === true || el.getAttribute('aria-checked') === 'true';
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
    money: { amountPayable: 3210000 },
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
    expect((screen.getByLabelText(/ยอดรวม/)).value).toBe('3210000');
  });

  it('a finance-view deal that has not reached CLOSED_PAID is refused with the stage in the message', async () => {
    api.finance.getDeal.mockResolvedValue({ deal: financeDeal({ salesStage: 'DELIVERY_SCHEDULING' }) });
    renderAccountPage();
    fireEvent.change(screen.getByLabelText(/เลขที่ Ticket ID/), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: /โหลดข้อมูลดีล/ }));
    expect(await screen.findByText(/ยังไม่ถึงขั้นตอนปิดงาน/)).not.toBeNull();
  });

  it('a successful create-from-deal invalidates the finance deal cache too, so the commission CTA is not stale', async () => {
    const { queryClient } = renderAccountPage();
    const spy = vi.spyOn(queryClient, 'invalidateQueries');
    await loadEligibleDeal();
    fireEvent.change(screen.getByLabelText(/เลขที่ใบกำกับ/), { target: { value: 'INV-0042' } });
    const original = new File(['x'], 'tax-invoice-0042.jpg', { type: 'image/jpeg' });
    fireEvent.change(document.getElementById('commission-invoice-file'), { target: { files: [original] } });
    fireEvent.change(screen.getByLabelText(/ยอดรวม/), { target: { value: '3210000' } });
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
    fireEvent.change(screen.getByLabelText(/ยอดรวม/), { target: { value: '3210000' } });

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
    fireEvent.change(screen.getByLabelText(/ยอดรวม/), { target: { value: '3210000' } });

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

// P0 fix (fix/commission-approved-record-immutable): CommissionService#updateDeductions now
// refuses an APPROVED record outright (see the backend integration test,
// CommissionApprovedRecordImmutableIntegrationTest). Before this fix the pencil rendered for
// every non-manual record regardless of status -- per V102's census every one of prod's 1,132
// commission records is APPROVED, so the unguarded pencil would 409 on essentially every row a
// sales_manager/CEO could click it on. Reuses canReviewRecord, the exact gate the approve/reject
// buttons beside it already use.
describe('CommissionPage — edit-deductions pencil gated on reviewable status (fix/commission-approved-record-immutable)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('hides the edit pencil for an APPROVED record (matches every prod commission record today)', async () => {
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord()] }); // default status: APPROVED
    renderPage(salesManagerUser);

    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    // Wait for the row itself before asserting an absence, so a failed/slow load could never
    // produce a false "hidden" pass.
    await screen.findByText('INV-405-0001');

    expect(screen.queryByRole('button', { name: 'แก้ไขค่าหัก' })).toBeNull();
    // The approve/reject buttons use the exact same canReviewRecord gate the pencil now reuses --
    // both must also be absent here, proving this is that shared gate and not a pencil-only rule.
    expect(screen.queryByRole('button', { name: 'ผู้จัดการอนุมัติ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ผู้จัดการปฏิเสธ' })).toBeNull();
    // Not "hide everything": the sanctioned correction path for an APPROVED SALE record --
    // clawback -- stays offered.
    expect(screen.getByRole('button', { name: 'บันทึกหักคืน' })).not.toBeNull();
  });

  it('still shows the edit pencil for a SUBMITTED record reviewed by a sales_manager', async () => {
    const submitted = saleRecord({
      id: 601,
      status: 'SUBMITTED',
      approvedById: null,
      approvedAt: null,
      managerApprovedBy: null,
      managerApprovedByName: null,
      managerApprovedAt: null,
      ceoApprovedBy: null,
      ceoApprovedByName: null,
      ceoApprovedAt: null,
      invoiceDetails: invoiceDetails({ id: 601, invoiceNumber: 'INV-405-0601' }),
    });
    api.commissions.list.mockResolvedValue({ commissions: [submitted] });
    renderPage(salesManagerUser);

    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    await screen.findByText('INV-405-0601');

    expect(screen.getByRole('button', { name: 'แก้ไขค่าหัก' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'ผู้จัดการอนุมัติ' })).not.toBeNull();
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
    // Scoped to the manual form's picker: the page's own "สรุปรายคน" picker now lists the same
    // reps, so a bare findByText would match both.
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

// sales_manager รออนุมัติ view (?view=pending): every SUBMITTED sale record across ALL payroll months,
// with per-item stock/weight lines, served by GET /api/commissions/pending-approval and adjusted by
// POST /api/commissions/{id}/item-weights (sales_manager ONLY; ceo reads). MOCK-DRIVEN: these prove the
// page's plumbing, NOT the authorization boundary (the Java integration tests own that) and NOT the
// estimatedCommission / effectiveWeight maths, which come back from the server and are never recomputed here.
describe('CommissionPage — รออนุมัติ pending-approval view', () => {
  function renderAt(user, url) {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    return render(
      <MemoryRouter initialEntries={[url]}>
        <QueryClientProvider client={queryClient}>
          <CommissionPage user={user} showToast={vi.fn()} />
        </QueryClientProvider>
      </MemoryRouter>,
    );
  }

  const pendingRecord = () => saleRecord({
    id: 701,
    status: 'SUBMITTED',
    payrollMonth: '2026-07-01', // deliberately NOT the current month: the view must not depend on the month filter
    approvedById: null, approvedAt: null,
    managerApprovedBy: null, managerApprovedByName: null, managerApprovedAt: null,
    ceoApprovedBy: null, ceoApprovedByName: null, ceoApprovedAt: null,
    commissionableBase: 4000000, // ex-VAT, BEFORE weighting (weightedCommissionableBase below is after)
    invoiceDetails: invoiceDetails({ id: 701, invoiceNumber: 'INV-PEND-0701' }),
  });
  const pendingDto = (over = {}) => ({
    commission: pendingRecord(),
    ticketCode: 'TCK-0077',
    customerName: 'บริษัท รออนุมัติ จำกัด',
    items: [
      { itemId: 11, description: 'PADANA 60x60 ผิวเงา', qty: 120, qtyFromStock: 30, weightMultiplier: 1 },
      { itemId: 12, description: 'PADANA 30x60 ผิวด้าน', qty: 40, qtyFromStock: 0, weightMultiplier: 1 },
    ],
    effectiveWeight: 1.6,
    weightedCommissionableBase: 4800000,
    estimatedCommission: 95000.5,
    ...over,
  });

  beforeEach(() => {
    vi.clearAllMocks();
    // list() returns OTHER-status records: they must never leak into the pending view.
    api.commissions.list.mockResolvedValue({
      commissions: [
        saleRecord({ id: 801, status: 'APPROVED', invoiceDetails: invoiceDetails({ id: 801, invoiceNumber: 'INV-OTHER-APPROVED' }) }),
        saleRecord({ id: 802, status: 'MANAGER_APPROVED', invoiceDetails: invoiceDetails({ id: 802, invoiceNumber: 'INV-OTHER-MGRAPPR' }) }),
      ],
    });
    api.commissions.reps.mockResolvedValue({ reps: [] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: null });
    api.commissions.pendingApproval.mockResolvedValue({ commissions: [pendingDto()] });
  });

  const pendingTab = () => screen.queryByRole('tab', { name: /รออนุมัติ/ }) ?? screen.queryByRole('button', { name: /^รออนุมัติ/ });

  it('?view=pending shows the รออนุมัติ view from api.commissions.pendingApproval (no month argument) and only its items', async () => {
    renderAt(salesManagerUser, '/commissions?view=pending');
    expect(await screen.findByText('INV-PEND-0701')).not.toBeNull();
    expect(api.commissions.pendingApproval).toHaveBeenCalledWith();
    expect(screen.getByText(/TCK-0077/)).not.toBeNull();
    expect(screen.getByText(/บริษัท รออนุมัติ จำกัด/)).not.toBeNull();
    // other-status rows from list() do not appear in the pending view
    expect(screen.queryByText('INV-OTHER-APPROVED')).toBeNull();
    expect(screen.queryByText('INV-OTHER-MGRAPPR')).toBeNull();
  });

  it('each pending card shows product, qty, qty from stock, a ×1/×2/×3 weight segmented control, total weight (2dp) and the estimated commission', async () => {
    renderAt(salesManagerUser, '/commissions?view=pending');
    await screen.findByText('INV-PEND-0701');
    expect(screen.getByText('PADANA 60x60 ผิวเงา')).not.toBeNull();
    expect(screen.getByText('PADANA 30x60 ผิวด้าน')).not.toBeNull();
    // Redesign: "สต็อก n/qty" replaces the separate จำนวน / จากสต็อก columns, and the native
    // <select> becomes a ×1/×2/×3 segmented radiogroup per line.
    expect(screen.getByText('สต็อก 30/120')).not.toBeNull();
    expect(screen.queryAllByRole('combobox')).toHaveLength(0);
    const groups = screen.getAllByRole('radiogroup');
    expect(groups).toHaveLength(2);
    expect(within(groups[0]).getAllByRole('radio').map((r) => r.textContent.trim())).toEqual(['×1', '×2', '×3']);
    expect(isChecked(within(groups[0]).getByRole('radio', { name: '×1' }))).toBe(true);
    // The manager sees the effect of her key-in: base before weighting -> base after weighting.
    const entry = screen.getByTestId('pending-entry-701');
    expect(entry.textContent).toContain('฿4,000,000.00');
    expect(entry.textContent).toContain('฿4,800,000.00');
    expect(screen.getByText(/น้ำหนักรวม/)).not.toBeNull();
    expect(screen.getByText('1.60')).not.toBeNull();
    expect(screen.getByText(/ค่าคอมที่คำนวณได้/)).not.toBeNull();
    expect(screen.getByText(/฿95,000\.50/)).not.toBeNull();
  });

  it('sales_manager changing an item weight calls adjustItemWeights(recordId, {lines}) and shows the SERVER-recomputed effectiveWeight', async () => {
    api.commissions.adjustItemWeights.mockResolvedValue({
      pending: pendingDto({
        items: [
          { itemId: 11, description: 'PADANA 60x60 ผิวเงา', qty: 120, qtyFromStock: 30, weightMultiplier: 3 },
          { itemId: 12, description: 'PADANA 30x60 ผิวด้าน', qty: 40, qtyFromStock: 0, weightMultiplier: 1 },
        ],
        effectiveWeight: 2.2,
        estimatedCommission: 130000,
      }),
    });
    renderAt(salesManagerUser, '/commissions?view=pending');
    await screen.findByText('INV-PEND-0701');
    const line11 = () => within(screen.getByRole('radiogroup', { name: 'น้ำหนัก PADANA 60x60 ผิวเงา' }));
    fireEvent.click(line11().getByRole('radio', { name: '×3' }));
    await waitFor(() => expect(api.commissions.adjustItemWeights).toHaveBeenCalledTimes(1));
    expect(api.commissions.adjustItemWeights).toHaveBeenCalledWith(701, { lines: [{ itemId: 11, weightMultiplier: 3 }] });
    expect(await screen.findByText('2.20')).not.toBeNull();
    expect(screen.queryByText('1.60')).toBeNull();
    expect(isChecked(line11().getByRole('radio', { name: '×3' }))).toBe(true);
  });

  it('ceo sees the pending card but the weight selects are read-only (adjust is sales_manager only) and never call the API', async () => {
    renderAt(ceoUser, '/commissions?view=pending');
    await screen.findByText('INV-PEND-0701');
    expect(api.commissions.pendingApproval).toHaveBeenCalled();
    // Read-only for the CEO: the segmented control is still rendered (she can see the weights)
    // but every radio is disabled, and clicking one never reaches the API.
    const radios = screen.getAllByRole('radio');
    expect(radios.length).toBeGreaterThan(0);
    radios.forEach((radio) => expect(isDisabled(radio)).toBe(true));
    expect(screen.getByText('1.60')).not.toBeNull();
    fireEvent.click(within(screen.getByRole('radiogroup', { name: 'น้ำหนัก PADANA 60x60 ผิวเงา' })).getByRole('radio', { name: '×2' }));
    expect(api.commissions.adjustItemWeights).not.toHaveBeenCalled();
  });

  // Deliberately rewritten (worklist-first redesign): the sales_manager no longer has a tab to
  // REACH the queue -- the queue leads the default page. The CEO keeps the old tab behaviour.
  it('sales_manager: the รออนุมัติ queue leads the default view (no tab to click), above the month\'s records', async () => {
    renderAt(salesManagerUser, '/commissions');
    expect(await screen.findByText('INV-PEND-0701')).not.toBeNull();
    expect(api.commissions.pendingApproval).toHaveBeenCalled();
    expect(pendingTab()).toBeNull();
    // the month's records are still on the same page, after the queue
    const other = await screen.findByText('INV-OTHER-APPROVED');
    expect(screen.getByText('INV-PEND-0701').compareDocumentPosition(other) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('ceo still has a รออนุมัติ tab on the default view that switches to the pending view', async () => {
    renderAt(ceoUser, '/commissions');
    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    const tab = pendingTab();
    expect(tab).not.toBeNull();
    fireEvent.click(tab);
    expect(await screen.findByText('INV-PEND-0701')).not.toBeNull();
    expect(api.commissions.pendingApproval).toHaveBeenCalled();
    expect(screen.queryByText('INV-OTHER-APPROVED')).toBeNull();
  });

  it('a sales rep has no รออนุมัติ tab and ?view=pending never calls the pending endpoint for them', async () => {
    renderAt(salesUser, '/commissions?view=pending');
    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    expect(pendingTab()).toBeNull();
    expect(api.commissions.pendingApproval).not.toHaveBeenCalled();
  });
});
