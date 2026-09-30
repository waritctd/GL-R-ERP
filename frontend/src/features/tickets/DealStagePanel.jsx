import { forwardRef, useImperativeHandle, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { dealLifecycleLabel, dealLostReasonLabel, dealStageLabel, formatThaiDate, tenderRequirementLabel } from '../../utils/format.js';
import { ACTIVITY_KINDS, STAGE_ADVANCE_GATE_MESSAGE } from './dealTrackingMeta.js';
import { DealStageStepper, PhaseSummary, PhaseTracker } from './DealStageStepper.jsx';
import { MarkLostModal } from './MarkLostModal.jsx';
import { EMPTY_STAGE_CATALOG, findStage, nextStageIn } from './stageCatalog.js';
import { AUTO_STAGE_HINT, GATE_LABEL, STAGE_HEADLINE } from './stageMeta.js';
import { UpdateStageModal } from './UpdateStageModal.jsx';

function daysSince(iso) {
  if (!iso) return null;
  return Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 86400000));
}

/**
 * Deal pipeline panel (V50, widened by V143): the journey this deal must travel, with
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
 * component (`canEditStage`/`canLost`/`canHold`/`canDormant` below, now backed
 * ENTIRELY by the server's own answer — `availableActions` and the per-stage
 * `stageDecisions` payload, with no local re-derivation of who may do what);
 * this forwardRef only exposes WHEN each one is available (so the
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
 *
 * Slice A "chip diet": this panel used to also render a PricingRequestSummaryStrip,
 * a "ยอดชำระ" badge, a "นโยบายมัดจำ" badge, a PAYMENT_SUBSTEPS chip row, and a
 * PROCUREMENT_SUBSTEPS chip row + "ส่งมอบ x/y" badge — the same four statuses
 * (pricing/payment/import/deal-value) the header (DealStateHeader) and the
 * money/pricing/fulfilment tabs already name, repeated here a second or third
 * time before the viewer clicked anything. All five were removed:
 * PricingRequestSummaryStrip (redundant with PricingRequestPanel's own full
 * per-request list) and the payment/deposit badges (redundant with the money
 * tab's own panel-header badge and DealDepositPanel's own policy badge) were
 * deleted outright; the PAYMENT_SUBSTEPS progression (finer than anything the
 * money tab showed) moved there instead; PROCUREMENT_SUBSTEPS + the delivery
 * total were deleted, not moved — DealFulfilmentPanel already renders its own
 * copy of both (see that file's own `SubstepChips`/`totalDelivered` comments).
 * This panel keeps only the stage number/label/phase/gate/days-in-stage, the
 * "ถัดไป:" line, the tender `<select>`, the done banner, and docActions/
 * primaryAction — the pipeline's OWN state, not a rollup of everyone else's.
 */
