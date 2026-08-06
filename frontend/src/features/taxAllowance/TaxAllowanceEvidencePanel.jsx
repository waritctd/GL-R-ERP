import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import imageCompression from 'browser-image-compression';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { Icon } from '../../components/common/Icon.jsx';

const ACCEPT = 'application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png';
const ACCEPTED_MIME_TYPES = ['application/pdf', 'image/jpeg', 'image/png'];

function bytesToLabel(bytes) {
  if (!bytes) return '';
  const kb = Number(bytes) / 1024;
  return kb < 1024 ? `${kb.toFixed(0)} KB` : `${(kb / 1024).toFixed(1)} MB`;
}

/**
 * Shared prep step for both upload paths below — validates the MIME allowlist, then compresses an
 * image (PDFs pass through untouched). Lifted from the pre-#tax-allowance-sections version of this
 * file, itself lifted from LeavePage.jsx:417-430 (same compression settings).
 */
async function prepareFile(file) {
  if (!ACCEPTED_MIME_TYPES.includes(file.type)) {
    throw new Error('รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG');
  }
  if (!file.type.startsWith('image/')) return file;
  // imageCompression() returns a plain Blob, not a File -- re-wrap so the filename survives (same
  // fix as LeaveRequestPage.jsx's own prepareAttachment, #498/#504).
  return new File(
    [await imageCompression(file, { maxSizeMB: 2, maxWidthOrHeight: 1600, useWebWorker: true })],
    file.name,
    { type: file.type },
  );
}

/**
 * Evidence attachment panel — "สำเนาหลักฐานแสดงสิทธิ" the employee gives the employer alongside
 * ล.ย.01 (issue #387). Rendered PER SECTION now (#tax-allowance-sections): one instance per
 * TAX_ALLOWANCE_GROUPS entry, filtered to that section's own `sectionKey` (V135).
 *
 * Three modes, computed by the caller (TaxAllowancePage) from the declaration's own state — this
 * component has no opinion of its own about which mode applies:
 *
 *   - `'direct'`   — a real `declarationId` already exists AND is directly editable (today: the
 *                    caller's own PENDING declaration). Uploads/deletes go straight to the server,
 *                    same as this panel always worked before this task.
 *   - `'staging'`  — the form is being FILLED IN (a brand-new declaration, or a REJECTED/EXPIRED one
 *                    being re-prepared) and there is no declarationId a real upload could attach to
 *                    yet, OR there is one but it belongs to a superseded/old submission. Files are
 *                    validated + compressed immediately (same as direct mode) but held in the
 *                    PARENT's state (`staged`) instead of uploaded, and are actually sent to the
 *                    server only once the whole declaration is submitted and a real declarationId
 *                    exists (`TaxAllowancePage#flushStagedEvidence`). This is the fix for the
 *                    reported complaint ("I couldn't attach a PDF while first filling in the form")
 *                    — see that page's own comment for why a real "draft declaration" endpoint was
 *                    deliberately NOT added to get there (CLAUDE.md's payroll/tax business-logic
 *                    guardrail; this task's scope is additive data-tagging, not a new approval-
 *                    workflow state).
 *   - `'readonly'` — nothing can be added or removed here (a filed-and-decided declaration, or a
 *                    past tax year being browsed). Only the existing list renders.
 *
 * `sectionKey` is this instance's OWN section (null for the step-1 "general/uncategorized" bucket).
 * `showUncategorized` additionally surfaces attachments with a NULL `sectionKey` — pre-V135 rows
 * that predate section tagging entirely. Chosen home for those (documented per the task's own
 * instruction to pick one and say so): the step-1 "general" bucket, NOT duplicated onto every
 * section — showing the same legacy file under all five sections would look like five different
 * files to a user skimming quickly, and "the first section" was the other option on the table but
 * has no stronger claim to an untagged file than a dedicated, clearly-labelled bucket does.
 */
