import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DealPicker } from './DealPicker.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Slice 2 — flow A (SLICE-2-FLOW-A.md §A): "เลือกดีลที่มีอยู่". One combobox over the rows
// api.tickets.list() already returns (server-scoped: a sales rep only ever gets their own deals),
// filtered client-side on code · customer · project. Picking one sets `?ticket=<id>` — the editor's
// EXISTING ?ticket= path, not a second code path. A picked deal that already has a live direct
// quotation (TicketSummaryDto.liveDirectQuotation, S2-B4) gets an inline notice — never a modal.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      dealQuotations: { createRevision: vi.fn() },
    },
  };
});

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales' };
const managerUser = { id: 11, name: 'ผึ้ง', role: 'sales_manager' };

const DEALS = [
  { id: 11, code: 'DL-2026-0011', customerName: 'บริษัท ลาดพร้าว จำกัด', projectName: 'โครงการ Central Ladprao', salesStage: 'PRESENTATION', createdById: 6, lifecycle: 'ACTIVE', liveDirectQuotation: null },
  { id: 12, code: 'DL-2026-0012', customerName: 'บริษัท บางนา จำกัด', projectName: 'Mega Bangna', salesStage: 'QUOTE_OWNER', createdById: 6, lifecycle: 'ACTIVE', liveDirectQuotation: null },
  // Another rep's deal — the list is server-scoped already, but a sales rep may create a quotation
  // only on their OWN deal (canCreateDealQuotation), so it is never offered even if it arrives.
  { id: 13, code: 'DL-2026-0013', customerName: 'บริษัท คนอื่น จำกัด', projectName: 'โครงการคนอื่น', salesStage: 'PRESENTATION', createdById: 99, lifecycle: 'ACTIVE', liveDirectQuotation: null },
  // A lost deal cannot take a quotation — not offered.
  { id: 14, code: 'DL-2026-0014', customerName: 'บริษัท เสียงาน จำกัด', projectName: 'โครงการเสีย', salesStage: 'NEGOTIATION', createdById: 6, lifecycle: 'CLOSED_LOST', liveDirectQuotation: null },
];

function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location">{location.pathname}{location.search}</p>;
}

function QuotationProbe() {
  const { id } = useParams();
  return <p>quotation page {id}</p>;
}

function renderPicker({ user = salesUser, selected = null, initialPath = '/quotations/new', showToast = vi.fn() } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <LocationProbe />
        <Routes>
          <Route path="/quotations/new" element={<DealPicker user={user} selected={selected} showToast={showToast} />} />
          <Route path="/quotations/:id" element={<QuotationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { showToast };
}

function location() {
  return screen.getByTestId('location').textContent;
}

async function openList() {
  const input = await screen.findByRole('combobox', { name: /ดีล/ });
  fireEvent.focus(input);
  return input;
}

function optionTexts() {
  return within(screen.getByRole('listbox', { name: 'ผลการค้นหาดีล' }))
    .getAllByRole('option').map((o) => o.textContent);
}

describe('DealPicker — เลือกดีลที่มีอยู่ (slice 2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: DEALS });
  });

  it('reads api.tickets.list and offers only ACTIVE deals this user may quote on', async () => {
    renderPicker();
    await openList();
    await waitFor(() => expect(optionTexts()).toHaveLength(2));
    expect(api.tickets.list).toHaveBeenCalledWith({});
    const texts = optionTexts();
    expect(texts[0]).toContain('DL-2026-0011');
    expect(texts[1]).toContain('DL-2026-0012');
    expect(texts.join(' ')).not.toContain('DL-2026-0013');
    expect(texts.join(' ')).not.toContain('DL-2026-0014');
  });

  it('sales_manager may quote on any rep\'s deal, so the other rep\'s deal is offered to them', async () => {
    renderPicker({ user: managerUser });
    await openList();
    await waitFor(() => expect(optionTexts()).toHaveLength(3));
  });

  it('each option shows code · customer · project · the Thai stage label', async () => {
    renderPicker();
    await openList();
    const option = await screen.findByRole('option', { name: /DL-2026-0012/ });
    expect(option.textContent).toContain('บริษัท บางนา จำกัด');
    expect(option.textContent).toContain('Mega Bangna');
    expect(option.textContent).toContain('เสนอราคาเจ้าของโครงการ');
    expect(option.textContent).not.toContain('QUOTE_OWNER');
  });

  it('filters client-side on customer, project and code — case-insensitive, no extra request', async () => {
    renderPicker();
    const input = await openList();
    await waitFor(() => expect(optionTexts()).toHaveLength(2));

    fireEvent.change(input, { target: { value: 'bangna' } });
    expect(optionTexts()).toHaveLength(1);
    expect(optionTexts()[0]).toContain('DL-2026-0012');

    fireEvent.change(input, { target: { value: 'ลาดพร้าว' } });
    expect(optionTexts()).toHaveLength(1);
    expect(optionTexts()[0]).toContain('DL-2026-0011');

    fireEvent.change(input, { target: { value: 'dl-2026-0012' } });
    expect(optionTexts()[0]).toContain('DL-2026-0012');

    fireEvent.change(input, { target: { value: 'ไม่มีดีลนี้' } });
    expect(within(screen.getByRole('listbox', { name: 'ผลการค้นหาดีล' })).queryAllByRole('option')).toHaveLength(0);
    expect(screen.getByText('ไม่พบดีลที่ตรงกับคำค้น')).not.toBeNull();

    expect(api.tickets.list).toHaveBeenCalledTimes(1);
  });

  it('keyboard: ArrowDown walks the options (aria-activedescendant), Enter picks -> ?ticket=<id>', async () => {
    renderPicker();
    const input = await openList();
    await waitFor(() => expect(optionTexts()).toHaveLength(2));

    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(input.getAttribute('aria-activedescendant')).toBe('deal-option-11');
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(input.getAttribute('aria-activedescendant')).toBe('deal-option-12');
    fireEvent.keyDown(input, { key: 'Enter' });

    await waitFor(() => expect(location()).toBe('/quotations/new?ticket=12'));
  });

  it('keyboard: Escape closes the list without picking', async () => {
    renderPicker();
    const input = await openList();
    await waitFor(() => expect(optionTexts()).toHaveLength(2));
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(screen.queryByRole('listbox', { name: 'ผลการค้นหาดีล' })).toBeNull();
    expect(input.getAttribute('aria-expanded')).toBe('false');
    expect(location()).toBe('/quotations/new');
  });

  it('a mouse pick sets ?ticket=<id> too', async () => {
    renderPicker();
    await openList();
    fireEvent.mouseDown(await screen.findByRole('option', { name: /DL-2026-0011/ }));
    await waitFor(() => expect(location()).toBe('/quotations/new?ticket=11'));
  });

  it('a selected deal renders as a chip; "เปลี่ยนดีล" drops ?ticket= and brings the search back', async () => {
    renderPicker({ selected: DEALS[1], initialPath: '/quotations/new?ticket=12' });
    expect(screen.queryByRole('combobox', { name: /ดีล/ })).toBeNull();
    expect(screen.getByText('DL-2026-0012')).not.toBeNull();
    expect(screen.getByText('บริษัท บางนา จำกัด')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'เปลี่ยนดีล' }));
    await waitFor(() => expect(location()).toBe('/quotations/new'));
  });

  it('a deal list the role cannot read degrades to a calm Thai message, not a crash', async () => {
    api.tickets.list.mockRejectedValue(Object.assign(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'), { status: 403 }));
    renderPicker();
    expect(await screen.findByText(/โหลดรายการดีลไม่ได้/)).not.toBeNull();
  });
});

