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

function stubMobile() {
  window.matchMedia = (query) => ({
    matches: query === '(max-width: 720px)',
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  });
}

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

// The progress column, route-aware progress bar and plain mobile card belong to the layout of the roles that
// do NOT use the movement list (sales / sales_manager / ceo have their own columns, covered below).
const renderLegacyLayout = () => renderTicketListPage(importUser, ['/tickets?inbox=0']);

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

  it('renders rows from a mocked api.tickets.list', async () => {
    renderTicketListPage();

    expect(screen.getByRole('heading', { level: 1, name: 'รายการดีล' })).toBeTruthy();
    expect(await screen.findByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();
    expect(screen.getByText('PR-2026-0501')).not.toBeNull();
    // sales now also sends the default movement range (see 'movement range' below); the plain
    // no-params call is what an unchanged role (account/import) still makes.
    expect(api.tickets.list).toHaveBeenCalledWith(expect.objectContaining({
      movementFrom: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
      movementTo: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
    }));
  });

  // The desktop table does not end every row in an identical `เปิด ›` button; the row itself is a
  // mouse-only open target via DataTable's onRowClick, like /finance ("the action varies by row").
  it('opens a deal by clicking the row itself, with no repeated เปิด button', async () => {
    renderTicketListPage(salesUser, ['/tickets'], <LocationDisplay />);

    await screen.findByText('บริษัท ทดสอบ จำกัด');
    // What must stay gone is the identical-on-every-row `<button>เปิด ›</button>`.
    const literalOpenButtons = screen.queryAllByRole('button', { name: /^เปิด/ })
      .filter((el) => el.tagName === 'BUTTON');
    expect(literalOpenButtons).toHaveLength(0);

    fireEvent.click(screen.getByText('บริษัท ทดสอบ จำกัด').closest('tr'));

    await waitFor(() => expect(screen.getByTestId('location-display').textContent).toBe('/tickets/501'));
  });

  // `role="button"` on the `<tr>` would prune every descendant (customer name, ผู้ดูแล, the stage
  // cell incl. the "เงียบ" stale badge, the "ขั้นตอน N/15" readout) out of the accessibility
  // tree. The row must not be focusable or carry a button role; the identity cell's own <Link> is
  // the keyboard/assistive-tech affordance (and the tappable-row cue for the 721–1040px band).
  it('exposes the deal identity as a real link, not a focusable/labelled row (FIX A)', async () => {
    renderTicketListPage(salesUser, ['/tickets'], <LocationDisplay />);

    await screen.findByText('บริษัท ทดสอบ จำกัด');
    const row = screen.getByText('บริษัท ทดสอบ จำกัด').closest('tr');
    expect(row.hasAttribute('role')).toBe(false);
    expect(row.hasAttribute('tabindex')).toBe(false);
    expect(row.hasAttribute('aria-label')).toBe(false);

    const identityLink = screen.getByRole('link', { name: 'บริษัท ทดสอบ จำกัด' });
    expect(identityLink.closest('tr')).toBe(row);
    expect(identityLink.getAttribute('href')).toBe('/tickets/501');

    fireEvent.click(identityLink);
    await waitFor(() => expect(screen.getByTestId('location-display').textContent).toBe('/tickets/501'));
  });

  it('shows create for Sales and hides it for non-Sales ticket-list roles', async () => {
    const rolesWithoutCreate = [
      { employeeId: 2, name: 'Manager ทดสอบ', role: 'sales_manager' },
      { employeeId: 3, name: 'Import ทดสอบ', role: 'import' },
      { employeeId: 4, name: 'Account ทดสอบ', role: 'account' },
      { employeeId: 5, name: 'CEO ทดสอบ', role: 'ceo' },
    ];

    const { unmount } = renderTicketListPage(salesUser);
    expect(await screen.findByRole('button', { name: /สร้างดีลใหม่/ })).toBeTruthy();
    unmount();

    for (const roleUser of rolesWithoutCreate) {
      const view = renderTicketListPage(roleUser);
      await screen.findByRole('heading', { name: 'รายการดีล' });
      expect(screen.queryByRole('button', { name: /สร้างดีลใหม่/ })).toBeNull();
      view.unmount();
    }
  });

  it('keeps search and phase filters URL-backed in the consolidated filter bar', async () => {
    renderTicketListPage(salesUser, ['/tickets?q=เกินกำหนด&phase=5'], <LocationDisplay />);

    expect(await screen.findByText('บริษัท เกินกำหนด จำกัด')).not.toBeNull();
    expect(screen.queryByText('บริษัท ทดสอบ จำกัด')).toBeNull();
    expect(screen.getByRole('searchbox', { name: 'ค้นหาดีล' }).value).toBe('เกินกำหนด');
    expect(screen.getByText('ค้นหาในรายการ')).toBeTruthy();
    expect(screen.getByText('เฟส 5: ส่งมอบและปิดงาน')).toBeTruthy();

    fireEvent.change(screen.getByRole('searchbox', { name: 'ค้นหาดีล' }), { target: { value: 'พักไว้' } });
    expect(screen.getByTestId('location-display').textContent).toContain('q=%E0%B8%9E%E0%B8%B1%E0%B8%81%E0%B9%84%E0%B8%A7%E0%B9%89');

    fireEvent.click(screen.getByRole('button', { name: /เฟส 4/ }));
    expect(screen.getByTestId('location-display').textContent).toContain('phase=4');
  });

  // DataTable's footer only knows N matched; TicketListPage passes the unfiltered
  // `allDeals.length` as `unfilteredTotal` so the footer shows "ตรงเงื่อนไข N จาก M รายการ".
  it('shows how many deals were filtered out of the whole role-scoped population', async () => {
    renderTicketListPage(salesUser, ['/tickets?q=เกินกำหนด&phase=5']);

    expect(await screen.findByText('บริษัท เกินกำหนด จำกัด')).not.toBeNull();
    // DataTable renders this summary twice (always-mounted sr-only aria-live region and the
    // aria-hidden visible footer), so assert on the count of matches rather than one node.
    expect(screen.getAllByText(/แสดง 1–1 จาก 1 รายการ \(กรองจาก 5\)/)).toHaveLength(2);
  });

  it('omits the filtered-out clause when nothing narrows the list', async () => {
    renderTicketListPage(salesUser);

    await screen.findByText('บริษัท ทดสอบ จำกัด');
    expect(screen.getAllByText(/แสดง 1–5 จาก 5 รายการ/)).toHaveLength(2);
    expect(screen.queryByText(/กรองจาก/)).toBeNull();
  });

  it('preserves import/account worklist scope and hides create when not permitted', async () => {
    renderTicketListPage(accountUser, ['/tickets'], <LocationDisplay />);

    expect(await screen.findByText('บริษัท เกินกำหนด จำกัด')).not.toBeNull();
    expect(screen.queryByText('บริษัท ทดสอบ จำกัด')).toBeNull();
    expect(screen.queryByRole('button', { name: /สร้างดีลใหม่/ })).toBeNull();
    const scopeToggle = within(screen.getByLabelText('เลือกขอบเขตรายการ'));
    expect(scopeToggle.getByRole('button', { name: /ต้องดำเนินการ/ }).getAttribute('aria-pressed')).toBe('true');

    fireEvent.click(scopeToggle.getByRole('button', { name: /ทั้งหมด/ }));
    await waitFor(() => expect(screen.getByTestId('location-display').textContent).toContain('inbox=0'));
    await waitFor(() => expect(screen.getByText('บริษัท ทดสอบ จำกัด')).toBeTruthy());
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

  it('initializes import/account worklist scope from the URL', async () => {
    renderTicketListPage(importUser, ['/tickets?inbox=0']);

    expect(await screen.findByText('ขอบเขตฝ่ายนำเข้า')).toBeTruthy();
    const scopeToggle = within(screen.getByLabelText('เลือกขอบเขตรายการ'));
    expect(scopeToggle.getByRole('button', { name: /ทั้งหมด/ }).getAttribute('aria-pressed')).toBe('true');
    expect(scopeToggle.getByRole('button', { name: /ต้องดำเนินการ/ }).getAttribute('aria-pressed')).toBe('false');
  });

  it('shows the import scope labels without changing the existing inbox predicate', async () => {
    renderTicketListPage(importUser);

    expect(await screen.findByText('ขอบเขตฝ่ายนำเข้า')).toBeTruthy();
    const scopeToggle = within(screen.getByLabelText('เลือกขอบเขตรายการ'));
    expect(scopeToggle.getByRole('button', { name: /ต้องดำเนินการ/ }).getAttribute('aria-pressed')).toBe('true');
    expect(scopeToggle.getByRole('button', { name: /ทั้งหมด/ })).toBeTruthy();
  });

  it('renders a purpose-built mobile card with long Thai identity, readable work stage, and focus-restoring filters', async () => {
    stubMobile();
    const longDeal = {
      id: 507,
      code: 'PR-2026-0507',
      title: 'โครงการทดสอบมือถือ',
      customerName: 'บริษัท โรงแรมและศูนย์ประชุมมหานครริมแม่น้ำเจ้าพระยาขนาดใหญ่พิเศษ จำกัด',
      projectName: 'โครงการปรับปรุงพื้นที่ต้อนรับหลักชั้นล่างและโถงทางเดินเชื่อมอาคารสำนักงานฝั่งตะวันออก',
      status: 'quotation_issued',
      createdByName: 'คุณสมหมาย ขายดีมากประสบการณ์ประจำภูมิภาคกรุงเทพและปริมณฑล',
      createdAt: '2026-07-06T09:00:00.000Z',
      salesStage: 'QUOTE_DESIGN_SIDE',
      lifecycle: 'ACTIVE',
      lostReason: null,
      overdue: false,
      fulfillmentStatus: null,
      stageUpdatedAt: '2026-07-06T09:00:00.000Z',
    };
    api.tickets.list.mockResolvedValueOnce({ tickets: [longDeal] });

    const { container } = renderLegacyLayout();

    const card = await screen.findByRole('listitem');
    const cardScope = within(card);
    expect(container.querySelector('.data-table-table')).toBeNull();
    expect(cardScope.getByText(longDeal.customerName).className).toContain('ticket-card-title');
    expect(cardScope.getByText(longDeal.customerName).className).not.toContain('truncate');
    expect(cardScope.getByText(longDeal.projectName).className).toContain('ticket-card-project');
    expect(cardScope.getByText(longDeal.createdByName, { exact: false }).className).toContain('ticket-card-owner');
    expect(cardScope.getByText(/เสนอราคาผู้ออกแบบ/)).toBeTruthy();
    expect(cardScope.getByRole('button', { name: 'เปิดดีล PR-2026-0507' })).toBeTruthy();
    expect(screen.getByRole('searchbox', { name: 'ค้นหาดีล' })).toBeTruthy();
    // (the create button is sales-only; its visibility is pinned in 'shows create for Sales ...')

    const filterToggle = screen.getByRole('button', { name: /^ตัวกรอง/ });
    filterToggle.focus();
    fireEvent.click(filterToggle);
    // This case stubs the mobile viewport, where the sheet is a modal dialog
    // rather than the desktop inline region (see the modal-contract suite).
    const filterSheet = screen.getByRole('dialog', { name: 'ตัวกรองเพิ่มเติม' });
    expect(filterSheet).toBeTruthy();

    fireEvent.click(within(filterSheet).getByRole('button', { name: 'ปิดตัวกรองเพิ่มเติม' }));
    await waitFor(() => expect(document.activeElement).toBe(filterToggle));
  });

  it('invalidates and refetches the ticket list after creating a ticket', async () => {
    const showToast = vi.fn();
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/tickets']}>
          <TicketListPage user={salesUser} showToast={showToast} />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await screen.findByText('บริษัท ทดสอบ จำกัด');
    expect(api.tickets.list).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: /สร้างดีลใหม่/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันสร้าง (stub)' }));

    await waitFor(() => expect(api.tickets.create).toHaveBeenCalledWith({ title: 'โครงการใหม่' }));
    await waitFor(() => expect(api.tickets.list).toHaveBeenCalledTimes(2));
    expect(showToast).toHaveBeenCalledWith('success', 'สร้างดีลเรียบร้อย');
  });

  it('distinguishes filtered-empty copy from a truly empty ticket list', async () => {
    const { unmount } = renderTicketListPage(salesUser, ['/tickets?q=ไม่พบแน่นอน']);
    // EmptyState's visible `<strong>` title is `aria-hidden` when `titleAnnouncedElsewhere` is set
    // (DataTable always passes it), so it is never itself what a screen reader hears.
    expect(await screen.findByText('ไม่พบดีลที่ตรงกับตัวกรอง')).toBeTruthy();
    // All 5 role-scoped deals filtered out by the search: the live-region announcement, unlike the
    // visible EmptyState heading, carries the "(กรองจาก 5)" denominator, so the two are different strings.
    expect(document.querySelector('[aria-live="polite"]').textContent).toBe('ไม่พบดีลที่ตรงกับตัวกรอง (กรองจาก 5)');
    expect(screen.getByText('ไม่พบดีลที่ตรงกับคำค้นหาและตัวกรองที่เลือก')).toBeTruthy();
    unmount();

    api.tickets.list.mockResolvedValueOnce({ tickets: [] });
    renderTicketListPage(salesUser);
    // A truly empty list has no unfiltered population to denominate against, so no clause is
    // appended and the heading and live-region text are identical: this distinguishes "filtered
    // down to nothing" from "there was nothing to begin with".
    expect((await screen.findAllByText('ไม่มีดีล')).length).toBe(2);
    expect(document.querySelector('[aria-live="polite"]').textContent).toBe('ไม่มีดีล');
    expect(screen.getByText('ยังไม่มีดีลในเงื่อนไขที่เลือก')).toBeTruthy();
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

  // A newly created deal starts as an empty DRAFT with no price-request flow of its own;
  // handleCreate lands the user on the deal page so the PricingRequestPanel prompt is next.
  it('navigates to the new deal page after creating a ticket', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/tickets']}>
          <TicketListPage user={salesUser} showToast={vi.fn()} />
          <LocationDisplay />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await screen.findByText('บริษัท ทดสอบ จำกัด');
    fireEvent.click(screen.getByRole('button', { name: /สร้างดีลใหม่/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันสร้าง (stub)' }));

    await waitFor(() => expect(screen.getByTestId('location-display').textContent).toBe('/tickets/502'));
  });

  // The legacy `status` sublabel under the stage badge is gone for EVERY deal: the stage badge and
  // the payment/fulfilment chips say where the deal is, the old status only misled.
  it('shows no legacy status sublabel under the stage badge, for any status', async () => {
    renderTicketListPage();

    expect(await screen.findByText('บริษัท ดีลใหม่ จำกัด')).not.toBeNull();
    expect(screen.queryByText('แบบร่าง')).toBeNull();
    expect(screen.queryByText('รอฝ่ายนำเข้ารับเรื่อง')).toBeNull(); // fixture deal with status 'submitted'
    expect(screen.queryByText('ออกใบเสนอราคาแล้ว')).toBeNull(); // fixture deals with status 'quotation_issued'
  });

  it('filters by lifecycle, overdue, and partial delivery chips (after expanding "ตัวกรองเพิ่มเติม")', async () => {
    renderTicketListPage();

    expect(await screen.findByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /ตัวกรอง/ }));

    fireEvent.click(screen.getByRole('button', { name: /พักไว้ชั่วคราว/ }));
    expect(screen.getByText('บริษัท พักไว้ จำกัด')).not.toBeNull();
    expect(screen.queryByText('บริษัท ทดสอบ จำกัด')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /พักไว้ชั่วคราว/ }));
    fireEvent.click(screen.getByRole('button', { name: /เกินกำหนด/ }));
    expect(screen.getByText('บริษัท เกินกำหนด จำกัด')).not.toBeNull();
    expect(screen.queryByText('บริษัท พักไว้ จำกัด')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /เกินกำหนด/ }));
    fireEvent.click(screen.getByRole('button', { name: /ส่งมอบบางส่วน/ }));
    expect(screen.getByText('บริษัท เกินกำหนด จำกัด')).not.toBeNull();
    expect(screen.queryByText('บริษัท เงียบ จำกัด')).toBeNull();
  });

  // LIFECYCLE/FLAGS live behind a collapsed "ตัวกรองเพิ่มเติม" expander, closed by default.
  describe('LIFECYCLE/FLAGS de-emphasis ("ตัวกรองเพิ่มเติม")', () => {
    it('keeps the lifecycle/flags chips out of the always-visible primary bar by default', async () => {
      renderTicketListPage();
      expect(await screen.findByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();

      const toggle = screen.getByRole('button', { name: /ตัวกรอง/ });
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(screen.queryByRole('button', { name: /พักไว้ชั่วคราว/ })).toBeNull();
      expect(screen.queryByRole('button', { name: /^เกินกำหนด/ })).toBeNull();
      expect(screen.queryByRole('button', { name: /รอส่งมอบ/ })).toBeNull();
      expect(screen.queryByRole('button', { name: /ส่งมอบบางส่วน/ })).toBeNull();
    });

    it('reveals lifecycle/flags chips on toggle, and they still filter the table', async () => {
      renderTicketListPage();
      expect(await screen.findByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();

      const toggle = screen.getByRole('button', { name: /ตัวกรอง/ });
      fireEvent.click(toggle);
      expect(toggle.getAttribute('aria-expanded')).toBe('true');

      const lifecycleChip = screen.getByRole('button', { name: /พักไว้ชั่วคราว/ });
      expect(lifecycleChip).not.toBeNull();
      fireEvent.click(lifecycleChip);
      expect(screen.getByText('บริษัท พักไว้ จำกัด')).not.toBeNull();
      expect(screen.queryByText('บริษัท ทดสอบ จำกัด')).toBeNull();

      // Collapsing closes the sheet even with a filter applied (an `open || hasActiveFilter`
      // derivation would make it impossible to dismiss on the mobile modal). The applied filter is
      // not hidden by closing: it stays in the "ตัวกรองที่ใช้" summary row, the toggle's count
      // badge, and keeps filtering the table.
      fireEvent.click(toggle);
      expect(screen.queryByRole('button', { name: /พักไว้ชั่วคราว/ })).toBeNull();
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(within(toggle).getByText('1')).not.toBeNull();
      expect(screen.getByLabelText('ตัวกรองที่ใช้')).not.toBeNull();
      expect(screen.getByText('สถานะงาน: พักไว้ชั่วคราว')).not.toBeNull();
      expect(screen.getByText('บริษัท พักไว้ จำกัด')).not.toBeNull();
    });

    // "รอส่งมอบ" (awaiting_delivery, stages 13-14 handoff to sales): clicks a FLAG chip and checks
    // the resulting rows. Self-contained fixture (mockResolvedValueOnce) rather than the shared
    // 5-deal beforeEach list, so the "5 รายการ"/"กรองจาก 5" total-count assertions elsewhere hold.
    it('รอส่งมอบ filters to GOODS_RECEIVED/FROM_STOCK deals only — PARTIALLY_DELIVERED stays out (that is ส่งมอบบางส่วน\'s job)', async () => {
      const goodsReceived = {
        id: 701, code: 'PR-2026-0701', title: 'โครงการรอส่งมอบ', customerName: 'บริษัท รอส่งมอบ จำกัด',
        status: 'quotation_issued', createdByName: 'สมชาย ใจดี', createdAt: '2026-07-10T09:00:00.000Z',
        salesStage: 'DELIVERY_SCHEDULING', lifecycle: 'ACTIVE', lostReason: null, overdue: false,
        fulfillmentStatus: 'GOODS_RECEIVED', stageUpdatedAt: '2026-07-10T09:00:00.000Z',
      };
      const fromStock = {
        id: 702, code: 'PR-2026-0702', title: 'โครงการจากสต็อก', customerName: 'บริษัท จากสต็อก จำกัด',
        status: 'quotation_issued', createdByName: 'สมชาย ใจดี', createdAt: '2026-07-11T09:00:00.000Z',
        salesStage: 'DELIVERY_SCHEDULING', lifecycle: 'ACTIVE', lostReason: null, overdue: false,
        fulfillmentStatus: 'FROM_STOCK', stageUpdatedAt: '2026-07-11T09:00:00.000Z',
      };
      const partiallyDelivered = {
        id: 703, code: 'PR-2026-0703', title: 'โครงการส่งมอบบางส่วน', customerName: 'บริษัท ส่งมอบบางส่วน จำกัด',
        status: 'quotation_issued', createdByName: 'สมชาย ใจดี', createdAt: '2026-07-12T09:00:00.000Z',
        salesStage: 'DELIVERY_SCHEDULING', lifecycle: 'ACTIVE', lostReason: null, overdue: false,
        fulfillmentStatus: 'PARTIALLY_DELIVERED', stageUpdatedAt: '2026-07-12T09:00:00.000Z',
      };
      api.tickets.list.mockResolvedValueOnce({ tickets: [goodsReceived, fromStock, partiallyDelivered] });

      renderTicketListPage();
      await screen.findByText('บริษัท รอส่งมอบ จำกัด');

      fireEvent.click(screen.getByRole('button', { name: /ตัวกรอง/ }));
      const flagChip = screen.getByRole('button', { name: /รอส่งมอบ/ });
      expect(within(flagChip).getByText('2')).not.toBeNull();

      fireEvent.click(flagChip);
      expect(screen.getByText('บริษัท รอส่งมอบ จำกัด')).not.toBeNull();
      expect(screen.getByText('บริษัท จากสต็อก จำกัด')).not.toBeNull();
      expect(screen.queryByText('บริษัท ส่งมอบบางส่วน จำกัด')).toBeNull();

      // Toggling the SAME chip again clears the filter (same on/off pattern the lifecycle chip
      // test above uses) — the excluded deal reappears.
      fireEvent.click(flagChip);
      expect(screen.getByText('บริษัท ส่งมอบบางส่วน จำกัด')).not.toBeNull();
    });

    it('opens itself when a lifecycle filter arrives by deep link, so an applied filter is never hidden', async () => {
      renderTicketListPage(salesUser, ['/tickets?life=ON_HOLD']);
      expect(await screen.findByText('บริษัท พักไว้ จำกัด')).not.toBeNull();

      expect(screen.getByRole('button', { name: /^ตัวกรอง/ }).getAttribute('aria-expanded')).toBe('true');
      expect(screen.getByRole('button', { name: /พักไว้ชั่วคราว/ })).not.toBeNull();
    });
  });

  // At <=720px the filter sheet is a modal bottom sheet over a scrim, so it owes the full modal contract.
  describe('mobile filter sheet modal contract', () => {
    async function openMobileSheet() {
      stubMobile();
      renderTicketListPage();
      expect(await screen.findByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();
      const toggle = screen.getByRole('button', { name: /ตัวกรอง/ });
      toggle.focus();
      fireEvent.click(toggle);
      return { toggle, sheet: screen.getByRole('dialog', { name: 'ตัวกรองเพิ่มเติม' }) };
    }

    it('announces itself as a modal dialog on mobile only', async () => {
      const { sheet } = await openMobileSheet();
      expect(sheet.getAttribute('aria-modal')).toBe('true');
    });

    it('stays an inline region (not a dialog) above the mobile breakpoint', async () => {
      renderTicketListPage();
      expect(await screen.findByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();
      fireEvent.click(screen.getByRole('button', { name: /ตัวกรอง/ }));

      expect(screen.queryByRole('dialog')).toBeNull();
      const sheet = screen.getByRole('region', { name: 'ตัวกรองเพิ่มเติม' });
      expect(sheet.getAttribute('aria-modal')).toBeNull();
    });

    it('closes on Escape and restores focus to the filter toggle', async () => {
      const { toggle, sheet } = await openMobileSheet();

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(screen.queryByRole('dialog')).toBeNull();
      expect(sheet.isConnected).toBe(false);
      await waitFor(() => expect(document.activeElement).toBe(toggle));
    });

    it('closes on Escape even while a lifecycle filter is applied', async () => {
      stubMobile();
      renderTicketListPage(salesUser, ['/tickets?life=ON_HOLD']);
      expect(await screen.findByText('บริษัท พักไว้ จำกัด')).not.toBeNull();
      expect(screen.getByRole('dialog', { name: 'ตัวกรองเพิ่มเติม' })).not.toBeNull();

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(screen.queryByRole('dialog')).toBeNull();
      // The filter is still applied and still reported after dismissal.
      expect(screen.getByText('สถานะงาน: พักไว้ชั่วคราว')).not.toBeNull();
      expect(screen.getByText('บริษัท พักไว้ จำกัด')).not.toBeNull();
    });

    it('moves initial focus into the sheet and traps Tab inside it', async () => {
      const { sheet } = await openMobileSheet();
      const focusables = Array.from(
        sheet.querySelectorAll('a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])'),
      );
      expect(focusables.length).toBeGreaterThan(1);

      // Opening hands focus to the first control inside the sheet.
      expect(sheet.contains(document.activeElement)).toBe(true);
      expect(document.activeElement).toBe(focusables[0]);

      // Tab off the last control wraps to the first, and shift+Tab off the
      // first wraps to the last — focus never lands on the inert page behind.
      const last = focusables[focusables.length - 1];
      last.focus();
      fireEvent.keyDown(document, { key: 'Tab' });
      expect(document.activeElement).toBe(focusables[0]);

      fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
      expect(document.activeElement).toBe(last);
    });

    it('pulls focus back in if it escapes to the page behind', async () => {
      const { toggle, sheet } = await openMobileSheet();
      const first = sheet.querySelector('button');

      toggle.focus();
      fireEvent.keyDown(document, { key: 'Tab' });

      expect(document.activeElement).toBe(first);
    });

    it('marks the page behind the sheet inert and hidden, and clears both on close', async () => {
      const { sheet } = await openMobileSheet();
      const pageRoot = document.querySelector('.ticket-list-page');

      // The sheet is portalled out of the page root, so inerting the root
      // cannot swallow the sheet itself.
      expect(pageRoot.contains(sheet)).toBe(false);
      expect(pageRoot.hasAttribute('inert')).toBe(true);
      expect(pageRoot.getAttribute('aria-hidden')).toBe('true');

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(pageRoot.hasAttribute('inert')).toBe(false);
      expect(pageRoot.getAttribute('aria-hidden')).toBeNull();
    });

    it('does not inert the page when the sheet is the desktop inline panel', async () => {
      renderTicketListPage();
      expect(await screen.findByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();
      fireEvent.click(screen.getByRole('button', { name: /ตัวกรอง/ }));

      const pageRoot = document.querySelector('.ticket-list-page');
      expect(pageRoot.hasAttribute('inert')).toBe(false);
      expect(pageRoot.getAttribute('aria-hidden')).toBeNull();
      expect(pageRoot.querySelector('.ticket-filter-sheet')).not.toBeNull();
    });

    it('closes on scrim click and restores focus', async () => {
      const { toggle } = await openMobileSheet();

      // The scrim specifically — the header close button carries the same
      // accessible name, so target the backdrop element itself.
      fireEvent.click(document.querySelector('.ticket-filter-backdrop'));

      expect(screen.queryByRole('dialog')).toBeNull();
      await waitFor(() => expect(document.activeElement).toBe(toggle));
    });
  });

  // The progress column shows "ขั้นตอน X/15" only (no percentage, no manager-only win% badge:
  // win% belongs to the forecast in TeamPipelineSummary). The stale "เงียบ" flag sits in the
  // stage column so it survives the 721–1040px band, which hides the progress column.
  describe('progress column and stage-column stale flag', () => {
    function stageCellOf(container) {
      return container.querySelector('td[data-label^="ขั้นตอน"]');
    }

    function progressCellOf(container) {
      return container.querySelector('td[data-label="ความคืบหน้า"]');
    }

    it('shows only the stage fraction for a live ACTIVE deal, never a percentage', async () => {
      const { container } = renderLegacyLayout();
      await screen.findByText('บริษัท ทดสอบ จำกัด');

      // Deal 501 is QUOTE_DESIGN_SIDE, stage no. 4 of 15. Scoped to its own row: every row shares
      // the same `data-label`, so a container-wide query would grab whichever sorts first.
      const rows = Array.from(container.querySelectorAll('tr.data-row'));
      const targetRow = rows.find((row) => row.textContent.includes('บริษัท ทดสอบ จำกัด'));
      const progressCell = within(targetRow).getByText(/ขั้นตอน \d+\/15/).closest('td');
      expect(progressCell.textContent).toContain('ขั้นตอน 4/15');
      expect(progressCell.textContent).not.toMatch(/%/);
    });

    it('prefixes the lifecycle onto the fraction for a paused (ON_HOLD) deal instead of asserting bare live progress', async () => {
      const { container } = renderLegacyLayout();
      await screen.findByText('บริษัท พักไว้ จำกัด');

      const rows = Array.from(container.querySelectorAll('tr.data-row'));
      const pausedRow = rows.find((row) => row.textContent.includes('บริษัท พักไว้ จำกัด'));
      // Deal 504 is NEGOTIATION, no. 9 of 15, lifecycle ON_HOLD.
      const progressCell = within(pausedRow).getByText(/ขั้นตอน 9\/15/).closest('td');
      expect(progressCell.textContent).toContain('พักไว้ชั่วคราว');
      expect(progressCell.textContent).not.toMatch(/%/);
    });

    // The lifecycle prefix must apply even when there is no resolvable stage: a CANCELLED/COMPLETED
    // deal with a null/unrecognized salesStage must still say it was cancelled (DealStageCell only
    // badges ON_HOLD/DORMANT, never CANCELLED/COMPLETED).
    it('reports the lifecycle even when the deal has no resolvable stage (CANCELLED, null salesStage)', async () => {
      api.tickets.list.mockResolvedValueOnce({
        tickets: [
          {
            id: 602,
            code: 'PR-2026-0602',
            title: 'โครงการยกเลิกไม่ระบุขั้นตอน',
            customerName: 'บริษัท ยกเลิกไม่ระบุขั้นตอน จำกัด',
            status: 'draft',
            createdByName: 'สมชาย ใจดี',
            createdAt: '2026-07-06T09:00:00.000Z',
            salesStage: null,
            lifecycle: 'CANCELLED',
            lostReason: null,
            overdue: false,
            fulfillmentStatus: null,
            stageUpdatedAt: '2026-07-06T09:00:00.000Z',
          },
        ],
      });

      const { container } = renderLegacyLayout();
      await screen.findByText('บริษัท ยกเลิกไม่ระบุขั้นตอน จำกัด');

      const rows = Array.from(container.querySelectorAll('tr.data-row'));
      const targetRow = rows.find((row) => row.textContent.includes('บริษัท ยกเลิกไม่ระบุขั้นตอน จำกัด'));
      const progressCell = progressCellOf(targetRow);
      expect(progressCell.textContent).toContain('ยกเลิก');
      expect(progressCell.textContent).not.toMatch(/ขั้นตอน \d+\/15/);
    });

    // showLifecycleBadge must badge CANCELLED/COMPLETED too: the 721–1040px band hides the
    // progress column, so the stage column is the only place that mentions them there. Both deals
    // have a resolvable salesStage (unlike the "no resolvable stage" case above) to exercise the
    // stage column's own badge, not the progress column's null-stage fallback.
    it('badges a CANCELLED deal with a resolvable stage in the stage column specifically, not just the progress column', async () => {
      api.tickets.list.mockResolvedValueOnce({
        tickets: [
          {
            id: 610,
            code: 'PR-2026-0610',
            title: 'โครงการยกเลิกมีขั้นตอน',
            customerName: 'บริษัท ยกเลิกมีขั้นตอน จำกัด',
            status: 'in_review',
            createdByName: 'สมชาย ใจดี',
            createdAt: '2026-07-07T09:00:00.000Z',
            salesStage: 'NEGOTIATION',
            lifecycle: 'CANCELLED',
            lostReason: null,
            overdue: false,
            fulfillmentStatus: null,
            stageUpdatedAt: '2026-07-07T09:00:00.000Z',
          },
        ],
      });

      const { container } = renderLegacyLayout();
      await screen.findByText('บริษัท ยกเลิกมีขั้นตอน จำกัด');

      const stageCell = stageCellOf(container);
      expect(stageCell.textContent).toContain('ยกเลิก');
    });

    it('badges a COMPLETED deal with a resolvable stage in the stage column specifically', async () => {
      api.tickets.list.mockResolvedValueOnce({
        tickets: [
          {
            id: 611,
            code: 'PR-2026-0611',
            title: 'โครงการเสร็จสมบูรณ์มีขั้นตอน',
            customerName: 'บริษัท เสร็จสมบูรณ์มีขั้นตอน จำกัด',
            status: 'in_review',
            createdByName: 'สมชาย ใจดี',
            createdAt: '2026-07-08T09:00:00.000Z',
            salesStage: 'DELIVERY_SCHEDULING',
            lifecycle: 'COMPLETED',
            lostReason: null,
            overdue: false,
            fulfillmentStatus: null,
            stageUpdatedAt: '2026-07-08T09:00:00.000Z',
          },
        ],
      });

      const { container } = renderLegacyLayout();
      await screen.findByText('บริษัท เสร็จสมบูรณ์มีขั้นตอน จำกัด');

      const stageCell = stageCellOf(container);
      expect(stageCell.textContent).toContain('เสร็จสมบูรณ์');
    });

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
          // 25, NOT the 70 WIN_PROBABILITY_DEFAULTS.NEGOTIATION would give. The disagreement is
          // the assertion.
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
    const ceoUser = { employeeId: 5, name: 'CEO ทดสอบ', role: 'ceo' };
    const TODAY = bangkokTodayIso();
    const addDays = (iso, delta) => {
      const d = new Date(`${iso}T00:00:00.000Z`);
      d.setUTCDate(d.getUTCDate() + delta);
      return d.toISOString().slice(0, 10);
    };
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
    const rangeGroup = () => screen.getByRole('group', { name: 'ช่วงวันที่' });
    const weekChips = () => within(screen.getByLabelText('ตัวกรองความเคลื่อนไหว'));
    let shown = MOVE_DEALS; // the fixture whose names rowNames() looks for
    const rowNames = () => shown.map((d) => d.customerName).filter((n) => screen.queryByText(n));
    const columnHeaders = () => screen.getAllByRole('columnheader').map((th) => th.textContent.trim());
    const lastListArgs = () => api.tickets.list.mock.calls.at(-1)[0];

    beforeEach(() => {
      shown = MOVE_DEALS;
      api.tickets.list.mockResolvedValue({ tickets: MOVE_DEALS });
    });

    it('sales: renders the ช่วงวันที่ row with the default 7-day range and sends it to the API', async () => {
      renderTicketListPage(salesUser, ['/tickets'], <LocationDisplay />);
      await screen.findByText('ลูกค้า-ขยับ');

      expect(within(rangeGroup()).getByRole('button', { name: '7 วันล่าสุด' })).toBeTruthy();
      expect(within(rangeGroup()).getByRole('button', { name: 'เลื่อนช่วงวันที่ย้อนหลัง 1 วัน' })).toBeTruthy();
      expect(within(rangeGroup()).getByRole('button', { name: 'เลื่อนช่วงวันที่ไปข้างหน้า 1 วัน' })).toBeTruthy();
      expect(lastListArgs()).toMatchObject({ movementFrom: addDays(TODAY, -6), movementTo: TODAY });
      // default = absent from the URL
      expect(screen.getByTestId('location-display').textContent).toBe('/tickets');
      // phase chips unchanged
      expect(screen.getByLabelText('ตัวกรองเฟสของดีล')).toBeTruthy();
    });

    it('next shifts from/to in the URL by one day and re-queries with the shifted range; "7 วันล่าสุด" clears it', async () => {
      renderTicketListPage(salesUser, ['/tickets'], <LocationDisplay />);
      await screen.findByText('ลูกค้า-ขยับ');

      fireEvent.click(within(rangeGroup()).getByRole('button', { name: 'เลื่อนช่วงวันที่ไปข้างหน้า 1 วัน' }));
      const from = addDays(TODAY, -5);
      const to = addDays(TODAY, 1);
      await waitFor(() => expect(screen.getByTestId('location-display').textContent).toContain(`from=${from}`));
      expect(screen.getByTestId('location-display').textContent).toContain(`to=${to}`);
      await waitFor(() => expect(lastListArgs()).toMatchObject({ movementFrom: from, movementTo: to }));

      fireEvent.click(within(rangeGroup()).getByRole('button', { name: '7 วันล่าสุด' }));
      await waitFor(() => expect(screen.getByTestId('location-display').textContent).toBe('/tickets'));
      await waitFor(() => expect(lastListArgs()).toMatchObject({ movementFrom: addDays(TODAY, -6), movementTo: TODAY }));
    });

    it('reads from/to from the URL', async () => {
      renderTicketListPage(salesUser, ['/tickets?from=2026-11-06&to=2026-11-12']);
      await screen.findByText('ลูกค้า-ขยับ');
      expect(lastListArgs()).toMatchObject({ movementFrom: '2026-11-06', movementTo: '2026-11-12' });
    });

    it('each week chip filters rows by its rule, with counts', async () => {
      renderTicketListPage(salesUser, ['/tickets'], <LocationDisplay />);
      await screen.findByText('ลูกค้า-ขยับ');
      expect(rowNames()).toHaveLength(5);

      const chip = (re) => weekChips().getByRole('button', { name: re });
      expect(chip(/^ขยับขั้น/).textContent).toMatch(/1/);
      expect(chip(/^มีกิจกรรม/).textContent).toMatch(/1/);
      expect(chip(/^นัดติดตาม/).textContent).toMatch(/1/);
      expect(chip(/^ไม่เคลื่อนไหว/).textContent).toMatch(/2/);

      fireEvent.click(chip(/^ขยับขั้น/));
      await waitFor(() => expect(rowNames()).toEqual(['ลูกค้า-ขยับ']));
      expect(screen.getByTestId('location-display').textContent).toContain('week=moved');

      fireEvent.click(chip(/^มีกิจกรรม/));
      await waitFor(() => expect(rowNames()).toEqual(['ลูกค้า-กิจกรรม']));

      fireEvent.click(chip(/^นัดติดตาม/));
      await waitFor(() => expect(rowNames()).toEqual(['ลูกค้า-นัด']));

      // ACTIVE with neither flag; the CLOSED_LOST deal is excluded
      fireEvent.click(chip(/^ไม่เคลื่อนไหว/));
      await waitFor(() => expect(rowNames()).toEqual(['ลูกค้า-เงียบ', 'ลูกค้า-นัด']));
    });

    it('เลยนัด chip lists exactly the ACTIVE deals whose follow-up is before today (today and closed deals excluded)', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [
          moveDeal(711, 'ลูกค้า-เกินนัด', { nextFollowUpAt: addDays(TODAY, -3) }),
          moveDeal(712, 'ลูกค้า-นัดวันนี้', { nextFollowUpAt: TODAY }),
          moveDeal(713, 'ลูกค้า-ปิดเกินนัด', { nextFollowUpAt: addDays(TODAY, -3), lifecycle: 'CLOSED_LOST' }),
        ],
      });
      renderTicketListPage(salesUser, ['/tickets'], <LocationDisplay />);
      await screen.findByText('ลูกค้า-เกินนัด');
      const chip = weekChips().getByRole('button', { name: /^เลยนัด/ });
      expect(chip.textContent).toMatch(/1/);
      fireEvent.click(chip);
      await waitFor(() => expect(screen.getByText('ลูกค้า-เกินนัด')).toBeTruthy());
      expect(screen.queryByText('ลูกค้า-นัดวันนี้')).toBeNull();
      expect(screen.queryByText('ลูกค้า-ปิดเกินนัด')).toBeNull();
      expect(screen.getByTestId('location-display').textContent).toContain('week=overdue');
    });

    it('the active-filter summary says ช่วงวันที่, never สัปดาห์นี้', async () => {
      renderTicketListPage(salesUser, ['/tickets?week=silent']);
      await screen.findByText('ลูกค้า-เงียบ');
      expect(screen.getByLabelText('ตัวกรองที่ใช้').textContent).toContain('ช่วงวันที่: ไม่เคลื่อนไหว');
      expect(document.body.textContent).not.toContain('สัปดาห์นี้');
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

    // A chip's count must equal the rows it leaves: counted after EVERY other filter (search, lifecycle, ...).
    async function expectEveryChipCountToEqualItsRows() {
      for (const re of [/^ขยับขั้น/, /^มีกิจกรรม/, /^นัดติดตาม/, /^เลยนัด/, /^ไม่เคลื่อนไหว/]) {
        const chip = weekChips().getByRole('button', { name: re });
        const count = Number(/(\d+)\s*$/.exec(chip.textContent)[1]);
        fireEvent.click(chip);
        await waitFor(() => expect(rowNames()).toHaveLength(count));
      }
    }

    it('chip counts equal the rows after clicking, under a search term', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [
          moveDeal(731, 'ลูกค้า-อัลฟา', { stageChangedInRange: true }),
          moveDeal(732, 'ลูกค้า-อัลฟาสอง', { activityInRange: true }),
          moveDeal(733, 'ลูกค้า-เบตา'),
          moveDeal(734, 'ลูกค้า-เบตาสอง', { nextFollowUpAt: TODAY }),
        ],
      });
      shown = (await api.tickets.list()).tickets;
      renderTicketListPage(salesUser, ['/tickets?q=อัลฟา']);
      await screen.findByText('ลูกค้า-อัลฟา');
      expect(weekChips().getByRole('button', { name: /^ไม่เคลื่อนไหว/ }).textContent).toMatch(/0/);
      await expectEveryChipCountToEqualItsRows();
    });

    it('chip counts equal the rows after clicking, under a lifecycle filter', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [
          moveDeal(741, 'ลูกค้า-ใช้งานหนึ่ง', { activityInRange: true }),
          moveDeal(742, 'ลูกค้า-ใช้งานสอง'),
          moveDeal(743, 'ลูกค้า-พักหนึ่ง', { lifecycle: 'ON_HOLD', activityInRange: true }),
        ],
      });
      shown = (await api.tickets.list()).tickets;
      renderTicketListPage(salesUser, ['/tickets?life=ON_HOLD']);
      await screen.findByText('ลูกค้า-พักหนึ่ง');
      await expectEveryChipCountToEqualItsRows();
    });

    it('impossible dates or from > to in the URL fall back to the default range and are not sent to the API', async () => {
      for (const query of ['from=2026-02-30&to=2026-03-01', 'from=2026-11-12&to=2026-11-06']) {
        api.tickets.list.mockClear();
        const view = renderTicketListPage(salesUser, [`/tickets?${query}`]);
        await screen.findByText('ลูกค้า-ขยับ');
        expect(lastListArgs()).toEqual({ movementFrom: addDays(TODAY, -6), movementTo: TODAY });
        view.unmount();
      }
    });

    it('sales mobile card: amount, last-movement text, next follow-up badge; a zero amount reads "—"', async () => {
      stubMobile();
      api.tickets.list.mockResolvedValue({
        tickets: [
          moveDeal(751, 'ลูกค้า-การ์ดเลยนัด', { amountPayable: 1234.5, lastActivityAt: TODAY, nextFollowUpAt: addDays(TODAY, -2) }),
          moveDeal(752, 'ลูกค้า-การ์ดศูนย์', { amountPayable: 0, nextFollowUpAt: TODAY }),
        ],
      });
      renderTicketListPage(salesUser);
      await screen.findByText('ลูกค้า-การ์ดเลยนัด');
      const cardOf = (name) => screen.getAllByRole('listitem').find((li) => li.textContent.includes(name));

      const card = cardOf('ลูกค้า-การ์ดเลยนัด');
      expect(card.textContent).toContain('฿1,234.50');
      expect(card.textContent).toContain('ความเคลื่อนไหวล่าสุด');
      expect(card.textContent).toContain('วันนี้'); // lastActivityAt is today
      expect(card.textContent).toContain('นัดติดตามถัดไป');
      expect(card.textContent).toContain('เลยนัด');

      const zero = cardOf('ลูกค้า-การ์ดศูนย์');
      expect(zero.textContent).toContain('—');
      expect(zero.textContent).not.toContain('฿0.00');
      expect(zero.textContent).toContain('นัดวันนี้');
    });

    it('opens on ?week=silent', async () => {
      renderTicketListPage(salesUser, ['/tickets?week=silent']);
      await screen.findByText('ลูกค้า-เงียบ');
      expect(rowNames()).toEqual(['ลูกค้า-เงียบ', 'ลูกค้า-นัด']);
    });

    it('chip counts respect the current phase filter', async () => {
      renderTicketListPage(salesUser, ['/tickets?phase=5']);
      await screen.findByRole('heading', { name: 'รายการดีล' });
      // every fixture deal is NEGOTIATION (phase 3), so phase 5 leaves nothing to count
      await waitFor(() => expect(weekChips().getByRole('button', { name: /^ไม่เคลื่อนไหว/ }).textContent).toMatch(/0/));
    });

    it('sales columns: ดีล/ลูกค้า/โครงการ, ขั้นตอน, มูลค่า, ความเคลื่อนไหวล่าสุด, นัดติดตามถัดไป — no ผู้ดูแล, no ความคืบหน้า', async () => {
      renderTicketListPage(salesUser);
      await screen.findByText('ลูกค้า-ขยับ');
      const headers = columnHeaders().join('|');
      for (const h of ['ดีล', 'ขั้นตอน', 'มูลค่า', 'ความเคลื่อนไหวล่าสุด', 'นัดติดตามถัดไป']) expect(headers).toContain(h);
      expect(headers).not.toContain('ผู้ดูแล');
      expect(headers).not.toContain('ความคืบหน้า');
    });

    it('a deal whose amountPayable is 0 shows "—", not ฿0.00', async () => {
      renderTicketListPage(salesUser);
      await screen.findByText('ลูกค้า-กิจกรรม');
      const row = screen.getByText('ลูกค้า-กิจกรรม').closest('tr');
      expect(row.textContent).toContain('—');
      expect(row.textContent).not.toContain('฿0.00');
    });

    it('footer shows the row count and the total value of the rows shown', async () => {
      renderTicketListPage(salesUser, ['/tickets']);
      await screen.findByText('ลูกค้า-ขยับ');
      const footer = screen.getByTestId('deal-list-footer');
      expect(footer.textContent).toContain('5 รายการ');
      expect(footer.textContent).toContain('฿11,749.00');

      fireEvent.click(weekChips().getByRole('button', { name: /^ไม่เคลื่อนไหว/ }));
      await waitFor(() => expect(screen.getByTestId('deal-list-footer').textContent).toContain('2 รายการ'));
      expect(screen.getByTestId('deal-list-footer').textContent).toContain('฿750.00');
    });

    it.each([['sales_manager', managerUser], ['ceo', ceoUser]])('%s: same range row and chips, and the ผู้ดูแล column is kept', async (_role, user) => {
      renderTicketListPage(user);
      await screen.findByText('ลูกค้า-ขยับ');
      expect(within(rangeGroup()).getByRole('button', { name: '7 วันล่าสุด' })).toBeTruthy();
      expect(weekChips().getByRole('button', { name: /^ไม่เคลื่อนไหว/ })).toBeTruthy();
      const headers = columnHeaders().join('|');
      expect(headers).toContain('ผู้ดูแล');
      expect(headers).toContain('ความเคลื่อนไหวล่าสุด');
      expect(headers).not.toContain('ความคืบหน้า');
      expect(lastListArgs()).toMatchObject({ movementFrom: addDays(TODAY, -6), movementTo: TODAY });
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

  it('labels the all-phases chip "ทั้งหมด" with no count', async () => {
    renderTicketListPage(salesUser);
    await screen.findByText('บริษัท ทดสอบ จำกัด');

    const phaseGroup = within(screen.getByLabelText('ตัวกรองเฟสของดีล'));
    const allChip = phaseGroup.getByRole('button', { name: 'ทั้งหมด' });
    expect(allChip.textContent.trim()).toBe('ทั้งหมด');
  });

  // Assert the actual per-phase numbers, not just that the chip group renders. phaseCounts is
  // active-only (see TicketListPage.jsx): of the 5 seeded deals, only 501 (QUOTE_DESIGN_SIDE,
  // phase 2), 506 (LEAD_APPROACH, phase 1) and 503 (DELIVERY_SCHEDULING, phase 5) are ACTIVE;
  // 504/505 (NEGOTIATION, phase 3) are ON_HOLD/DORMANT, so phase 3's count is 0.
  it('gives each per-phase chip its own honest (active-only) count', async () => {
    renderTicketListPage(salesUser);
    await screen.findByText('บริษัท ทดสอบ จำกัด');

    const phaseGroup = within(screen.getByLabelText('ตัวกรองเฟสของดีล'));
    const phase1Chip = phaseGroup.getByRole('button', { name: /เฟส 1 ·/ });
    const phase3Chip = phaseGroup.getByRole('button', { name: /เฟส 3 ·/ });
    const phase5Chip = phaseGroup.getByRole('button', { name: /เฟส 5 ·/ });

    expect(within(phase1Chip).getByText('1')).toBeTruthy();
    expect(within(phase3Chip).getByText('0')).toBeTruthy();
    expect(within(phase5Chip).getByText('1')).toBeTruthy();
  });
  // A deal follows a ROUTE fixed by its entry channel, so the list calls dealStageLabel with the
  // channel: an owner-direct deal at S3 reads "เจ้าของตกลงตามสเปคแล้ว" here as on its detail page.
  // The stage stays the primary signal; the route name is a subordinate line, rendered ONLY for
  // the three channels that actually have a route.
  describe('route-aware stage label and route marker', () => {
    const OWNER_S3 = 'เจ้าของตกลงตามสเปคแล้ว';
    const BUYER_S3 = 'ผู้ซื้อ/ผู้รับเหมาตกลงตามสเปคแล้ว';
    const DESIGNER_S3 = 'ผู้ออกแบบอนุมัติสเปค';
    const ROUTE_NAMES = ['ผู้ออกแบบนำดีล', 'เจ้าของติดต่อโดยตรง', 'ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง'];

    function routeDeal(id, name, salesStage, entryChannel) {
      const deal = {
        id,
        code: `PR-2026-09${id}`,
        title: `โครงการ ${name}`,
        customerName: name,
        status: 'draft',
        createdByName: 'สมชาย ใจดี',
        createdAt: '2026-07-01T09:00:00.000Z',
        salesStage,
        lifecycle: 'ACTIVE',
        lostReason: null,
        overdue: false,
        fulfillmentStatus: null,
        stageUpdatedAt: '2026-07-01T09:00:00.000Z',
      };
      // `entryChannel` absent means the key is absent — not `undefined`, not null.
      if (entryChannel !== ABSENT) deal.entryChannel = entryChannel;
      return deal;
    }
    const ABSENT = Symbol('absent');

    async function renderRows(deals) {
      api.tickets.list.mockResolvedValueOnce({ tickets: deals });
      const view = renderTicketListPage(salesUser);
      await screen.findByText(deals[0].customerName);
      return view;
    }
    function rowOf(container, customerName) {
      return Array.from(container.querySelectorAll('tr.data-row'))
        .find((row) => row.textContent.includes(customerName));
    }
    function stageCellOfRow(container, customerName) {
      return rowOf(container, customerName).querySelector('td[data-label^="ขั้นตอน"]');
    }

    it('a ROW for an owner-direct deal at SPEC_APPROVED reads เจ้าของตกลงตามสเปคแล้ว', async () => {
      const { container } = await renderRows([routeDeal(1, 'ลูกค้าเจ้าของ', 'SPEC_APPROVED', 'OWNER_DIRECT')]);
      const cell = stageCellOfRow(container, 'ลูกค้าเจ้าของ');
      expect(cell.textContent).toContain(OWNER_S3);
      expect(cell.textContent).not.toContain(DESIGNER_S3);
    });

    it('a ROW for a buyer-direct deal at SPEC_APPROVED reads ผู้ซื้อ/ผู้รับเหมาตกลงตามสเปคแล้ว', async () => {
      const { container } = await renderRows([routeDeal(2, 'ลูกค้าผู้ซื้อ', 'SPEC_APPROVED', 'BUYER_DIRECT')]);
      const cell = stageCellOfRow(container, 'ลูกค้าผู้ซื้อ');
      expect(cell.textContent).toContain(BUYER_S3);
      expect(cell.textContent).not.toContain(DESIGNER_S3);
    });

    it('a mobile CARD for an owner-direct deal at SPEC_APPROVED reads เจ้าของตกลงตามสเปคแล้ว', async () => {
      stubMobile();
      await renderRows([routeDeal(3, 'ลูกค้าเจ้าของการ์ด', 'SPEC_APPROVED', 'OWNER_DIRECT')]);
      const card = await screen.findByRole('listitem');
      expect(within(card).getByText(new RegExp(OWNER_S3))).toBeTruthy();
      expect(within(card).queryByText(new RegExp(DESIGNER_S3))).toBeNull();
    });

    it('a mobile CARD for a buyer-direct deal at SPEC_APPROVED reads the buyer wording', async () => {
      stubMobile();
      await renderRows([routeDeal(4, 'ลูกค้าผู้ซื้อการ์ด', 'SPEC_APPROVED', 'BUYER_DIRECT')]);
      const card = await screen.findByRole('listitem');
      expect(within(card).getByText(new RegExp(BUYER_S3))).toBeTruthy();
    });

    it('designer-led, UNSPECIFIED, unknown and absent channels keep ผู้ออกแบบอนุมัติสเปค', async () => {
      const { container } = await renderRows([
        routeDeal(5, 'ลูกค้าดีไซเนอร์', 'SPEC_APPROVED', 'DESIGNER_LED'),
        routeDeal(6, 'ลูกค้ายังไม่ระบุ', 'SPEC_APPROVED', 'UNSPECIFIED'),
        routeDeal(7, 'ลูกค้าช่องทางแปลก', 'SPEC_APPROVED', 'SOMETHING_NEW'),
        routeDeal(8, 'ลูกค้าไม่มีช่องทาง', 'SPEC_APPROVED', ABSENT),
      ]);
      for (const name of ['ลูกค้าดีไซเนอร์', 'ลูกค้ายังไม่ระบุ', 'ลูกค้าช่องทางแปลก', 'ลูกค้าไม่มีช่องทาง']) {
        expect(stageCellOfRow(container, name).textContent).toContain(DESIGNER_S3);
      }
    });

    it.each([
      ['DESIGNER_LED', 'ผู้ออกแบบนำดีล'],
      ['OWNER_DIRECT', 'เจ้าของติดต่อโดยตรง'],
      ['BUYER_DIRECT', 'ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง'],
    ])('a ROW shows the Thai route name for %s', async (channel, routeName) => {
      const { container } = await renderRows([routeDeal(9, 'ลูกค้าเส้นทาง', 'PRESENTATION', channel)]);
      const marker = within(stageCellOfRow(container, 'ลูกค้าเส้นทาง')).getByTestId('deal-route-marker');
      expect(marker.textContent).toBe(routeName);
    });

    it.each([
      ['DESIGNER_LED', 'ผู้ออกแบบนำดีล'],
      ['OWNER_DIRECT', 'เจ้าของติดต่อโดยตรง'],
      ['BUYER_DIRECT', 'ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง'],
    ])('a mobile CARD shows the Thai route name for %s', async (channel, routeName) => {
      stubMobile();
      await renderRows([routeDeal(10, 'ลูกค้าเส้นทางการ์ด', 'PRESENTATION', channel)]);
      const card = await screen.findByRole('listitem');
      expect(within(card).getByTestId('deal-route-marker').textContent).toBe(routeName);
    });

    it.each([
      ['UNSPECIFIED', 'UNSPECIFIED'],
      ['an unknown channel', 'SOMETHING_NEW'],
      ['an absent channel', ABSENT],
      ['a null channel', null],
    ])('renders NO route marker at all for %s — a route is never invented', async (_name, channel) => {
      const { container } = await renderRows([routeDeal(11, 'ลูกค้าไม่มีเส้นทาง', 'PRESENTATION', channel)]);
      const cell = stageCellOfRow(container, 'ลูกค้าไม่มีเส้นทาง');
      expect(within(cell).queryByTestId('deal-route-marker')).toBeNull();
      for (const name of [...ROUTE_NAMES, 'ยังไม่ระบุช่องทาง', 'SOMETHING_NEW']) {
        expect(cell.textContent).not.toContain(name);
      }
    });

    it('renders NO route marker on a mobile CARD for UNSPECIFIED / unknown / absent channels', async () => {
      stubMobile();
      await renderRows([
        routeDeal(12, 'ลูกค้าการ์ดยังไม่ระบุ', 'PRESENTATION', 'UNSPECIFIED'),
        routeDeal(13, 'ลูกค้าการ์ดแปลก', 'PRESENTATION', 'SOMETHING_NEW'),
        routeDeal(14, 'ลูกค้าการ์ดไม่มี', 'PRESENTATION', ABSENT),
      ]);
      const cards = await screen.findAllByRole('listitem');
      expect(cards.length).toBeGreaterThanOrEqual(3);
      expect(screen.queryAllByTestId('deal-route-marker')).toHaveLength(0);
    });

    it('a designer-led row is unchanged apart from the route marker', async () => {
      const { container } = await renderRows([
        routeDeal(15, 'ลูกค้าดีไซเนอร์เทียบ', 'QUOTE_DESIGN_SIDE', 'DESIGNER_LED'),
        routeDeal(16, 'ลูกค้าไม่มีช่องทางเทียบ', 'QUOTE_DESIGN_SIDE', ABSENT),
      ]);
      const withRoute = stageCellOfRow(container, 'ลูกค้าดีไซเนอร์เทียบ').textContent;
      const withoutRoute = stageCellOfRow(container, 'ลูกค้าไม่มีช่องทางเทียบ').textContent;
      expect(withoutRoute).toContain('4. เสนอราคาผู้ออกแบบ');
      expect(withRoute.replace('ผู้ออกแบบนำดีล', '')).toBe(withoutRoute);
    });

    it('a CLOSED_LOST row keeps its เสียงาน badge and shows no route marker', async () => {
      const lost = { ...routeDeal(17, 'ลูกค้าเสียงาน', 'PRESENTATION', 'OWNER_DIRECT'), lifecycle: 'CLOSED_LOST', lostReason: 'PRICE' };
      const { container } = await renderRows([lost]);
      const cell = stageCellOfRow(container, 'ลูกค้าเสียงาน');
      expect(cell.textContent).toContain('เสียงาน');
      expect(within(cell).queryByTestId('deal-route-marker')).toBeNull();
    });
  });
  // The progress readout uses the route's own denominator (a buyer-direct deal at S8 reads
  // "ขั้นที่ 4 จาก 11"). A row has no per-deal decisions payload (one fetch per row would be an
  // N+1), so the position comes from the routes the stage-catalog endpoint serves, keyed by the
  // row's own `entryChannel`.
  describe('route-aware progress readout and bar', () => {
    const NO_CHANNEL = Symbol('no-channel');

    function progressDeal(id, name, salesStage, entryChannel, extra = {}) {
      const deal = {
        id,
        code: `PR-2026-08${id}`,
        title: `โครงการ ${name}`,
        customerName: name,
        status: 'draft',
        createdByName: 'สมชาย ใจดี',
        createdAt: '2026-07-01T09:00:00.000Z',
        salesStage,
        lifecycle: 'ACTIVE',
        lostReason: null,
        overdue: false,
        fulfillmentStatus: null,
        stageUpdatedAt: '2026-07-01T09:00:00.000Z',
        ...extra,
      };
      if (entryChannel !== NO_CHANNEL) deal.entryChannel = entryChannel;
      return deal;
    }

    async function renderProgressRows(deals) {
      api.tickets.list.mockResolvedValueOnce({ tickets: deals });
      const view = renderLegacyLayout();
      await screen.findByText(deals[0].customerName);
      return view;
    }

    function progressCellFor(container, customerName) {
      const row = Array.from(container.querySelectorAll('tr.data-row'))
        .find((r) => r.textContent.includes(customerName));
      return row.querySelector('td[data-label="ความคืบหน้า"]');
    }

    // The bar's per-phase segments: flex-grow is the phase's on-route stage count.
    const segmentGrows = (cell) => Array.from(cell.querySelector('[aria-hidden="true"]').children)
      .map((seg) => Number(seg.style.flexGrow));

    it('a buyer-direct row at QUOTE_BUYER reads ขั้นตอน 4/11 — not 8/15', async () => {
      const { container } = await renderProgressRows([
        progressDeal(1, 'ลูกค้าผู้ซื้อตรง', 'QUOTE_BUYER', 'BUYER_DIRECT'),
      ]);
      const text = progressCellFor(container, 'ลูกค้าผู้ซื้อตรง').textContent;
      expect(text).toContain('ขั้นตอน 4/11');
      expect(text).not.toContain('8/15');
    });

    it('an owner-direct row at QUOTE_OWNER reads ขั้นตอน 4/14', async () => {
      const { container } = await renderProgressRows([
        progressDeal(2, 'ลูกค้าเจ้าของตรง', 'QUOTE_OWNER', 'OWNER_DIRECT'),
      ]);
      expect(progressCellFor(container, 'ลูกค้าเจ้าของตรง').textContent).toContain('ขั้นตอน 4/14');
    });

    it('designer-led, UNSPECIFIED, unknown, null and absent channels are unchanged at 8/15', async () => {
      const { container } = await renderProgressRows([
        progressDeal(3, 'ลูกค้าดีไซเนอร์นำ', 'QUOTE_BUYER', 'DESIGNER_LED'),
        progressDeal(4, 'ลูกค้ายังไม่ระบุช่อง', 'QUOTE_BUYER', 'UNSPECIFIED'),
        progressDeal(5, 'ลูกค้าช่องทางแปลกใหม่', 'QUOTE_BUYER', 'SOMETHING_NEW'),
        progressDeal(6, 'ลูกค้าช่องทางว่างเปล่า', 'QUOTE_BUYER', null),
        progressDeal(7, 'ลูกค้าไม่มีช่องทางเลย', 'QUOTE_BUYER', NO_CHANNEL),
      ]);
      for (const name of [
        'ลูกค้าดีไซเนอร์นำ', 'ลูกค้ายังไม่ระบุช่อง', 'ลูกค้าช่องทางแปลกใหม่',
        'ลูกค้าช่องทางว่างเปล่า', 'ลูกค้าไม่มีช่องทางเลย',
      ]) {
        expect(progressCellFor(container, name).textContent, name).toContain('ขั้นตอน 8/15');
      }
    });

    it('keeps the lifecycle prefix: a buyer-direct ON_HOLD row reads พักไว้ชั่วคราว · ขั้นตอน 4/11', async () => {
      const { container } = await renderProgressRows([
        progressDeal(8, 'ลูกค้าผู้ซื้อพักไว้', 'QUOTE_BUYER', 'BUYER_DIRECT', { lifecycle: 'ON_HOLD' }),
      ]);
      expect(progressCellFor(container, 'ลูกค้าผู้ซื้อพักไว้').textContent)
        .toContain('พักไว้ชั่วคราว · ขั้นตอน 4/11');
    });

    it('keeps the CLOSED_LOST copy for a buyer-direct row — no fraction at all', async () => {
      const { container } = await renderProgressRows([
        progressDeal(9, 'ลูกค้าผู้ซื้อเสียงาน', 'QUOTE_BUYER', 'BUYER_DIRECT', {
          lifecycle: 'CLOSED_LOST', lostReason: 'PRICE',
        }),
      ]);
      const text = progressCellFor(container, 'ลูกค้าผู้ซื้อเสียงาน').textContent;
      expect(text).toContain('ไม่คืบหน้า (เสียงาน)');
      expect(text).not.toMatch(/\d+\/\d+/);
    });

    it('keeps the no-stage cases: a buyer-direct row with no salesStage says ยังไม่ระบุขั้นตอน', async () => {
      const { container } = await renderProgressRows([
        progressDeal(10, 'ลูกค้าผู้ซื้อไม่มีขั้น', null, 'BUYER_DIRECT'),
        progressDeal(11, 'ลูกค้าผู้ซื้อยกเลิกไม่มีขั้น', null, 'BUYER_DIRECT', { lifecycle: 'CANCELLED' }),
      ]);
      expect(progressCellFor(container, 'ลูกค้าผู้ซื้อไม่มีขั้น').textContent)
        .toContain('ยังไม่ระบุขั้นตอน');
      const cancelled = progressCellFor(container, 'ลูกค้าผู้ซื้อยกเลิกไม่มีขั้น').textContent;
      expect(cancelled).toContain('ยกเลิก');
      expect(cancelled).not.toMatch(/\d+\/\d+/);
    });

    it('the bar says the same thing as the text: buyer-direct segments count on-route stages (2,1,2,3,3 = 11)', async () => {
      const { container } = await renderProgressRows([
        progressDeal(12, 'ลูกค้าแถบผู้ซื้อ', 'QUOTE_BUYER', 'BUYER_DIRECT'),
        progressDeal(13, 'ลูกค้าแถบดีไซเนอร์', 'QUOTE_BUYER', 'DESIGNER_LED'),
      ]);
      expect(segmentGrows(progressCellFor(container, 'ลูกค้าแถบผู้ซื้อ'))).toEqual([2, 1, 2, 3, 3]);
      // The majority route's bar is untouched: 2,4,3,3,3 = 15.
      expect(segmentGrows(progressCellFor(container, 'ลูกค้าแถบดีไซเนอร์'))).toEqual([2, 4, 3, 3, 3]);
    });

    it('the mobile CARD bar follows the route too', async () => {
      stubMobile();
      api.tickets.list.mockResolvedValueOnce({
        tickets: [progressDeal(14, 'ลูกค้าการ์ดผู้ซื้อ', 'QUOTE_BUYER', 'BUYER_DIRECT')],
      });
      renderLegacyLayout();
      const card = await screen.findByRole('listitem');
      const bar = card.querySelector('[aria-hidden="true"].flex');
      expect(Array.from(bar.children).map((seg) => Number(seg.style.flexGrow))).toEqual([2, 1, 2, 3, 3]);
    });
  });
});
