import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SalesInsights } from './SalesInsights.jsx';
import { api } from '../../api/index.js';
import { ml, pct } from './insight/insightFormat.js';
import { formatThaiDate } from '../../utils/format.js';
import { SNAPSHOT } from '../../test/fixtures/salesAnalyticsSnapshot.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: { salesAnalytics: { snapshot: vi.fn(), metrics: vi.fn() } },
}));

const NO_METRICS = { weekStart: null, computedAt: null, metrics: [], checks: [] };
// Invented figures. Brand X: 8.0 ล้าน quoted; Brand Y: 2.0 ล้าน. Rep B has no activity metric and a real 0 for moves.
const m = (key, dimension, label, value, previous = null) => ({ key, dimension, label, value, previous });
const METRICS = {
  weekStart: '2026-11-09', computedAt: '2026-11-10T01:00:00Z',
  metrics: [
    m('pipeline_value_dedup', '', '', 12_000_000, 10_000_000),
    m('pipeline_value_raw', '', '', 15_000_000, null),
    m('pipeline_stale_share', '', '', 0.25, 0.2),
    m('rep_stage_moves_week', 'rep=1', 'Rep A', 4), m('rep_activities_week', 'rep=1', 'Rep A', 12),
    m('rep_quiet_deal_share', 'rep=1', 'Rep A', 0.25), m('hygiene_score', 'rep=1', 'Rep A', 0.8),
    m('rep_stage_moves_week', 'rep=2', 'Rep B', 0), m('rep_quiet_deal_share', 'rep=2', 'Rep B', 0.1), m('hygiene_score', 'rep=2', 'Rep B', 0),
    m('rep_stage_moves_week', 'rep=3', 'Rep C', 0),                                   // nothing at all: hidden
    m('rep_activities_week', 'rep=4', 'Rep D', 3),                                    // only activity: shown
    m('quoted_value', 'brand=Brand Y', 'Brand Y', 2_000_000), m('quoted_sqm', 'brand=Brand Y', 'Brand Y', 1000),
    m('quoted_value', 'brand=Brand X', 'Brand X', 8_000_000), m('quoted_sqm', 'brand=Brand X', 'Brand X', 4000),
    m('ordered_value', 'brand=Brand X', 'Brand X', 3_000_000),
  ],
  checks: [
    { key: 'stage_mapped', passed: true, detail: 'unmapped tickets=0' },
    { key: 'quote_line_brand_present', passed: true, detail: 'lines with no brand=0' },
  ],
};
const FAILED = { key: 'quote_line_brand_present', passed: false, detail: 'lines with no brand=4' };
const withMetrics = (data = METRICS) => api.salesAnalytics.metrics.mockResolvedValue(data);
const block = async () => { await screen.findByTestId('portal-week'); return screen.getByTestId('portal-metrics'); };
const cells = (row) => [...row.querySelectorAll('td')].map((td) => td.textContent);

// The role decides section order: a product ruling, pinned here per role.
const CEO_ORDER = ['sales', 'comm', 'cust', 'pipe', 'des'];
const MANAGER_ORDER = ['pipe', 'des', 'sales', 'cust', 'comm'];

// `queries` overrides the test client's defaults (the app's own defaults are no focus refetch and a 30 s staleTime).
function renderInsights(role, queries = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, ...queries }, mutations: { retry: false } } });
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
const expectNoUploadControl = () => {
  const root = screen.getByTestId('sales-insights');
  expect(root.querySelector('input[type="file"]')).toBeNull();
  expect(within(root).queryByRole('button', { name: /อัปโหลด|upload/i })).toBeNull();
};
const detail = () => screen.getByTestId('insight-month-detail');
const sectionOrder = () => [...document.querySelectorAll('[data-testid^="insight-section-"]')]
  .map((el) => el.getAttribute('data-testid').replace('insight-section-', ''));

