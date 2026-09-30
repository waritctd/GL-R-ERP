import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CommissionPage } from './CommissionPage.jsx';
import { api } from '../../api/index.js';
import { formatMoney, formatThaiMonthYearFromMonthInputValue } from '../../utils/format.js';

globalThis.React = React;

// Commission page redesign (branch feat/commission-page-redesign), STEP 1: tests first.
//
// Pins the rep-facing "ค่าคอมของฉัน" STATEMENT (a ledger that builds the total limb by limb), the
// receipt ledger under it, the month header that states the M-1 rule, the manager/CEO rep picker
// that re-uses the same statement, and the HR payroll team-override column.
//
// MOCK-DRIVEN: every figure is a stub of the DTO the backend is being extended to return
// (GET /api/commissions/monthly-summary: rawCommissionableBase, weightUpliftBase, stockBonusAmount,
// teamOverrideAmount, companyCommissionableBase, teamOverrideThresholdBase,
// teamOverrideRatePercent). These tests prove PLUMBING AND RENDERING of server figures -- they say
// nothing about the commission maths (CommissionCalculator / CommissionService own that), and
// nothing about who may call which endpoint (the "wrong-way" cases below pin what the UI OFFERS a
// role, not what the Java service ENFORCES; authz stays UNVERIFIED here).
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
        pendingApproval: vi.fn().mockResolvedValue({ commissions: [] }),
        adjustItemWeights: vi.fn(),
        reps: vi.fn().mockResolvedValue({ reps: [] }),
      },
      tickets: { list: vi.fn().mockResolvedValue({ tickets: [] }), get: vi.fn() },
      finance: { getDeal: vi.fn() },
    },
  };
});

vi.mock('browser-image-compression', () => ({
  default: vi.fn((file) => Promise.resolve(new Blob([file], { type: file.type }))),
}));

function renderPage(user, url = '/commissions') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <MemoryRouter initialEntries={[url]}>
      <QueryClientProvider client={queryClient}>
        <CommissionPage user={user} showToast={vi.fn()} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

const salesUser = { id: 10, employeeId: 10, name: 'พนักงานขาย ทดสอบ', role: 'sales' };
const salesManagerUser = { id: 30, employeeId: 30, name: 'ผู้จัดการฝ่ายขาย ทดสอบ', role: 'sales_manager' };
const ceoUser = { id: 50, employeeId: 50, name: 'CEO ทดสอบ', role: 'ceo' };
const hrUser = { id: 900, employeeId: 900, name: 'HR Test', role: 'hr' };

