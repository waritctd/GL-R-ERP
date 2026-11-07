import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
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
    },
  };
});

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales' };
const ceoUser = { id: 8, name: 'ราม', role: 'ceo' };
const salesManagerUser = { id: 9, name: 'ผึ้ง', role: 'sales_manager' };

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

// Prints the current search string so a test can assert the tab actually travels in the URL
// (owner feedback F5: "driven by the URL (`?status=`)") rather than only in component state.
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
    // BARE, no `{ counts: ... }` envelope — DealQuotationController#counts returns the
    // DealQuotationCountsDto record itself (review finding MED-3). This fixture wrapped it while
    // the page still read `r?.counts ?? r`, so it passed on the tolerance rather than on the
    // contract; with the tolerance gone the fixture has to match the controller.
    api.dealQuotations.counts.mockResolvedValue({ all: 7, pendingApproval: 2, needsRework: 3, cancelled: 1, approved: 4 });
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

  // F5: the five tabs the owner asked for, in order — ร่าง and ถูกแทนที่ are deliberately gone.
  it('renders exactly ทั้งหมด · รออนุมัติ · ฉบับแก้ · ยกเลิก · อนุมัติแล้ว', async () => {
    renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    const labels = screen.getAllByRole('tab').map((tab) => tab.textContent.replace(/\d+$/, ''));
    expect(labels).toEqual(['ทั้งหมด', 'รออนุมัติ', 'ฉบับแก้', 'ยกเลิก', 'อนุมัติแล้ว']);
    // Owner rewording (2026-09-15): the SUPERSEDED status label is now "ฉบับที่ไม่ได้ใช้แล้ว" --
    // still no tab of its own (unchanged decision; see QuotationListPage.jsx's own comment).
    expect(screen.queryByRole('tab', { name: /ฉบับที่ไม่ได้ใช้แล้ว/ })).toBeNull();
  });

  it('shows each tab its own count from GET /deal-quotations/counts', async () => {
    renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    await waitFor(() => expect(api.dealQuotations.counts).toHaveBeenCalledTimes(1));
    const tabs = await screen.findAllByRole('tab');
    // Label + count, concatenated, in DEAL_QUOTATION_STATUS_TABS order.
    await waitFor(() => expect(tabs.map((t) => t.textContent)).toEqual([
      'ทั้งหมด7', 'รออนุมัติ2', 'ฉบับแก้3', 'ยกเลิก1', 'อนุมัติแล้ว4',
    ]));
  });

  // Counts are advisory chrome. A failed counts call must not blank the list or print a 0 that
  // would read as "nothing here" — the tabs simply carry no number.
  it('still renders the tabs and the list when the counts request fails', async () => {
    api.dealQuotations.counts.mockRejectedValue(new Error('boom'));
    renderListPage(salesUser);

    expect(await screen.findByText('QD69-0001')).not.toBeNull();
    const tabs = await screen.findAllByRole('tab');
    expect(tabs.map((t) => t.textContent)).toEqual(['ทั้งหมด', 'รออนุมัติ', 'ฉบับแก้', 'ยกเลิก', 'อนุมัติแล้ว']);
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

  it('opens the tab named by ?status= rather than the role default', async () => {
    renderListPage(ceoUser, '/quotations?status=CANCELLED');
    await screen.findByText('QD69-0001');

    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ status: 'CANCELLED' }));
    expect(api.dealQuotations.list).not.toHaveBeenCalledWith({ status: 'PENDING_APPROVAL' });
    expect(screen.getByRole('tab', { name: /^ยกเลิก/ }).getAttribute('aria-selected')).toBe('true');
  });

  // A retired or mistyped key must fall back to this role's default tab, not render an empty list.
  it('falls back to the role default when ?status= names no tab', async () => {
    renderListPage(ceoUser, '/quotations?status=SUPERSEDED');
    await screen.findByText('QD69-0001');

    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ status: 'PENDING_APPROVAL' }));
  });

  // Regression: an approver's default tab is รออนุมัติ, and "no ?status=" MEANS that default —
  // so ทั้งหมด has to write its own key. Clearing the param instead bounced the CEO straight back
  // to รออนุมัติ, making ทั้งหมด unreachable for the one role whose default is not it.
  it('lets an approver actually reach ทั้งหมด, writing status=all to the URL', async () => {
    renderListPage(ceoUser);
    await screen.findByText('QD69-0001');

    fireEvent.click(screen.getByRole('tab', { name: /^ทั้งหมด/ }));

    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({}));
    expect(screen.getByTestId('location-search').textContent).toBe('?status=all');
    expect(screen.getByRole('tab', { name: /^ทั้งหมด/ }).getAttribute('aria-selected')).toBe('true');
  });

  it('renders the required columns: number, customer, sales rep, total, status, date', async () => {
    renderListPage(salesUser);

    await screen.findByText('QD69-0001');
    expect(screen.getByText('บริษัท แฟชั่นไอส์แลนด์ จำกัด')).not.toBeNull();
    expect(screen.getByText('คุณสมหมาย ขายดี')).not.toBeNull();
    // ร่าง is no longer a tab, so the row's own StatusBadge is the only place it appears.
    expect(screen.getByText('ร่าง')).not.toBeNull();
  });

  // Owner ruling 2026-09-24: only sales_manager/ceo (whose global list already contains every
  // deal) get a rep filter — everyone else's rows are already scoped to their own deals
  // server-side, so a per-rep filter over one rep's own rows is pointless chrome.
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

  // retainHeaderWhenEmpty keeps the COLUMN HEADER ROW (not the search box, which DataTable's
  // toolbar renders unconditionally) when a filter empties the list. It used to be see-all only;
  // now every role can empty the list via the email filter, so a plain sales user must get it too.
  it('keeps the column header row for a plain sales user whose email filter empties the list', async () => {
    api.dealQuotations.list.mockResolvedValue({
      items: [row({ id: 1, number: 'QD69-0001', contactEmail: 'a@example.co.th' })],
    });
    const { container } = renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    fireEvent.change(screen.getByRole('combobox', { name: 'กรองตามอีเมลลูกค้า' }), { target: { value: 'none' } });

    await waitFor(() => expect(screen.queryByText('QD69-0001')).toBeNull());
    expect(screen.getByText('ไม่มีใบเสนอราคาในเงื่อนไขนี้')).not.toBeNull();
    expect(container.querySelector('.table-head'), 'column header row must stay mounted').not.toBeNull();
  });

  it('a plain sales user does not see the rep dropdown, but does see the customer/project search box', async () => {
    renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    expect(screen.queryByRole('combobox', { name: 'กรองตามพนักงานขาย' })).toBeNull();
    expect(screen.getByPlaceholderText('ค้นหาลูกค้า / โครงการ')).not.toBeNull();
  });
});

