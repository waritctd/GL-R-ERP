import { forwardRef, useImperativeHandle, useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  dealLifecycleLabel, dealLostReasonLabel, dealStageLabel, depositPolicyLabel,
  formatThaiDate, overdueBadgeLabel, paymentStageLabel, pricingRequestStatusLabel, tenderRequirementLabel,
} from '../../utils/format.js';
import {
  activePricingRequestsSummary, PRICING_REQUEST_STATUSES, pricingRequestRecipientLabel,
} from '../pricingRequests/pricingRequestMeta.js';
import { DealStageStepper, PhaseTracker } from './DealStageStepper.jsx';
import { MarkLostModal } from './MarkLostModal.jsx';
import {
  allowedTargetStages, canMarkLost, canSetStage, GATE_LABEL, nextStage,
  PAYMENT_SUBSTEPS, PROCUREMENT_SUBSTEPS, stageMeta,
} from './stageMeta.js';
import { UpdateStageModal } from './UpdateStageModal.jsx';

function daysSince(iso) {
  if (!iso) return null;
  return Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 86400000));
}

/**
 * One labelled row of sub-status chips — the inner journey of the current
 * stage (internal pricing / payment / import). Replaces the old standalone
 * Track P / Track F steppers.
 */
function SubstepChips({ label, steps, currentCode }) {
  const currentIdx = steps.findIndex((s) => s.code === currentCode);
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <span className="text-2xs font-bold text-text-muted">{label}</span>
      {steps.map((step, i) => {
        const done = i < currentIdx;
        const current = i === currentIdx;
        return (
          <span
            key={step.code}
            className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-2xs font-bold ${
              done ? 'bg-success-bg text-success-dark'
                : current ? 'bg-info-bg text-info'
                  : 'bg-surface-subtle text-text-muted'
            }`}
          >
            {done ? <Icon name="check" size={11} /> : null}
            {step.label}
          </span>
        );
      })}
    </div>
  );
}

/**
 * Deal-level pricing-request glance strip (Fix 3 of the review-remediation
 * plan): a roll-up count line, plus one "recipient → status" line per
 * non-CANCELLED request. Deliberately compact — PricingRequestPanel's cards
 * (items, event log, actions) remain the source of truth for detail; this
 * only needs to answer "is there pricing work in flight on this deal, and
 * with whom" at a glance, without reducing several requests to one.
 */
function PricingRequestSummaryStrip({ summary }) {
  const rollupParts = PRICING_REQUEST_STATUSES
    .filter((status) => status !== 'CANCELLED' && summary.counts[status])
    .map((status) => `${pricingRequestStatusLabel(status).label} ${summary.counts[status]}`);
  const rollupText = [`ใบขอราคา ${summary.total} รายการ`, ...rollupParts].join(' · ');
  return (
    <div className="flex flex-col gap-1">
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-2xs font-bold text-text-muted">การขอราคา:</span>
        <span className="text-2xs text-text-muted">{rollupText}</span>
      </div>
      {summary.requests.map((pr) => {
        const status = pricingRequestStatusLabel(pr.status);
        return (
          <div key={pr.id} className="flex flex-wrap items-center gap-1.5 pl-1">
            <span className="text-2xs text-text-muted">
              {pricingRequestRecipientLabel(pr.recipientType)}
              {pr.recipientLabel ? ` · ${pr.recipientLabel}` : ''}
            </span>
            <Icon name="chevronRight" size={10} className="text-text-muted" />
            <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
          </div>
        );
      })}
    </div>
  );
}

/**
 * Deal pipeline panel (V50): the 14-stage journey this deal must travel, with
 * the current stage front and center. One ticket = one deal — the operational
 * price-request/dual-track machinery below the panel is HOW some stages get
 * done, and doc generation surfaces here on exactly the stage it belongs to
 * (docActions is rendered by the parent from its real `can` permission flags).
 *
 * Ticket-detail IA rebuild Phase 1 (see
 * docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md
 * "Overflow menu ⋯" / "จัดการดีล danger section"): แก้ไขสถานะ… / พักดีลไว้ /
 * พัก dormant / เสียงาน no longer render inline here — they moved into
 * TicketDetailPage's single header overflow menu and bottom danger zone. The
 * actual decision of whether each is AVAILABLE stays entirely in this
 * component (`canEditStage`/`canLost`/`canHold`/`canDormant` below, backed by
 * the same `hasAction`/`canSetStage`/`canMarkLost` gates as before —
 * unchanged); this forwardRef only exposes WHEN each one is available (so the
 * parent knows whether to render a menu item / danger button at all) and
 * functions that open this panel's own modals (so the actual submit/mutation
 * logic — and the "who may act" re-check — never leaves this component). A
 * ref caller that opens something this deal doesn't actually allow is a
 * no-op: the guard is re-checked inside `openEditStage`/`openHold`/
 * `openDormant`/`openMarkLost` themselves, not just read off the exposed
 * booleans.
 *
 * Phase-1 clutter follow-up (FIX 2): the "เลื่อนไป: <next stage>" primary
 * advance button ALSO moved out of this panel, into the same header overflow
 * menu — it used to compete with the sticky header's own primary CTA for "the
 * one thing to click," and the owner's decision was that the resolver-derived
 * sticky action wins that slot. `canAdvance` follows the same pattern as
 * `canEditStage`/etc. above (this component decides availability;
 * `openAdvance` re-checks `canAdvance` AND the `advanceReady` precondition
 * before calling `onUpdateStage` — the exact same gate the old inline button
 * enforced via its `disabled` attribute, just invoked from the menu instead
 * of a panel button). `nextHint`/the "ถัดไป:" line stay in this panel — they
 * are informational, not a second copy of the action.
 */
export const DealStagePanel = forwardRef(function DealStagePanel({
  user, summary, availableActions = [], pricingRequests = [], docActions, primaryAction, actionLoading,
  advanceReady = true,
  deliveryProgress = null,
  onUpdateStage, onMarkLost, onReopen, onHold, onDormant, onResume, onSetTenderRequirement,
}, ref) {
  const [editOpen, setEditOpen] = useState(false);
  const [lostOpen, setLostOpen] = useState(false);
  const [noteAction, setNoteAction] = useState(null);
  const [note, setNote] = useState('');
  const [showSteps, setShowSteps] = useState(false);

  const hasAction = (action, targetStage = null) => availableActions.some((item) =>
    item.action === action && (targetStage == null || item.targetStage === targetStage));
  // lifecycle, not lostReason — the reason persists after a reopen (V57).
  const lost = summary.lifecycle === 'CLOSED_LOST';
  const lifecycle = summary.lifecycle ?? (lost ? 'CLOSED_LOST' : 'ACTIVE');
  const meta = stageMeta(summary.salesStage);
  const label = dealStageLabel(summary.salesStage);
  const next = lost ? null : nextStage(summary.salesStage);
  const days = daysSince(summary.stageUpdatedAt);
  const canEditStage = hasAction('UPDATE_STAGE') && allowedTargetStages(user, summary).length > 0 && !lost;
  const canLost = hasAction('MARK_LOST') && canMarkLost(user, summary) && !lost && summary.salesStage !== 'CLOSED_PAID';
  const canAdvance = next && !next.auto && hasAction('ADVANCE_STAGE', next.code) && canSetStage(user, summary, next.code);
  const canHold = hasAction('PLACE_ON_HOLD');
  const canDormant = hasAction('MARK_DORMANT');
  const canResume = hasAction('RESUME');
  const canTender = hasAction('SET_TENDER_REQUIREMENT') && summary.salesStage === 'AWAITING_BUYER';
  const isDone = !lost && summary.salesStage === 'CLOSED_PAID';

  useImperativeHandle(ref, () => ({
    canEditStage, canLost, canHold, canDormant,
    // FIX 3 (P2, clutter-follow-up review round 2): the old inline buttons
    // this forwardRef replaced were each `disabled={actionLoading}` — a
    // mutation already in flight blocked a second click. The overflow menu
    // item only disables its own click on its OWN precondition (e.g.
    // `!readyToAdvance` for เลื่อนไป); it never re-derives actionLoading, so
    // without this re-check here a stale double-click (⋯ → click, reopen ⋯
    // while the first mutation is still pending → click again) fired two
    // requests, the second landing as a 409 red toast
    // (TicketService.java:1143, "Deal is already in stage X").
    // actionLoading is a plain boolean prop from the parent's own
    // useMutation().isPending — undefined (no prop passed, e.g. some tests)
    // is falsy, so this is a no-op change when the caller doesn't track it.
    openEditStage: () => { if (canEditStage && !actionLoading) setEditOpen(true); },
    openMarkLost: () => { if (canLost) setLostOpen(true); },
    openHold: () => { if (canHold && !actionLoading) setNoteAction('hold'); },
    openDormant: () => { if (canDormant && !actionLoading) setNoteAction('dormant'); },
    // Re-checks the real gate (canAdvance), the readiness precondition
    // (advanceReady), AND actionLoading — the same three conditions the old
    // inline "เลื่อนไป" button's `disabled` attribute enforced (canAdvance/
    // advanceReady were already re-checked here; actionLoading was the one
    // that got dropped when the button moved into the overflow menu — see
    // the FIX 3 note above).
    openAdvance: () => { if (canAdvance && advanceReady && !actionLoading && next) onUpdateStage({ stage: next.code }); },
  }));

  // When the next stage isn't one this user can one-click into, explain who or
  // what advances it instead of showing a dead end.
  const nextHint = next && !canAdvance
    ? (next.auto ? next.autoHint : `ขั้นถัดไปอัปเดตโดย${GATE_LABEL[next.gate]}`)
    : null;

  // Sub-status rows — the inner journey of the current stage:
  // • การขอราคา: every non-CANCELLED PricingRequest on this deal (Fix 3 of the
  //   review-remediation plan), NOT ticket.status (which is permanently stuck
  //   at 'draft' now that ticket creation no longer auto-submits; see
  //   TicketService.create/submit, commit 5) and NOT just "the latest one" —
  //   reducing several concurrent requests to a single highest-id winner used
  //   to hide a live IMPORT_REVIEWING request behind a newer DRAFT, or hide
  //   the whole strip behind a newer CANCELLED one. Renders nothing when the
  //   deal has no requests, or every request is CANCELLED.
  // • การชำระเงิน / การนำเข้า: replace the old Track P / Track F steppers.
  const pricingSummary = activePricingRequestsSummary(pricingRequests);
  const showPricingChips = pricingSummary != null;
  const showPaymentChips = summary.paymentStatus != null;
  const derivedPayment = paymentStageLabel(summary.paymentStage);
  const showImportChips = summary.fulfillmentStatus != null;

  async function submitStage(payload) {
    await onUpdateStage(payload);
    setEditOpen(false);
  }

  async function submitLost(payload) {
    await onMarkLost(payload);
    setLostOpen(false);
  }

  async function submitNoteAction() {
    const payload = { note: note.trim() || undefined };
    if (noteAction === 'hold') await onHold(payload);
    if (noteAction === 'dormant') await onDormant(payload);
    if (noteAction === 'resume') await onResume(payload);
    setNoteAction(null);
    setNote('');
  }

  return (
    <section className="panel" data-testid="deal-stage-panel">
      <div className="panel-header">
        <h2>สถานะดีล (Pipeline)</h2>
        <button
          type="button"
          className="secondary-button"
          style={{ fontSize: 12 }}
          onClick={() => setShowSteps((v) => !v)}
        >
          {showSteps ? 'ซ่อนขั้นตอนทั้งหมด' : 'ดูขั้นตอนทั้งหมด (14 ขั้น)'}
        </button>
      </div>

      <div className="flex flex-col gap-4 px-4 py-4 sm:px-5">
        <PhaseTracker salesStage={summary.salesStage} lost={lost} />

        {lifecycle === 'ON_HOLD' || lifecycle === 'DORMANT' ? (
          <div className={`flex flex-wrap items-center gap-3 rounded-xl border px-4 py-3 ${
            lifecycle === 'ON_HOLD'
              ? 'border-warning-border bg-warning-bg-soft'
              : 'border-border bg-surface-subtle'
          }`}>
            <div className="min-w-0 flex-1">
              <div className="text-sm font-extrabold text-text">
                {dealLifecycleLabel(lifecycle).label}
              </div>
              <div className="mt-0.5 text-xs text-text-muted">
                ขั้นเดิมยังอยู่ที่ {meta?.no ?? '-'}. {label.label}
              </div>
            </div>
            {canResume ? (
              <button type="button" className="primary-button" disabled={actionLoading} onClick={() => setNoteAction('resume')}>
                ดำเนินการต่อ
              </button>
            ) : null}
            {canDormant && lifecycle === 'ON_HOLD' ? (
              <button type="button" className="secondary-button" disabled={actionLoading} onClick={() => setNoteAction('dormant')}>
                พัก dormant
              </button>
            ) : null}
          </div>
        ) : lost ? (
          <div className="flex flex-wrap items-center gap-3 rounded-xl border border-danger-border bg-danger-bg px-4 py-3">
            <div className="min-w-0 flex-1">
              <div className="text-sm font-extrabold text-danger-dark">
                เสียงาน · {dealLostReasonLabel(summary.lostReason).label}
              </div>
              <div className="mt-0.5 text-xs text-danger-dark">
                ปิดเมื่อ {formatThaiDate(summary.lostAt)} — เปิดดีลใหม่ได้โดยสถานะเดิม (ขั้นที่ {meta?.no ?? '-'}) ยังอยู่
              </div>
            </div>
            {canMarkLost(user, summary) ? (
              <button type="button" className="secondary-button" disabled={actionLoading} onClick={onReopen}>
                เปิดดีลอีกครั้ง
              </button>
            ) : null}
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            <div className="flex items-start gap-3">
              <span className="grid h-12 w-12 shrink-0 place-items-center rounded-xl bg-info text-lg font-extrabold text-surface">
                {meta?.no ?? '-'}
              </span>
              <div className="min-w-0 flex-1">
                <div className="text-base font-extrabold leading-snug text-text">{label.label}</div>
                <div className="mt-1 flex flex-wrap items-center gap-2">
                  <StatusBadge tone={label.tone}>เฟส {meta?.phase ?? '-'}</StatusBadge>
                  {meta ? (
                    <span className="rounded-full bg-surface-subtle px-2 py-0.5 text-2xs font-bold text-text-muted">
                      {GATE_LABEL[meta.gate]}
                    </span>
                  ) : null}
                  {days != null ? (
                    <span className="text-xs text-text-muted">อยู่ในขั้นนี้ {days === 0 ? 'วันนี้' : `${days} วัน`}</span>
                  ) : null}
                </div>
              </div>
            </div>

            {/* Compact "next step" line — keeps the default view to current + next
                only. Used to be suppressed when canAdvance (the explicit
                "เลื่อนไป:" button used to render right below it) to avoid
                saying it twice; that button moved into the header overflow
                menu (FIX 2), so this line is now the only next-stage context
                left in the panel and always shows when there is one. */}
            {next && !isDone ? (
              <div className="flex items-center gap-2 text-xs">
                <span className="font-bold text-text-muted">ถัดไป:</span>
                <span className="rounded-full bg-info-bg px-2.5 py-0.5 text-2xs font-bold text-info">
                  {next.no}. {dealStageLabel(next.code).label}
                </span>
              </div>
            ) : null}

            {/* Inner journeys of the current stage — replace the old standalone
                Track P / Track F panel and make the internal price workflow
                (sales → Import → CEO confirmed price) visible inside the quote
                stages. All read live from the deal's own status fields. */}
            {(showPricingChips || showPaymentChips || showImportChips) ? (
              <div className="flex flex-col gap-1.5">
                {showPricingChips ? (
                  <PricingRequestSummaryStrip summary={pricingSummary} />
                ) : null}
                {showPaymentChips ? (
                  depositBypassesNotice(summary.depositPolicy) ? null : (
                    <SubstepChips label="การชำระเงิน:" steps={PAYMENT_SUBSTEPS} currentCode={summary.paymentStatus} />
                  )
                ) : null}
                {summary.paymentStage && summary.paymentStage !== 'NOT_REQUIRED' ? (
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="text-2xs font-bold text-text-muted">ยอดชำระ:</span>
                    <StatusBadge tone={derivedPayment.tone}>{derivedPayment.label}</StatusBadge>
                    {summary.overdue ? (
                      <StatusBadge tone={overdueBadgeLabel(true).tone}>{overdueBadgeLabel(true).label}</StatusBadge>
                    ) : null}
                  </div>
                ) : null}
                {depositBypassesNotice(summary.depositPolicy) ? (
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="text-2xs font-bold text-text-muted">นโยบายมัดจำ:</span>
                    <StatusBadge tone={depositPolicyLabel(summary.depositPolicy).tone}>
                      {depositPolicyLabel(summary.depositPolicy).label}
                    </StatusBadge>
                    {summary.depositPolicyReason ? (
                      <span className="text-2xs text-text-muted">— {summary.depositPolicyReason}</span>
                    ) : null}
                  </div>
                ) : null}
                {showImportChips ? (
                  <SubstepChips label="การนำเข้า:" steps={PROCUREMENT_SUBSTEPS} currentCode={summary.fulfillmentStatus} />
                ) : null}
                {deliveryProgress && deliveryProgress.ordered > 0 ? (
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="text-2xs font-bold text-text-muted">ส่งมอบ:</span>
                    <StatusBadge tone={deliveryProgress.delivered >= deliveryProgress.ordered ? 'success' : 'warning'}>
                      {deliveryProgress.delivered.toLocaleString('en-US')} / {deliveryProgress.ordered.toLocaleString('en-US')}
                    </StatusBadge>
                  </div>
                ) : null}
              </div>
            ) : null}

            {/* The work-state banner (whose move, what's blocking) now lives
                once, in the sticky header (DealStateHeader) — see
                workState.js / TicketDetailPage. Repeating it here was the
                Phase-1 audit's duplicate #2 finding. */}

            {isDone ? (
              <div className="flex flex-col gap-2">
                <div className="rounded-xl bg-success-bg px-4 py-3 text-center text-sm font-extrabold text-success-dark">
                  ✓ ดีลเสร็จสมบูรณ์ — เก็บเงินครบแล้ว
                </div>
                {/* The operational close (ปิดเรื่อง) still happens here — the
                    pipeline reaching CLOSED_PAID doesn't close the ticket itself. */}
                {primaryAction ? (
                  <div className="flex flex-wrap items-center gap-2">{primaryAction}</div>
                ) : null}
              </div>
            ) : (
              <div className="flex flex-col gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  {primaryAction}
                  {/* The "เลื่อนไป: <next stage>" primary advance button (and its
                      disabled-with-why gate hint) moved into the header
                      overflow menu — see this component's own doc comment
                      (FIX 2) and TicketDetailPage's `openAdvance` handler.
                      `canAdvance`/`advanceReady` still gate it there, byte-
                      identical to before; only where it renders changed. */}
                  {nextHint ? (
                    <span className="rounded-lg border border-border bg-surface-subtle px-3 py-2 text-xs text-text-muted">
                      <Icon name="clock" size={12} /> {nextHint}
                    </span>
                  ) : null}
                </div>
              </div>
            )}

            {canTender ? (
              <div className="flex flex-wrap items-center gap-2 border-t border-border pt-3">
                <label className="flex items-center gap-2 text-xs font-bold text-text-muted">
                  ประมูล
                  <select
                    value={summary.tenderRequirement ?? 'UNKNOWN'}
                    disabled={actionLoading}
                    onChange={(event) => onSetTenderRequirement({ value: event.target.value })}
                  >
                    {['UNKNOWN', 'REQUIRED', 'NOT_REQUIRED'].map((value) => (
                      <option key={value} value={value}>{tenderRequirementLabel(value).label}</option>
                    ))}
                  </select>
                </label>
              </div>
            ) : null}

            {/* Stage-gated documents: the doc that belongs to THIS stage of the
                deal (quotation at the quote stages, deposit notice at order,
                IR at procurement...) — parent renders them from real `can` flags. */}
            {docActions ? (
              <div className="flex flex-wrap items-center gap-2 border-t border-border pt-3">
                <span className="text-xs font-bold text-text-muted">เอกสารของขั้นนี้:</span>
                {docActions}
              </div>
            ) : null}
          </div>
        )}

        {showSteps ? <DealStageStepper salesStage={summary.salesStage} lost={lost} /> : null}
      </div>

      {editOpen ? (
        <UpdateStageModal
          user={user}
          deal={summary}
          submitting={actionLoading}
          onClose={() => setEditOpen(false)}
          onSubmit={submitStage}
        />
      ) : null}
      {lostOpen ? (
        <MarkLostModal
          submitting={actionLoading}
          onClose={() => setLostOpen(false)}
          onSubmit={submitLost}
        />
      ) : null}
      {noteAction ? (
        <Modal
          title={noteAction === 'resume' ? 'ดำเนินการต่อ' : noteAction === 'hold' ? 'พักดีลไว้' : 'พัก dormant'}
          onClose={() => { setNoteAction(null); setNote(''); }}
          footer={(
            <>
              <button type="button" className="secondary-button" onClick={() => { setNoteAction(null); setNote(''); }}>ยกเลิก</button>
              <button type="button" className="primary-button" disabled={actionLoading} onClick={submitNoteAction}>บันทึก</button>
            </>
          )}
        >
          <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
            หมายเหตุ (ถ้ามี)
            <textarea className="min-h-20" value={note} onChange={(event) => setNote(event.target.value)} />
          </label>
        </Modal>
      ) : null}
    </section>
  );
});

function depositBypassesNotice(policy) {
  return ['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'].includes(policy);
}
