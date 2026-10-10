import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';

// Per-phase accents (design tokens --color-phase-N, from the user's Claude
// Design prototype). Static map — Tailwind needs full class names in source.
const PHASE_FILL = {
  1: 'bg-phase-1',
  2: 'bg-phase-2',
  3: 'bg-phase-3',
  4: 'bg-phase-4',
  5: 'bg-phase-5',
};
import { dealStageLabel } from '../../utils/format.js';
import { EntryChannelFix } from './EntryChannelFix.jsx';
import {
  EMPTY_STAGE_CATALOG, routeForChannel, routePath, routePosition, stageIndexIn, stagesInPhase,
} from './stageCatalog.js';
import { GATE_LABEL } from './stageMeta.js';

// The Thai names of the five phases. The phase LIST (which ids exist, and which stages sit in
// each) comes from the backend catalog; only the wording is ours. See stageMeta.js's header for
// the split.
const PHASE_NAME = {
  1: { name: 'การเข้าถึงโครงการ', helper: 'Lead' },
  2: { name: 'งานสเปค', helper: 'Specification' },
  3: { name: 'ประมูลและเจรจา', helper: 'Bidding' },
  4: { name: 'คำสั่งซื้อและนำเข้า', helper: 'Order & import' },
  5: { name: 'ส่งมอบและปิดงาน', helper: 'Delivery & closing' },
};

function phaseName(phaseId) {
  return PHASE_NAME[phaseId]?.name ?? `เฟส ${phaseId}`;
}

/**
 * The deal's route as one row of numbered pips — ONLY the stages this deal's route visits. A stage
 * that is off-route is absent here, and that absence is the point: a buyer-direct deal reads as
 * eleven steps, not fifteen with four greyed out. Where the skipped stages went is answered by the
 * off-route group below the phase list.
 *
 * Pip numbers are the stages' own business numbers (S8 is "8"), the same number the panel badge and
 * the update-stage dialog print, so a buyer-direct route visibly jumps 3 → 8. Progress in words
 * ("ขั้นที่ 4 จาก 11") is PhaseSummary's job. State is carried by shape AND a Thai word (screen-
 * reader text), never by colour alone: done = tick, current = filled, upcoming = hollow.
 */
