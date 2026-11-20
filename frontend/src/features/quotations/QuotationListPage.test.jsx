import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationListPage, groupQuotationRows, quotationRowClassName } from './QuotationListPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      dealQuotations: {
        list: vi.fn(),
        counts: vi.fn(),
      },
      depositNotices: { listAll: vi.fn(), counts: vi.fn() },
      storedRemainingInvoices: { listAll: vi.fn(), counts: vi.fn() },
    },
  };
});

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales' };
const ceoUser = { id: 8, name: 'ราม', role: 'ceo' };
const salesManagerUser = { id: 9, name: 'ผึ้ง', role: 'sales_manager' };
const accountUser = { id: 11, name: 'บัญชี', role: 'account' };
const importUser = { id: 7, name: 'นำเข้า', role: 'import' };

function noticeRow(origin = {}, overrides = {}) {
  return {
    id: 501, status: 'ISSUED', docNumber: 'GLRD69041', version: 1, issueDate: '2026-11-10', depositPercent: 0.3,
    totalPayable: 107000, moneyReceived: true, viewerMayWrite: true,
    origin: {
      ticketId: 91, ticketCode: 'DL-2611-001', projectName: 'โครงการตัวอย่าง', customerName: 'บริษัท ตัวอย่าง ก จำกัด',
      ownerId: 6, ownerName: 'คุณสมหมาย ขายดี', quotationId: 11, quotationNumber: 'QT-2026-9001', ...origin,
    },
    ...overrides,
  };
}

function row(overrides = {}) {
  return {
    id: 1,
    number: 'QD69-0001',
    customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด',
    projectName: null,
    salesRepId: 6,
    salesRepName: 'คุณสมหมาย ขายดี',
    grandTotal: 123456.78,
    docStatus: 'DRAFT',
    quotationDate: '2026-09-01',
    ...overrides,
  };
}

// This repo's Node ships a broken built-in `localStorage` (see quotationPrefs.test.js), so each test gets a
// fresh in-memory one.
function memoryStorage() {
  const map = new Map();
  return {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => { map.set(key, String(value)); },
    removeItem: (key) => { map.delete(key); },
    clear: () => map.clear(),
  };
}
beforeEach(() => vi.stubGlobal('localStorage', memoryStorage()));

// Prints the current search string so a test can assert the tab actually travels in the URL (owner feedback
// F5: "driven by the URL (`?status=`)") rather than only in component state.
function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location-search">{location.search}</p>;
}

