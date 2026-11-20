import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FinanceDealPage } from './FinanceDealPage.jsx';
import { makeFinanceDeal, makeTrack } from '../../test/fixtures/financeDeal.js';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../utils/download.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, fetchDocumentBlob: vi.fn() };
});

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
      finance: {
        getDeal: vi.fn(),
        addComment: vi.fn(),
        confirmDepositPaid: vi.fn(),
        confirmFinalPayment: vi.fn(),
        recordPayment: vi.fn(),
        confirmCloseReady: vi.fn(),
        revokeCloseConfirmation: vi.fn(),
        updateStage: vi.fn(),
      },
      commissions: { createFromDeal: vi.fn() },
    },
  };
});

const accountUser = { role: 'account', name: 'บัญชี ทดสอบ', employeeId: 3 };
const action = (name, label, extra = {}) => ({ action: name, label, targetStage: null, requiredFields: [], ...extra });

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/finance/deals/501']}>
        <Routes>
          <Route path="/finance/deals/:id" element={<FinanceDealPage user={accountUser} showToast={vi.fn()} />} />
          <Route path="/finance" element={<div>WORKLIST-PAGE</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...utils, queryClient };
}

function section(name) {
  return screen.getByRole('region', { name });
}

describe('FinanceDealPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.finance.getDeal.mockResolvedValue({ deal: makeFinanceDeal() });
  });

  describe('money band', () => {
    it('shows payable / paid / outstanding and the VAT caption driven by amountVatBasis', async () => {
      renderPage();
      const band = await screen.findByRole('group', { name: 'สรุปยอดเงิน' });
      expect(within(band).getByText('฿120,000.00')).not.toBeNull();
      expect(within(band).getByText('฿40,000.00')).not.toBeNull();
      expect(within(band).getByText('฿80,000.00')).not.toBeNull();
      expect(within(band).getByText('ยอดที่ต้องชำระ')).not.toBeNull();
      expect(within(band).getByText('รับแล้ว')).not.toBeNull();
      expect(within(band).getByText('คงค้าง')).not.toBeNull();
      expect(within(band).getByText('รวม VAT')).not.toBeNull();
    });
  });

  describe('RECORD_INVOICE (invoice recording in-page, VAT-inclusive amount received default)', () => {
    const recordInvoice = () => action('RECORD_INVOICE', 'บันทึกใบกำกับ');
    const closedDeal = (extra = {}) => {
      const deal = makeFinanceDeal({
        salesStage: 'CLOSED_PAID',
        milestoneTrack: makeTrack(5),
        availableActions: [recordInvoice()],
        commissionInvoice: null,
        ...extra,
      });
      deal.money.amountPayable = 107000;
      deal.money.amountPayableExVat = 100000;
      deal.money.commissionRecorded = false;
      return deal;
    };
    const pdf = () => new File(['%PDF-fake'], 'tax-invoice-0501.pdf', { type: 'application/pdf' });

    it('clicking it opens an in-page form (no navigation to /commissions) whose amount received defaults to the VAT-INCLUSIVE payable (QA-02)', async () => {
      api.finance.getDeal.mockResolvedValue({ deal: closedDeal() });
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: 'บันทึกใบกำกับ' }));
      // QA-02 (owner ruling 2026-10-28): CommissionCalculator.calculateInvoice divides by 1.07 itself, so the
      // field is the amount received INCLUDING VAT; pre-filling the ex-VAT figure strips VAT twice.
      const gross = await screen.findByLabelText(/ยอดรับจริง \(รวม VAT\)/);
      expect(gross.value).toBe('107000');
      expect(gross.value).not.toBe('100000');
      expect(screen.queryByLabelText(/ก่อน VAT/)).toBeNull();
      expect(screen.queryByText('WORKLIST-PAGE')).toBeNull();
      expect(screen.getByLabelText(/เลขที่ใบกำกับ/)).not.toBeNull();
      expect(screen.getByLabelText(/วันที่ใบกำกับ/)).not.toBeNull();
      expect(screen.getByLabelText(/ไฟล์ใบกำกับภาษี/)).not.toBeNull();
    });

    it('submitting calls api.commissions.createFromDeal once with exactly the contract keys, then refetches the deal', async () => {
      api.finance.getDeal.mockResolvedValue({ deal: closedDeal() });
      api.commissions.createFromDeal.mockResolvedValue({ commission: { id: 900 } });
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: 'บันทึกใบกำกับ' }));
      fireEvent.change(await screen.findByLabelText(/เลขที่ใบกำกับ/), { target: { value: 'INV-0501' } });
      fireEvent.change(screen.getByLabelText(/วันที่ใบกำกับ/), { target: { value: '2026-09-20' } });
      fireEvent.blur(screen.getByLabelText(/วันที่ใบกำกับ/));
      const file = pdf();
      fireEvent.change(screen.getByLabelText(/ไฟล์ใบกำกับภาษี/), { target: { files: [file] } });
      fireEvent.change(screen.getByLabelText(/ค่าธรรมเนียมธนาคาร/), { target: { value: '50' } });
      fireEvent.change(screen.getByLabelText(/ภาษีพัก/), { target: { value: '7000' } });
      fireEvent.change(screen.getByLabelText(/ค่าขนส่ง/), { target: { value: '300' } });
      fireEvent.change(screen.getByLabelText(/ค่าตัด/), { target: { value: '200' } });
      fireEvent.change(screen.getByLabelText(/รับเงินขาด/), { target: { value: '10' } });
      fireEvent.change(screen.getByLabelText(/หัก ณ ที่จ่าย/), { target: { value: '3000' } });
      fireEvent.change(screen.getByLabelText(/รับเงินเกิน/), { target: { value: '5' } });
      const callsBefore = api.finance.getDeal.mock.calls.length;
      // submit the form directly: jsdom does not treat a synthetic file change as satisfying `required` (same reason
      // CommissionPage.test.jsx dispatches submit on the form).
      fireEvent.submit(screen.getByLabelText(/เลขที่ใบกำกับ/).closest('form'));

      await waitFor(() => expect(api.commissions.createFromDeal).toHaveBeenCalledTimes(1));
      const payload = api.commissions.createFromDeal.mock.calls[0][0];
      expect(Object.keys(payload).sort()).toEqual([
        'bankFees', 'cutFee', 'grossAmount', 'invoiceAttachment', 'invoiceDate', 'invoiceNumber', 'overpayment',
        'shortfall', 'suspenseVat', 'ticketId', 'transportFee', 'withholdingTax',
      ]);
      expect(payload.ticketId).toBe(501);
      expect(payload.invoiceNumber).toBe('INV-0501');
      expect(payload.invoiceDate).toBe('2026-09-20');
      expect(Number(payload.grossAmount)).toBe(107000);
      expect(Number(payload.bankFees)).toBe(50);
      expect(Number(payload.suspenseVat)).toBe(7000);
      expect(Number(payload.transportFee)).toBe(300);
      expect(Number(payload.cutFee)).toBe(200);
      expect(Number(payload.shortfall)).toBe(10);
      expect(Number(payload.withholdingTax)).toBe(3000);
      expect(Number(payload.overpayment)).toBe(5);
      expect(payload.invoiceAttachment).toBeInstanceOf(File);
      expect(payload.invoiceAttachment.name).toBe('tax-invoice-0501.pdf');
      await waitFor(() => expect(api.finance.getDeal.mock.calls.length).toBeGreaterThan(callsBefore));
    });

    const invoice = (over = {}) => ({
      invoiceNumber: 'INV-0501', invoiceDate: '2026-09-20', grossAmount: 100000,
      bankFees: 50, suspenseVat: 7000, transportFee: 300, cutFee: 200, shortfall: 10, withholdingTax: 3000, overpayment: 5,
      fileName: 'tax-invoice-0501.pdf', downloadPath: '/downloads/attachment-77',
      approvalStatus: 'SUBMITTED', rejectionReason: null, recordedAt: '2026-09-20T03:00:00Z', ...over,
    });

    it('never renders a commission amount, even if a payload wrongly carries one', async () => {
      const deal = closedDeal({
        availableActions: [],
        commissionInvoice: invoice({ commissionableBase: 123456.78, actualReceived: 123456.78, commissionAmount: 123456.78 }),
      });
      deal.money.commissionRecorded = true;
      api.finance.getDeal.mockResolvedValue({ deal });
      const { container } = renderPage();
      await screen.findByRole('region', { name: /ชำระครบ ปิดงาน/ });
      expect(container.textContent).not.toMatch(/123,?456/);
    });

    it('a ceo-style deal (no RECORD_INVOICE action) shows no บันทึกใบกำกับ button', async () => {
      api.finance.getDeal.mockResolvedValue({ deal: closedDeal({ availableActions: [] }) });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(screen.queryByRole('button', { name: /บันทึกใบกำกับ/ })).toBeNull();
    });
  });

  describe('whose move it is', () => {
    it('an ISSUED remaining invoice with no FINAL_PAYMENT action carries no รอรับชำระ badge', async () => {
      api.finance.getDeal.mockResolvedValue({ deal: makeFinanceDeal({ availableActions: [] }) });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(screen.queryByText('รอรับชำระ')).toBeNull();
    });

    it('a remaining invoice awaiting FINAL_PAYMENT carries รอรับชำระ', async () => {
      api.finance.getDeal.mockResolvedValue({ deal: makeFinanceDeal({ availableActions: [action('FINAL_PAYMENT', 'รับเงินครบ')] }) });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(within(section(/^4 ส่งมอบ/)).getByText('รอรับชำระ')).not.toBeNull();
    });
  });

  describe('money actions', () => {
    it('DEPOSIT_PAID asks for an explicit confirmation, then posts and updates the view from the returned deal', async () => {
      const before = makeFinanceDeal({ availableActions: [action('DEPOSIT_PAID', 'รับมัดจำ')] });
      const after = makeFinanceDeal({ availableActions: [] });
      after.money.amountPaid = 60000;
      after.money.amountOutstanding = 60000;
      api.finance.getDeal.mockResolvedValue({ deal: before });
      api.finance.confirmDepositPaid.mockResolvedValue({ deal: after });
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันรับมัดจำ' }));
      // nothing is sent until the dialog is confirmed
      expect(api.finance.confirmDepositPaid).not.toHaveBeenCalled();
      const dialog = await screen.findByRole('dialog');
      fireEvent.click(within(dialog).getByRole('button', { name: 'ยืนยันรับมัดจำ' }));
      await waitFor(() => expect(api.finance.confirmDepositPaid).toHaveBeenCalledWith(501));
      const band = await screen.findByRole('group', { name: 'สรุปยอดเงิน' });
      await waitFor(() => expect(within(band).getAllByText('฿60,000.00').length).toBe(2));
      expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('RECORD_PAYMENT submits the right body to api.finance.recordPayment and the view updates from the returned deal', async () => {
      const before = makeFinanceDeal({ availableActions: [action('RECORD_PAYMENT', 'บันทึกรับชำระเงิน')] });
      const after = makeFinanceDeal({ availableActions: [action('RECORD_PAYMENT', 'บันทึกรับชำระเงิน')] });
      after.money.amountPaid = 100000;
      after.money.amountOutstanding = 20000;
      api.finance.getDeal.mockResolvedValue({ deal: before });
      api.finance.recordPayment.mockResolvedValue({ deal: after });
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: 'บันทึกรับชำระ' }));
      const dialog = await screen.findByRole('dialog');
      fireEvent.change(within(dialog).getByLabelText('ประเภท'), { target: { value: 'BALANCE' } });
      fireEvent.change(within(dialog).getByLabelText('จำนวนเงิน'), { target: { value: '60000' } });
      fireEvent.change(within(dialog).getByLabelText('เลขอ้างอิง'), { target: { value: ' RC-999 ' } });
      // The note is collapsed behind "+ หมายเหตุ" (design m-payment).
      fireEvent.click(within(dialog).getByRole('button', { name: '+ หมายเหตุ' }));
      fireEvent.change(within(dialog).getByLabelText('หมายเหตุ'), { target: { value: 'โอนเข้าบัญชี' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกรับชำระ' }));
      await waitFor(() => expect(api.finance.recordPayment).toHaveBeenCalledTimes(1));
      const [id, body] = api.finance.recordPayment.mock.calls[0];
      expect(id).toBe(501);
      expect(body).toEqual(expect.objectContaining({
        kind: 'BALANCE', amount: 60000, receiptRef: 'RC-999', note: 'โอนเข้าบัญชี', allowOverpayment: false,
      }));
      const band = await screen.findByRole('group', { name: 'สรุปยอดเงิน' });
      await waitFor(() => expect(within(band).getByText('฿20,000.00')).not.toBeNull());
      expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('RECORD_PAYMENT refuses a missing amount inline and never calls the API', async () => {
      api.finance.getDeal.mockResolvedValue({ deal: makeFinanceDeal({ availableActions: [action('RECORD_PAYMENT', 'บันทึกรับชำระเงิน')] }) });
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: 'บันทึกรับชำระ' }));
      const dialog = await screen.findByRole('dialog');
      // The fixture's open invoice prefills the outstanding amount; clearing it is what this test is about.
      fireEvent.change(within(dialog).getByLabelText('จำนวนเงิน'), { target: { value: '' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกรับชำระ' }));
      expect(await within(dialog).findByRole('alert')).not.toBeNull();
      expect(api.finance.recordPayment).not.toHaveBeenCalled();
    });

    async function openPaymentDialog(deal) {
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: 'บันทึกรับชำระ' }));
      return screen.findByRole('dialog');
    }
    const payDeal = (over = {}, money = {}) => {
      const d = makeFinanceDeal({ availableActions: [action('RECORD_PAYMENT', 'บันทึกรับชำระเงิน')], ...over });
      Object.assign(d.money, money);
      return d;
    };

    it('an issued remaining invoice prefills BALANCE and THAT invoice’s outstanding (its total less balance taken)', async () => {
      const dialog = await openPaymentDialog(payDeal());
      expect(within(dialog).getByLabelText('ประเภท').value).toBe('BALANCE');
      expect(within(dialog).getByLabelText('จำนวนเงิน').value).toBe('85600');
      expect(within(dialog).getByTestId('payment-prefill-doc').textContent).toContain('ใบแจ้งหนี้ส่วนที่เหลือ RI-2026-0012 · คงค้าง ฿85,600.00');
    });

    it('payment kind defaults to BALANCE for a skipped-deposit deal (CREDIT_CUSTOMER at S12-S17) with nothing paid', async () => {
      const dialog = await openPaymentDialog(payDeal(
        { salesStage: 'PROCUREMENT', milestoneTrack: makeTrack(3, { skipDeposit: true }) },
        { amountPaid: 0, depositPolicy: 'CREDIT_CUSTOMER' },
      ));
      expect(within(dialog).getByLabelText('ประเภท').value).toBe('BALANCE');
    });

    // QA-19 (owner ruling 2026-10-29): account only ever moves a money stage FORWARD.
    async function openStageModal(salesStage) {
      const deal = makeFinanceDeal({ salesStage, availableActions: [action('UPDATE_STAGE', 'แก้ไขสถานะ')] });
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: 'แก้ไขสถานะ' }));
      const dialog = await screen.findByRole('dialog');
      return Array.from(within(dialog).getByLabelText('สถานะที่ต้องการ').querySelectorAll('option')).map((o) => o.value);
    }

    it('UPDATE_STAGE lists only the money stages AHEAD of the deal (a delivered deal cannot be sent back to deposit)', async () => {
      expect(await openStageModal('DELIVERY_SCHEDULING')).toEqual(['CLOSED_PAID']);
    });

    it('UPDATE_STAGE lists both money stages while the deal is still before the deposit stage', async () => {
      expect(await openStageModal('ORDER_RECEIVED')).toEqual(['DEPOSIT_RECEIVED', 'CLOSED_PAID']);
    });

    it('ADVANCE_STAGE (money stage) confirms, then posts the action targetStage to api.finance.updateStage', async () => {
      const deal = makeFinanceDeal({ availableActions: [action('ADVANCE_STAGE', 'เลื่อนสถานะ', { targetStage: 'CLOSED_PAID' })] });
      api.finance.getDeal.mockResolvedValue({ deal });
      api.finance.updateStage.mockResolvedValue({ deal: makeFinanceDeal() });
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: /เลื่อนไปขั้น/ }));
      const dialog = await screen.findByRole('dialog');
      expect(api.finance.updateStage).not.toHaveBeenCalled();
      fireEvent.click(within(dialog).getByRole('button', { name: /เลื่อนไปขั้น/ }));
      await waitFor(() => expect(api.finance.updateStage).toHaveBeenCalledWith(501, { stage: 'CLOSED_PAID' }));
    });
  });
});
