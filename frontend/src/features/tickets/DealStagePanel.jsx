import { forwardRef, useEffect, useImperativeHandle, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import {
  bangkokTodayIso, dealStageLabel, tenderRequirementLabel,
} from '../../utils/format.js';
import { depositBypassed } from './dealMoneyStatus.js';
import { ACTIVITY_KINDS, STAGE_ADVANCE_GATE_MESSAGE } from './dealTrackingMeta.js';
import { goodsArrived, goodsNotArrivedWarning } from '../importProgress/importSteps.js';
import { DealStageStepper, PhaseSummary, PhaseTracker, skippedStageCodes } from './DealStageStepper.jsx';
import { EntryChannelFix } from './EntryChannelFix.jsx';
import { MarkLostModal } from './MarkLostModal.jsx';
import { MoveToS2Modal } from './MoveToS2Modal.jsx';
import { PauseDealModal } from './PauseDealModal.jsx';
import {
  EMPTY_STAGE_CATALOG, findStage, nextOnRoute, routePath, stageIndexIn,
} from './stageCatalog.js';
import {
  AUTO_STAGE_HINT, GATE_LABEL, manualMoveLabel, routeName, STAGE_HEADLINE, STAGE_OWNER_DISPLAY,
} from './stageMeta.js';
import { UpdateStageModal } from './UpdateStageModal.jsx';
import { stageForQuotationRecipient } from '../quotations/quotationMeta.js';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';

/**
 * The next stage this deal moves to and the "ถัดไป: …" line that names it (route-aware, deposit-bypass-aware, and
 * naming the parties' own quote stages while the route choice is the main button). Pure: every gate is read off the
 * server's own payload (`availableActions`, `stageDecisions`), never re-derived. Shared by DealStagePanel (its gates)
 * and the deal page (the line, shown in the ขั้นต่อไป card).
 */
export function nextStageLine({
  summary, availableActions = [], stageDecisions = [], catalog = EMPTY_STAGE_CATALOG, quoteParties = null, ctaStage = null,
}) {
  const hasAction = (action, targetStage = null) => availableActions.some((item) =>
    item.action === action && (targetStage == null || item.targetStage === targetStage));
  // lifecycle, not lostReason — the reason persists after a reopen (V57).
  const lost = summary.lifecycle === 'CLOSED_LOST';
  const nextStage = lost ? null : nextOnRoute(catalog, summary.salesStage, stageDecisions);
  // A no-deposit deal never passes ขั้น 11 (nothing for the account team to confirm): step over it.
  const next = nextStage?.code === 'DEPOSIT_RECEIVED' && depositBypassed(summary.depositPolicy)
    ? nextOnRoute(catalog, nextStage.code, stageDecisions)
    : nextStage;
  // `next` is on-route by construction; the explicit onRoute check is the belt to that braces — an off-route
  // stage must never be one click away, whatever else the server listed.
  const nextDecision = next ? stageDecisions.find((decision) => decision.stage === next.code) : null;
  const canAdvance = Boolean(next) && !next.auto && nextDecision?.onRoute !== false
    && hasAction('ADVANCE_STAGE', next.code);
  // The next stage is reachable EXCEPT the readiness gate (a follow-up date + a logged activity since the last
  // stage change) is unmet — the ONE block the acting sales rep clears themselves. When true, the guided-advance
  // flow replaces the dead "อัปเดตโดย…" hint.
  const readinessBlocked = Boolean(next) && !next.auto && !canAdvance
    && Boolean(nextDecision) && !nextDecision.allowed
    && nextDecision.blockedReason === STAGE_ADVANCE_GATE_MESSAGE;
  // When the next stage isn't one this user can one-click into, explain who or what advances it instead of
  // showing a dead end. Suppressed while the guided-advance button stands in for it.
  const nextHint = next && !canAdvance && !readinessBlocked
    ? (next.auto ? AUTO_STAGE_HINT[next.code] : `ขั้นถัดไปอัปเดตโดย${GATE_LABEL[next.gate]}`)
    : null;
  // With the route choice as the main button, ถัดไป names the parties' own quote stages (the first, then "หรือข้ามไป"
  // the rest) with the first one's auto hint — not the stage after the current one. The first party's stage missing
  // from the catalog: the plain next-stage line.
  const [choiceStage, ...skipStages] = (quoteParties ?? []).map((party) => findStage(catalog, stageForQuotationRecipient(party)));
  const ctaRow = ctaStage ? findStage(catalog, ctaStage) : null;
  return {
    next,
    nextDecision,
    canAdvance,
    readinessBlocked,
    lineStage: choiceStage ?? ctaRow ?? next,
    lineHint: choiceStage ? AUTO_STAGE_HINT[choiceStage.code] : ctaRow ? AUTO_STAGE_HINT[ctaRow.code] : nextHint,
    lineSkips: choiceStage ? skipStages.filter(Boolean) : [],
  };
}

/** ONE "what happens next, and who moves it" line, from nextStageLine(); nothing when there is no next stage. */
export function NextStageLine({ line, entryChannel }) {
  if (!line?.lineStage) return null;
  return (
    <div data-testid="deal-stage-next" className="text-sm leading-snug text-text">
      <span className="font-bold text-text-muted">ถัดไป: </span>
      <span className="font-extrabold">{line.lineStage.no}. {dealStageLabel(line.lineStage.code, entryChannel).label}</span>
      {line.lineSkips.map((stage) => (
        <span key={stage.code}> หรือข้ามไป <span className="font-extrabold">{stage.no}. {dealStageLabel(stage.code, entryChannel).label}</span></span>
      ))}
      {line.lineHint ? <span className="text-text-secondary"> — {line.lineHint}</span> : null}
    </div>
  );
}

/** The S1 stages: a rep moves a deal on from here to S2 by hand (spec 2026-11-15 §7.1). */
export const S1_STAGES = ['LEAD_APPROACH', 'PRESENTATION'];

/**
 * May this viewer move the deal from S1 to S2 through the ย้ายไป S2 dialog (drawing m-stage)? Read entirely off the
 * server's per-viewer answer, like every other gate here: the deal is ACTIVE at an S1 stage and its SPEC_APPROVED
 * decision is on-route and either allowed or blocked ONLY by the readiness gate — the one block the dialog itself
 * clears. TicketService.stageDecisions runs the stage WRITE gate before the readiness gate, so a readiness-only
 * block already means "this viewer may write S2": exactly the reach the old เลื่อนไป / guided-advance button had
 * (`advanceAwaitsReadiness`). The server re-checks on the move. (An allowed decision is itself what makes the server
 * list ADVANCE_STAGE / UPDATE_STAGE, so no action needs checking on top.)
 */
export function canMoveDealToS2({ summary, stageDecisions = [] }) {
  if ((summary?.lifecycle ?? 'ACTIVE') !== 'ACTIVE' || !S1_STAGES.includes(summary?.salesStage)) return false;
  const decision = stageDecisions.find((d) => d.stage === 'SPEC_APPROVED');
  if (!decision || decision.onRoute === false) return false;
  return decision.allowed || decision.blockedReason === STAGE_ADVANCE_GATE_MESSAGE;
}

function daysSince(iso) {
  if (!iso) return null;
  return Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 86400000));
}

