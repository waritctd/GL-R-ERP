import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TeamMovementPanel } from './TeamMovementPanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, api: { meta: { dealStages: vi.fn() } } };
});

// Deliberately NOT today's default range: the panel must use the range its parent fetched with.
const RANGE = { from: '2025-03-01', to: '2025-03-07' };
const deal = (id, extra) => ({
  id, createdById: 11, createdByName: 'เซลส์ เอ', lifecycle: 'ACTIVE', salesStage: 'NEGOTIATION', amountPayable: 100,
  stageChangedInRange: false, activityInRange: false, nextFollowUpAt: null, ...extra,
});
const DEALS = [
  deal(1, { stageChangedInRange: true, salesStage: 'ORDER_RECEIVED', amountPayable: 4000 }),
  deal(2, { amountPayable: 250 }),
];

function renderPanel(props = { deals: DEALS, orders: DEALS }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <TeamMovementPanel {...props} range={RANGE} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TeamMovementPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('classes money per ORDER: a project whose root is ordered and whose add-on is still quoting', async () => {
    const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
    api.meta.dealStages.mockResolvedValue(DEAL_STAGE_CATALOG);
    // The folded row takes the ROOT's (ordered) stage but sums both orders' value (4000 + 250); the per-order rows split it.
    const folded = [deal(1, { salesStage: 'ORDER_RECEIVED', amountPayable: 4250 })];
    const orders = [
      deal(1, { salesStage: 'ORDER_RECEIVED', amountPayable: 4000 }),
      deal(2, { salesStage: 'QUOTE_BUYER', amountPayable: 250 }),
    ];
    renderPanel({ deals: folded, orders });
    const row = screen.getByTestId('team-row-11');
    await waitFor(() => expect([...row.querySelectorAll('td')].slice(-2).map((td) => td.textContent.trim()))
      .toEqual(['฿4,000.00', '฿250.00']));
    expect(row.querySelectorAll('td')[0].textContent).toBe('1'); // one open project
  });

  it('attributes an add-on moved to another rep to ITS owner: that owner gets a row and the money is not dropped', async () => {
    const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
    api.meta.dealStages.mockResolvedValue(DEAL_STAGE_CATALOG);
    // Folded: the project is one row owned by A (the lead order is the root). Orders: R-2 belongs to B, 50,000, quoting.
    const folded = [deal(1, { salesStage: 'ORDER_RECEIVED', amountPayable: 54000 })];
    const orders = [
      deal(1, { salesStage: 'ORDER_RECEIVED', amountPayable: 4000 }),
      deal(2, { createdById: 12, createdByName: 'เซลส์ บี', salesStage: 'QUOTE_BUYER', amountPayable: 50000 }),
    ];
    renderPanel({ deals: folded, orders });
    const rowB = await screen.findByTestId('team-row-12');
    await waitFor(() => expect([...rowB.querySelectorAll('td')].slice(-2).map((td) => td.textContent.trim()))
      .toEqual(['฿0.00', '฿50,000.00']));
    const cells = (id) => [...screen.getByTestId(`team-row-${id}`).querySelectorAll('td')].slice(-2)
      .reduce((sum, td) => sum + Number(td.textContent.replace(/[^\d.]/g, '')), 0);
    expect(cells(11) + cells(12)).toBe(54000);
  });
});
