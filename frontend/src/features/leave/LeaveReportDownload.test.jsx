import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LeaveReportDownload } from './LeaveReportDownload.jsx';
import { api } from '../../api/index.js';
import { downloadBlob } from '../../utils/download.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    leave: {
      downloadMyReport: vi.fn(),
      downloadTeamReport: vi.fn(),
    },
  },
}));

vi.mock('../../utils/download.js', () => ({
  downloadBlob: vi.fn(),
}));

function renderDownload(scope, showToast = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <LeaveReportDownload scope={scope} showToast={showToast} />
    </QueryClientProvider>,
  );

  return { showToast };
}

function ownButton() {
  return screen.getByRole('button', { name: /ดาวน์โหลดรายงานของฉัน/ });
}

function teamButton() {
  return screen.getByRole('button', { name: /ดาวน์โหลดรายงานทีม/ });
}

describe('LeaveReportDownload', () => {
  beforeEach(() => {
    api.leave.downloadMyReport.mockReset();
    api.leave.downloadTeamReport.mockReset();
    downloadBlob.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('scope routing', () => {
    it('scope="own" calls downloadMyReport and never downloadTeamReport', async () => {
      api.leave.downloadMyReport.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }));
      renderDownload('own');

      fireEvent.click(ownButton());

      await waitFor(() => expect(api.leave.downloadMyReport).toHaveBeenCalledTimes(1));
      expect(api.leave.downloadTeamReport).not.toHaveBeenCalled();
    });

    it('scope="team" calls downloadTeamReport and never downloadMyReport', async () => {
      api.leave.downloadTeamReport.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }));
      renderDownload('team');

      fireEvent.click(teamButton());

      await waitFor(() => expect(api.leave.downloadTeamReport).toHaveBeenCalledTimes(1));
      expect(api.leave.downloadMyReport).not.toHaveBeenCalled();
    });
  });
});