/**
 * Deal pipeline panel: the journey this deal must travel, with the current stage front and
 * center. One ticket = one deal; the operational price-request/dual-track machinery below the
 * panel is HOW some stages get done, and doc generation surfaces here on exactly the stage it
 * belongs to (docActions is rendered by the parent from its real `can` permission flags).
 *
 * แก้ไขสถานะ… / พัก dormant / "เลื่อนไป: <next stage>" live in TicketDetailPage's header ⋯ menu,
 * พักงาน / ปิดงาน (เสียงาน) in the quiet links at the foot of ภาพรวม, and ดำเนินการต่อ / เปิดงานอีกครั้ง
 * in the ขั้นต่อไป card (deal page redesign, 2026-11-16) — not inline here: the resolver-derived
 * action owns the "one thing to click" slot. The decision of whether each is AVAILABLE stays entirely in this component
 * (`canEditStage`/`canLost`/`canHold`/`canDormant`/`canAdvance` below, backed ENTIRELY by the
 * server's own answer: `availableActions` and the per-stage `stageDecisions` payload, with no local
 * re-derivation of who may do what). This forwardRef only exposes WHEN each one is available (so
 * the parent knows whether to render a menu item / danger button at all) and functions that open
 * this panel's own modals (so the submit/mutation logic and the "who may act" re-check never leave
 * this component). A ref caller that opens something this deal doesn't allow is a no-op: the guard
 * is re-checked inside `openEditStage`/`openHold`/`openDormant`/`openMarkLost`/`openAdvance`
 * themselves (`openAdvance` re-checks `canAdvance` AND the `advanceReady` precondition before
 * calling `onUpdateStage`). `nextHint`/the "ถัดไป:" line stay in this panel: they are
 * informational, not a second copy of the action.
 *
 * Since the redesign it renders as a slim strip under the header: the "ถัดไป:" line, the guided
 * advance, the tender `<select>`, docActions, and the route line + "ดูขั้นตอนทั้งหมด" disclosure
 * (stage owner, phases, the full stepper with its แก้ช่องทาง remedy). The S stepper and the
 * ขั้นต่อไป card in DealHeader replaced its "สถานะดีล (Pipeline)" title, numbered pip and the
 * paused / lost banners.
 */