describe('SalesInsights', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    withSnapshot();
    api.salesAnalytics.metrics.mockResolvedValue(NO_METRICS);
  });

  describe('empty state', () => {
    it('offers no upload control to anyone, the CEO included: the data arrives through the weekly update', async () => {
      api.salesAnalytics.snapshot.mockResolvedValue({ snapshot: null });
      const { unmount } = renderInsights('ceo');
      const ceoEmpty = await screen.findByTestId('insight-empty');
      expect(ceoEmpty.textContent).toBe('ยังไม่มีข้อมูลวิเคราะห์ — ข้อมูลอัปเดตทุกสัปดาห์');
      expectNoUploadControl();
      unmount();

      renderInsights('sales_manager');
      expect((await screen.findByTestId('insight-empty')).textContent).toBe(ceoEmpty.textContent);
      expectNoUploadControl();
    });
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
  });

  describe('ทั้งปี part', () => {
    it('CEO sees sales sections first, sales_manager sees the pipeline first', async () => {
      const { unmount } = renderInsights('ceo');
      await screen.findByTestId('insight-year');
      expect(sectionOrder()).toEqual(CEO_ORDER);
      unmount();

      renderInsights('sales_manager');
      await screen.findByTestId('insight-year');
      expect(sectionOrder()).toEqual(MANAGER_ORDER);
    });
  });
  describe('robustness: a stored snapshot never crashes the page', () => {
    it('prints no Infinity / NaN for a month whose total is 0', async () => {
      const zero = { ...SNAPSHOT.monthly[2], total: 0, comm: 0, reps: [{ rep: 'Rep A', rev: 0, comm: 0, corr: 0 }], topc: [{ name: 'ลูกค้า ค', amt: 0, n: 0, reps: 'Rep A', share: 0 }] };
      withSnapshot({ ...SNAPSHOT, monthly: [SNAPSHOT.monthly[0], SNAPSHOT.monthly[1], zero], monthly_total: [1000000, 2000000, 0] });
      renderInsights('ceo');
      await screen.findByTestId('insight-month-detail');
      expect(detail().textContent).not.toMatch(/Infinity|NaN/);
    });
  });
  describe('review round 3', () => {
    describe('findings and recommendations follow the data', () => {
      const findingTexts = () => [...screen.getByTestId('insight-year').querySelectorAll('#find strong')].map((e) => e.textContent);
      const dotOf = (head) => [...screen.getByTestId('insight-year').querySelectorAll('#find strong')]
        .find((e) => e.textContent.includes(head)).querySelector('span').className;
      const show = async (patch) => {
        withSnapshot({ ...SNAPSHOT, ...patch });
        renderInsights('ceo');
        return screen.findByTestId('insight-year');
      };

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
    });
  });

  describe('ตัวเลขจาก Portal block (analytics Phase 2)', () => {
    it('renders the week line, the VAT note, the pipeline figures with the change vs the previous week, from the response only', async () => {
      withMetrics();
      renderInsights('ceo');
      const el = await block();
      expect(within(el).getByRole('heading', { name: 'ตัวเลขจาก Portal' })).toBeTruthy();
      expect(within(el).getByTestId('portal-week').textContent)
        .toBe(`สัปดาห์เริ่ม ${formatThaiDate('2026-11-09T00:00:00')} · คำนวณเมื่อ ${formatThaiDate('2026-11-10T01:00:00Z')}`);
      expect(el.textContent).toContain('ไม่รวม VAT');
      const fig = (label) => within(el).getByText(label).closest('div');
      expect(fig('มูลค่า pipeline (นับโครงการละครั้ง)').textContent).toContain(ml(12_000_000));
      expect(fig('มูลค่า pipeline (นับโครงการละครั้ง)').textContent).toContain('เทียบสัปดาห์ก่อน +2.0 ล้าน');
      expect(fig('มูลค่า pipeline (รวมทุกงาน)').textContent).toContain(ml(15_000_000));
      expect(fig('มูลค่า pipeline (รวมทุกงาน)').textContent).not.toContain('เทียบสัปดาห์ก่อน');
      expect(fig('สัดส่วน pipeline ที่เงียบเกิน 14 วัน').textContent).toContain(pct(0.25));
      expect(fig('สัดส่วน pipeline ที่เงียบเกิน 14 วัน').textContent).toContain('เทียบสัปดาห์ก่อน +5 จุด');
    });

    it('team table: one row per active rep; a missing metric is "—", a real 0 is 0; a rep with no deals and no activity is hidden', async () => {
      withMetrics();
      renderInsights('sales_manager');
      const rows = [...(await within(await block()).findByTestId('portal-team')).querySelectorAll('tbody tr')];
      expect(rows.map(cells)).toEqual([
        ['Rep A', '4', '12', '25%', '80%'],
        ['Rep B', '0', '—', '10%', '0%'],
        ['Rep D', '—', '3', '—', '—'],
      ]);
    });

    it('brand table: sorted by quoted value, share of the quoted total, sqm, ordered value; heading says เสนอราคา, never ขาย', async () => {
      withMetrics();
      renderInsights('ceo');
      const panel = (await block()).querySelector('[data-testid="portal-brands-panel"]');
      expect(panel.textContent).toContain('เสนอราคา');
      expect(panel.textContent).not.toMatch(/ขาย/);
      expect(panel.textContent).toContain('ได้รับคำสั่งซื้อ');
      const rows = [...within(panel).getByTestId('portal-brands').querySelectorAll('tbody tr')];
      expect(rows.map(cells)).toEqual([
        ['Brand X', ml(8_000_000), '80%', '4,000 ตร.ม.', ml(3_000_000)],
        ['Brand Y', ml(2_000_000), '20%', '1,000 ตร.ม.', '—'],
      ]);
      expect(panel.className).toContain('[&_td]:whitespace-nowrap');
    });

    it('shows a warning banner listing only the failed checks, and none when every check passed', async () => {
      withMetrics({ ...METRICS, checks: [...METRICS.checks, FAILED] });
      const { unmount } = renderInsights('ceo');
      const banner = await screen.findByTestId('portal-checks');
      expect(banner.getAttribute('role')).toBe('status');
      expect(banner.textContent).toContain('รายการสินค้าในใบเสนอราคาที่ไม่มีแบรนด์');
      expect(banner.textContent).toContain('lines with no brand=4');
      expect(banner.textContent).not.toContain('stage_mapped');
      expect(banner.textContent).not.toContain('งานที่ยังไม่ได้จับคู่ขั้นงาน');
      unmount();

      withMetrics();
      renderInsights('ceo');
      await block();
      expect(screen.queryByTestId('portal-checks')).toBeNull();
    });

    it('stale-share change is the difference of the two ROUNDED percentages, so it agrees with what is shown', async () => {
      withMetrics({ ...METRICS, metrics: METRICS.metrics.map((x) => (x.key === 'pipeline_stale_share' ? { ...x, value: 0.254, previous: 0.206 } : x)) });
      renderInsights('ceo');
      const el = await block();
      const f = within(el).getByText('สัดส่วน pipeline ที่เงียบเกิน 14 วัน').closest('div').textContent;
      expect(f).toContain('25%');
      expect(f).toContain('เทียบสัปดาห์ก่อน +4 จุด');
    });
  });
});
