import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ExpressUploadCard, ReceiptsImportPage } from './ReceiptsImportPage.jsx';
import { ReceiptSourceChip } from './ReceiptSourceChip.jsx';
import { UnmatchedReceipts, UnmatchedReceiptsSummary } from '../salesBoard/UnmatchedReceipts.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      receiptImports: {
        upload: vi.fn(), latest: vi.fn(), template: vi.fn(), unmatched: vi.fn(), ignored: vi.fn(),
        managerView: vi.fn(), deals: vi.fn(), resolve: vi.fn(), ignore: vi.fn(), unignore: vi.fn(),
        flagManager: vi.fn(), sameAsManual: vi.fn(),
      },
    },
  };
});
vi.mock('../../utils/download.js', () => ({ downloadBlob: vi.fn() }));

const summary = {
  importId: 4, fileName: 'RE-พ.ย.-สัปดาห์2.xlsx', uploadedAt: '2026-11-13T02:12:00Z', uploadedByName: 'คุณบุษบา',
  totalRows: 46, matched: 37, matchedAmount: 2841500, possibleManualDuplicates: 1, duplicates: 3, duplicateAmount: 212400,
  unmatched: 2, unmatchedAmount: 418000, nonCash: 1, invalid: 1, failed: 1,
  errors: [{ line: 9, message: 'วันที่ไม่ถูกต้อง (RE-6911-0009)' }],
};

const queued = (over) => ({
  rowId: 11, importId: 4, docNo: 'RE-6911-0057', docDate: '2026-11-12', customerName: 'บจก. ลุมพินี เรสซิเดนซ์',
  amount: 372000, reference: 'มัดจำ บ้านสวน', reason: 'AMBIGUOUS', status: 'UNMATCHED', resolvedTicketId: null,
  managerFlaggedAt: null,
  candidates: [
    { ticketId: 31, title: 'โครงการบ้านสวนลุมพินี', customerName: 'ลุมพินี', ownerId: 6, ownerName: 'คุณสมหมาย',
      hint: 'ลูกค้า + ยอดตรง', expectedAmount: 372000, expectedWeek: '2026-11-16', expectedKind: 'DEPOSIT' },
    { ticketId: 27, title: 'ลุมพินี ทาวเวอร์ ชั้น 12', customerName: 'ลุมพินี', ownerId: 7, ownerName: 'คุณวิภา',
      hint: 'ลูกค้าตรง', expectedAmount: null, expectedWeek: null, expectedKind: null },
  ],
  ...over,
});

function wrap(ui) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>);
}

