package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderModel.RenderItem;
import th.co.glr.hr.ticket.QuotationRenderModel.Signatories;

/**
 * Quotation v2 (direct deal quotation, V165) — builds {@link QuotationRenderer}'s rich model
 * ({@link QuotationRenderModel}) straight from a {@link DealQuotationDto}, per
 * docs/sales/quotation-v2-plan.md's "Printed lines" section. Every field the renderer needs (customer/rep/
 * creator/approver names, phone, dept/unit codes, per-item lead times) already lives on
 * {@code DealQuotationDto} / {@code DealQuotationItemDto}, so unlike pass 1's version of this
 * class, there is no more adaptation into the legacy {@code TicketDto}/{@code QuotationDto}
 * shapes — this builds the real model directly.
 *
 * <p>A pure function: no DB access. The approver's signature bytes are a live
 * {@code hr.employee_signature} read, so the caller ({@code DealQuotationService}) resolves them
 * and passes the bytes in.
 */
public final class DealQuotationRenderAdapter {
    private DealQuotationRenderAdapter() {}

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    public static QuotationRenderModel toRenderModel(DealQuotationDto quotation, byte[] approverSignaturePng,
                                                      String approverSignatureMime) {
        // B4 (header วันที่) = the date the SALES REP CREATED the quotation, for every status —
        // owner feedback F8, 2026-09-10: "for วันที่ at the top of the page it should be the date
        // it was created by the sale". It used to print the APPROVED date once approved (and
        // today's date before that), which made an approved document appear to have been quoted on
        // the day the manager got round to it. Mirrors DealQuotationRepository#mapQuotation's
        // quotationDate so the UI and the document can never disagree. The remark-1 offer date
        // (วันที่รับจำนวน) is a DIFFERENT, rep-editable date and stays untouched — see
        // #remarkLines. createdAt is sales.quotation.issued_at, NOT NULL since V59; the
        // today-fallback is defensive only.
        LocalDate issueDate = quotation.createdAt() != null
            ? quotation.createdAt().atZone(BANGKOK).toLocalDate()
            : LocalDate.now(BANGKOK);

        String salesLine = "Sales/" + nullSafe(quotation.salesRepName()) + " T." + nullSafe(quotation.salesRepPhone());

        String contactPart = !blank(quotation.contactName())
            ? "คุณ" + quotation.contactName().trim() + "   /   " : "";
        String taxIdPart = !blank(quotation.customerTaxId())
            ? "   เลขที่ผู้เสียภาษี : " + quotation.customerTaxId().trim() : "";
        String attnLine = contactPart + nullSafe(quotation.customerName()) + taxIdPart;
        String phoneLine = !blank(quotation.customerPhone()) ? "โทร. " + quotation.customerPhone().trim() : "";

        List<RenderItem> items = quotation.items().stream()
            .sorted((a, b) -> Integer.compare(a.seq(), b.seq()))
            .map(item -> toRenderItem(item, quotation.priceMode()))
            .toList();

        // Owner feedback pass 1 (2026-09-10): slot 4 = the ผู้สั่งซื้อ snapshot (F2); the dates row =
        // created / submitted / approved (F4), each only once it exists -- a DRAFT prints only
        // ผู้พิมพ์'s, and ผู้สั่งซื้อ's stays dotted for the customer's pen.
        Signatories signatories = new Signatories(
            quotation.createdByName(), quotation.salesRepName(), quotation.approvedByName(),
            blank(quotation.contactName()) ? null : quotation.contactName().trim(),
            approverSignaturePng, approverSignatureMime,
            bangkokDate(quotation.createdAt()), bangkokDate(quotation.submittedAt()), bangkokDate(quotation.approvedAt()));

        return new QuotationRenderModel(
            issueDate, quotation.number(), quotation.deptCode(), quotation.unitCode(), salesLine,
            attnLine, phoneLine, quotation.projectName(), items, remarkLines(quotation), signatories, true);
    }

