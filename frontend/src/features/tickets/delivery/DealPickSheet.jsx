/* Tokens only. "เลือกงาน" (phone design M12): the quotations list's "ขอออกใบกำกับ / ส่งของ" starts here. A tap on a deal (each
 * order is its own row) goes to that deal's ship tab with the request sheet open; "สร้างงานใหม่" is for a deal ordered
 * before the portal and opens the create form (the host does that). Search is on the screen, so nothing is typed twice.
 */
import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../../api/index.js';
import { Button } from '../../../components/common/Button.jsx';
import { Modal } from '../../../components/common/Modal.jsx';
import { dealStageLabel } from '../../../utils/format.js';

const ROW = 'flex min-h-14 w-full flex-col items-start gap-0.5 rounded-md border border-border bg-surface px-3 py-2 text-left hover:bg-surface-hover';

/** "PR-2026-0013-1" for a root order, the child's own code for an add-on order; "ออเดอร์ n" from its tail. */
export function orderOf(ticket) {
  const addOn = ticket.childKind === 'ADD_ON';
  const displayCode = addOn ? ticket.code : `${ticket.code}-1`;
  const n = Number(displayCode.slice(displayCode.lastIndexOf('-') + 1));
  return { displayCode, orderNo: Number.isFinite(n) ? n : 1 };
}

export function DealPickSheet({ onClose, onCreateNew }) {
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  // Unfolded: every order of a project is its own row. Same key family as the deal lists, so a new deal shows at once.
  const listQuery = useQuery({
    queryKey: ['tickets', 'list', 'pick-deal'],
    queryFn: () => api.tickets.list({ size: 500 }).then((r) => r.tickets ?? []),
  });
  const { rows, matched } = useMemo(() => {
    const q = search.trim().toLowerCase();
    const all = (listQuery.data ?? [])
      .filter((t) => (t.lifecycle ?? 'ACTIVE') === 'ACTIVE')
      .map((t) => ({ t, ...orderOf(t) }))
      .filter(({ t, displayCode }) => !q
        || [t.customerName, displayCode, t.projectName, t.title].some((v) => String(v ?? '').toLowerCase().includes(q)));
    return { rows: all.slice(0, 60), matched: all.length };
  }, [listQuery.data, search]);

  return (
    <Modal
      title="เลือกงาน"
      placement="sheet-on-phone"
      onClose={onClose}
      testId="deal-pick-sheet"
      footer={(
        <div className="flex w-full flex-col gap-1">
          <p className="m-0 text-sm text-text-secondary">หาไม่เจอ?</p>
          <Button type="button" variant="secondary" className="min-h-11 w-full" data-testid="pick-deal-create" onClick={onCreateNew}>
            สร้างงานใหม่
          </Button>
          <p className="m-0 text-xs text-text-muted">สำหรับงานที่สั่งซื้อไปแล้วก่อนใช้ระบบ สร้างแล้วขอออกใบกำกับต่อได้เลย</p>
        </div>
      )}
    >
      <div className="flex flex-col gap-3">
        <input
          type="search"
          className="min-h-11"
          placeholder="ค้นหาลูกค้า โครงการ หรือเลขงาน"
          aria-label="ค้นหางาน"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        {listQuery.isLoading ? <p className="m-0 text-sm text-text-muted" role="status">กำลังโหลด…</p> : null}
        {listQuery.isError ? <p className="m-0 text-sm text-danger" role="alert">โหลดรายการงานไม่สำเร็จ</p> : null}
        {!listQuery.isLoading && !listQuery.isError ? (
          <p className="m-0 text-sm font-bold text-text-secondary">งานที่ตรง {rows.length} ออเดอร์</p>
        ) : null}
        <ul className="m-0 flex list-none flex-col gap-2 p-0" data-testid="deal-pick-list">
          {rows.map(({ t, displayCode, orderNo }) => (
            <li key={t.id}>
              <button type="button" className={ROW} data-testid={`pick-deal-${t.id}`} onClick={() => navigate(`/tickets/${t.id}?tab=ship&request=open`)}>
                <b className="text-[0.9375rem]">{t.customerName ?? '-'} <span className="tabular-nums">{displayCode}</span> · ออเดอร์ {orderNo}</b>
                <small className="text-xs text-text-muted">{dealStageLabel(t.salesStage, t.entryChannel).label}</small>
              </button>
            </li>
          ))}
        </ul>
        {matched > rows.length ? <p className="m-0 text-xs text-text-muted" data-testid="pick-more">พิมพ์เพื่อค้นหาเพิ่ม (แสดง {rows.length} จาก {matched} ออเดอร์)</p> : null}
      </div>
    </Modal>
  );
}
