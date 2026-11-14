import { Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate } from '../../utils/format.js';
import {
  currencyForLanguage, dealQuotationStatusLabel, documentDiscountLabel, formatQuotationMoney,
  fullPaymentTermLabel, joinPresent, LINE_TYPE_ADJUSTMENT, LINE_TYPE_TILE, lineTypeOf, remainderModeLabel,
  stripPhoneLabel,
} from './quotationMeta.js';

// The item table, totals and signature block follow the DOCUMENT's language, because this view's
// whole job is to look like the page the customer receives. The English labels are read off the
// F-SM-008 samples (QN6900902-6): Description & Conditions / Qty / Unit price / Disc. / Net price /
// Amount (USD), Grand Total (USD) with NO VAT row, and Printed by / Quoted by / Approved by /
// Ordered by. The surrounding panels (customer, terms) stay Thai: they are the app's own chrome,
// not a reproduction of the form.
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

/** "184 แผ่น", "85 Bags", "1 JOB" — and "-1" with an EMPTY unit for a ส่วนลดพิเศษ row, exactly as her
 * QN6900704-2 prints it. `quantity`/`unit` are the v3 per-row fields; a pre-v3 DTO (or fixture) without them
 * falls back to the tile's own piecesFinal/แผ่น, which is what they always meant. */
function quantityCell(item, language = 'TH') {
  const type = lineTypeOf(item);
  // An English per-sqm row's quantity is square metres the SERVER computes (boxes × ตร.ม./กล่อง). Where it is
  // absent (mock mode never computes it) print "-", never the piece count under "SQM".
  if (item.unit === 'SQM' && item.quantity == null) return '-';
  const quantity = item.quantity ?? item.piecesFinal;
  const shown = quantity == null ? '-' : Number(quantity).toLocaleString('en-US', { maximumFractionDigits: 2 });
  if (type === LINE_TYPE_ADJUSTMENT) return shown;
  // The server already sends "PCS" on an English tile row (DealQuotationLines#printedTileUnit); this is only the
  // fallback for a DTO without a unit, and it follows the document too.
  const unit = item.unit ?? (type === LINE_TYPE_TILE ? (language === 'EN' ? 'PCS' : 'แผ่น') : '');
  return unit ? `${shown} ${unit}` : shown;
}

// `reflow-cards` (styles.css): below 720px it hides `.table-head` and turns each `.data-row` into a
// single-column card whose `[data-label]` cells print their own label via `::before` (see
// OvertimePanel.jsx's OVERTIME_TABLE_GRID for the same pattern). Plain `mobile:grid-cols-1` would
// stack the six columns with the head row's six orphan headings and every body value unlabelled.
// ── Column floors: FIXED, identical in every row, and NOT band-scoped ────────────────────────
// 1. A money column must never be shrunk below its own text: `minmax(0, …)` lets the grid do that
//    and `.data-row > span` is `nowrap` with `text-overflow: ellipsis`, so the loss shows up as a
//    silently truncated baht figure (a ฿1,524,369.36 line was cut at every table width under 850px
//    and on desktop at 1041–1150px, where the sidebar returns). So the floors are deliberately
//    ungated.
//
// 2. The floors must be FIXED lengths, not `min-content`. The head row and each data row are
//    SEPARATE grid containers, so `min-content` sizes every row from ITS OWN numbers and
//    right-aligned currency staggers row to row (up to 23px of drift at 721px). Fixed floors give
//    every row the identical track list.
//
// The values are the measured min-content of each column's WIDEST formattable value at the app's
// 16px root (999,999 แผ่น · ฿999,999.99 · 99.99% · ฿999,999.99 · ฿999,999,999.99 → 90/88/54/88/123px),
// rounded up, EXCEPT เป็นเงิน, which is sized for the FALLBACK font rather than Sarabun (Sarabun
// arrives with `display=swap`, and on-prem, if fonts.googleapis.com is unreachable, `system-ui`
// renders permanently, ~14% wider: ฿99,999,999.99 is 114px in Sarabun but 130.5px in the
// fallback). 8.25rem/132px covers both. It costs รายละเอียด 8px at the narrowest in-band width, the
// right trade: the description wraps, the total does not. They total 28.5rem; with gaps and panel
// padding that leaves ~100px for รายละเอียด in the narrowest in-band container (655px at a 721px
// viewport), which is fine because รายละเอียด is the one column that WRAPS, so it keeps
// `minmax(0, …)`.
//
// `tablet:min-w-0` releases `.reflow-cards`'s shared 900px floor for 721–1040px. THAT part is
// band-scoped, and safe only because of the floors above. styles.css loads in `layer(legacy)`, so
// the utility wins on layer order without `!important`. Below 721px none of this applies: that
// band's own `min-width: 0 !important` and `grid-template-columns: 1fr !important` turn these rows
// into labelled cards.
const ITEM_GRID = 'grid-cols-[minmax(0,3fr)_minmax(5.75rem,0.8fr)_minmax(5.75rem,0.9fr)_minmax(3.5rem,0.6fr)_minmax(5.75rem,0.9fr)_minmax(8.25rem,1fr)] tablet:min-w-0 reflow-cards';

