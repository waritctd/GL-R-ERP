import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { MyWork } from './MyWork.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// A fixed Thursday, 12 Nov 2026 10:00 Bangkok: this week is Mon 9 – Sun 15 Nov.
vi.setSystemTime(new Date('2026-11-12T03:00:00Z'));
afterAll(() => vi.useRealTimers());

// The phone screen follows the app's `mobile` breakpoint (useIsMobile: max-width 720px). The mock is a live store so
// a test can cross the breakpoint mid-render (a phone rotated to landscape), as matchMedia's change event would.
const viewport = vi.hoisted(() => {
  const listeners = new Set();
  return {
    mobile: true,
    listeners,
    set(value) {
      this.mobile = value;
      listeners.forEach((notify) => notify());
    },
  };
});
vi.mock('../../hooks/useIsMobile.js', async () => {
  const { useSyncExternalStore } = await import('react');
  const subscribe = (notify) => {
    viewport.listeners.add(notify);
    return () => viewport.listeners.delete(notify);
  };
  return {
    useIsMobile: () => useSyncExternalStore(subscribe, () => viewport.mobile),
    useMediaQuery: () => false,
  };
});

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      salesBoard: { myWork: vi.fn() },
      salesVisits: { list: vi.fn(), create: vi.fn(), done: vi.fn(), cancel: vi.fn() },
      tickets: { addActivity: vi.fn(), markLost: vi.fn(), create: vi.fn(), resume: vi.fn() },
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
    },
  };
});

vi.mock('../tickets/TicketCreateModal.jsx', () => ({
  TicketCreateModal: ({ onSubmit }) => (
    <div role="dialog">
      สร้างงานใหม่ (stub)
      <button type="button" onClick={() => onSubmit({ title: 'x' })}>ส่งสร้าง (stub)</button>
    </div>
  ),
}));

const TODAY = '2026-11-12';
const quiet = {
  ticketId: 31, title: 'สยามพารากอน · ห้องน้ำชั้น 3', customerName: 'สยามพิวรรธน์', projectName: null, ownerId: 6,
  ownerName: 'คุณสมหมาย', salesStage: 'QUOTE_DESIGN_SIDE', repCode: 'S3', boardColumn: 3, value: 1240000,
  designerName: 'เทพสตูดิโอ ดีไซน์', winProbability: 40, expectedQuarter: null,
  lastTouchedAt: '2026-10-16T00:00:00Z', daysQuiet: 27, quiet: true, currency: 'THB', valueExpired: false,
  lastActivityKind: null, lastActivityNote: null,
};
const fresh = { ...quiet, ticketId: 32, title: 'BMW Showroom เชียงใหม่', daysQuiet: 2, quiet: false };

const visit = (over) => ({
  id: 1, ownerId: 6, ownerName: 'คุณสมหมาย', ticketId: null, ticketTitle: null, contactName: 'ลูกค้า',
  plannedDate: TODAY, plannedTime: null, status: 'PLANNED', doneAt: null, note: null, ...over,
});
const visits = [
  visit({ id: 1, contactName: 'อรุณ อาร์คิเทค', plannedDate: '2026-11-10', status: 'DONE' }),
  visit({ id: 2, contactName: 'Linea Interior', plannedTime: '14:00:00', ticketId: 31,
    ticketTitle: 'สยามพารากอน · ห้องน้ำชั้น 3', note: 'ตามใบเสนอราคา' }),
  visit({ id: 4, contactName: 'ก้าวหน้า คอนสตรัคชั่น', plannedDate: '2026-11-13', plannedTime: '10:00:00' }),
];

const week = (weekStart, over = {}) => ({
  weekStart, deliveredAwaiting: 0, deliveriesScheduled: 0, depositsExpected: 0, received: 0, ...over,
});
const cash = {
  month: '2026-11', forecast: 1301000, received: 121150, deliveredAwaiting: 45000, deliveriesScheduled: 415000,
  depositsExpected: 352000,
  weeks: [
    week('2026-11-02', { deliveredAwaiting: 9000 }),
    week('2026-11-09', { deliveredAwaiting: 45000, deliveriesScheduled: 415000, depositsExpected: 85000 }),
    week('2026-11-16', { depositsExpected: 267000 }),
  ],
};

const sales = { id: 6, role: 'sales', employeeId: 6, name: 'คุณสมหมาย ขายดี' };