function invoiceDetails(overrides = {}) {
  return {
    id: 1,
    invoiceNumber: 'INV-0001',
    invoiceDate: '2026-07-20',
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
    createdAt: '2026-07-20T00:00:00Z',
    updatedAt: '2026-07-20T00:00:00Z',
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
    actualReceived: 3210000,
    commissionableBase: 3000000,
    weightMultiplier: 1,
    effectiveWeightMultiplier: null,
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

// Distinct, unrelated figures so a row assertion can only pass if THAT row carries THAT number.
function summary(overrides = {}) {
  return {
    payrollMonth: '2026-08-01',
    salesRepId: 10,
    rawCommissionableBase: 2000000,
    weightUpliftBase: 1000000,
    commissionableBase: 3000000,
    tierCommission: 48750,
    incentiveAmount: 15000,
    stockBonusAmount: 2000,
    teamOverrideAmount: 0,
    companyCommissionableBase: null,
    teamOverrideThresholdBase: null,
    teamOverrideRatePercent: null,
    manualTotal: 500,
    totalCommission: 66250,
    belowFloor: false,
    tiers: [],
    ...overrides,
  };
}

async function openAugust(user = salesUser) {
  renderPage(user);
  await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
  fireEvent.change(screen.getByLabelText('รอบเดือน'), { target: { value: '2026-08' } });
}

const row = (key) => screen.getByTestId(`statement-${key}`);
const text = (el) => el.textContent.replace(/\s+/g, ' ').trim();

describe('rep statement — "ค่าคอมของฉัน" builds the total limb by limb', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord()] });
  });

  it('renders every non-zero limb with the API\'s own numbers, in ledger order, ending in the total', async () => {
    api.commissions.monthlySummary.mockResolvedValue({ summary: summary() });
    await openAugust();

    const region = await screen.findByRole('region', { name: 'ค่าคอมของฉัน' });
    const expected = [
      ['raw', 'ยอดรับเงิน (ไม่รวม VAT)', 2000000],
      ['uplift', 'ส่วนเพิ่มจากสินค้าสต็อก (2x/3x)', 1000000],
      ['base', 'ฐานคิดค่าคอม', 3000000],
      ['tier', 'ค่าคอมตามขั้นบันได', 48750],
      ['incentive', 'Incentive ข้อ 12', 15000],
      ['stock-bonus', 'โบนัสขายสต็อก', 2000],
      ['manual', 'รายการปรับปรุง', 500],
      ['total', 'รวมค่าคอม (ประมาณการ)', 66250],
    ];
    const rows = expected.map(([key, label, amount]) => {
      const el = within(region).getByTestId(`statement-${key}`);
      expect(text(el)).toContain(label);
      expect(text(el)).toContain(formatMoney(amount));
      return el;
    });
    // Ledger order: each limb sits after the previous one in the document.
    rows.slice(1).forEach((el, i) => {
      expect(rows[i].compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });
  });

  it('labels the total a PREVIEW that counts receipts not yet approved', async () => {
    api.commissions.monthlySummary.mockResolvedValue({ summary: summary() });
    await openAugust();
    await screen.findByTestId('statement-total');
    const note = screen.getByTestId('statement-preview-note');
    expect(text(note)).toMatch(/ประมาณการ/);
    expect(text(note)).toMatch(/ยังไม่(ได้)?อนุมัติ/);
  });

  it('hides the limbs that are zero (uplift, incentive, stock bonus, manual) but always keeps base, tier and total', async () => {
    api.commissions.monthlySummary.mockResolvedValue({
      summary: summary({
        rawCommissionableBase: 3000000,
        weightUpliftBase: 0,
        incentiveAmount: 0,
        stockBonusAmount: 0,
        manualTotal: 0,
        totalCommission: 48750,
      }),
    });
    await openAugust();
    await screen.findByTestId('statement-total');

    ['uplift', 'incentive', 'stock-bonus', 'manual'].forEach((key) => {
      expect(screen.queryByTestId(`statement-${key}`)).toBeNull();
    });
    ['raw', 'base', 'tier', 'total'].forEach((key) => expect(screen.getByTestId(`statement-${key}`)).not.toBeNull());
    expect(text(row('total'))).toContain('฿48,750.00');
  });

  it('shows the ค่าคอมทีม row ONLY when companyCommissionableBase is non-null, with the formula spelled out from the API\'s numbers', async () => {
    api.commissions.monthlySummary.mockResolvedValue({
      summary: summary({
        teamOverrideAmount: 4875,
        companyCommissionableBase: 9500000,
        teamOverrideThresholdBase: 3000000,
        teamOverrideRatePercent: 0.075,
        totalCommission: 71125,
      }),
    });
    await openAugust();
    const team = await screen.findByTestId('statement-team-override');
    expect(text(team)).toContain('ค่าคอมทีม');
    expect(text(team)).toContain('฿4,875.00');
    // "(ยอดรับทั้งบริษัท X − Y) × Z%": X, Y and Z all come from the DTO. 0.075 must NOT be rounded
    // to 0.08 -- the recipient rate is three decimals.
    expect(text(team)).toContain('(ยอดรับทั้งบริษัท ฿9,500,000.00 − ฿3,000,000.00) × 0.075%');
  });

  // Review fix (MEDIUM): a 0.00 team row needs a reason, derived ONLY from API fields (the API has
  // no suppression flag): company base <= threshold means the company total is under the bar;
  // otherwise a 0 amount with a cleared bar means a hand-entered team commission replaced it.
  it('a recipient whose company total is BELOW the threshold: row stays, amount 0, with "ยอดรับทั้งบริษัทยังไม่ถึงเกณฑ์"', async () => {
    api.commissions.monthlySummary.mockResolvedValue({
      summary: summary({
        teamOverrideAmount: 0,
        companyCommissionableBase: 2500000,
        teamOverrideThresholdBase: 3000000,
        teamOverrideRatePercent: 0.075,
      }),
    });
    await openAugust();
    const team = await screen.findByTestId('statement-team-override');
    expect(text(team)).toContain('฿0.00');
    expect(text(team)).toContain('฿2,500,000.00');
    expect(text(team)).toContain('ยอดรับทั้งบริษัทยังไม่ถึงเกณฑ์');
    expect(text(team)).not.toContain('แทนที่ด้วยรายการที่บันทึกเอง');
  });

  it('a recipient above the threshold whose amount is still 0: "แทนที่ด้วยรายการที่บันทึกเอง (ดูรายการปรับปรุง)"', async () => {
    api.commissions.monthlySummary.mockResolvedValue({
      summary: summary({
        teamOverrideAmount: 0,
        companyCommissionableBase: 9500000,
        teamOverrideThresholdBase: 3000000,
        teamOverrideRatePercent: 0.075,
      }),
    });
    await openAugust();
    const team = await screen.findByTestId('statement-team-override');
    expect(text(team)).toContain('฿0.00');
    expect(text(team)).toContain('แทนที่ด้วยรายการที่บันทึกเอง (ดูรายการปรับปรุง)');
    expect(text(team)).not.toContain('ยังไม่ถึงเกณฑ์');
  });

  it('a recipient above the threshold WITH an amount: no reason line at all', async () => {
    api.commissions.monthlySummary.mockResolvedValue({
      summary: summary({
        teamOverrideAmount: 4875,
        companyCommissionableBase: 9500000,
        teamOverrideThresholdBase: 3000000,
        teamOverrideRatePercent: 0.075,
      }),
    });
    await openAugust();
    const team = await screen.findByTestId('statement-team-override');
    expect(text(team)).not.toContain('ยังไม่ถึงเกณฑ์');
    expect(text(team)).not.toContain('แทนที่ด้วยรายการที่บันทึกเอง');
  });

  it('WRONG-WAY: a rep who is not an override recipient never sees ค่าคอมทีม, even if a stray teamOverrideAmount arrives', async () => {
    // companyCommissionableBase null = not a recipient. The row must be gated on THAT, not on the
    // amount being non-zero -- a stray amount on a non-recipient must not surface as a line.
    api.commissions.monthlySummary.mockResolvedValue({
      summary: summary({ teamOverrideAmount: 999, companyCommissionableBase: null }),
    });
    await openAugust();
    await screen.findByTestId('statement-total');

    expect(screen.queryByTestId('statement-team-override')).toBeNull();
    expect(screen.queryByText(/ค่าคอมทีม/)).toBeNull();
    expect(screen.queryByText(/ยอดรับทั้งบริษัท/)).toBeNull();
  });

  it('belowFloor renders a clear warning that this round earns no tiered commission; absent otherwise', async () => {
    api.commissions.monthlySummary.mockResolvedValue({
      summary: summary({
        rawCommissionableBase: 40000,
        weightUpliftBase: 0,
        commissionableBase: 40000,
        tierCommission: 0,
        incentiveAmount: 0,
        stockBonusAmount: 0,
        manualTotal: 0,
        totalCommission: 0,
        belowFloor: true,
      }),
    });
    await openAugust();
    const warning = await screen.findByTestId('statement-below-floor');
    // The ฿ floor itself is a policy number owned by the backend (see the existing comment in
    // CommissionPage on MonthlyTierPanel), so the wording is pinned, not the figure.
    expect(text(warning)).toMatch(/ต่ำกว่า.*ยังไม่ได้ค่าคอมตามขั้น/);
  });

  it('no belowFloor warning when the base clears the floor', async () => {
    api.commissions.monthlySummary.mockResolvedValue({ summary: summary({ belowFloor: false }) });
    await openAugust();
    await screen.findByTestId('statement-total');
    expect(screen.queryByTestId('statement-below-floor')).toBeNull();
  });
});

