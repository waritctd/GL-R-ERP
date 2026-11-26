/* Tokens only. One trip (canvas WH4/WH5): delay an order, or press ส่งของแล้ว once for all the others. */
import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { RoundText, tripDayLabel } from './tripText.jsx';

export function TripSheet({ trip, today, onClose, onEdit, onChanged, onDelivered, onOpenOrder }) {
  const [confirm, setConfirm] = useState(null); // 'deliver' | 'cancel'
  const [results, setResults] = useState([]);
  const delay = useMutation({
    mutationFn: ({ roundId, delayed }) => api.deliveryTrips.delay(trip.id, roundId, delayed),
    onSettled: onChanged,
  });
  const deliver = useMutation({
    mutationFn: () => api.deliveryTrips.deliver(trip.id),
    onSuccess: (res) => { setResults(res.results); onDelivered(res.trip); },
    onSettled: () => { setConfirm(null); return onChanged(); },
  });
  const cancel = useMutation({
    mutationFn: () => api.deliveryTrips.cancel(trip.id),
    onSuccess: onClose,
    onSettled: () => { setConfirm(null); return onChanged(); },
  });
  const open = trip.status === 'OPEN';
  const planned = trip.rounds.filter((r) => r.state === 'PLANNED');
  const delivered = results.filter((r) => r.delivered).length;
  const blocked = results.filter((r) => !r.delivered);
  const busy = delay.isPending || deliver.isPending || cancel.isPending;
  const error = delay.error?.message || deliver.error?.message || cancel.error?.message;
  const deliveredOn = trip.deliveryDate < today ? trip.deliveryDate : today;

  return (
    <>
      <Modal
        title={`รอบส่ง ${formatThaiDate(trip.deliveryDate)}`}
        subtitle={`${trip.route} · ${tripDayLabel(trip, today)}`}
        onClose={onClose}
        placement="sheet-on-phone"
        testId="trip-sheet"
        footer={open ? (
          <div className="flex w-full flex-col gap-1">
            {error ? <p role="alert" className="m-0 text-sm text-danger">{error}</p> : null}
            <Button type="button" variant="primary" className="w-full" disabled={busy || !planned.length} onClick={() => setConfirm('deliver')}>
              {deliver.isPending ? 'กำลังบันทึก…' : `ส่งของแล้ว ${planned.length} ออเดอร์`}
            </Button>
            <div className="flex justify-between gap-2">
              <Button type="button" variant="text" className="min-h-12" disabled={busy} onClick={onEdit}>แก้ไขรอบส่ง</Button>
              {!trip.rounds.some((r) => r.state === 'DELIVERED') ? (
                <Button type="button" variant="text" className="min-h-12 text-danger" disabled={busy} onClick={() => setConfirm('cancel')}>ยกเลิกรอบส่งนี้</Button>
              ) : null}
            </div>
          </div>
        ) : null}
      >
        <div className="flex flex-col gap-3">
          {[trip.timeSlot, trip.vehicle, trip.note].some(Boolean) ? (
            <p className="m-0 text-sm text-text-secondary">{[trip.timeSlot, trip.vehicle, trip.note].filter(Boolean).join(' · ')}</p>
          ) : null}
          {results.length ? (
            <p role="status" className="m-0 rounded-md border border-border bg-surface-subtle px-3 py-2 text-sm">
              บันทึกส่งของแล้ว {delivered} ออเดอร์{blocked.length ? ` · ยังบันทึกไม่ได้ ${blocked.length} ออเดอร์` : ''}
            </p>
          ) : null}
          <ul className="m-0 list-none divide-y divide-border-subtle overflow-hidden rounded-md border border-border p-0">
            {trip.rounds.map((r) => {
              const refused = r.state === 'PLANNED' ? blocked.find((b) => b.roundId === r.roundId) : null;
              return (
                <li key={r.roundId} className={`flex flex-col gap-2 px-3 py-2 ${r.state === 'DELAYED' ? 'bg-surface-subtle' : ''}`}>
                  <div className="flex items-start gap-2">
                    <RoundText round={r} />
                    {r.state === 'DELIVERED' ? <span className="shrink-0 rounded-full bg-success-bg px-2 py-0.5 text-xs font-bold text-success-dark">ส่งแล้ว</span> : null}
                    {r.state === 'DELAYED' ? <span className="shrink-0 rounded-full bg-warning-bg px-2 py-0.5 text-xs font-bold text-warning-dark">เลื่อนส่ง</span> : null}
                  </div>
                  {refused ? <p role="alert" className="m-0 text-sm text-danger">ยังส่งไม่ได้: {refused.reason}</p> : null}
                  <div className="flex justify-between gap-2">
                    <Button type="button" variant="text" className="min-h-11" onClick={() => onOpenOrder(r)}>ดูเอกสาร</Button>
                    {open && r.state === 'PLANNED' ? (
                      <Button type="button" variant="secondary" disabled={busy} onClick={() => delay.mutate({ roundId: r.roundId, delayed: true })}>เลื่อนส่ง</Button>
                    ) : null}
                    {open && r.state === 'DELAYED' ? (
                      <Button type="button" variant="text" className="min-h-11" disabled={busy} onClick={() => delay.mutate({ roundId: r.roundId, delayed: false })}>ยกเลิกการเลื่อน</Button>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      </Modal>
      {confirm === 'deliver' ? (
        <ConfirmDialog
          open
          title={`ส่งของแล้ว ${planned.length} ออเดอร์`}
          message={`ระบบจะบันทึกส่งครบทุกออเดอร์ที่ไม่ได้เลื่อน วันที่ส่ง ${formatThaiDate(deliveredOn)}`}
          confirmLabel="ยืนยันส่งของแล้ว"
          busy={deliver.isPending}
          onConfirm={() => deliver.mutate()}
          onCancel={() => setConfirm(null)}
        />
      ) : null}
      {confirm === 'cancel' ? (
        <ConfirmDialog
          open
          title="ยกเลิกรอบส่งนี้"
          message="ออเดอร์จะกลับไปรอจัดรอบส่ง และฝ่ายขายจะได้รับแจ้ง"
          confirmLabel="ยกเลิกรอบส่ง"
          busy={cancel.isPending}
          onConfirm={() => cancel.mutate()}
          onCancel={() => setConfirm(null)}
        />
      ) : null}
    </>
  );
}
