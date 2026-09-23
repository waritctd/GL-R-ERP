import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate } from '../../utils/format.js';

const STATUS_LABEL = { DRAFT: 'ร่าง', ISSUED: 'ออกแล้ว' };
const STATUS_TONE = { DRAFT: 'warning', ISSUED: 'success' };

/**
 * GLA-129: a compact entry point into `RemainingInvoiceDialog` from the money tab — the dialog
 * itself already carries the full generate → edit → issue → version-history → download lifecycle
 * (GLA-99 step 2), so this card is new UI around an old, unmodified implementation, not a second
 * one. `onManage` opens the SAME dialog instance/state TicketDetailPage's own sticky-bar button
 * already controls (`remainingInvoiceDialogOpen`) — deliberately not a second independent
 * `useState` here, which would risk two `RemainingInvoiceDialog` instances mounted at once if a
 * viewer opened it from both entry points.
 *
 * `canManage` (review round 1, 2026-09-23): the status/docNumber summary is shown to the FULL
 * sections.payment audience this card is mounted for (sales/sales_manager/ceo/account) — reading
 * it leaks nothing the pipeline above doesn't already state. The "จัดการ..." BUTTON is narrower —
 * the caller must pass exactly `can.downloadRemainingInvoice`
 * (`isRemainingInvoiceReady(summary) && isSales`), the SAME gate the pre-existing sticky-bar entry
 * point uses, so this card cannot offer a control account/ceo/non-sales never had, and cannot offer
 * it on a deal that isn't ready yet — which would otherwise contradict the pipeline's own "รอ
 * ขั้นตอนก่อนหน้า" reading for the exact same deal a few lines above.
 */
export function DealRemainingInvoiceCard({ ticketId, canManage, onManage }) {
  const listQuery = useQuery({
    queryKey: queryKeys.storedRemainingInvoices(ticketId),
    queryFn: () => api.storedRemainingInvoices.listForTicket(ticketId).then((r) => r.remainingInvoices ?? []),
    enabled: !!ticketId,
  });
  const rows = listQuery.data ?? [];
  const live = rows.find((r) => r.status === 'ISSUED') ?? rows.find((r) => r.status === 'DRAFT') ?? null;

  return (
    <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <strong className="text-sm">ใบแจ้งหนี้ส่วนที่เหลือ</strong>
        {live ? <StatusBadge tone={STATUS_TONE[live.status]}>{STATUS_LABEL[live.status]}</StatusBadge> : null}
      </div>
      {listQuery.isLoading ? (
        <p className="text-xs text-text-muted">กำลังโหลด…</p>
      ) : live ? (
        <p className="text-xs text-text-muted">
          {live.docNumber ?? '(ร่าง)'} · {formatThaiDate(live.docDate)}
        </p>
      ) : (
        <p className="text-xs text-text-muted">ยังไม่มีใบแจ้งหนี้ส่วนที่เหลือ</p>
      )}
      {canManage ? (
        <Button type="button" variant="secondary" className="self-start" onClick={onManage}>
          จัดการใบแจ้งหนี้ส่วนที่เหลือ
        </Button>
      ) : null}
    </div>
  );
}
