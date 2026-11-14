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

/** Quotation v2 (direct deal quotation, V165) — builds QuotationRenderer's rich model (QuotationRenderModel)
 * straight from a DealQuotationDto, per docs/sales/quotation-v2-plan.md's "Printed lines" section. */
public final class DealQuotationRenderAdapter {
    private DealQuotationRenderAdapter() {}

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    /** The deposit percent the PDF prints when a quotation's {@code deposit_percent} is NULL. The
     * deposit notice bills the same default, so the customer is billed
     * what the accepted PDF promised. */
    public static final int DEFAULT_DEPOSIT_PERCENT = 30;

    /** The percent a new-engine quotation bills and prints: a blank one means {@link #DEFAULT_DEPOSIT_PERCENT}. */
    public static int effectiveDepositPercent(Integer stored) {
        return stored != null ? stored : DEFAULT_DEPOSIT_PERCENT;
    }

    /** Overload for callers with no configured bank block — the English document then prints the proforma-invoice
     * line instead. Kept so tests and any hand-wired caller need not thread a config value they do not care about. */
    public static QuotationRenderModel toRenderModel(DealQuotationDto quotation, byte[] approverSignaturePng,
                                                      String approverSignatureMime) {
        return toRenderModel(quotation, approverSignaturePng, approverSignatureMime, List.of());
    }

    /** Selects affected frontend tests, including readers outside the import graph. */
    public static QuotationRenderModel toRenderModel(DealQuotationDto quotation, byte[] approverSignaturePng,
                                                      String approverSignatureMime,
                                                      List<String> bankBlockLines) {
        return toRenderModel(quotation, approverSignaturePng, approverSignatureMime, bankBlockLines,
            java.util.Map.of());
    }

    /**
     * {@code itemPictures} is item id → the stored picture (V170), resolved by the caller
     * for the same reason the signature bytes are — this class stays a pure function. An item
     * whose DTO says {@code hasPicture} but has no entry here (a race with a removal) simply
     * prints without one; an item with an entry but {@code hasPicture=false} is never given one.
     */
    public static QuotationRenderModel toRenderModel(DealQuotationDto quotation, byte[] approverSignaturePng,
                                                      String approverSignatureMime,
                                                      List<String> bankBlockLines,
                                                      java.util.Map<Long, DealQuotationRepository.PictureImage> itemPictures) {
        return toRenderModel(quotation, approverSignaturePng, approverSignatureMime, bankBlockLines, itemPictures,
            null, null, null, null);
    }

