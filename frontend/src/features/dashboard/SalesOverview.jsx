import { MyWork } from '../salesBoard/MyWork.jsx';
import { bangkokTodayIso, formatThaiDate, greetingName } from '../../utils/format.js';

/**
 * Sales' landing: งานของฉัน (spec 2026-11-15 §7.3, slice 1) — the quiet deals to chase, then the five-column board.
 * Own-scoped server-side by GET /api/sales/my-work (created_by = the caller); this component adds no client-side
 * ownership filtering. The full pipeline (รายการดีล / TicketListPage) and the commission page stay their own nav
 * items.
 */
export function SalesOverview({ user, employee }) {
  const greetName = employee?.nickName || employee?.nameTh || user?.name || '';
  return <MyWork subtitle={`สวัสดี, ${greetingName(greetName)} · ${formatThaiDate(bangkokTodayIso())}`} />;
}
