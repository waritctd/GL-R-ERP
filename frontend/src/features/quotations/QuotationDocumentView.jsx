import { Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatThaiDate } from '../../utils/format.js';
import { dealQuotationStatusLabel, remainderModeLabel } from './quotationMeta.js';

// `reflow-cards` (styles.css ~L1330, mirrored by every other DataTable-style grid in this app —
// see OvertimePanel.jsx's OVERTIME_TABLE_GRID for the same pattern): below 720px it hides
// `.table-head` and turns each `.data-row` into a single-column card whose `[data-label]` cells
// print their own label via `::before`. Plain `mobile:grid-cols-1` (the previous rule here) only
// stacked the six columns into six rows apiece with no labels — the head row's six orphan
// headings and every body value unlabelled.
const ITEM_GRID = 'grid-cols-[minmax(0,3fr)_minmax(0,0.8fr)_minmax(0,0.9fr)_minmax(0,0.6fr)_minmax(0,0.9fr)_minmax(0,1fr)] reflow-cards';

/**
 * Read-only "clean document" view of a non-draft (or not-editable-by-this-viewer) quotation --
 * the plan's "Read view (non-draft) shows the same data as a clean document-like summary with the
 * signature block names (ผู้พิมพ์ / พนักงานขาย / ผู้จัดการฝ่ายขาย)".
 */