    /**
     * The full overload, additionally threading the
     * ผู้พิมพ์ (slot 0) and พนักงานขาย (slot 1) signature image bytes — resolved LIVE by the
     * caller ({@code DealQuotationService#toRenderModel}) the same way {@code approverSignaturePng}
     * is, but never frozen into an approval snapshot the way the approver's is.
     * Either pair may be null independently (that person has no signature on file, or the caller
     * chose not to resolve one) — the corresponding slot then prints text-only. Kept as a
     * SEPARATE overload (rather than adding params to the one above) so
     * every caller of the five-argument overload keeps compiling unchanged.
     */
    public static QuotationRenderModel toRenderModel(DealQuotationDto quotation, byte[] approverSignaturePng,
                                                      String approverSignatureMime,
                                                      List<String> bankBlockLines,
                                                      java.util.Map<Long, DealQuotationRepository.PictureImage> itemPictures,
                                                      byte[] printedBySignaturePng, String printedBySignatureMime,
                                                      byte[] salesRepSignaturePng, String salesRepSignatureMime) {
        // B4 (header วันที่) = the date the SALES REP CREATED the quotation, for every status.
        // Printing the APPROVED date would make an approved document appear to have been quoted
        // on the day the manager got round to it. Mirrors DealQuotationRepository#mapQuotation's
        // quotationDate so the UI and the document can never disagree. The remark-1 offer date
        // (วันที่รับจำนวน) is a DIFFERENT, rep-editable date and is not touched — see
        // #remarkLines. createdAt is sales.quotation.issued_at, NOT NULL since V59; the
        // today-fallback is defensive only.
        LocalDate issueDate = quotation.createdAt() != null
            ? quotation.createdAt().atZone(BANGKOK).toLocalDate()
            : LocalDate.now(BANGKOK);

        boolean english = WastageCalculator.DOCUMENT_LANGUAGE_EN.equals(quotation.documentLanguage());

        // The rep's own name follows the document too: her English sample prints
        // "Sales/Jannet T.080-7767707", the same shape as the Thai "Sales/เจนเนต".
        // salesRepDisplayId (V179) overrides BOTH the name and the
        // phone here — see #salesRepDisplayNameOrReal/#salesRepDisplayPhoneOrReal.
        // No phone, no dangling " T.".
        String salesPhone = nullSafe(salesRepDisplayPhoneOrReal(quotation));
        String salesLine = "Sales/" + nullSafe(salesRepDisplayNameOrReal(quotation, english))
            + (salesPhone.isBlank() ? "" : " T." + salesPhone);

        String attnLine;
        String phoneLine;
        if (english) {
            // F-SM-008 header block: the template's A5 label cell becomes
            // "Attn :" (see QuotationRenderer#writeEnglishHeaderLabels) and B5 carries the contact
            // and the customer; B6 carries "Address : ...", "E : ..." and the telephone as ONE
            // line, because the template has exactly TWO free rows here and the sample documents
            // print three labelled lines.
            // The same dedupe as the Thai branch below, minus
            // the คุณ decision (the English form never prefixes it at all).
            String contactPart = printContactPart(quotation.contactName(), quotation.customerName())
                ? quotation.contactName().trim() + "   /   " : "";
            attnLine = contactPart + nullSafe(quotation.customerName());
            List<String> parts = new ArrayList<>();
            if (!blank(quotation.customerAddress())) {
                parts.add("Address : " + quotation.customerAddress().trim().replace('\n', ' '));
            }
            // ONLY the ผู้ติดต่อ e-mail/phone typed on the quotation print,
            // and nothing when they are null -- no customer-master or contact fallback (see
            // #printedEmail / #printedPhone). #stripPhoneLabel — the typed value may open with its
            // OWN label; see its Javadoc.
            String email = printedEmail(quotation);
            if (!blank(email)) {
                parts.add("E : " + email);
            }
            String phone = printedPhone(quotation);
            if (!blank(phone)) {
                parts.add("Tel. " + phone);
            }
            phoneLine = String.join("   ", parts);
        } else {
            // The "contact" snapshot can BE the company name (no separate human contact recorded,
            // so the write path falls back to the customer name); prefixing "คุณ" and printing it
            // would give "เรียน คุณบริษัท นันทวัน จำกัด   /   บริษัท นันทวัน จำกัด". Two independent
            // rules, both in #printContactPart/#looksLikeOrganisation:
            // (1) when the contact snapshot equals the customer name (normalised), the contact part
            // is omitted entirely rather than printed twice; (2) "คุณ" is prefixed only when the
            // surviving contact name does not itself look like an organisation (a company recorded
            // as its OWN contact, distinct from the customer name, still should not read "คุณ").
            //
            // A contact name that already carries its own honorific (e.g. "คุณสมหญิง ใจดี")
            // must not get a second one ("คุณคุณ..."): #hasThaiHonorificPrefix is the same
            // "already has one" check #looksLikeOrganisation does for a company name.
            // The ไม่เติม “คุณ” หน้าชื่อผู้สั่งซื้อ option (V180) is the ONE
            // override on top of the existing organisation/already-has-one detection below — a rep
            // ticks this when the contact snapshot is genuinely a department/section name ("ฝ่าย
            // จัดซื้อ") that #looksLikeOrganisation's markers do not catch. Only ever suppresses the
            // honorific itself; #printContactPart's own dedupe (contact == customer name) is
            // unaffected and still decides whether the contact part prints at all.
            String honorific = quotation.omitContactHonorific() ? ""
                : (looksLikeOrganisation(quotation.contactName())
                        || hasThaiHonorificPrefix(quotation.contactName()) ? "" : "คุณ");
            String contactPart = printContactPart(quotation.contactName(), quotation.customerName())
                ? honorific + quotation.contactName().trim() + "   /   "
                : "";
            String taxIdPart = !blank(quotation.customerTaxId())
                ? "   เลขที่ผู้เสียภาษี : " + quotation.customerTaxId().trim() : "";
            attnLine = contactPart + nullSafe(quotation.customerName()) + taxIdPart;
            // The customer address must be printed from quotation.customerAddress(). The Thai
            // template (F-SM-002) reserves only ATTN_ROW/PHONE_ROW for this header block and has
            // no free row of its own for an address (unlike QuotationRenderer's English branch
            // just above, whose B6 already folds Address/E/Tel into one merged B6:G6 cell for the
            // same reason) — so the address is folded into that same PHONE_ROW line here rather
            // than left unprinted.
            List<String> parts = new ArrayList<>();
            if (!blank(quotation.customerAddress())) {
                parts.add("ที่อยู่ " + quotation.customerAddress().trim().replace('\n', ' '));
            }
            // The e-mail joins this same ที่อยู่/Phone line (the English branch above folds
            // Address/E/Tel into one line too; this template has no free row of its own for
            // either — see the comment just above for why the address already lives here).
            // There is no fallback: only the ผู้ติดต่อ e-mail/phone typed on the quotation print,
            // and nothing when they are null (see #printedEmail / #printedPhone). The ADDRESS
            // still prints from the customer master.
            String email = printedEmail(quotation);
            if (!blank(email)) {
                parts.add("Email " + email);
            }
            // The stored phone value may open with its own label ("โทร 02 314 354-2"), which
            // would print as "โทร. โทร 02 ...". See #stripPhoneLabel's Javadoc.
            String phone = printedPhone(quotation);
            if (!blank(phone)) {
                parts.add("Phone " + phone);
            }
            phoneLine = String.join("   ", parts);
        }

        List<RenderItem> items = quotation.items().stream()
            .sorted((a, b) -> Integer.compare(a.seq(), b.seq()))
            // V192.5: each row is printed in the mode IT was priced in — the document's, or on a
            // MIXED document the row's own. One line; everything #toRenderItem and #discountLabel
            // decide from "the price mode" follows.
            .map(item -> toRenderItem(item,
                WastageCalculator.rowPriceMode(quotation.priceMode(), item.priceMode()), english, itemPictures))
            .toList();

        // Slot 4 = ผู้สั่งซื้อ: ALWAYS null, the dotted line for the customer's pen (owner 2026-09-26,
        // restated 2026-11-06). The picked ผู้ติดต่อ prints only after "เรียน"; the stored
        // ordered_by_name is never printed here. The dates row = created / submitted / approved,
        // each only once it exists -- a DRAFT prints only ผู้พิมพ์'s.
        // On an ENGLISH document each signatory prints its hr.employee first_name_en/
        // last_name_en, FALLING BACK to the Thai name when those are blank — an empty signature
        // slot on a customer-facing document is worse than a Thai name on an English page.
        // printedBy/salesRep each fall back to the display override when the corresponding
        // *DisplayId is set (V179) — see #printedByName/#salesRepDisplayNameOrReal. approvedBy
        // is untouched: the override never covers the approver slot.
        Signatories signatories = new Signatories(
            printedByName(quotation, english),
            salesRepDisplayNameOrReal(quotation, english),
            displayName(quotation.approvedByName(), quotation.approvedByNameEn(), english),
            null,
            approverSignaturePng, approverSignatureMime,
            bangkokDate(quotation.createdAt()), bangkokDate(quotation.submittedAt()), bangkokDate(quotation.approvedAt()),
            printedBySignaturePng, printedBySignatureMime,
            salesRepSignaturePng, salesRepSignatureMime);

        // A document with NO tile line at all (sanitaryware sold
        // on ชุด/PLAIN lines) prints a different, shorter หมายเหตุ block (V182) — the tile-oriented
        // remarks (sizes vs. ISO/มอก., colour/LOT variance) are simply wrong on such a document.
        // #hasAnyTileLine also answers "non-tile" for an all-ADJUSTMENT item list, but that is a
        // defensive branch, not a real document: DealQuotationService#buildItems refuses BOTH an
        // empty item list and an all-ADJUSTMENT one before anything is ever saved. See
        // #hasAnyTileLine.
        boolean hasTile = hasAnyTileLine(quotation.items());
        List<String> remarks = english
            ? (hasTile ? englishRemarkLines(quotation, bankBlockLines) : englishNonTileRemarkLines(quotation, bankBlockLines))
            : (hasTile ? remarkLines(quotation) : nonTileRemarkLines(quotation));

        return new QuotationRenderModel(
            // ฝ่าย is not printed, so the
            // stored dept_code — still on older quotations — is not passed on: null, not
            // quotation.deptCode(). The renderer ignores the field either way; withholding it
            // here as well means a stale value cannot reach a document by any route.
            issueDate, quotation.number(), null, quotation.unitCode(), salesLine,
            // The "ไม่แสดงชื่อโครงการในใบเสนอราคา" flag (V192.1): the ONE place
            // it has any effect, and it does two things. The NAME is withheld here, so it
            // never reaches the renderer at all; and hideProjectLine (the last argument below)
            // tells QuotationRenderer to blank the template's own "Project  : " label, which a
            // null name alone would leave printed — on the Thai and English documents alike.
            // quotation.projectName() itself is untouched: the editor, the register and the deal
            // still read it.
            attnLine, phoneLine, quotation.hideProjectName() ? null : quotation.projectName(), items,
            remarks, signatories, true,
            english ? WastageCalculator.DOCUMENT_LANGUAGE_EN : WastageCalculator.DOCUMENT_LANGUAGE_TH,
            quotation.currency(),
            // forceCompactRemarks: this class ALWAYS builds the direct-deal v2 render and always wants the
            // packed/compacted remark-box layout.
            true,
            // hideProjectLine — see the projectName argument above.
            quotation.hideProjectName());
    }

    /** true when item is a TILE row — lineType null or WastageCalculator#LINE_TYPE_TILE — the same question
     * #printedUnit/#discountLabel each ask inline for their own reasons. Shared here for the non-tile remark-set
     * gate below. */
    private static boolean isTileLine(DealQuotationItemDto item) {
        return item.lineType() == null || WastageCalculator.LINE_TYPE_TILE.equals(item.lineType());
    }

    /** {@code true} when ANY item on the document is a TILE row. {@code false} for a document made
     * entirely of PLAIN rows (sanitaryware — taps, showers, sold as ชุด) — the real-world non-tile
     * case this feature is for — and, defensively, for an item list that is all ADJUSTMENT rows.
     * The latter is NOT a real "credit-note-ish" document —
     * {@code DealQuotationService#buildItems} refuses both an empty item list and an all-ADJUSTMENT
     * one before either is ever saved, so this branch of the method is never exercised by a real
     * quotation, only by a test constructing a {@code DealQuotationItemDto} list directly. Either
     * way the answer is "non-tile" for the remark block below: neither case has anything to do with
     * ขนาด/สี variance between kiln lots, which is what the tile-oriented remarks 4/5 are actually
     * about. */
    private static boolean hasAnyTileLine(List<DealQuotationItemDto> items) {
        return items.stream().anyMatch(DealQuotationRenderAdapter::isTileLine);
    }

