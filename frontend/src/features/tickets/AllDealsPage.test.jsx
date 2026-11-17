import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AllDealsPage } from './AllDealsPage.jsx';
import { api } from '../../api/index.js';
import { currentQuarter } from '../salesBoard/boardFormat.js';
import { viewStorageKey } from './allDealsView.js';

globalThis.React = React;

// This repo's Node ships a broken global `localStorage` that shadows jsdom's (see quotationPrefs.test.js), so the
// storage is stubbed outright.
function memoryStorage() {
  const map = new Map();
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => { map.set(k, String(v)); },
    removeItem: (k) => { map.delete(k); },
    clear: () => map.clear(),
  };
}

function throwingStorage() {
  const boom = () => { throw new DOMException('The operation is insecure.', 'SecurityError'); };
  return { getItem: boom, setItem: boom, removeItem: boom, clear: boom };
}

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
      tickets: { list: vi.fn(), create: vi.fn() },
      salesBoard: { myWork: vi.fn(), teamBoard: vi.fn(), teamOverview: vi.fn() },
    },
  };
});

vi.mock('./TicketCreateModal.jsx', () => ({
  TicketCreateModal: () => <div role="dialog">สร้างงาน (stub)</div>,
}));

const salesUser = { id: 6, employeeId: 6, name: 'สมหมาย ขายดี', role: 'sales' };
const managerUser = { id: 2, employeeId: 2, name: 'สมชาย ศรีสุข', role: 'sales_manager' };
const ceoUser = { id: 1, employeeId: 1, name: 'ประเสริฐ มั่นคง', role: 'ceo' };

const QUARTER = currentQuarter();

const boardDeal = (over) => ({
  ticketId: 1, title: 'งาน', customerName: 'ลูกค้า', projectName: null, ownerId: 6, ownerName: 'สมหมาย ขายดี',
  salesStage: 'LEAD_APPROACH', repCode: 'S1', boardColumn: 1, value: null, currency: 'THB', valueExpired: false,
  designerName: null, winProbability: 20, expectedQuarter: QUARTER, lastTouchedAt: '2026-11-10T00:00:00Z',
  daysQuiet: 2, quiet: false, lastActivityKind: null, lastActivityNote: null, ...over,
});

const REP_BOARD = [
  boardDeal({ ticketId: 11, title: 'Aira Two Hotel Silom', designerName: 'PIA', value: 2500000, daysQuiet: 3 }),
  boardDeal({ ticketId: 12, title: 'Central Ladprao ชั้น B1', salesStage: 'SPEC_APPROVED', repCode: 'S2', boardColumn: 2,
    value: 640000, daysQuiet: 19, quiet: true }),
  // 20 days without a touch but NOT quiet under the shared rule (e.g. a visit is booked): no days chip.
  boardDeal({ ticketId: 13, title: 'ปุรีมายา Wellness', salesStage: 'SPEC_APPROVED', repCode: 'S2', boardColumn: 2,
    value: 3250000, daysQuiet: 20, quiet: false }),
  boardDeal({ ticketId: 14, title: 'ร้านกาแฟ Roast Lab', value: 380000, daysQuiet: 1, expectedQuarter: '2001-Q1' }),
];

const TEAM_BOARD = [
  boardDeal({ ticketId: 21, title: 'BMW Showroom เชียงใหม่', ownerId: 8, ownerName: 'กมล ใจดี', salesStage: 'QUOTE_OWNER',
    repCode: 'S5', boardColumn: 3, value: 1560650, daysQuiet: 4 }),
  boardDeal({ ticketId: 22, title: 'สยามพารากอน · ห้องน้ำชั้น 3', ownerId: 6, ownerName: 'สมหมาย ขายดี',
    salesStage: 'QUOTE_DESIGN_SIDE', repCode: 'S3', boardColumn: 3, value: 1240000, daysQuiet: 27, quiet: true }),
];