    /**
     * One printed row, for any of the three v3 line types.
     *
     * <p>Every null-guarded {@code lines.add} below follows layout-spec §2's contract: a printed
     * line that does not apply is {@code null}, NOT blank, and the caller must omit the row
     * entirely rather than emit an empty one. A PLAIN or ADJUSTMENT row has no size line and no
     * calculation line at all, so both are null there and only the description prints; a
     * SPECIAL_SQM tile adds the ราคาพิเศษ sub-line the owner's documents carry.
     *
     * <p>{@code quantity}/{@code unit} rather than {@code piecesFinal}/"แผ่น": the printed จำนวน
     * and หน่วย are per-row now. A TILE row sets quantity = piecesFinal and unit = "แผ่น", so this
     * is byte-identical to the previous expression for every pre-v3 document; an ADJUSTMENT row
     * carries −1 and a NULL unit, which the renderer prints as an EMPTY unit cell.
     */
    private static RenderItem toRenderItem(DealQuotationItemDto item, String priceMode) {
        List<String> lines = new ArrayList<>();
        lines.add(item.descriptionLine());
        if (item.sizeLine() != null) {
            lines.add(item.sizeLine());
        }
        if (item.calculationLine() != null) {
            lines.add(item.calculationLine());
        }
        if (item.specialPriceLine() != null) {
            lines.add(item.specialPriceLine());
        }
        return new RenderItem(
            item.locationLabel(), lines,
            item.quantity(), printedUnit(item), item.unitPrice(), discountLabel(item, priceMode),
            item.netUnitPrice(), item.lineAmount());
    }

    /**
     * The หน่วย cell. The distinction between {@code null} and {@code ""} is load-bearing here and
     * must not be collapsed: {@code QuotationRenderer#fillItemMainRow} treats a NULL unit as "use
     * the default แผ่น" and an EMPTY unit as "print nothing". An ADJUSTMENT row stores no unit at
     * all (raw_unit NULL), and the owner's ส่วนลดพิเศษ line prints a BLANK หน่วย — so a non-tile
     * row's missing unit is mapped to "" here, never passed through as null.
     */
    private static String printedUnit(DealQuotationItemDto item) {
        boolean tile = item.lineType() == null
            || WastageCalculator.LINE_TYPE_TILE.equals(item.lineType());
        if (item.unit() != null) {
            return item.unit();
        }
        return tile ? "แผ่น" : "";
    }

    /**
     * The ส่วนลด column. Owner feedback pass 3: it reads <b>พิเศษ</b> for a SPECIAL_SQM row, and for
     * a DIRECT_NET row whose net actually differs from the list price; it stays {@code Net} /
     * {@code N%} in NET mode, exactly as before.
     *
     * <p>The price mode only governs TILE rows. A PLAIN row follows its OWN discount (normally
     * absent, so "Net") whatever mode the document is in — that is what lets a ราคาพิเศษ document
     * still carry an ordinary freight line. An ADJUSTMENT row prints "Net" because its ราคา and
     * คงเหลือ are literally equal; the discount IS the row, not a modifier on it.
     */
    private static String discountLabel(DealQuotationItemDto item, String priceMode) {
        BigDecimal pct = item.discountPct();
        boolean tile = item.lineType() == null
            || WastageCalculator.LINE_TYPE_TILE.equals(item.lineType());
        if (tile) {
            if (WastageCalculator.PRICE_MODE_SPECIAL_SQM.equals(priceMode)) {
                return "พิเศษ";
            }
            if (WastageCalculator.PRICE_MODE_DIRECT_NET.equals(priceMode)) {
                boolean differs = item.unitPrice() != null && item.netUnitPrice() != null
                    && item.unitPrice().compareTo(item.netUnitPrice()) != 0;
                return differs ? "พิเศษ" : "Net";
            }
        }
        return (pct == null || pct.signum() == 0) ? "Net" : formatPct(pct) + "%";
    }

    // ── remarks (8 lines) — docs/sales/quotation-v2-plan.md "Printed lines"; 4/5/6/8 are the template's own
    // fixed text (two rows concatenated where the template splits a sentence across a "head" row
    // and an unnumbered continuation row). ─────────────────────────────────────────────────────

    private static final String LINE3_FALLBACK =
        "3.ขณะนี้โรงงานผู้ผลิตประเทศอิตาลีมีสินค้าในสต็อก ระยะเวลานำเข้าประมาณ 90 วัน";
    private static final String LINE4 =
        "4.ขนาดของกระเบื้องจริง จะแตกต่างจากขนาดที่ระบุในใบเสนอราคา ได้เล็กน้อย ตามมาตรฐาน ISO และ มอก.";
    private static final String LINE5 =
        "5.สีและลวดลายของกระเบื้อง อาจแตกต่างจากตัวอย่างได้เล็กน้อย  เนื่องจากเป็นสินค้าคนละ LOT การผลิต";
    private static final String LINE6 =
        "6.ทางบริษัทฯ ไม่รับเปลี่ยนหรือคืนสินค้า กรุณาตรวจสอบ ความถูกต้องก่อนสั่งซื้อหรือลงชื่อรับสินค้า";
    private static final String LINE8 = "8.เงื่อนไขประกอบใบเสนอราคา ตามเอกสารแนบ";