describe('ReceiptsImportPage — อัปโหลดไฟล์รับเงินจาก Express (account)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.receiptImports.latest.mockResolvedValue({ uploadEnabled: true, latest: { ...summary, errors: [] }, openUnmatched: 2 });
    api.receiptImports.unmatched.mockResolvedValue([queued()]);
    api.receiptImports.ignored.mockResolvedValue([]);
    api.receiptImports.upload.mockResolvedValue(summary);
    api.receiptImports.template.mockResolvedValue(new Blob(['x']));
  });

  it('shows the last upload before anything is chosen', async () => {
    wrap(<ReceiptsImportPage showToast={vi.fn()} />);
    expect(await screen.findByText(/RE-พ.ย.-สัปดาห์2.xlsx · 46 รายการ · คุณบุษบา/)).toBeTruthy();
  });

  it('reads the outcome back, including linked-to-manual, non-cash and failed rows', async () => {
    wrap(<ReceiptsImportPage showToast={vi.fn()} />);
    const file = new File(['a'], 'week2.csv', { type: 'text/csv' });
    fireEvent.change(await screen.findByLabelText('เลือกไฟล์'), { target: { files: [file] } });
    fireEvent.click(screen.getByRole('button', { name: 'อัปโหลดไฟล์' }));

    await waitFor(() => expect(api.receiptImports.upload).toHaveBeenCalledWith(file));
    const table = await screen.findByRole('table', { name: 'ผลการอัปโหลด' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((r) => within(r).getAllByRole('cell')[0].textContent)).toEqual([
      'จับคู่แล้ว', 'อาจซ้ำกับรายการที่บันทึกเอง', 'ซ้ำ (เคยนำเข้าแล้ว)', 'ยังจับคู่ไม่ได้', 'ไม่ใช่ใบเสร็จรับเงิน',
      'อ่านไม่ได้', 'บันทึกไม่สำเร็จ',
    ]);
    expect(within(rows[0]).getByText('2,841,500.00')).toBeTruthy();
    expect(screen.getByText('บรรทัด 9: วันที่ไม่ถูกต้อง (RE-6911-0009)')).toBeTruthy();
  });

  it('offers the template download and says what the matching order is', async () => {
    wrap(<ReceiptsImportPage showToast={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'ดาวน์โหลดแม่แบบ' }));
    await waitFor(() => expect(api.receiptImports.template).toHaveBeenCalled());
    expect(screen.getByText('จับคู่จากเลขใบแจ้งหนี้ก่อน แล้วเลขใบเสนอราคา แล้วลูกค้า + ยอดเงิน')).toBeTruthy();
  });

  it('while the owner has not switched the upload on, there is no upload at all', async () => {
    api.receiptImports.latest.mockResolvedValue({ uploadEnabled: false, latest: null, openUnmatched: 0 });
    wrap(<ReceiptsImportPage showToast={vi.fn()} />);
    expect(await screen.findByText('ยังไม่เปิดใช้การอัปโหลดไฟล์ Express')).toBeTruthy();
    expect(screen.queryByLabelText('เลือกไฟล์')).toBeNull();
    expect(screen.queryByRole('button', { name: 'อัปโหลดไฟล์' })).toBeNull();
  });
});

