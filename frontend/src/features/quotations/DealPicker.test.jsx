import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DealPicker } from './DealPicker.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Slice 2 — flow A (SLICE-2-FLOW-A.md §A): "เลือกดีลที่มีอยู่". One combobox over the rows api.tickets.list()
// already returns (server-scoped: a sales rep only ever gets their own deals), filtered client-side on code ·
// customer · project.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      dealQuotations: { },
    },
  };
});

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales' };
const managerUser = { id: 11, name: 'ผึ้ง', role: 'sales_manager' };

const DEALS = [
  { id: 11, code: 'DL-2026-0011', customerName: 'บริษัท ลาดพร้าว จำกัด', projectName: 'โครงการ Central Ladprao', salesStage: 'PRESENTATION', createdById: 6, lifecycle: 'ACTIVE', liveDirectQuotation: null },
  { id: 12, code: 'DL-2026-0012', customerName: 'บริษัท บางนา จำกัด', projectName: 'Mega Bangna', salesStage: 'QUOTE_OWNER', createdById: 6, lifecycle: 'ACTIVE', liveDirectQuotation: null },
  // Another rep's deal — the list is server-scoped already, but a sales rep may create a quotation only on their
  // OWN deal (canCreateDealQuotation), so it is never offered even if it arrives.
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

function renderPicker({ user = salesUser, selected = null, recipientType = '', initialPath = '/quotations/new', showToast = vi.fn() } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <LocationProbe />
        <Routes>
          <Route path="/quotations/new" element={<DealPicker user={user} selected={selected} recipientType={recipientType} showToast={showToast} />} />
          <Route path="/quotations/:id" element={<QuotationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { showToast };
}

async function openList() {
  const input = await screen.findByRole('combobox', { name: /^งาน/ });
  fireEvent.focus(input);
  return input;
}

function optionTexts() {
  return within(screen.getByRole('listbox', { name: 'ผลการค้นหางาน' }))
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
});

