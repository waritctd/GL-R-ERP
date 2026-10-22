import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';

// Quiet, outlined counter chip — neutral by default so the strip informs without competing with
// the per-factory tracker beside/below it. `whitespace-nowrap` is deliberate: a chip that wraps
// onto two lines reads as two chips.
// 26px tall / 12px type — the same box StatusBadge draws, so the status badge and these chips read
// as one row. Keep in lockstep with FactoryProgressBar's CHIP_BOX.
const CHIP = 'inline-flex min-h-[26px] items-center gap-1.5 whitespace-nowrap rounded-full border border-border-subtle bg-surface px-2.5 py-0.5 text-xs font-bold tabular-nums text-text-secondary';
const CHIP_ARRIVED = 'border-success-border text-success';

/**
 * The at-a-glance import summary — fulfilment status → "ถึงไทย X/N โรงงาน" → "ส่งมอบ N%" — shared
 * so the deal tab's Step-1 "นำเข้าสินค้า" header and import's own per-deal page read identically
 * (one import-tracking system, two surfaces). Presentation only: the caller derives every value
 * (the counters come from the same live-row derivation on both surfaces) and this component only
 * lays them out. Price-free by construction — it has no money prop.
 *
 * `testIdPrefix` namespaces the testids per surface: `${prefix}-status-strip` (wrapper),
 * `${prefix}-ir-rollup-chip` and `${prefix}-delivery-chip`. A chip whose value is absent
 * (`issuedCount` 0 / `deliveryPct` null) is omitted rather than rendered as an empty "0/0".
 */
export function ImportStatusStrip({
  fulfilment, arrivedCount = 0, issuedCount = 0, deliveryPct = null, testIdPrefix, className = '',
}) {
  const allArrived = issuedCount > 0 && arrivedCount === issuedCount;
  return (
    <div
      className={cn('flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1', className)}
      data-testid={`${testIdPrefix}-status-strip`}
    >
      {fulfilment ? <StatusBadge tone={fulfilment.tone}>{fulfilment.label}</StatusBadge> : null}
      {issuedCount > 0 ? (
        <span
          className={cn(CHIP, allArrived && CHIP_ARRIVED)}
          data-testid={`${testIdPrefix}-ir-rollup-chip`}
        >
          ถึงไทย {arrivedCount}/{issuedCount} โรงงาน
        </span>
      ) : null}
      {deliveryPct != null ? (
        <span className={CHIP} data-testid={`${testIdPrefix}-delivery-chip`}>ส่งมอบ {deliveryPct}%</span>
      ) : null}
    </div>
  );
}
