import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { dealStageLabel } from '../../utils/format.js';
import { GATE_LABEL, SALES_PHASES, SALES_STAGES, stageIndex } from './stageMeta.js';

/**
 * Phase accordion for the 14-stage pipeline (adapted from the Claude Design
 * prototype's accordion). Only the current phase starts expanded so the page
 * never shows 14 rows at once; completed phases collapse behind a ✓ header.
 */
export function DealStageStepper({ salesStage, lost = false }) {
  const currentIdx = stageIndex(salesStage);
  const currentPhase = SALES_STAGES[currentIdx]?.phase ?? 1;
  const [open, setOpen] = useState(() => ({ [currentPhase]: true }));

  function toggle(phaseId) {
    setOpen((prev) => ({ ...prev, [phaseId]: !prev[phaseId] }));
  }

  return (
    <div className="overflow-hidden rounded-xl border border-border bg-surface">
      {SALES_PHASES.map((phase) => {
        const steps = SALES_STAGES.filter((s) => s.phase === phase.id);
        const firstIdx = stageIndex(steps[0].code);
        const lastIdx = stageIndex(steps[steps.length - 1].code);
        const isDone = currentIdx > lastIdx;
        const isCurrent = currentIdx >= firstIdx && currentIdx <= lastIdx;
        const doneCount = Math.min(Math.max(currentIdx - firstIdx + (isDone ? 1 : 1), 0), steps.length);
        const isOpen = !!open[phase.id];
        return (
          <div key={phase.id} className="border-b border-border last:border-b-0">
            <button
              type="button"
              className="flex w-full items-center gap-3 bg-transparent px-4 py-3 text-left"
              aria-expanded={isOpen}
              onClick={() => toggle(phase.id)}
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
                {isDone ? <Icon name="check" size={13} /> : phase.id}
              </span>
              <span className="min-w-0 flex-1">
                <span className={`block text-sm font-extrabold ${isCurrent && !lost ? 'text-text' : 'text-text-muted'}`}>
                  เฟส {phase.id} · {phase.name}
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
                  const idx = stageIndex(step.code);
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
 * Horizontal 5-phase tracker with proportional fill (from the prototype's
 * phase bar). Lost projects render an empty track.
 */
export function PhaseTracker({ salesStage, lost = false }) {
  const currentIdx = stageIndex(salesStage);
  return (
    <div className="flex items-end gap-2">
      {SALES_PHASES.map((phase) => {
        const steps = SALES_STAGES.filter((s) => s.phase === phase.id);
        const firstIdx = stageIndex(steps[0].code);
        const lastIdx = stageIndex(steps[steps.length - 1].code);
        let fill = 0;
        if (!lost) {
          if (currentIdx > lastIdx) fill = 1;
          else if (currentIdx >= firstIdx) fill = (currentIdx - firstIdx + 1) / steps.length;
        }
        const isCurrent = !lost && currentIdx >= firstIdx && currentIdx <= lastIdx;
        return (
          <div key={phase.id} className="min-w-0 flex flex-col gap-1.5" style={{ flex: steps.length }}>
            <span className={`text-2xs font-extrabold ${isCurrent ? 'text-info' : 'text-text-muted'}`}>
              เฟส {phase.id}
            </span>
            <div className="h-2 overflow-hidden rounded-full bg-surface-subtle">
              <span
                className={`block h-full rounded-full ${lost ? 'bg-danger-bg' : 'bg-info'}`}
                style={{ width: `${fill * 100}%` }}
              />
            </div>
            <span className="truncate text-2xs font-semibold text-text-muted">{phase.name}</span>
          </div>
        );
      })}
    </div>
  );
}

/** Compact per-row progress bar for the list page (5 proportional segments). */
export function StageProgressBar({ salesStage, lost = false }) {
  const currentIdx = stageIndex(salesStage);
  return (
    <div className="flex items-center gap-0.5" aria-hidden="true">
      {SALES_PHASES.map((phase) => {
        const steps = SALES_STAGES.filter((s) => s.phase === phase.id);
        const firstIdx = stageIndex(steps[0].code);
        const lastIdx = stageIndex(steps[steps.length - 1].code);
        let fill = 0;
        if (!lost) {
          if (currentIdx > lastIdx) fill = 1;
          else if (currentIdx >= firstIdx) fill = (currentIdx - firstIdx + 1) / steps.length;
        }
        return (
          <span
            key={phase.id}
            className="h-1.5 overflow-hidden rounded-full bg-surface-subtle"
            style={{ flex: steps.length }}
          >
            <span
              className={`block h-full rounded-full ${lost ? 'bg-danger-bg' : 'bg-info'}`}
              style={{ width: `${fill * 100}%` }}
            />
          </span>
        );
      })}
    </div>
  );
}
