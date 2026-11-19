import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { VisitsPage } from './VisitsPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// A fixed Thursday, 12 Nov 2026 10:00 Bangkok: this week is Mon 9 – Sun 15 Nov.
vi.setSystemTime(new Date('2026-11-12T03:00:00Z'));
afterAll(() => vi.useRealTimers());

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      salesBoard: { myWork: vi.fn(), teamOverview: vi.fn() },
      salesVisits: { list: vi.fn(), team: vi.fn(), create: vi.fn(), done: vi.fn(), cancel: vi.fn() },
    },
  };
});

const TODAY = '2026-11-12';
const visit = (over) => ({
  id: 1, ownerId: 6, ownerName: 'คุณสมหมาย ขายดี', ticketId: null, ticketTitle: null, contactName: 'ลูกค้า',
  plannedDate: TODAY, plannedTime: null, status: 'PLANNED', doneAt: null, note: null, dealMovedToName: null,
  dealMoved: false, ...over,
});

const thisWeek = [
  visit({ id: 1, contactName: 'อรุณ อาร์คิเทค', plannedDate: '2026-11-10', status: 'DONE', note: 'เทียบสเปค' }),
  visit({ id: 2, contactName: 'Linea Interior', plannedTime: '14:00:00', ticketId: 31, dealFollowUpOpen: true,
    ticketTitle: 'สยามพารากอน · ห้องน้ำชั้น 3', note: 'ตามใบเสนอราคา' }),
  visit({ id: 3, contactName: 'Studio Nomad', plannedDate: '2026-11-10', status: 'CANCELLED' }),
  visit({ id: 4, contactName: 'ก้าวหน้า คอนสตรัคชั่น', plannedDate: '2026-11-13' }),
  visit({ id: 5, contactName: 'บ้านสวน ดีไซน์', plannedDate: '2026-11-10' }),
];
const lastWeek = [visit({ id: 8, contactName: 'PIA Interior', plannedDate: '2026-11-04', status: 'DONE' })];

const deal = {
  ticketId: 31, title: 'สยามพารากอน · ห้องน้ำชั้น 3', customerName: 'สยามพิวรรธน์', projectName: null, ownerId: 6,
  boardColumn: 3, value: 1240000, designerName: 'เทพสตูดิโอ ดีไซน์', daysQuiet: 27, quiet: true, currency: 'THB',
};

const rep = (over) => ({
  employeeId: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', openDeals: 3, openValue: 1000, otherCurrencyValues: {},
  quietDeals: 0, lastUpdateAt: '2026-11-12T01:00:00Z', visitsDone: 1, visitsPlanned: 2, ...over,
});
const team = {
  quietDays: 14, stale: [], cash: null,
  reps: [rep({}), rep({ employeeId: 7, name: 'คุณวิภา ใจดี' })],
  teamVisitsThisWeek: [
    visit({ id: 11, contactName: 'เทพสตูดิโอ', plannedDate: '2026-11-09', status: 'DONE' }),
    visit({ id: 12, ownerId: 7, ownerName: 'คุณวิภา ใจดี', contactName: 'DWP', plannedDate: '2026-11-11' }),
  ],
};

const sales = { id: 6, role: 'sales', employeeId: 6, name: 'คุณสมหมาย ขายดี' };

function renderPage(user = sales, showToast = vi.fn(), url = '/visits') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[url]}><VisitsPage user={user} showToast={showToast} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

/** The page's own text: the open dialogs (shared components, not this page's copy) are left out. */
function pageText(container) {
  const clone = container.cloneNode(true);
  clone.querySelectorAll('[role="dialog"]').forEach((node) => node.remove());
  return clone.textContent;
}

describe('VisitsPage — a sales rep\'s own week', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.salesVisits.list.mockImplementation(({ from }) => Promise.resolve(
      from === '2026-11-09' ? thisWeek : from === '2026-11-02' ? lastWeek : [],
    ));
    api.salesBoard.myWork.mockResolvedValue({ followUp: [deal], board: [deal], quietDays: 14, visitsThisWeek: thisWeek });
    api.salesVisits.create.mockResolvedValue(visit({ id: 9 }));
    api.salesVisits.done.mockResolvedValue(visit({ id: 2, status: 'DONE' }));
    api.salesVisits.cancel.mockResolvedValue(visit({ id: 2, status: 'CANCELLED' }));
  });

  // Slice 6.4: the same ตามต่อ as on งานของฉัน, on this page's tick and log dialogs.
  it('a tick with ตามต่อ sends the deal\'s next follow-up date; without it, exactly the old request', async () => {
    renderPage();
    const row = await screen.findByTestId('visit-2');
    fireEvent.click(within(row).getByRole('button', { name: 'เข้าพบแล้ว: Linea Interior' }));
    let dialog = await screen.findByRole('dialog');
    fireEvent.click(within(within(dialog).getByRole('radiogroup', { name: 'ตามต่อ' })).getByRole('radio', { name: '3 วัน' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.salesVisits.done).toHaveBeenCalledTimes(1));
    expect(api.salesVisits.done.mock.calls[0]).toStrictEqual([2, { note: undefined, nextFollowUpAt: '2026-11-15' }]);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกเข้าพบ' }));
    dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.salesVisits.done).toHaveBeenCalledTimes(2));
    expect(api.salesVisits.done.mock.calls[1]).toStrictEqual([2, { note: undefined }]);
  });

  it('never asks for the team list, and says งาน, not ดีล, in its own text', async () => {
    const { container } = renderPage();
    await screen.findByTestId('visit-2');

    expect(api.salesVisits.team).not.toHaveBeenCalled();
    expect(api.salesBoard.teamOverview).not.toHaveBeenCalled();
    expect(screen.queryByTestId('visit-week')).toBeNull();
    expect(pageText(container)).not.toMatch(/ดีล/);
  });
});

describe('VisitsPage — sales_manager and ceo see the team week', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.salesBoard.teamOverview.mockResolvedValue(team);
    api.salesVisits.team.mockResolvedValue([
      visit({ id: 21, ownerId: 7, ownerName: 'คุณวิภา ใจดี', contactName: 'Orbit', plannedDate: '2026-11-04' }),
    ]);
  });

  for (const role of ['sales_manager', 'ceo']) {
    it(`${role}: the team grid with week navigation, and none of the rep's own list`, async () => {
      const { container } = renderPage({ id: 2, role, employeeId: 2, name: 'ผู้จัดการ' });

      const grid = await screen.findByTestId('visit-week');
      expect(within(grid).getByText('ตารางเข้าพบสัปดาห์นี้')).toBeTruthy();
      expect(within(grid).getByTestId('visit-chip-12')).toBeTruthy();
      expect(api.salesVisits.list).not.toHaveBeenCalled();
      expect(api.salesBoard.myWork).not.toHaveBeenCalled();
      expect(screen.queryByRole('button', { name: 'บันทึกเข้าพบ' })).toBeNull();

      fireEvent.click(within(grid).getByRole('button', { name: 'สัปดาห์ก่อน' }));
      await waitFor(() => expect(api.salesVisits.team).toHaveBeenCalledWith({ from: '2026-11-02', to: '2026-11-08' }));
      expect(await within(grid).findByTestId('visit-chip-21')).toBeTruthy();
      expect(pageText(container)).not.toMatch(/ดีล/);
    });
  }
});
