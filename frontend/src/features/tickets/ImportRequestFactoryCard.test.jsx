import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ImportRequestFactoryCard } from './ImportRequestFactoryCard.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({ api: { storedImportRequests: {} } }));

const issued = {
  id: 41, ticketId: 701, factoryName: 'Equipe', status: 'ISSUED', version: 1, docNumber: 'IR-2026-0041',
  importStep: 'IN_TRANSIT', importStepAt: '2026-11-06T00:00:00Z', items: [], lines: [],
};

function renderCard(props) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ImportRequestFactoryCard
        ticketId={701} canFullWrite={false} canFooterWrite={false} canSetLeadTime={false} canEmailWrite={false}
        showToast={vi.fn()} {...props}
      />
    </QueryClientProvider>,
  );
}

// Fix round 1 (rulings 7 and 9): the card is shared with import's own ImportDealPage, which is not in the deal-page
// slice. Its wording there stays as it was, and the "ดูอย่างเดียว" chip is the deal page's opt-in, shown only on a
// card that offers this viewer nothing to edit.
describe('ImportRequestFactoryCard — what the deal page changed stays on the deal page', () => {
  it('the deal page: ดูอย่างเดียว on an issued card that offers this viewer no edit', () => {
    renderCard({ row: issued, showReadOnlyChip: true });
    expect(screen.getByText('ดูอย่างเดียว')).toBeTruthy();
  });

  it('an IR line on no deal line says so on a RECEIVED IR: import gets the link button, the rep only the notice', () => {
    const row = { ...issued, importStep: 'RECEIVED', items: [{ id: 5, code: 'LOOSE', size: '20x20', qty: 10, unit: 'แผ่น', ticketItemId: null }] };
    const { unmount } = renderCard({ row, canSetLeadTime: true });
    expect(screen.getByTestId('ir-link-5')).toBeTruthy();
    expect(screen.getByTestId('ir-close-out-5')).toBeTruthy();
    unmount();
    const closed = { ...row, items: [{ ...row.items[0], closedOutReason: 'ของงานอื่น' }] };
    const second = renderCard({ row: closed, canSetLeadTime: true });
    expect(screen.getByTestId('ir-closed-out-5').textContent).toContain('ของงานอื่น');
    expect(screen.getByTestId('ir-reopen-5')).toBeTruthy();
    second.unmount();
    renderCard({ row });
    expect(screen.queryByTestId('ir-link-5')).toBeNull();
    expect(screen.queryByTestId('ir-close-out-5')).toBeNull();
    expect(screen.getByTestId('ir-unlinked-5').textContent).toContain('ให้ฝ่ายนำเข้าหรือ CEO ผูกหรือปิดรายการให้');
  });

  it('part of the order in the warehouse: the card says ถึงโกดัง 70 / 140, not a step that reads as nothing arrived', () => {
    renderCard({ row: issued, arrival: { received: '70', ordered: '140', unit: 'แผ่น' } });
    expect(screen.getByText('ถึงโกดัง 70 / 140 แผ่น')).toBeTruthy();
    expect(screen.queryByText(/S12/)).toBeNull();
  });

  it('a short arrival at RECEIVED has no green tick and names the shortage, including 0 of 140', () => {
    renderCard({ row: { ...issued, importStep: 'RECEIVED' }, arrival: { received: '0', ordered: '140', unit: 'แผ่น', missing: '140' } });
    expect(screen.getByText('ถึงโกดัง 0 / 140 แผ่น · ขาด 140')).toBeTruthy();
    expect(screen.queryByText(/✓/)).toBeNull();
  });

  it('lines with no unit still show the figures (NULL and blank are one value): partial 100 / 196, rest still coming, no tick and no ขาด', () => {
    renderCard({ row: { ...issued, importStep: 'AWAITING_CUSTOMS' }, arrival: { received: '100', ordered: '196', unit: '', missing: null } });
    expect(screen.getByText('ถึงโกดัง 100 / 196')).toBeTruthy();
  });
});
