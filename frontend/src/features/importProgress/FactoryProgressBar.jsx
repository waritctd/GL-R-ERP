import { useEffect, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate } from '../../utils/format.js';
import { IMPORT_STEPS, importStepIndex, importStepMeta, nextImportStep, stepsAhead } from './importSteps.js';

// Chip metrics — 26px tall / 12px type, the same box StatusBadge (the .status-badge rule) draws, so
// a chip, the filled step chip and a StatusBadge sitting in one row share a height and baseline.
// Keep in lockstep with ImportStatusStrip's CHIP.
const CHIP_BOX = 'inline-flex min-h-[26px] items-center gap-1 whitespace-nowrap rounded-full border px-2.5 py-0.5 text-xs';
// Outlined ETA chip — the neutral counterpart to the filled step chip (filled = state, outlined =
// a date/count), shared in spirit with ImportStatusStrip's chips.
const ETA_CHIP = `${CHIP_BOX} max-w-full border-border-subtle bg-surface font-bold tabular-nums text-text-secondary`;

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
//
// Presentation-only props (no behaviour): `bare` drops this component's own frame so a host that
// already owns one (ImportRequestFactoryCard) does not draw a card inside a card, and `titleExtra`
// is rendered right after the factory name (the card puts its v#/status/doc-number there).
// Everything else — the step picker, the forward-only advance, the email hook — is unchanged.
export function FactoryProgressBar({
  row, editable = false, advancing = false, onAdvance, onOpenEmail, bare = false, titleExtra = null,
}) {
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
    <div
      className={cn(
        'flex flex-col gap-3',
        !bare && 'rounded-md border border-border bg-surface p-4 mobile:p-3',
      )}
      data-testid={`factory-progress-${row.id}`}
    >
      {/* Header: identity + status on the left (name → step → ETA → last update), the action
          cluster on the right. The left block has a floor width, so when the row is too narrow the
          cluster wraps BELOW it (full width, left-aligned on a phone) instead of squeezing the
          name into a sliver. */}
      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
        <div className="flex min-w-0 flex-1 basis-56 flex-col gap-1.5">
          <div className="flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-1">
            <strong className="min-w-0 text-md font-extrabold text-text [overflow-wrap:anywhere]">{row.factoryName}</strong>
            {titleExtra}
          </div>
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5">
            <span
              className={cn(
                CHIP_BOX,
                'font-bold',
                done ? 'border-transparent bg-success-bg text-success-dark' : 'border-transparent bg-info-bg text-info',
              )}
            >
              {current ? `${current.s} · ${current.label}` : (row.importStep ?? '—')}
              {done ? ' ✓' : null}
            </span>
            {/* ETA — expected arrival window, derived from the factory's lead time (issueDate +
                leadTimeMin/Max). Read-only, shown to sales/CEO/import alike. Falls back to the raw
                lead-time span before the row is issued (no issue date yet, so no derived dates) —
                same chip, dashed edge, because it is an estimate rather than a date. */}
            {row.expectedArrivalFrom && row.expectedArrivalTo ? (
              <span className={ETA_CHIP} data-testid={`factory-eta-${row.id}`}>
                ETA {formatThaiDate(row.expectedArrivalFrom)}
                {row.expectedArrivalTo !== row.expectedArrivalFrom ? ` – ${formatThaiDate(row.expectedArrivalTo)}` : ''}
              </span>
            ) : (row.leadTimeMinDays != null && row.leadTimeMaxDays != null) ? (
              <span className={cn(ETA_CHIP, 'border-dashed text-text-muted')} data-testid={`factory-eta-${row.id}`}>
                ETA ~{row.leadTimeMinDays}–{row.leadTimeMaxDays} วันหลังออกเลข
              </span>
            ) : null}
            {row.importStepAt ? (
              <span className="text-2xs text-text-muted">อัปเดต {formatThaiDate(row.importStepAt)}</span>
            ) : null}
          </div>
        </div>
        {onOpenEmail || (editable && ahead.length > 0) ? (
          <div className="flex max-w-full shrink-0 flex-wrap items-center justify-end gap-2 mobile:w-full mobile:justify-start">
            {onOpenEmail ? (
              <Button type="button" variant="text" size="sm" className="whitespace-nowrap" onClick={() => onOpenEmail(row)} data-testid={`order-email-${row.id}`}>
                ✉ อีเมลสั่งซื้อ
              </Button>
            ) : null}
            {editable && ahead.length > 0 ? (
              <>
                {ahead.length > 1 ? (
                  <select
                    className="h-8 w-auto min-w-0 max-w-full rounded-md border-[1.5px] border-border-input bg-surface px-2 text-sm mobile:h-11 mobile:w-full"
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
                  size="sm"
                  className="whitespace-nowrap"
                  disabled={advancing}
                  onClick={() => onAdvance?.(row, target || ahead[0].code)}
                  data-testid={`advance-${row.id}`}
                >
                  → {ahead.length > 1 ? 'เลื่อนขั้นตอน' : `${ahead[0].s} ${ahead[0].label}`}
                </Button>
              </>
            ) : null}
          </div>
        ) : null}
      </div>

      {/* The bar — the visual anchor. Scrolls horizontally on a narrow screen rather than squashing
          the labels. Done = solid green dot + green rule; current = the one larger, ringed blue dot
          with a heavier label (green once the whole journey is RECEIVED); upcoming = hollow dot on
          a muted rule. The dot row is a fixed height so the larger current dot never nudges the
          labels out of line with their neighbours. */}
      <div className="overflow-x-auto pb-2">
        <ol className="m-0 flex min-w-[600px] list-none items-start p-0">
          {IMPORT_STEPS.map((step, i) => {
            const isDone = currentIdx >= 0 && i < currentIdx;
            const isCurrent = i === currentIdx;
            const dot = isDone
              ? 'h-6 w-6 bg-success text-surface'
              : isCurrent
                ? cn('h-7 w-7 text-surface ring-4', done ? 'bg-success ring-success-bg' : 'bg-info ring-info-bg')
                : 'h-6 w-6 border border-border-muted bg-surface text-text-muted';
            return (
              <li
                key={step.code}
                className="flex min-w-0 flex-1 flex-col items-center text-center"
                aria-current={isCurrent ? 'step' : undefined}
              >
                <div className="flex h-7 w-full items-center">
                  <span className={`h-0.5 flex-1 ${i === 0 ? 'bg-transparent' : (i <= currentIdx ? 'bg-success' : 'bg-border-muted')}`} />
                  <span className={`grid shrink-0 place-items-center rounded-full text-2xs font-extrabold ${dot}`}>
                    {i + 1}
                  </span>
                  <span className={`h-0.5 flex-1 ${i === IMPORT_STEPS.length - 1 ? 'bg-transparent' : (i < currentIdx ? 'bg-success' : 'bg-border-muted')}`} />
                </div>
                <span
                  className={cn(
                    'mt-2 px-1 text-xs leading-tight',
                    isCurrent ? (done ? 'font-extrabold text-success-dark' : 'font-extrabold text-info') : isDone ? 'font-bold text-success-dark' : 'text-text-muted',
                  )}
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
