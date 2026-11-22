import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { cn } from '../../utils/cn.js';
import { bangkokTodayIso } from '../../utils/format.js';
import { PURPOSE_LABELS, roundTitle, stepLabel } from './shipmentRounds.js';

const blankLine = () => ({ key: Math.random().toString(36).slice(2), code: '', size: '', qty: '', unit: 'แผ่น' });
const DOC_NUMBER = /^IR\d{5,6}(-\d{1,2})?$/;
const lineOk = (l) => l.code.trim() && Number(l.qty) > 0 && l.unit.trim();

function Chips({ label, id, options, value, onChange, testPrefix }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs font-bold text-text-secondary" id={id}>{label}</span>
      <div className="flex flex-wrap gap-2" role="group" aria-labelledby={id}>
        {options.map(([code, text]) => (
          <button key={code} type="button" aria-pressed={value === code} onClick={() => onChange(code)} data-testid={`${testPrefix}-${code}`}
            className={cn('inline-flex min-h-11 items-center rounded-full border px-4 text-sm font-bold',
              value === code ? 'border-primary bg-primary/10 text-primary' : 'border-border-input bg-surface text-text-secondary')}>
            {text}
          </button>
        ))}
      </div>
    </div>
  );
}

function Field({ id, label, children }) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <label htmlFor={id} className="text-xs font-bold text-text-secondary">{label}</label>
      {children}
    </div>
  );
}

/**
 * สร้างงานนำเข้า and its edit (ID2 / IM2): an import request with no sales job, which import fills in themselves. A centred
 * dialog from 640px, a bottom sheet on a phone. With `job` it edits the header only: items are edited on the job's page.
 */
