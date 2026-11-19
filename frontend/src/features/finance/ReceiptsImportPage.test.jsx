import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
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
    fireEvent.click(within(row).getAllByRole('button', { name: /^จับคู่กับงานนี้/ })[0]);
    await waitFor(() => expect(api.receiptImports.resolve).toHaveBeenCalledWith(11, { ticketId: 31 }));
  });

  it('splits one row across deals; the split is sent only when the lines sum to the row', async () => {
    api.receiptImports.unmatched.mockResolvedValue([queued({ amount: 3000, candidates: [], reason: 'NO_CANDIDATE' })]);
    wrap(<UnmatchedReceipts showToast={vi.fn()} />);
    const row = await screen.findByTestId('unmatched-11');
    fireEvent.click(within(row).getByRole('button', { name: 'เลือกงานเอง' }));
    fireEvent.change(within(row).getByLabelText('ค้นหางาน'), { target: { value: 'ดีล' } });
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
});
