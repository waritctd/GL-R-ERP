import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TeamOverview } from './TeamOverview.jsx';
import { api } from '../../api/index.js';
import { bangkokTodayIso } from '../../utils/format.js';
import { weekDays } from './visitWeek.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, api: { salesBoard: { teamOverview: vi.fn() } } };
});

const today = bangkokTodayIso();
const [mon, tue, wed, , fri, sat] = weekDays(today);

const visit = (over) => ({
  id: 1, ownerId: 6, ownerName: 'คุณสมหมาย ขายดี', ticketId: null, ticketTitle: null, contactName: 'ลูกค้า',
  plannedDate: mon.iso, plannedTime: null, status: 'PLANNED', doneAt: null, note: null, ...over,
});

const rep = (over) => ({
  employeeId: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', openDeals: 3, openValue: 1000, otherCurrencyValues: {},
  quietDeals: 0, lastUpdateAt: new Date().toISOString(), visitsDone: 0, visitsPlanned: 0, ...over,
});

const fixture = {
  quietDays: 14,
  stale: [],
  reps: [
    rep({ employeeId: 6, name: 'คุณสมหมาย ขายดี', visitsDone: 2, visitsPlanned: 3 }),
    rep({ employeeId: 7, name: 'คุณวิภา ใจดี', visitsDone: 1, visitsPlanned: 2 }),
    rep({ employeeId: 9, name: 'คุณกมล รุ่งเรือง', visitsDone: 0, visitsPlanned: 0 }),
  ],
  teamVisitsThisWeek: [
    visit({ id: 1, ownerId: 6, contactName: 'เทพสตูดิโอ', plannedDate: mon.iso, status: 'DONE' }),
    visit({ id: 2, ownerId: 6, contactName: 'อรุณ อาร์คิเทค', plannedDate: tue.iso, status: 'DONE' }),
    visit({ id: 3, ownerId: 6, contactName: 'Linea Interior', plannedDate: fri.iso, status: 'PLANNED' }),
    visit({ id: 4, ownerId: 7, ownerName: 'คุณวิภา ใจดี', contactName: 'DWP', plannedDate: tue.iso, status: 'DONE' }),
    visit({ id: 5, ownerId: 7, ownerName: 'คุณวิภา ใจดี', contactName: 'Orbit', plannedDate: wed.iso,
      status: 'PLANNED' }),
    visit({ id: 6, ownerId: 7, ownerName: 'คุณวิภา ใจดี', contactName: 'Taak', plannedDate: wed.iso,
      status: 'CANCELLED' }),
  ],
};

function renderTeam() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><TeamOverview /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TeamOverview — visits', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.salesBoard.teamOverview.mockResolvedValue(fixture);
  });

  it('the เข้าพบสัปดาห์นี้ tile sums done / booked across the team', async () => {
    renderTeam();

    const tile = await screen.findByTestId('tile-visits');
    expect(within(tile).getByText('เข้าพบสัปดาห์นี้')).toBeTruthy();
    expect(within(tile).getByText('3 / 5')).toBeTruthy();
    expect(within(tile).getByText('ไปแล้ว / นัดไว้')).toBeTruthy();
  });

  it('each rep row carries visit dots with a text label, not colour only', async () => {
    renderTeam();

    const dots = await screen.findByTestId('rep-visits-6');
    expect(dots.getAttribute('aria-label')).toBe('เข้าพบแล้ว 2 จาก 3 นัด');
    expect(dots.querySelectorAll('[data-dot="done"]')).toHaveLength(2);
    expect(dots.querySelectorAll('[data-dot="planned"]')).toHaveLength(1);
    expect(screen.getByTestId('rep-visits-9').getAttribute('aria-label')).toBe('ไม่มีนัดสัปดาห์นี้');
  });

  it('the week grid puts each visit under its rep and day, with its status in text', async () => {
    renderTeam();

    const grid = await screen.findByTestId('visit-week');
    expect(within(grid).getByText('ตารางเข้าพบสัปดาห์นี้')).toBeTruthy();
    for (const day of [mon, tue, wed, fri]) {
      expect(within(grid).getAllByText(`${day.short} ${day.dayOfMonth}`).length).toBeGreaterThan(0);
    }
    // No weekend visit, so no weekend column.
    expect(within(grid).queryByText(`${sat.short} ${sat.dayOfMonth}`)).toBeNull();

    const cell = within(grid).getByTestId(`visit-cell-7-${wed.iso}`);
    expect(within(cell).getByText('Orbit')).toBeTruthy();
    expect(within(cell).getByText('Taak')).toBeTruthy();
    expect(within(cell).getByTestId('visit-chip-5').textContent).toContain('นัดไว้');
    expect(within(cell).getByTestId('visit-chip-6').textContent).toContain('ยกเลิก');
    expect(within(grid).getByTestId('visit-chip-1').textContent).toContain('ไปแล้ว');
    // A rep with no visits this week still has a row, so a gap is visible.
    expect(within(grid).getByTestId('visit-row-9')).toBeTruthy();
  });

  it('a weekend visit adds the weekend columns', async () => {
    api.salesBoard.teamOverview.mockResolvedValue({
      ...fixture,
      teamVisitsThisWeek: [...fixture.teamVisitsThisWeek, visit({ id: 7, contactName: 'Showroom', plannedDate: sat.iso })],
    });
    renderTeam();

    const grid = await screen.findByTestId('visit-week');
    expect(within(grid).getAllByText(`${sat.short} ${sat.dayOfMonth}`).length).toBeGreaterThan(0);
    expect(within(grid).getByTestId(`visit-cell-6-${sat.iso}`)).toBeTruthy();
  });

  it('says so when nobody has a visit this week', async () => {
    api.salesBoard.teamOverview.mockResolvedValue({ ...fixture, teamVisitsThisWeek: [] });
    renderTeam();

    const grid = await screen.findByTestId('visit-week');
    expect(within(grid).getByText('ยังไม่มีนัดเข้าพบสัปดาห์นี้')).toBeTruthy();
  });
});
