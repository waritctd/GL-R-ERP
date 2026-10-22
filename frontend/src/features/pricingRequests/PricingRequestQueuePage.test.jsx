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
    // PricingRequestDtos.java's actual FIFO fields (~36-39) — default null so byOldestFirst's
    // `?? 0` fallback is exercised unless a test explicitly cares about ordering.
    submittedAt: null,
    createdAt: null,
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

// The desktop table's own row order, in DOM order (header row excluded) — used to assert
// byOldestFirst's re-sort in task tabs and its ABSENCE in ทั้งหมด.
function visibleRequestCodes() {
  return screen.getAllByRole('row')
    .slice(1)
    .map((r) => r.textContent.match(/PCR-2026-\d{4}/)?.[0])
    .filter(Boolean);
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

    // owner ask 2026-09-24: รอรับเรื่อง now shows its own count badge too, from the same
    // unclaimed (SUBMITTED) query — and, like งานของฉัน, without needing to open the tab first.
    it('shows the รอรับเรื่อง badge count (unclaimed SUBMITTED requests)', async () => {
      api.pricingRequests.queue.mockImplementation((params) => {
        if (params?.status === 'SUBMITTED') {
          return Promise.resolve({
            items: [
              row({ id: 21, requestCode: 'PCR-2026-0021', status: 'SUBMITTED' }),
              row({ id: 22, requestCode: 'PCR-2026-0022', status: 'SUBMITTED' }),
              row({ id: 23, requestCode: 'PCR-2026-0023', status: 'SUBMITTED' }),
            ],
          });
        }
        return Promise.resolve({ items: [row({ id: 11, status: 'IMPORT_REVIEWING', assignedImportId: 5 })] });
      });
      renderQueuePage(importUser);

      // Default tab is งานของฉัน — the รอรับเรื่อง badge appears without opening it.
      await waitFor(() => expect(within(tabByName('รอรับเรื่อง')).getByText('3')).not.toBeNull());
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
      // Isolate the assignedImportId-scoped call (myWorkQuery, the badge's own query):
      // unclaimedQuery now also fires unconditionally for import (2026-09-19), so a single
      // shared unresolved promise for every call would starve myWorkQuery of ITS OWN resolver
      // whenever unclaimedQuery happened to be the last one to call the mock. Let everything
      // else resolve immediately; only the badge's own query hangs.
      let resolveMyWork;
      api.pricingRequests.queue.mockImplementation((params) => {
        if (params?.assignedImportId === 5) return new Promise((resolve) => { resolveMyWork = resolve; });
        return Promise.resolve({ items: [] });
      });
      renderQueuePage(importUser);

      // The tab itself renders immediately; the badge must not, because nothing has
      // succeeded yet — Tabs.jsx never renders a badge span at all when `badge == null`.
      const tab = tabByName('งานของฉัน');
      expect(tab).not.toBeNull();
      expect(within(tab).queryByText('0')).toBeNull();
      expect(within(tab).queryByText(/รายการ/)).toBeNull();

      resolveMyWork({ items: [] });
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

  describe('task tabs are oldest-first (GLA-110 follow-up, 2026-09-19)', () => {
    it('shows งานของฉัน oldest-first even though the mock returns newest-first', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 11, requestCode: 'PCR-2026-0011', status: 'IMPORT_REVIEWING', assignedImportId: 5, submittedAt: '2026-09-18T10:00:00Z' }),
          row({ id: 12, requestCode: 'PCR-2026-0012', status: 'IMPORT_REVIEWING', assignedImportId: 5, submittedAt: '2026-09-16T10:00:00Z' }),
          row({ id: 13, requestCode: 'PCR-2026-0013', status: 'IMPORT_REVIEWING', assignedImportId: 5, submittedAt: '2026-09-17T10:00:00Z' }),
        ],
      });
      renderQueuePage(importUser);
      await screen.findByText('PCR-2026-0011');

      expect(visibleRequestCodes()).toEqual(['PCR-2026-0012', 'PCR-2026-0013', 'PCR-2026-0011']);
    });

    it('shows รอรับเรื่อง oldest-first', async () => {
      api.pricingRequests.queue.mockImplementation((params) => {
        if (params?.status === 'SUBMITTED') {
          return Promise.resolve({
            items: [
              row({ id: 21, requestCode: 'PCR-2026-0021', status: 'SUBMITTED', submittedAt: '2026-09-18T09:00:00Z' }),
              row({ id: 22, requestCode: 'PCR-2026-0022', status: 'SUBMITTED', submittedAt: '2026-09-15T09:00:00Z' }),
            ],
          });
        }
        return Promise.resolve({ items: [] });
      });
      renderQueuePage(importUser);
      fireEvent.click(tabByName('รอรับเรื่อง'));
      await screen.findByText('PCR-2026-0021');

      expect(visibleRequestCodes()).toEqual(['PCR-2026-0022', 'PCR-2026-0021']);
    });

    it('shows รอฉันพิจารณา oldest-first', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 31, requestCode: 'PCR-2026-0031', status: 'READY_FOR_CEO_REVIEW', submittedAt: '2026-09-18T08:00:00Z' }),
          row({ id: 32, requestCode: 'PCR-2026-0032', status: 'CEO_REVIEWING', submittedAt: '2026-09-14T08:00:00Z' }),
        ],
      });
      renderQueuePage(ceoUser);
      await screen.findByText('PCR-2026-0031');

      expect(visibleRequestCodes()).toEqual(['PCR-2026-0032', 'PCR-2026-0031']);
    });

    it('leaves ทั้งหมด in the API\'s own order — no FIFO re-sort there', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          // Newest first, deliberately the OPPOSITE of oldest-first — if ทั้งหมด applied
          // byOldestFirst too, this would render reversed.
          row({ id: 42, requestCode: 'PCR-2026-0042', status: 'IMPORT_REVIEWING', submittedAt: '2026-09-18T08:00:00Z' }),
          row({ id: 41, requestCode: 'PCR-2026-0041', status: 'IMPORT_REVIEWING', submittedAt: '2026-09-10T08:00:00Z' }),
        ],
      });
      renderQueuePage(importUser);
      await goToAllTab();
      await screen.findByText('PCR-2026-0042');

      expect(visibleRequestCodes()).toEqual(['PCR-2026-0042', 'PCR-2026-0041']);
    });

    it('prefers submittedAt over createdAt per-row, and ties break by id ascending', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          // Only createdAt (no submittedAt) — 2026-09-01, earlier than 62/63's submittedAt below.
          row({ id: 61, requestCode: 'PCR-2026-0061', status: 'IMPORT_REVIEWING', assignedImportId: 5, submittedAt: null, createdAt: '2026-09-01T00:00:00Z' }),
          // 62 and 63 share the EXACT same submittedAt — only id can break the tie. Listed here
          // with 63 first so a naive "keep API order on ties" implementation would fail.
          row({ id: 63, requestCode: 'PCR-2026-0063', status: 'IMPORT_REVIEWING', assignedImportId: 5, submittedAt: '2026-09-05T00:00:00Z', createdAt: '2026-08-01T00:00:00Z' }),
          row({ id: 62, requestCode: 'PCR-2026-0062', status: 'IMPORT_REVIEWING', assignedImportId: 5, submittedAt: '2026-09-05T00:00:00Z', createdAt: '2026-08-20T00:00:00Z' }),
        ],
      });
      renderQueuePage(importUser);
      await screen.findByText('PCR-2026-0061');

      // 61's createdAt fallback (Sep 1) sorts before 62/63's real submittedAt (Sep 5) — a row
      // WITH submittedAt is never pushed behind one using the createdAt fallback just because
      // the fallback happens to be a "lesser" field. Then 62 before 63 by id, since their
      // submittedAt ties exactly.
      expect(visibleRequestCodes()).toEqual(['PCR-2026-0061', 'PCR-2026-0062', 'PCR-2026-0063']);
    });

    // Mutation-checked: removing `key={activeTab ?? 'no-tabs'}` from the <DataTable> below turns
    // this red (confirmed by hand — commenting out the `key` prop made the manual sort survive
    // the tab switch — then restored; `git diff` shows no residual change from that check).
    it('does not carry a manual column-header sort from one tab into another', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 71, requestCode: 'PCR-2026-0071', status: 'IMPORT_REVIEWING', assignedImportId: 5, submittedAt: '2026-09-10T00:00:00Z' }),
          row({ id: 72, requestCode: 'PCR-2026-0072', status: 'IMPORT_REVIEWING', assignedImportId: 5, submittedAt: '2026-09-05T00:00:00Z' }),
        ],
      });
      renderQueuePage(importUser);
      await screen.findByText('PCR-2026-0071');
      // Default byOldestFirst order: 0072 (older) then 0071 (newer).
      expect(visibleRequestCodes()).toEqual(['PCR-2026-0072', 'PCR-2026-0071']);

      // Manually sort by เลขที่คำขอราคา ascending — flips the order to alphabetical (0071, 0072).
      fireEvent.click(screen.getByRole('button', { name: /เลขที่คำขอราคา/ }));
      await waitFor(() => expect(visibleRequestCodes()).toEqual(['PCR-2026-0071', 'PCR-2026-0072']));

      // Leave the tab and come back — the manual sort must NOT have survived; the tab's own
      // default (byOldestFirst) must be showing again, not the alphabetical order above.
      await goToAllTab();
      fireEvent.click(tabByName('งานของฉัน'));
      await waitFor(() => expect(visibleRequestCodes()).toEqual(['PCR-2026-0072', 'PCR-2026-0071']));
    });
  });

  describe('รอรับเรื่อง freshness on entry (GLA-110 review fix, 2026-09-19)', () => {
    it('refetches รอรับเรื่อง when entering the tab and the cached copy is stale', async () => {
      // The test QueryClient (renderQueuePage) sets no staleTime, so react-query's own default
      // of 0 applies — the mount-time fetch is stale the instant it settles. That is exactly the
      // scenario the fix targets: unclaimedQuery is `enabled` for the whole import session now,
      // so switching INTO this tab no longer gets a free "became enabled" refetch from
      // react-query itself — goToUnclaimed must force one when the cache has gone stale.
      let submittedCallCount = 0;
      api.pricingRequests.queue.mockImplementation((params) => {
        if (params?.status === 'SUBMITTED') {
          submittedCallCount += 1;
          return Promise.resolve({ items: [row({ status: 'SUBMITTED' })] });
        }
        return Promise.resolve({ items: [] });
      });
      renderQueuePage(importUser);
      // unclaimedQuery already fired once on mount (enabled the whole session, not tab-gated).
      await waitFor(() => expect(submittedCallCount).toBeGreaterThanOrEqual(1));
      const callsBeforeEntry = submittedCallCount;

      fireEvent.click(tabByName('รอรับเรื่อง'));

      await waitFor(() => expect(submittedCallCount).toBeGreaterThan(callsBeforeEntry));
    });

    it('invalidates the queue when a pickup fails, so a stale (already-claimed) row is dropped', async () => {
      api.pricingRequests.queue.mockImplementation((params) => {
        if (params?.status === 'SUBMITTED') return Promise.resolve({ items: [row({ status: 'SUBMITTED' })] });
        return Promise.resolve({ items: [] });
      });
      // Mirrors PricingRequestService.pickup's compare-and-set-miss 409: someone else already
      // claimed this row between requireViewable's read and this call.
      api.pricingRequests.pickup.mockRejectedValue(new Error('คำขอราคานี้ถูกรับเรื่องไปแล้วโดยผู้ใช้อื่น'));
      renderQueuePage(importUser);

      fireEvent.click(tabByName('รอรับเรื่อง'));
      const pickupButton = await screen.findByTestId('pcr-queue-pickup');
      const callsBeforePickup = api.pricingRequests.queue.mock.calls.length;
      fireEvent.click(pickupButton);

      await waitFor(() => expect(api.pricingRequests.pickup).toHaveBeenCalled());
      // onError invalidates the same ['pricingRequests'] key onSuccess does — both active
      // queries (myWorkQuery + unclaimedQuery, both enabled for the whole import session) are
      // refetched as a result, which is what actually drops the now-stale row from view.
      await waitFor(() => expect(api.pricingRequests.queue.mock.calls.length).toBeGreaterThan(callsBeforePickup));
    });
  });

  describe('empty งานของฉัน points to the next job (GLA-110 follow-up, 2026-09-19)', () => {
    it('shows an announced ไม่มีงานค้าง with the unclaimed count, and its button switches to + focuses รอรับเรื่อง', async () => {
      api.pricingRequests.queue.mockImplementation((params) => {
        if (params?.assignedImportId === 5) return Promise.resolve({ items: [] });
        if (params?.status === 'SUBMITTED') {
          return Promise.resolve({
            items: [
              row({ id: 51, requestCode: 'PCR-2026-0051', status: 'SUBMITTED' }),
              row({ id: 52, requestCode: 'PCR-2026-0052', status: 'SUBMITTED' }),
              row({ id: 53, requestCode: 'PCR-2026-0053', status: 'SUBMITTED' }),
            ],
          });
        }
        return Promise.resolve({ items: [] });
      });
      renderQueuePage(importUser);

      // role="status" (implicit aria-live="polite") is what makes this announced rather than
      // silent — assert the copy lives INSIDE that region, not just somewhere on the page.
      const status = await screen.findByRole('status');
      expect(status.textContent).toContain('ไม่มีงานค้าง');
      expect(status.textContent).toContain('มี 3 คำขอรอรับเรื่อง');

      fireEvent.click(within(status).getByRole('button', { name: 'ดูคำขอที่รอรับเรื่อง' }));

      expect(tabByName('รอรับเรื่อง').getAttribute('aria-selected')).toBe('true');
      expect(await screen.findByText('PCR-2026-0051')).not.toBeNull();
      // Review fix: this navigation did not originate from clicking the tab itself (the click
      // target was a button inside a different panel), so focus would otherwise land nowhere in
      // particular — it must end up ON the รอรับเรื่อง tab.
      await waitFor(() => expect(document.activeElement).toBe(tabByName('รอรับเรื่อง')));
    });

    it('does not show ไม่มีงานค้าง when รอรับเรื่อง is also empty — the plain empty state stays', async () => {
      api.pricingRequests.queue.mockResolvedValue({ items: [] });
      renderQueuePage(importUser);

      await waitFor(() => expect(screen.getAllByText('ไม่มีงานที่คุณรับเรื่องค้างอยู่').length).toBeGreaterThan(0));
      expect(screen.queryByText('ไม่มีงานค้าง')).toBeNull();
      expect(screen.queryByRole('button', { name: 'ดูคำขอที่รอรับเรื่อง' })).toBeNull();
    });

    it('shows the error empty-state, not ไม่มีงานค้าง, when งานของฉัน fails even though รอรับเรื่อง has requests', async () => {
      api.pricingRequests.queue.mockImplementation((params) => {
        if (params?.assignedImportId === 5) return Promise.reject(new Error('เครือข่ายขัดข้อง'));
        if (params?.status === 'SUBMITTED') return Promise.resolve({ items: [row({ status: 'SUBMITTED' })] });
        return Promise.resolve({ items: [] });
      });
      renderQueuePage(importUser);

      await waitFor(() => expect(screen.getAllByText('โหลดคิวขอราคาไม่สำเร็จ').length).toBeGreaterThan(0));
      expect(screen.queryByText('ไม่มีงานค้าง')).toBeNull();
      expect(screen.queryByRole('button', { name: 'ดูคำขอที่รอรับเรื่อง' })).toBeNull();
    });
  });

  describe('sales_manager role — no task tabs, defaults to ทั้งหมด (owner ruling, 2026-09-19)', () => {
    it('renders no tablist and defaults to the ทั้งหมด chip (owner ruling, 2026-09-19)', async () => {
      renderQueuePage(salesManagerUser);

      expect(await screen.findByText('PCR-2026-0001')).not.toBeNull();
      expect(screen.queryByRole('tablist')).toBeNull();
      expect(screen.queryByRole('tab')).toBeNull();
      // Chips render unconditionally for a role with no task tabs, ทั้งหมด already selected.
      expect(screen.getByRole('button', { name: 'ทั้งหมด' }).getAttribute('aria-pressed')).toBe('true');
      await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ status: undefined, activeOnly: true }));
      // No task-tab queries (งานของฉัน/รอรับเรื่อง/รอฉันพิจารณา all `enabled: false` for this
      // role) — only the one chip-driven fetch a sales_manager has ever made.
      expect(api.pricingRequests.queue).toHaveBeenCalledTimes(1);
    });

    it('refetches with the new status when a filter pill is clicked', async () => {
      renderQueuePage(salesManagerUser);
      await screen.findByText('PCR-2026-0001');

      // ทั้งหมด is already the default (owner ruling, 2026-09-19), so this clicks a DIFFERENT
      // chip — the SUBMITTED one, labelled รอฝ่ายนำเข้ารับเรื่อง — to prove clicking still refetches.
      fireEvent.click(screen.getByRole('button', { name: 'รอฝ่ายนำเข้ารับเรื่อง' }));

      await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ status: 'SUBMITTED', activeOnly: true }));
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
