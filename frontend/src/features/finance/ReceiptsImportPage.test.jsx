import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReceiptsImportPage } from './ReceiptsImportPage.jsx';
import { ReceiptSourceChip } from './ReceiptSourceChip.jsx';
import { UnmatchedReceipts } from '../salesBoard/UnmatchedReceipts.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      receiptImports: {
        upload: vi.fn(), latest: vi.fn(), template: vi.fn(), unmatched: vi.fn(), resolve: vi.fn(), ignore: vi.fn(),
      },
    },
  };
});
vi.mock('../../utils/download.js', () => ({ downloadBlob: vi.fn() }));

const summary = {
  importId: 4, fileName: 'RE-พ.ย.-สัปดาห์2.xlsx', uploadedAt: '2026-11-13T02:12:00Z', uploadedByName: 'คุณบุษบา',
  totalRows: 43, matched: 37, matchedAmount: 2841500, duplicates: 3, duplicateAmount: 212400, unmatched: 2,
  unmatchedAmount: 418000, invalid: 1, errors: [{ line: 9, message: 'วันที่ไม่ถูกต้อง (RE-6911-0009)' }],
};

const queued = (over) => ({
  rowId: 11, importId: 4, docNo: 'RE-6911-0057', docDate: '2026-11-12', customerName: 'บจก. ลุมพินี เรสซิเดนซ์',
  amount: 372000, reference: 'มัดจำ บ้านสวน', reason: 'AMBIGUOUS', status: 'UNMATCHED', resolvedTicketId: null,
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

describe('ReceiptsImportPage — อัปโหลดไฟล์รับเงินจาก Express', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.receiptImports.latest.mockResolvedValue({ latest: { ...summary, errors: [] }, openUnmatched: 2 });
    api.receiptImports.unmatched.mockResolvedValue([queued()]);
    api.receiptImports.upload.mockResolvedValue(summary);
    api.receiptImports.template.mockResolvedValue(new Blob(['x']));
  });

  it('shows the last upload before anything is chosen', async () => {
    wrap(<ReceiptsImportPage showToast={vi.fn()} />);
    expect(await screen.findByText(/RE-พ.ย.-สัปดาห์2.xlsx · 43 รายการ · คุณบุษบา/)).toBeTruthy();
  });

  it('uploads the chosen file and reads the outcome back as matched / duplicate / unmatched / unreadable', async () => {
    wrap(<ReceiptsImportPage showToast={vi.fn()} />);
    const file = new File(['a'], 'week2.csv', { type: 'text/csv' });
    fireEvent.change(await screen.findByLabelText('เลือกไฟล์'), { target: { files: [file] } });
    fireEvent.click(screen.getByRole('button', { name: 'อัปโหลดไฟล์' }));

    await waitFor(() => expect(api.receiptImports.upload).toHaveBeenCalledWith(file));
    const table = await screen.findByRole('table', { name: 'ผลการอัปโหลด' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((r) => within(r).getAllByRole('cell')[0].textContent))
      .toEqual(['จับคู่แล้ว', 'ซ้ำ (เคยนำเข้าแล้ว)', 'ยังจับคู่ไม่ได้', 'อ่านไม่ได้']);
    expect(within(rows[0]).getByText('37')).toBeTruthy();
    expect(within(rows[0]).getByText('2,841,500.00')).toBeTruthy();
    expect(screen.getByText('บรรทัด 9: วันที่ไม่ถูกต้อง (RE-6911-0009)')).toBeTruthy();
  });

  it('offers the template download and says what the matching order is', async () => {
    wrap(<ReceiptsImportPage showToast={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'ดาวน์โหลดแม่แบบ' }));
    await waitFor(() => expect(api.receiptImports.template).toHaveBeenCalled());
    expect(screen.getByText('จับคู่จากเลขใบแจ้งหนี้ก่อน แล้วเลขใบเสนอราคา แล้วลูกค้า + ยอดเงิน')).toBeTruthy();
  });
});

