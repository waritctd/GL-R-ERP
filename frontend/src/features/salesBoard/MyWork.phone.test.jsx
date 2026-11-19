import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

  describe('quick actions', () => {
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
  });
});
