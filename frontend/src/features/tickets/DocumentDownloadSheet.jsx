import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { attachTypeLabel } from './dealTrackingMeta.js';

const UPLOAD_ACCEPT = '.pdf,.png,.jpg,.jpeg';

/**
 * The one "ดาวน์โหลด PDF" sheet for a deposit notice / remaining invoice: the document itself is always in; the
 * referenced quotation and the deal's attachments are checkboxes. It starts from the server's remembered choice for
 * this document (else the quotation + every PO), SAVES the choice (PUT) and then calls `onDownload()`, which downloads
 * the PDF with the saved choice and shows its own errors. `kind` is 'deposit' | 'remaining'.
 */
export function DocumentDownloadSheet({ kind, documentId, ticketId, reference, onClose, onDownload }) {
  const queryClient = useQueryClient();
  const remaining = kind === 'remaining';
  // The SAME key and shape as TicketDetailPage's attachment list (a plain array): the two share one cache entry.
  const attachmentsQuery = useQuery({
    queryKey: queryKeys.ticketAttachments(ticketId),
    queryFn: () => api.attachments.list(ticketId).then((r) => r.attachments ?? []),
  });
  const selectionQuery = useQuery({
    queryKey: ['bundleSelection', kind, documentId],
    // spelled out per kind (not through an alias) so the UI-reachability scan sees every call
    queryFn: () => (remaining ? api.storedRemainingInvoices.bundleSelection(documentId)
      : api.depositNotices.bundleSelection(documentId)),
    gcTime: 0,
    retry: false,
  });
  // upload order (the list comes newest first), so the sheet and the PDF follow the same oldest-first order
  const files = [...(attachmentsQuery.data ?? [])].sort((a, b) => a.id - b.id);
  const [quotation, setQuotation] = useState(true);
  const [checked, setChecked] = useState(() => new Set());
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState(null);
  const selectionFailed = selectionQuery.isError;

  // Seed once, when BOTH queries have answered: the remembered / default choice, or (selection fetch failed) the
  // defaults, the quotation plus every PO. Later edits (and uploads) are the user's.
  useEffect(() => {
    if (ready || !attachmentsQuery.data || (!selectionQuery.data && !selectionFailed)) return;
    if (selectionFailed) {
      setQuotation(true);
      setChecked(new Set(attachmentsQuery.data.filter((f) => f.attachType === 'PO').map((f) => f.id)));
    } else {
      setQuotation(selectionQuery.data.quotation !== false);
      setChecked(new Set(selectionQuery.data.attachmentIds ?? []));
    }
    setReady(true);
  }, [ready, attachmentsQuery.data, selectionQuery.data, selectionFailed]);

  function toggle(id) {
    setChecked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  async function handleUpload(event) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setUploading(true);
    setError(null);
    try {
      const { attachment } = await api.attachments.upload(ticketId, file, 'OTHER');
      await queryClient.invalidateQueries({ queryKey: queryKeys.ticketAttachments(ticketId) });
      setChecked((prev) => new Set(prev).add(attachment.id));
    } catch (err) {
      setError(err.message || 'อัปโหลดไม่สำเร็จ');
    } finally {
      setUploading(false);
    }
  }

  async function handleDownload() {
    setBusy(true);
    setError(null);
    try {
      const selection = { quotation, attachmentIds: files.filter((f) => checked.has(f.id)).map((f) => f.id) };
      await (remaining ? api.storedRemainingInvoices.saveBundleSelection(documentId, selection)
        : api.depositNotices.saveBundleSelection(documentId, selection));
      await onDownload();
      onClose();
    } catch (err) {
      setError(err?.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="ดาวน์โหลด PDF"
      subtitle="เลือกไฟล์ที่จะรวมในเอกสาร"
      onClose={onClose}
      placement="sheet-on-phone"
      testId="document-download-sheet"
      footer={(
        <Button onClick={handleDownload} disabled={!ready || busy || uploading} data-testid="document-download-confirm">
          {busy ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลด'}
        </Button>
      )}
    >
      <div className="grid gap-3">
        {selectionFailed ? (
          <p role="alert" className="m-0 text-sm text-warning-dark">
            โหลดตัวเลือกที่จำไว้ไม่สำเร็จ ใช้ค่าเริ่มต้น (ใบเสนอราคาและ PO)
          </p>
        ) : null}
        {attachmentsQuery.isError ? (
          <p role="alert" className="m-0 text-sm text-danger">โหลดรายการไฟล์แนบไม่สำเร็จ</p>
        ) : null}
        <label className="flex items-center gap-2 text-sm text-text-muted">
          <input type="checkbox" checked disabled readOnly />
          <span>ตัวเอกสาร (รวมเสมอ)</span>
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={quotation} disabled={!ready}
            onChange={(e) => setQuotation(e.target.checked)} data-testid="include-quotation" />
          <span>ใบเสนอราคาที่อ้างอิง{reference ? ` (${reference})` : ''}</span>
        </label>
        {files.map((file) => (
          <label key={file.id} className="flex min-w-0 items-center gap-2 text-sm">
            <input type="checkbox" checked={checked.has(file.id)} disabled={!ready}
              onChange={() => toggle(file.id)} data-testid={`include-attachment-${file.id}`} />
            <span className="min-w-0 truncate">{file.fileName}</span>
            <span className="shrink-0 text-xs text-text-muted">{attachTypeLabel(file.attachType)}</span>
          </label>
        ))}
        <label className="cursor-pointer">
          <input type="file" accept={UPLOAD_ACCEPT} className="sr-only" onChange={handleUpload}
            disabled={uploading} data-testid="sheet-upload-input" />
          <span className="inline-block rounded-md border border-border px-3 py-2 text-sm font-bold">
            {uploading ? 'กำลังอัปโหลด…' : 'อัปโหลดไฟล์เพิ่ม'}
          </span>
        </label>
        {error ? <p role="alert" className="m-0 text-sm text-danger">{error}</p> : null}
      </div>
    </Modal>
  );
}
