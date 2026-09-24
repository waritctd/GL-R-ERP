import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { FactoryProgressBar } from '../importProgress/FactoryProgressBar.jsx';

const STATUS_LABEL = { DRAFT: 'ร่าง', ISSUED: 'ออกเลขแล้ว', SUPERSEDED: 'ถูกแทนที่แล้ว' };
const STATUS_TONE = { DRAFT: 'blue', ISSUED: 'green', SUPERSEDED: 'neutral' };

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/**
 * One STORED ใบขอซื้อ row (V184, per factory) — draft or issued — as a self-contained card.
 * Used by DealFulfilmentPanel's "ใบขอซื้อรายโรงงาน" section (PR-B).
 *
 * Role shape (mirrors ImportRequestService — see its own Javadoc for the authoritative matrix):
 *   - `canFullWrite` (owning sales rep or CEO): edit the draft body/lead-time, issue, revise, delete.
 *   - `canFooterWrite` (CEO only): Checked/Approved By+date, vessel ETA.
 *   - `canAdvance` (import/CEO): advance the per-factory step, set lead time AFTER issue.
 *   - `canEmailWrite` (owning rep, CEO, or import): edit/mark-sent the order-email draft.
 * Everyone who can reach this card at all may read it and download the PDF — the buttons above
 * are an affordance; the server is the authority, so a 403/409 surfaces its own Thai message.
 */
