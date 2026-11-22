import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate } from '../../utils/format.js';
import { IMPORT_STEPS, importStepIndex } from '../importProgress/importSteps.js';
import { ImportJobSheet } from './ImportJobSheet.jsx';
import { formatQty, ORIGIN_LABELS, PURPOSE_LABELS, stepLabel, stepTone } from './shipmentRounds.js';

const ITEMS_GRID = 'grid-cols-[minmax(0,1.7fr)_minmax(0,0.9fr)_minmax(0,1.2fr)_minmax(0,0.9fr)_minmax(0,1fr)]';

const FIELD_LABELS = {
  purpose: 'ประเภท', purposeNote: 'โครงการ / ลูกค้า', requester: 'ฝ่ายขายผู้ขอ', requesterId: 'รหัสผู้ขอ', receivedOn: 'วันที่ได้รับใบขอซื้อ',
  depositReceivedOn: 'วันที่ได้รับมัดจำ', wantedBy: 'ลูกค้าต้องการของ', docNumber: 'เลขที่ใบขอซื้อ', factory: 'โรงงาน',
  code: 'ชื่อสินค้า', size: 'ขนาด', qty: 'จำนวน', unit: 'หน่วย',
};
const DATE_FIELDS = new Set(['receivedOn', 'depositReceivedOn', 'wantedBy']);

const parse = (text) => {
  try {
    return text ? JSON.parse(text) : {};
  } catch {
    return {};
  }
};
const shown = (field, value) => {
  if (value == null || value === '') return 'ว่าง';
  if (field === 'purpose') return PURPOSE_LABELS[value] ?? value;
  return DATE_FIELDS.has(field) ? formatThaiDate(value) : String(value);
};

/** One line of the history in plain Thai, built from the audit row's before and after. */
export function historyText(h) {
  const before = parse(h.before);
  const after = parse(h.after);
  if (h.action === 'IMPORT_JOB_BACKFILLED') return 'นำเข้าจากตารางของเข้า';
  if (h.action === 'IMPORT_JOB_CREATED') return `สร้างงานนำเข้า ${after.docNumber ?? ''}`.trim();
  if (h.action === 'IMPORT_JOB_ITEM_ADDED') return `เพิ่มสินค้า ${after.item} ${formatQty(after.qty)} ${after.unit ?? ''}`.trim();
  if (h.action === 'IMPORT_JOB_ITEM_REMOVED') return `ลบสินค้า ${before.item} ${formatQty(before.qty)} ${before.unit ?? ''}`.trim();
  const fields = Object.keys(after).filter((f) => f !== 'item');
  const parts = fields.map((f) => `${FIELD_LABELS[f] ?? f} จาก ${shown(f, before[f])} เป็น ${shown(f, after[f])}`);
  return `${h.action === 'IMPORT_JOB_ITEM_UPDATED' && before.item ? `${before.item}: ` : 'แก้'}${parts.join(', ')}`;
}

function Fact({ label, value }) {
  return (
    <div className="flex justify-between gap-3 border-t border-border-subtle py-1.5 text-sm first:border-t-0">
      <span className="text-text-muted">{label}</span>
      <b className="min-w-0 text-right [overflow-wrap:anywhere]">{value || '-'}</b>
    </div>
  );
}

