import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MyWork } from './MyWork.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      salesBoard: { myWork: vi.fn() },
      salesVisits: { create: vi.fn(), done: vi.fn(), cancel: vi.fn() },
      tickets: { addActivity: vi.fn(), markLost: vi.fn(), create: vi.fn() },
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
    },
  };
});

vi.mock('../tickets/TicketCreateModal.jsx', () => ({
  TicketCreateModal: () => <div role="dialog">สร้างดีลใหม่ (stub)</div>,
}));


const cash = {
  month: '2026-11', forecast: 1301000, received: 121150, deliveredAwaiting: 45000, deliveriesScheduled: 0,
  depositsExpected: 0,
  weeks: [{ weekStart: '2026-11-09', deliveredAwaiting: 45000, deliveriesScheduled: 0, depositsExpected: 0, received: 121150 }],
};

function renderMyWork() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><MyWork showToast={vi.fn()} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('MyWork — เงินที่จะเข้า (slice 3)', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows the served month as card C, beside follow-up and visits', async () => {
    api.salesBoard.myWork.mockResolvedValue({ followUp: [], board: [], quietDays: 14, visitsThisWeek: [], cash });
    renderMyWork();
    const card = await screen.findByRole('region', { name: 'เงินที่จะเข้า พ.ย.' });
    expect(within(card).getByTestId('cash-received').textContent).toBe('฿121,150');
  });

  it('shows the calm empty state when the month has nothing', async () => {
    api.salesBoard.myWork.mockResolvedValue({
      followUp: [], board: [], quietDays: 14, visitsThisWeek: [],
      cash: { ...cash, forecast: 0, received: 0, deliveredAwaiting: 0, weeks: [] },
    });
    renderMyWork();
    expect(await screen.findByText('ยังไม่มีเงินที่คาดว่าจะเข้าเดือนนี้')).toBeTruthy();
  });
});
