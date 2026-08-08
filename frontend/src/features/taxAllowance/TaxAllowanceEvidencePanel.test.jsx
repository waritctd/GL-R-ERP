import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaxAllowanceEvidencePanel } from './TaxAllowanceEvidencePanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// `browser-image-compression` genuinely returns a plain **Blob**, not a File -- it does not carry
// `file.name` through. Reproduced faithfully here (a Blob, deliberately NOT a File) because that is
// the exact shape that exposed the bug this file's compression test guards: an unnamed Blob appended
// to FormData defaults its multipart filename to the literal string "blob" per spec, so every
// compressed JPG/PNG landed in the backend -- and in every list and download UI thereafter -- named
// "blob" instead of e.g. "receipt-medical.jpg". Found live against the real backend, and fixed
// identically in features/specialmoney/AttachmentList.jsx (#498) and here (#504).
//
// A `vi.fn()` returning a File would make the test pass with the fix REMOVED -- the mock must be the
// unhelpful shape the real library actually produces, or it proves nothing.
vi.mock('browser-image-compression', () => ({
  default: vi.fn((file) => Promise.resolve(new Blob([file], { type: file.type }))),
}));

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

  // The regression this file previously had no guard for. The PDF case above cannot catch it:
  // `prepareFile` returns non-images untouched, so a PDF never reaches the compression branch at
  // all. Only an image/* file does -- and that is the branch where the name used to be lost.
  it('an image keeps its real filename through compression, rather than becoming "blob"', async () => {
    const onStageFile = vi.fn();
    renderPanel({ mode: 'staging', declarationId: null, sectionKey: 'family', onStageFile });

    const file = new File(['x'], 'receipt-medical.jpg', { type: 'image/jpeg' });
    const input = document.querySelector('input[type="file"]');
    fireEvent.change(input, { target: { files: [file] } });

    await waitFor(() => expect(onStageFile).toHaveBeenCalledTimes(1));
    const staged = onStageFile.mock.calls[0][0];
    // Asserting the NAME is the point -- `instanceof File` alone would pass on any re-wrap, and a
    // bare truthiness check would pass on the literal "blob" the bug produced.
    expect(staged.name).toBe('receipt-medical.jpg');
    expect(staged.type).toBe('image/jpeg');
  });

  it('an image uploaded directly (not staged) also keeps its filename', async () => {
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({ items: [] });
    api.payroll.uploadTaxAllowanceAttachment.mockResolvedValue({});
    renderPanel({ mode: 'direct', declarationId: 55, sectionKey: 'family' });

    const file = new File(['x'], 'insurance-photo.png', { type: 'image/png' });
    const input = document.querySelector('input[type="file"]');
    fireEvent.change(input, { target: { files: [file] } });

    await waitFor(() => expect(api.payroll.uploadTaxAllowanceAttachment).toHaveBeenCalledTimes(1));
    // The real upload path is the one that reaches FormData, where an unnamed Blob becomes "blob".
    expect(api.payroll.uploadTaxAllowanceAttachment.mock.calls[0][1].name).toBe('insurance-photo.png');
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