describe('DealPicker — the picked deal already has a live direct quotation (N6)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: DEALS });
  });

  function withLive(docStatus) {
    return { ...DEALS[1], liveDirectQuotation: { id: 77, number: 'QT-2026-0077-1', docStatus, recipientType: 'OWNER' } };
  }

  it('DRAFT: an inline notice (not a dialog) naming the quotation and its status, with เปิดใบเสนอราคา and the reason a new one cannot be made', () => {
    renderPicker({ selected: withLive('DRAFT'), initialPath: '/quotations/new?ticket=12' });
    const notice = screen.getByTestId('deal-picker-live-quotation');
    expect(notice.textContent).toContain('ดีลนี้มีใบเสนอราคาตรงที่ใช้งานอยู่ — QT-2026-0077-1 · ร่าง');
    // The disabled-with-reason half (DESIGN.md §14): the SAME reason the editor's บันทึกร่าง carries.
    expect(notice.textContent).toContain('สร้างใบเสนอราคาใหม่บนดีลนี้ไม่ได้');
    expect(within(notice).getByRole('link', { name: 'เปิดใบเสนอราคา' }).getAttribute('href')).toBe('/quotations/77');
    expect(within(notice).queryByRole('button', { name: 'สร้างฉบับแก้ไข' })).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('PENDING_APPROVAL: no revision offered either (only an APPROVED quotation may be revised)', () => {
    renderPicker({ selected: withLive('PENDING_APPROVAL'), initialPath: '/quotations/new?ticket=12' });
    const notice = screen.getByTestId('deal-picker-live-quotation');
    expect(notice.textContent).toContain('รออนุมัติ');
    expect(within(notice).queryByRole('button', { name: 'สร้างฉบับแก้ไข' })).toBeNull();
  });

  it('APPROVED: สร้างฉบับแก้ไข creates the revision (-n) and opens it', async () => {
    api.dealQuotations.createRevision.mockResolvedValue({ quotation: { id: 78, number: 'QT-2026-0077-2' } });
    const { showToast } = renderPicker({ selected: withLive('APPROVED'), initialPath: '/quotations/new?ticket=12' });
    const notice = screen.getByTestId('deal-picker-live-quotation');
    fireEvent.click(within(notice).getByRole('button', { name: 'สร้างฉบับแก้ไข' }));
    await waitFor(() => expect(api.dealQuotations.createRevision).toHaveBeenCalledWith(77, {}));
    expect(await screen.findByText('quotation page 78')).not.toBeNull();
    expect(showToast).toHaveBeenCalledWith('success', 'สร้างฉบับแก้ไขแล้ว');
  });

  it('no notice at all for a deal without a live direct quotation', () => {
    renderPicker({ selected: DEALS[1], initialPath: '/quotations/new?ticket=12' });
    expect(screen.queryByTestId('deal-picker-live-quotation')).toBeNull();
  });
});
