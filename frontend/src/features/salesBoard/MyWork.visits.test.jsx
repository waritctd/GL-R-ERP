import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MyWork } from './MyWork.jsx';
import { api } from '../../api/index.js';
import { bangkokTodayIso } from '../../utils/format.js';
import { weekDays } from './visitWeek.js';

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

const today = bangkokTodayIso();
const week = weekDays(today);
// A weekday other than today, inside this week, for the non-today rows.
const otherDay = week.find((d) => !d.isToday && d.short !== 'ส.' && d.short !== 'อา.') ?? week[0];

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
  });

  it('lists this week with day, contact, time and deal, and counts done of booked', async () => {
    renderMyWork();

    const section = await screen.findByTestId('visits-this-week');
    expect(within(section).getByText('นัดเข้าพบสัปดาห์นี้')).toBeTruthy();
    expect(await within(section).findByText('1 จาก 2 ไปแล้ว')).toBeTruthy();

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
