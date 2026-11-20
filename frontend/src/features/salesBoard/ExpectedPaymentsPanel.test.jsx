import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ExpectedPaymentsPanel } from './ExpectedPaymentsPanel.jsx';
import { api } from '../../api/index.js';
import { bangkokTodayIso } from '../../utils/format.js';
import { nextMondays, weekRangeLabel } from './cashFormat.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: { expectedPayments: { list: vi.fn(), create: vi.fn(), update: vi.fn(), cancel: vi.fn() } },
  };
});

const mondays = nextMondays(bangkokTodayIso(), 4);
const row = (over) => ({
  id: 1, ticketId: 23, kind: 'DEPOSIT', amount: 456150, expectedWeek: mondays[0], status: 'OPEN', receiptId: null,
  receiptRef: null, createdById: 6, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', ...over,
});

function renderPanel(props = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const showToast = vi.fn();
  render(
    <QueryClientProvider client={client}>
      <ExpectedPaymentsPanel ticketId={23} dealValue={912300} canWrite showToast={showToast} {...props} />
    </QueryClientProvider>,
  );
  return { showToast };
}

describe('ExpectedPaymentsPanel — เงินที่คาดว่าจะเข้า', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.expectedPayments.list.mockResolvedValue([
      row({ id: 1, status: 'RECEIVED', receiptId: 88, receiptRef: 'RE-6910-0188' }),
      row({ id: 2, kind: 'BALANCE', expectedWeek: mondays[2] }),
    ]);
    api.expectedPayments.create.mockResolvedValue(row({ id: 3 }));
    api.expectedPayments.cancel.mockResolvedValue(row({ id: 2, status: 'CANCELLED' }));
  });

  it('a RECEIVED row offers no edit or cancel; an OPEN row cancels after a confirm', async () => {
    renderPanel();
    const table = await screen.findByRole('table', { name: 'เงินที่คาดว่าจะเข้า' });
    const [received, open] = within(table).getAllByRole('row').slice(1);
    expect(within(received).queryByRole('button')).toBeNull();

    fireEvent.click(within(open).getByRole('button', { name: /ยกเลิกรายการ/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'ยกเลิกรายการนี้' }));
    await waitFor(() => expect(api.expectedPayments.cancel).toHaveBeenCalledWith(23, 2));
  });

  it('adds a deposit: 30% of the deal value pre-fills, a week pick sends that Monday', async () => {
    renderPanel();
    fireEvent.click(await screen.findByRole('button', { name: '+ เพิ่ม' }));
    const dialog = await screen.findByRole('dialog', { name: 'เงินที่คาดว่าจะเข้า' });

    fireEvent.click(within(dialog).getByRole('radio', { name: 'มัดจำ' }));
    fireEvent.click(within(dialog).getByRole('button', { name: '30% ของมูลค่า' }));
    expect(within(dialog).getByLabelText('จำนวนเงิน').value).toBe('273690');
    fireEvent.click(within(dialog).getByRole('radio', { name: weekRangeLabel(mondays[1]) }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.expectedPayments.create).toHaveBeenCalledWith(23, {
      kind: 'DEPOSIT', amount: 273690, expectedWeek: mondays[1],
    }));
  });

  it('"ส่วนที่เหลือทั้งหมด" is the deal value minus every row already planned', async () => {
    api.expectedPayments.list.mockResolvedValue([row({ id: 1, amount: 456150 })]);
    renderPanel();
    fireEvent.click(await screen.findByRole('button', { name: '+ เพิ่ม' }));
    const dialog = await screen.findByRole('dialog', { name: 'เงินที่คาดว่าจะเข้า' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ส่วนที่เหลือทั้งหมด' }));
    expect(within(dialog).getByLabelText('จำนวนเงิน').value).toBe('456150');
  });

  it('read-only viewers see the list but no add / edit / cancel', async () => {
    renderPanel({ canWrite: false });
    const table = await screen.findByRole('table', { name: 'เงินที่คาดว่าจะเข้า' });
    expect(screen.queryByRole('button', { name: '+ เพิ่ม' })).toBeNull();
    expect(within(table).queryByRole('button')).toBeNull();
  });
});
