import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { MyWork } from './MyWork.jsx';
import { api } from '../../api/index.js';
import { bangkokTodayIso } from '../../utils/format.js';
import { weekDays } from './visitWeek.js';

globalThis.React = React;

// A fixed Thursday, 12 Nov 2026 10:00 Bangkok, so past / today / future rows are deterministic (Mon 9 – Sun 15).
vi.setSystemTime(new Date('2026-11-12T03:00:00Z'));
afterAll(() => vi.useRealTimers());

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      salesBoard: { myWork: vi.fn() },
      salesVisits: { list: vi.fn(), create: vi.fn(), done: vi.fn(), cancel: vi.fn() },
      tickets: { addActivity: vi.fn(), markLost: vi.fn(), create: vi.fn() },
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
    },
  };
});

vi.mock('../tickets/TicketCreateModal.jsx', () => ({
  TicketCreateModal: () => <div role="dialog">สร้างดีลใหม่ (stub)</div>,
}));

const today = bangkokTodayIso();
const week = weekDays(today);
// Tuesday 10 Nov: earlier this week, so a PLANNED visit that day is overdue.
const otherDay = week[1];
const friday = week[4].iso;

const quiet = {
  ticketId: 31, title: 'สยามพารากอน · ห้องน้ำชั้น 3', customerName: 'สยามพิวรรธน์', projectName: null, ownerId: 6,
  ownerName: 'คุณสมหมาย', salesStage: 'QUOTE_DESIGN_SIDE', repCode: 'S3', boardColumn: 3, value: 1240000,
  designerName: 'เทพสตูดิโอ ดีไซน์', winProbability: 40, expectedQuarter: null,
  lastTouchedAt: '2026-10-19T00:00:00Z', daysQuiet: 27, quiet: true, currency: 'THB', valueExpired: false,
  lastActivityKind: null, lastActivityNote: null,
};
const fresh = { ...quiet, ticketId: 32, title: 'BMW Showroom', daysQuiet: 2, quiet: false, designerName: null };

const visit = (over) => ({
  id: 1, ownerId: 6, ownerName: 'คุณสมหมาย', ticketId: null, ticketTitle: null, contactName: 'ลูกค้า',
  plannedDate: today, plannedTime: null, status: 'PLANNED', doneAt: null, note: null, ...over,
});

const visits = [
  visit({ id: 1, contactName: 'อรุณ อาร์คิเทค', plannedDate: otherDay.iso, status: 'DONE', note: 'เทียบสเปค',
    doneAt: '2026-11-10T03:00:00Z' }),
  visit({ id: 2, contactName: 'Linea Interior', plannedTime: '14:00:00', ticketId: 31,
    ticketTitle: 'สยามพารากอน · ห้องน้ำชั้น 3', note: 'ตามใบเสนอราคา' }),
  visit({ id: 3, contactName: 'Studio Nomad', plannedDate: otherDay.iso, status: 'CANCELLED' }),
  // PLANNED on other days pin the today-only filter of บันทึกเข้าพบ (review M5) and the overdue / future states.
  visit({ id: 4, contactName: 'ก้าวหน้า คอนสตรัคชั่น', plannedDate: friday }),
  visit({ id: 5, contactName: 'บ้านสวน ดีไซน์', plannedDate: otherDay.iso }),
];