export function QuotationDocumentView({ quotation }) {
  const status = dealQuotationStatusLabel(quotation.docStatus);
  return (
    <div className="grid gap-[18px]">
      <Panel title="ข้อมูลลูกค้า">
        <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1 text-sm">
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">ลูกค้า</span>
            <strong>{quotation.customerName ?? '-'}</strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">โครงการ</span>
            <strong>{quotation.projectName ?? '-'}</strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">พนักงานขาย</span>
            <strong>{quotation.salesRepName ?? '-'}{quotation.salesRepPhone ? ` · T.${quotation.salesRepPhone}` : ''}</strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">วันที่เอกสาร</span>
            <strong>{formatThaiDate(quotation.quotationDate)}</strong>
          </div>
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
        {quotation.items.map((item, index) => (
          <div key={item.id ?? item.seq} className={`${ITEM_GRID} data-row`}>
            <span data-label="รายละเอียด" className="min-w-0">
              {/* ONE ตำแหน่งติดตั้ง heading per RUN of equal labels (owner feedback F1,
                  2026-09-10) — the same grouping the renderer applies to the printed document,
                  and the same one locationGroupsFromItems rebuilds in the editor. Repeating the
                  label on every row of a six-tile floor was the thing the owner was reacting to.
                  Compared against the PREVIOUS item's label, never against a collected set, so
                  the run structure survives an item order the rep chose deliberately. */}
              {/* These sub-lines are <span>, NOT <div>, and that is load-bearing rather than a
                  style preference. `styles.css` clips on the WRAPPER — `.data-row > span` is
                  `white-space: nowrap; text-overflow: ellipsis` — and its escape hatch (the
                  "SECONDARY sub-line wraps instead of truncating" rule) only reaches
                  `> strong | > small | > span`. A <div> child therefore inherits the nowrap and
                  the Thai description gets cut mid-word instead of wrapping (measured: 111 px lost
                  at 390, 74 at 1024). This is the recorded `.data-row` wrapper-span trap recurring
                  in new code — the clip is on the parent, so styling the child alone does nothing;
                  the child has to MATCH the escape hatch's selector. (A <div> inside a <span> was
                  invalid nesting too, but the wrapping bug is the reason.) */}
              {item.locationLabel && item.locationLabel !== quotation.items[index - 1]?.locationLabel ? (
                <span className="text-2xs font-bold uppercase text-text-muted">{item.locationLabel}</span>
              ) : null}
              <span className="font-bold">{item.descriptionLine}</span>
              {item.sizeLine ? <span className="text-2xs text-text-muted">{item.sizeLine}</span> : null}
              {item.calculationLine ? <span className="text-2xs text-text-muted">{item.calculationLine}</span> : null}
            </span>
            <span data-label="จำนวน" className="tabular-nums text-right">{item.piecesFinal ?? '-'} แผ่น</span>
            <span data-label="ราคา/หน่วย" className="tabular-nums text-right">{formatMoney(item.unitPrice)}</span>
            <span data-label="ส่วนลด" className="tabular-nums text-right">{item.discountPct ? `${item.discountPct}%` : 'Net'}</span>
            <span data-label="สุทธิ" className="tabular-nums text-right">{formatMoney(item.netUnitPrice)}</span>
            <span data-label="เป็นเงิน" className="tabular-nums text-right font-bold">{formatMoney(item.lineAmount)}</span>
          </div>
        ))}
        <div className="flex flex-col items-end gap-1 border-t border-border px-5 py-4">
          <span className="text-sm text-text-muted">รวมเป็นเงิน <span className="tabular-nums text-text">{formatMoney(quotation.subtotalAmount)}</span></span>
          <span className="text-sm text-text-muted">ภาษีมูลค่าเพิ่ม 7% <span className="tabular-nums text-text">{formatMoney(quotation.vatAmount)}</span></span>
          <span className="text-lg font-extrabold">ยอดรวมทั้งสิ้น <span className="tabular-nums">{formatMoney(quotation.grandTotal)}</span></span>
        </div>
      </Panel>

      <Panel title="เงื่อนไข">
        <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1 text-sm">
          <div><span className="block text-2xs font-bold uppercase text-text-muted">มัดจำ</span><strong>{quotation.depositPercent != null ? `${quotation.depositPercent}%` : '-'}</strong></div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">ส่วนที่เหลือ</span>
            <strong>{remainderModeLabel(quotation.remainderMode)}{quotation.remainderMode === 'CREDIT' && quotation.creditDays ? ` ${quotation.creditDays} วัน` : ''}</strong>
          </div>
          <div><span className="block text-2xs font-bold uppercase text-text-muted">ยืนราคา</span><strong>{quotation.validityDays ? `${quotation.validityDays} วัน` : '-'}{quotation.validityDate ? ` (ถึง ${formatThaiDate(quotation.validityDate)})` : ''}</strong></div>
          <div><span className="block text-2xs font-bold uppercase text-text-muted">สถานะ</span><StatusBadge tone={status.tone}>{status.label}</StatusBadge></div>
        </div>
        {quotation.customerNotes ? (
          <p className="mt-3 text-sm text-text-muted">{quotation.customerNotes}</p>
        ) : null}
      </Panel>

      {/* Four slots, matching the printed form's own signature block — ผู้สั่งซื้อ is the fourth
          (owner feedback F2, 2026-09-10: "use that name to auto fill in the name for signature in
          the quotation pdf"). The name shown here is the FROZEN `contactName` snapshot the DTO
          carries, which is exactly what the renderer prints, so screen and paper cannot disagree
          even if the customer's contact record is edited later. */}
      <Panel title="ผู้เกี่ยวข้อง">
        <div className="grid grid-cols-4 gap-4 tablet:grid-cols-2 mobile:grid-cols-1 text-center text-sm">
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">ผู้พิมพ์</span>
            <strong className="block mt-6 border-t border-border pt-2">{quotation.createdByName ?? '-'}</strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">พนักงานขาย</span>
            <strong className="block mt-6 border-t border-border pt-2">{quotation.salesRepName ?? '-'}</strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">ผู้จัดการฝ่ายขาย</span>
            <strong className="block mt-6 border-t border-border pt-2">
              {quotation.docStatus === 'APPROVED' ? (quotation.approvedByName ?? '-') : ''}
            </strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">ผู้สั่งซื้อ</span>
            <strong className="block mt-6 border-t border-border pt-2">{quotation.contactName ?? '-'}</strong>
          </div>
        </div>
      </Panel>
    </div>
  );
}