function ItemEditor({ draft, setDraft, locked }) {
  return (
    <div className="grid grid-cols-2 gap-2">
      <div className="col-span-2 flex flex-col gap-1">
        <label htmlFor="item-code" className="text-2xs text-text-muted">สินค้า</label>
        <input id="item-code" className="min-h-11 w-full text-base" maxLength={255} value={draft.code} onChange={(e) => setDraft({ ...draft, code: e.target.value })} />
      </div>
      <div className="col-span-2 flex flex-col gap-1">
        <label htmlFor="item-size" className="text-2xs text-text-muted">ขนาด</label>
        <input id="item-size" className="min-h-11 w-full text-base" maxLength={80} value={draft.size} onChange={(e) => setDraft({ ...draft, size: e.target.value })} />
      </div>
      <div className="flex flex-col gap-1">
        <label htmlFor="item-qty" className="text-2xs text-text-muted">จำนวน</label>
        <input id="item-qty" type="number" inputMode="decimal" min="0" step="any" disabled={locked} className="min-h-11 w-full text-base tabular-nums" value={draft.qty} onChange={(e) => setDraft({ ...draft, qty: e.target.value })} />
      </div>
      <div className="flex flex-col gap-1">
        <label htmlFor="item-unit" className="text-2xs text-text-muted">หน่วย</label>
        <input id="item-unit" className="min-h-11 w-full text-base" maxLength={30} value={draft.unit} onChange={(e) => setDraft({ ...draft, unit: e.target.value })} />
      </div>
      {locked ? <small className="col-span-2 text-text-muted">ของถึงโกดังแล้ว แก้จำนวนไม่ได้</small> : null}
    </div>
  );
}

function RoundCard({ round }) {
  const current = importStepIndex(round.step);
  const dates = { ORDERED: round.orderedOn, PICKED_UP: round.pickedUpOn, IN_TRANSIT: round.etdActualOn, AWAITING_CUSTOMS: round.arrivedOn, RECEIVED: round.receivedOn };
  return (
    <Panel className="flex flex-col gap-3">
      <div className="flex items-start justify-between gap-3">
        <span className="flex min-w-0 flex-col gap-0.5">
          <Link to={`/fulfilment/rounds/${round.id}`} className="text-sm font-bold text-text underline">{[round.factoryName, round.label].filter(Boolean).join(' · ')}</Link>
          <span className="text-2xs text-text-muted">{round.vessel ? `${round.transportMode === 'AIR' ? 'เที่ยวบิน' : 'เรือ'} ${round.vessel}` : 'ยังไม่ระบุพาหนะ'}{round.port ? ` · ${round.port}` : ''}</span>
        </span>
        <StatusBadge tone={stepTone(round.step)}>{stepLabel(round.step)}</StatusBadge>
      </div>
      <ol className="m-0 grid list-none grid-cols-5 gap-2 p-0 mobile:grid-cols-1">
        {IMPORT_STEPS.filter((s) => s.code !== 'CONTACTED').map((s) => {
          const index = importStepIndex(s.code);
          const done = index < current || (index === current && s.code === 'RECEIVED');
          const now = index === current && !done;
          return (
            <li key={s.code} className={cn('rounded-md px-2.5 py-2', done ? 'bg-success-bg' : now ? 'bg-info-bg' : 'bg-surface-subtle')}>
              <span className={cn('block text-2xs font-bold', done ? 'text-success-dark' : now ? 'text-info' : 'text-text-muted')}>{s.label}{done ? ' ✓' : ''}</span>
              <span className={cn('text-sm tabular-nums', !done && !now && 'text-text-muted')}>
                {dates[s.code] ? formatThaiDate(dates[s.code]) : s.code === 'AWAITING_CUSTOMS' && round.etaOn ? `คาดว่าถึง ${formatThaiDate(round.etaOn)}` : '-'}
              </span>
            </li>
          );
        })}
      </ol>
      <div className="flex flex-wrap gap-x-6 gap-y-1 text-sm">
        <span><span className="text-text-muted">ETA ท่าเปลี่ยนเรือ </span><b>{round.transshipEtaOn ? formatThaiDate(round.transshipEtaOn) : '-'}</b></span>
        <span><span className="text-text-muted">วันที่ทำประกัน </span><b>{round.insuredOn ? formatThaiDate(round.insuredOn) : '-'}</b></span>
      </div>
    </Panel>
  );
}

/** งานนำเข้า (ID3 / IM3): one import request with no sales job. Import and the CEO correct it here; the steps stay on the round page. */
export function ImportJobPage(props) {
  const { id } = useParams();
  // keyed by the job, so moving to another job never carries an open editor or a draft along
  return <ImportJobPageBody key={id} id={id} {...props} />;
}

