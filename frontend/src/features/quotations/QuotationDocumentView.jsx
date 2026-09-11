import { Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate } from '../../utils/format.js';
import {
  currencyForLanguage, dealQuotationStatusLabel, documentDiscountLabel, formatQuotationMoney,
  joinPresent, LINE_TYPE_ADJUSTMENT, LINE_TYPE_TILE, lineTypeOf, remainderModeLabel,
} from './quotationMeta.js';

// v3b: the item table, totals and signature block follow the DOCUMENT's language, because this
// view's whole job is to look like the page the customer receives. The Thai labels are the ones
// every pre-v3b test and the printed F-SM-002 already use; the English ones are read off the
// owner's F-SM-008 samples (QN6900902-6): Description & Conditions / Qty / Unit price / Disc. /
// Net price / Amount (USD), Grand Total (USD) with NO VAT row, and Printed by / Quoted by /
// Approved by / Ordered by. The surrounding panels (customer, terms) stay Thai — they are the
// app's own chrome, not a reproduction of the form.
const DOC_LABELS = {
  TH: {
    description: 'รายละเอียด', quantity: 'จำนวน', unitPrice: 'ราคา/หน่วย', discount: 'ส่วนลด',
    net: 'สุทธิ', amount: 'เป็นเงิน', subtotal: 'รวมเป็นเงิน', vat: 'ภาษีมูลค่าเพิ่ม 7%',
    grand: 'ยอดรวมทั้งสิ้น', printedBy: 'ผู้พิมพ์', quotedBy: 'พนักงานขาย',
    approvedBy: 'ผู้จัดการฝ่ายขาย', orderedBy: 'ผู้สั่งซื้อ',
  },
  EN: {
    description: 'Description & Conditions', quantity: 'Qty', unitPrice: 'Unit price', discount: 'Disc.',
    net: 'Net price', amount: 'Amount (USD)', subtotal: 'Total (USD)', vat: null,
    grand: 'Grand Total (USD)', printedBy: 'Printed by', quotedBy: 'Quoted by',
    approvedBy: 'Approved by', orderedBy: 'Ordered by',
  },
};

/** "184 แผ่น", "85 Bags", "1 JOB" — and "-1" with an EMPTY unit for a ส่วนลดพิเศษ row, exactly as
 * her QN6900704-2 prints it. `quantity`/`unit` are the v3 per-row fields; a pre-v3 DTO (or fixture)
 * without them falls back to the tile's own piecesFinal/แผ่น, which is what they always meant. */
function quantityCell(item) {
  const type = lineTypeOf(item);
  const quantity = item.quantity ?? item.piecesFinal;
  const shown = quantity == null ? '-' : Number(quantity).toLocaleString('en-US', { maximumFractionDigits: 2 });
  if (type === LINE_TYPE_ADJUSTMENT) return shown;
  const unit = item.unit ?? (type === LINE_TYPE_TILE ? 'แผ่น' : '');
  return unit ? `${shown} ${unit}` : shown;
}

