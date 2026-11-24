import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DepositNoticePage } from './DepositNoticePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    depositNotices: {
      noteTemplates: vi.fn(),
      preparerOptions: vi.fn(),
      listByTicket: vi.fn(),
      createDraft: vi.fn(),
      update: vi.fn(),
      issue: vi.fn(),
    },
    pricingRequests: {
      listForTicket: vi.fn(),
    },
    customers: {
      search: vi.fn(),
    },
    dealQuotations: {
      listForTicket: vi.fn(),
    },
  },
}));

function renderDepositNoticePage(props = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <DepositNoticePage
        ticketId="701"
        onBack={vi.fn()}
        showToast={vi.fn()}
        {...props}
      />
    </QueryClientProvider>,
  );
}

// The summary block (ยอดรวม) is a column of lines like "ขอรับเงินมัดจำ 33% ฿33.03". Returns one line's full text.
function summaryLine(label) {
  const panel = document.getElementById('doc-summary-panel');
  return within(panel).getByText(label).textContent;
}

describe('DepositNoticePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.depositNotices.noteTemplates.mockResolvedValue({
      templates: [{ id: 1, text: 'หมายเหตุมาตรฐาน', defaultSelected: true }],
    });
    api.customers.search.mockResolvedValue({ customers: [] });
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] });
    api.depositNotices.createDraft.mockResolvedValue({
      depositNotice: {
        id: 9001, ticketId: 701, version: 1, status: 'DRAFT', docNumber: null,
        customerName: 'บริษัท ทดสอบ จำกัด', customerTaxId: '', customerAddress: '',
        projectName: '', reference: '', depositPercent: 0.5, notes: [], items: [],
      },
    });
    // Default: no pricing requests on this ticket at all — every existing (pre-chain) test below relies on the
    // legacy ticket-level createDraft path, which is exactly the fallback this resolution degrades to.
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
    // QA-32: the page reads the deal's quotations for its non-blocking "% differs" hint.
    api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
    // ผู้จัดทำ options: the deal's owner, keyer and sales managers.
    api.depositNotices.preparerOptions.mockResolvedValue({ items: [{ id: 1, name: 'Sales' }, { id: 2, name: 'Other Rep' }] });
    api.depositNotices.update.mockResolvedValue({ depositNotice: {} });
    api.depositNotices.issue.mockResolvedValue({
      depositNotice: { id: 9002, docNumber: 'DN-0001', status: 'ISSUED' },
    });
  });

  // Shared fixture for the UX-03 issue-validation tests below: a DRAFT with one fully-valid line item (subtotal
  // 500 -> deposit 250 -> vat 17.5 -> total 267.5, all > 0), so each test only has to break the ONE rule it's
  // asserting on.
  const validDraftDoc = {
    id: 9002, ticketId: 701, version: 1, status: 'DRAFT', docNumber: null,
    customerName: 'บริษัท ทดสอบ จำกัด', customerTaxId: '', customerAddress: '',
    projectName: '', reference: '', depositPercent: 0.5, notes: [],
    items: [
      { seq: 1, description: 'กระเบื้อง A', qty: 5, unit: 'แผ่น', unitPrice: 100, netUnitPrice: 100 },
    ],
  };

  // UX-03: an empty customerName (its label already carries a `*`) must be flagged inline on that field, must
  // not open the confirm dialog, and must never reach api.depositNotices.issue.
  it('blocks issue with an empty customer name, flags it inline, and never opens the confirm dialog', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [validDraftDoc] });
    renderDepositNoticePage();

    const nameInput = await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
    fireEvent.change(nameInput, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));

    const error = await screen.findByText('กรุณากรอกชื่อบริษัท / หน่วยงาน');
    expect(error.getAttribute('role')).toBe('alert');
    expect(nameInput.getAttribute('aria-invalid')).toBe('true');
    expect(nameInput.getAttribute('aria-describedby')).toBe(error.id);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(api.depositNotices.issue).not.toHaveBeenCalled();
  });

  // UX-03 rule 4: a ฿0 deposit notice is meaningless and would still advance
  // the ticket's payment track — issuing it must be blocked even though
  // rules 1-3 (customer name, >=1 item, description+qty) are all satisfied.
  // A % outside a whole 1–100 gets its own message on the % field (it used to blame the unit
  // price, or pass the gate at 150% and fail behind the confirm dialog).
  it('blocks issue when the computed total is zero, or the % is outside 1–100, before the dialog', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{
        ...validDraftDoc,
        items: [
          { seq: 1, description: 'กระเบื้อง A', qty: 5, unit: 'แผ่น', unitPrice: 0, netUnitPrice: 0 },
        ],
      }],
    });
    renderDepositNoticePage();

    await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
    fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));

    expect(await screen.findByText(/ยอดเงินรวมต้องมากกว่า 0/)).not.toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();

    fireEvent.change(document.getElementById('doc-item-0-qty'), { target: { value: '5' } });
    fireEvent.change(screen.getByPlaceholderText('ราคา'), { target: { value: '100' } });
    const percentInput = screen.getByLabelText('มัดจำ %');
    for (const typed of ['0', '150']) {
      fireEvent.change(percentInput, { target: { value: typed } });
      fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));
      const error = await screen.findByText('กรุณากรอก % มัดจำเป็นจำนวนเต็ม 1–100');
      expect(percentInput.getAttribute('aria-invalid')).toBe('true');
      expect(percentInput.getAttribute('aria-describedby')).toBe(error.id);
      expect(document.activeElement).toBe(percentInput);
      expect(screen.queryByText(/ยอดเงินรวมต้องมากกว่า 0/)).toBeNull();
      expect(screen.queryByRole('dialog')).toBeNull();
    }
    expect(api.depositNotices.issue).not.toHaveBeenCalled();
  });

  it('draft: shows a ผู้จัดทำ select with the options and the stored preparer selected; changing it sends preparerId', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ ...validDraftDoc, preparerId: 1, preparerName: 'Sales' }],
    });
    renderDepositNoticePage();

    const select = await screen.findByLabelText('ผู้จัดทำ');
    expect(select.tagName).toBe('SELECT');
    await waitFor(() => expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['Sales', 'Other Rep']));
    expect(select.value).toBe('1');
    fireEvent.change(select, { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalledTimes(1));
    expect(api.depositNotices.update.mock.calls[0][1]).toMatchObject({ preparerId: 2 });
  });

  it('an ISSUED notice shows its preparer as read-only text, no select', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ ...validDraftDoc, status: 'ISSUED', docNumber: 'GLRD69001', preparerId: 2, preparerName: 'Other Rep' }],
    });
    renderDepositNoticePage();

    expect(await screen.findByText('Other Rep')).not.toBeNull();
    expect(screen.queryByLabelText('ผู้จัดทำ')).toBeNull();
  });

  // What บันทึก sends, and the summary beside it, must match what the backend stores:
  // - a stored whole 0.33 shows, labels and saves as 33%;
  // - a 100%-discounted line (net 0, unit 100) adds 0 and is sent at net 0, never its unit price;
  // - money rounds per step like the backend: 100.10 at 33% is 33.03 + VAT 2.31 = 35.34 (35.35 unrounded);
  // - a blank เพิ่มรายการ row is left out (the backend refuses a line without a description);
  // - the draft's updatedAt travels as expectedUpdatedAt (a stale save is refused server-side);
  // - a cleared % sends null, so the save keeps the stored % instead of failing the whole PUT.
  it('saves what the backend will store: rounded %, zero-net line, per-step rounding, no blank row, the stamp, null for a cleared %', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{
        ...validDraftDoc, depositPercent: 0.33, updatedAt: '2026-10-23T08:57:19.732123Z',
        items: [
          { seq: 1, description: 'กระเบื้อง A', qty: 1, unit: 'แผ่น', unitPrice: 100.1, netUnitPrice: 100.1 },
          { seq: 2, description: 'ของแถม', qty: 2, unit: 'แผ่น', unitPrice: 100, netUnitPrice: 0 },
        ],
      }],
    });
    renderDepositNoticePage();

    await screen.findByRole('heading', { name: 'รายการสินค้า' });
    const percentInput = screen.getByLabelText('มัดจำ %');
    expect(percentInput.value).toBe('33');
    expect(summaryLine('ขอรับเงินมัดจำ 33%')).toBe('ขอรับเงินมัดจำ 33% ฿33.03');
    expect(summaryLine('รวมเป็นเงิน')).toBe('รวมเป็นเงิน ฿100.10');
    expect(summaryLine(/ภาษีมูลค่าเพิ่ม 7%/)).toContain('฿2.31');
    expect(summaryLine('รวมเป็นเงินที่ต้องชำระ')).toBe('รวมเป็นเงินที่ต้องชำระ ฿35.34');

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalledTimes(1));
    const payload = api.depositNotices.update.mock.calls[0][1];
    expect(payload).toMatchObject({ depositPercent: 0.33, expectedUpdatedAt: '2026-10-23T08:57:19.732123Z' });
    expect(payload.items).toEqual([
      expect.objectContaining({ seq: 1, unitPrice: 100.1, netUnitPrice: 100.1 }),
      expect.objectContaining({ seq: 2, qty: 2, unitPrice: 100, netUnitPrice: 0 }),
    ]);

    fireEvent.change(percentInput, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalledTimes(2));
    expect(api.depositNotices.update.mock.calls[1][1].depositPercent).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  // A fully valid document opens the confirm dialog, and confirming it calls api.depositNotices.issue for that
  // document.
  it('opens the confirm dialog for a valid document and issues it on confirm, with the stamp its own save returned', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [{ ...validDraftDoc, updatedAt: 'T1' }] });
    api.depositNotices.update.mockResolvedValue({ depositNotice: { updatedAt: 'T2' } });
    renderDepositNoticePage();

    await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
    fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('ยืนยันการออกเอกสาร')).not.toBeNull();

    fireEvent.click(within(dialog).getByRole('button', { name: 'ออกเอกสาร' }));

    await waitFor(() => expect(api.depositNotices.issue).toHaveBeenCalledWith(9002, { expectedUpdatedAt: 'T2' }));
  });

  // A quotation-copied draft: the goods line's stored amount (467,125.00) differs from net x qty (467,130.00), and the
  // ส่วนลดพิเศษ is its own qty -1 line. The page shows and saves the stored amounts; editing a line drops its amount.
  it('shows and saves the quotation\'s stored amounts, drops an edited line\'s amount, and does not block issuing over the discount line', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{
        ...validDraftDoc,
        items: [
          { seq: 1, description: 'กระเบื้อง', qty: 1000, unit: 'แผ่น', unitPrice: 505, netUnitPrice: 467.13, amount: 467125 },
          { seq: 2, description: 'ส่วนลดพิเศษ 45,000 บาท', qty: -1, unit: '', unitPrice: 45000, netUnitPrice: 45000, amount: -45000 },
        ],
      }],
    });
    renderDepositNoticePage();

    expect(await screen.findByText('฿467,125.00')).not.toBeNull();
    expect(screen.getByText('฿-45,000.00')).not.toBeNull();
    expect(screen.getAllByText(/422,125\.00/).length).toBeGreaterThan(0);
    expect(screen.queryByDisplayValue('-1')).toBeNull();   // the discount row's qty is read-only

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalledTimes(1));
    expect(api.depositNotices.update.mock.calls[0][1].items.map((i) => [i.qty, i.amount])).toEqual([[1000, 467125], [-1, -45000]]);

    fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));
    expect(await screen.findByRole('dialog')).not.toBeNull();   // the qty -1 row did not trip "จำนวนมากกว่า 0"
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

    fireEvent.change(screen.getByDisplayValue('1000'), { target: { value: '2' } });
    expect(screen.getByText('฿934.26')).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalledTimes(2));
    expect(api.depositNotices.update.mock.calls[1][1].items.map((i) => i.amount)).toEqual([null, -45000]);
  });

  // QA-32: sales may type a deposit % that differs from the quotation's (owner ruling 2026-10-23);
  // the page says so, without blocking ออกเอกสาร. The draft's reference is the QT number.
  describe('deposit % differs from the quotation (non-blocking hint)', () => {
    const quotation = { id: 61, number: 'QT-2026-0042', depositPercent: 30 };
    function draftWith(over = {}) {
      api.depositNotices.listByTicket.mockResolvedValue({
        depositNotices: [{ ...validDraftDoc, reference: 'QT-2026-0042', depositPercent: 0.4, ...over }],
      });
    }

    it('makes the customer fields read-only, labelled as from the quotation, when it is addressed to a party', async () => {
      draftWith({ customerFromQuotation: true });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [quotation] });
      renderDepositNoticePage();

      expect(await screen.findByTestId('customer-from-quotation')).not.toBeNull();
      expect(screen.getByLabelText(/อ้างอิง PO/).readOnly).toBe(true);
      expect(screen.getByLabelText(/ชื่อบริษัท/).readOnly).toBe(true);
      expect(screen.getByLabelText(/เลขประจำตัวผู้เสียภาษี/).readOnly).toBe(true);
      expect(screen.getByLabelText('ที่อยู่').readOnly).toBe(true);

      // feedback #74: ออกในนามลูกค้าอื่น picks a payer from the master and saves its id
      api.customers.search.mockResolvedValue({ customers: [{ id: 77, name: 'ผู้ชำระ ทดสอบ', taxId: '0100000000077', address: 'กรุงเทพ' }] });
      fireEvent.click(screen.getByTestId('pick-payer'));
      fireEvent.change(screen.getByLabelText('ค้นหาลูกค้า (master)'), { target: { value: 'ผู้ชำระ' } });
      fireEvent.click(await screen.findByRole('button', { name: /ผู้ชำระ ทดสอบ/ }));
      expect(screen.getByLabelText(/ชื่อบริษัท/).value).toBe('ผู้ชำระ ทดสอบ');
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
      await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalled());
      expect(api.depositNotices.update.mock.calls.at(-1)[1]).toMatchObject({ payerCustomerId: 77 });
    });

    it('lets a draft be re-pointed to another party quotation of the deal (a select with number and customer)', async () => {
      draftWith({ customerFromQuotation: true });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [
        { ...quotation, partyId: 1, docStatus: 'APPROVED', origin: 'DEAL_DIRECT', customerName: 'บริษัท เอ' },
        { id: 62, number: 'QT-2026-0043', partyId: 2, docStatus: 'ISSUED', customerName: 'บริษัท บี' },
        { id: 64, number: 'QT-2026-0045', partyId: 3, docStatus: 'DRAFT', customerName: 'ร่าง' },
        { id: 63, number: 'QT-2026-0044', partyId: null, customerName: 'ไม่มีฝ่าย' },
      ] });
      renderDepositNoticePage();

      const select = await screen.findByLabelText(/อ้างอิง PO/);
      await waitFor(() => expect(select.tagName).toBe('SELECT'));
      expect([...select.options].map((o) => o.textContent)).toEqual(['QT-2026-0042 — บริษัท เอ', 'QT-2026-0043 — บริษัท บี']);
    });

    it('keeps the customer fields editable when the server did not take them from a quotation party', async () => {
      draftWith({ customerFromQuotation: false });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [{ ...quotation, partyId: 322 }] });
      renderDepositNoticePage();

      await screen.findByTestId('deposit-percent-differs');
      expect(screen.queryByTestId('customer-from-quotation')).toBeNull();
      expect(screen.getByLabelText(/ชื่อบริษัท/).readOnly).toBe(false);
      expect(screen.getByLabelText(/อ้างอิง PO/).readOnly).toBe(false);
    });

    it('shows which quotation and which percent, next to the notice percent, and leaves ออกเอกสาร enabled', async () => {
      draftWith();
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [{ id: 60, number: 'QT-2026-0001', depositPercent: 50 }, quotation] });
      renderDepositNoticePage();

      const hint = await screen.findByTestId('deposit-percent-differs');
      expect(api.dealQuotations.listForTicket).toHaveBeenCalledWith(701);
      expect(hint.textContent).toContain('ใบเสนอราคา QT-2026-0042 ระบุมัดจำ 30%');
      expect(hint.textContent).toContain('40%');
      expect(hint.className).toContain('text-warning-dark');
      expect(screen.getByRole('button', { name: /ออกเอกสาร/ }).disabled).toBe(false);
    });
  });

  // A notice's lines may be edited after it was drafted (an edited notice is the agreed amount, issuing is never
  // blocked), so the page and the issue confirm say when the notice's subtotal is not its quotation's.
  // validDraftDoc's subtotal is 5 x 100 = 500.
  describe('notice subtotal differs from the quotation (non-blocking line)', () => {
    const quotation = { id: 61, number: 'QT-2026-0042', depositPercent: 50, subtotalAmount: 800 };
    const LINE = 'ใบเสนอราคา QT-2026-0042 มูลค่า 800.00 — ใบแจ้งนี้ 500.00';
    function draftWith(over = {}) {
      api.depositNotices.listByTicket.mockResolvedValue({
        depositNotices: [{ ...validDraftDoc, reference: 'QT-2026-0042', ...over }],
      });
    }

    it('shows the quotation and both subtotals, and leaves ออกเอกสาร enabled', async () => {
      draftWith();
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [quotation] });
      renderDepositNoticePage();

      const line = await screen.findByTestId('deposit-subtotal-differs');
      expect(line.textContent).toBe(LINE);
      expect(line.className).toContain('text-warning-dark');
      expect(screen.getByRole('button', { name: /ออกเอกสาร/ }).disabled).toBe(false);
    });
  });

  // Owner ruling 2026-10-23: on a DRAFT the deposit % is an editable whole-number field (a typed
  // 39.6 rounds to 40). The amount recalculates as sales types, and บันทึก sends the fraction.
  // subtotal 500: 30% -> 150.00, 40% -> 200.00 deposit, 14.00 VAT, 214.00 total; cleared -> 0.
  it('lets sales change the deposit percent on a draft, recalculates the amount, and saves it', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ ...validDraftDoc, depositPercent: 0.3 }],
    });
    renderDepositNoticePage();

    await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
    const percentInput = screen.getByLabelText('มัดจำ %');
    expect(percentInput.disabled).toBe(false);
    expect(percentInput.value).toBe('30');
    expect(summaryLine('ขอรับเงินมัดจำ 30%')).toBe('ขอรับเงินมัดจำ 30% ฿150.00');

    fireEvent.change(percentInput, { target: { value: '39.6' } });

    expect(percentInput.value).toBe('40');
    expect(summaryLine('ขอรับเงินมัดจำ 40%')).toBe('ขอรับเงินมัดจำ 40% ฿200.00');
    expect(summaryLine('รวมเป็นเงินที่ต้องชำระ')).toBe('รวมเป็นเงินที่ต้องชำระ ฿214.00');
    expect(within(document.getElementById('doc-summary-panel')).queryByText('฿150.00')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalledWith(
      9002, expect.objectContaining({ depositPercent: 0.4 }),
    ));

    // A cleared field bills nothing: 0% and ฿0 for deposit, VAT and total, never a silent 50%.
    fireEvent.change(percentInput, { target: { value: '' } });
    expect(summaryLine('ขอรับเงินมัดจำ 0%')).toBe('ขอรับเงินมัดจำ 0% ฿0.00');
    expect(within(document.getElementById('doc-summary-panel')).getAllByText('฿0.00')).toHaveLength(3);
  });

  // the VAT rate must come from the served document's vatPercent, not a hardcoded 0.07 literal —
  // DepositNoticeDto.vatPercent (backend/src/main/java/th/co/glr/hr/ deposit/DepositNoticeDto.java:25) is the
  // authoritative source once a document exists.
  it('an ISSUED notice offers an แก้ไข button: it unlocks the percent, and บันทึก updates the same document without re-issuing', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ ...validDraftDoc, status: 'ISSUED', docNumber: 'GLRD69001', depositPercent: 0.3 }],
    });
    renderDepositNoticePage();

    fireEvent.click(await screen.findByRole('button', { name: 'แก้ไขฉบับปัจจุบัน' }));
    const percentInput = await screen.findByLabelText('มัดจำ %');
    expect(percentInput.disabled).toBe(false);
    fireEvent.change(percentInput, { target: { value: '40' } });
    // Still the same issued document: the issue action never reappears.
    expect(screen.queryByRole('button', { name: /ออกเอกสาร/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalledWith(
      9002, expect.objectContaining({ depositPercent: 0.4 }),
    ));
    expect(api.depositNotices.issue).not.toHaveBeenCalled();
  });

  it('startEditing (?edit=1 from เอกสารขาย) opens an issued notice straight in edit mode', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ ...validDraftDoc, status: 'ISSUED', docNumber: 'GLRD69001', depositPercent: 0.3 }],
    });
    renderDepositNoticePage({ startEditing: true });

    expect((await screen.findByLabelText('มัดจำ %')).disabled).toBe(false);
    expect(screen.queryByRole('button', { name: 'แก้ไขฉบับปัจจุบัน' })).toBeNull();
  });

  it('canWrite=false shows an issued notice and a draft read-only: no edit, save, issue or add-line controls, download stays', async () => {
    for (const status of ['ISSUED', 'DRAFT']) {
      api.depositNotices.listByTicket.mockResolvedValue({
        depositNotices: [{ ...validDraftDoc, status, docNumber: status === 'ISSUED' ? 'GLRD69001' : null }],
      });
      const { unmount } = renderDepositNoticePage({ canWrite: false });
      expect(await screen.findByRole('button', { name: 'ดาวน์โหลด' })).toBeTruthy();
      expect(screen.queryByLabelText('มัดจำ %')).toBeNull();
      for (const name of ['แก้ไขฉบับปัจจุบัน', 'บันทึกร่าง', /ออกเอกสาร/, /เพิ่มรายการ/]) {
        expect(screen.queryByRole('button', { name })).toBeNull();
      }
      unmount();
    }
  });

  it('uses the served document vatPercent (10%) in the VAT label and amount, not the 7% fallback', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ ...validDraftDoc, vatPercent: 0.10 }],
    });
    renderDepositNoticePage();

    await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');

    // Summary panel: label carries the served 10% rate, not a hardcoded "7%" — and the VAT amount is deposit(250)
    // * 0.10 = 25.00, not deposit * 0.07 = 17.50.
    expect(screen.getByText('ภาษีมูลค่าเพิ่ม 10% (คิดจากมัดจำ)')).not.toBeNull();
    expect(screen.queryByText(/ภาษีมูลค่าเพิ่ม 7%/)).toBeNull();
    expect(summaryLine(/ภาษีมูลค่าเพิ่ม 10%/)).toBe('ภาษีมูลค่าเพิ่ม 10% (คิดจากมัดจำ) ฿25.00');
    expect(within(document.getElementById('doc-summary-panel')).queryByText('฿17.50')).toBeNull();

    // ConfirmDialog carries the same served rate + amount (validDraftDoc is a fully valid document, so "ออกเอกสาร"
    // opens the dialog with no issue-validation errors in the way).
    fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));
    const dialog = await screen.findByRole('dialog');
    // Drawing m-deposit-issue labels the row "VAT n%" (was "ภาษีมูลค่าเพิ่ม n%"); the rate is still the served one.
    expect(within(dialog).getByText('VAT 10%')).not.toBeNull();
    expect(within(dialog).queryByText(/VAT 7%$/)).toBeNull();
    expect(within(dialog).getByText('25.00 บาท')).not.toBeNull();
    expect(within(dialog).queryByText('17.50 บาท')).toBeNull();
  });

  // Drawing m-deposit-issue (2026-11-16): the confirm body is rebuilt; the requests it sends are not.
  describe('issue confirm (drawing m-deposit-issue)', () => {
    // subtotal 3 x 1,234.57 = 3,703.71; 40% -> 1,481.48 deposit; 7% -> 103.70 VAT; 1,585.18 total. All differ.
    const doc = {
      ...validDraftDoc, customerName: 'บริษัท ลุมพินี จำกัด', customerTaxId: '0105551234567', customerAddress: 'กรุงเทพฯ',
      projectName: 'บ้านชะอำ', reference: 'QT-2026-0201', depositPercent: 0.4, notes: ['หมายเหตุมาตรฐาน'], updatedAt: 'T1',
      items: [{ seq: 1, description: 'กระเบื้อง Raku', qty: 3, unit: 'ตร.ม.', unitPrice: 1500, discountLabel: '-', netUnitPrice: 1234.57, amount: null }],
    };

    // Payload identity — captured against the pre-rebuild dialog and kept green through it.
    it('confirm saves the form, then issues with the stamp that save returned — byte-for-byte as before', async () => {
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [doc] });
      api.depositNotices.update.mockResolvedValue({ depositNotice: { updatedAt: 'T2' } });
      renderDepositNoticePage();

      await screen.findByDisplayValue('บริษัท ลุมพินี จำกัด');
      fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));
      fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'ออกเอกสาร' }));

      await waitFor(() => expect(api.depositNotices.issue).toHaveBeenCalledTimes(1));
      expect(api.depositNotices.update.mock.calls).toEqual([[9002, {
        customerName: 'บริษัท ลุมพินี จำกัด', customerTaxId: '0105551234567', customerAddress: 'กรุงเทพฯ',
        projectName: 'บ้านชะอำ', reference: 'QT-2026-0201', depositPercent: 0.4, notes: ['หมายเหตุมาตรฐาน'], preparerId: null, payerCustomerId: null,
        expectedUpdatedAt: 'T1',
        items: [{ seq: 1, description: 'กระเบื้อง Raku', qty: 3, unit: 'ตร.ม.', unitPrice: 1500, discountLabel: '-', netUnitPrice: 1234.57, amount: null }],
      }]]);
      expect(api.depositNotices.issue.mock.calls).toEqual([[9002, { expectedUpdatedAt: 'T2' }]]);
      expect(api.depositNotices.update.mock.invocationCallOrder[0])
        .toBeLessThan(api.depositNotices.issue.mock.invocationCallOrder[0]);
    });
  });
});
