import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import imageCompression from 'browser-image-compression';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { Icon } from '../../components/common/Icon.jsx';

const ACCEPT = 'application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png';

function bytesToLabel(bytes) {
  if (!bytes) return '';
  const kb = Number(bytes) / 1024;
  return kb < 1024 ? `${kb.toFixed(0)} KB` : `${(kb / 1024).toFixed(1)} MB`;
}

/**
 * Evidence attachment panel — "สำเนาหลักฐานแสดงสิทธิ" the employee gives the employer alongside
 * ล.ย.01 (issue #387). `prepareAttachment` is lifted verbatim from LeavePage.jsx:417-430 (same
 * compression settings) — employees photograph certificates/receipts on phones, same as a leave
 * medical certificate.
 *
 * Requires an existing declaration (`declarationId`): attachments hang off
 * `POST/GET .../declarations/{id}/attachments`, so there is nothing to attach to before the
 * employee's first submit creates the row.
 */
export function TaxAllowanceEvidencePanel({ declarationId, canEdit, showToast }) {
  const queryClient = useQueryClient();
  const [pendingFile, setPendingFile] = useState(null);
  const [busyId, setBusyId] = useState(null);

  const attachmentsQuery = useQuery({
    queryKey: queryKeys.taxAllowanceAttachments(declarationId),
    queryFn: () => api.payroll.listTaxAllowanceAttachments(declarationId).then((response) => response.items || []),
    enabled: declarationId != null,
  });
  const attachments = (attachmentsQuery.data ?? []).filter((item) => !item.deletedAt);

  const uploadMutation = useMutation({
    mutationFn: (file) => api.payroll.uploadTaxAllowanceAttachment(declarationId, file),
    onSuccess: () => {
      setPendingFile(null);
      queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceAttachments(declarationId) });
      showToast?.('success', 'แนบหลักฐานเรียบร้อย');
    },
    onError: (error) => showToast?.('error', error.message || 'แนบหลักฐานไม่สำเร็จ'),
  });

  const deleteMutation = useMutation({
    mutationFn: (attachmentId) => api.payroll.deleteTaxAllowanceAttachment(attachmentId),
    onMutate: (attachmentId) => setBusyId(attachmentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceAttachments(declarationId) });
      showToast?.('success', 'ลบไฟล์แนบแล้ว');
    },
    onError: (error) => showToast?.('error', error.message || 'ลบไฟล์แนบไม่สำเร็จ'),
    onSettled: () => setBusyId(null),
  });

  async function handleFileChosen(event) {
    const file = event.target.files?.[0];
    if (!file) return;
    if (!['application/pdf', 'image/jpeg', 'image/png'].includes(file.type)) {
      showToast?.('error', 'รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG');
      return;
    }
    setPendingFile(file);
    try {
      const prepared = file.type.startsWith('image/')
        ? await imageCompression(file, { maxSizeMB: 2, maxWidthOrHeight: 1600, useWebWorker: true })
        : file;
      uploadMutation.mutate(prepared);
    } catch (error) {
      setPendingFile(null);
      showToast?.('error', error.message || 'เตรียมไฟล์แนบไม่สำเร็จ');
    }
  }

  async function handleDownload(attachment) {
    try {
      const blob = await api.payroll.downloadTaxAllowanceAttachment(attachment.attachmentId);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = attachment.fileName || `evidence-${attachment.attachmentId}`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      showToast?.('error', error.message || 'ดาวน์โหลดไฟล์ไม่สำเร็จ');
    }
  }

  if (declarationId == null) {
    return (
      <section className="rounded-md border border-border bg-surface p-4">
        <h3 className="m-0 mb-2 text-sm font-extrabold text-text">หลักฐานแสดงสิทธิ</h3>
        <EmptyState icon="paperclip" title="ยื่นแบบแจ้งก่อนเพื่อแนบหลักฐาน" description="แนบสำเนาหลักฐานได้หลังจากยื่นแบบแจ้งครั้งแรก" />
      </section>
    );
  }

  return (
    <section className="rounded-md border border-border bg-surface p-4">
      <h3 className="m-0 mb-2 text-sm font-extrabold text-text">หลักฐานแสดงสิทธิ (สำเนา)</h3>
      {canEdit ? (
        <FileUploadField
          id="tax-allowance-evidence-file"
          accept={ACCEPT}
          onChange={handleFileChosen}
          helperText="PDF, JPG หรือ PNG — เช่น ทะเบียนสมรส สูติบัตรบุตร ใบเสร็จเบี้ยประกัน หนังสือรับรองดอกเบี้ยกู้ยืม"
          disabled={uploadMutation.isPending}
        />
      ) : null}
      {uploadMutation.isPending && pendingFile ? (
        <p className="m-0 mt-2 text-xs text-text-muted">กำลังอัปโหลด {pendingFile.name}…</p>
      ) : null}
      <ul className="m-0 mt-3 flex list-none flex-col gap-2 p-0">
        {attachments.length === 0 ? (
          <li className="text-xs text-text-muted">ยังไม่มีไฟล์แนบ</li>
        ) : attachments.map((attachment) => (
          <li key={attachment.attachmentId} className="flex items-center justify-between gap-3 rounded-md border border-border-subtle bg-surface-subtle px-3 py-2">
            <button
              type="button"
              className="flex min-w-0 flex-1 items-center gap-2 truncate text-left text-sm font-bold text-primary"
              onClick={() => handleDownload(attachment)}
            >
              <Icon name="fileText" size={14} />
              <span className="truncate">{attachment.fileName}</span>
              {attachment.fileSize ? <span className="shrink-0 text-2xs font-normal text-text-muted">{bytesToLabel(attachment.fileSize)}</span> : null}
            </button>
            {canEdit ? (
              <Button
                type="button"
                variant="danger"
                size="sm"
                onClick={() => deleteMutation.mutate(attachment.attachmentId)}
                loading={busyId === attachment.attachmentId}
                aria-label={`ลบไฟล์ ${attachment.fileName}`}
              >
                ลบ
              </Button>
            ) : null}
          </li>
        ))}
      </ul>
    </section>
  );
}