function renderListPage(user, initialPath = '/quotations') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route
            path="/quotations"
            element={<><QuotationListPage user={user} /><LocationProbe /></>}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('QuotationListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.dealQuotations.list.mockResolvedValue({ items: [row()] });
    // BARE, no `{ counts: ... }` envelope — DealQuotationController#counts returns the DealQuotationCountsDto
    // record itself (review finding MED-3). This fixture wrapped it while the page still read `r?.counts ??
    api.dealQuotations.counts.mockResolvedValue({ all: 7, pendingApproval: 2, needsRework: 3, cancelled: 1, approved: 4 });
    api.depositNotices.listAll.mockResolvedValue({ depositNotices: [noticeRow()] });
    api.depositNotices.counts.mockResolvedValue({ all: 1, draft: 0, issued: 1 });
    api.storedRemainingInvoices.listAll.mockResolvedValue({ remainingInvoices: [] });
    api.storedRemainingInvoices.counts.mockResolvedValue({ all: 0, draft: 0, issued: 0 });
  });

  it('sales lands on ทั้งหมด (no approval queue of its own)', async () => {
    renderListPage(salesUser);

    expect(await screen.findByText('QD69-0001')).not.toBeNull();
    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({}));
  });

  it('sales_manager / ceo default to the รออนุมัติ queue', async () => {
    renderListPage(ceoUser);
    await screen.findByText('QD69-0001');
    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ status: 'PENDING_APPROVAL' }));

    vi.clearAllMocks();
    api.dealQuotations.list.mockResolvedValue({ items: [row()] });
    api.dealQuotations.counts.mockResolvedValue({});
    renderListPage(salesManagerUser);
    await screen.findByText('QD69-0001');
    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ status: 'PENDING_APPROVAL' }));
  });

  it('shows each tab its own count from GET /deal-quotations/counts', async () => {
    renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    await waitFor(() => expect(api.dealQuotations.counts).toHaveBeenCalledTimes(1));
    const tabs = await within(await screen.findByRole('tablist', { name: 'สถานะใบเสนอราคา' })).findAllByRole('tab');
    // Label + count, concatenated, in DEAL_QUOTATION_STATUS_TABS order.
    await waitFor(() => expect(tabs.map((t) => t.textContent)).toEqual([
      'ทั้งหมด7', 'รออนุมัติ2', 'ฉบับแก้3', 'ยกเลิก1', 'อนุมัติแล้ว4',
    ]));
  });

  it('drives the ฉบับแก้ tab off needsRework=true, not a docStatus, and writes it to the URL', async () => {
    renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    fireEvent.click(screen.getByRole('tab', { name: /^ฉบับแก้/ }));

    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ needsRework: true }));
    await waitFor(() => expect(screen.getByTestId('location-search').textContent).toBe('?status=NEEDS_REWORK'));
  });

  // QA-37: ISSUED/SENT/ACCEPTED are approved-and-beyond; the tab used to ask for APPROVED only.
  it('drives the อนุมัติแล้ว tab off every approved-and-beyond status, keeping ?status=APPROVED in the URL', async () => {
    renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    fireEvent.click(screen.getByRole('tab', { name: /^อนุมัติแล้ว/ }));

    await waitFor(() => expect(api.dealQuotations.list)
      .toHaveBeenCalledWith({ status: ['APPROVED', 'ISSUED', 'SENT', 'ACCEPTED'] }));
    await waitFor(() => expect(screen.getByTestId('location-search').textContent).toBe('?status=APPROVED'));
  });

  // Owner ruling 2026-09-24: only sales_manager/ceo (whose global list already contains every deal) get a rep
  // filter — everyone else's rows are already scoped to their own deals server-side, so a per-rep filter over
  // one rep's own rows is pointless chrome.
  it('sales_manager sees the rep dropdown and selecting a rep narrows the rows', async () => {
    api.dealQuotations.list.mockResolvedValue({
      items: [
        row({ id: 1, number: 'QD69-0001', salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี' }),
        row({ id: 2, number: 'QD69-0002', salesRepId: 9, salesRepName: 'ผึ้ง' }),
      ],
    });
    renderListPage(salesManagerUser);
    await screen.findByText('QD69-0001');
    expect(screen.getByText('QD69-0002')).not.toBeNull();

    const select = screen.getByRole('combobox', { name: 'กรองตามพนักงานขาย' });
    fireEvent.change(select, { target: { value: '6' } });

    expect(screen.getByText('QD69-0001')).not.toBeNull();
    expect(screen.queryByText('QD69-0002')).toBeNull();
    // The อีเมลลูกค้า filter sits beside the rep filter for see-all roles too (owner ask 2026-10-13).
    expect(screen.getByRole('combobox', { name: 'กรองตามอีเมลลูกค้า' })).not.toBeNull();
  });

  // Owner ask 2026-10-13: filter by whether the ลูกค้า has an email. Not a scope question, so
  // EVERY role gets it (a plain sales rep included), unlike the see-all-only rep filter. "มีอีเมล"
  // is contactEmail OR orderedByEmail non-blank — see quotationMeta.test.js for why those two.
  it('a plain sales user can filter by ไม่มีอีเมล / มีอีเมล, which narrows the rows', async () => {
    api.dealQuotations.list.mockResolvedValue({
      items: [
        row({ id: 1, number: 'QD69-0001', contactEmail: 'a@example.co.th', orderedByEmail: null }),
        row({ id: 2, number: 'QD69-0002', contactEmail: null, orderedByEmail: 'b@example.co.th' }),
        row({ id: 3, number: 'QD69-0003', contactEmail: null, orderedByEmail: null }),
      ],
    });
    renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    const select = screen.getByRole('combobox', { name: 'กรองตามอีเมลลูกค้า' });
    expect([...select.options].map((o) => o.textContent)).toEqual(['ทั้งหมด', 'มีอีเมล', 'ไม่มีอีเมล']);

    fireEvent.change(select, { target: { value: 'none' } });
    expect(screen.queryByText('QD69-0001')).toBeNull();
    expect(screen.queryByText('QD69-0002')).toBeNull();
    expect(screen.getByText('QD69-0003')).not.toBeNull();

    fireEvent.change(select, { target: { value: 'has' } });
    expect(screen.getByText('QD69-0001')).not.toBeNull();
    expect(screen.getByText('QD69-0002')).not.toBeNull();
    expect(screen.queryByText('QD69-0003')).toBeNull();

    fireEvent.change(select, { target: { value: '' } });
    expect(screen.getByText('QD69-0003')).not.toBeNull();
  });
});

