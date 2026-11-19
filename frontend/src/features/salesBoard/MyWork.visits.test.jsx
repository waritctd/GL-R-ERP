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
  visit({ id: 2, contactName: 'Linea Interior', plannedTime: '14:00:00', ticketId: 31, dealFollowUpOpen: true,
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

  // Slice 6.4: ตามต่อ. Unused, the requests are byte-for-byte what they were (no nextFollowUpAt key at all).
  it('ticking a visit on a deal with ตามต่อ sends the deal\'s next follow-up date with the tick', async () => {
    renderMyWork();
    const row = await screen.findByTestId('visit-2');
    fireEvent.click(within(row).getByRole('button', { name: 'เข้าพบแล้ว: Linea Interior' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(within(dialog).getByRole('radiogroup', { name: 'ตามต่อ' })).getByRole('radio', { name: '1 สัปดาห์' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.salesVisits.done).toHaveBeenCalledTimes(1));
    expect(api.salesVisits.done.mock.calls[0]).toStrictEqual([2, { note: undefined, nextFollowUpAt: '2026-11-19' }]);
  });

  it('without ตามต่อ the tick and the one-step log send exactly the requests they sent before', async () => {
    renderMyWork();
    await screen.findByTestId('visit-2');
    fireEvent.click(screen.getByTestId('log-visit-header'));
    let dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ขอใบเสนอราคา' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.salesVisits.done).toHaveBeenCalledTimes(1));
    expect(api.salesVisits.done.mock.calls[0]).toStrictEqual([2, { note: 'ขอใบเสนอราคา' }]);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    fireEvent.click(screen.getByTestId('log-visit-header'));
    dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ไม่ได้นัด' }));
    fireEvent.change(within(dialog).getByLabelText(/พบใคร/), { target: { value: 'Walk-in' } });
    fireEvent.change(within(dialog).getByLabelText(/^งาน/), { target: { value: '31' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.salesVisits.create).toHaveBeenCalledTimes(1));
    expect(api.salesVisits.create.mock.calls[0][0]).toStrictEqual({
      contactName: 'Walk-in', ticketId: 31, plannedDate: today, note: undefined, done: true,
    });
  });
});