export function TaxAllowanceEvidencePanel({
  mode, // 'direct' | 'staging' | 'readonly'
  declarationId,
  sectionKey,
  showUncategorized = false,
  staged = [],
  onStageFile,
  onUnstageFile,
  showToast,
  title = 'หลักฐานแสดงสิทธิ (สำเนา)',
  emptyLabel,
}) {
  const queryClient = useQueryClient();
  const [pendingFile, setPendingFile] = useState(null);
  const [busyId, setBusyId] = useState(null);
  // FileUploadField forwards this straight to the native input (its own doc comment names this
  // exact reset pattern). Without it, unstaging a file then re-picking the SAME file fires no
  // change event -- the browser only fires one for a value that differs from the input's current
  // value (review #tax-allowance-sections).
  const fileInputRef = useRef(null);

  const attachmentsQuery = useQuery({
    queryKey: queryKeys.taxAllowanceAttachments(declarationId),
    queryFn: () => api.payroll.listTaxAllowanceAttachments(declarationId).then((response) => response.items || []),
    enabled: declarationId != null,
  });
  const allAttachments = (attachmentsQuery.data ?? []).filter((item) => !item.deletedAt);
  // `sectionKey == null` means THIS instance IS the general/uncategorized bucket (step 1's panel) --
  // its own list is `uncategorizedAttachments` below, not a "section" match against a null key,
  // which would otherwise double-count every uncategorized row (once here, once there).
  const sectionAttachments = sectionKey != null ? allAttachments.filter((item) => item.sectionKey === sectionKey) : [];
  const uncategorizedAttachments = showUncategorized ? allAttachments.filter((item) => !item.sectionKey) : [];

  const uploadMutation = useMutation({
    mutationFn: (file) => api.payroll.uploadTaxAllowanceAttachment(declarationId, file, sectionKey ?? undefined),
    onSuccess: () => {
      setPendingFile(null);
      queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceAttachments(declarationId) });
      showToast?.('success', 'แนบหลักฐานเรียบร้อย');
    },
    onError: (error) => showToast?.('error', error.message || 'แนบหลักฐานไม่สำเร็จ'),
  });

  const deleteMutation = useMutation({
    mutationFn: (attachmentId) => api.payroll.deleteTaxAllowanceAttachment(attachmentId),
    onSuccess: (_, attachmentId) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceAttachments(declarationId) });
      showToast?.('success', 'ลบไฟล์แนบแล้ว');
      setBusyId((current) => (current === attachmentId ? null : current));
    },
    onError: (error) => showToast?.('error', error.message || 'ลบไฟล์แนบไม่สำเร็จ'),
    onSettled: () => setBusyId(null),
  });

  async function handleFileChosen(event) {
    const file = event.target.files?.[0];
    // Reset immediately (not after processing) so the input's value never lingers at a file the
    // user then unstages/removes -- otherwise picking that exact file again is a silent no-op.
    if (fileInputRef.current) fileInputRef.current.value = '';
    if (!file) return;
    setPendingFile(file);
    try {
      const prepared = await prepareFile(file);
      if (mode === 'direct') {
        uploadMutation.mutate(prepared);
      } else if (mode === 'staging') {
        onStageFile?.(prepared);
        setPendingFile(null);
      }
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

  function handleDeleteStored(attachmentId) {
    setBusyId(attachmentId);
    deleteMutation.mutate(attachmentId);
  }

  const canUpload = mode === 'direct' || mode === 'staging';
  const hasAnything = sectionAttachments.length > 0 || uncategorizedAttachments.length > 0 || staged.length > 0;

  return (
    <section className="rounded-md border border-border bg-surface p-4">
      <h3 className="m-0 mb-2 text-sm font-extrabold text-text">{title}</h3>

      {canUpload ? (
        <FileUploadField
          ref={fileInputRef}
          id={`tax-allowance-evidence-file-${sectionKey ?? 'general'}`}
          accept={ACCEPT}
          onChange={handleFileChosen}
          helperText="PDF, JPG หรือ PNG — เช่น ทะเบียนสมรส สูติบัตรบุตร ใบเสร็จเบี้ยประกัน หนังสือรับรองดอกเบี้ยกู้ยืม"
          disabled={uploadMutation.isPending}
        />
      ) : null}
      {uploadMutation.isPending && pendingFile ? (
        <p className="m-0 mt-2 text-xs text-text-muted">กำลังอัปโหลด {pendingFile.name}…</p>
      ) : null}

      {!hasAnything ? (
        canUpload ? (
          <p className="m-0 mt-3 text-xs text-text-muted">ยังไม่มีไฟล์แนบ</p>
        ) : (
          <EmptyState
            icon="paperclip"
            title="ไม่มีไฟล์แนบ"
            description={emptyLabel ?? 'ไม่มีหลักฐานแนบสำหรับส่วนนี้'}
          />
        )
      ) : (
        <ul className="m-0 mt-3 flex list-none flex-col gap-2 p-0">
          {/* Staged (not-yet-uploaded) files first -- these are what the employee JUST picked while
              filling this section in, so they belong at the top, closest to the picker above. */}
          {staged.map((item) => (
            <li key={item.tempId} className="flex items-center justify-between gap-3 rounded-md border border-dashed border-border-input bg-surface-subtle px-3 py-2">
              <span className="flex min-w-0 flex-1 items-center gap-2 truncate text-sm font-bold text-text">
                <Icon name="fileText" size={14} />
                <span className="truncate">{item.fileName}</span>
                <span className="shrink-0 rounded-sm bg-info-bg-alt px-1.5 py-0.5 text-2xs font-bold text-info">รอส่ง</span>
                {item.fileSize ? <span className="shrink-0 text-2xs font-normal text-text-muted">{bytesToLabel(item.fileSize)}</span> : null}
              </span>
              <Button type="button" variant="danger" size="sm" onClick={() => onUnstageFile?.(item.tempId)} aria-label={`ลบไฟล์ ${item.fileName}`}>
                ลบ
              </Button>
            </li>
          ))}
          {sectionAttachments.map((attachment) => (
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
              {mode === 'direct' ? (
                <Button
                  type="button"
                  variant="danger"
                  size="sm"
                  onClick={() => handleDeleteStored(attachment.attachmentId)}
                  loading={busyId === attachment.attachmentId}
                  aria-label={`ลบไฟล์ ${attachment.fileName}`}
                >
                  ลบ
                </Button>
              ) : null}
            </li>
          ))}
          {uncategorizedAttachments.length > 0 ? (
            <>
              {/* Only worth a sub-heading when there is something ELSE above it to distinguish from
                  -- the general/uncategorized bucket instance (sectionKey=null) has nothing else in
                  its own list, so the heading would just repeat the section's own title for no
                  reason. */}
              {staged.length > 0 || sectionAttachments.length > 0 ? (
                <li className="mt-1 text-2xs font-extrabold uppercase tracking-wide text-text-muted">
                  หลักฐานเก่าที่ยังไม่ได้ระบุหมวด
                </li>
              ) : null}
              {uncategorizedAttachments.map((attachment) => (
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
                  {mode === 'direct' ? (
                    <Button
                      type="button"
                      variant="danger"
                      size="sm"
                      onClick={() => handleDeleteStored(attachment.attachmentId)}
                      loading={busyId === attachment.attachmentId}
                      aria-label={`ลบไฟล์ ${attachment.fileName}`}
                    >
                      ลบ
                    </Button>
                  ) : null}
                </li>
              ))}
            </>
          ) : null}
        </ul>
      )}
    </section>
  );
}