describe('rep month header — payroll month N is paid from money received in N-1', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord()] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: summary() });
  });

  it('says "รอบจ่าย <N> · จากยอดรับเงินเดือน <N-1>"', async () => {
    await openAugust();
    const expected = `รอบจ่าย ${formatThaiMonthYearFromMonthInputValue('2026-08')} · จากยอดรับเงินเดือน ${formatThaiMonthYearFromMonthInputValue('2026-07')}`;
    expect(await screen.findByText(expected)).not.toBeNull();
    // Literal guard on the month names (the shared formatter abbreviates: ส.ค. / ก.ค.) so the
    // helper cannot drift on both sides of the assertion together.
    expect(expected).toBe('รอบจ่าย ส.ค. 2569 · จากยอดรับเงินเดือน ก.ค. 2569');
  });

  it('rolls the year back for a January payroll month (ม.ค. 2569 is paid from ธ.ค. 2568)', async () => {
    renderPage(salesUser);
    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    fireEvent.change(screen.getByLabelText('รอบเดือน'), { target: { value: '2026-01' } });
    const expected = `รอบจ่าย ${formatThaiMonthYearFromMonthInputValue('2026-01')} · จากยอดรับเงินเดือน ${formatThaiMonthYearFromMonthInputValue('2025-12')}`;
    expect(await screen.findByText(expected)).not.toBeNull();
  });
});

