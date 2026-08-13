import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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
      },
      tickets: {
        list: vi.fn().mockResolvedValue({ tickets: [] }),
        get: vi.fn(),
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

const hrUser = { id: 900, employeeId: 900, name: 'HR Test', role: 'hr' };
const salesUser = { id: 10, employeeId: 10, name: 'พนักงานขาย ทดสอบ', role: 'sales' };
const salesManagerUser = { id: 30, employeeId: 30, name: 'ผู้จัดการฝ่ายขาย ทดสอบ', role: 'sales_manager' };

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

    expect(await screen.findByText('อินเซนทีฟ (นอกขั้นบันได)')).not.toBeNull();
    // Two 15,000.00 occurrences would also be plausible depending on layout; assert the exact
    // incentive figure is present at least once.
    expect(screen.getAllByText('฿15,000.00').length).toBeGreaterThan(0);
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

    // Wait for the panel (proves records loaded and the month change took effect) before
    // asserting the suppressed line is absent.
    expect(await screen.findByText('ขั้นบันไดค่าคอมเดือนนี้ (ประมาณการ)')).not.toBeNull();
    expect(screen.queryByText('อินเซนทีฟ (นอกขั้นบันได)')).toBeNull();
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

    expect(await screen.findByText('฿99,999.99')).not.toBeNull();
    expect(screen.getByText('฿1,200,000.00')).not.toBeNull();
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

    expect(await screen.findByText('฿4,567.89')).not.toBeNull();
    expect(screen.getByText('฿654,321.09')).not.toBeNull();
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
        ],
      },
    });

    renderPage(salesUser);

    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    await setMonthInput('2026-08');

    expect(await screen.findByText('ขั้นบันไดค่าคอมเดือนนี้ (ประมาณการ)')).not.toBeNull();
    fireEvent.click(screen.getByTitle('ขยาย'));

    // Rates come straight from the server rows, formatted but never recomputed.
    expect(await screen.findByText('0.25%')).not.toBeNull();
    expect(screen.getByText('0.50%')).not.toBeNull();
    expect(screen.getByText('฿625.00')).not.toBeNull();
    expect(screen.getByText('฿250.50')).not.toBeNull();
    // The open-ended top tier renders its bound as "ขึ้นไป", not "null".
    expect(screen.getByText(/ขึ้นไป/)).not.toBeNull();
    // The mock-mode empty state must NOT appear when the server did supply rows.
    expect(screen.queryByText('ไม่มีรายละเอียดขั้นบันไดค่าคอมให้แสดงในขณะนี้')).toBeNull();
  });
});

const accountUser = { id: 20, employeeId: 20, name: 'บัญชี ทดสอบ', role: 'account' };

function closedPaidTicket(overrides = {}) {
  return {
    id: 42,
    code: 'TCK-0042',
    customerName: 'บริษัท ทดสอบ จำกัด',
    salesStage: 'CLOSED_PAID',
    amountPayable: 3210000,
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
    api.tickets.get.mockResolvedValue({ ticket: { summary: closedPaidTicket() } });
    api.commissions.createFromDeal.mockResolvedValue({ commission: { id: 900, invoiceDetails: invoiceDetails() } });
  });

  function renderAccountPage() {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    return render(
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <CommissionPage user={accountUser} showToast={showToast} />
        </QueryClientProvider>
      </MemoryRouter>,
    );
  }

  it('re-wraps the compressed invoice image so createFromDeal receives the original filename, not "blob"', async () => {
    renderAccountPage();
    await loadEligibleDeal();

    fireEvent.change(screen.getByLabelText('Invoice Number *'), { target: { value: 'INV-0042' } });
    const original = new File(['fake-jpeg-bytes'], 'tax-invoice-0042.jpg', { type: 'image/jpeg' });
    fireEvent.change(document.getElementById('commission-invoice-file'), { target: { files: [original] } });
    fireEvent.change(screen.getByLabelText(/Gross Amount/), { target: { value: '3210000' } });

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

    fireEvent.change(screen.getByLabelText('Invoice Number *'), { target: { value: 'INV-0042' } });
    const pdf = new File(['fake-pdf-bytes'], 'tax-invoice-0042.pdf', { type: 'application/pdf' });
    fireEvent.change(document.getElementById('commission-invoice-file'), { target: { files: [pdf] } });
    fireEvent.change(screen.getByLabelText(/Gross Amount/), { target: { value: '3210000' } });

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