function ImportJobPageBody({ id, showToast }) {
  const queryClient = useQueryClient();
  const [editingJob, setEditingJob] = useState(false);
  const [editItem, setEditItem] = useState(null);
  const [draft, setDraft] = useState(null);
  const [removing, setRemoving] = useState(null);
  const query = useQuery({ queryKey: queryKeys.importJob(id), queryFn: () => api.importJobs.get(id), retry: false });
  const job = query.data;

  const applied = (saved, message) => {
    queryClient.setQueryData(queryKeys.importJob(id), saved);
    queryClient.invalidateQueries({ queryKey: queryKeys.shipmentRounds() });
    queryClient.invalidateQueries({ queryKey: queryKeys.myIncoming() });
    setEditItem(null);
    setDraft(null);
    setRemoving(null);
    showToast?.('success', message);
  };
  const failed = (e) => { setRemoving(null); showToast?.('error', e?.message ?? 'บันทึกไม่สำเร็จ'); };
  const payload = () => ({ code: draft.code.trim(), size: draft.size.trim(), qty: Number(draft.qty), unit: draft.unit.trim() });
  const saveItem = useMutation({
    mutationFn: () => (editItem === 'new' ? api.importJobs.addItem(id, payload()) : api.importJobs.updateItem(id, editItem, payload())),
    onSuccess: (saved) => applied(saved, 'บันทึกรายการแล้ว'),
    onError: failed,
  });
  const removeItem = useMutation({
    mutationFn: (item) => api.importJobs.removeItem(id, item.id),
    onSuccess: (saved) => applied(saved, 'ลบรายการแล้ว'),
    onError: failed,
  });

  if (query.isLoading) return <PageStack><p className="text-sm text-text-muted">กำลังโหลด…</p></PageStack>;
  if (query.isError || !job) {
    return (
      <PageStack>
        <EmptyState icon="alert" title="เปิดงานนำเข้านี้ไม่ได้" description={query.error?.message ?? 'ไม่พบงานนำเข้านี้'} />
        <Link to="/fulfilment" className={cn(buttonVariants({ variant: 'secondary' }), 'self-start no-underline')}>กลับไปการขนส่ง</Link>
      </PageStack>
    );
  }

  const ir = job.importRequest;
  const startEdit = (item) => { setEditItem(item.id); setDraft({ code: item.code, size: item.size ?? '', qty: String(item.qty), unit: item.unit ?? '', locked: item.locked }); };
  const startAdd = () => { setEditItem('new'); setDraft({ code: '', size: '', qty: '', unit: 'แผ่น', locked: false }); };
  const draftOk = draft && draft.code.trim() && Number(draft.qty) > 0 && draft.unit.trim();
  const editorActions = (
    <span className="flex flex-wrap justify-end gap-2">
      <Button type="button" size="sm" variant="secondary" onClick={() => { setEditItem(null); setDraft(null); }}>ยกเลิก</Button>
      <Button type="button" size="sm" variant="primary" disabled={!draftOk || saveItem.isPending} onClick={() => saveItem.mutate()} data-testid="item-save">บันทึก</Button>
    </span>
  );
  const lotText = (item) => (item.lots.length === 0 ? 'รอจัดรอบ' : item.lots.length === 1 && Number(item.lots[0].qty) === Number(item.qty) ? 'ครบทั้งรายการ' : `${item.lots.length} ล็อต`);
  const roundName = (item) => {
    if (item.lots.length === 0) return null;
    const r = job.rounds.find((x) => x.id === item.lots[0].roundId);
    return r ? [r.factoryName, r.label].filter(Boolean).join(' · ') : null;
  };
  const stepOf = (item) => (item.lots.length === 0 ? <StatusBadge tone="warning">รอจัดรอบ</StatusBadge> : <StatusBadge tone={stepTone(item.lots[0].step)}>{stepLabel(item.lots[0].step)}</StatusBadge>);
  const busy = saveItem.isPending || removeItem.isPending;
  const itemActions = (item) => (job.canEdit ? (
    <span className="flex flex-wrap justify-end gap-2">
      <Button type="button" size="sm" variant="secondary" disabled={busy} onClick={() => startEdit(item)} data-testid={`item-edit-${item.id}`}>แก้ไข</Button>
      {!item.locked && job.items.length > 1 ? <Button type="button" size="sm" variant="text" disabled={busy || (editItem != null && editItem !== item.id)} onClick={() => setRemoving(item)}>ลบ</Button> : null}
    </span>
  ) : null);

  const columns = [
    { key: 'item', header: 'สินค้า', render: (i) => (editItem === i.id ? <ItemEditor draft={draft} setDraft={setDraft} locked={draft.locked} /> : (
      <span className="flex flex-col gap-0.5"><b className="text-sm">{[i.code, i.size].filter(Boolean).join(' ')}</b>{i.note ? <small className="text-text-muted">{i.note}</small> : null}</span>
    )) },
    { key: 'qty', header: 'จำนวน', render: (i) => <b className="tabular-nums">{formatQty(i.qty)} {i.unit}</b> },
    { key: 'round', header: 'รอบขนส่ง', render: (i) => <span className="flex flex-col gap-0.5"><span className="text-sm font-bold">{roundName(i) ?? '-'}</span><small className="text-text-muted">{lotText(i)}</small></span> },
    { key: 'step', header: 'ขั้นตอน', render: stepOf },
    { key: 'actions', header: '', render: (i) => (editItem === i.id ? editorActions : itemActions(i)) },
  ];
  const card = (i) => (
    <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3" data-testid="job-item-card">
      {editItem === i.id ? <><ItemEditor draft={draft} setDraft={setDraft} locked={draft.locked} />{editorActions}</> : (
        <>
          <div className="flex items-start justify-between gap-3">
            <span className="flex min-w-0 flex-col gap-0.5"><b className="text-sm">{[i.code, i.size].filter(Boolean).join(' ')}</b>
              <small className="text-text-muted">{[roundName(i), lotText(i)].filter(Boolean).join(' · ')}</small></span>
            <b className="shrink-0 tabular-nums">{formatQty(i.qty)} {i.unit}</b>
          </div>
          <div className="flex items-center justify-between gap-2">{stepOf(i)}{itemActions(i)}</div>
        </>
      )}
    </div>
  );

  return (
    <PageStack>
      <nav aria-label="เส้นทาง" className="text-sm text-text-muted">
        <Link to="/fulfilment" className="text-info underline">การขนส่ง</Link> › งานนำเข้า <code>{ir.docNumber}</code>
      </nav>
      <PageHeader
        title={job.purposeNote}
        subtitle={[ir.factoryName, ir.requestedByName ? `ฝ่ายขาย ${ir.requestedByName}` : null].filter(Boolean).join(' · ')}
        actions={job.canEdit ? <Button type="button" variant="primary" onClick={() => setEditingJob(true)} data-testid="job-edit">แก้ไขข้อมูล</Button> : null}
      />
      <div className="flex flex-wrap items-center gap-2">
        <code>{ir.docNumber}</code>
        <StatusBadge tone="info">{PURPOSE_LABELS[job.purpose] ?? job.purpose}{job.purpose === 'CUSTOMER' ? ' · ยังไม่มีงานขาย' : ''}</StatusBadge>
        <StatusBadge tone="neutral">{ORIGIN_LABELS[job.source === 'PORTAL' ? 'SALES_ORDER' : job.source]}</StatusBadge>
      </div>

      {/* DOM order is the phone order (data, items, history); from desktop the history moves under the data card. */}
      <div className="grid grid-cols-1 items-start gap-[18px] min-[1041px]:grid-cols-[22rem_minmax(0,1fr)] min-[1041px]:grid-rows-[auto_1fr]">
          <Panel title="ข้อมูลงานนำเข้า">
            <Fact label="ประเภท" value={PURPOSE_LABELS[job.purpose]} />
            <Fact label="โครงการ / ลูกค้า" value={job.purposeNote} />
            <Fact label="ฝ่ายขายผู้ขอ" value={ir.requestedByName} />
            <Fact label="โรงงาน" value={ir.factoryName} />
            <Fact label="วันที่ได้รับใบขอซื้อ" value={formatThaiDate(ir.firstIssuedDate)} />
            <Fact label="วันที่ได้รับมัดจำ" value={ir.depositReceivedDate ? formatThaiDate(ir.depositReceivedDate) : null} />
            <Fact label="ลูกค้าต้องการของ" value={job.wantedBy ? formatThaiDate(job.wantedBy) : ir.requiredByNote} />
          </Panel>

        <div className="flex min-w-0 flex-col gap-3 min-[1041px]:col-start-2 min-[1041px]:row-span-2 min-[1041px]:row-start-1">
          <h2 className="m-0 flex items-baseline gap-2 text-base font-bold">สินค้าและล็อต
            <span className="text-2xs font-normal text-text-muted">{job.items.length} รายการ · แก้จำนวนได้จนกว่าของจะถึงโกดัง</span>
          </h2>
          <DataTable columns={columns} rows={job.items} getRowKey={(i) => i.id} gridClassName={ITEMS_GRID} mobileCard={card} emptyState={{ icon: 'check', title: 'ไม่มีรายการ' }} />
          {editItem === 'new' ? (
            <Panel className="flex flex-col gap-3" data-testid="item-new">
              <ItemEditor draft={draft} setDraft={setDraft} locked={false} />
              {editorActions}
            </Panel>
          ) : null}
          {job.canEdit && editItem !== 'new' ? <Button type="button" variant="secondary" className="w-full sm:w-auto sm:self-start" disabled={busy} onClick={startAdd} data-testid="item-add">+ เพิ่มสินค้า</Button> : null}

          <h2 className="m-0 mt-2 flex items-baseline gap-2 text-base font-bold">รอบขนส่งของงานนี้
            <span className="text-2xs font-normal text-text-muted">เลื่อนขั้นตอนและแก้วันที่ที่หน้ารอบขนส่ง</span>
          </h2>
          {job.rounds.length === 0 ? <Panel><p className="m-0 text-sm text-text-muted">ยังไม่ได้จัดรอบ งานนี้รออยู่ใน รอจัดรอบขนส่ง</p></Panel> : null}
          {job.rounds.map((r) => <RoundCard key={r.id} round={r} />)}
          <p className="m-0 text-xs text-text-muted">ทุกการแก้ไขถูกบันทึกไว้ในประวัติ · ฝ่ายขายเห็นสินค้า ขั้นตอน และวันที่ แต่ไม่เห็นเอกสารโรงงานและราคา</p>
        </div>
        <Panel title="ประวัติ" className="min-w-0 min-[1041px]:col-start-1 min-[1041px]:row-start-2">
          <ul className="m-0 flex list-none flex-col p-0" data-testid="job-history">
            {job.history.map((h, i) => (
              <li key={`${h.at}-${i}`} className="flex flex-col gap-0.5 border-t border-border-subtle py-2 first:border-t-0">
                <span className="text-sm font-bold">{historyText(h)}</span>
                <span className="text-2xs text-text-muted">{h.actorName} · {formatThaiDate(h.at)}</span>
              </li>
            ))}
          </ul>
          {job.history.length === 0 ? <p className="m-0 text-sm text-text-muted">ยังไม่มีการแก้ไข</p> : null}
        </Panel>
      </div>

      {editingJob ? <ImportJobSheet job={job} showToast={showToast} onClose={() => setEditingJob(false)} onSaved={() => setEditingJob(false)} /> : null}
      <ConfirmDialog
        open={Boolean(removing)}
        title="ลบรายการสินค้า"
        message={removing ? `ลบ ${removing.code} ออกจาก ${ir.docNumber} ใช่ไหม ล็อตของรายการนี้จะถูกเอาออกจากรอบขนส่งด้วย รายการอื่นไม่เปลี่ยน` : ''}
        confirmLabel="ลบรายการ"
        cancelLabel="ไม่ลบ"
        busy={removeItem.isPending}
        onConfirm={() => removeItem.mutate(removing)}
        onCancel={() => setRemoving(null)}
      />
    </PageStack>
  );
}