describe('rep tier-band disclosure', () => {
  it('is collapsed by default and, once opened, lists only the bands actually reached as range · rate · amount', async () => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord()] });
    api.commissions.monthlySummary.mockResolvedValue({
      summary: summary({
        tiers: [
          { tierNumber: 1, lowerBound: 0, upperBound: 250000, ratePercent: 0.25, highRoller: false, commission: 625 },
          { tierNumber: 2, lowerBound: 250000, upperBound: 500000, ratePercent: '0.5000', highRoller: false, commission: 1250 },
          { tierNumber: 3, lowerBound: 500000, upperBound: null, ratePercent: 0.75, highRoller: true, commission: 0 },
        ],
      }),
    });
    await openAugust();
    const disclosure = await screen.findByRole('button', { name: /ขั้นบันไดที่ได้รับ/ });
    expect(screen.queryByText('0.25%')).toBeNull();
    fireEvent.click(disclosure);
    expect(await screen.findByText('0.25%')).not.toBeNull();
    expect(screen.getByText('0.50%')).not.toBeNull();
    expect(screen.getByText('฿625.00')).not.toBeNull();
    expect(screen.getByText('฿1,250.00')).not.toBeNull();
    expect(screen.queryByText('0.75%')).toBeNull();
  });
});

describe('rep receipt ledger — where each figure comes from', () => {
  const plain = saleRecord({ id: 501, invoiceDetails: invoiceDetails({ id: 501, invoiceNumber: 'INV-PLAIN-501' }) });
  // Weight 2 (effective, item-derived). Figures are internally consistent:
  // 1,500,000 - 500 - 1,500 - 3,000 = 1,495,000 actual; / 1.07 = 1,397,196.26 ex-VAT; x2 = 2,794,392.52 into the base.
  const weighted = saleRecord({
    id: 503,
    status: 'SUBMITTED',
    actualReceived: 1495000,
    commissionableBase: 1397196.26,
    weightMultiplier: 1,
    effectiveWeightMultiplier: 2,
    invoiceDetails: invoiceDetails({
      id: 503,
      invoiceNumber: 'INV-WEIGHTED-503',
      grossAmount: 1500000,
      bankFees: 500,
      transportFee: 1500,
      withholdingTax: 3000,
    }),
  });

  // Weight set by hand at record level (no deal lines behind it): effective weight null, fallback 2.
  const managerSet = saleRecord({
    id: 504,
    weightMultiplier: 2,
    effectiveWeightMultiplier: null,
    invoiceDetails: invoiceDetails({ id: 504, invoiceNumber: 'INV-MGRSET-504' }),
  });

  beforeEach(() => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [plain, weighted, managerSet] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: summary() });
  });

  it('one row per receipt: invoice no. · Thai status · ex-VAT amount · weight chip only when != 1 · contribution to the base', async () => {
    await openAugust();
    const plainRow = await screen.findByTestId('receipt-row-501');
    const weightedRow = screen.getByTestId('receipt-row-503');

    expect(text(plainRow)).toContain('INV-PLAIN-501');
    expect(text(plainRow)).toContain('อนุมัติแล้ว');
    expect(text(plainRow)).toContain('฿3,000,000.00');
    expect(text(plainRow)).not.toContain('×'); // weight 1 -> no chip

    expect(text(weightedRow)).toContain('INV-WEIGHTED-503');
    expect(text(weightedRow)).toContain('รอผู้จัดการ');
    expect(text(weightedRow)).toContain('฿1,397,196.26'); // ยอดไม่รวม VAT
    expect(text(weightedRow)).toContain('×2'); // weight chip
    expect(text(weightedRow)).toContain('฿2,794,392.52'); // เข้าฐาน = ex-VAT x weight
  });

  it('explains in one line why a single sale has no baht commission of its own, and never shows one per row', async () => {
    await openAugust();
    await screen.findByTestId('receipt-row-501');
    expect(text(screen.getByTestId('no-per-sale-note'))).toMatch(/ขั้นบันได.*ยอดรวม.*เดือน/);
    // The ladder is marginal over the month, so a per-receipt baht figure would be invented.
    // None of the month's commission figures may appear inside a receipt row.
    ['฿48,750.00', '฿66,250.00', '฿15,000.00'].forEach((figure) => {
      expect(text(screen.getByTestId('receipt-row-501'))).not.toContain(figure);
      expect(text(screen.getByTestId('receipt-row-503'))).not.toContain(figure);
    });
  });

  it('expanding a receipt shows the whole chain: gross - each deduction + overpayment = actual ÷ VAT = ex-VAT × weight = into the base', async () => {
    await openAugust();
    const weightedRow = await screen.findByTestId('receipt-row-503');
    expect(screen.queryByTestId('receipt-chain-503')).toBeNull();

    fireEvent.click(within(weightedRow).getByRole('button', { name: 'ดูรายละเอียดการคำนวณ' }));
    const chain = await screen.findByTestId('receipt-chain-503');
    const chainText = text(chain);
    [
      ['ยอดตามใบกำกับ', '฿1,500,000.00'],
      ['ค่าธรรมเนียม', '฿500.00'],
      ['ค่าขนส่ง', '฿1,500.00'],
      ['หัก ณ ที่จ่าย', '฿3,000.00'],
      ['ยอดรับจริง', '฿1,495,000.00'],
      ['ยอดไม่รวม VAT', '฿1,397,196.26'],
      ['น้ำหนัก', '×2'],
      ['ที่เข้าฐาน', '฿2,794,392.52'],
    ].forEach(([label, value]) => {
      expect(chainText).toContain(label);
      expect(chainText).toContain(value);
    });
  });
});

