import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AttachmentList } from './AttachmentList.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    specialMoney: {
      attachments: vi.fn(),
      addAttachment: vi.fn(),
      attachmentDownloadUrl: vi.fn((id) => `/api/special-money/attachments/${id}`),
    },
  },
}));

// browser-image-compression genuinely returns a plain Blob with no `.name` -- this mock reproduces
// that faithfully rather than a File, which is exactly the shape that exposed the bug: a mock that
// quietly upgrades the library's real return type would make this test pass whether or not the
// component re-wraps it.
vi.mock('browser-image-compression', () => ({
  default: vi.fn((file) => Promise.resolve(new Blob([file], { type: file.type }))),
}));

function renderList(props = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AttachmentList requestId={1} canUpload {...props} />
    </QueryClientProvider>,
  );
}

// Found live: uploading a real welfare evidence photo through the real Java backend stored
// fileName "blob" instead of the photo's actual name. Root cause: imageCompression() returns a
// Blob, and FormData.append('file', blob) has no name to send, so the multipart part's filename
// defaults to the literal string "blob" per spec. Only JPG/PNG hit this -- PDFs skip compression
// entirely, which is why the bug read as image-only.
describe('AttachmentList upload', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.specialMoney.attachments.mockResolvedValue({ attachments: [] });
    api.specialMoney.addAttachment.mockResolvedValue({ attachment: { id: 99 } });
  });

  it('re-wraps the compressed image so the backend receives the original filename, not "blob"', async () => {
    renderList();

    const input = document.getElementById('smr-attachment-upload-1');
    const original = new File(['fake-jpeg-bytes'], 'receipt-medical.jpg', { type: 'image/jpeg' });
    fireEvent.change(input, { target: { files: [original] } });

    await waitFor(() => expect(api.specialMoney.addAttachment).toHaveBeenCalledTimes(1));
    const [, uploaded] = api.specialMoney.addAttachment.mock.calls[0];

    // The regression this guards: without the File re-wrap, `uploaded.name` is undefined (a bare
    // Blob has no `.name`), and FormData/fetch would send "blob" to the real backend -- exactly
    // what a live upload against the real Java service produced before this fix.
    expect(uploaded.name).toBe('receipt-medical.jpg');
    expect(uploaded).toBeInstanceOf(File);
    expect(uploaded.type).toBe('image/jpeg');
  });

  it('does not touch PDFs -- they skip compression and keep their name for a different reason', async () => {
    const imageCompression = (await import('browser-image-compression')).default;
    renderList();

    const input = document.getElementById('smr-attachment-upload-1');
    const pdf = new File(['fake-pdf-bytes'], 'receipt.pdf', { type: 'application/pdf' });
    fireEvent.change(input, { target: { files: [pdf] } });

    await waitFor(() => expect(api.specialMoney.addAttachment).toHaveBeenCalledTimes(1));
    const [, uploaded] = api.specialMoney.addAttachment.mock.calls[0];

    expect(imageCompression).not.toHaveBeenCalled();
    expect(uploaded.name).toBe('receipt.pdf');
  });

  it('rejects a file type the backend does not accept before ever calling compression or upload', async () => {
    const imageCompression = (await import('browser-image-compression')).default;
    const showToast = vi.fn();
    renderList({ showToast });

    const input = document.getElementById('smr-attachment-upload-1');
    const bad = new File(['x'], 'evidence.gif', { type: 'image/gif' });
    fireEvent.change(input, { target: { files: [bad] } });

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', expect.stringContaining('PDF, JPG หรือ PNG')));
    expect(imageCompression).not.toHaveBeenCalled();
    expect(api.specialMoney.addAttachment).not.toHaveBeenCalled();
  });

  it('renders attachment file names, sizes and a download link for a reviewer (no upload control)', async () => {
    api.specialMoney.attachments.mockResolvedValue({
      attachments: [{ id: 5, fileName: 'receipt-medical.jpg', sizeBytes: 204800 }],
    });
    renderList({ canUpload: false });

    expect(await screen.findByText('receipt-medical.jpg')).not.toBeNull();
    expect(screen.getByText('200 KB')).not.toBeNull();
    expect(document.getElementById('smr-attachment-upload-1')).toBeNull();

    const link = screen.getByText('receipt-medical.jpg').closest('a');
    expect(link.getAttribute('href')).toBe('/api/special-money/attachments/5');
    expect(link.getAttribute('download')).toBe('receipt-medical.jpg');
  });
});