    private static List<String> remarkLines(DealQuotationDto quotation) {
        LocalDate offerDate = quotation.offerDate() != null ? quotation.offerDate() : LocalDate.now(BANGKOK);
        int depositPct = quotation.depositPercent() != null ? quotation.depositPercent() : 30;
        String remainderText = "CREDIT".equals(quotation.remainderMode())
            ? "เครดิต " + (quotation.creditDays() != null ? quotation.creditDays() : 0) + " วัน"
            : "ขอรับก่อนส่งมอบสินค้าหรือเมื่อส่งมอบสินค้า";
        int validityDays = quotation.validityDays() != null ? quotation.validityDays() : 30;

        List<String> lines = new ArrayList<>();
        lines.add("1.จำนวนที่เสนอข้างต้นเป็นจำนวนที่ได้รับมาเมื่อวันที่  " + shortThaiDate(offerDate));
        lines.add("2.บริษัทฯ ขอรับมัดจำ " + depositPct + "% เมื่อสั่งซื้อสินค้า ส่วนที่เหลือ" + remainderText);
        lines.add(leadTimeLine(quotation.items()));
        lines.add(LINE4);
        lines.add(LINE5);
        lines.add(LINE6);
        lines.add("7.กำหนดยืนยันราคา " + validityDays + " วัน นับจากวันที่ในใบเสนอราคา");
        lines.add(LINE8);
        return lines;
    }

    /** {@code "รายการที่ 1-2 ระยะเวลานำเข้า 75-90 วัน  รายการที่ 3 ระยะเวลานำเข้า 30-45 วัน"} —
     * consecutive item numbers sharing the same (min, max) lead time are grouped; items with no
     * lead time are omitted; when nothing has one, the template's original line 3 stands. */
    private static String leadTimeLine(List<DealQuotationItemDto> items) {
        List<DealQuotationItemDto> ordered = items.stream()
            .sorted((a, b) -> Integer.compare(a.seq(), b.seq())).toList();
        List<String> groups = new ArrayList<>();
        Integer groupMin = null;
        Integer groupMax = null;
        Integer groupFirstSeq = null;
        Integer groupLastSeq = null;
        for (DealQuotationItemDto item : ordered) {
            Integer min = item.leadTimeMinDays();
            Integer max = item.leadTimeMaxDays();
            boolean hasLeadTime = min != null && max != null;
            boolean continuesGroup = hasLeadTime && groupMin != null
                && min.equals(groupMin) && max.equals(groupMax) && item.seq() == groupLastSeq + 1;
            if (continuesGroup) {
                groupLastSeq = item.seq();
            } else {
                flushGroup(groups, groupMin, groupMax, groupFirstSeq, groupLastSeq);
                if (hasLeadTime) {
                    groupMin = min;
                    groupMax = max;
                    groupFirstSeq = item.seq();
                    groupLastSeq = item.seq();
                } else {
                    groupMin = null;
                    groupMax = null;
                    groupFirstSeq = null;
                    groupLastSeq = null;
                }
            }
        }
        flushGroup(groups, groupMin, groupMax, groupFirstSeq, groupLastSeq);

        return groups.isEmpty() ? LINE3_FALLBACK : "3.กำหนดส่งมอบสินค้า : " + String.join("  ", groups);
    }

    private static void flushGroup(List<String> groups, Integer min, Integer max, Integer first, Integer last) {
        if (min == null) {
            return;
        }
        String range = first.equals(last) ? String.valueOf(first) : first + "-" + last;
        groups.add("รายการที่ " + range + " ระยะเวลานำเข้า " + min + "-" + max + " วัน");
    }

    // LOW: zero-pad dd/mm ("01/09/2569", not "1/9/2569") -- same fix as
    // QuotationRenderer#shortThaiDate (its own separate copy, for the legacy remark-line path).
    private static String shortThaiDate(LocalDate d) {
        return String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear() + 543);
    }

    private static String formatPct(BigDecimal value) {
        // Not thread-safe -- DecimalFormat never shared, mirrors DealQuotationLines' own discipline.
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(value);
    }

    private static LocalDate bangkokDate(java.time.Instant instant) {
        return instant == null ? null : instant.atZone(BANGKOK).toLocalDate();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