describe('rep receipt ledger — a rejected or void receipt puts nothing into the base', () => {
  const rejected = saleRecord({
    id: 601,
    status: 'REJECTED',
    commissionableBase: 1000000,
    weightMultiplier: 2,
    invoiceDetails: invoiceDetails({ id: 601, invoiceNumber: 'INV-REJECTED-601' }),
  });
  const voided = saleRecord({
    id: 602,
    status: 'VOID',
    commissionableBase: 1000000,
    weightMultiplier: 2,
    invoiceDetails: invoiceDetails({ id: 602, invoiceNumber: 'INV-VOID-602' }),
  });
  const live = saleRecord({ id: 603, status: 'SUBMITTED', commissionableBase: 1000000, invoiceDetails: invoiceDetails({ id: 603, invoiceNumber: 'INV-LIVE-603' }) });

  beforeEach(() => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [rejected, voided, live] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: summary() });
  });

  it.each([[601], [602]])('receipt %s shows ไม่เข้าฐาน in its row and chain, never the positive contribution a x2 weight would give (฿2,000,000.00)', async (id) => {
    await openAugust();
    const rowEl = await screen.findByTestId(`receipt-row-${id}`);
    expect(text(rowEl)).toContain('ไม่เข้าฐาน');
    expect(text(rowEl)).not.toContain('฿2,000,000.00');
    fireEvent.click(within(rowEl).getByRole('button', { name: 'ดูรายละเอียดการคำนวณ' }));
    const chain = await screen.findByTestId(`receipt-chain-${id}`);
    expect(text(chain)).toContain('ไม่เข้าฐาน');
    expect(text(chain)).not.toContain('฿2,000,000.00');
  });

  it('a live (submitted) receipt still shows its contribution', async () => {
    await openAugust();
    const rowEl = await screen.findByTestId('receipt-row-603');
    expect(text(rowEl)).not.toContain('ไม่เข้าฐาน');
    expect(text(rowEl)).toContain('฿1,000,000.00');
  });
});

