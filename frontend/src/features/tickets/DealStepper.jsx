import { Icon } from '../../components/common/Icon.jsx';
import { cn } from '../../utils/cn.js';
import { REP_STEPS } from './dealSteps.js';

/**
 * The deal page's S stepper (drawing a-deal): the eight rep codes S1–S9 (no S4), the current one marked, and under
 * each whether the rep moves it ("เซลส์ย้ายเอง") or its documents do ("ย้ายตามเอกสาร", DealStage.AUTO_ADVANCED). It replaces the old
 * "สถานะดีล (Pipeline)" panel's numbered pip; the full 15-stage route stays one click away in DealStagePanel.
 * `current` is REP_STEPS' index (repStepIndex), -1 when the stage is unknown. On a phone the list scrolls inside its
 * own wrapper rather than widening the page.
 */
export function DealStepper({ current }) {
  return (
    <div className="-mx-1 overflow-x-auto px-1 pb-1 [scrollbar-width:thin]">
      <ol aria-label="ขั้นของงาน" className="m-0 grid min-w-[44rem] list-none grid-cols-8 gap-0 p-0">
        {REP_STEPS.map((step, index) => {
          const state = index < current ? 'done' : index === current ? 'current' : 'todo';
          return (
            <li
              key={step.code}
              data-step={step.code}
              data-state={state}
              aria-current={state === 'current' ? 'step' : undefined}
              className="relative flex min-w-0 flex-col items-start gap-1 pr-2"
            >
              {/* The rail between pips: filled up to the current step. */}
              {index < REP_STEPS.length - 1 ? (
                <span
                  aria-hidden="true"
                  className={cn('absolute left-7 right-0 top-3 h-0.5', index < current ? 'bg-success' : 'bg-border')}
                />
              ) : null}
              <span
                className={cn(
                  'relative z-[1] grid h-6 w-6 shrink-0 place-items-center rounded-full text-2xs font-extrabold',
                  state === 'done' && 'bg-success text-surface',
                  state === 'current' && 'bg-primary text-surface ring-4 ring-primary-bg',
                  state === 'todo' && 'border border-border-strong bg-surface text-text-muted',
                )}
              >
                {state === 'done' ? <Icon name="check" size={13} /> : step.code}
              </span>
              <span className={cn('text-xs leading-snug', state === 'current' ? 'font-extrabold text-text' : 'font-bold text-text-secondary')}>
                {step.code} {step.label}
              </span>
              <span className="flex items-center gap-1 text-2xs text-text-muted">
                {step.mover === 'document' ? <Icon name="fileText" size={11} aria-hidden="true" /> : null}
                {step.moverLabel}
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
