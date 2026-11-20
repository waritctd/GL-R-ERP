import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CommissionPage } from './CommissionPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Commission approval redesign: the weighting desk is no longer a page of cards. Its ×1/×2/×3 control
// lives in the SINGLE detail panel that opens when a queue row (or an all-records row) is expanded,
// region "สินค้าและน้ำหนัก". Every behaviour pinned below is unchanged; only the way to reach it moved.
//
// Pins the weighting controls: each pending line gets a ×1/×2/×3 segmented control
// (role="radiogroup"), import lines are locked at ×1, there is one "สต็อกทั้งหมด ×2" quick action,
// changes are optimistic with per-line saving state and revert-on-error, and the record-level
// weight fallback (records with no deal lines) uses the same control.
//
// MOCK-DRIVEN: api.commissions.* is stubbed. This proves the client's plumbing (what it sends,
// how it reacts to the response). It is NOT evidence about the server-side weight maths, nor about
// who may POST item-weights (sales_manager only is a Java CommissionService rule -- the CEO and
// rep cases below pin what the UI OFFERS, not what the service ENFORCES; authz UNVERIFIED here).
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      commissions: {
        list: vi.fn(),
        payrollReady: vi.fn(),
        createFromDeal: vi.fn(),
        monthlySummary: vi.fn().mockResolvedValue({ summary: null }),
        simulate: vi.fn(),
        pendingApproval: vi.fn(),
        adjustItemWeights: vi.fn(),
        updateDeductions: vi.fn(),
        approve: vi.fn(),
        reject: vi.fn(),
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

const salesManagerUser = { id: 30, employeeId: 30, name: 'ผู้จัดการฝ่ายขาย ทดสอบ', role: 'sales_manager' };
const ceoUser = { id: 50, employeeId: 50, name: 'CEO ทดสอบ', role: 'ceo' };

// Only `Date` is faked: the page opens on the current payroll month (Aug 2026, matching the
// fixtures' payrollMonth) and the queue shows that month only.
beforeEach(() => {
  vi.useRealTimers();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-08-15T05:00:00Z'));
});
afterEach(() => { vi.useRealTimers(); });

const isChecked = (el) => el.checked === true || el.getAttribute('aria-checked') === 'true';
const isDisabled = (el) => el.disabled === true || el.getAttribute('aria-disabled') === 'true';

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

function invoiceDetails(overrides = {}) {
  return {
    id: 701,
    invoiceNumber: 'INV-PEND-0701',
    invoiceDate: '2026-07-20',
    grossAmount: 4280000,
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

function submittedRecord(overrides = {}) {
  return {
    id: 701,
    kind: 'SALE',
    status: 'SUBMITTED',
    salesRepId: 10,
    salesRepName: 'พนักงานขาย ทดสอบ',
    submittedById: 20,
    payrollMonth: '2026-08-01',
    actualReceived: 4280000,
    commissionableBase: 4000000,
    weightMultiplier: 1,
    effectiveWeightMultiplier: null,
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

const STOCK_A = 'PADANA 60x60 ผิวเงา'; // itemId 11: 30 of 120 from stock
const IMPORT_B = 'PADANA 30x60 ผิวด้าน'; // itemId 12: nothing from stock (ของสั่ง)
const STOCK_C = 'PADANA 80x80 ผิวด้าน'; // itemId 13: 10 of 10 from stock

function line(itemId, description, qty, qtyFromStock, weightMultiplier = 1) {
  return { itemId, description, qty, qtyFromStock, weightMultiplier };
}

// Two stock lines and one import line, so "all stock" and "import stays untouched" are both observable in one
// fixture.
function pendingDto(weights = {}, over = {}) {
  return {
    commission: submittedRecord(),
    ticketCode: 'TCK-0077',
    customerName: 'บริษัท รออนุมัติ จำกัด',
    items: [
      line(11, STOCK_A, 120, 30, weights[11] ?? 1),
      line(12, IMPORT_B, 40, 0, 1),
      line(13, STOCK_C, 10, 10, weights[13] ?? 1),
    ],
    effectiveWeight: 1.6,
    weightedCommissionableBase: 4800000,
    estimatedCommission: 95000.5,
    ...over,
  };
}

// Server echo: applies the submitted lines to the current weights and returns a fresh entry with a
// recognisably different weighted base, so a refresh from the response is observable.
function echoAdjust() {
  const weights = {};
  api.commissions.adjustItemWeights.mockImplementation(async (_id, payload) => {
    payload.lines.forEach((l) => { weights[l.itemId] = l.weightMultiplier; });
    return {
      pending: pendingDto(weights, { weightedCommissionableBase: 5600000, estimatedCommission: 120000.25, effectiveWeight: 2.05 }),
    };
  });
}

const group = (description) => screen.getByRole('radiogroup', { name: `น้ำหนัก ${description}` });
const radio = (description, label) => within(group(description)).getByRole('radio', { name: label });

// The queue row's chevron opens the detail panel; the weights live in its "สินค้าและน้ำหนัก" region.
async function openDesk(user = salesManagerUser) {
  renderAt(user, '/commissions');
  const row = await screen.findByTestId('queue-row-701');
  fireEvent.click(within(row).getByRole('button', { name: 'ดูรายละเอียด INV-PEND-0701' }));
  return screen.findByTestId('detail-panel-701');
}
// The weighted base shown on the queue row (the SERVER's figure, never recomputed here).
const queueRow = () => screen.getByTestId('queue-row-701');

describe('weights in the detail panel — ×1/×2/×3 segmented control per line (sales_manager)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [] });
    api.commissions.reps.mockResolvedValue({ reps: [] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: null });
    api.commissions.pendingApproval.mockResolvedValue({ commissions: [pendingDto()] });
  });

  it('clicking ×2 on a stock line calls adjustItemWeights(id, {lines:[{itemId, weightMultiplier:2}]}) and refreshes the figures from the response', async () => {
    echoAdjust();
    const panel = await openDesk();
    fireEvent.click(radio(STOCK_A, '×2'));

    await waitFor(() => expect(api.commissions.adjustItemWeights).toHaveBeenCalledTimes(1));
    expect(api.commissions.adjustItemWeights).toHaveBeenCalledWith(701, { lines: [{ itemId: 11, weightMultiplier: 2 }] });
    // The weighted base on the queue row refreshes from the SERVER's response, never recomputed locally.
    await waitFor(() => expect(queueRow().textContent).toContain('฿5,600,000.00'));
    expect(queueRow().textContent).not.toContain('฿4,800,000.00');
    expect(panel.textContent).toContain('฿4,000,000.00'); // the before-weighting (ex-VAT) base in the money chain is unchanged
    expect(isChecked(radio(STOCK_A, '×2'))).toBe(true);
  });

  it('an import line (qtyFromStock 0) is locked at ×1 and says it counts ×1; clicking it never calls the API', async () => {
    const entry = await openDesk();

    expect(isChecked(radio(IMPORT_B, '×1'))).toBe(true);
    // Prototype copy: "จำนวน 40 · ของสั่ง/นำเข้า คิด ×1" and "จำนวน 120 · จากสต็อก 30/120".
    expect(within(entry).getByText(/ของสั่ง\/นำเข้า คิด ×1/)).not.toBeNull();
    within(group(IMPORT_B)).getAllByRole('radio').forEach((r) => expect(isDisabled(r)).toBe(true));

    fireEvent.click(radio(IMPORT_B, '×2'));
    fireEvent.click(radio(IMPORT_B, '×3'));
    expect(api.commissions.adjustItemWeights).not.toHaveBeenCalled();
    expect(isChecked(radio(IMPORT_B, '×1'))).toBe(true);

    // And a stock line is labelled with how much of it came from stock.
    expect(within(entry).getByText(/จากสต็อก 30\/120/)).not.toBeNull();
    expect(within(entry).getByText(/จากสต็อก 10\/10/)).not.toBeNull();
    // The stock lines are NOT locked.
    within(group(STOCK_A)).getAllByRole('radio').forEach((r) => expect(isDisabled(r)).toBe(false));
  });

  it('"สต็อกทั้งหมด ×2" sends ONE request carrying every stock line and no import line', async () => {
    echoAdjust();
    await openDesk();

    fireEvent.click(screen.getByRole('button', { name: 'สต็อกทั้งหมด ×2' }));

    await waitFor(() => expect(api.commissions.adjustItemWeights).toHaveBeenCalledTimes(1));
    const [id, payload] = api.commissions.adjustItemWeights.mock.calls[0];
    expect(id).toBe(701);
    expect(payload.lines).toEqual([
      { itemId: 11, weightMultiplier: 2 },
      { itemId: 13, weightMultiplier: 2 },
    ]);
    expect(payload.lines.some((l) => l.itemId === 12)).toBe(false); // the import line never rides along
    await waitFor(() => expect(isChecked(radio(STOCK_A, '×2'))).toBe(true));
    expect(isChecked(radio(STOCK_C, '×2'))).toBe(true);
    expect(isChecked(radio(IMPORT_B, '×1'))).toBe(true);
  });
});

describe('who can change weights, and when the weights section appears', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [] });
    api.commissions.reps.mockResolvedValue({ reps: [] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: null });
  });

  it('CANNOT REACH: the CEO sees the weights of a MANAGER_APPROVED item read-only -- disabled segments, a locked note, no bulk button, no API call', async () => {
    const managerApproved = submittedRecord({
      status: 'MANAGER_APPROVED', managerApprovedBy: 2, managerApprovedByName: 'สมหญิง', managerApprovedAt: '2026-08-05T05:00:00Z',
    });
    api.commissions.pendingApproval.mockResolvedValue({ commissions: [pendingDto({}, { commission: managerApproved })] });

    const panel = await openDesk(ceoUser);

    const products = within(panel).getByRole('region', { name: 'สินค้าและน้ำหนัก' });
    expect(within(products).getByText('ดูอย่างเดียว · น้ำหนักล็อกแล้วหลังผู้จัดการอนุมัติ')).not.toBeNull();
    expect(within(products).queryByRole('button', { name: 'สต็อกทั้งหมด ×2' })).toBeNull();
    const radios = within(products).getAllByRole('radio');
    expect(radios.length).toBeGreaterThan(0);
    radios.forEach((r) => expect(isDisabled(r)).toBe(true));
    fireEvent.click(radio(STOCK_A, '×2'));
    expect(isChecked(radio(STOCK_A, '×2'))).toBe(false);
    expect(api.commissions.adjustItemWeights).not.toHaveBeenCalled();
  });
});
