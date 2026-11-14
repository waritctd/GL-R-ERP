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

const now = new Date();
const daysAgoIso = (n) => new Date(now.getTime() - n * 86_400_000).toISOString();

const stale = (over) => ({
  ticketId: 1, title: 'ดีล', customerName: 'ลูกค้า', projectName: null, ownerId: 6, ownerName: 'คุณสมหมาย ขายดี',
  salesStage: 'QUOTE_OWNER', repCode: 'S3', boardColumn: 3, value: null, designerName: null, winProbability: 40,
  expectedQuarter: null, lastTouchedAt: daysAgoIso(20), daysQuiet: 20, quiet: true, ...over,
});

const fixture = {
  quietDays: 14,
  reps: [
    { employeeId: 7, name: 'คุณวิภา ใจดี', openDeals: 17, openValue: 31200000, quietDeals: 0, lastUpdateAt: now.toISOString() },
    { employeeId: 6, name: 'คุณสมหมาย ขายดี', openDeals: 14, openValue: 18600000, quietDeals: 3, lastUpdateAt: daysAgoIso(1) },
    { employeeId: 9, name: 'คุณกมล รุ่งเรือง', openDeals: 11, openValue: 0, quietDeals: 3, lastUpdateAt: daysAgoIso(6) },
  ],
  stale: [
    stale({ ticketId: 41, title: 'Etihad Lounge', ownerName: 'คุณกมล รุ่งเรือง', value: 1880000, daysQuiet: 41 }),
    stale({ ticketId: 42, title: 'สยามพารากอน', value: 1240000, daysQuiet: 27 }),
    stale({ ticketId: 43, title: 'Central Ladprao', value: null, daysQuiet: 19 }),
  ],
};

function renderTeam(props = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><TeamOverview {...props} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TeamOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.salesBoard.teamOverview.mockResolvedValue(fixture);
  });

  it('heads the section ภาพรวมทีมขาย with the two tiles from the served numbers', async () => {
    renderTeam();

    expect(screen.getByRole('heading', { name: 'ภาพรวมทีมขาย' })).toBeTruthy();
    const held = await screen.findByTestId('tile-held');
    expect(within(held).getByText('ดีลที่ถืออยู่')).toBeTruthy();
    expect(within(held).getByText('42 ดีล')).toBeTruthy();
    expect(within(held).getByText('มูลค่า ฿49,800,000')).toBeTruthy();
    const quiet = screen.getByTestId('tile-quiet');
    expect(within(quiet).getByText('ดีลเงียบเกิน 14 วัน')).toBeTruthy();
    expect(within(quiet).getByText('6 ดีล')).toBeTruthy();
  });

  it('can drop its own heading when the page header already says it', async () => {
    renderTeam({ showTitle: false });

    await screen.findByTestId('tile-held');
    expect(screen.queryByRole('heading', { name: 'ภาพรวมทีมขาย' })).toBeNull();
  });

  it('has one table row per rep, with counts, th-TH baht and last update; a name opens that rep\'s deals', async () => {
    renderTeam();

    const table = await screen.findByRole('table');
    const headers = within(table).getAllByRole('columnheader').map((th) => th.textContent);
    expect(headers).toEqual(['พนักงานขาย', 'ดีล', 'มูลค่า', 'ดีลเงียบ', 'อัปเดตล่าสุด']);
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(3);
    const somMai = rows[1];
    expect(within(somMai).getByText('คุณสมหมาย ขายดี')).toBeTruthy();
    expect(within(somMai).getByText('14')).toBeTruthy();
    expect(within(somMai).getByText('฿18,600,000')).toBeTruthy();
    expect(within(somMai).getByText('3 ดีล')).toBeTruthy();
    expect(within(somMai).getByText('เมื่อวาน')).toBeTruthy();
    expect(within(rows[0]).getByText('ไม่มี')).toBeTruthy();
    expect(within(rows[0]).getByText('วันนี้')).toBeTruthy();
    // The deal list's existing ?owner= filter (TicketListPage) — the plan assumed none existed.
    expect(within(somMai).getByRole('link', { name: 'คุณสมหมาย ขายดี' }).getAttribute('href')).toBe('/tickets?owner=6');
  });

  it('lists ดีลที่ควรโทรตาม in the order served, with owner, value and days quiet', async () => {
    renderTeam();

    const list = await screen.findByTestId('team-stale');
    expect(screen.getByText('ดีลที่ควรโทรตาม')).toBeTruthy();
    const items = within(list).getAllByRole('listitem');
    expect(items.map((li) => within(li).getByRole('link').textContent)).toEqual(['Etihad Lounge', 'สยามพารากอน', 'Central Ladprao']);
    expect(within(items[0]).getByText('฿1,880,000')).toBeTruthy();
    expect(within(items[0]).getByText(/คุณกมล รุ่งเรือง/)).toBeTruthy();
    expect(within(items[0]).getByText('เงียบ 41 วัน')).toBeTruthy();
    expect(within(items[2]).getByText('—')).toBeTruthy();
  });

  it('has no cash or visit tiles (slices 2–3) and no ฿0.00', async () => {
    renderTeam();

    await screen.findByTestId('tile-held');
    expect(screen.queryByText(/เงินเข้า|เข้าพบ/)).toBeNull();
    expect(screen.queryByText('฿0.00')).toBeNull();
  });
});
