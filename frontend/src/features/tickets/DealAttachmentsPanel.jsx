// DealAttachmentsPanel — extracted verbatim from TicketDetailPage.jsx in the
// ticket-workspace IA rebuild (ia-extract Slice C1, "extract two panels out
// of TicketDetailPage"). This is a pure move: the JSX, its comments, and the
// gating logic are unchanged from the "documents" tab's ไฟล์แนบ (PO / ใบเซ็น)
// section — only the plumbing (props in, `api`/`Icon`/`EmptyState`/`Skeleton`
// imported directly like the parent already did) changed so this block could
// live in its own file. It holds NO state of its own: every mutation
// (upload/delete) is still owned by TicketDetailPage's mutations and handed
// down as callbacks, exactly as before the move.
import { api } from '../../api/index.js';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { cn } from '../../utils/cn.js';

export function DealAttachmentsPanel({
  attachments,
  attachLoading,
  canManageDocuments,
  uploadingFile,
  onUploadAttachment,
  onDeleteAttachment,
  canUpload,
  notTerminal,
  user,
}) {
  return (
    <Panel
      title="ไฟล์แนบ (PO / ใบเซ็น)"
      // No แนบใบกำกับภาษี control here, deliberately (2026-07-30 owner
      // decision). The closing tax invoice is ฝ่ายบัญชี's to record, and
      // the ONLY supported path is CommissionService.createFromDeal
      // (POST /api/commissions/from-deal, CREATE_FROM_DEAL_ROLES =
      // account-only), reached from this page's own sticky CTA
      // "บันทึกใบกำกับ + ออกค่าคอม" -> /commissions?ticketId=NN
      // (accountActions.js). That one upload dual-writes the file as an
      // AttachType.INVOICE ticket attachment, so it satisfies the close
      // gate's invoiceOnFile check AND creates the deal owner's
      // commission in the same transaction.
      //
      // A second invoice path here would satisfy the close gate WITHOUT
      // creating the commission — the sales rep would silently lose it.
      // That is why this is not simply re-gated to a role the backend
      // permits: the control that used to live here was gated isAccount
      // and 403'd for real, and only ever looked functional because
      // mockApi.js had no authz on attachments at all.
      //
      // Issue #389 fixed the OTHER half of that gate — account can now
      // READ every deal document (it is asked to confirm money against
      // them) and hr can no longer read any — but the WRITE side was
      // left deliberately narrow for exactly the reason above:
      // TicketAccessPolicy.canManageDocuments is participant OR
      // sales_manager/ceo, never account. Pinned by
      // AttachmentTicketAccessIntegrationTest
      // .accountCannotUploadADocument_theTaxInvoiceKeepsExactlyOneEntryPoint.
      // Do not reintroduce it — the frontend regression guard is
      // TicketDetailPage.test.jsx, "offers NO ใบกำกับภาษี upload
      // control in เอกสาร".
      actions={canUpload && (
        <label className="cursor-pointer mobile:w-full" htmlFor="ticket-attachment-file">
          <input
            id="ticket-attachment-file"
            type="file"
            // See FileUploadField: styles.css now loads into @layer legacy
            // (before Tailwind's utilities layer), so these utilities win
            // over the legacy global `input` rules without `!` overrides.
            className="sr-only h-px min-h-0 w-px border-0 p-0"
            onChange={onUploadAttachment}
            accept=".pdf,.doc,.docx,.xls,.xlsx,.png,.jpg,.jpeg"
          />
          {/* Not a <Button>: this <span> is the visible face of the <label>
              above, which forwards clicks to the hidden file input by
              htmlFor. Nesting a real <button> inside that label would add a
              second interactive control competing for the same click. */}
          <span
            className={cn(
              buttonVariants({ variant: 'secondary' }),
              'gap-1 px-2.5 py-1 text-xs mobile:min-h-11 mobile:w-full',
            )}
          >
            <Icon name="upload" size={13} />
            {uploadingFile ? 'กำลังอัปโหลด…' : 'แนบไฟล์ (PDF/JPG/PNG/Excel)'}
          </span>
        </label>
      )}
    >
      {attachLoading ? (
        <div
          style={{ padding: '8px 18px', display: 'flex', flexDirection: 'column', gap: 6 }}
          aria-busy="true"
          aria-label="กำลังโหลดไฟล์แนบ"
        >
          {[0, 1, 2].map((i) => (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', background: 'var(--color-surface-muted)', borderRadius: 6, border: '1px solid var(--color-border-subtle)' }}>
              <Skeleton width={13} height={13} radius="var(--radius-sm)" />
              <Skeleton width="50%" height={13} />
              <Skeleton width={40} height={16} radius="var(--radius-pill)" />
            </div>
          ))}
        </div>
      ) : attachments.length === 0 ? (
        <div style={{ padding: '4px 18px 14px' }}>
          <EmptyState icon="paperclip" title="ยังไม่มีไฟล์แนบ" description="แนบ PO หรือใบเซ็นได้ด้วยปุ่มด้านบน" />
        </div>
      ) : (
        <div style={{ padding: '8px 18px', display: 'flex', flexDirection: 'column', gap: 6 }}>
          {attachments.map((att) => (
            <div key={att.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', background: 'var(--color-surface-muted)', borderRadius: 6, border: '1px solid var(--color-border-subtle)' }}>
              <Icon name="paperclip" size={13} style={{ color: 'var(--color-text-muted)', flexShrink: 0 }} />
              <span style={{ flex: 1, fontSize: 13, color: 'var(--color-text)', wordBreak: 'break-all' }}>{att.fileName}</span>
              <span style={{ fontSize: 11, color: 'var(--color-text-muted)', whiteSpace: 'nowrap', background: 'var(--color-surface-subtle)', padding: '1px 6px', borderRadius: 99 }}>
                {att.attachType}
              </span>
              <a href={api.attachments.fileUrl(att.id)} target="_blank" rel="noreferrer"
                style={{ fontSize: 12, color: 'var(--color-link)', textDecoration: 'none', whiteSpace: 'nowrap' }}>
                ดูไฟล์
              </a>
              {notTerminal && (canManageDocuments || att.uploadedBy === user.id) && (
                <Button
                  variant="icon"
                  className="shrink-0 text-danger"
                  // Named from the file it deletes rather than left bare: this was
                  // the one icon control #537 could not convert, because Button
                  // warns without an accessible name and inventing one would have
                  // been a guess. The handler already carries att.fileName, so the
                  // name is derived, not invented.
                  aria-label={`ลบไฟล์แนบ ${att.fileName}`}
                  title={`ลบไฟล์แนบ ${att.fileName}`}
                  onClick={() => onDeleteAttachment(att.id, att.fileName)}
                >
                  <Icon name="close" size={13} />
                </Button>
              )}
            </div>
          ))}
        </div>
      )}
    </Panel>
  );
}
