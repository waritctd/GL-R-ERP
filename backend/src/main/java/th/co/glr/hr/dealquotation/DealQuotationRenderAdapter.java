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
        // B4 date = approved date; drafts (not yet approved) print today — docs/sales/quotation-v2-plan.md
        // "Printed lines" header section.
        LocalDate issueDate = quotation.approvedAt() != null
            ? quotation.approvedAt().atZone(BANGKOK).toLocalDate()
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
            .map(DealQuotationRenderAdapter::toRenderItem)
            .toList();

        Signatories signatories = new Signatories(
            quotation.createdByName(), quotation.salesRepName(), quotation.approvedByName(),
            approverSignaturePng, approverSignatureMime);

        return new QuotationRenderModel(
            issueDate, quotation.number(), quotation.deptCode(), quotation.unitCode(), salesLine,
            attnLine, phoneLine, quotation.projectName(), items, remarkLines(quotation), signatories, true);
    }

    private static RenderItem toRenderItem(DealQuotationItemDto item) {
        BigDecimal pct = item.discountPct();
        String discountLabel = (pct == null || pct.signum() == 0) ? "Net" : formatPct(pct) + "%";
        // layout-spec §2: sizeLine is null (not blank) when the item has no thickness — its size
        // already went inline on descriptionLine instead — so the printed line list must OMIT the
        // row entirely rather than emit a blank one.
        List<String> lines = new ArrayList<>();
        lines.add(item.descriptionLine());
        if (item.sizeLine() != null) {
            lines.add(item.sizeLine());
        }
        lines.add(item.calculationLine());
        return new RenderItem(
            item.locationLabel(), lines,
            BigDecimal.valueOf(item.piecesFinal()), "แผ่น", item.unitPrice(), discountLabel,
            item.netUnitPrice(), item.lineAmount());
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

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
