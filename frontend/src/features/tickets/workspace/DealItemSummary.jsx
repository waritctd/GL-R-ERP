import { formatMoney } from '../../../utils/format.js';

function emptyValue(value) {
  return value == null || value === '' ? '-' : value;
}

function quantityLabel(item) {
  if (item.unitBasis === 'SQM') {
    return item.qtySqm != null ? `${Number(item.qtySqm).toFixed(2)} ตร.ม. · ${item.qty ?? 0} แผ่น` : `${item.qty ?? 0} แผ่น`;
  }
  return item.qtySqm != null ? `${item.qty ?? 0} แผ่น · ${Number(item.qtySqm).toFixed(2)} ตร.ม.` : `${item.qty ?? 0} แผ่น`;
}

function fulfilmentLabel(item) {
  const ordered = Number(item.qty || 0);
  const delivered = Number(item.qtyDelivered || 0);
  const fromStock = Number(item.qtyFromStock || 0);
  if (!ordered) return '-';
  const parts = [`ส่งมอบ ${delivered.toLocaleString('th-TH')} / ${ordered.toLocaleString('th-TH')}`];
  if (fromStock > 0) parts.push(`สต็อก ${fromStock.toLocaleString('th-TH')}`);
  return parts.join(' · ');
}

function priceLabel(item, { showProposed, showApproved, showCalcBreakdown }) {
  if (showCalcBreakdown) {
    const raw = item.rawPrice != null ? `${Number(item.rawPrice).toLocaleString('th-TH', { minimumFractionDigits: 2 })} ${item.rawCurrency ?? ''}`.trim() : '-';
    const cost = item.calcedCost != null ? formatMoney(item.calcedCost) : '-';
    const salesPrice = item.manualPrice != null ? formatMoney(item.manualPrice) : item.calcedPrice != null ? formatMoney(item.calcedPrice) : '-';
    return [
      `โรงงาน ${raw}`,
      `ต้นทุน ${cost}`,
      `ขาย ${salesPrice}`,
    ].join(' · ');
  }
  if (showProposed && showApproved) {
    return `เสนอ ${formatMoney(item.proposedPrice)} · อนุมัติ ${formatMoney(item.approvedPrice)}`;
  }
  if (showProposed) return `เสนอ ${formatMoney(item.proposedPrice)}`;
  if (showApproved) return `อนุมัติ ${formatMoney(item.approvedPrice)}`;
  return '-';
}

export function DealItemSummary({
  items = [], showProposed = false, showApproved = false, showCalcBreakdown = false,
}) {
  if (items.length === 0) {
    return (
      <div className="px-4 py-3 text-sm text-text-muted sm:px-5" data-testid="deal-item-summary-empty">
        ไม่มีรายการสินค้า
      </div>
    );
  }

  return (
    <div className="divide-y divide-border-subtle" data-testid="deal-item-summary">
      {items.map((item, index) => (
        <div key={item.id ?? index} className="grid gap-2 px-4 py-3 text-sm sm:grid-cols-[1.4fr_1fr_1fr_1.2fr] sm:gap-4 sm:px-5">
          <div className="min-w-0">
            <div className="font-extrabold text-text">{emptyValue(item.brand)}</div>
            <div className="break-words text-xs text-text-muted">
              {[item.model, item.factory].filter(Boolean).join(' · ') || '-'}
            </div>
          </div>
          <div className="min-w-0">
            <div className="text-2xs font-bold uppercase text-text-muted">รายละเอียด</div>
            <div className="break-words text-text-secondary">
              {[item.color, item.texture, item.size].filter(Boolean).join(' · ') || '-'}
            </div>
          </div>
          <div className="min-w-0">
            <div className="text-2xs font-bold uppercase text-text-muted">จำนวน / ส่งมอบ</div>
            <div className="text-text-secondary">{quantityLabel(item)}</div>
            <div className="text-xs text-text-muted">{fulfilmentLabel(item)}</div>
          </div>
          <div className="min-w-0">
            <div className="text-2xs font-bold uppercase text-text-muted">ราคา</div>
            <div className="break-words font-bold text-text-secondary">{priceLabel(item, { showProposed, showApproved, showCalcBreakdown })}</div>
          </div>
        </div>
      ))}
    </div>
  );
}
