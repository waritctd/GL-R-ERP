package th.co.glr.hr.dealquotation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class DealQuotationRequests {
    private DealQuotationRequests() {}

    /** One item row, as typed by Sales in the editor. Server recomputes every derived number —
     * see {@link WastageCalculator} — never trusts a client-sent total.
     *
     * <p>M4: {@code model}/{@code color}/{@code texture}/{@code sizeText}/{@code brand} are
     * capped at 255, matching {@code sales.quotation_item}'s LIVE column widths — V49 created them
     * at VARCHAR(80), but V162 widened model/color/texture/size (and brand was always 255); a
     * stale {@code max = 80} here would 400 a value the column happily stores. {@code
     * wastageValue}'s bound is MODE-dependent (percent vs. flat piece count) and so is enforced in
     * {@link WastageCalculator#calculate}, not here — see that class's own M4 note.
     *
     * <p><b>Type-aware price validation (quotation v3, 2026-09-11).</b> {@code unitPrice} used to
     * carry {@code @NotNull @DecimalMin("0.01")}. It cannot any more: an ADJUSTMENT row has no
     * rep-typed price at all (the system derives it from the rows above), so a blanket bean
     * annotation would 400 the very row type this pass exists to add. The rule was made
     * TYPE-AWARE rather than relaxed — {@code DealQuotationService#requirePriceValidForType}
     * still refuses a null, zero or negative price on a TILE or PLAIN row, on EVERY path
     * including the lenient {@code calculate-line} preview, so nothing a rep could type before is
     * accepted now. {@code quantityMode}/{@code wastageMode} lost {@code @NotBlank} for the same
     * reason (a PLAIN row has neither) and are re-required for TILE rows in that same method.
     */
    public record ItemInput(
        @Size(max = 255) String locationLabel,
        Long catalogPriceId,
        @Size(max = 255) String productCode,
        @Size(max = 255) String brand,
        @Size(max = 255) String model,
        @Size(max = 255) String color,
        @Size(max = 255) String texture,
        @Size(max = 255) String sizeText,
        BigDecimal thicknessMm,
        BigDecimal sqmPerPiece,
        // "AREA" | "PIECES" — see QuantityMode. Only meaningful on a TILE row.
        String quantityMode,
        @DecimalMax("999999") BigDecimal areaSqm,
        @Max(1_000_000) Integer piecesInput,
        // "PERCENT" | "PIECES" | "NONE" — see WastageMode. Only meaningful on a TILE row.
        String wastageMode,
        BigDecimal wastageValue,
        @Min(1) @Max(1000) Integer piecesPerBox,
        // ⚠️ NO @NotNull/@DecimalMin here any more — see the record Javadoc's "Type-aware price
        // validation" note. The rule is enforced per line type in DealQuotationService.
        BigDecimal unitPrice,
        @DecimalMin("0") @DecimalMax("100") BigDecimal discountPct,
        @Size(max = 40) String originCountry,
        Integer leadTimeMinDays,
        Integer leadTimeMaxDays,
        @Size(max = 4000) String itemNotes,

        // ── quotation v3 (owner feedback pass 3, 2026-09-11) ──────────────────────────────────
        /** "TILE" (default when null/blank) | "PLAIN" | "ADJUSTMENT" — see WastageCalculator. */
        String lineType,
        /** PLAIN/ADJUSTMENT only. On a TILE row the description is auto-composed from
         * รุ่น/สี/ผิว/ขนาด and anything sent here is IGNORED, never printed. On an ADJUSTMENT row
         * with a percent it is also derived; it is honoured only for a FLAT-amount adjustment. */
        @Size(max = 2000) String description,
        /** PLAIN only — the printed จำนวน. BigDecimal, not int: her documents happen to carry
         * whole numbers (1 JOB, 85 Bags, 22 แผ่น) but a half-day of a service or a part ตร.ม. is a
         * perfectly ordinary thing to quote, and an int here would silently 500 on it. */
        @DecimalMax("999999") BigDecimal quantity,
        /** PLAIN only — the printed หน่วย (JOB, Bags, Barrels, แผ่น, ชุด). Capped at 30 to match
         * sales.quotation_item.raw_unit's LIVE column width (V49), not a guessed round number. */
        @Size(max = 30) String unit,
        /** SPECIAL_SQM price mode, TILE rows — ราคาพิเศษ in บาท per ตร.ม., INCLUDING VAT. */
        @DecimalMax("9999999") BigDecimal specialPriceSqm,
        /** DIRECT_NET price mode, TILE rows — the per-piece net price, typed straight in. */
        @DecimalMax("9999999") BigDecimal directNetPrice,
        /** ADJUSTMENT only — percent of the non-adjustment rows above this one. */
        @DecimalMin("0") @DecimalMax("100") BigDecimal adjustmentPct,
        /** ADJUSTMENT only — the "สั่งซื้อภายใน" date the composed description prints. */
        LocalDate adjustmentDeadline,
        /** ADJUSTMENT only — a FLAT baht amount, as the alternative to a percent. Positive; the
         * row prints it negative. Exactly one of this and {@code adjustmentPct} must be given. */
        @DecimalMax("99999999") BigDecimal adjustmentAmount
    ) {
        /**
         * The pre-v3 22-argument shape, kept so the many existing call sites (and every test that
         * builds a plain tile row) compile unchanged — the SAME device, for the same reason, as
         * {@code QuotationRenderModel.Signatories}' 5-argument legacy constructor. Defaults every
         * v3 field to null, which reads as: a TILE row, in the quotation's own price mode.
         *
         * <p>Jackson deserialises records through the CANONICAL constructor, so this overload
         * never competes with it on the wire.
         */
        public ItemInput(String locationLabel, Long catalogPriceId, String productCode, String brand,
                         String model, String color, String texture, String sizeText,
                         BigDecimal thicknessMm, BigDecimal sqmPerPiece, String quantityMode,
                         BigDecimal areaSqm, Integer piecesInput, String wastageMode,
                         BigDecimal wastageValue, Integer piecesPerBox, BigDecimal unitPrice,
                         BigDecimal discountPct, String originCountry, Integer leadTimeMinDays,
                         Integer leadTimeMaxDays, String itemNotes) {
            this(locationLabel, catalogPriceId, productCode, brand, model, color, texture, sizeText,
                thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput, wastageMode,
                wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes,
                null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * {@code contactId} (owner feedback F2, 2026-09-10 — ผู้สั่งซื้อ) is OPTIONAL on the wire and
     * defaults to the deal's own contact ({@code sales.ticket.contact_id}); what is REQUIRED is
     * that one resolves — create/update/submit answer 400 "กรุณาระบุผู้สั่งซื้อ" otherwise. The
     * chosen contact must belong to the deal's customer; its name/phone/email are snapshotted onto
     * the quotation (V167). Enforced in {@code DealQuotationService}, not by bean validation, because
     * the default is a DB lookup.
     */
    public record UpsertDealQuotationRequest(
        Long contactId,
        @Size(max = 20) String deptCode,
        @Size(max = 20) String unitCode,
        LocalDate offerDate,
        @Min(0) @Max(100) Integer depositPercent,
        // "CREDIT" | "ON_DELIVERY"
        @Pattern(regexp = "CREDIT|ON_DELIVERY", message = "ต้องเป็น CREDIT หรือ ON_DELIVERY")
        String remainderMode,
        @Min(0) @Max(365) Integer creditDays,
        @Min(1) @Max(365) Integer validityDays,
        @Size(max = 4000) String customerNotes,
        /**
         * "NET" (default when null) | "SPECIAL_SQM" | "DIRECT_NET" — quotation v3, owner feedback
         * pass 3. PER-QUOTATION rather than per-item on purpose: in all nine of the owner's
         * documents every tile row in a document uses the same mode, so one selector is the least
         * typing. PLAIN rows always carry a direct price and ignore this, which is what still lets
         * a document mix a ราคาพิเศษ tile with a freight line.
         */
        @Pattern(regexp = "NET|SPECIAL_SQM|DIRECT_NET",
            message = "ต้องเป็น NET, SPECIAL_SQM หรือ DIRECT_NET")
        String priceMode,
        /**
         * "TH" (default when null) | "EN" — quotation v3b (V169). TH prints the Thai F-SM-002 with
         * its 7% VAT row; EN prints the English F-SM-008 in USD with NO VAT row. The ONE thing the
         * rep picks: {@code currency} and the VAT treatment both default from it.
         */
        @Pattern(regexp = "TH|EN", message = "ต้องเป็น TH หรือ EN")
        String documentLanguage,
        /**
         * "THB" | "USD" — optional; defaults from {@code documentLanguage} (TH→THB, EN→USD) in
         * {@code DealQuotationService#resolveCurrency}, which also REFUSES a combination the forms
         * do not have (a THB English document or a USD Thai one). Kept on the wire rather than
         * being purely derived so a later "EN document billed in THB" ruling is a service change,
         * not another schema column.
         */
        @Pattern(regexp = "THB|USD", message = "ต้องเป็น THB หรือ USD")
        String currency,
        @NotEmpty List<@Valid ItemInput> items
    ) {
        /** The pre-v3 shape (no {@code priceMode}) — same legacy-constructor device as
         * {@link ItemInput}'s, defaulting the mode to null, which reads as {@code NET}. */
        public UpsertDealQuotationRequest(Long contactId, String deptCode, String unitCode,
                                          LocalDate offerDate, Integer depositPercent,
                                          String remainderMode, Integer creditDays,
                                          Integer validityDays, String customerNotes,
                                          List<ItemInput> items) {
            this(contactId, deptCode, unitCode, offerDate, depositPercent, remainderMode,
                creditDays, validityDays, customerNotes, null, items);
        }

        /** The pre-v3b shape (priceMode but no language/currency) — defaults both to null, which
         * reads as a TH/THB document, i.e. exactly what every v3 client already sends. */
        public UpsertDealQuotationRequest(Long contactId, String deptCode, String unitCode,
                                          LocalDate offerDate, Integer depositPercent,
                                          String remainderMode, Integer creditDays,
                                          Integer validityDays, String customerNotes,
                                          String priceMode, List<ItemInput> items) {
            this(contactId, deptCode, unitCode, offerDate, depositPercent, remainderMode,
                creditDays, validityDays, customerNotes, priceMode, null, null, items);
        }
    }

    public record ApproveRequest(@Size(max = 2000) String note) {}

    public record RejectRequest(@NotBlank @Size(max = 2000) String reason) {}

    public record CancelRequest(@Size(max = 2000) String reason) {}
}
