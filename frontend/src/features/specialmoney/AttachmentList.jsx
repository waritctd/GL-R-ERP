import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import imageCompression from 'browser-image-compression';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { Icon } from '../../components/common/Icon.jsx';

const ACCEPT = 'application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png';

function bytesToLabel(bytes) {
  if (!bytes) return '';
  const kb = Number(bytes) / 1024;
  return kb < 1024 ? `${kb.toFixed(0)} KB` : `${(kb / 1024).toFixed(1)} MB`;
}

/** AttachmentList — closes the redesign's most serious defect (plan §"Context"): the CEO used to approve
 * evidence they could never see. There is no `download`/`eye`/`external-link` icon in Icon.jsx's 39-icon
 * registry (checked), so `fileText` carries the row instead. */
export function AttachmentList({ requestId, canUpload = false, showToast, className }) {
  const queryClient = useQueryClient();

  const attachmentsQuery = useQuery({
    queryKey: queryKeys.specialMoneyAttachments(requestId),
    queryFn: () => api.specialMoney.attachments(requestId).then((response) => response.attachments || []),
    enabled: requestId != null,
  });
  const attachments = attachmentsQuery.data ?? [];

  const uploadMutation = useMutation({
    mutationFn: (file) => api.specialMoney.addAttachment(requestId, file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.specialMoneyAttachments(requestId) });
      queryClient.invalidateQueries({ queryKey: ['specialMoney', 'list'] });
      showToast?.('success', 'แนบเอกสารแล้ว');
    },
    onError: (error) => showToast?.('error', error.message || 'แนบเอกสารไม่สำเร็จ'),
  });

  async function handleFileChosen(event) {
    const file = event.target.files?.[0];
    if (!file) return;
    if (!['application/pdf', 'image/jpeg', 'image/png'].includes(file.type)) {
      showToast?.('error', 'รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG');
      return;
    }
    try {
      // imageCompression() returns a plain Blob, not a File -- it does not carry `file.name` through. PDFs skip
      // compression entirely and never hit this path, which is why the bug reads as image-only. Re-wrapping in a
      // File restores the name (and mtime) the compression step dropped.
      const prepared = file.type.startsWith('image/')
        ? new File(
          [await imageCompression(file, { maxSizeMB: 2, maxWidthOrHeight: 1600, useWebWorker: true })],
          file.name,
          { type: file.type },
        )
        : file;
      uploadMutation.mutate(prepared);
    } catch (error) {
      showToast?.('error', error.message || 'เตรียมไฟล์แนบไม่สำเร็จ');
    }
  }

  return (
    <div className={className} data-testid="attachment-list">
      {canUpload ? (
        <FileUploadField
          id={`smr-attachment-upload-${requestId}`}
          accept={ACCEPT}
          onChange={handleFileChosen}
          helperText="PDF, JPG หรือ PNG"
          disabled={uploadMutation.isPending}
        />
      ) : null}
      {/* No border/background per row (card-diet rule) -- a divider between rows is enough; a bordered box per file,
       * inside a component that itself sits inside an already-bordered request row/Panel, is the "boxes inside
       * boxes" this redesign is meant to remove. */}
      <ul className="m-0 mt-2 flex list-none flex-col divide-y divide-border-subtle p-0">
        {attachmentsQuery.isLoading ? (
          <li className="text-xs text-text-muted">กำลังโหลดเอกสารแนบ…</li>
        ) : attachments.length === 0 ? (
          <li className="text-xs text-text-muted">ยังไม่มีเอกสารแนบ</li>
        ) : attachments.map((attachment) => (
          <li
            key={attachment.id}
            className="flex items-center justify-between gap-2 py-1.5"
          >
            <a
              href={api.specialMoney.attachmentDownloadUrl(attachment.id)}
              target="_blank"
              rel="noopener noreferrer"
              download={attachment.fileName}
              className="flex min-w-0 flex-1 items-center gap-1.5 truncate text-left text-xs font-bold text-primary"
            >
              <Icon name="fileText" size={13} />
              <span className="truncate">{attachment.fileName}</span>
              {attachment.sizeBytes ? (
                <span className="shrink-0 text-2xs font-normal text-text-muted">{bytesToLabel(attachment.sizeBytes)}</span>
              ) : null}
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
