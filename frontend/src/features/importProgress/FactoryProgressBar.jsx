import { Button } from '../../components/common/Button.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { IMPORT_STEPS, importStepIndex, importStepMeta, nextImportStep } from './importSteps.js';

// One factory's import journey as a 6-step progress bar (S12–S17). Read-only for sales/CEO;
// `editable` (import/ceo) adds the "advance to next step" control. Price-free — nothing here is
// confidential, which is why sales may see it. Reuses the app's success/info/muted state tokens,
// the same visual language as DealFulfilmentPanel's substep chips.
export function FactoryProgressBar({ row, editable = false, advancing = false, onAdvance, onGenerateEmail }) {
  const currentIdx = importStepIndex(row.importStep);
  const current = importStepMeta(row.importStep);
  const next = nextImportStep(row.importStep);
  const done = row.importStep === 'RECEIVED';

  return (
    <div className="rounded-lg border border-border-subtle bg-surface p-3">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="font-bold text-text">{row.factoryName}</span>
        <span
          className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-2xs font-bold ${
            done ? 'bg-success-bg text-success-dark' : 'bg-info-bg text-info'
          }`}
        >
          {current ? `${current.s} · ${current.label}` : row.importStep}
          {done ? ' ✓' : null}
        </span>
        {row.eta ? (
          <span className="text-2xs text-text-muted">ETA {formatThaiDate(row.eta)}</span>
        ) : null}
        {row.importStepAt ? (
          <span className="text-2xs text-text-muted">· อัปเดต {formatThaiDate(row.importStepAt)}</span>
        ) : null}
        {editable ? (
          <span className="ml-auto flex flex-wrap items-center gap-2">
            {onGenerateEmail ? (
              <Button type="button" variant="text" onClick={() => onGenerateEmail(row)} data-testid={`order-email-${row.id}`}>
                ✉ อีเมลสั่งซื้อ
              </Button>
            ) : null}
            {next ? (
              <Button
                type="button"
                variant="secondary"
                disabled={advancing}
                onClick={() => onAdvance?.(row, next.code)}
                data-testid={`advance-${row.id}`}
              >
                → {next.s} {next.label}
              </Button>
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
