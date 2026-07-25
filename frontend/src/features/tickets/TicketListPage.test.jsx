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
  return {
    ...actual,
    api: {
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

  it('opens a deal through the explicit row action, not an implicit row click', async () => {
    renderTicketListPage(salesUser, ['/tickets'], <LocationDisplay />);

    await screen.findByText('บริษัท ทดสอบ จำกัด');
    fireEvent.click(screen.getByRole('button', { name: 'เปิดดีล PR-2026-0501' }));

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
    expect(cardScope.getByText(/เสนอราคาผู้ออกแบบ\/เจ้าของ/)).toBeTruthy();
    expect(cardScope.getByRole('button', { name: 'เปิดดีล PR-2026-0507' })).toBeTruthy();
    expect(screen.getByRole('searchbox', { name: 'ค้นหาดีล' })).toBeTruthy();
    expect(screen.getByRole('button', { name: /สร้างดีลใหม่/ })).toBeTruthy();

    const filterToggle = screen.getByRole('button', { name: /ตัวกรอง/ });
    filterToggle.focus();
    fireEvent.click(filterToggle);
    const filterSheet = screen.getByRole('region', { name: 'ตัวกรองเพิ่มเติม' });
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
    expect(await screen.findByText('ไม่พบดีลที่ตรงกับตัวกรอง')).toBeTruthy();
    expect(screen.getByText('ไม่พบดีลที่ตรงกับคำค้นหาและตัวกรองที่เลือก')).toBeTruthy();
    unmount();

    api.tickets.list.mockResolvedValueOnce({ tickets: [] });
    renderTicketListPage(salesUser);
    expect(await screen.findByText('ไม่มีดีล')).toBeTruthy();
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
    expect(screen.getByText('รอรับเรื่องจากฝ่าย Import')).not.toBeNull();
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

      // Collapsing again keeps the now-active filter visible/working rather
      // than silently hiding an applied filter from the viewer.
      fireEvent.click(toggle);
      expect(screen.getByRole('button', { name: /พักไว้ชั่วคราว/ })).not.toBeNull();
      expect(screen.getByText('บริษัท พักไว้ จำกัด')).not.toBeNull();
    });
  });
});
