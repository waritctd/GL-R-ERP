import { cn } from '../../utils/cn.js';
import { Skeleton } from './Skeleton.jsx';

/**
 * CompactStatRow — a single horizontal band of label/value readouts.
 *
 * Replaces StatCard × StatGrid ("the hero-metric template": 269×161
 * fixed-height icon-tile cards, identical across ten screens per the
 * 2026-07 card-diet audit) for stat cards that never navigate anywhere.
 * Scope is deliberate and narrow: only StatCard call sites that ALWAYS omit
 * `onClick` convert to this. A StatCard that navigates — even conditionally,
 * only when its value is non-zero — stays a StatCard; see that component's
 * own doc comment for why "a zero card is deliberately inert; a non-zero one
 * is a live link" must not be collapsed into a plain readout.
 *
 * Shape follows the two passive-readout patterns already shipped in the
 * product: CeoOverview's "ผลบริษัทเดือนนี้" (plain key/value list) and
 * HrOverview's "จำนวนพนักงานตามฝ่าย" (label + number). Both use one shared
 * surface for the whole group and zero chrome per item — no border, no
 * background, no icon tile, no fixed height on the individual readouts —
 * so this does the same: one bordered strip (`<dl>`) containing N label/value
 * pairs that wrap via CSS grid, costing only as many rows as the viewport
 * actually needs instead of the 393–531px a 2-column StatGrid cost on a
 * phone for the same data.
 *
 * `tone` is intentionally NOT part of this API. StatCard's tone colors an
 * icon tile so same-shaped cards read apart at a glance in a grid; this
 * component has no icon tile, and its items already sit adjacent in one
 * reading order, so per-item color would add no information — it would only
 * compete with the app's one real use of color-as-signal (StatusBadge tone
 * on live status/alerts elsewhere on the same page). Neither reference
 * pattern above colors its rows either, so dropping tone here matches
 * existing product precedent, not a new invention.
 *
 * `helper` (the bilingual English subtitle StatCard renders as a separate
 * third line) is kept — it's folded into the label line as "label · helper"
 * so each item is two lines in the common case instead of three. The label
 * line is allowed to wrap (no `truncate`) rather than clip: at the ~150px
 * columns this grid hits on a phone, a Thai label alone can eat the column,
 * and `truncate` silently ellipsised the helper into nothing — see FIX 2,
 * 2026-07 review. Only the value (`dd`) still truncates, with a `title`
 * tooltip per DESIGN.md §18's "truncate with ellipsis + tooltip" rule.
 *
 * Values are pass-through content (already formatted by the caller, e.g.
 * `formatMoney`); this component never formats or computes a number itself.
 */
export function CompactStatRow({
  items,
  loading = false,
  loadingCount = 4,
  className,
  'aria-label': ariaLabel,
  ...props
}) {
  const gridClassName = cn(
    'm-0 grid grid-cols-4 gap-x-6 gap-y-4 rounded-md border border-border bg-surface px-5 py-4',
    'nav-drawer:grid-cols-2 mobile:grid-cols-2 mobile:gap-x-4 mobile:gap-y-3 mobile:px-4 mobile:py-3.5',
    className,
  );

  if (loading) {
    // Skeleton.jsx's own contract: the shimmer blocks are decorative
    // (`aria-hidden` internally) and the *surrounding container* carries
    // `aria-busy` + a caller-supplied `aria-label` so assistive tech
    // announces the loading state — mirrors every other `aria-busy`
    // loading region in this codebase (e.g. CeoOverview's StatGrid skeleton).
    // A default label is provided so a future caller that forgets to pass
    // one still ships an announced busy region instead of a silent one;
    // callers may still override it via `aria-label`.
    // Heights/gap below intentionally mirror the loaded row (11px label
    // line + 2px gap + ~16-22px value line) so the strip does not change
    // height when data lands and shift whatever sits below it.
    return (
      <div
        className={gridClassName}
        aria-busy="true"
        aria-label={ariaLabel || 'กำลังโหลดข้อมูลสรุป'}
        data-testid="compact-stat-row"
        {...props}
      >
        {Array.from({ length: loadingCount }, (_, index) => (
          <div key={index} className="flex min-w-0 flex-col gap-0.5">
            <Skeleton width="65%" height={14} />
            <Skeleton width="45%" height={20} />
          </div>
        ))}
      </div>
    );
  }

  return (
    <dl className={gridClassName} aria-label={ariaLabel} data-testid="compact-stat-row" {...props}>
      {items.map((item) => (
        <div key={item.key ?? item.label} className="flex min-w-0 flex-col gap-0.5">
          <dt className="text-2xs font-bold uppercase tracking-wide text-text-muted">
            {item.label}
            {item.helper ? <span className="ml-1 font-normal normal-case">· {item.helper}</span> : null}
          </dt>
          <dd
            className="m-0 truncate text-lg font-extrabold tabular-nums text-text sm:text-xl"
            title={String(item.value)}
          >
            {item.value}
          </dd>
        </div>
      ))}
    </dl>
  );
}
