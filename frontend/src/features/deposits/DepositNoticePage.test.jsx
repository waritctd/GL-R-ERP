import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DepositIssueSummary, DepositNoticePage } from './DepositNoticePage.jsx';
import { api } from '../../api/index.js';
import { downloadBlob } from '../../utils/download.js';

globalThis.React = React;

vi.mock('../../utils/download.js', () => ({ downloadBlob: vi.fn() }));

vi.mock('../../api/index.js', () => ({
  api: {
    depositNotices: {
      noteTemplates: vi.fn(),
      listByTicket: vi.fn(),
      createDraft: vi.fn(),
      update: vi.fn(),
      issue: vi.fn(),
      downloadXlsx: vi.fn(),
      downloadPdf: vi.fn(),
    },
    pricingRequests: {
      listForTicket: vi.fn(),
      createDepositNoticeFromQuotation: vi.fn(),
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

// Open the ดาวน์โหลด menu and pick a format.
async function pickDownload(format) {
  fireEvent.click(await screen.findByRole('button', { name: 'ดาวน์โหลด' }));
  fireEvent.click(await screen.findByRole('menuitem', { name: format }));
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
    api.pricingRequests.createDepositNoticeFromQuotation.mockResolvedValue({
      depositNotice: {
        id: 9003, ticketId: 701, version: 1, status: 'DRAFT', docNumber: null,
        customerName: 'บริษัท ทดสอบ จำกัด', customerTaxId: '', customerAddress: '',
        projectName: '', reference: 'QT-2026-0001', depositPercent: 0.5, notes: [], items: [],
      },
    });
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

  // UX-03: a line item missing its description must flag THAT item's description field specifically — not the
  // whole form, not the other (valid) item's fields.
  it('blocks issue when a line item is missing its description, flagging that item specifically', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{
        ...validDraftDoc,
        items: [
          { seq: 1, description: 'กระเบื้อง A', qty: 5, unit: 'แผ่น', unitPrice: 100, netUnitPrice: 100 },
          { seq: 2, description: '', qty: 5, unit: 'แผ่น', unitPrice: 100, netUnitPrice: 100 },
        ],
      }],
    });
    renderDepositNoticePage();

    await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
    fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));

    const error = await screen.findByText('กรุณากรอกรายละเอียดสินค้าในรายการที่ 2');
    expect(error.getAttribute('role')).toBe('alert');

    const row1Description = document.getElementById('doc-item-0-description');
    const row2Description = document.getElementById('doc-item-1-description');
    expect(row1Description.getAttribute('aria-invalid')).not.toBe('true');
    expect(row2Description.getAttribute('aria-invalid')).toBe('true');
    expect(row2Description.getAttribute('aria-describedby')).toBe(error.id);
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

  // Regression guard for the SCOPING DECISION: this is a draft editor and "บันทึก" must stay fully permissive —
  // saving an incomplete draft (no customer name, no items) is a legitimate workflow and must never be blocked
  // by the issue-time validation added above.
  it('still saves an incomplete draft (no customer name, no items) via บันทึก', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ ...validDraftDoc, customerName: '', items: [] }],
    });
    renderDepositNoticePage();

    await screen.findByRole('heading', { name: 'รายการสินค้า' });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalledTimes(1));
    expect(api.depositNotices.update).toHaveBeenCalledWith(
      9002,
      expect.objectContaining({ customerName: '', items: [] }),
    );
    // No issue-validation errors should have been introduced by saving.
    expect(screen.queryByRole('alert')).toBeNull();
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

  // The page must not rewrite what it did not edit: a stored 0.125 (shown as 13) survives a save of
  // another field untouched.
  it('keeps a stored non-whole % as-is when only another field is edited', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ ...validDraftDoc, depositPercent: 0.125, updatedAt: 'T1' }],
    });
    renderDepositNoticePage();

    fireEvent.change(await screen.findByLabelText(/ที่อยู่/), { target: { value: 'ที่อยู่ใหม่' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalledTimes(1));
    expect(api.depositNotices.update.mock.calls[0][1]).toMatchObject({ depositPercent: 0.125, customerAddress: 'ที่อยู่ใหม่' });
  });

  // Blank rows are dropped from the payload and the backend keeps the stored lines when `items` is
  // empty — so saving/downloading an all-blank form would "succeed" and bring the old lines back.
  it('refuses to save or download when every row was cleared, with the items error, and calls no API', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [validDraftDoc] });
    renderDepositNoticePage();

    await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
    fireEvent.change(document.getElementById('doc-item-0-description'), { target: { value: '' } });
    fireEvent.change(document.getElementById('doc-item-0-qty'), { target: { value: '0' } });

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    expect((await screen.findByText('กรุณาเพิ่มรายการสินค้าอย่างน้อย 1 รายการ')).getAttribute('role')).toBe('alert');
    expect(api.depositNotices.update).not.toHaveBeenCalled();

    await pickDownload('PDF');
    expect(api.depositNotices.update).not.toHaveBeenCalled();
    expect(api.depositNotices.downloadPdf).not.toHaveBeenCalled();
    expect(downloadBlob).not.toHaveBeenCalled();
    expect(screen.getByText('กรุณาเพิ่มรายการสินค้าอย่างน้อย 1 รายการ')).not.toBeNull();
  });

  // One ดาวน์โหลด menu (PDF, Excel) serves a draft and an issued notice, like the quotation editor's.
  describe('ดาวน์โหลด menu', () => {
    const pdfBlob = { type: 'application/pdf' };
    const xlsxBlob = { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' };
    beforeEach(() => {
      api.depositNotices.downloadPdf.mockResolvedValue(pdfBlob);
      api.depositNotices.downloadXlsx.mockResolvedValue(xlsxBlob);
    });

    it('lists PDF and Excel in one menu and no separate download buttons', async () => {
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [validDraftDoc] });
      renderDepositNoticePage();

      fireEvent.click(await screen.findByRole('button', { name: 'ดาวน์โหลด' }));
      const menu = within(await screen.findByRole('menu', { name: 'ดาวน์โหลด' }));
      expect(menu.getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['PDF', 'Excel']);
      expect(screen.queryByRole('button', { name: 'ดาวน์โหลด PDF' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'ดาวน์โหลด Excel' })).toBeNull();
    });

    it('disables the download items while a save is pending, so no second update goes out', async () => {
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [{ ...validDraftDoc, updatedAt: 'T1' }] });
      api.depositNotices.update.mockReturnValue(new Promise(() => {}));
      renderDepositNoticePage();

      fireEvent.change(await screen.findByLabelText(/ที่อยู่/), { target: { value: 'ที่อยู่ใหม่' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
      await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalledTimes(1));
      fireEvent.click(await screen.findByRole('button', { name: 'ดาวน์โหลด' }));
      const item = await screen.findByRole('menuitem', { name: /^PDF/ });
      expect(item.getAttribute('aria-disabled')).toBe('true');
      fireEvent.click(item);
      expect(api.depositNotices.update).toHaveBeenCalledTimes(1);
      expect(api.depositNotices.downloadPdf).not.toHaveBeenCalled();
    });

    it('downloads an unchanged draft as stored, without saving', async () => {
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [{ ...validDraftDoc, updatedAt: 'T1' }] });
      renderDepositNoticePage();

      await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
      await pickDownload('PDF');

      await waitFor(() => expect(api.depositNotices.downloadPdf).toHaveBeenCalledWith(9002));
      await waitFor(() => expect(downloadBlob).toHaveBeenCalledWith(pdfBlob, 'draft', 'pdf'));
      expect(api.depositNotices.update).not.toHaveBeenCalled();
    });

    it('saves an edited draft first (same payload as บันทึกร่าง), then downloads', async () => {
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [{ ...validDraftDoc, updatedAt: 'T1' }] });
      api.depositNotices.update.mockResolvedValue({ depositNotice: { updatedAt: 'T2' } });
      renderDepositNoticePage();

      fireEvent.change(await screen.findByLabelText(/ที่อยู่/), { target: { value: 'ที่อยู่ใหม่' } });
      await pickDownload('Excel');

      await waitFor(() => expect(api.depositNotices.downloadXlsx).toHaveBeenCalledWith(9002));
      expect(api.depositNotices.update).toHaveBeenCalledTimes(1);
      expect(api.depositNotices.update.mock.calls[0][1])
        .toMatchObject({ customerAddress: 'ที่อยู่ใหม่', expectedUpdatedAt: 'T1' });
      expect(api.depositNotices.update.mock.invocationCallOrder[0])
        .toBeLessThan(api.depositNotices.downloadXlsx.mock.invocationCallOrder[0]);
    });

    it('treats a form just saved as unchanged: a second download does not save again', async () => {
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [{ ...validDraftDoc, updatedAt: 'T1' }] });
      api.depositNotices.update.mockResolvedValue({ depositNotice: { updatedAt: 'T2' } });
      renderDepositNoticePage();

      fireEvent.change(await screen.findByLabelText(/ที่อยู่/), { target: { value: 'ที่อยู่ใหม่' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
      await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalledTimes(1));
      await pickDownload('PDF');

      await waitFor(() => expect(api.depositNotices.downloadPdf).toHaveBeenCalledTimes(1));
      expect(api.depositNotices.update).toHaveBeenCalledTimes(1);
    });

    it('does not download when the save before it fails, and shows the save error', async () => {
      const showToast = vi.fn();
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [{ ...validDraftDoc, updatedAt: 'T1' }] });
      api.depositNotices.update.mockRejectedValue(new Error('บันทึกไม่สำเร็จ ลองใหม่'));
      renderDepositNoticePage({ showToast });

      fireEvent.change(await screen.findByLabelText(/ที่อยู่/), { target: { value: 'ที่อยู่ใหม่' } });
      await pickDownload('PDF');

      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'บันทึกไม่สำเร็จ ลองใหม่'));
      expect(api.depositNotices.update).toHaveBeenCalledTimes(1);
      expect(api.depositNotices.downloadPdf).not.toHaveBeenCalled();
      expect(downloadBlob).not.toHaveBeenCalled();
    });

    it('on a 409 save reloads the draft and does not download', async () => {
      api.depositNotices.listByTicket
        .mockResolvedValueOnce({ depositNotices: [{ ...validDraftDoc, depositPercent: 0.3, updatedAt: 'T1' }] })
        .mockResolvedValue({ depositNotices: [{ ...validDraftDoc, depositPercent: 0.4, updatedAt: 'T2' }] });
      const stale = Object.assign(new Error('ถูกแก้ไขโดยผู้ใช้อื่น'), { status: 409 });
      api.depositNotices.update.mockRejectedValueOnce(stale);
      renderDepositNoticePage();

      await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
      const percentInput = screen.getByLabelText('มัดจำ %');
      fireEvent.change(percentInput, { target: { value: '35' } });
      await pickDownload('PDF');

      await waitFor(() => expect(percentInput.value).toBe('40'));
      expect(api.depositNotices.downloadPdf).not.toHaveBeenCalled();
    });

    it('calls neither update nor download for a draft whose rows were all cleared, and shows the items error', async () => {
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [validDraftDoc] });
      renderDepositNoticePage();

      await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
      fireEvent.change(document.getElementById('doc-item-0-description'), { target: { value: '' } });
      fireEvent.change(document.getElementById('doc-item-0-qty'), { target: { value: '0' } });
      await pickDownload('Excel');

      expect((await screen.findByText('กรุณาเพิ่มรายการสินค้าอย่างน้อย 1 รายการ')).getAttribute('role')).toBe('alert');
      expect(api.depositNotices.update).not.toHaveBeenCalled();
      expect(api.depositNotices.downloadXlsx).not.toHaveBeenCalled();
    });

    it('downloads an issued notice directly, without saving', async () => {
      api.depositNotices.listByTicket.mockResolvedValue({
        depositNotices: [{ ...validDraftDoc, status: 'ISSUED', docNumber: 'GLRD69001', updatedAt: 'T1' }],
      });
      renderDepositNoticePage();

      await screen.findAllByText('บริษัท ทดสอบ จำกัด', { exact: false });
      await pickDownload('PDF');

      await waitFor(() => expect(api.depositNotices.downloadPdf).toHaveBeenCalledWith(9002));
      await waitFor(() => expect(downloadBlob).toHaveBeenCalledWith(pdfBlob, 'GLRD69001', 'pdf'));
      expect(api.depositNotices.update).not.toHaveBeenCalled();
    });

    it('disables both menu items while a download is running', async () => {
      let finish;
      api.depositNotices.downloadPdf.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [{ ...validDraftDoc, updatedAt: 'T1' }] });
      renderDepositNoticePage();

      await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
      await pickDownload('PDF');
      await waitFor(() => expect(api.depositNotices.downloadPdf).toHaveBeenCalledTimes(1));

      fireEvent.click(screen.getByRole('button', { name: 'ดาวน์โหลด' }));
      // A disabled item also names why ("กำลังดาวน์โหลด…"), so match on the format it starts with.
      expect((await screen.findByRole('menuitem', { name: /^PDF/ })).getAttribute('aria-disabled')).toBe('true');
      expect(screen.getByRole('menuitem', { name: /^Excel/ }).getAttribute('aria-disabled')).toBe('true');
      finish(pdfBlob);
    });
  });

  // Someone else saved the draft since this page loaded it: the backend answers 409. The page
  // reloads the draft (form and stamp) and shows the server's message, so the next save is a
  // deliberate one over the fresh copy.
  it('on a 409 save, reloads the draft and shows the server message', async () => {
    const showToast = vi.fn();
    api.depositNotices.listByTicket
      .mockResolvedValueOnce({ depositNotices: [{ ...validDraftDoc, depositPercent: 0.3, updatedAt: 'T1' }] })
      .mockResolvedValue({ depositNotices: [{ ...validDraftDoc, depositPercent: 0.4, updatedAt: 'T2' }] });
    const stale = Object.assign(new Error('ใบแจ้งรับมัดจำนี้ถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง'), { status: 409 });
    api.depositNotices.update.mockRejectedValueOnce(stale);
    renderDepositNoticePage({ showToast });

    await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
    const percentInput = screen.getByLabelText('มัดจำ %');
    fireEvent.change(percentInput, { target: { value: '35' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', stale.message));
    expect(api.depositNotices.update.mock.calls[0][1]).toMatchObject({ depositPercent: 0.35, expectedUpdatedAt: 'T1' });
    await waitFor(() => expect(percentInput.value).toBe('40'));

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalledTimes(2));
    expect(api.depositNotices.update.mock.calls[1][1]).toMatchObject({ depositPercent: 0.4, expectedUpdatedAt: 'T2' });
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

  it('renders the empty-state and lets the user create a draft', async () => {
    renderDepositNoticePage();

    expect(await screen.findByText('ยังไม่มีใบแจ้งยอดเงินรับมัดจำ')).not.toBeNull();
    // A deal document: the helper says ดีล, not คำขอราคา.
    expect(screen.getByText('สร้างเอกสารฉบับร่างเพื่อเริ่มกรอกรายละเอียดใบแจ้งยอดมัดจำสำหรับงานนี้')).not.toBeNull();

    const createButton = screen.getByRole('button', { name: /สร้างเอกสารฉบับร่าง/ });
    fireEvent.click(createButton);

    await waitFor(() => expect(api.depositNotices.createDraft).toHaveBeenCalledTimes(1));
    // depositPercent is not sent on create: the backend picks the draft's starting percent.
    // Sales changes it on the draft afterwards.
    expect(api.depositNotices.createDraft).toHaveBeenCalledWith('701', {
      notes: ['หมายเหตุมาตรฐาน'],
    });

    // Mutation succeeding invalidates depositNotices(ticketId), which refetches listByTicket — the second call is
    // that background refetch.
    await waitFor(() => expect(api.depositNotices.listByTicket).toHaveBeenCalledTimes(2));
  });

  // Branch fix (deposit-notice autofill): a chain deal — one of this ticket's pricing requests has an accepted
  // customer quotation (status QUOTATION_ACCEPTED) — must use the correct endpoint instead of the legacy
  // ticket-level route.
  it('uses the pricing-request quotation endpoint when the ticket has an accepted quotation', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({
      items: [
        { id: 501, status: 'QUOTATION_ISSUED' },
        { id: 502, status: 'QUOTATION_ACCEPTED' },
      ],
    });
    renderDepositNoticePage();

    const createButton = await screen.findByRole('button', { name: /สร้างเอกสารฉบับร่าง/ });
    fireEvent.click(createButton);

    await waitFor(() => expect(api.pricingRequests.createDepositNoticeFromQuotation).toHaveBeenCalledTimes(1));
    // depositPercent is not sent on create: the backend takes it from the accepted quotation.
    expect(api.pricingRequests.createDepositNoticeFromQuotation).toHaveBeenCalledWith(502, {});
    expect(api.depositNotices.createDraft).not.toHaveBeenCalled();
  });

  // No pricing request on this ticket has an accepted quotation (e.g. still mid-negotiation, or a legacy ticket
  // with pricing requests that never went through the chain) — must fall back to today's ticket-level route
  // rather than erroring.
  it('falls back to the ticket-level route when no pricing request has an accepted quotation', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({
      items: [{ id: 501, status: 'QUOTATION_ISSUED' }],
    });
    renderDepositNoticePage();

    const createButton = await screen.findByRole('button', { name: /สร้างเอกสารฉบับร่าง/ });
    fireEvent.click(createButton);

    await waitFor(() => expect(api.depositNotices.createDraft).toHaveBeenCalledTimes(1));
    expect(api.depositNotices.createDraft).toHaveBeenCalledWith('701', {
      notes: ['หมายเหตุมาตรฐาน'],
    });
    expect(api.pricingRequests.createDepositNoticeFromQuotation).not.toHaveBeenCalled();
  });

  // A failed pricing-request lookup must NOT silently route the deal to the ticket-level engine
  // (it could create the wrong kind of deposit notice): the error surfaces and nothing is created.
  it('surfaces the error and creates nothing when resolving pricing requests fails', async () => {
    api.pricingRequests.listForTicket.mockRejectedValue(new Error('network error'));
    const showToast = vi.fn();
    renderDepositNoticePage({ showToast });

    const createButton = await screen.findByRole('button', { name: /สร้างเอกสารฉบับร่าง/ });
    fireEvent.click(createButton);

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'network error'));
    expect(api.depositNotices.createDraft).not.toHaveBeenCalled();
    expect(api.pricingRequests.createDepositNoticeFromQuotation).not.toHaveBeenCalled();
  });

  it('renders the existing draft document instead of the empty state', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{
        id: 9002, ticketId: 701, version: 1, status: 'DRAFT', docNumber: null,
        customerName: 'บริษัท ทดสอบ จำกัด', customerTaxId: '1234567890123', customerAddress: 'กรุงเทพฯ',
        projectName: 'โครงการ A', reference: 'PO-001', depositPercent: 0.5, notes: [], items: [],
      }],
    });

    renderDepositNoticePage();

    await waitFor(() => expect(screen.queryByText('ยังไม่มีใบแจ้งยอดเงินรับมัดจำ')).toBeNull());
    expect(await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด')).not.toBeNull();

    // QA-32: this is a DEAL document, so the header says ใบแจ้งยอดเงินรับมัดจำ for this customer and project, never
    // คำขอราคา, and the issue helper no longer talks about a คำขอราคา status change. The page header (title,
    // subtitle) replaced the old breadcrumb trail.
    expect(screen.getByRole('heading', { name: 'ใบแจ้งยอดเงินรับมัดจำ' })).not.toBeNull();
    expect(screen.getByText('บริษัท ทดสอบ จำกัด · โครงการ A')).not.toBeNull();
    expect(screen.getByRole('button', { name: /เปิดงาน/ })).not.toBeNull();
    expect(document.body.textContent).not.toContain('คำขอราคา');
    const helper = screen.getByText(/กด "ออกเอกสาร" เพื่อออกเลขที่เอกสารอย่างเป็นทางการ/);
    expect(helper.textContent).toMatch(/แก้ไขข้อมูลในเอกสารนี้ไม่ได้อีก$/);
    expect(helper.textContent).not.toContain('คำขอราคา');
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

    // A quotation with no % stored means the 30% default everywhere else, so 40% differs from it.
    it('reads a quotation without a stored deposit percent as 30%', async () => {
      draftWith();
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [{ ...quotation, depositPercent: null }] });
      renderDepositNoticePage();

      const hint = await screen.findByTestId('deposit-percent-differs');
      expect(hint.textContent).toContain('ใบเสนอราคา QT-2026-0042 ระบุมัดจำ 30%');
    });

    it.each([
      ['the percents are equal', { depositPercent: 0.3 }, [quotation]],
      ['no quotation carries the draft reference', { reference: 'PO-001' }, [quotation]],
      // An issued notice is a record; the hint is advice for a draft still being edited.
      ['the notice is already issued', { status: 'ISSUED', docNumber: 'DN-0001' }, [quotation]],
    ])('shows no hint when %s', async (_name, docOver, items) => {
      draftWith(docOver);
      api.dealQuotations.listForTicket.mockResolvedValue({ items });
      renderDepositNoticePage();

      await screen.findAllByText('บริษัท ทดสอบ จำกัด', { exact: false });
      await waitFor(() => expect(api.dealQuotations.listForTicket).toHaveBeenCalled());
      expect(screen.queryByTestId('deposit-percent-differs')).toBeNull();
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

    it('shows nothing when the subtotals are equal', async () => {
      draftWith();
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [{ ...quotation, subtotalAmount: 500 }] });
      renderDepositNoticePage();

      await screen.findAllByText('บริษัท ทดสอบ จำกัด', { exact: false });
      await waitFor(() => expect(api.dealQuotations.listForTicket).toHaveBeenCalled());
      expect(screen.queryByTestId('deposit-subtotal-differs')).toBeNull();
    });

    it('resolves the quotation by orderConfirmedQuotationId even when the reference is a PO number', async () => {
      draftWith({ reference: 'PO-001' });
      api.dealQuotations.listForTicket.mockResolvedValue({
        items: [{ id: 60, number: 'QT-2026-0001', subtotalAmount: 1 }, quotation], orderConfirmedQuotationId: 61,
      });
      renderDepositNoticePage();

      expect((await screen.findByTestId('deposit-subtotal-differs')).textContent).toBe(LINE);
    });

    it('falls back to the quotation whose number is the reference when the response names none', async () => {
      draftWith();
      api.dealQuotations.listForTicket.mockResolvedValue({
        items: [{ id: 60, number: 'QT-2026-0001', subtotalAmount: 1 }, quotation], orderConfirmedQuotationId: null,
      });
      renderDepositNoticePage();

      expect((await screen.findByTestId('deposit-subtotal-differs')).textContent).toBe(LINE);
    });

    it('shows nothing when no quotation resolves', async () => {
      draftWith({ reference: 'PO-001' });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [quotation], orderConfirmedQuotationId: null });
      renderDepositNoticePage();

      await screen.findAllByText('บริษัท ทดสอบ จำกัด', { exact: false });
      await waitFor(() => expect(api.dealQuotations.listForTicket).toHaveBeenCalled());
      expect(screen.queryByTestId('deposit-subtotal-differs')).toBeNull();
    });

    it('shows nothing on an issued notice', async () => {
      draftWith({ status: 'ISSUED', docNumber: 'DN-0001' });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [quotation] });
      renderDepositNoticePage();

      await screen.findAllByText('บริษัท ทดสอบ จำกัด', { exact: false });
      await waitFor(() => expect(api.dealQuotations.listForTicket).toHaveBeenCalled());
      expect(screen.queryByTestId('deposit-subtotal-differs')).toBeNull();
    });

    it('the issue confirm repeats the line', async () => {
      draftWith();
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [quotation] });
      renderDepositNoticePage();

      await screen.findByTestId('deposit-subtotal-differs');
      fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));
      const dialog = await screen.findByRole('dialog');
      expect(within(dialog).getByTestId('deposit-issue-differs').textContent).toBe(LINE);
    });
  });

  describe('DepositIssueSummary quotation line', () => {
    const base = { customerName: 'ลูกค้า', depositPercent: 0.3, subtotal: 500, deposit: 150, vatPercent: 0.07, vat: 10.5, total: 160.5 };

    it('shows the line when the subtotal differs from the quotation\'s', () => {
      render(<DepositIssueSummary {...base} quotation={{ number: 'QT-9', subtotalAmount: 800 }} />);
      expect(screen.getByTestId('deposit-issue-differs').textContent).toBe('ใบเสนอราคา QT-9 มูลค่า 800.00 — ใบแจ้งนี้ 500.00');
    });

    it('shows nothing when equal, or when no quotation is given', () => {
      const { rerender } = render(<DepositIssueSummary {...base} quotation={{ number: 'QT-9', subtotalAmount: 500 }} />);
      expect(screen.queryByTestId('deposit-issue-differs')).toBeNull();
      rerender(<DepositIssueSummary {...base} />);
      expect(screen.queryByTestId('deposit-issue-differs')).toBeNull();
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

  // Phone width (Task 5): the item row reflows into a card, and a cell only gets its column name there from
  // `data-label`. Without it the card is a stack of unlabelled numbers.
  it.each([['DRAFT'], ['ISSUED']])('labels every item cell with its column name for the phone card (%s)', async (status) => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ ...validDraftDoc, status, docNumber: status === 'ISSUED' ? 'GLRD69001' : null }],
    });
    renderDepositNoticePage();
    await waitFor(() => expect(document.querySelector('.data-row.reflow-cards')).not.toBeNull());
    const labels = [...document.querySelector('.data-row.reflow-cards').children].map((c) => c.getAttribute('data-label'));
    expect(labels).toEqual(['รายละเอียด', 'จำนวน', 'ราคา/หน่วย', '', 'สุทธิ', 'เป็นเงิน']);
  });

  it.each([['DRAFT'], ['ISSUED']])('shows "ลด X" in a labelled ส่วนลด cell on a line priced above its net (%s)', async (status) => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{
        ...validDraftDoc, status, docNumber: status === 'ISSUED' ? 'GLRD69001' : null,
        items: [{ seq: 1, description: 'กระเบื้อง A', qty: 1, unit: 'แผ่น', unitPrice: 1300, netUnitPrice: 100 }],
      }],
    });
    renderDepositNoticePage();
    await waitFor(() => expect(document.querySelector('.data-row.reflow-cards')).not.toBeNull());
    const cell = document.querySelector('.data-row.reflow-cards').children[3];
    expect(cell.textContent).toBe('ลด 1,200.00');
    expect(cell.getAttribute('data-label')).toBe('ส่วนลด');
  });

  it('shows the deposit percent read-only once the notice is issued', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ ...validDraftDoc, status: 'ISSUED', docNumber: 'GLRD69001', depositPercent: 0.3 }],
    });
    renderDepositNoticePage();

    // An issued notice shows the percent as plain text: there is no input to edit.
    const percent = await screen.findByText('30%');
    expect(percent.tagName).toBe('STRONG');
    expect(screen.queryByLabelText('มัดจำ %')).toBeNull();
    expect(document.getElementById('doc-deposit-percent')).toBeNull();
  });

  // the VAT rate must come from the served document's vatPercent, not a hardcoded 0.07 literal —
  // DepositNoticeDto.vatPercent (backend/src/main/java/th/co/glr/hr/ deposit/DepositNoticeDto.java:25) is the
  // authoritative source once a document exists.
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
        projectName: 'บ้านชะอำ', reference: 'QT-2026-0201', depositPercent: 0.4, notes: ['หมายเหตุมาตรฐาน'],
        expectedUpdatedAt: 'T1',
        items: [{ seq: 1, description: 'กระเบื้อง Raku', qty: 3, unit: 'ตร.ม.', unitPrice: 1500, discountLabel: '-', netUnitPrice: 1234.57, amount: null }],
      }]]);
      expect(api.depositNotices.issue.mock.calls).toEqual([[9002, { expectedUpdatedAt: 'T2' }]]);
      expect(api.depositNotices.update.mock.invocationCallOrder[0])
        .toBeLessThan(api.depositNotices.issue.mock.invocationCallOrder[0]);
    });

    it('cancel closes the confirm and sends nothing', async () => {
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [doc] });
      renderDepositNoticePage();

      await screen.findByDisplayValue('บริษัท ลุมพินี จำกัด');
      fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));
      fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'ยกเลิก' }));

      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      expect(api.depositNotices.update).not.toHaveBeenCalled();
      expect(api.depositNotices.issue).not.toHaveBeenCalled();
    });

    // Fix round 1, F2: this page's figures are computed in the browser (round2 on floats) and the server
    // recomputes them on save, so they can be a satang off the issued document. The confirm shows the old
    // confirm's figures only (deposit, VAT, total) under an estimate line — no subtotal row, no bold
    // "X บาท รวม VAT" summary presenting them as the document's content.
    it('shows the old figures as an estimate: estimate line, no summary line, no subtotal row', async () => {
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [doc] });
      renderDepositNoticePage();

      await screen.findByDisplayValue('บริษัท ลุมพินี จำกัด');
      fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));
      const dialogEl = await screen.findByRole('dialog');
      const dialog = within(dialogEl);
      expect(dialog.getByTestId('deposit-issue-estimate').textContent).toBe('ยอดโดยประมาณ · ยอดจริงตามเอกสารที่ระบบออก');
      expect(dialog.queryByTestId('deposit-issue-summary')).toBeNull();
      expect(dialogEl.textContent).not.toContain('รวม VAT');
      expect(dialogEl.textContent).not.toContain('มูลค่าสินค้า');
      expect(dialogEl.textContent).not.toContain('3,703.71');
      const row = (label) => dialog.getByText(label, { exact: false }).closest('tr');
      expect(row('มัดจำ 40% (ก่อน VAT)').textContent).toContain('1,481.48');
      expect(row('VAT 7%').textContent).toContain('103.70');
      expect(row('ยอดที่ลูกค้าต้องชำระ').textContent).toContain('1,585.18');
      expect(dialogEl.textContent).not.toContain('ดีล');
    });

    // Fix round 1, F4: the warning is the first thing in the scrolling body, so a short phone screen shows it
    // together with the footer's ออกเอกสาร.
    it('puts the irreversibility warning first in the scrolling body', async () => {
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [doc] });
      renderDepositNoticePage();

      await screen.findByDisplayValue('บริษัท ลุมพินี จำกัด');
      fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));
      const dialogEl = await screen.findByRole('dialog');
      const body = dialogEl.querySelector('.modal-body');
      const warning = within(dialogEl).getByTestId('deposit-issue-warning');
      expect(warning.textContent).toMatch(/หลังจากนี้จะไม่สามารถแก้ไขได้/);
      expect(body.firstElementChild.firstElementChild).toBe(warning);
    });
  });
});
