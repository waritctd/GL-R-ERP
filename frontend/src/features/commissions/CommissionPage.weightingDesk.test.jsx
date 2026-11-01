import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

  // Review fix (HIGH): two parallel item-weights writes to ONE entry race in the backend (no row
  // lock) and the frozen effective weight can ignore a line, and the later response could overwrite
  // the earlier one. So the WHOLE entry is locked while any of its lines is saving -- siblings and
  // the bulk button included. (This replaces the earlier "only that line is disabled" test.)
  it('locks the WHOLE entry while one line saves: sibling lines and the bulk button are disabled, and a click on a sibling sends nothing', async () => {
    let resolve;
    api.commissions.adjustItemWeights.mockImplementation(() => new Promise((r) => { resolve = r; }));
    await openDesk();

    fireEvent.click(radio(STOCK_A, '×2'));

    await waitFor(() => {
      within(group(STOCK_A)).getAllByRole('radio').forEach((r) => expect(isDisabled(r)).toBe(true));
    });
    within(group(STOCK_C)).getAllByRole('radio').forEach((r) => expect(isDisabled(r)).toBe(true));
    expect(isDisabled(screen.getByRole('button', { name: 'สต็อกทั้งหมด ×2' }))).toBe(true);

    fireEvent.click(radio(STOCK_C, '×2'));
    fireEvent.click(screen.getByRole('button', { name: 'สต็อกทั้งหมด ×2' }));
    expect(api.commissions.adjustItemWeights).toHaveBeenCalledTimes(1);
    expect(isChecked(radio(STOCK_C, '×2'))).toBe(false); // no optimistic flip either

    await act(async () => { resolve({ pending: pendingDto({ 11: 2 }) }); });
    await waitFor(() => {
      within(group(STOCK_C)).getAllByRole('radio').forEach((r) => expect(isDisabled(r)).toBe(false));
    });
    expect(isDisabled(screen.getByRole('button', { name: 'สต็อกทั้งหมด ×2' }))).toBe(false);
  });

  it('a saving line is visibly different from a locked one: it keeps its selected tint (aria-checked) and says "กำลังบันทึก…"', async () => {
    let resolve;
    api.commissions.adjustItemWeights.mockImplementation(() => new Promise((r) => { resolve = r; }));
    await openDesk();
    // The import line is locked and says nothing about saving.
    expect(within(group(IMPORT_B).closest('li')).queryByText('กำลังบันทึก…')).toBeNull();

    fireEvent.click(radio(STOCK_A, '×2'));

    expect(await within(group(STOCK_A).closest('li')).findByText('กำลังบันทึก…')).not.toBeNull();
    expect(isChecked(radio(STOCK_A, '×2'))).toBe(true);
    expect(group(STOCK_A).getAttribute('aria-busy')).toBe('true');
    expect(group(IMPORT_B).getAttribute('aria-busy')).toBeNull();

    await act(async () => { resolve({ pending: pendingDto({ 11: 2 }) }); });
  });

  // Review fix (MEDIUM): focus used to move to the next segment BEFORE select() refused the change,
  // leaving focus on the old segment while the new one was checked.
  it('keyboard during a save: focus stays put and nothing is selected or sent', async () => {
    let resolve;
    api.commissions.adjustItemWeights.mockImplementation(() => new Promise((r) => { resolve = r; }));
    await openDesk();
    fireEvent.click(radio(STOCK_A, '×2'));
    await waitFor(() => expect(isDisabled(radio(STOCK_A, '×2'))).toBe(true));

    radio(STOCK_A, '×2').focus();
    fireEvent.keyDown(radio(STOCK_A, '×2'), { key: 'ArrowRight' });

    expect(document.activeElement).toBe(radio(STOCK_A, '×2'));
    expect(isChecked(radio(STOCK_A, '×3'))).toBe(false);
    expect(api.commissions.adjustItemWeights).toHaveBeenCalledTimes(1);

    await act(async () => { resolve({ pending: pendingDto({ 11: 2 }) }); });
  });

  it('keyboard on a locked (import) line: arrow keys do not move focus off the checked segment', async () => {
    await openDesk();
    radio(IMPORT_B, '×1').focus();
    fireEvent.keyDown(radio(IMPORT_B, '×1'), { key: 'ArrowRight' });
    expect(document.activeElement).toBe(radio(IMPORT_B, '×1'));
    expect(isChecked(radio(IMPORT_B, '×1'))).toBe(true);
    expect(api.commissions.adjustItemWeights).not.toHaveBeenCalled();
  });

  it('"สต็อกทั้งหมด ×2" never LOWERS a line that is already ×3: it is skipped', async () => {
    api.commissions.pendingApproval.mockResolvedValue({ commissions: [pendingDto({ 11: 3 })] });
    echoAdjust();
    await openDesk();

    fireEvent.click(screen.getByRole('button', { name: 'สต็อกทั้งหมด ×2' }));

    await waitFor(() => expect(api.commissions.adjustItemWeights).toHaveBeenCalledTimes(1));
    expect(api.commissions.adjustItemWeights.mock.calls[0][1].lines).toEqual([{ itemId: 13, weightMultiplier: 2 }]);
  });

  // Review fix (MEDIUM-LOW): the month table and the picked rep's statement were stale after a
  // weight change, because only the pending entry was refreshed.
  it("after a weight change, reloads the month's records and the picked rep's statement", async () => {
    echoAdjust();
    api.commissions.list.mockResolvedValue({ commissions: [submittedRecord({ id: 801, status: 'APPROVED', invoiceDetails: invoiceDetails({ id: 801, invoiceNumber: 'INV-OK-801' }) })] });
    api.commissions.monthlySummary.mockResolvedValue({
      summary: {
        payrollMonth: '2026-08-01', salesRepId: 10, rawCommissionableBase: 0, weightUpliftBase: 0, commissionableBase: 4000000,
        tierCommission: 1, incentiveAmount: 0, stockBonusAmount: 0, teamOverrideAmount: 0, companyCommissionableBase: null,
        teamOverrideThresholdBase: null, teamOverrideRatePercent: null, manualTotal: 0, totalCommission: 1000, belowFloor: false, tiers: [],
      },
    });
    const panel = await openDesk();
    const repRegion = await screen.findByRole('region', { name: 'สรุปรายคน · ส.ค. 2569' });
    fireEvent.click(await within(repRegion).findByText('พนักงานขาย ทดสอบ'));
    await screen.findByTestId('statement-total');
    expect(panel).not.toBeNull();
    const listCalls = api.commissions.list.mock.calls.length;
    const summaryCalls = api.commissions.monthlySummary.mock.calls.length;

    fireEvent.click(radio(STOCK_A, '×2'));

    await waitFor(() => expect(api.commissions.list.mock.calls.length).toBeGreaterThan(listCalls));
    await waitFor(() => expect(api.commissions.monthlySummary.mock.calls.length).toBeGreaterThan(summaryCalls));
    expect(api.commissions.monthlySummary).toHaveBeenLastCalledWith(expect.objectContaining({ salesRepId: 10 }));
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

  it('a failed request reverts the segment and shows an inline error that names the line', async () => {
    api.commissions.adjustItemWeights.mockRejectedValue(new Error('เซิร์ฟเวอร์ปฏิเสธ'));
    await openDesk();

    fireEvent.click(radio(STOCK_A, '×2'));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain(STOCK_A);
    await waitFor(() => expect(isChecked(radio(STOCK_A, '×1'))).toBe(true));
    expect(isChecked(radio(STOCK_A, '×2'))).toBe(false);
    // The figures were never touched by the failed attempt.
    expect(queueRow().textContent).toContain('฿4,800,000.00');
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

  // The pencil now lives in the expanded panel of the record's row in "รายการทั้งหมดในรอบนี้".
  async function openLegacyEdit() {
    renderAt(salesManagerUser, '/commissions');
    const all = await screen.findByRole('region', { name: 'รายการทั้งหมดในรอบนี้' });
    fireEvent.click(await within(all).findByRole('button', { name: 'ดูรายละเอียด INV-LEGACY-601' }));
    fireEvent.click(within(await screen.findByTestId('detail-panel-601')).getByRole('button', { name: 'แก้ไขค่าหัก' }));
  }

  it('the edit panel offers a ×1/×2/×3 radiogroup instead of a <select>, and saving sends the chosen weightMultiplier', async () => {
    await openLegacyEdit();

    // Named through aria-labelledby -> the panel's visible label (id record-weight-label).
    const weightGroup = await screen.findByRole('radiogroup', { name: 'น้ำหนักฐานคอมของรายการนี้' });
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

  it('the record-level control is disabled while the save is in flight', async () => {
    let resolve;
    api.commissions.updateDeductions.mockImplementation(() => new Promise((r) => { resolve = r; }));
    await openLegacyEdit();
    const weightGroup = await screen.findByRole('radiogroup', { name: 'น้ำหนักฐานคอมของรายการนี้' });
    fireEvent.change(screen.getByLabelText(/เหตุผลในการแก้ไข/), { target: { value: 'ปรับ' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.commissions.updateDeductions).toHaveBeenCalled());
    within(weightGroup).getAllByRole('radio').forEach((r) => expect(isDisabled(r)).toBe(true));
    fireEvent.click(within(weightGroup).getByRole('radio', { name: '×3' }));
    expect(isChecked(within(weightGroup).getByRole('radio', { name: '×3' }))).toBe(false);

    await act(async () => { resolve({}); });
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

  it('a record with no item lines gets no "สินค้าและน้ำหนัก" section, but keeps the money chain and the approval status', async () => {
    api.commissions.pendingApproval.mockResolvedValue({ commissions: [pendingDto({}, { items: [] })] });

    const panel = await openDesk();

    expect(within(panel).queryByRole('region', { name: 'สินค้าและน้ำหนัก' })).toBeNull();
    expect(within(panel).getByRole('region', { name: 'การคำนวณ' })).not.toBeNull();
    expect(within(panel).getByRole('region', { name: 'สถานะและเอกสาร' })).not.toBeNull();
  });

  it('opening the same SUBMITTED record from the all-records table gives the sales_manager the same editable weights', async () => {
    echoAdjust();
    api.commissions.list.mockResolvedValue({ commissions: [submittedRecord()] });
    api.commissions.pendingApproval.mockResolvedValue({ commissions: [pendingDto()] });
    renderAt(salesManagerUser, '/commissions');

    const all = await screen.findByRole('region', { name: 'รายการทั้งหมดในรอบนี้' });
    fireEvent.click(await within(all).findByRole('button', { name: 'ดูรายละเอียด INV-PEND-0701' }));
    await screen.findByTestId('detail-panel-701');

    within(group(STOCK_A)).getAllByRole('radio').forEach((r) => expect(isDisabled(r)).toBe(false));
    fireEvent.click(radio(STOCK_A, '×3'));
    await waitFor(() => expect(api.commissions.adjustItemWeights).toHaveBeenCalledWith(701, { lines: [{ itemId: 11, weightMultiplier: 3 }] }));
  });
});