export const DealStagePanel = forwardRef(function DealStagePanel({
  summary, availableActions = [], stageDecisions = [], catalog = EMPTY_STAGE_CATALOG,
  docActions, primaryAction, actionLoading,
  advanceReady = true,
  onUpdateStage, onMarkLost, onReopen, onHold, onDormant, onResume, onSetTenderRequirement,
}, ref) {
  const [editOpen, setEditOpen] = useState(false);
  const [lostOpen, setLostOpen] = useState(false);
  const [noteAction, setNoteAction] = useState(null);
  const [note, setNote] = useState('');
  const [showSteps, setShowSteps] = useState(false);
  // Guided advance (owner ask 2026-09-28): the readiness gate stays, but instead of a dead hint the
  // rep gets one flow that collects the two things it needs, then advances.
  const [guidedOpen, setGuidedOpen] = useState(false);
  const [guidedDraft, setGuidedDraft] = useState({ nextFollowUpAt: '', kind: 'CALL', activityNote: '', winProbability: '' });
  const queryClient = useQueryClient();

  const hasAction = (action, targetStage = null) => availableActions.some((item) =>
    item.action === action && (targetStage == null || item.targetStage === targetStage));
  // lifecycle, not lostReason — the reason persists after a reopen (V57).
  const lost = summary.lifecycle === 'CLOSED_LOST';
  const lifecycle = summary.lifecycle ?? (lost ? 'CLOSED_LOST' : 'ACTIVE');
  const meta = findStage(catalog, summary.salesStage);
  const label = dealStageLabel(summary.salesStage);
  const next = lost ? null : nextStageIn(catalog, summary.salesStage);
  const days = daysSince(summary.stageUpdatedAt);
  // Every gate below is now read off the server's answer, never recomputed. `hasAction` is the
  // backend's availableActions list (TicketService.actions) and `stageDecisions` is its per-stage
  // verdict; the local canSetStage/canMarkLost/allowedTargetStages copies these replaced were a
  // second implementation of TicketService's authorization, and had gone stale.
  const canEditStage = hasAction('UPDATE_STAGE')
    && stageDecisions.some((decision) => decision.allowed) && !lost;
  const canLost = hasAction('MARK_LOST') && !lost && summary.salesStage !== 'CLOSED_PAID';
  const canAdvance = Boolean(next) && !next.auto && hasAction('ADVANCE_STAGE', next.code);
  // The next stage is reachable EXCEPT the readiness gate (a follow-up date + a logged activity
  // since the last stage change) is unmet — the ONE block the acting sales rep clears themselves.
  // Detected off the server's own reason (STAGE_ADVANCE_GATE_MESSAGE verbatim) so it can never
  // disagree with the backend; distinct from an auto stage or a block owned by another role, which
  // the rep cannot act on. When true, the guided-advance flow replaces the dead "อัปเดตโดย…" hint.
  const nextDecision = next ? stageDecisions.find((decision) => decision.stage === next.code) : null;
  const readinessBlocked = Boolean(next) && !next.auto && !canAdvance
    && Boolean(nextDecision) && !nextDecision.allowed
    && nextDecision.blockedReason === STAGE_ADVANCE_GATE_MESSAGE;
  // One click, three writes IN ORDER: set the follow-up date (+ optional win%), log the activity,
  // then advance through the PARENT's own onUpdateStage so its invalidation + toast still run. The
  // gate itself is unchanged — this just satisfies it in one place instead of two scattered panels.
  const guidedAdvance = useMutation({
    mutationFn: async () => {
      await api.tickets.updateTracking(summary.id, {
        nextFollowUpAt: guidedDraft.nextFollowUpAt || null,
        winProbability: guidedDraft.winProbability === '' ? null : Number(guidedDraft.winProbability),
      });
      await api.tickets.addActivity(summary.id, {
        activityDate: new Date().toISOString().slice(0, 10),
        kind: guidedDraft.kind,
        note: guidedDraft.activityNote.trim() || null,
      });
      await onUpdateStage({ stage: next.code });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(summary.id) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(summary.id) });
      setGuidedOpen(false);
      setGuidedDraft({ nextFollowUpAt: '', kind: 'CALL', activityNote: '', winProbability: '' });
    },
  });
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
  // Suppressed while the guided-advance button stands in for it: that button IS the way forward, so
  // "อัปเดตโดย<ฝ่าย>" would be a misleading dead end next to it.
  const nextHint = next && !canAdvance && !readinessBlocked
    ? (next.auto ? AUTO_STAGE_HINT[next.code] : `ขั้นถัดไปอัปเดตโดย${GATE_LABEL[next.gate]}`)
    : null;

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
    <Panel
      title="สถานะดีล (Pipeline)"
      data-testid="deal-stage-panel"
      actions={(
        <Button
          type="button"
          variant="ghost"
          style={{ fontSize: 12 }}
          onClick={() => setShowSteps((v) => !v)}
        >
          {showSteps ? 'ซ่อนขั้นตอนทั้งหมด' : `ดูขั้นตอนทั้งหมด (${catalog.stages.length} ขั้น)`}
        </Button>
      )}
    >
      <div className="flex flex-col gap-4 px-4 py-4 sm:px-5">
        {/* Closing the deal does NOT create the rep's commission. Only the accountant recording
            the tax invoice does (POST /api/commissions/from-deal, account-only), and that same
            upload is what flips invoiceOnFile. Until then a CLOSED_PAID deal has earned the rep
            nothing and nothing on this page said so — the rep had to know to ask. */}
        {summary.salesStage === 'CLOSED_PAID' && !summary.invoiceOnFile ? (
          <div
            className="rounded-xl border border-warning-border bg-warning-bg-soft px-4 py-3"
            data-testid="awaiting-invoice-for-commission"
          >
            <div className="text-sm font-extrabold text-text">รอฝ่ายบัญชีบันทึกใบกำกับภาษี</div>
            <div className="mt-0.5 text-xs text-text-muted">
              ปิดการขายแล้ว แต่ค่าคอมมิชชันของผู้ดูแลดีลจะยังไม่เกิดขึ้น
              จนกว่าฝ่ายบัญชีจะบันทึกใบกำกับภาษีของดีลนี้
            </div>
          </div>
        ) : null}

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
              <Button type="button" variant="primary" disabled={actionLoading} onClick={() => setNoteAction('resume')}>
                ดำเนินการต่อ
              </Button>
            ) : null}
            {canDormant && lifecycle === 'ON_HOLD' ? (
              <Button type="button" variant="secondary" disabled={actionLoading} onClick={() => setNoteAction('dormant')}>
                พัก dormant
              </Button>
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
            {hasAction('REOPEN') ? (
              <Button type="button" variant="secondary" disabled={actionLoading} onClick={onReopen}>
                เปิดดีลอีกครั้ง
              </Button>
            ) : null}
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            <div className="flex items-start gap-3">
              <span className="grid h-12 w-12 shrink-0 place-items-center rounded-xl bg-info text-lg font-extrabold text-surface">
                {meta?.no ?? '-'}
              </span>
              <div className="min-w-0 flex-1">
                <div className="text-base font-extrabold leading-snug text-text">{STAGE_HEADLINE[summary.salesStage] ?? label.label}</div>
                {meta && GATE_LABEL[meta.gate] ? (
                  <div data-testid="deal-stage-owner" className="mt-0.5 text-xs font-bold text-text-muted">
                    {`ผู้รับผิดชอบ: ${GATE_LABEL[meta.gate]}`}
                  </div>
                ) : null}
                <div className="mt-1">
                  <PhaseSummary catalog={catalog} salesStage={summary.salesStage} lost={lost} />
                </div>
                {days != null ? (
                  <div className="mt-1 text-xs text-text-muted">อยู่ในขั้นนี้ {days === 0 ? 'วันนี้' : `${days} วัน`}</div>
                ) : null}
              </div>
            </div>

            {/* ONE "what happens next, and who moves it" line. It used to be a small "ถัดไป:" pill
                plus a separate grey hint pill further down, with the hint — the only sentence that
                says whose move it is — styled as the least important thing on screen. */}
            {next && !isDone ? (
              <div data-testid="deal-stage-next" className="text-sm leading-snug text-text">
                <span className="font-bold text-text-muted">ถัดไป: </span>
                <span className="font-extrabold">{next.no}. {dealStageLabel(next.code).label}</span>
                {nextHint ? <span className="text-text-secondary"> — {nextHint}</span> : null}
              </div>
            ) : null}

            {/* Slice A "chip diet": the pricing/payment/deposit-policy/import/
                delivery sub-status rows that used to render here were removed
                or moved — see this component's own doc comment above for
                where each one went. */}

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
            ) : primaryAction || readinessBlocked ? (
              <div className="flex flex-wrap items-center gap-2">
                {primaryAction}
                {/* Not a dead hint: the rep CAN advance — they just have to log the follow-up + an
                    activity first, which this button collects and then advances, in one go. The
                    "who moves it" hint for everyone else is part of the ถัดไป line above. */}
                {readinessBlocked ? (
                  <Button
                    type="button"
                    variant="primary"
                    disabled={actionLoading}
                    onClick={() => setGuidedOpen(true)}
                    data-testid="guided-advance-open"
                  >
                    เลื่อนไป: {next.no}. {dealStageLabel(next.code).label}
                  </Button>
                ) : null}
              </div>
            ) : null}

            {canTender ? (
              <div className="flex flex-wrap items-center gap-x-4 gap-y-3 border-t border-border pt-3">
                {canTender ? (
                  <label className="flex min-w-0 items-center gap-2 text-xs font-bold text-text-muted">
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
                ) : null}
              </div>
            ) : null}

            {/* Stage-gated documents: the doc that belongs to THIS stage of the
                deal (quotation at the quote stages, deposit notice at order,
                IR at procurement...) — parent renders them from real `can` flags. */}
            {docActions ? (
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs font-bold text-text-muted">เอกสารของขั้นนี้:</span>
                {docActions}
              </div>
            ) : null}
          </div>
        )}

        {showSteps ? (
          <div className="flex flex-col gap-4 border-t border-border pt-4">
            <PhaseTracker catalog={catalog} salesStage={summary.salesStage} lost={lost} />
            <DealStageStepper catalog={catalog} salesStage={summary.salesStage} lost={lost} />
          </div>
        ) : null}
      </div>

      {editOpen ? (
        <UpdateStageModal
          deal={summary}
          stageDecisions={stageDecisions}
          submitting={actionLoading}
          onClose={() => setEditOpen(false)}
          onSubmit={submitStage}
        />
      ) : null}
      {lostOpen ? (
        <MarkLostModal
          catalog={catalog}
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
              <Button type="button" variant="secondary" onClick={() => { setNoteAction(null); setNote(''); }}>ยกเลิก</Button>
              <Button type="button" variant="primary" disabled={actionLoading} onClick={submitNoteAction}>บันทึก</Button>
            </>
          )}
        >
          <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
            หมายเหตุ (ถ้ามี)
            <textarea className="min-h-20" value={note} onChange={(event) => setNote(event.target.value)} />
          </label>
        </Modal>
      ) : null}
      {guidedOpen && next ? (
        <Modal
          title={`เลื่อนไปขั้น ${next.no}. ${dealStageLabel(next.code).label}`}
          subtitle="ก่อนเลื่อนขั้น ระบุวันติดตามครั้งถัดไป และบันทึกกิจกรรมล่าสุดอย่างน้อย 1 รายการ"
          onClose={() => setGuidedOpen(false)}
          testId="guided-advance-modal"
          footer={(
            <>
              <Button type="button" variant="secondary" onClick={() => setGuidedOpen(false)}>ยกเลิก</Button>
              <Button
                type="button"
                variant="primary"
                disabled={guidedAdvance.isPending
                  || !guidedDraft.nextFollowUpAt
                  || !guidedDraft.activityNote.trim()}
                onClick={() => guidedAdvance.mutate()}
                data-testid="guided-advance-submit"
              >
                {guidedAdvance.isPending ? 'กำลังเลื่อน…' : 'บันทึกและเลื่อนขั้น'}
              </Button>
            </>
          )}
        >
          <div className="grid gap-3">
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              วันติดตามครั้งถัดไป *
              <input
                type="date"
                value={guidedDraft.nextFollowUpAt}
                onChange={(event) => setGuidedDraft((d) => ({ ...d, nextFollowUpAt: event.target.value }))}
                data-testid="guided-advance-followup"
              />
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              ประเภทกิจกรรม
              <select
                value={guidedDraft.kind}
                onChange={(event) => setGuidedDraft((d) => ({ ...d, kind: event.target.value }))}
              >
                {ACTIVITY_KINDS.map((k) => (
                  <option key={k.code} value={k.code}>{k.label}</option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              บันทึกกิจกรรม *
              <textarea
                className="min-h-20"
                placeholder="เช่น โทรติดตามลูกค้า / ส่งอีเมลใบเสนอราคา"
                value={guidedDraft.activityNote}
                onChange={(event) => setGuidedDraft((d) => ({ ...d, activityNote: event.target.value }))}
                data-testid="guided-advance-note"
              />
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              โอกาสปิดการขาย % (ถ้ามี)
              <input
                type="number"
                min="0"
                max="100"
                value={guidedDraft.winProbability}
                onChange={(event) => setGuidedDraft((d) => ({ ...d, winProbability: event.target.value }))}
              />
            </label>
            {guidedAdvance.isError ? (
              <p className="rounded-md border border-danger-border bg-danger-bg px-3 py-2 text-xs text-danger-dark">
                {guidedAdvance.error?.message ?? 'เลื่อนสถานะไม่สำเร็จ'}
              </p>
            ) : null}
          </div>
        </Modal>
      ) : null}
    </Panel>
  );
});