export function ImportRequestFactoryCard({
  row, ticketId, canFullWrite, canFooterWrite, canAdvance, canEmailWrite, showToast,
}) {
  const queryClient = useQueryClient();
  const [leadTimeDraft, setLeadTimeDraft] = useState({ min: row.leadTimeMinDays ?? '', max: row.leadTimeMaxDays ?? '' });
  const [requiredByDraft, setRequiredByDraft] = useState(row.requiredByNote ?? '');
  const [emailOpen, setEmailOpen] = useState(false);
  // PR-B REVIEW ROUND 1, B1: field names MUST be emailTo/emailSubject/emailBody — Java's
  // UpdateEmailDraftRequest (ImportRequestRequests.java) has no `to`/`subject`/`body` components,
  // so those were silently dropped by Jackson (unknown properties ignored) and the update's own
  // COALESCE(:field, existing) kept the OLD value with a 200 OK and no error anywhere.
  const [emailDraft, setEmailDraft] = useState({ emailTo: row.emailTo ?? '', emailSubject: row.emailSubject ?? '', emailBody: row.emailBody ?? '' });
  const [footerOpen, setFooterOpen] = useState(false);
  const [footerDraft, setFooterDraft] = useState({
    vesselEtaNote: row.vesselEtaNote ?? '', checkedByName: row.checkedByName ?? '', checkedDate: row.checkedDate ?? '',
    approvedByName: row.approvedByName ?? '', approvedDate: row.approvedDate ?? '',
  });
  // Nit: window.confirm() replaced with the app's own ConfirmDialog pattern (see that
  // component's own Javadoc-style header — "Replaces window.confirm/window.prompt call sites").
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: queryKeys.storedImportRequests(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
    queryClient.invalidateQueries({ queryKey: queryKeys.dashboardSummary() });
    queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
  }
  function onError(err) {
    showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ');
  }

  const updateBodyMutation = useMutation({
    mutationFn: (payload) => api.storedImportRequests.update(row.id, payload),
    onSuccess: () => { showToast?.('success', 'บันทึกแล้ว'); invalidate(); },
    onError,
  });
  const issueMutation = useMutation({
    mutationFn: () => api.storedImportRequests.issue(row.id, {}),
    onSuccess: () => { showToast?.('success', `ออกเลขใบขอซื้อโรงงาน ${row.factoryName} แล้ว`); invalidate(); },
    onError,
  });
  const reviseMutation = useMutation({
    mutationFn: () => api.storedImportRequests.revise(row.id),
    onSuccess: () => { showToast?.('success', 'สร้างฉบับแก้ไขแล้ว'); invalidate(); },
    onError,
  });
  const deleteMutation = useMutation({
    mutationFn: () => api.storedImportRequests.deleteDraft(row.id),
    onSuccess: () => { showToast?.('success', 'ลบร่างแล้ว'); setDeleteConfirmOpen(false); invalidate(); },
    onError,
  });
  const advanceMutation = useMutation({
    mutationFn: (targetStep) => api.storedImportRequests.advanceStep(row.id, { targetStep }),
    onSuccess: () => { showToast?.('success', 'อัปเดตสถานะรายโรงงานแล้ว'); invalidate(); },
    onError,
  });
  const setLeadTimeMutation = useMutation({
    mutationFn: (payload) => (row.status === 'DRAFT'
      ? api.storedImportRequests.update(row.id, payload)
      : api.storedImportRequests.setLeadTime(row.id, { leadTimeMinDays: payload.leadTimeMinDays, leadTimeMaxDays: payload.leadTimeMaxDays })),
    onSuccess: () => { showToast?.('success', 'บันทึกระยะเวลานำเข้าแล้ว'); invalidate(); },
    onError,
  });
  const updateEmailMutation = useMutation({
    mutationFn: (payload) => api.storedImportRequests.updateEmailDraft(row.id, payload),
    onSuccess: () => { showToast?.('success', 'บันทึกอีเมลแล้ว'); setEmailOpen(false); invalidate(); },
    onError,
  });
  const markEmailSentMutation = useMutation({
    mutationFn: () => api.storedImportRequests.markEmailSent(row.id),
    onSuccess: () => { showToast?.('success', 'บันทึกว่าส่งอีเมลแล้ว'); invalidate(); },
    onError,
  });
  const updateFooterMutation = useMutation({
    mutationFn: (payload) => api.storedImportRequests.update(row.id, payload),
    onSuccess: () => { showToast?.('success', 'บันทึกแล้ว'); setFooterOpen(false); invalidate(); },
    onError,
  });
  const downloadMutation = useMutation({
    mutationFn: (copy) => api.storedImportRequests.download(row.id, copy).then((blob) => ({ blob, copy })),
    onSuccess: ({ blob, copy }) => downloadBlob(blob, `IR-${row.docNumber ?? `draft-${row.id}`}-${row.factoryName}${copy === 'factory' ? '' : '-internal'}.pdf`),
    onError,
  });

  const saveLeadTime = () => {
    const min = leadTimeDraft.min === '' ? null : Number(leadTimeDraft.min);
    const max = leadTimeDraft.max === '' ? null : Number(leadTimeDraft.max);
    if ((min == null) !== (max == null)) {
      showToast?.('error', 'ต้องระบุระยะเวลานำเข้าทั้งค่าต่ำสุดและสูงสุดพร้อมกัน');
      return;
    }
    // Nit: a blank save must surface a VALIDATION message, not silently succeed with a "saved"
    // toast — ISSUED rows go through setLeadTime, whose Java shape (SetLeadTimeRequest) requires
    // BOTH fields non-null (@NotNull @Min(1)); a DRAFT row's own update() tolerates null (clears
    // it), so this guard only blocks the case that would otherwise 400.
    if (min == null && max == null && row.status !== 'DRAFT') {
      showToast?.('error', 'กรุณาระบุระยะเวลานำเข้า (วัน) ทั้งค่าต่ำสุดและสูงสุด');
      return;
    }
    // Nit: both branches of this used to build the identical payload — the actual DRAFT/ISSUED
    // branching already lives in setLeadTimeMutation's own mutationFn (update() vs setLeadTime()),
    // so restating it here as a ternary with two identical arms was dead branching.
    setLeadTimeMutation.mutate({ leadTimeMinDays: min, leadTimeMaxDays: max });
  };

  const isDraft = row.status === 'DRAFT';
  const isIssued = row.status === 'ISSUED';

  return (
    <div className="flex flex-col gap-2.5 rounded-md border border-border bg-surface p-3" data-testid={`ir-factory-card-${row.id}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <strong className="min-w-0 truncate text-sm">{row.factoryName}</strong>
          <span className="text-2xs text-text-muted">v{row.version}</span>
          <StatusBadge tone={STATUS_TONE[row.status]}>{STATUS_LABEL[row.status] ?? row.status}</StatusBadge>
          {row.docNumber ? <code className="text-2xs text-text-muted">{row.docNumber}</code> : null}
        </div>
        <div className="flex flex-wrap gap-1.5">
          {isDraft && canFullWrite ? (
            <Button type="button" size="sm" variant="primary" disabled={issueMutation.isPending}
              onClick={() => issueMutation.mutate()} data-testid={`ir-issue-${row.id}`}>
              ออกเลข
            </Button>
          ) : null}
          {isDraft && canFullWrite ? (
            <Button type="button" size="sm" variant="danger" disabled={deleteMutation.isPending}
              onClick={() => setDeleteConfirmOpen(true)} data-testid={`ir-delete-${row.id}`}>
              ลบร่าง
            </Button>
          ) : null}
          {isIssued && canFullWrite && row.importStep !== 'RECEIVED' ? (
            <Button type="button" size="sm" variant="secondary" disabled={reviseMutation.isPending}
              onClick={() => reviseMutation.mutate()} data-testid={`ir-revise-${row.id}`}>
              ออกฉบับแก้ไข
            </Button>
          ) : null}
          <Button type="button" size="sm" variant="secondary" disabled={downloadMutation.isPending}
            onClick={() => downloadMutation.mutate(undefined)} data-testid={`ir-download-internal-${row.id}`}>
            PDF (ภายใน)
          </Button>
          <Button type="button" size="sm" variant="secondary" disabled={downloadMutation.isPending}
            onClick={() => downloadMutation.mutate('factory')} data-testid={`ir-download-factory-${row.id}`}>
            PDF (ให้โรงงาน)
          </Button>
        </div>
      </div>

      {/* เนื้อหา (draft body): lead time + required-by, editable by the owning rep/CEO only while
          DRAFT. Post-issue the min/max here is READ-ONLY — the edit control below (import/CEO)
          is the one that still writes.
          PR-B REVIEW ROUND 1, S1: a viewer who cannot write (sales_manager always; sales/CEO once
          ISSUED; import while still DRAFT) gets PLAIN TEXT here, not a disabled input — a disabled
          input is a look-alike control that implies "there is a field here you almost can edit",
          which is misleading for a role with no path to ever edit it. */}
      {(() => {
        const canWriteLeadTimeNow = isDraft ? canFullWrite : canAdvance;
        if (!canWriteLeadTimeNow) {
          return (
            <div className="flex flex-wrap items-center gap-3 text-xs" data-testid={`ir-lead-readonly-${row.id}`}>
              <span className="text-text-secondary">
                {row.leadTimeMinDays != null && row.leadTimeMaxDays != null
                  ? `ระยะเวลานำเข้า ${row.leadTimeMinDays}–${row.leadTimeMaxDays} วัน`
                  : 'ยังไม่ระบุระยะเวลานำเข้า'}
              </span>
              {row.expectedArrivalFrom && row.expectedArrivalTo ? (
                <span className="text-text-muted">
                  คาดว่าถึง {formatThaiDate(row.expectedArrivalFrom)} – {formatThaiDate(row.expectedArrivalTo)}
                </span>
              ) : null}
            </div>
          );
        }
        return (
          <div className="flex flex-wrap items-end gap-3 text-xs">
            <label className="flex flex-col gap-1 font-bold text-text-secondary">
              ระยะเวลานำเข้า (วัน) ต่ำสุด
              <input type="number" min="1" max="365" className="w-24"
                value={leadTimeDraft.min}
                onChange={(e) => setLeadTimeDraft((d) => ({ ...d, min: e.target.value }))}
                data-testid={`ir-lead-min-${row.id}`} />
            </label>
            <label className="flex flex-col gap-1 font-bold text-text-secondary">
              สูงสุด
              <input type="number" min="1" max="365" className="w-24"
                value={leadTimeDraft.max}
                onChange={(e) => setLeadTimeDraft((d) => ({ ...d, max: e.target.value }))}
                data-testid={`ir-lead-max-${row.id}`} />
            </label>
            <Button type="button" size="sm" variant="secondary" disabled={setLeadTimeMutation.isPending}
              onClick={saveLeadTime} data-testid={`ir-lead-save-${row.id}`}>
              บันทึกระยะเวลา
            </Button>
            {row.expectedArrivalFrom && row.expectedArrivalTo ? (
              <span className="text-text-muted">
                คาดว่าถึง {formatThaiDate(row.expectedArrivalFrom)} – {formatThaiDate(row.expectedArrivalTo)}
              </span>
            ) : null}
          </div>
        );
      })()}

      {isDraft && canFullWrite ? (
        <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
          กำหนดวันที่ต้องการของ (ฉบับนี้)
          <input type="text" value={requiredByDraft}
            onChange={(e) => setRequiredByDraft(e.target.value)}
            onBlur={() => { if (requiredByDraft !== (row.requiredByNote ?? '')) updateBodyMutation.mutate({ requiredByNote: requiredByDraft }); }}
            placeholder="เช่น Within 21/5/26"
            data-testid={`ir-required-by-${row.id}`} />
        </label>
      ) : row.requiredByNote ? (
        <p className="text-2xs text-text-muted">กำหนดวันที่ต้องการของ: {row.requiredByNote}</p>
      ) : null}

      <div className="flex flex-col gap-1 text-2xs text-text-muted">
        {row.items.map((it) => (
          <div key={it.id} className="flex flex-wrap items-baseline gap-x-2">
            <strong className="text-text">{it.code}</strong>
            {it.size ? <span>{it.size}</span> : null}
            <span>{Number(it.qty).toLocaleString('en-US')} {it.unit ?? ''}</span>
            {it.note ? <span>· {it.note}</span> : null}
          </div>
        ))}
      </div>

      {isIssued ? (
        <FactoryProgressBar
          row={{ id: row.id, factoryName: row.factoryName, importStep: row.importStep, importStepAt: row.importStepAt }}
          editable={canAdvance}
          advancing={advanceMutation.isPending}
          onAdvance={(_r, targetStep) => advanceMutation.mutate(targetStep)}
        />
      ) : null}

      {isIssued ? (
        <div className="flex flex-wrap items-center gap-2 rounded-md border border-border-subtle bg-surface-subtle p-2 text-xs">
          <strong>อีเมลสั่งซื้อ:</strong>
          {row.emailSentAt ? (
            <StatusBadge tone="green">ส่งแล้ว {formatThaiDate(row.emailSentAt)}{row.emailSentByName ? ` · ${row.emailSentByName}` : ''}</StatusBadge>
          ) : (
            <StatusBadge tone="blue">ยังไม่ได้ส่ง</StatusBadge>
          )}
          {canEmailWrite ? (
            <Button type="button" size="sm" variant="text"
              onClick={() => { setEmailDraft({ emailTo: row.emailTo ?? '', emailSubject: row.emailSubject ?? '', emailBody: row.emailBody ?? '' }); setEmailOpen(true); }}
              data-testid={`ir-email-open-${row.id}`}>
              ดู/แก้ไขอีเมล
            </Button>
          ) : null}
        </div>
      ) : null}

      {/* Nit: the CEO-only footer (checked/approved/vessel ETA) is editable from DRAFT onward —
          not gated on isIssued — and its current status stays visible READ-ONLY to every other
          viewer who can reach this card, at any status, rather than only once ISSUED and only the
          vessel ETA line. */}
      {canFooterWrite ? (
        <div className="flex flex-wrap items-center gap-2 rounded-md border border-border-subtle bg-surface-subtle p-2 text-xs">
          <strong>ท้ายฟอร์ม (CEO):</strong>
          <span className="text-text-muted">
            {row.checkedByName ? `ตรวจสอบโดย ${row.checkedByName}${row.checkedDate ? ` (${formatThaiDate(row.checkedDate)})` : ''}` : 'ยังไม่ตรวจสอบ'}
            {' · '}
            {row.approvedByName ? `อนุมัติโดย ${row.approvedByName}${row.approvedDate ? ` (${formatThaiDate(row.approvedDate)})` : ''}` : 'ยังไม่อนุมัติ'}
            {row.vesselEtaNote ? ` · กำหนดเรือเข้าโดยประมาณ: ${row.vesselEtaNote}` : ''}
          </span>
          <Button type="button" size="sm" variant="text" onClick={() => setFooterOpen(true)} data-testid={`ir-footer-open-${row.id}`}>
            แก้ไข
          </Button>
        </div>
      ) : (row.checkedByName || row.approvedByName || row.vesselEtaNote) ? (
        <p className="text-2xs text-text-muted" data-testid={`ir-footer-readonly-${row.id}`}>
          {row.checkedByName ? `ตรวจสอบโดย ${row.checkedByName}${row.checkedDate ? ` (${formatThaiDate(row.checkedDate)})` : ''}` : null}
          {row.checkedByName && row.approvedByName ? ' · ' : ''}
          {row.approvedByName ? `อนุมัติโดย ${row.approvedByName}${row.approvedDate ? ` (${formatThaiDate(row.approvedDate)})` : ''}` : null}
          {(row.checkedByName || row.approvedByName) && row.vesselEtaNote ? ' · ' : ''}
          {row.vesselEtaNote ? `กำหนดเรือเข้าโดยประมาณ: ${row.vesselEtaNote}` : null}
        </p>
      ) : null}

      {emailOpen ? (
        <Modal
          title="อีเมลสั่งซื้อ (ร่าง)"
          subtitle={row.factoryName}
          onClose={() => setEmailOpen(false)}
          testId={`ir-email-modal-${row.id}`}
          footer={(
            <>
              <Button type="button" variant="secondary" onClick={() => setEmailOpen(false)}>ปิด</Button>
              {/* Nit: hide "บันทึก" once sent — the fields are already readOnly below, but an
                  enabled save button next to them still implied an edit that PATCH would refuse
                  (ImportRequestService#updateEmailDraft 409s once emailSentAt is set). */}
              {!row.emailSentAt ? (
                <Button type="button" variant="secondary" disabled={updateEmailMutation.isPending}
                  onClick={() => updateEmailMutation.mutate(emailDraft)} data-testid={`ir-email-save-${row.id}`}>
                  บันทึก
                </Button>
              ) : null}
              <Button type="button" variant="secondary"
                onClick={async () => {
                  try { await navigator.clipboard.writeText(emailDraft.emailBody ?? ''); showToast?.('success', 'คัดลอกข้อความอีเมลแล้ว'); }
                  catch { showToast?.('error', 'คัดลอกไม่สำเร็จ — เลือกข้อความแล้วคัดลอกเอง'); }
                }}>
                คัดลอกข้อความ
              </Button>
              {!row.emailSentAt && canEmailWrite ? (
                <Button type="button" variant="primary" disabled={markEmailSentMutation.isPending}
                  onClick={() => markEmailSentMutation.mutate()} data-testid={`ir-email-mark-sent-${row.id}`}>
                  ส่งแล้ว
                </Button>
              ) : null}
            </>
          )}
        >
          <div className="grid gap-2.5">
            <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
              ถึง
              <input type="email" value={emailDraft.emailTo} disabled={Boolean(row.emailSentAt)}
                onChange={(e) => setEmailDraft((d) => ({ ...d, emailTo: e.target.value }))} />
            </label>
            <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
              หัวข้อ
              <input type="text" value={emailDraft.emailSubject} disabled={Boolean(row.emailSentAt)}
                onChange={(e) => setEmailDraft((d) => ({ ...d, emailSubject: e.target.value }))} />
            </label>
            <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
              เนื้อหา
              <textarea className="form-input min-h-64 font-mono text-xs" value={emailDraft.emailBody}
                readOnly={Boolean(row.emailSentAt)}
                onChange={(e) => setEmailDraft((d) => ({ ...d, emailBody: e.target.value }))} />
            </label>
          </div>
        </Modal>
      ) : null}

      {footerOpen ? (
        <Modal
          title="ท้ายฟอร์ม (CEO เท่านั้น)"
          subtitle={row.factoryName}
          onClose={() => setFooterOpen(false)}
          footer={(
            <>
              <Button type="button" variant="secondary" onClick={() => setFooterOpen(false)}>ยกเลิก</Button>
              <Button type="button" variant="primary" disabled={updateFooterMutation.isPending}
                onClick={() => updateFooterMutation.mutate(footerDraft)} data-testid={`ir-footer-save-${row.id}`}>
                บันทึก
              </Button>
            </>
          )}
        >
          <div className="grid gap-2.5">
            <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
              กำหนดเรือเข้าโดยประมาณ
              <input type="text" value={footerDraft.vesselEtaNote}
                onChange={(e) => setFooterDraft((d) => ({ ...d, vesselEtaNote: e.target.value }))} />
            </label>
            <div className="grid grid-cols-2 gap-2.5">
              <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
                ตรวจสอบโดย
                <input type="text" value={footerDraft.checkedByName}
                  onChange={(e) => setFooterDraft((d) => ({ ...d, checkedByName: e.target.value }))} />
              </label>
              <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
                วันที่ตรวจสอบ
                <input type="date" value={footerDraft.checkedDate ?? ''}
                  onChange={(e) => setFooterDraft((d) => ({ ...d, checkedDate: e.target.value }))} />
              </label>
            </div>
            <div className="grid grid-cols-2 gap-2.5">
              <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
                อนุมัติโดย
                <input type="text" value={footerDraft.approvedByName}
                  onChange={(e) => setFooterDraft((d) => ({ ...d, approvedByName: e.target.value }))} />
              </label>
              <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
                วันที่อนุมัติ
                <input type="date" value={footerDraft.approvedDate ?? ''}
                  onChange={(e) => setFooterDraft((d) => ({ ...d, approvedDate: e.target.value }))} />
              </label>
            </div>
          </div>
        </Modal>
      ) : null}

      <ConfirmDialog
        open={deleteConfirmOpen}
        title="ลบร่างใบขอซื้อ"
        message={`ลบร่างใบขอซื้อของโรงงาน ${row.factoryName} นี้? การลบไม่สามารถย้อนกลับได้`}
        tone="danger"
        confirmLabel="ลบร่าง"
        busy={deleteMutation.isPending}
        onConfirm={() => deleteMutation.mutate()}
        onCancel={() => setDeleteConfirmOpen(false)}
      />
    </div>
  );
}
