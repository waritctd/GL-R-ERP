import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SalesOverview } from './SalesOverview.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// The rep landing is งานของฉัน (spec 2026-11-15 §7.3). Its own behaviour — follow-up list, call logging,
// mark-lost, the board — is pinned in features/salesBoard/MyWork.test.jsx and DealBoard.test.jsx. The former
// worklist / money band / 7-day panel this page carried were replaced by it (plan Task 6).
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      salesBoard: { myWork: vi.fn() },
      tickets: { list: vi.fn(), addActivity: vi.fn(), markLost: vi.fn() },
      commissions: { monthlySummary: vi.fn() },
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
    },
  };
});

const salesUser = { employeeId: 1, name: 'Sales Test', role: 'sales' };
const employee = { nameTh: 'สมชาย ใจดี' };

function renderOverview() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><SalesOverview user={salesUser} employee={employee} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SalesOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.salesBoard.myWork.mockResolvedValue({ followUp: [], board: [], quietDays: 14 });
  });

  it('greets the rep and lands on งานของฉัน, read from the own-scoped my-work endpoint', async () => {
    renderOverview();

    expect(screen.getByRole('heading', { name: 'งานของฉัน' })).toBeTruthy();
    expect(await screen.findByText(/สวัสดี, คุณสมชาย ใจดี/)).toBeTruthy();
    expect(await screen.findByText('ไม่มีดีลค้าง — ทุกดีลมีความเคลื่อนไหวใน 14 วันที่ผ่านมา')).toBeTruthy();
    expect(api.salesBoard.myWork).toHaveBeenCalledTimes(1);
  });

  it('no longer fetches the full deal list or the commission estimate on landing', async () => {
    renderOverview();
    await screen.findByTestId('deal-board');

    expect(api.tickets.list).not.toHaveBeenCalled();
    expect(api.commissions.monthlySummary).not.toHaveBeenCalled();
  });
});
