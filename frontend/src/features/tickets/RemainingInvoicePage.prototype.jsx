// PROTOTYPE for the before/after artifact only: the remaining invoice on the quotation generator's page layout.
// Reads the same data the dialog reads; nothing is saved from here.
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';

const ITEM_GRID = 'grid-cols-[minmax(0,3fr)_minmax(5.75rem,0.8fr)_minmax(5.75rem,0.9fr)_minmax(3.5rem,0.6fr)_minmax(5.75rem,0.9fr)_minmax(8.25rem,1fr)] tablet:min-w-0 reflow-cards';
const money = (v) => (v == null ? '—' : `฿${Number(v).toLocaleString('th-TH', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`);

export function RemainingInvoicePagePrototype() {
  const { ticketId } = useParams();
  const navigate = useNavigate();
  const deal = useQuery({ queryKey: ['proto-ri-deal', ticketId], queryFn: () => api.tickets.get(Number(ticketId)).then((r) => ({ ...r.ticket.summary, lines: r.ticket.items })) }).data;
  const options = useQuery({ queryKey: ['proto-ri-opts', ticketId], queryFn: () => api.tickets.remainingInvoiceOptions(Number(ticketId)).then((r) => r.options) }).data;
  if (!deal || !options) return null;
  const notes = options.noteTemplates ?? [];
  const items = options.items ?? (deal.lines ?? []).map((it, i) => ({
    seq: i + 1, description: [it.brand, it.model, it.color, it.texture, it.size].filter(Boolean).join(' '),
    qty: it.qty, unit: 'แผ่น', unitPrice: it.approvedPrice, netUnitPrice: it.approvedPrice, amount: it.approvedPrice * it.qty,
  }));
  return (
    <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
      <PageHeader
        title="ใบแจ้งหนี้ส่วนที่เหลือ"
        subtitle={[deal.customerName, deal.projectName].filter(Boolean).join(' · ')}
        context={<StatusBadge tone="neutral">ฉบับร่าง</StatusBadge>}
        actions={(
          <>
            <OverflowMenu label="ดาวน์โหลด" triggerLabel="ดาวน์โหลด" triggerIcon="download" align="start"
              items={[{ key: 'pdf', label: 'PDF', icon: 'fileText', onSelect: () => {} }, { key: 'xlsx', label: 'Excel', icon: 'fileText', onSelect: () => {} }]} />
            <Button variant="primary">ออกเอกสาร</Button>
          </>
        )}
      />
      <div className="flex min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1.5 text-sm">
        <span className="font-bold text-text-muted">งาน</span>
        <code className="font-mono text-sm text-text">{deal.code}</code>
        <span aria-hidden="true" className="text-text-muted">·</span>
        <span className="min-w-0 max-w-full truncate font-bold text-text">{deal.customerName}</span>
        <span aria-hidden="true" className="text-text-muted">·</span>
        <span className="text-text-muted">อ้างอิง</span>
        <code className="font-mono text-sm text-text">{options.defaultReference}</code>
        <button type="button" onClick={() => navigate(`/tickets/${ticketId}`)}
          className="inline-flex min-h-[38px] cursor-pointer items-center gap-1 rounded-md border-0 bg-transparent p-0 font-bold text-primary hover:text-primary-hover mobile:min-h-[44px]">
          เปิดงาน
          <Icon name="chevronRight" size={14} />
        </button>
      </div>

      <Panel title="ข้อมูลเอกสาร">
        <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
          <FormField label="อ้างอิงใบเสนอราคา / ใบสั่งซื้อ" htmlFor="ri-reference">
            <input id="ri-reference" defaultValue={options.defaultReference ?? ''} />
          </FormField>
          <FormField label="วันที่เอกสาร" htmlFor="ri-date">
            <input id="ri-date" defaultValue="18/11/2569" />
          </FormField>
        </div>
      </Panel>

      <Panel title="รายการสินค้า" flush>
        <div className={`${ITEM_GRID} table-head`}>
          <span>รายละเอียด</span>
          <span className="text-right">จำนวน</span>
          <span className="text-right">ราคา/หน่วย</span>
          <span className="text-right">ส่วนลด</span>
          <span className="text-right">สุทธิ</span>
          <span className="text-right">เป็นเงิน</span>
        </div>
        {items.map((it) => (
          <div key={it.seq} className={`${ITEM_GRID} data-row`}>
            <span className="min-w-0"><span className="font-bold">{it.description}</span></span>
            <span className="tabular-nums text-right">{Number(it.qty).toLocaleString('th-TH')} {it.unit ?? ''}</span>
            <span className="tabular-nums text-right">{money(it.unitPrice)}</span>
            <span className="tabular-nums text-right">{it.discountLabel ?? ''}</span>
            <span className="tabular-nums text-right">{money(it.netUnitPrice)}</span>
            <span className="tabular-nums text-right font-bold">{money(it.amount)}</span>
          </div>
        ))}
      </Panel>

      <Panel title="เงื่อนไข">
        <span className="mb-1.5 block text-sm font-bold text-text-secondary">หมายเหตุในเอกสาร</span>
        <div className="flex flex-col gap-2">
          {notes.map((t) => (
            <label key={t.id} className="flex cursor-pointer items-start gap-2 text-sm leading-normal">
              <input type="checkbox" defaultChecked={t.defaultSelected} className="mt-[3px] h-3.5 w-3.5 shrink-0 cursor-pointer accent-info-dot" />
              <span className="min-w-0 flex-1">{t.text}</span>
            </label>
          ))}
        </div>
      </Panel>

      <Panel title="ยอดรวม">
        <div className="flex flex-col items-end gap-1">
          <span className="text-sm text-text-muted">ยอดสินค้า <span className="tabular-nums text-text">{money(options.itemsTotal)}</span></span>
          <span className="text-sm text-text-muted">หักมัดจำ <span className="tabular-nums text-text">{money(options.depositAmount)}</span></span>
          <span className="text-sm text-text-muted">รวมเป็นเงิน <span className="tabular-nums text-text">{money(options.netAmount)}</span></span>
          <span className="text-sm text-text-muted">ภาษีมูลค่าเพิ่ม 7% <span className="tabular-nums text-text">{money(options.vatAmount)}</span></span>
          <span className="text-lg font-extrabold">ยอดชำระ <span className="tabular-nums">{money(options.totalPayable)}</span></span>
        </div>
      </Panel>

      <div className="flex justify-end rounded-md border border-border-muted bg-surface p-4">
        <Button variant="secondary" className="mobile:w-full">บันทึกร่าง</Button>
      </div>
    </div>
  );
}
