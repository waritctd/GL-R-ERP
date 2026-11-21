import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NotDeliveredSheet } from './NotDeliveredSheet.jsx';
import { api } from '../../../api/index.js';

globalThis.React = React;

vi.mock('../../../api/index.js', async (importOriginal) => ({
  ...(await importOriginal()),
  api: { tickets: { notDeliveredAllowed: vi.fn(), markNotDelivered: vi.fn() } },
}));

const item = { id: 5, brand: 'SCG', model: 'A1', size: '60x60' };

function renderSheet(onDone = vi.fn()) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <NotDeliveredSheet ticketId={701} item={item} onClose={vi.fn()} onDone={onDone} />
    </QueryClientProvider>,
  );
  return onDone;
}

afterEach(() => vi.clearAllMocks());

describe('NotDeliveredSheet (ไม่ได้รับของ)', () => {
  it('defaults to the most the server allows, needs a reason, then marks the line', async () => {
    vi.mocked(api.tickets.notDeliveredAllowed).mockResolvedValue({ allowed: 100 });
    vi.mocked(api.tickets.markNotDelivered).mockResolvedValue({});
    const onDone = renderSheet();
    await waitFor(() => expect(screen.getByTestId('not-delivered-qty').value).toBe('100'));
    expect(screen.getByTestId('not-delivered-confirm').disabled).toBe(true); // no reason yet
    fireEvent.change(screen.getByTestId('not-delivered-reason'), { target: { value: 'โรงงานไม่ส่ง' } });
    fireEvent.change(screen.getByTestId('not-delivered-qty'), { target: { value: '150' } });
    expect(screen.getByTestId('not-delivered-blocked').textContent).toContain('ไม่เกิน 100');
    fireEvent.change(screen.getByTestId('not-delivered-qty'), { target: { value: '100' } });
    fireEvent.click(screen.getByTestId('not-delivered-confirm'));
    await waitFor(() => expect(api.tickets.markNotDelivered).toHaveBeenCalledWith(701, 5, { qty: 100, reason: 'โรงงานไม่ส่ง' }));
    await waitFor(() => expect(onDone).toHaveBeenCalled());
  });

  it('says why when the line cannot be marked (a round is still on its way)', async () => {
    vi.mocked(api.tickets.notDeliveredAllowed).mockRejectedValue(new Error('มีสินค้าของรายการนี้อยู่ในรอบขนส่งที่ยังดำเนินการอยู่'));
    renderSheet();
    await waitFor(() => expect(screen.getByTestId('not-delivered-blocked').textContent).toContain('ยังดำเนินการอยู่'));
    expect(screen.getByTestId('not-delivered-confirm').disabled).toBe(true);
  });
});