// deal-party-model.md, prototype screen 1: a project quoted to several contractors is ONE deal, its
// bids listed together under a group row.
describe('QuotationListPage — เอกสารขาย document tabs', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.dealQuotations.list.mockResolvedValue({ items: [row()] });
    api.dealQuotations.counts.mockResolvedValue({ all: 5 });
    api.depositNotices.listAll.mockResolvedValue({ depositNotices: [noticeRow()] });
    api.depositNotices.counts.mockResolvedValue({ all: 3, draft: 1, issued: 2 });
    api.storedRemainingInvoices.listAll.mockResolvedValue({ remainingInvoices: [] });
    api.storedRemainingInvoices.counts.mockResolvedValue({ all: 0, draft: 0, issued: 0 });
  });

  const docTabs = () => within(screen.getByRole('tablist', { name: 'ประเภทเอกสาร' })).getAllByRole('tab');

  it('is titled เอกสารขาย, with three tabs that switch through ?doc=', async () => {
    renderListPage(salesUser);
    expect(screen.getByRole('heading', { name: 'เอกสารขาย' })).not.toBeNull();
    await waitFor(() => expect(docTabs().slice(0, 2).map((t) => t.textContent)).toEqual(['ใบเสนอราคา5', 'ใบแจ้งมัดจำ3']));
    expect(docTabs()).toHaveLength(3);
    expect(screen.getByRole('tab', { name: /^ใบแจ้งหนี้ส่วนที่เหลือ/ })).toBe(docTabs()[2]);

    fireEvent.click(docTabs()[1]);
    expect(await screen.findByText('GLRD69041')).not.toBeNull();
    expect(screen.getByTestId('location-search').textContent).toBe('?doc=deposit');
    expect(docTabs()[1].getAttribute('aria-selected')).toBe('true');

    fireEvent.click(docTabs()[2]);
    expect((await screen.findAllByText('ยังไม่มีใบแจ้งหนี้ส่วนที่เหลือ')).length).toBeGreaterThan(0);
  });

  it('a notice row names its quotation and links it only when the quotation is known', async () => {
    renderListPage(salesUser, '/quotations?doc=deposit');
    const link = await screen.findByRole('link', { name: 'QT-2026-9001' });
    expect(link.getAttribute('href')).toBe('/quotations/11');
    expect(screen.getByText('รับเงินแล้ว')).not.toBeNull();

    api.depositNotices.listAll.mockResolvedValue({ depositNotices: [noticeRow({ quotationId: null, quotationNumber: 'QT-2026-9003' })] });
    renderListPage(salesUser, '/quotations?doc=deposit');
    await waitFor(() => expect(screen.getAllByText('QT-2026-9003').length).toBeGreaterThan(0));
    expect(screen.queryByRole('link', { name: 'QT-2026-9003' })).toBeNull();
  });

  it('account gets no ฉบับร่าง chip; sales does', async () => {
    renderListPage(accountUser, '/quotations?doc=deposit');
    await screen.findByText('GLRD69041');
    expect(screen.queryByRole('button', { name: /ฉบับร่าง/ })).toBeNull();
    expect(screen.getByRole('button', { name: /ออกแล้ว/ })).not.toBeNull();
    expect(screen.getByRole('combobox', { name: 'กรองตามพนักงานขาย' })).not.toBeNull();
  });

  it('sales sees the draft chip', async () => {
    renderListPage(salesUser, '/quotations?doc=deposit');
    await screen.findByText('GLRD69041');
    expect(screen.getByRole('button', { name: /ฉบับร่าง/ })).not.toBeNull();
  });

  it('import sees only the quotation tab, and ?doc= is ignored', async () => {
    renderListPage(importUser, '/quotations?doc=deposit');
    expect(await screen.findByText('QD69-0001')).not.toBeNull();
    expect(screen.queryByRole('tablist', { name: 'ประเภทเอกสาร' })).toBeNull();
    expect(api.depositNotices.listAll).not.toHaveBeenCalled();
  });

  it('a quotation row lists the documents made from it', async () => {
    api.dealQuotations.list.mockResolvedValue({ items: [row({
      ticketId: 91,
      documents: { depositNotice: { id: 501, status: 'ISSUED', docNumber: 'GLRD69041', forThisQuotation: true }, remainingInvoices: [{ id: 7, status: 'DRAFT', docNumber: null, version: 3 }] },
    })] });
    renderListPage(salesUser);
    expect((await screen.findByRole('link', { name: /GLRD69041/ })).getAttribute('href')).toBe('/tickets/91/deposit');
    expect(screen.getByRole('link', { name: /ฉบับร่าง -3/ }).getAttribute('href')).toBe('/tickets/91?tab=money');
  });
});