describe('UnmatchedReceipts — account works the queue', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.receiptImports.unmatched.mockResolvedValue([queued()]);
    api.receiptImports.ignored.mockResolvedValue([queued({ rowId: 12, docNo: 'RE-IGN-1', status: 'IGNORED', candidates: [] })]);
    api.receiptImports.resolve.mockResolvedValue(queued({ status: 'RESOLVED', resolvedTicketId: 31 }));
    api.receiptImports.ignore.mockResolvedValue(queued({ status: 'IGNORED' }));
    api.receiptImports.unignore.mockResolvedValue(queued({ rowId: 12, status: 'UNMATCHED' }));
    api.receiptImports.flagManager.mockResolvedValue(queued({ managerFlaggedAt: '2026-11-13T03:00:00Z' }));
    api.receiptImports.deals.mockResolvedValue([
      { ticketId: 40, code: 'PR-2026-0040', title: 'ดีลที่ค้นเจอ', customerName: 'ลูกค้าอื่น', ownerName: 'คุณกมล' },
      { ticketId: 41, code: 'PR-2026-0041', title: 'ดีลที่สอง', customerName: 'ลูกค้าอื่น', ownerName: 'คุณกมล' },
    ]);
  });

  it('matches a candidate in one tap', async () => {
    wrap(<UnmatchedReceipts showToast={vi.fn()} />);
    const row = await screen.findByTestId('unmatched-11');
    expect(within(row).getByText('ลูกค้า + ยอดตรง')).toBeTruthy();
    fireEvent.click(within(row).getAllByRole('button', { name: /^จับคู่กับดีลนี้/ })[0]);
    await waitFor(() => expect(api.receiptImports.resolve).toHaveBeenCalledWith(11, { ticketId: 31 }));
  });

  it('picks any deal in scope by search when no candidate fits', async () => {
    api.receiptImports.unmatched.mockResolvedValue([queued({ candidates: [], reason: 'NO_CANDIDATE' })]);
    wrap(<UnmatchedReceipts showToast={vi.fn()} />);
    const row = await screen.findByTestId('unmatched-11');
    fireEvent.click(within(row).getByRole('button', { name: 'เลือกดีลเอง' }));
    fireEvent.change(within(row).getByLabelText('ค้นหาดีล'), { target: { value: 'ค้นเจอ' } });
    fireEvent.click(within(row).getByRole('button', { name: 'ค้นหา' }));
    await waitFor(() => expect(api.receiptImports.deals).toHaveBeenCalledWith('ค้นเจอ'));
    fireEvent.click(await within(row).findByRole('button', { name: 'จับคู่กับดีลนี้ ดีลที่ค้นเจอ' }));
    await waitFor(() => expect(api.receiptImports.resolve).toHaveBeenCalledWith(11, { ticketId: 40 }));
  });

  it('splits one row across deals; the split is sent only when the lines sum to the row', async () => {
    api.receiptImports.unmatched.mockResolvedValue([queued({ amount: 3000, candidates: [], reason: 'NO_CANDIDATE' })]);
    wrap(<UnmatchedReceipts showToast={vi.fn()} />);
    const row = await screen.findByTestId('unmatched-11');
    fireEvent.click(within(row).getByRole('button', { name: 'เลือกดีลเอง' }));
    fireEvent.change(within(row).getByLabelText('ค้นหาดีล'), { target: { value: 'ดีล' } });
    fireEvent.click(within(row).getByRole('button', { name: 'ค้นหา' }));
    fireEvent.click(await within(row).findByRole('button', { name: 'แบ่งยอด ดีลที่ค้นเจอ' }));
    fireEvent.click(within(row).getByRole('button', { name: 'แบ่งยอด ดีลที่สอง' }));
    fireEvent.change(within(row).getByLabelText('ยอดของ ดีลที่ค้นเจอ'), { target: { value: '1000' } });
    fireEvent.change(within(row).getByLabelText('ยอดของ ดีลที่สอง'), { target: { value: '1999' } });
    const send = within(row).getByRole('button', { name: 'บันทึกการแบ่งยอด' });
    expect(send.disabled).toBe(true);
    expect(within(row).getByText('เหลืออีก ฿1')).toBeTruthy();
    fireEvent.change(within(row).getByLabelText('ยอดของ ดีลที่สอง'), { target: { value: '2000' } });
    fireEvent.click(send);
    await waitFor(() => expect(api.receiptImports.resolve).toHaveBeenCalledWith(11, {
      allocations: [{ ticketId: 40, amount: 1000 }, { ticketId: 41, amount: 2000 }],
    }));
  });

  it('sends a row to the sales manager to look at, and sets one aside', async () => {
    wrap(<UnmatchedReceipts showToast={vi.fn()} />);
    const row = await screen.findByTestId('unmatched-11');
    fireEvent.click(within(row).getByRole('button', { name: 'ส่งให้ผู้จัดการขายดู' }));
    await waitFor(() => expect(api.receiptImports.flagManager).toHaveBeenCalledWith(11));
    fireEvent.click(within(row).getByRole('button', { name: /^ข้าม/ }));
    await waitFor(() => expect(api.receiptImports.ignore).toHaveBeenCalledWith(11));
  });

  it('a possible duplicate of a hand-typed receipt: account says it is the same one, or a different payment', async () => {
    const possible = queued({
      reason: 'POSSIBLE_MANUAL_DUPLICATE', candidates: [], possibleManualReceiptId: 77,
      possibleManual: { receiptId: 77, ticketId: 31, kind: 'DEPOSIT', amount: 372000, receivedAt: '2026-11-10T03:00:00Z', recordedByName: 'คุณบุษบา' },
    });
    api.receiptImports.unmatched.mockResolvedValue([possible]);
    api.receiptImports.sameAsManual.mockResolvedValue({ ...possible, status: 'RESOLVED' });
    wrap(<UnmatchedReceipts showToast={vi.fn()} />);
    const row = await screen.findByTestId('unmatched-11');
    expect(within(row).getByText(/อาจเป็นรายการเดียวกับที่บันทึกเอง/)).toBeTruthy();
    fireEvent.click(within(row).getByRole('button', { name: 'รายการเดียวกับที่บันทึกเอง' }));
    await waitFor(() => expect(api.receiptImports.sameAsManual).toHaveBeenCalledWith(11));
    fireEvent.click(within(row).getByRole('button', { name: 'คนละรายการ' }));
    await waitFor(() => expect(api.receiptImports.resolve).toHaveBeenCalledWith(11, { ticketId: 31 }));
  });

  it('a set-aside row can be brought back', async () => {
    wrap(<UnmatchedReceipts showToast={vi.fn()} />);
    fireEvent.click(await screen.findByText(/รายการที่ข้ามไป/));
    fireEvent.click(await screen.findByRole('button', { name: 'นำกลับ RE-IGN-1' }));
    await waitFor(() => expect(api.receiptImports.unignore).toHaveBeenCalledWith(12));
  });
});