/**
 * Read-only "clean document" view of a non-draft (or not-editable-by-this-viewer) quotation: a
 * document-like summary with the signature block names (ผู้พิมพ์ / พนักงานขาย / ผู้จัดการฝ่ายขาย).
 */
export function QuotationDocumentView({ quotation }) {
  const status = dealQuotationStatusLabel(quotation.docStatus);
  const language = quotation.documentLanguage === 'EN' ? 'EN' : 'TH';
  const labels = DOC_LABELS[language];
  const currency = quotation.currency || currencyForLanguage(language);
  const money = (value) => formatQuotationMoney(value, currency);
  // The English signature block prints English names and FALLS BACK to the Thai one when an employee has none on
  // file — the renderer's rule (DealQuotationDto's *NameEn javadoc), so the screen never shows an empty slot the
  // paper would have filled.
  const name = (en, th) => (language === 'EN' ? (en || th) : th);
  // Print-only ผู้พิมพ์/พนักงานขาย name override. Mirrors DealQuotationRenderAdapter#printedByName /
  // #salesRepDisplayNameOrReal / #salesRepDisplayPhoneOrReal exactly, so this preview never shows a
  // different name than the PDF/XLSX prints. createdById/createdByName and salesRepId/
  // salesRepName are not touched, only which value this VIEW reads.
  const printedByName = quotation.printedByDisplayId != null
    ? name(quotation.printedByDisplayNameEn, quotation.printedByDisplayName)
    : name(quotation.createdByNameEn, quotation.createdByName);
  const quotedByName = quotation.salesRepDisplayId != null
    ? name(quotation.salesRepDisplayNameEn, quotation.salesRepDisplayName)
    : name(quotation.salesRepNameEn, quotation.salesRepName);
  const quotedByPhone = quotation.salesRepDisplayId != null
    ? quotation.salesRepDisplayPhone : quotation.salesRepPhone;
  // The paper prints ONLY the ผู้ติดต่อ phone/email typed on the quotation,
  // and nothing when blank -- no customer-master or contact fallback -- so this panel shows the same.
  const typedPhone = stripPhoneLabel(quotation.orderedByPhone);
  const contactLine = joinPresent([
    quotation.contactName,
    typedPhone ? `โทร. ${typedPhone}` : null,
    quotation.orderedByEmail?.trim(),
  ]);
  // ผู้ติดต่อ's Phone and Email: printed by DealQuotationRenderAdapter when filled. Only the ones
  // that were typed, so a missing one leaves no bare label.
  const salesContact = [['Phone', quotation.orderedByPhone], ['Email', quotation.orderedByEmail]]
    // A label-only phone ("โทร.") prints nothing, so it earns no block either; the raw value is shown.
    .filter(([label, value]) => (label === 'Phone' ? stripPhoneLabel(value) : value?.trim()));
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
            {/* The PRINTED document drops its "Project : …" line (DealQuotationRenderAdapter). This
                screen is the internal view of the same document and the approver still needs to know
                which project they are approving, so the name stays and the difference is said in
                words beside it. Only when there is a name to hide. */}
            {quotation.hideProjectName && quotation.projectName?.trim() ? (
              <span className="block text-xs text-text-muted" data-testid="doc-project-hidden">
                ไม่แสดงในใบเสนอราคา
              </span>
            ) : null}
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">พนักงานขาย</span>
            <strong>{quotedByName ?? '-'}{quotedByPhone ? ` · T.${quotedByPhone}` : ''}</strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">วันที่เอกสาร</span>
            <strong>{formatThaiDate(quotation.quotationDate)}</strong>
          </div>
          {/* The header lines the printed document carries, each rendered ONLY when it has a value,
              so a missing one leaves no bare label and no dangling separator (joinPresent). The
              contact name is the FROZEN snapshot the DTO carries; the phone and e-mail are ONLY
              ผู้ติดต่อ's typed ones, what the renderer prints. */}
          {contactLine ? (
            <div data-testid="doc-contact">
              {/* "ติดต่อผู้สั่งซื้อ", not a second "ผู้สั่งซื้อ": that word already heads the fourth signature slot below,
               * and two identical headings for two different things read as a duplicate. */}
              <span className="block text-2xs font-bold uppercase text-text-muted">ติดต่อผู้สั่งซื้อ</span>
              <strong>{contactLine}</strong>
            </div>
          ) : null}
          {/* ผู้ติดต่อ's typed Phone and Email, listed under their own words; they also print in the
              ติดต่อผู้สั่งซื้อ / โทร. cells around it, and the note says so. `min-w-0` + overflow-wrap
              because an address has no spaces to break at. */}
          {salesContact.length ? (
            <div className="min-w-0" data-testid="doc-sales-contact">
              <span className="block text-2xs font-bold uppercase text-text-muted">ผู้ติดต่อ</span>
              {salesContact.map(([label, value]) => (
                <span key={label} className="block [overflow-wrap:anywhere]">
                  <span className="text-text-muted">{label}</span> <strong>{value.trim()}</strong>
                </span>
              ))}
              <span className="block text-xs text-text-muted">พิมพ์ในใบเสนอราคาเมื่อกรอก</span>
            </div>
          ) : null}
          {quotation.customerTaxId?.trim() ? (
            <div data-testid="doc-tax-id">
              <span className="block text-2xs font-bold uppercase text-text-muted">เลขที่ผู้เสียภาษี</span>
              <strong>{quotation.customerTaxId.trim()}</strong>
            </div>
          ) : null}
          {typedPhone ? (
            <div data-testid="doc-customer-phone">
              <span className="block text-2xs font-bold uppercase text-text-muted">โทร.</span>
              <strong>{typedPhone}</strong>
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
              {/* ONE ตำแหน่งติดตั้ง heading per RUN of equal labels: the same grouping the renderer
                  applies to the printed document and locationGroupsFromItems rebuilds in the editor.
                  Compared against the PREVIOUS item's label, never a collected set, so the run
                  structure survives an item order the rep chose deliberately. */}
              {/* These sub-lines are <span>, NOT <div>, and that is load-bearing rather than a style preference. A <div>
               * child therefore inherits the nowrap and the Thai description gets cut mid-word instead of wrapping
               * (measured: 111 px lost at 390, 74 at 1024). */}
              {item.locationLabel && item.locationLabel !== quotation.items[index - 1]?.locationLabel ? (
                <span className="text-2xs font-bold uppercase text-text-muted">{item.locationLabel}</span>
              ) : null}
              <span className="font-bold">{item.descriptionLine}</span>
              {item.sizeLine ? <span className="text-2xs text-text-muted">{item.sizeLine}</span> : null}
              {item.calculationLine ? <span className="text-2xs text-text-muted">{item.calculationLine}</span> : null}
              {/* "(ราคาพิเศษ 1,350 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)" under a SPECIAL_SQM tile —
                  a <span> for the same wrapper-clip reason as the two lines above. */}
              {item.specialPriceLine ? <span className="text-2xs text-text-muted">{item.specialPriceLine}</span> : null}
            </span>
            {/* A ส่วนลดพิเศษ row prints จำนวน -1, NO unit, NO ส่วนลด, a POSITIVE ราคา and
                คงเหลือ and a NEGATIVE เป็นเงิน — her QN6900704-2, and what the server already
                stores (quantity -1 × a positive net IS the negative amount), so nothing here is
                special-cased beyond the two empty cells. */}
            <span data-label={labels.quantity} className="tabular-nums text-right">{quantityCell(item, language)}</span>
            <span data-label={labels.unitPrice} className="tabular-nums text-right">{money(item.unitPrice)}</span>
            <span data-label={labels.discount} className="tabular-nums text-right">{documentDiscountLabel(item, quotation.priceMode, language)}</span>
            <span data-label={labels.net} className="tabular-nums text-right">{money(item.netUnitPrice)}</span>
            <span data-label={labels.amount} className="tabular-nums text-right font-bold">{money(item.lineAmount)}</span>
          </div>
        ))}
        <div className="flex flex-col items-end gap-1 border-t border-border px-5 py-4">
          {/* The English form has NO subtotal/VAT rows — only Grand Total (USD). */}
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
          {/* depositPercent === 0 reads as "ไม่รับมัดจำ", not a phantom "0%" deposit demand (as
              DealQuotationRenderAdapter#depositLine does for the printed document), and ส่วนที่เหลือ
              becomes เงื่อนไขการชำระเงิน, since a remainder is meaningless once there is no deposit. */}
          <div><span className="block text-2xs font-bold uppercase text-text-muted">มัดจำ</span>
            <strong>{quotation.depositPercent === 0 ? 'ไม่รับมัดจำ' : (quotation.depositPercent != null ? `${quotation.depositPercent}%` : '-')}</strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">
              {quotation.depositPercent === 0 ? 'เงื่อนไขการชำระเงิน' : 'ส่วนที่เหลือ'}
            </span>
            <strong>
              {quotation.depositPercent === 0 ? (
                quotation.fullPaymentTerm ? fullPaymentTermLabel(quotation.fullPaymentTerm)
                  // LEGACY: a zero-deposit row saved before the term existed has no term — mirror
                  // DealQuotationRenderAdapter#depositLine's own remainderMode-based fallback text
                  // so this summary never disagrees with the printed document.
                  : (quotation.remainderMode === 'CREDIT' && quotation.creditDays
                      ? `ชำระเต็มจำนวนเป็นเครดิต ${quotation.creditDays} วัน`
                      : 'ชำระเต็มจำนวนก่อนส่งมอบสินค้าหรือเมื่อส่งมอบสินค้า')
              ) : (
                `${remainderModeLabel(quotation.remainderMode)}${quotation.remainderMode === 'CREDIT' && quotation.creditDays ? ` ${quotation.creditDays} วัน` : ''}`
              )}
            </strong>
          </div>
          {/* DATE mode shows the rep's exact ภายในวันที่ deadline (validityUntil) instead of a day
              count, in the same date format (formatThaiDate) as the DAYS branch below. */}
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">ยืนราคา</span>
            <strong>
              {quotation.validityMode === 'DATE'
                ? (quotation.validityUntil ? `ถึง ${formatThaiDate(quotation.validityUntil)}` : '-')
                : `${quotation.validityDays ? `${quotation.validityDays} วัน` : '-'}${quotation.validityDate ? ` (ถึง ${formatThaiDate(quotation.validityDate)})` : ''}`}
            </strong>
          </div>
          <div><span className="block text-2xs font-bold uppercase text-text-muted">สถานะ</span><StatusBadge tone={status.tone}>{status.label}</StatusBadge></div>
        </div>
        {quotation.customerNotes ? (
          <p className="mt-3 text-sm text-text-muted">{quotation.customerNotes}</p>
        ) : null}
      </Panel>

      {/* Four slots, matching the printed form's own signature block — ผู้สั่งซื้อ is the fourth and
          is ALWAYS "-" (the dotted line the customer signs on in the PDF/XLSX), matching
          DealQuotationRenderAdapter. The picked ผู้ติดต่อ prints only after "เรียน" (owner 2026-11-06). */}
      <Panel title="ผู้เกี่ยวข้อง">
        <div className="grid grid-cols-4 gap-4 tablet:grid-cols-2 mobile:grid-cols-1 text-center text-sm">
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">{labels.printedBy}</span>
            <strong className="block mt-6 border-t border-border pt-2">{printedByName ?? '-'}</strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">{labels.quotedBy}</span>
            <strong className="block mt-6 border-t border-border pt-2">{quotedByName ?? '-'}</strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">{labels.approvedBy}</span>
            <strong className="block mt-6 border-t border-border pt-2">
              {quotation.docStatus === 'APPROVED' ? (name(quotation.approvedByNameEn, quotation.approvedByName) ?? '-') : ''}
            </strong>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">{labels.orderedBy}</span>
            <strong className="block mt-6 border-t border-border pt-2">-</strong>
          </div>
        </div>
      </Panel>
    </div>
  );
}
