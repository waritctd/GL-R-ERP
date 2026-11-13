import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Panel } from '../../components/common/Layout.jsx';
import { bangkokTodayIso, formatMoney, formatThaiDate } from '../../utils/format.js';
import { isOrderedIn, summariseByOwner } from '../tickets/movementRange.js';
import { stageIndexIn, useStageCatalog } from '../tickets/stageCatalog.js';

// "ความเคลื่อนไหวของทีม" for the manager / CEO landings: one row per deal owner over `range`. `deals` is the
// deal list fetched with that SAME range (null while loading); the server already scopes it for the role, so
// nothing is filtered here. Each count links to the deal list filtered by owner + week key AND carrying the
// range explicitly, so the list opens on exactly the range the counts were computed for.
function Count({ id, week, range, value, danger = false }) {
  return (
    <Link
      to={`/tickets?owner=${id}&week=${week}&from=${range.from}&to=${range.to}`}
      className={`inline-block min-w-8 rounded px-1 text-right font-bold tabular-nums underline-offset-2 hover:underline ${danger && value > 0 ? 'text-danger' : 'text-text'}`}
    >
      {value}
    </Link>
  );
}

const HEADS = ['ผู้ดูแล', 'ดีลที่เปิดอยู่', 'ขยับขั้น', 'มีกิจกรรม', 'เลยนัด', 'ไม่เคลื่อนไหว', 'ยอดที่สั่งซื้อแล้ว', 'ยอดคาดหวัง'];

export function TeamMovementPanel({ deals, range }) {
  const { catalog } = useStageCatalog();
  const todayIso = bangkokTodayIso();
  // Until the catalog loads nothing can be classed as ordered or expected: show — rather than a wrong split.
  const catalogReady = stageIndexIn(catalog, 'ORDER_RECEIVED') >= 0;
  const rows = useMemo(() => {
    if (!deals) return null;
    return summariseByOwner(deals, range, todayIso, isOrderedIn(catalog));
  }, [deals, catalog, range, todayIso]);
  return (
    <Panel
      flush
      title="ความเคลื่อนไหวของทีม"
      actions={<span className="text-xs text-text-muted">{formatThaiDate(range.from)} – {formatThaiDate(range.to)}</span>}
    >
      {rows === null ? (
        <p className="m-0 px-5 py-4 text-sm text-text-muted mobile:px-3.5">กำลังโหลด…</p>
      ) : rows.length === 0 ? (
        <p className="m-0 px-5 py-4 text-sm text-text-muted mobile:px-3.5">ยังไม่มีดีลที่เปิดอยู่</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr>
                {HEADS.map((h, i) => (
                  <th key={h} scope="col" className={`whitespace-nowrap border-b border-border px-3 py-2 text-xs font-bold text-text-muted ${i === 0 ? 'text-left' : 'text-right'}`}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.ownerId} data-testid={`team-row-${r.ownerId}`} className="border-t border-border-subtle">
                  <th scope="row" className="whitespace-nowrap px-3 py-2 text-left font-bold text-text">{r.ownerName}</th>
                  <td className="px-3 py-2 text-right font-bold tabular-nums text-text">{r.open}</td>
                  <td className="px-3 py-2 text-right"><Count id={r.ownerId} range={range} week="moved" value={r.moved} /></td>
                  <td className="px-3 py-2 text-right"><Count id={r.ownerId} range={range} week="activity" value={r.activity} /></td>
                  <td className="px-3 py-2 text-right"><Count id={r.ownerId} range={range} week="overdue" value={r.overdue} danger /></td>
                  <td className="px-3 py-2 text-right"><Count id={r.ownerId} range={range} week="silent" value={r.silent} danger /></td>
                  <td className="whitespace-nowrap px-3 py-2 text-right tabular-nums">{catalogReady ? formatMoney(r.won) : '—'}</td>
                  <td className="whitespace-nowrap px-3 py-2 text-right tabular-nums">{catalogReady ? formatMoney(r.expected) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}
