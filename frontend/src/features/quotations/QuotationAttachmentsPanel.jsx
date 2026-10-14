import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { cn } from '../../utils/cn.js';
import { formatBangkokTime, formatFileSize, formatThaiDate } from '../../utils/format.js';
import {
  canAttachToDealQuotation, canRemoveDealQuotationAttachment, canSeeDealQuotationAttachments,
} from './quotationMeta.js';

// The panel's DOM id, which is what QuotationAttachmentsLink points at.
const PANEL_ID = 'quotation-attachments';
// Mirrors MAX_ATTACHMENTS_PER_QUOTATION and ALLOWED_TYPES of DealQuotationAttachmentService. The
// server refuses on both, and its refusal is what the panel shows. These only keep a rep from
// being offered a pick the server will turn down.
const MAX_OWN_FILES = 10;
const ACCEPT = 'application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png';

/**
 * A quotation's เอกสารแนบ, read once for the panel and for the link that points at it. Both ask
 * with the same key, so they share one request. Nothing is asked for a viewer who is not shown
 * the files, or for a quotation that has no id yet.
 */
function useQuotationAttachments(user, quotation) {
  return useQuery({
    queryKey: queryKeys.dealQuotationAttachments(quotation?.id),
    queryFn: () => api.dealQuotations.listAttachments(quotation.id).then((response) => response?.items ?? []),
    enabled: quotation?.id != null && canSeeDealQuotationAttachments(user),
  });
}

/**
 * "เอกสารแนบ {n} ไฟล์", for the top of the read-only details. The panel is the last block of the
 * page, so without this a reader has to scroll to learn there is anything to read. It is drawn
 * only when there is a file to go to, so not while the list loads, not when it failed and not at
 * zero.
 */
export function QuotationAttachmentsLink({ user, quotation }) {
  const files = useQuotationAttachments(user, quotation).data ?? [];
  if (!files.length) return null;

  // The same two steps as TicketDetailPage#scrollToSection. <main> is the scroll container here,
  // not the document, and browsers disagree on whether following a fragment moves focus, so both
  // are done by hand. Without the panel on the page the href is left to do what it can.
  function goToPanel(event) {
    const panel = document.getElementById(PANEL_ID);
    if (!panel) return;
    event.preventDefault();
    panel.scrollIntoView?.({ block: 'start' });
    panel.focus({ preventScroll: true });
  }

  return (
    <a
      href={`#${PANEL_ID}`}
      onClick={goToPanel}
      className="inline-flex min-h-[26px] items-center gap-1 text-xs font-bold text-link underline mobile:min-h-11"
    >
      <Icon name="paperclip" size={13} />
      เอกสารแนบ {files.length} ไฟล์
    </a>
  );
}

/**
 * The files attached to a quotation (เอกสารแนบ). A rep attaches the vendor's own quotation so the
 * approver sees what the goods cost. The files are internal and are never printed.
 *
 * `quotation` is null while the quotation has never been saved. Files picked then are not sent:
 * they are held by the page as `pending` (File[], changed through `onPendingChange`) and the page
 * sends them once the first บันทึกร่าง has made the quotation, so while `busy` (that save is
 * running) they can be neither added to nor removed. Otherwise what is offered follows
 * the three predicates in quotationMeta.js. The server decides, and its refusals are shown here
 * in its own words.
 */