describe('UnmatchedReceiptsSummary — the sales manager reads, never works, the queue', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows how many rows wait and only what account flagged, with no actions', async () => {
    api.receiptImports.managerView.mockResolvedValue({
      openUnmatched: 5,
      flagged: [{ rowId: 3, docNo: 'RE-F-1', docDate: '2026-11-12', customerName: 'ลูกค้า ก', amount: 1200,
        reference: null, managerFlaggedAt: '2026-11-13T03:00:00Z' }],
    });
    wrap(<UnmatchedReceiptsSummary />);
    const card = await screen.findByRole('region', { name: 'รายการรับเงินที่ยังจับคู่ไม่ได้' });
    expect(within(card).getByText('5 รายการรอฝ่ายบัญชีจับคู่')).toBeTruthy();
    expect(within(card).getByText('RE-F-1')).toBeTruthy();
    expect(within(card).queryByRole('button')).toBeNull();
    expect(api.receiptImports.unmatched).not.toHaveBeenCalled();
  });

  it('renders nothing when nothing waits', async () => {
    api.receiptImports.managerView.mockResolvedValue({ openUnmatched: 0, flagged: [] });
    const { container } = wrap(<UnmatchedReceiptsSummary />);
    await waitFor(() => expect(api.receiptImports.managerView).toHaveBeenCalled());
    expect(container.textContent).toBe('');
  });
});

describe('ExpressUploadCard — the account landing top card (a-fin-land)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.receiptImports.latest.mockResolvedValue({ uploadEnabled: true, latest: { ...summary, errors: [] }, openUnmatched: 2 });
  });

  it('shows the last upload and what still waits; both actions open the upload page', async () => {
    wrap(<ExpressUploadCard />);
    const card = await screen.findByRole('region', { name: 'อัปโหลดไฟล์รับเงินจาก Express' });
    expect(within(card).getByText('จับคู่แล้ว 37 · ฿2,841,500')).toBeTruthy();
    expect(within(card).getByText('ซ้ำ (เคยนำเข้าแล้ว) 3')).toBeTruthy();
    expect(within(card).getByText('ยังจับคู่ไม่ได้ 2')).toBeTruthy();
    expect(within(card).getByRole('link', { name: 'อัปโหลดไฟล์' }).getAttribute('href')).toBe('/finance/receipts-import');
    expect(within(card).getByRole('link', { name: 'จับคู่ 2 รายการ' }).getAttribute('href')).toBe('/finance/receipts-import');
  });

  it('is hidden while the upload is switched off', async () => {
    api.receiptImports.latest.mockResolvedValue({ uploadEnabled: false, latest: null, openUnmatched: 0 });
    const { container } = wrap(<ExpressUploadCard />);
    await waitFor(() => expect(api.receiptImports.latest).toHaveBeenCalled());
    expect(container.textContent).toBe('');
  });
});

describe('ReceiptSourceChip', () => {
  it('names where a receipt came from', () => {
    const { rerender } = render(<ReceiptSourceChip source="EXPRESS" />);
    expect(screen.getByText('Express')).toBeTruthy();
    rerender(<ReceiptSourceChip source="MANUAL" />);
    expect(screen.getByText('บันทึกเอง')).toBeTruthy();
  });
});