export const DealStagePanel = forwardRef(function DealStagePanel({
  summary, availableActions = [], stageDecisions = [], catalog = EMPTY_STAGE_CATALOG,
  docActions, primaryAction, actionLoading,
  advanceReady = true, advanceInHeader = false,
  // The parties the sticky เสนอราคา menu offers, in route order.
  quoteParties = null,
  // The stage the page's main button leads to (e.g. ยืนยันคำสั่งซื้อ → ORDER_RECEIVED); ถัดไป names it.
  ctaStage = null,
  // The ticket's events, oldest first — the stepper reads which stages the deal skipped from them.
  events = [],
  onUpdateStage, onMarkLost, onReopen, onHold, onDormant, onResume, onSetTenderRequirement,
  onSetEntryChannel,
  // The deal page shows the "ถัดไป" line in its ขั้นต่อไป card instead (one "what's next" statement); a standalone
  // panel keeps its own.
  showNextLine = true,
  // Whether a deal_activity exists since the last stage change (the page computes it from activities + events); the
  // ย้ายไป S2 dialog asks for the note as that activity only when this is false. null = not known.
  hasRecentActivity = null,
}, ref) {
  const [editOpen, setEditOpen] = useState(false);
  const [lostOpen, setLostOpen] = useState(false);
  // 'resume' only now: hold / dormant open the one พักงาน dialog (pauseKind) since the m-hold drawing.
  const [noteAction, setNoteAction] = useState(null);
  const [pauseKind, setPauseKind] = useState(null); // null | 'ON_HOLD' | 'DORMANT' — which choice พักงาน opens on
  const [moveS2Open, setMoveS2Open] = useState(false);
  const [note, setNote] = useState('');
  const [showSteps, setShowSteps] = useState(false);
  // Guided advance: the readiness gate stays, but instead of a dead hint the
  // rep gets one flow that collects the two things it needs, then advances.
  const [guidedOpen, setGuidedOpen] = useState(false);
  // The stage the ขั้น 12 skip confirm was opened for; confirm posts this, never a re-derived `next`.
  const [procurementSkipTarget, setProcurementSkipTarget] = useState(null);
  // A deal that left ขั้น 12 drops the captured target, so coming back never reopens the dialog.
  useEffect(() => { if (summary.salesStage !== 'PROCUREMENT') setProcurementSkipTarget(null); }, [summary.salesStage]);
  const [guidedDraft, setGuidedDraft] = useState({ nextFollowUpAt: '', kind: 'CALL', activityNote: '', winProbability: '' });
  const queryClient = useQueryClient();

  const hasAction = (action, targetStage = null) => availableActions.some((item) =>
    item.action === action && (targetStage == null || item.targetStage === targetStage));
  // lifecycle, not lostReason — the reason persists after a reopen (V57).
  const lost = summary.lifecycle === 'CLOSED_LOST';
  const lifecycle = summary.lifecycle ?? (lost ? 'CLOSED_LOST' : 'ACTIVE');
  const meta = findStage(catalog, summary.salesStage);
  // Route-aware: the channel re-words S3, and the next step steps over stages
  // this deal's route does not visit. Both are read off the server's answer — `entryChannel` on the
  // summary, `onRoute` on each stage decision — never derived here. Absent => today's behaviour.
  const label = dealStageLabel(summary.salesStage, summary.entryChannel);
  // The next stage, whether this viewer can one-click into it, and the "ถัดไป" line — one pure computation shared with
  // the page, which shows the line in the ขั้นต่อไป card (fix round 1, L3: one "what's next" statement on the page).
  const {
    next, canAdvance, readinessBlocked, lineStage, lineSkips, lineHint,
  } = nextStageLine({
    summary, availableActions, stageDecisions, catalog, quoteParties, ctaStage,
  });
  const route = routeName(summary.entryChannel);
  const routeStageCount = routePath(catalog, stageDecisions).length;
  const days = daysSince(summary.stageUpdatedAt);
  // Every gate below is read off the server's answer, never recomputed. `hasAction` is the
  // backend's availableActions list (TicketService.actions) and `stageDecisions` is its per-stage
  // verdict; a local re-derivation would be a second implementation of TicketService's
  // authorization that goes stale.
  const canEditStage = hasAction('UPDATE_STAGE')
    && stageDecisions.some((decision) => decision.allowed) && !lost;
  const canLost = hasAction('MARK_LOST') && !lost && summary.salesStage !== 'CLOSED_PAID';
  // The page's main button carries เลื่อนไป on ขั้น 1–2; one copy on the page.
  // No เลื่อนไป beside a main button that leads elsewhere (ยืนยันคำสั่งซื้อ → 10).
  const showGuidedOpener = readinessBlocked && !advanceInHeader && !(ctaStage === 'ORDER_RECEIVED' && ctaStage !== next?.code);
  // One click, three writes IN ORDER: set the follow-up date (+ optional win%), log the activity, then advance
  // through the PARENT's own onUpdateStage so its invalidation + toast still run. The gate itself is unchanged —
  // this just satisfies it in one place instead of two scattered panels.
  const guidedAdvance = useMutation({
    mutationFn: async () => {
      await api.tickets.updateTracking(summary.id, {
        nextFollowUpAt: guidedDraft.nextFollowUpAt || null,
        winProbability: guidedDraft.winProbability === '' ? null : Number(guidedDraft.winProbability),
      });
      await api.tickets.addActivity(summary.id, {
        activityDate: bangkokTodayIso(),
        kind: guidedDraft.kind,
        note: guidedDraft.activityNote.trim() || null,
      });
      await onUpdateStage({ stage: next.code });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(summary.id) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(summary.id) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketActivities(summary.id) });
      setGuidedOpen(false);
      setGuidedDraft({ nextFollowUpAt: '', kind: 'CALL', activityNote: '', winProbability: '' });
    },
  });
  const canHold = hasAction('PLACE_ON_HOLD');
  const canDormant = hasAction('MARK_DORMANT');
  const canMoveToS2 = canMoveDealToS2({ summary, stageDecisions });
  // Which half of the readiness gate is missing, when it is the gate that blocks S2.
  const s2Decision = stageDecisions.find((decision) => decision.stage === 'SPEC_APPROVED');
  const s2ReadinessBlocked = Boolean(s2Decision) && !s2Decision.allowed
    && s2Decision.blockedReason === STAGE_ADVANCE_GATE_MESSAGE;
  const s2NeedsFollowUp = s2ReadinessBlocked && !summary.nextFollowUpAt;
  const s2NeedsActivity = s2ReadinessBlocked
    && (hasRecentActivity === false || (hasRecentActivity == null && Boolean(summary.nextFollowUpAt)));
  // ย้ายไป S2: clear whatever half of the gate is missing, then move through the PARENT's onUpdateStage (its
  // invalidation and toast still run). The gate itself is unchanged.
  const moveToS2 = useMutation({
    mutationFn: async ({ note, nextFollowUpAt }) => {
      if (s2NeedsFollowUp && nextFollowUpAt) {
        // PUT /tickets/{id}/tracking REPLACES every tracking column (TicketRepository.updateTracking), so the fields
        // this dialog does not edit are sent back exactly as the deal holds them.
        await api.tickets.updateTracking(summary.id, {
          nextFollowUpAt,
          designerName: summary.designerName ?? null,
          ownerName: summary.ownerName ?? null,
          buyerName: summary.buyerName ?? null,
          winProbability: summary.winProbabilityOverride ?? null,
        });
      }
      if (s2NeedsActivity && note) {
        await api.tickets.addActivity(summary.id, { activityDate: bangkokTodayIso(), kind: 'OTHER', note });
      }
      await onUpdateStage({ stage: 'SPEC_APPROVED', note: note || undefined });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(summary.id) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketActivities(summary.id) });
      setMoveS2Open(false);
    },
  });
  const canResume = hasAction('RESUME');
  const canTender = hasAction('SET_TENDER_REQUIREMENT') && summary.salesStage === 'AWAITING_BUYER';
  // "แก้ช่องทางดีล" is the remedy for a route refusal, not standing chrome (the ช่องทางรับงาน row is
  // not on this panel). The server's own advertisement is handed down to the two places a refusal
  // is READ (the stepper's off-route rows, UpdateStageModal's blocked list), which render nothing
  // without it. Its `requiredFields` decides the reason field.
  const entryChannelAction = availableActions.find((item) => item.action === 'SET_ENTRY_CHANNEL');
  const isDone = !lost && summary.salesStage === 'CLOSED_PAID';

  useImperativeHandle(ref, () => ({
    canEditStage, canLost, canHold, canDormant, canMoveToS2,
    // An in-flight mutation must block a second click: the overflow menu item only disables its
    // own click on its OWN precondition (e.g. `!readyToAdvance`) and never re-derives
    // actionLoading, so a stale double-click (⋯ → click, reopen ⋯ while the first mutation is
    // pending → click again) would fire two requests, the second landing as a 409 red toast
    // (TicketService.java:1143, "Deal is already in stage X"). actionLoading is a plain boolean
    // prop from the parent's useMutation().isPending; undefined (no prop passed, e.g. some tests)
    // is falsy.
    openEditStage: () => { if (canEditStage && !actionLoading) setEditOpen(true); },
    openMarkLost: () => { if (canLost) setLostOpen(true); },
    // The one พักงาน dialog (m-hold): พักชั่วคราว from the quiet link, ไม่เคลื่อนไหว from ⋯ พัก dormant. Same gates.
    openHold: () => { if (canHold && !actionLoading) setPauseKind('ON_HOLD'); },
    openDormant: () => { if (canDormant && !actionLoading) setPauseKind('DORMANT'); },
    // ย้ายไป S2 · สเปคผ่าน (m-stage), the ขั้นต่อไป card's button on an S1 deal. Re-checks canMoveDealToS2.
    openMoveToS2: () => { if (canMoveToS2 && !actionLoading) { moveToS2.reset(); setMoveS2Open(true); } },
    // The old paused / lost banners' buttons, now in the ขั้นต่อไป card. Same gates as those banners had:
    // ดำเนินการต่อ on a paused deal with RESUME, เปิดงานอีกครั้ง on a lost deal with REOPEN. The page mirrors
    // these gates to decide whether to show the button; the re-check here is the guard.
    openResume: () => {
      if ((lifecycle === 'ON_HOLD' || lifecycle === 'DORMANT') && canResume && !actionLoading) setNoteAction('resume');
    },
    reopen: () => { if (lost && hasAction('REOPEN') && !actionLoading) onReopen(); },
    // Re-checks the real gate (canAdvance), the readiness precondition (advanceReady), AND
    // actionLoading (see the note above).
    openAdvance: () => {
      if (!(canAdvance && advanceReady && !actionLoading && next)) return;
      if (summary.salesStage === 'PROCUREMENT' && !goodsArrived(summary.fulfillmentStatus)) setProcurementSkipTarget(next); // warn before skipping ahead
      else onUpdateStage({ stage: next.code });
    },
    // The page's ขั้น 1–2 main button: move now when ready, else collect the follow-up + activity first.
    startAdvance: () => {
      if (!next || actionLoading) return;
      if (canAdvance && advanceReady) onUpdateStage({ stage: next.code });
      else if (canAdvance || readinessBlocked) setGuidedOpen(true);
    },
  }));

  // A skipped stage is final: แก้ไขสถานะ never offers what the track marks ข้าม.
  // Only a skipped OWNER stage is final; any other never-visited stage (e.g. the routine 4 -> 3) stays offered.
  const skippedAll = skippedStageCodes(catalog, stageIndexIn(catalog, summary.salesStage), events);
  const skipped = skippedAll.has('QUOTE_OWNER')
    ? new Set(['QUOTE_OWNER', 'OWNER_SIGNOFF', 'AWAITING_BUYER'].filter((code) => skippedAll.has(code)))
    : new Set();
  const showGoodsWarning = summary.salesStage === 'PROCUREMENT' && !goodsArrived(summary.fulfillmentStatus);

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
    if (noteAction === 'resume') await onResume(payload);
    setNoteAction(null);
    setNote('');
  }

  // พักงาน → the SAME two handlers (and so the same two endpoints) as before, now carrying pauseReturnOn.
  async function submitPause({ kind, pauseReturnOn, note: pauseNote }) {
    const payload = { note: pauseNote, pauseReturnOn };
    if (kind === 'ON_HOLD') await onHold(payload);
    else await onDormant(payload);
    setPauseKind(null);
  }

  // The deal page's S stepper and ขั้นต่อไป card (DealHeader) replaced this panel's "สถานะดีล (Pipeline)" shell, its
  // numbered pip and its paused / lost banners (their ดำเนินการต่อ / เปิดงานอีกครั้ง now sit in the card, through the
  // ref above). What stays here is what the card does not say: the exact next stage and who moves it, the guided
  // advance, the tender select, this stage's documents, and the full 15-stage route behind ดูขั้นตอนทั้งหมด.
  return (
    // data-stage-no: the catalogue's absolute stage number, which the visible numbered pip used to carry (the e2e
    // suite pins the 15-stage position through it; the S stepper only shows the rep code).
    <section data-testid="deal-stage-panel" data-stage-no={meta?.no ?? ''} aria-label="รายละเอียดขั้นตอน" className="rounded-md border border-border bg-surface">
      <div className="flex flex-col gap-3 px-4 py-3 sm:px-5">
        {/* Closing the deal does NOT create the rep's commission. Only the accountant recording
            the tax invoice does (POST /api/commissions/from-deal, account-only), and that same
            upload is what flips invoiceOnFile. Until then a CLOSED_PAID deal has earned the rep
            nothing, so say so. */}
        {summary.salesStage === 'CLOSED_PAID' && !summary.invoiceOnFile ? (
          <div
            className="rounded-md border border-warning-border bg-warning-bg-soft px-4 py-3"
            data-testid="awaiting-invoice-for-commission"
          >
            <div className="text-sm font-extrabold text-text">รอฝ่ายบัญชีบันทึกใบกำกับภาษี</div>
            <div className="mt-0.5 text-xs text-text-muted">
              ปิดการขายแล้ว แต่ค่าคอมมิชชันของผู้ดูแลงานจะยังไม่เกิดขึ้น
              จนกว่าฝ่ายบัญชีจะบันทึกใบกำกับภาษีของงานนี้
            </div>
          </div>
        ) : null}

        {lifecycle === 'ON_HOLD' || lifecycle === 'DORMANT' || lost ? null : (
          <div className="flex flex-col gap-3">
            {/* ONE "what happens next, and who moves it" line: the hint (the only sentence that
                says whose move it is) must not be the least prominent thing on screen. */}
            {showNextLine && !isDone ? (
              <NextStageLine line={{ lineStage, lineSkips, lineHint }} entryChannel={summary.entryChannel} />
            ) : null}

            {/* CLOSED_PAID's "done" line is the ขั้นต่อไป card's now. */}
            {!isDone && (primaryAction || showGuidedOpener) ? (
              <div className="flex flex-wrap items-center gap-2">
                {primaryAction}
                {/* Not a dead hint: the rep CAN advance — they just have to log the follow-up + an activity first, which this
                 * button collects and then advances, in one go. The "who moves it" hint for everyone else is part of the ถัดไป
                 * line above. */}
                {showGuidedOpener ? (
                  <Button
                    type="button"
                    variant={summary.salesStage === 'PROCUREMENT' ? 'secondary' : 'primary'}
                    disabled={actionLoading}
                    onClick={() => setGuidedOpen(true)}
                    data-testid="guided-advance-open"
                  >
                    {manualMoveLabel(summary.salesStage, next, dealStageLabel(next.code, summary.entryChannel).label)}
                  </Button>
                ) : null}
              </div>
            ) : null}

            {canTender ? (
              <div className="flex flex-wrap items-center border-t border-border pt-3">
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
              </div>
            ) : null}

            {/* Stage-gated documents: the doc that belongs to THIS stage of the deal (quotation at the quote stages,
             * deposit notice at order, IR at procurement...) — parent renders them from real `can` flags. */}
            {docActions ? (
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs font-bold text-text-muted">เอกสารของขั้นนี้:</span>
                {docActions}
              </div>
            ) : null}
          </div>
        )}

        <div className="flex flex-wrap items-center justify-between gap-2">
          {/* One text line naming the route and its stage count. Nothing at all when the deal has no route
              (UNSPECIFIED / unknown / absent channel). */}
          {route ? (
            <span data-testid="deal-route-line" className="text-xs font-bold text-text-muted">
              เส้นทาง · {route} · {routeStageCount} ขั้น{days != null ? ` · อยู่ในขั้นนี้ ${days === 0 ? 'วันนี้' : `${days} วัน`}` : ''}
            </span>
          ) : entryChannelAction && onSetEntryChannel ? (
            // No route because no channel was stated — the short create form (m-create) does not ask for one. Where
            // the route would be named, the rep can state it (the server advertises SET_ENTRY_CHANNEL).
            <div data-testid="entry-channel-unstated" className="flex min-w-0 flex-wrap items-center gap-x-2">
              <span className="text-xs font-bold text-text-muted">ยังไม่ได้ระบุช่องทางงาน</span>
              <EntryChannelFix
                entryChannel={summary.entryChannel}
                action={entryChannelAction}
                onSubmit={onSetEntryChannel}
                disabled={actionLoading}
                noun="งาน"
              />
            </div>
          ) : <span />}
          <Button
            type="button"
            variant="text"
            className="min-h-[44px] text-xs mobile:min-h-[44px]"
            aria-expanded={showSteps}
            onClick={() => setShowSteps((v) => !v)}
          >
            {showSteps ? 'ซ่อนขั้นตอนทั้งหมด' : `ดูขั้นตอนทั้งหมด (${routeStageCount} ขั้น)`}
          </Button>
        </div>

        {showSteps ? (
          <div className="flex flex-col gap-4 border-t border-border pt-4">
            {/* The current stage by its catalogue number and headline, and the department that owns it — what the
                old panel's numbered pip block said, one click away now. */}
            {meta ? (
              <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 text-sm">
                <span className="font-extrabold text-text">
                  <span className="tabular-nums">{meta.no}. </span>
                  <span data-testid="deal-stage-headline">{STAGE_HEADLINE[summary.salesStage] ?? label.label}</span>
                </span>
                {GATE_LABEL[STAGE_OWNER_DISPLAY[meta.code] ?? meta.gate] ? (
                  <span data-testid="deal-stage-owner" className="text-xs font-bold text-text-muted">
                    {`ผู้รับผิดชอบ: ${GATE_LABEL[STAGE_OWNER_DISPLAY[meta.code] ?? meta.gate]}`}
                  </span>
                ) : null}
              </div>
            ) : null}
            <PhaseSummary catalog={catalog} salesStage={summary.salesStage} lost={lost} stageDecisions={stageDecisions} />
            <PhaseTracker catalog={catalog} salesStage={summary.salesStage} lost={lost} />
            <DealStageStepper
              catalog={catalog}
              salesStage={summary.salesStage}
              lost={lost}
              stageDecisions={stageDecisions}
              events={events}
              entryChannel={summary.entryChannel}
              entryChannelAction={entryChannelAction}
              onSetEntryChannel={onSetEntryChannel}
              actionLoading={actionLoading}
            />
          </div>
        ) : null}
      </div>

      {editOpen ? (
        <UpdateStageModal
          deal={summary}
          stageDecisions={stageDecisions.filter((d) => !(skipped.has(d.stage) && d.onRoute !== false))}
          submitting={actionLoading}
          onClose={() => setEditOpen(false)}
          onSubmit={submitStage}
          entryChannelAction={entryChannelAction}
          onSetEntryChannel={onSetEntryChannel}
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
      {pauseKind ? (
        <PauseDealModal
          canHold={canHold}
          canDormant={canDormant}
          initialKind={pauseKind}
          submitting={actionLoading}
          onClose={() => setPauseKind(null)}
          onSubmit={submitPause}
        />
      ) : null}
      {moveS2Open ? (
        <MoveToS2Modal
          deal={summary}
          needsFollowUp={s2NeedsFollowUp}
          needsActivity={s2NeedsActivity}
          submitting={moveToS2.isPending || actionLoading}
          error={moveToS2.isError ? (moveToS2.error?.message ?? 'ย้ายไม่สำเร็จ') : null}
          onClose={() => setMoveS2Open(false)}
          onSubmit={(values) => moveToS2.mutate(values)}
        />
      ) : null}
      {noteAction ? (
        <Modal
          title="ดำเนินการต่อ"
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
      <ConfirmDialog
        open={Boolean(procurementSkipTarget) && summary.salesStage === 'PROCUREMENT'}
        title={`ข้ามไปขั้น ${procurementSkipTarget?.no}`}
        message={goodsNotArrivedWarning(procurementSkipTarget?.no)}
        confirmLabel={`ข้ามไปขั้น ${procurementSkipTarget?.no}`}
        onCancel={() => setProcurementSkipTarget(null)}
        onConfirm={() => {
          const target = procurementSkipTarget;
          setProcurementSkipTarget(null);
          if (!actionLoading && summary.salesStage === 'PROCUREMENT') onUpdateStage({ stage: target.code });
        }}
      />
      {guidedOpen && next ? (
        <Modal
          title={`เลื่อนไปขั้น ${next.no}. ${dealStageLabel(next.code, summary.entryChannel).label}`}
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
            {showGoodsWarning ? <p className="m-0 text-xs font-bold text-warning-dark">{goodsNotArrivedWarning(next.no)}</p> : null}
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              วันติดตามครั้งถัดไป *
              <ThaiDateInput
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
    </section>
  );
});
