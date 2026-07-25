import { Icon } from '../../../components/common/Icon.jsx';
import { Skeleton } from '../../../components/common/Skeleton.jsx';
import { formatThaiDate } from '../../../utils/format.js';

const ATTACHMENT_TYPE_LABEL = {
  INVOICE: 'ใบกำกับภาษี',
  PO: 'PO / ใบสั่งซื้อ',
  SIGNED_QUOTATION: 'ใบเซ็น',
  OTHER: 'เอกสารประกอบ',
};

function attachmentTypeLabel(type) {
  return ATTACHMENT_TYPE_LABEL[type] ?? type ?? 'เอกสารประกอบ';
}

export function DealDocumentList({
  attachments = [],
  loading = false,
  canDelete = false,
  fileUrl,
  onDelete,
}) {
  if (loading) {
    return (
      <div
        className="flex flex-col gap-2"
        aria-busy="true"
        aria-label="กำลังโหลดไฟล์แนบ"
        data-testid="deal-document-list"
      >
        {[0, 1, 2].map((index) => (
          <div key={index} className="flex min-h-11 items-center gap-2 rounded-md bg-surface-subtle px-3 py-2">
            <Skeleton width={16} height={16} radius="var(--radius-sm)" />
            <Skeleton width="55%" height={14} />
            <Skeleton width={72} height={16} radius="var(--radius-pill)" />
          </div>
        ))}
      </div>
    );
  }

  if (attachments.length === 0) {
    return (
      <div className="rounded-md bg-surface-subtle px-3 py-3 text-sm" data-testid="deal-document-list">
        <strong className="block text-text">ยังไม่มีไฟล์แนบ</strong>
        <span className="mt-1 block text-xs text-text-muted">
          เอกสาร PO ใบเซ็น หรือใบกำกับภาษีจะแสดงที่นี่เมื่อมีการแนบไฟล์
        </span>
      </div>
    );
  }

  return (
    <div className="divide-y divide-border-subtle border-y border-border-subtle" data-testid="deal-document-list">
      {attachments.map((attachment) => (
        <div key={attachment.id} className="flex min-h-11 flex-wrap items-center gap-2 py-2.5 text-sm">
          <Icon name="paperclip" size={14} className="shrink-0 text-icon-muted" />
          <div className="min-w-0 flex-1">
            <span className="block break-all font-semibold text-text">{attachment.fileName}</span>
            <span className="mt-1 block text-xs text-text-muted">
              {attachmentTypeLabel(attachment.attachType)}
              {attachment.uploadedAt ? ` · ${formatThaiDate(attachment.uploadedAt)}` : ''}
            </span>
          </div>
          <a
            href={fileUrl(attachment.id)}
            target="_blank"
            rel="noreferrer"
            className="secondary-button min-h-9 px-3 text-xs"
          >
            ดูไฟล์
          </a>
          {canDelete ? (
            <button
              type="button"
              className="icon-button"
              style={{ color: 'var(--color-danger)' }}
              aria-label={`ลบไฟล์ ${attachment.fileName}`}
              onClick={() => onDelete?.(attachment.id, attachment.fileName)}
            >
              <Icon name="close" size={14} />
            </button>
          ) : null}
        </div>
      ))}
    </div>
  );
}
