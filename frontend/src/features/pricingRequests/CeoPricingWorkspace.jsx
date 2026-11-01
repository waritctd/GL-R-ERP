import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate } from '../../utils/format.js';
import { pricingRequestRecipientLabel } from './pricingRequestMeta.js';

// CEO pricing workspace for a NEW-FORM decision (redesign, 2026-11): header, sticky summary bar,
// pricing table (desktop) / cards (mobile), details row, ONE save. Presentational only: the page
// builds the view-model (`model`) from its drafts and mutations, so no pricing rule lives here.
// The table AND the cards are both rendered and hidden with Tailwind classes; the card inputs
// reuse the table's data-testids (scope queries with within()).

const MODES = [
  ['NET', 'ราคาตั้ง − ส่วนลด %', 'ใส่กำไร % แล้วระบบคำนวณราคาตั้งจากต้นทุน · ส่วนลด % หักจากราคาตั้ง'],
  ['SPECIAL_SQM', 'ราคาพิเศษ/ตร.ม.', 'ใส่ราคาพิเศษต่อ ตร.ม. (รวม VAT) ระบบคำนวณราคาสุทธิต่อแผ่นให้'],
  ['DIRECT_NET', 'ราคาสุทธิ/แผ่น', 'ใส่ราคาสุทธิต่อแผ่น (ก่อน VAT) ตรง ๆ'],
];

const INPUT = 'min-w-0 rounded-md border border-border-input bg-surface px-2 text-right disabled:opacity-60';
const CARD_INPUT = `${INPUT} w-full min-h-[44px]`;

export function CeoPricingHeader({ summary, status, actions }) {
  return (
    <header data-testid="pcr-ceo-header" className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2.5">
          <h1 className="m-0 text-2xl font-extrabold">{summary.requestCode}</h1>
          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
        </div>
        <p className="m-0 mt-0.5 text-text-muted">{summary.customerName ?? '-'}</p>
        <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-sm text-text-muted">
          <span>ดีล <Link to={`/tickets/${summary.ticketId}`} className="text-info underline">{summary.ticketCode}</Link></span>
          <span>ผู้รับ: {pricingRequestRecipientLabel(summary.recipientType)}{summary.recipientLabel ? ` · ${summary.recipientLabel}` : ''}</span>
          <span>ต้องการภายใน {formatThaiDate(summary.requiredDate)}</span>
          <span>ฝ่ายนำเข้า: ผู้รับเรื่องและประสานราคาโรงงาน</span>
          {summary.projectName ? <span>โครงการ {summary.projectName}</span> : null}
        </div>
        {/* GLA-125 header terms, read-only (what the old ภาพรวม panel showed) */}
        {(summary.paymentTermMode || summary.validityDays != null || summary.unitCode
          || summary.printedByDisplayId != null || summary.salesRepDisplayId != null || summary.omitContactHonorific) ? (
          <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
            <span>เงื่อนไขการชำระเงิน: {summary.paymentTermMode === 'CREDIT'
              ? `เครดิต ${summary.creditDays ?? '—'} วัน`
              : summary.paymentTermMode === 'ON_DELIVERY' ? 'ชำระเมื่อส่งมอบ' : '—'}</span>
            <span>ยืนราคา: {summary.validityDays != null ? `${summary.validityDays} วัน` : '—'}</span>
            <span>หน่วยงาน / รหัสผู้ออกแบบ: {summary.unitCode || '—'}</span>
            <span>ไม่เติม &quot;คุณ&quot; หน้าชื่อผู้รับ: {summary.omitContactHonorific ? 'ใช่' : 'ไม่ใช่'}</span>
          </div>
        ) : null}
      </div>
      <div className="flex flex-wrap gap-2">{actions}</div>
    </header>
  );
}

// Row edge: blocked -> danger, unsaved -> warning.
function edgeClass(row) {
  if (row.blocker) return 'border-l-4 border-l-danger';
  return row.dirty ? 'border-l-4 border-l-warning' : '';
}

