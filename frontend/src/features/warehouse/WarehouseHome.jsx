/* Tokens only. The warehouse manager's home (canvas WH1-WH5): what waits on them, what waits to be delivered, and their
 * trips. Every list and action is the server's (DeliveryTripService); no money anywhere. */
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { TripFormSheet } from './TripFormSheet.jsx';
import { PickupSheet } from './PickupSheet.jsx';
import { TripSheet } from './TripSheet.jsx';
import { RoundText, tripDayLabel } from './tripText.jsx';

function Section({ title, hint, children, testId }) {
  return (
    <section className="flex flex-col gap-2" data-testid={testId} aria-label={title}>
      <h2 className="m-0 flex flex-wrap items-baseline justify-between gap-2 px-1 text-sm font-bold text-text-secondary">
        {title}
        {hint ? <span className="font-normal text-text-muted">{hint}</span> : null}
      </h2>
      {children}
    </section>
  );
}

const CARD = 'm-0 list-none divide-y divide-border-subtle overflow-hidden rounded-md border border-border bg-surface p-0';
const EMPTY = 'm-0 rounded-md border border-border bg-surface px-4 py-3 text-sm text-text-muted';

export function WarehouseHome() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [picked, setPicked] = useState([]);
  const [form, setForm] = useState(null); // { trip } to edit, { trip: null } to create
  const [openId, setOpenId] = useState(null);
  const [pickupRound, setPickupRound] = useState(null);
  const [justDone, setJustDone] = useState(null); // an old trip leaves the list once done; its result must stay on screen
  const query = useQuery({ queryKey: queryKeys.deliveryTripsHome(), queryFn: () => api.deliveryTrips.home() });
  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.deliveryTripsHome() });
  const switchPickup = useMutation({
    mutationFn: ({ roundId, pickup }) => api.deliveryRequests.setPickup(roundId, pickup),
    onSettled: refresh,
  });
  const save = useMutation({
    mutationFn: ({ id, body }) => (id ? api.deliveryTrips.update(id, body) : api.deliveryTrips.create(body)),
    onSuccess: () => { setForm(null); setPicked([]); },
    onSettled: refresh,
  });

  if (query.isLoading) return <Skeleton height={256} />;
  if (query.isError) {
    return <StatePanel title="โหลดข้อมูลงานคลังไม่สำเร็จ" description={query.error.message} action={<Button type="button" variant="secondary" onClick={() => query.refetch()}>ลองอีกครั้ง</Button>} />;
  }
  const { today, toCheck, toInvoice, waiting, pickups, trips, routes } = query.data;
  const selected = picked.filter((id) => waiting.some((r) => r.roundId === id));
  const openTrip = trips.find((t) => t.id === openId) ?? (justDone?.id === openId ? justDone : null);
  const toggle = (id) => setPicked((now) => (now.includes(id) ? now.filter((x) => x !== id) : [...now, id]));
  const openForm = (trip) => { save.reset(); setForm({ trip }); };
  const openOrder = (round) => navigate(`/tickets/${round.ticketId}?tab=ship`);

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-5" data-testid="warehouse-home">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="m-0">งานคลัง</h1>
          <p className="m-0 text-sm text-text-muted">
            {formatThaiDate(today)} · รอคุณตรวจ {toCheck.length} · รอจัดรอบส่ง {waiting.length}
          </p>
        </div>
      </header>

      <div className="grid gap-5 lg:grid-cols-2">
        <div className="flex min-w-0 flex-col gap-5">
          <Section title="ขอออกใบกำกับแล้ว · รอคุณตรวจ" hint={`${toCheck.length} รายการ`} testId="to-check">
            {toCheck.length ? (
              <ul className={CARD}>
                {toCheck.map((r) => (
                  <li key={r.roundId}>
                    <button type="button" className="flex min-h-14 w-full cursor-pointer items-center gap-3 border-0 bg-transparent px-4 py-2 text-left text-text" onClick={() => openOrder(r)}>
                      <RoundText round={r} handover extra={r.sentAt ? `ส่งเรื่อง ${formatThaiDate(r.sentAt)}` : null} />
                      <Icon name="chevronRight" size={16} />
                    </button>
                  </li>
                ))}
              </ul>
            ) : <p className={EMPTY}>ไม่มีคำขอที่รอตรวจ</p>}
          </Section>

          {toInvoice.length ? (
            <Section title="รอออกใบกำกับ" hint={`${toInvoice.length} รายการ`} testId="to-invoice">
              <ul className={CARD}>
                {toInvoice.map((r) => (
                  <li key={r.roundId} className="px-4 py-2">
                    <RoundText round={r} handover extra={r.forwardedAt ? `ส่งต่อแล้ว ${formatThaiDate(r.forwardedAt)}` : null} />
                  </li>
                ))}
              </ul>
            </Section>
          ) : null}

          <Section title="ลูกค้ามารับเอง" hint={`${pickups.length} ออเดอร์`} testId="pickups">
            {pickups.length ? (
              <ul className={CARD}>
                {pickups.map((r) => (
                  <li key={r.roundId} className="flex flex-col gap-1 px-4 py-2">
                    <RoundText round={r} />
                    <div className="flex flex-wrap items-center gap-2">
                      <Button type="button" variant="primary" className="min-h-12" data-testid={`pickup-done-${r.roundId}`} onClick={() => setPickupRound(r)}>ลูกค้ารับของแล้ว</Button>
                      <Button type="button" variant="text" className="min-h-12" disabled={switchPickup.isPending} onClick={() => switchPickup.mutate({ roundId: r.roundId, pickup: false })}>เปลี่ยนเป็นส่งให้ลูกค้า</Button>
                    </div>
                  </li>
                ))}
              </ul>
            ) : <p className={EMPTY}>ไม่มีออเดอร์ที่รอลูกค้ามารับ</p>}
            {switchPickup.error ? <p role="alert" className="m-0 px-1 text-sm text-danger">{switchPickup.error.message}</p> : null}
          </Section>

          <Section title="ได้ใบกำกับแล้ว · รอจัดรอบส่ง" hint={selected.length ? `เลือกแล้ว ${selected.length} จาก ${waiting.length}` : `${waiting.length} ออเดอร์`} testId="waiting">
            {waiting.length ? (
              <ul className={CARD}>
                {waiting.map((r) => (
                  <li key={r.roundId}>
                    <label className="flex min-h-14 cursor-pointer items-start gap-3 px-4 py-2">
                      <input type="checkbox" className="mt-1 size-5 shrink-0 accent-primary" checked={selected.includes(r.roundId)} onChange={() => toggle(r.roundId)} />
                      <RoundText round={r} />
                      {r.delayed ? <span className="shrink-0 rounded-full bg-warning-bg px-2 py-0.5 text-xs font-bold text-warning-dark">เลื่อนส่ง</span> : null}
                    </label>
                    <Button type="button" variant="text" className="ml-11 min-h-12" disabled={switchPickup.isPending} onClick={() => switchPickup.mutate({ roundId: r.roundId, pickup: true })}>เปลี่ยนเป็นลูกค้ามารับเอง</Button>
                  </li>
                ))}
              </ul>
            ) : <p className={EMPTY}>ไม่มีออเดอร์ที่รอจัดรอบส่ง</p>}
            {selected.length ? (
              <Button type="button" variant="primary" className="w-full" onClick={() => openForm(null)}>
                จัดรอบส่ง {selected.length} ออเดอร์ที่เลือก
              </Button>
            ) : null}
          </Section>
        </div>

        <div className="flex min-w-0 flex-col gap-2">
          <Section title="รอบส่ง" hint="รอบที่ยังไม่ปิด และของสัปดาห์นี้" testId="trips">
            {trips.length ? (
              <ul className={CARD}>
                {trips.map((t) => (
                  <li key={t.id}>
                    <button type="button" className="flex min-h-14 w-full cursor-pointer items-center gap-3 border-0 bg-transparent px-4 py-2 text-left text-text" onClick={() => setOpenId(t.id)}>
                      <span className="min-w-0 flex-1">
                        <b className="block text-base">{formatThaiDate(t.deliveryDate)} · {t.route}</b>
                        <small className="block text-sm text-text-muted">{[`${t.rounds.length} ออเดอร์`, t.timeSlot, t.vehicle].filter(Boolean).join(' · ')}</small>
                      </span>
                      <span className="shrink-0 rounded-full bg-surface-subtle px-2 py-0.5 text-xs font-bold text-text-secondary">{tripDayLabel(t, today)}</span>
                      <Icon name="chevronRight" size={16} />
                    </button>
                  </li>
                ))}
              </ul>
            ) : <p className={EMPTY}>ยังไม่มีรอบส่ง เลือกออเดอร์ที่รอจัดรอบส่งแล้วกดจัดรอบส่ง</p>}
          </Section>
        </div>
      </div>

      {pickupRound ? (
        <PickupSheet round={pickupRound} onClose={() => setPickupRound(null)} onDone={() => { setPickupRound(null); refresh(); }} />
      ) : null}
      {openTrip && !form ? (
        <TripSheet trip={openTrip} today={today} onClose={() => setOpenId(null)} onEdit={() => openForm(openTrip)} onChanged={refresh} onDelivered={setJustDone} onOpenOrder={openOrder} />
      ) : null}
      {form ? (
        <TripFormSheet
          trip={form.trip}
          today={today}
          routes={routes}
          waiting={waiting}
          initialIds={form.trip ? form.trip.rounds.filter((r) => r.state === 'PLANNED').map((r) => r.roundId) : selected}
          pending={save.isPending}
          error={save.error?.message}
          onSubmit={(body) => save.mutate({ id: form.trip?.id, body })}
          onClose={() => setForm(null)}
        />
      ) : null}
    </div>
  );
}
