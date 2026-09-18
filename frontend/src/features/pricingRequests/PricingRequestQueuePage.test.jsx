import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PricingRequestQueuePage } from './PricingRequestQueuePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Same stub used by TicketListPage.test.jsx (and others) to force DataTable's
// `useIsMobile()` down the mobileCard path — per repo memory, a mobileCard test
// is vacuous without this: jsdom has no layout engine, so without a real
// `matchMedia` stub the hook always reports desktop and QueueCard never renders.
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
      pricingRequests: {
        queue: vi.fn(),
        pickup: vi.fn(),
      },
    },
  };
});

const importUser = { id: 5, name: 'ฝ่ายนำเข้า', role: 'import' };
const ceoUser = { id: 9, name: 'ซีอีโอ', role: 'ceo' };
const salesManagerUser = { id: 3, name: 'ผู้จัดการฝ่ายขาย', role: 'sales_manager' };

function row(overrides = {}) {
  return {
    id: 1,
    requestCode: 'PCR-2026-0001',
    ticketId: 701,
    ticketCode: 'PR-2026-0701',
    customerName: 'บริษัท ทดสอบ จำกัด',
    projectName: 'โครงการทดสอบ',
    recipientType: 'DESIGNER',
    recipientLabel: null,
    status: 'SUBMITTED',
    itemCount: 3,
    requiredDate: null,
    assignedImportId: null,
    assignedImportName: null,
    ...overrides,
  };
}

function renderQueuePage(user = importUser) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/pricing-requests']}>
        <PricingRequestQueuePage user={user} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// The chip bar and the table both contain buttons/badges with the same Thai
// wording now that IMPORT_REVIEWING reads "รับเรื่อง" (the chip) exactly like the
// per-row pickup action, so every assertion below scopes to one or the other
// instead of querying the whole document.
function chips() {
  // "ทั้งหมด" is unique to the chip bar; its parent is the chip container.
  return within(screen.getByRole('button', { name: 'ทั้งหมด' }).parentElement)
    .getAllByRole('button')
    .map((button) => button.textContent);
}

function tabByName(name) {
  return screen.getByRole('tab', { name: new RegExp(`^${name}`) });
}

function tableRow(requestCode) {
  const found = screen.getAllByRole('row').find((r) => r.textContent.includes(requestCode));
  if (!found) throw new Error(`no rendered row for ${requestCode}`);
  return found;
}

async function goToAllTab() {
  fireEvent.click(tabByName('ทั้งหมด'));
  // The ทั้งหมด tab is what renders the chip bar; wait for it before touching
  // a chip so a click never lands before the tab switch has rendered it.
  await waitFor(() => expect(screen.getByRole('button', { name: 'ทั้งหมด' })).not.toBeNull());
}

