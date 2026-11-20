import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketListPage } from './TicketListPage.jsx';
import { api } from '../../api/index.js';
import { bangkokTodayIso } from '../../utils/format.js';

globalThis.React = React;

const realMatchMedia = window.matchMedia;

afterEach(() => {
  window.matchMedia = realMatchMedia;
});

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  // Imported inside the factory, not at the top of the file: vi.mock is hoisted above every import.
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      // The pipeline enumeration (GET /api/meta/deal-stages), served as the SAME canned payload
      // mockApi serves (data/dealStageCatalog.js, pinned against DealStage.java by
      // stageCatalog.test.js), so this fixture cannot describe a pipeline the backend lacks.
      meta: {
        dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG),
      },
      tickets: {
        list: vi.fn(),
        create: vi.fn(),
      },
      salesBoard: {
        teamOverview: vi.fn(),
      },
    },
  };
});

// TicketCreateModal owns its own multi-field customer/catalog search form; stubbed with a single
// button so this test can drive TicketListPage's create mutation (invalidate + refetch).
vi.mock('./TicketCreateModal.jsx', () => ({
  TicketCreateModal: ({ onSubmit }) => (
    <button type="button" onClick={() => onSubmit({ title: 'โครงการใหม่' })}>
      ยืนยันสร้าง (stub)
    </button>
  ),
}));

const salesUser = {
  employeeId: 1,
  name: 'Sales ทดสอบ',
  role: 'sales',
};

const importUser = {
  employeeId: 3,
  name: 'Import ทดสอบ',
  role: 'import',
};

const accountUser = {
  employeeId: 4,
  name: 'Account ทดสอบ',
  role: 'account',
};

const managerUser = {
  employeeId: 2,
  name: 'Manager ทดสอบ',
  role: 'sales_manager',
};

// handleCreate navigates to the new deal's page; MemoryRouter doesn't touch window.location,
// so surface the current path via useLocation for assertions.
function LocationDisplay() {
  const location = useLocation();
  return <div data-testid="location-display">{location.pathname}{location.search}</div>;
}