export function QuotationAttachmentsPanel({ user, quotation, pending = [], onPendingChange, busy = false }) {
  const queryClient = useQueryClient();
  const list = useQuotationAttachments(user, quotation);
  // What the last attach or remove was refused with, until the next one is tried.
  const [refusal, setRefusal] = useState(null);
  // The file the confirm dialog is asking about.
  const [removing, setRemoving] = useState(null);

  const reload = () => queryClient.invalidateQueries({ queryKey: queryKeys.dealQuotationAttachments(quotation?.id) });
  const attach = useMutation({
    mutationFn: (file) => api.dealQuotations.uploadAttachment(quotation.id, file),
    onMutate: () => setRefusal(null),
    onSuccess: reload,
    onError: (error) => setRefusal(error?.message || 'แนบเอกสารไม่สำเร็จ'),
  });
  const remove = useMutation({
    mutationFn: (file) => api.dealQuotations.deleteAttachment(file.id),
    onMutate: () => setRefusal(null),
    onSuccess: reload,
    onError: (error) => setRefusal(error?.message || 'ลบเอกสารแนบไม่สำเร็จ'),
    onSettled: () => setRemoving(null),
  });

  if (!canSeeDealQuotationAttachments(user)) return null;

  const files = list.data ?? [];
  const unsaved = quotation == null;
  // The page decides who is shown the unsaved form, so there it is enough that it listens.
  const mayAttach = unsaved ? Boolean(onPendingChange) : canAttachToDealQuotation(user, quotation);
  const full = (unsaved ? pending.length : files.filter((file) => !file.inherited).length) >= MAX_OWN_FILES;
  const canPick = !full && !attach.isPending && !busy;

  function attachPicked(event) {
    const file = event.target.files?.[0];
    // Cleared at once, so picking the same file again after a refusal is still a change.
    event.target.value = '';
    if (!file) return;
    if (unsaved) onPendingChange([...pending, file]);
    else attach.mutate(file);
  }

  return (
    <>
      <Panel
        flush
        id={PANEL_ID}
        // Focusable so the link at the top of the details can land here, and out of the tab order.
        tabIndex={-1}
        aria-label="เอกสารแนบ"
        title="เอกสารแนบ"
        className="scroll-mt-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-hover"
        actions={mayAttach ? (
          // A <label> around the real input, because a <button> cannot open the file picker. The
          // input is visually hidden, not display:none, so Tab reaches it and Enter opens the
          // picker. The label draws the focus ring the hidden input cannot show.
          <label
            className={cn(
              buttonVariants({ variant: 'secondary' }),
              'has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-primary-hover',
              canPick ? 'cursor-pointer' : 'cursor-not-allowed opacity-55',
            )}
          >
            <input
              type="file"
              accept={ACCEPT}
              disabled={!canPick}
              onChange={attachPicked}
              className="sr-only h-px min-h-0 w-px border-0 p-0"
            />
            <Icon name="upload" size={14} />
            แนบเอกสาร
          </label>
        ) : null}
      >
        <div className="grid gap-2 px-5 py-4 text-sm mobile:px-4">
          <p className="m-0 text-xs text-text-muted">
            เอกสารประกอบการอนุมัติ เช่น ใบเสนอราคาของผู้ขาย ใช้ภายใน ไม่พิมพ์ในใบเสนอราคา
          </p>
          {mayAttach ? (
            <p className="m-0 text-xs text-text-muted">
              {full
                ? `แนบครบ ${MAX_OWN_FILES} ไฟล์แล้ว`
                : `แนบได้เฉพาะ PDF, JPG หรือ PNG ไม่เกิน ${MAX_OWN_FILES} ไฟล์ต่อใบเสนอราคา`}
              {quotation?.docStatus === 'PENDING_APPROVAL' ? ' ระหว่างรออนุมัติ แนบเพิ่มได้ แต่ลบไม่ได้' : ''}
            </p>
          ) : null}
          {attach.isPending ? (
            <p role="status" className="m-0 text-xs font-bold text-text-secondary">
              กำลังแนบ {attach.variables?.name}…
            </p>
          ) : null}
          {refusal ? (
            <p role="alert" className="m-0 rounded-md border border-danger-border bg-danger/10 p-3 text-xs text-danger">
              {refusal}
            </p>
          ) : null}
          {unsaved ? (
            <>
              {mayAttach ? (
                <p className="m-0 text-xs text-text-muted">ไฟล์ที่เลือกไว้จะถูกแนบเมื่อบันทึกร่างครั้งแรก</p>
              ) : null}
              {pending.length === 0 ? <p className="m-0 text-text-muted">ยังไม่มีเอกสารแนบ</p> : null}
            </>
          ) : list.isPending ? (
            // `isPending`, not `isLoading`: a request that is held back (offline, or between
            // retries while the tab is hidden) is not an answer, and must not read as "none".
            <p role="status" className="m-0 text-text-muted">กำลังโหลดเอกสารแนบ…</p>
          ) : list.isError ? (
            <p role="alert" className="m-0 text-danger">
              โหลดเอกสารแนบไม่สำเร็จ{list.error?.message ? ` (${list.error.message})` : ''}
            </p>
          ) : files.length === 0 ? (
            <p className="m-0 text-text-muted">{mayAttach ? 'ยังไม่มีเอกสารแนบ' : 'ไม่มีเอกสารแนบ'}</p>
          ) : null}
        </div>

        {unsaved && pending.length ? (
          <ul className="m-0 list-none p-0">
            {pending.map((file, index) => (
              <li
                key={`${index}-${file.name}`}
                data-testid="quotation-attachment"
                className="flex items-center justify-between gap-3 border-t border-border-subtle px-5 py-2 mobile:px-4"
              >
                <div className="min-w-0">
                  <span className="inline-flex items-center gap-2 font-bold [overflow-wrap:anywhere]">
                    <Icon name="fileText" size={14} className="shrink-0" />
                    {file.name}
                  </span>
                  <p className="m-0 text-xs text-text-muted [overflow-wrap:anywhere]">
                    {formatFileSize(file.size)} · รอบันทึกร่าง
                  </p>
                </div>
                <Button
                  variant="danger"
                  className="shrink-0"
                  aria-label={`นำออก ${file.name}`}
                  disabled={busy}
                  onClick={() => onPendingChange(pending.filter((_, at) => at !== index))}
                >
                  นำออก
                </Button>
              </li>
            ))}
          </ul>
        ) : null}

        {files.length ? (
          <ul className="m-0 list-none p-0">
            {files.map((file) => (
              <li
                key={file.id}
                data-testid="quotation-attachment"
                className="flex items-center justify-between gap-3 border-t border-border-subtle px-5 py-2 mobile:px-4"
              >
                <div className="min-w-0">
                  {/* A new tab, as the pricing-request file links are. The server answers a
                      download, and a refusal then lands in that tab and not over this page. */}
                  <a
                    href={api.dealQuotations.attachmentUrl(file.id)}
                    target="_blank"
                    rel="noreferrer"
                    className="inline-flex items-center gap-2 font-bold text-link underline [overflow-wrap:anywhere] mobile:min-h-11"
                  >
                    <Icon name="fileText" size={14} className="shrink-0" />
                    {file.fileName}
                  </a>
                  <p className="m-0 text-xs text-text-muted [overflow-wrap:anywhere]">
                    {formatFileSize(file.fileSize)} · แนบโดย {file.uploadedByName ?? '-'} ·{' '}
                    {formatThaiDate(file.uploadedAt)} {formatBangkokTime(file.uploadedAt)} น.
                  </p>
                  {file.inherited ? (
                    <p className="m-0 text-xs font-bold text-text-secondary">จากฉบับก่อนหน้า {file.quotationNumber}</p>
                  ) : null}
                </div>
                {canRemoveDealQuotationAttachment(user, quotation, file) ? (
                  <Button
                    variant="danger"
                    className="shrink-0"
                    aria-label={`ลบเอกสาร ${file.fileName}`}
                    onClick={() => setRemoving(file)}
                  >
                    ลบ
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        ) : null}
      </Panel>

      <ConfirmDialog
        open={removing != null}
        title="ลบเอกสารแนบ"
        message={(
          <p className="text-text-secondary leading-normal [overflow-wrap:anywhere]">
            ลบ <strong>{removing?.fileName}</strong> ออกจากใบเสนอราคานี้ ไฟล์ที่ลบแล้วกู้คืนไม่ได้
          </p>
        )}
        confirmLabel="ลบเอกสาร"
        tone="danger"
        busy={remove.isPending}
        onConfirm={() => remove.mutate(removing)}
        onCancel={() => setRemoving(null)}
      />
    </>
  );
}