describe('rep receipt ledger — why a receipt is weighted, and the rounding caveat', () => {
  const plain = saleRecord({ id: 501, invoiceDetails: invoiceDetails({ id: 501, invoiceNumber: 'INV-PLAIN-501' }) });
  const fromDeal = saleRecord({
    id: 503,
    commissionableBase: 1000000,
    effectiveWeightMultiplier: 2,
    invoiceDetails: invoiceDetails({ id: 503, invoiceNumber: 'INV-FROMDEAL-503' }),
  });
  const managerSet = saleRecord({
    id: 504,
    commissionableBase: 1000000,
    weightMultiplier: 2,
    effectiveWeightMultiplier: null,
    invoiceDetails: invoiceDetails({ id: 504, invoiceNumber: 'INV-MGRSET-504' }),
  });

  beforeEach(() => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [plain, fromDeal, managerSet] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: summary() });
  });

  async function expand(id) {
    const rowEl = await screen.findByTestId(`receipt-row-${id}`);
    fireEvent.click(within(rowEl).getByRole('button', { name: 'ดูรายละเอียดการคำนวณ' }));
    return screen.findByTestId(`receipt-chain-${id}`);
  }

  it('an item-derived weight (effectiveWeightMultiplier non-null) is explained as stock-weighted, import lines at ×1', async () => {
    await openAugust();
    const chain = await expand(503);
    expect(text(chain)).toContain('น้ำหนักถ่วงจากรายการสต็อกของดีล (ของสั่งคิด ×1)');
    expect(text(chain)).not.toContain('น้ำหนักที่ผู้จัดการกำหนด');
  });

  it('a weight that exists only at record level (weightMultiplier != 1, no effective weight) is explained as manager-set', async () => {
    await openAugust();
    const chain = await expand(504);
    expect(text(chain)).toContain('น้ำหนักที่ผู้จัดการกำหนด');
    expect(text(chain)).not.toContain('น้ำหนักถ่วงจากรายการสต็อกของดีล');
  });

  it('a weight-1 receipt carries no weight explanation at all', async () => {
    await openAugust();
    const chain = await expand(501);
    expect(text(chain)).not.toContain('น้ำหนักถ่วงจากรายการสต็อกของดีล');
    expect(text(chain)).not.toContain('น้ำหนักที่ผู้จัดการกำหนด');
  });

  it('says once that per-receipt figures are rounded, so their sum can differ from ฐานคิดค่าคอม by a few satang', async () => {
    await openAugust();
    await screen.findByTestId('receipt-row-501');
    expect(text(screen.getByTestId('receipt-rounding-note'))).toMatch(/ปัดเศษ.*ต่างจาก.*ฐานคิดค่าคอม.*สตางค์/);
  });
});