function RouteRibbon({ path, currentIdx, catalog, lost }) {
  if (path.length === 0) return null;
  return (
    <div className="border-b border-border px-4 py-3">
      <div className="mb-2 text-xs font-bold text-text-muted">เส้นทางของดีลนี้ ({path.length} ขั้น)</div>
      <ol data-testid="route-ribbon" className="m-0 flex list-none flex-wrap gap-1.5 p-0">
        {path.map((step, position) => {
          // Owner ruling 2026-09-30: the pip carries the ROUTE'S ordinal (1..N), NOT step.no (the
          // business S-number). On a buyer-direct deal S8 is the 4th of 11 steps, and a pip reading
          // "8" beside a line reading "ขั้นที่ 4 จาก 11" made the rep stop and reconcile two numbers.
          // The business number still identifies the stage in the header, list and แก้ไขสถานะ modal;
          // here the question is "how far along am I", so position wins.
          const ordinal = position + 1;
          const idx = stageIndexIn(catalog, step.code);
          const done = idx < currentIdx;
          const current = idx === currentIdx && !lost;
          return (
            <li
              key={step.code}
              data-testid="route-pip"
              data-stage={step.code}
              aria-current={current ? 'step' : undefined}
              className={`relative grid h-[22px] min-w-[22px] place-items-center rounded-full px-1 text-2xs font-extrabold ${
                done
                  ? 'bg-success-bg text-success-dark'
                  : current
                    ? 'bg-info text-surface'
                    : 'border border-border bg-surface text-text-muted'
              }`}
            >
              <span aria-hidden="true">{done ? <Icon name="check" size={12} /> : ordinal}</span>
              <span className="sr-only">
                {`ขั้นที่ ${ordinal} `}{done ? 'เสร็จแล้ว' : current ? 'ขั้นปัจจุบัน' : 'ยังไม่ถึง'}
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

/**
 * The stages this deal's route does NOT visit, each with the server's own reason. Follows
 * UpdateStageModal's blocked list: an off-route stage is explained, not hidden and not silently
 * dead. `blockedReason` is rendered verbatim (DealRoute.refusalMessage) so what a rep reads here is
 * exactly what the server would answer if they tried to move there.
 */
function OffRouteGroup({
  stages, decisionByCode, salesStage, channel, entryChannelAction, onSetEntryChannel, disabled,
}) {
  if (stages.length === 0) return null;
  return (
    <div data-testid="off-route-group" className="border-t border-border bg-surface-muted px-4 py-3">
      <div className="text-xs font-bold text-text-secondary">ไม่อยู่ในเส้นทางนี้ ({stages.length})</div>
      <ul className="m-0 mt-2 flex list-none flex-col gap-2 p-0">
        {stages.map((step) => (
          <li key={step.code} className="grid grid-cols-[24px_1fr] gap-2.5">
            <span className="grid h-6 w-6 shrink-0 place-items-center rounded-lg bg-surface-subtle text-2xs font-extrabold text-text-muted">
              {step.no}
            </span>
            <span className="min-w-0">
              <span className="block text-xs font-bold text-text-secondary">
                {dealStageLabel(step.code, channel).label}
                {step.code === salesStage ? (
                  <span className="ml-2 rounded-full bg-surface-subtle px-2 py-0.5 text-2xs font-bold text-text-muted">
                    ดีลอยู่ขั้นนี้
                  </span>
                ) : null}
              </span>
              <span className="block text-2xs leading-snug text-text-muted [overflow-wrap:anywhere]">
                {decisionByCode.get(step.code)?.blockedReason
                  ?? 'ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน'}
              </span>
              {/* The remedy for the sentence above, where it is read. Renders nothing unless the
                  server advertised SET_ENTRY_CHANNEL (and a handler is wired). */}
              <EntryChannelFix
                entryChannel={channel}
                action={entryChannelAction}
                onSubmit={onSetEntryChannel}
                disabled={disabled}
                context={dealStageLabel(step.code, channel).label}
              />
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Phase accordion for the deal pipeline. Only the current phase starts expanded so the page never
 * shows every stage at once; completed phases collapse behind a ✓ header.
 *
 * The stage list is the backend's (`catalog`), not this file's — it was a hardcoded 14-entry array
 * until V143 added a fifteenth stage and this component silently kept rendering fourteen.
 *
 * Route-aware: `stageDecisions` carries the server's per-stage `onRoute`. Only on-route stages get
 * a row in the phase list and a pip in the ribbon; the rest are listed once, with the server's
 * reason, in the off-route group. With no decisions (or none carrying `onRoute`) every stage is
 * on-route and this renders exactly the full pipeline.
 *
 * `entryChannelAction` / `onSetEntryChannel` carry the "แก้ช่องทางดีล" remedy for the off-route
 * group's refusals (see EntryChannelFix): the server's own SET_ENTRY_CHANNEL advertisement and the
 * parent's handler. With either absent the rows render exactly as before.
 */
export function DealStageStepper({
  catalog = EMPTY_STAGE_CATALOG, salesStage, lost = false, stageDecisions = [], entryChannel,
  entryChannelAction, onSetEntryChannel, actionLoading = false,
}) {
  const currentIdx = stageIndexIn(catalog, salesStage);
  const currentPhase = catalog.stages[currentIdx]?.phase ?? 1;
  const [open, setOpen] = useState(() => ({ [currentPhase]: true }));
  const path = routePath(catalog, stageDecisions);
  const onRouteCodes = new Set(path.map((step) => step.code));
  const offRoute = catalog.stages.filter((step) => !onRouteCodes.has(step.code));
  const decisionByCode = new Map((stageDecisions ?? []).map((decision) => [decision.stage, decision]));

  function toggle(phaseId) {
    setOpen((prev) => ({ ...prev, [phaseId]: !prev[phaseId] }));
  }

  return (
    <div className="overflow-hidden rounded-xl border border-border bg-surface">
      <RouteRibbon path={path} currentIdx={currentIdx} catalog={catalog} lost={lost} />
      {catalog.phases.map((phaseId) => {
        const phaseStages = stagesInPhase(catalog, phaseId);
        if (phaseStages.length === 0) return null;
        // Phase done/current is judged over ALL of the phase's stages, so a deal sitting on an
        // off-route stage still lights its own phase. Only the ROWS and the count are route-filtered.
        const firstIdx = stageIndexIn(catalog, phaseStages[0].code);
        const lastIdx = stageIndexIn(catalog, phaseStages[phaseStages.length - 1].code);
        const steps = phaseStages.filter((step) => onRouteCodes.has(step.code));
        if (steps.length === 0) return null;
        const isDone = currentIdx > lastIdx;
        const isCurrent = currentIdx >= firstIdx && currentIdx <= lastIdx;
        const doneCount = steps.filter((step) => stageIndexIn(catalog, step.code) <= currentIdx).length;
        const isOpen = !!open[phaseId];
        return (
          <div key={phaseId} className="border-b border-border last:border-b-0">
            <button
              type="button"
              className="flex w-full items-center gap-3 bg-transparent px-4 py-3 text-left"
              aria-expanded={isOpen}
              onClick={() => toggle(phaseId)}
            >
              <span
                className={`grid h-6 w-6 shrink-0 place-items-center rounded-lg text-xs font-extrabold ${
                  isDone
                    ? 'bg-success-bg text-success-dark'
                    : isCurrent && !lost
                      ? 'bg-info-bg text-info'
                      : 'bg-surface-subtle text-text-muted'
                }`}
              >
                {isDone ? <Icon name="check" size={13} /> : phaseId}
              </span>
              <span className="min-w-0 flex-1">
                {/* WCAG AA fix (fix/ui-contrast-tokens): a PHASE_TEXT map used to apply
                    --color-phase-N directly to this label. As text-on-white (11px/800,
                    not "large text") three of the five phase colors fail the 4.5:1
                    floor — phase-1 4.47, phase-2 2.77, phase-5 3.77 — only phase-3
                    (6.29) and phase-4 (5.47) happened to pass. Rather than mint five
                    new text-safe phase variants for this one call site, the label now
                    always uses the app's normal text tokens; the fill bar directly
                    below already carries the full phase hue, so the phase-specific
                    color isn't lost, just moved off text onto the decorative element
                    that can safely carry it. Applied uniformly across all 5 phases for
                    consistency (no phase looks different from its siblings depending
                    on whether it happened to pass). */}
                <span className={`block text-sm font-extrabold ${isCurrent && !lost ? 'text-text' : 'text-text-muted'}`}>
                  เฟส {phaseId} · {phaseName(phaseId)}
                </span>
                <span className="block text-2xs text-text-muted">
                  {isDone ? 'เสร็จแล้ว' : isCurrent ? `${doneCount}/${steps.length} ขั้นตอน` : `${steps.length} ขั้นตอน`}
                </span>
              </span>
              <span className={`shrink-0 text-text-muted transition-transform ${isOpen ? 'rotate-180' : ''}`}>
                <Icon name="chevronDown" size={16} />
              </span>
            </button>
            {isOpen ? (
              <div className="px-4 pb-3">
                {steps.map((step) => {
                  const idx = stageIndexIn(catalog, step.code);
                  const stepDone = idx < currentIdx;
                  const stepCurrent = idx === currentIdx;
                  const label = dealStageLabel(step.code, entryChannel);
                  return (
                    <div key={step.code} className="grid grid-cols-[26px_1fr] gap-3">
                      <div className="flex flex-col items-center">
                        <span
                          className={`grid h-[22px] w-[22px] shrink-0 place-items-center rounded-full text-2xs font-extrabold ${
                            stepDone
                              ? 'bg-success-bg text-success-dark'
                              : stepCurrent && !lost
                                ? 'bg-info text-surface'
                                : 'border border-border bg-surface text-text-muted'
                          }`}
                        >
                          {stepDone ? <Icon name="check" size={12} /> : step.no}
                        </span>
                        <span className={`w-0.5 flex-1 ${stepDone ? 'bg-success-soft' : 'bg-border'}`} />
                      </div>
                      <div className="min-w-0 pb-3">
                        <div className={`text-sm leading-snug ${stepCurrent && !lost ? 'font-extrabold text-text' : stepDone ? 'text-text-muted' : 'text-text-muted'}`}>
                          {label.label}
                        </div>
                        <span className="mt-1 inline-flex rounded-full bg-surface-subtle px-2 py-0.5 text-2xs font-bold text-text-muted">
                          {GATE_LABEL[step.gate]}
                        </span>
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : null}
          </div>
        );
      })}
      <OffRouteGroup
        stages={offRoute}
        decisionByCode={decisionByCode}
        salesStage={salesStage}
        channel={entryChannel}
        entryChannelAction={entryChannelAction}
        onSetEntryChannel={onSetEntryChannel}
        disabled={actionLoading}
      />
    </div>
  );
}

/**
 * Horizontal phase tracker. Lost projects render an empty track.
 */
export function PhaseTracker({ catalog = EMPTY_STAGE_CATALOG, salesStage, lost = false }) {
  const currentIdx = stageIndexIn(catalog, salesStage);
  return (
    <div className="flex items-start gap-2">
      {catalog.phases.map((phaseId) => {
        const steps = stagesInPhase(catalog, phaseId);
        if (steps.length === 0) return null;
        const firstIdx = stageIndexIn(catalog, steps[0].code);
        const lastIdx = stageIndexIn(catalog, steps[steps.length - 1].code);
        let fill = 0;
        if (!lost) {
          if (currentIdx > lastIdx) fill = 1;
          else if (currentIdx >= firstIdx) fill = (currentIdx - firstIdx + 1) / steps.length;
        }
        const isCurrent = !lost && currentIdx >= firstIdx && currentIdx <= lastIdx;
        return (
          <div key={phaseId} className="min-w-0 flex flex-1 basis-0 flex-col gap-1.5">
            <span className={`text-2xs font-extrabold ${isCurrent ? 'text-text-secondary' : 'text-text-muted'}`}>
              เฟส {phaseId}
            </span>
            <div className="h-2 overflow-hidden rounded-full bg-surface-subtle">
              <span
                className={`block h-full rounded-full ${lost ? 'bg-danger-bg' : PHASE_FILL[phaseId]}`}
                style={{ width: `${fill * 100}%` }}
              />
            </div>
            <span className="text-2xs font-semibold leading-tight text-text-muted [overflow-wrap:anywhere]">{phaseName(phaseId)}</span>
          </div>
        );
      })}
    </div>
  );
}

/**
 * One-line "where are we" for the default deal view: "เฟส 4 จาก 5 · <phase name>" over a single-colour
 * bar. PhaseTracker's five hues and five labels encode nothing a user decides on, so it now lives
 * only inside the expanded stepper.
 */
export function PhaseSummary({
  catalog = EMPTY_STAGE_CATALOG, salesStage, lost = false, stageDecisions = [],
}) {
  const currentIdx = stageIndexIn(catalog, salesStage);
  const phases = catalog.phases.filter((phaseId) => stagesInPhase(catalog, phaseId).length > 0);
  const currentPhase = phases.find((phaseId) => {
    const steps = stagesInPhase(catalog, phaseId);
    return currentIdx >= stageIndexIn(catalog, steps[0].code)
      && currentIdx <= stageIndexIn(catalog, steps[steps.length - 1].code);
  });
  if (currentPhase == null || lost) return null;
  // The denominator is THIS deal's route, not the catalog: a buyer-direct deal at S8 is step 4 of
  // 11, and the bar fills 4/11. `catalog.stages.length` said 8 of 15 for it.
  const { position, total } = routePosition(catalog, salesStage, stageDecisions);
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <span className="text-xs font-bold text-text-muted">
        เฟส {currentPhase} จาก {phases.length} · {phaseName(currentPhase)}
      </span>
      <div className="h-1.5 overflow-hidden rounded-full bg-surface-subtle" aria-hidden="true">
        <span className="block h-full rounded-full bg-info" style={{ width: `${(position / total) * 100}%` }} />
      </div>
      <span className="sr-only">ขั้นที่ {position} จาก {total}</span>
    </div>
  );
}

/**
 * Compact per-row progress bar for the list page (one proportional segment per phase).
 *
 * Route-aware: with an `entryChannel` each phase's segment counts only the stages that channel's
 * route visits (`catalog.routes`, served by the backend — see routeForChannel), so it reads the
 * same as the list's "ขั้นตอน 4/11" instead of contradicting it with a catalog-wide 15-stage
 * graphic. A phase with no on-route stage has no segment. `entryChannel` is optional: absent,
 * UNSPECIFIED, unknown or no served route is every stage on-route, i.e. the bar as it always was.
 * Fill counts the on-route stages up to and including the current one, the same rule as
 * routePositionForChannel, so a deal sitting on a since-off-route stage never over-fills.
 */
export function StageProgressBar({
  catalog = EMPTY_STAGE_CATALOG, salesStage, lost = false, entryChannel,
}) {
  const currentIdx = stageIndexIn(catalog, salesStage);
  const onRoute = new Set(routeForChannel(catalog, entryChannel).map((step) => step.code));
  return (
    <div className="flex items-center gap-0.5" aria-hidden="true">
      {catalog.phases.map((phaseId) => {
        const steps = stagesInPhase(catalog, phaseId).filter((step) => onRoute.has(step.code));
        if (steps.length === 0) return null;
        let fill = 0;
        if (!lost) {
          const passed = steps.filter((step) => stageIndexIn(catalog, step.code) <= currentIdx).length;
          fill = passed / steps.length;
        }
        return (
          <span
            key={phaseId}
            className="h-1.5 overflow-hidden rounded-full bg-surface-subtle"
            style={{ flex: steps.length }}
          >
            <span
              className={`block h-full rounded-full ${lost ? 'bg-danger-bg' : PHASE_FILL[phaseId]}`}
              style={{ width: `${fill * 100}%` }}
            />
          </span>
        );
      })}
    </div>
  );
}