function renderMyWork(props = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><MyWork showToast={vi.fn()} {...props} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('MyWork — นัดเข้าพบสัปดาห์นี้', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.salesBoard.myWork.mockResolvedValue({
      followUp: [quiet], board: [quiet, fresh], quietDays: 14, visitsThisWeek: visits,
    });
    api.salesVisits.create.mockResolvedValue(visit({ id: 9 }));
    api.salesVisits.done.mockResolvedValue(visit({ id: 2, status: 'DONE' }));
    api.salesVisits.cancel.mockResolvedValue(visit({ id: 2, status: 'CANCELLED' }));
    api.salesVisits.list.mockResolvedValue([]);
  });

  it('lists this week with day, contact, time and deal, and counts done of booked', async () => {
    renderMyWork();

    const section = await screen.findByTestId('visits-this-week');
    expect(within(section).getByText('นัดเข้าพบสัปดาห์นี้')).toBeTruthy();
    expect(await within(section).findByText('1 จาก 4 ไปแล้ว')).toBeTruthy();

    const planned = within(section).getByTestId('visit-2');
    expect(within(planned).getByText('Linea Interior')).toBeTruthy();
    expect(within(planned).getByText('14:00 · สยามพารากอน · ห้องน้ำชั้น 3 · ตามใบเสนอราคา')).toBeTruthy();
    expect(within(planned).getByText(String(Number(today.slice(8, 10))))).toBeTruthy();

    const done = within(section).getByTestId('visit-1');
    expect(within(done).getByText('ไปแล้ว')).toBeTruthy();
    expect(within(done).queryByRole('button', { name: /เข้าพบแล้ว/ })).toBeNull();

    const cancelled = within(section).getByTestId('visit-3');
    expect(within(cancelled).getByText('ยกเลิก')).toBeTruthy();
  });

  it('says so when nothing is booked this week', async () => {
    api.salesBoard.myWork.mockResolvedValue({ followUp: [], board: [], quietDays: 14, visitsThisWeek: [] });
    renderMyWork();

    expect(await screen.findByText('ยังไม่มีนัดเข้าพบสัปดาห์นี้')).toBeTruthy();
  });

  it('ticking a planned visit asks for an optional outcome and note, then marks it done', async () => {
    renderMyWork();
    const row = await screen.findByTestId('visit-2');

    fireEvent.click(within(row).getByRole('button', { name: 'เข้าพบแล้ว: Linea Interior' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'เข้าพบแล้ว' })).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ส่งตัวอย่าง' }));
    fireEvent.change(within(dialog).getByLabelText(/โน้ต/), { target: { value: 'ชอบสีเทา' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.salesVisits.done).toHaveBeenCalledWith(2, { note: 'ส่งตัวอย่าง — ชอบสีเทา' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(api.salesBoard.myWork).toHaveBeenCalledTimes(2);
  });

  it('a planned visit can be cancelled after a confirmation', async () => {
    renderMyWork();
    const row = await screen.findByTestId('visit-2');

    fireEvent.click(within(row).getByRole('button', { name: 'ยกเลิกนัด: Linea Interior' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิกนัด' }));

    await waitFor(() => expect(api.salesVisits.cancel).toHaveBeenCalledWith(2));
  });

  it('+ นัดเข้าพบ plans a visit, with or without a deal', async () => {
    renderMyWork();
    const section = await screen.findByTestId('visits-this-week');

    fireEvent.click(within(section).getByRole('button', { name: /นัดเข้าพบ/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'นัดเข้าพบ' })).toBeTruthy();
    expect(within(dialog).getByText('ไปพบผู้ออกแบบหรือเจ้าของได้ แม้ยังไม่มีดีล')).toBeTruthy();
    const save = within(dialog).getByRole('button', { name: 'บันทึกนัด' });
    expect(save.disabled).toBe(true);

    fireEvent.change(within(dialog).getByLabelText(/พบใคร/), { target: { value: 'คุณอรุณ · อรุณ อาร์คิเทค' } });
    fireEvent.change(within(dialog).getByLabelText(/ดีล/), { target: { value: '32' } });
    fireEvent.change(within(dialog).getByLabelText(/เวลา/), { target: { value: '10:00' } });
    fireEvent.change(within(dialog).getByLabelText(/เรื่องที่จะคุย/), { target: { value: 'นำตัวอย่างไปเทียบสี' } });
    expect(save.disabled).toBe(false);
    fireEvent.click(save);

    await waitFor(() => expect(api.salesVisits.create).toHaveBeenCalledTimes(1));
    expect(api.salesVisits.create.mock.calls[0][0]).toEqual({
      contactName: 'คุณอรุณ · อรุณ อาร์คิเทค', ticketId: 32, plannedDate: today, plannedTime: '10:00',
      note: 'นำตัวอย่างไปเทียบสี',
    });
  });

  it("the follow-up row's นัดเข้าพบ opens the plan dialog prefilled with that deal", async () => {
    renderMyWork();
    const row = await screen.findByTestId('follow-up-31');

    fireEvent.click(within(row).getByRole('button', { name: 'นัดเข้าพบ' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByLabelText(/ดีล/).value).toBe('31');
    expect(within(dialog).getByLabelText(/พบใคร/).value).toBe('เทพสตูดิโอ ดีไซน์');
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกนัด' }));

    await waitFor(() => expect(api.salesVisits.create).toHaveBeenCalledTimes(1));
    expect(api.salesVisits.create.mock.calls[0][0]).toMatchObject({ ticketId: 31, contactName: 'เทพสตูดิโอ ดีไซน์' });
  });

  it('บันทึกเข้าพบ ticks one of today\'s visits in one step', async () => {
    renderMyWork();
    await screen.findByTestId('visit-2');

    fireEvent.click(screen.getByTestId('log-visit-header'));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'บันทึกเข้าพบ' })).toBeTruthy();
    // Only today's PLANNED visits are offered, plus ไม่ได้นัด.
    expect(within(dialog).getByRole('radio', { name: /Linea Interior · 14:00/ })).toBeTruthy();
    expect(within(dialog).queryByRole('radio', { name: /Studio Nomad/ })).toBeNull();
    expect(within(dialog).queryByRole('radio', { name: /ก้าวหน้า/ })).toBeNull();
    expect(within(dialog).queryByRole('radio', { name: /บ้านสวน/ })).toBeNull();
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ขอใบเสนอราคา' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.salesVisits.done).toHaveBeenCalledWith(2, { note: 'ขอใบเสนอราคา' }));
    expect(api.salesVisits.create).not.toHaveBeenCalled();
  });

  it('บันทึกเข้าพบ for an unplanned visit creates it done today', async () => {
    renderMyWork();
    await screen.findByTestId('visit-2');

    fireEvent.click(screen.getByTestId('log-visit-sticky'));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ไม่ได้นัด' }));
    const save = within(dialog).getByRole('button', { name: 'บันทึก' });
    expect(save.disabled).toBe(true);
    fireEvent.change(within(dialog).getByLabelText(/พบใคร/), { target: { value: 'Walk-in · คุณวราภรณ์' } });
    fireEvent.change(within(dialog).getByLabelText(/โน้ต/), { target: { value: 'บ้าน 2 ชั้น' } });
    fireEvent.click(save);

    await waitFor(() => expect(api.salesVisits.create).toHaveBeenCalledTimes(1));
    expect(api.salesVisits.create.mock.calls[0][0]).toEqual({
      contactName: 'Walk-in · คุณวราภรณ์', ticketId: null, plannedDate: today, note: 'บ้าน 2 ชั้น', done: true,
    });
  });

  it('a failed save shows the error toast and keeps the dialog open', async () => {
    const showToast = vi.fn();
    api.salesVisits.create.mockRejectedValue(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    renderMyWork({ showToast });
    const section = await screen.findByTestId('visits-this-week');
    fireEvent.click(within(section).getByRole('button', { name: /นัดเข้าพบ/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/พบใคร/), { target: { value: 'คุณอรุณ' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกนัด' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    expect(screen.getByRole('dialog')).toBeTruthy();
  });
});

describe('MyWork — visits review fixes', () => {
  const nextWeekVisit = visit({ id: 20, contactName: 'Orbit Studio', plannedDate: '2026-11-17' });
  const oldVisit = visit({ id: 21, contactName: 'Prompt Interior', plannedDate: '2026-11-03' });

  beforeEach(() => {
    vi.clearAllMocks();
    api.salesBoard.myWork.mockResolvedValue({
      followUp: [quiet], board: [quiet, fresh], quietDays: 14, visitsThisWeek: visits,
    });
    api.salesVisits.done.mockResolvedValue(visit({ id: 2, status: 'DONE' }));
    api.salesVisits.cancel.mockResolvedValue(visit({ id: 21, status: 'CANCELLED' }));
    api.salesVisits.list.mockImplementation(async ({ from, to }) => [oldVisit, nextWeekVisit]
      .filter((v) => v.plannedDate >= from && v.plannedDate <= to));
  });

  it('a future visit has no tick (M2); an overdue one says เลยกำหนด in text (M4)', async () => {
    renderMyWork();
    const future = await screen.findByTestId('visit-4');
    expect(within(future).queryByRole('button', { name: /เข้าพบแล้ว/ })).toBeNull();
    expect(within(future).getByRole('button', { name: /ยกเลิกนัด/ })).toBeTruthy();
    expect(within(future).queryByText('เลยกำหนด')).toBeNull();

    const overdue = screen.getByTestId('visit-5');
    expect(within(overdue).getByText('เลยกำหนด')).toBeTruthy();
    expect(within(overdue).getByRole('button', { name: /เข้าพบแล้ว/ })).toBeTruthy();
    expect(within(screen.getByTestId('visit-2')).queryByText('เลยกำหนด')).toBeNull();
  });

  it('ค้างจากสัปดาห์ก่อน lists my PLANNED visits before this week, to tick or cancel (H1)', async () => {
    renderMyWork();

    const stale = await screen.findByTestId('visits-carried-over');
    expect(within(stale).getByText('ค้างจากสัปดาห์ก่อน (1)')).toBeTruthy();
    const row = within(stale).getByTestId('visit-21');
    expect(within(row).getByText('Prompt Interior')).toBeTruthy();
    expect(within(row).getByText('เลยกำหนด')).toBeTruthy();
    // The 31 days before this Monday, through the ranged endpoint.
    expect(api.salesVisits.list).toHaveBeenCalledWith({ from: '2026-10-09', to: '2026-11-08' });

    fireEvent.click(within(row).getByRole('button', { name: 'ยกเลิกนัด: Prompt Interior' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'ยกเลิกนัด' }));
    await waitFor(() => expect(api.salesVisits.cancel).toHaveBeenCalledWith(21));
  });

  it('week navigation shows next week through the ranged endpoint, and back (H1)', async () => {
    renderMyWork();
    const section = await screen.findByTestId('visits-this-week');
    expect(await within(section).findByText('สัปดาห์นี้ · 9–15 พ.ย.')).toBeTruthy();

    fireEvent.click(within(section).getByRole('button', { name: 'สัปดาห์หน้า' }));

    expect(await within(section).findByTestId('visit-20')).toBeTruthy();
    expect(within(section).getByText('16–22 พ.ย.')).toBeTruthy();
    expect(api.salesVisits.list).toHaveBeenCalledWith({ from: '2026-11-16', to: '2026-11-22' });
    expect(within(section).queryByTestId('visit-2')).toBeNull();

    fireEvent.click(within(section).getByRole('button', { name: 'สัปดาห์นี้' }));
    expect(await within(section).findByTestId('visit-2')).toBeTruthy();
  });

  it('a visit planned for next week says where it landed, and tapping there goes to that week (H1)', async () => {
    api.salesVisits.create.mockResolvedValue(nextWeekVisit);
    renderMyWork();
    const section = await screen.findByTestId('visits-this-week');
    fireEvent.click(within(section).getByRole('button', { name: /นัดเข้าพบ/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/พบใคร/), { target: { value: 'Orbit Studio' } });
    fireEvent.change(within(dialog).getByLabelText('วันที่'), { target: { value: '17/11/2569' } });
    fireEvent.blur(within(dialog).getByLabelText('วันที่'));
    expect(await within(dialog).findByText('นัดนี้อยู่ในสัปดาห์ 16–22 พ.ย.')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกนัด' }));

    const jump = await within(section).findByRole('button', { name: 'นัดใหม่อยู่ในสัปดาห์ 16–22 พ.ย. — ไปที่สัปดาห์นั้น' });
    fireEvent.click(jump);
    expect(await within(section).findByTestId('visit-20')).toBeTruthy();
  });

  it('the one-step log cannot be sent twice while it is saving (LOW)', async () => {
    let resolveDone;
    api.salesVisits.done.mockReturnValue(new Promise((r) => { resolveDone = r; }));
    renderMyWork();
    await screen.findByTestId('visit-2');
    fireEvent.click(screen.getByTestId('log-visit-header'));
    const dialog = await screen.findByRole('dialog');
    const save = within(dialog).getByRole('button', { name: 'บันทึก' });

    fireEvent.click(save);
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'กำลังบันทึก…' }).disabled).toBe(true));
    fireEvent.click(within(dialog).getByRole('button', { name: 'กำลังบันทึก…' }));
    expect(api.salesVisits.done).toHaveBeenCalledTimes(1);
    resolveDone(visit({ id: 2, status: 'DONE' }));
  });

  it('the one-step log is a bottom sheet on a phone (LOW)', async () => {
    renderMyWork();
    await screen.findByTestId('visit-2');
    fireEvent.click(screen.getByTestId('log-visit-sticky'));

    expect((await screen.findByRole('dialog')).getAttribute('data-placement')).toBe('sheet-on-phone');
  });
});
