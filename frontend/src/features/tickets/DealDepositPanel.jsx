import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Modal } from '../../components/common/Modal.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { depositPolicyLabel } from '../../utils/format.js';
import { downloadBlob } from '../../utils/download.js';
import { canCreateDepositNoticeFromQuotation } from '../pricingRequests/pricingRequestMeta.js';

const POLICY_OPTIONS = ['WAIVED', 'NOT_REQUIRED', 'CREDIT_CUSTOMER'];

// A policy other than the default REQUIRED means no deposit notice/payment is
// ever expected — mirrors DealStagePanel's own depositBypassesNotice (moved
// out of there along with the control, kept here as the single definition).
function bypassesNotice(policy) {
  return ['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'].includes(policy);
}

const STEP_ROLE_TH = { sales: 'ฝ่ายขาย', account: 'ฝ่ายบัญชี', ceo: 'CEO' };

function StepRoleTag({ owners, viewerRole }) {
  const mine = owners.includes(viewerRole);
  return (
    <span className={`rounded-full px-2 py-0.5 text-2xs font-bold ${
      mine ? 'bg-info-bg text-info' : 'bg-surface-subtle text-text-muted'
    }`}>
      {owners.map((o) => STEP_ROLE_TH[o] ?? o).join('/')}
    </span>
  );
}

function StepNumber({ no }) {
  return (
    <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-info text-2xs font-extrabold text-surface">
      {no}
    </span>
  );
}

// Most recently created pricing request that has actually reached order
// confirmation — the only status createDepositNoticeFromQuotation's own gate
// (canCreateDepositNoticeFromQuotation) accepts. Mirrors DealQuotationPanel's
// pickRelevantPricingRequest, narrowed to the one status this step cares about.
function pickAcceptedPricingRequest(pricingRequests = []) {
  return pricingRequests
    .filter((pr) => pr.status === 'QUOTATION_ACCEPTED')
    .sort((a, b) => b.id - a.id)[0] ?? null;
}

/**
 * "มัดจำ" (Phase 3 Slice S3 — see
 * docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md): one
 * role-shaped section walking the deposit lifecycle as three ordered,
 * explicitly-owned steps —
 *   1. นโยบายมัดจำ (account/CEO): required/waived/not-required/credit.
 *   2. ใบแจ้งยอดมัดจำ (sales): create the draft, then issue/preview/download.
 *   3. รับชำระมัดจำ (account): confirm payment once the notice is issued.
 *
 * Replaces the deposit-policy control that lived in DealStagePanel and the
 * scattered "ออกใบแจ้งยอดมัดจำ"/"ดูใบแจ้งยอดมัดจำ"/"ยืนยันรับมัดจำ" bits that
 * lived directly in TicketDetailPage. Every mutation here reuses an existing
 * hrApi method verbatim (tickets.setDepositPolicy/confirmDepositPaid,
 * pricingRequests.createDepositNoticeFromQuotation, depositNotices.*) — none
 * of that surface changed shape or gate for this slice.
 *
 * Full line-item editing of the notice itself (customer info, item table,
 * notes) stays on DepositNoticePage, linked from step 2 — duplicating that
 * ~300-line editable form here would be exactly the two-implementations risk
 * DealQuotationPanel's own doc comment already warns against for the
 * customer-quotation editor.
 *
 * Known overlap (documented, not fixed here — see the handoff's "Decisions
 * Made"): DealQuotationPanel (Phase 2 Slice S2, out of scope to edit in this
 * slice) already exposes its own "สร้างใบแจ้งยอดเงินรับมัดจำ" control for the
 * exact same canCreateDepositNoticeFromQuotation(user, pr) condition, inside
 * its own "ยืนยันคำสั่งซื้อ..." box. Both panels can show that specific
 * create action at once until a notice exists for this deal.
 */