    /**
     * One printed row, for any of the three line types.
     *
     * <p>Every null-guarded {@code lines.add} below follows layout-spec §2's contract: a printed
     * line that does not apply is {@code null}, NOT blank, and the caller must omit the row
     * entirely rather than emit an empty one. A PLAIN or ADJUSTMENT row has no size line and no
     * calculation line at all, so both are null there and only the description prints; a
     * SPECIAL_SQM tile adds the ราคาพิเศษ sub-line the owner's documents carry.
     *
     * <p>{@code quantity}/{@code unit} rather than {@code piecesFinal}/"แผ่น": the printed จำนวน
     * and หน่วย are per-row. A TILE row sets quantity = piecesFinal and unit = "แผ่น"; an
     * ADJUSTMENT row carries −1 and a NULL unit, which the renderer prints as an EMPTY unit
     * cell.
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
        // The English per-sqm Qty prints to 2dp (72.00, 56.43).
        boolean perSqmRow = english && WastageCalculator.PRICE_MODE_SPECIAL_SQM.equals(priceMode)
            && DealQuotationLines.TILE_UNIT_SQM.equals(item.unit())
            && (item.lineType() == null || WastageCalculator.LINE_TYPE_TILE.equals(item.lineType()));
        return new RenderItem(
            item.locationLabel(), lines,
            item.quantity(), printedUnit(item, english), item.unitPrice(), discountLabel(item, priceMode, english),
            item.netUnitPrice(), item.lineAmount(), picture, perSqmRow ? QTY_FORMAT_SQM : null);
    }

    /** Pass an empty unit for non-tile adjustments: null would print the renderer's default unit. */
    static final String QTY_FORMAT_SQM = "#,##0.00";

    private static String printedUnit(DealQuotationItemDto item, boolean english) {
        boolean tile = item.lineType() == null
            || WastageCalculator.LINE_TYPE_TILE.equals(item.lineType());
        if (item.unit() != null) {
            return item.unit();
        }
        // A tile with no unit prints "PCS" on the English form.
        return tile ? DealQuotationLines.tileUnit(english
            ? WastageCalculator.DOCUMENT_LANGUAGE_EN : WastageCalculator.DOCUMENT_LANGUAGE_TH) : "";
    }

