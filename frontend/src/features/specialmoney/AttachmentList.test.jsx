import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, waitFor } from '@testing-library/react';
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

// browser-image-compression genuinely returns a plain Blob with no `.name`.
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

// Found live: uploading a real welfare evidence photo through the real Java backend stored fileName "blob"
// instead of the photo's actual name. Only JPG/PNG hit this -- PDFs skip compression entirely, which is why
// the bug read as image-only.
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

    // The regression this guards: without the File re-wrap, `uploaded.name` is undefined (a bare Blob has no
    // `.name`), and FormData/fetch would send "blob" to the real backend -- exactly what a live upload against the
    // real Java service produced before this fix.
    expect(uploaded.name).toBe('receipt-medical.jpg');
    expect(uploaded).toBeInstanceOf(File);
    expect(uploaded.type).toBe('image/jpeg');
  });
});
