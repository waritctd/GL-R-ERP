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
});
