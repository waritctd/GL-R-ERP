import { Link } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate, formatThaiMonthYearFromMonthInputValue } from '../../utils/format.js';
import { isManualKind } from './commissionRecord.js';
import { ReceiptChain } from './ReceiptChain.jsx';
import { WeightLines } from './WeightLines.jsx';

function Step({ state, title, sub }) {
  return (
    <li
      className={cn(
        'relative grid grid-cols-[18px_minmax(0,1fr)] gap-2 pb-2.5 last:pb-0',
        'before:absolute before:bottom-0 before:left-[8px] before:top-[18px] before:w-0.5 before:bg-border-strong last:before:hidden',
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          'grid h-[18px] w-[18px] place-items-center rounded-pill border-2 bg-surface text-surface',
          state === 'done' && 'border-success bg-success',
          state === 'now' && 'border-warning bg-warning-bg',
          !state && 'border-border-muted',
        )}
      >
        {state === 'done' ? <Icon name="check" size={10} /> : null}
      </span>
      <span className="min-w-0">
        <b className="block text-sm">{title}</b>
        <small className="text-sm text-text-muted">{sub}</small>
      </span>
    </li>
  );
}

const who = (name, at) => `${name || '-'} · ${formatThaiDate(at)}`;

/**
 * The ONE panel an expanded invoice opens (queue row or all-records row): (a) how the money became
 * the base, with the inline deduction editor; (b) the deal's products and weights when the pending
 * entry is known; (c) where the record is in approval, plus deal / invoice / file references.
 */
export function InvoiceDetailPanel({
  record, entry, canEdit, canAdjust, editor, onEdit, onAdjust, onEntryUpdate, onSaved,
}) {
  const manual = isManualKind(record.kind);
  const mgrDone = record.status === 'MANAGER_APPROVED' || record.status === 'APPROVED';
  const ceoDone = record.status === 'APPROVED';
  const invoice = record.invoiceDetails;
  const hasWeights = entry && (entry.items ?? []).length > 0;
  const columns = hasWeights
    ? 'grid-cols-[minmax(0,1.15fr)_minmax(0,1.25fr)_minmax(0,.9fr)] nav-drawer:grid-cols-[minmax(0,1fr)]'
    : 'grid-cols-[minmax(0,1.15fr)_minmax(0,.9fr)] nav-drawer:grid-cols-[minmax(0,1fr)]';
  const divider = 'border-l border-border nav-drawer:border-l-0 nav-drawer:border-t';
  return (
    <div data-testid={`detail-panel-${record.id}`} className="border-t border-border bg-surface-muted">
      <div className={cn('grid', columns)}>
        <section aria-label="การคำนวณ" className="grid min-w-0 content-start gap-2.5 p-4 mobile:p-3.5">
          <h4 className="m-0 flex items-center justify-between gap-2 text-sm font-extrabold text-text">
            ยอดเงิน → ฐานค่าคอม
            {canEdit && !manual ? (
              <Button type="button" variant="secondary" size="sm" className="whitespace-nowrap pointer-coarse:min-h-11" onClick={onEdit}>
                <Icon name="pencil" size={14} />
                แก้ไขค่าหัก
              </Button>
            ) : null}
          </h4>
          <ReceiptChain record={record} compact bare />
        </section>

        {hasWeights ? (
          <div className={cn('min-w-0', divider)}>
            <WeightLines entry={entry} canAdjust={canAdjust} onAdjust={onAdjust} onEntryUpdate={onEntryUpdate} onSaved={onSaved} />
          </div>
        ) : null}

        <section aria-label="สถานะและเอกสาร" className={cn('grid min-w-0 content-start gap-2.5 p-4 mobile:p-3.5', divider)}>
          <h4 className="m-0 text-sm font-extrabold text-text">สถานะการอนุมัติ</h4>
          <ol className="m-0 grid list-none p-0">
            <Step state="done" title="บัญชีบันทึกใบกำกับ" sub={invoice?.invoiceDate ? formatThaiDate(invoice.invoiceDate) : formatThaiDate(record.createdAt)} />
            <Step
              state={mgrDone ? 'done' : 'now'}
              title="ผู้จัดการฝ่ายขาย"
              sub={mgrDone ? who(record.managerApprovedByName, record.managerApprovedAt) : record.status === 'REJECTED' ? 'ไม่อนุมัติ' : 'รออนุมัติ'}
            />
            <Step
              state={ceoDone ? 'done' : mgrDone ? 'now' : undefined}
              title="CEO"
              sub={ceoDone ? who(record.ceoApprovedByName, record.ceoApprovedAt) : mgrDone ? 'รออนุมัติ' : 'ยังไม่ถึงขั้นนี้'}
            />
            <Step title="จ่ายกับเงินเดือน" sub={formatThaiMonthYearFromMonthInputValue((record.payrollMonth || '').slice(0, 7))} />
          </ol>
          <div className="grid gap-1 text-sm text-text-muted">
            {entry?.ticketCode ? (
              <span>
                ดีล{' '}
                {record.sourceTicketId
                  ? <Link to={`/tickets/${record.sourceTicketId}`} className="font-bold text-link">{entry.ticketCode}</Link>
                  : entry.ticketCode}
                {entry.customerName ? ` · ${entry.customerName}` : ''}
              </span>
            ) : null}
            {invoice ? <span>{`ใบกำกับ ${invoice.invoiceNumber} · ${formatThaiDate(invoice.invoiceDate)}`}</span> : null}
            {invoice?.invoiceAttachmentFileName ? <span>{`ไฟล์ ${invoice.invoiceAttachmentFileName}`}</span> : null}
          </div>
        </section>
      </div>
      {editor ? <div className="border-t border-border p-4 mobile:p-3.5">{editor}</div> : null}
    </div>
  );
}
