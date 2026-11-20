import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DealPaymentSection } from './DealPaymentSection.jsx';
import { api } from '../../api/index.js';
import { formatThaiDate } from '../../utils/format.js';
import {
  chainDepositBlockedReason, chainDepositState, chainRemainingBlockedReason, chainRemainingState,
} from '../../api/mockDocumentChainRules.js';

globalThis.React = React;

vi.mock('../../utils/download.js', () => ({ downloadBlob: vi.fn() }));
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      depositNotices: {
        listByTicket: vi.fn(), issue: vi.fn(), preview: vi.fn(), downloadPdf: vi.fn(), downloadXlsx: vi.fn(),
      },
      storedRemainingInvoices: { listForTicket: vi.fn(), download: vi.fn() },
      documentChain: { get: vi.fn() },
      dealQuotations: { listForTicket: vi.fn() },
      deliveryRequests: { list: vi.fn() },
    },
  };
});

// Document chain (2026-11-17): the step STATES now come from the server's summary (GET /tickets/{id}/document-chain).
// Each fixture's summary is the one the server would compute from the same deal facts — built here through the
// mock's mirror of DocumentChainRules, which documentChainCases.test.js pins to the Java rules' state table.
function chainFor({ summary, depositNotices = [], remainingInvoices = [], user }) {
  const f = {
    status: summary.status, lifecycle: summary.lifecycle ?? 'ACTIVE', depositPolicy: summary.depositPolicy ?? 'REQUIRED',
    paymentStatus: summary.paymentStatus ?? null, fulfillmentStatus: summary.fulfillmentStatus ?? null,
    noticeDraft: depositNotices.some((d) => d.status === 'DRAFT'), noticeIssued: depositNotices.some((d) => d.status === 'ISSUED'),
    invoiceDraft: remainingInvoices.some((r) => r.status === 'DRAFT'), invoiceIssued: remainingInvoices.some((r) => r.status === 'ISSUED'),
  };
  const mayWrite = user.role === 'sales' && Number(summary.createdById) === Number(user.id);
  const remainingState = chainRemainingState(f);
  const remainingReason = chainRemainingBlockedReason(f);
  return {
    ticketId: 19, dealStatus: f.status, lifecycle: f.lifecycle,
    order: { confirmed: f.paymentStatus != null, confirmable: false, confirmedFromQuotationId: null, confirmedAt: null },
    deposit: { policy: f.depositPolicy, state: chainDepositState(f), blockedReason: chainDepositBlockedReason(f) },
    remaining: { state: remainingState, blockedReason: remainingReason, canCreate: remainingState === 'TO_CREATE' && remainingReason == null },
    viewer: { mayConfirmOrder: false, mayWriteDepositNotice: mayWrite, mayWriteRemainingInvoice: mayWrite },
  };
}

const owner = { id: 1, role: 'sales' };
const otherSales = { id: 2, role: 'sales' };
const salesManager = { id: 3, role: 'sales_manager' };
const ceo = { id: 9, role: 'ceo' };
const ROLES = { owner, otherSales, salesManager, ceo };

const acceptedPr = { id: 501, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-01T09:00:00Z', ticketCreatedById: 1 };
const draftNotice = { id: 31, status: 'DRAFT', docNumber: null, depositPercent: 0.3, depositAmount: 300, totalPayable: 321, updatedAt: 'u1', customerName: 'ลูกค้า' };
const issuedInvoice = { id: 900, status: 'ISSUED', docNumber: 'GLR6900014-1', docDate: '2026-11-05' };
const issuedNotice = { ...draftNotice, status: 'ISSUED', docNumber: 'GLRD6900012', docDate: '2026-10-31', version: 1 };

function summaryOf(overrides = {}) {
  return {
    createdById: 1, status: 'quotation_issued', depositPolicy: 'REQUIRED', paymentStatus: null, fulfillmentStatus: null,
    paymentStage: 'NOT_STARTED', amountPayable: 1000, amountPaid: 0, amountOutstanding: 1000, overdue: false,
    ...overrides,
  };
}

function renderSection({
  user = owner, summary = summaryOf(), depositNotices = [], remainingInvoices = [], pricingRequests = [],
  availableActions = [], depositFails = false, remainingFails = false, ...props
} = {}) {
  if (depositFails) api.depositNotices.listByTicket.mockRejectedValue(new Error('boom'));
  else api.depositNotices.listByTicket.mockResolvedValue({ depositNotices });
  if (remainingFails) api.storedRemainingInvoices.listForTicket.mockRejectedValue(new Error('boom'));
  else api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices });
  api.documentChain.get.mockResolvedValue({ documentChain: chainFor({ summary, depositNotices, remainingInvoices, user }) });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const showToast = vi.fn();
  const utils = render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <DealPaymentSection
          ticketId={19} user={user} summary={summary} events={[]} paymentReceipts={[]}
          pricingRequests={pricingRequests} dealQuotations={[]} availableActions={availableActions}
          showToast={showToast} {...props}
        />
      </QueryClientProvider>
    </MemoryRouter>,
  );
  return { ...utils, showToast };
}

