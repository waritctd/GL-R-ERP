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
import { EMPTY_STAGE_CATALOG, stageIndexIn, stagesInPhase } from './stageCatalog.js';
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
 * Phase accordion for the deal pipeline. Only the current phase starts expanded so the page never
 * shows every stage at once; completed phases collapse behind a ✓ header.
 *
 * The stage list is the backend's (`catalog`), not this file's — it was a hardcoded 14-entry array
 * until V143 added a fifteenth stage and this component silently kept rendering fourteen.
 */
export function DealStageStepper({ catalog = EMPTY_STAGE_CATALOG, salesStage, lost = false }) {
  const currentIdx = stageIndexIn(catalog, salesStage);
  const currentPhase = catalog.stages[currentIdx]?.phase ?? 1;
  const [open, setOpen] = useState(() => ({ [currentPhase]: true }));

  function toggle(phaseId) {
    setOpen((prev) => ({ ...prev, [phaseId]: !prev[phaseId] }));
  }

  return (
    <div className="overflow-hidden rounded-xl border border-border bg-surface">
      {catalog.phases.map((phaseId) => {
        const steps = stagesInPhase(catalog, phaseId);
        if (steps.length === 0) return null;
        const firstIdx = stageIndexIn(catalog, steps[0].code);
        const lastIdx = stageIndexIn(catalog, steps[steps.length - 1].code);
        const isDone = currentIdx > lastIdx;
        const isCurrent = currentIdx >= firstIdx && currentIdx <= lastIdx;
        const doneCount = Math.min(Math.max(currentIdx - firstIdx + 1, 0), steps.length);
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
                  const label = dealStageLabel(step.code);
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

/** Compact per-row progress bar for the list page (one proportional segment per phase). */
export function StageProgressBar({ catalog = EMPTY_STAGE_CATALOG, salesStage, lost = false }) {
  const currentIdx = stageIndexIn(catalog, salesStage);
  return (
    <div className="flex items-center gap-0.5" aria-hidden="true">
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
