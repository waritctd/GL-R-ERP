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

        boolean english = WastageCalculator.DOCUMENT_LANGUAGE_EN.equals(quotation.documentLanguage());

        // The rep's own name follows the document too: her English sample prints
        // "Sales/Jannet T.080-7767707", the same shape as the Thai "Sales/เจนเนต".
        String salesLine = "Sales/" + nullSafe(displayName(quotation.salesRepName(), quotation.salesRepNameEn(), english))
            + " T." + nullSafe(quotation.salesRepPhone());

        String attnLine;
        String phoneLine;
        if (english) {
            // F-SM-008 header block, read off QN6900902-6: the template's A5 label cell becomes
            // "Attn :" (see QuotationRenderer#writeEnglishHeaderLabels) and B5 carries the contact
            // and the customer; B6 carries "Address : ...", "E : ..." and the telephone as ONE
            // line, because the template has exactly TWO free rows here and her samples print
            // three labelled lines. Flagged in the PR: with a genuine F-SM-008.xls each would get
            // its own row.
            String contactPart = !blank(quotation.contactName()) ? quotation.contactName().trim() + "   /   " : "";
            attnLine = contactPart + nullSafe(quotation.customerName());
            List<String> parts = new ArrayList<>();
            if (!blank(quotation.customerAddress())) {
                parts.add("Address : " + quotation.customerAddress().trim().replace('\n', ' '));
            }
            if (!blank(quotation.contactEmail())) {
                parts.add("E : " + quotation.contactEmail().trim());
            }
            if (!blank(quotation.customerPhone())) {
                parts.add("Tel. " + quotation.customerPhone().trim());
            }
            phoneLine = String.join("   ", parts);
        } else {
            String contactPart = !blank(quotation.contactName())
                ? "คุณ" + quotation.contactName().trim() + "   /   " : "";
            String taxIdPart = !blank(quotation.customerTaxId())
                ? "   เลขที่ผู้เสียภาษี : " + quotation.customerTaxId().trim() : "";
            attnLine = contactPart + nullSafe(quotation.customerName()) + taxIdPart;
            phoneLine = !blank(quotation.customerPhone()) ? "โทร. " + quotation.customerPhone().trim() : "";
        }

        List<RenderItem> items = quotation.items().stream()
            .sorted((a, b) -> Integer.compare(a.seq(), b.seq()))
            .map(item -> toRenderItem(item, quotation.priceMode()))
            .toList();

        // Owner feedback pass 1 (2026-09-10): slot 4 = the ผู้สั่งซื้อ snapshot (F2); the dates row =
        // created / submitted / approved (F4), each only once it exists -- a DRAFT prints only
        // ผู้พิมพ์'s, and ผู้สั่งซื้อ's stays dotted for the customer's pen.
        // v3b: on an ENGLISH document each signatory prints its hr.employee first_name_en/
        // last_name_en, FALLING BACK to the Thai name when those are blank — an empty signature
        // slot on a customer-facing document is worse than a Thai name on an English page. The
        // ผู้สั่งซื้อ slot is the CUSTOMER's contact, not an employee: there is no English name
        // stored for it anywhere, so it prints the snapshot as typed in either language.
        Signatories signatories = new Signatories(
            displayName(quotation.createdByName(), quotation.createdByNameEn(), english),
            displayName(quotation.salesRepName(), quotation.salesRepNameEn(), english),
            displayName(quotation.approvedByName(), quotation.approvedByNameEn(), english),
            blank(quotation.contactName()) ? null : quotation.contactName().trim(),
            approverSignaturePng, approverSignatureMime,
            bangkokDate(quotation.createdAt()), bangkokDate(quotation.submittedAt()), bangkokDate(quotation.approvedAt()));

        return new QuotationRenderModel(
            issueDate, quotation.number(), quotation.deptCode(), quotation.unitCode(), salesLine,
            attnLine, phoneLine, quotation.projectName(), items,
            english ? englishRemarkLines(quotation) : remarkLines(quotation), signatories, true,
            english ? WastageCalculator.DOCUMENT_LANGUAGE_EN : WastageCalculator.DOCUMENT_LANGUAGE_TH,
            quotation.currency());
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

    // ── v3b: the ENGLISH remark block (F-SM-008) ─────────────────────────────────────────────

    /**
     * The English หมายเหตุ block, modelled on the owner's QN6900902-6 and QN6900933.
     *
     * <p>⚠️ <b>A LIST OF LINES, deliberately — not a template with computed numbering.</b> Her own
     * numbering is irregular in BOTH samples and irregular in DIFFERENT ways: QN6900902-6 runs
     * 1, 2, 3, (two unnumbered bank lines), SWIFT, 5, 6, 7 — <b>there is no 4 at all</b>; QN6900933
     * runs 1, 2, 3, (bank lines), SWIFT, 4, 5. Any code that numbered these itself would print a
     * document that matches neither. So the numbers are part of the line TEXT, the bank block is
     * simply unnumbered lines like hers, and a later "renumber them properly" ruling is an edit to
     * these strings rather than a change to any logic. Flagged in the PR for her confirmation.
     *
     * <p>Exactly 8 lines, which is {@code QuotationRenderer#REMARK_HEAD_ROWS.length} — the count
     * that triggers the v2 compaction path (all-8-lines, one merged row each, no continuation
     * rows). Fewer would fall back to the legacy 3-line layout and leave the template's own Thai
     * continuation rows visible on an English page.
     *
     * <p>Lines 1/2/3 and the validity line are COMPUTED from the same header fields the Thai block
     * uses, so an English document reports the same deposit / lead time / validity the Thai one
     * would. The bank block is fixed text.
     */
    private static List<String> englishRemarkLines(DealQuotationDto quotation) {
        LocalDate offerDate = quotation.offerDate() != null ? quotation.offerDate() : LocalDate.now(BANGKOK);
        int depositPct = quotation.depositPercent() != null ? quotation.depositPercent() : 30;
        String remainderText = "CREDIT".equals(quotation.remainderMode())
            ? "the balance on " + (quotation.creditDays() != null ? quotation.creditDays() : 0) + " days credit"
            : "the balance before or upon delivery";
        int validityDays = quotation.validityDays() != null ? quotation.validityDays() : 30;

        List<String> lines = new ArrayList<>();
        lines.add("1.The quantities above are as received on " + shortEnglishDate(offerDate)
            + ". Please re-confirm the actual quantities with your installer before ordering.");
        lines.add("2.A deposit of " + depositPct + "% is required upon order confirmation, "
            + remainderText + ".");
        lines.add(englishLeadTimeLine(quotation.items()));
        lines.add(BANK_BLOCK_PLACEHOLDER_LINE);
        lines.add("5.Price validity : " + validityDays + " days from the date of this quotation.");
        lines.add("6.Actual tile sizes may vary slightly from the sizes stated above, within ISO "
            + "and TIS tolerances.");
        lines.add("7.Colours and patterns may vary slightly from the samples, as goods come from "
            + "different production lots.");
        lines.add("8.Goods sold are not returnable or exchangeable. Please check the order "
            + "carefully before confirming or signing for delivery.");
        return lines;
    }

    /**
     * ⚠️ <b>THE BANK BLOCK IS DELIBERATELY NOT TRANSCRIBED — ask the owner.</b> Both of her English
     * samples carry an unnumbered three-line bank block (beneficiary / bank + branch / SWIFT) after
     * remark 3, and it is the one part of this document a customer would actually wire money
     * against. The spec does not quote those particulars and this repository holds them nowhere, so
     * inventing a plausible-looking account number, bank branch or SWIFT code would put fabricated
     * banking details on a customer-facing document — the one failure mode here that is not
     * recoverable by re-issuing the quotation.
     *
     * <p>This line stands in its place and is true as written. When the owner supplies the real
     * block, replace this single constant with the three lines and widen
     * {@link #englishRemarkLines} accordingly (it is capped at
     * {@code QuotationRenderer#REMARK_HEAD_ROWS.length} = 8 rows, so two of the numbered lines
     * below would need to merge, exactly as her own samples merge theirs). Flagged at the top of
     * the PR's risks.
     */
    private static final String BANK_BLOCK_PLACEHOLDER_LINE =
        "4.Payment by telegraphic transfer. Full bank details are issued with the proforma invoice.";

    private static final String EN_LINE3_FALLBACK =
        "3.Goods are in stock at the factory in Italy; shipping time is approximately 90 days.";

    /** The English twin of {@link #leadTimeLine} — same grouping, same source data, English words.
     * Kept as its own method rather than parameterising the Thai one: the two differ in every
     * literal, and a shared method with four language ternaries reads worse than two flat ones. */
    private static String englishLeadTimeLine(List<DealQuotationItemDto> items) {
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
                flushEnglishGroup(groups, groupMin, groupMax, groupFirstSeq, groupLastSeq);
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
        flushEnglishGroup(groups, groupMin, groupMax, groupFirstSeq, groupLastSeq);
        return groups.isEmpty() ? EN_LINE3_FALLBACK : "3.Delivery : " + String.join("  ", groups);
    }

    private static void flushEnglishGroup(List<String> groups, Integer min, Integer max,
                                          Integer first, Integer last) {
        if (min == null) {
            return;
        }
        String range = first.equals(last) ? "item " + first : "items " + first + "-" + last;
        groups.add(range + " approximately " + min + "-" + max + " days");
    }

    /**
     * The signature/sales name for this document: the ENGLISH name on an English document, the
     * Thai one otherwise — and the Thai one anyway when the employee has no English name recorded,
     * because a blank signature slot on a customer-facing document is worse than a Thai name on an
     * English page. Owner-visible consequence, stated in the PR: an employee with no
     * {@code first_name_en} prints in Thai on the English form until HR fills it in.
     */
    static String displayName(String thai, String english, boolean useEnglish) {
        if (useEnglish && !blank(english)) {
            return english.trim();
        }
        return thai;
    }

    // LOW: zero-pad dd/mm ("01/09/2569", not "1/9/2569") -- same fix as
    // QuotationRenderer#shortThaiDate (its own separate copy, for the legacy remark-line path).
    private static String shortThaiDate(LocalDate d) {
        return String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear() + 543);
    }

    /** ⚠️ The English remark date. {@code dd/MM/yyyy} — a <b>CE</b> year, where
     * {@link #shortThaiDate} adds 543 for the Buddhist era. Getting this wrong is the single
     * easiest mistake on the English form (the owner's spec says so in as many words), so the two
     * live next to each other here rather than one being reused with a flag. */
    private static String shortEnglishDate(LocalDate d) {
        return String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
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
