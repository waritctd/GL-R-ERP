import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate } from '../../utils/format.js';
import { IMPORT_STEPS, importStepIndex } from '../importProgress/importSteps.js';
import { AdvanceRoundSheet } from './AdvanceRoundSheet.jsx';
import { DownloadIrButton } from './DownloadIrButton.jsx';
import { LotModal } from './LotModal.jsx';
import { PlanRoundSheet } from './PlanRoundSheet.jsx';
import { RoundDocuments } from './RoundDocuments.jsx';
import { RoundFactsModal } from './RoundFactsModal.jsx';
import {
  factLabel, formatQty, lotLabel, lotProduct, lotsEditable, lotWho, lotWhoSub, roundIrCount, roundTitle, STEP_DATE_KEY, stepPillText, stepTone,
} from './shipmentRounds.js';
import { useRoundUndo } from './useRoundUndo.jsx';

// Whole literal, one track per column (5): IR, who, product, quantity on this round, lot.
const LOTS_GRID = 'grid-cols-[minmax(0,0.9fr)_minmax(0,1.5fr)_minmax(0,1.3fr)_minmax(0,0.8fr)_minmax(0,1.2fr)]';
// With the lot actions column (round not yet left): 6 tracks.
const LOTS_GRID_EDIT = 'grid-cols-[minmax(0,0.9fr)_minmax(0,1.5fr)_minmax(0,1.3fr)_minmax(0,0.8fr)_minmax(0,1.2fr)_minmax(0,0.8fr)]';

function StepList({ round }) {
  const current = importStepIndex(round.importStep);
  return (
    <ol className="m-0 flex list-none flex-col p-0" aria-label="ขั้นตอนของรอบ">
      {IMPORT_STEPS.map((s, i) => {
        const state = i < current || round.importStep === 'RECEIVED' ? 'done' : i === current ? 'now' : 'todo';
        const date = round[STEP_DATE_KEY[s.code]];
        return (
          <li key={s.code} data-step={s.code} data-state={state} className="flex min-h-11 items-center gap-3 border-t border-border-subtle py-2 first:border-t-0">
            <span className={cn('grid size-7 shrink-0 place-items-center rounded-full text-sm font-bold',
              state === 'done' && 'bg-success text-surface', state === 'now' && 'bg-info text-surface', state === 'todo' && 'bg-surface-subtle text-text-muted')}>
              {state === 'done' ? '✓' : i + 1}
            </span>
            <span className="flex min-w-0 flex-col">
              <b className="text-sm">{s.label}</b>
              <small className="text-text-muted">{date ? formatThaiDate(date) : state === 'todo' ? 'ยังไม่ถึง' : '-'}</small>
            </span>
          </li>
        );
      })}
    </ol>
  );
}

const Kv = ({ k, v }) => (
  <div className="flex justify-between gap-3 border-t border-border-subtle py-1.5 text-sm first:border-t-0">
    <span className="text-text-muted">{k}</span><b className="text-right">{v || '-'}</b>
  </div>
);

