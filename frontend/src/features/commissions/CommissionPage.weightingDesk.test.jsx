import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CommissionPage } from './CommissionPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Commission page redesign (branch feat/commission-page-redesign), STEP 1: tests first.
//
// Pins the sales manager's WEIGHTING DESK: each pending line gets a ×1/×2/×3 segmented control
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

// Two stock lines and one import line, so "all stock" and "import stays untouched" are both
// observable in one fixture.
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

// Server echo: applies the submitted lines to the current weights and returns a fresh entry with
// a recognisably different weighted base, so a refresh from the response is observable.
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

async function openDesk() {
  renderAt(salesManagerUser, '/commissions?view=pending');
  // Wait on the record's own text, not on any new test id, so a failure below is about the
  // missing BEHAVIOUR rather than about this setup line.
  await screen.findByText('INV-PEND-0701');
}

describe('weighting desk — ×1/×2/×3 segmented control per line (sales_manager)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [] });
    api.commissions.reps.mockResolvedValue({ reps: [] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: null });
    api.commissions.pendingApproval.mockResolvedValue({ commissions: [pendingDto()] });
  });

  it('clicking ×2 on a stock line calls adjustItemWeights(id, {lines:[{itemId, weightMultiplier:2}]}) and refreshes the figures from the response', async () => {
    echoAdjust();
    await openDesk();
    fireEvent.click(radio(STOCK_A, '×2'));
    const entry = screen.getByTestId('pending-entry-701');

    await waitFor(() => expect(api.commissions.adjustItemWeights).toHaveBeenCalledTimes(1));
    expect(api.commissions.adjustItemWeights).toHaveBeenCalledWith(701, { lines: [{ itemId: 11, weightMultiplier: 2 }] });
    // "ฐานก่อนถ่วง → ฐานหลังถ่วง" refreshes from the SERVER's response, never recomputed locally.
    await waitFor(() => expect(entry.textContent).toContain('฿5,600,000.00'));
    expect(entry.textContent).not.toContain('฿4,800,000.00');
    expect(entry.textContent).toContain('฿4,000,000.00'); // the before-weighting base is unchanged
    expect(isChecked(radio(STOCK_A, '×2'))).toBe(true);
  });

  it('is OPTIMISTIC: the segment flips at once, while the request is still in flight', async () => {
    let resolve;
    api.commissions.adjustItemWeights.mockImplementation(() => new Promise((r) => { resolve = r; }));
    await openDesk();

    fireEvent.click(radio(STOCK_A, '×3'));

    // No await for the request: the UI already shows ×3.
    expect(isChecked(radio(STOCK_A, '×3'))).toBe(true);
    expect(isChecked(radio(STOCK_A, '×1'))).toBe(false);

    await act(async () => { resolve({ pending: pendingDto({ 11: 3 }) }); });
  });

  it('per-line saving state: only the line being saved is disabled, its siblings stay usable', async () => {
    let resolve;
    api.commissions.adjustItemWeights.mockImplementation(() => new Promise((r) => { resolve = r; }));
    await openDesk();

    fireEvent.click(radio(STOCK_A, '×2'));

    await waitFor(() => {
      within(group(STOCK_A)).getAllByRole('radio').forEach((r) => expect(isDisabled(r)).toBe(true));
    });
    within(group(STOCK_C)).getAllByRole('radio').forEach((r) => expect(isDisabled(r)).toBe(false));

    await act(async () => { resolve({ pending: pendingDto({ 11: 2 }) }); });
    await waitFor(() => {
      within(group(STOCK_A)).getAllByRole('radio').forEach((r) => expect(isDisabled(r)).toBe(false));
    });
  });

  it('an import line (qtyFromStock 0) is locked at ×1 and says "ของสั่ง — ไม่คิด 2x"; clicking it never calls the API', async () => {
    await openDesk();

    expect(isChecked(radio(IMPORT_B, '×1'))).toBe(true);
    const entry = screen.getByTestId('pending-entry-701');
    expect(within(entry).getByText('ของสั่ง — ไม่คิด 2x')).not.toBeNull();
    within(group(IMPORT_B)).getAllByRole('radio').forEach((r) => expect(isDisabled(r)).toBe(true));

    fireEvent.click(radio(IMPORT_B, '×2'));
    fireEvent.click(radio(IMPORT_B, '×3'));
    expect(api.commissions.adjustItemWeights).not.toHaveBeenCalled();
    expect(isChecked(radio(IMPORT_B, '×1'))).toBe(true);

    // And a stock line is labelled with how much of it came from stock.
    expect(within(entry).getByText('สต็อก 30/120')).not.toBeNull();
    expect(within(entry).getByText('สต็อก 10/10')).not.toBeNull();
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

  it('a failed request reverts the segment and shows an inline error that names the line', async () => {
    api.commissions.adjustItemWeights.mockRejectedValue(new Error('เซิร์ฟเวอร์ปฏิเสธ'));
    await openDesk();

    fireEvent.click(radio(STOCK_A, '×2'));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain(STOCK_A);
    await waitFor(() => expect(isChecked(radio(STOCK_A, '×1'))).toBe(true));
    expect(isChecked(radio(STOCK_A, '×2'))).toBe(false);
    // The figures were never touched by the failed attempt.
    expect(screen.getByTestId('pending-entry-701').textContent).toContain('฿4,800,000.00');
    // The line is usable again after the failure.
    within(group(STOCK_A)).getAllByRole('radio').forEach((r) => expect(isDisabled(r)).toBe(false));
  });

  it('keyboard: arrow keys move the selection (and focus) along ×1 → ×2 → ×3 and back', async () => {
    echoAdjust();
    await openDesk();

    radio(STOCK_A, '×1').focus();
    fireEvent.keyDown(radio(STOCK_A, '×1'), { key: 'ArrowRight' });
    await waitFor(() => expect(isChecked(radio(STOCK_A, '×2'))).toBe(true));
    expect(api.commissions.adjustItemWeights).toHaveBeenLastCalledWith(701, { lines: [{ itemId: 11, weightMultiplier: 2 }] });
    await waitFor(() => expect(document.activeElement).toBe(radio(STOCK_A, '×2')));
    // The line is locked while its own request is in flight; let it settle before the next key.
    await waitFor(() => expect(isDisabled(radio(STOCK_A, '×2'))).toBe(false));

    fireEvent.keyDown(radio(STOCK_A, '×2'), { key: 'ArrowRight' });
    await waitFor(() => expect(isChecked(radio(STOCK_A, '×3'))).toBe(true));
    expect(api.commissions.adjustItemWeights).toHaveBeenLastCalledWith(701, { lines: [{ itemId: 11, weightMultiplier: 3 }] });
    await waitFor(() => expect(isDisabled(radio(STOCK_A, '×3'))).toBe(false));

    fireEvent.keyDown(radio(STOCK_A, '×3'), { key: 'ArrowLeft' });
    await waitFor(() => expect(isChecked(radio(STOCK_A, '×2'))).toBe(true));
    expect(api.commissions.adjustItemWeights).toHaveBeenLastCalledWith(701, { lines: [{ itemId: 11, weightMultiplier: 2 }] });
  });

  it('keyboard: Enter and Space select the focused segment', async () => {
    echoAdjust();
    await openDesk();

    radio(STOCK_A, '×3').focus();
    fireEvent.keyDown(radio(STOCK_A, '×3'), { key: 'Enter' });
    await waitFor(() => expect(api.commissions.adjustItemWeights).toHaveBeenLastCalledWith(701, { lines: [{ itemId: 11, weightMultiplier: 3 }] }));
    await waitFor(() => expect(isChecked(radio(STOCK_A, '×3'))).toBe(true));

    radio(STOCK_C, '×2').focus();
    fireEvent.keyDown(radio(STOCK_C, '×2'), { key: ' ' });
    await waitFor(() => expect(api.commissions.adjustItemWeights).toHaveBeenLastCalledWith(701, { lines: [{ itemId: 13, weightMultiplier: 2 }] }));
  });

  it('approve and reject stay in the entry footer; reject still asks for a reason', async () => {
    // Guard on behaviour the redesign must keep (passes before AND after the change).
    await openDesk();
    expect(screen.getByRole('button', { name: 'ผู้จัดการอนุมัติ' })).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'ไม่อนุมัติ' }));
    expect(await screen.findByText('เหตุผลการปฏิเสธ')).not.toBeNull();
  });
});