export function ImportJobSheet({ job, onClose, onSaved, showToast }) {
  const queryClient = useQueryClient();
  // a save that finishes after this sheet was dismissed must not close or navigate away from a newer one
  const live = useRef(true);
  useEffect(() => () => { live.current = false; }, []);
  const editing = Boolean(job);
  const ir = job?.importRequest;
  const [purpose, setPurpose] = useState(job?.purpose ?? 'CUSTOMER');
  const [note, setNote] = useState(job?.purposeNote ?? '');
  const [requesterId, setRequesterId] = useState(job?.requesterId ? String(job.requesterId) : '');
  const [factoryId, setFactoryId] = useState(ir?.factoryId ? String(ir.factoryId) : '');
  const [numberMode, setNumberMode] = useState(editing ? 'TYPED' : 'NEXT');
  const [docNumber, setDocNumber] = useState(ir?.docNumber ?? '');
  const [receivedOn, setReceivedOn] = useState(ir?.firstIssuedDate ?? bangkokTodayIso());
  const [depositOn, setDepositOn] = useState(ir?.depositReceivedDate ?? '');
  const [wantedBy, setWantedBy] = useState(job?.wantedBy ?? '');
  const [lines, setLines] = useState(() => [blankLine()]);
  const [roundMode, setRoundMode] = useState('NONE');
  const [roundId, setRoundId] = useState('');

  const factories = useQuery({ queryKey: queryKeys.factoryNames(), queryFn: () => api.factoryConfigs.names().then((r) => r.factories ?? []) });
  const reps = useQuery({ queryKey: queryKeys.importJobReps(), queryFn: () => api.importJobs.repOptions().then((r) => r.reps ?? []) });
  const roundsQuery = useQuery({ queryKey: queryKeys.shipmentRounds(), queryFn: () => api.shipmentRounds.list(), enabled: !editing });
  const joinable = (roundsQuery.data?.rounds ?? []).filter((r) => String(r.factoryId) === factoryId && r.importStep !== 'RECEIVED');
  const factoryLocked = editing && (job.rounds?.length ?? 0) > 0;

  const setLine = (key, patch) => setLines((cur) => cur.map((l) => (l.key === key ? { ...l, ...patch } : l)));
  const typed = docNumber.trim().toUpperCase();
  const missing = [
    !note.trim() && 'โครงการ / ลูกค้า',
    !factoryId && 'โรงงาน',
    !receivedOn && 'วันที่ได้รับใบขอซื้อ',
    receivedOn !== ir?.firstIssuedDate && receivedOn > bangkokTodayIso() && 'วันที่ได้รับต้องไม่เป็นวันในอนาคต',
    numberMode === 'TYPED' && typed !== ir?.docNumber && !DOC_NUMBER.test(typed) && 'เลขใบขอซื้อ (เช่น IR69412)',
    !editing && (!lines.some(lineOk) || lines.some((l) => (l.code.trim() || l.qty) && !lineOk(l))) && 'สินค้าและจำนวน',
    !editing && roundMode === 'EXISTING' && !roundId && 'รอบขนส่ง',
  ].filter(Boolean);

  const save = useMutation({
    mutationFn: () => {
      if (!editing) {
        return api.importJobs.create({
          purpose, purposeNote: note.trim(), factoryId: Number(factoryId), receivedOn,
          requesterId: requesterId ? Number(requesterId) : null, depositReceivedOn: depositOn || null, wantedBy: wantedBy || null,
          docNumber: numberMode === 'TYPED' ? typed : null,
          lines: lines.filter(lineOk).map((l) => ({ code: l.code.trim(), size: l.size.trim() || null, qty: Number(l.qty), unit: l.unit.trim() })),
          round: { mode: roundMode, roundId: roundMode === 'EXISTING' ? Number(roundId) : null },
        });
      }
      const clear = [];
      if (!requesterId && job.requesterId) clear.push('requesterId');
      if (!depositOn && ir.depositReceivedDate) clear.push('depositReceivedOn');
      if (!wantedBy && job.wantedBy) clear.push('wantedBy');
      return api.importJobs.update(ir.id, {
        purpose, purposeNote: note.trim(), requesterId: requesterId ? Number(requesterId) : null, receivedOn,
        depositReceivedOn: depositOn || null, wantedBy: wantedBy || null, docNumber: typed,
        factoryId: factoryLocked ? null : Number(factoryId), clear,
      });
    },
    onSuccess: (saved) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.shipmentRounds() });
      queryClient.invalidateQueries({ queryKey: queryKeys.myIncoming() });
      queryClient.setQueryData(queryKeys.importJob(saved.importRequest.id), saved);
      showToast?.('success', editing ? 'บันทึกการแก้ไขแล้ว' : `สร้างงานนำเข้า ${saved.importRequest.docNumber} แล้ว`);
      if (live.current) onSaved?.(saved);
    },
    onError: (e) => showToast?.('error', e?.message ?? 'บันทึกไม่สำเร็จ'),
  });

  const footer = (
    <div className="flex w-full flex-col items-stretch gap-1 sm:items-end">
      <div className="flex flex-col-reverse gap-2 sm:flex-row">
        <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
        <Button type="button" variant="primary" disabled={missing.length > 0 || save.isPending} onClick={() => save.mutate()} data-testid="import-job-save">
          {editing ? 'บันทึกการแก้ไข' : 'สร้างงานนำเข้า'}
        </Button>
      </div>
      {missing.length > 0 ? <small className="text-center text-text-muted sm:text-right" data-testid="import-job-missing">ยังขาด: {missing.join(' · ')}</small> : null}
    </div>
  );

  return (
    <Modal title={editing ? 'แก้ไขงานนำเข้า' : 'สร้างงานนำเข้า'} subtitle={editing ? ir.docNumber : 'สำหรับของที่สั่งโรงงานแล้วแต่ยังไม่มีงานขายในระบบ กรอกเท่าที่รู้ ที่เหลือแก้ทีหลังได้'}
      onClose={onClose} placement="sheet-on-phone" size="lg" testId="import-job-sheet" footer={footer}>
      <fieldset disabled={save.isPending} className="m-0 flex min-w-0 flex-col gap-4 border-0 p-0">
        <div className="flex flex-col gap-1">
          <Chips label="ประเภท *" id="job-purpose" options={Object.entries(PURPOSE_LABELS)} value={purpose} onChange={setPurpose} testPrefix="job-purpose" />
          {purpose === 'CUSTOMER' ? <small className="text-text-muted">งานลูกค้า คือของที่สั่งให้ลูกค้าแล้ว แต่ฝ่ายขายยังไม่ได้เปิดงานในระบบ</small> : null}
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field id="job-note" label="โครงการ / ลูกค้า *">
            <input id="job-note" type="text" maxLength={200} className="min-h-11 w-full text-base" value={note} onChange={(e) => setNote(e.target.value)} />
          </Field>
          <Field id="job-rep" label="ฝ่ายขายผู้ขอ (ไม่บังคับ)">
            <select id="job-rep" className="min-h-11 w-full text-base" value={requesterId} onChange={(e) => setRequesterId(e.target.value)}>
              <option value="">ไม่ระบุ</option>
              {(reps.data ?? []).map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
            </select>
          </Field>
          <Field id="job-factory" label="โรงงาน *">
            <select id="job-factory" className="min-h-11 w-full text-base" value={factoryId} disabled={factoryLocked}
              onChange={(e) => { setFactoryId(e.target.value); setRoundId(''); }}>
              <option value="">เลือกโรงงาน</option>
              {(factories.data ?? []).map((f) => <option key={f.id} value={f.id}>{f.factoryName}</option>)}
            </select>
            {factoryLocked ? <small className="text-text-muted">งานนี้อยู่ในรอบขนส่งแล้ว เอาออกจากรอบก่อนจึงเปลี่ยนโรงงานได้</small> : null}
          </Field>
          {editing ? (
            <Field id="job-number" label="เลขที่ใบขอซื้อ *">
              <input id="job-number" type="text" maxLength={30} className="min-h-11 w-full text-base" value={docNumber} onChange={(e) => setDocNumber(e.target.value)} />
            </Field>
          ) : (
            <div className="flex min-w-0 flex-col gap-2">
              <Chips label="เลขที่ใบขอซื้อ *" id="job-number-mode" options={[['NEXT', 'ออกเลขถัดไป'], ['TYPED', 'ใส่เลขเอง']]} value={numberMode} onChange={setNumberMode} testPrefix="job-number" />
              {numberMode === 'TYPED' ? (
                <input aria-label="เลขที่ใบขอซื้อ" type="text" maxLength={30} placeholder="เช่น IR69412" className="min-h-11 w-full text-base" value={docNumber}
                  onChange={(e) => setDocNumber(e.target.value)} data-testid="job-number-input" />
              ) : null}
            </div>
          )}
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Field id="job-received" label="วันที่ได้รับใบขอซื้อ *">
            <ThaiDateInput id="job-received" className="min-h-11 text-base" max={bangkokTodayIso()} value={receivedOn} onChange={(e) => setReceivedOn(e.target.value)} />
          </Field>
          <Field id="job-deposit" label="วันที่ได้รับมัดจำ (ไม่บังคับ)">
            <ThaiDateInput id="job-deposit" className="min-h-11 text-base" value={depositOn} onChange={(e) => setDepositOn(e.target.value)} />
          </Field>
          <Field id="job-wanted" label="ลูกค้าต้องการของ (ไม่บังคับ)">
            <ThaiDateInput id="job-wanted" className="min-h-11 text-base" value={wantedBy} onChange={(e) => setWantedBy(e.target.value)} />
          </Field>
        </div>
        {editing ? null : (
          <>
            <div className="flex flex-col gap-3">
              <span className="text-xs font-bold text-text-secondary">สินค้าและจำนวน *</span>
              {lines.map((l, i) => (
                <div key={l.key} className="grid grid-cols-2 gap-2 rounded-md border border-border p-3 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_6rem_5rem_auto] sm:items-end sm:border-0 sm:p-0" data-testid="job-line">
                  <div className="col-span-2 flex flex-col gap-1 sm:col-span-1">
                    <label htmlFor={`job-code-${l.key}`} className="text-xs font-bold text-text-secondary">สินค้า (ชื่อหรือรหัสโรงงาน)</label>
                    <input id={`job-code-${l.key}`} type="text" maxLength={255} className="min-h-11 w-full text-base" value={l.code} onChange={(e) => setLine(l.key, { code: e.target.value })} />
                  </div>
                  <div className="col-span-2 flex flex-col gap-1 sm:col-span-1">
                    <label htmlFor={`job-size-${l.key}`} className="text-xs font-bold text-text-secondary">ขนาด</label>
                    <input id={`job-size-${l.key}`} type="text" maxLength={80} className="min-h-11 w-full text-base" value={l.size} onChange={(e) => setLine(l.key, { size: e.target.value })} />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label htmlFor={`job-qty-${l.key}`} className="text-xs font-bold text-text-secondary">จำนวน</label>
                    <input id={`job-qty-${l.key}`} type="number" inputMode="decimal" min="0" step="any" className="min-h-11 w-full text-base tabular-nums" value={l.qty} onChange={(e) => setLine(l.key, { qty: e.target.value })} />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label htmlFor={`job-unit-${l.key}`} className="text-xs font-bold text-text-secondary">หน่วย</label>
                    <input id={`job-unit-${l.key}`} type="text" maxLength={30} className="min-h-11 w-full text-base" value={l.unit} onChange={(e) => setLine(l.key, { unit: e.target.value })} />
                  </div>
                  {lines.length > 1 ? (
                    <Button type="button" size="sm" variant="text" className="col-span-2 justify-self-end sm:col-span-1" onClick={() => setLines((cur) => cur.filter((x) => x.key !== l.key))}
                      aria-label={`ลบรายการที่ ${i + 1}`}>ลบ</Button>
                  ) : <span className="hidden sm:block" />}
                </div>
              ))}
              <Button type="button" variant="secondary" className="w-full sm:w-auto sm:self-start" onClick={() => setLines((cur) => [...cur, blankLine()])} data-testid="job-add-line">+ เพิ่มรายการ</Button>
            </div>
            <div className="flex flex-col gap-2">
              <Chips label="รอบขนส่ง" id="job-round" options={[['NONE', 'ยังไม่จัดรอบ'], ['EXISTING', 'เข้ารอบที่มีอยู่'], ['NEW', 'สร้างรอบใหม่']]} value={roundMode} onChange={setRoundMode} testPrefix="job-round" />
              {roundMode === 'NONE' ? <small className="text-text-muted">ไปอยู่ใน รอจัดรอบขนส่ง ก่อน</small> : null}
              {roundMode === 'NEW' ? <small className="text-text-muted">ใส่ชื่อเรือและวันที่ทีหลังได้</small> : null}
              {roundMode === 'EXISTING' ? (
                <>
                  <label htmlFor="job-round-pick" className="sr-only">เลือกรอบขนส่ง</label>
                  <select id="job-round-pick" className="min-h-11 w-full text-base" value={roundId} onChange={(e) => setRoundId(e.target.value)}>
                    <option value="">{factoryId ? (joinable.length ? 'เลือกรอบขนส่ง' : 'โรงงานนี้ยังไม่มีรอบที่เข้าได้') : 'เลือกโรงงานก่อน'}</option>
                    {joinable.map((r) => <option key={r.id} value={r.id}>{roundTitle(r)} ({stepLabel(r.importStep)})</option>)}
                  </select>
                </>
              ) : null}
            </div>
            <p className="m-0 rounded-md border border-info-border bg-info-bg px-3 py-2 text-sm">ไม่ต้องขออนุมัติ ไม่มีราคา และไม่มีขั้นตอนเงิน · ฝ่ายขายทุกคนเห็นความคืบหน้าของใบขอซื้อนี้</p>
          </>
        )}
      </fieldset>
    </Modal>
  );
}