// `reflow-cards` (styles.css ~L1330, mirrored by every other DataTable-style grid in this app —
// see OvertimePanel.jsx's OVERTIME_TABLE_GRID for the same pattern): below 720px it hides
// `.table-head` and turns each `.data-row` into a single-column card whose `[data-label]` cells
// print their own label via `::before`. Plain `mobile:grid-cols-1` (the previous rule here) only
// stacked the six columns into six rows apiece with no labels — the head row's six orphan
// headings and every body value unlabelled.
// ── Column floors: FIXED, identical in every row, and NOT band-scoped ────────────────────────
// Two separate problems, one line.
//
// 1. A money column must never be shrunk below its own text. Every numeric track used to be
//    `minmax(0, …)`, which lets the grid do exactly that, and `.data-row > span` is `nowrap` with
//    `text-overflow: ellipsis` — so the loss did not show up as overflow, it showed up as a
//    silently truncated baht figure. Measured on the demo's ฿1,524,369.36 line, เป็นเงิน was cut
//    at every table width under 850px, and (this is the part the first attempt got wrong) ALSO on
//    desktop at 1041–1150px, where the sidebar returns. So the floors are deliberately ungated:
//    they fix a clip that was never confined to the tablet band.
//
// 2. The floors must be FIXED lengths, not `min-content`. The head row and each data row are
//    SEPARATE grid containers, so `min-content` sizes every row from ITS OWN numbers: a row
//    holding ฿1,524,369.36 floors its last track wide and squeezes the rest, while the row below
//    it does not. Right-aligned currency then staggers row to row, and the header sits over
//    neither — measured at up to 23px of drift at 721px, and 19px at 1041px. On a view whose whole
//    job is to look like the printed form, that reads as broken. Fixed floors give every row the
//    identical track list, so the columns stay on one rail at any width.
//
// The values are the measured min-content of each column's WIDEST formattable value at the app's
// 16px root (999,999 แผ่น · ฿999,999.99 · 99.99% · ฿999,999.99 · ฿999,999,999.99 → 90/88/54/88/123px),
// rounded up — EXCEPT เป็นเงิน, which is sized for the FALLBACK font rather than Sarabun. Sarabun
// arrives from Google Fonts with `display=swap`, so during the swap window, and permanently on the
// on-prem deployment if that host cannot reach fonts.googleapis.com, `system-ui` renders instead
// and every figure is ~14% wider: ฿99,999,999.99 measures 114px in Sarabun but 130.5px in the
// fallback. A 7.75rem/124px floor covers Sarabun and silently clips the fallback from about ฿10M a
// line upward, which a large project reaches. 8.25rem/132px covers both. It costs รายละเอียด 8px
// at the narrowest in-band width, which is the right trade: the description wraps, the total does
// not. They total 28.5rem; with gaps and panel padding that leaves ~100px for รายละเอียด in
// the narrowest in-band container (655px at a 721px viewport), which is fine because รายละเอียด is
// the one column that WRAPS — it is meant to absorb the squeeze, which is why it keeps `minmax(0, …)`.
//
// `tablet:min-w-0` then releases `.reflow-cards`'s shared 900px floor for 721–1040px. THAT part is
// band-scoped, and it is safe only because of the floors above: the table now shrinks to what its
// numbers actually need instead of scrolling 245px sideways inside a 655px container (owner review,
// 2026-09-10). styles.css loads in `layer(legacy)`, so the utility wins on layer order without
// `!important`. Below 721px none of this applies — that band's own `min-width: 0 !important` and
// `grid-template-columns: 1fr !important` turn these rows into labelled cards.
const ITEM_GRID = 'grid-cols-[minmax(0,3fr)_minmax(5.75rem,0.8fr)_minmax(5.75rem,0.9fr)_minmax(3.5rem,0.6fr)_minmax(5.75rem,0.9fr)_minmax(8.25rem,1fr)] tablet:min-w-0 reflow-cards';

/**
 * Read-only "clean document" view of a non-draft (or not-editable-by-this-viewer) quotation --
 * the plan's "Read view (non-draft) shows the same data as a clean document-like summary with the
 * signature block names (ผู้พิมพ์ / พนักงานขาย / ผู้จัดการฝ่ายขาย)".
 */