/** N02 / ND2 — one shipment round: its steps with the real dates, the shipment facts, and the IRs (lots) on it. */
export function ShipmentRoundPage({ user, showToast }) {
  const { id } = useParams();
  const editable = user?.role === 'import' || user?.role === 'ceo';
  const [advancing, setAdvancing] = useState(false);
  const [editing, setEditing] = useState(false);
  const [planning, setPlanning] = useState(false);
  const [lotOpen, setLotOpen] = useState(null);
  const undo = useRoundUndo({ showToast });
  const query = useQuery({
    queryKey: queryKeys.shipmentRound(id),
    queryFn: () => api.shipmentRounds.get(id).then((r) => r.round),
    enabled: !!id,
    retry: false,
  });
  const round = query.data;
  const canEditLots = editable && round != null && lotsEditable(round);
  // The queue feeds the add picker (waiting IRs) and the move targets (other rounds of this factory that have not left).
  const listQuery = useQuery({ queryKey: queryKeys.shipmentRounds(), queryFn: () => api.shipmentRounds.list(), enabled: canEditLots });
  const targets = (listQuery.data?.rounds ?? []).filter((r) => r.id !== round?.id && r.factoryId === round?.factoryId && lotsEditable(r));

  if (query.isLoading) return <PageStack><Panel aria-busy="true" aria-label="กำลังโหลดรอบขนส่ง"><SkeletonText lines={6} /></Panel></PageStack>;
  if (query.isError || !round) {
    return (
      <PageStack>
        <Panel data-testid="round-error">
          <EmptyState icon="search" title={query.error?.status === 404 ? 'ไม่พบรอบขนส่งนี้' : 'โหลดรอบขนส่งไม่สำเร็จ'}
            description={<Link to="/fulfilment" className="text-info underline">กลับไปหน้า การขนส่ง</Link>} />
        </Panel>
      </PageStack>
    );
  }

  const f = round.facts ?? {};
  const mode = f.transportMode;
  const lotColumns = [
    { key: 'ir', header: 'ใบขอซื้อ', render: (l) => (l.ticketId == null ? <span className="flex flex-col items-start gap-1"><code>{l.docNumber}</code><DownloadIrButton importRequestId={l.importRequestId} docNumber={l.docNumber} factoryName={round.factoryName} showToast={showToast} /></span> : <Link to={`/import/deals/${l.ticketId}`} className="text-info underline"><code>{l.docNumber}</code></Link>) },
    { key: 'who', header: 'โครงการ / ลูกค้า', render: (l) => <span className="flex flex-col"><b className="text-sm">{lotWho(l)}</b><small className="text-text-muted">{lotWhoSub(l)}</small></span> },
    { key: 'product', header: 'สินค้า', render: (l) => lotProduct(l) },
    { key: 'qty', header: 'ไปรอบนี้', render: (l) => <span className="flex flex-col tabular-nums"><b>{formatQty(l.qty)} {l.unit ?? ''}</b><small className="text-text-muted">จาก {formatQty(l.itemQty)}</small></span> },
    { key: 'lot', header: 'ล็อต', render: (l) => <span className="flex flex-col"><span>{lotLabel(l)}</span>{l.remainingQty > 0 ? <small className="text-text-muted">เหลือ {formatQty(l.remainingQty)} รอจัดรอบ</small> : null}</span> },
    ...(canEditLots ? [{
      key: 'manage',
      header: '',
      render: (l) => <Button type="button" size="sm" variant="secondary" onClick={() => setLotOpen(l)} data-testid={`lot-manage-${l.lotId}`}>จัดการ</Button>,
    }] : []),
  ];
  const lotCard = (l) => (
    <div className="flex items-start justify-between gap-3 rounded-md border border-border bg-surface p-3" data-testid="lot-card">
      <span className="flex min-w-0 flex-col gap-0.5">
        <b className="text-sm">{lotWho(l)}</b>
        {l.ticketId == null ? <DownloadIrButton importRequestId={l.importRequestId} docNumber={l.docNumber} factoryName={round.factoryName} showToast={showToast} /> : null}
        <small className="text-text-muted">{l.ticketId == null ? <code>{l.docNumber}</code> : <Link to={`/import/deals/${l.ticketId}`} className="text-info underline"><code>{l.docNumber}</code></Link>} · {lotProduct(l)}</small>
        <small className="text-text-muted">{lotLabel(l)}{l.remainingQty > 0 ? ` · เหลือ ${formatQty(l.remainingQty)} รอจัดรอบ` : ''}</small>
      </span>
      <span className="flex shrink-0 flex-col items-end gap-1 text-right tabular-nums">
        <span><b>{formatQty(l.qty)} {l.unit ?? ''}</b><br /><small className="text-text-muted">จาก {formatQty(l.itemQty)}</small></span>
        {canEditLots ? <Button type="button" size="sm" variant="secondary" onClick={() => setLotOpen(l)} data-testid={`lot-manage-${l.lotId}`}>จัดการ</Button> : null}
      </span>
    </div>
  );

  return (
    <PageStack>
      <PageHeader
        title={roundTitle(round)}
        breadcrumbs={[{ label: 'การขนส่ง', to: '/fulfilment' }, { label: 'รอบขนส่ง' }]}
        subtitle={(
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge tone={stepTone(round.importStep)}>{stepPillText(round.importStep)}</StatusBadge>
            <span>{roundIrCount(round)} ใบขอซื้อ</span>
          </span>
        )}
        actions={(canEditLots || (editable && round.importStep !== 'RECEIVED')) ? (
          <>
            {canEditLots ? (
              <Button type="button" variant="secondary" className="hidden min-[1041px]:inline-flex" onClick={() => setPlanning(true)} data-testid="round-add-ir-header">+ เพิ่มใบขอซื้อเข้ารอบนี้</Button>
            ) : null}
            {editable && round.importStep !== 'RECEIVED' ? (
              <Button type="button" variant="primary" onClick={() => setAdvancing(true)} data-testid="round-advance">เลื่อนขั้นตอน</Button>
            ) : null}
          </>
        ) : null}
      />
      <div className="grid grid-cols-1 gap-4 min-[1041px]:grid-cols-[22rem_minmax(0,1fr)] min-[1041px]:items-start">
        <div className="flex min-w-0 flex-col gap-4">
          <Panel title="ขั้นตอน"><StepList round={round} /></Panel>
          <Panel title="เอกสาร"><RoundDocuments round={round} editable={editable} showToast={showToast} /></Panel>
          <Panel
            title="ข้อมูลรอบนี้"
            actions={editable ? <Button type="button" size="sm" variant="secondary" onClick={() => setEditing(true)} data-testid="round-facts-edit">แก้ไข</Button> : null}
          >
            <Kv k={factLabel({ key: 'vessel' }, mode)} v={f.vessel} />
            {f.voyage ? <Kv k={factLabel({ key: 'voyage' }, mode)} v={f.voyage} /> : null}
            {f.forwarder ? <Kv k="ตัวแทนขนส่ง" v={f.forwarder} /> : null}
            {f.containerNos ? <Kv k="เลขตู้" v={f.containerNos} /> : null}
            <Kv k="ของพร้อมที่โรงงาน" v={f.goodsReadyOn && formatThaiDate(f.goodsReadyOn)} />
            <Kv k={factLabel({ key: 'etdPlannedOn' }, mode)} v={f.etdPlannedOn && formatThaiDate(f.etdPlannedOn)} />
            <Kv k={factLabel({ key: 'etdActualOn' }, mode)} v={f.etdActualOn && formatThaiDate(f.etdActualOn)} />
            <Kv k="คาดว่าถึงไทย" v={f.etaOn && formatThaiDate(f.etaOn)} />
            <Kv k={factLabel({ key: 'port' }, mode)} v={f.port} />
            <Kv k="ของถึงไทย" v={f.arrivedOn && formatThaiDate(f.arrivedOn)} />
            <Kv k="ออกของ" v={f.customsReleasedOn && formatThaiDate(f.customsReleasedOn)} />
            <Kv k="ใบตรวจรับ (GR)" v={f.grNumber} />
            {f.note ? <Kv k="หมายเหตุ" v={f.note} /> : null}
            {f.completePerInvoice != null ? <Kv k="ของครบตามอินวอยซ์" v={f.completePerInvoice ? 'ครบ' : `ไม่ครบ${f.completeNote ? ` · ${f.completeNote}` : ''}`} /> : null}
          </Panel>
        </div>
        <section className="flex min-w-0 flex-col gap-2" aria-label="ใบขอซื้อในรอบนี้">
          <h2 className="m-0 flex items-baseline gap-2 text-base font-bold">ใบขอซื้อในรอบนี้ <span className="text-2xs font-normal text-text-muted">{roundIrCount(round)} ใบ</span></h2>
          <DataTable columns={lotColumns} rows={round.lots} getRowKey={(l) => l.lotId} gridClassName={canEditLots ? LOTS_GRID_EDIT : LOTS_GRID} mobileCard={lotCard}
            emptyState={{ icon: 'check', title: 'ยังไม่มีใบขอซื้อในรอบนี้' }} />
          {canEditLots ? (
            <Button type="button" variant="secondary" className="w-full sm:w-auto min-[1041px]:hidden" onClick={() => setPlanning(true)} data-testid="round-add-ir">เพิ่มใบขอซื้อเข้ารอบนี้</Button>
          ) : null}
          {editable && !lotsEditable(round) ? <p className="m-0 text-xs text-text-muted">เพิ่มหรือแบ่งของได้จนกว่าเรือจะออก หลังจากนั้นแก้ได้เฉพาะข้อมูลและเอกสารของรอบ</p> : null}
          <p className="m-0 text-xs text-text-muted">ใบขอซื้อทุกใบในรอบนี้เลื่อนขั้นตอนพร้อมกัน · แก้ไขข้อมูลใบขอซื้อ (ระยะเวลานำเข้า อีเมลสั่งซื้อ PDF) ที่หน้าของใบนั้น</p>
        </section>
      </div>

      {advancing ? (
        <AdvanceRoundSheet round={round} showToast={showToast} onClose={() => setAdvancing(false)}
          onAdvanced={(r) => { setAdvancing(false); undo.offer(r); }} />
      ) : null}
      {planning ? (
        <PlanRoundSheet round={round} waiting={listQuery.data?.waiting ?? []} showToast={showToast} onClose={() => setPlanning(false)} onDone={() => setPlanning(false)} />
      ) : null}
      {lotOpen ? <LotModal round={round} lot={lotOpen} targets={targets} showToast={showToast} onClose={() => setLotOpen(null)} /> : null}
      {editing ? <RoundFactsModal round={round} showToast={showToast} onClose={() => setEditing(false)} /> : null}
      {undo.snackbar}
    </PageStack>
  );
}
