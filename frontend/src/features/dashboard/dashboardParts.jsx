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