function workWith(over = {}) {
  return { followUp: [quiet], board: [quiet, fresh], quietDays: 14, visitsThisWeek: visits, cash, ...over };
}

function renderMyWork(props = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><MyWork user={sales} showToast={vi.fn()} {...props} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

/** The phone screen's own text, without any dialog a test opened. */
function ownText(node) {
  const clone = node.cloneNode(true);
  clone.querySelectorAll('[role="dialog"]').forEach((d) => d.remove());
  return clone.textContent;
}

describe('MyWork on a phone — วันนี้ของฉัน', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    viewport.mobile = true;
    try { sessionStorage.clear(); } catch { /* no storage */ }
    api.salesBoard.myWork.mockResolvedValue(workWith());
    api.salesVisits.list.mockResolvedValue([]);
    api.salesVisits.done.mockResolvedValue(visit({ id: 2, status: 'DONE' }));
    api.tickets.addActivity.mockResolvedValue({});
    api.tickets.create.mockResolvedValue({ ticket: { summary: { id: 77 } } });
  });

  it('shows the drawn phone screen instead of the stacked page', async () => {
    renderMyWork({ before: <section>แถบตัวเลข</section> });

    const today = await screen.findByTestId('today-phone');
    await within(today).findByTestId('today-next-visit');
    expect(within(today).getByRole('heading', { level: 1, name: 'วันนี้ของฉัน' })).toBeTruthy();
    expect(within(today).getByText('พฤหัสบดี 12 พ.ย.')).toBeTruthy();
    // The stacked desktop page is not rendered under it.
    expect(screen.queryByText('งานทั้งหมดของฉัน')).toBeNull();
    expect(screen.queryByTestId('visits-this-week')).toBeNull();
    expect(screen.queryByText('แถบตัวเลข')).toBeNull();
    expect(ownText(today)).not.toMatch(/ดีล/);
  });

  it('has exactly one บันทึกเข้าพบ, which opens the log sheet on today\'s visit', async () => {
    renderMyWork();
    await screen.findByTestId('today-next-visit');

    const buttons = screen.getAllByRole('button', { name: /บันทึกเข้าพบ/ });
    expect(buttons).toHaveLength(1);
    // The one button carries the old sticky button's id (the real-browser suite opens the sheet through it).
    expect(screen.getAllByTestId('log-visit-sticky')).toEqual(buttons);
    expect(within(screen.getByTestId('today-phone')).getByTestId('log-visit-sticky')).toBe(buttons[0]);
    expect(screen.queryByTestId('log-visit-header')).toBeNull();

    fireEvent.click(buttons[0]);
    const dialog = await screen.findByRole('dialog');
    expect(dialog.getAttribute('data-placement')).toBe('sheet-on-phone');
    expect(within(dialog).getByRole('radio', { name: /Linea Interior · 14:00/ }).getAttribute('aria-checked')).toBe('true');
  });

  describe('นัดถัดไป', () => {
    it('is the next unticked visit today, with who and what', async () => {
      renderMyWork();
      const card = await screen.findByTestId('today-next-visit');
      expect(within(card).getByText('นัดถัดไป · 14:00')).toBeTruthy();
      expect(within(card).getByText('Linea Interior')).toBeTruthy();
      expect(within(card).getByText('ตามใบเสนอราคา · สยามพารากอน · ห้องน้ำชั้น 3')).toBeTruthy();
    });

    it('falls back to the next visit later this week when today has none left', async () => {
      api.salesBoard.myWork.mockResolvedValue(workWith({ visitsThisWeek: [visits[0], visits[2]] }));
      renderMyWork();
      const card = await screen.findByTestId('today-next-visit');
      expect(within(card).getByText('นัดถัดไป · ศ. 13 · 10:00')).toBeTruthy();
      expect(within(card).getByText('ก้าวหน้า คอนสตรัคชั่น')).toBeTruthy();
    });

    // Ruling 5: the card looks 7 days ahead across the week boundary — GET /api/sales/visits from today to today + 7
    // (8 days inclusive, inside the endpoint's 31-day cap).
    it('looks into next week when nothing is left this week', async () => {
      api.salesBoard.myWork.mockResolvedValue(workWith({ visitsThisWeek: [visits[0]] }));
      api.salesVisits.list.mockImplementation(({ from }) => Promise.resolve(from === TODAY
        ? [visit({ id: 30, contactName: 'Orbit Studio', plannedDate: '2026-11-16', plannedTime: '09:00:00' })]
        : []));
      renderMyWork();

      const card = await screen.findByTestId('today-next-visit');
      expect(await within(card).findByText('นัดถัดไป · จ. 16 · 09:00')).toBeTruthy();
      expect(within(card).getByText('Orbit Studio')).toBeTruthy();
      expect(api.salesVisits.list).toHaveBeenCalledWith({ from: '2026-11-12', to: '2026-11-19' });
    });

    it('says so when nothing is planned in the next 7 days, and offers to plan one (in งาน wording)', async () => {
      api.salesBoard.myWork.mockResolvedValue(workWith({ visitsThisWeek: [visits[0]] }));
      renderMyWork();
      const card = await screen.findByTestId('today-next-visit');
      expect(await within(card).findByText('ไม่มีนัดใน 7 วันข้างหน้า')).toBeTruthy();

      fireEvent.click(within(card).getByRole('button', { name: 'นัดเข้าพบ' }));
      const dialog = await screen.findByRole('dialog');
      expect(within(dialog).getByRole('heading', { name: 'นัดเข้าพบ' })).toBeTruthy();
      expect(within(dialog).getByLabelText(/งาน/)).toBeTruthy();
      expect(dialog.textContent).not.toMatch(/ดีล/);
    });
  });

  // Ruling 1: a missed visit (PLANNED, its day passed) never vanishes from the phone — this week's and the ones carried
  // over from the 31 days before this Monday (the query MyWork already makes), each tickable through the tick dialog.
  describe('ค้าง — missed visits', () => {
    const overdueThisWeek = visit({ id: 41, contactName: 'บ้านสวน ดีไซน์', plannedDate: '2026-11-10' });
    const carried = visit({ id: 42, contactName: 'PIA Interior', plannedDate: '2026-11-05', ticketId: 31,
      ticketTitle: 'สยามพารากอน · ห้องน้ำชั้น 3' });

    beforeEach(() => {
      api.salesBoard.myWork.mockResolvedValue(workWith({ visitsThisWeek: [visits[0], overdueThisWeek] }));
      api.salesVisits.list.mockImplementation(({ from }) => Promise.resolve(from === '2026-10-09' ? [carried] : []));
      api.salesVisits.done.mockResolvedValue({ ...carried, status: 'DONE' });
    });

    it('lists this week\'s and carried-over unticked visits, each tickable', async () => {
      renderMyWork();
      const missed = await screen.findByTestId('today-missed');
      expect(await within(missed).findByText('ค้าง 2 นัด')).toBeTruthy();
      expect(within(missed).getByTestId('visit-42')).toBeTruthy();
      expect(within(missed).getByTestId('visit-41')).toBeTruthy();

      fireEvent.click(within(missed).getByRole('button', { name: 'เข้าพบแล้ว: PIA Interior' }));
      const dialog = await screen.findByRole('dialog');
      expect(within(dialog).getByRole('heading', { name: 'เข้าพบแล้ว' })).toBeTruthy();
      expect(dialog.textContent).not.toMatch(/ดีล/);
      fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
      await waitFor(() => expect(api.salesVisits.done).toHaveBeenCalledWith(42, { note: undefined }));
    });

    it('never claims there is nothing left while a visit is missed', async () => {
      renderMyWork();
      const card = await screen.findByTestId('today-next-visit');
      await screen.findByText('ค้าง 2 นัด');
      expect(within(card).queryByText('ไม่มีนัดใน 7 วันข้างหน้า')).toBeNull();
      expect(within(card).getByText('ไม่มีนัดใหม่ใน 7 วันข้างหน้า · ค้าง 2 นัดด้านล่าง')).toBeTruthy();
    });

    it('shows no ค้าง block when nothing is missed', async () => {
      api.salesBoard.myWork.mockResolvedValue(workWith());
      api.salesVisits.list.mockResolvedValue([]);
      renderMyWork();
      await screen.findByText('นัดถัดไป · 14:00');
      expect(screen.queryByTestId('today-missed')).toBeNull();
    });
  });

  // Ruling 2: บันทึกเข้าพบ and the quick row do not depend on /my-work — a failed load shows the error and a retry
  // beside them, as the old sticky button was always there.
  it('keeps บันทึกเข้าพบ and the quick row when /my-work fails, with a retry', async () => {
    api.salesBoard.myWork.mockRejectedValue(new Error('down'));
    renderMyWork();

    expect(await screen.findByText('โหลดไม่สำเร็จ')).toBeTruthy();
    const log = screen.getByRole('button', { name: 'บันทึกเข้าพบ' });
    expect(within(screen.getByTestId('today-quick-actions')).getByRole('link', { name: 'ทำใบเสนอราคา' })).toBeTruthy();

    api.salesBoard.myWork.mockResolvedValue(workWith());
    fireEvent.click(screen.getByRole('button', { name: 'ลองใหม่' }));
    await waitFor(() => expect(api.salesBoard.myWork).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('นัดถัดไป · 14:00')).toBeTruthy();

    fireEvent.click(log);
    expect(within(await screen.findByRole('dialog')).getByRole('heading', { name: 'บันทึกเข้าพบ' })).toBeTruthy();
  });

  it('logs from a failed load too — the sheet opens with ไม่ได้นัด', async () => {
    api.salesBoard.myWork.mockRejectedValue(new Error('down'));
    renderMyWork();
    await screen.findByText('โหลดไม่สำเร็จ');

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกเข้าพบ' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('radio', { name: 'ไม่ได้นัด' }).getAttribute('aria-checked')).toBe('true');
    expect(dialog.textContent).not.toMatch(/ดีล/);
  });

  // Ruling 3: rotating past 720px swaps the screen, but the call picker / call dialog and what was typed survive.
  describe('crossing the breakpoint', () => {
    it('keeps an open call dialog, its outcome and its note', async () => {
      renderMyWork();
      fireEvent.click(await within(await screen.findByTestId('today-quick-actions')).findByRole('button', { name: 'โทรแล้ว' }));
      fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: /สยามพารากอน/ }));
      let call = await screen.findByRole('dialog');
      fireEvent.click(within(call).getByRole('radio', { name: 'ไม่รับสาย' }));
      fireEvent.change(within(call).getByLabelText(/โน้ต/), { target: { value: 'โทรอีกทีบ่ายนี้' } });

      act(() => viewport.set(false));

      expect(await screen.findByText('งานทั้งหมดของฉัน')).toBeTruthy();
      call = screen.getByRole('dialog');
      expect(within(call).getByRole('heading', { name: 'โทรแล้ว' })).toBeTruthy();
      expect(within(call).getByRole('radio', { name: 'ไม่รับสาย' }).getAttribute('aria-checked')).toBe('true');
      expect(within(call).getByLabelText(/โน้ต/).value).toBe('โทรอีกทีบ่ายนี้');
    });

    it('keeps an open call picker', async () => {
      renderMyWork();
      fireEvent.click(await within(await screen.findByTestId('today-quick-actions')).findByRole('button', { name: 'โทรแล้ว' }));
      await screen.findByRole('heading', { name: 'โทรหางานไหน' });

      act(() => viewport.set(false));

      expect(await screen.findByText('งานทั้งหมดของฉัน')).toBeTruthy();
      expect(screen.getByRole('heading', { name: 'โทรหางานไหน' })).toBeTruthy();
    });
  });

  // Ruling 4: "ดูหน้าเต็ม" is remembered for the browser session; the way back is as visible as the way there.
  describe('ดูหน้าเต็ม is remembered for the session', () => {
    it('reopens on the full page until the rep goes back', async () => {
      const first = renderMyWork();
      fireEvent.click(within(await screen.findByTestId('today-phone')).getByRole('button', { name: 'ดูหน้าเต็ม' }));
      await screen.findByText('งานทั้งหมดของฉัน');
      first.unmount();

      const second = renderMyWork();
      expect(await screen.findByText('งานทั้งหมดของฉัน')).toBeTruthy();
      expect(screen.queryByTestId('today-phone')).toBeNull();
      const back = screen.getByRole('button', { name: 'กลับไปหน้าวันนี้' });
      // As visible as ดูหน้าเต็ม: a full-width 52px button, not a small link.
      expect(back.className).toContain('w-full');
      expect(back.className).toContain('min-h-[52px]');
      fireEvent.click(back);
      expect(await screen.findByTestId('today-phone')).toBeTruthy();
      second.unmount();

      renderMyWork();
      expect(await screen.findByTestId('today-phone')).toBeTruthy();
    });

    it('still works when the browser refuses storage', async () => {
      // A stand-in rather than a spy: this Node ships its own Web Storage, which can shadow jsdom's Storage.prototype.
      const denied = () => { throw new Error('denied'); };
      const refusing = { getItem: vi.fn(denied), setItem: vi.fn(denied), removeItem: vi.fn(denied) };
      vi.stubGlobal('sessionStorage', refusing);
      try {
        renderMyWork();
        fireEvent.click(within(await screen.findByTestId('today-phone')).getByRole('button', { name: 'ดูหน้าเต็ม' }));
        expect(await screen.findByText('งานทั้งหมดของฉัน')).toBeTruthy();
        fireEvent.click(screen.getByRole('button', { name: 'กลับไปหน้าวันนี้' }));
        expect(await screen.findByTestId('today-phone')).toBeTruthy();
        // The refusing storage was really consulted, so this is not passing on an unread stand-in.
        expect(refusing.getItem).toHaveBeenCalled();
        expect(refusing.setItem).toHaveBeenCalled();
      } finally {
        vi.unstubAllGlobals();
      }
    });
  });

  describe('quick actions', () => {
    // Slice 6d: ส่งตัวอย่าง is recorded now (deal activity SAMPLE_SENT, V223), so the drawn tile is real — before 6d
    // this test asserted its absence because nothing could record a sample.
    it('offers โทรแล้ว, ส่งตัวอย่าง and ทำใบเสนอราคา to a rep, in the drawn order', async () => {
      renderMyWork();
      const row = await screen.findByTestId('today-quick-actions');
      expect(await within(row).findByRole('button', { name: 'โทรแล้ว' })).toBeTruthy();
      expect(within(row).getByRole('button', { name: 'ส่งตัวอย่าง' })).toBeTruthy();
      expect(within(row).getByRole('link', { name: 'ทำใบเสนอราคา' }).getAttribute('href')).toBe('/quotations/new');
      expect([...row.children].map((tile) => tile.textContent)).toEqual(['โทรแล้ว', 'ส่งตัวอย่าง', 'ทำใบเสนอราคา']);
    });

    it('hides ทำใบเสนอราคา from someone the create-quotation entry points refuse', async () => {
      renderMyWork({ user: { id: 1, role: 'ceo', employeeId: 1, name: 'ซีอีโอ' } });
      const row = await screen.findByTestId('today-quick-actions');
      expect(await within(row).findByRole('button', { name: 'โทรแล้ว' })).toBeTruthy();
      expect(within(row).queryByRole('link', { name: 'ทำใบเสนอราคา' })).toBeNull();
    });

    it('shows a grantee ทำใบเสนอราคา even outside the sales roles', async () => {
      renderMyWork({ user: { id: 3, role: 'qc', employeeId: 3, name: 'คิวซี', canCreateQuotation: true } });
      const row = await screen.findByTestId('today-quick-actions');
      expect(within(row).getByRole('link', { name: 'ทำใบเสนอราคา' })).toBeTruthy();
    });

    it('hides โทรแล้ว and ส่งตัวอย่าง when the rep has no open งาน to call about or send to', async () => {
      api.salesBoard.myWork.mockResolvedValue(workWith({ followUp: [], board: [] }));
      renderMyWork();
      // Wait for the payload: the row itself renders before /my-work answers (ruling 2).
      await screen.findByTestId('today-follow-up');
      const row = screen.getByTestId('today-quick-actions');
      expect(within(row).queryByRole('button', { name: 'โทรแล้ว' })).toBeNull();
      expect(within(row).queryByRole('button', { name: 'ส่งตัวอย่าง' })).toBeNull();
    });

    describe('ส่งตัวอย่าง (slice 6d)', () => {
      const openSample = async () => {
        fireEvent.click(await within(await screen.findByTestId('today-quick-actions')).findByRole('button', { name: 'ส่งตัวอย่าง' }));
        return within(await screen.findByRole('dialog', { name: 'ส่งตัวอย่าง' }));
      };

      it('one sheet: pick the งาน, say what was sent, follow up when — recorded as a SAMPLE_SENT activity today', async () => {
        renderMyWork();
        const sheet = await openSample();
        fireEvent.change(sheet.getByLabelText(/^งาน/), { target: { value: '31' } });
        fireEvent.change(sheet.getByLabelText(/^ส่งอะไร/), { target: { value: 'ตัวอย่าง Raku สีเทา 3 แผ่น' } });
        fireEvent.click(sheet.getByRole('radio', { name: '1 สัปดาห์' }));
        fireEvent.click(sheet.getByRole('button', { name: 'บันทึก' }));

        await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledWith(31, {
          kind: 'SAMPLE_SENT', activityDate: TODAY, note: 'ตัวอย่าง Raku สีเทา 3 แผ่น', nextFollowUpAt: '2026-11-19',
        }));
        await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      });

      it('without a follow-up date the request carries no nextFollowUpAt key at all', async () => {
        renderMyWork();
        const sheet = await openSample();
        fireEvent.change(sheet.getByLabelText(/^งาน/), { target: { value: '32' } });
        fireEvent.change(sheet.getByLabelText(/^ส่งอะไร/), { target: { value: 'แคตตาล็อก' } });
        fireEvent.click(sheet.getByRole('button', { name: 'บันทึก' }));
        await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledTimes(1));
        expect(api.tickets.addActivity.mock.calls[0]).toStrictEqual([32, {
          kind: 'SAMPLE_SENT', activityDate: TODAY, note: 'แคตตาล็อก',
        }]);
      });

      it('a refusal is toasted and the sheet stays open with what was typed', async () => {
        const showToast = vi.fn();
        api.tickets.addActivity.mockRejectedValue(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));
        renderMyWork({ showToast });
        const sheet = await openSample();
        fireEvent.change(sheet.getByLabelText(/^งาน/), { target: { value: '31' } });
        fireEvent.change(sheet.getByLabelText(/^ส่งอะไร/), { target: { value: 'ตัวอย่างไม้' } });
        fireEvent.click(sheet.getByRole('button', { name: 'บันทึก' }));
        await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ไม่มีสิทธิ์เข้าถึงรายการนี้'));
        expect(screen.getByRole('dialog', { name: 'ส่งตัวอย่าง' })).toBeTruthy();
        expect(sheet.getByLabelText(/^ส่งอะไร/).value).toBe('ตัวอย่างไม้');
      });

      it('survives the switch to the full page with what was typed (the sheet is owned by MyWork)', async () => {
        renderMyWork();
        const sheet = await openSample();
        fireEvent.change(sheet.getByLabelText(/^ส่งอะไร/), { target: { value: 'ตัวอย่างหิน' } });
        act(() => viewport.set(false));
        try {
          expect(screen.getByRole('dialog', { name: 'ส่งตัวอย่าง' })).toBeTruthy();
          expect(screen.getByLabelText(/^ส่งอะไร/).value).toBe('ตัวอย่างหิน');
        } finally {
          act(() => viewport.set(true));
        }
      });
    });

    it('โทรแล้ว picks a งาน — ควรตามต่อ first — then logs the call through the existing dialog', async () => {
      renderMyWork();
      fireEvent.click(await within(await screen.findByTestId('today-quick-actions')).findByRole('button', { name: 'โทรแล้ว' }));

      const picker = await screen.findByRole('dialog');
      expect(within(picker).getByRole('heading', { name: 'โทรหางานไหน' })).toBeTruthy();
      expect(picker.textContent).not.toMatch(/ดีล/);
      const options = within(picker).getAllByRole('button', { name: /สยามพารากอน|BMW/ });
      expect(options.map((o) => o.textContent)).toEqual([
        expect.stringContaining('สยามพารากอน'), expect.stringContaining('BMW Showroom'),
      ]);
      fireEvent.click(options[0]);

      const call = await screen.findByRole('dialog');
      expect(within(call).getByRole('heading', { name: 'โทรแล้ว' })).toBeTruthy();
      expect(within(call).getByText('สยามพารากอน · ห้องน้ำชั้น 3 · เงียบ 27 วัน')).toBeTruthy();
      expect(call.textContent).not.toMatch(/ดีล/);
      fireEvent.click(within(call).getByRole('radio', { name: 'คุยแล้ว ยังสนใจ' }));
      fireEvent.click(within(call).getByRole('button', { name: 'บันทึก' }));

      await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledWith(31, {
        kind: 'CALL', activityDate: TODAY, note: 'คุยแล้ว ยังสนใจ',
      }));
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    });

    // Ruling 6: "เงียบ N วัน" only for a งาน the quiet rule marks quiet (BoardDeal.quiet), not every picked one.
    it('does not call a fresh งาน quiet in the call dialog', async () => {
      renderMyWork();
      fireEvent.click(await within(await screen.findByTestId('today-quick-actions')).findByRole('button', { name: 'โทรแล้ว' }));
      fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: /BMW Showroom/ }));

      const call = await screen.findByRole('dialog');
      expect(within(call).getByText('BMW Showroom เชียงใหม่')).toBeTruthy();
      expect(call.textContent).not.toMatch(/เงียบ/);
    });
  });

  it('lists ควรตามต่อ compactly with the quiet-days chip, each opening its งาน', async () => {
    renderMyWork();
    const list = await screen.findByTestId('today-follow-up');
    expect(within(list).getByText('ควรตามต่อ (1)')).toBeTruthy();
    const link = within(list).getByRole('link', { name: /สยามพารากอน · ห้องน้ำชั้น 3/ });
    expect(link.getAttribute('href')).toBe('/tickets/31');
    expect(within(link).getByText('27 วัน')).toBeTruthy();
    expect(within(list).queryByText('BMW Showroom เชียงใหม่')).toBeNull();
  });

  // V220 on the phone (review wn3 P1): a paused deal whose วันที่จะกลับมาดู has arrived is listed as on the desktop
  // ควรตามต่อ (FollowUpList) — its return label and ดำเนินการต่อ — never as a quiet "n วัน" deal.
  describe('a returning paused deal in ควรตามต่อ', () => {
    const back = {
      ...quiet, ticketId: 40, title: 'งานที่พักไว้', quiet: false, returning: true, pauseReturnOn: '2026-11-12', daysQuiet: 61,
    };

    it('shows the return label, not a quiet-days chip', async () => {
      api.salesBoard.myWork.mockResolvedValue(workWith({ followUp: [back, quiet] }));
      renderMyWork();
      const row = await screen.findByTestId('today-follow-up-40');
      expect(row.textContent).toContain('พักไว้ · ถึงวันกลับมาดู 12 พ.ย. 2569');
      expect(row.textContent).not.toContain('61 วัน');
      expect(within(row).getByRole('link', { name: /งานที่พักไว้/ }).getAttribute('href')).toBe('/tickets/40');
      // The quiet row beside it keeps its chip and has no resume.
      const quietRow = screen.getByTestId('today-follow-up-31');
      expect(quietRow.textContent).toContain('27 วัน');
      expect(within(quietRow).queryByRole('button', { name: 'ดำเนินการต่อ' })).toBeNull();
    });

    it('resumes from the phone with the desktop request, and the row leaves the list', async () => {
      api.salesBoard.myWork.mockResolvedValue(workWith({ followUp: [back, quiet] }));
      api.tickets.resume.mockResolvedValue({});
      renderMyWork();
      const row = await screen.findByTestId('today-follow-up-40');
      const resume = within(row).getByRole('button', { name: 'ดำเนินการต่อ' });
      // A thumb-sized tap target (≥44px), as every other phone control here.
      expect(resume.className).toMatch(/min-h-11|min-h-\[44px\]|min-h-\[52px\]/);

      api.salesBoard.myWork.mockResolvedValue(workWith({ followUp: [quiet] }));
      fireEvent.click(resume);

      await waitFor(() => expect(api.tickets.resume).toHaveBeenCalledWith(40, {}));
      await waitFor(() => expect(screen.queryByTestId('today-follow-up-40')).toBeNull());
      expect(screen.getByText('ควรตามต่อ (1)')).toBeTruthy();
    });
  });

  describe('เงินเข้าเดือนนี้', () => {
    it('shows received, from expected, and the next week with money due', async () => {
      renderMyWork();
      const card = await screen.findByTestId('today-cash');
      expect(within(card).getByRole('heading', { name: 'เงินเข้าเดือนนี้' })).toBeTruthy();
      expect(within(card).getByText('฿121,150')).toBeTruthy();
      // As drawn: "รอบถัดไป 13 พ.ย." — the week's date, no "ถึง".
      expect(within(card).getByText('จากที่คาด ฿1,301,000 · รอบถัดไป 13 พ.ย.')).toBeTruthy();
    });

    it('skips a week with nothing due when naming the next one', async () => {
      api.salesBoard.myWork.mockResolvedValue(workWith({
        // Not the month's last week, which CashCard labels through the month's end (weekEndLabel's isLast).
        cash: { ...cash, weeks: [cash.weeks[0], week('2026-11-09'), cash.weeks[2], week('2026-11-23', { depositsExpected: 1 })] },
      }));
      renderMyWork();
      const card = await screen.findByTestId('today-cash');
      expect(within(card).getByText('จากที่คาด ฿1,301,000 · รอบถัดไป 20 พ.ย.')).toBeTruthy();
    });

    it('leaves the next due out when nothing more is due this month', async () => {
      api.salesBoard.myWork.mockResolvedValue(workWith({ cash: { ...cash, weeks: [cash.weeks[0]] } }));
      renderMyWork();
      const card = await screen.findByTestId('today-cash');
      expect(within(card).getByText('จากที่คาด ฿1,301,000')).toBeTruthy();
    });

    it('is absent when the payload carries no cash', async () => {
      api.salesBoard.myWork.mockResolvedValue(workWith({ cash: null }));
      renderMyWork();
      await screen.findByTestId('today-follow-up');
      expect(screen.queryByTestId('today-cash')).toBeNull();
      expect(screen.queryByText('เงินเข้าเดือนนี้')).toBeNull();
    });
  });

  it('keeps everything else reachable: all งาน, the visits page, a new งาน, and the full page', async () => {
    renderMyWork({ before: <section>แถบตัวเลข</section> });
    const today = await screen.findByTestId('today-phone');

    expect(within(today).getByRole('link', { name: 'ดูงานทั้งหมด' }).getAttribute('href')).toBe('/tickets');
    expect(within(today).getByRole('link', { name: 'นัดเข้าพบทั้งสัปดาห์' }).getAttribute('href')).toBe('/visits');
    fireEvent.click(within(today).getByRole('button', { name: 'งานใหม่' }));
    expect(await screen.findByText('สร้างงานใหม่ (stub)')).toBeTruthy();

    fireEvent.click(within(today).getByRole('button', { name: 'ดูหน้าเต็ม' }));
    expect(await screen.findByText('งานทั้งหมดของฉัน')).toBeTruthy();
    expect(screen.getByText('แถบตัวเลข')).toBeTruthy();
    expect(screen.queryByTestId('today-phone')).toBeNull();
    // The full page keeps its own sticky บันทึกเข้าพบ — still only one on screen.
    expect(screen.getAllByRole('button', { name: /บันทึกเข้าพบ/ }).filter((b) => b.dataset.testid !== 'log-visit-header'))
      .toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: 'กลับไปหน้าวันนี้' }));
    expect(await screen.findByTestId('today-phone')).toBeTruthy();
  });

  it('says งาน when a new งาน made from the phone is saved', async () => {
    const showToast = vi.fn();
    renderMyWork({ showToast });
    fireEvent.click(within(await screen.findByTestId('today-phone')).getByRole('button', { name: 'งานใหม่' }));
    fireEvent.click(await screen.findByRole('button', { name: 'ส่งสร้าง (stub)' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'สร้างงานเรียบร้อย'));
  });

  it('pads the bottom for the home indicator (env(safe-area-inset-bottom))', async () => {
    renderMyWork();
    const today = await screen.findByTestId('today-phone');
    expect(today.className).toContain('env(safe-area-inset-bottom)');
  });
});

describe('MyWork at desktop and tablet width — unchanged', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    viewport.mobile = false;
    api.salesBoard.myWork.mockResolvedValue(workWith());
    api.salesVisits.list.mockResolvedValue([]);
  });

  it('renders the stacked page, not the phone screen', async () => {
    renderMyWork();
    expect(await screen.findByText('งานทั้งหมดของฉัน')).toBeTruthy();
    expect(screen.getByTestId('visits-this-week')).toBeTruthy();
    expect(screen.getByTestId('log-visit-header')).toBeTruthy();
    expect(screen.queryByTestId('today-phone')).toBeNull();
    expect(screen.queryByText('วันนี้ของฉัน')).toBeNull();
    expect(screen.queryByRole('button', { name: 'กลับไปหน้าวันนี้' })).toBeNull();
  });
});
