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
 * <p>A pure function: no DB access. The approver's signature bytes are a DB read (the V175
 * snapshot frozen at approval), so the caller ({@code DealQuotationService}) resolves them and
 * passes the bytes in.
 */
public final class DealQuotationRenderAdapter {
    private DealQuotationRenderAdapter() {}

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    /** Overload for callers with no configured bank block — the English document then prints the
     * proforma-invoice line instead. Kept so tests and any hand-wired caller need not thread a
     * config value they do not care about. */
    public static QuotationRenderModel toRenderModel(DealQuotationDto quotation, byte[] approverSignaturePng,
                                                      String approverSignatureMime) {
        return toRenderModel(quotation, approverSignaturePng, approverSignatureMime, List.of());
    }

    /**
     * @param bankBlockLines the unnumbered bank block for an ENGLISH document, from
     *     {@code app.quotation.bank-block-line1..3}. EMPTY or partially filled prints the
     *     proforma-invoice line instead — half a set of wire instructions is worse than none, so
     *     there is no "best effort" here. Ignored entirely on a Thai document, whose หมายเหตุ block
     *     carries no bank details.
     */
    public static QuotationRenderModel toRenderModel(DealQuotationDto quotation, byte[] approverSignaturePng,
                                                      String approverSignatureMime,
                                                      List<String> bankBlockLines) {
        return toRenderModel(quotation, approverSignaturePng, approverSignatureMime, bankBlockLines,
            java.util.Map.of());
    }

