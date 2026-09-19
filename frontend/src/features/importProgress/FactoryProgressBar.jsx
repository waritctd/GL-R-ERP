import { useEffect, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { IMPORT_STEPS, importStepIndex, importStepMeta, nextImportStep, stepsAhead } from './importSteps.js';

// One factory's import journey as a 6-step progress bar (S12-S17). Read-only for sales/CEO/
// sales_manager; `editable` (import/CEO — ImportRequestService.ADVANCE_STEP_ROLES) adds the
// "advance" control. Price-free — nothing here is confidential, which is why sales may see it.
// Ported from Yang's origin/feat/per-factory-import-tracking (65dfe171) onto PR-A's own S12-S17
// codes (CONTACTED/ORDERED/PICKED_UP/IN_TRANSIT/AWAITING_CUSTOMS/RECEIVED) — see importSteps.js's
// own header for why those, not Yang's original set.
//
// Forward SKIPS are allowed (owner decision 4: "forward skips allowed, never backwards — correct
// via revision"), so the advance control is a step PICKER over every step still ahead, not just a
// single "next" button — the server enforces the same forward-only rule regardless of what this
// picker offers.
export function FactoryProgressBar({ row, editable = false, advancing = false, onAdvance, onOpenEmail }) {
  const currentIdx = importStepIndex(row.importStep);
  const current = importStepMeta(row.importStep);
  const ahead = stepsAhead(row.importStep);
  const [target, setTarget] = useState(() => nextImportStep(row.importStep)?.code ?? ahead[0]?.code ?? '');
  // PR-B REVIEW ROUND 1, B3: the useState initializer above only runs on FIRST mount. Advancing a
  // step invalidates the query and this component re-renders with a NEW row.importStep, but React
  // keeps the component instance (same position in the tree) — so `target` kept pointing at the
  // step picked for the OLD current step. A second advance in a row then submitted that stale
  // target, which is behind the row's real current step and 409s (advanceStep is forward-only).
  // Re-deriving whenever row.importStep changes keeps the picker's default in sync with the row.
  useEffect(() => {
    setTarget(nextImportStep(row.importStep)?.code ?? stepsAhead(row.importStep)[0]?.code ?? '');
  }, [row.importStep]);
  const done = row.importStep === 'RECEIVED';

  return (
    <div className="rounded-lg border border-border-subtle bg-surface p-3" data-testid={`factory-progress-${row.id}`}>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="font-bold text-text">{row.factoryName}</span>
        <span
          className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-2xs font-bold ${
            done ? 'bg-success-bg text-success-dark' : 'bg-info-bg text-info'
          }`}
        >
          {current ? `${current.s} · ${current.label}` : (row.importStep ?? '—')}
          {done ? ' ✓' : null}
        </span>
        {row.importStepAt ? (
          <span className="text-2xs text-text-muted">· อัปเดต {formatThaiDate(row.importStepAt)}</span>
        ) : null}
        {onOpenEmail || (editable && ahead.length > 0) ? (
          <span className="ml-auto flex flex-wrap items-center gap-2">
            {onOpenEmail ? (
              <Button type="button" variant="text" onClick={() => onOpenEmail(row)} data-testid={`order-email-${row.id}`}>
                ✉ อีเมลสั่งซื้อ
              </Button>
            ) : null}
            {editable && ahead.length > 0 ? (
              <>
                {ahead.length > 1 ? (
                  <select
                    className="min-h-8 rounded-md border border-border-input px-2 text-xs"
                    value={target}
                    onChange={(e) => setTarget(e.target.value)}
                    aria-label={`เลื่อนขั้นตอนโรงงาน ${row.factoryName} ไปที่`}
                    data-testid={`advance-target-${row.id}`}
                  >
                    {ahead.map((step) => (
                      <option key={step.code} value={step.code}>{step.s} {step.label}</option>
                    ))}
                  </select>
                ) : null}
                <Button
                  type="button"
                  variant="secondary"
                  disabled={advancing}
                  onClick={() => onAdvance?.(row, target || ahead[0].code)}
                  data-testid={`advance-${row.id}`}
                >
                  → {ahead.length > 1 ? 'เลื่อนขั้นตอน' : `${ahead[0].s} ${ahead[0].label}`}
                </Button>
              </>
            ) : null}
          </span>
        ) : null}
      </div>

      {/* The bar. Scrolls horizontally on a narrow screen rather than squashing the labels. */}
      <div className="mt-3 overflow-x-auto">
        <ol className="flex min-w-[560px] items-start">
          {IMPORT_STEPS.map((step, i) => {
            const isDone = currentIdx >= 0 && i < currentIdx;
            const isCurrent = i === currentIdx;
            const dot = isDone
              ? 'bg-success text-surface'
              : isCurrent
                ? 'bg-info text-surface ring-2 ring-info/30'
                : 'bg-surface-subtle text-text-muted';
            const connector = i <= currentIdx ? 'bg-success' : 'bg-border';
            return (
              <li key={step.code} className="flex flex-1 flex-col items-center text-center">
                <div className="flex w-full items-center">
                  <span className={`h-0.5 flex-1 ${i === 0 ? 'bg-transparent' : connector}`} />
                  <span className={`grid h-6 w-6 shrink-0 place-items-center rounded-full text-2xs font-extrabold ${dot}`}>
                    {i + 1}
                  </span>
                  <span className={`h-0.5 flex-1 ${i === IMPORT_STEPS.length - 1 ? 'bg-transparent' : (i < currentIdx ? 'bg-success' : 'bg-border')}`} />
                </div>
                <span
                  className={`mt-1 px-1 text-2xs leading-tight ${
                    isCurrent ? 'font-bold text-info' : isDone ? 'text-success-dark' : 'text-text-muted'
                  }`}
                >
                  {step.label}
                </span>
              </li>
            );
          })}
        </ol>
      </div>
    </div>
  );
}