function Tags({ row }) {
  return (
    <div className="mt-1 flex flex-wrap gap-1">
      {row.isStock ? <StatusBadge tone="warning">จากสต็อก</StatusBadge> : null}
      {row.uncostable ? <StatusBadge tone="danger">ต้องระบุต้นทุนเอง</StatusBadge> : null}
      {row.stale ? <StatusBadge tone="warning">ต้นทุนที่ปรับล้าสมัย</StatusBadge> : null}
      {row.hasPriceOverride ? <StatusBadge tone="info">ปรับเอง</StatusBadge> : null}
      {row.dirty ? <StatusBadge tone="warning">ยังไม่บันทึก</StatusBadge> : null}
      {!row.blocker && !row.dirty ? <StatusBadge tone="success">มีราคาแล้ว</StatusBadge> : null}
    </div>
  );
}

function Num({ row, field, label, testid, value, disabled, suffix, card, max }) {
  return (
    <span className={cn('inline-flex items-center gap-1', card && 'w-full')}>
      <input
        type="number"
        step="0.01"
        min={field === 'marginPct' ? undefined : '0'}
        max={max}
        inputMode="decimal"
        className={card ? CARD_INPUT : cn(INPUT, suffix === '%' ? 'w-20' : 'w-28')}
        aria-label={`${label} ${row.name}`}
        disabled={disabled}
        value={value}
        onChange={(e) => row.setDraft({ [field]: e.target.value })}
        data-testid={testid}
      />
      {suffix ? <span className="shrink-0 text-xs text-text-muted">{suffix}</span> : null}
    </span>
  );
}

// The price inputs of one row, in column order for the current mode. `card` wraps each input in a
// visible <label> and uses the 44px height; the aria-label is the same in both.
function priceFields(row, model, card) {
  const { priceMode, editable, fmt } = model;
  // while save-all is in flight nothing may change: a value typed now would be sent stale or wiped
  const lock = model.savingAll;
  const id = row.item.id;
  const lbl = (text, node) => (card ? <label className="grid gap-1 text-sm font-bold">{text}{node}</label> : node);
  if (priceMode === 'NET') {
    const margin = row.margin.show
      ? (<div className="grid gap-1">{lbl('กำไร %', <Num row={row} card={card} field="marginPct" label="กำไร %" suffix="%" disabled={!editable || lock} value={row.margin.value} testid={`pcr-ceo-margin-input-${id}`} />)}
        {row.margin.invalid ? <span role="alert" className="text-2xs text-danger">{row.margin.error}</span> : null}
      </div>)
      : <span className="text-text-muted">–</span>;
    const list = (
      <div className="grid gap-1">
        {lbl('ราคาตั้ง/แผ่น', <Num row={row} card={card} field="listUnitPrice" label="ราคาตั้ง/แผ่น" disabled={!editable || lock || row.list.disabled} value={row.list.value} testid={`pcr-ceo-list-price-${id}`} max={row.list.max} />)}
        {row.list.invalid ? <span role="alert" className="text-2xs text-danger">ราคาตั้งต้องมากกว่า 0</span> : null}
        {row.formulaPerPiece != null ? (
          <span className="text-2xs text-text-muted" data-testid={`pcr-ceo-formula-list-${id}`}>
            สูตร: {fmt(row.formulaPerPiece)} / แผ่น{row.formulaPerSqm != null ? ` · ${fmt(row.formulaPerSqm)} / ตร.ม.` : ''}
          </span>
        ) : null}
        {row.hasPriceOverride && editable ? (
          <Button type="button" variant="secondary" size="sm" loading={row.clearingOverride} disabled={row.saving || lock} onClick={row.useFormula} data-testid={`pcr-ceo-use-formula-price-${id}`}>
            ใช้ราคาตามสูตร
          </Button>
        ) : null}
      </div>
    );
    const discount = (
      <div className="grid gap-1">
        {lbl('ส่วนลด %', <Num row={row} card={card} field="discountPct" label="ส่วนลด %" suffix="%" max="100" disabled={!editable || lock} value={row.discount} testid={`pcr-ceo-discount-${id}`} />)}
        {editable && row.hasDiscount ? (
          <Button type="button" variant="secondary" size="sm" loading={row.clearingDiscount} disabled={lock} onClick={row.clearDiscount} data-testid={`pcr-ceo-clear-discount-${id}`}>
            ล้างส่วนลด
          </Button>
        ) : null}
      </div>
    );
    return [margin, list, discount];
  }
  if (priceMode === 'SPECIAL_SQM') {
    const formula = !row.isStock && row.formulaPerPiece != null
      ? <span className="text-xs" data-testid={`pcr-ceo-formula-list-${id}`}>{fmt(row.formulaPerPiece)}{row.formulaPerSqm != null ? <small className="block text-text-muted">{fmt(row.formulaPerSqm)} / ตร.ม.</small> : null}</span>
      : <span className="text-text-muted">–</span>;
    const special = (
      <div className="grid gap-1">
        {lbl('ราคาพิเศษ/ตร.ม. (รวม VAT)', <Num row={row} card={card} field="specialPriceSqm" label="ราคาพิเศษ/ตร.ม. (รวม VAT)" suffix="/ตร.ม." disabled={!editable || lock || row.item.sqmPerPiece == null} value={row.special} max={row.specialMax} testid={`pcr-ceo-special-sqm-${id}`} />)}
        <span className="text-2xs text-text-muted">
          {row.item.sqmPerPiece != null ? `รวม VAT · ${row.piecesPerSqm} แผ่น/ตร.ม.` : 'รายการนี้ไม่มี ตร.ม./แผ่น จึงใช้โหมดนี้ไม่ได้'}
        </span>
      </div>
    );
    return [formula, special];
  }
  if (priceMode === 'DIRECT_NET') {
    return [lbl('ราคาสุทธิ/แผ่น', <Num row={row} card={card} field="directNetPrice" label="ราคาสุทธิ/แผ่น" suffix="/แผ่น" disabled={!editable || lock} value={row.direct} max={row.directMax} testid={`pcr-ceo-direct-net-${id}`} />)];
  }
  return [];
}

