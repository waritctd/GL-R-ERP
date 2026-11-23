import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { LinkIrLineSheet } from './LinkIrLineSheet.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: { importDeals: { get: vi.fn() }, tickets: { get: vi.fn() }, storedImportRequests: { linkLine: vi.fn() } },
}));

const line = { id: 5, code: 'LOOSE', size: '20x20', qty: 10, unit: 'แผ่น' };
function show() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><LinkIrLineSheet ticketId={701} line={line} onClose={() => {}} showToast={() => {}} /></QueryClientProvider>);
}

describe('LinkIrLineSheet', () => {
  it('lists the deal lines from the price-free import view, never the ticket read import may not open', async () => {
    api.importDeals.get.mockResolvedValue({ deal: { items: [{ id: 9, brand: 'Raku', model: 'R1', size: '20x20', qty: 40 }] } });
    show();
    expect(await screen.findByRole('option', { name: /Raku R1 20x20/ })).toBeTruthy();
    expect(api.tickets.get).not.toHaveBeenCalled();
  });

  it('a failed load says so, and is not mistaken for a deal with no lines', async () => {
    api.importDeals.get.mockRejectedValue(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    show();
    expect((await screen.findByTestId('link-line-error')).textContent).toContain('โหลดรายการของงานไม่สำเร็จ');
    expect(screen.queryByTestId('link-line-none')).toBeNull();
  });
});