describe('QuotationListPage — จัดกลุ่มตามโครงการ', () => {
  const bid = (overrides) => row({
    ticketId: 18, ticketCode: 'DL-2026-0280', dealBuyerCount: 3, projectName: 'S26 HOTEL', docStatus: 'APPROVED', recipientType: 'BUYER', ...overrides,
  });
  it('groupQuotationRows: headline first, 1-based groupIndex, groupSize, groupLast on the final member', () => {
    const rows = [
      bid({ id: 3, partyStatus: 'QUOTED' }),
      bid({ id: 1, partyHeadline: true }),
      bid({ id: 4 }),
    ];
    const members = groupQuotationRows(rows).filter((r) => r.inGroup);
    expect(members.map((m) => m.id)).toEqual([1, 3, 4]);
    expect(members.map((m) => m.groupIndex)).toEqual([1, 2, 3]);
    expect(members.every((m) => m.groupSize === 3)).toBe(true);
    expect(members.map((m) => !!m.groupLast)).toEqual([false, false, true]);
  });
});

// Every deal holding more than one quotation is grouped, a single contractor's revisions included, and
// the revisions of one document fold into the version in force (prototype: list-grouping, 2026-11-07).
describe('QuotationListPage — revisions of one document fold into the version in force', () => {
  const rev = (n, overrides = {}) => row({
    id: 100 + n, number: `QT-2026-0303-${n}`, customerName: 'บจก.สตูดิโอ ตัวอย่าง', projectName: 'Toscana House',
    ticketId: 307, ticketCode: 'DL-2026-0302', partyId: 7, dealBuyerCount: 1,
    docStatus: n === 5 ? 'APPROVED' : 'SUPERSEDED', revisionNo: n, ...overrides,
  });
  const chain = [rev(5), rev(4), rev(3), rev(2), rev(1)];

  it('groupQuotationRows: one contractor with five versions is ONE group, the approved newest in force, older versions open beneath it', () => {
    const out = groupQuotationRows(chain);
    expect(out.filter((r) => r.isGroup)).toHaveLength(1);
    const members = out.filter((r) => r.inGroup);
    expect(members.map((m) => m.number)).toEqual(['QT-2026-0303-5', 'QT-2026-0303-4', 'QT-2026-0303-3', 'QT-2026-0303-2', 'QT-2026-0303-1']);
    expect(members[0]).toMatchObject({ olderCount: 4, versionNo: 5, versionsOpen: true });
    expect(members.slice(1).every((m) => m.isOlderVersion)).toBe(true);
    expect(quotationRowClassName(members[1])).toContain('bg-surface-muted');
    expect(members[4].groupLast).toBe(true);
    expect(out.find((r) => r.isGroup)).toMatchObject({ partyCount: 1, versionCount: 5, documentCount: 1 });
  });

  it('the version in force is the newest APPROVED one, not just the newest', () => {
    const rows = [rev(3, { docStatus: 'DRAFT' }), rev(2, { docStatus: 'APPROVED' }), rev(1, { docStatus: 'SUPERSEDED' })];
    const [, inForce] = groupQuotationRows(rows);
    expect(inForce.number).toBe('QT-2026-0303-2');
    expect(inForce.olderCount).toBe(2);
  });
});

