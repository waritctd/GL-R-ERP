import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DocumentDownloadSheet } from './DocumentDownloadSheet.jsx';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      ...actual.api,
      attachments: { list: vi.fn(), upload: vi.fn() },
      depositNotices: { bundleSelection: vi.fn(), saveBundleSelection: vi.fn() },
      storedRemainingInvoices: { bundleSelection: vi.fn(), saveBundleSelection: vi.fn() },
    },
  };
});

// newest first, as the list endpoint serves them
const FILES = [
  { id: 9, fileName: 'extra.pdf', attachType: 'OTHER' },
  { id: 7, fileName: 'po-2.pdf', attachType: 'PO' },
  { id: 5, fileName: 'po-1.pdf', attachType: 'PO' },
];

function renderSheet(client = new QueryClient({ defaultOptions: { queries: { retry: false } } }), props = {}) {
  const onDownload = vi.fn().mockResolvedValue(undefined);
  const onClose = vi.fn();
  render(
    <QueryClientProvider client={client}>
      <DocumentDownloadSheet kind="deposit" documentId={12} ticketId={701} reference="QT-1"
        onClose={onClose} onDownload={onDownload} {...props} />
    </QueryClientProvider>,
  );
  return { onDownload, onClose };
}

describe('DocumentDownloadSheet', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.attachments.list.mockResolvedValue({ attachments: FILES });
    api.depositNotices.bundleSelection.mockResolvedValue({ quotation: true, attachmentIds: [5, 7] });
    api.depositNotices.saveBundleSelection.mockResolvedValue({ ok: true });
  });

  it('shows the POs ticked (oldest first) even over the deal page\'s array-shaped cache, then saves and downloads', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(queryKeys.ticketAttachments(701), FILES); // what TicketDetailPage caches: a plain array
    const { onDownload, onClose } = renderSheet(client);

    const confirm = await screen.findByTestId('document-download-confirm');
    await waitFor(() => expect(screen.getByTestId('include-attachment-5').checked).toBe(true));
    expect(screen.getByTestId('include-attachment-7').checked).toBe(true);
    expect(screen.getByTestId('include-attachment-9').checked).toBe(false);
    expect(screen.getByTestId('include-quotation').checked).toBe(true);
    const order = screen.getAllByTestId(/include-attachment-/).map((el) => el.dataset.testid);
    expect(order).toEqual(['include-attachment-5', 'include-attachment-7', 'include-attachment-9']);

    fireEvent.click(screen.getByTestId('include-attachment-9'));
    fireEvent.click(confirm);
    await waitFor(() => expect(onDownload).toHaveBeenCalledTimes(1));
    expect(api.depositNotices.saveBundleSelection).toHaveBeenCalledWith(12, { quotation: true, attachmentIds: [5, 7, 9] });
    expect(onClose).toHaveBeenCalled();
  });

  it('falls back to the defaults (quotation + POs) when the remembered choice cannot be loaded, and still works', async () => {
    api.depositNotices.bundleSelection.mockRejectedValue(new Error('boom'));
    const { onDownload } = renderSheet();

    expect(await screen.findByText(/ใช้ค่าเริ่มต้น/)).not.toBeNull();
    await waitFor(() => expect(screen.getByTestId('include-attachment-5').checked).toBe(true));
    expect(screen.getByTestId('include-attachment-9').checked).toBe(false);
    fireEvent.click(screen.getByTestId('document-download-confirm'));
    await waitFor(() => expect(onDownload).toHaveBeenCalled());
    expect(api.depositNotices.saveBundleSelection).toHaveBeenCalledWith(12, { quotation: true, attachmentIds: [5, 7] });
  });

  it('a viewer who cannot write sees the remembered choice read-only and downloads without saving it', async () => {
    const { onDownload } = renderSheet(undefined, { canSave: false });
    const confirm = await screen.findByTestId('document-download-confirm');
    await waitFor(() => expect(screen.getByTestId('include-attachment-5').checked).toBe(true));
    expect(screen.getByTestId('include-attachment-5').disabled).toBe(true);
    expect(screen.queryByTestId('sheet-upload-input')).toBeNull();
    expect(screen.getByTestId('sheet-read-only-note')).not.toBeNull();
    fireEvent.click(confirm);
    await waitFor(() => expect(onDownload).toHaveBeenCalledTimes(1));
    expect(api.depositNotices.saveBundleSelection).not.toHaveBeenCalled();
  });

  it('stays open and shows the message when the download is refused (e.g. over the size cap)', async () => {
    const { onDownload, onClose } = renderSheet();
    onDownload.mockRejectedValue(new Error('ไฟล์ที่เลือกรวมกันใหญ่เกิน 40 MB'));

    const confirm = await screen.findByTestId('document-download-confirm');
    await waitFor(() => expect(confirm.disabled).toBe(false));
    fireEvent.click(confirm);
    expect(await screen.findByText(/ใหญ่เกิน 40 MB/)).not.toBeNull();
    expect(onClose).not.toHaveBeenCalled();
  });
});