function modeHeaders(priceMode) {
  if (priceMode === 'NET') return ['กำไร %', 'ราคาตั้ง/แผ่น', 'ส่วนลด'];
  if (priceMode === 'SPECIAL_SQM') return ['ราคาตั้ง (สูตร)', 'ราคาพิเศษ'];
  if (priceMode === 'DIRECT_NET') return ['ราคาสุทธิ/แผ่น'];
  return [];
}

function CostCell({ row, model }) {
  const { fmt, editable } = model;
  const id = row.item.id;
  let body;
  if (row.isStock) body = <span className="text-xs text-text-muted">ไม่มีต้นทุน (สต็อก)</span>;
  else if (row.cost == null) {
    body = (
      <>
        <b className="text-danger">–</b>
        {editable ? (
          <Button type="button" variant="text" size="sm" onClick={row.openCostOverride} data-testid={`pcr-ceo-cost-override-quick-${id}`}>
            ปรับต้นทุนเอง
          </Button>
        ) : null}
      </>
    );
  } else {
    body = (
      <>
        <b>{fmt(row.cost, 'THB')}</b>
        {row.costPerSqm != null ? <small className="block text-text-muted">{`${fmt(row.costPerSqm, 'THB')}/ตร.ม.`}</small> : null}
      </>
    );
  }
  return <div className="text-right" data-testid={`pcr-ceo-cost-both-${id}`}>{body}</div>;
}

function NetCell({ row, model }) {
  const id = row.item.id;
  return (
    <div className="text-right" data-testid={`pcr-ceo-net-${id}`}>
      {row.netPending ? <i className="text-xs text-text-muted">คำนวณเมื่อบันทึก</i> : <b>{model.fmt(row.net)}</b>}
      {row.netPerSqm != null ? <small className="block text-text-muted">{`${model.fmt(row.netPerSqm)}/ตร.ม.`}</small> : null}
    </div>
  );
}

function ProfitCell({ row, model }) {
  const id = row.item.id;
  if (row.profitTotal == null) return <span className="text-text-muted" data-testid={`pcr-ceo-profit-${id}`}>–</span>;
  return (
    <div className="text-right" data-testid={`pcr-ceo-profit-${id}`}>
      <b className={row.profitTotal >= 0 ? 'text-success' : 'text-danger'}>{model.fmt(row.profitTotal, 'THB')}</b>
      {row.profitPct != null ? <small className="block text-text-muted">{`${row.profitPct.toFixed(1)}% ของต้นทุน`}</small> : null}
    </div>
  );
}