describe('PricingRequestQueuePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.pricingRequests.queue.mockResolvedValue({ items: [row()] });
  });

  describe('import role — task tabs (GLA-110)', () => {
    it('lands on งานของฉัน by default and fetches it with assignedImportId', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [row({ status: 'IMPORT_REVIEWING', assignedImportId: 5, assignedImportName: 'ฝ่ายนำเข้า' })],
      });
      renderQueuePage(importUser);

      expect(await screen.findByText('PCR-2026-0001')).not.toBeNull();
      expect(api.pricingRequests.queue).toHaveBeenCalledWith({ assignedImportId: 5, activeOnly: true });
      // No chip bar on the default tab — it is already status-scoped.
      expect(screen.queryByRole('button', { name: 'ทั้งหมด' })).toBeNull();
      expect(tabByName('งานของฉัน').getAttribute('aria-selected')).toBe('true');
    });

    it('shows งานของฉัน rows across IMPORT_REVIEWING / AWAITING_FACTORY_RESPONSE / legacy COSTING_IN_PROGRESS, all assigned to me', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 11, requestCode: 'PCR-2026-0011', status: 'IMPORT_REVIEWING', assignedImportId: 5 }),
          row({ id: 12, requestCode: 'PCR-2026-0012', status: 'AWAITING_FACTORY_RESPONSE', assignedImportId: 5 }),
          row({ id: 13, requestCode: 'PCR-2026-0013', status: 'COSTING_IN_PROGRESS', assignedImportId: 5 }),
        ],
      });
      renderQueuePage(importUser);

      expect(await screen.findByText('PCR-2026-0011')).not.toBeNull();
      expect(screen.getByText('PCR-2026-0012')).not.toBeNull();
      expect(screen.getByText('PCR-2026-0013')).not.toBeNull();
    });

    it('excludes a READY_FOR_CEO_REVIEW row assigned to me — out of import\'s hands', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 11, requestCode: 'PCR-2026-0011', status: 'IMPORT_REVIEWING', assignedImportId: 5 }),
          row({ id: 14, requestCode: 'PCR-2026-0014', status: 'READY_FOR_CEO_REVIEW', assignedImportId: 5 }),
        ],
      });
      renderQueuePage(importUser);

      expect(await screen.findByText('PCR-2026-0011')).not.toBeNull();
      expect(screen.queryByText('PCR-2026-0014')).toBeNull();
    });

    it('excludes a row assigned to someone else even though the mock returns it, both by query param and client-side narrowing', async () => {
      // The real endpoint would never return another import user's rows for
      // this call — this proves the page still narrows client-side too, so a
      // misbehaving mock/backend cannot leak another user's row into view.
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 11, requestCode: 'PCR-2026-0011', status: 'IMPORT_REVIEWING', assignedImportId: 5 }),
          row({ id: 15, requestCode: 'PCR-2026-0015', status: 'IMPORT_REVIEWING', assignedImportId: 99 }),
        ],
      });
      renderQueuePage(importUser);

      expect(await screen.findByText('PCR-2026-0011')).not.toBeNull();
      await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ assignedImportId: 5, activeOnly: true }));
      // Client-side defence, not just the query param: the row assigned to
      // someone else must never render even though the (misbehaving, for this
      // test) mock returned it.
      expect(screen.queryByText('PCR-2026-0015')).toBeNull();
    });

    it('shows the งานของฉัน badge count and keeps showing it while another tab is active', async () => {
      api.pricingRequests.queue.mockImplementation((params) => {
        if (params?.assignedImportId === 5) {
          return Promise.resolve({
            items: [
              row({ id: 11, requestCode: 'PCR-2026-0011', status: 'IMPORT_REVIEWING', assignedImportId: 5 }),
              row({ id: 12, requestCode: 'PCR-2026-0012', status: 'AWAITING_FACTORY_RESPONSE', assignedImportId: 5 }),
            ],
          });
        }
        return Promise.resolve({ items: [row({ status: 'SUBMITTED' })] });
      });
      renderQueuePage(importUser);

      await screen.findByText('PCR-2026-0011');
      expect(within(tabByName('งานของฉัน')).getByText('2')).not.toBeNull();

      fireEvent.click(tabByName('รอรับเรื่อง'));
      await screen.findByText('PCR-2026-0001');
      expect(within(tabByName('งานของฉัน')).getByText('2')).not.toBeNull();
    });

    it('รอรับเรื่อง calls the API with status SUBMITTED and offers the pickup button', async () => {
      api.pricingRequests.queue.mockImplementation((params) => {
        if (params?.status === 'SUBMITTED') {
          return Promise.resolve({ items: [row({ status: 'SUBMITTED' })] });
        }
        return Promise.resolve({ items: [] });
      });
      renderQueuePage(importUser);

      fireEvent.click(tabByName('รอรับเรื่อง'));

      await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ status: 'SUBMITTED', activeOnly: true }));
      expect(await screen.findByTestId('pcr-queue-pickup')).not.toBeNull();
    });

    it('moves a request from รอรับเรื่อง to งานของฉัน after pickup invalidates the queue', async () => {
      let pickedUp = false;
      api.pricingRequests.queue.mockImplementation((params) => {
        if (params?.assignedImportId === 5) {
          return Promise.resolve({
            items: pickedUp ? [row({ status: 'IMPORT_REVIEWING', assignedImportId: 5 })] : [],
          });
        }
        if (params?.status === 'SUBMITTED') {
          return Promise.resolve({ items: pickedUp ? [] : [row({ status: 'SUBMITTED' })] });
        }
        return Promise.resolve({ items: [] });
      });
      api.pricingRequests.pickup.mockImplementation(async () => {
        pickedUp = true;
        return { pricingRequest: { summary: row({ status: 'IMPORT_REVIEWING', assignedImportId: 5 }), items: [], events: [] } };
      });
      renderQueuePage(importUser);

      fireEvent.click(tabByName('รอรับเรื่อง'));
      const pickupButton = await screen.findByTestId('pcr-queue-pickup');
      fireEvent.click(pickupButton);
      await waitFor(() => expect(api.pricingRequests.pickup).toHaveBeenCalledWith(1));

      // Now empty on รอรับเรื่อง... (DataTable renders the empty-state copy
      // twice — a visible heading plus an sr-only live-region echo — so this
      // asserts at least one match rather than a single unique element).
      await waitFor(() => expect(screen.getAllByText('ไม่มีคำขอที่รอรับเรื่อง').length).toBeGreaterThan(0));

      // ...and showing up on งานของฉัน.
      fireEvent.click(tabByName('งานของฉัน'));
      expect(await screen.findByText('PCR-2026-0001')).not.toBeNull();
    });

    it('offers the ทั้งหมด tab with the existing chip behaviour, defaulting to the ทั้งหมด chip', async () => {
      renderQueuePage(importUser);

      await goToAllTab();
      await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ status: undefined, activeOnly: true }));
      expect(chips()).toEqual([
        'ทั้งหมด',
        'รอฝ่ายนำเข้ารับเรื่อง',
        'รับเรื่อง',
        'เจรจาราคากับโรงงาน',
        'รอ CEO อนุมัติราคา',
        'ยกเลิกแล้ว',
      ]);
      expect(within(screen.getByRole('button', { name: 'ทั้งหมด' })).getByText('ทั้งหมด')).not.toBeNull();
    });

    // Review finding: the badge must never show a confident "0" before its own query has
    // actually succeeded — a query that is still loading or has failed is "unknown", not "zero".
    it('shows no badge count while the งานของฉัน query is loading', async () => {
      let resolveQueue;
      api.pricingRequests.queue.mockImplementation(() => new Promise((resolve) => { resolveQueue = resolve; }));
      renderQueuePage(importUser);

      // The tab itself renders immediately; the badge must not, because nothing has
      // succeeded yet — Tabs.jsx never renders a badge span at all when `badge == null`.
      const tab = tabByName('งานของฉัน');
      expect(tab).not.toBeNull();
      expect(within(tab).queryByText('0')).toBeNull();
      expect(within(tab).queryByText(/รายการ/)).toBeNull();

      resolveQueue({ items: [] });
      // Now it has succeeded, genuinely with zero rows — THAT 0 is allowed to show.
      await waitFor(() => expect(within(tabByName('งานของฉัน')).getByText('0')).not.toBeNull());
    });

    it('shows no badge count and an error empty-state when the งานของฉัน query fails', async () => {
      api.pricingRequests.queue.mockRejectedValue(new Error('เครือข่ายขัดข้อง'));
      renderQueuePage(importUser);

      await waitFor(() => expect(screen.getAllByText('โหลดคิวขอราคาไม่สำเร็จ').length).toBeGreaterThan(0));
      const tab = tabByName('งานของฉัน');
      expect(within(tab).queryByText('0')).toBeNull();
      expect(within(tab).queryByText(/รายการ/)).toBeNull();
      // The confident "you have no claimed work" copy must not appear underneath a failure.
      expect(screen.queryByText('ไม่มีงานที่คุณรับเรื่องค้างอยู่')).toBeNull();
    });

    // Mutation-checked (see the coordinator's review): temporarily changing myWorkQuery's
    // `enabled` to also require `activeTab === 'MY_WORK'` turns this test red — confirmed by
    // hand, then reverted; `git diff` shows no residual change from that check.
    it('bumps the งานของฉัน badge from N to N+1 after a pickup, without leaving รอรับเรื่อง', async () => {
      let pickedUp = false;
      api.pricingRequests.queue.mockImplementation((params) => {
        if (params?.assignedImportId === 5) {
          return Promise.resolve({
            items: [
              row({ id: 11, requestCode: 'PCR-2026-0011', status: 'IMPORT_REVIEWING', assignedImportId: 5 }),
              ...(pickedUp ? [row({ id: 16, requestCode: 'PCR-2026-0016', status: 'IMPORT_REVIEWING', assignedImportId: 5 })] : []),
            ],
          });
        }
        if (params?.status === 'SUBMITTED') {
          return Promise.resolve({ items: pickedUp ? [] : [row({ id: 16, requestCode: 'PCR-2026-0016', status: 'SUBMITTED' })] });
        }
        return Promise.resolve({ items: [] });
      });
      api.pricingRequests.pickup.mockImplementation(async () => {
        pickedUp = true;
        return { pricingRequest: { summary: row({ id: 16, status: 'IMPORT_REVIEWING', assignedImportId: 5 }), items: [], events: [] } };
      });
      renderQueuePage(importUser);

      // Start on งานของฉัน (N=1), then move to รอรับเรื่อง to pick up the second request —
      // asserting the badge WITHOUT ever switching back to งานของฉัน first.
      await waitFor(() => expect(within(tabByName('งานของฉัน')).getByText('1')).not.toBeNull());
      fireEvent.click(tabByName('รอรับเรื่อง'));
      const pickupButton = await screen.findByTestId('pcr-queue-pickup');
      fireEvent.click(pickupButton);
      await waitFor(() => expect(api.pricingRequests.pickup).toHaveBeenCalledWith(16));

      // Still on รอรับเรื่อง (never clicked งานของฉัน again) — the badge query
      // re-fetched on invalidation regardless, and the count reads N+1 = 2.
      await waitFor(() => expect(within(tabByName('งานของฉัน')).getByText('2')).not.toBeNull());
      expect(tabByName('รอรับเรื่อง').getAttribute('aria-selected')).toBe('true');
    });
  });

  describe('ceo role — task tabs (GLA-110)', () => {
    it('lands on รอฉันพิจารณา by default, fetching everything and narrowing client-side', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 21, requestCode: 'PCR-2026-0021', status: 'READY_FOR_CEO_REVIEW' }),
          row({ id: 22, requestCode: 'PCR-2026-0022', status: 'CEO_REVIEWING' }),
          row({ id: 23, requestCode: 'PCR-2026-0023', status: 'IMPORT_REVIEWING' }),
        ],
      });
      renderQueuePage(ceoUser);

      expect(await screen.findByText('PCR-2026-0021')).not.toBeNull();
      expect(screen.getByText('PCR-2026-0022')).not.toBeNull();
      expect(screen.queryByText('PCR-2026-0023')).toBeNull();
      await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ activeOnly: true }));
      expect(tabByName('รอฉันพิจารณา').getAttribute('aria-selected')).toBe('true');
      expect(within(tabByName('รอฉันพิจารณา')).getByText('2')).not.toBeNull();
    });

    it('offers only ทั้งหมด besides รอฉันพิจารณา (no งานของฉัน/รอรับเรื่อง for ceo)', async () => {
      renderQueuePage(ceoUser);
      await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalled());

      expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual([
        expect.stringContaining('รอฉันพิจารณา'),
        'ทั้งหมด',
      ]);
    });

    it('shows the empty state copy when nothing is waiting on the CEO', async () => {
      api.pricingRequests.queue.mockResolvedValue({ items: [row({ status: 'IMPORT_REVIEWING' })] });
      renderQueuePage(ceoUser);

      // DataTable renders the empty-state copy twice — a visible heading plus
      // an sr-only live-region echo — so this asserts at least one match
      // rather than a single unique element.
      await waitFor(() => expect(screen.getAllByText('ไม่มีคำขอราคาที่รอคุณพิจารณา').length).toBeGreaterThan(0));
    });

    it('shows no badge and an error empty-state when the รอฉันพิจารณา query fails', async () => {
      api.pricingRequests.queue.mockRejectedValue(new Error('เครือข่ายขัดข้อง'));
      renderQueuePage(ceoUser);

      await waitFor(() => expect(screen.getAllByText('โหลดคิวขอราคาไม่สำเร็จ').length).toBeGreaterThan(0));
      const tab = tabByName('รอฉันพิจารณา');
      expect(within(tab).queryByText('0')).toBeNull();
      expect(screen.queryByText('ไม่มีคำขอราคาที่รอคุณพิจารณา')).toBeNull();
    });
  });

  describe('sales_manager role — status quo, no task tabs', () => {
    it('renders no tablist and defaults to the SUBMITTED chip, exactly as before', async () => {
      renderQueuePage(salesManagerUser);

      expect(await screen.findByText('PCR-2026-0001')).not.toBeNull();
      expect(screen.queryByRole('tablist')).toBeNull();
      expect(screen.queryByRole('tab')).toBeNull();
      // Chips render unconditionally for a role with no task tabs.
      expect(screen.getByRole('button', { name: 'ทั้งหมด' })).not.toBeNull();
      await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ status: 'SUBMITTED', activeOnly: true }));
      // No task-tab queries (งานของฉัน/รอรับเรื่อง/รอฉันพิจารณา all `enabled: false` for this
      // role) — only the one chip-driven fetch a sales_manager has ever made.
      expect(api.pricingRequests.queue).toHaveBeenCalledTimes(1);
    });

    it('refetches with the new status when a filter pill is clicked', async () => {
      renderQueuePage(salesManagerUser);
      await screen.findByText('PCR-2026-0001');

      fireEvent.click(screen.getByRole('button', { name: 'ทั้งหมด' }));

      await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ status: undefined, activeOnly: true }));
    });

    it('offers only the three Import stages plus ทั้งหมด/รอฝ่ายนำเข้ารับเรื่อง/ยกเลิกแล้ว as chips', async () => {
      renderQueuePage(salesManagerUser);
      await screen.findByText('PCR-2026-0001');

      expect(chips()).toEqual([
        'ทั้งหมด',
        'รอฝ่ายนำเข้ารับเรื่อง',
        'รับเรื่อง',
        'เจรจาราคากับโรงงาน',
        'รอ CEO อนุมัติราคา',
        'ยกเลิกแล้ว',
      ]);
    });

    it('no longer offers the ขอข้อมูลเพิ่มเติม or แบบร่าง chips', async () => {
      renderQueuePage(salesManagerUser);
      await screen.findByText('PCR-2026-0001');

      // MORE_INFO_REQUIRED's feature was deleted from the product, and DRAFT is the
      // sales rep's private scratchpad — neither is Import queue work.
      expect(chips()).not.toContain('รอข้อมูลเพิ่มเติม');
      expect(chips()).not.toContain('แบบร่าง');
      expect(screen.queryByRole('button', { name: 'รอข้อมูลเพิ่มเติม' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'แบบร่าง' })).toBeNull();
    });

    it('renders both AWAITING_FACTORY_RESPONSE and COSTING_IN_PROGRESS as the merged เจรจาราคากับโรงงาน stage', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 11, requestCode: 'PCR-2026-0011', status: 'AWAITING_FACTORY_RESPONSE' }),
          row({ id: 12, requestCode: 'PCR-2026-0012', status: 'COSTING_IN_PROGRESS' }),
        ],
      });
      renderQueuePage(salesManagerUser);
      // ทั้งหมด so the two rows are shown by the API's own result, not by the
      // client-side narrowing the merged chip does (covered separately below).
      fireEvent.click(screen.getByRole('button', { name: 'ทั้งหมด' }));
      await screen.findByText('PCR-2026-0011');

      expect(tableRow('PCR-2026-0011').textContent).toContain('เจรจาราคากับโรงงาน');
      expect(tableRow('PCR-2026-0012').textContent).toContain('เจรจาราคากับโรงงาน');
      // The two statuses these replaced must not survive anywhere on the page.
      expect(screen.queryByText('รอราคาโรงงาน')).toBeNull();
      expect(screen.queryByText('กำลังร่างต้นทุน')).toBeNull();
    });

    it('narrows the merged เจรจาราคากับโรงงาน chip client-side, because the API takes a single status', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 11, requestCode: 'PCR-2026-0011', status: 'AWAITING_FACTORY_RESPONSE' }),
          row({ id: 12, requestCode: 'PCR-2026-0012', status: 'COSTING_IN_PROGRESS' }),
          row({ id: 13, requestCode: 'PCR-2026-0013', status: 'IMPORT_REVIEWING' }),
        ],
      });
      renderQueuePage(salesManagerUser);
      await screen.findByText('PCR-2026-0011');

      fireEvent.click(screen.getByRole('button', { name: 'เจรจาราคากับโรงงาน' }));

      // Fetches everything: neither of the two merged statuses may be pushed down as
      // the single `status` the endpoint accepts, or the other one's rows vanish.
      await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ status: undefined, activeOnly: true }));
      expect(api.pricingRequests.queue).not.toHaveBeenCalledWith(
        expect.objectContaining({ status: 'AWAITING_FACTORY_RESPONSE' }),
      );
      expect(api.pricingRequests.queue).not.toHaveBeenCalledWith(
        expect.objectContaining({ status: 'COSTING_IN_PROGRESS' }),
      );

      // ...and the rows that are not part of the merged stage are dropped locally.
      // Wait for the refetched page to actually render first: clicking the chip puts
      // the table into its loading state, where "row 13 is absent" would otherwise
      // pass for the wrong reason (nothing is rendered yet at all).
      expect(await screen.findByText('PCR-2026-0011')).not.toBeNull();
      expect(screen.getByText('PCR-2026-0012')).not.toBeNull();
      expect(screen.queryByText('PCR-2026-0013')).toBeNull();
    });

    it('still pushes the single-status รอ CEO อนุมัติราคา chip down to the API', async () => {
      renderQueuePage(salesManagerUser);
      await screen.findByText('PCR-2026-0001');

      fireEvent.click(screen.getByRole('button', { name: 'รอ CEO อนุมัติราคา' }));

      await waitFor(() => expect(api.pricingRequests.queue)
        .toHaveBeenCalledWith({ status: 'READY_FOR_CEO_REVIEW', activeOnly: true }));
    });

    it('does not offer a pickup action to a sales_manager viewer', async () => {
      renderQueuePage(salesManagerUser);

      await screen.findByText('PCR-2026-0001');
      expect(screen.queryByTestId('pcr-queue-pickup')).toBeNull();
      // Scoped to the table so the same-wording IMPORT_REVIEWING chip does not
      // masquerade as a pickup control and make this pass for the wrong reason.
      expect(within(screen.getByRole('table')).queryByRole('button', { name: 'รับเรื่อง' })).toBeNull();
    });
  });

  describe('ผู้รับเรื่อง assignee column — คุณ for my own rows', () => {
    it('renders คุณ when the row is assigned to the viewer, the name otherwise', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 11, requestCode: 'PCR-2026-0011', status: 'IMPORT_REVIEWING', assignedImportId: 5, assignedImportName: 'ฝ่ายนำเข้า' }),
          row({ id: 12, requestCode: 'PCR-2026-0012', status: 'IMPORT_REVIEWING', assignedImportId: 99, assignedImportName: 'คนอื่น' }),
        ],
      });
      renderQueuePage(importUser);
      await goToAllTab();
      await screen.findByText('PCR-2026-0011');

      expect(tableRow('PCR-2026-0011').textContent).toContain('คุณ');
      expect(tableRow('PCR-2026-0012').textContent).toContain('คนอื่น');
      expect(tableRow('PCR-2026-0012').textContent).not.toContain('คุณ');
    });

    it('renders the name (not คุณ) for a viewer that is not the assignee', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [row({ status: 'IMPORT_REVIEWING', assignedImportId: 99, assignedImportName: 'คนอื่น' })],
      });
      renderQueuePage(ceoUser);

      // ceo's default tab is รอฉันพิจารณา, which excludes IMPORT_REVIEWING — go
      // to ทั้งหมด to actually see this row rendered.
      await goToAllTab();
      expect(await screen.findByText('PCR-2026-0001')).not.toBeNull();
      expect(tableRow('PCR-2026-0001').textContent).toContain('คนอื่น');
      expect(tableRow('PCR-2026-0001').textContent).not.toContain('คุณ');
    });

    // Review finding: QueueCard (the mobile layout) never showed ผู้รับเรื่อง at all, so
    // "คุณ" never had anywhere to render on a phone. stubMobile() forces DataTable's
    // mobileCard path — without it this test would pass vacuously against the desktop
    // table instead (repo memory: "mobileCard tests are vacuous without a stub").
    it('shows a compact ผู้รับเรื่อง line on the mobile card, using the same คุณ rule as the column', async () => {
      stubMobile();
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 11, requestCode: 'PCR-2026-0011', status: 'IMPORT_REVIEWING', assignedImportId: 5, assignedImportName: 'ฝ่ายนำเข้า' }),
          row({ id: 12, requestCode: 'PCR-2026-0012', status: 'IMPORT_REVIEWING', assignedImportId: 99, assignedImportName: 'คนอื่น' }),
          row({ id: 13, requestCode: 'PCR-2026-0013', status: 'SUBMITTED', assignedImportId: null, assignedImportName: null }),
        ],
      });
      renderQueuePage(importUser);
      await goToAllTab();
      await screen.findByText('PCR-2026-0011');

      // DataTable's mobile layout renders each row as an <li class="record-card"> (no
      // `role="row"` grid to query by), so this finds each card by its unique requestCode
      // text and walks up to that <li>.
      function card(requestCode) {
        return screen.getByText(requestCode).closest('li');
      }

      expect(card('PCR-2026-0011').textContent).toContain('ผู้รับเรื่อง: คุณ');
      expect(card('PCR-2026-0012').textContent).toContain('ผู้รับเรื่อง: คนอื่น');
      expect(card('PCR-2026-0012').textContent).not.toContain('ผู้รับเรื่อง: คุณ');
      // No assignee at all (still SUBMITTED, unclaimed) — the line is omitted entirely,
      // not shown as "ผู้รับเรื่อง: -".
      expect(card('PCR-2026-0013').textContent).not.toContain('ผู้รับเรื่อง');
    });
  });

  describe('stale tab/chip reset on a role change (GLA-110 review fix)', () => {
    // This page is one long-lived instance across a session (AppShell never remounts it just
    // because `user` changed), so a stale activeTab/filterKey from the PREVIOUS role must not
    // survive into the new one — otherwise a ceo session that inherited import's 'UNCLAIMED' key
    // would silently fall through to ทั้งหมด's behaviour instead of ceo's own default tab.
    it('resets activeTab to the new role\'s default when user.role changes without a remount', async () => {
      api.pricingRequests.queue.mockResolvedValue({ items: [] });
      const { rerender } = renderQueuePage(importUser);
      await waitFor(() => expect(tabByName('งานของฉัน')).not.toBeNull());

      // Move off the default tab first, so a "reset" that merely no-ops would not be caught.
      fireEvent.click(tabByName('รอรับเรื่อง'));
      expect(tabByName('รอรับเรื่อง').getAttribute('aria-selected')).toBe('true');

      rerender(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
          <MemoryRouter initialEntries={['/pricing-requests']}>
            <PricingRequestQueuePage user={ceoUser} showToast={vi.fn()} />
          </MemoryRouter>
        </QueryClientProvider>,
      );

      await waitFor(() => expect(tabByName('รอฉันพิจารณา').getAttribute('aria-selected')).toBe('true'));
      expect(screen.queryByRole('tab', { name: /^งานของฉัน/ })).toBeNull();
      expect(screen.queryByRole('tab', { name: /^รอรับเรื่อง/ })).toBeNull();
    });
  });
});