const deposit = () => within(screen.getByTestId('payment-step-deposit'));
const remaining = () => within(screen.getByTestId('payment-step-remaining'));
const settled = async () => {
  await waitFor(() => expect(api.depositNotices.listByTicket).toHaveBeenCalled());
  await waitFor(() => expect(api.storedRemainingInvoices.listForTicket).toHaveBeenCalled());
  await screen.findByTestId('payment-step-deposit');
  await waitFor(() => expect(screen.queryByText('กำลังโหลด…')).toBeNull());
};

beforeEach(() => { vi.clearAllMocks(); });

describe('DealPaymentSection — KPI tiles and progress bar', () => {
  // The three figures were the balance strip's ยอดที่ต้องชำระ / ชำระแล้ว / คงเหลือ; they are the drawn tiles
  // มูลค่างาน / รับแล้ว / คงค้าง now, still the server's amountPayable / amountPaid / amountOutstanding.
  const tile = (label) => within(screen.getByTestId('money-kpis')).getByText(label).closest('[data-testid="money-kpi"]');

  it('keeps the three figures and fills the bar with amountPaid / amountPayable', async () => {
    renderSection({ summary: summaryOf({ amountPayable: 1000, amountPaid: 400, amountOutstanding: 600 }) });
    await settled();
    expect(within(tile('มูลค่างาน')).getByText('฿1,000.00')).toBeTruthy();
    expect(within(tile('รับแล้ว')).getByText('฿400.00')).toBeTruthy();
    expect(within(tile('คงค้าง')).getByText('฿600.00')).toBeTruthy();
    const bar = screen.getByRole('progressbar');
    expect(bar.getAttribute('aria-valuenow')).toBe('40');
    expect(bar.getAttribute('aria-label')).toBe('ชำระแล้ว 40%');
    expect(screen.getByText('ชำระแล้ว 40%')).toBeTruthy();
  });

  it('คงค้าง is red only while above 0', async () => {
    const first = renderSection({ summary: summaryOf({ amountOutstanding: 600 }) });
    await settled();
    expect(tile('คงค้าง').querySelector('strong').textContent).toBe('฿600.00');
    expect(tile('คงค้าง').querySelector('strong').className).toContain('text-danger');
    first.unmount();
    renderSection({ summary: summaryOf({ amountPayable: 1000, amountPaid: 1000, amountOutstanding: 0 }) });
    await settled();
    expect(tile('คงค้าง').querySelector('strong').className).not.toContain('text-danger');
  });
});