    /**
     * GLA-75: {@code itemPictures} is item id → the stored picture (V170), resolved by the caller
     * for the same reason the signature bytes are — this class stays a pure function. An item
     * whose DTO says {@code hasPicture} but has no entry here (a race with a removal) simply
     * prints without one; an item with an entry but {@code hasPicture=false} is never given one.
     */
    public static QuotationRenderModel toRenderModel(DealQuotationDto quotation, byte[] approverSignaturePng,
                                                      String approverSignatureMime,
                                                      List<String> bankBlockLines,
                                                      java.util.Map<Long, DealQuotationRepository.PictureImage> itemPictures) {
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
        // V179 (owner feedback #4, 2026-09-14): salesRepDisplayId overrides BOTH the name and the
        // phone here — see #salesRepDisplayNameOrReal/#salesRepDisplayPhoneOrReal.
        String salesLine = "Sales/" + nullSafe(salesRepDisplayNameOrReal(quotation, english))
            + " T." + nullSafe(salesRepDisplayPhoneOrReal(quotation));

        String attnLine;
        String phoneLine;
        if (english) {
            // F-SM-008 header block, read off QN6900902-6: the template's A5 label cell becomes
            // "Attn :" (see QuotationRenderer#writeEnglishHeaderLabels) and B5 carries the contact
            // and the customer; B6 carries "Address : ...", "E : ..." and the telephone as ONE
            // line, because the template has exactly TWO free rows here and her samples print
            // three labelled lines. Flagged in the PR: with a genuine F-SM-008.xls each would get
            // its own row.
            // Owner feedback item #1 (2026-09-14): the same dedupe as the Thai branch below, minus
            // the คุณ decision (the English form never prefixes it at all).
            String contactPart = printContactPart(quotation.contactName(), quotation.customerName())
                ? quotation.contactName().trim() + "   /   " : "";
            attnLine = contactPart + nullSafe(quotation.customerName());
            List<String> parts = new ArrayList<>();
            if (!blank(quotation.customerAddress())) {
                parts.add("Address : " + quotation.customerAddress().trim().replace('\n', ' '));
            }
            if (!blank(quotation.contactEmail())) {
                parts.add("E : " + quotation.contactEmail().trim());
            }
            // Fix (2026-09-15): #effectiveCustomerPhone -- see its Javadoc. #stripPhoneLabel — the
            // stored value may open with its OWN label; see its Javadoc.
            String phone = effectiveCustomerPhone(quotation);
            if (!blank(stripPhoneLabel(phone))) {
                parts.add("Tel. " + stripPhoneLabel(phone));
            }
            phoneLine = String.join("   ", parts);
        } else {
            // Owner feedback item #1 (2026-09-14): production printed "เรียน คุณบริษัท นันทวัน
            // จำกัด   /   บริษัท นันทวัน จำกัด" because the "contact" snapshot on that deal WAS the
            // company name (no separate human contact was ever recorded, so the write path fell
            // back to the customer name) and this branch prefixed "คุณ" and duplicated it
            // unconditionally. Two independent fixes, both in #printContactPart/#looksLikeOrganisation:
            // (1) when the contact snapshot equals the customer name (normalised), the contact part
            // is omitted entirely rather than printed twice; (2) "คุณ" is prefixed only when the
            // surviving contact name does not itself look like an organisation (a company recorded
            // as its OWN contact, distinct from the customer name, still should not read "คุณ").
            //
            // Fix (2026-09-15): production printed "เรียน คุณคุณปิยพร เมืองจีน" for contact_name =
            // "คุณปิยพร เมืองจีน" -- the imported/typed value already carried its own honorific, and
            // this branch prefixed a second one unconditionally whenever the name was not an
            // organisation. #hasThaiHonorificPrefix adds the same "already has one" check
            // #looksLikeOrganisation already does for a company name.
            String contactPart = printContactPart(quotation.contactName(), quotation.customerName())
                ? (looksLikeOrganisation(quotation.contactName())
                        || hasThaiHonorificPrefix(quotation.contactName()) ? "" : "คุณ")
                    + quotation.contactName().trim() + "   /   "
                : "";
            String taxIdPart = !blank(quotation.customerTaxId())
                ? "   เลขที่ผู้เสียภาษี : " + quotation.customerTaxId().trim() : "";
            attnLine = contactPart + nullSafe(quotation.customerName()) + taxIdPart;
            // Bug fix: this branch built "เรียน"/"โทร." but never read quotation.customerAddress()
            // at all, so every Thai-form quotation printed with no address line even though the
            // address is captured in the UI and denormalized correctly onto the quotation record —
            // it was simply dropped one step before printing. The Thai template (F-SM-002) reserves
            // only ATTN_ROW/PHONE_ROW for this header block and has no free row of its own for an
            // address (unlike QuotationRenderer's English branch just above, whose B6 already folds
            // Address/E/Tel into one merged B6:G6 cell for the same reason) — so the address is
            // folded into that same PHONE_ROW line here rather than left unprinted.
            List<String> parts = new ArrayList<>();
            if (!blank(quotation.customerAddress())) {
                parts.add("ที่อยู่ " + quotation.customerAddress().trim().replace('\n', ' '));
            }
            // Fix (2026-09-15): production example customer_phone=null, contact_phone=
            // "062-328-7555", contact_email="qs.twcfurline@gmail.com" printed NEITHER -- this
            // branch only ever read customerPhone (never contactPhone, which existed on the DTO
            // unused) and never read contactEmail at all. #effectiveCustomerPhone falls back to the
            // deal's own contact phone when the customer master carries none, and the e-mail joins
            // the same ที่อยู่/โทร. line the English branch above already folds Address/E/Tel into
            // (this template has no free row of its own for either — see the bug-fix comment just
            // above for why the address already lives here).
            if (!blank(quotation.contactEmail())) {
                parts.add("อีเมล " + quotation.contactEmail().trim());
            }
            // QT-2026-0032-1 (2026-09-15): B6 printed "โทร. โทร 02 314 354-2" — the customer master
            // row's own phone value opens with the label. See #stripPhoneLabel's Javadoc.
            String phone = effectiveCustomerPhone(quotation);
            if (!blank(stripPhoneLabel(phone))) {
                parts.add("โทร. " + stripPhoneLabel(phone));
            }
            phoneLine = String.join("   ", parts);
        }

        List<RenderItem> items = quotation.items().stream()
            .sorted((a, b) -> Integer.compare(a.seq(), b.seq()))
            .map(item -> toRenderItem(item, quotation.priceMode(), english, itemPictures))
            .toList();

        // Owner feedback pass 1 (2026-09-10): slot 4 = the ผู้สั่งซื้อ snapshot (F2); the dates row =
        // created / submitted / approved (F4), each only once it exists -- a DRAFT prints only
        // ผู้พิมพ์'s, and ผู้สั่งซื้อ's stays dotted for the customer's pen.
        // v3b: on an ENGLISH document each signatory prints its hr.employee first_name_en/
        // last_name_en, FALLING BACK to the Thai name when those are blank — an empty signature
        // slot on a customer-facing document is worse than a Thai name on an English page. The
        // ผู้สั่งซื้อ slot is the CUSTOMER's contact, not an employee: there is no English name
        // stored for it anywhere, so it prints the snapshot as typed in either language.
        // V179 (owner feedback #4, 2026-09-14): printedBy/salesRep each fall back to the display
        // override when the corresponding *DisplayId is set — see #printedByName/
        // #salesRepDisplayNameOrReal. approvedBy is untouched: this feature never covers the
        // approver slot.
        Signatories signatories = new Signatories(
            printedByName(quotation, english),
            salesRepDisplayNameOrReal(quotation, english),
            displayName(quotation.approvedByName(), quotation.approvedByNameEn(), english),
            orderedByName(quotation),
            approverSignaturePng, approverSignatureMime,
            bangkokDate(quotation.createdAt()), bangkokDate(quotation.submittedAt()), bangkokDate(quotation.approvedAt()));

        return new QuotationRenderModel(
            issueDate, quotation.number(), quotation.deptCode(), quotation.unitCode(), salesLine,
            attnLine, phoneLine, quotation.projectName(), items,
            english ? englishRemarkLines(quotation, bankBlockLines) : remarkLines(quotation), signatories, true,
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
    private static RenderItem toRenderItem(DealQuotationItemDto item, String priceMode, boolean english,
                                           java.util.Map<Long, DealQuotationRepository.PictureImage> itemPictures) {
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
        DealQuotationRepository.PictureImage stored = item.hasPicture() && itemPictures != null
            ? itemPictures.get(item.id()) : null;
        QuotationRenderModel.ItemPicture picture = stored == null ? null
            : new QuotationRenderModel.ItemPicture(stored.image(), stored.mimeType(), item.picturePlacement());
        // Owner decision 2026-09-13: the English per-sqm Qty prints to 2dp (72.00, 56.43).
        boolean perSqmRow = english && WastageCalculator.PRICE_MODE_SPECIAL_SQM.equals(priceMode)
            && DealQuotationLines.TILE_UNIT_SQM.equals(item.unit())
            && (item.lineType() == null || WastageCalculator.LINE_TYPE_TILE.equals(item.lineType()));
        return new RenderItem(
            item.locationLabel(), lines,
            item.quantity(), printedUnit(item, english), item.unitPrice(), discountLabel(item, priceMode, english),
            item.netUnitPrice(), item.lineAmount(), picture, perSqmRow ? QTY_FORMAT_SQM : null);
    }

    /**
     * The หน่วย cell. The distinction between {@code null} and {@code ""} is load-bearing here and
     * must not be collapsed: {@code QuotationRenderer#fillItemMainRow} treats a NULL unit as "use
     * the default แผ่น" and an EMPTY unit as "print nothing". An ADJUSTMENT row stores no unit at
     * all (raw_unit NULL), and the owner's ส่วนลดพิเศษ line prints a BLANK หน่วย — so a non-tile
     * row's missing unit is mapped to "" here, never passed through as null.
     */
    static final String QTY_FORMAT_SQM = "#,##0.00";

    private static String printedUnit(DealQuotationItemDto item, boolean english) {
        boolean tile = item.lineType() == null
            || WastageCalculator.LINE_TYPE_TILE.equals(item.lineType());
        if (item.unit() != null) {
            return item.unit();
        }
        // Owner ruling 2026-09-13 (1): a tile with no unit prints "PCS" on the English form.
        return tile ? DealQuotationLines.tileUnit(english
            ? WastageCalculator.DOCUMENT_LANGUAGE_EN : WastageCalculator.DOCUMENT_LANGUAGE_TH) : "";
    }

    /**
     * The ส่วนลด column. Owner feedback pass 3: it reads <b>พิเศษ</b> for a SPECIAL_SQM row, and for
     * a DIRECT_NET row whose net actually differs from the list price; it stays {@code Net} /
     * {@code N%} in NET mode, exactly as before.
     *
     * <p>The price mode only governs TILE rows. A PLAIN row follows its OWN discount (normally
     * absent, so "Net") whatever mode the document is in — that is what lets a ราคาพิเศษ document
     * still carry an ordinary freight line.
     *
     * <p>An ADJUSTMENT row prints <b>NOTHING</b>. This used to print "Net", on the reasoning that its
     * ราคา and คงเหลือ are literally equal — a plausible inference that the owner's own document
     * contradicts. QN6900704-2's ส่วนลดพิเศษ row leaves the ส่วนลด cell EMPTY, exactly as it leaves
     * หน่วย empty, because neither column means anything for a row that IS the discount rather than a
     * row carrying one. Spotted by rendering her document back and comparing it against the original;
     * the document is the authority here, not the inference.
     */
    private static String discountLabel(DealQuotationItemDto item, String priceMode, boolean english) {
        // English: "Special", never "พิเศษ" — the English form carries no Thai in its item table.
        // The SAME word QuotationDocumentView prints on screen (quotationMeta#documentDiscountLabel),
        // so the page and the PDF agree.
        String special = english ? "Special" : "พิเศษ";
        BigDecimal pct = item.discountPct();
        if (WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(item.lineType())) {
            return "";
        }
        boolean tile = item.lineType() == null
            || WastageCalculator.LINE_TYPE_TILE.equals(item.lineType());
        if (tile) {
            if (WastageCalculator.PRICE_MODE_SPECIAL_SQM.equals(priceMode)) {
                // Owner decision 2026-09-13: an English per-sqm row prints "Net" — the USD/sqm IS
                // the net, as her QN6900933 shows. Thai keeps พิเศษ.
                return english ? "Net" : special;
            }
            if (WastageCalculator.PRICE_MODE_DIRECT_NET.equals(priceMode)) {
                boolean differs = item.unitPrice() != null && item.netUnitPrice() != null
                    && item.unitPrice().compareTo(item.netUnitPrice()) != 0;
                return differs ? special : "Net";
            }
        }
        return (pct == null || pct.signum() == 0) ? "Net" : formatPct(pct) + "%";
    }

    // ── V178 — "has special pricing" (owner ruling 2026-09-14). The ONE shared gate for whether
    // remark 7's DATE variant may print AND whether DealQuotationService may even SAVE a quotation
    // in DATE mode — the service calls the NewItem overload below (before anything is written),
    // this class calls the DTO overload (after a read), and both funnel into #hasSpecialPricingCore
    // so the rule is defined exactly once. A quotation has special pricing when ANY of:
    //   a. priceMode SPECIAL_SQM (ราคาพิเศษ บาท/ตร.ม.) and at least one TILE row exists;
    //   b. priceMode DIRECT_NET (ราคาสุทธิต่อแผ่น) and some TILE row's net differs from its list
    //      price (the row whose ส่วนลด cell prints พิเศษ, per #discountCellText above);
    //   c. some TILE row (under any OTHER price mode, i.e. NET) has discountPct > 0;
    //   d. some PLAIN row has discountPct > 0;
    //   e. an ADJUSTMENT (ส่วนลดพิเศษ) row exists.
    // Decided from the DATA, never from the printed discount label: an English per-sqm SPECIAL_SQM
    // row prints "Net" (see #discountCellText) but rule (a) still counts it as special pricing.
    // TILE = lineType null or LINE_TYPE_TILE.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** {@code true} when the SAVED quotation has special pricing. */
    public static boolean hasSpecialPricing(DealQuotationDto quotation) {
        return hasSpecialPricingCore(quotation.priceMode(), quotation.items().stream()
            .map(i -> new PricingRow(i.lineType(), i.unitPrice(), i.discountPct(), i.netUnitPrice()))
            .toList());
    }

    /** Same rule, over the rows about to be WRITTEN — {@code DealQuotationService#create}/{@code
     * #update} call this so DATE mode is refused BEFORE anything is saved, rather than needing a
     * round trip through a persisted read. */
    public static boolean hasSpecialPricingForNewItems(String priceMode,
                                                        List<DealQuotationRepository.NewItem> items) {
        return hasSpecialPricingCore(priceMode, items.stream()
            .map(i -> new PricingRow(i.lineType(), i.unitPrice(), i.discountPct(), i.netUnitPrice()))
            .toList());
    }

    private record PricingRow(String lineType, BigDecimal unitPrice, BigDecimal discountPct,
                              BigDecimal netUnitPrice) {}

    private static boolean hasSpecialPricingCore(String priceMode, List<PricingRow> rows) {
        boolean specialSqm = WastageCalculator.PRICE_MODE_SPECIAL_SQM.equals(priceMode);
        boolean directNet = WastageCalculator.PRICE_MODE_DIRECT_NET.equals(priceMode);
        for (PricingRow row : rows) {
            if (WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(row.lineType())) {
                return true; // (e)
            }
            if (WastageCalculator.LINE_TYPE_PLAIN.equals(row.lineType())) {
                if (isPositive(row.discountPct())) {
                    return true; // (d)
                }
                continue;
            }
            // TILE (lineType null or LINE_TYPE_TILE)
            if (specialSqm) {
                return true; // (a)
            }
            if (directNet) {
                if (row.netUnitPrice() != null && row.unitPrice() != null
                    && row.netUnitPrice().compareTo(row.unitPrice()) != 0) {
                    return true; // (b)
                }
            } else if (isPositive(row.discountPct())) {
                return true; // (c)
            }
        }
        return false;
    }

    private static boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    // ── remarks (8 lines) — docs/sales/quotation-v2-plan.md "Printed lines"; 4/5/6/8 are the template's own
    // fixed text (two rows concatenated where the template splits a sentence across a "head" row
    // and an unnumbered continuation row). ─────────────────────────────────────────────────────

    // Owner feedback #7 (2026-09-14) REVERSES pass 3 (2026-09-11)'s "จีน 30-45 วัน / ไทย มีในสตอค
    // 3-7 วัน" default: she now wants NO country and NO stock wording printed at all when nothing
    // on the document carries a lead time — submit() below makes that case rare (every TILE row
    // must now carry one), but a DRAFT preview or a re-render of an older document can still hit
    // it. Same header word ("ระยะเวลานำเข้า") the per-item line below now uses too, so a document
    // that mixes a priced item with an unpriced legacy row never reads as two different features.
    //
    // Superseded, and now unreachable (Opus review nit, 2026-09-15): this used to say "the
    // fallback is now a visible blank the rep has to notice and fill in" -- true for one day, but
    // a LATER same-day owner request ("if ระยะเวลานำเข้า is not chosen remove that from the
    // หมายเหตุ") replaced "print a blank" with "drop the line entirely" -- see
    // #dropLeadTimeLineAndRenumber, which fires on exactly the condition that makes #leadTimeLine
    // return this constant, so the value below is computed and then always discarded by
    // remarkLines()'s caller. Kept for the same reason EN_LINE3_FALLBACK is -- see its Javadoc.
    private static final String LINE3_FALLBACK = "3.ระยะเวลานำเข้า : ประมาณ ...... วัน";
    private static final String LINE4 =
        "4.ขนาดของกระเบื้องจริง จะแตกต่างจากขนาดที่ระบุในใบเสนอราคา ได้เล็กน้อย ตามมาตรฐาน ISO และ มอก.";
    private static final String LINE5 =
        "5.สีและลวดลายของกระเบื้อง อาจแตกต่างจากตัวอย่างได้เล็กน้อย  เนื่องจากเป็นสินค้าคนละ LOT การผลิต";
    private static final String LINE6 =
        "6.ทางบริษัทฯ ไม่รับเปลี่ยนหรือคืนสินค้า กรุณาตรวจสอบ ความถูกต้องก่อนสั่งซื้อหรือลงชื่อรับสินค้า";
    private static final String LINE8 = "8.เงื่อนไขประกอบใบเสนอราคา ตามเอกสารแนบ";

    /** Remark 7's DATE-mode variant (owner feedback 2026-09-14, verbatim, including the number
     * prefix and the ONE ASCII space before the date): "ราคาพิเศษสำหรับการสั่งซื้อและชำระมัดจำ
     * ภายในวันที่ ..../..../....." */
    private static final String LINE7_DATE_PREFIX =
        "7.ราคาพิเศษสำหรับการสั่งซื้อและชำระมัดจำภายในวันที่ ";

    /**
     * Note 2, remark slot -- the ONE numbered line that names the deposit. Production complaint
     * (2026-09-15): a document with {@code deposit_percent = 0} still printed "บริษัทฯ ขอรับมัดจำ
     * 0% เมื่อสั่งซื้อสินค้า ส่วนที่เหลือ..." -- a phantom zero-percent deposit demand, since
     * nothing in the old text handled zero as anything other than "some positive percentage".
     *
     * <p>Only an EXPLICIT zero takes this branch. A {@code null} {@code depositPercent} still
     * defaults to 30 above ({@code depositPct}), UNCHANGED -- out of scope for this fix, per the
     * task that requested it, and mentioned here so a later reader does not conflate the two.
     *
     * <p>When there truly is no deposit, note 2 states the FULL-amount payment terms directly
     * instead of naming a deposit that does not exist -- the remainder text passed in (the
     * CREDIT-days wording, or the before/upon-delivery wording) already covers the SAME two
     * {@code remainderMode} variants this file has ever supported (nothing else is stored in
     * {@code remainder_mode} -- see the DB column's own comment), just now applied to the WHOLE
     * amount rather than "the remainder after the deposit". The "2." slot number is kept exactly
     * as before either way.
     */
    private static String depositLine(int depositPct, String remainderMode, Integer creditDays,
                                       String remainderText) {
        if (depositPct != 0) {
            return "2.บริษัทฯ ขอรับมัดจำ " + depositPct + "% เมื่อสั่งซื้อสินค้า ส่วนที่เหลือ" + remainderText;
        }
        return "CREDIT".equals(remainderMode)
            ? "2.บริษัทฯ ขอรับชำระเต็มจำนวนเป็นเครดิต " + (creditDays != null ? creditDays : 0) + " วัน"
            : "2.บริษัทฯ ขอรับชำระเต็มจำนวนก่อนส่งมอบสินค้าหรือเมื่อส่งมอบสินค้า";
    }

    private static List<String> remarkLines(DealQuotationDto quotation) {
        LocalDate offerDate = quotation.offerDate() != null ? quotation.offerDate() : LocalDate.now(BANGKOK);
        int depositPct = quotation.depositPercent() != null ? quotation.depositPercent() : 30;
        String remainderText = "CREDIT".equals(quotation.remainderMode())
            ? "เครดิต " + (quotation.creditDays() != null ? quotation.creditDays() : 0) + " วัน"
            : "ขอรับก่อนส่งมอบสินค้าหรือเมื่อส่งมอบสินค้า";
        int validityDays = quotation.validityDays() != null ? quotation.validityDays() : 30;
        // Owner feedback 2026-09-14 (V178) — remark 7's second กำหนดยืนยันราคา variant: a rep may
        // name an exact calendar date instead of a day count. DAYS-mode output is byte-identical
        // to before this change; validityUntil is only ever non-null in DATE mode (service-enforced).
        String line7 = WastageCalculator.VALIDITY_MODE_DATE.equals(quotation.validityMode())
            && quotation.validityUntil() != null
            && hasSpecialPricing(quotation)
            ? LINE7_DATE_PREFIX + shortThaiDate(quotation.validityUntil())
            : "7.กำหนดยืนยันราคา " + validityDays + " วัน นับจากวันที่ในใบเสนอราคา";

        List<String> lines = new ArrayList<>();
        lines.add("1.จำนวนที่เสนอข้างต้นเป็นจำนวนที่ได้รับมาเมื่อวันที่  " + shortThaiDate(offerDate));
        lines.add(depositLine(depositPct, quotation.remainderMode(), quotation.creditDays(), remainderText));
        int leadTimeLineIndex = lines.size();
        lines.add(leadTimeLine(quotation.items()));
        lines.add(LINE4);
        lines.add(LINE5);
        lines.add(LINE6);
        lines.add(line7);
        lines.add(LINE8);
        // Owner feedback (2026-09-14): "if ระยะเวลานำเข้า is not chosen remove that from the
        // หมายเหตุ" — when NOTHING on the document carries a lead time (leadTimeLine above would
        // have printed LINE3_FALLBACK), the line is dropped ENTIRELY (not left blank) and every
        // remark after it renumbers down by one. See #dropLeadTimeLineAndRenumber.
        return dropLeadTimeLineAndRenumber(lines, hasAnyLeadTime(quotation.items()), leadTimeLineIndex);
    }

    /** {@code "3.ระยะเวลานำเข้า : รายการที่ 1-2 ประมาณ 75-90 วัน  รายการที่ 3 ประมาณ 30-45 วัน"} —
     * consecutive item numbers sharing the same (min, max) lead time are grouped, ALWAYS in this
     * per-item form (owner feedback #7, 2026-09-14) even for a single group; items with no lead
     * time are omitted; when nothing has one, this returns {@link #LINE3_FALLBACK} -- but the
     * ONLY caller ({@link #remarkLines}) then drops that whole line via
     * {@link #dropLeadTimeLineAndRenumber} rather than keeping the fallback (superseded same-day
     * owner feedback, 2026-09-14 -- see {@link #LINE3_FALLBACK}'s own comment), so in practice this
     * value never reaches a rendered document. */
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

        return hasAnyLeadTime(items) ? "3.ระยะเวลานำเข้า : " + String.join("  ", groups) : LINE3_FALLBACK;
    }

    // ── owner feedback (2026-09-14): "if ระยะเวลานำเข้า is not chosen remove that from the
    // หมายเหตุ" ─────────────────────────────────────────────────────────────────────────────────

    /** {@code true} when ANY item on the document carries a lead time (both
     * {@code leadTimeMinDays}/{@code leadTimeMaxDays} set) — the SAME question
     * {@link #leadTimeLine}/{@link #englishLeadTimeLine}'s own grouping already asks (an item
     * contributes to a group iff it has both fields set), asked ONCE here and shared by both of
     * those methods AND {@link #dropLeadTimeLineAndRenumber}'s callers, so "does the fallback
     * print" and "should the line be dropped" can never disagree. A document made entirely of
     * PLAIN rows (no lead-time fields at all) or a DRAFT preview before any TILE row's lead time
     * has been filled in both answer {@code false} here. */
    private static boolean hasAnyLeadTime(List<DealQuotationItemDto> items) {
        return items.stream().anyMatch(i -> i.leadTimeMinDays() != null && i.leadTimeMaxDays() != null);
    }

    // Matches the leading "N." numbering convention every OTHER remark line (LINE4/5/6/7/8 and
    // their English twins) carries as a literal prefix baked into the string — never a digit that
    // is merely part of the sentence, since none of those constants opens with one for any other
    // reason (checked by reading each literal, not inferred).
    private static final java.util.regex.Pattern LEADING_REMARK_NUMBER =
        java.util.regex.Pattern.compile("^(\\d+)\\.");

    /**
     * Drops the lead-time line at {@code leadTimeLineIndex} from {@code lines} when
     * {@code hasLeadTime} is {@code false} — ENTIRELY, not blanked — and renumbers every
     * subsequent line's leading {@code "N."} digit down by one, so e.g. old 4→3, 5→4, 6→5, 7→6,
     * 8→7 with NO gap. A line with no leading number (an unnumbered bank-block line on the English
     * form) is left untouched by the renumbering pass — {@link #LEADING_REMARK_NUMBER} simply does
     * not match it, so it is never mistaken for one of the numbered remarks.
     *
     * <p>Shared by the Thai {@code remarkLines} builder and both branches of
     * {@code englishRemarkLines} — each passes the FULL (today's) 8-line list and the index of its
     * own lead-time line, so the 8-line construction itself never changes and this is the ONLY
     * place that decides whether/how to shrink it to 7.
     */
    private static List<String> dropLeadTimeLineAndRenumber(List<String> lines, boolean hasLeadTime,
                                                              int leadTimeLineIndex) {
        if (hasLeadTime) {
            return lines;
        }
        List<String> result = new ArrayList<>(lines);
        result.remove(leadTimeLineIndex);
        for (int i = leadTimeLineIndex; i < result.size(); i++) {
            result.set(i, decrementLeadingRemarkNumber(result.get(i)));
        }
        return result;
    }

    private static String decrementLeadingRemarkNumber(String line) {
        java.util.regex.Matcher m = LEADING_REMARK_NUMBER.matcher(line);
        if (!m.find()) {
            return line; // unnumbered (e.g. a bank-block line) -- leave untouched
        }
        int newNumber = Integer.parseInt(m.group(1)) - 1;
        return newNumber + "." + line.substring(m.end());
    }

    private static void flushGroup(List<String> groups, Integer min, Integer max, Integer first, Integer last) {
        if (min == null) {
            return;
        }
        String range = first.equals(last) ? String.valueOf(first) : first + "-" + last;
        // Owner feedback #7 nicety: an exact lead time (min == max) reads "ประมาณ 30 วัน", not the
        // pointless "30-30 วัน" a range formatter would print for it.
        String days = min.equals(max) ? String.valueOf(min) : min + "-" + max;
        groups.add("รายการที่ " + range + " ประมาณ " + days + " วัน");
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
     * would.

     * <p><b>What this prints</b>, which is deliberately NOT her numbering. With the bank block
     * configured: 1, 2, (three unnumbered bank lines, straight after the payment remark as in her
     * samples), 3, 4, 5 — validity and size tolerance share line 4, colour and returns share line 5,
     * because the block costs three of the eight rows. Without it: the original 1–8, with the
     * proforma-invoice line at 4. The bank lines themselves come from configuration, not from this
     * class — see {@code app.quotation.bank-block-line1..3}.
     */
    /** The English twin of {@link #depositLine} -- same zero-deposit rule, same two {@code
     * remainderMode} variants, English words. See that method's Javadoc for the full reasoning;
     * kept as its own method for the same "two flat methods read better than one with language
     * ternaries" reason {@link #englishLeadTimeLine} gives for its own Thai twin. */
    private static String englishDepositLine(int depositPct, String remainderMode, Integer creditDays,
                                              String remainderText) {
        if (depositPct != 0) {
            return "2.A deposit of " + depositPct + "% is required upon order confirmation, " + remainderText + ".";
        }
        return "CREDIT".equals(remainderMode)
            ? "2.Full payment is due on " + (creditDays != null ? creditDays : 0) + " days credit."
            : "2.Full payment is due before or upon delivery.";
    }

    private static List<String> englishRemarkLines(DealQuotationDto quotation, List<String> bankBlockLines) {
        LocalDate offerDate = quotation.offerDate() != null ? quotation.offerDate() : LocalDate.now(BANGKOK);
        int depositPct = quotation.depositPercent() != null ? quotation.depositPercent() : 30;
        String remainderText = "CREDIT".equals(quotation.remainderMode())
            ? "the balance on " + (quotation.creditDays() != null ? quotation.creditDays() : 0) + " days credit"
            : "the balance before or upon delivery";
        int validityDays = quotation.validityDays() != null ? quotation.validityDays() : 30;
        // V178, English twin of the Thai line7 branch above — same guard, same byte-identical
        // DAYS-mode output.
        boolean validityIsDate = WastageCalculator.VALIDITY_MODE_DATE.equals(quotation.validityMode())
            && quotation.validityUntil() != null
            && hasSpecialPricing(quotation);
        String validityUntilEn = validityIsDate ? shortEnglishDate(quotation.validityUntil()) : null;

        // ⚠️ EXACTLY 8 lines either way — QuotationRenderer#REMARK_HEAD_ROWS has 8 slots and fewer
        // falls back to the legacy 3-line layout, which leaves the template's Thai continuation
        // rows visible on an English page. The bank block costs THREE of those 8, so the two
        // layouts below are not the same text with a line swapped: with the block configured, the
        // validity/tolerance and colour/returns remarks each merge into one line to make room.
        // That merging is why this is written as two explicit lists rather than a conditional
        // insert — the alternative silently overflows the box.
        boolean hasBankBlock = bankBlockLines != null && bankBlockLines.size() == 3
            && bankBlockLines.stream().noneMatch(DealQuotationRenderAdapter::blank);

        List<String> lines = new ArrayList<>();
        lines.add("1.The quantities above are as received on " + shortEnglishDate(offerDate)
            + ". Please re-confirm the actual quantities with your installer before ordering.");
        lines.add(englishDepositLine(depositPct, quotation.remainderMode(), quotation.creditDays(), remainderText));
        int leadTimeLineIndex;
        if (hasBankBlock) {
            // The block sits straight after the PAYMENT remark, unnumbered, as it does in both of
            // her samples. Numbering then runs on 3, 4, 5 — NOT her 5, 6, 7: her skipped 4 is a
            // spreadsheet artefact rather than intent, and reproducing it would print a gap a
            // customer reads as a missing term.
            lines.addAll(bankBlockLines);
            leadTimeLineIndex = lines.size();
            lines.add(englishLeadTimeLine(quotation.items()));
            // ⚠️ Each remark is ONE merged B..I cell that NEVER wraps, so an over-long line is not
            // wrapped but CUT at the border. The first version of these two merged lines ran to 145
            // and 151 characters and printed "…within ISO and TIS toler" and "…not returnab" — with
            // every content test green, because the text in the cell was intact. Both are now well
            // inside the 130 that the pre-existing line 1 proves fits in both PDF engines, and
            // DealQuotationEnglishFormTest guards the length of every line in both layouts.
            lines.add(validityIsDate
                ? "4.Special price for orders with deposit paid by " + validityUntilEn
                    + "; sizes may vary slightly within ISO and TIS tolerances."
                : "4.Price validity : " + validityDays + " days from the date of this quotation; "
                    + "sizes may vary slightly within ISO and TIS tolerances.");
            lines.add("5.Colours may vary slightly between production lots. "
                + "Goods sold are not returnable or exchangeable.");
        } else {
            leadTimeLineIndex = lines.size();
            lines.add(englishLeadTimeLine(quotation.items()));
            lines.add(BANK_BLOCK_PLACEHOLDER_LINE);
            lines.add(validityIsDate
                ? "5.Special price for orders with deposit paid by " + validityUntilEn + "."
                : "5.Price validity : " + validityDays + " days from the date of this quotation.");
            lines.add("6.Actual tile sizes may vary slightly from the sizes stated above, within ISO "
                + "and TIS tolerances.");
            lines.add("7.Colours and patterns may vary slightly from the samples, as goods come from "
                + "different production lots.");
            lines.add("8.Goods sold are not returnable or exchangeable. Please check the order "
                + "carefully before confirming or signing for delivery.");
        }
        // Owner feedback (2026-09-14): same drop-and-renumber as the Thai branch, sharing the SAME
        // hasAnyLeadTime question — see #dropLeadTimeLineAndRenumber. leadTimeLineIndex is 5 in the
        // bank-block layout (after the 2 numbered + 3 unnumbered bank lines) and 2 in the no-bank
        // layout; either way the method only ever renumbers a NUMBERED line, so the bank block's
        // own unnumbered lines are untouched.
        return dropLeadTimeLineAndRenumber(lines, hasAnyLeadTime(quotation.items()), leadTimeLineIndex);
    }

    /**
     * The FALLBACK printed at remark 4 when the bank block is not fully configured.
     *
     * <p>History worth keeping: the block was first left out deliberately, because no source in the
     * repository held the particulars and inventing an account number or SWIFT code would have put
     * fabricated wire instructions on a customer-facing document. The owner then supplied it
     * (2026-09-11, "take what's from the example image"), and it now lives in
     * {@code app.quotation.bank-block-line1..3}. This line is what an English document prints when
     * that configuration is missing, partial, or blank — true as written, and never half a block.
     */
    private static final String BANK_BLOCK_PLACEHOLDER_LINE =
        "4.Payment by telegraphic transfer. Full bank details are issued with the proforma invoice.";

    /**
     * Remark 3 when NO row carries a lead time — typically a document made entirely of PLAIN rows
     * (freight, consumables, mosaic priced per SQM), which have no lead-time fields at all, or a
     * DRAFT/older re-render that predates {@code DealQuotationService#submit}'s new requirement
     * that every TILE row carry one.
     *
     * <p>Owner feedback #7 (2026-09-14) REVERSES pass 3 (2026-09-11)'s English mirror of
     * {@link #LINE3_FALLBACK}, which had named "China (import) ... Thailand (in stock) ...": no
     * country, no stock wording, at all now — just a visible blank for the rep to notice and fill
     * in, since with the submit guard in place this fallback should rarely be seen on anything but
     * a draft. That 2026-09-11 line had itself replaced "Delivery : lead time will be confirmed at
     * order confirmation.", which had in turn replaced an even older "Goods are in stock at the
     * factory in Italy; shipping time is approximately 90 days" that named a country with nothing
     * to do with the shipment (it once sat above a "Transportation Charges from China to Male Port,
     * Maldives" row on the owner's own QN6900902-6).
     *
     * <p><b>Superseded, and now unreachable (Opus review nit, 2026-09-15):</b> this comment used to
     * explain why the line was kept as a visible blank rather than dropped — "dropping one leaves
     * the no-bank-block layout at 7, which falls through to the older 3-line remark path" — but
     * that was true only because {@code QuotationRenderer} still assumed a hardcoded 8-line v2
     * render. Owner feedback (2026-09-14, "if ระยะเวลานำเข้า is not chosen remove that from the
     * หมายเหตุ") asked for the drop anyway, and {@code QuotationRenderer#REMARK_V2_MIN_LINES}
     * generalized the renderer to accept 7 lines as v2/full-remarks too, closing exactly the hazard
     * this comment warned about. {@link #englishRemarkLines} now unconditionally runs
     * {@link #dropLeadTimeLineAndRenumber} with {@link #hasAnyLeadTime}, which is {@code false}
     * in EXACTLY the case that makes {@link #englishLeadTimeLine} return this constant — so this
     * value is computed and then immediately discarded by the caller; it can never reach a
     * rendered document. Kept (not deleted) as the value {@link #englishLeadTimeLine} still has to
     * return something for while composing the full line list, and as a documented fallback should
     * the two decisions (fallback text vs. drop-the-line) ever be decoupled again.
     */
    private static final String EN_LINE3_FALLBACK = "3.Delivery : approximately ...... days";

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
        return hasAnyLeadTime(items) ? "3.Delivery : " + String.join("  ", groups) : EN_LINE3_FALLBACK;
    }

    private static void flushEnglishGroup(List<String> groups, Integer min, Integer max,
                                          Integer first, Integer last) {
        if (min == null) {
            return;
        }
        String range = first.equals(last) ? "item " + first : "items " + first + "-" + last;
        // Owner feedback #7 nicety: the same min == max collapse as the Thai #flushGroup.
        String days = min.equals(max) ? String.valueOf(min) : min + "-" + max;
        groups.add(range + " approximately " + days + " days");
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

    // ── Fix (2026-09-15, production complaint): ผู้สั่งซื้อ signature-name fallback ────────────

    /** The ผู้สั่งซื้อ (F2) signature-slot name: the deal's contact snapshot when there is one,
     * else the CUSTOMER name -- production printed the dotted {@code
     * QuotationRenderer#BLANK_NAME_PLACEHOLDER} on the signature line whenever a deal recorded no
     * separate contact, even though the customer being quoted to is right there on the same
     * document. {@code QuotationRenderer} only ever falls back to the placeholder when THIS
     * returns null, i.e. when both fields are blank. Shared by both TH/EN documents -- {@code
     * Signatories} is built once above for either language. */
    static String orderedByName(DealQuotationDto quotation) {
        if (!blank(quotation.contactName())) {
            return quotation.contactName().trim();
        }
        return blank(quotation.customerName()) ? null : quotation.customerName().trim();
    }

    // ── V179 (owner feedback #4, 2026-09-14): ผู้พิมพ์/พนักงานขาย print-name override ──────────
    // "they should be able to select who to show for ผู้พิมพ์ and พนักงานขาย" — PRINT-ONLY. Each
    // helper prefers the display-override fields when the corresponding *DisplayId is set, and
    // otherwise falls back to today's real-name fields, so createdById/salesRepId (deal ownership,
    // access, commission) never change and every quotation predating this feature (every
    // *DisplayId null) prints byte-identically to before.

    /** The ผู้พิมพ์ signature-slot name: {@code printedByDisplayName(En)} when
     * {@code printedByDisplayId} is set, else the real {@code createdByName(En)}. */
    static String printedByName(DealQuotationDto quotation, boolean english) {
        if (quotation.printedByDisplayId() != null) {
            return displayName(quotation.printedByDisplayName(), quotation.printedByDisplayNameEn(), english);
        }
        return displayName(quotation.createdByName(), quotation.createdByNameEn(), english);
    }

    /** The พนักงานขาย signature-slot / header "Sales/{name}" name: {@code salesRepDisplayName(En)}
     * when {@code salesRepDisplayId} is set, else the real {@code salesRepName(En)}. */
    static String salesRepDisplayNameOrReal(DealQuotationDto quotation, boolean english) {
        if (quotation.salesRepDisplayId() != null) {
            return displayName(quotation.salesRepDisplayName(), quotation.salesRepDisplayNameEn(), english);
        }
        return displayName(quotation.salesRepName(), quotation.salesRepNameEn(), english);
    }

    /** The header "T.{phone}" that pairs with {@link #salesRepDisplayNameOrReal} — the SAME
     * {@code salesRepDisplayId} decides both, so the printed name and phone can never come from
     * two different employees. */
    static String salesRepDisplayPhoneOrReal(DealQuotationDto quotation) {
        return quotation.salesRepDisplayId() != null ? quotation.salesRepDisplayPhone() : quotation.salesRepPhone();
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

    // ── item #1 (2026-09-14): the "เรียน"/attn contact prefix ────────────────────────────────

    /** Whether the contact part of the attn line should print at all — {@code false} when there
     * is no contact name, or when it is (after normalising whitespace, case-insensitively) the
     * SAME string as the customer name, which used to print the customer name twice
     * ("...บริษัท นันทวัน จำกัด   /   บริษัท นันทวัน จำกัด..."). */
    static boolean printContactPart(String contactName, String customerName) {
        if (blank(contactName)) {
            return false;
        }
        return !normalizeWhitespace(contactName).equalsIgnoreCase(normalizeWhitespace(customerName));
    }

    /** Trims and collapses internal whitespace runs to one space, so two names that differ only in
     * spacing still compare equal in {@link #printContactPart}. Also drops a trailing "."/","
     * (Opus review nit, 2026-09-14): a contact re-typed with a stray trailing full stop or comma
     * — "...จำกัด." vs "...จำกัด" — is the SAME duplicate-name bug this method exists to catch,
     * not a genuinely different name; only the very END of the string is touched, so a name that
     * legitimately ends mid-abbreviation elsewhere is untouched. */
    private static String normalizeWhitespace(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("\\s+", " ").replaceAll("[.,]+$", "");
    }

    // A leading marker is checked as a PREFIX of the (whitespace-normalised) name — these are all
    // legal-entity-type words that only ever open a Thai organisation's name, never a person's.
    private static final String[] ORG_NAME_PREFIXES = {
        "บริษัท", "บจก", "บ.จ.ก", "หจก", "ห.จ.ก", "ห้างหุ้นส่วน", "ร้าน", "สำนักงาน",
        "มูลนิธิ", "สมาคม", "โรงแรม", "โรงเรียน", "โรงพยาบาล", "องค์การ", "การไฟฟ้า", "การประปา",
    };
    // These are checked as a SUBSTRING anywhere in the (lower-cased) name — a Thai "จำกัด"/"(มหาชน)"
    // suffix, or an English company-type phrase, can trail after a name a leading-prefix check
    // alone would miss (e.g. a contact typed as "นันทวัน จำกัด" with no "บริษัท"). Each is already
    // distinctive/multi-character enough that a bare substring match is safe (unlike the short
    // WORD markers below).
    private static final String[] ORG_NAME_MARKERS = {
        "จำกัด", "(มหาชน)", "co., ltd", "co.,ltd",
    };
    // Opus review nit (2026-09-14): these are short enough that a bare substring match false-
    // positives on an ordinary name — "Somchai INchana" contains " inc", "John HoLLCroft" contains
    // "llc". Matched with \b word boundaries instead, via #ORG_NAME_WORD_PATTERN.
    private static final java.util.regex.Pattern ORG_NAME_WORD_PATTERN = java.util.regex.Pattern.compile(
        "\\b(company|limited|ltd|inc|llc)\\b");

    /** Owner feedback item #1 (2026-09-14): is {@code name} an organisation rather than a person,
     * for deciding whether the attn line's surviving contact part should be prefixed "คุณ". A
     * package-private static helper (see its own unit test) rather than inlined logic, because the
     * list of markers is exactly the kind of thing a later owner request edits in isolation. */
    static boolean looksLikeOrganisation(String name) {
        if (blank(name)) {
            return false;
        }
        String trimmed = normalizeWhitespace(name);
        for (String prefix : ORG_NAME_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                return true;
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String marker : ORG_NAME_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return ORG_NAME_WORD_PATTERN.matcher(lower).find();
    }

    // ── "คุณ" double-prefix fix (2026-09-15) ─────────────────────────────────────────────────

    // Longest-prefix-first so "นางสาว" (Ms.) is never left matching only as far as "นาง" (Mrs.) --
    // doesn't actually change which names match (either entry alone already answers "starts with
    // ONE of these" correctly), but keeps the list read in the same "most specific first" order as
    // #ORG_NAME_PREFIXES above rather than inviting a future maintainer to wonder why it isn't.
    private static final String[] THAI_HONORIFIC_PREFIXES = {
        "นางสาว", "นาย", "นาง", "คุณ", "ดร.",
    };

    /** {@code true} when {@code name} (whitespace-normalised) already OPENS with one of {@link
     * #THAI_HONORIFIC_PREFIXES} -- production bug, contact_name = "คุณปิยพร เมืองจีน" printed
     * "เรียน คุณคุณปิยพร เมืองจีน" because the value stored on the deal already carried its own
     * honorific and the attn-line builder prefixed a second one. Checked as a PREFIX only, exactly
     * like {@link #ORG_NAME_PREFIXES} -- a name that merely contains "นาง" mid-word does not count. */
    static boolean hasThaiHonorificPrefix(String name) {
        if (blank(name)) {
            return false;
        }
        String trimmed = normalizeWhitespace(name);
        for (String prefix : THAI_HONORIFIC_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ── Fix (2026-09-15, production complaint): customer phone falls back to the deal's own ──
    // ── contact phone; the contact e-mail joins the same line ────────────────────────────────

    /** The phone to print for this customer: {@code customerPhone} (the customer-master value)
     * when present, else {@code contactPhone} (the deal's own contact snapshot) -- production
     * example: {@code customer_phone=null}, {@code contact_phone="062-328-7555"} printed nothing
     * at all, even though a phone number for this exact deal WAS recorded, just on the contact
     * rather than the customer row. Shared by both TH/EN branches. */
    static String effectiveCustomerPhone(DealQuotationDto quotation) {
        return !blank(quotation.customerPhone()) ? quotation.customerPhone() : quotation.contactPhone();
    }

    // ── the customer phone's own label (QT-2026-0032-1, 2026-09-15) ─────────────────────────

    /** A phone value that OPENS with its own label, which both header branches above then prefix a
     * second one onto. Matched case-insensitively, with an optional "."/":" separator, and — this
     * is the safety catch — only when what FOLLOWS is the start of an actual number ({@code 0-9},
     * {@code +} or {@code (}), or nothing at all. Without that lookahead "โทรสาร 02-…" (fax) would
     * lose its "สาร", which changes the meaning of the line rather than tidying it. The
     * end-of-string alternative covers a value that is ONLY a label ("โทร."), which carries no
     * number and so must leave nothing behind for the caller to print.
     *
     * <p>Alternatives are longest-first, because Java's alternation takes the first that matches:
     * "โทรศัพท์" must be tried before "โทร", and "เบอร์โทรศัพท์" before "เบอร์โทร" before "เบอร์". */
    private static final java.util.regex.Pattern PHONE_LABEL_PREFIX = java.util.regex.Pattern.compile(
        "^(?:โทรศัพท์|เบอร์โทรศัพท์|เบอร์โทร|เบอร์|โทร|telephone|tel|phone)\\s*[.:：]?\\s*(?=[0-9+(]|$)",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Trims {@code phone} and drops a leading โทร./Tel.-style label off it.
     *
     * <p>Production bug, QT-2026-0032-1 (2026-09-15): B6 printed "โทร. โทร 02 314 354-2". Nothing
     * printed the label twice — the customer master row itself carries {@code phone = "โทร 02 314
     * 354-2"}, because the imported GL&amp;R customer directory stored the Thai label inside the
     * value. It is not one bad row: 299 of 4320 {@code customers.customer} rows open their phone
     * with a โทร/Tel label, so every quotation for any of them would print the same doubled line.
     * The label is stripped at PRINT time rather than cleaned out of the master data: the same
     * column also holds genuinely free-form text (contact names, e-mail addresses, "ต่อ 233"), so a
     * blanket data migration over it would be the riskier change, and this keeps the document
     * correct for anything typed that way in future too.
     *
     * <p>Only a LEADING label is touched. "คุณเจี๊ยบ โทร 081-927-9010" is a genuine free-text note,
     * not a mislabelled number, and prints as recorded. */
    static String stripPhoneLabel(String phone) {
        if (blank(phone)) {
            return "";
        }
        return PHONE_LABEL_PREFIX.matcher(phone.trim()).replaceFirst("").trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
