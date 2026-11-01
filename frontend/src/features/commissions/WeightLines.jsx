import { useEffect, useRef, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { WeightSegmented } from './WeightSegmented.jsx';

const formatQty = (value) => Number(value ?? 0).toLocaleString('th-TH', { maximumFractionDigits: 2 });
// A line only counts as "from stock" when some of it actually came from stock. The backend weights
// just the stock portion of a line (CommissionCalculator#itemDerivedWeight), so a qtyFromStock of 0
// means the ×2 has nothing to act on and the line is locked at ×1.
const isStockLine = (item) => Number(item.qtyFromStock ?? 0) > 0;
const SAVED_FLASH_MS = 2500;

/**
 * The "สินค้าและน้ำหนัก" section of the invoice detail panel: a line per product with its ×1/×2/×3
 * control plus the "สต็อกทั้งหมด ×2" quick action. `canAdjust` is true only for the sales_manager on a
 * SUBMITTED record; otherwise every segment is read-only.
 *
 * Weight changes are OPTIMISTIC. `optimistic` overlays the server's weights so the segment flips on
 * the click; the request then goes out, the parent replaces the whole entry with the server's answer
 * (the server recomputes effectiveWeight / weightedCommissionableBase / estimatedCommission -- never
 * recomputed here), and on failure the overlay for exactly those lines is dropped, which reverts the
 * segment, and an inline alert names the line(s). The WHOLE entry is one write at a time.
 */
export function WeightLines({ entry, canAdjust, onAdjust, onEntryUpdate, onSaved }) {
  const { commission: record, items = [] } = entry;
  const [optimistic, setOptimistic] = useState({});
  const [lineState, setLineState] = useState({}); // itemId -> 'saving' | 'saved' | 'error'
  const [error, setError] = useState(null);
  const timers = useRef([]);
  useEffect(() => () => timers.current.forEach(clearTimeout), []);

  const weightOf = (item) => optimistic[item.itemId] ?? item.weightMultiplier;
  const anySaving = Object.values(lineState).includes('saving');
  const errorId = `weights-${record.id}-error`;
  const stockLines = items.filter(isStockLine);

  function setStates(ids, state) {
    setLineState((current) => {
      const next = { ...current };
      ids.forEach((id) => { if (state == null) delete next[id]; else next[id] = state; });
      return next;
    });
  }

  async function change(lines) {
    // Two parallel item-weights requests for one record race in the backend (no row lock), the frozen
    // effective weight can then ignore one of the lines, and the later response could overwrite the
    // earlier one. So nothing is accepted while anything of this entry is saving -- the controls are
    // disabled too, this is the belt to their braces.
    if (anySaving) return;
    // Never re-send a line that is already at that weight.
    const todo = lines.filter((l) => {
      const item = items.find((i) => i.itemId === l.itemId);
      return item && weightOf(item) !== l.weightMultiplier;
    });
    if (todo.length === 0) return;
    const ids = todo.map((l) => l.itemId);
    setError(null);
    // A fresh attempt clears any earlier failure marker on the other lines too.
    setLineState((current) => Object.fromEntries(Object.entries(current).filter(([, value]) => value !== 'error')));
    setOptimistic((current) => ({ ...current, ...Object.fromEntries(todo.map((l) => [l.itemId, l.weightMultiplier])) }));
    setStates(ids, 'saving');
    const dropOverlay = () => setOptimistic((current) => {
      const next = { ...current };
      ids.forEach((id) => { delete next[id]; });
      return next;
    });
    try {
      const response = await onAdjust(record.id, { lines: todo });
      onEntryUpdate(response.pending);
      // The month's records and any picked rep's statement moved with this weight: reload them.
      onSaved?.();
      dropOverlay();
      setStates(ids, 'saved');
      timers.current.push(setTimeout(() => {
        setLineState((current) => {
          const next = { ...current };
          ids.forEach((id) => { if (next[id] === 'saved') delete next[id]; });
          return next;
        });
      }, SAVED_FLASH_MS));
    } catch (failure) {
      // Dropping the overlay is the revert: the segment falls back to the server's weight.
      dropOverlay();
      setStates(ids, 'error');
      const names = todo.map((l) => items.find((i) => i.itemId === l.itemId)?.description).filter(Boolean);
      setError(`ปรับน้ำหนัก ${names.join(', ')} ไม่สำเร็จ${failure?.message ? ` — ${failure.message}` : ''}`);
    }
  }

  return (
    <section aria-label="สินค้าและน้ำหนัก" className="grid min-w-0 content-start gap-2.5 p-4 mobile:p-3.5">
      <h4 className="m-0 flex items-center justify-between gap-2 text-sm font-extrabold text-text">
        สินค้าและน้ำหนัก
        {canAdjust && stockLines.length > 0 ? (
          <Button
            type="button"
            variant="secondary"
            size="sm"
            className="whitespace-nowrap pointer-coarse:min-h-11 aria-disabled:cursor-not-allowed aria-disabled:opacity-[0.55]"
            aria-disabled={anySaving ? 'true' : undefined}
            // A line already at ×3 is skipped: this action only ever RAISES a line to ×2.
            onClick={() => change(stockLines.filter((item) => weightOf(item) < 2).map((item) => ({ itemId: item.itemId, weightMultiplier: 2 })))}
          >
            สต็อกทั้งหมด ×2
          </Button>
        ) : null}
      </h4>

      <ul className="m-0 grid list-none gap-0 p-0">
        {items.map((item) => {
          const stock = isStockLine(item);
          const state = lineState[item.itemId];
          return (
            <li
              key={item.itemId}
              className="grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1 border-t border-border py-2 first:border-t-0 first:pt-0 mobile:grid-cols-[minmax(0,1fr)]"
            >
              <div className="grid min-w-0 gap-0.5">
                <span className="text-md font-bold text-text [overflow-wrap:anywhere]">{item.description}</span>
                <span className="text-sm text-text-muted tabular-nums">
                  {stock
                    ? `จำนวน ${formatQty(item.qty)} · จากสต็อก ${formatQty(item.qtyFromStock)}/${formatQty(item.qty)}`
                    : `จำนวน ${formatQty(item.qty)} · ของสั่ง/นำเข้า คิด ×1`}
                </span>
              </div>
              <div className="flex min-w-0 flex-wrap items-center gap-2 mobile:justify-between">
                <WeightSegmented
                  label={`น้ำหนัก ${item.description}`}
                  value={stock ? weightOf(item) : 1}
                  onChange={(weight) => change([{ itemId: item.itemId, weightMultiplier: weight }])}
                  disabled={!canAdjust || !stock || anySaving}
                  busy={state === 'saving'}
                  invalid={state === 'error'}
                  describedBy={state === 'error' ? errorId : undefined}
                />
                <span className="text-sm" aria-live="polite">
                  {state === 'saving' ? (
                    <span className="inline-flex items-center gap-1.5 text-text-muted">
                      <span
                        aria-hidden="true"
                        className="h-3.5 w-3.5 rounded-pill border-2 border-current border-r-transparent animate-[button-loading-spin_700ms_linear_infinite] motion-reduce:animate-none"
                      />
                      กำลังบันทึก…
                    </span>
                  ) : null}
                  {state === 'saved' ? (
                    <span className="inline-flex items-center gap-1 text-success"><Icon name="check" size={14} />บันทึกแล้ว</span>
                  ) : null}
                  {state === 'error' ? <span className="text-danger">ไม่สำเร็จ</span> : null}
                </span>
              </div>
            </li>
          );
        })}
      </ul>
      {error ? (
        <p id={errorId} role="alert" className="m-0 rounded-md border border-danger-border bg-danger-bg px-3 py-2 text-sm text-danger-dark [overflow-wrap:anywhere]">
          {error}
        </p>
      ) : null}
      <span className="text-sm text-text-muted">
        {canAdjust
          ? (stockLines.length > 0 ? 'ปรับแล้วระบบคำนวณฐานใหม่ทันที · ×2/×3 ใช้ได้เฉพาะส่วนที่ส่งจากสต็อก' : 'ไม่มีสินค้าจากสต็อก จึงคิด ×1 ทั้งใบ')
          : 'ดูอย่างเดียว · น้ำหนักล็อกแล้วหลังผู้จัดการอนุมัติ'}
      </span>
    </section>
  );
}