const TICKETS = [
  { id: 11, code: 'D-11', title: 'Aira Two Hotel Silom', lifecycle: 'ACTIVE', salesStage: 'LEAD_APPROACH', createdById: 6, createdByName: 'สมหมาย ขายดี' },
  { id: 31, code: 'D-31', title: 'พักไว้หนึ่ง', lifecycle: 'ON_HOLD', salesStage: 'LEAD_APPROACH', createdById: 6, createdByName: 'สมหมาย ขายดี' },
  { id: 32, code: 'D-32', title: 'เสียงานหนึ่ง', lifecycle: 'CLOSED_LOST', salesStage: 'LEAD_APPROACH', createdById: 6, createdByName: 'สมหมาย ขายดี' },
  { id: 33, code: 'D-33', title: 'เสียงานสอง', lifecycle: 'CLOSED_LOST', salesStage: 'LEAD_APPROACH', createdById: 6, createdByName: 'สมหมาย ขายดี' },
  { id: 34, code: 'D-34', title: 'ยกเลิกหนึ่ง', lifecycle: 'CANCELLED', salesStage: 'LEAD_APPROACH', createdById: 7, createdByName: 'วิภา' },
];

function LocationDisplay() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}{location.search}</div>;
}

function renderPage(user = salesUser, entry = '/tickets') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/tickets" element={<AllDealsPage user={user} showToast={vi.fn()} />} />
        </Routes>
        <LocationDisplay />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const board = () => screen.findByTestId('deal-board');

