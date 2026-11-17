import React from 'react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationListPage } from './QuotationListPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// The list's per-row document chain mark: ONE field the list endpoint returns per row (`chainStage`,
// DocumentChainService#listStages), worded by documentChainText.js — never a per-row request.

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, api: { dealQuotations: { list: vi.fn(), counts: vi.fn() }, documentChain: { get: vi.fn() } } };
});

function memoryStorage() {
  const map = new Map();
  return {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => { map.set(key, String(value)); },
    removeItem: (key) => { map.delete(key); },
    clear: () => map.clear(),
  };
}

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales' };
const row = (overrides) => ({
  number: 'QT', customerName: 'บริษัท ก จำกัด', projectName: null, salesRepId: 6, salesRepName: 'คุณสมหมาย',
  grandTotal: 1000, docStatus: 'APPROVED', quotationDate: '2026-11-17', approvedAt: '2026-11-17T03:00:00Z',
  ticketCode: 'DL-1', dealBuyerCount: 1, chainStage: null, ...overrides,
});
const ROWS = [
  row({ id: 1, number: 'QT-2026-0001-1', ticketId: 1, chainStage: 'AWAITING_ORDER' }),
  row({ id: 2, number: 'QT-2026-0002-1', ticketId: 2, chainStage: 'AWAITING_DEPOSIT_NOTICE' }),
  row({ id: 3, number: 'QT-2026-0003-1', ticketId: 3, chainStage: 'NO_DEPOSIT' }),
  row({ id: 4, number: 'QT-2026-0004-1', ticketId: 4, chainStage: 'DEPOSIT_NOTICE_ISSUED' }),
  row({ id: 5, number: 'QT-2026-0005-1', ticketId: 5, chainStage: 'REMAINING_INVOICE_ISSUED' }),
  row({ id: 6, number: 'QT-2026-0006-1', ticketId: 6, docStatus: 'DRAFT', approvedAt: null }),
  row({ id: 7, number: 'QT-2026-0007-1', ticketId: 7, docStatus: 'SUPERSEDED' }),
  row({ id: 8, number: 'QT-2026-0008-1', ticketId: 8, docStatus: 'CANCELLED' }),
];
const EXPECTED = {
  'QT-2026-0001-1': 'รอยืนยันคำสั่งซื้อ',
  'QT-2026-0002-1': 'ยืนยันแล้ว · รอใบแจ้งมัดจำ',
  'QT-2026-0003-1': 'ยืนยันแล้ว · ไม่รับมัดจำ',
  'QT-2026-0004-1': 'ออกใบแจ้งมัดจำแล้ว',
  'QT-2026-0005-1': 'ออกใบแจ้งหนี้แล้ว',
};

function renderList() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/quotations']}>
        <Routes><Route path="/quotations" element={<QuotationListPage user={salesUser} />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function marksByNumber() {
  await screen.findAllByText('QT-2026-0001-1');
  const out = {};
  for (const mark of screen.queryAllByTestId('quotation-chain-mark')) {
    const container = mark.closest('tr') ?? mark.closest('[data-testid="quotation-card"]') ?? mark.parentElement.parentElement;
    const number = Object.keys(EXPECTED).concat(['QT-2026-0006-1', 'QT-2026-0007-1', 'QT-2026-0008-1'])
      .find((n) => container.textContent.includes(n));
    out[number] = mark.textContent;
  }
  return out;
}

describe('QuotationListPage — the per-row document chain mark', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('localStorage', memoryStorage());
    api.dealQuotations.counts.mockResolvedValue({});
    api.dealQuotations.list.mockResolvedValue({ items: ROWS });
  });

  it('flat view: each approved row carries its one mark from chainStage; drafts, superseded and cancelled rows none', async () => {
    renderList();
    expect(await marksByNumber()).toEqual(EXPECTED);
  });

  it('grouped view: the same marks', async () => {
    globalThis.localStorage.setItem('glr.quotation.listView.v1:6', JSON.stringify('grouped'));
    renderList();
    expect(await marksByNumber()).toEqual(EXPECTED);
  });

  it('makes no per-row request: one list read, no chain read', async () => {
    renderList();
    await marksByNumber();
    expect(api.dealQuotations.list).toHaveBeenCalledTimes(1);
    expect(api.documentChain.get).not.toHaveBeenCalled();
  });

  it('the mark sits in the status cell and wraps inside the measured floor rather than widening it', async () => {
    const { container } = renderList();
    await marksByNumber();
    const mark = within(container.querySelector('tbody')).getAllByTestId('quotation-chain-mark')[0];
    expect(mark.className).toContain('whitespace-normal');
    // the floors are unchanged (see the comment above LIST_TABLE_GRID)
    const source = readFileSync(resolve(__dirname, 'QuotationListPage.jsx'), 'utf8');
    expect(source).toContain("const LIST_TABLE_GRID = 'grid-cols-[minmax(6.875rem,1fr)_minmax(0,2.2fr)_minmax(0,1.3fr)_minmax(8.25rem,1.1fr)_minmax(4.75rem,1fr)_minmax(6.5rem,1fr)]';");
  });
});
