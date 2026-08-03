import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaxAllowanceEvidencePanel } from './TaxAllowanceEvidencePanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    payroll: {
      listTaxAllowanceAttachments: vi.fn(),
      uploadTaxAllowanceAttachment: vi.fn(),
      downloadTaxAllowanceAttachment: vi.fn(),
      deleteTaxAllowanceAttachment: vi.fn(),
    },
  },
}));

// browser-image-compression genuinely returns a plain Blob with no `.name` -- reproduced faithfully
// here rather than a File, which is exactly the shape that exposed the bug this test guards
// against: see features/specialmoney/AttachmentList.jsx / AttachmentList.test.jsx, where the
// identical defect was found live (a real evidence photo stored as fileName "blob" against the
// real Java backend) and this same mock shape is what caught it in a unit test.
vi.mock('browser-image-compression', () => ({
  default: vi.fn((file) => Promise.resolve(new Blob([file], { type: file.type }))),
}));

function renderPanel(props = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <TaxAllowanceEvidencePanel declarationId={1} canEdit {...props} />
    </QueryClientProvider>,
  );
}

// This file's own docblock says its upload flow was "lifted verbatim from LeavePage.jsx" and that
// AttachmentList.jsx's upload flow was in turn modelled on this file -- the Blob-loses-its-name bug
// found live in AttachmentList.jsx (PR #498) was flagged there as present here too, unfixed, "out
// of welfare scope". This is that follow-up.
describe('TaxAllowanceEvidencePanel upload', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({ items: [] });
    api.payroll.uploadTaxAllowanceAttachment.mockResolvedValue({ attachment: { attachmentId: 99 } });
  });

  it('re-wraps the compressed image so the backend receives the original filename, not "blob"', async () => {
    renderPanel();

    const input = document.getElementById('tax-allowance-evidence-file');
    const original = new File(['fake-jpeg-bytes'], 'life-insurance-policy.jpg', { type: 'image/jpeg' });
    fireEvent.change(input, { target: { files: [original] } });

    await waitFor(() => expect(api.payroll.uploadTaxAllowanceAttachment).toHaveBeenCalledTimes(1));
    const [, uploaded] = api.payroll.uploadTaxAllowanceAttachment.mock.calls[0];

    // The regression this guards: without the File re-wrap, `uploaded.name` is undefined (a bare
    // Blob has no `.name`), and `formData.append('file', blob)` would send the literal string
    // "blob" to the real backend -- exactly what a live upload against the real Java service
    // produced before AttachmentList.jsx's fix.
    expect(uploaded.name).toBe('life-insurance-policy.jpg');
    expect(uploaded).toBeInstanceOf(File);
    expect(uploaded.type).toBe('image/jpeg');
  });

  it('does not touch PDFs -- they skip compression and keep their name for a different reason', async () => {
    const imageCompression = (await import('browser-image-compression')).default;
    renderPanel();

    const input = document.getElementById('tax-allowance-evidence-file');
    const pdf = new File(['fake-pdf-bytes'], 'marriage-certificate.pdf', { type: 'application/pdf' });
    fireEvent.change(input, { target: { files: [pdf] } });

    await waitFor(() => expect(api.payroll.uploadTaxAllowanceAttachment).toHaveBeenCalledTimes(1));
    const [, uploaded] = api.payroll.uploadTaxAllowanceAttachment.mock.calls[0];

    expect(imageCompression).not.toHaveBeenCalled();
    expect(uploaded.name).toBe('marriage-certificate.pdf');
  });

  it('rejects a file type the backend does not accept before ever calling compression or upload', async () => {
    const imageCompression = (await import('browser-image-compression')).default;
    const showToast = vi.fn();
    renderPanel({ showToast });

    const input = document.getElementById('tax-allowance-evidence-file');
    const bad = new File(['x'], 'evidence.gif', { type: 'image/gif' });
    fireEvent.change(input, { target: { files: [bad] } });

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', expect.stringContaining('PDF, JPG หรือ PNG')));
    expect(imageCompression).not.toHaveBeenCalled();
    expect(api.payroll.uploadTaxAllowanceAttachment).not.toHaveBeenCalled();
  });

  it('renders attachment file names and sizes for a read-only viewer (no upload control)', async () => {
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({
      items: [{ attachmentId: 5, fileName: 'life-insurance-policy.jpg', fileSize: 204800 }],
    });
    renderPanel({ canEdit: false });

    expect(await screen.findByText('life-insurance-policy.jpg')).not.toBeNull();
    expect(screen.getByText('200 KB')).not.toBeNull();
    expect(document.getElementById('tax-allowance-evidence-file')).toBeNull();
  });
});
