import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DealPaymentSection } from './DealPaymentSection.jsx';
import { api } from '../../api/index.js';
import { downloadBlob } from '../../utils/download.js';

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
    },
  };
});

const owner = { id: 1, role: 'sales' };
const otherSales = { id: 2, role: 'sales' };
const salesManager = { id: 3, role: 'sales_manager' };
const ceo = { id: 9, role: 'ceo' };
const ROLES = { owner, otherSales, salesManager, ceo };

const acceptedPr = { id: 501, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-01T09:00:00Z', ticketCreatedById: 1 };
const draftNotice = { id: 31, status: 'DRAFT', docNumber: null, depositPercent: 0.3, depositAmount: 300, totalPayable: 321, updatedAt: 'u1', customerName: 'ลูกค้า' };
const issuedNotice = { ...draftNotice, status: 'ISSUED', docNumber: 'GLRD6900012', docDate: '2026-10-31', version: 1 };
const issuedInvoice = { id: 900, status: 'ISSUED', docNumber: 'GLR6900014-1', docDate: '2026-11-05' };
const draftInvoice = { id: 901, status: 'DRAFT', docNumber: null, docDate: null };

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
const final = () => within(screen.getByTestId('payment-step-final'));
const settled = async () => {
  await waitFor(() => expect(api.depositNotices.listByTicket).toHaveBeenCalled());
  await waitFor(() => expect(api.storedRemainingInvoices.listForTicket).toHaveBeenCalled());
  await screen.findByTestId('payment-step-deposit');
  await waitFor(() => expect(screen.queryByText('กำลังโหลด…')).toBeNull());
};

beforeEach(() => { vi.clearAllMocks(); });

describe('DealPaymentSection — one panel, no วางบิล', () => {
  it('is a single "การชำระเงิน" panel with the three steps and the timeline, and no สถานะการเงิน or วางบิล text', async () => {
    const { container } = renderSection();
    await settled();
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['การชำระเงิน']);
    expect(screen.getByRole('heading', { level: 3, name: 'เอกสารและการรับชำระ' })).toBeTruthy();
    expect(screen.getByRole('heading', { level: 3, name: 'ไทม์ไลน์การชำระเงิน' })).toBeTruthy();
    expect(['payment-step-deposit', 'payment-step-remaining', 'payment-step-final'].every((id) => screen.getByTestId(id))).toBe(true);
    expect(screen.getByText('ใบแจ้งยอดเงินรับมัดจำ')).toBeTruthy();
    expect(container.textContent).not.toMatch(/สถานะการเงิน|วางบิล|ครบกำหนดชำระ|ติดตามครั้งถัดไป|ใบวางบิล/);
  });

  it('shows the stage and overdue badges only when they apply', async () => {
    const first = renderSection({ summary: summaryOf({ paymentStage: 'PARTIALLY_PAID', overdue: true }) });
    await settled();
    expect(screen.getByText('ชำระบางส่วน')).toBeTruthy();
    expect(screen.getByText('เกินกำหนด')).toBeTruthy();
    first.unmount();
    renderSection({ summary: summaryOf({ overdue: false }) });
    await settled();
    expect(screen.queryByText('เกินกำหนด')).toBeNull();
  });
});

