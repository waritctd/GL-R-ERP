import { forwardRef, useImperativeHandle, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { dealLifecycleLabel, dealLostReasonLabel, dealStageLabel, entryChannelLabel, formatThaiDate, tenderRequirementLabel } from '../../utils/format.js';
import { DealStageStepper, PhaseTracker } from './DealStageStepper.jsx';
import { MarkLostModal } from './MarkLostModal.jsx';
import { EMPTY_STAGE_CATALOG, findStage, nextStageIn } from './stageCatalog.js';
import { AUTO_STAGE_HINT, GATE_LABEL } from './stageMeta.js';
import { UpdateStageModal } from './UpdateStageModal.jsx';

function daysSince(iso) {
  if (!iso) return null;
  return Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 86400000));
}

/**
 * The three channels EntryChannel.java accepts as INPUT. UNSPECIFIED is deliberately absent: it is
 * valid as STORED (the V144 column default) but `TicketService.setEntryChannel` 400s on it, because
 * once a channel has been stated it must not be possible to un-state it. Offering it would be
 * offering an action that dies on click.
 */
const SETTABLE_ENTRY_CHANNELS = ['DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT'];

/**
 * "ช่องทางรับงาน" — how this deal arrived, and the control that corrects it.
 *
 * Issue #740: `api.tickets.setEntryChannel` existed, `TicketService.addPolicyActions` advertised
 * SET_ENTRY_CHANNEL to every deal owner, and NO component anywhere called it. V144 made
 * UNSPECIFIED the stored default and #711 made the create modal demand a choice — but only
 * client-side, so a deal created before #711, or by any other client, sat at ยังไม่ระบุช่องทาง with
 * the server offering a correction the UI could not fire. The channel was not even DISPLAYED: the
 * `entryChannelLabel` map in utils/format.js had no caller at all.
 *
 * This component decides nothing. `editable` is the server's own availableActions entry and
 * `reasonRequired` is that entry's `requiredFields` — TicketService.entryChannelIsStated is what
 * populates it, so the reason rule lives in exactly one place and this side is told the answer
 * rather than keeping a copy of it. The read-only branch still renders the value, because a viewer
 * who cannot change the channel still needs to see which route the deal came in on.
 */
function EntryChannelControl({ entryChannel, editable, reasonRequired, disabled, onSubmit }) {
  const [pending, setPending] = useState(null); // the picked channel awaiting its reason
  const [reason, setReason] = useState('');
  const current = entryChannel ?? 'UNSPECIFIED';

  if (!editable) {
    return (
      <span className="flex min-w-0 items-center gap-2 text-xs font-bold text-text-muted">
        ช่องทางรับงาน
        <span className="font-normal text-text">{entryChannelLabel(current).label}</span>
      </span>
    );
  }

  function pick(value) {
    if (value === current) return;
    // The server said a reason is required, so collect one BEFORE calling — the alternative is
    // firing a request we have been told will 400 and surfacing it as a red toast.
    if (reasonRequired) { setPending(value); setReason(''); return; }
    onSubmit({ value, note: null });
  }

  return (
    <span className="flex min-w-0 flex-wrap items-center gap-2">
      <label className="flex min-w-0 items-center gap-2 text-xs font-bold text-text-muted">
        ช่องทางรับงาน
        <select
          value={pending ?? current}
          disabled={disabled}
          onChange={(event) => pick(event.target.value)}
        >
          {/* The stored-only default is rendered as an option ONLY while the deal is still on it,
              so the select can show where the deal actually stands. It is disabled, so it cannot
              be chosen — picking it would 400. */}
          {current === 'UNSPECIFIED' ? (
            <option value="UNSPECIFIED" disabled>{entryChannelLabel('UNSPECIFIED').label}</option>
          ) : null}
          {SETTABLE_ENTRY_CHANNELS.map((value) => (
            <option key={value} value={value}>{entryChannelLabel(value).label}</option>
          ))}
        </select>
      </label>
      {pending ? (
        <span className="flex min-w-0 flex-wrap items-center gap-2">
          <input
            type="text"
            className="min-w-0"
            aria-label="เหตุผลที่เปลี่ยนช่องทางรับงาน"
            placeholder="เหตุผลที่เปลี่ยน"
            value={reason}
            disabled={disabled}
            onChange={(event) => setReason(event.target.value)}
          />
          <Button
            type="button"
            variant="primary"
            disabled={disabled || !reason.trim()}
            onClick={() => { onSubmit({ value: pending, note: reason.trim() }); setPending(null); setReason(''); }}
          >
            บันทึก
          </Button>
          <Button type="button" variant="ghost" onClick={() => { setPending(null); setReason(''); }}>
            ยกเลิก
          </Button>
        </span>
      ) : null}
    </span>
  );
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
  onSetEntryChannel,
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
  const canHold = hasAction('PLACE_ON_HOLD');
  const canDormant = hasAction('MARK_DORMANT');
  const canResume = hasAction('RESUME');
  const canTender = hasAction('SET_TENDER_REQUIREMENT') && summary.salesStage === 'AWAITING_BUYER';
  // Entry channel (issue #740). Unlike ประมูล this is NOT stage-gated — "how did this deal arrive"
  // is true of the deal at every stage, and a deal stuck at UNSPECIFIED needs correcting wherever
  // it happens to sit. `entryChannelAction` is the server's own advertisement; its requiredFields
  // is what says whether a reason is needed, so nothing here re-derives that rule.
  const entryChannelAction = availableActions.find((item) => item.action === 'SET_ENTRY_CHANNEL');
  const canSetEntryChannel = Boolean(entryChannelAction) && Boolean(onSetEntryChannel);
  // Shown read-only to anyone else with the deal open: the channel was previously rendered
  // NOWHERE, so even a viewer who cannot change it had no way to see which route the deal came in
  // on. Hidden only when there is genuinely nothing to say.
  const showEntryChannel = canSetEntryChannel || Boolean(summary.entryChannel);
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
          variant="secondary"
          style={{ fontSize: 12 }}
          onClick={() => setShowSteps((v) => !v)}
        >
          {showSteps ? 'ซ่อนขั้นตอนทั้งหมด' : `ดูขั้นตอนทั้งหมด (${catalog.stages.length} ขั้น)`}
        </Button>
      )}
    >
      <div className="flex flex-col gap-4 px-4 py-4 sm:px-5">
        <PhaseTracker catalog={catalog} salesStage={summary.salesStage} lost={lost} />

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

            {canTender || showEntryChannel ? (
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
                {showEntryChannel ? (
                  <EntryChannelControl
                    entryChannel={summary.entryChannel}
                    editable={canSetEntryChannel}
                    reasonRequired={entryChannelAction?.requiredFields?.includes('note') ?? false}
                    disabled={actionLoading}
                    onSubmit={onSetEntryChannel}
                  />
                ) : null}
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

        {showSteps ? <DealStageStepper catalog={catalog} salesStage={summary.salesStage} lost={lost} /> : null}
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
    </Panel>
  );
});
