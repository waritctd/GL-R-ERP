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
  visit({ id: 2, contactName: 'Linea Interior', plannedTime: '14:00:00', ticketId: 31,
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

function renderPage(user = sales, showToast = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><VisitsPage user={user} showToast={showToast} /></MemoryRouter>
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

  it('opens on this week, and pages to the previous and next week and back', async () => {
    renderPage();

    expect(screen.getByRole('heading', { level: 1, name: 'นัดเข้าพบ' })).toBeTruthy();
    expect(await screen.findByText('สัปดาห์นี้ · 9–15 พ.ย.')).toBeTruthy();
    expect(api.salesVisits.list).toHaveBeenCalledWith({ from: '2026-11-09', to: '2026-11-15' });
    expect(await screen.findByTestId('visit-2')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'สัปดาห์ก่อน' }));
    expect(await screen.findByText('2–8 พ.ย.')).toBeTruthy();
    await waitFor(() => expect(api.salesVisits.list).toHaveBeenCalledWith({ from: '2026-11-02', to: '2026-11-08' }));
    expect(await screen.findByTestId('visit-8')).toBeTruthy();
    expect(screen.queryByTestId('visit-2')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'สัปดาห์นี้' }));
    expect(await screen.findByTestId('visit-2')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'สัปดาห์หน้า' }));
    await waitFor(() => expect(api.salesVisits.list).toHaveBeenCalledWith({ from: '2026-11-16', to: '2026-11-22' }));
    expect(await screen.findByText('16–22 พ.ย.')).toBeTruthy();
  });

  it('shows the week as planned vs done, with the cancelled ones apart', async () => {
    renderPage();

    const planned = await screen.findByTestId('visits-planned');
    const done = screen.getByTestId('visits-done');
    expect(screen.getByText('1 จาก 4 ไปแล้ว')).toBeTruthy();
    expect(within(planned).getByText('นัดไว้ (3)')).toBeTruthy();
    expect(['visit-5', 'visit-2', 'visit-4'].every((id) => within(planned).queryByTestId(id))).toBe(true);
    expect(within(planned).queryByTestId('visit-1')).toBeNull();
    expect(within(done).getByText('ไปแล้ว (1)')).toBeTruthy();
    expect(within(done).getByTestId('visit-1')).toBeTruthy();
    expect(within(done).queryByTestId('visit-2')).toBeNull();
    expect(within(screen.getByTestId('visits-cancelled')).getByTestId('visit-3')).toBeTruthy();
  });

  it('says so when the week has no visits, and still offers to plan one', async () => {
    renderPage();
    await screen.findByTestId('visit-2');

    fireEvent.click(screen.getByRole('button', { name: 'สัปดาห์หน้า' }));

    expect(await screen.findByText('ไม่มีนัดเข้าพบในสัปดาห์นี้')).toBeTruthy();
    expect(screen.queryByTestId('visits-planned')).toBeNull();
    expect(screen.queryByTestId('visits-done')).toBeNull();
    expect(screen.getByRole('button', { name: 'นัดเข้าพบ' })).toBeTruthy();
  });

  it('นัดเข้าพบ opens the plan dialog and saves the visit', async () => {
    renderPage();
    await screen.findByTestId('visit-2');

    fireEvent.click(screen.getByRole('button', { name: 'นัดเข้าพบ' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'นัดเข้าพบ' })).toBeTruthy();
    fireEvent.change(within(dialog).getByLabelText(/พบใคร/), { target: { value: 'คุณอรุณ' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกนัด' }));

    await waitFor(() => expect(api.salesVisits.create).toHaveBeenCalledTimes(1));
    expect(api.salesVisits.create.mock.calls[0][0]).toMatchObject({ contactName: 'คุณอรุณ', plannedDate: TODAY });
  });

  it('บันทึกเข้าพบ opens the log dialog with today\'s planned visits, even from another week', async () => {
    renderPage();
    await screen.findByTestId('visit-2');
    fireEvent.click(screen.getByRole('button', { name: 'สัปดาห์ก่อน' }));
    await screen.findByTestId('visit-8');

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกเข้าพบ' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'บันทึกเข้าพบ' })).toBeTruthy();
    expect(within(dialog).getByRole('radio', { name: /Linea Interior · 14:00/ })).toBeTruthy();
    expect(within(dialog).queryByRole('radio', { name: /ก้าวหน้า/ })).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.salesVisits.done).toHaveBeenCalledWith(2, { note: undefined }));
  });

  it('tapping a planned visit marks it done through the tick dialog; a future one cannot be ticked', async () => {
    renderPage();
    const row = await screen.findByTestId('visit-2');
    expect(within(screen.getByTestId('visit-4')).queryByRole('button', { name: /เข้าพบแล้ว/ })).toBeNull();

    fireEvent.click(within(row).getByRole('button', { name: 'เข้าพบแล้ว: Linea Interior' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'เข้าพบแล้ว' })).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ส่งตัวอย่าง' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.salesVisits.done).toHaveBeenCalledWith(2, { note: 'ส่งตัวอย่าง' }));
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

  it('a tick on a งาน that has moved to someone else says so, in งาน wording', async () => {
    const showToast = vi.fn();
    api.salesVisits.done.mockResolvedValue(visit({ id: 2, status: 'DONE', dealMovedToName: 'คุณอรุณี', dealMoved: true }));
    renderPage(sales, showToast);
    fireEvent.click(within(await screen.findByTestId('visit-2')).getByRole('button', { name: 'เข้าพบแล้ว: Linea Interior' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success',
      'งานนี้ย้ายไปให้ คุณอรุณี แล้ว — บันทึกการเข้าพบไว้กับนัดเท่านั้น'));
  });

  it('cancels a planned visit after confirming', async () => {
    renderPage();
    fireEvent.click(within(await screen.findByTestId('visit-2')).getByRole('button', { name: 'ยกเลิกนัด: Linea Interior' }));
    fireEvent.click(await screen.findByRole('button', { name: 'ยกเลิกนัด' }));

    await waitFor(() => expect(api.salesVisits.cancel).toHaveBeenCalledWith(2));
  });

  // Fix round 1, ruling 1: on the current week the page also lists the visits carried over from the 31 days before
  // this Monday (the same request as MyWork's ค้างจากสัปดาห์ก่อน), tickable; other weeks do not repeat them.
  it('shows visits carried over from earlier weeks on the current week only', async () => {
    const carried = visit({ id: 50, contactName: 'Orbit Studio', plannedDate: '2026-11-03' });
    api.salesVisits.list.mockImplementation(({ from }) => Promise.resolve(
      from === '2026-11-09' ? thisWeek : from === '2026-10-09' ? [carried, { ...carried, id: 51, status: 'DONE' }] : [],
    ));
    api.salesVisits.done.mockResolvedValue({ ...carried, status: 'DONE' });
    renderPage();

    const section = await screen.findByTestId('visits-carried-over');
    expect(api.salesVisits.list).toHaveBeenCalledWith({ from: '2026-10-09', to: '2026-11-08' });
    expect(within(section).getByTestId('visit-50')).toBeTruthy();
    expect(within(section).queryByTestId('visit-51')).toBeNull();
    fireEvent.click(within(section).getByRole('button', { name: 'เข้าพบแล้ว: Orbit Studio' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.salesVisits.done).toHaveBeenCalledWith(50, { note: undefined }));

    fireEvent.click(screen.getByRole('button', { name: 'สัปดาห์ก่อน' }));
    await screen.findByText('2–8 พ.ย.');
    expect(screen.queryByTestId('visits-carried-over')).toBeNull();
  });

  // Ruling 7: the dialogs this page opens say งาน in their own labels and hints.
  it('opens dialogs that say งาน, not ดีล', async () => {
    renderPage();
    await screen.findByTestId('visit-2');

    fireEvent.click(screen.getByRole('button', { name: 'นัดเข้าพบ' }));
    let dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('ไปพบผู้ออกแบบหรือเจ้าของได้ แม้ยังไม่มีงาน')).toBeTruthy();
    expect(within(dialog).getByLabelText(/งาน/).querySelector('option').textContent).toBe('ไม่มีงาน');
    expect(dialog.textContent).not.toMatch(/ดีล/);
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิก' }));

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกเข้าพบ' }));
    dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ไม่ได้นัด' }));
    expect(dialog.textContent).not.toMatch(/ดีล/);
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิก' }));

    fireEvent.click(within(screen.getByTestId('visit-2')).getByRole('button', { name: 'เข้าพบแล้ว: Linea Interior' }));
    dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('บันทึกแล้วงานนี้ออกจาก “ควรตามต่อ”')).toBeTruthy();
    expect(dialog.textContent).not.toMatch(/ดีล/);
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
