import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TeamOverview } from './TeamOverview.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, api: { salesBoard: { teamOverview: vi.fn() } } };
});

const rep = (over) => ({
  employeeId: 6, name: 'คุณสมหมาย ขายดี', openDeals: 3, openValue: 1000000, quietDeals: 0, lastUpdateAt: null,
  visitsDone: 0, visitsPlanned: 0, expected: 0, received: 0, ...over,
});

const team = {
  quietDays: 14,
  reps: [
    rep({ employeeId: 6, expected: 1301000, received: 121150 }),
    rep({ employeeId: 7, name: 'คุณวิภา ใจดี', expected: 0, received: 0 }),
  ],
  stale: [],
  teamVisitsThisWeek: [],
  cash: {
    month: '2026-11', forecast: 7900000, received: 2840000, deliveredAwaiting: 0, deliveriesScheduled: 0,
    depositsExpected: 0, weeks: [],
  },
};

function renderTeam() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><TeamOverview /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TeamOverview — cash (slice 3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.salesBoard.teamOverview.mockResolvedValue(team);
  });

  it('leads with เงินเข้าแล้ว {month}: received against the team forecast, from Express', async () => {
    renderTeam();
    const tile = await screen.findByTestId('tile-cash');
    expect(within(tile).getByText('เงินเข้าแล้ว พ.ย.')).toBeTruthy();
    expect(within(tile).getByText('฿2,840,000')).toBeTruthy();
    expect(within(tile).getByText('จากที่ทีมคาด ฿7,900,000 · มาจาก Express')).toBeTruthy();
  });

  it('puts each rep’s expected and received in the row; zero reads as a dash, not ฿0', async () => {
    renderTeam();
    const table = await screen.findByRole('table');
    const [, somMai, wipa] = within(table).getAllByRole('row');
    expect(within(somMai).getByTestId('rep-expected').textContent).toBe('฿1,301,000');
    expect(within(somMai).getByTestId('rep-received').textContent).toBe('฿121,150');
    expect(within(wipa).getByTestId('rep-expected').textContent).toBe('—');
    expect(within(wipa).getByTestId('rep-received').textContent).toBe('—');
  });
});