describe('AllDealsPage (งานทั้งหมด)', () => {
  let store;

  beforeEach(() => {
    vi.clearAllMocks();
    store = memoryStorage();
    vi.stubGlobal('localStorage', store);
    api.salesBoard.myWork.mockResolvedValue({ followUp: [], board: REP_BOARD, quietDays: 14, visitsThisWeek: [], cash: null });
    api.salesBoard.teamBoard.mockResolvedValue({ board: TEAM_BOARD, quietDays: 14 });
    api.salesBoard.teamOverview.mockResolvedValue({ reps: [], stale: [], quietDays: 14, teamVisitsThisWeek: [] });
    api.tickets.list.mockResolvedValue({ tickets: TICKETS });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // ── default view and data source per role ──────────────────────────────

  it('opens a sales rep on the board, fed by their own my-work board, never the team board', async () => {
    renderPage(salesUser);

    await board();
    expect(screen.getByRole('heading', { level: 1, name: 'งานทั้งหมด' })).toBeTruthy();
    expect(api.salesBoard.myWork).toHaveBeenCalled();
    expect(api.salesBoard.teamBoard).not.toHaveBeenCalled();
    expect(within(screen.getByTestId('board-column-1')).getByText('Aira Two Hotel Silom')).toBeTruthy();
    // A rep looking at their own deals sees no owner tag.
    expect(screen.queryByTestId('board-card-owner')).toBeNull();
  });

  it.each([['sales_manager', managerUser], ['ceo', ceoUser]])(
    '%s sees the whole team board, each card naming its rep, and never the own-deals board',
    async (_role, user) => {
      renderPage(user);

      await board();
      expect(api.salesBoard.teamBoard).toHaveBeenCalled();
      expect(api.salesBoard.myWork).not.toHaveBeenCalled();
      const card = await screen.findByTestId('board-card-21');
      expect(within(card).getByTestId('board-card-owner').textContent).toContain('กมล');
      expect(within(screen.getByTestId('board-card-22')).getByTestId('board-card-owner').textContent).toContain('สมหมาย');
    },
  );

  // ── header ─────────────────────────────────────────────────────────────

  it('subtitle counts and sums exactly the cards shown, and says the order', async () => {
    renderPage(salesUser);
    await board();

    // ทั้งหมด is the default (ruling 2026-11-16): all four deals.
    expect(await screen.findByText('4 งานที่เปิดอยู่ · มูลค่ารวม ฿6,770,000 · เรียงตามวันที่เงียบ')).toBeTruthy();

    // ไตรมาสนี้ leaves the three deals expected this quarter: 2,500,000 + 640,000 + 3,250,000.
    fireEvent.click(screen.getByRole('button', { name: 'ไตรมาสนี้' }));
    expect(await screen.findByText('3 งานที่เปิดอยู่ · มูลค่ารวม ฿6,390,000 · เรียงตามวันที่เงียบ')).toBeTruthy();
  });

  it('subtitle adds another currency beside the ฿ total, never into it', async () => {
    api.salesBoard.myWork.mockResolvedValue({ followUp: [], quietDays: 14, visitsThisWeek: [], cash: null, board: [
      boardDeal({ ticketId: 41, value: 2000 }),
      boardDeal({ ticketId: 42, value: 1000, currency: 'USD' }),
    ] });
    renderPage(salesUser);

    expect(await screen.findByText('2 งานที่เปิดอยู่ · มูลค่ารวม ฿2,000 + USD 1,000 · เรียงตามวันที่เงียบ')).toBeTruthy();
  });

  it('งานใหม่ opens the existing create-deal modal', async () => {
    renderPage(salesUser);
    await board();

    fireEvent.click(screen.getByRole('button', { name: /งานใหม่/ }));

    expect(await screen.findByText('สร้างงาน (stub)')).toBeTruthy();
  });

  // ── the quiet rule ─────────────────────────────────────────────────────

  it('shows the days chip only on a deal the shared quiet rule calls quiet', async () => {
    renderPage(salesUser);
    await board();

    expect(within(screen.getByTestId('board-card-12')).getByText('เงียบ 19 วัน')).toBeTruthy();
    expect(within(screen.getByTestId('board-card-13')).queryByText(/เงียบ/)).toBeNull();
  });

  it('orders each column by days quiet, longest first', async () => {
    renderPage(salesUser);
    await board();

    const column = screen.getByTestId('board-column-2');
    const titles = within(column).getAllByRole('link').map((a) => a.textContent);
    expect(titles).toEqual(['ปุรีมายา Wellness', 'Central Ladprao ชั้น B1']);
  });

  // ── search and chips ───────────────────────────────────────────────────

  it('searches by project, customer or designer', async () => {
    renderPage(salesUser);
    await board();

    fireEvent.change(screen.getByRole('searchbox', { name: 'ค้นหางาน' }), { target: { value: 'pia' } });

    await waitFor(() => expect(screen.queryByTestId('board-card-12')).toBeNull());
    expect(screen.getByTestId('board-card-11')).toBeTruthy();
    expect(screen.getByRole('searchbox', { name: 'ค้นหางาน' }).getAttribute('placeholder')).toBe('ค้นหาโครงการ ลูกค้า ผู้ออกแบบ');
  });

  it('ทั้งหมด is the default chip; ไตรมาสนี้ keeps this quarter; เฉพาะงานเงียบ n keeps only quiet deals', async () => {
    renderPage(salesUser);
    await board();

    expect(screen.getByRole('button', { name: 'ทั้งหมด' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByTestId('board-card-14')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'ไตรมาสนี้' }));
    await waitFor(() => expect(screen.queryByTestId('board-card-14')).toBeNull());
    expect(screen.getByTestId('board-card-11')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'เฉพาะงานเงียบ 1' }));
    await waitFor(() => expect(screen.queryByTestId('board-card-11')).toBeNull());
    expect(screen.getByTestId('board-card-12')).toBeTruthy();
  });

  // The adversarial probe: every deal carries some quarter, none this one. The old default (ไตรมาสนี้ once half the
  // deals had any quarter) showed an empty board here.
  it('a team whose deals are all in other quarters still opens on every deal', async () => {
    api.salesBoard.teamBoard.mockResolvedValue({ quietDays: 14, board: [
      boardDeal({ ticketId: 51, expectedQuarter: '2001-Q2' }),
      boardDeal({ ticketId: 52, expectedQuarter: '2001-Q3' }),
      boardDeal({ ticketId: 53, expectedQuarter: null }),
    ] });
    renderPage(managerUser);

    expect(await screen.findByTestId('board-card-51')).toBeTruthy();
    expect(screen.getByTestId('board-card-52')).toBeTruthy();
    expect(screen.getByTestId('board-card-53')).toBeTruthy();
    expect(screen.getByText(/^3 งานที่เปิดอยู่/)).toBeTruthy();
  });

  it('a chip that leaves no card says so, and one action brings every deal back', async () => {
    api.salesBoard.myWork.mockResolvedValue({ followUp: [], quietDays: 14, visitsThisWeek: [], cash: null, board: [
      boardDeal({ ticketId: 61, title: 'งานไตรมาสอื่น', expectedQuarter: '2001-Q1' }),
    ] });
    renderPage(salesUser);
    await screen.findByTestId('board-card-61');

    fireEvent.click(screen.getByRole('button', { name: 'ไตรมาสนี้' }));

    expect(await screen.findByText('ไม่มีงานในตัวกรองนี้')).toBeTruthy();
    expect(screen.getByText(/^0 งานที่เปิดอยู่ · มูลค่ารวม ฿0/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'แสดงงานทั้งหมด' }));

    expect(await screen.findByTestId('board-card-61')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'ทั้งหมด' }).getAttribute('aria-pressed')).toBe('true');
  });

  it('a search that matches nothing names the search, and the same action clears it', async () => {
    renderPage(salesUser);
    await board();

    fireEvent.click(screen.getByRole('button', { name: 'ไตรมาสนี้' }));
    fireEvent.change(screen.getByRole('searchbox', { name: 'ค้นหางาน' }), { target: { value: 'ไม่มีแน่นอน' } });

    expect(await screen.findByText('ไม่พบงานที่ตรงกับ “ไม่มีแน่นอน”')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'แสดงงานทั้งหมด' }));

    expect(await screen.findByTestId('board-card-14')).toBeTruthy();
    expect(screen.getByRole('searchbox', { name: 'ค้นหางาน' }).value).toBe('');
    expect(screen.getByRole('button', { name: 'ทั้งหมด' }).getAttribute('aria-pressed')).toBe('true');
  });

  // Decision: the chip n = quiet deals among the SEARCH results, in every quarter: exactly the cards pressing it
  // shows. It ignores the quarter chip (pressing it replaces that chip) but follows the search (which it keeps).
  it('the quiet chip counts quiet deals in the search results, whatever quarter is selected', async () => {
    api.salesBoard.myWork.mockResolvedValue({ followUp: [], quietDays: 14, visitsThisWeek: [], cash: null, board: [
      boardDeal({ ticketId: 71, title: 'Alpha หนึ่ง', quiet: true, daysQuiet: 20 }),
      boardDeal({ ticketId: 72, title: 'Alpha สอง', quiet: true, daysQuiet: 30, expectedQuarter: '2001-Q1' }),
      boardDeal({ ticketId: 73, title: 'Beta', quiet: true, daysQuiet: 40 }),
    ] });
    renderPage(salesUser);
    await screen.findByTestId('board-card-71');

    expect(screen.getByRole('button', { name: 'เฉพาะงานเงียบ 3' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'ไตรมาสนี้' }));
    expect(screen.getByRole('button', { name: 'เฉพาะงานเงียบ 3' })).toBeTruthy();

    fireEvent.change(screen.getByRole('searchbox', { name: 'ค้นหางาน' }), { target: { value: 'alpha' } });
    expect(await screen.findByRole('button', { name: 'เฉพาะงานเงียบ 2' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'เฉพาะงานเงียบ 2' }));
    await waitFor(() => expect(screen.getAllByTestId(/^board-card-\d+$/)).toHaveLength(2));
  });

  // ── failure and other roles ────────────────────────────────────────────

  it('a failed team-board load (e.g. an older backend) says so in the subtitle and the board, and retries', async () => {
    api.salesBoard.teamBoard.mockRejectedValue(new Error('404'));
    renderPage(managerUser);

    expect(await screen.findByText('โหลดงานไม่สำเร็จ')).toBeTruthy();
    expect(screen.queryByText('กำลังโหลดงาน…')).toBeNull();
    expect(screen.getByText('โหลดบอร์ดไม่สำเร็จ ลองอีกครั้ง หรือดูแบบตาราง')).toBeTruthy();

    api.salesBoard.teamBoard.mockResolvedValue({ board: TEAM_BOARD, quietDays: 14 });
    fireEvent.click(screen.getByRole('button', { name: 'ลองอีกครั้ง' }));
    expect(await screen.findByTestId('board-card-21')).toBeTruthy();
  });

  it('a failed board load still reaches the table in one tap', async () => {
    api.salesBoard.teamBoard.mockRejectedValue(new Error('404'));
    renderPage(managerUser);
    await screen.findByText('โหลดงานไม่สำเร็จ');

    fireEvent.click(screen.getByRole('button', { name: 'เปิดแบบตาราง' }));

    expect(await screen.findByRole('region', { name: 'ขอบเขตและตัวกรองงานทั้งหมด' })).toBeTruthy();
  });

  it.each([['account', { id: 4, employeeId: 4, role: 'account' }], ['import', { id: 3, employeeId: 3, role: 'import' }]])(
    '%s gets the table only: no board, no board data, no view toggle',
    async (_role, user) => {
      renderPage(user, '/tickets?view=board');

      expect(await screen.findByRole('region', { name: 'ขอบเขตและตัวกรองงานทั้งหมด' })).toBeTruthy();
      expect(screen.queryByTestId('deal-board')).toBeNull();
      expect(screen.queryByRole('button', { name: /บอร์ด/ })).toBeNull();
      expect(api.salesBoard.myWork).not.toHaveBeenCalled();
      expect(api.salesBoard.teamBoard).not.toHaveBeenCalled();
    },
  );

  it('links the paused, lost and cancelled counts to the table filtered to each', async () => {
    renderPage(salesUser);
    await board();

    const closed = await screen.findByRole('navigation', { name: 'งานที่พักไว้หรือปิดแล้ว' });
    await waitFor(() => expect(within(closed).getByRole('link', { name: /เสียงาน 2/ })).toBeTruthy());
    expect(within(closed).getByRole('link', { name: /พักไว้ 1/ }).getAttribute('href')).toBe('/tickets?view=table&life=ON_HOLD');
    expect(within(closed).getByRole('link', { name: /ยกเลิก 1/ }).getAttribute('href')).toBe('/tickets?view=table&life=CANCELLED');
    // No dormant deal: no พักยาว segment.
    expect(within(closed).queryByRole('link', { name: /พักยาว/ })).toBeNull();

    fireEvent.click(within(closed).getByRole('link', { name: /เสียงาน 2/ }));

    expect(await screen.findByRole('region', { name: 'ขอบเขตและตัวกรองงานทั้งหมด' })).toBeTruthy();
    expect(screen.queryByTestId('deal-board')).toBeNull();
  });

  // ── table toggle and the remembered view ───────────────────────────────

  it('ตาราง switches to the existing table, remembers it per user, and บอร์ด switches back', async () => {
    renderPage(salesUser);
    await board();

    fireEvent.click(screen.getByRole('button', { name: /ตาราง/ }));

    expect(await screen.findByRole('region', { name: 'ขอบเขตและตัวกรองงานทั้งหมด' })).toBeTruthy();
    expect(screen.queryByTestId('deal-board')).toBeNull();
    expect(screen.getByRole('heading', { level: 1, name: 'งานทั้งหมด' })).toBeTruthy();
    expect(store.getItem(viewStorageKey(salesUser))).toBe('table');

    fireEvent.click(screen.getByRole('button', { name: /บอร์ด/ }));

    expect(await board()).toBeTruthy();
    expect(store.getItem(viewStorageKey(salesUser))).toBe('board');
  });

  it('reopens on the table when that was the last choice', async () => {
    store.setItem(viewStorageKey(salesUser), 'table');
    renderPage(salesUser);

    expect(await screen.findByRole('region', { name: 'ขอบเขตและตัวกรองงานทั้งหมด' })).toBeTruthy();
    expect(screen.queryByTestId('deal-board')).toBeNull();
  });

  it('a deep link with a table-only filter (?owner=) opens the table even when the board was remembered', async () => {
    store.setItem(viewStorageKey(managerUser), 'board');
    renderPage(managerUser, '/tickets?owner=8');

    expect(await screen.findByRole('region', { name: 'ขอบเขตและตัวกรองงานทั้งหมด' })).toBeTruthy();
    expect(screen.queryByTestId('deal-board')).toBeNull();
    expect(api.salesBoard.teamBoard).not.toHaveBeenCalled();
  });

  it('still works when localStorage throws: board by default, toggle still switches', async () => {
    vi.stubGlobal('localStorage', throwingStorage());
    renderPage(salesUser);

    await board();
    fireEvent.click(screen.getByRole('button', { name: /ตาราง/ }));

    expect(await screen.findByRole('region', { name: 'ขอบเขตและตัวกรองงานทั้งหมด' })).toBeTruthy();
  });

  // ── phone ──────────────────────────────────────────────────────────────

  it('keeps every board control at a 44px tap target on a phone', async () => {
    renderPage(salesUser);
    await board();

    for (const name of ['ไตรมาสนี้', 'ทั้งหมด', 'เฉพาะงานเงียบ 1']) {
      expect(screen.getByRole('button', { name }).className).toMatch(/mobile:min-h-11|min-h-11/);
    }
    const closed = screen.getByRole('navigation', { name: 'งานที่พักไว้หรือปิดแล้ว' });
    await waitFor(() => expect(within(closed).getAllByRole('link').length).toBeGreaterThan(0));
    for (const link of within(closed).getAllByRole('link')) {
      expect(link.className).toMatch(/mobile:min-h-11|min-h-11/);
    }
  });
});