// Sales request 2026-11-16 (annotated screenshot from the sales manager): no deal grouping by default, newest
// number on top; search by the quotation total; the date column is the approval date.
describe('QuotationListPage — flat list by number (sales request 2026-11-16)', () => {
  const items = [
    row({ id: 11, number: 'QT-2026-0119-1', customerName: 'บริษัท สมมติ วูด จำกัด', projectName: 'บ้านคุณปองพล', ticketId: 114, ticketCode: 'PR-2026-0114', docStatus: 'SUPERSEDED', grandTotal: 40449.21, quotationDate: '2026-07-24' }),
    row({ id: 31, number: 'QT-2026-0141-2', customerName: 'คุณรมิตา เกรียงไกรรัตน์', projectName: 'บ้านคุณรมิตา', ticketId: 136, ticketCode: 'PR-2026-0136', docStatus: 'APPROVED', grandTotal: 7198.45, quotationDate: '2026-07-29', approvedAt: '2026-07-29T03:00:00Z' }),
    row({ id: 12, number: 'QT-2026-0119-2', customerName: 'บริษัท สมมติ วูด จำกัด', projectName: 'บ้านคุณปองพล', ticketId: 114, ticketCode: 'PR-2026-0114', docStatus: 'APPROVED', grandTotal: 5444.8, quotationDate: '2026-07-24',
      // 18:30 UTC on the 15th is already the 16th in Bangkok: the cell must show the Bangkok day.
      approvedAt: '2026-11-15T18:30:00Z' }),
    row({ id: 32, number: 'QT-2026-0141-3', customerName: 'คุณรมิตา เกรียงไกรรัตน์', projectName: 'บ้านคุณรมิตา', ticketId: 136, ticketCode: 'PR-2026-0136', docStatus: 'DRAFT', grandTotal: 6406.61, quotationDate: '2026-11-13', approvedAt: null }),
    row({ id: 13, number: 'QT-2026-0120-1', customerName: 'บริษัท สมมติ วูด จำกัด', projectName: 'บ้านคุณปองพล', ticketId: 114, ticketCode: 'PR-2026-0114', docStatus: 'SUPERSEDED', grandTotal: 9188.14, quotationDate: '2026-07-24' }),
  ];
  beforeEach(() => {
    vi.clearAllMocks();
    api.dealQuotations.counts.mockResolvedValue({});
    api.dealQuotations.list.mockResolvedValue({ items });
  });
  const bodyRows = (container) => [...container.querySelectorAll('tbody tr')];

  it.each([
    ['as printed, with the comma', '5,444.80'],
    ['digits only', '5444.8'],
    ['a leading fragment', '5,444'],
  ])('search finds a quotation by its total (%s)', async (_label, typed) => {
    const { container } = renderListPage(salesManagerUser);
    await screen.findByText('QT-2026-0141-3');
    fireEvent.change(screen.getByPlaceholderText('ค้นหาลูกค้า / โครงการ / ยอดรวม'), { target: { value: typed } });
    await waitFor(() => expect(bodyRows(container)).toHaveLength(1));
    expect(bodyRows(container)[0].textContent).toContain('QT-2026-0119-2');
  });

  it('the date column is the approval date in Bangkok time; an unapproved row shows a dash, not its document date', async () => {
    renderListPage(salesManagerUser);
    const approved = (await screen.findByText('QT-2026-0119-2')).closest('tr');
    expect(within(approved).getByTestId('quotation-approved-date').textContent).toBe('16 พ.ย. 2569');
    const draft = screen.getByText('QT-2026-0141-3').closest('tr');
    expect(within(draft).getByTestId('quotation-approved-date').textContent).toBe('—');
    expect(draft.textContent).not.toContain('13 พ.ย. 2569');
  });
});

