import { Fragment, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate } from '../../utils/format.js';
import { pricingRequestRecipientLabel } from './pricingRequestMeta.js';

// Import pricing workspace for /pricing-requests/:id (redesign, 2026-11): header, progress strip,
// unassigned-factory callout, ONE factory section (a card per factory: header / terms row / table /
// footer) and the small stock card. Presentational only: the page builds the view-model (`model`)
// from its drafts, gates and mutations, so no pricing or gate rule lives here.

const STEP_LABELS = ['ติดต่อโรงงาน', 'กรอกราคา', 'ยืนยันราคาเสนอ'];
const STEP_TONE = { done: 'bg-success text-white', now: 'bg-primary text-white', todo: 'bg-surface-subtle text-text-muted', declined: 'bg-danger text-white' };
const SEG_TONE = { confirmed: 'bg-success', contacted: 'bg-info', none: 'bg-border' };
const TD = 'px-3 py-3 align-top mobile:flex mobile:flex-col mobile:gap-1 mobile:px-4 mobile:py-2 mobile:before:content-[attr(data-label)] mobile:before:text-2xs mobile:before:font-bold mobile:before:text-text-muted';

export function ImportPricingHeader({ summary, status, actions }) {
  return (
    <header data-testid="pcr-import-header" className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2.5">
          <h1 className="m-0 text-2xl font-extrabold">{summary.requestCode}</h1>
          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
        </div>
        <p className="m-0 mt-0.5 text-text-muted">{summary.customerName ?? '-'}</p>
        <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-sm text-text-muted">
          <span>ดีล <Link to={`/import/deals/${summary.ticketId}`} className="text-info underline">{summary.ticketCode}</Link></span>
          <span>ผู้รับ: {pricingRequestRecipientLabel(summary.recipientType)}{summary.recipientLabel ? ` · ${summary.recipientLabel}` : ''}</span>
          <span>ต้องการภายใน {formatThaiDate(summary.requiredDate)}</span>
          {summary.requestedByName ? <span>ฝ่ายขาย: {summary.requestedByName}</span> : null}
          {summary.assignedImportName ? <span>ผู้รับเรื่อง: {summary.assignedImportName}</span> : null}
          {summary.projectName ? <span>โครงการ {summary.projectName}</span> : null}
        </div>
        {/* GLA-125 header terms, read-only (what the old ภาพรวม panel showed) */}
        {(summary.paymentTermMode || summary.validityDays != null || summary.unitCode || summary.omitContactHonorific) ? (
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

function Steps({ states, id }) {
  return (
    <span role="group" data-testid={id != null ? `pcr-factory-steps-${id}` : undefined} aria-label="ขั้นตอนโรงงานนี้" className="inline-flex flex-wrap items-center gap-1.5 text-xs">
      {STEP_LABELS.map((label, i) => (
        <Fragment key={label}>
          {i > 0 ? <span aria-hidden="true" className="h-px w-3 bg-border-strong" /> : null}
          <span data-testid="pcr-factory-step" data-state={states[i]} className={cn('inline-flex items-center gap-1 font-bold', states[i] === 'todo' ? 'text-text-muted' : states[i] === 'declined' ? 'text-danger-dark' : 'text-text')}>
            <i className={cn('inline-grid h-5 w-5 place-items-center rounded-full text-2xs not-italic', STEP_TONE[states[i]])}>{states[i] === 'done' ? '✓' : states[i] === 'declined' ? '✕' : i + 1}</i>
            {states[i] === 'declined' ? 'แจ้งไม่พร้อมเสนอราคา' : label}
          </span>
        </Fragment>
      ))}
    </span>
  );
}

function ProgressStrip({ progress }) {
  const figs = [['factories', 'โรงงาน'], ['contacted', 'ติดต่อแล้ว'], ['confirmed', 'ยืนยันราคาแล้ว'], ['items', 'สินค้า']];
  return (
    <section aria-label="ความคืบหน้าการขอราคา" className="grid gap-3 rounded-md border border-border bg-surface p-4">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <dl className="m-0 flex flex-wrap gap-x-6 gap-y-2">
          {figs.map(([key, label]) => (
            <div key={key}>
              <dt className="text-xs text-text-muted">{label}</dt>
              <dd data-testid={`pcr-import-fig-${key}`} className="m-0 text-lg font-extrabold tabular-nums">{progress.figs[key]}</dd>
            </div>
          ))}
        </dl>
        <div role="status" className="min-w-0 max-w-[60ch]">
          <span className="text-xs font-bold text-text-muted">ขั้นต่อไป</span>
          <div data-testid="pcr-import-next-step" className="font-bold">{progress.next}</div>
          <small data-testid="pcr-import-next-sub" className="block text-xs text-text-muted">{progress.sub}</small>
        </div>
      </div>
      <div aria-hidden="true" className="flex gap-1">
        {progress.segs.map((state, i) => (
          <i key={i} data-testid="pcr-import-progress-seg" data-state={state} className={cn('h-2 flex-1 rounded-pill', SEG_TONE[state])} />
        ))}
      </div>
    </section>
  );
}

// `display:block` on mobile strips native table semantics, so the cell roles are explicit.
/* eslint-disable jsx-a11y/no-interactive-element-to-noninteractive-role */
function SpecRow({ id, spec }) {
  return (
    <tr role="row" data-testid={`pcr-spec-${id}`} className="bg-surface-muted">
      <td role="cell" colSpan={5} className="px-4 py-3">
        <dl className="m-0 grid grid-cols-2 gap-x-4 gap-y-1 text-xs sm:grid-cols-4">
          {spec.map(([label, value]) => (
            <div key={label}>
              <dt className="text-text-muted">{label}</dt>
              <dd className="m-0 font-bold text-text">{value}</dd>
            </div>
          ))}
        </dl>
      </td>
    </tr>
  );
}

function PriceCell({ row, quoteId }) {
  const p = row.price;
  return (
    <div data-testid={quoteId != null ? `pcr-quote-price-cell-${quoteId}-${row.itemId}` : undefined}>
      {p.mode === 'input' ? (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="grid w-full gap-1.5">
            <label className="flex w-full items-stretch overflow-hidden rounded-md border border-border-input bg-surface md:w-44">
              <span data-testid={`pcr-quote-symbol-${quoteId}-${row.itemId}`} aria-hidden="true" className="grid min-w-10 place-items-center border-r border-border-input bg-surface-subtle px-2 font-extrabold text-text-secondary">{p.symbol}</span>
              <input
                id={p.id}
                className="w-full min-w-0 border-0 bg-transparent px-2.5 py-1.5 text-right tabular-nums outline-none mobile:min-h-11 mobile:text-base"
                type="text"
                inputMode="decimal"
                placeholder={p.placeholder}
                aria-label={p.ariaLabel}
                value={p.value}
                onChange={(e) => p.onChange(e.target.value)}
              />
            </label>
            {row.currencyNote ? <small data-testid={`pcr-quote-currency-${quoteId}-${row.itemId}`} className="text-xs text-text-muted">{row.currencyNote}</small> : null}
            {p.fixedUnit ? (
              <span data-testid={`pcr-quote-unit-${quoteId}-${row.itemId}`} className="text-sm font-bold text-text-secondary">{p.fixedUnit}</span>
            ) : (
            <span role="group" aria-label={p.unitSwitch.ariaLabel} className="inline-flex w-full overflow-hidden rounded-md border border-border-muted md:w-auto">
              {p.unitSwitch.options.map((o) => (
                <button
                  key={o.code}
                  type="button"
                  aria-pressed={p.unitSwitch.value === o.code}
                  onClick={() => p.unitSwitch.onChange(o.code)}
                  className={cn('min-h-8 flex-1 border-0 border-l border-border px-3 text-sm font-bold first:border-l-0 mobile:min-h-11', p.unitSwitch.value === o.code ? 'bg-primary-bg text-primary' : 'bg-surface text-text-secondary')}
                >
                  {o.label}
                </button>
              ))}
            </span>
            )}
          </span>
          {p.sqm ? (
            <input
              id={p.sqm.id}
              className="w-full md:w-24 mobile:min-h-11"
              type="text"
              inputMode="decimal"
              placeholder="ตร.ม./หน่วย"
              aria-label={p.sqm.ariaLabel}
              value={p.sqm.value}
              onChange={(e) => p.sqm.onChange(e.target.value)}
            />
          ) : null}
          {p.equivalent ? (
            <span className="w-full text-xs text-text-muted" data-testid={p.equivalent.testId}>{p.equivalent.text}</span>
          ) : null}
        </div>
      ) : p.mode === 'locked' ? (
        <input
          className="w-full opacity-50 md:w-32 mobile:min-h-11"
          type="text"
          disabled
          value=""
          placeholder="ติดต่อโรงงานก่อน"
          aria-label={p.ariaLabel}
          data-testid={p.testId}
        />
      ) : p.mode === 'text' ? (
        <span className="text-sm text-text-secondary">{p.text}</span>
      ) : <span className="text-text-muted">–</span>}
      {row.thb ? (
        <div className="mt-1 text-xs text-text-muted" data-testid={`pcr-quote-thb-${quoteId}-${row.itemId}`}>{row.thb}</div>
      ) : row.priceEmpty ? <div className="mt-1 text-xs text-text-muted">ยังไม่กรอก</div> : null}
    </div>
  );
}

function FactoryRow({ row, quoteId }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <tr role="row" data-testid={`pcr-factory-row-${row.itemId}`} className="border-t border-border-subtle mobile:block mobile:border-t-4">
        <td role="cell" className="px-3 py-3 align-top mobile:block mobile:px-4">
          <div className="break-words text-sm font-bold text-text">{row.name}</div>
          <div className="break-words text-xs text-text-secondary"><span>{row.variant || '-'}</span> · <span>{row.code}</span></div>
          <button type="button" aria-expanded={open} onClick={() => setOpen(!open)} className="mt-1 inline-flex min-h-6 items-center gap-1 border-0 bg-transparent p-0 text-xs font-bold text-primary">
            <Icon name={open ? 'chevronUp' : 'chevronDown'} size={13} />
            สเปกจากฝ่ายขาย
          </button>
        </td>
        <td role="cell" data-label="จำนวน" className={cn(TD, 'text-right mobile:text-left')}>
          <b className="text-sm">{row.qty}</b>
          {row.qtyDetail ? <small className="block text-xs text-text-muted">{row.qtyDetail}</small> : null}
        </td>
        <td role="cell" data-label="ราคาที่โรงงานเสนอ" className={TD}><PriceCell row={row} quoteId={quoteId} /></td>
        <td role="cell" data-label="ระยะเวลานำเข้า" className={cn(TD, 'text-sm text-text-secondary')}>
          <div data-testid={quoteId != null ? `pcr-lt-value-${quoteId}-${row.itemId}` : undefined}>
            {row.leadPending ? (
              <>
                <span className="tabular-nums">{row.leadPending}</span>
                <span className="block text-2xs font-bold text-warning-dark">รอฝ่ายขายอนุมัติ</span>
              </>
            ) : <span className="tabular-nums">{row.lead}</span>}
          </div>
        </td>
        <td role="cell" data-label="ราคาที่ยืนยันแล้ว" className={cn(TD, 'text-right mobile:text-left')}>
          <span className="text-sm font-bold text-text">{row.approved ?? '–'}</span>
        </td>
      </tr>
      {open ? <SpecRow id={row.itemId} spec={row.spec} /> : null}
    </>
  );
}

/* eslint-enable jsx-a11y/no-interactive-element-to-noninteractive-role */

function CardTerms({ card }) {
  const t = card.terms;
  return (
    <div data-testid={card.quoteId != null ? `pcr-factory-terms-row-${card.quoteId}` : undefined} className="flex flex-wrap items-center gap-x-4 gap-y-1.5 border-b border-border-subtle px-5 py-2.5 text-xs text-text-secondary mobile:px-4">
      <span className="inline-flex flex-wrap items-center gap-x-2" data-testid={`pcr-factory-currency-${card.quoteId}`}>
        <b className="text-text">สกุลเงิน</b>
        <span className="inline-flex items-center gap-1 rounded-pill bg-surface-subtle px-2 py-0.5 font-bold text-text" title="ใช้สกุลเงินตามคำขอราคา เปลี่ยนไม่ได้">
          <Icon name="lock" size={12} className="shrink-0 text-text-muted" />
          {t.currency ? `${t.currency} ${t.symbol}` : 'หลายสกุลเงิน — ดูในแต่ละรายการ'}
        </span>
        <span className="text-text-muted">ตามคำขอราคา</span>
        {t.fxNote ? <span className="text-text-muted" data-testid={`pcr-factory-fx-${card.quoteId}`}>{`· ${t.fxNote}`}</span> : null}
      </span>
      {t.lockReason ? (
        <span className="inline-flex items-center gap-1 font-bold text-warning-dark" data-testid={`pcr-await-contact-${card.quoteId}`}>
          <Icon name="lock" size={13} />
          ช่องราคาจะเปิดหลังกด &quot;ติดต่อโรงงานแล้ว&quot;
        </span>
      ) : null}
    </div>
  );
}

function CardFooter({ card }) {
  const f = card.footer;
  const id = card.quoteId;
  return (
    <div data-testid={`pcr-factory-footer-${id}`} className="grid gap-3 px-5 py-3 mobile:px-4 md:grid-cols-[1fr_auto]">
      <div className="grid gap-2">
        {f.attachments ? (
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="font-bold text-text-muted">ไฟล์แนบ</span>
            {f.attachments.canUpload ? (
              <label className={cn(buttonVariants({ variant: 'secondary', size: 'sm' }), 'cursor-pointer')}>
                <input type="file" className="hidden" onChange={(event) => {
                  const file = event.target.files?.[0];
                  if (file) f.attachments.onUpload(file);
                  event.target.value = '';
                }} />
                <Icon name="upload" size={13} />
                แนบไฟล์
              </label>
            ) : null}
            {f.attachments.list.map((a) => (
              <a key={a.id} className="text-info underline" href={a.href} target="_blank" rel="noreferrer">{a.fileName}</a>
            ))}
            {f.attachments.list.length === 0 ? <span className="text-text-muted">-</span> : null}
          </div>
        ) : null}
        {f.editable ? (
          <FormField label="หมายเหตุราคา" htmlFor={`pcr-note-${id}`}>
            <textarea id={`pcr-note-${id}`} className="min-h-20" placeholder="ข้อมูลเพิ่มเติมเกี่ยวกับราคา (ถ้ามี)" value={f.note} onChange={(e) => f.onNote(e.target.value)} />
          </FormField>
        ) : f.note ? <p className="m-0 text-xs text-text-muted"><strong>หมายเหตุราคา:</strong> {f.note}</p> : null}
        {f.history ? <p className="m-0 text-xs text-text-muted">ประวัติ: {f.history}</p> : null}
      </div>
      <div className="grid content-start justify-items-end gap-2">
        <div className="flex flex-wrap justify-end gap-2">
          {f.canNegotiate ? (
            <Button type="button" variant="secondary" className="mobile:min-h-11" disabled={f.negotiating} onClick={f.onNegotiate}>บันทึกว่ากำลังเจรจา</Button>
          ) : null}
          {f.showCancel ? <Button type="button" variant="secondary" className="mobile:min-h-11" onClick={f.onCancel}>ยกเลิก</Button> : null}
          {f.canConfirm ? (
            <Button type="button" variant="primary" className="mobile:min-h-11" disabled={f.confirming} onClick={f.onConfirm} data-testid="pcr-submit-to-ceo">
              {f.confirming ? 'กำลังยืนยัน…' : 'ยืนยันราคาเสนอ'}
            </Button>
          ) : null}
        </div>
        {f.confirmedLine ? (
          <div data-testid={`pcr-factory-confirmed-${id}`} className="inline-flex items-center gap-1.5 rounded-md border border-success-border bg-success-bg px-3 py-1.5 text-sm font-bold text-success-dark">
            <Icon name="check" size={14} />
            {f.confirmedLine}
          </div>
        ) : null}
        {f.helper ? <small data-testid={`pcr-factory-helper-${id}`} className="max-w-[44ch] text-right text-xs text-text-muted mobile:text-left">{f.helper}</small> : null}
      </div>
    </div>
  );
}

function FactoryCard({ card }) {
  const id = card.quoteId;
  return (
    <article
      className="mb-3 overflow-hidden rounded-md border border-border bg-surface"
      data-testid={id != null ? `pcr-factory-card-${id}` : 'pcr-price-preview-group'}
    >
      <div data-testid={id != null ? `pcr-factory-head-${id}` : undefined} className="flex flex-wrap items-center justify-between gap-3 bg-surface-subtle px-5 py-3 mobile:px-4">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <b className="text-text">{card.name}</b>
            {(card.badges ?? []).map((b) => <StatusBadge key={b.label} tone={b.tone}>{b.label}</StatusBadge>)}
          </div>
          <span className="text-xs text-text-muted">{card.meta}</span>
          {card.contactedLine ? <span className="text-xs text-text-muted" data-testid={`pcr-factory-status-${id}`}> · {card.contactedLine}</span> : null}
        </div>
        <Steps states={card.steps} id={id} />
        <div className="flex flex-wrap items-center gap-2">
          {card.mail ? (
            <Button type="button" size="sm" variant="secondary" disabled={card.mail.busy} onClick={card.mail.onClick} data-testid={card.mail.testId}>
              <Icon name="mail" size={14} />
              {card.mail.label}
            </Button>
          ) : null}
          {card.contact ? (
            <Button type="button" size="sm" variant="primary" disabled={card.contact.busy} onClick={card.contact.onClick} data-testid={card.contact.testId}>
              <Icon name="check" size={14} />
              ติดต่อโรงงานแล้ว
            </Button>
          ) : null}
        </div>
      </div>
      {card.contactNote ? (
        <p className="m-0 border-b border-border-subtle px-5 py-2.5 text-xs text-text-secondary mobile:px-4" data-testid={`pcr-contact-note-${id}`}>
          <span className="font-bold text-text-muted">หมายเหตุการติดต่อ: </span>{card.contactNote}
        </p>
      ) : null}
      {card.terms ? <CardTerms card={card} /> : null}
      <table role="table" className="w-full border-collapse text-left mobile:block">
        {/* eslint-disable-next-line jsx-a11y/no-redundant-roles -- kept for mobile display:block */}
        <thead role="rowgroup" className="mobile:hidden">
          <tr role="row" className="bg-surface-subtle text-xs font-bold text-text-muted">
            <th role="columnheader" className="px-3 py-2">สินค้า</th>
            <th role="columnheader" className="px-3 py-2 text-right">จำนวน</th>
            <th role="columnheader" className="px-3 py-2">ราคาที่โรงงานเสนอ</th>
            <th role="columnheader" className="px-3 py-2">ระยะเวลานำเข้า</th>
            <th role="columnheader" className="px-3 py-2 text-right">ราคาที่ยืนยันแล้ว</th>
          </tr>
        </thead>
        {/* eslint-disable-next-line jsx-a11y/no-redundant-roles -- kept for mobile display:block */}
        <tbody role="rowgroup" className="mobile:block">
          {card.rows.map((row) => <FactoryRow key={row.itemId} row={row} quoteId={id} />)}
        </tbody>
      </table>
      {card.leadBlock}
      {card.footer ? <CardFooter card={card} /> : (
        <p data-testid={card.helperTestId} className="m-0 px-5 py-3 text-right text-xs text-text-muted mobile:px-4 mobile:text-left">{card.helper}</p>
      )}
    </article>
  );
}

export function ImportPricingWorkspace({ model }) {
  return (
    <>
      <ProgressStrip progress={model.progress} />
      {model.missingBanner}
      {model.callout ? (
        <div role="status" data-testid="pcr-import-unassigned" className="flex gap-2 rounded-md border border-warning-border bg-warning-bg px-4 py-3 text-warning-dark">
          <Icon name="triangleAlert" size={15} className="mt-0.5 shrink-0" />
          <div className="grid flex-1 gap-3">
            <b>{`ยังไม่ได้ระบุโรงงาน ${model.callout.entries.length} รายการ`}</b>
            {model.callout.entries.map((entry) => (
              <div key={entry.itemId} className="grid gap-1.5">
                <span>{`รายการที่ ${entry.position} (${entry.name})`}{entry.detail ? ` · ${entry.detail}` : ''}</span>
                {entry.picker}
              </div>
            ))}
          </div>
        </div>
      ) : null}
      {model.gateNotice ? (
        <div role="status" data-testid="pcr-ceo-gate-notice" className="rounded-md border border-warning-border bg-warning-bg-soft p-3 text-xs text-warning-dark">
          <strong className="block">ยืนยันราคาเสนอแล้ว แต่ยังไม่ส่งถึง CEO</strong>
          <span>{model.gateNotice}</span>
        </div>
      ) : null}
      <section aria-label="ขอราคาจากโรงงาน" className="grid">
        <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="m-0 text-lg font-extrabold">ขอราคาจากโรงงาน</h2>
          {model.showFactoryChangeHint ? <span className="text-xs text-text-muted">เปลี่ยนโรงงานของรายการที่ระบุแล้วต้องสร้างคำขอราคารอบใหม่</span> : null}
          {model.generate ? (
            <Button type="button" variant="secondary" disabled={model.generate.pending} onClick={model.generate.onClick} data-testid="pcr-generate-drafts">
              สร้างเมลให้ทุกโรงงาน
            </Button>
          ) : null}
        </div>
        {model.previewCards.length > 0 ? (
          <div data-testid="pcr-price-preview">
            {model.previewCards.map((card) => <FactoryCard key={card.key} card={card} />)}
          </div>
        ) : null}
        {model.quoteCards.map((card) => <FactoryCard key={card.key} card={card} />)}
        {model.stock.length > 0 ? (
          <article data-testid="pcr-stock-card" className="mb-3 overflow-hidden rounded-md border border-border bg-surface">
            <div className="bg-surface-subtle px-5 py-3 font-bold mobile:px-4">{`จากสต็อก · ไม่ต้องขอราคา (${model.stock.length} รายการ)`}</div>
            <ul className="m-0 grid list-none gap-2 p-0 px-5 py-3 mobile:px-4">
              {model.stock.map((s) => (
                <li key={s.id} className="flex flex-wrap items-baseline gap-x-2 text-sm">
                  <strong>{s.name}</strong>
                  <StatusBadge tone="warning">จากสต็อก</StatusBadge>
                  {s.variant ? <span className="text-xs text-text-secondary">{s.variant}</span> : null}
                  <span className="text-xs text-text-muted">· {s.qty}</span>
                </li>
              ))}
            </ul>
          </article>
        ) : null}
      </section>
    </>
  );
}