describe('DealPaymentSection — step 1 ใบแจ้งยอดเงินรับมัดจำ, per state and role', () => {
  const STATES = {
    notyet: { props: {}, badge: 'ยังไม่ถึงขั้นตอน' },
    skipped: { props: { summary: summaryOf({ depositPolicy: 'NOT_REQUIRED' }) }, badge: 'ไม่เก็บมัดจำ' },
    create: {
      props: { summary: summaryOf({ paymentStatus: 'CUSTOMER_CONFIRMED' }), pricingRequests: [acceptedPr] }, badge: 'ยังไม่สร้าง',
    },
    draft: { props: { summary: summaryOf({ paymentStatus: 'CUSTOMER_CONFIRMED' }), depositNotices: [draftNotice] }, badge: 'ร่าง' },
    issued: { props: { summary: summaryOf({ paymentStatus: 'DEPOSIT_NOTICE_ISSUED' }), depositNotices: [issuedNotice] }, badge: 'รอชำระ' },
    paid: { props: { summary: summaryOf({ paymentStatus: 'DEPOSIT_PAID' }), depositNotices: [issuedNotice] }, badge: 'ชำระแล้ว' },
  };
  const BUTTONS = ['สร้างใบแจ้งยอดเงินรับมัดจำ', 'ตัวอย่าง', 'แก้ไขรายการ', 'ออกเอกสาร', 'PDF', 'Excel'];
  const EXPECTED = {
    notyet: { owner: [], otherSales: [], salesManager: [], ceo: [] },
    skipped: { owner: [], otherSales: [], salesManager: [], ceo: [] },
    create: {
      owner: ['สร้างใบแจ้งยอดเงินรับมัดจำ'], otherSales: [], salesManager: [], ceo: [],
    },
    draft: {
      owner: ['ตัวอย่าง', 'แก้ไขรายการ', 'ออกเอกสาร'], otherSales: ['ตัวอย่าง'], salesManager: ['ตัวอย่าง'], ceo: ['ตัวอย่าง'],
    },
    issued: {
      owner: ['PDF', 'Excel'], otherSales: ['PDF', 'Excel'], salesManager: ['PDF', 'Excel'], ceo: ['PDF', 'Excel'],
    },
    paid: {
      owner: ['PDF', 'Excel'], otherSales: ['PDF', 'Excel'], salesManager: ['PDF', 'Excel'], ceo: ['PDF', 'Excel'],
    },
  };
  const cases = Object.keys(STATES).flatMap((state) => Object.keys(ROLES).map((role) => [state, role]));

  it.each(cases)('%s as %s: right badge, right buttons', async (state, role) => {
    renderSection({ ...STATES[state].props, user: ROLES[role] });
    await settled();
    expect(deposit().getByText(STATES[state].badge)).toBeTruthy();
    const shown = BUTTONS.filter((name) => deposit().queryByRole('button', { name }) || deposit().queryByRole('link', { name }));
    expect(shown.sort()).toEqual([...EXPECTED[state][role]].sort());
  });

  // "Manage all sales documents" grant (Ploy 2026-11-18): the holder acts as the owner of every deal's documents, so the
  // draft's edit/issue controls show on a deal they neither own nor keyed. `canManageAllSalesDocuments` is the
  // AuthResponse hint carried onto the user (like canCreateQuotation); the backend re-reads the grant live.
  it.each([['sales', otherSales], ['sales_manager', salesManager]])(
    'draft: a canManageAllSalesDocuments holder (%s) gets ตัวอย่าง, แก้ไขรายการ and ออกเอกสาร on a deal they do not own',
    async (_role, base) => {
      renderSection({ ...STATES.draft.props, user: { ...base, canManageAllSalesDocuments: true } });
      await settled();
      const shown = BUTTONS.filter((name) => deposit().queryByRole('button', { name }) || deposit().queryByRole('link', { name }));
      expect(shown.sort()).toEqual(['ตัวอย่าง', 'แก้ไขรายการ', 'ออกเอกสาร'].sort());
    },
  );

  it('ready to create: a non-owner is told sales creates it', async () => {
    renderSection({ ...STATES.create.props, user: salesManager });
    await settled();
    expect(deposit().getByText(/รอฝ่ายขายสร้าง/)).toBeTruthy();
  });

  it('create goes through the page handler (the main-button path)', async () => {
    const onCreateDepositNotice = vi.fn();
    renderSection({ ...STATES.create.props, onCreateDepositNotice });
    await settled();
    fireEvent.click(deposit().getByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' }));
    expect(onCreateDepositNotice).toHaveBeenCalledTimes(1);
  });

  it('draft: ออกเอกสาร asks first, shows the amounts, and only the dialog confirm issues it', async () => {
    api.depositNotices.issue.mockResolvedValue({ depositNotice: { docNumber: 'GLRD6900012' } });
    const { showToast } = renderSection({ ...STATES.draft.props });
    await settled();
    fireEvent.click(deposit().getByRole('button', { name: 'ออกเอกสาร' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'ยืนยันการออกเอกสาร' }));
    expect(dialog.getByText(/หลังจากนี้จะไม่สามารถแก้ไขได้/)).toBeTruthy();
    expect(api.depositNotices.issue).not.toHaveBeenCalled();

    fireEvent.click(dialog.getByRole('button', { name: 'ยกเลิก' }));
    expect(api.depositNotices.issue).not.toHaveBeenCalled();

    fireEvent.click(deposit().getByRole('button', { name: 'ออกเอกสาร' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'ออกเอกสาร' }));
    await waitFor(() => expect(api.depositNotices.issue).toHaveBeenCalledWith(31, { expectedUpdatedAt: 'u1' }));
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'ออกเอกสาร GLRD6900012 แล้ว'));
  });
});