describe('record-level weight (records with NO deal lines) uses the same segmented control', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // A legacy / directly-submitted SUBMITTED record: no deal lines, so no pending entry to weight
    // line by line -- the record-level weightMultiplier in the edit panel is the only lever.
    api.commissions.list.mockResolvedValue({ commissions: [submittedRecord({ id: 601, invoiceDetails: invoiceDetails({ id: 601, invoiceNumber: 'INV-LEGACY-601' }) })] });
    api.commissions.pendingApproval.mockResolvedValue({ commissions: [] });
    api.commissions.reps.mockResolvedValue({ reps: [] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: null });
    api.commissions.simulate.mockResolvedValue({ simulation: { actualReceived: 4280000, commissionableBase: 4000000 } });
    api.commissions.updateDeductions.mockResolvedValue({});
  });

  it('the edit panel offers a ×1/×2/×3 radiogroup instead of a <select>, and saving sends the chosen weightMultiplier', async () => {
    renderAt(salesManagerUser, '/commissions');
    await screen.findByText('INV-LEGACY-601');
    fireEvent.click(screen.getByRole('button', { name: 'แก้ไขค่าหัก' }));

    const weightGroup = await screen.findByRole('radiogroup', { name: 'น้ำหนักฐานคอม' });
    // Inside the edit panel only: the page's own rep picker is (legitimately) a combobox.
    const panel = screen.getByRole('heading', { name: /แก้ไขข้อมูลใบกำกับ/ }).closest('section');
    expect(within(panel).queryAllByRole('combobox')).toHaveLength(0);
    expect(within(weightGroup).getAllByRole('radio').map((r) => r.textContent.trim())).toEqual(['×1', '×2', '×3']);
    expect(isChecked(within(weightGroup).getByRole('radio', { name: '×1' }))).toBe(true);

    fireEvent.click(within(weightGroup).getByRole('radio', { name: '×2' }));
    expect(isChecked(within(weightGroup).getByRole('radio', { name: '×2' }))).toBe(true);

    fireEvent.change(screen.getByLabelText(/เหตุผลในการแก้ไข/), { target: { value: 'ปรับน้ำหนักตามสต็อก' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.commissions.updateDeductions).toHaveBeenCalledTimes(1));
    expect(api.commissions.updateDeductions).toHaveBeenCalledWith(601, expect.objectContaining({
      weightMultiplier: 2,
      reason: 'ปรับน้ำหนักตามสต็อก',
    }));
  });
});
