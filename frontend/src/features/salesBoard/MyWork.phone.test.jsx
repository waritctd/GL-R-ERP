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

// The phone screen follows the app's `mobile` breakpoint (useIsMobile: max-width 720px).
const viewport = vi.hoisted(() => ({ mobile: true }));
vi.mock('../../hooks/useIsMobile.js', () => ({
  useIsMobile: () => viewport.mobile,
  useMediaQuery: () => false,
}));

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
  TicketCreateModal: () => <div role="dialog">สร้างงานใหม่ (stub)</div>,
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
    api.salesBoard.myWork.mockResolvedValue(workWith());
    api.salesVisits.list.mockResolvedValue([]);
    api.salesVisits.done.mockResolvedValue(visit({ id: 2, status: 'DONE' }));
    api.tickets.addActivity.mockResolvedValue({});
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
    expect(screen.queryByTestId('log-visit-sticky')).toBeNull();
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

    it('says so when nothing is planned for the rest of the week, and offers to plan one', async () => {
      api.salesBoard.myWork.mockResolvedValue(workWith({ visitsThisWeek: [visits[0]] }));
      renderMyWork();
      const card = await screen.findByTestId('today-next-visit');
      expect(within(card).getByText('ไม่มีนัดที่เหลือสัปดาห์นี้')).toBeTruthy();

      fireEvent.click(within(card).getByRole('button', { name: 'นัดเข้าพบ' }));
      const dialog = await screen.findByRole('dialog');
      expect(within(dialog).getByRole('heading', { name: 'นัดเข้าพบ' })).toBeTruthy();
    });
  });

  describe('quick actions', () => {
    it('offers โทรแล้ว and ทำใบเสนอราคา to a rep, and no ส่งตัวอย่าง (nothing records a sample)', async () => {
      renderMyWork();
      const row = await screen.findByTestId('today-quick-actions');
      expect(within(row).getByRole('button', { name: 'โทรแล้ว' })).toBeTruthy();
      expect(within(row).getByRole('link', { name: 'ทำใบเสนอราคา' }).getAttribute('href')).toBe('/quotations/new');
      expect(screen.queryByText('ส่งตัวอย่าง')).toBeNull();
    });

    it('hides ทำใบเสนอราคา from someone the create-quotation entry points refuse', async () => {
      renderMyWork({ user: { id: 1, role: 'ceo', employeeId: 1, name: 'ซีอีโอ' } });
      const row = await screen.findByTestId('today-quick-actions');
      expect(within(row).queryByRole('link', { name: 'ทำใบเสนอราคา' })).toBeNull();
      expect(within(row).getByRole('button', { name: 'โทรแล้ว' })).toBeTruthy();
    });

    it('shows a grantee ทำใบเสนอราคา even outside the sales roles', async () => {
      renderMyWork({ user: { id: 3, role: 'qc', employeeId: 3, name: 'คิวซี', canCreateQuotation: true } });
      const row = await screen.findByTestId('today-quick-actions');
      expect(within(row).getByRole('link', { name: 'ทำใบเสนอราคา' })).toBeTruthy();
    });

    it('hides โทรแล้ว when the rep has no open งาน to call about', async () => {
      api.salesBoard.myWork.mockResolvedValue(workWith({ followUp: [], board: [] }));
      renderMyWork();
      const row = await screen.findByTestId('today-quick-actions');
      expect(within(row).queryByRole('button', { name: 'โทรแล้ว' })).toBeNull();
    });

    it('โทรแล้ว picks a งาน — ควรตามต่อ first — then logs the call through the existing dialog', async () => {
      renderMyWork();
      fireEvent.click(within(await screen.findByTestId('today-quick-actions')).getByRole('button', { name: 'โทรแล้ว' }));

      const picker = await screen.findByRole('dialog');
      expect(within(picker).getByRole('heading', { name: 'โทรหางานไหน' })).toBeTruthy();
      const options = within(picker).getAllByRole('button', { name: /สยามพารากอน|BMW/ });
      expect(options.map((o) => o.textContent)).toEqual([
        expect.stringContaining('สยามพารากอน'), expect.stringContaining('BMW Showroom'),
      ]);
      fireEvent.click(options[0]);

      const call = await screen.findByRole('dialog');
      expect(within(call).getByRole('heading', { name: 'โทรแล้ว' })).toBeTruthy();
      fireEvent.click(within(call).getByRole('radio', { name: 'คุยแล้ว ยังสนใจ' }));
      fireEvent.click(within(call).getByRole('button', { name: 'บันทึก' }));

      await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledWith(31, {
        kind: 'CALL', activityDate: TODAY, note: 'คุยแล้ว ยังสนใจ',
      }));
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
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

  describe('เงินเข้าเดือนนี้', () => {
    it('shows received, from expected, and the next week with money due', async () => {
      renderMyWork();
      const card = await screen.findByTestId('today-cash');
      expect(within(card).getByRole('heading', { name: 'เงินเข้าเดือนนี้' })).toBeTruthy();
      expect(within(card).getByText('฿121,150')).toBeTruthy();
      expect(within(card).getByText('จากที่คาด ฿1,301,000 · รอบถัดไป ถึง 13 พ.ย.')).toBeTruthy();
    });

    it('skips a week with nothing due when naming the next one', async () => {
      api.salesBoard.myWork.mockResolvedValue(workWith({
        // Not the month's last week, which CashCard labels through the month's end (weekEndLabel's isLast).
        cash: { ...cash, weeks: [cash.weeks[0], week('2026-11-09'), cash.weeks[2], week('2026-11-23', { depositsExpected: 1 })] },
      }));
      renderMyWork();
      const card = await screen.findByTestId('today-cash');
      expect(within(card).getByText('จากที่คาด ฿1,301,000 · รอบถัดไป ถึง 20 พ.ย.')).toBeTruthy();
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