function Details({ row, model }) {
  const id = row.item.id;
  const { fmt, editable } = model;
  return (
    <div data-testid={`pcr-ceo-details-${id}`} className="grid gap-4 p-3 md:grid-cols-3">
      <section aria-label="สินค้า" className="grid content-start gap-1 text-sm">
        <h4 className="m-0 text-sm">สินค้า</h4>
        {row.productNode}
      </section>
      <section aria-label="ต้นทุน" className="grid content-start gap-1 text-sm">
        <h4 className="m-0 flex flex-wrap items-center gap-2 text-sm">
          ต้นทุน/แผ่น
          {editable && !row.isStock ? (
            <Button type="button" variant="secondary" size="sm" onClick={row.openCostOverride} data-testid={`pcr-ceo-cost-override-${id}`}>
              {row.hasCostOverride ? 'แก้ไขต้นทุนที่ปรับ' : 'ปรับต้นทุนเอง'}
            </Button>
          ) : null}
        </h4>
        {row.isStock ? <p className="m-0 text-text-muted">รายการจากสต็อก ไม่มีต้นทุนนำเข้า ใส่ราคาตั้งได้เลย</p> : null}
        {!row.isStock && row.cost != null ? <span>ต้นทุนรวมนำเข้า: <b>{fmt(row.cost, 'THB')}</b></span> : null}
        {row.costingItem && !row.isStock ? (
          <span className="text-text-muted">
            ต้นทุนคำนวณ/ชิ้น: <code>{fmt(row.costingItem.landedCostPerUnitThb, 'THB')}</code>
            {row.hasCostOverride ? <> · ต้นทุนที่ปรับ/ชิ้น: <code className="font-bold text-override">{fmt(row.costingItem.manualLandedCostPerUnitThb, 'THB')}</code>{row.costingItem.overrideReason ? ` (${row.costingItem.overrideReason})` : ''}</> : null}
          </span>
        ) : null}
        {row.costingItem?.uncostableReason ? (
          <div className="rounded-md border border-danger-border bg-danger-bg p-2 text-danger">
            <b className="block">คำนวณต้นทุนอัตโนมัติไม่ได้</b>
            <span>{row.costingItem.uncostableReason}</span>
          </div>
        ) : null}
        {row.stale ? (
          <p className="m-0 text-2xs text-warning-dark">
            อัตราแลกเปลี่ยนหรือค่าคำนวณเปลี่ยนไปหลังปรับต้นทุน — ต้องคำนวณต้นทุนใหม่หรือยืนยันค่าที่ปรับอีกครั้งก่อนอนุมัติ
          </p>
        ) : null}
      </section>
      <section aria-label="ราคาที่ได้" className="grid content-start gap-1 text-sm">
        <h4 className="m-0 text-sm">ราคาที่ได้</h4>
        <span>ราคาสุทธิ/แผ่น (ก่อน VAT): <b>{row.netPending ? 'คำนวณเมื่อบันทึก' : fmt(row.net)}</b></span>
        <span>ต่อ ตร.ม. (ก่อน VAT): {row.netPerSqm != null ? fmt(row.netPerSqm) : '–'}</span>
        <span>รวม {row.item.requestedQuantity} แผ่น: <b>{fmt(row.total)}</b></span>
        <span>กำไร: {row.profitTotal != null ? `${fmt(row.profitTotal, 'THB')}${row.profitPct != null ? ` (${row.profitPct.toFixed(1)}%)` : ''}` : '–'}</span>
      </section>
    </div>
  );
}

