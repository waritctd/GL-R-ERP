import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaxAllowanceEvidencePanel } from './TaxAllowanceEvidencePanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      payroll: {
        listTaxAllowanceAttachments: vi.fn(),
        uploadTaxAllowanceAttachment: vi.fn(),
        deleteTaxAllowanceAttachment: vi.fn(),
        downloadTaxAllowanceAttachment: vi.fn(),
      },
    },
  };
});

function attachment(overrides = {}) {
  return {
    attachmentId: 1,
    fileName: 'evidence.pdf',
    fileSize: 1024,
    deletedAt: null,
    sectionKey: null,
    ...overrides,
  };
}

function renderPanel(props = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <TaxAllowanceEvidencePanel showToast={vi.fn()} {...props} />
    </QueryClientProvider>,
  );
}

describe('TaxAllowanceEvidencePanel — section filtering (V135)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('filters the displayed list to sectionKey and does not show a different section\'s attachment', async () => {
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({
      items: [
        attachment({ attachmentId: 1, fileName: 'insurance-cert.pdf', sectionKey: 'insurance' }),
        attachment({ attachmentId: 2, fileName: 'family-cert.pdf', sectionKey: 'family' }),
      ],
    });

    renderPanel({ mode: 'readonly', declarationId: 55, sectionKey: 'insurance' });

    expect(await screen.findByText('insurance-cert.pdf')).not.toBeNull();
    expect(screen.queryByText('family-cert.pdf')).toBeNull();
  });

  it('shows a null-sectionKey (pre-migration) attachment under the general bucket when showUncategorized is set, never dropping it', async () => {
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({
      items: [
        attachment({ attachmentId: 3, fileName: 'legacy-no-section.pdf', sectionKey: null }),
        attachment({ attachmentId: 4, fileName: 'family-cert.pdf', sectionKey: 'family' }),
      ],
    });

    renderPanel({ mode: 'readonly', declarationId: 55, sectionKey: null, showUncategorized: true });

    expect(await screen.findByText('legacy-no-section.pdf')).not.toBeNull();
    // The general bucket does not also show a properly-tagged section's file.
    expect(screen.queryByText('family-cert.pdf')).toBeNull();
  });

  it('a null-sectionKey attachment is NOT shown under an unrelated section when showUncategorized is off', async () => {
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({
      items: [attachment({ attachmentId: 5, fileName: 'legacy-no-section.pdf', sectionKey: null })],
    });

    renderPanel({ mode: 'readonly', declarationId: 55, sectionKey: 'family', showUncategorized: false });

    await waitFor(() => expect(api.payroll.listTaxAllowanceAttachments).toHaveBeenCalled());
    expect(screen.queryByText('legacy-no-section.pdf')).toBeNull();
  });
});

describe('TaxAllowanceEvidencePanel — mode: direct (an existing, directly-editable declaration)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({ items: [] });
  });

  it('uploads straight to the server, tagged with the current sectionKey', async () => {
    api.payroll.uploadTaxAllowanceAttachment.mockResolvedValue({ attachment: attachment({ sectionKey: 'family' }) });
    renderPanel({ mode: 'direct', declarationId: 55, sectionKey: 'family' });

    const file = new File(['x'], 'cert.pdf', { type: 'application/pdf' });
    const input = document.querySelector('input[type="file"]');
    fireEvent.change(input, { target: { files: [file] } });

    await waitFor(() => expect(api.payroll.uploadTaxAllowanceAttachment).toHaveBeenCalledWith(55, file, 'family'));
  });
});

describe('TaxAllowanceEvidencePanel — mode: staging (filling in a not-yet-submitted declaration)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('a chosen PDF is staged via onStageFile rather than uploaded immediately -- the fix for "cannot attach while first filling in"', async () => {
    const onStageFile = vi.fn();
    // No declarationId at all -- a brand-new declaration, exactly the case the reported complaint
    // was about ("I couldn't attach a PDF while first filling in the form").
    renderPanel({ mode: 'staging', declarationId: null, sectionKey: 'family', onStageFile });

    const file = new File(['x'], 'cert.pdf', { type: 'application/pdf' });
    const input = document.querySelector('input[type="file"]');
    fireEvent.change(input, { target: { files: [file] } });

    await waitFor(() => expect(onStageFile).toHaveBeenCalledTimes(1));
    expect(onStageFile.mock.calls[0][0].name).toBe('cert.pdf');
    // Never hit the server directly while staging.
    expect(api.payroll.uploadTaxAllowanceAttachment).not.toHaveBeenCalled();
  });

  it('renders staged files with a "รอส่ง" (pending) marker and lets them be removed before submit', () => {
    const onUnstageFile = vi.fn();
    renderPanel({
      mode: 'staging',
      declarationId: null,
      sectionKey: 'family',
      staged: [{ tempId: 'staged-1', fileName: 'cert.pdf', fileSize: 2048 }],
      onUnstageFile,
    });

    expect(screen.getByText('cert.pdf')).not.toBeNull();
    expect(screen.getByText('รอส่ง')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'ลบไฟล์ cert.pdf' }));
    expect(onUnstageFile).toHaveBeenCalledWith('staged-1');
  });
});

describe('TaxAllowanceEvidencePanel — mode: readonly', () => {
  it('shows no upload control and no delete button', async () => {
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({
      items: [attachment({ attachmentId: 9, fileName: 'approved-cert.pdf', sectionKey: 'family' })],
    });
    renderPanel({ mode: 'readonly', declarationId: 55, sectionKey: 'family' });

    expect(await screen.findByText('approved-cert.pdf')).not.toBeNull();
    expect(document.querySelector('input[type="file"]')).toBeNull();
    expect(screen.queryByRole('button', { name: /ลบไฟล์/ })).toBeNull();
  });
});
