/* Tokens only. ลูกค้ารับของแล้ว (canvas PK3): the warehouse records who collected the whole remaining round. The goods go
 * through the same record-delivery call as every delivery (same gates); a photo may go with it, attached to the round after. */
import { useRef, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { addDaysIso, bangkokTodayIso } from '../../utils/format.js';
import { MAX_DELIVERY_BACK_DAYS } from '../tickets/deliveryDate.js';
import { MAX_UPLOAD_BYTES } from '../tickets/delivery/deliveryRound.js';
import { RoundText } from './tripText.jsx';

const FIELD = 'min-h-12 w-full rounded-md border border-border-input bg-surface px-3 text-base font-normal';

export function PickupSheet({ round, onClose, onDone }) {
  const pickerRef = useRef(null);
  const today = bangkokTodayIso();
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [collectedOn, setCollectedOn] = useState(today);
  const [photo, setPhoto] = useState(null);
  const [error, setError] = useState(null);
  const [photoFailed, setPhotoFailed] = useState(null); // the handover is saved; only the photo can be retried
  const tooBig = photo != null && photo.size > MAX_UPLOAD_BYTES;
  const blocked = !name.trim() ? 'ระบุชื่อผู้มารับของ' : tooBig ? 'ไฟล์มีขนาดใหญ่เกินไป (สูงสุด 10 MB)' : null;

  const attach = async () => {
    try {
      await api.deliveryRequests.addFile(round.roundId, photo);
      onDone();
    } catch (e) {
      setPhotoFailed(e?.message || 'แนบรูปไม่สำเร็จ');
    }
  };
  const save = useMutation({
    mutationFn: () => api.deliveryRequests.recordDelivery(round.roundId, {
      complete: true,
      recipientName: name.trim(),
      ...(phone.trim() ? { note: `โทร ${phone.trim()}` } : {}),
      ...(collectedOn && collectedOn !== today ? { deliveredOn: collectedOn } : {}),
    }),
    onSuccess: () => (photo ? attach() : onDone()),
    onError: (e) => setError(e?.message || 'บันทึกไม่สำเร็จ'),
  });

  if (photoFailed) {
    return (
      <Modal
        title="บันทึกการรับของแล้ว"
        onClose={onDone}
        placement="sheet-on-phone"
        testId="pickup-sheet"
        footer={(
          <div className="flex w-full flex-col gap-1">
            <Button type="button" variant="primary" className="w-full" onClick={() => { setPhotoFailed(null); attach(); }}>แนบรูปอีกครั้ง</Button>
            <Button type="button" variant="text" className="min-h-12 w-full" onClick={onDone}>เสร็จ ไม่แนบรูป</Button>
          </div>
        )}
      >
        <p role="alert" className="m-0 text-base text-danger">บันทึกแล้ว แต่แนบรูปไม่สำเร็จ: {photoFailed}</p>
      </Modal>
    );
  }

  return (
    <Modal
      title="ลูกค้ารับของแล้ว"
      onClose={save.isPending ? () => {} : onClose}
      placement="sheet-on-phone"
      testId="pickup-sheet"
      footer={(
        <div className="flex w-full flex-col gap-1">
          {error ? <p role="alert" className="m-0 text-center text-sm text-danger">{error}</p> : null}
          {blocked ? <p role="status" className="m-0 text-center text-sm text-text-secondary">{blocked}</p> : null}
          <Button type="button" variant="primary" className="w-full" disabled={Boolean(blocked) || save.isPending} data-testid="pickup-confirm" onClick={() => { setError(null); save.mutate(); }}>
            {save.isPending ? 'กำลังบันทึก…' : 'ยืนยันลูกค้ารับของแล้ว'}
          </Button>
          <Button type="button" variant="text" className="min-h-12 w-full" disabled={save.isPending} onClick={onClose}>ยกเลิก</Button>
        </div>
      )}
    >
      <div className="flex flex-col gap-4">
        <div className="rounded-md border border-border bg-surface px-3 py-2"><RoundText round={round} /></div>
        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          ชื่อผู้มารับของ
          <input className={FIELD} maxLength={255} autoComplete="off" value={name} data-testid="pickup-name" onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          <span>เบอร์โทร <span className="font-normal text-text-muted">ไม่บังคับ</span></span>
          <input className={FIELD} type="tel" inputMode="tel" maxLength={30} autoComplete="off" value={phone} onChange={(e) => setPhone(e.target.value)} />
        </label>
        <label htmlFor="pickup-date" className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          วันที่รับของ
          <ThaiDateInput id="pickup-date" value={collectedOn} min={addDaysIso(today, -MAX_DELIVERY_BACK_DAYS)} max={today} onChange={(e) => setCollectedOn(e.target.value || today)} className="min-h-12 w-full text-base" />
        </label>
        <div className="flex flex-col gap-1.5">
          <span className="text-sm font-bold text-text-secondary">รูปหลักฐาน <span className="font-normal text-text-muted">ไม่บังคับ</span></span>
          {photo ? (
            <div className="flex min-h-12 items-center gap-2 rounded-md border border-border bg-surface px-3">
              <Icon name="image" size={18} />
              <span className="min-w-0 flex-1 truncate text-sm">{photo.name}</span>
              <Button type="button" variant="text" className="min-h-12" onClick={() => setPhoto(null)}>เอาออก</Button>
            </div>
          ) : (
            <Button type="button" variant="secondary" className="min-h-12 w-full gap-2 border-dashed" data-testid="pickup-photo" onClick={() => pickerRef.current?.click()}>
              <Icon name="camera" size={18} />
              ถ่ายรูปหรือเลือกไฟล์
            </Button>
          )}
          <input ref={pickerRef} type="file" accept="image/*,.pdf" className="sr-only" tabIndex={-1} aria-hidden="true" data-testid="pickup-photo-input"
            onChange={(e) => { setPhoto(e.target.files?.[0] ?? null); e.target.value = ''; }} />
        </div>
      </div>
    </Modal>
  );
}
