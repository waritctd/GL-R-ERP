const num = (n) => Number(n).toLocaleString('en-US', { maximumFractionDigits: 2 });

export function RoundText({ round, extra = null }) {
  return (
    <span className="min-w-0 flex-1">
      <b className="block text-base [overflow-wrap:anywhere]">{round.projectName || round.customerName || round.displayCode}</b>
      <small className="block text-sm text-text-muted">
        {[round.displayCode, `รอบส่งที่ ${round.roundNo}`, Number(round.remaining) > 0 ? `${num(round.remaining)} แผ่น` : null, round.ownerName ? `ฝ่ายขาย ${round.ownerName}` : null].filter(Boolean).join(' · ')}
      </small>
      {round.note || extra ? <small className="block text-sm text-text-muted">{[extra, round.note].filter(Boolean).join(' · ')}</small> : null}
    </span>
  );
}

export function tripDayLabel(trip, today) {
  if (trip.status === 'DONE') return 'ส่งแล้ว';
  if (trip.deliveryDate === today) return 'วันนี้';
  return trip.deliveryDate < today ? 'ค้างส่ง' : 'วางแผนแล้ว';
}
