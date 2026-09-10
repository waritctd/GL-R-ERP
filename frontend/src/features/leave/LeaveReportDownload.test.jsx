import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LeaveReportDownload } from './LeaveReportDownload.jsx';
import { api } from '../../api/index.js';
import { downloadBlob } from '../../utils/download.js';
import { yearFrom } from './leaveFormatting.js';

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

// Computed the same way the component computes it (leaveFormatting.js#yearFrom with no
// argument -- today's date in Asia/Bangkok, CE), rather than hardcoded, so this test stays
// correct across a year boundary instead of quietly going stale on 2027-01-01.
const currentYear = yearFrom();

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

  describe('month selection', () => {
    it('defaults to ทั้งปี and sends month: undefined for a whole-year report', async () => {
      api.leave.downloadMyReport.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }));
      renderDownload('own');

      const monthSelect = screen.getByLabelText('เดือน');
      expect(monthSelect.value).toBe('');

      fireEvent.click(ownButton());

      await waitFor(() => expect(api.leave.downloadMyReport).toHaveBeenCalledTimes(1));
      const params = api.leave.downloadMyReport.mock.calls[0][0];
      // Strict: must be undefined, not the falsy-but-wrong 0 or '' a naive Number(month)
      // or a missing `? :` fallback could produce.
      expect(params.month).toBeUndefined();
      expect(params.year).toBe(currentYear);
    });

    it('selecting a month sends that month as a number', async () => {
      api.leave.downloadMyReport.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }));
      renderDownload('own');

      fireEvent.change(screen.getByLabelText('เดือน'), { target: { value: '3' } });
      fireEvent.click(ownButton());

      await waitFor(() => expect(api.leave.downloadMyReport).toHaveBeenCalledTimes(1));
      const params = api.leave.downloadMyReport.mock.calls[0][0];
      expect(params.month).toBe(3);
    });
  });

  describe('year select — Buddhist era label, Christian era value', () => {
    it('labels the current year in Buddhist era while sending the Christian-era value to the API', async () => {
      api.leave.downloadMyReport.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }));
      renderDownload('own');

      const yearSelect = screen.getByLabelText('ปี');
      expect(yearSelect.value).toBe(String(currentYear));

      const selectedOption = yearSelect.querySelector(`option[value="${currentYear}"]`);
      // The trap: the label reads e.g. 2569 (currentYear + 543) ...
      expect(selectedOption.textContent).toBe(String(currentYear + 543));

      fireEvent.click(ownButton());

      await waitFor(() => expect(api.leave.downloadMyReport).toHaveBeenCalledTimes(1));
      const params = api.leave.downloadMyReport.mock.calls[0][0];
      // ... but the API receives the plain Christian-era year, not the displayed +543 label.
      expect(params.year).toBe(currentYear);
      expect(params.year).not.toBe(currentYear + 543);
    });

    it('sends the selected (non-default) year as its Christian-era value', async () => {
      api.leave.downloadMyReport.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }));
      renderDownload('own');

      const previousYear = currentYear - 1;
      fireEvent.change(screen.getByLabelText('ปี'), { target: { value: String(previousYear) } });
      fireEvent.click(ownButton());

      await waitFor(() => expect(api.leave.downloadMyReport).toHaveBeenCalledTimes(1));
      const params = api.leave.downloadMyReport.mock.calls[0][0];
      expect(params.year).toBe(previousYear);
    });
  });

  describe('failure and success feedback', () => {
    it('surfaces the error toast on a failed download instead of a silent no-op', async () => {
      api.leave.downloadMyReport.mockRejectedValue(new Error('เครือข่ายขัดข้อง'));
      const { showToast } = renderDownload('own');

      fireEvent.click(ownButton());

      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'เครือข่ายขัดข้อง'));
      expect(downloadBlob).not.toHaveBeenCalled();
    });

    it('falls back to a generic Thai error message when the rejection carries no message', async () => {
      api.leave.downloadMyReport.mockRejectedValue(new Error());
      const { showToast } = renderDownload('own');

      fireEvent.click(ownButton());

      await waitFor(() =>
        expect(showToast).toHaveBeenCalledWith('error', 'ดาวน์โหลดรายงานใบลาไม่สำเร็จ'),
      );
    });

    it('on success, downloads the blob and shows the success toast', async () => {
      const blob = new Blob(['pdf'], { type: 'application/pdf' });
      api.leave.downloadMyReport.mockResolvedValue(blob);
      const { showToast } = renderDownload('own');

      fireEvent.click(ownButton());

      await waitFor(() => expect(downloadBlob).toHaveBeenCalledTimes(1));
      expect(downloadBlob).toHaveBeenCalledWith(blob, `glr-leave-report-${currentYear}`, 'pdf');
      expect(showToast).toHaveBeenCalledWith('success', 'ดาวน์โหลดรายงานใบลาแล้ว');
    });

    it('team-scope success uses the team filename base', async () => {
      const blob = new Blob(['pdf'], { type: 'application/pdf' });
      api.leave.downloadTeamReport.mockResolvedValue(blob);
      const { showToast } = renderDownload('team');

      fireEvent.click(teamButton());

      await waitFor(() => expect(downloadBlob).toHaveBeenCalledTimes(1));
      expect(downloadBlob).toHaveBeenCalledWith(blob, `glr-leave-report-team-${currentYear}`, 'pdf');
      expect(showToast).toHaveBeenCalledWith('success', 'ดาวน์โหลดรายงานใบลาแล้ว');
    });
  });
});