// deal-party-model.md, prototype screen 1: a project quoted to several contractors is ONE deal, its
// bids listed together under a group row.
describe('QuotationListPage — จัดกลุ่มตามโครงการ', () => {
  const bid = (overrides) => row({
    ticketId: 18, ticketCode: 'DL-2026-0280', dealBuyerCount: 3, projectName: 'S26 HOTEL', docStatus: 'APPROVED', recipientType: 'BUYER', ...overrides,
  });
  beforeEach(() => {
    vi.clearAllMocks();
    api.dealQuotations.counts.mockResolvedValue({});
    api.dealQuotations.list.mockResolvedValue({
      items: [
        bid({ id: 3, number: 'QT-2026-0280-2', customerName: 'บจก.สมมติ ก่อสร้าง', partyStatus: 'QUOTED', grandTotal: 491400.03 }),
        row({ id: 2, number: 'QT-2026-0339-2', customerName: 'บริษัท เดี่ยว จำกัด', projectName: 'บ้านคุณตัวอย่าง', dealBuyerCount: 1, ticketId: 30 }),
        bid({ id: 1, number: 'QT-2026-0270-4', customerName: 'บริษัท ตัวอย่าง คอนสตรัคชั่น จำกัด', partyStatus: 'QUOTED', partyHeadline: true, grandTotal: 512900 }),
        bid({ id: 4, number: 'QT-2026-0340-1', customerName: 'บจก.ทิ้งท้าย', partyStatus: 'NOT_AWARDED' }),
      ],
    });
  });

  const s26Group = async () => (await screen.findAllByTestId('quotation-group-row')).find((g) => g.textContent.includes('S26 HOTEL'));

  it('always on: every deal gets a group row led by the deal, its quotations beneath it', async () => {
    const { container } = renderListPage(salesUser);
    const group = await s26Group();
    expect(within(group).getByTestId('quotation-group-deal').textContent).toContain('ดีล DL-2026-0280');
    expect(group.textContent).toContain('S26 HOTEL');
    const summary = within(group).getByTestId('quotation-group-summary').textContent;
    expect(summary).toContain('ใบเสนอราคา 3 รายการ');
    expect(summary).toContain('ผู้รับเหมา×3');
    expect(summary).toContain('รอผลประมูล · ยอดหลัก');
    expect(within(group).getByRole('link', { name: 'เปิดดีล DL-2026-0280' }).getAttribute('href')).toBe('/tickets/18');
    // Order: each group header where its newest row was, then its quotations; the single-quotation deal too.
    const numbers = [...container.querySelectorAll('tbody tr')].map((tr) => tr.textContent);
    expect(numbers[0]).toContain('S26 HOTEL');
    // The ยอดหลัก bid leads its group; the rest keep server order.
    expect(numbers[1]).toContain('QT-2026-0270-4');
    expect(numbers[2]).toContain('QT-2026-0280-2');
    expect(numbers[3]).toContain('QT-2026-0340-1');
    expect(numbers[4]).toContain('บ้านคุณตัวอย่าง');
    expect(numbers[4]).toContain('ใบเสนอราคา 1 รายการ');
    expect(numbers[5]).toContain('QT-2026-0339-2');
  });

  it('a grouped bid shows its outcome chip and ★ ยอดหลัก instead of the project name; NOT_AWARDED is dimmed', async () => {
    renderListPage(salesUser);
    await s26Group();
    const headlineRow = screen.getByText('QT-2026-0270-4').closest('tr');
    expect(within(headlineRow).getByText('★ ยอดหลัก')).toBeTruthy();
    expect(within(headlineRow).getByText('เสนอราคาแล้ว')).toBeTruthy();
    expect(within(headlineRow).queryByText('S26 HOTEL')).toBeNull();
    const lostRow = screen.getByText('QT-2026-0340-1').closest('tr');
    expect(lostRow.className).toContain('opacity-60');
    expect(within(lostRow).getByText('ไม่ได้งาน')).toBeTruthy();
    // A single-quotation deal's project is in its own header, not repeated on the row.
    expect(within(screen.getByText('QT-2026-0339-2').closest('tr')).queryByText('บ้านคุณตัวอย่าง')).toBeNull();
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

  it('groupQuotationRows numbers contractors, not quotations: one party\'s quotations sit together under one number', () => {
    const rows = [
      bid({ id: 2, partyId: 10, partyHeadline: true }),
      bid({ id: 4, partyId: 20 }),
      bid({ id: 3, partyId: 30 }),
      bid({ id: 1, partyId: 10, partyHeadline: true }),
    ];
    const members = groupQuotationRows(rows).filter((r) => r.inGroup);
    expect(members.map((m) => m.id)).toEqual([2, 1, 4, 3]);
    expect(members.map((m) => m.groupIndex)).toEqual([1, 1, 2, 3]);
    expect(members.map((m) => !!m.groupLast)).toEqual([false, false, false, true]);
  });

  it('desktop rail and closing edge sit on the row, not on each cell', () => {
    const rows = groupQuotationRows([bid({ id: 1, partyHeadline: true }), bid({ id: 3 })]);
    const head = quotationRowClassName(rows[0]);
    const last = quotationRowClassName(rows[rows.length - 1]);
    expect(head).not.toContain('[&>td]:border-t');
    expect(head).toContain('border-t-2');
    expect(last).not.toContain('[&>td]:border-b');
    expect(last).toContain('border-b-2');
    expect(last).not.toContain('[&>td:first-child]:shadow');
    expect(last).toContain('shadow-[inset_4px_0_0_var(--color-info)]');
  });

  it('every member after the first gets the dashed divider, even a second quotation of the same contractor', () => {
    const members = groupQuotationRows([bid({ id: 1, partyId: 10, partyHeadline: true }), bid({ id: 2, partyId: 10 }), bid({ id: 3, partyId: 20 })])
      .filter((r) => r.inGroup);
    expect(quotationRowClassName(members[0])).not.toContain('border-top-style:dashed');
    expect(quotationRowClassName(members[1])).toContain('border-top-style:dashed');
  });

  it('a member that opens or closes a page still looks like a card edge (group split by pagination)', () => {
    const [, first] = groupQuotationRows([bid({ id: 1, partyHeadline: true }), bid({ id: 3 }), bid({ id: 4 })]);
    const f = quotationRowClassName(first);
    expect(f).toContain('mobile:first:mt-0');
    expect(f).toContain('mobile:last:rounded-b-md');
  });

  it('search keeps a group whole: a status or customer match on one bid shows the header and every bid', async () => {
    renderListPage(salesUser);
    await s26Group();
    fireEvent.change(screen.getByPlaceholderText(/ค้นหา/), { target: { value: 'บจก.ทิ้งท้าย' } });
    await waitFor(() => expect(screen.queryByText('บริษัท เดี่ยว จำกัด')).toBeNull());
    expect(screen.getAllByTestId('quotation-group-row')).toHaveLength(1);
    expect(screen.getAllByTestId('quotation-group-index').map((c) => c.textContent)).toEqual(['1', '2', '3']);
  });

  it('last member box: no rounded top corners on mobile', () => {
    const rows = groupQuotationRows([bid({ id: 1, partyHeadline: true }), bid({ id: 3 })]);
    expect(quotationRowClassName(rows[rows.length - 1])).toContain('mobile:rounded-t-none');
  });

  it('row classes join a group into one box: header + members share the info rail/border; only the last member closes it', () => {
    const [head, first, , last] = groupQuotationRows([bid({ id: 1, partyHeadline: true }), bid({ id: 3 }), bid({ id: 4 })]);
    const h = quotationRowClassName(head);
    expect(h).toContain('border-info-border');
    expect(h).toContain('bg-info-bg-alt');
    expect(h).toContain('mobile:rounded-b-none');
    const f = quotationRowClassName(first);
    expect(f).toContain('mobile:rounded-none');
    expect(f).toContain('mobile:-mt-[10px]');
    expect(f).toContain('border-info-border');
    expect(f).not.toContain('mobile:rounded-b-md');
    expect(quotationRowClassName(last)).toContain('mobile:rounded-b-md');
    expect(quotationRowClassName(last)).toContain('border-b-2');
  });

  it('header: deal code link right of the title line, project name in the customer-name style, no separate count badge', async () => {
    renderListPage(salesUser);
    const group = await s26Group();
    expect(group.textContent).not.toMatch(/^3 ผู้รับเหมา/);
    expect(within(group).getByText('S26 HOTEL').className).toContain('text-md font-extrabold');
    expect(group.innerHTML).not.toMatch(/text-\[\d+px\]/);
  });

  it('a grouped member carries its numbered circle', async () => {
    renderListPage(salesUser);
    await s26Group();
    const circles = screen.getAllByTestId('quotation-group-index').map((c) => c.textContent);
    expect(circles).toEqual(['1', '2', '3']);
  });

  it('has no grouping switch and no expand-all switch: grouping is always on', async () => {
    renderListPage(salesUser);
    await s26Group();
    expect(screen.queryByTestId('quotation-group-toggle')).toBeNull();
    expect(screen.queryByTestId('quotation-expand-toggle')).toBeNull();
    expect(screen.queryByText('จัดกลุ่มตามโครงการ')).toBeNull();
  });

  it('on a phone a member card is indented behind a connector line; an older version one step further', () => {
    const rows = groupQuotationRows([
      row({ id: 2, number: 'QT-2026-0500-2', ticketId: 50, partyId: 1, docStatus: 'APPROVED' }),
      row({ id: 1, number: 'QT-2026-0500-1', ticketId: 50, partyId: 1, docStatus: 'SUPERSEDED' }),
    ]);
    const [, current, older] = rows;
    expect(quotationRowClassName(current)).toContain('mobile:before:bg-info-border');
    expect(quotationRowClassName(current)).toContain('mobile:pl-9');
    expect(quotationRowClassName(older)).toContain('mobile:pl-12');
    // The line ends at the last card's branch instead of running past it.
    expect(quotationRowClassName(older)).toContain('mobile:before:h-6');
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

  beforeEach(() => {
    vi.clearAllMocks();
    api.dealQuotations.counts.mockResolvedValue({});
    api.dealQuotations.list.mockResolvedValue({ items: chain });
  });

  it('groupQuotationRows: one contractor with five versions is ONE group, the approved newest in force, older versions open beneath it', () => {
    const out = groupQuotationRows(chain);
    expect(out.filter((r) => r.isGroup)).toHaveLength(1);
    const members = out.filter((r) => r.inGroup);
    expect(members.map((m) => m.number)).toEqual(['QT-2026-0303-5', 'QT-2026-0303-4', 'QT-2026-0303-3', 'QT-2026-0303-2', 'QT-2026-0303-1']);
    expect(members[0]).toMatchObject({ olderCount: 4, versionNo: 5, versionsOpen: true });
    expect(members.slice(1).every((m) => m.isOlderVersion)).toBe(true);
    expect(quotationRowClassName(members[1])).toContain('bg-surface-muted');
    expect(members[4].groupLast).toBe(true);
    expect(out.find((r) => r.isGroup)).toMatchObject({ buyerCount: 1, versionCount: 5, documentCount: 1 });
  });

  it('a collapsed document shows only its version in force', () => {
    const members = groupQuotationRows(chain, { collapsed: new Set(['307:QT-2026-0303']) }).filter((r) => r.inGroup);
    expect(members.map((m) => m.number)).toEqual(['QT-2026-0303-5']);
    expect(members[0].versionsOpen).toBe(false);
  });

  it('the version in force is the newest APPROVED one, not just the newest', () => {
    const rows = [rev(3, { docStatus: 'DRAFT' }), rev(2, { docStatus: 'APPROVED' }), rev(1, { docStatus: 'SUPERSEDED' })];
    const [, inForce] = groupQuotationRows(rows);
    expect(inForce.number).toBe('QT-2026-0303-2');
    expect(inForce.olderCount).toBe(2);
  });

  it('a number without a revision suffix is never folded with its neighbours', () => {
    const rows = [row({ id: 1, number: 'QT-2026-0901', ticketId: 9, partyId: 1 }), row({ id: 2, number: 'QT-2026-0902', ticketId: 9, partyId: 1 })];
    expect(groupQuotationRows(rows).filter((r) => r.inGroup).every((r) => !r.isOlderVersion)).toBe(true);
  });

  it('a deal with a single quotation row still gets its group row', () => {
    const out = groupQuotationRows([rev(5)]);
    expect(out[0]).toMatchObject({ isGroup: true, documentCount: 1, versionCount: 1 });
    expect(out[1]).toMatchObject({ inGroup: true, number: 'QT-2026-0303-5', groupFirst: true, groupLast: true });
  });

  it('renders a one-contractor deal with no numbered circle; older versions start open and the button hides them', async () => {
    renderListPage(salesUser);
    const group = await screen.findByTestId('quotation-group-row');
    expect(group.textContent).toContain('Toscana House');
    expect(within(group).getByTestId('quotation-group-summary').textContent).toBe('ใบเสนอราคา 1 รายการ (5 เวอร์ชัน)');
    expect(screen.queryByTestId('quotation-group-index')).toBeNull();
    expect(screen.getByText('QT-2026-0303-4')).toBeTruthy();
    const toggle = screen.getByTestId('quotation-versions-toggle');
    expect(toggle.textContent).toContain('ซ่อนเวอร์ชันก่อนหน้า 4');
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    fireEvent.click(toggle);
    await waitFor(() => expect(screen.queryByText('QT-2026-0303-4')).toBeNull());
    expect(screen.getByTestId('quotation-versions-toggle').getAttribute('aria-expanded')).toBe('false');
    expect(screen.getByTestId('quotation-versions-toggle').textContent).toContain('เวอร์ชันก่อนหน้า 4');
    fireEvent.click(screen.getByTestId('quotation-versions-toggle'));
    await screen.findByText('QT-2026-0303-1');
  });

  it('a search for an older version\'s number still finds its group', async () => {
    renderListPage(salesUser);
    await screen.findByTestId('quotation-group-row');
    fireEvent.change(screen.getByPlaceholderText(/ค้นหา/), { target: { value: 'QT-2026-0303-2' } });
    expect(await screen.findByText('QT-2026-0303-5')).toBeTruthy();
  });
});

// Not every quotation is for a contractor: each row says who it is for, and the deal header counts by role.
describe('QuotationListPage — who each quotation is for', () => {
  const q = (overrides) => row({ ticketId: 70, ticketCode: 'DL-2026-0070', projectName: 'โครงการ ผู้รับหลายฝ่าย', docStatus: 'APPROVED', ...overrides });
  const items = [
    q({ id: 4, number: 'QT-2026-0704-1', customerName: 'บจก.รับเหมา หนึ่ง', recipientType: 'BUYER', partyId: 4, partyStatus: 'QUOTED' }),
    q({ id: 3, number: 'QT-2026-0703-1', customerName: 'บจก.รับเหมา สอง', recipientType: 'BUYER', partyId: 3, partyStatus: 'QUOTED' }),
    q({ id: 2, number: 'QT-2026-0702-1', customerName: 'บจก.เจ้าของ ตัวอย่าง', recipientType: 'OWNER', partyId: 2, partyStatus: 'QUOTED' }),
    q({ id: 1, number: 'QT-2026-0701-1', customerName: 'บจก.ดีไซน์ ตัวอย่าง', recipientType: 'DESIGNER', partyId: 1, partyStatus: 'QUOTED' }),
    row({ id: 9, number: 'QT-2026-0109-1', customerName: 'บจก.ใบเก่า', projectName: 'ใบเก่า', ticketId: 71, ticketCode: 'DL-2026-0071', recipientType: 'UNSPECIFIED' }),
  ];
  beforeEach(() => {
    vi.clearAllMocks();
    api.dealQuotations.counts.mockResolvedValue({});
    api.dealQuotations.list.mockResolvedValue({ items });
  });

  it('labels each row with its recipient role; an unrecorded recipient gets no label', async () => {
    renderListPage(salesUser);
    await screen.findAllByTestId('quotation-group-row');
    const roleOf = (number) => within(screen.getByText(number).closest('tr')).queryByTestId('quotation-recipient-chip')?.dataset.role ?? null;
    expect(roleOf('QT-2026-0701-1')).toBe('DESIGNER');
    expect(roleOf('QT-2026-0702-1')).toBe('OWNER');
    expect(roleOf('QT-2026-0704-1')).toBe('BUYER');
    expect(roleOf('QT-2026-0109-1')).toBeNull();
    expect(screen.queryByText('ไม่ระบุ')).toBeNull();
    // The bid outcome chip belongs to contractors only.
    expect(within(screen.getByText('QT-2026-0701-1').closest('tr')).queryByText('เสนอราคาแล้ว')).toBeNull();
    expect(within(screen.getByText('QT-2026-0704-1').closest('tr')).getByText('เสนอราคาแล้ว')).toBeTruthy();
  });

  it('the deal header counts by role, and only contractors bring the bid state', async () => {
    renderListPage(salesUser);
    const group = (await screen.findAllByTestId('quotation-group-row')).find((g) => g.textContent.includes('DL-2026-0070'));
    const roles = within(group).getAllByTestId('quotation-recipient-chip').map((c) => `${c.dataset.role}:${c.textContent}`);
    expect(roles).toEqual(['DESIGNER:ผู้ออกแบบ', 'OWNER:เจ้าของโครงการ', 'BUYER:ผู้รับเหมา×2']);
    expect(group.textContent).toContain('รอผลประมูล');
    const old = (await screen.findAllByTestId('quotation-group-row')).find((g) => g.textContent.includes('DL-2026-0071'));
    expect(within(old).queryByTestId('quotation-recipient-chip')).toBeNull();
    expect(old.textContent).not.toContain('รอผลประมูล');
  });
});

describe('QuotationListPage column floors (responsive review, 2026-09-10)', () => {
  // ⚠️ WHAT THIS CAN AND CANNOT PROVE. jsdom does no grid layout and no text measurement, so it
  // cannot observe the defect this pins: at a 721px viewport the six `minmax(0, …)` tracks were
  // shrunk below their own text and the cells clip rather than overflow, so `QT-2026-0005-2`
  // rendered `QT-2026-000…` — losing the `-N` suffix that is the only thing distinguishing a
  // revision from the document it replaces. That was measured in a real browser and can only be
  // RE-measured in one. This is a TEXT guard on the decisions that fix it:
  //
  //   1. FIXED floors on the four columns that cannot wrap (เลขที่, ยอดรวม, สถานะ, วันที่) plus a
  //      smaller one on พนักงานขาย. Fixed, never `min-content` — the head row and each data row are
  //      separate grid containers, so `min-content` sizes each row from its own content and the
  //      columns stagger row to row.
  //   2. ลูกค้า / โครงการ keeps `minmax(0, …)` and stays the SECOND track: it is the designated
  //      absorber, the one column of free text that can give up width without losing anything.
  //   3. Both free-text cells render a <span> wrapper, because styles.css's wrap escape hatch
  //      (`.data-row > td > strong | small | span`) reaches nothing else — a <div> child, or a bare
  //      string with no child element at all, inherits the cell's `nowrap` and is truncated.
  //
  // The MAGNITUDES are pinned literally, not merely counted. Shrinking every floor, or permuting
  // them so ยอดรวม inherits สถานะ's, would keep any count-based assertion green while silently
  // clipping the money again — that exact escape was found on the sibling page's guard.
  function gridClasses(el, what) {
    expect(el, `no ${what} to read the grid classes from`).toBeTruthy();
    return el.className;
  }

  it('floors the columns that cannot wrap and leaves ลูกค้า / โครงการ as the absorber', async () => {
    api.dealQuotations.list.mockResolvedValue({ items: [row()] });
    const { container } = renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    // Asserted on the head row AND a data row. They share one constant today, so this cannot
    // diverge — but splitting the grid and flooring only one is exactly how the header would stop
    // lining up with the body, and a guard that reads only the head row would stay green.
    for (const [el, what] of [
      [container.querySelector('.table-head'), '.table-head'],
      [container.querySelector('.data-row'), '.data-row'],
    ]) {
      const classes = gridClasses(el, what);

      // `min-content` is the specific WRONG floor: it re-sizes per row and staggers the columns.
      expect(classes, `${what}: min-content floors stagger the columns row to row`)
        .not.toContain('min-content');

      // Two shrinkable tracks — ลูกค้า / โครงการ and พนักงานขาย, the two free-text columns that
      // wrap — and they are tracks TWO and THREE.
      const shrinkable = classes.match(/minmax\(0,/g) ?? [];
      expect(shrinkable, `${what}: only the two free-text columns may shrink below their content`)
        .toHaveLength(2);

      // The whole track list, pinned literally. The values are each column's widest realistic
      // value measured in the live cell typography and rounded up; ยอดรวม and วันที่ are sized
      // against the FALLBACK font, not Sarabun, because Sarabun is swap-loaded from Google Fonts
      // and absent entirely on an on-prem host that cannot reach them. Re-measure before changing
      // any of these — see the source comment beside LIST_TABLE_GRID.
      expect(classes).toContain(
        'grid-cols-[minmax(6.875rem,1fr)_minmax(0,2.2fr)_minmax(0,1.3fr)'
        + '_minmax(8.25rem,1.1fr)_minmax(4.75rem,1fr)_minmax(6.5rem,1fr)]',
      );
    }
  });

  it('keeps LIST_TABLE_GRID as ONE unbroken literal so Tailwind can see it', () => {
    // Reading this component's own SOURCE, not its rendered output, and that is the point.
    // Tailwind's scanner matches whole class strings in the source text; splitting the literal
    // across a `+` concatenation — which looks like a harmless tidy-up, and is exactly what the
    // assertion above does to itself — leaves the arbitrary value unseen, so the rule is NEVER
    // EMITTED. `grid-template-columns` then goes unset, `.table-head`/`.data-row` supply
    // `display:grid` with no columns of their own, and every row collapses to one implicit track
    // (measured: 63.5px tall becomes 242px, all six cells stacked).
    //
    // Nothing else catches it: lint passes, this suite passes, `npm run build` exits 0 with no
    // warning, and the dev server keeps rendering correctly from Tailwind's cache. The only
    // evidence is that `dist/assets/*.css` stops containing the value. A source-text assertion is
    // the cheapest guard that fails in CI instead.
    // `import.meta.url` is not a file: URL under vitest's transform, so resolve from the runner's
    // root (frontend/) instead.
    const source = readFileSync(
      resolve(process.cwd(), 'src/features/quotations/QuotationListPage.jsx'), 'utf8',
    );
    const declaration = source
      .split('\n')
      .find((line) => line.includes('const LIST_TABLE_GRID'));
    expect(declaration, 'LIST_TABLE_GRID declaration not found').toBeTruthy();
    // The whole value must sit on that one line, ending the statement there.
    expect(declaration.trimEnd().endsWith("]';"), 'the grid literal must not be split or continued')
      .toBe(true);
    for (const track of ['6.875rem', '2.2fr', '1.3fr', '8.25rem', '4.75rem', '6.5rem']) {
      expect(declaration, `track ${track} must be in the same literal`).toContain(track);
    }
  });

  it('pins the COLUMNS order the track list is measured against', async () => {
    // The grid string and the COLUMNS array are two halves of one contract, and pinning only the
    // string leaves the other half free. Swapping the ยอดรวม and สถานะ entries in COLUMNS, with the
    // grid untouched, puts a grand total in the 76px status track: measured at 721px,
    // `฿600,616.00` breaks across two lines mid-number while สถานะ sits in 132px. That is the same
    // defect the permuted-floors mutation covers, reached from the other side.
    api.dealQuotations.list.mockResolvedValue({ items: [row()] });
    const { container } = renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    const headers = [...container.querySelector('.table-head').children]
      .map((cell) => cell.textContent.replace(/[▲▼↑↓]/g, '').trim());
    expect(headers).toEqual([
      'เลขที่', 'ลูกค้า / ผู้รับใบเสนอราคา', 'พนักงานขาย', 'ยอดรวม', 'สถานะ', 'วันที่',
    ]);
  });

  it('gives both free-text cells a <span> so they can wrap instead of truncating', async () => {
    api.dealQuotations.list.mockResolvedValue({
      items: [row({ customerName: 'บริษัท เดโม เรสซิเดนซ์ จำกัด', projectName: 'Residence Nawamin 76' })],
    });
    const { container } = renderListPage(salesUser);
    await screen.findByText('บริษัท เดโม เรสซิเดนซ์ จำกัด');

    const cells = [...container.querySelector('.data-row').children];

    // ลูกค้า / โครงการ — the wrapper must be a <span>. A <div> here inherits the cell's nowrap,
    // which is what stopped the absorber absorbing anything.
    const customerCell = cells[1];
    const customerWrapper = customerCell.firstElementChild;
    expect(customerWrapper?.tagName, 'ลูกค้า / โครงการ wrapper must be a <span>, not a <div>')
      .toBe('SPAN');
    expect(customerWrapper.textContent).toContain('บริษัท เดโม เรสซิเดนซ์ จำกัด');
    expect(customerWrapper.textContent).toContain('Residence Nawamin 76');

    // พนักงานขาย — a bare string leaves no child element for the escape hatch to match at all.
    const repCell = cells[2];
    expect(repCell.firstElementChild?.tagName, 'พนักงานขาย must render inside a <span>')
      .toBe('SPAN');
    expect(repCell.textContent).toBe('คุณสมหมาย ขายดี');
  });
});