describe('DealPaymentSection — a failed document list is an error with a retry, never a create button', () => {
  it('remaining-invoice list fails: inline error + retry in step 2, no สร้าง/จัดการ/PDF even when the deal is ready and the viewer may manage; retry refetches', async () => {
    renderSection({
      remainingFails: true, canManageRemainingInvoice: true, depositNotices: [issuedNotice],
      summary: summaryOf({ paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: 'GOODS_RECEIVED' }),
    });
    expect(await remaining().findByText('โหลดเอกสารไม่สำเร็จ')).toBeTruthy();
    expect(remaining().queryByRole('button', { name: /สร้างใบแจ้งหนี้ส่วนที่เหลือ|จัดการเอกสาร|PDF/ })).toBeNull();
    const before = api.storedRemainingInvoices.listForTicket.mock.calls.length;
    fireEvent.click(remaining().getByRole('button', { name: 'ลองใหม่' }));
    await waitFor(() => expect(api.storedRemainingInvoices.listForTicket.mock.calls.length).toBeGreaterThan(before));
  });
});

describe('DealPaymentSection — step 2 ใบแจ้งหนี้ส่วนที่เหลือ', () => {
  const afterDeposit = summaryOf({ paymentStatus: 'DEPOSIT_PAID', status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED' });

  it('waits for the deposit while it is still outstanding, with no buttons even for the owner', async () => {
    renderSection({ canManageRemainingInvoice: true });
    await settled();
    expect(remaining().getByText('รอรับมัดจำก่อน')).toBeTruthy();
    expect(remaining().queryByRole('button')).toBeNull();
  });

  it('ready (none yet): the manager gets สร้างใบแจ้งหนี้ส่วนที่เหลือ, which opens the dialog through the page', async () => {
    const onManageRemainingInvoice = vi.fn();
    renderSection({ summary: afterDeposit, depositNotices: [issuedNotice], canManageRemainingInvoice: true, onManageRemainingInvoice });
    await settled();
    expect(remaining().getByText('ยังไม่ออก')).toBeTruthy();
    fireEvent.click(remaining().getByRole('button', { name: 'สร้างใบแจ้งหนี้ส่วนที่เหลือ' }));
    expect(onManageRemainingInvoice).toHaveBeenCalledTimes(1);
  });

  it('ready (none yet) for a viewer who cannot manage it (ceo here): no button, a waiting-for-sales hint', async () => {
    renderSection({ user: ceo, summary: afterDeposit, depositNotices: [issuedNotice], canManageRemainingInvoice: false });
    await settled();
    expect(remaining().queryByRole('button')).toBeNull();
    expect(remaining().getByText(/รอฝ่ายขาย/)).toBeTruthy();
  });

  it('ISSUED: รอชำระ with doc number; จัดการเอกสาร only for the manager', async () => {
    const first = renderSection({
      user: salesManager, summary: afterDeposit, remainingInvoices: [issuedInvoice], canManageRemainingInvoice: false,
    });
    await settled();
    expect(remaining().getByText('รอชำระ')).toBeTruthy();
    expect(remaining().getByText('GLR6900014-1')).toBeTruthy();
    expect(remaining().queryByRole('button', { name: 'จัดการเอกสาร' })).toBeNull();
    first.unmount();
    renderSection({ summary: afterDeposit, remainingInvoices: [issuedInvoice], canManageRemainingInvoice: true });
    await settled();
    expect(remaining().getByRole('button', { name: 'จัดการเอกสาร' })).toBeTruthy();
  });

  it('paid in full by the deposit (no remaining invoice): a done, neutral "not needed" state with no create button and no chip', async () => {
    renderSection({
      summary: summaryOf({
        paymentStatus: 'FULLY_PAID', paymentStage: 'FULLY_PAID', amountPaid: 1000, amountOutstanding: 0, fulfillmentStatus: 'GOODS_RECEIVED',
      }),
      depositNotices: [issuedNotice], canManageRemainingInvoice: true,
    });
    await settled();
    expect(remaining().getByText('ไม่ต้องออก — ชำระครบจากมัดจำ')).toBeTruthy();
    expect(remaining().queryByText('ยังไม่ออก')).toBeNull();
    expect(remaining().queryByRole('button')).toBeNull();
  });

  it('a deal that skips the deposit is ready for its invoice straight away', async () => {
    renderSection({ summary: summaryOf({ depositPolicy: 'NOT_REQUIRED', paymentStatus: 'CUSTOMER_CONFIRMED', fulfillmentStatus: 'GOODS_RECEIVED' }), canManageRemainingInvoice: true });
    await settled();
    expect(remaining().queryByText('รอรับมัดจำก่อน')).toBeNull();
    expect(remaining().getByRole('button', { name: 'สร้างใบแจ้งหนี้ส่วนที่เหลือ' })).toBeTruthy();
  });
});

describe('DealPaymentSection — fix round 1: never a button the server refuses', () => {
  // Surviving mutation F17: the create button follows the server's canCreate, not merely the TO_CREATE state.
  it('deposit received but the goods are not in: no สร้างใบแจ้งหนี้ส่วนที่เหลือ even for the manager, and the reason', async () => {
    renderSection({
      summary: summaryOf({ paymentStatus: 'DEPOSIT_PAID', status: 'quotation_issued', fulfillmentStatus: null }),
      depositNotices: [issuedNotice], canManageRemainingInvoice: true,
    });
    await settled();
    expect(remaining().getByText('ยังไม่ออก')).toBeTruthy();
    expect(remaining().queryByRole('button', { name: 'สร้างใบแจ้งหนี้ส่วนที่เหลือ' })).toBeNull();
    expect(remaining().getByText('ออกได้เมื่อสินค้าถึงขั้นรับ/ส่งมอบแล้ว')).toBeTruthy();
  });
});

describe('DealPaymentSection — deposit receipt date', () => {
  it('shows the LATEST deposit receipt date, whatever order the receipts arrive in', async () => {
    renderSection({
      summary: summaryOf({ paymentStatus: 'DEPOSIT_PAID' }), depositNotices: [issuedNotice],
      paymentReceipts: [
        { receiptId: 1, kind: 'DEPOSIT', amount: 100, receivedAt: '2026-08-01T09:00:00.000Z' },
        { receiptId: 2, kind: 'DEPOSIT', amount: 200, receivedAt: '2026-09-15T09:00:00.000Z' },
        { receiptId: 3, kind: 'DEPOSIT', amount: 50, receivedAt: '2026-08-20T09:00:00.000Z' },
      ],
    });
    await settled();
    expect(deposit().getByText(`รับเงิน ${formatThaiDate('2026-09-15T09:00:00.000Z')}`)).toBeTruthy();
    expect(deposit().queryByText(`รับเงิน ${formatThaiDate('2026-08-01T09:00:00.000Z')}`)).toBeNull();
  });
});

// Drawing m-deposit-issue (2026-11-16): the issue confirm is rebuilt; what it SENDS must not change. Every figure in
// this fixture differs from every other, so reading one field where another belongs fails.
describe('DealPaymentSection — ใบแจ้งมัดจำ issue confirm (drawing m-deposit-issue)', () => {
  const notice = {
    ...draftNotice, depositPercent: 0.3, subtotal: 1240000.11, depositAmount: 372000.03, vatPercent: 0.07,
    vatAmount: 26040.02, totalPayable: 398040.05, customerName: 'บริษัท ลุมพินี เรสซิเดนซ์ จำกัด', reference: 'QT-2026-0201',
    updatedAt: 'stamp-7',
  };
  const props = { summary: summaryOf({ paymentStatus: 'CUSTOMER_CONFIRMED' }), depositNotices: [notice] };
  const openConfirm = async () => {
    fireEvent.click(deposit().getByRole('button', { name: 'ออกเอกสาร' }));
    return screen.findByRole('dialog', { name: 'ยืนยันการออกเอกสาร' });
  };

  // Payload identity — captured against the pre-rebuild dialog and kept green through it.
  it('confirm sends exactly one issue(id, { expectedUpdatedAt }) with the listed draft\'s stamp, nothing else', async () => {
    api.depositNotices.issue.mockResolvedValue({ depositNotice: { docNumber: 'GLRD6900099' } });
    renderSection(props);
    await settled();
    const dialog = await openConfirm();
    fireEvent.click(within(dialog).getByRole('button', { name: 'ออกเอกสาร' }));
    await waitFor(() => expect(api.depositNotices.issue).toHaveBeenCalledTimes(1));
    expect(api.depositNotices.issue.mock.calls).toEqual([[31, { expectedUpdatedAt: 'stamp-7' }]]);
    expect(api.depositNotices.preview).not.toHaveBeenCalled();
  });

  // The notice's lines may have been edited after it was drafted: the confirm says when its subtotal is not the
  // quotation's (the order's confirmed quotation, else the one named by the notice's reference). Never blocks.
  it('says when the notice subtotal differs from its quotation, and still lets ออกเอกสาร through', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [{ id: 3, number: 'QT-2026-0201', subtotalAmount: 1300000 }, { id: 4, number: 'QT-2026-0300', subtotalAmount: 5 }],
      orderConfirmedQuotationId: 3,
    });
    renderSection(props);
    await settled();
    const dialog = await openConfirm();
    expect((await within(dialog).findByTestId('deposit-issue-differs')).textContent)
      .toBe('ใบเสนอราคา QT-2026-0201 มูลค่า 1,300,000.00 — ใบแจ้งนี้ 1,240,000.11');
    expect(within(dialog).getByRole('button', { name: 'ออกเอกสาร' }).disabled).toBe(false);
  });

  it('a confirmed tax invoice reads as a receipt with both figures, and a round still waiting for its invoice is announced', async () => {
    api.deliveryRequests.list.mockResolvedValueOnce({
      rounds: [
        { id: 7, roundNo: 2, status: 'FORWARDED', invoice: null },
        { id: 6, roundNo: 1, status: 'INVOICED', invoice: { number: 'IV2611-0042', total: 85065, recordedAmount: 14177.5, receiptId: 77 } },
      ],
    });
    renderSection({
      summary: summaryOf(),
      paymentReceipts: [{ receiptId: 77, kind: 'BALANCE', amount: 14177.5, source: 'TAX_INVOICE', receiptRef: 'IV2611-0042', receivedAt: '2026-11-19T05:00:00.000Z', recordedByName: 'คุณออกบิล' }],
    });
    await settled();
    const line = await screen.findByTestId('tax-invoice-receipt');
    expect(line.textContent).toContain('รอบส่งที่ 1 · IV2611-0042');
    expect(line.textContent).toContain('ใบกำกับ ฿85,065.00 · รับไว้ก่อนแล้ว ฿70,887.50 · บันทึกรับเพิ่ม ฿14,177.50');
    expect(within(screen.getByTestId('money-received')).getByText('ใบกำกับ')).toBeTruthy();
    expect((await screen.findByTestId('round-waiting-invoice')).textContent).toContain('รอบส่งที่ 2');
  });

  it('a confirmed invoice that recorded nothing new still shows in the tab, with both figures', async () => {
    api.deliveryRequests.list.mockResolvedValueOnce({
      rounds: [{ id: 6, roundNo: 3, status: 'INVOICED', invoice: { number: 'IV2611-0099', total: 5000, recordedAmount: 0, receiptId: null } }],
    });
    renderSection({ summary: summaryOf() });
    await settled();
    const row = await screen.findByTestId('round-invoiced-no-receipt');
    expect(row.textContent).toContain('รอบส่งที่ 3 · ใบกำกับ IV2611-0099');
    expect(row.textContent).toContain('ใบกำกับ ฿5,000.00 · รับไว้ก่อนแล้ว ฿5,000.00 · บันทึกรับเพิ่ม ฿0.00');
    expect(screen.getByTestId('tax-invoice-note').textContent).toContain('บัญชีไม่ต้องบันทึกซ้ำ');
  });
});
