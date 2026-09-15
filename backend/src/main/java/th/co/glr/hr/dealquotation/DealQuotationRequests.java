package th.co.glr.hr.dealquotation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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
         * perfectly ordinary thing to quote, and an int here would silently 500 on it.
         *
         * <p>{@code @Digits} matches {@code sales.quotation_item.qty}'s NUMERIC(12,2) EXACTLY
         * (review fix F4). Without it a 3dp quantity is accepted, the amount is computed from the
         * unrounded value, Postgres stores the 2dp one — and the printed row no longer multiplies
         * out: จำนวน 1.01 × คงเหลือ X ≠ เป็นเงิน, an arithmetic error the customer can check. */
        @DecimalMax("999999") @Digits(integer = 10, fraction = 2) BigDecimal quantity,
        /** PLAIN only — the printed หน่วย (JOB, Bags, Barrels, แผ่น, ชุด). Capped at 30 to match
         * sales.quotation_item.raw_unit's LIVE column width (V49), not a guessed round number. */
        @Size(max = 30) String unit,
        /** SPECIAL_SQM price mode, TILE rows — ราคาพิเศษ in บาท per ตร.ม., INCLUDING VAT.
         * {@code @Digits} matches {@code sales.quotation_item.special_price_sqm}'s NUMERIC(12,2)
         * (review fix F4) — the value is printed VERBATIM on the ราคาพิเศษ sub-line, so a 3dp input
         * would print one figure and store another. */
        @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal specialPriceSqm,
        /** DIRECT_NET price mode, TILE rows — the per-piece net price, typed straight in. */
        @DecimalMax("9999999") BigDecimal directNetPrice,
        /** ADJUSTMENT only — percent of the non-adjustment rows above this one. {@code @Digits}
         * matches {@code sales.quotation_item.adjustment_pct}'s NUMERIC(6,3) (review fix F4);
         * a 4dp percent applied to a seven-figure base would move the stored discount by baht. */
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 3) BigDecimal adjustmentPct,
        /** ADJUSTMENT only — the "สั่งซื้อภายใน" date the composed description prints. */
        LocalDate adjustmentDeadline,
        /** ADJUSTMENT only — a FLAT baht amount, as the alternative to a percent. Positive; the
         * row prints it negative. Exactly one of this and {@code adjustmentPct} must be given. */
        @DecimalMax("99999999") BigDecimal adjustmentAmount,

        /**
         * GLA-75 (V170), stable-id follow-up: the EXISTING item's id, exactly as
         * {@code DealQuotationItemDto#id} served it — optional, and meaningful on UPDATE only.
         *
         * <p>{@code PUT /api/deal-quotations/{id}} keeps item ids STABLE: an input whose {@code id}
         * matches one of THIS quotation's current item ids — and is not already claimed by an
         * earlier item in the same payload — is UPDATED IN PLACE (same {@code
         * quotation_item_id}), so its picture (and its {@code /items/{itemId}/picture} URL)
         * survives the save untouched. An input with a null id, a foreign id (another quotation's),
         * a stale id (no longer one of this quotation's rows), or a duplicate of an id already
         * claimed earlier in the same payload is INSERTED as a new row instead, with no picture.
         * Any of this quotation's rows not claimed by the payload is deleted, and its picture is
         * dropped unless another row (e.g. a parent revision sharing it) still references it.
         * Ignored on create and by calculate-line.
         */
        Long id,

        /**
         * Owner decision 2026-09-13 (V176): the supplier-stated square metres in ONE box — the
         * factor an ENGLISH per-sqm quotation prints its quantity from ({@code boxes × sqmPerBox},
         * 2dp). Optional on the wire; stored on every TILE row that carries it, and REQUIRED (with
         * {@code piecesPerBox}) only for SPECIAL_SQM on an EN document. {@code @Digits} matches
         * {@code sales.quotation_item.sqm_per_box}'s NUMERIC(10,6).
         */
        @DecimalMin("0") @DecimalMax("9999") @Digits(integer = 4, fraction = 6) BigDecimal sqmPerBox
    ) {
        /** The pre-V176 canonical shape (with {@link #id}, no {@link #sqmPerBox}) — kept so every
         * existing construction site compiles unchanged. */
        public ItemInput(String locationLabel, Long catalogPriceId, String productCode, String brand,
                         String model, String color, String texture, String sizeText,
                         BigDecimal thicknessMm, BigDecimal sqmPerPiece, String quantityMode,
                         BigDecimal areaSqm, Integer piecesInput, String wastageMode,
                         BigDecimal wastageValue, Integer piecesPerBox, BigDecimal unitPrice,
                         BigDecimal discountPct, String originCountry, Integer leadTimeMinDays,
                         Integer leadTimeMaxDays, String itemNotes, String lineType, String description,
                         BigDecimal quantity, String unit, BigDecimal specialPriceSqm,
                         BigDecimal directNetPrice, BigDecimal adjustmentPct, LocalDate adjustmentDeadline,
                         BigDecimal adjustmentAmount, Long id) {
            this(locationLabel, catalogPriceId, productCode, brand, model, color, texture, sizeText,
                thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput, wastageMode,
                wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes, lineType, description, quantity, unit, specialPriceSqm,
                directNetPrice, adjustmentPct, adjustmentDeadline, adjustmentAmount, id, null);
        }

        /** The v3 shape — every field except {@link #id}; see the class-level note on the legacy
         * constructors below. Defaults {@code id} to null (a brand-new item, no picture). */
        public ItemInput(String locationLabel, Long catalogPriceId, String productCode, String brand,
                         String model, String color, String texture, String sizeText,
                         BigDecimal thicknessMm, BigDecimal sqmPerPiece, String quantityMode,
                         BigDecimal areaSqm, Integer piecesInput, String wastageMode,
                         BigDecimal wastageValue, Integer piecesPerBox, BigDecimal unitPrice,
                         BigDecimal discountPct, String originCountry, Integer leadTimeMinDays,
                         Integer leadTimeMaxDays, String itemNotes, String lineType, String description,
                         BigDecimal quantity, String unit, BigDecimal specialPriceSqm,
                         BigDecimal directNetPrice, BigDecimal adjustmentPct, LocalDate adjustmentDeadline,
                         BigDecimal adjustmentAmount) {
            this(locationLabel, catalogPriceId, productCode, brand, model, color, texture, sizeText,
                thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput, wastageMode,
                wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes, lineType, description, quantity, unit, specialPriceSqm,
                directNetPrice, adjustmentPct, adjustmentDeadline, adjustmentAmount, (Long) null);
        }

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
                null, null, null, null, null, null, null, null, null, (Long) null);
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
        /**
         * Owner feedback 2026-09-14 (V178): "DAYS" (default when null) | "DATE" — remark 7's
         * second variant, กำหนดยืนยันราคา by an exact calendar date rather than a day count.
         * {@code null}/blank means DAYS, same device as {@code priceMode}/{@code
         * documentLanguage} above. On UPDATE a missing value keeps the STORED mode, for the SAME
         * reason those two fields do (see {@code DealQuotationService#update}) — a client that
         * omits it must not silently flip a DATE document back to counting days from today.
         */
        @Pattern(regexp = "DAYS|DATE", message = "ต้องเป็น DAYS หรือ DATE") String validityMode,
        /**
         * DATE mode only — the exact "ภายในวันที่" deadline. Required by
         * {@code DealQuotationService} when {@code validityMode} resolves to DATE; ignored
         * (stored NULL) in DAYS mode.
         */
        LocalDate validityUntil,
        @Size(max = 4000) String customerNotes,
        /**
         * "NET" | "SPECIAL_SQM" | "DIRECT_NET" — quotation v3, owner feedback pass 3.
         *
         * <p><b>null means different things on create and update</b> (review fix F1). On CREATE it
         * means NET — a brand-new document with no mode chosen is a NET document. On UPDATE it
         * means KEEP THE STORED MODE, because defaulting to NET there silently reprices every
         * SPECIAL_SQM/DIRECT_NET row the moment a client PUTs without this field. See
         * {@code DealQuotationService#update}.
         *
         * <p>PER-QUOTATION rather than per-item on purpose: in all nine of the owner's
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
        /**
         * V179 (owner feedback #4, 2026-09-14) — PRINT-ONLY override: when non-null, the ผู้พิมพ์
         * signature slot prints THIS employee's name instead of the real {@code createdBy}'s. Does
         * NOT change who created the document, who may edit it, or anything about access/commission.
         * Must name an active employee in the eligible union (sales-division member OR a
         * {@code can_create_quotation} grant holder — {@code DealQuotationService}
         * {@code #requireEligibleDisplayEmployeeId}) or the request is refused with 400. Null (the
         * default) means "use the real name", i.e. today's behaviour.
         */
        Long printedByDisplayId,
        /**
         * V179 — PRINT-ONLY override: when non-null, the พนักงานขาย signature slot AND the header
         * "Sales/{name} T.{phone}" line print THIS employee's name+phone instead of the real
         * {@code salesRepId}'s. Does NOT change who owns the deal or who earns commission on it.
         * Same eligibility rule and validation as {@link #printedByDisplayId}. Null (the default)
         * means "use the real name".
         */
        Long salesRepDisplayId,
        /**
         * Owner feedback 2026-09-14: "sometimes there's a typo in the ... project so they should
         * be able to correct it". Was write-once at CREATE (always {@code ticket.projectName()},
         * never updatable) — now a genuinely editable header field, same "no missing-keeps-stored"
         * discipline as {@link #printedByDisplayId}/{@link #salesRepDisplayId} just above: the
         * editor always sends its current value (blank included), so a null here is a real
         * request to CLEAR it, not "leave alone". {@code null} on CREATE falls back to the deal's
         * own {@code ticket.projectName()} (today's behaviour, for any caller that does not send
         * this field at all — see {@code DealQuotationService#create}).
         */
        @Size(max = 200) String projectName,
        /**
         * Item 2 (V180, owner ruling 2026-09-16) — "ไม่เติม “คุณ” หน้าชื่อผู้สั่งซื้อ": when true,
         * {@code DealQuotationRenderAdapter}'s attn line never prefixes "คุณ" onto the ผู้สั่งซื้อ
         * contact name, for a deal whose contact is genuinely a department/section name ("ฝ่าย
         * จัดซื้อ") rather than a person. Nullable on the wire and treated as {@code false} when
         * absent (see {@code DealQuotationService#resolveOmitContactHonorific}) — UNticked is the
         * default for every new quotation, and is NOT remembered in {@code quotationPrefs} (unlike
         * depositPercent/remainderMode/etc.), so it never silently carries over from a previous
         * document.
         */
        Boolean omitContactHonorific,
        /**
         * Item 4 (V181, "ไม่รับมัดจำ", owner ruling 2026-09-16) — one of
         * {@link WastageCalculator#FULL_PAYMENT_TERM_BEFORE_DELIVERY}/
         * {@link WastageCalculator#FULL_PAYMENT_TERM_ON_DELIVERY}/
         * {@link WastageCalculator#FULL_PAYMENT_TERM_ON_OR_BEFORE_DELIVERY}, meaningful ONLY on a
         * document whose {@link #depositPercent} resolves to exactly 0 ("ไม่รับมัดจำ" ticked) —
         * {@code DealQuotationService#resolveFullPaymentTerm} forces it back to null on any other
         * deposit percentage, so a rep who unticks "ไม่รับมัดจำ" can never leave a stale term
         * attached to a document that now names an ordinary percentage deposit. Null/blank on a
         * zero-deposit DRAFT is allowed (the rep has not picked one yet); {@code submit()} refuses
         * to advance such a document until one is chosen. An unrecognised code is rejected here by
         * bean validation (400), before the service ever sees it.
         */
        @Pattern(regexp = "BEFORE_DELIVERY|ON_DELIVERY|ON_OR_BEFORE_DELIVERY",
            message = "ต้องเป็น BEFORE_DELIVERY, ON_DELIVERY หรือ ON_OR_BEFORE_DELIVERY")
        String fullPaymentTerm,
        @NotEmpty List<@Valid ItemInput> items
    ) {
        /** The pre-V180/V181 shape (no {@link #omitContactHonorific}/{@link #fullPaymentTerm}) —
         * kept so every existing construction site (tests, mostly) compiles unchanged. Defaults
         * omitContactHonorific to null (read as {@code false} — UNticked, today's only behaviour)
         * and fullPaymentTerm to null (no zero-deposit document existed before this change had a
         * term to carry). */
        public UpsertDealQuotationRequest(Long contactId, String deptCode, String unitCode,
                                          LocalDate offerDate, Integer depositPercent,
                                          String remainderMode, Integer creditDays,
                                          Integer validityDays, String validityMode, LocalDate validityUntil,
                                          String customerNotes, String priceMode, String documentLanguage,
                                          String currency, Long printedByDisplayId, Long salesRepDisplayId,
                                          String projectName, List<ItemInput> items) {
            this(contactId, deptCode, unitCode, offerDate, depositPercent, remainderMode,
                creditDays, validityDays, validityMode, validityUntil, customerNotes, priceMode,
                documentLanguage, currency, printedByDisplayId, salesRepDisplayId, projectName,
                null, null, items);
        }

        /** The pre-projectName shape — kept so every existing construction site (tests, mostly)
         * compiles unchanged. Defaults to null, which on create falls back to the ticket's own
         * project name (today's behaviour for every one of those fixtures) and on update would
         * clear it — but nothing pre-existing calls update() through this overload with a
         * genuinely different project already stored, so that edge is theoretical here. */
        public UpsertDealQuotationRequest(Long contactId, String deptCode, String unitCode,
                                          LocalDate offerDate, Integer depositPercent,
                                          String remainderMode, Integer creditDays,
                                          Integer validityDays, String validityMode, LocalDate validityUntil,
                                          String customerNotes, String priceMode, String documentLanguage,
                                          String currency, Long printedByDisplayId, Long salesRepDisplayId,
                                          List<ItemInput> items) {
            this(contactId, deptCode, unitCode, offerDate, depositPercent, remainderMode,
                creditDays, validityDays, validityMode, validityUntil, customerNotes, priceMode,
                documentLanguage, currency, printedByDisplayId, salesRepDisplayId, null, items);
        }

        /** The pre-V179 shape (no display-name override fields) — kept so every existing
         * construction site (tests, mostly) compiles unchanged. Defaults both to null, which
         * means "use the real name" — today's behaviour for every one of those fixtures. */
        public UpsertDealQuotationRequest(Long contactId, String deptCode, String unitCode,
                                          LocalDate offerDate, Integer depositPercent,
                                          String remainderMode, Integer creditDays,
                                          Integer validityDays, String validityMode, LocalDate validityUntil,
                                          String customerNotes, String priceMode, String documentLanguage,
                                          String currency, List<ItemInput> items) {
            this(contactId, deptCode, unitCode, offerDate, depositPercent, remainderMode,
                creditDays, validityDays, validityMode, validityUntil, customerNotes, priceMode,
                documentLanguage, currency, null, null, items);
        }

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

        /** The pre-V178 shape (no {@code validityMode}/{@code validityUntil}) — the canonical
         * shape from v3b until this change, kept so every existing call site (mostly tests)
         * compiles unchanged. Defaults both to null, which reads as DAYS mode — exactly what
         * every one of those fixtures means. */
        public UpsertDealQuotationRequest(Long contactId, String deptCode, String unitCode,
                                          LocalDate offerDate, Integer depositPercent,
                                          String remainderMode, Integer creditDays,
                                          Integer validityDays, String customerNotes,
                                          String priceMode, String documentLanguage, String currency,
                                          List<ItemInput> items) {
            this(contactId, deptCode, unitCode, offerDate, depositPercent, remainderMode,
                creditDays, validityDays, null, null, customerNotes, priceMode, documentLanguage,
                currency, items);
        }
    }

    /** GLA-75: {@code PATCH /api/deal-quotations/{id}/items/{itemId}/picture} — move an existing
     * picture between the two placements without re-uploading it. */
    public record PicturePlacementRequest(
        @NotBlank @Pattern(regexp = "BELOW|BESIDE", message = "ต้องเป็น BELOW หรือ BESIDE") String placement
    ) {}

    public record ApproveRequest(@Size(max = 2000) String note) {}

    public record RejectRequest(@NotBlank @Size(max = 2000) String reason) {}

    public record CancelRequest(@Size(max = 2000) String reason) {}
}
