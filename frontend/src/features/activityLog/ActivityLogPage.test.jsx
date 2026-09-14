import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivityLogPage, TABS } from './ActivityLogPage.jsx';
import { api } from '../../api/index.js';

// Tab id -> the api.activityLog method it drives (ActivityLogPage.jsx's QUERIES map). Kept
// separate from TABS itself so this table still catches TABS drifting out of sync with QUERIES,
// not just TABS drifting out of sync with Tabs.jsx's `id` contract.
const METHOD_BY_TAB_ID = { summary: 'summary', actions: 'audit', requests: 'list', system: 'events' };

globalThis.React = React;

// This page has no e2e coverage on purpose (seeding an admin persona would grant real prod
// access — see route-coverage.spec.js's EXCLUDED_ROUTE_PATTERNS) and mockApi.js deliberately
// throws "not supported in mock mode" for every activityLog.* method, so this component test is
// the ONLY thing standing between a Tabs.jsx contract change (or a typo like this one) and a
// crash nobody notices until the one admin who can reach this page clicks a tab.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      ...actual.api,
      activityLog: {
        summary: vi.fn().mockResolvedValue([]),
        audit: vi.fn().mockResolvedValue([]),
        list: vi.fn().mockResolvedValue([]),
        events: vi.fn().mockResolvedValue([]),
      },
    },
  };
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ActivityLogPage />
    </QueryClientProvider>,
  );
}

describe('ActivityLogPage', () => {
  beforeEach(() => {
    // Each vi.fn() lives in the module-level vi.mock factory, so call counts survive across
    // tests unless cleared — without this, a later test's waitFor(toHaveBeenCalled) can pass on a
    // call left over from an earlier test rather than the click this test just fired.
    vi.clearAllMocks();
    for (const fn of Object.values(api.activityLog)) fn.mockResolvedValue([]);
  });

  it('renders the default summary tab without crashing', async () => {
    renderPage();
    await waitFor(() => expect(api.activityLog.summary).toHaveBeenCalled());
    // getAttribute, not jest-dom's toHaveAttribute — this project does not wire up jest-dom.
    expect(screen.getByRole('tab', { name: /สรุป/ }).getAttribute('aria-selected')).toBe('true');
  });

  // Regression test for the Tabs.jsx contract mismatch: TABS items must carry `id` (what
  // Tabs.jsx's onClick/onChange/selected-state logic reads), not `value` — see git history for
  // the incident this pins. Driven off the real TABS array (not a hardcoded label list) so a
  // future tab added the same wrong way is caught automatically, not just today's four.
  it.each(TABS.filter((t) => t.id !== 'summary'))('switches to the $label tab without throwing', async (tabItem) => {
    renderPage();
    await waitFor(() => expect(api.activityLog.summary).toHaveBeenCalled());

    const tab = screen.getByRole('tab', { name: new RegExp(tabItem.label) });
    fireEvent.click(tab);

    expect(tab.getAttribute('aria-selected')).toBe('true');
    const method = METHOD_BY_TAB_ID[tabItem.id];
    await waitFor(() => expect(api.activityLog[method]).toHaveBeenCalled());
  });
});