function renderTicketListPage(user = salesUser, initialEntries = ['/tickets'], children = null) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        <TicketListPage user={user} showToast={vi.fn()} />
        {children}
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TicketListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({
      tickets: [
        {
          id: 501,
          code: 'PR-2026-0501',
          title: 'โครงการทดสอบ',
          customerName: 'บริษัท ทดสอบ จำกัด',
          status: 'submitted',
          createdByName: 'สมชาย ใจดี',
          createdAt: '2026-07-01T09:00:00.000Z',
          salesStage: 'QUOTE_DESIGN_SIDE',
          lifecycle: 'ACTIVE',
          lostReason: null,
          overdue: false,
          fulfillmentStatus: null,
          stageUpdatedAt: '2026-07-01T09:00:00.000Z',
        },
        {
          id: 503,
          code: 'PR-2026-0503',
          title: 'โครงการเกินกำหนด',
          customerName: 'บริษัท เกินกำหนด จำกัด',
          status: 'quotation_issued',
          createdByName: 'สมชาย ใจดี',
          createdAt: '2026-07-02T09:00:00.000Z',
          salesStage: 'DELIVERY_SCHEDULING',
          lifecycle: 'ACTIVE',
          lostReason: null,
          overdue: true,
          fulfillmentStatus: 'PARTIALLY_DELIVERED',
          stageUpdatedAt: '2026-07-02T09:00:00.000Z',
        },
        {
          id: 504,
          code: 'PR-2026-0504',
          title: 'โครงการพักไว้',
          customerName: 'บริษัท พักไว้ จำกัด',
          status: 'in_review',
          createdByName: 'สมชาย ใจดี',
          createdAt: '2026-07-03T09:00:00.000Z',
          salesStage: 'NEGOTIATION',
          lifecycle: 'ON_HOLD',
          lostReason: null,
          overdue: false,
          fulfillmentStatus: null,
          stageUpdatedAt: '2026-07-03T09:00:00.000Z',
        },
        {
          id: 505,
          code: 'PR-2026-0505',
          title: 'โครงการเงียบ',
          customerName: 'บริษัท เงียบ จำกัด',
          status: 'in_review',
          createdByName: 'สมชาย ใจดี',
          createdAt: '2026-07-04T09:00:00.000Z',
          salesStage: 'NEGOTIATION',
          lifecycle: 'DORMANT',
          lostReason: null,
          overdue: false,
          fulfillmentStatus: null,
          stageUpdatedAt: '2026-07-04T09:00:00.000Z',
        },
        {
          id: 506,
          code: 'PR-2026-0506',
          title: 'ดีลใหม่ยังไม่มีขอราคา',
          customerName: 'บริษัท ดีลใหม่ จำกัด',
          // Created deals stay on the legacy status 'draft'; real progress lives on
          // salesStage/PricingRequest.
          status: 'draft',
          createdByName: 'สมชาย ใจดี',
          createdAt: '2026-07-05T09:00:00.000Z',
          salesStage: 'LEAD_APPROACH',
          lifecycle: 'ACTIVE',
          lostReason: null,
          overdue: false,
          fulfillmentStatus: null,
          stageUpdatedAt: '2026-07-05T09:00:00.000Z',
        },
      ],
    });
    api.tickets.create.mockResolvedValue({ ticket: { summary: { id: 502, code: 'PR-2026-0502' } } });
  });

  it('shows create for Sales and hides it for non-Sales ticket-list roles', async () => {
    const rolesWithoutCreate = [
      { employeeId: 2, name: 'Manager ทดสอบ', role: 'sales_manager' },
      { employeeId: 3, name: 'Import ทดสอบ', role: 'import' },
      { employeeId: 4, name: 'Account ทดสอบ', role: 'account' },
      { employeeId: 5, name: 'CEO ทดสอบ', role: 'ceo' },
    ];

    const { unmount } = renderTicketListPage(salesUser);
    expect(await screen.findByRole('button', { name: /งานใหม่/ })).toBeTruthy();
    unmount();

    for (const roleUser of rolesWithoutCreate) {
      const view = renderTicketListPage(roleUser);
      await screen.findByRole('heading', { name: 'งานทั้งหมด' });
      expect(screen.queryByRole('button', { name: /งานใหม่/ })).toBeNull();
      view.unmount();
    }
  });

  it('H1: account row links open the finance deal page; other roles keep the ticket page', async () => {
    const first = renderTicketListPage(accountUser);
    const accountLink = await screen.findByRole('link', { name: 'บริษัท เกินกำหนด จำกัด' });
    expect(accountLink.getAttribute('href')).toBe('/finance/deals/503');
    first.unmount();

    renderTicketListPage(salesUser);
    const salesLink = await screen.findByRole('link', { name: 'บริษัท เกินกำหนด จำกัด' });
    expect(salesLink.getAttribute('href')).toBe('/tickets/503');
  });

  it('does not render role-sensitive financial internals in sales-visible rows or cards', async () => {
    api.tickets.list.mockResolvedValueOnce({
      tickets: [{
        id: 508,
        code: 'PR-2026-0508',
        title: 'ดีลมีข้อมูลการเงิน',
        customerName: 'บริษัท มีต้นทุน จำกัด',
        projectName: 'โครงการไม่แสดงต้นทุน',
        status: 'quotation_issued',
        createdByName: 'สมชาย ใจดี',
        createdAt: '2026-07-08T09:00:00.000Z',
        salesStage: 'QUOTE_BUYER',
        lifecycle: 'ACTIVE',
        lostReason: null,
        overdue: false,
        fulfillmentStatus: null,
        stageUpdatedAt: '2026-07-08T09:00:00.000Z',
        costAmount: 'ต้นทุนลับ 999999',
        marginAmount: 'กำไรขั้นต้นลับ',
        financeNote: 'หมายเหตุการเงินภายใน',
      }],
    });

    renderTicketListPage(salesUser);

    expect(await screen.findByText('บริษัท มีต้นทุน จำกัด')).toBeTruthy();
    expect(screen.queryByText(/ต้นทุนลับ/)).toBeNull();
    expect(screen.queryByText(/กำไรขั้นต้นลับ/)).toBeNull();
    expect(screen.queryByText(/หมายเหตุการเงินภายใน/)).toBeNull();
  });

  /**
   * The win-weighted forecast must come from the SERVER's `effectiveWinProbability`, never from a
   * client-side re-derivation of it (the most money-shaped number on the deal list).
   *
   * The fixture is deliberately WRONG-WAY-ROUND: `effectiveWinProbability: 25` on a NEGOTIATION
   * deal, where the stage table says 70. Any re-derivation (from the stage, the override, or a
   * copy of WIN_PROBABILITY_DEFAULTS) yields a different total, so the test cannot pass unless
   * the served field is what is read.
   */
  it('weights the forecast by the server-sent effectiveWinProbability, not a re-derived one', async () => {
    api.tickets.list.mockResolvedValueOnce({
      tickets: [
        {
          id: 701,
          code: 'PR-2026-0701',
          title: 'ดีลคาดการณ์',
          customerName: 'บริษัท คาดการณ์ จำกัด',
          status: 'in_review',
          createdByName: 'สมชาย ใจดี',
          createdAt: '2026-07-06T09:00:00.000Z',
          salesStage: 'NEGOTIATION',
          lifecycle: 'ACTIVE',
          lostReason: null,
          overdue: false,
          fulfillmentStatus: null,
          stageUpdatedAt: '2026-07-06T09:00:00.000Z',
          stale: false,
          amountPayable: '1000000',
          // 25, NOT the 70 WIN_PROBABILITY_DEFAULTS.NEGOTIATION would give. The disagreement is the assertion.
          winProbabilityOverride: null,
          effectiveWinProbability: 25,
        },
      ],
    });

    renderTicketListPage(managerUser);
    await screen.findByText('บริษัท คาดการณ์ จำกัด');

    const forecast = await screen.findByText(/คาดการณ์ถ่วงน้ำหนัก/);
    // 1,000,000 × 25% = 250,000. A stage-derived 70% would read 700,000.
    expect(forecast.textContent).toContain('250,000');
    expect(forecast.textContent).not.toContain('700,000');
  });

  // The "all phases" chip is labelled `ทั้งหมด` with no count: it lists every deal including
  // CLOSED_LOST/CANCELLED/COMPLETED, and the single statement of the filtered total is
  // DataTable's own footer.
  describe('movement range (sales / sales_manager / ceo)', () => {
    const TODAY = bangkokTodayIso();
    const moveDeal = (id, name, extra) => ({
      id, code: `MV-${id}`, title: `โครงการ ${id}`, customerName: name, status: 'in_review',
      createdById: 11, createdByName: 'สมชาย', createdAt: '2026-07-01T09:00:00.000Z',
      salesStage: 'NEGOTIATION', lifecycle: 'ACTIVE', lostReason: null, overdue: false, fulfillmentStatus: null,
      stageUpdatedAt: '2026-07-01T09:00:00.000Z', amountPayable: 100, nextFollowUpAt: null, lastActivityAt: null,
      stageChangedInRange: false, activityInRange: false, ...extra,
    });
    const MOVE_DEALS = [
      moveDeal(701, 'ลูกค้า-ขยับ', { stageChangedInRange: true, amountPayable: 1000 }),
      moveDeal(702, 'ลูกค้า-กิจกรรม', { activityInRange: true, amountPayable: 0, lastActivityAt: TODAY }),
      moveDeal(703, 'ลูกค้า-เงียบ', { amountPayable: 500, createdById: 12, createdByName: 'สมหญิง' }),
      moveDeal(704, 'ลูกค้า-นัด', { amountPayable: 250, nextFollowUpAt: TODAY }),
      moveDeal(705, 'ลูกค้า-ปิดแพ้', { amountPayable: 9999, lifecycle: 'CLOSED_LOST' }),
    ];
    const weekChips = () => within(screen.getByLabelText('ตัวกรองความเคลื่อนไหว'));
    let shown = MOVE_DEALS; // the fixture whose names rowNames() looks for
    const rowNames = () => shown.map((d) => d.customerName).filter((n) => screen.queryByText(n));
    const columnHeaders = () => screen.getAllByRole('columnheader').map((th) => th.textContent.trim());
    const lastListArgs = () => api.tickets.list.mock.calls.at(-1)[0];

    beforeEach(() => {
      shown = MOVE_DEALS;
      api.tickets.list.mockResolvedValue({ tickets: MOVE_DEALS });
    });

    // Every movement signal considers ACTIVE deals only, so a chip never lists a paused deal.
    it('a week chip never lists a non-ACTIVE deal: ?owner&week=activity shows only the ACTIVE flagged deal', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [
          moveDeal(721, 'ลูกค้า-ใช้งาน', { activityInRange: true }),
          moveDeal(722, 'ลูกค้า-พักไว้', { activityInRange: true, stageChangedInRange: true, lifecycle: 'ON_HOLD' }),
        ],
      });
      renderTicketListPage(managerUser, ['/tickets?owner=11&week=activity']);
      await screen.findByText('ลูกค้า-ใช้งาน');
      expect(screen.queryByText('ลูกค้า-พักไว้')).toBeNull();
      expect(weekChips().getByRole('button', { name: /^มีกิจกรรม/ }).textContent).toMatch(/1/);
      expect(weekChips().getByRole('button', { name: /^ขยับขั้น/ }).textContent).toMatch(/0/);
    });

    it('?owner=<id> lists only that owner, shows a removable "ผู้ดูแล: ชื่อ" chip, and counts respect it', async () => {
      renderTicketListPage(managerUser, ['/tickets?owner=12'], <LocationDisplay />);
      await screen.findByText('ลูกค้า-เงียบ');
      expect(rowNames()).toEqual(['ลูกค้า-เงียบ']);
      expect(weekChips().getByRole('button', { name: /^ขยับขั้น/ }).textContent).toMatch(/0/);
      expect(weekChips().getByRole('button', { name: /^ไม่เคลื่อนไหว/ }).textContent).toMatch(/1/);

      fireEvent.click(screen.getByRole('button', { name: /ผู้ดูแล: สมหญิง/ }));
      await waitFor(() => expect(screen.getByTestId('location-display').textContent).not.toContain('owner='));
      await waitFor(() => expect(rowNames()).toHaveLength(5));
    });

    it('sales_manager gets the range row and the API is called with movement params', async () => {
      renderTicketListPage(managerUser, ['/tickets?inbox=0']);
      await screen.findByText('ลูกค้า-ขยับ');
      expect(screen.getByRole('group', { name: 'ช่วงวันที่' })).toBeTruthy();
      expect(lastListArgs()).toHaveProperty('movementFrom');
    });

    // UNCHANGED ROLE: account/import render exactly as before.
    it.each([['account', accountUser], ['import', importUser]])('%s: no range row, old columns, API called without movement params', async (_role, user) => {
      renderTicketListPage(user, ['/tickets?inbox=0']); // inbox=0: every deal the role may read, not the worklist subset
      await screen.findByText('ลูกค้า-ขยับ');
      expect(screen.queryByRole('group', { name: 'ช่วงวันที่' })).toBeNull();
      expect(screen.queryByLabelText('ตัวกรองความเคลื่อนไหว')).toBeNull();
      const headers = columnHeaders().join('|');
      expect(headers).toContain('ผู้ดูแล');
      expect(headers).toContain('ความคืบหน้า');
      expect(lastListArgs()).not.toHaveProperty('movementFrom');
      expect(lastListArgs()).not.toHaveProperty('movementTo');
    });
  });
});