describe('WRONG-WAY: what a sales rep is NOT offered', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord({ status: 'SUBMITTED' })] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: summary() });
  });

  it('a sales rep sees NO weighting controls and NO rep picker, on the default view or via ?view=pending', async () => {
    for (const url of ['/commissions', '/commissions?view=pending']) {
      const { unmount } = renderPage(salesUser, url);
      await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
      await screen.findByTestId('statement-total');

      expect(screen.queryAllByRole('radiogroup')).toHaveLength(0);
      expect(screen.queryAllByRole('radio')).toHaveLength(0);
      expect(screen.queryByLabelText('เลือกพนักงานขาย')).toBeNull();
      expect(screen.queryByText('สรุปรายคน')).toBeNull();
      expect(screen.queryByText(/สต็อกทั้งหมด ×2/)).toBeNull();
      expect(api.commissions.adjustItemWeights).not.toHaveBeenCalled();
      expect(api.commissions.pendingApproval).not.toHaveBeenCalled();
      expect(api.commissions.reps).not.toHaveBeenCalled();
      unmount();
    }
  });
});

describe('manager / CEO: "สรุปรายคน" re-uses the rep statement for a chosen rep', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [] });
    api.commissions.pendingApproval.mockResolvedValue({ commissions: [] });
    api.commissions.reps.mockResolvedValue({ reps: [{ id: 10, name: 'พนักงานขาย ทดสอบ' }, { id: 11, name: 'อีกคนหนึ่ง' }] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: summary({ salesRepId: 10 }) });
  });

  it.each([
    ['sales_manager', salesManagerUser],
    ['ceo', ceoUser],
  ])('%s picks a rep from the reps endpoint and gets that rep\'s statement via monthly-summary(salesRepId)', async (_role, user) => {
    renderPage(user);
    await waitFor(() => expect(api.commissions.reps).toHaveBeenCalled());
    fireEvent.change(screen.getByLabelText('รอบเดือน'), { target: { value: '2026-08' } });

    const picker = await screen.findByLabelText('เลือกพนักงานขาย');
    await within(picker).findByText('พนักงานขาย ทดสอบ');
    fireEvent.change(picker, { target: { value: '10' } });

    await waitFor(() => expect(api.commissions.monthlySummary).toHaveBeenCalledWith(
      expect.objectContaining({ payrollMonth: '2026-08', salesRepId: 10 }),
    ));
    const total = await screen.findByTestId('statement-total');
    expect(text(total)).toContain('฿66,250.00');
    expect(text(screen.getByTestId('statement-incentive'))).toContain('฿15,000.00');
  });
});

