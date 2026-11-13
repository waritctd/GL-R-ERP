import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TeamMovementPanel } from './TeamMovementPanel.jsx';
import { api } from '../../api/index.js';
import { formatThaiDate } from '../../utils/format.js';

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

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <TeamMovementPanel deals={DEALS} range={RANGE} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const linkTo = (row, href) => [...row.querySelectorAll('a')].find((a) => a.getAttribute('href') === href);

describe('TeamMovementPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the range it was given and carries it explicitly in every count link', async () => {
    api.meta.dealStages.mockImplementation(() => new Promise(() => {}));
    renderPanel();
    const panel = screen.getByRole('heading', { name: 'ความเคลื่อนไหวของทีม' }).closest('section');
    expect(panel.textContent).toContain(formatThaiDate(RANGE.from));
    expect(panel.textContent).toContain(formatThaiDate(RANGE.to));

    const row = within(panel).getByTestId('team-row-11');
    const range = 'from=2025-03-01&to=2025-03-07';
    for (const week of ['moved', 'activity', 'overdue', 'silent']) {
      expect(linkTo(row, `/tickets?owner=11&week=${week}&${range}`), week).toBeDefined();
    }
    // the open-deals count is a plain number, not a link
    expect([...row.querySelectorAll('a')].every((a) => a.getAttribute('href').includes('week='))).toBe(true);
  });

  it('shows — in both value columns until the stage catalog has loaded, then the sums', async () => {
    let resolve;
    api.meta.dealStages.mockImplementation(() => new Promise((r) => { resolve = r; }));
    renderPanel();
    const row = screen.getByTestId('team-row-11');
    const cells = () => [...row.querySelectorAll('td')].map((td) => td.textContent.trim());
    expect(cells().slice(-2)).toEqual(['—', '—']);

    const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
    resolve(DEAL_STAGE_CATALOG);
    await waitFor(() => expect(cells().slice(-2)).toEqual(['฿4,000.00', '฿250.00']));
  });
});
