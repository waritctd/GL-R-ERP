import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FinanceDealPage } from './FinanceDealPage.jsx';
import { makeFinanceDeal, makeTrack } from '../../test/fixtures/financeDeal.js';
import { api } from '../../api/index.js';
import { ApiError } from '../../api/client.js';
import { fetchDocumentBlob } from '../../utils/download.js';

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

  describe('header', () => {
    it('shows code, title, customer / project / contact and the real stage with its S-number from the catalog', async () => {
      renderPage();
      expect(await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ })).not.toBeNull();
      expect(screen.getByText(/ปูกระเบื้องคอนโดสุขุมวิท/)).not.toBeNull();
      expect(screen.getByText(/บริษัท สุขุมวิท พร็อพเพอร์ตี้ จำกัด/)).not.toBeNull();
      expect(screen.getByText(/คอนโด สุขุมวิท 31/)).not.toBeNull();
      expect(screen.getByText(/คุณสมชาย ใจดี/)).not.toBeNull();
      // DELIVERY_SCHEDULING is S18 in the catalog — never hardcoded on the page.
      expect(await screen.findByText(/S18/)).not.toBeNull();
      expect(screen.getByText(/นัดส่งสินค้า/)).not.toBeNull();
    });

    it('links back to the worklist', async () => {
      renderPage();
      const back = await screen.findByRole('link', { name: /งานการเงิน/ });
      expect(back.getAttribute('href')).toBe('/finance');
    });
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

    it('the caption follows the server basis (EXCLUDING_VAT reads ไม่รวม VAT) rather than being hardcoded', async () => {
      const deal = makeFinanceDeal();
      deal.money.amountVatBasis = 'EXCLUDING_VAT';
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      const band = await screen.findByRole('group', { name: 'สรุปยอดเงิน' });
      expect(within(band).getByText('ไม่รวม VAT')).not.toBeNull();
      expect(within(band).queryByText('รวม VAT')).toBeNull();
    });

    it('null amounts render an em dash, never a fabricated zero', async () => {
      const deal = makeFinanceDeal();
      deal.money.amountPaid = null;
      deal.money.amountOutstanding = null;
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      const band = await screen.findByRole('group', { name: 'สรุปยอดเงิน' });
      expect(within(band).getAllByText('—').length).toBeGreaterThanOrEqual(2);
      expect(within(band).queryByText('฿0.00')).toBeNull();
    });

    it('an overdue deal says เกินกำหนดชำระ in text with an icon (not colour alone)', async () => {
      const deal = makeFinanceDeal();
      deal.money.overdue = true;
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      const flag = await screen.findByText('เกินกำหนดชำระ');
      expect(flag.parentElement.querySelector('svg')).not.toBeNull();
    });

    it('a deal that is not overdue does not show the overdue flag; the due date is shown', async () => {
      renderPage();
      await screen.findByRole('group', { name: 'สรุปยอดเงิน' });
      expect(screen.queryByText('เกินกำหนดชำระ')).toBeNull();
      expect(screen.getByText(/ครบกำหนด/)).not.toBeNull();
    });

    it('shows the derived due date with its basis: credit N days from delivery', async () => {
      renderPage();
      const band = await screen.findByRole('group', { name: 'สรุปยอดเงิน' });
      expect(within(band).getByText('ครบกำหนด 15 ต.ค. 2569 · เครดิต 30 วันนับจากวันส่งมอบ')).not.toBeNull();
    });

    it('shows ชำระเมื่อส่งมอบ for on-delivery terms', async () => {
      const deal = makeFinanceDeal();
      Object.assign(deal.money, { paymentDueBasis: 'ON_DELIVERY', paymentDueCreditDays: null });
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      const band = await screen.findByRole('group', { name: 'สรุปยอดเงิน' });
      expect(within(band).getByText('ครบกำหนด 15 ต.ค. 2569 · ชำระเมื่อส่งมอบ')).not.toBeNull();
    });

    it('with structured terms but no delivery yet it says ยังไม่ถึงกำหนด — รอส่งมอบ (no date invented)', async () => {
      const deal = makeFinanceDeal();
      Object.assign(deal.money, { paymentDueDate: null });
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      const band = await screen.findByRole('group', { name: 'สรุปยอดเงิน' });
      expect(within(band).getByText('ยังไม่ถึงกำหนด — รอส่งมอบ')).not.toBeNull();
      expect(within(band).queryByText(/ครบกำหนด/)).toBeNull();
    });

    it('with no structured terms nothing about a due date is shown', async () => {
      const deal = makeFinanceDeal();
      Object.assign(deal.money, { paymentDueDate: null, paymentDueBasis: null, paymentDueCreditDays: null });
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      const band = await screen.findByRole('group', { name: 'สรุปยอดเงิน' });
      expect(within(band).queryByText(/ครบกำหนด/)).toBeNull();
      expect(within(band).queryByText(/รอส่งมอบ/)).toBeNull();
    });

    it('shows the close-confirmed state when closeConfirmedAt is set', async () => {
      const deal = makeFinanceDeal();
      deal.money.closeConfirmedAt = '2026-10-20T03:00:00Z';
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      expect(await screen.findByText(/ยืนยันพร้อมปิดงานแล้ว/)).not.toBeNull();
    });
  });

  describe('milestone spine', () => {
    it('renders the five milestone sections in order with "<index> <label>" titles', async () => {
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent.replace(/\s+/g, ' ').trim());
      const milestoneHeadings = headings.filter((t) => /^[1-5] /.test(t));
      expect(milestoneHeadings).toEqual([
        '1 ได้รับคำสั่งซื้อ',
        '2 ได้รับมัดจำ',
        '3 รอสินค้า / นำเข้า',
        '4 ส่งมอบ — รอชำระส่วนที่เหลือ',
        '5 ชำระครบ ปิดงาน',
      ]);
    });

    it('each panel states its status as text: เสร็จแล้ว / ขั้นปัจจุบัน / ข้าม / ยังไม่ถึง', async () => {
      const deal = makeFinanceDeal({ milestoneTrack: makeTrack(4, { skipDeposit: true }) });
      deal.money.depositPolicy = 'WAIVED';
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(within(section(/^1 ได้รับคำสั่งซื้อ/)).getByText('เสร็จแล้ว')).not.toBeNull();
      expect(within(section(/^2 ได้รับมัดจำ/)).getByText('ข้าม')).not.toBeNull();
      expect(within(section(/^3 รอสินค้า/)).getByText('เสร็จแล้ว')).not.toBeNull();
      expect(within(section(/^4 ส่งมอบ/)).getByText('ขั้นปัจจุบัน')).not.toBeNull();
      expect(within(section(/^5 ชำระครบ/)).getByText('ยังไม่ถึง')).not.toBeNull();
    });

    it('the separate horizontal milestone track is gone (the panels are the only milestone display)', async () => {
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(screen.queryByRole('list', { name: 'ความคืบหน้าการเงินของดีล' })).toBeNull();
    });

    it('document rows in different panels share one row layout (same grid template) so amounts and buttons line up', async () => {
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      const rows = [...document.querySelectorAll('[data-doc-row]')];
      expect(rows.length).toBeGreaterThanOrEqual(4);
      const templates = new Set(rows.map((r) => r.getAttribute('data-doc-row')));
      expect(templates.size).toBe(1);
    });

    it('marks exactly the server-current milestone as current and collapses upcoming ones to a muted line', async () => {
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(section(/^4 ส่งมอบ/).getAttribute('aria-current')).toBe('step');
      expect(section(/^4 ส่งมอบ/).getAttribute('data-state')).toBe('current');
      expect(section(/^3 รอสินค้า/).getAttribute('aria-current')).toBeNull();
      // milestone 5 is upcoming and holds no data: collapsed to the one-liner
      // upcoming with no data: header row only — the status text says it, there is no body at all
      expect(section(/^5 ชำระครบ/).getAttribute('data-state')).toBe('upcoming');
      expect(within(section(/^5 ชำระครบ/)).getByText('ยังไม่ถึง')).not.toBeNull();
      expect(section(/^5 ชำระครบ/).querySelector('[data-panel-body]')).toBeNull();
      // the current milestone is expanded (its documents are visible)
      expect(within(section(/^4 ส่งมอบ/)).getByText(/RI-2026-0012/)).not.toBeNull();
    });

    it('a skipped deposit milestone collapses to one muted line naming the policy', async () => {
      const deal = makeFinanceDeal({ milestoneTrack: makeTrack(4, { skipDeposit: true }) });
      deal.money.depositPolicy = 'WAIVED';
      deal.documents.depositNotices = [];
      deal.money.payments = [];
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(within(section(/^2 ได้รับมัดจำ/)).getByText('ดีลนี้ไม่ต้องเก็บมัดจำ')).not.toBeNull();
    });

    it('past milestones stay visible with their content, and an upcoming milestone that already holds data shows it', async () => {
      const deal = makeFinanceDeal({ milestoneTrack: makeTrack(2) });
      // milestone 4 is upcoming, but a remaining invoice already exists: data wins over the collapse rule
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(within(section(/^4 ส่งมอบ/)).getByText(/RI-2026-0012/)).not.toBeNull();
      expect(within(section(/^1 ได้รับคำสั่งซื้อ/)).getByText(/QT-2026-0031/)).not.toBeNull();
    });

    it('milestone 1: accepted quotation with its own VAT caption, items table with dashes for null prices, PO file', async () => {
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      const s1 = section(/^1 ได้รับคำสั่งซื้อ/);
      expect(within(s1).getByText(/QT-2026-0031/)).not.toBeNull();
      expect(within(s1).getByText('฿120,000.00')).not.toBeNull();
      expect(within(s1).getByText('PADANA 60x60 ผิวเงา')).not.toBeNull();
      expect(within(s1).getByText('฿850.00')).not.toBeNull();
      expect(within(s1).getByText('฿102,000.00')).not.toBeNull();
      // the null-priced line shows dashes rather than a fabricated number
      const row = within(s1).getByText('PADANA 30x60 ผิวด้าน').closest('tr, [role="row"], li');
      expect(within(row).getAllByText('—').length).toBe(2);
      expect(within(s1).getByText('PO-สุขุมวิท.pdf')).not.toBeNull();
    });

    it('milestone 2: deposit notice (v#, VAT caption) and DEPOSIT payments only', async () => {
      const deal = makeFinanceDeal();
      deal.money.payments.push({ id: 2, kind: 'BALANCE', amount: 5000, currency: 'THB', receivedAt: '2026-10-02T03:00:00Z', receiptRef: 'RC-BAL', note: null, depositNoticeId: null, recordedByName: null });
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      const s2 = section(/^2 ได้รับมัดจำ/);
      expect(within(s2).getByText(/DN-2026-0007/)).not.toBeNull();
      expect(within(s2).getByText(/v2/)).not.toBeNull();
      expect(within(s2).getByText('฿42,800.00')).not.toBeNull();
      expect(within(s2).getByText('รวม VAT')).not.toBeNull();
      expect(within(s2).getByText(/RC-001/)).not.toBeNull();
      expect(within(s2).queryByText(/RC-BAL/)).toBeNull();
      // the BALANCE payment belongs to milestone 4
      expect(within(section(/^4 ส่งมอบ/)).getByText(/RC-BAL/)).not.toBeNull();
    });

    it('milestone 3: read-only fulfilment status in Thai, no money', async () => {
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      const s3 = section(/^3 รอสินค้า/);
      expect(within(s3).getByText('สินค้าถึงโกดังแล้ว')).not.toBeNull();
      expect(within(s3).queryByText(/฿/)).toBeNull();
    });

    it('milestone 5: tax invoice files, invoice-on-file and commission step status', async () => {
      const deal = makeFinanceDeal({ salesStage: 'CLOSED_PAID', milestoneTrack: makeTrack(5) });
      deal.documents.taxInvoices = [{ id: 71, fileName: 'TAX-0071.pdf', attachType: 'INVOICE', uploadedAt: null, fileSize: null, downloadPath: '/downloads/attachment-71' }];
      deal.money.invoiceOnFile = true;
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      const s5 = section(/^5 ชำระครบ/);
      expect(within(s5).getByText('TAX-0071.pdf')).not.toBeNull();
      expect(within(s5).getByText(/ใบกำกับภาษี.*(มีแล้ว|อยู่ในระบบ)/)).not.toBeNull();
      expect(within(s5).getByText(/ค่าคอมมิชชัน.*ยังไม่ได้บันทึก/)).not.toBeNull();
    });

    it('a document download goes through the session-cookie fetch helper with the DTO path (no bare href)', async () => {
      fetchDocumentBlob.mockResolvedValue(new Blob(['x'], { type: 'application/pdf' }));
      URL.createObjectURL = vi.fn(() => 'blob:x');
      URL.revokeObjectURL = vi.fn();
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      const s1 = section(/^1 ได้รับคำสั่งซื้อ/);
      fireEvent.click(within(s1).getByRole('button', { name: /ดาวน์โหลด.*QT-2026-0031/ }));
      await waitFor(() => expect(fetchDocumentBlob).toHaveBeenCalledWith('/downloads/quotation-31.pdf'));
    });
  });

  describe('primary action and overflow', () => {
    it('the primary button is the highest-priority available action; the rest sit in the overflow menu', async () => {
      const deal = makeFinanceDeal({
        availableActions: [
          action('RECORD_PAYMENT', 'บันทึกรับชำระเงิน'),
          action('FINAL_PAYMENT', 'รับเงินครบ'),
        ],
      });
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      const primary = await screen.findByRole('button', { name: 'รับชำระส่วนที่เหลือ' });
      expect(primary).not.toBeNull();
      expect(screen.queryByRole('button', { name: 'บันทึกรับชำระ' })).toBeNull();
      fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      expect(screen.getByRole('menuitem', { name: 'บันทึกรับชำระ' })).not.toBeNull();
    });

    it('no available actions -> no action button and no disabled fake', async () => {
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(screen.queryByRole('button', { name: 'การดำเนินการเพิ่มเติม' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'รับชำระส่วนที่เหลือ' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'บันทึกรับชำระ' })).toBeNull();
    });

    it('CLOSED_PAID without a recorded commission offers the link to the commission flow', async () => {
      const deal = makeFinanceDeal({ salesStage: 'CLOSED_PAID', milestoneTrack: makeTrack(5) });
      api.finance.getDeal.mockResolvedValue({ deal });
      renderPage();
      const link = await screen.findByRole('link', { name: 'บันทึกใบกำกับ + ออกค่าคอม' });
      expect(link.getAttribute('href')).toBe('/commissions?ticketId=501');
    });

    it('no commission link once the commission is recorded, or before CLOSED_PAID', async () => {
      const closed = makeFinanceDeal({ salesStage: 'CLOSED_PAID', milestoneTrack: makeTrack(5) });
      closed.money.commissionRecorded = true;
      api.finance.getDeal.mockResolvedValue({ deal: closed });
      const first = renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(screen.queryByRole('link', { name: 'บันทึกใบกำกับ + ออกค่าคอม' })).toBeNull();
      first.unmount();
      api.finance.getDeal.mockResolvedValue({ deal: makeFinanceDeal() });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(screen.queryByRole('link', { name: 'บันทึกใบกำกับ + ออกค่าคอม' })).toBeNull();
    });
  });

  describe('whose move it is', () => {
    const depositDeal = (withAction) => {
      const d = makeFinanceDeal({
        milestoneTrack: makeTrack(1),
        availableActions: withAction ? [action('DEPOSIT_PAID', 'รับมัดจำ')] : [],
      });
      d.documents.depositNotices = [{ id: 7, docNumber: 'DN-2026-0007', version: 1, status: 'ISSUED', issueDate: '2026-08-26', depositAmount: 40000, totalPayable: 42800, totalPayableVatBasis: 'INCLUDING_VAT', downloadPath: '/downloads/dn7' }];
      d.money.payments = [];
      return d;
    };

    it('a deposit notice awaiting DEPOSIT_PAID carries the text status รอยืนยันรับมัดจำ', async () => {
      api.finance.getDeal.mockResolvedValue({ deal: depositDeal(true) });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(within(section(/^2 ได้รับมัดจำ/)).getByText('รอยืนยันรับมัดจำ')).not.toBeNull();
    });

    it('without that action the badge is not shown', async () => {
      api.finance.getDeal.mockResolvedValue({ deal: depositDeal(false) });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(screen.queryByText('รอยืนยันรับมัดจำ')).toBeNull();
    });

    it('the section holding the action subject is not drawn muted, and keeps the server current marker on milestone 1', async () => {
      api.finance.getDeal.mockResolvedValue({ deal: depositDeal(true) });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(section(/^2 ได้รับมัดจำ/).getAttribute('data-emphasis')).toBe('true');
      expect(section(/^2 ได้รับมัดจำ/).getAttribute('aria-current')).toBeNull();
      // status reads as pending action (warning), never the contradictory "ยังไม่ถึง"; the real current panel keeps its word
      expect(within(section(/^2 ได้รับมัดจำ/)).getByText('รอดำเนินการ')).not.toBeNull();
      expect(within(section(/^2 ได้รับมัดจำ/)).queryByText('ยังไม่ถึง')).toBeNull();
      expect(within(section(/^1 ได้รับคำสั่งซื้อ/)).getByText('ขั้นปัจจุบัน')).not.toBeNull();
      expect(section(/^1 ได้รับคำสั่งซื้อ/).getAttribute('aria-current')).toBe('step');
    });

    it('the same section IS muted when no action refers to it (upcoming)', async () => {
      api.finance.getDeal.mockResolvedValue({ deal: depositDeal(false) });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(section(/^2 ได้รับมัดจำ/).getAttribute('data-emphasis')).toBe('false');
    });

    it('with a SUPERSEDED v1 and an ISSUED v2 and DEPOSIT_PAID available, exactly one รอยืนยันรับมัดจำ badge shows, on v2', async () => {
      const d = depositDeal(true);
      d.documents.depositNotices = [
        { id: 6, docNumber: 'DN-2026-0007', version: 1, status: 'SUPERSEDED', issueDate: '2026-08-20', depositAmount: 30000, totalPayable: 32100, totalPayableVatBasis: 'INCLUDING_VAT', downloadPath: '/downloads/dn6' },
        { id: 7, docNumber: 'DN-2026-0007', version: 2, status: 'ISSUED', issueDate: '2026-08-26', depositAmount: 40000, totalPayable: 42800, totalPayableVatBasis: 'INCLUDING_VAT', downloadPath: '/downloads/dn7' },
      ];
      api.finance.getDeal.mockResolvedValue({ deal: d });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      const s2 = section(/^2 ได้รับมัดจำ/);
      const badges = within(s2).getAllByText('รอยืนยันรับมัดจำ');
      expect(badges).toHaveLength(1);
      const row = badges[0].closest('li');
      expect(within(row).getByText('v2')).not.toBeNull();
      expect(within(row).queryByText('v1')).toBeNull();
    });

    it('an ISSUED remaining invoice with no FINAL_PAYMENT action carries no รอรับชำระ badge', async () => {
      api.finance.getDeal.mockResolvedValue({ deal: makeFinanceDeal({ availableActions: [] }) });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(screen.queryByText('รอรับชำระ')).toBeNull();
    });

    it('RECORD_PAYMENT alone does not un-mute the deposit section (it names no specific document)', async () => {
      const d = depositDeal(false);
      d.availableActions = [action('RECORD_PAYMENT', 'บันทึกรับชำระเงิน')];
      api.finance.getDeal.mockResolvedValue({ deal: d });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(section(/^2 ได้รับมัดจำ/).getAttribute('data-emphasis')).toBe('false');
      expect(screen.queryByText('รอยืนยันรับมัดจำ')).toBeNull();
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
      fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกรับชำระ' }));
      expect(await within(dialog).findByRole('alert')).not.toBeNull();
      expect(api.finance.recordPayment).not.toHaveBeenCalled();
    });

    it('a server error stays inside the modal (message from the ApiError), and the modal stays open', async () => {
      api.finance.getDeal.mockResolvedValue({ deal: makeFinanceDeal({ availableActions: [action('RECORD_PAYMENT', 'บันทึกรับชำระเงิน')] }) });
      api.finance.recordPayment.mockRejectedValue(new ApiError('ยอดรับชำระเกินยอดที่ต้องชำระ', 409));
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: 'บันทึกรับชำระ' }));
      const dialog = await screen.findByRole('dialog');
      fireEvent.change(within(dialog).getByLabelText('จำนวนเงิน'), { target: { value: '999999' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกรับชำระ' }));
      const alert = await within(dialog).findByRole('alert');
      expect(alert.textContent).toContain('ยอดรับชำระเกินยอดที่ต้องชำระ');
      expect(screen.getByRole('dialog')).not.toBeNull();
    });

    it('double-submit is prevented: the button is busy while the request is pending', async () => {
      api.finance.getDeal.mockResolvedValue({ deal: makeFinanceDeal({ availableActions: [action('RECORD_PAYMENT', 'บันทึกรับชำระเงิน')] }) });
      let resolve;
      api.finance.recordPayment.mockReturnValue(new Promise((r) => { resolve = r; }));
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: 'บันทึกรับชำระ' }));
      const dialog = await screen.findByRole('dialog');
      fireEvent.change(within(dialog).getByLabelText('จำนวนเงิน'), { target: { value: '100' } });
      const submit = within(dialog).getByRole('button', { name: 'บันทึกรับชำระ' });
      fireEvent.click(submit);
      await waitFor(() => expect(submit.disabled).toBe(true));
      fireEvent.click(submit);
      expect(api.finance.recordPayment).toHaveBeenCalledTimes(1);
      resolve({ deal: makeFinanceDeal() });
    });

    it('ตั้งค่าการวางบิล is gone: no billing action is offered and no billing form exists on the page', async () => {
      // even if a stale server still listed SET_BILLING, the page has no billing flow to open
      api.finance.getDeal.mockResolvedValue({ deal: makeFinanceDeal({ availableActions: [action('SET_BILLING', 'ตั้งค่าการวางบิล')] }) });
      renderPage();
      await screen.findByRole('heading', { level: 1, name: /PR-2026-0501/ });
      expect(screen.queryByRole('button', { name: 'ตั้งค่าการวางบิล' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'การดำเนินการเพิ่มเติม' })).toBeNull();
      expect(screen.queryByRole('dialog')).toBeNull();
      expect(api.finance.setBilling).toBeUndefined();
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

    it('payment kind defaults to DEPOSIT when nothing has been paid on a deposit deal', async () => {
      const dialog = await openPaymentDialog(payDeal({}, { amountPaid: 0 }));
      expect(within(dialog).getByLabelText('ประเภท').value).toBe('DEPOSIT');
    });

    it('payment kind defaults to BALANCE once something has been paid', async () => {
      const dialog = await openPaymentDialog(payDeal({ milestoneTrack: makeTrack(2) }, { amountPaid: 500 }));
      expect(within(dialog).getByLabelText('ประเภท').value).toBe('BALANCE');
    });

    it('payment kind defaults to BALANCE for a skipped-deposit deal (CREDIT_CUSTOMER at S12-S17) with nothing paid', async () => {
      const dialog = await openPaymentDialog(payDeal(
        { salesStage: 'PROCUREMENT', milestoneTrack: makeTrack(3, { skipDeposit: true }) },
        { amountPaid: 0, depositPolicy: 'CREDIT_CUSTOMER' },
      ));
      expect(within(dialog).getByLabelText('ประเภท').value).toBe('BALANCE');
    });

    it('received date is empty by default, says blank means now, and a blank date sends receivedAt null', async () => {
      const dialog = await openPaymentDialog(payDeal());
      const date = within(dialog).getByLabelText(/วันที่รับเงิน/);
      expect(date.value).toBe('');
      expect(within(dialog).getByText(/เว้นว่าง.*ตอนบันทึก/)).not.toBeNull();
      api.finance.recordPayment.mockResolvedValue({ deal: makeFinanceDeal() });
      fireEvent.change(within(dialog).getByLabelText('จำนวนเงิน'), { target: { value: '100' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกรับชำระ' }));
      await waitFor(() => expect(api.finance.recordPayment).toHaveBeenCalledTimes(1));
      expect(api.finance.recordPayment.mock.calls[0][1].receivedAt).toBeNull();
    });

    it('a picked received date is sent serialised exactly like TicketDetailPage (new Date(value).toISOString())', async () => {
      const dialog = await openPaymentDialog(payDeal());
      api.finance.recordPayment.mockResolvedValue({ deal: makeFinanceDeal() });
      fireEvent.change(within(dialog).getByLabelText(/วันที่รับเงิน/), { target: { value: '2026-09-20' } });
      fireEvent.change(within(dialog).getByLabelText('จำนวนเงิน'), { target: { value: '100' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกรับชำระ' }));
      await waitFor(() => expect(api.finance.recordPayment).toHaveBeenCalledTimes(1));
      expect(api.finance.recordPayment.mock.calls[0][1].receivedAt).toBe(new Date('2026-09-20').toISOString());
    });

    it('REVOKE_CLOSE_CONFIRM sends the optional note to api.finance.revokeCloseConfirmation', async () => {
      const deal = makeFinanceDeal({ availableActions: [action('REVOKE_CLOSE_CONFIRM', 'ยกเลิกการยืนยันปิดงาน')] });
      api.finance.getDeal.mockResolvedValue({ deal });
      api.finance.revokeCloseConfirmation.mockResolvedValue({ deal: makeFinanceDeal() });
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      fireEvent.click(screen.getByRole('menuitem', { name: 'ยกเลิกการยืนยันปิดงาน' }));
      const dialog = await screen.findByRole('dialog');
      fireEvent.change(within(dialog).getByLabelText(/หมายเหตุ/), { target: { value: 'ยอดไม่ตรง' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิกการยืนยันปิดงาน' }));
      await waitFor(() => expect(api.finance.revokeCloseConfirmation).toHaveBeenCalledWith(501, { note: 'ยอดไม่ตรง' }));
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

  describe('comments', () => {
    it('lists comments oldest first with author and message; posting appends the new comment from the returned deal', async () => {
      const deal = makeFinanceDeal();
      deal.comments = [
        { id: 1, authorName: 'สมหญิง ขายดี', createdAt: '2026-09-05T03:00:00Z', message: 'ลูกค้าขอชำระเป็นงวด' },
        { id: 2, authorName: 'บัญชี ทดสอบ', createdAt: '2026-09-06T03:00:00Z', message: 'รับทราบ' },
      ];
      const after = { ...deal, comments: [...deal.comments, { id: 3, authorName: 'บัญชี ทดสอบ', createdAt: '2026-09-07T03:00:00Z', message: 'แจ้งเลขที่โอนแล้ว' }] };
      api.finance.getDeal.mockResolvedValue({ deal });
      api.finance.addComment.mockResolvedValue({ deal: after });
      renderPage();
      const region = await screen.findByRole('region', { name: 'บันทึกถึงฝ่ายขาย' });
      const items = within(region).getAllByRole('listitem').map((li) => li.textContent);
      expect(items[0]).toContain('ลูกค้าขอชำระเป็นงวด');
      expect(items[1]).toContain('รับทราบ');
      fireEvent.change(within(region).getByLabelText('ข้อความถึงฝ่ายขาย'), { target: { value: 'แจ้งเลขที่โอนแล้ว' } });
      fireEvent.click(within(region).getByRole('button', { name: 'ส่งบันทึก' }));
      await waitFor(() => expect(api.finance.addComment).toHaveBeenCalledWith(501, { message: 'แจ้งเลขที่โอนแล้ว' }));
      expect(await within(region).findByText('แจ้งเลขที่โอนแล้ว')).not.toBeNull();
    });

    it('an empty message is not sent, and a comment error is shown inline', async () => {
      api.finance.addComment.mockRejectedValue(new ApiError('ส่งบันทึกไม่สำเร็จ', 500));
      renderPage();
      const region = await screen.findByRole('region', { name: 'บันทึกถึงฝ่ายขาย' });
      const send = within(region).getByRole('button', { name: 'ส่งบันทึก' });
      expect(send.disabled).toBe(true);
      fireEvent.change(within(region).getByLabelText('ข้อความถึงฝ่ายขาย'), { target: { value: 'ทดสอบ' } });
      fireEvent.click(within(region).getByRole('button', { name: 'ส่งบันทึก' }));
      expect((await within(region).findByRole('alert')).textContent).toContain('ส่งบันทึกไม่สำเร็จ');
    });
  });

  describe('load states', () => {
    it('shows a layout-shaped skeleton while loading', async () => {
      api.finance.getDeal.mockReturnValue(new Promise(() => {}));
      renderPage();
      expect(await screen.findByLabelText('กำลังโหลดดีล')).not.toBeNull();
    });

    it('403 shows access-denied with a link back to /finance', async () => {
      api.finance.getDeal.mockRejectedValue(new ApiError('ไม่มีสิทธิ์เข้าถึงรายการนี้', 403));
      renderPage();
      expect(await screen.findByText(/ไม่มีสิทธิ์เข้าถึงดีลนี้/)).not.toBeNull();
      expect(screen.getByRole('link', { name: /กลับไปงานการเงิน/ }).getAttribute('href')).toBe('/finance');
    });

    it('404 shows not-found with a link back to /finance', async () => {
      api.finance.getDeal.mockRejectedValue(new ApiError('ไม่พบดีลนี้', 404));
      renderPage();
      expect(await screen.findByText(/ไม่พบดีลนี้/)).not.toBeNull();
      expect(screen.getByRole('link', { name: /กลับไปงานการเงิน/ }).getAttribute('href')).toBe('/finance');
    });
  });
});
