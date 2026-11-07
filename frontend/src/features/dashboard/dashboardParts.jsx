import { Button } from '../../components/common/Button.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';

export function moneySum(items, pick) {
  return items.reduce((sum, item) => sum + (Number(pick(item)) || 0), 0);
}

// One figure of the money band: <div><dt/><dd/><small/></div> (the shared test helper finds it
// via `dl > div`). Stacked cell on desktop, a label-left / value-right row on a phone.
export function Figure({ label, value, caption, lead = false, danger = false }) {
  return (
    <div className="grid min-w-0 gap-0.5 mobile:grid-cols-[1fr_auto] mobile:items-baseline mobile:gap-x-3">
      <dt className="!m-0 !text-xs !font-bold !text-text-muted">{label}</dt>
      <dd
        className={`m-0 font-extrabold tabular-nums mobile:text-right ${lead ? 'text-[26px] mobile:text-xl' : 'text-xl'} ${danger ? '!text-danger' : '!text-text'}`}
      >
        {value}
      </dd>
      <small className="text-xs text-text-muted mobile:col-span-2">{caption}</small>
    </div>
  );
}

// Failed only while there is nothing to show: a background refetch that errors keeps the last
// data (React Query holds it alongside the error), so the page keeps showing it.
export const failed = (query) => query.isError && query.data === undefined;

export const WORKLIST_MAX_ROWS = 3;

/**
 * One group of a landing page's grouped worklist (CeoOverview's pattern). `rows` is null while its
 * own source loads (small skeleton, aria-busy); `failed` says so instead of loading forever.
 * `count` may exceed rows.length. row = { testId, entity, meta, amount?, ctaLabel, ctaVariant?, to }.
 */
export function WorklistGroup({ groupKey, label, dot, summary, tint, rows, count, failed: hasFailed, to, onOpen }) {
  if (!hasFailed && rows !== null && count === 0) return null;
  const shown = rows?.slice(0, WORKLIST_MAX_ROWS) ?? [];
  const more = count - shown.length;
  const openAll = (
    <Button type="button" variant="text" size="sm" onClick={() => onOpen(to)} className="shrink-0 mobile:min-h-[44px]">
      ดูทั้งหมด →
    </Button>
  );
  return (
    <div
      data-testid={`worklist-group-${groupKey}`}
      aria-busy={rows === null && !hasFailed ? 'true' : undefined}
      className={`border-b border-border last:border-b-0 ${tint ?? ''}`}
    >
      {hasFailed ? (
        <div className="flex items-center justify-between gap-3 px-5 py-3 mobile:px-3.5">
          <span className="text-sm font-extrabold text-text">
            {label} <small className="font-semibold text-danger">โหลดไม่สำเร็จ</small>
          </span>
          {openAll}
        </div>
      ) : rows === null ? (
        <div className="grid gap-2 px-5 py-3 mobile:px-3.5" role="status" aria-label={`กำลังโหลด${label}`}>
          <Skeleton width="30%" height={14} />
          <Skeleton height={36} />
        </div>
      ) : (
        <>
          <div className="flex items-center justify-between gap-3 px-5 pb-1 pt-3 mobile:px-3.5">
            <div className="flex min-w-0 items-center gap-2 text-sm font-extrabold text-text">
              <span aria-hidden="true" className={`size-2 shrink-0 rounded-full ${dot}`} />
              <span>{label}</span>
              <small className="truncate font-semibold text-text-muted">{summary}</small>
            </div>
            {openAll}
          </div>
          {shown.map((row) => (
            <div key={row.testId} data-testid={row.testId} className="flex items-center justify-between gap-3 px-5 py-2 mobile:flex-wrap mobile:px-3.5 mobile:py-2.5">
              <div className="min-w-0 flex-1 mobile:basis-full">
                <strong className="block truncate text-sm font-bold text-text">{row.entity}</strong>
                <span className="block truncate text-xs text-text-muted">{row.meta}</span>
              </div>
              <div className="flex shrink-0 items-center gap-3 mobile:w-full mobile:justify-between">
                {row.amount != null ? <span className="text-sm font-bold tabular-nums text-text">{row.amount}</span> : null}
                <Button type="button" variant={row.ctaVariant ?? 'secondary'} size="sm" onClick={() => onOpen(row.to)} className="mobile:min-h-[44px]">
                  {row.ctaLabel}
                </Button>
              </div>
            </div>
          ))}
          {more > 0 ? (
            <div className="px-5 pt-0.5 mobile:px-3.5">
              <Button type="button" variant="text" size="sm" onClick={() => onOpen(to)} className="mobile:min-h-[44px]">
                อีก {more} รายการ
              </Button>
            </div>
          ) : null}
          <div className="pb-2" />
        </>
      )}
    </div>
  );
}

/** The count pill on a worklist panel; shown only once every group has settled. */
export function WorklistTotal({ total }) {
  return (
    <span className="rounded-full bg-text px-2.5 py-px text-xs font-extrabold text-surface">
      <span data-testid="worklist-total" aria-hidden="true">{total}</span>
      <span className="sr-only">รวม {total} รายการ</span>
    </span>
  );
}