export function DealDepositPanel({ user, ticketId, summary, availableActions = [], pricingRequests = [], showToast }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const role = user?.role;
  const isOwner = user?.id === summary?.createdById;
  const isSales = ROLE_PERMISSIONS.canCreateTickets.includes(role);
  const isAccount = ROLE_PERMISSIONS.canConfirmPayments.includes(role);

  const hasAction = (action) => availableActions.some((item) => item.action === action);

  const [policyOpen, setPolicyOpen] = useState(false);
  const [policyValue, setPolicyValue] = useState('WAIVED');
  const [policyReason, setPolicyReason] = useState('');
  const [depositPercentInput, setDepositPercentInput] = useState('0.5');
  const [downloadingFormat, setDownloadingFormat] = useState(null);
  const [previewHtml, setPreviewHtml] = useState('');

  const st = summary?.status;
  const ps = summary?.paymentStatus;
  const policy = summary?.depositPolicy ?? 'REQUIRED';
  const skipsNotice = bypassesNotice(policy);
  const policyLabel = depositPolicyLabel(policy);

  const pr = useMemo(() => pickAcceptedPricingRequest(pricingRequests), [pricingRequests]);

  const depositNoticesQuery = useQuery({
    queryKey: queryKeys.depositNotices(ticketId),
    queryFn: () => api.depositNotices.listByTicket(ticketId).then((r) => r.depositNotices ?? []),
    enabled: !!ticketId,
  });
  const doc = useMemo(() => {
    const docs = depositNoticesQuery.data ?? [];
    const draft = docs.find((d) => d.status === 'DRAFT');
    const latestIssued = [...docs]
      .filter((d) => d.status === 'ISSUED')
      .sort((a, b) => (b.version ?? 0) - (a.version ?? 0))[0];
    return draft ?? latestIssued ?? null;
  }, [depositNoticesQuery.data]);

  function invalidateAfterDepositChange() {
    queryClient.invalidateQueries({ queryKey: queryKeys.depositNotices(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketPayments(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
  }

  const setPolicyMutation = useMutation({
    mutationFn: (payload) => api.tickets.setDepositPolicy(ticketId, payload),
    onSuccess: () => {
      showToast?.('success', 'บันทึกนโยบายมัดจำแล้ว');
      setPolicyOpen(false);
      setPolicyReason('');
      invalidateAfterDepositChange();
    },
    onError: (err) => showToast?.('error', err.message || 'บันทึกไม่สำเร็จ'),
  });

  const createNoticeMutation = useMutation({
    mutationFn: () => api.pricingRequests.createDepositNoticeFromQuotation(pr.id, {
      depositPercent: depositPercentInput === '' ? null : Number(depositPercentInput),
    }),
    onSuccess: () => {
      showToast?.('success', 'สร้างร่างใบแจ้งยอดเงินรับมัดจำแล้ว');
      invalidateAfterDepositChange();
      navigate(`/tickets/${ticketId}/deposit`);
    },
    onError: (err) => showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ'),
  });

  const issueMutation = useMutation({
    mutationFn: () => api.depositNotices.issue(doc.id),
    onSuccess: (res) => {
      showToast?.('success', `ออกเอกสาร ${res.depositNotice.docNumber} แล้ว`);
      setPreviewHtml('');
      invalidateAfterDepositChange();
    },
    onError: (err) => showToast?.('error', err.message || 'ออกเอกสารไม่สำเร็จ'),
  });

  const previewMutation = useMutation({
    mutationFn: () => api.depositNotices.preview(doc.id),
    onSuccess: (html) => setPreviewHtml(html),
    onError: (err) => showToast?.('error', err.message || 'Preview ไม่สำเร็จ'),
  });

  const confirmPaidMutation = useMutation({
    mutationFn: () => api.tickets.confirmDepositPaid(ticketId),
    onSuccess: () => {
      showToast?.('success', 'ยืนยันรับมัดจำแล้ว');
      invalidateAfterDepositChange();
    },
    onError: (err) => showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ'),
  });

  async function handleDownload(format) {
    if (!doc) return;
    setDownloadingFormat(format);
    try {
      const blob = format === 'pdf' ? await api.depositNotices.downloadPdf(doc.id) : await api.depositNotices.downloadXlsx(doc.id);
      downloadBlob(blob, doc.docNumber ?? 'deposit-notice', format);
    } catch (err) {
      showToast?.('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloadingFormat(null);
    }
  }

  function openPolicyModal() {
    setPolicyValue(POLICY_OPTIONS.includes(policy) ? policy : 'WAIVED');
    setPolicyReason('');
    setPolicyOpen(true);
  }

  const canSetPolicy = hasAction('WAIVE_DEPOSIT');
  const canCreateNotice = pr != null && canCreateDepositNoticeFromQuotation(user, pr);
  const canManageThisNotice = isSales && isOwner;
  // Legacy dual-track creation path (ticket.confirmCustomer → ISSUE_DEPOSIT_NOTICE),
  // independent of the PricingRequest/CustomerQuotation chain above — mirrors the
  // old TicketDetailPage `can.generateDocument` gate verbatim.
  const legacyNoticeEligible = !doc && !canCreateNotice
    && hasAction('ISSUE_DEPOSIT_NOTICE') && st === 'quotation_issued' && ps === 'CUSTOMER_CONFIRMED'
    && canManageThisNotice;
  const canConfirmPaid = hasAction('DEPOSIT_PAID') && st === 'quotation_issued' && ps === 'DEPOSIT_NOTICE_ISSUED' && isAccount;
  const alreadyPaid = ['DEPOSIT_PAID', 'AWAITING_FINAL_PAYMENT', 'FULLY_PAID'].includes(ps);

  return (
    <section className="table-panel">
      <div className="panel-header">
        <h2>มัดจำ</h2>
      </div>

      <div className="flex flex-col gap-3 p-4">
        {/* Step 1: นโยบายมัดจำ */}
        <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <StepNumber no={1} />
              <strong className="text-sm">นโยบายมัดจำ</strong>
              <StepRoleTag owners={['account', 'ceo']} viewerRole={role} />
            </div>
            <StatusBadge tone={policyLabel.tone}>{policyLabel.label}</StatusBadge>
          </div>
          {summary?.depositPolicyReason ? (
            <p className="text-xs text-text-muted">เหตุผล: {summary.depositPolicyReason}</p>
          ) : null}
          {canSetPolicy ? (
            <button type="button" className="secondary-button self-start" onClick={openPolicyModal}>
              เปลี่ยนนโยบายมัดจำ…
            </button>
          ) : null}
        </div>

        {/* Step 2: ใบแจ้งยอดมัดจำ */}
        <div className={`flex flex-col gap-2 rounded-md border border-border bg-surface p-3 ${skipsNotice ? 'opacity-60' : ''}`}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <StepNumber no={2} />
              <strong className="text-sm">ใบแจ้งยอดมัดจำ</strong>
              <StepRoleTag owners={['sales']} viewerRole={role} />
            </div>
            {doc ? (
              <StatusBadge tone={doc.status === 'ISSUED' ? 'success' : 'neutral'}>
                {doc.status === 'ISSUED' ? 'ออกแล้ว' : 'ฉบับร่าง'}
              </StatusBadge>
            ) : null}
          </div>

          {skipsNotice ? (
            <p className="text-xs text-text-muted">
              ข้ามขั้นตอนนี้ — นโยบายมัดจำคือ &quot;{policyLabel.label}&quot;
              {summary?.depositPolicyReason ? ` (${summary.depositPolicyReason})` : ''}
            </p>
          ) : depositNoticesQuery.isLoading ? (
            <p className="text-sm text-text-muted">กำลังโหลด...</p>
          ) : doc ? (
            <>
              <div className="flex flex-wrap items-center gap-2 text-xs text-text-muted">
                {doc.docNumber ? (
                  <span className="font-mono text-sm font-bold text-text">{doc.docNumber}</span>
                ) : (
                  <span>ฉบับร่าง — ยังไม่ออกเลขที่เอกสาร</span>
                )}
                <span>มัดจำ {Math.round(Number(doc.depositPercent ?? 0.5) * 100)}%</span>
              </div>
              <div className="flex flex-wrap gap-2">
                <button type="button" className="secondary-button" disabled={previewMutation.isPending}
                  onClick={() => previewMutation.mutate()}>
                  {previewMutation.isPending ? 'กำลังโหลด...' : 'ตัวอย่าง'}
                </button>
                {doc.status === 'ISSUED' ? (
                  <>
                    <button type="button" className="secondary-button" disabled={downloadingFormat === 'pdf'} onClick={() => handleDownload('pdf')}>
                      {downloadingFormat === 'pdf' ? 'กำลังดาวน์โหลด...' : 'PDF'}
                    </button>
                    <button type="button" className="secondary-button" disabled={downloadingFormat === 'xlsx'} onClick={() => handleDownload('xlsx')}>
                      {downloadingFormat === 'xlsx' ? 'กำลังดาวน์โหลด...' : 'Excel'}
                    </button>
                  </>
                ) : canManageThisNotice ? (
                  <button type="button" className="primary-button" disabled={issueMutation.isPending}
                    onClick={() => issueMutation.mutate()}>
                    ออกเอกสาร
                  </button>
                ) : null}
              </div>
              {previewHtml ? (
                <div className="flex flex-col gap-1">
                  <div className="flex items-center justify-between">
                    <span className="text-2xs font-bold text-text-muted">ตัวอย่างเอกสาร</span>
                    <button type="button" className="icon-button" onClick={() => setPreviewHtml('')} aria-label="ปิดตัวอย่าง">×</button>
                  </div>
                  <iframe srcDoc={previewHtml} title="Deposit notice preview"
                    className="h-64 w-full rounded border border-border-subtle" />
                </div>
              ) : null}
            </>
          ) : canCreateNotice ? (
            <div className="flex flex-col gap-2">
              <p className="text-xs text-text-muted">ลูกค้ายืนยันคำสั่งซื้อแล้ว — สร้างใบแจ้งยอดเงินรับมัดจำจากใบเสนอราคาที่ยอมรับได้เลย</p>
              <label className="flex items-center gap-2 text-sm">
                % มัดจำ
                <input type="number" min="0" max="1" step="0.05" className="w-24 rounded border border-border p-1 text-sm"
                  value={depositPercentInput} onChange={(e) => setDepositPercentInput(e.target.value)} />
              </label>
              <button type="button" className="primary-button self-start" disabled={createNoticeMutation.isPending}
                onClick={() => createNoticeMutation.mutate()}>
                สร้างใบแจ้งยอดเงินรับมัดจำ
              </button>
            </div>
          ) : legacyNoticeEligible ? (
            <div className="flex flex-col gap-2">
              <p className="text-xs text-text-muted">ลูกค้ายืนยันคำสั่งซื้อแล้ว — ออกใบแจ้งยอดมัดจำได้</p>
              <Link to={`/tickets/${ticketId}/deposit`} className="primary-button self-start">
                ออกใบแจ้งยอดมัดจำ
              </Link>
            </div>
          ) : (
            <p className="text-xs text-text-muted">ยังไม่ถึงขั้นตอนออกใบแจ้งยอดมัดจำ</p>
          )}

          {!skipsNotice ? (
            <Link to={`/tickets/${ticketId}/deposit`} className="self-start text-xs font-bold text-link">
              ไปที่ใบแจ้งยอดเงินรับมัดจำ (แก้ไขรายการแบบเต็ม) →
            </Link>
          ) : null}
        </div>

        {/* Step 3: รับชำระมัดจำ */}
        <div className={`flex flex-col gap-2 rounded-md border border-border bg-surface p-3 ${skipsNotice ? 'opacity-60' : ''}`}>
          <div className="flex items-center gap-2">
            <StepNumber no={3} />
            <strong className="text-sm">รับชำระมัดจำ</strong>
            <StepRoleTag owners={['account', 'ceo']} viewerRole={role} />
          </div>
          {skipsNotice ? (
            <p className="text-xs text-text-muted">
              ข้ามขั้นตอนนี้ — นโยบายมัดจำคือ &quot;{policyLabel.label}&quot;
            </p>
          ) : canConfirmPaid ? (
            <button type="button" className="primary-button self-start" disabled={confirmPaidMutation.isPending}
              onClick={() => confirmPaidMutation.mutate()}>
              ยืนยันรับมัดจำ
            </button>
          ) : alreadyPaid ? (
            <StatusBadge tone="success">รับมัดจำแล้ว</StatusBadge>
          ) : (
            <p className="text-xs text-text-muted">รอออกใบแจ้งยอดมัดจำก่อน</p>
          )}
        </div>
      </div>

      {policyOpen ? (
        <Modal
          title="นโยบายมัดจำ"
          onClose={() => setPolicyOpen(false)}
          footer={(
            <>
              <button type="button" className="secondary-button" onClick={() => setPolicyOpen(false)}>ยกเลิก</button>
              <button type="button" className="primary-button" disabled={setPolicyMutation.isPending || !policyReason.trim()}
                onClick={() => setPolicyMutation.mutate({ policy: policyValue, reason: policyReason.trim() })}>
                บันทึก
              </button>
            </>
          )}
        >
          <div className="flex flex-col gap-3">
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              นโยบาย
              <select value={policyValue} onChange={(e) => setPolicyValue(e.target.value)}>
                {POLICY_OPTIONS.map((value) => (
                  <option key={value} value={value}>{depositPolicyLabel(value).label}</option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              เหตุผล *
              <textarea className="min-h-20" value={policyReason} onChange={(e) => setPolicyReason(e.target.value)} />
            </label>
          </div>
        </Modal>
      ) : null}
    </section>
  );
}