describe('DealPaymentSection — balance strip and progress bar', () => {
  it('keeps the three figures and fills the bar with amountPaid / amountPayable', async () => {
    renderSection({ summary: summaryOf({ amountPayable: 1000, amountPaid: 400, amountOutstanding: 600 }) });
    await settled();
    expect(within(screen.getByText('ยอดที่ต้องชำระ').parentElement).getByText('฿1,000.00')).toBeTruthy();
    expect(within(screen.getByText('ชำระแล้ว', { selector: 'span' }).parentElement).getByText('฿400.00')).toBeTruthy();
    expect(within(screen.getByText('คงเหลือ').parentElement).getByText('฿600.00')).toBeTruthy();
    const bar = screen.getByRole('progressbar');
    expect(bar.getAttribute('aria-valuenow')).toBe('40');
    expect(bar.getAttribute('aria-label')).toBe('ชำระแล้ว 40%');
    expect(screen.getByText('ชำระแล้ว 40%')).toBeTruthy();
  });

  it.each([
    ['nothing payable yet (no NaN)', { amountPayable: 0, amountPaid: 0, amountOutstanding: 0 }, '0'],
    ['over-paid is clamped to 100', { amountPayable: 1000, amountPaid: 1500, amountOutstanding: 0 }, '100'],
    ['fully paid', { amountPayable: 1000, amountPaid: 1000, amountOutstanding: 0 }, '100'],
    ['99.5% rounds DOWN, never to 100 while คงเหลือ is still due', { amountPayable: 1000, amountPaid: 995, amountOutstanding: 5 }, '99'],
  ])('%s', async (_label, money, expected) => {
    renderSection({ summary: summaryOf(money) });
    await settled();
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe(expected);
    expect(screen.getByText(`ชำระแล้ว ${expected}%`)).toBeTruthy();
  });

  it('คงเหลือ is red only while above 0', async () => {
    const first = renderSection({ summary: summaryOf({ amountOutstanding: 600 }) });
    await settled();
    expect(screen.getByText('฿600.00', { selector: 'strong' }).className).toContain('text-danger');
    first.unmount();
    renderSection({ summary: summaryOf({ amountPayable: 1000, amountPaid: 1000, amountOutstanding: 0 }) });
    await settled();
    expect(screen.getByText('คงเหลือ').parentElement.querySelector('strong').className).not.toContain('text-danger');
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

  it('draft: the owner edit link opens /tickets/:id/deposit; the row says it is a draft', async () => {
    renderSection({ ...STATES.draft.props });
    await settled();
    expect(deposit().getByRole('link', { name: 'แก้ไขรายการ' }).getAttribute('href')).toBe('/tickets/19/deposit');
    expect(deposit().getByText(/ฉบับร่าง/)).toBeTruthy();
    expect(deposit().getByText(/มัดจำ 30%/)).toBeTruthy();
  });

  it('issued: the row shows the doc number in mono', async () => {
    renderSection({ ...STATES.issued.props });
    await settled();
    expect(deposit().getByText('GLRD6900012').className).toContain('font-mono');
  });

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

  it('the page main button already creates it: a hint replaces the second create button', async () => {
    renderSection({ ...STATES.create.props, mainButtonCreatesNotice: true });
    await settled();
    expect(deposit().queryByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' })).toBeNull();
    expect(deposit().getByText(/กดปุ่ม "สร้างใบแจ้งยอดเงินรับมัดจำ" ด้านบน/)).toBeTruthy();
  });

  it('legacy dual-track deal (no pricing request): the owner gets a link to the notice page, others nothing', async () => {
    const legacy = {
      summary: summaryOf({ paymentStatus: 'CUSTOMER_CONFIRMED' }), availableActions: [{ action: 'ISSUE_DEPOSIT_NOTICE' }],
    };
    const first = renderSection({ ...legacy });
    await settled();
    expect(deposit().getByRole('link', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' }).getAttribute('href')).toBe('/tickets/19/deposit');
    first.unmount();
    renderSection({ ...legacy, user: otherSales });
    await settled();
    expect(deposit().queryByRole('link', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' })).toBeNull();
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

  it('draft: ตัวอย่าง loads the preview into a frame', async () => {
    api.depositNotices.preview.mockResolvedValue('<p>preview</p>');
    renderSection({ ...STATES.draft.props });
    await settled();
    fireEvent.click(deposit().getByRole('button', { name: 'ตัวอย่าง' }));
    await waitFor(() => expect(api.depositNotices.preview).toHaveBeenCalledWith(31));
    expect(await deposit().findByTitle('Deposit notice preview')).toBeTruthy();
  });

  it('issued: PDF and Excel call the download API for this notice', async () => {
    api.depositNotices.downloadPdf.mockResolvedValue('pdf-blob');
    api.depositNotices.downloadXlsx.mockResolvedValue('xlsx-blob');
    renderSection({ ...STATES.issued.props, user: ceo });
    await settled();
    fireEvent.click(deposit().getByRole('button', { name: 'PDF' }));
    await waitFor(() => expect(downloadBlob).toHaveBeenCalledWith('pdf-blob', 'GLRD6900012', 'pdf'));
    fireEvent.click(deposit().getByRole('button', { name: 'Excel' }));
    await waitFor(() => expect(downloadBlob).toHaveBeenCalledWith('xlsx-blob', 'GLRD6900012', 'xlsx'));
    expect(api.depositNotices.downloadPdf).toHaveBeenCalledWith(31);
    expect(api.depositNotices.downloadXlsx).toHaveBeenCalledWith(31);
  });
});

describe('DealPaymentSection — a failed document list is an error with a retry, never a create button', () => {
  it('deposit list fails: inline error + retry in step 1, no create button even for the owner with an accepted order; retry refetches', async () => {
    renderSection({
      depositFails: true, summary: summaryOf({ paymentStatus: 'CUSTOMER_CONFIRMED' }), pricingRequests: [acceptedPr],
    });
    expect(await deposit().findByText('โหลดเอกสารไม่สำเร็จ')).toBeTruthy();
    expect(deposit().queryByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' })).toBeNull();
    expect(screen.queryByText('ขั้นตอนถัดไป')).toBeNull();
    const before = api.depositNotices.listByTicket.mock.calls.length;
    fireEvent.click(deposit().getByRole('button', { name: 'ลองใหม่' }));
    await waitFor(() => expect(api.depositNotices.listByTicket.mock.calls.length).toBeGreaterThan(before));
  });

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

  it('DRAFT: badge ร่าง and จัดการเอกสาร (manager only) opens the dialog', async () => {
    const onManageRemainingInvoice = vi.fn();
    const first = renderSection({ summary: afterDeposit, remainingInvoices: [draftInvoice], canManageRemainingInvoice: true, onManageRemainingInvoice });
    await settled();
    expect(remaining().getByText('ร่าง')).toBeTruthy();
    expect(remaining().queryByRole('button', { name: 'PDF' })).toBeNull();
    fireEvent.click(remaining().getByRole('button', { name: 'จัดการเอกสาร' }));
    expect(onManageRemainingInvoice).toHaveBeenCalledTimes(1);
    first.unmount();
    renderSection({ user: ceo, summary: afterDeposit, remainingInvoices: [draftInvoice], canManageRemainingInvoice: false });
    await settled();
    expect(remaining().queryByRole('button', { name: 'จัดการเอกสาร' })).toBeNull();
  });

  it('ISSUED: รอชำระ with doc number; PDF for every role downloads the ISSUED version; จัดการเอกสาร only for the manager', async () => {
    api.storedRemainingInvoices.download.mockResolvedValue('ri-blob');
    const first = renderSection({
      user: salesManager, summary: afterDeposit, remainingInvoices: [draftInvoice, issuedInvoice], canManageRemainingInvoice: false,
    });
    await settled();
    expect(remaining().getByText('รอชำระ')).toBeTruthy();
    expect(remaining().getByText('GLR6900014-1').className).toContain('font-mono');
    expect(remaining().queryByRole('button', { name: 'จัดการเอกสาร' })).toBeNull();
    fireEvent.click(remaining().getByRole('button', { name: 'PDF' }));
    await waitFor(() => expect(api.storedRemainingInvoices.download).toHaveBeenCalledWith(900));
    await waitFor(() => expect(downloadBlob).toHaveBeenCalledWith('ri-blob', 'GLR6900014-1', 'pdf'));
    first.unmount();
    renderSection({ summary: afterDeposit, remainingInvoices: [issuedInvoice], canManageRemainingInvoice: true });
    await settled();
    expect(remaining().getByRole('button', { name: 'จัดการเอกสาร' })).toBeTruthy();
  });

  it('paid: ชำระแล้ว once the whole deal is paid', async () => {
    renderSection({
      summary: summaryOf({ paymentStatus: 'FULLY_PAID', paymentStage: 'FULLY_PAID', amountPaid: 1000, amountOutstanding: 0 }),
      depositNotices: [issuedNotice], remainingInvoices: [issuedInvoice],
    });
    await settled();
    expect(remaining().getByText('ชำระแล้ว')).toBeTruthy();
  });

  it('a deal that skips the deposit is ready for its invoice straight away', async () => {
    renderSection({ summary: summaryOf({ depositPolicy: 'NOT_REQUIRED', paymentStatus: 'CUSTOMER_CONFIRMED', fulfillmentStatus: 'GOODS_RECEIVED' }), canManageRemainingInvoice: true });
    await settled();
    expect(remaining().queryByText('รอรับมัดจำก่อน')).toBeNull();
    expect(remaining().getByRole('button', { name: 'สร้างใบแจ้งหนี้ส่วนที่เหลือ' })).toBeTruthy();
  });
});

describe('DealPaymentSection — step 3 รับชำระครบ, and the next-step chip', () => {
  it('shows the outstanding amount and says account records payments on its own page', async () => {
    renderSection({
      summary: summaryOf({ paymentStatus: 'AWAITING_FINAL_PAYMENT', amountOutstanding: 600 }),
      depositNotices: [issuedNotice], remainingInvoices: [issuedInvoice],
    });
    await settled();
    expect(final().getByText('คงเหลือ ฿600.00')).toBeTruthy();
    expect(final().getByText(/ฝ่ายบัญชีบันทึกรับชำระ/)).toBeTruthy();
    expect(final().queryByRole('button')).toBeNull();
  });

  it('reads ชำระครบแล้ว once FULLY_PAID', async () => {
    renderSection({ summary: summaryOf({ paymentStatus: 'FULLY_PAID', amountOutstanding: 0 }), depositNotices: [issuedNotice], remainingInvoices: [issuedInvoice] });
    await settled();
    expect(final().getByText('ชำระครบแล้ว')).toBeTruthy();
  });

  const AFTER = { status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED' };
  it.each([
    ['ready to create the deposit notice', { summary: summaryOf({ paymentStatus: 'CUSTOMER_CONFIRMED' }), pricingRequests: [acceptedPr] }, 'deposit'],
    ['deposit notice out, unpaid', { summary: summaryOf({ paymentStatus: 'DEPOSIT_NOTICE_ISSUED' }), depositNotices: [issuedNotice] }, 'deposit'],
    ['deposit skipped, no invoice yet', { summary: summaryOf({ depositPolicy: 'WAIVED', paymentStatus: 'CUSTOMER_CONFIRMED' }) }, 'remaining'],
    ['deposit paid, no invoice yet', { summary: summaryOf({ paymentStatus: 'DEPOSIT_PAID', ...AFTER }), depositNotices: [issuedNotice] }, 'remaining'],
    ['invoice issued, awaiting payment (steps 2 and 3 finish together, as in the prototype)', { summary: summaryOf({ paymentStatus: 'AWAITING_FINAL_PAYMENT', ...AFTER }), depositNotices: [issuedNotice], remainingInvoices: [issuedInvoice] }, 'remaining'],
  ])('the ขั้นตอนถัดไป chip lands on the first unfinished step: %s', async (_label, props, step) => {
    renderSection(props);
    await settled();
    const chips = screen.getAllByText('ขั้นตอนถัดไป');
    expect(chips).toHaveLength(1);
    expect(screen.getByTestId(`payment-step-${step}`).contains(chips[0])).toBe(true);
  });

  it.each([
    ['before the order is confirmed', {}],
    ['a deal WAIVED before the order is confirmed (step 1 is skipped, but nothing is next yet)', { summary: summaryOf({ depositPolicy: 'WAIVED', paymentStatus: null }) }],
    ['everything is paid', { summary: summaryOf({ paymentStatus: 'FULLY_PAID', amountOutstanding: 0 }), depositNotices: [issuedNotice], remainingInvoices: [issuedInvoice] }],
  ])('no chip %s', async (_label, props) => {
    renderSection(props);
    await settled();
    expect(screen.queryByText('ขั้นตอนถัดไป')).toBeNull();
  });
});

describe('DealPaymentSection — the timeline (moved here unchanged)', () => {
  const summary = summaryOf({ paymentStage: 'PARTIALLY_PAID', paymentStatus: 'DEPOSIT_PAID' });
  const timelineItems = () => within(screen.getByRole('heading', { level: 3, name: 'ไทม์ไลน์การชำระเงิน' }).parentElement)
    .queryAllByRole('listitem').map((li) => li.textContent);

  it('merges money-track events and receipts, newest first, and drops non-payment events', async () => {
    renderSection({
      summary,
      events: [
        { id: 1, kind: 'DEPOSIT_PAID', actorName: 'ฝ่ายบัญชี', createdAt: '2026-06-20T09:00:00.000Z' },
        { id: 2, kind: 'STAGE_CHANGED', actorName: 'พนักงานขาย', createdAt: '2026-06-25T09:00:00.000Z' },
      ],
      paymentReceipts: [{ receiptId: 501, kind: 'DEPOSIT', amount: 400, receivedAt: '2026-07-10T09:00:00.000Z', recordedByName: 'คุณบัญชี', note: 'โอนแล้ว' }],
    });
    await settled();
    const items = timelineItems();
    expect(items).toHaveLength(2);
    expect(items[0]).toContain('คุณบัญชี');
    expect(items[0]).toContain('มัดจำ');
    expect(items[0]).not.toContain('DEPOSIT');
    expect(items[1]).toContain('รับมัดจำแล้ว');
  });

  it('orders events before receipts at a shared instant and sorts an unparseable timestamp last', async () => {
    const t = '2026-07-20T10:00:00.000Z';
    renderSection({
      summary,
      events: [{ id: 10, kind: 'CUSTOMER_CONFIRMED', actorName: 'พนักงานขาย', createdAt: t }],
      paymentReceipts: [
        { receiptId: 1, kind: 'DEPOSIT', amount: 400, receivedAt: t, recordedByName: 'คุณบัญชี' },
        { receiptId: 2, kind: 'BALANCE', amount: 600, receivedAt: null, recordedByName: 'คุณบัญชี' },
      ],
    });
    await settled();
    const items = timelineItems();
    expect(items).toHaveLength(3);
    expect(items[0]).toContain('ลูกค้ายืนยันคำสั่งซื้อ');
    expect(items[1]).toContain('มัดจำ');
    expect(items[2]).toContain('ส่วนที่เหลือ');
  });

  it('shows the empty state with no events and no receipts', async () => {
    renderSection({ summary });
    await settled();
    expect(screen.getByText('ยังไม่มีความเคลื่อนไหว')).toBeTruthy();
    expect(timelineItems()).toHaveLength(0);
  });

  it('shows บันทึกรับชำระเงิน only when granted, and never a ตั้งค่าการวางบิล button', async () => {
    const onRecordPayment = vi.fn();
    const first = renderSection({ summary, canRecordPayment: true, onRecordPayment });
    await settled();
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกรับชำระเงิน' }));
    expect(onRecordPayment).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: 'ตั้งค่าการวางบิล' })).toBeNull();
    first.unmount();
    renderSection({ summary, canRecordPayment: false });
    await settled();
    expect(screen.queryByRole('button', { name: 'บันทึกรับชำระเงิน' })).toBeNull();
  });
});