export function QuotationDocumentView({ quotation }) {
  const status = dealQuotationStatusLabel(quotation.docStatus);
  const language = quotation.documentLanguage === 'EN' ? 'EN' : 'TH';
  const labels = DOC_LABELS[language];
  const currency = quotation.currency || currencyForLanguage(language);
  const money = (value) => formatQuotationMoney(value, currency);
  // The English signature block prints English names and FALLS BACK to the Thai one when an
  // employee has none on file — the renderer's rule (DealQuotationDto's *NameEn javadoc), so the
  // screen never shows an empty slot the paper would have filled.
  const name = (en, th) => (language === 'EN' ? (en || th) : th);
  const contactLine = joinPresent([
    quotation.contactName,
    quotation.contactPhone?.trim() ? `โทร. ${quotation.contactPhone.trim()}` : null,
    quotation.contactEmail,
  ]);
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
          {/* The header lines the printed document carries (owner, 2026-09-11) — each rendered ONLY
              when it has a value, so a missing one leaves no bare label and no dangling separator
              (joinPresent). These are the FROZEN snapshot columns the DTO carries, i.e. exactly
              what the renderer prints. */}
          {contactLine ? (
            <div data-testid="doc-contact">
              {/* "ติดต่อผู้สั่งซื้อ", not a second "ผู้สั่งซื้อ": that word already heads the fourth
                  signature slot below, and two identical headings for two different things read
                  as a duplicate. */}
              <span className="block text-2xs font-bold uppercase text-text-muted">ติดต่อผู้สั่งซื้อ</span>
              <strong>{contactLine}</strong>
            </div>
          ) : null}
          {quotation.customerTaxId?.trim() ? (
            <div data-testid="doc-tax-id">
              <span className="block text-2xs font-bold uppercase text-text-muted">เลขที่ผู้เสียภาษี</span>
              <strong>{quotation.customerTaxId.trim()}</strong>
            </div>
          ) : null}
          {quotation.customerPhone?.trim() ? (
            <div data-testid="doc-customer-phone">
              <span className="block text-2xs font-bold uppercase text-text-muted">โทร.</span>
              <strong>{quotation.customerPhone.trim()}</strong>
            </div>
          ) : null}
          {quotation.customerAddress?.trim() ? (
            <div className="col-span-full" data-testid="doc-address">
              <span className="block text-2xs font-bold uppercase text-text-muted">ที่อยู่</span>
              <strong className="whitespace-pre-line">{quotation.customerAddress.trim()}</strong>
            </div>
          ) : null}
        </div>
      </Panel>

      <Panel title="รายการสินค้า" flush>
        <div className={`${ITEM_GRID} table-head`}>
          <span>{labels.description}</span>
          <span className="text-right">{labels.quantity}</span>
          <span className="text-right">{labels.unitPrice}</span>
          <span className="text-right">{labels.discount}</span>
          <span className="text-right">{labels.net}</span>
          <span className="text-right">{labels.amount}</span>
        </div>
        {quotation.items.map((item, index) => (
          <div key={item.id ?? item.seq} className={`${ITEM_GRID} data-row`} data-line-type={lineTypeOf(item)}>
            <span data-label={labels.description} className="min-w-0">
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
              {/* v3: "(ราคาพิเศษ 1,350 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)" under a SPECIAL_SQM tile —
                  a <span> for the same wrapper-clip reason as the two lines above. */}
              {item.specialPriceLine ? <span className="text-2xs text-text-muted">{item.specialPriceLine}</span> : null}
            </span>
            {/* v3: a ส่วนลดพิเศษ row prints จำนวน -1, NO unit, NO ส่วนลด, a POSITIVE ราคา and
                คงเหลือ and a NEGATIVE เป็นเงิน — her QN6900704-2, and what the server already
                stores (quantity -1 × a positive net IS the negative amount), so nothing here is
                special-cased beyond the two empty cells. */}
            <span data-label={labels.quantity} className="tabular-nums text-right">{quantityCell(item)}</span>
            <span data-label={labels.unitPrice} className="tabular-nums text-right">{money(item.unitPrice)}</span>
            <span data-label={labels.discount} className="tabular-nums text-right">{documentDiscountLabel(item, quotation.priceMode, language)}</span>
            <span data-label={labels.net} className="tabular-nums text-right">{money(item.netUnitPrice)}</span>
            <span data-label={labels.amount} className="tabular-nums text-right font-bold">{money(item.lineAmount)}</span>
          </div>
        ))}
        <div className="flex flex-col items-end gap-1 border-t border-border px-5 py-4">
          {/* v3b: the English form has NO subtotal/VAT rows — only Grand Total (USD). */}
          {labels.vat ? (
            <>
              <span className="text-sm text-text-muted">{labels.subtotal} <span className="tabular-nums text-text">{money(quotation.subtotalAmount)}</span></span>
              <span className="text-sm text-text-muted">{labels.vat} <span className="tabular-nums text-text">{money(quotation.vatAmount)}</span></span>
            </>
          ) : null}
          <span className="text-lg font-extrabold">{labels.grand} <span className="tabular-nums">{money(quotation.grandTotal)}</span></span>
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
            <span className="block text-2xs font-bold uppercase text-text-muted">{labels.printedBy}</span>
            <strong className="block mt-6 border-t border-border pt-2">{name(quotation.createdByNameEn, quotation.createdByName) ?? '-'}</strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">{labels.quotedBy}</span>
            <strong className="block mt-6 border-t border-border pt-2">{name(quotation.salesRepNameEn, quotation.salesRepName) ?? '-'}</strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">{labels.approvedBy}</span>
            <strong className="block mt-6 border-t border-border pt-2">
              {quotation.docStatus === 'APPROVED' ? (name(quotation.approvedByNameEn, quotation.approvedByName) ?? '-') : ''}
            </strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">{labels.orderedBy}</span>
            <strong className="block mt-6 border-t border-border pt-2">{quotation.contactName ?? '-'}</strong>
          </div>
        </div>
      </Panel>
    </div>
  );
}