    /**
     * The ส่วนลด column. It reads <b>พิเศษ</b> for a SPECIAL_SQM row, and for
     * a DIRECT_NET row whose net actually differs from the list price; it stays {@code Net} /
     * {@code N%} in NET mode.
     *
     * <p>The price mode only governs TILE rows. A PLAIN row follows its OWN discount (normally
     * absent, so "Net") whatever mode the document is in — that is what lets a ราคาพิเศษ document
     * still carry an ordinary freight line.
     *
     * <p>An ADJUSTMENT row prints <b>NOTHING</b>, not "Net" (even though its ราคา and คงเหลือ are
     * literally equal): the owner's reference document leaves the ส่วนลด cell of the ส่วนลดพิเศษ
     * row EMPTY, exactly as it leaves หน่วย empty, because neither column means anything for a
     * row that IS the discount rather than a row carrying one.
     */
    private static String discountLabel(DealQuotationItemDto item, String priceMode, boolean english) {
        // English: "Special", never "พิเศษ" — the English form carries no Thai in its item table. The SAME word
        // QuotationDocumentView prints on screen (quotationMeta#documentDiscountLabel), so the page and the PDF agree.
        String special = english ? "Special" : "พิเศษ";
        BigDecimal pct = item.discountPct();
        if (WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(item.lineType())) {
            return "";
        }
        boolean tile = item.lineType() == null
            || WastageCalculator.LINE_TYPE_TILE.equals(item.lineType());
        if (tile) {
            if (WastageCalculator.PRICE_MODE_SPECIAL_SQM.equals(priceMode)) {
                // An English per-sqm row prints "Net" — the USD/sqm IS the net.
                // Thai keeps พิเศษ.
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

    // ── "Has special pricing" (V178). The ONE shared gate for whether
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
            .map(i -> new PricingRow(i.lineType(), i.unitPrice(), i.discountPct(), i.netUnitPrice(), i.priceMode()))
            .toList());
    }

    /** Same rule, over the rows about to be WRITTEN — DealQuotationService#create/#update call this so DATE mode is
     * refused BEFORE anything is saved, rather than needing a round trip through a persisted read. */
    public static boolean hasSpecialPricingForNewItems(String priceMode,
                                                        List<DealQuotationRepository.NewItem> items) {
        return hasSpecialPricingCore(priceMode, items.stream()
            .map(i -> new PricingRow(i.lineType(), i.unitPrice(), i.discountPct(), i.netUnitPrice(), i.priceMode()))
            .toList());
    }

    /** {@code priceMode} is the ROW's own stored mode (V192.5) — non-null only on a tile row of a
     * MIXED document. */
    private record PricingRow(String lineType, BigDecimal unitPrice, BigDecimal discountPct,
                              BigDecimal netUnitPrice, String priceMode) {}

    private static boolean hasSpecialPricingCore(String documentPriceMode, List<PricingRow> rows) {
        for (PricingRow row : rows) {
            // Rules (a)–(c) are about how a TILE row is priced, so each is asked of the mode
            // THAT ROW is priced in — the document's, or on a MIXED document the row's own (V192.5).
            // For a single-mode document this is the same value on every pass.
            String priceMode = WastageCalculator.rowPriceMode(documentPriceMode, row.priceMode());
            boolean specialSqm = WastageCalculator.PRICE_MODE_SPECIAL_SQM.equals(priceMode);
            boolean directNet = WastageCalculator.PRICE_MODE_DIRECT_NET.equals(priceMode);
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

    // remarks (8 lines) — docs/sales/quotation-v2-plan.md "Printed lines"; 4/5/6/8 are the template's own fixed
    // text (two rows concatenated where the template splits a sentence across a "head" row and an unnumbered
    // continuation row).

    // No country and no stock wording is printed at all when nothing on the document carries a
    // lead time — submit() makes that case rare (every TILE row must carry one), but a DRAFT
    // preview or a re-render of an older document can still hit it. Same header word
    // ("ระยะเวลานำเข้า") the per-item line below uses, so a document that mixes a priced item with
    // an unpriced legacy row never reads as two different features.
    //
    // The fallback value below is unreachable in practice: #dropLeadTimeLineAndRenumber drops
    // the whole line on exactly the condition that makes #leadTimeLine return this constant, so
    // the value is computed and then always discarded by remarkLines()'s caller. Kept for the
    // same reason EN_LINE3_FALLBACK is -- see its Javadoc.
    private static final String LINE3_FALLBACK = "3.ระยะเวลานำเข้า : ประมาณ ...... วัน";
    private static final String LINE4 =
        "4.ขนาดของกระเบื้องจริง จะแตกต่างจากขนาดที่ระบุในใบเสนอราคา ได้เล็กน้อย ตามมาตรฐาน ISO และ มอก.";
    private static final String LINE5 =
        "5.สีและลวดลายของกระเบื้อง อาจแตกต่างจากตัวอย่างได้เล็กน้อย  เนื่องจากเป็นสินค้าคนละ LOT การผลิต";
    private static final String LINE6 =
        "6.ทางบริษัทฯ ไม่รับเปลี่ยนหรือคืนสินค้า กรุณาตรวจสอบ ความถูกต้องก่อนสั่งซื้อหรือลงชื่อรับสินค้า";
    private static final String LINE8 = "8.เงื่อนไขประกอบใบเสนอราคา ตามเอกสารแนบ";

    /** Remark 7's DATE-mode variant (verbatim, including the number
     * prefix and the ONE ASCII space before the date): "ราคาพิเศษสำหรับการสั่งซื้อและชำระมัดจำ
     * ภายในวันที่ ..../..../....." */
    private static final String LINE7_DATE_PREFIX =
        "7.ราคาพิเศษสำหรับการสั่งซื้อและชำระมัดจำภายในวันที่ ";

    /**
     * Note 2, remark slot -- the ONE numbered line that names the deposit. A document with
     * {@code deposit_percent = 0} must not print "บริษัทฯ ขอรับมัดจำ 0% เมื่อสั่งซื้อสินค้า
     * ส่วนที่เหลือ..." -- a phantom zero-percent deposit demand.
     *
     * <p>Only an EXPLICIT zero takes this branch. A {@code null} {@code depositPercent}
     * defaults to 30 above ({@code depositPct}); do not conflate the two.
     *
     * <p>When there truly is no deposit, note 2 states the FULL-amount payment terms directly
     * instead of naming a deposit that does not exist -- the remainder text passed in (the
     * CREDIT-days wording, or the before/upon-delivery wording) covers the SAME two
     * {@code remainderMode} variants (nothing else is stored in
     * {@code remainder_mode} -- see the DB column's own comment), applied to the WHOLE
     * amount rather than "the remainder after the deposit". The "2." slot number is the same
     * either way.
     */
    private static String depositLine(int depositPct, String fullPaymentTerm, String remainderMode,
                                       Integer creditDays, String remainderText) {
        // A 100% deposit is full payment, not "a deposit, with a
        // remainder" -- remainderMode/creditDays describe money that, at 100%, does not exist, and
        // printing them anyway ("...ส่วนที่เหลือขอรับก่อนส่งมอบสินค้าหรือเมื่อส่งมอบสินค้า" on top of
        // a 100% figure) would be wrong. This is a DIFFERENT
        // sentence from the fullPaymentTerm-selected text below (which only ever applies at
        // 0%): 100% is charged UP FRONT, 0% is charged with none up front at all.
        if (depositPct == 100) {
            return "2.บริษัทขอรับเงินค่าสินค้า 100% เมื่อสั่งซื้อสินค้า";
        }
        if (depositPct != 0) {
            return "2.บริษัทฯ ขอรับมัดจำ " + depositPct + "% เมื่อสั่งซื้อสินค้า ส่วนที่เหลือ" + remainderText;
        }
        // "ไม่รับมัดจำ" (V181): a zero-deposit document names ONE
        // of three fixed payment terms — see #fullPaymentTermThaiText. A row whose fullPaymentTerm
        // is null (every LEGACY zero-deposit row, which predates the option and never set it —
        // DealQuotationService#resolveFullPaymentTerm guarantees it is null on every OTHER row too,
        // but those never reach depositPct == 0 in the first place) falls through to the original
        // remainderMode/creditDays-based text below, so an already-approved legacy
        // document keeps printing byte-for-byte what it always did.
        String termText = fullPaymentTermThaiText(fullPaymentTerm);
        if (termText != null) {
            return "2." + termText;
        }
        return "CREDIT".equals(remainderMode)
            ? "2.บริษัทฯ ขอรับชำระเต็มจำนวนเป็นเครดิต " + (creditDays != null ? creditDays : 0) + " วัน"
            : "2.บริษัทฯ ขอรับชำระเต็มจำนวนก่อนส่งมอบสินค้าหรือเมื่อส่งมอบสินค้า";
    }

    /** The three fixed Thai payment-term sentences, or {@code null} for
     * any other value — including a legacy null, which lets {@link #depositLine} fall back to its
     * ORIGINAL remainderMode-based text rather than printing nothing. */
    private static String fullPaymentTermThaiText(String code) {
        if (WastageCalculator.FULL_PAYMENT_TERM_BEFORE_DELIVERY.equals(code)) {
            return "บริษัทขอรับเงินค่าสินค้า 100% ก่อนส่งมอบสินค้า";
        }
        if (WastageCalculator.FULL_PAYMENT_TERM_ON_DELIVERY.equals(code)) {
            return "บริษัทขอรับเงินค่าสินค้า 100% เมื่อส่งมอบสินค้า";
        }
        // "เมื่อ..." FIRST, then "หรือก่อน..." — the reverse order of
        // the legacy fallback text in #depositLine's "ก่อน...หรือเมื่อ..." phrasing, on purpose.
        if (WastageCalculator.FULL_PAYMENT_TERM_ON_OR_BEFORE_DELIVERY.equals(code)) {
            return "บริษัทขอรับเงินค่าสินค้า 100% เมื่อส่งมอบสินค้าหรือก่อนส่งมอบสินค้า";
        }
        return null;
    }

    // ── The NON-TILE remark set (V182) ("สินค้าที่ไม่ใช่กระเบื้อง" —
    // sanitaryware: taps, showers, sold as ชุด on PLAIN lines; also an ADJUSTMENT-only document):
    // exactly four remarks. ─────

    private static final String NON_TILE_LINE1 =
        "1.ราคาข้างต้นรวมค่าขนส่งถึงชั้น 1 ของหน่วยงานในเขตกทม. แต่ไม่รวมค่าติดตั้ง";

    /** Remark 3 when NO row on a non-tile document carries a lead time — discarded by #dropLeadTimeLineAndRenumber
     * in exactly the same way #LINE3_FALLBACK is for the tile set; kept only as the value #nonTileLeadTimeLine
     * must return something for while composing the full line list. */
    private static final String NON_TILE_LINE3_FALLBACK = "3.ระยะเวลานำเข้า : ประมาณ ...... วัน";

    /**
     * {@code "3.กรณีโรงงานผู้ผลิตมีสินค้าพร้อมจัดส่ง ระยะเวลานำเข้า {groups} หลังจากได้รับมัดจำ
     * {deposit}% เรียบร้อยแล้ว"} — or, when the quotation takes NO deposit ({@code
     * depositPct == 0}), the same sentence with just the "หลังจากได้รับมัดจำ...เรียบร้อยแล้ว" clause
     * dropped (naming a deposit that does not exist would be nonsensical, but the lead-time claim
     * itself still stands).
     *
     * <p>{@code {groups}} is the SAME per-item grouped
     * list the tile set's own {@link #leadTimeLine} builds ({@link #leadTimeGroups}) — NOT an
     * overall min-of-mins/max-of-maxes envelope: a genuinely mixed non-tile document (e.g. one
     * row 75-90 days, another 30-45) would print a single misleading "30-90 วัน" span under the
     * envelope approach. Falls back to
     * {@link #NON_TILE_LINE3_FALLBACK} when {@link #hasAnyLeadTime} is false — the ONLY caller
     * ({@link #nonTileRemarkLines}) then drops the whole line via {@link #dropLeadTimeLineAndRenumber},
     * exactly as the tile set's own {@link #leadTimeLine} does. */
    private static String nonTileLeadTimeLine(List<DealQuotationItemDto> items, int depositPct) {
        if (!hasAnyLeadTime(items)) {
            return NON_TILE_LINE3_FALLBACK;
        }
        String groups = String.join("  ", leadTimeGroups(items, false));
        // At 100% deposit there is no "deposit" left to name a
        // percentage of -- the whole price was already collected -- so this states that plainly
        // ("after full payment is received") instead of the misleading "...มัดจำ 100%...".
        String depositClause;
        if (depositPct == 0) {
            depositClause = "";
        } else if (depositPct == 100) {
            depositClause = " หลังจากได้รับชำระเงินเรียบร้อยแล้ว";
        } else {
            depositClause = " หลังจากได้รับมัดจำ " + depositPct + "% เรียบร้อยแล้ว";
        }
        return "3.กรณีโรงงานผู้ผลิตมีสินค้าพร้อมจัดส่ง ระยะเวลานำเข้า " + groups + depositClause;
    }

    /** Renumbers #LINE6's own "no exchange or return" sentence to number — REUSES the constant's text rather than
     * retyping it, so the tile and non-tile remark sets can never print two different translations of the same
     * rule. */
    private static String noReturnLine(int number) {
        return number + LINE6.substring(LINE6.indexOf('.'));
    }

    /** The non-tile หมายเหตุ block — exactly four lines (three when there is no lead time to
      * report): freight/no-installation, the deposit (verbatim {@link #depositLine}, so 30%/50%/
      * custom, เครดิต N วัน and the V181 "ไม่รับมัดจำ" sentences all work), the
      * lead time (dropped entirely, not blanked, when nothing on the document carries one — same
      * {@link #dropLeadTimeLineAndRenumber} the tile set uses), and the no-exchange-or-return rule. */
    private static List<String> nonTileRemarkLines(DealQuotationDto quotation) {
        int depositPct = quotation.depositPercent() != null ? quotation.depositPercent() : DEFAULT_DEPOSIT_PERCENT;
        String remainderText = "CREDIT".equals(quotation.remainderMode())
            ? "เครดิต " + (quotation.creditDays() != null ? quotation.creditDays() : 0) + " วัน"
            : "ขอรับก่อนส่งมอบสินค้าหรือเมื่อส่งมอบสินค้า";

        List<String> lines = new ArrayList<>();
        lines.add(NON_TILE_LINE1);
        lines.add(depositLine(depositPct, quotation.fullPaymentTerm(), quotation.remainderMode(),
            quotation.creditDays(), remainderText));
        int leadTimeLineIndex = lines.size();
        lines.add(nonTileLeadTimeLine(quotation.items(), depositPct));
        lines.add(noReturnLine(4));
        return dropLeadTimeLineAndRenumber(lines, hasAnyLeadTime(quotation.items()), leadTimeLineIndex);
    }

    private static List<String> remarkLines(DealQuotationDto quotation) {
        LocalDate offerDate = quotation.offerDate() != null ? quotation.offerDate() : LocalDate.now(BANGKOK);
        int depositPct = quotation.depositPercent() != null ? quotation.depositPercent() : DEFAULT_DEPOSIT_PERCENT;
        String remainderText = "CREDIT".equals(quotation.remainderMode())
            ? "เครดิต " + (quotation.creditDays() != null ? quotation.creditDays() : 0) + " วัน"
            : "ขอรับก่อนส่งมอบสินค้าหรือเมื่อส่งมอบสินค้า";
        int validityDays = quotation.validityDays() != null ? quotation.validityDays() : 30;
        // Remark 7's second กำหนดยืนยันราคา variant (V178): a rep may
        // name an exact calendar date instead of a day count. validityUntil is only ever non-null
        // in DATE mode (service-enforced).
        String line7 = WastageCalculator.VALIDITY_MODE_DATE.equals(quotation.validityMode())
            && quotation.validityUntil() != null
            && hasSpecialPricing(quotation)
            ? LINE7_DATE_PREFIX + shortThaiDate(quotation.validityUntil())
            : "7.กำหนดยืนยันราคา " + validityDays + " วัน นับจากวันที่ในใบเสนอราคา";

        List<String> lines = new ArrayList<>();
        lines.add("1.จำนวนที่เสนอข้างต้นเป็นจำนวนที่ได้รับมาเมื่อวันที่  " + shortThaiDate(offerDate));
        lines.add(depositLine(depositPct, quotation.fullPaymentTerm(), quotation.remainderMode(),
            quotation.creditDays(), remainderText));
        int leadTimeLineIndex = lines.size();
        lines.add(leadTimeLine(quotation.items()));
        lines.add(LINE4);
        lines.add(LINE5);
        lines.add(LINE6);
        lines.add(line7);
        lines.add(LINE8);
        // When NOTHING on the document carries a lead time (leadTimeLine above would
        // have printed LINE3_FALLBACK), the line is dropped ENTIRELY (not left blank) and every
        // remark after it renumbers down by one. See #dropLeadTimeLineAndRenumber.
        return dropLeadTimeLineAndRenumber(lines, hasAnyLeadTime(quotation.items()), leadTimeLineIndex);
    }

    /** {@code "3.ระยะเวลานำเข้า : รายการที่ 1-2 ประมาณ 75-90 วัน  รายการที่ 3 ประมาณ 30-45 วัน"} —
     * consecutive item numbers sharing the same (min, max) lead time are grouped, ALWAYS in this
     * per-item form even for a single group; items with no lead
     * time are omitted; when nothing has one, this returns {@link #LINE3_FALLBACK} -- but the
     * ONLY caller ({@link #remarkLines}) then drops that whole line via
     * {@link #dropLeadTimeLineAndRenumber} rather than keeping the fallback (see
     * {@link #LINE3_FALLBACK}'s own comment), so in practice this
     * value never reaches a rendered document. */
    private static String leadTimeLine(List<DealQuotationItemDto> items) {
        return hasAnyLeadTime(items)
            ? "3.ระยะเวลานำเข้า : " + String.join("  ", leadTimeGroups(items, false))
            : LINE3_FALLBACK;
    }

    /**
     * The per-item lead-time grouping shared by the tile set's {@link #leadTimeLine}/
     * {@link #englishLeadTimeLine} AND the non-tile set's {@link #nonTileLeadTimeLine}/
     * {@link #englishNonTileLeadTimeLine} (a non-tile
     * document with genuinely mixed lead times must use this SAME per-item grouping, not an
     * overall min-of-mins/max-of-maxes envelope that could misstate a customer's actual wait).
     * Consecutive item numbers sharing the same {@code (min, max)} are grouped, ALWAYS in this
     * per-item form even for a single group; items with no lead
     * time are omitted and only ever act as a group boundary. Returns one formatted string per
     * group — {@code "รายการที่ {range} ประมาณ {days} วัน"} in Thai, {@code "item(s) {range}
     * approximately {days} days"} in English — for the caller to {@code String.join("  ", ...)}.
     */
    private static List<String> leadTimeGroups(List<DealQuotationItemDto> items, boolean english) {
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
            boolean hasLeadTime = hasImportLeadTime(item);
            boolean continuesGroup = hasLeadTime && groupMin != null
                && min.equals(groupMin) && max.equals(groupMax) && item.seq() == groupLastSeq + 1;
            if (continuesGroup) {
                groupLastSeq = item.seq();
            } else {
                flushLeadTimeGroup(groups, groupMin, groupMax, groupFirstSeq, groupLastSeq, english);
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
        flushLeadTimeGroup(groups, groupMin, groupMax, groupFirstSeq, groupLastSeq, english);
        return groups;
    }

    // ── When ระยะเวลานำเข้า is not chosen on any item, the line is removed from the
    // หมายเหตุ ───────────────────────────────────────────────────────────────────────────────────

    /** true when ANY item on the document carries a lead time. A document made entirely of PLAIN rows (no lead-time
     * fields at all) or a DRAFT preview before any TILE row's lead time has been filled in both answer false here. */
    private static boolean hasAnyLeadTime(List<DealQuotationItemDto> items) {
        return items.stream().anyMatch(DealQuotationRenderAdapter::hasImportLeadTime);
    }

    /** A stock line (IN_THAILAND) is not imported, so its lead time (if any is stored) is never printed. */
    private static boolean hasImportLeadTime(DealQuotationItemDto item) {
        return item.leadTimeMinDays() != null && item.leadTimeMaxDays() != null
            && !th.co.glr.hr.pricingrequest.PricingRequestService.STOCK_IN_THAILAND.equals(item.stockSource());
    }

    // Matches the leading "N." numbering convention every OTHER remark line carries as a literal prefix baked into
    // the string — never a digit that is merely part of the sentence, since none of those constants opens with one
    // for any other reason.
    private static final java.util.regex.Pattern LEADING_REMARK_NUMBER =
        java.util.regex.Pattern.compile("^(\\d+)\\.");

    /** Drops the lead-time line at leadTimeLineIndex from lines when hasLeadTime is false — ENTIRELY, not blanked —
     * and renumbers every subsequent line's leading "N." digit down by one, so e.g. old 4→3, 5→4, 6→5, 7→6, 8→7
     * with NO gap. */
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

    /** Formats and appends ONE flushed group (Thai or English, per english) — shared by #leadTimeGroups, the ONE
     * place either language's grouping traversal lives now. */
    private static void flushLeadTimeGroup(List<String> groups, Integer min, Integer max,
                                            Integer first, Integer last, boolean english) {
        if (min == null) {
            return;
        }
        // An exact lead time (min == max) reads "ประมาณ 30 วัน"/
        // "approximately 30 days", not the pointless "30-30 วัน" a range formatter would print.
        String days = min.equals(max) ? String.valueOf(min) : min + "-" + max;
        if (english) {
            String range = first.equals(last) ? "item " + first : "items " + first + "-" + last;
            // A single-value exact lead time (min == max) is
            // pluralized by that value -- "approximately 1 day", not "approximately 1 days". A
            // RANGE ("75-90") is always plural regardless of its endpoints, so only the single-value
            // branch routes through the shared helper.
            String dayWord = min.equals(max) ? DealQuotationLines.plural(min, "day", "days") : "days";
            groups.add(range + " approximately " + days + " " + dayWord);
        } else {
            String range = first.equals(last) ? String.valueOf(first) : first + "-" + last;
            groups.add("รายการที่ " + range + " ประมาณ " + days + " วัน");
        }
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
     * these strings rather than a change to any logic.
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
    /** The English twin of #depositLine -- same zero-deposit rule, same two remainderMode variants, English words. */
    private static String englishDepositLine(int depositPct, String fullPaymentTerm, String remainderMode,
                                              Integer creditDays, String remainderText) {
        // English twin of the Thai branch above; see its own comment for why 100% and the 0%
        // fullPaymentTerm sentences are different cases.
        if (depositPct == 100) {
            return "2.Full payment (100%) is required upon order confirmation.";
        }
        if (depositPct != 0) {
            return "2.A deposit of " + depositPct + "% is required upon order confirmation, " + remainderText + ".";
        }
        // Item 4 (V181) English twin of the Thai branch above — same legacy fallback, same reason.
        String termText = fullPaymentTermEnglishText(fullPaymentTerm);
        if (termText != null) {
            return "2." + termText;
        }
        if ("CREDIT".equals(remainderMode)) {
            // Singular "day credit" at exactly 1 day.
            int days = creditDays != null ? creditDays : 0;
            return "2.Full payment is due on " + days + " " + DealQuotationLines.plural(days, "day", "days")
                + " credit.";
        }
        return "2.Full payment is due before or upon delivery.";
    }

    /** The English twin of #fullPaymentTermThaiText — same three codes, same null-for-unrecognised/legacy contract. */
    private static String fullPaymentTermEnglishText(String code) {
        if (WastageCalculator.FULL_PAYMENT_TERM_BEFORE_DELIVERY.equals(code)) {
            return "Full payment (100%) is required before delivery.";
        }
        if (WastageCalculator.FULL_PAYMENT_TERM_ON_DELIVERY.equals(code)) {
            return "Full payment (100%) is required upon delivery.";
        }
        if (WastageCalculator.FULL_PAYMENT_TERM_ON_OR_BEFORE_DELIVERY.equals(code)) {
            return "Full payment (100%) is required upon or before delivery.";
        }
        return null;
    }

    private static List<String> englishRemarkLines(DealQuotationDto quotation, List<String> bankBlockLines) {
        LocalDate offerDate = quotation.offerDate() != null ? quotation.offerDate() : LocalDate.now(BANGKOK);
        int depositPct = quotation.depositPercent() != null ? quotation.depositPercent() : DEFAULT_DEPOSIT_PERCENT;
        // Singular "day credit" at exactly 1 day.
        String remainderText;
        if ("CREDIT".equals(quotation.remainderMode())) {
            int creditDays = quotation.creditDays() != null ? quotation.creditDays() : 0;
            remainderText = "the balance on " + creditDays + " " + DealQuotationLines.plural(creditDays, "day", "days")
                + " credit";
        } else {
            remainderText = "the balance before or upon delivery";
        }
        int validityDays = quotation.validityDays() != null ? quotation.validityDays() : 30;
        String validityDayWord = DealQuotationLines.plural(validityDays, "day", "days");
        // V178, English twin of the Thai line7 branch above — same guard, same byte-identical DAYS-mode output.
        boolean validityIsDate = WastageCalculator.VALIDITY_MODE_DATE.equals(quotation.validityMode())
            && quotation.validityUntil() != null
            && hasSpecialPricing(quotation);
        String validityUntilEn = validityIsDate ? shortEnglishDate(quotation.validityUntil()) : null;

        // ⚠️ EXACTLY 8 lines either way — QuotationRenderer#REMARK_HEAD_ROWS has 8 slots and fewer falls back to the
        // legacy 3-line layout, which leaves the template's Thai continuation rows visible on an English page.
        boolean hasBankBlock = bankBlockLines != null && bankBlockLines.size() == 3
            && bankBlockLines.stream().noneMatch(DealQuotationRenderAdapter::blank);

        List<String> lines = new ArrayList<>();
        lines.add("1.The quantities above are as received on " + shortEnglishDate(offerDate)
            + ". Please re-confirm the actual quantities with your installer before ordering.");
        lines.add(englishDepositLine(depositPct, quotation.fullPaymentTerm(), quotation.remainderMode(),
            quotation.creditDays(), remainderText));
        int leadTimeLineIndex;
        if (hasBankBlock) {
            // The block sits straight after the PAYMENT remark, unnumbered, as it does in both of her samples. Numbering
            // then runs on 3, 4, 5 — NOT her 5, 6, 7: her skipped 4 is a spreadsheet artefact rather than intent, and
            // reproducing it would print a gap a customer reads as a missing term.
            lines.addAll(bankBlockLines);
            leadTimeLineIndex = lines.size();
            lines.add(englishLeadTimeLine(quotation.items()));
            // ⚠️ Each remark is ONE merged B..I cell that NEVER wraps, so an over-long line is not wrapped but CUT at the
            // border. Both are now well inside the 130 that the pre-existing line 1 proves fits in both PDF engines, and
            // DealQuotationEnglishFormTest guards the length of every line in both layouts.
            lines.add(validityIsDate
                ? "4.Special price for orders with deposit paid by " + validityUntilEn
                    + "; sizes may vary slightly within ISO and TIS tolerances."
                : "4.Price validity : " + validityDays + " " + validityDayWord + " from the date of this quotation; "
                    + "sizes may vary slightly within ISO and TIS tolerances.");
            lines.add("5.Colours may vary slightly between production lots. "
                + "Goods sold are not returnable or exchangeable.");
        } else {
            leadTimeLineIndex = lines.size();
            lines.add(englishLeadTimeLine(quotation.items()));
            lines.add(BANK_BLOCK_PLACEHOLDER_LINE);
            lines.add(validityIsDate
                ? "5.Special price for orders with deposit paid by " + validityUntilEn + "."
                : "5.Price validity : " + validityDays + " " + validityDayWord + " from the date of this quotation.");
            lines.add("6.Actual tile sizes may vary slightly from the sizes stated above, within ISO "
                + "and TIS tolerances.");
            lines.add("7.Colours and patterns may vary slightly from the samples, as goods come from "
                + "different production lots.");
            lines.add(enNoReturnLine(8));
        }
        // Same drop-and-renumber as the Thai branch, sharing the SAME
        // hasAnyLeadTime question — see #dropLeadTimeLineAndRenumber. leadTimeLineIndex is 5 in the
        // bank-block layout (after the 2 numbered + 3 unnumbered bank lines) and 2 in the no-bank
        // layout; either way the method only ever renumbers a NUMBERED line, so the bank block's
        // own unnumbered lines are untouched.
        return dropLeadTimeLineAndRenumber(lines, hasAnyLeadTime(quotation.items()), leadTimeLineIndex);
    }

    /** The standalone "no exchange or return" sentence — the English twin of #LINE6, reused (not retyped) by both
     * the tile no-bank-block layout above (remark 8) and the non-tile set below, so the two can never drift apart. */
    private static final String EN_LINE_NO_RETURN =
        "Goods sold are not returnable or exchangeable. Please check the order "
        + "carefully before confirming or signing for delivery.";

    /** {@link #EN_LINE_NO_RETURN}, renumbered — the English twin of {@link #noReturnLine}. */
    private static String enNoReturnLine(int number) {
        return number + "." + EN_LINE_NO_RETURN;
    }

    // ── The NON-TILE English remark set (V182) — the same rule as
    // #nonTileRemarkLines, English equivalents, with the SAME optional bank block the tile set
    // carries (inserted straight after the payment remark, unnumbered, exactly as it does there —
    // see #englishRemarkLines's own Javadoc). Bank or no bank, the block never consumes a numbered
    // slot, so the numbering is IDENTICAL either way: 1/2/3/4. ─────────────────────────────────

    private static final String EN_NON_TILE_LINE1 =
        "1.The price above includes delivery to the ground floor within the Bangkok Metropolitan "
        + "Area, but excludes installation.";

    /** The English twin of #NON_TILE_LINE3_FALLBACK — same unreachable-in-practice contract (see that constant's
     * Javadoc). */
    private static final String EN_NON_TILE_LINE3_FALLBACK = "3.Delivery : approximately ...... days";

    /** The English twin of {@link #nonTileLeadTimeLine} — same {@link #leadTimeGroups} grouping
      * (see that method's own Javadoc), same zero-deposit
      * clause-drop rule, English words. */
    private static String englishNonTileLeadTimeLine(List<DealQuotationItemDto> items, int depositPct) {
        if (!hasAnyLeadTime(items)) {
            return EN_NON_TILE_LINE3_FALLBACK;
        }
        String groups = String.join("  ", leadTimeGroups(items, true));
        String prefix = "3.If the factory has the goods ready to ship, the import lead time is " + groups;
        // English twin of the Thai branch above.
        if (depositPct == 0) {
            return prefix + ".";
        }
        if (depositPct == 100) {
            return prefix + ", after full payment is received.";
        }
        return prefix + ", after the " + depositPct + "% deposit is received.";
    }

    /** The English twin of {@link #nonTileRemarkLines}. */
    private static List<String> englishNonTileRemarkLines(DealQuotationDto quotation, List<String> bankBlockLines) {
        int depositPct = quotation.depositPercent() != null ? quotation.depositPercent() : DEFAULT_DEPOSIT_PERCENT;
        // Singular "day credit" at exactly 1 day.
        String remainderText;
        if ("CREDIT".equals(quotation.remainderMode())) {
            int creditDays = quotation.creditDays() != null ? quotation.creditDays() : 0;
            remainderText = "the balance on " + creditDays + " " + DealQuotationLines.plural(creditDays, "day", "days")
                + " credit";
        } else {
            remainderText = "the balance before or upon delivery";
        }
        boolean hasBankBlock = bankBlockLines != null && bankBlockLines.size() == 3
            && bankBlockLines.stream().noneMatch(DealQuotationRenderAdapter::blank);

        List<String> lines = new ArrayList<>();
        lines.add(EN_NON_TILE_LINE1);
        lines.add(englishDepositLine(depositPct, quotation.fullPaymentTerm(), quotation.remainderMode(),
            quotation.creditDays(), remainderText));
        if (hasBankBlock) {
            lines.addAll(bankBlockLines);
        }
        int leadTimeLineIndex = lines.size();
        lines.add(englishNonTileLeadTimeLine(quotation.items(), depositPct));
        lines.add(enNoReturnLine(4));
        return dropLeadTimeLineAndRenumber(lines, hasAnyLeadTime(quotation.items()), leadTimeLineIndex);
    }

    /**
     * The FALLBACK printed at remark 4 when the bank block is not fully configured.
     *
     * <p>The bank details live in {@code app.quotation.bank-block-line1..3}; they must never be
     * invented, since fabricated wire instructions on a customer-facing document would be
     * harmful. This line is what an English document prints when that configuration is missing,
     * partial, or blank — true as written, and never half a block.
     */
    private static final String BANK_BLOCK_PLACEHOLDER_LINE =
        "4.Payment by telegraphic transfer. Full bank details are issued with the proforma invoice.";

    /**
     * Remark 3 when NO row carries a lead time — typically a document made entirely of PLAIN rows
     * (freight, consumables, mosaic priced per SQM), which have no lead-time fields at all, or a
     * DRAFT/older re-render that predates {@code DealQuotationService#submit}'s new requirement
     * that every TILE row carry one.
     *
     * <p>It names no country and no stock wording (a lead-time line must not name a country
     * unrelated to the shipment).
     *
     * <p><b>Unreachable in practice:</b> {@link #englishRemarkLines} unconditionally runs
     * {@link #dropLeadTimeLineAndRenumber} with {@link #hasAnyLeadTime}, which is {@code false}
     * in EXACTLY the case that makes {@link #englishLeadTimeLine} return this constant — so this
     * value is computed and then immediately discarded by the caller; it can never reach a
     * rendered document. That drop is safe because {@code QuotationRenderer#REMARK_V2_MIN_LINES}
     * lets the renderer accept 7 lines as v2/full-remarks too. Kept (not deleted) as the value
     * {@link #englishLeadTimeLine} still has to return something for while composing the full
     * line list, and as a documented fallback should the two decisions (fallback text vs.
     * drop-the-line) ever be decoupled.
     */
    private static final String EN_LINE3_FALLBACK = "3.Delivery : approximately ...... days";

    /** The English twin of #leadTimeLine — same #leadTimeGroups grouping, same source data, English words. */
    private static String englishLeadTimeLine(List<DealQuotationItemDto> items) {
        return hasAnyLeadTime(items)
            ? "3.Delivery : " + String.join("  ", leadTimeGroups(items, true))
            : EN_LINE3_FALLBACK;
    }

    /**
     * The signature/sales name for this document: the ENGLISH name on an English document, the
     * Thai one otherwise — and the Thai one anyway when the employee has no English name recorded,
     * because a blank signature slot on a customer-facing document is worse than a Thai name on an
     * English page. Consequence: an employee with no
     * {@code first_name_en} prints in Thai on the English form until HR fills it in.
     */
    static String displayName(String thai, String english, boolean useEnglish) {
        if (useEnglish && !blank(english)) {
            return english.trim();
        }
        return thai;
    }

    // ── ผู้พิมพ์/พนักงานขาย print-name override (V179) ──────────────────────────────────────────
    // The rep can choose who to show for ผู้พิมพ์ and พนักงานขาย — PRINT-ONLY. Each
    // helper prefers the display-override fields when the corresponding *DisplayId is set, and
    // otherwise falls back to the real-name fields, so createdById/salesRepId (deal ownership,
    // access, commission) never change and a quotation with no *DisplayId prints the real names.

    /** The ผู้พิมพ์ signature-slot name: printedByDisplayName(En) when printedByDisplayId is set, else the real
     * createdByName(En). */
    static String printedByName(DealQuotationDto quotation, boolean english) {
        if (quotation.printedByDisplayId() != null) {
            return displayName(quotation.printedByDisplayName(), quotation.printedByDisplayNameEn(), english);
        }
        return displayName(quotation.createdByName(), quotation.createdByNameEn(), english);
    }

    /** The พนักงานขาย signature-slot / header "Sales/{name}" name: salesRepDisplayName(En) when salesRepDisplayId
     * is set, else the real salesRepName(En). */
    static String salesRepDisplayNameOrReal(DealQuotationDto quotation, boolean english) {
        if (quotation.salesRepDisplayId() != null) {
            return displayName(quotation.salesRepDisplayName(), quotation.salesRepDisplayNameEn(), english);
        }
        return displayName(quotation.salesRepName(), quotation.salesRepNameEn(), english);
    }

    /** The header "T.{phone}" that pairs with #salesRepDisplayNameOrReal — the SAME salesRepDisplayId decides both,
     * so the printed name and phone can never come from two different employees. */
    static String salesRepDisplayPhoneOrReal(DealQuotationDto quotation) {
        return quotation.salesRepDisplayId() != null ? quotation.salesRepDisplayPhone() : quotation.salesRepPhone();
    }

    // Zero-pad dd/mm ("01/09/2569", not "1/9/2569") -- same as
    // QuotationRenderer#shortThaiDate (its own separate copy, for the legacy remark-line path).
    private static String shortThaiDate(LocalDate d) {
        return String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear() + 543);
    }

    /** ⚠️ The English remark date. dd/MM/yyyy — a CE year, where #shortThaiDate adds 543 for the Buddhist era.
     * Getting this wrong is the single easiest mistake on the English form (the owner's spec says so in as many
     * words), so the two live next to each other here rather than one being reused with a flag. */
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
     * SAME string as the customer name (which would print the customer name twice:
     * "...บริษัท นันทวัน จำกัด   /   บริษัท นันทวัน จำกัด..."). */
    static boolean printContactPart(String contactName, String customerName) {
        if (blank(contactName)) {
            return false;
        }
        return !normalizeWhitespace(contactName).equalsIgnoreCase(normalizeWhitespace(customerName));
    }

    /** Trims and collapses internal whitespace runs to one space, so two names that differ only in
     * spacing still compare equal in {@link #printContactPart}. Also drops a trailing "."/",":
     * a contact re-typed with a stray trailing full stop or comma
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
    // These are checked as a SUBSTRING anywhere in the (lower-cased) name — a Thai "จำกัด"/"(มหาชน)" suffix, or an
    // English company-type phrase, can trail after a name a leading-prefix check alone would miss (e.g. a contact
    // typed as "นันทวัน จำกัด" with no "บริษัท").
    private static final String[] ORG_NAME_MARKERS = {
        "จำกัด", "(มหาชน)", "co., ltd", "co.,ltd",
    };
    // These are short enough that a bare substring match false-
    // positives on an ordinary name — "Somchai INchana" contains " inc", "John HoLLCroft" contains
    // "llc". Matched with \b word boundaries instead, via #ORG_NAME_WORD_PATTERN.
    private static final java.util.regex.Pattern ORG_NAME_WORD_PATTERN = java.util.regex.Pattern.compile(
        "\\b(company|limited|ltd|inc|llc)\\b");

    /** Is {@code name} an organisation rather than a person,
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

    // ── "คุณ" double-prefix guard ────────────────────────────────────────────────────────────

    // Longest-prefix-first so "นางสาว" is never left matching only as far as "นาง" -- doesn't actually change
    // which names match, but keeps the list read in the same "most specific first" order as #ORG_NAME_PREFIXES
    // above rather than inviting a future maintainer to wonder why it isn't.
    private static final String[] THAI_HONORIFIC_PREFIXES = {
        "นางสาว", "นาย", "นาง", "คุณ", "ดร.",
    };

    /** {@code true} when {@code name} (whitespace-normalised) already OPENS with one of {@link
     * #THAI_HONORIFIC_PREFIXES} -- contact_name = "คุณสมหญิง ใจดี" would otherwise print
     * "เรียน คุณคุณสมหญิง ใจดี" because the stored value already carries its own
     * honorific and the attn-line builder would prefix a second one. Checked as a PREFIX only,
     * exactly like {@link #ORG_NAME_PREFIXES} -- a name that merely contains "นาง" mid-word does
     * not count.
     *
     * <p>A bare {@code startsWith} would also match real Thai given names
     * that happen to OPEN with the same letters as an honorific -- "คุณากร ใจดี", "คุณาพร",
     * "คุณัญญา", "นายิกา" all start with "คุณ"/"นาย", which would read as "already prefixed" and
     * print "เรียน คุณากร ใจดี" with no "คุณ" at all. {@link #isThaiHonorificBoundary} is the
     * guard: a prefix only counts when the very next character is NOT a Thai FOLLOWING vowel/tone
     * mark (one that attaches to the preceding consonant rather than opening a new syllable) --
     * the same signal that tells "คุณ" + "สมหญิง" (a genuine honorific + name, next char a plain
     * consonant) apart from "คุณ" + "ากร" (one word, next char a following vowel). A leading vowel
     * (เ/แ/โ/ใ/ไ) always starts a new syllable and so is still a valid boundary -- "คุณเอก" is
     * "คุณ" + "เอก", not a longer word. */
    static boolean hasThaiHonorificPrefix(String name) {
        if (blank(name)) {
            return false;
        }
        String trimmed = normalizeWhitespace(name);
        for (String prefix : THAI_HONORIFIC_PREFIXES) {
            if (trimmed.startsWith(prefix) && isThaiHonorificBoundary(trimmed, prefix.length())) {
                return true;
            }
        }
        return false;
    }

    /** true when index afterPrefix of trimmed is either past the end of the string, or a character that is NOT a
     * Thai "following" vowel sign or tone/diacritic mark. */
    private static boolean isThaiHonorificBoundary(String trimmed, int afterPrefix) {
        if (afterPrefix >= trimmed.length()) {
            return true;
        }
        char c = trimmed.charAt(afterPrefix);
        boolean followingVowelOrTone = (c >= 'ะ' && c <= 'ฺ') || (c >= '็' && c <= '๎');
        return !followingVowelOrTone;
    }

    // ── Customer-contact line phone / e-mail ─────────────────────────────────────────────────
    // No fallback: if the typed field is null, nothing is rendered.

    /** The phone the customer-contact line prints -- ONLY the ผู้ติดต่อ
     * phone typed on the quotation ({@code orderedByPhone}), label-stripped and trimmed. Null when
     * it is null, blank or only a label ("โทร."): no customer-master or contact fallback. Both
     * TH/EN branches. */
    static String printedPhone(DealQuotationDto quotation) {
        String typed = stripPhoneLabel(quotation.orderedByPhone());
        return typed.isEmpty() ? null : typed;
    }

    /** The e-mail twin of {@link #printedPhone}: ONLY {@code orderedByEmail}, trimmed; null when
     * null or blank. */
    static String printedEmail(DealQuotationDto quotation) {
        String typed = quotation.orderedByEmail();
        return blank(typed) ? null : typed.trim();
    }

    // ── the customer phone's own label ──────────────────────────────────────────────────────

    /** A phone value that OPENS with its own label, which both header branches above then prefix a second one onto.
     * Matched case-insensitively, with an optional "."/":" separator, and — this is the safety catch — only when
     * what FOLLOWS is the start of an actual number (0-9, + or (), or nothing at all. */
    private static final java.util.regex.Pattern PHONE_LABEL_PREFIX = java.util.regex.Pattern.compile(
        "^(?:โทรศัพท์|เบอร์โทรศัพท์|เบอร์โทร|เบอร์|โทร|telephone|tel|phone)\\s*[.:：]?\\s*(?=[0-9+(]|$)",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Trims {@code phone} and drops a leading โทร./Tel.-style label off it.
     *
     * <p>Without this, B6 prints "โทร. โทร 02 314 354-2": the stored value itself carries
     * {@code phone = "โทร 02 314 354-2"}, because the imported GL&amp;R customer directory stored
     * the Thai label inside the value, and it is not one bad row (roughly 7% of
     * {@code customers.customer} rows open their phone with a โทร/Tel label), so every quotation
     * for any of them would print the same doubled line. The label is stripped at PRINT time rather than cleaned out of the master data: the same
     * column also holds genuinely free-form text (contact names, e-mail addresses, "ต่อ 233"), so a
     * blanket data migration over it would be the riskier change, and this keeps the document
     * correct for anything typed that way in future too.
     *
     * <p>Only a LEADING label is touched. "คุณสมชาย โทร 08x-xxx-xxxx" is a genuine free-text note,
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
