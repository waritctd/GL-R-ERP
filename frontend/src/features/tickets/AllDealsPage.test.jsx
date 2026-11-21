import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AllDealsPage } from './AllDealsPage.jsx';
import { api } from '../../api/index.js';
import { currentQuarter } from '../salesBoard/boardFormat.js';

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

  it('a read-all flag holder with the sales role gets the team board (not her own my-work board), keeping the create button', async () => {
    renderPage({ ...salesUser, readAllSales: true });

    await board();
    expect(api.salesBoard.teamBoard).toHaveBeenCalled();
    expect(api.salesBoard.myWork).not.toHaveBeenCalled();
    expect(within(await screen.findByTestId('board-card-22')).getByTestId('board-card-owner').textContent).toContain('สมหมาย');
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
});
