import { useEffect, useRef, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatMoney } from '../../utils/format.js';
import { WeightSegmented } from './WeightSegmented.jsx';

const formatQty = (value) => Number(value ?? 0).toLocaleString('th-TH', { maximumFractionDigits: 2 });
// A line only counts as "from stock" when some of it actually came from stock. The backend weights
// just the stock portion of a line (CommissionCalculator#itemDerivedWeight), so a qtyFromStock of 0
// means the ×2 has nothing to act on and the line is locked at ×1.
const isStockLine = (item) => Number(item.qtyFromStock ?? 0) > 0;
const SAVED_FLASH_MS = 2500;

/**
 * One pending commission as a weighting desk card: who / which deal, a line per product with its
 * ×1/×2/×3 control, the before → after base, and the approve / reject decision.
 *
 * Weight changes are OPTIMISTIC. `optimistic` overlays the server's weights so the segment flips on
 * the click; the request then goes out, the parent replaces the whole entry with the server's answer
 * (the server recomputes effectiveWeight / weightedCommissionableBase / estimatedCommission — never
 * recomputed here), and on failure the overlay for exactly those lines is dropped, which reverts the
 * segment, and an inline alert names the line(s). `lineState` is per line, so a save locks only the
 * line being saved.
 */
function PendingEntry({ entry, canAdjust, canReview, busyElsewhere, onAdjust, onEntryUpdate, onApprove, onReject }) {
  const { commission: record, items = [] } = entry;
  const [optimistic, setOptimistic] = useState({});
  const [lineState, setLineState] = useState({}); // itemId -> 'saving' | 'saved' | 'error'
  const [error, setError] = useState(null);
  const timers = useRef([]);
  useEffect(() => () => timers.current.forEach(clearTimeout), []);

  const weightOf = (item) => optimistic[item.itemId] ?? item.weightMultiplier;
  const anySaving = Object.values(lineState).includes('saving');
  const errorId = `pending-entry-${record.id}-error`;

  function setStates(ids, state) {
    setLineState((current) => {
      const next = { ...current };
      ids.forEach((id) => { if (state == null) delete next[id]; else next[id] = state; });
      return next;
    });
  }

  async function change(lines) {
    // Never re-send a line that is already at that weight, and never touch a line that is mid-save.
    const todo = lines.filter((l) => {
      const item = items.find((i) => i.itemId === l.itemId);
      return item && lineState[l.itemId] !== 'saving' && weightOf(item) !== l.weightMultiplier;
    });
    if (todo.length === 0) return;
    const ids = todo.map((l) => l.itemId);
    setError(null);
    // A fresh attempt clears any earlier failure marker on the other lines too.
    setLineState((current) => Object.fromEntries(Object.entries(current).filter(([, value]) => value !== 'error')));
    setOptimistic((current) => ({ ...current, ...Object.fromEntries(todo.map((l) => [l.itemId, l.weightMultiplier])) }));
    setStates(ids, 'saving');
    try {
      const response = await onAdjust(record.id, { lines: todo });
      onEntryUpdate(response.pending);
      setOptimistic((current) => {
        const next = { ...current };
        ids.forEach((id) => { delete next[id]; });
        return next;
      });
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
      setOptimistic((current) => {
        const next = { ...current };
        ids.forEach((id) => { delete next[id]; });
        return next;
      });
      setStates(ids, 'error');
      const names = todo.map((l) => items.find((i) => i.itemId === l.itemId)?.description).filter(Boolean);
      setError(`ปรับน้ำหนัก ${names.join(', ')} ไม่สำเร็จ${failure?.message ? ` — ${failure.message}` : ''}`);
    }
  }

  const stockLines = items.filter(isStockLine);

  return (
    <article
      data-testid={`pending-entry-${record.id}`}
      className="@container min-w-0 rounded-md border border-border bg-surface"
    >
      <div className="grid min-w-0 gap-4 p-4 mobile:p-3">
        <header className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
          <div className="grid min-w-0 gap-0.5">
            <h3 className="m-0 text-lg font-extrabold text-text [overflow-wrap:anywhere]">{record.invoiceDetails?.invoiceNumber}</h3>
            <span className="text-sm text-text-secondary [overflow-wrap:anywhere]">{record.salesRepName || record.salesRepId}</span>
            <span className="text-sm text-text-muted [overflow-wrap:anywhere]">{[entry.ticketCode, entry.customerName].filter(Boolean).join(' · ')}</span>
          </div>
          <StatusBadge tone="warning">รอผู้จัดการฝ่ายขายอนุมัติ</StatusBadge>
        </header>

        <div className="grid min-w-0 gap-2">
          <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
            <h4 className="m-0 text-md font-extrabold text-text">น้ำหนักรายสินค้า</h4>
            {canAdjust && stockLines.length > 0 ? (
              <Button
                type="button"
                variant="secondary"
                size="sm"
                className="whitespace-nowrap pointer-coarse:min-h-11 mobile:min-h-11 aria-disabled:cursor-not-allowed aria-disabled:opacity-[0.55]"
                aria-disabled={anySaving ? 'true' : undefined}
                onClick={() => { if (!anySaving) change(stockLines.map((item) => ({ itemId: item.itemId, weightMultiplier: 2 }))); }}
              >
                สต็อกทั้งหมด ×2
              </Button>
            ) : null}
          </div>
          {!canAdjust ? (
            <p className="m-0 text-sm text-text-muted">ดูอย่างเดียว — ผู้จัดการฝ่ายขายเป็นผู้ปรับน้ำหนัก</p>
          ) : null}

          <ul className="m-0 grid list-none gap-0 p-0">
            {items.map((item) => {
              const stock = isStockLine(item);
              const state = lineState[item.itemId];
              const groupLabel = `น้ำหนัก ${item.description}`;
              return (
                <li
                  key={item.itemId}
                  className="grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-2 border-t border-border-subtle py-3 first:border-t-0 mobile:grid-cols-[minmax(0,1fr)]"
                >
                  <div className="grid min-w-0 gap-0.5">
                    <span className="text-md font-bold text-text [overflow-wrap:anywhere]">{item.description}</span>
                    <span className="flex flex-wrap items-center gap-x-3 text-sm text-text-muted">
                      <span className="tabular-nums">{`จำนวน ${formatQty(item.qty)}`}</span>
                      {stock
                        ? <span className="tabular-nums">{`สต็อก ${formatQty(item.qtyFromStock)}/${formatQty(item.qty)}`}</span>
                        : <span>ของสั่ง — ไม่คิด 2x</span>}
                    </span>
                  </div>
                  <div className="flex min-w-0 flex-wrap items-center gap-2 mobile:justify-between">
                    <WeightSegmented
                      label={groupLabel}
                      value={stock ? weightOf(item) : 1}
                      onChange={(weight) => change([{ itemId: item.itemId, weightMultiplier: weight }])}
                      disabled={!canAdjust || !stock || state === 'saving'}
                      busy={state === 'saving'}
                      invalid={state === 'error'}
                      describedBy={state === 'error' ? errorId : undefined}
                    />
                    <span className="min-w-[5.5rem] text-sm" aria-live="polite">
                      {state === 'saving' ? <span className="text-text-muted">กำลังบันทึก…</span> : null}
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
        </div>

        <dl className="m-0 grid grid-cols-[repeat(auto-fit,minmax(11rem,1fr))] gap-x-6 gap-y-3 rounded-md bg-surface-muted p-3">
          <div className="grid min-w-0 content-start gap-0.5">
            <dt className="text-sm text-text-muted">ฐานค่าคอม ก่อนถ่วง → หลังถ่วง</dt>
            <dd className="m-0 text-md font-extrabold tabular-nums text-text [overflow-wrap:anywhere]">
              {`${formatMoney(record.commissionableBase)} → ${formatMoney(entry.weightedCommissionableBase)}`}
            </dd>
          </div>
          <div className="grid min-w-0 content-start gap-0.5">
            <dt className="text-sm text-text-muted">น้ำหนักรวม</dt>
            <dd className="m-0 text-md font-extrabold tabular-nums text-text">{Number(entry.effectiveWeight ?? 0).toFixed(2)}</dd>
          </div>
          <div className="grid min-w-0 content-start gap-0.5">
            <dt className="text-sm text-text-muted">ค่าคอมที่คำนวณได้</dt>
            <dd className="m-0 text-xl font-extrabold tabular-nums text-text [overflow-wrap:anywhere]">{formatMoney(entry.estimatedCommission)}</dd>
          </div>
        </dl>

        {canReview ? (
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="secondary"
              className="min-w-0 flex-1 whitespace-nowrap border-success text-success hover:bg-success-bg active:bg-success-bg"
              disabled={busyElsewhere || anySaving}
              onClick={() => onApprove(record.id)}
            >
              <Icon name="check" size={14} />
              ผู้จัดการอนุมัติ
            </Button>
            <Button
              type="button"
              variant="secondary"
              className={cn('min-w-0 flex-1 whitespace-nowrap border-danger text-danger hover:bg-danger-bg active:bg-danger-bg')}
              disabled={busyElsewhere || anySaving}
              onClick={() => onReject(record.id)}
            >
              <Icon name="close" size={14} />
              ไม่อนุมัติ
            </Button>
          </div>
        ) : null}
      </div>
    </article>
  );
}

/**
 * The weighting desk: the pending (SUBMITTED) queue as a worklist. For the sales_manager this leads
 * her page ("รอคุณอนุมัติ (n)"); for the CEO it is a read-only view of the same queue. `entries` is
 * null until first loaded.
 */
export function WeightingDesk({ title, entries, loading, canAdjust, busy, canReviewRecord, onAdjust, onEntryUpdate, onApprove, onReject }) {
  const heading = entries == null ? title : `${title} (${entries.length})`;
  return (
    <section aria-labelledby="weighting-desk-heading" className="grid min-w-0 gap-3">
      <div className="grid gap-1">
        <h2 id="weighting-desk-heading" className="m-0 text-lg font-extrabold text-text">{heading}</h2>
        <p className="m-0 max-w-[72ch] text-sm text-text-muted">
          ทุกรอบเดือน — ปรับน้ำหนักสินค้าแล้วระบบคำนวณค่าคอมใหม่ให้ · ×2 ใช้กับสินค้าที่ส่งจากสต็อกเท่านั้น ของสั่ง/นำเข้าคิด ×1 เสมอ
        </p>
      </div>
      {entries == null ? (
        <p className="m-0 text-sm text-text-muted" aria-busy={loading}>กำลังโหลดรายการรออนุมัติ…</p>
      ) : entries.length === 0 ? (
        <EmptyState icon="badge" title="ไม่มีรายการรออนุมัติ" description="ใบกำกับที่ฝ่ายบัญชีบันทึกและรอผู้จัดการฝ่ายขายอนุมัติจะแสดงที่นี่ ทุกรอบเดือน" />
      ) : (
        <div className="grid gap-4">
          {entries.map((entry) => (
            <PendingEntry
              key={entry.commission.id}
              entry={entry}
              canAdjust={canAdjust}
              canReview={canReviewRecord(entry.commission)}
              busyElsewhere={busy}
              onAdjust={onAdjust}
              onEntryUpdate={onEntryUpdate}
              onApprove={onApprove}
              onReject={onReject}
            />
          ))}
        </div>
      )}
    </section>
  );
}
