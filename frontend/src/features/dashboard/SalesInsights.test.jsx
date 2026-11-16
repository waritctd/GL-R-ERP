import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SalesInsights } from './SalesInsights.jsx';
import { api } from '../../api/index.js';
import { ml, pct } from './insight/insightFormat.js';
import { SNAPSHOT, SNAPSHOT_MINIMAL, SNAPSHOT_SPARSE, SNAPSHOT_WITH_NOW } from '../../test/fixtures/salesAnalyticsSnapshot.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: { salesAnalytics: { snapshot: vi.fn() } },
}));

// The role decides section order: a product ruling, pinned here per role.
const CEO_ORDER = ['sales', 'comm', 'cust', 'pipe', 'des'];
const MANAGER_ORDER = ['pipe', 'des', 'sales', 'cust', 'comm'];

function renderInsights(role) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <SalesInsights user={{ role }} />
        </MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

const withSnapshot = (payload = SNAPSHOT) => api.salesAnalytics.snapshot.mockResolvedValue({
  snapshot: { payload, asOf: '2026-03-31', uploadedAt: '2026-04-02T03:00:00Z' },
});
const detail = () => screen.getByTestId('insight-month-detail');
const sectionOrder = () => [...document.querySelectorAll('[data-testid^="insight-section-"]')]
  .map((el) => el.getAttribute('data-testid').replace('insight-section-', ''));

