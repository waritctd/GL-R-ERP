import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketListPage } from './TicketListPage.jsx';
import { api } from '../../api/index.js';

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
      // The pipeline enumeration (GET /api/meta/deal-stages). Served here as the SAME canned
      // payload mockApi serves — data/dealStageCatalog.js, which stageCatalog.test.js pins against
      // DealStage.java — so this fixture cannot quietly describe a pipeline the backend does not
      // have. A fixture more populated (or differently shaped) than production is the failure mode
      // CLAUDE.md names; reusing the guarded one is how that is avoided.
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

// TicketCreateModal owns its own multi-field customer/catalog search form
// (out of scope for this slice — see task note not to touch it). Stubbed here
// with a single button so this test can drive TicketListPage's create
// mutation (invalidate + refetch) without re-implementing that form.
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

// Commit 6: handleCreate now navigates to the new deal's page — MemoryRouter
// doesn't touch window.location, so surface the current path via useLocation
// for assertions instead.
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
          // Review-remediation plan Commit D: every deal created since
          // 03b5ba9 stays frozen on the legacy status 'draft' forever — real
          // progress now lives on salesStage/PricingRequest instead.
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
    expect(api.tickets.list).toHaveBeenCalledWith({});
  });

  // Task 2d (ui-worklist-pattern): the desktop table used to end every row in
  // an identical `เปิด ›` button (15x the same word, no information). The row
  // itself is now a mouse-only open target via DataTable's onRowClick,
  // matching /finance's "the action varies by row, not a repeated button"
  // pattern.
  it('opens a deal by clicking the row itself, with no repeated เปิด button', async () => {
    renderTicketListPage(salesUser, ['/tickets'], <LocationDisplay />);

    await screen.findByText('บริษัท ทดสอบ จำกัด');
    // What must stay gone is the literal, identical-on-every-row
    // `<button>เปิด ›</button>` the old action column rendered.
    const literalOpenButtons = screen.queryAllByRole('button', { name: /^เปิด/ })
      .filter((el) => el.tagName === 'BUTTON');
    expect(literalOpenButtons).toHaveLength(0);

    fireEvent.click(screen.getByText('บริษัท ทดสอบ จำกัด').closest('tr'));

    await waitFor(() => expect(screen.getByTestId('location-display').textContent).toBe('/tickets/501'));
  });

  // FIX A (review-remediation): `role="button"` on the `<tr>` pruned every
  // descendant — customer name, ผู้ดูแล, the stage cell (incl. the "เงียบ"
  // stale badge), and the "ขั้นตอน N/15" progress readout — out of the
  // accessibility tree, so a screen reader announced only "เปิดดีล
  // PR-2026-0501, button" and nothing else. The row itself must no longer be
  // focusable or carry a button role; the identity cell's own <Link> is the
  // keyboard/assistive-tech affordance (and the visible tappable-row cue for
  // the 721–1040px tablet band, which has no hover).
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

  // FIX G (review-remediation): the removed `.ticket-result-count` band used
  // to show "ตรงเงื่อนไข N จาก M รายการ" (N matched out of the whole M-deal
  // population). DataTable's own footer only ever knew N; TicketListPage now
  // passes the unfiltered `allDeals.length` as `unfilteredTotal` so the
  // footer can restore the M denominator.
  it('shows how many deals were filtered out of the whole role-scoped population', async () => {
    renderTicketListPage(salesUser, ['/tickets?q=เกินกำหนด&phase=5']);

    expect(await screen.findByText('บริษัท เกินกำหนด จำกัด')).not.toBeNull();
    // FIX F1: DataTable now renders this same summary twice — once in the
    // always-mounted sr-only aria-live region, once in the aria-hidden
    // visible footer — so this asserts on the count of matches rather than a
    // single unique node.
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

    const { container } = renderTicketListPage();

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
    expect(screen.getByRole('button', { name: /สร้างดีลใหม่/ })).toBeTruthy();

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
    // FIX F10: EmptyState's visible `<strong>` title is `aria-hidden` when
    // `titleAnnouncedElsewhere` is set (which DataTable always passes), so it
    // stays visible for sighted users but is pruned from the accessibility
    // tree — it is never itself the thing a screen reader hears.
    expect(await screen.findByText('ไม่พบดีลที่ตรงกับตัวกรอง')).toBeTruthy();
    // FIX F7: the whole role-scoped population (5 deals) all got filtered out
    // by the search, so the live-region announcement — unlike the visible
    // EmptyState heading above — carries the "(กรองจาก 5)" denominator too.
    // This is the exact case the review flagged as mattering most ("you
    // filtered N down to nothing"), and it is also what makes the visible
    // heading and the live-region text two DIFFERENT strings now, not a
    // literal duplicate of each other.
    expect(document.querySelector('[aria-live="polite"]').textContent).toBe('ไม่พบดีลที่ตรงกับตัวกรอง (กรองจาก 5)');
    expect(screen.getByText('ไม่พบดีลที่ตรงกับคำค้นหาและตัวกรองที่เลือก')).toBeTruthy();
    unmount();

    api.tickets.list.mockResolvedValueOnce({ tickets: [] });
    renderTicketListPage(salesUser);
    // A truly empty list has no unfiltered population to denominate against
    // (allDeals.length is 0, equal to the 0 matched), so no clause is
    // appended and the visible heading and the live-region text are
    // identical strings here — this is what actually distinguishes "filtered
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

  // Commit 6: a newly created deal starts as an empty DRAFT with no
  // price-request flow of its own (TicketService.create, commit 5) —
  // handleCreate now lands the user on the deal page instead of just closing
  // the modal, so the PricingRequestPanel prompt is the very next thing seen.
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

  // Review-remediation plan Commit D: since 03b5ba9 stopped ticket-level
  // auto-submit, a new deal's legacy `status` is permanently 'draft' and no
  // longer reflects real workflow — showing it as a secondary label under the
  // stage badge would read as if nothing had happened. Deals that already
  // progressed under the old flow (`status: 'submitted'` etc.) still show it.
  it('suppresses the legacy status sublabel for a deal frozen on draft, but keeps it for others', async () => {
    renderTicketListPage();

    expect(await screen.findByText('บริษัท ดีลใหม่ จำกัด')).not.toBeNull();
    expect(screen.queryByText('แบบร่าง')).toBeNull();
    expect(screen.getByText('รอฝ่ายนำเข้ารับเรื่อง')).not.toBeNull();
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

  // Owner feedback (role-scoped views, Sales branch): LIFECYCLE/FLAGS used to
  // sit in the primary view, competing with the deal list. They now live
  // behind a collapsed "ตัวกรองเพิ่มเติม" expander, closed by default.
  describe('LIFECYCLE/FLAGS de-emphasis ("ตัวกรองเพิ่มเติม")', () => {
    it('keeps the lifecycle/flags chips out of the always-visible primary bar by default', async () => {
      renderTicketListPage();
      expect(await screen.findByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();

      const toggle = screen.getByRole('button', { name: /ตัวกรอง/ });
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(screen.queryByRole('button', { name: /พักไว้ชั่วคราว/ })).toBeNull();
      expect(screen.queryByRole('button', { name: /^เกินกำหนด/ })).toBeNull();
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

      // Phase 4A: collapsing now genuinely closes the sheet even with a filter
      // applied — the old `open || hasActiveFilter` derivation made the sheet
      // impossible to dismiss, which broke close/scrim/Escape on the mobile
      // modal. The applied filter is not hidden by closing: it stays reported
      // in the "ตัวกรองที่ใช้" summary row, in the toggle's count badge, and it
      // keeps filtering the table.
      fireEvent.click(toggle);
      expect(screen.queryByRole('button', { name: /พักไว้ชั่วคราว/ })).toBeNull();
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(within(toggle).getByText('1')).not.toBeNull();
      expect(screen.getByLabelText('ตัวกรองที่ใช้')).not.toBeNull();
      expect(screen.getByText('สถานะงาน: พักไว้ชั่วคราว')).not.toBeNull();
      expect(screen.getByText('บริษัท พักไว้ จำกัด')).not.toBeNull();
    });

    it('opens itself when a lifecycle filter arrives by deep link, so an applied filter is never hidden', async () => {
      renderTicketListPage(salesUser, ['/tickets?life=ON_HOLD']);
      expect(await screen.findByText('บริษัท พักไว้ จำกัด')).not.toBeNull();

      expect(screen.getByRole('button', { name: /^ตัวกรอง/ }).getAttribute('aria-expanded')).toBe('true');
      expect(screen.getByRole('button', { name: /พักไว้ชั่วคราว/ })).not.toBeNull();
    });
  });

  // Acceptance gate for Phase 4A: at <=720px the filter sheet is a modal
  // bottom sheet over a scrim, so it owes the viewer the full modal contract.
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

  // FIX 3/4/5 (review-remediation): the progress column used to show
  // "N% · ขั้นตอน X/15" (a percentage restating the fraction next to it),
  // and a manager-only win% badge lived in that same column — both gone
  // now (owner decision: win% belongs to the forecast in TeamPipelineSummary,
  // not a second progress number). The stale "เงียบ" flag moved to the stage
  // column so it survives the 721–1040px tablet band, which hides the
  // progress column outright.
  describe('progress column and stage-column stale flag', () => {
    function stageCellOf(container) {
      return container.querySelector('td[data-label="ขั้นตอน / เหตุผลงาน"]');
    }

    function progressCellOf(container) {
      return container.querySelector('td[data-label="ความคืบหน้า"]');
    }

    it('shows only the stage fraction for a live ACTIVE deal, never a percentage', async () => {
      const { container } = renderTicketListPage(salesUser);
      await screen.findByText('บริษัท ทดสอบ จำกัด');

      // Deal 501 is QUOTE_DESIGN_SIDE, stage no. 4 of 15 (V143 added a fifteenth). Scoped to its own
      // row — the table has several rows, all sharing the same
      // `data-label`, so a container-wide query would grab whichever row
      // happens to sort first instead.
      const rows = Array.from(container.querySelectorAll('tr.data-row'));
      const targetRow = rows.find((row) => row.textContent.includes('บริษัท ทดสอบ จำกัด'));
      const progressCell = within(targetRow).getByText(/ขั้นตอน \d+\/15/).closest('td');
      expect(progressCell.textContent).toContain('ขั้นตอน 4/15');
      expect(progressCell.textContent).not.toMatch(/%/);
    });

    it('prefixes the lifecycle onto the fraction for a paused (ON_HOLD) deal instead of asserting bare live progress', async () => {
      const { container } = renderTicketListPage(salesUser);
      await screen.findByText('บริษัท พักไว้ จำกัด');

      const rows = Array.from(container.querySelectorAll('tr.data-row'));
      const pausedRow = rows.find((row) => row.textContent.includes('บริษัท พักไว้ จำกัด'));
      // Deal 504 is NEGOTIATION — no. 9 of 15 since V143 inserted QUOTE_OWNER ahead of it (it was
      // 8 of 14) — lifecycle ON_HOLD.
      const progressCell = within(pausedRow).getByText(/ขั้นตอน 9\/15/).closest('td');
      expect(progressCell.textContent).toContain('พักไว้ชั่วคราว');
      expect(progressCell.textContent).not.toMatch(/%/);
    });

    // FIX F (review-remediation): the lifecycle prefix used to be decided
    // AFTER the "no resolvable stage" early return, so a CANCELLED/COMPLETED
    // deal with a null/unrecognized salesStage displayed nothing anywhere
    // saying it was cancelled (DealStageCell only badges ON_HOLD/DORMANT,
    // never CANCELLED/COMPLETED).
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

      const { container } = renderTicketListPage(salesUser);
      await screen.findByText('บริษัท ยกเลิกไม่ระบุขั้นตอน จำกัด');

      const rows = Array.from(container.querySelectorAll('tr.data-row'));
      const targetRow = rows.find((row) => row.textContent.includes('บริษัท ยกเลิกไม่ระบุขั้นตอน จำกัด'));
      const progressCell = progressCellOf(targetRow);
      expect(progressCell.textContent).toContain('ยกเลิก');
      expect(progressCell.textContent).not.toMatch(/ขั้นตอน \d+\/15/);
    });

    // FIX F6 (review-remediation): showLifecycleBadge used to badge only
    // ON_HOLD/DORMANT, so a CANCELLED or COMPLETED deal with a resolvable
    // salesStage looked indistinguishable from a live ACTIVE one in this
    // column specifically — which matters because the 721–1040px tablet
    // band hides the progress column outright, so the stage column is the
    // only place left that ever mentions CANCELLED/COMPLETED at that width.
    // Deliberately give both deals a resolvable salesStage (unlike the
    // "no resolvable stage" case above) so this exercises the stage column's
    // own badge, not the progress column's null-stage fallback.
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

      const { container } = renderTicketListPage(salesUser);
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

      const { container } = renderTicketListPage(salesUser);
      await screen.findByText('บริษัท เสร็จสมบูรณ์มีขั้นตอน จำกัด');

      const stageCell = stageCellOf(container);
      expect(stageCell.textContent).toContain('เสร็จสมบูรณ์');
    });

    it('drops the win% badge but keeps the เงียบ stale badge in the stage column, for a manager view', async () => {
      api.tickets.list.mockResolvedValueOnce({
        tickets: [
          {
            id: 601,
            code: 'PR-2026-0601',
            title: 'โครงการเงียบ (manager)',
            customerName: 'บริษัท ผู้จัดการ จำกัด',
            status: 'in_review',
            createdByName: 'สมชาย ใจดี',
            createdAt: '2026-07-06T09:00:00.000Z',
            salesStage: 'NEGOTIATION',
            lifecycle: 'ACTIVE',
            lostReason: null,
            overdue: false,
            fulfillmentStatus: null,
            stageUpdatedAt: '2026-07-06T09:00:00.000Z',
            stale: true,
            winProbabilityOverride: null,
          },
        ],
      });

      const { container } = renderTicketListPage(managerUser);
      await screen.findByText('บริษัท ผู้จัดการ จำกัด');

      const stageCell = stageCellOf(container);
      const progressCell = progressCellOf(container);
      expect(stageCell.textContent).toContain('เงียบ');
      expect(progressCell.textContent).not.toContain('เงียบ');
      // The win% badge that used to live in the progress column is gone
      // outright (owner decision) — not moved, not duplicated elsewhere.
      expect(progressCell.textContent).not.toMatch(/%/);
      expect(stageCell.textContent).not.toMatch(/%/);
    });
  });

  /**
   * The win-weighted forecast, and the one property that makes issue #738's fix load-bearing:
   * this number must come from the SERVER's `effectiveWinProbability`, never from a client-side
   * re-derivation of it.
   *
   * Nothing covered this figure before — the forecast is the single most money-shaped number on
   * the deal list and it had no assertion at all, which is why #714 (V143 adds QUOTE_OWNER, the
   * copied table does not, every S5 deal contributes 0) could not have been caught here.
   *
   * The fixture is deliberately built WRONG-WAY-ROUND: `effectiveWinProbability: 25` on a
   * NEGOTIATION deal, where the stage table says 70. Any re-derivation — from the stage, from the
   * override, from a copy of WIN_PROBABILITY_DEFAULTS — produces a different total, so this test
   * cannot pass unless the served field is what is actually read. A fixture where the two agreed
   * would pass either way and be evidence of nothing.
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

  // FIX 5: the "all phases" chip's label used to be `ดีลที่ดำเนินอยู่`
  // (`activePipelineCount`, ACTIVE-only) over an action that actually lists
  // every deal including CLOSED_LOST/CANCELLED/COMPLETED — the label
  // matched its own count but misdescribed the action. Renamed to
  // `ทั้งหมด` with no count at all (owner decision): the single statement of
  // the filtered total is DataTable's own footer.
  it('labels the all-phases chip "ทั้งหมด" with no count', async () => {
    renderTicketListPage(salesUser);
    await screen.findByText('บริษัท ทดสอบ จำกัด');

    const phaseGroup = within(screen.getByLabelText('ตัวกรองเฟสของดีล'));
    const allChip = phaseGroup.getByRole('button', { name: 'ทั้งหมด' });
    expect(allChip.textContent.trim()).toBe('ทั้งหมด');
  });

  // Previously this only asserted the phase-chip group existed
  // (`querySelector('[aria-label="..."]')`), which stays truthy even if every
  // phase chip's count were deleted — it proves the group renders, not that
  // per-phase counts do. Assert the actual numbers instead. phaseCounts is
  // active-only (see TicketListPage.jsx): of the 5 seeded deals, only 501
  // (QUOTE_DESIGN_SIDE, phase 2, ACTIVE), 506 (LEAD_APPROACH, phase 1,
  // ACTIVE) and 503 (DELIVERY_SCHEDULING, phase 5, ACTIVE) count; 504/505
  // (NEGOTIATION, phase 3) are ON_HOLD/DORMANT and are excluded, so phase 3's
  // honest count is 0.
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
});
