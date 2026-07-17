import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { dealLostReasonLabel, dealStageLabel, formatThaiDate } from '../../utils/format.js';
import { DealStageStepper, PhaseTracker } from './DealStageStepper.jsx';
import { MarkLostModal } from './MarkLostModal.jsx';
import {
  allowedTargetStages, canMarkLost, canSetStage, GATE_LABEL, nextStage,
  PAYMENT_SUBSTEPS, PRICING_SUBSTEPS, PROCUREMENT_SUBSTEPS, stageMeta,
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
 * Deal pipeline panel (V50): the 14-stage journey this deal must travel, with
 * the current stage front and center. One ticket = one deal — the operational
 * price-request/dual-track machinery below the panel is HOW some stages get
 * done, and doc generation surfaces here on exactly the stage it belongs to
 * (docActions is rendered by the parent from its real `can` permission flags).
 */
export function DealStagePanel({ user, summary, docActions, primaryAction, guidance, actionLoading, onUpdateStage, onMarkLost, onReopen }) {
  const [editOpen, setEditOpen] = useState(false);
  const [lostOpen, setLostOpen] = useState(false);
  const [showSteps, setShowSteps] = useState(false);

  const lost = !!summary.lostReason;
  const meta = stageMeta(summary.salesStage);
  const label = dealStageLabel(summary.salesStage);
  const next = lost ? null : nextStage(summary.salesStage);
  const days = daysSince(summary.stageUpdatedAt);
  const canEditStage = allowedTargetStages(user, summary).length > 0 && !lost;
  const canLost = canMarkLost(user, summary) && !lost && summary.salesStage !== 'CLOSED_PAID';
  const canAdvance = next && !next.auto && canSetStage(user, summary, next.code);
  const isDone = !lost && summary.salesStage === 'CLOSED_PAID';

  // When the next stage isn't one this user can one-click into, explain who or
  // what advances it instead of showing a dead end. Suppressed when the parent's
  // guidance line already says the same thing (guidance wins).
  const rawNextHint = next && !canAdvance
    ? (next.auto ? next.autoHint : `ขั้นถัดไปอัปเดตโดย${GATE_LABEL[next.gate]}`)
    : null;
  const nextHint = rawNextHint && rawNextHint !== guidance ? rawNextHint : null;

  // Sub-status chip rows — the inner journey of the current stage:
  // • การขอราคา: the internal sales→Import→CEO confirmed-price flow that lives
  //   inside the quote stages (until the customer confirms the order).
  // • การชำระเงิน / การนำเข้า: replace the old Track P / Track F steppers.
  const showPricingChips = ['submitted', 'in_review', 'price_proposed', 'approved'].includes(summary.status)
    || (summary.status === 'quotation_issued' && summary.paymentStatus == null);
  const showPaymentChips = summary.paymentStatus != null;
  const showImportChips = summary.fulfillmentStatus != null;

  async function submitStage(payload) {
    await onUpdateStage(payload);
    setEditOpen(false);
  }

  async function submitLost(payload) {
    await onMarkLost(payload);
    setLostOpen(false);
  }

  return (
    <section className="panel">
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

        {lost ? (
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

            {/* Inner journeys of the current stage — replace the old standalone
                Track P / Track F panel and make the internal price workflow
                (sales → Import → CEO confirmed price) visible inside the quote
                stages. All read live from the deal's own status fields. */}
            {(showPricingChips || showPaymentChips || showImportChips) ? (
              <div className="flex flex-col gap-1.5">
                {showPricingChips ? (
                  <SubstepChips label="การขอราคา:" steps={PRICING_SUBSTEPS} currentCode={summary.status} />
                ) : null}
                {showPaymentChips ? (
                  <SubstepChips label="การชำระเงิน:" steps={PAYMENT_SUBSTEPS} currentCode={summary.paymentStatus} />
                ) : null}
                {showImportChips ? (
                  <SubstepChips label="การนำเข้า:" steps={PROCUREMENT_SUBSTEPS} currentCode={summary.fulfillmentStatus} />
                ) : null}
              </div>
            ) : null}

            {/* One guidance line (folded in from the old standalone callout bars):
                the viewer's next action, or who the deal is waiting on. */}
            {guidance ? (
              <div className="flex items-start gap-2 text-xs text-info">
                <Icon name="chevronRight" size={14} className="mt-0.5 shrink-0" />
                <span>{guidance}</span>
              </div>
            ) : null}

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
              <div className="flex flex-wrap items-center gap-2">
                {primaryAction}
                {canAdvance ? (
                  <button
                    type="button"
                    className="primary-button"
                    disabled={actionLoading}
                    onClick={() => onUpdateStage({ stage: next.code })}
                  >
                    เลื่อนไป: {dealStageLabel(next.code).label}
                    <Icon name="chevronRight" size={14} />
                  </button>
                ) : null}
                {nextHint ? (
                  <span className="rounded-lg border border-border bg-surface-subtle px-3 py-2 text-xs text-text-muted">
                    <Icon name="clock" size={12} /> {nextHint}
                  </span>
                ) : null}
                {canEditStage ? (
                  <button type="button" className="secondary-button" disabled={actionLoading} onClick={() => setEditOpen(true)}>
                    แก้ไขสถานะ…
                  </button>
                ) : null}
                {canLost ? (
                  <button
                    type="button"
                    className="secondary-button"
                    style={{ marginLeft: 'auto', color: 'var(--color-danger)', borderColor: 'var(--color-danger-border)' }}
                    disabled={actionLoading}
                    onClick={() => setLostOpen(true)}
                  >
                    เสียงาน
                  </button>
                ) : null}
              </div>
            )}

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
    </section>
  );
}