describe('SalesInsights', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    withSnapshot();
  });

  describe('month picker', () => {
    it('defaults to the latest month and shows that month\'s detail (revenue, per-rep table, top customer)', async () => {
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');

      expect(screen.getByTestId('insight-month-2').getAttribute('aria-pressed')).toBe('true');
      expect(screen.getByTestId('insight-month-0').getAttribute('aria-pressed')).toBe('false');
      expect(within(detail()).getByRole('heading', { name: 'มี.ค. 69' })).toBeTruthy();
      expect(within(detail()).getByText('ลูกค้า ค')).toBeTruthy();
      expect(within(detail()).getAllByText('Rep A').length).toBeGreaterThan(0);
      expect(within(detail()).queryByText('ลูกค้า ก')).toBeNull();
    });

    it('clicking a month column switches the detail to that month', async () => {
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');

      fireEvent.click(screen.getByTestId('insight-month-0'));

      expect(screen.getByTestId('insight-month-0').getAttribute('aria-pressed')).toBe('true');
      expect(within(detail()).getByText('ลูกค้า ก')).toBeTruthy();
      expect(within(detail()).queryByText('ลูกค้า ค')).toBeNull();
    });

    it('previous / next step one month and stop at the ends', async () => {
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');
      const prev = screen.getByRole('button', { name: 'เดือนก่อนหน้า' });
      const next = screen.getByRole('button', { name: 'เดือนถัดไป' });

      expect(next.disabled).toBe(true); // already on the latest month
      fireEvent.click(prev);
      expect(within(detail()).getByText('ลูกค้า ข')).toBeTruthy();
      fireEvent.click(prev);
      expect(within(detail()).getByText('ลูกค้า ก')).toBeTruthy();
      expect(prev.disabled).toBe(true); // first month
      fireEvent.click(next);
      expect(within(detail()).getByText('ลูกค้า ข')).toBeTruthy();
    });
  });

  describe('ทั้งปี part', () => {
    it('shows the four headline figures and the data notes', async () => {
      renderInsights('ceo');
      await screen.findByTestId('insight-year');
      expect(within(screen.getByTestId('insight-year')).getAllByTestId('insight-figure')).toHaveLength(4);
      expect(screen.getByText('หมายเหตุข้อมูล')).toBeTruthy();
    });

    it('CEO sees sales sections first, sales_manager sees the pipeline first', async () => {
      const { unmount } = renderInsights('ceo');
      await screen.findByTestId('insight-year');
      expect(sectionOrder()).toEqual(CEO_ORDER);
      unmount();

      renderInsights('sales_manager');
      await screen.findByTestId('insight-year');
      expect(sectionOrder()).toEqual(MANAGER_ORDER);
    });

    it('chips filter to one section, and ทั้งหมด restores all of them', async () => {
      renderInsights('ceo');
      await screen.findByTestId('insight-year');
      const chips = () => within(screen.getByRole('group', { name: 'หัวข้อวิเคราะห์' })).getAllByRole('button');
      expect(chips().map((t) => t.textContent)).toEqual([
        'ทั้งหมด', 'ยอดขาย', 'ค่าคอมมิชชัน', 'ลูกค้า', 'งานในมือและประมาณการ', 'ผู้ออกแบบและการใช้เวลา',
      ]);

      fireEvent.click(chips()[2]);
      expect(chips()[2].getAttribute('aria-pressed')).toBe('true');
      expect(sectionOrder()).toEqual(['comm']);
      fireEvent.click(chips()[0]);
      expect(sectionOrder()).toEqual(CEO_ORDER);
    });
  });

  describe('empty state', () => {
    beforeEach(() => api.salesAnalytics.snapshot.mockResolvedValue({ snapshot: null }));

    it('shows the empty state, not the month picker, when no snapshot exists', async () => {
      renderInsights('sales_manager');
      await screen.findByTestId('insight-empty');
      expect(screen.queryByTestId('insight-month-detail')).toBeNull();
    });

    it('offers no upload control to anyone, the CEO included: the data arrives through the weekly update', async () => {
      const { unmount } = renderInsights('ceo');
      const ceoEmpty = await screen.findByTestId('insight-empty');
      expect(document.querySelector('input[type="file"]')).toBeNull();
      expect(screen.queryByText(/อัปโหลด/)).toBeNull();
      expect(ceoEmpty.textContent).toBe('ยังไม่มีข้อมูลวิเคราะห์ — ข้อมูลอัปเดตทุกสัปดาห์');
      unmount();

      renderInsights('sales_manager');
      expect((await screen.findByTestId('insight-empty')).textContent).toBe(ceoEmpty.textContent);
      expect(document.querySelector('input[type="file"]')).toBeNull();
    });
  });

  describe('optional parts of the snapshot', () => {
    it('shows the ยังไม่จบเดือน chip only when `now` exists, and selecting it shows the in-progress table', async () => {
      const { unmount } = renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');
      expect(screen.queryByRole('button', { name: 'ยังไม่จบเดือน' })).toBeNull();
      unmount();

      withSnapshot(SNAPSHOT_WITH_NOW);
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');
      fireEvent.click(screen.getByRole('button', { name: 'ยังไม่จบเดือน' }));

      const table = await screen.findByTestId('insight-now-table');
      expect(within(table).getByText('Rep A')).toBeTruthy();
      expect(within(table).getByText('Rep B')).toBeTruthy();
      expect(screen.getByTestId('insight-now-detail').textContent).toContain('เม.ย. 69');
      // Picking a closed month goes back to that month's detail.
      fireEvent.click(screen.getByTestId('insight-month-0'));
      expect(screen.queryByTestId('insight-now-table')).toBeNull();
      expect(within(detail()).getByText('ลูกค้า ก')).toBeTruthy();
    });

    it('renders a snapshot with no optional sections without throwing, omitting those panels', async () => {
      withSnapshot(SNAPSHOT_MINIMAL);
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');

      expect(screen.queryByRole('button', { name: 'ยังไม่จบเดือน' })).toBeNull();
      expect(screen.queryByTestId('insight-section-des')).toBeNull();
      expect(screen.queryByTestId('insight-section-pipe')).toBeNull();
      expect(screen.getByTestId('insight-section-sales')).toBeTruthy();
    });
  });
  describe('robustness: a stored snapshot never crashes the page', () => {
    it('shows the empty state for a snapshot with no months, or whose monthly entries are empty objects', async () => {
      withSnapshot({ ...SNAPSHOT_MINIMAL, months: [], monthly_total: [], monthly: [] });
      const { unmount } = renderInsights('ceo');
      expect(await screen.findByTestId('insight-empty')).toBeTruthy();
      unmount();

      withSnapshot({ ...SNAPSHOT_MINIMAL, monthly: [{}, {}, {}] });
      renderInsights('ceo');
      expect(await screen.findByTestId('insight-empty')).toBeTruthy();
    });

    it('prints no Infinity / NaN for a month whose total is 0', async () => {
      const zero = { ...SNAPSHOT.monthly[2], total: 0, comm: 0, reps: [{ rep: 'Rep A', rev: 0, comm: 0, corr: 0 }], topc: [{ name: 'ลูกค้า ค', amt: 0, n: 0, reps: 'Rep A', share: 0 }] };
      withSnapshot({ ...SNAPSHOT, monthly: [SNAPSHOT.monthly[0], SNAPSHOT.monthly[1], zero], monthly_total: [1000000, 2000000, 0] });
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');
      expect(detail().textContent).not.toMatch(/Infinity|NaN/);
    });

    it('prints no Infinity / NaN for an in-progress month whose forecast is 0', async () => {
      withSnapshot({ ...SNAPSHOT_WITH_NOW, now: { ...SNAPSHOT_WITH_NOW.now, rows: [{ rep: 'Rep A', fc: 0, recv: 0 }] } });
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');
      fireEvent.click(screen.getByRole('button', { name: 'ยังไม่จบเดือน' }));
      expect((await screen.findByTestId('insight-now-detail')).textContent).not.toMatch(/Infinity|NaN/);
    });

    it('prints no Infinity / NaN when the 12-month total is 0', async () => {
      withSnapshot({ ...SNAPSHOT, t12_total: 0, top_customers: [{ name: 'ลูกค้า ก', amt: 0, n: 0, reps: 'Rep A', share: 0 }] });
      renderInsights('ceo');
      expect((await screen.findByTestId('insight-year')).textContent).not.toMatch(/Infinity|NaN/);
    });
  });

  describe('snapshot contract: period labels and counts come from the data', () => {
    it('takes the quarter headings from quarter.label / prev_label and bakes in no period of its own', async () => {
      renderInsights('ceo');
      const year = await screen.findByTestId('insight-year');
      expect(year.textContent).toContain('ไตรมาส 1/70');
      expect(year.textContent).toContain('ไตรมาส 1/69');
      expect(year.textContent).not.toMatch(/3\/69|3\/68/);
    });

    it('omits the quarter figure, finding and columns when `quarter` is absent', async () => {
      withSnapshot({ ...SNAPSHOT, quarter: undefined });
      renderInsights('ceo');
      const year = await screen.findByTestId('insight-year');
      expect(within(year).getAllByTestId('insight-figure')).toHaveLength(3);
      expect(year.textContent).not.toMatch(/ไตรมาส \d/);
      expect(within(screen.getByTestId('insight-section-sales')).queryByText('เปลี่ยนแปลง')).toBeNull();
    });

    it('counts the customer lists from the array length, not a hard-coded number', async () => {
      renderInsights('ceo');
      await screen.findByTestId('insight-year');
      fireEvent.click(screen.getByRole('button', { name: 'ลูกค้า' }));
      expect(screen.getByText('ลูกค้า 1 อันดับแรก')).toBeTruthy();
    });
  });

  describe('prototype detail', () => {
    it('both customer lists carry every column and the confidentiality footer', async () => {
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');
      const lists = [screen.getByTestId('insight-customers-month'), screen.getByTestId('insight-customers-year')];
      lists.forEach((list) => {
        ['#', 'ลูกค้า', 'ยอดรับ (ไม่รวม VAT)', 'สัดส่วน', '%', 'ใบเสร็จ', 'พนักงานขาย'].forEach((h) => {
          expect(within(list).getByRole('columnheader', { name: h })).toBeTruthy();
        });
        expect(list.textContent).toContain('ห้ามส่งต่อนอก GL&R');
        expect(list.textContent).toMatch(/รวม 1 รายนี้คิดเป็น/);
      });
    });

    it('month detail shows the three customer figures and the per-rep status badge', async () => {
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');
      ['5 รายใหญ่สุด', 'รายใหญ่สุดรายเดียว', 'ใบเสร็จเกิน 500,000'].forEach((t) => expect(within(detail()).getByText(t)).toBeTruthy());
      expect(within(detail()).getAllByText('OK').length).toBeGreaterThan(0);
    });
  });
  describe('month picker bars', () => {
    const heightOf = (i) => parseFloat(screen.getByTestId(`insight-month-bar-${i}`).style.height);

    it('gives a zero month a 0-height bar inside a full-height, still clickable button; a tiny one keeps a 2% floor; never over 100%', async () => {
      const m = SNAPSHOT.monthly;
      withSnapshot({
        ...SNAPSHOT,
        monthly_total: [0, 1, 1000000],
        monthly: [{ ...m[0], total: 0, reps: [] }, { ...m[1], total: 1 }, m[2]],
      });
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');
      expect(heightOf(0)).toBe(0);
      expect(heightOf(1)).toBe(2);
      expect(heightOf(2)).toBe(100);
      expect(screen.getByTestId('insight-month-0').className).toContain('h-full');
      fireEvent.click(screen.getByTestId('insight-month-0'));
      expect(screen.getByTestId('insight-month-0').getAttribute('aria-pressed')).toBe('true');
    });

    it('renders and selects both columns when two months share a label', async () => {
      const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
      withSnapshot({ ...SNAPSHOT, months: ['ม.ค. 69', 'ม.ค. 69', 'มี.ค. 69'] });
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');
      fireEvent.click(screen.getByTestId('insight-month-0'));
      expect(within(detail()).getByText('ลูกค้า ก')).toBeTruthy();
      fireEvent.click(screen.getByTestId('insight-month-1'));
      expect(within(detail()).getByText('ลูกค้า ข')).toBeTruthy();
      expect(screen.getByTestId('insight-month-1').getAttribute('aria-pressed')).toBe('true');
      // Duplicate React keys (columns keyed by label) log this and can drop or duplicate a column.
      expect(spy.mock.calls.flat().join(' ')).not.toContain('same key');
      spy.mockRestore();
    });
  });
  describe('review round 2', () => {
    it('recovers from a snapshot that fails to render once a newer one is fetched', async () => {
      const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
      api.salesAnalytics.snapshot.mockResolvedValue({ snapshot: { payload: { ...SNAPSHOT, cust: { ...SNAPSHOT.cust, buckets: 'x' } }, asOf: null, uploadedAt: '2026-04-02T03:00:00Z' } });
      const { queryClient } = renderInsights('ceo');
      expect((await screen.findByRole('alert')).textContent).toContain('แสดงหัวข้อนี้ไม่ได้');

      api.salesAnalytics.snapshot.mockResolvedValue({ snapshot: { payload: SNAPSHOT, asOf: null, uploadedAt: '2026-04-03T03:00:00Z' } });
      await queryClient.invalidateQueries();
      await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
      expect(screen.getByTestId('insight-month-detail')).toBeTruthy();
      spy.mockRestore();
    });

    it('treats rows that are not objects with a string rep as an unusable (empty-state) snapshot', async () => {
      withSnapshot({ ...SNAPSHOT, reps: [null] });
      const { unmount } = renderInsights('ceo');
      expect(await screen.findByTestId('insight-empty')).toBeTruthy();
      unmount();

      withSnapshot({ ...SNAPSHOT, monthly: [{ ...SNAPSHOT.monthly[0], reps: [null] }, SNAPSHOT.monthly[1], SNAPSHOT.monthly[2]] });
      renderInsights('ceo');
      expect(await screen.findByTestId('insight-empty')).toBeTruthy();
    });

    it('renders every row when two reps share a name, with no duplicate-key error', async () => {
      const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
      const dup = { rep: 'Rep A', rev: 100, comm: 1, corr: 0 };
      withSnapshot({
        ...SNAPSHOT,
        reps: [SNAPSHOT.reps[0], SNAPSHOT.reps[0]],
        monthly: SNAPSHOT.monthly.map((m) => ({ ...m, reps: [dup, dup] })),
      });
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');
      expect(within(detail()).getAllByRole('row').length).toBeGreaterThanOrEqual(3);
      expect(spy.mock.calls.flat().join(' ')).not.toContain('same key');
      spy.mockRestore();
    });

    it('never truncates a month label; on a phone only every third month and the selected one stay visible', async () => {
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');
      const label = (i) => screen.getByTestId(`insight-month-label-${i}`);
      [0, 1, 2].forEach((i) => expect(label(i).className).not.toContain('truncate'));
      expect(label(2).className).not.toContain('mobile:sr-only'); // selected
      expect(label(0).className).not.toContain('mobile:sr-only'); // every third
      expect(label(1).className).toContain('mobile:sr-only');
      [0, 1, 2].forEach((i) => expect(screen.getByTestId(`insight-month-${i}`).getAttribute('aria-label')).toContain(SNAPSHOT.months[i]));
    });
  });
  describe('review round 3', () => {
    it('a snapshot with only the required keys, and every optional section empty, prints no "undefined" or "NaN"', async () => {
      withSnapshot(SNAPSHOT_SPARSE);
      const view = renderInsights('ceo');
      const root = await screen.findByTestId('sales-insights');
      await screen.findByTestId('insight-year');
      // innerHTML, so aria-label / title attributes are scanned too.
      const clean = () => expect(root.innerHTML).not.toMatch(/undefined|NaN/);
      clean();
      fireEvent.click(screen.getByTestId('insight-month-1'));
      clean();
      fireEvent.click(screen.getByRole('button', { name: 'ยังไม่จบเดือน' }));
      await screen.findByTestId('insight-now-detail');
      clean();
      expect(screen.queryByRole('alert')).toBeNull();
      view.unmount();

      // Same for the bare minimum (no optional section at all) and the manager's section order.
      withSnapshot({ months: ['ม.ค. 69'], monthly_total: [1], monthly: [{ total: 1, reps: [{ rep: 'Rep A' }] }], reps: [{ rep: 'Rep A' }] });
      renderInsights('sales_manager');
      expect((await screen.findByTestId('sales-insights')).innerHTML).not.toMatch(/undefined|NaN/);
    });

    it('one malformed optional section shows a section fallback while the month picker, detail and other sections stay', async () => {
      const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
      withSnapshot({ ...SNAPSHOT, cust: { ...SNAPSHOT.cust, buckets: { b: 'x' } } });
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');
      await screen.findByTestId('insight-year');

      expect(screen.getByTestId('insight-month-0')).toBeTruthy();
      const alerts = screen.getAllByRole('alert');
      expect(alerts).toHaveLength(1);
      expect(alerts[0].textContent).toContain('แสดงหัวข้อนี้ไม่ได้');
      expect(within(screen.getByTestId('insight-section-cust')).getByRole('alert')).toBeTruthy();
      expect(within(screen.getByTestId('insight-section-sales')).queryByRole('alert')).toBeNull();
      expect(screen.getAllByTestId('insight-figure')).toHaveLength(4);
      spy.mockRestore();
    });

    it('keeps the month picker, and recovers by picking another month, when the selected month\'s data is malformed', async () => {
      const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
      const m = SNAPSHOT.monthly;
      withSnapshot({ ...SNAPSHOT, monthly: [m[0], m[1], { ...m[2], fc: [null] }] });
      renderInsights('ceo');
      expect((await screen.findByRole('alert')).textContent).toBe('แสดงเดือนนี้ไม่ได้ เลือกเดือนอื่น');
      expect(screen.queryByTestId('insight-month-detail')).toBeNull();

      fireEvent.click(screen.getByTestId('insight-month-0'));
      expect(within(detail()).getByText('ลูกค้า ก')).toBeTruthy();
      expect(screen.queryByText('แสดงเดือนนี้ไม่ได้ กรุณาอัปโหลดไฟล์ใหม่หรือแจ้งผู้ดูแลระบบ')).toBeNull();
      spy.mockRestore();
    });

    it('treats an empty string and non-numeric text as a missing figure, not 0', () => {
      ['', ' ', 'abc', null, undefined].forEach((v) => {
        expect(pct(v)).toBe('—');
        expect(ml(v)).toBe('—');
      });
      expect(pct(0)).toBe('0%');
      expect(pct('0.5')).toBe('50%');
      expect(ml('2000000')).toBe('2.0 ล้าน');
    });

    it('shows the ข้อมูล ณ date to the sales manager too, like the CEO, with no upload control', async () => {
      renderInsights('sales_manager');
      await screen.findByTestId('insight-month-detail');
      expect(screen.getByText('ข้อมูล ณ 2026-03-31')).toBeTruthy();
      expect(document.querySelector('input[type="file"]')).toBeNull();
    });

    describe('findings and recommendations follow the data', () => {
      const findingTexts = () => [...screen.getByTestId('insight-year').querySelectorAll('#find strong')].map((e) => e.textContent);
      const dotOf = (head) => [...screen.getByTestId('insight-year').querySelectorAll('#find strong')]
        .find((e) => e.textContent.includes(head)).querySelector('span').className;
      const show = async (patch) => {
        withSnapshot({ ...SNAPSHOT, ...patch });
        renderInsights('ceo');
        return screen.findByTestId('insight-year');
      };

      it('the quarter finding is red only when the quarter fell, neutral when it rose', async () => {
        const { unmount } = renderInsights('ceo');
        await screen.findByTestId('insight-year');
        expect(dotOf('ไตรมาส 1/70')).toContain('bg-danger');
        unmount();

        await show({ quarter: { ...SNAPSHOT.quarter, chg: 5 } });
        expect(dotOf('ไตรมาส 1/70')).not.toContain('bg-danger');
        expect(dotOf('ไตรมาส 1/70')).toContain('bg-text-muted');
      });

      it('"แค่" and the make-mandatory recommendation appear only when a fill rate is below 80', async () => {
        const year = await show({});
        expect(year.textContent).toContain('แค่ 50%');
        expect(year.textContent).toContain('เป็นช่องบังคับในดีล');
        cleanup();

        // designer is not named by the recommendation, so a low designer rate alone must not trigger it.
        const full = await show({ pipe: { ...SNAPSHOT.pipe, fill: { prob: 90, q: 85, stage: 95, designer: 50 } } });
        expect(full.textContent).not.toContain('แค่ 90%');
        expect(full.textContent).not.toContain('เป็นช่องบังคับในดีล');
      });

      it('the commission finding is a warning only when paid is above the formula', async () => {
        const { unmount } = renderInsights('ceo');
        await screen.findByTestId('insight-year');
        expect(dotOf('ค่าคอมจ่ายจริง')).toContain('bg-warning');
        // The wording must agree with the tone: paid above the formula reads "สูงกว่า", never "ต่ำกว่า".
        expect(screen.getByTestId('insight-year').textContent).toMatch(/ค่าคอมจ่ายจริง [^·]*สูงกว่าสูตรขั้นบันได/);
        unmount();

        await show({ comm: { ...SNAPSHOT.comm, paid: 30000 } });
        expect(dotOf('ค่าคอมจ่ายจริง')).not.toContain('bg-warning');
        expect(dotOf('ค่าคอมจ่ายจริง')).toContain('bg-text-muted');
        expect(screen.getByTestId('insight-year').textContent).toMatch(/ค่าคอมจ่ายจริง [^·]*ต่ำกว่าสูตรขั้นบันได/);
      });

      it('the pipe concentration finding is red with "แต่" only when the top 10 hold at least half', async () => {
        const { unmount } = renderInsights('ceo');
        await screen.findByTestId('insight-year');
        expect(dotOf('งานคาดหวัง 9.0 ล้าน')).toContain('bg-danger');
        expect(findingTexts().find((t) => t.includes('อยู่ใน 10 งาน'))).toContain('แต่');
        unmount();

        await show({ pipe: { ...SNAPSHOT.pipe, top10: 30 } });
        expect(dotOf('อยู่ใน 10 งาน')).not.toContain('bg-danger');
        expect(dotOf('อยู่ใน 10 งาน')).toContain('bg-text-muted');
        expect(findingTexts().find((t) => t.includes('อยู่ใน 10 งาน'))).not.toContain('แต่');
      });

      it('recommends re-basing the forecast only when some rep is outside 85-115%', async () => {
        const { unmount } = renderInsights('ceo'); // Rep A 0.8
        const year = await screen.findByTestId('insight-year');
        expect(year.textContent).toContain('ปรับฐานประมาณการรายคน');
        unmount();

        const ok = await show({ fc: { ...SNAPSHOT.fc, by_rep: { 'Rep A': 0.9, 'Rep B': 1.1 } } });
        expect(ok.textContent).not.toContain('ปรับฐานประมาณการรายคน');
      });

      it('the customer finding names the first two bucket labels from the snapshot, and is omitted without buckets', async () => {
        await show({ cust: { ...SNAPSHOT.cust, buckets: [{ b: 'ถึง 10k', cnt: 10, val: 1 }, { b: '10-30k', cnt: 20, val: 4 }, { b: 'เกิน 30k', cnt: 70, val: 95 }] } });
        const line = screen.getByTestId('insight-year').querySelector('#find').textContent;
        expect(line).toContain('ถึง 10k');
        expect(line).toContain('10-30k');
        expect(line).toContain('30.0%'); // 10 + 20 of the count
        expect(line).not.toContain('20,000');
        cleanup();

        const year = await show({ cust: { ...SNAPSHOT.cust, buckets: undefined } });
        expect(findingTexts().some((t) => t.includes('จาก 20 ราย'))).toBe(false);
        expect(year.textContent).not.toContain('20,000');
      });
    });

    it('prints a real 0 forecast accuracy as 0%, and an absent one as —', async () => {
      withSnapshot({ ...SNAPSHOT, fc: { ...SNAPSHOT.fc, by_rep: { 'Rep A': 0 } }, pipe: { ...SNAPSHOT.pipe, by_rep: [{ ...SNAPSHOT.pipe.by_rep[0] }, { ...SNAPSHOT.pipe.by_rep[0], rep: 'Rep B' }] } });
      renderInsights('ceo');
      await screen.findByTestId('insight-year');
      const rows = within(screen.getByTestId('insight-section-pipe').querySelector('#pipe')).getAllByRole('row');
      const cell = (row) => row.querySelectorAll('td')[4].textContent;
      expect(cell(rows[1])).toBe('0%');
      expect(cell(rows[2])).toBe('—');
    });
  });
});