describe('UnmatchedReceipts — รายการรับเงินที่ยังจับคู่ไม่ได้', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.receiptImports.unmatched.mockResolvedValue([queued()]);
    api.receiptImports.resolve.mockResolvedValue(queued({ status: 'RESOLVED', resolvedTicketId: 31 }));
    api.receiptImports.ignore.mockResolvedValue(queued({ status: 'IGNORED' }));
  });

  it('lists each row with its candidates and matches one in one tap', async () => {
    wrap(<UnmatchedReceipts showToast={vi.fn()} canIgnore={false} />);
    const row = await screen.findByTestId('unmatched-11');
    expect(within(row).getByText('RE-6911-0057')).toBeTruthy();
    expect(within(row).getByText('฿372,000')).toBeTruthy();
    expect(within(row).getByText('ลูกค้า + ยอดตรง')).toBeTruthy();
    fireEvent.click(within(row).getAllByRole('button', { name: /^จับคู่กับดีลนี้/ })[0]);
    await waitFor(() => expect(api.receiptImports.resolve).toHaveBeenCalledWith(11, { ticketId: 31 }));
  });

  it('a rep gets no ข้าม; account / managers do', async () => {
    const { unmount } = wrap(<UnmatchedReceipts showToast={vi.fn()} canIgnore={false} />);
    await screen.findByTestId('unmatched-11');
    expect(screen.queryByRole('button', { name: /^ข้าม/ })).toBeNull();
    unmount();
    wrap(<UnmatchedReceipts showToast={vi.fn()} canIgnore />);
    fireEvent.click(await screen.findByRole('button', { name: /^ข้าม/ }));
    await waitFor(() => expect(api.receiptImports.ignore).toHaveBeenCalledWith(11));
  });

  it('renders nothing for a rep when nothing waits (hideWhenEmpty)', async () => {
    api.receiptImports.unmatched.mockResolvedValue([]);
    const { container } = wrap(<UnmatchedReceipts showToast={vi.fn()} hideWhenEmpty />);
    await waitFor(() => expect(api.receiptImports.unmatched).toHaveBeenCalled());
    expect(container.textContent).toBe('');
  });

  it('a row with no candidate says who should pick', async () => {
    api.receiptImports.unmatched.mockResolvedValue([queued({ candidates: [], reason: 'NO_CANDIDATE' })]);
    wrap(<UnmatchedReceipts showToast={vi.fn()} canIgnore />);
    expect(await screen.findByText('ไม่พบดีลที่ตรง — บันทึกรับชำระเองที่หน้าดีล หรือข้ามไป')).toBeTruthy();
  });
});

describe('ReceiptSourceChip', () => {
  it('names where a receipt came from', () => {
    const { rerender } = render(<ReceiptSourceChip source="EXPRESS" />);
    expect(screen.getByText('Express')).toBeTruthy();
    rerender(<ReceiptSourceChip source="MANUAL" />);
    expect(screen.getByText('บันทึกเอง')).toBeTruthy();
    rerender(<ReceiptSourceChip source={undefined} />);
    expect(screen.getByText('บันทึกเอง')).toBeTruthy();
  });
});

describe('ExpressUploadCard — the account landing top card (a-fin-land)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.receiptImports.latest.mockResolvedValue({ latest: { ...summary, errors: [] }, openUnmatched: 2 });
  });

  it('shows the last upload and what still waits, and both actions open the upload page', async () => {
    const { ExpressUploadCard } = await import('./ReceiptsImportPage.jsx');
    wrap(<ExpressUploadCard />);
    const card = await screen.findByRole('region', { name: 'อัปโหลดไฟล์รับเงินจาก Express' });
    expect(within(card).getByText('จับคู่แล้ว 37 · ฿2,841,500')).toBeTruthy();
    expect(within(card).getByText('ซ้ำ (เคยนำเข้าแล้ว) 3')).toBeTruthy();
    expect(within(card).getByText('ยังจับคู่ไม่ได้ 2')).toBeTruthy();
    expect(within(card).getByRole('link', { name: 'อัปโหลดไฟล์' }).getAttribute('href')).toBe('/finance/receipts-import');
    expect(within(card).getByRole('link', { name: 'จับคู่ 2 รายการ' }).getAttribute('href')).toBe('/finance/receipts-import');
  });

  it('before any upload it just invites one', async () => {
    api.receiptImports.latest.mockResolvedValue({ latest: null, openUnmatched: 0 });
    const { ExpressUploadCard } = await import('./ReceiptsImportPage.jsx');
    wrap(<ExpressUploadCard />);
    expect(await screen.findByText('ยังไม่เคยอัปโหลด — รับเงินจะขึ้นเองเมื่อจับคู่ได้')).toBeTruthy();
    expect(screen.queryByRole('link', { name: /^จับคู่/ })).toBeNull();
  });
});
