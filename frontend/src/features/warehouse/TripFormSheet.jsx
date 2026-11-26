/* Tokens only. Create or edit one trip (canvas WH3): date and route required, the rest optional. */
import { zodResolver } from '@hookform/resolvers/zod';
import { Controller, useForm } from 'react-hook-form';
import { z } from 'zod';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { RoundText } from './tripText.jsx';

const FIELD = 'min-h-12 w-full rounded-md border border-border-input bg-surface px-3 text-base';
const OPTIONAL = [
  ['timeSlot', 'ช่วงเวลา', 'เช่น รอบเช้า 08:00–12:00', 120],
  ['vehicle', 'รถและคนขับ', 'เช่น รถ 6 ล้อ · คุณสมชาย', 120],
  ['note', 'หมายเหตุถึงฝ่ายขาย', 'เช่น โทรแจ้งลูกค้าก่อนถึง 30 นาที', 500],
];

const schema = z.object({
  deliveryDate: z.string().min(1, 'ระบุวันที่ส่ง'),
  route: z.string().trim().min(1, 'ระบุสายส่ง'),
  timeSlot: z.string(),
  vehicle: z.string(),
  note: z.string(),
  roundIds: z.array(z.number()),
});

export function TripFormSheet({ trip, today, routes, waiting, initialIds, pending, error, onSubmit, onClose }) {
  const { register, control, handleSubmit, setValue, watch } = useForm({
    resolver: zodResolver(schema),
    defaultValues: {
      deliveryDate: trip?.deliveryDate ?? '', route: trip?.route ?? '', timeSlot: trip?.timeSlot ?? '', vehicle: trip?.vehicle ?? '', note: trip?.note ?? '', roundIds: initialIds,
    },
  });
  const values = watch();
  const ids = values.roundIds;
  const planned = trip?.rounds.filter((r) => r.state === 'PLANNED') ?? [];
  const choices = [...planned, ...waiting];
  const blocked = !values.deliveryDate ? 'ระบุวันที่ส่ง' : !values.route.trim() ? 'ระบุสายส่ง' : !trip && !ids.length ? 'เลือกออเดอร์อย่างน้อย 1 รายการ' : null;
  const toggle = (id) => setValue('roundIds', ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]);

  return (
    <Modal
      title={trip ? 'แก้ไขรอบส่ง' : 'สร้างรอบส่ง'}
      onClose={pending ? () => {} : onClose}
      placement="sheet-on-phone"
      testId="trip-form"
      footer={(
        <div className="flex w-full flex-col gap-1">
          {error ? <p role="alert" className="m-0 text-sm text-danger">{error}</p> : null}
          <Button type="submit" form="trip-form-fields" variant="primary" className="w-full" disabled={pending || Boolean(blocked)}>
            {pending ? 'กำลังบันทึก…' : trip ? 'บันทึกการแก้ไข' : 'สร้างรอบส่งและแจ้งฝ่ายขาย'}
          </Button>
          <small className="text-center text-xs text-text-muted">{blocked ?? 'ฝ่ายขายเจ้าของออเดอร์จะได้รับการแจ้งเตือนและอีเมล'}</small>
        </div>
      )}
    >
      <SafeForm
        id="trip-form-fields"
        className="flex flex-col gap-4"
        canSubmit={!blocked && !pending}
        onSubmit={handleSubmit(onSubmit)}
      >
        <label className="flex flex-col gap-1 text-sm font-bold" htmlFor="trip-date">
          วันที่ส่ง
          <Controller
            name="deliveryDate"
            control={control}
            render={({ field }) => <ThaiDateInput id="trip-date" {...field} min={trip && trip.deliveryDate < today ? trip.deliveryDate : today} />}
          />
        </label>
        <div className="flex flex-col gap-2">
          <label className="flex flex-col gap-1 text-sm font-bold">
            สายส่ง
            <input className={FIELD} maxLength={120} placeholder="เช่น สายบางนา–สมุทรปราการ" {...register('route')} />
          </label>
          {routes.length ? (
            <div className="flex flex-wrap gap-2" aria-label="สายที่เคยใช้">
              {routes.map((route) => (
                <Button key={route} type="button" variant="secondary" aria-pressed={values.route === route} className={values.route === route ? 'border-primary bg-primary-bg text-primary' : ''} onClick={() => setValue('route', route)}>
                  {route}
                </Button>
              ))}
            </div>
          ) : null}
        </div>
        {OPTIONAL.map(([key, label, placeholder, max]) => (
          <label key={key} className="flex flex-col gap-1 text-sm font-bold">
            <span>{label} <span className="font-normal text-text-muted">ไม่บังคับ</span></span>
            <input className={FIELD} maxLength={max} placeholder={placeholder} {...register(key)} />
          </label>
        ))}
        <fieldset className="m-0 flex flex-col border-0 p-0">
          <legend className="mb-1 p-0 text-sm font-bold">ออเดอร์ในรอบนี้ {ids.length} ออเดอร์</legend>
          <ul className="m-0 list-none divide-y divide-border-subtle overflow-hidden rounded-md border border-border p-0">
            {choices.map((r) => (
              <li key={r.roundId}>
                <label className="flex min-h-14 cursor-pointer items-start gap-3 px-3 py-2">
                  <input type="checkbox" className="mt-1 size-5 shrink-0 accent-primary" checked={ids.includes(r.roundId)} onChange={() => toggle(r.roundId)} />
                  <RoundText round={r} />
                </label>
              </li>
            ))}
          </ul>
        </fieldset>
      </SafeForm>
    </Modal>
  );
}
