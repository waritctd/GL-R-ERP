import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivityLogPage, TABS, formatDateTime, formatTime } from './ActivityLogPage.jsx';
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

// formatTime backs summary's firstSeen/lastSeen columns, formatDateTime backs every other tab's
// `at` column. Unit-tested directly (rather than only through a rendered tab) so this coverage
// does not depend on tab-switching working.
describe('formatTime / formatDateTime', () => {
  // Regression coverage for the invalid-date crash: these used to only guard `!value`, so a
  // non-empty value that doesn't parse to a real date left `new Date(value)` an Invalid Date, and
  // Intl.DateTimeFormat#format threw a RangeError. Because that throw happens DURING RENDER inside
  // a DataTable column (not inside a promise), react-query's isError never sees it — it propagates
  // to the route-level ErrorBoundary and crashes the ENTIRE page, the same failure class as the
  // Tabs.jsx id/value bug fixed on fix/activity-log-page-load-failure.
  it.each([
    ['a non-empty string that is not a date', 'not-a-real-date'],
    ['a malformed ISO-ish string', '2026-13-45T99:99:99'],
    ['an empty object (what a misconfigured ObjectMapper could emit for OffsetDateTime)', {}],
  ])('formatTime returns "-" instead of throwing for %s', (_label, value) => {
    expect(() => formatTime(value)).not.toThrow();
    expect(formatTime(value)).toBe('-');
  });

  it.each([
    ['a non-empty string that is not a date', 'not-a-real-date'],
    ['a malformed ISO-ish string', '2026-13-45T99:99:99'],
  ])('formatDateTime returns "-" instead of throwing for %s', (_label, value) => {
    expect(() => formatDateTime(value)).not.toThrow();
    expect(formatDateTime(value)).toBe('-');
  });

  it('still formats a genuinely valid date (guard does not over-fire)', () => {
    expect(formatTime('2026-09-15T09:30:00+07:00')).not.toBe('-');
    expect(formatDateTime('2026-09-15T09:30:00+07:00')).not.toBe('-');
  });

  it('still returns "-" for null/undefined/empty string, as before', () => {
    expect(formatTime(null)).toBe('-');
    expect(formatTime(undefined)).toBe('-');
    expect(formatTime('')).toBe('-');
    expect(formatDateTime(null)).toBe('-');
  });
});

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

  it('renders a summary row with valid data without crashing', async () => {
    api.activityLog.summary.mockResolvedValue([
      { employeeId: 1, name: 'ทดสอบ', employeeCode: 'EMP-1', requestCount: 3, firstSeen: '2026-09-15T09:00:00+07:00', lastSeen: '2026-09-15T10:00:00+07:00' },
    ]);
    renderPage();
    await waitFor(() => expect(screen.getByText('ทดสอบ (EMP-1)')).toBeTruthy());
  });

  // End-to-end proof that a malformed row from the real endpoint no longer crashes the page — the
  // summary tab is the default tab, reachable without depending on the tab-switching test above.
  it('renders "-" instead of crashing the page when a summary row has an unparseable firstSeen', async () => {
    api.activityLog.summary.mockResolvedValue([
      { employeeId: 1, name: 'ทดสอบ', employeeCode: 'EMP-1', requestCount: 1, firstSeen: 'not-a-real-date', lastSeen: null },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('ทดสอบ (EMP-1)')).toBeTruthy());
    // The malformed firstSeen and the null lastSeen both fall back to '-' — two cells, proving the
    // page rendered past both instead of throwing on the first one.
    expect(screen.getAllByText('-')).toHaveLength(2);
  });
});
