import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DealDirectQuotationPanel } from './DealDirectQuotationPanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: { dealQuotations: { listForTicket: vi.fn(), downloadPdf: vi.fn(), downloadXlsx: vi.fn() } },
  };
});

const owner = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales' };
const deal = { id: 18, createdById: 6 };

function renderPanel(user = owner) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <DealDirectQuotationPanel ticketId={18} deal={deal} user={user} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// GLA-136 (owner ruling 2026-09-30): direct quotations are written only from /quotations — this
// deal-page panel lists them but no longer offers to create one.
describe('DealDirectQuotationPanel (GLA-136)', () => {
  beforeEach(() => vi.clearAllMocks());

  it.each([
    ['the owning rep', owner],
    ['sales_manager', { id: 11, name: 'ผึ้ง', role: 'sales_manager' }],
  ])('offers no "สร้างใบเสนอราคา" link to %s, and says where to create one', async (_label, user) => {
    api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
    renderPanel(user);
    expect(await screen.findByText('ยังไม่มีใบเสนอราคา')).not.toBeNull();
    expect(screen.getByText('สร้างได้จากหน้า ใบเสนอราคา')).not.toBeNull();
    expect(screen.queryByRole('link', { name: /สร้างใบเสนอราคา/ })).toBeNull();
    expect(document.querySelector('a[href*="/quotations/new"]')).toBeNull();
  });

  it('still lists the deal\'s quotations, each linking to its own page', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [{ id: 5, number: 'QT-2026-0005-1', docStatus: 'APPROVED', grandTotal: 1070,
        approvedByName: 'ผึ้ง', approvedAt: '2026-09-02T00:00:00Z', createdAt: '2026-09-01T00:00:00Z' }],
    });
    renderPanel();
    const link = await screen.findByRole('link', { name: 'QT-2026-0005-1' });
    expect(link.getAttribute('href')).toBe('/quotations/5');
  });
});