export function CeoPricingWorkspace({ model }) {
  const { rows, summary, blockers, priceMode, editable, fmt } = model;
  // Explicit toggles win; otherwise a row with a cost blocker starts open. State is keyed by item
  // id and read per render, so a costing query landing late still opens its blocked row.
  const [toggled, setToggled] = useState({});
  const [historyOpen, setHistoryOpen] = useState(false);
  const els = useRef({});
  const isOpen = (row) => toggled[row.item.id] ?? row.defaultOpen;
  const toggle = (row) => setToggled((prev) => ({ ...prev, [row.item.id]: !isOpen(row) }));
  const jump = (row) => {
    setToggled((prev) => ({ ...prev, [row.item.id]: true }));
    const target = els.current[row.item.id] ?? {};
    target.row?.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
    target.card?.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
  };
  const hc = modeHeaders(priceMode);
  const saveLabel = `บันทึกราคา (${model.dirtyCount} รายการ)`;
  const hint = MODES.find(([code]) => code === priceMode)?.[2] ?? 'เลือกวิธีกรอกราคาสำหรับมติราคานี้ — เปลี่ยนได้ ใช้กับทุกรายการในมติราคานี้';

  return (
    <>
      <section
        aria-label="สรุปราคาคำขอนี้"
        data-testid="pcr-ceo-summary"
        className="sticky top-2 z-10 grid gap-2 rounded-md border border-border bg-surface p-3 mobile:static"
      >
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
          <div data-testid="pcr-ceo-sum-priced"><span className="block text-sm text-text-muted">ตั้งราคาแล้ว</span><b className="text-xl font-extrabold tabular-nums">{summary.priced}/{rows.length} <small className="text-sm font-semibold text-text-muted">รายการ</small></b></div>
          <div data-testid="pcr-ceo-sum-total"><span className="block text-sm text-text-muted">ราคาขายรวม (ก่อน VAT)</span><b className="text-xl font-extrabold tabular-nums">{fmt(summary.sellTotal)}</b></div>
          <div data-testid="pcr-ceo-sum-cost"><span className="block text-sm text-text-muted">ต้นทุนรวม (รายการที่มีต้นทุน)</span><b className="text-xl font-extrabold tabular-nums">{fmt(summary.costTotal, 'THB')}</b></div>
          <div data-testid="pcr-ceo-sum-profit">
            <span className="block text-sm text-text-muted">กำไรรวม</span>
            <b className={cn('text-xl font-extrabold tabular-nums', summary.profitTotal >= 0 ? 'text-success' : 'text-danger')}>{fmt(summary.profitTotal, 'THB')}</b>
            {summary.profitPct != null ? <small className={cn('ml-1 text-sm font-semibold', summary.profitTotal >= 0 ? 'text-success' : 'text-danger')}>{`${summary.profitPct.toFixed(1)}%`}</small> : null}
          </div>
          {editable ? (
            <div className="ml-auto flex flex-wrap gap-2 mobile:hidden">
              {model.dirtyCount > 0 ? (
                <Button type="button" variant="secondary" disabled={model.saveDisabled} loading={model.savingAll} onClick={model.onSaveAll} data-testid="pcr-ceo-save-all">{saveLabel}</Button>
              ) : null}
              {model.canReturn ? (
                <Button type="button" variant="secondary" disabled={model.returnPending} onClick={model.onReturn} data-testid="pcr-ceo-return">ตีกลับฝ่ายนำเข้า</Button>
              ) : null}
              <Button type="button" variant="primary" disabled={model.approveDisabled} onClick={model.onApprove} data-testid="pcr-ceo-approve">อนุมัติราคาขาย</Button>
            </div>
          ) : null}
        </div>
        <div className="h-1 overflow-hidden rounded-full bg-surface-subtle" aria-hidden="true">
          <i className="block h-full bg-success" style={{ width: `${rows.length ? (summary.priced / rows.length) * 100 : 0}%` }} />
        </div>
        {blockers.length ? (
          <div role="status" data-testid="pcr-ceo-blockers" className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-danger">
            <b>ยังอนุมัติไม่ได้:</b>
            {blockers.map((row) => (
              <Button key={row.item.id} type="button" variant="text" size="sm" className="underline" onClick={() => jump(row)}>
                {`รายการที่ ${row.position} ${row.name} — ${row.blocker}`}
              </Button>
            ))}
          </div>
        ) : null}
        {!blockers.length && model.dirtyCount > 0 ? (
          <div data-testid="pcr-ceo-dirty-note" className="text-sm text-warning-dark">มีราคาที่แก้แล้วยังไม่บันทึก — กดบันทึกก่อนอนุมัติ</div>
        ) : null}
      </section>

      <Panel flush aria-label="ตั้งราคารายการ">
        <div className="flex items-start justify-between gap-3 border-b border-border-subtle px-5 py-4 mobile:px-4 mobile:py-3.5">
          <h2 className="m-0 min-w-0 text-lg break-words">
            ตั้งราคา{' '}
            <span className="text-sm font-semibold text-text-muted">
              <code>{model.decisionCode}</code>{' '}
              <StatusBadge tone={model.decisionStatus.tone}>{model.decisionStatus.label}</StatusBadge> · เวอร์ชัน {model.versionNo} · ราคาก่อน VAT (ใบเสนอราคาบวก VAT 7%)
            </span>
          </h2>
          {model.recalc}
        </div>
        {model.costingError}
        {model.factoryWarning}
        {model.startReview ? <div className="border-b border-border-subtle p-4">{model.startReview}</div> : null}
        <div className="flex flex-wrap items-center gap-3 border-b border-border-subtle p-4">
          <b className="text-sm">วิธีกรอกราคา</b>
          <div className="inline-flex flex-wrap gap-1" role="group" aria-label="วิธีกรอกราคา">
            {MODES.map(([code, label]) => (
              <Button
                key={code}
                type="button"
                size="sm"
                variant={priceMode === code ? 'primary' : 'secondary'}
                className="aria-pressed:disabled:opacity-100"
                disabled={!editable || model.modePending || model.savingAll || priceMode === code}
                aria-pressed={priceMode === code}
                onClick={() => model.onPickMode(code)}
                data-testid={`pcr-ceo-price-mode-${code}`}
              >
                {label}
              </Button>
            ))}
          </div>
          <span className="text-xs text-text-muted">{hint}</span>
        </div>

        <div data-testid="pcr-ceo-table-wrap" className="mobile:hidden">
          <table data-testid="pcr-ceo-table" className="w-full border-collapse text-sm">
            <thead>
              <tr className="border-b border-border-subtle bg-surface-subtle text-left text-xs text-text-muted">
                {['#', 'สินค้า', 'จำนวน', 'ต้นทุน/แผ่น', ...hc, 'ราคาสุทธิ/แผ่น', 'รวม (ก่อน VAT)', 'กำไร'].map((h, i) => (
                  <th key={`${h}-${i}`} scope="col" className="px-2 py-2 font-bold">{h}</th>
                ))}
                <th scope="col" className="px-2 py-2"><span className="sr-only">รายละเอียด</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const open = isOpen(row);
                const id = row.item.id;
                const edge = edgeClass(row);
                return [
                  <tr
                    key={`r${id}`}
                    ref={(el) => { els.current[id] = { ...els.current[id], row: el }; }}
                    data-testid={`pcr-ceo-row-${id}`}
                    data-state={row.blocker ? 'blocked' : row.dirty ? 'dirty' : 'ok'}
                    className={cn('border-b border-border-subtle align-top', edge)}
                  >
                    <td className="px-2 py-2 text-text-muted">{row.position}</td>
                    <td className="px-2 py-2">
                      <b className="block">{row.name}</b>
                      <span className="text-xs text-text-muted">{[row.factoryLabel, row.size].filter(Boolean).join(' · ')}</span>
                      <Tags row={row} />
                    </td>
                    <td className="px-2 py-2 text-right"><b className="block">{row.qtyText}</b><small className="text-text-muted">{row.sqmText}</small></td>
                    <td className="px-2 py-2"><CostCell row={row} model={model} /></td>
                    {priceFields(row, model, false).map((cell, i) => <td key={i} className="min-w-[110px] px-2 py-2">{cell}</td>)}
                    <td className="px-2 py-2"><NetCell row={row} model={model} /></td>
                    <td className="px-2 py-2 text-right" data-testid={`pcr-ceo-total-${id}`}><b>{row.netPending ? '–' : model.fmt(row.total)}</b></td>
                    <td className="px-2 py-2"><ProfitCell row={row} model={model} /></td>
                    <td className="px-2 py-2">
                      <Button type="button" variant="icon" size="sm" aria-label={`รายละเอียด ${row.name}`} aria-expanded={open} onClick={() => toggle(row)}>
                        <Icon name={open ? 'chevronUp' : 'chevronDown'} size={14} />
                      </Button>
                    </td>
                  </tr>,
                  open ? (
                    <tr key={`d${id}`} className="border-b border-border-subtle bg-surface-subtle">
                      <td colSpan={8 + hc.length}><Details row={row} model={model} /></td>
                    </tr>
                  ) : null,
                ];
              })}
              {model.missingRows.map((m) => (
                <tr key={`m${m.requestItem.id}`} data-testid={`pcr-ceo-row-req-${m.requestItem.id}`} className="border-b border-border-subtle text-text-muted">
                  <td className="px-2 py-2">{m.position}</td>
                  <td className="px-2 py-2"><b className="block text-text">{m.name}</b></td>
                  <td className="px-2 py-2 text-right">{m.qtyText}</td>
                  <td className="px-2 py-2" colSpan={5 + hc.length}>ไม่อยู่ในมติราคานี้</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div data-testid="pcr-ceo-cards" className="hidden gap-3 p-3 mobile:grid">
          {rows.map((row) => {
            const id = row.item.id;
            const open = isOpen(row);
            const edge = edgeClass(row);
            return (
              <div
                key={id}
                ref={(el) => { els.current[id] = { ...els.current[id], card: el }; }}
                data-testid={`pcr-ceo-card-${id}`}
                className={cn('grid gap-2 rounded-md border border-border bg-surface p-3', edge)}
              >
                <div>
                  <b className="block">{`${row.position}. ${row.name}`}</b>
                  <span className="text-xs text-text-muted">{`${row.qtyText} · ${row.sqmText} · ต้นทุน ${row.isStock ? 'ไม่มี (สต็อก)' : row.cost == null ? 'ต้องระบุเอง' : `${model.fmt(row.cost, 'THB')}/แผ่น`}`}</span>
                  <Tags row={row} />
                </div>
                <div className="grid grid-cols-2 gap-2">
                  {priceFields(row, model, true).map((cell, i) => <div key={i}>{cell}</div>)}
                </div>
                <dl className="m-0 grid grid-cols-3 gap-2 text-center">
                  <div><dt className="text-xs text-text-muted">สุทธิ/แผ่น</dt><dd className="m-0"><NetCell row={row} model={model} /></dd></div>
                  <div><dt className="text-xs text-text-muted">รวม</dt><dd className="m-0 font-bold" data-testid={`pcr-ceo-total-${id}`}>{row.netPending ? '–' : model.fmt(row.total)}</dd></div>
                  <div><dt className="text-xs text-text-muted">กำไร</dt><dd className="m-0 font-bold" data-testid={`pcr-ceo-profit-${id}`}>{row.profitPct != null ? `${row.profitPct.toFixed(0)}%` : '–'}</dd></div>
                </dl>
                <Button type="button" variant="secondary" size="sm" className="justify-self-start" aria-expanded={open} onClick={() => toggle(row)}>
                  <Icon name={open ? 'chevronUp' : 'chevronDown'} size={14} /> รายละเอียด
                </Button>
                {open ? <Details row={row} model={model} /> : null}
              </div>
            );
          })}
        </div>

        {model.missingRows.length ? (
          <div className="hidden gap-2 p-3 mobile:grid">
            {model.missingRows.map((m) => (
              <div key={m.requestItem.id} data-testid={`pcr-ceo-card-req-${m.requestItem.id}`} className="rounded-md border border-border p-3 text-text-muted">
                <b className="block text-text">{`${m.position}. ${m.name}`}</b>
                <span className="text-xs">{`${m.qtyText} · ไม่อยู่ในมติราคานี้`}</span>
              </div>
            ))}
          </div>
        ) : null}

        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border-subtle p-3 text-xs text-text-muted">
          <span>{model.fxText}</span>
          {model.history.length > 1 ? (
            <span>
              <button type="button" className="inline-flex items-center gap-1 border-0 bg-transparent p-0 text-xs text-text-muted" aria-expanded={historyOpen} onClick={() => setHistoryOpen((v) => !v)}>
                {`ประวัติการพิจารณา (${model.history.length})`}
                <Icon name={historyOpen ? 'chevronUp' : 'chevronDown'} size={12} />
              </button>
              {historyOpen ? <span className="ml-2">{model.history.join(' · ')}</span> : null}
            </span>
          ) : null}
        </div>
      </Panel>

      {editable ? (
        <div data-testid="pcr-ceo-mobile-bar" className="fixed inset-x-0 bottom-0 z-20 hidden gap-2 border-t border-border bg-surface p-3 mobile:flex">
          {model.dirtyCount > 0 ? (
            <Button type="button" variant="secondary" className="flex-1" disabled={model.saveDisabled} loading={model.savingAll} onClick={model.onSaveAll} data-testid="pcr-ceo-mobile-save">{`บันทึก (${model.dirtyCount})`}</Button>
          ) : model.canReturn ? (
            <Button type="button" variant="secondary" className="flex-1" disabled={model.returnPending} onClick={model.onReturn} data-testid="pcr-ceo-mobile-return">ตีกลับ</Button>
          ) : null}
          <Button type="button" variant="primary" className="flex-1" disabled={model.approveDisabled} onClick={model.onApprove} data-testid="pcr-ceo-mobile-approve">อนุมัติราคาขาย</Button>
        </div>
      ) : null}
    </>
  );
}
