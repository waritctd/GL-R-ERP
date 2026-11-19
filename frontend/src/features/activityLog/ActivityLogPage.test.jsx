import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivityLogPage } from './ActivityLogPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Mock activity-log APIs here because the normal mock API does not support this admin-only page.
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
    // Each vi.fn() lives in the module-level vi.mock factory, so call counts survive across tests unless cleared —
    // without this, a later test's waitFor(toHaveBeenCalled) can pass on a call left over from an earlier test
    // rather than the click this test just fired.
    vi.clearAllMocks();
    for (const fn of Object.values(api.activityLog)) fn.mockResolvedValue([]);
  });

  // Date fields commit on blur: a partly typed date reverts and never reaches the query; a
  // complete one does.
  it('a partly typed from-date does not refetch; a complete one does', async () => {
    renderPage();
    await waitFor(() => expect(api.activityLog.summary).toHaveBeenCalledTimes(1));
    const initial = api.activityLog.summary.mock.calls[0][0];

    fireEvent.change(screen.getByLabelText(/ตั้งแต่วันที่/), { target: { value: '01/0' } });
    fireEvent.blur(screen.getByLabelText(/ตั้งแต่วันที่/));
    await new Promise((resolve) => { setTimeout(resolve, 50); });
    for (const [range] of api.activityLog.summary.mock.calls) {
      expect(range.from).toBeTruthy();
      expect(range.to).toBeTruthy();
    }

    fireEvent.change(screen.getByLabelText(/ตั้งแต่วันที่/), { target: { value: '01/01/2569' } });
    fireEvent.blur(screen.getByLabelText(/ตั้งแต่วันที่/));
    await waitFor(() => expect(api.activityLog.summary).toHaveBeenLastCalledWith({ from: '2026-01-01', to: initial.to }));
  });

  // End-to-end proof that a malformed row from the real endpoint no longer crashes the page — the summary tab is
  // the default tab, so no tab switching is needed to reach it.
  it('renders "-" instead of crashing the page when a summary row has an unparseable firstSeen', async () => {
    api.activityLog.summary.mockResolvedValue([
      { employeeId: 1, name: 'ทดสอบ', employeeCode: 'EMP-1', requestCount: 1, firstSeen: 'not-a-real-date', lastSeen: null },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('ทดสอบ (EMP-1)')).toBeTruthy());
    // The malformed firstSeen and the null lastSeen both fall back to '-' — two cells, proving the page rendered
    // past both instead of throwing on the first one.
    expect(screen.getAllByText('-')).toHaveLength(2);
  });
});