describe('manager worklist-first — tab removal (deliberate rewrite of the old "รออนุมัติ tab" test)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord({ id: 801, invoiceDetails: invoiceDetails({ id: 801, invoiceNumber: 'INV-OTHER-APPROVED' }) })] });
    api.commissions.reps.mockResolvedValue({ reps: [] });
    api.commissions.pendingApproval.mockResolvedValue({ commissions: [] });
  });

  it('sales_manager has NO ทั้งหมด/รออนุมัติ tab strip: the worklist is simply the top of the page, with the month\'s records below it', async () => {
    renderPage(salesManagerUser);
    expect(await screen.findByText('INV-OTHER-APPROVED')).not.toBeNull();
    expect(screen.queryByRole('tab', { name: /รออนุมัติ/ })).toBeNull();
    expect(screen.getByRole('heading', { name: 'รอคุณอนุมัติ (0)' })).not.toBeNull();
  });

  it('?view=pending stays a working deep link (the notification target): the worklist alone, without the month\'s records', async () => {
    renderPage(salesManagerUser, '/commissions?view=pending');
    expect(await screen.findByRole('heading', { name: 'รอคุณอนุมัติ (0)' })).not.toBeNull();
    expect(screen.queryByText('INV-OTHER-APPROVED')).toBeNull();
    expect(screen.queryByRole('heading', { name: 'สรุปรายคน' })).toBeNull();
  });

  it('CEO keeps the ทั้งหมด / รออนุมัติ tabs (her queue is the MANAGER_APPROVED records, not the weighting desk)', async () => {
    renderPage(ceoUser);
    expect(await screen.findByRole('tab', { name: /รออนุมัติ/ })).not.toBeNull();
    expect(screen.queryByRole('heading', { name: /รอคุณอนุมัติ/ })).toBeNull();
  });
});

describe('manager worklist-first', () => {
  it('the default manager page leads with "รอคุณอนุมัติ (n)", ahead of the per-rep summary', async () => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [] });
    api.commissions.reps.mockResolvedValue({ reps: [{ id: 10, name: 'พนักงานขาย ทดสอบ' }] });
    api.commissions.pendingApproval.mockResolvedValue({
      commissions: [{
        commission: saleRecord({ id: 701, status: 'SUBMITTED', invoiceDetails: invoiceDetails({ id: 701, invoiceNumber: 'INV-PEND-701' }) }),
        ticketCode: 'TCK-1',
        customerName: 'ลูกค้า',
        items: [],
        effectiveWeight: 1,
        weightedCommissionableBase: 3000000,
        estimatedCommission: 1,
      }],
    });
    renderPage(salesManagerUser);

    const worklist = await screen.findByRole('heading', { name: 'รอคุณอนุมัติ (1)' });
    const byRep = await screen.findByRole('heading', { name: 'สรุปรายคน' });
    expect(worklist.compareDocumentPosition(byRep) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(api.commissions.pendingApproval).toHaveBeenCalled();
  });
});

describe('hr payroll summary — ค่าคอมทีม', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows a ค่าคอมทีม column with each rep\'s amount and a total', async () => {
    api.commissions.payrollReady.mockResolvedValue({
      summary: {
        payrollMonth: '2026-08-01',
        status: 'PAYROLL_READY',
        totalCommissionableBase: 6000000,
        totalCommissionAmount: 140000,
        totalIncentiveAmount: 15000,
        totalStockBonusAmount: 0,
        totalTeamOverrideAmount: 1234.56,
        companyCommissionableBase: 9500000,
        salesReps: [
          {
            salesRepId: 10,
            salesRepName: 'เจนเนตร',
            commissionableBase: 3000000,
            commissionAmount: 70000,
            manualAdjustmentAmount: 0,
            incentiveAmount: 15000,
            stockBonusAmount: 0,
            teamOverrideAmount: 1234.56,
          },
          {
            salesRepId: 11,
            salesRepName: 'อีกคนหนึ่ง',
            commissionableBase: 3000000,
            commissionAmount: 70000,
            manualAdjustmentAmount: 0,
            incentiveAmount: 0,
            stockBonusAmount: 0,
            teamOverrideAmount: 0,
          },
        ],
      },
    });
    renderPage(hrUser);

    expect((await screen.findAllByText('ค่าคอมทีม')).length).toBeGreaterThan(0);
    expect(await screen.findByText('เจนเนตร')).not.toBeNull();
    expect(screen.getAllByText('฿1,234.56').length).toBeGreaterThan(0); // the recipient's cell
    expect(text(screen.getByTestId('payroll-team-override-total'))).toContain('฿1,234.56');
  });
});
