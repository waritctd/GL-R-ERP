import { Panel } from '../../components/common/Layout.jsx';
import { formatMoney } from '../../utils/format.js';

const NUMBER = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 });

function quantityLabel(item) {
  if (item.quantityMode === 'AREA' && item.areaSqm != null) return `${NUMBER.format(Number(item.areaSqm))} ตร.ม.`;
  if (item.piecesFinal != null) return `${NUMBER.format(Number(item.piecesFinal))} แผ่น`;
  return '—';
}

/**
 * "สินค้าในใบล่าสุด" (drawing a-deal-quote): the lines of the deal's latest priced quotation (dealTimeline.js's
 * latestPricedQuotation), read off the quotation list the page already fetched (DealQuotationDto.items). Every
 * figure is the server's own — descriptionLine, the quantity, netUnitPrice, lineAmount — never recomputed here.
 * Renders nothing when there is no such quotation or it has no lines.
 */
export function DealLatestQuotationItems({ quotation }) {
  const items = quotation?.items ?? [];
  if (!quotation || items.length === 0) return null;
  return (
    <Panel
      flush
      data-testid="latest-quotation-items"
      title="สินค้าในใบล่าสุด"
      actions={<span className="text-xs text-text-muted">{`มาจาก ${quotation.number ?? `ร่าง #${quotation.id}`} ไม่ต้องกรอกซ้ำในงาน`}</span>}
    >
      <div className="overflow-x-auto">
        <table className="w-full min-w-[32rem] border-collapse text-sm">
          <thead>
            <tr className="border-b border-border-subtle text-left text-xs text-text-muted">
              <th scope="col" className="px-5 py-2.5 font-bold mobile:px-4">สินค้า</th>
              <th scope="col" className="px-3 py-2.5 text-right font-bold">จำนวน</th>
              <th scope="col" className="px-3 py-2.5 text-right font-bold">ราคา/หน่วย</th>
              <th scope="col" className="px-5 py-2.5 text-right font-bold mobile:px-4">รวม</th>
            </tr>
          </thead>
          <tbody>
            {[...items].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0)).map((item) => (
              <tr key={item.id} className="border-b border-border-subtle last:border-b-0">
                <td className="px-5 py-2.5 text-text [overflow-wrap:anywhere] mobile:px-4">{item.descriptionLine || [item.brand, item.model].filter(Boolean).join(' ') || '—'}</td>
                <td className="px-3 py-2.5 text-right tabular-nums">{quantityLabel(item)}</td>
                <td className="px-3 py-2.5 text-right tabular-nums">{item.netUnitPrice != null ? formatMoney(item.netUnitPrice) : '—'}</td>
                <td className="px-5 py-2.5 text-right font-bold tabular-nums mobile:px-4">{item.lineAmount != null ? formatMoney(item.lineAmount) : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Panel>
  );
}
