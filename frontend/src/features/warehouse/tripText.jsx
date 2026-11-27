import { formatThaiDate } from '../../utils/format.js';
const num = (n) => Number(n).toLocaleString('en-US', { maximumFractionDigits: 2 });

/** `handover`: say how the customer gets the goods (the check list, where the manager reads it first). */
export function RoundText({ round, extra = null, handover = false }) {
  return (
    <span className="min-w-0 flex-1">
      <b className="block text-base [overflow-wrap:anywhere]">{round.projectName || round.customerName || round.displayCode}</b>
      <small className="block text-sm text-text-muted">
        {[round.displayCode, `รอบส่งที่ ${round.roundNo}`, Number(round.remaining) > 0 ? `${num(round.remaining)} แผ่น` : null, round.ownerName ? `ฝ่ายขาย ${round.ownerName}` : null, handover ? (round.customerPickup ? 'ลูกค้ามารับเอง' : 'ส่งให้ลูกค้า') : null].filter(Boolean).join(' · ')}
      </small>
      {round.requestedDate ? <b className="block text-sm">{round.customerPickup ? 'ลูกค้าจะมารับ' : 'ต้องการให้ส่ง'} {formatThaiDate(round.requestedDate)}</b> : null}
      {round.note || extra ? <small className="block text-sm text-text-muted">{[extra, round.note].filter(Boolean).join(' · ')}</small> : null}
    </span>
  );
}

export function tripDayLabel(trip, today) {
  if (trip.status === 'DONE') return 'ส่งแล้ว';
  if (trip.deliveryDate === today) return 'วันนี้';
  return trip.deliveryDate < today ? 'ค้างส่ง' : 'วางแผนแล้ว';
}
