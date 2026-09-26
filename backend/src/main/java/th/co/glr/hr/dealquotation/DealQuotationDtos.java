package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Quotation v2 (direct deal quotation, V165) — see docs/sales/quotation-v2-plan.md's "API" section for the
 * exact shape. Sales creates a deal, adds items straight onto a quotation with typed unit price +
 * discount (the pricing-request/factory-quote/CEO-costing chain is bypassed for this release —
 * owner ruling 2026-09-09), the server computes every number, and sales_manager/ceo approves.
 *
 * <p>Extends the SAME {@code sales.quotation}/{@code sales.quotation_item} aggregate the pricing
 * chain (Step 4, {@code customerquotation/*}) already uses — tagged {@code origin =
 * 'DEAL_DIRECT'} so the two flows' rows never see each other (see V165's migration comment).
 */
public final class DealQuotationDtos {
    private DealQuotationDtos() {}

    public record DealQuotationDto(
        long id,
        String number,
        long ticketId,
        String docStatus,
        int revisionNo,
        Long parentQuotationId,
        long createdById,
        String createdByName,
        /** {@code hr.employee.first_name_en + last_name_en}, or null when the employee has none —
         * quotation v3b (2026-09-11). The ENGLISH document's signature block prints these and
         * FALLS BACK to the Thai name when blank, rather than printing an empty slot; the Thai
         * document never looks at them. Served on the DTO rather than resolved at render time so
         * the adapter stays the pure function it is documented to be. */
        String createdByNameEn,
        long salesRepId,
        String salesRepName,
        String salesRepNameEn,
        String salesRepPhone,
        Instant submittedAt,
        Long approvedById,
        String approvedByName,
        String approvedByNameEn,
        Instant approvedAt,
        String approvalNote,
        // The date the sales rep CREATED the quotation (Bangkok), for EVERY status — owner
        // feedback F8, 2026-09-10, "for วันที่ at the top of the page it should be the date it was
        // created by the sale". Was "approved date, else today" until then; the header cell the
        // renderer prints follows the same rule (DealQuotationRenderAdapter#toRenderModel), so the
        // UI and the document always agree. NOT offerDate (remark 1, วันที่รับจำนวน), which is a
        // separate rep-editable date.
        LocalDate quotationDate,
        String customerName,
        String customerAddress,
        String customerTaxId,
        String customerPhone,
        // ผู้สั่งซื้อ — the FROZEN snapshot V167 stores at create/update (owner feedback F2,
        // 2026-09-10), never a live read of the ticket's contact; null only on a pre-V167 row
        // whose ticket had no contact, which submit() refuses until one is chosen.
        Long contactId,
        String contactName,
        String contactPhone,
        String contactEmail,
        String projectName,
        String deptCode,
        String unitCode,
        LocalDate offerDate,
        Integer depositPercent,
        String remainderMode,
        Integer creditDays,
        Integer validityDays,
        LocalDate validityDate,
        /** "DAYS" (default) | "DATE" — owner feedback 2026-09-14, remark 7's second variant.
         * Never null on the wire; a stored NULL (every pre-V178 row, and the whole legacy
         * customer-quotation path) normalises to DAYS on read. */
        String validityMode,
        /** DATE mode only — the exact "ภายในวันที่" deadline the rep typed. Null in DAYS mode. */
        LocalDate validityUntil,
        String customerNotes,
        /** "NET" | "SPECIAL_SQM" | "DIRECT_NET" — quotation v3 (V168). Never null on the wire; a
         * stored NULL (every pre-V168 row) normalises to NET on read. Per-QUOTATION, because in
         * all nine of the owner's documents every tile row shares one mode. */
        String priceMode,
        /** "TH" | "EN" — quotation v3b (V169). Never null on the wire; a stored NULL (every
         * pre-V169 row) normalises to TH on read, which is the Thai F-SM-002 every document has
         * been until now. It is the ONE choice a rep makes: {@code currency} defaults from it
         * (TH→THB, EN→USD) and so does the VAT treatment — {@code vatAmount} is ZERO and
         * {@code grandTotal == subtotalAmount} on an EN document, because the English form carries
         * no VAT row at all. */
        String documentLanguage,
        BigDecimal subtotalAmount,
        BigDecimal vatAmount,
        BigDecimal grandTotal,
        String currency,
        boolean approverHasSignature,
        // ── V179 (owner feedback #4, 2026-09-14) — PRINT-ONLY name override. "they should be able
        // to select who to show for ผู้พิมพ์ and พนักงานขาย": when admin fills in a quotation on
        // behalf of a sales rep, ผู้พิมพ์ should read the admin's name and พนักงานขาย the rep's.
        // NOT a change of deal ownership/access/commission — createdById/salesRepId (and every
        // access/commission decision built on them) are completely untouched; these seven fields
        // only ever change what DealQuotationRenderAdapter prints. Null (the default) means "use
        // the real name", i.e. today's behaviour — see that class's #printedByName /
        // #salesRepDisplayNameOrReal / #salesRepDisplayPhoneOrReal helpers.
        /** Overrides {@link #createdByName}/{@link #createdByNameEn} on the ผู้พิมพ์ slot when set. */
        Long printedByDisplayId,
        String printedByDisplayName,
        String printedByDisplayNameEn,
        /** Overrides {@link #salesRepName}/{@link #salesRepNameEn}/{@link #salesRepPhone} on the
         * พนักงานขาย slot AND the header "Sales/{name} T.{phone}" line when set. */
        Long salesRepDisplayId,
        String salesRepDisplayName,
        String salesRepDisplayNameEn,
        String salesRepDisplayPhone,
        /** Item 2 (V180, "ไม่เติม “คุณ” หน้าชื่อผู้สั่งซื้อ", owner ruling 2026-09-16) — see
         * {@code DealQuotationRequests.UpsertDealQuotationRequest#omitContactHonorific}'s Javadoc.
         * {@code NOT NULL DEFAULT FALSE} on the column (V180), so this is never null once read from
         * the repository — unlike the {@code Boolean} on the request DTO, which is nullable on the
         * wire and resolved to this primitive by
         * {@code DealQuotationService#resolveOmitContactHonorific}. */
        boolean omitContactHonorific,
        /** Item 4 (V181, "ไม่รับมัดจำ", owner ruling 2026-09-16) — see
         * {@code DealQuotationRequests.UpsertDealQuotationRequest#fullPaymentTerm}'s Javadoc. Null
         * on every row whose {@link #depositPercent} is not exactly 0, and on every LEGACY
         * zero-deposit row that predates this feature — {@code DealQuotationRenderAdapter} keeps
         * printing such a row's existing {@link #remainderMode}/{@link #creditDays}-based text
         * byte-for-byte when this is null, so an already-approved document never changes. */
        String fullPaymentTerm,
        /** GLA-74 part 1 ("สร้างจากใบเดิม" / สั่งเหมือนเดิม, V186) — the APPROVED quotation this row
         * was CLONED from, for audit only. Null on every ordinary create/revision. Distinct from
         * {@link #parentQuotationId}: this column itself is never read by {@code approve}'s
         * ancestor walk, {@code hasOpenRevision}, or the "แก้" bucket predicate — see
         * {@code DealQuotationService#createReorder}. Owner ruling 2026-09-19: that does NOT mean
         * the source is immune from supersession — a deal may hold only ONE APPROVED
         * {@code DEAL_DIRECT} quotation, so once THIS row is itself approved, {@code approve}'s
         * SEPARATE same-ticket sweep (keyed on {@code doc_status}/{@code ticket_id}, not on this
         * column) supersedes the source then. Only clone CREATION leaves the source untouched. */
        Long derivedFromQuotationId,
        /** The source's own {@code number} at read time — a plain join, never frozen — purely so
         * the UI can print "สั่งเหมือนเดิมจาก {source.number}" without a second round trip. Null
         * exactly when {@link #derivedFromQuotationId} is null. */
        String derivedFromQuotationNumber,
        /** The source's own {@code docStatus} at read time (Opus review, 2026-09-19) — same live
         * join as {@link #derivedFromQuotationNumber}, added because the source's status is NOT
         * frozen at clone time: once the source (or the clone itself) is later approved, the
         * one-approved-per-deal sweep can flip the source from {@code APPROVED} to {@code
         * SUPERSEDED} at any point after the clone exists. The UI needs this to word the
         * provenance note correctly — "the original stays approved until approved" is only true
         * while this reads {@code APPROVED}; once it reads {@code SUPERSEDED}, something
         * (possibly NOT this clone) has already replaced it. Null exactly when
         * {@link #derivedFromQuotationId} is null. */
        String derivedFromQuotationStatus,
        List<DealQuotationItemDto> items,
        Instant createdAt,
        Instant updatedAt,
        // ── GLA-123 slice S1 (Phase 3 of the sales pricing redesign, 2026-09-19) ────────────────
        /** {@code 'DEAL_DIRECT'} (the only value this class has ever written before this feature)
         * or {@code 'PRICING_REQUEST'} — a quotation created from an APPROVED-FOR-QUOTATION
         * pricing request via {@link DealQuotationService#createFromPricingRequest}, prefilled
         * from the CEO's {@code pricing_decision}. NIT fix (Opus review, 2026-09-20): this used
         * to say "drives the editor's CEO-locked-field UI and the server-side locked-price guard"
         * — stale since the owner ruling revised 2026-09-19 below, which REPLACED that lock with
         * sales editing price freely and a CEO-re-approval flag instead. What this field actually
         * drives now: the editor's CEO-comparison badge/marker UI, the extra-row refusal, the
         * linked-line identity guard, and the origin-aware view gate (M3) — see {@link
         * #priceChangedFromCeo}/{@link #itemsRemovedFromCeoCount} below for the flags themselves.
         * Never null on the wire — a stored NULL/legacy row (every row this class wrote before
         * this field existed) reads as {@code DEAL_DIRECT}, see the repository's own mapping. */
        String origin,
        /** Non-null exactly when {@link #origin} is {@code PRICING_REQUEST} — the source pricing
         * request this quotation was created from, so the editor can link back to it. Null for
         * every {@code DEAL_DIRECT} row. */
        Long pricingRequestId,
        // ── Owner ruling revised 2026-09-19: sales MAY edit price on a PRICING_REQUEST quotation,
        // subject to CEO re-approval — there is no server-side lock any more. These two fields are
        // the header half of that: whether the CHOSEN {@link #priceMode} itself still matches the
        // CEO's original decision, and what that original was, so the editor can show "ราคา CEO:
        // วิธี X" beside a changed price-mode picker. Recomputed fresh on every read, never stored.
        /** {@code true} when {@link #origin} is {@code PRICING_REQUEST} and the document's current
         * {@link #priceMode} no longer matches {@link #ceoPriceMode}. Always {@code false} for a
         * {@code DEAL_DIRECT} row. */
        boolean priceModeChangedFromCeo,
        /** The CEO's ORIGINAL {@code pricing_decision.price_mode} for the decision this quotation
         * was created from. Null for a {@code DEAL_DIRECT} row. */
        String ceoPriceMode,
        /** {@code sales.pricing_request.request_code} (e.g. {@code "PCR-2026-0042"}) for
         * {@link #pricingRequestId} — joined in purely so the editor can print "สร้างจากคำขอราคา
         * {code}" without a second round trip. Null exactly when {@link #pricingRequestId} is
         * null (coordinator follow-up, 2026-09-20). */
        String pricingRequestCode,
        /** GLA-123 slice S1 M4(c) fix (Opus review, 2026-09-20) — count of CEO-linked lines
         * dropped from this quotation since creation (V191). Always 0 for a {@code DEAL_DIRECT}
         * row (the concept is meaningless there — no line is ever CEO-linked). Drives the
         * header-level "รายการที่ CEO อนุมัติถูกลบออก N รายการ" marker, the removal-side
         * counterpart of {@link #priceModeChangedFromCeo} above. */
        int itemsRemovedFromCeoCount,
        /** M4(d) fix (Opus review, 2026-09-20) — every CEO-linked line this quotation's decision
         * has that is NOT currently live on it, i.e. what {@code POST
         * .../items/{pricingDecisionItemId}/restore} can bring back. Empty for a DEAL_DIRECT row
         * or a PRICING_REQUEST row with nothing dropped ({@link #itemsRemovedFromCeoCount} and
         * this list's size always agree). Set via {@link #withRemovedCeoItems} — a SEPARATE
         * repository round trip ({@code findRemovedLinkedItems}), not baked into the main
         * {@code baseSelect}/{@code mapQuotation} join, because that query only ever runs for a
         * SINGLE PRICING_REQUEST-origin row (findById/update/restoreRemovedItem), never inside
         * the list/search loops (which stay DEAL_DIRECT-only) — see
         * {@code DealQuotationService#requireQuotation}. */
        List<DealQuotationRepository.RemovedLinkedItemDto> removedCeoItems,
        /** Owner-directed reversal of F2 (2026-09-10, hardened 2026-09-15, reversed 2026-09-26) —
         * see {@code DealQuotationRenderAdapter#orderedByName}'s own Javadoc for the full history.
         * The ผู้สั่งซื้อ signature slot no longer auto-fills from {@link #contactName}/
         * {@link #customerName} at all: this is the ONLY source it ever prints, and it is a
         * manual, OPTIONAL field a sales rep types in the editor ({@code sales.quotation.ordered_by_name},
         * V192). Null/blank (the default, and every pre-V192 row) prints the dotted placeholder —
         * the customer signs on paper — exactly like every other signature slot with nothing set. */
        String orderedByName
    ) {
        /** This DTO carrying {@link #removedCeoItems} — same device as {@code
         * DealQuotationItemDto#withCeoComparison} (appended field written after the shorter
         * legacy constructors most call sites still build from). Parameter named distinctly from
         * the record's own {@code items} field (the quotation LINE items) so the two can never be
         * confused inside this method body — {@code removedItems} in, {@code items} (this
         * record's own field) passed straight through unchanged to the canonical constructor. */
        public DealQuotationDto withRemovedCeoItems(List<DealQuotationRepository.RemovedLinkedItemDto> removedItems) {
            return new DealQuotationDto(id, number, ticketId, docStatus, revisionNo, parentQuotationId,
                createdById, createdByName, createdByNameEn, salesRepId, salesRepName, salesRepNameEn,
                salesRepPhone, submittedAt, approvedById, approvedByName, approvedByNameEn, approvedAt,
                approvalNote, quotationDate, customerName, customerAddress, customerTaxId, customerPhone,
                contactId, contactName, contactPhone, contactEmail, projectName, deptCode, unitCode, offerDate,
                depositPercent, remainderMode, creditDays, validityDays, validityDate, validityMode,
                validityUntil, customerNotes, priceMode, documentLanguage, subtotalAmount, vatAmount,
                grandTotal, currency, approverHasSignature, printedByDisplayId, printedByDisplayName,
                printedByDisplayNameEn, salesRepDisplayId, salesRepDisplayName, salesRepDisplayNameEn,
                salesRepDisplayPhone, omitContactHonorific, fullPaymentTerm, derivedFromQuotationId,
                derivedFromQuotationNumber, derivedFromQuotationStatus, items, createdAt, updatedAt, origin,
                pricingRequestId, priceModeChangedFromCeo, ceoPriceMode, pricingRequestCode,
                itemsRemovedFromCeoCount, removedItems, orderedByName);
        }

        /** This DTO carrying {@code value} as its {@link #orderedByName} — same appended-field
         * device as {@link #withRemovedCeoItems}, added purely so a test/call site built from an
         * older legacy constructor (none of which know about this field) can still set it without
         * repeating this record's entire 66-argument canonical constructor by hand. */
        public DealQuotationDto withOrderedByName(String value) {
            return new DealQuotationDto(id, number, ticketId, docStatus, revisionNo, parentQuotationId,
                createdById, createdByName, createdByNameEn, salesRepId, salesRepName, salesRepNameEn,
                salesRepPhone, submittedAt, approvedById, approvedByName, approvedByNameEn, approvedAt,
                approvalNote, quotationDate, customerName, customerAddress, customerTaxId, customerPhone,
                contactId, contactName, contactPhone, contactEmail, projectName, deptCode, unitCode, offerDate,
                depositPercent, remainderMode, creditDays, validityDays, validityDate, validityMode,
                validityUntil, customerNotes, priceMode, documentLanguage, subtotalAmount, vatAmount,
                grandTotal, currency, approverHasSignature, printedByDisplayId, printedByDisplayName,
                printedByDisplayNameEn, salesRepDisplayId, salesRepDisplayName, salesRepDisplayNameEn,
                salesRepDisplayPhone, omitContactHonorific, fullPaymentTerm, derivedFromQuotationId,
                derivedFromQuotationNumber, derivedFromQuotationStatus, items, createdAt, updatedAt, origin,
                pricingRequestId, priceModeChangedFromCeo, ceoPriceMode, pricingRequestCode,
                itemsRemovedFromCeoCount, removedCeoItems, value);
        }

        /** The pre-M4(c) shape (no {@link #itemsRemovedFromCeoCount}) — kept so every existing
         * construction site (tests, mostly) compiles unchanged. Defaults to 0, correct for every
         * one of those fixtures (nothing before this feature ever dropped a CEO-linked line). */
        public DealQuotationDto(
            long id, String number, long ticketId, String docStatus, int revisionNo,
            Long parentQuotationId, long createdById, String createdByName, String createdByNameEn,
            long salesRepId, String salesRepName, String salesRepNameEn, String salesRepPhone,
            Instant submittedAt, Long approvedById, String approvedByName, String approvedByNameEn,
            Instant approvedAt, String approvalNote, LocalDate quotationDate, String customerName,
            String customerAddress, String customerTaxId, String customerPhone, Long contactId,
            String contactName, String contactPhone, String contactEmail, String projectName,
            String deptCode, String unitCode, LocalDate offerDate, Integer depositPercent,
            String remainderMode, Integer creditDays, Integer validityDays, LocalDate validityDate,
            String validityMode, LocalDate validityUntil,
            String customerNotes, String priceMode, String documentLanguage, BigDecimal subtotalAmount,
            BigDecimal vatAmount, BigDecimal grandTotal, String currency, boolean approverHasSignature,
            Long printedByDisplayId, String printedByDisplayName, String printedByDisplayNameEn,
            Long salesRepDisplayId, String salesRepDisplayName, String salesRepDisplayNameEn,
            String salesRepDisplayPhone, boolean omitContactHonorific, String fullPaymentTerm,
            Long derivedFromQuotationId, String derivedFromQuotationNumber, String derivedFromQuotationStatus,
            List<DealQuotationItemDto> items, Instant createdAt, Instant updatedAt,
            String origin, Long pricingRequestId, boolean priceModeChangedFromCeo, String ceoPriceMode,
            String pricingRequestCode) {
            this(id, number, ticketId, docStatus, revisionNo, parentQuotationId, createdById, createdByName,
                createdByNameEn, salesRepId, salesRepName, salesRepNameEn, salesRepPhone, submittedAt,
                approvedById, approvedByName, approvedByNameEn, approvedAt, approvalNote, quotationDate,
                customerName, customerAddress, customerTaxId, customerPhone, contactId, contactName,
                contactPhone, contactEmail, projectName, deptCode, unitCode, offerDate, depositPercent,
                remainderMode, creditDays, validityDays, validityDate, validityMode, validityUntil,
                customerNotes, priceMode, documentLanguage, subtotalAmount, vatAmount, grandTotal, currency,
                approverHasSignature, printedByDisplayId, printedByDisplayName, printedByDisplayNameEn,
                salesRepDisplayId, salesRepDisplayName, salesRepDisplayNameEn, salesRepDisplayPhone,
                omitContactHonorific, fullPaymentTerm, derivedFromQuotationId, derivedFromQuotationNumber,
                derivedFromQuotationStatus, items, createdAt, updatedAt, origin, pricingRequestId,
                priceModeChangedFromCeo, ceoPriceMode, pricingRequestCode, 0, List.of(), null);
        }

        /** The pre-GLA-123 shape (no {@link #origin}/{@link #pricingRequestId}/CEO-comparison
         * fields) — kept so every existing construction site (tests, mostly) compiles unchanged.
         * Defaults origin to {@code DEAL_DIRECT} (the only value that has ever existed before this
         * feature) and everything else to false/null. */
        public DealQuotationDto(
            long id, String number, long ticketId, String docStatus, int revisionNo,
            Long parentQuotationId, long createdById, String createdByName, String createdByNameEn,
            long salesRepId, String salesRepName, String salesRepNameEn, String salesRepPhone,
            Instant submittedAt, Long approvedById, String approvedByName, String approvedByNameEn,
            Instant approvedAt, String approvalNote, LocalDate quotationDate, String customerName,
            String customerAddress, String customerTaxId, String customerPhone, Long contactId,
            String contactName, String contactPhone, String contactEmail, String projectName,
            String deptCode, String unitCode, LocalDate offerDate, Integer depositPercent,
            String remainderMode, Integer creditDays, Integer validityDays, LocalDate validityDate,
            String validityMode, LocalDate validityUntil,
            String customerNotes, String priceMode, String documentLanguage, BigDecimal subtotalAmount,
            BigDecimal vatAmount, BigDecimal grandTotal, String currency, boolean approverHasSignature,
            Long printedByDisplayId, String printedByDisplayName, String printedByDisplayNameEn,
            Long salesRepDisplayId, String salesRepDisplayName, String salesRepDisplayNameEn,
            String salesRepDisplayPhone, boolean omitContactHonorific, String fullPaymentTerm,
            Long derivedFromQuotationId, String derivedFromQuotationNumber, String derivedFromQuotationStatus,
            List<DealQuotationItemDto> items, Instant createdAt, Instant updatedAt) {
            this(id, number, ticketId, docStatus, revisionNo, parentQuotationId, createdById, createdByName,
                createdByNameEn, salesRepId, salesRepName, salesRepNameEn, salesRepPhone, submittedAt,
                approvedById, approvedByName, approvedByNameEn, approvedAt, approvalNote, quotationDate,
                customerName, customerAddress, customerTaxId, customerPhone, contactId, contactName,
                contactPhone, contactEmail, projectName, deptCode, unitCode, offerDate, depositPercent,
                remainderMode, creditDays, validityDays, validityDate, validityMode, validityUntil,
                customerNotes, priceMode, documentLanguage, subtotalAmount, vatAmount, grandTotal, currency,
                approverHasSignature, printedByDisplayId, printedByDisplayName, printedByDisplayNameEn,
                salesRepDisplayId, salesRepDisplayName, salesRepDisplayNameEn, salesRepDisplayPhone,
                omitContactHonorific, fullPaymentTerm, derivedFromQuotationId, derivedFromQuotationNumber,
                derivedFromQuotationStatus, items, createdAt, updatedAt, "DEAL_DIRECT", null, false, null, null);
        }

        /** The pre-GLA-74 shape (no {@link #derivedFromQuotationId}/
         * {@link #derivedFromQuotationNumber}/{@link #derivedFromQuotationStatus}) — kept so
         * every existing construction site (tests, mostly) compiles unchanged. Defaults all
         * three to null, which is correct for every one of those fixtures (nothing before this
         * feature was ever a clone). */
        public DealQuotationDto(
            long id, String number, long ticketId, String docStatus, int revisionNo,
            Long parentQuotationId, long createdById, String createdByName, String createdByNameEn,
            long salesRepId, String salesRepName, String salesRepNameEn, String salesRepPhone,
            Instant submittedAt, Long approvedById, String approvedByName, String approvedByNameEn,
            Instant approvedAt, String approvalNote, LocalDate quotationDate, String customerName,
            String customerAddress, String customerTaxId, String customerPhone, Long contactId,
            String contactName, String contactPhone, String contactEmail, String projectName,
            String deptCode, String unitCode, LocalDate offerDate, Integer depositPercent,
            String remainderMode, Integer creditDays, Integer validityDays, LocalDate validityDate,
            String validityMode, LocalDate validityUntil,
            String customerNotes, String priceMode, String documentLanguage, BigDecimal subtotalAmount,
            BigDecimal vatAmount, BigDecimal grandTotal, String currency, boolean approverHasSignature,
            Long printedByDisplayId, String printedByDisplayName, String printedByDisplayNameEn,
            Long salesRepDisplayId, String salesRepDisplayName, String salesRepDisplayNameEn,
            String salesRepDisplayPhone, boolean omitContactHonorific, String fullPaymentTerm,
            List<DealQuotationItemDto> items, Instant createdAt, Instant updatedAt) {
            this(id, number, ticketId, docStatus, revisionNo, parentQuotationId, createdById, createdByName,
                createdByNameEn, salesRepId, salesRepName, salesRepNameEn, salesRepPhone, submittedAt,
                approvedById, approvedByName, approvedByNameEn, approvedAt, approvalNote, quotationDate,
                customerName, customerAddress, customerTaxId, customerPhone, contactId, contactName,
                contactPhone, contactEmail, projectName, deptCode, unitCode, offerDate, depositPercent,
                remainderMode, creditDays, validityDays, validityDate, validityMode, validityUntil,
                customerNotes, priceMode, documentLanguage, subtotalAmount, vatAmount, grandTotal, currency,
                approverHasSignature, printedByDisplayId, printedByDisplayName, printedByDisplayNameEn,
                salesRepDisplayId, salesRepDisplayName, salesRepDisplayNameEn, salesRepDisplayPhone,
                omitContactHonorific, fullPaymentTerm, null, null, null, items, createdAt, updatedAt);
        }

        /** The pre-V180/V181 shape (no {@link #omitContactHonorific}/{@link #fullPaymentTerm}) —
         * kept so every existing construction site (tests, mostly) compiles unchanged. Defaults
         * omitContactHonorific to {@code false} (UNticked — the only behaviour every one of those
         * fixtures means) and fullPaymentTerm to null (no zero-deposit row had a term to carry
         * before this change). */
        public DealQuotationDto(
            long id, String number, long ticketId, String docStatus, int revisionNo,
            Long parentQuotationId, long createdById, String createdByName, String createdByNameEn,
            long salesRepId, String salesRepName, String salesRepNameEn, String salesRepPhone,
            Instant submittedAt, Long approvedById, String approvedByName, String approvedByNameEn,
            Instant approvedAt, String approvalNote, LocalDate quotationDate, String customerName,
            String customerAddress, String customerTaxId, String customerPhone, Long contactId,
            String contactName, String contactPhone, String contactEmail, String projectName,
            String deptCode, String unitCode, LocalDate offerDate, Integer depositPercent,
            String remainderMode, Integer creditDays, Integer validityDays, LocalDate validityDate,
            String validityMode, LocalDate validityUntil,
            String customerNotes, String priceMode, String documentLanguage, BigDecimal subtotalAmount,
            BigDecimal vatAmount, BigDecimal grandTotal, String currency, boolean approverHasSignature,
            Long printedByDisplayId, String printedByDisplayName, String printedByDisplayNameEn,
            Long salesRepDisplayId, String salesRepDisplayName, String salesRepDisplayNameEn,
            String salesRepDisplayPhone,
            List<DealQuotationItemDto> items, Instant createdAt, Instant updatedAt) {
            this(id, number, ticketId, docStatus, revisionNo, parentQuotationId, createdById, createdByName,
                createdByNameEn, salesRepId, salesRepName, salesRepNameEn, salesRepPhone, submittedAt,
                approvedById, approvedByName, approvedByNameEn, approvedAt, approvalNote, quotationDate,
                customerName, customerAddress, customerTaxId, customerPhone, contactId, contactName,
                contactPhone, contactEmail, projectName, deptCode, unitCode, offerDate, depositPercent,
                remainderMode, creditDays, validityDays, validityDate, validityMode, validityUntil,
                customerNotes, priceMode, documentLanguage, subtotalAmount, vatAmount, grandTotal, currency,
                approverHasSignature, printedByDisplayId, printedByDisplayName, printedByDisplayNameEn,
                salesRepDisplayId, salesRepDisplayName, salesRepDisplayNameEn, salesRepDisplayPhone,
                false, null, items, createdAt, updatedAt);
        }

        /** The pre-V179 shape (no display-name override fields) — kept so every existing
         * construction site (tests, mostly) compiles unchanged. Defaults all seven of those fields to
         * null, which reads as "use the real name" — today's behaviour for every one of those
         * fixtures — and (via the overload above) omitContactHonorific to false / fullPaymentTerm
         * to null. */
        public DealQuotationDto(
            long id, String number, long ticketId, String docStatus, int revisionNo,
            Long parentQuotationId, long createdById, String createdByName, String createdByNameEn,
            long salesRepId, String salesRepName, String salesRepNameEn, String salesRepPhone,
            Instant submittedAt, Long approvedById, String approvedByName, String approvedByNameEn,
            Instant approvedAt, String approvalNote, LocalDate quotationDate, String customerName,
            String customerAddress, String customerTaxId, String customerPhone, Long contactId,
            String contactName, String contactPhone, String contactEmail, String projectName,
            String deptCode, String unitCode, LocalDate offerDate, Integer depositPercent,
            String remainderMode, Integer creditDays, Integer validityDays, LocalDate validityDate,
            String validityMode, LocalDate validityUntil,
            String customerNotes, String priceMode, String documentLanguage, BigDecimal subtotalAmount,
            BigDecimal vatAmount, BigDecimal grandTotal, String currency, boolean approverHasSignature,
            List<DealQuotationItemDto> items, Instant createdAt, Instant updatedAt) {
            this(id, number, ticketId, docStatus, revisionNo, parentQuotationId, createdById, createdByName,
                createdByNameEn, salesRepId, salesRepName, salesRepNameEn, salesRepPhone, submittedAt,
                approvedById, approvedByName, approvedByNameEn, approvedAt, approvalNote, quotationDate,
                customerName, customerAddress, customerTaxId, customerPhone, contactId, contactName,
                contactPhone, contactEmail, projectName, deptCode, unitCode, offerDate, depositPercent,
                remainderMode, creditDays, validityDays, validityDate, validityMode, validityUntil,
                customerNotes, priceMode, documentLanguage, subtotalAmount, vatAmount, grandTotal, currency,
                approverHasSignature, null, null, null, null, null, null, null, items, createdAt, updatedAt);
        }

        /** The pre-V178 shape (no {@link #validityMode}/{@link #validityUntil}, and so also no
         * V179 display-override fields) — kept so every existing construction site (tests, mostly)
         * compiles unchanged. Defaults to {@code DAYS}/{@code null}, which reads as the day-count
         * behaviour every one of those fixtures actually means. */
        public DealQuotationDto(
            long id, String number, long ticketId, String docStatus, int revisionNo,
            Long parentQuotationId, long createdById, String createdByName, String createdByNameEn,
            long salesRepId, String salesRepName, String salesRepNameEn, String salesRepPhone,
            Instant submittedAt, Long approvedById, String approvedByName, String approvedByNameEn,
            Instant approvedAt, String approvalNote, LocalDate quotationDate, String customerName,
            String customerAddress, String customerTaxId, String customerPhone, Long contactId,
            String contactName, String contactPhone, String contactEmail, String projectName,
            String deptCode, String unitCode, LocalDate offerDate, Integer depositPercent,
            String remainderMode, Integer creditDays, Integer validityDays, LocalDate validityDate,
            String customerNotes, String priceMode, String documentLanguage, BigDecimal subtotalAmount,
            BigDecimal vatAmount, BigDecimal grandTotal, String currency, boolean approverHasSignature,
            List<DealQuotationItemDto> items, Instant createdAt, Instant updatedAt) {
            this(id, number, ticketId, docStatus, revisionNo, parentQuotationId, createdById, createdByName,
                createdByNameEn, salesRepId, salesRepName, salesRepNameEn, salesRepPhone, submittedAt,
                approvedById, approvedByName, approvedByNameEn, approvedAt, approvalNote, quotationDate,
                customerName, customerAddress, customerTaxId, customerPhone, contactId, contactName,
                contactPhone, contactEmail, projectName, deptCode, unitCode, offerDate, depositPercent,
                remainderMode, creditDays, validityDays, validityDate,
                WastageCalculator.VALIDITY_MODE_DAYS, null,
                customerNotes, priceMode, documentLanguage, subtotalAmount, vatAmount, grandTotal, currency,
                approverHasSignature, items, createdAt, updatedAt);
        }
    }

    /**
     * Per-status counts for the caller's OWN list scope (owner feedback F5, 2026-09-10: "for
     * สถานะ make it ทั้งหมด, รออนุมัติ, แก้, ยกเลิก" — the list page's tab labels carry counts). Same
     * scope rule as {@code GET /api/deal-quotations} (sales: own deals only), computed in ONE SQL
     * statement so the tabs never need a second full fetch. {@code needsRework} = DRAFT rows sent
     * back with a reason ({@code approvalNote}) OR revisions in progress ({@code parentQuotationId}),
     * exactly the {@code needsRework=true} filter's own definition.
     */
    public record DealQuotationCountsDto(
        long all,
        long pendingApproval,
        long needsRework,
        long cancelled,
        long approved
    ) {}

    /** {@link DealQuotationRequests.ItemInput}'s fields, plus what the server computed for it. */
    public record DealQuotationItemDto(
        long id,
        int seq,
        String locationLabel,
        Long catalogPriceId,
        String productCode,
        String brand,
        String model,
        String color,
        String texture,
        String sizeText,
        BigDecimal thicknessMm,
        BigDecimal sqmPerPiece,
        String quantityMode,
        BigDecimal areaSqm,
        Integer piecesInput,
        String wastageMode,
        BigDecimal wastageValue,
        Integer piecesPerBox,
        BigDecimal unitPrice,
        BigDecimal discountPct,
        String originCountry,
        Integer leadTimeMinDays,
        Integer leadTimeMaxDays,
        String itemNotes,
        // Server-computed (WastageCalculator) — never trusted from the client.
        BigDecimal piecesPerSqm,
        int piecesBeforeWastage,
        int piecesAfterWastage,
        int piecesFinal,
        Integer boxes,
        BigDecimal netUnitPrice,
        BigDecimal lineAmount,
        // Printed-line text (DealQuotationLines) — served here so the frontend never has to
        // reimplement the wastage/description phrasing. sizeLine and calculationLine are null on
        // a PLAIN or ADJUSTMENT row, which has no size and no wastage arithmetic to print.
        String descriptionLine,
        String sizeLine,
        String calculationLine,

        // ── quotation v3 (owner feedback pass 3, 2026-09-11) ──────────────────────────────────
        /** "TILE" | "PLAIN" | "ADJUSTMENT". Never null on the wire — a stored NULL (every pre-V168
         * row) is normalised to TILE on read, so the client never has to know about the default. */
        String lineType,
        /** The printed จำนวน for EVERY row type: {@code piecesFinal} for a TILE row, the rep's own
         * quantity for PLAIN, and −1 for an ADJUSTMENT. Prefer this over {@code piecesFinal},
         * which is a TILE-only piece count and reads 0 on the other two. */
        BigDecimal quantity,
        /** The printed หน่วย — "แผ่น" for a TILE row, the rep's own (JOB/Bags/Barrels/ชุด) for
         * PLAIN, and null for an ADJUSTMENT, which prints an EMPTY unit cell. */
        String unit,
        /** SPECIAL_SQM rows only — the ราคาพิเศษ the rep typed, in บาท per ตร.ม. INCLUDING VAT. */
        BigDecimal specialPriceSqm,
        /** ADJUSTMENT rows only — the percent, when the adjustment was entered as one. */
        BigDecimal adjustmentPct,
        /** ADJUSTMENT rows only — the "สั่งซื้อภายใน" date. */
        LocalDate adjustmentDeadline,
        /** SPECIAL_SQM rows only — "(ราคาพิเศษ 1,350 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)", the sub-line
         * the owner's documents carry under such a row. Null in every other mode. */
        String specialPriceLine,
        /**
         * ADJUSTMENT rows entered as a FLAT baht amount — the positive magnitude, mirroring
         * {@code ItemInput.adjustmentAmount}. Null on every other row, INCLUDING a percentage
         * adjustment (which round-trips through {@code adjustmentPct} instead), so exactly one of
         * the two is ever non-null and the "exactly one of percent / flat" rule is satisfied by
         * echoing what we returned.
         *
         * <p>Added by review fix F2, which is about the GET→PUT round-trip. Relaxing the
         * unitPrice rejection alone fixes only the percentage case: a flat adjustment stores its
         * figure in {@code unit_price}/{@code amount} and has NO dedicated column, so before this
         * field the client had no way to hand the amount back and the PUT 400'd on "must be a
         * percent or an amount". Derived on read rather than stored — see V168's note on why a
         * duplicate column would only invite drift.
         */
        BigDecimal adjustmentAmount,

        // ── GLA-75 item pictures (V170) ───────────────────────────────────────────────────────
        /** Whether this item carries a picture. The BYTES are never inlined in this DTO — fetch
         * them from {@link #pictureUrl}. */
        boolean hasPicture,
        /** "BELOW" (large, under the description lines) | "BESIDE" (small thumbnail at the right
         * of the description cell); null exactly when {@link #hasPicture} is false. */
        String picturePlacement,
        /** {@code /api/deal-quotations/{id}/items/{itemId}/picture} (same view access as the
         * quotation itself), or null when there is no picture. The item id in it is STABLE across
         * a draft save that sends this item's {@code id} back (see
         * {@code DealQuotationRequests.ItemInput#id}) — only an item saved without its id (new, or
         * one whose id was foreign/stale/a duplicate) gets a new id and therefore a new URL. */
        String pictureUrl,
        /** V176 — the supplier-stated square metres per box (TILE rows; null when unknown).
         * OPTIONAL for an ENGLISH per-sqm quotation (Option B, owner decision 2026-09-16): WITH one,
         * {@code quantity} is {@code boxes × sqmPerBox} (2dp); WITHOUT one, it derives from
         * {@code piecesFinal × sqmPerPiece} instead (see {@code WastageCalculator#sqmQuantityFromPieces}
         * and {@code DealQuotationLines#tilePrint}). */
        BigDecimal sqmPerBox,
        /** Owner-approved "sell loose pieces" (2026-09-16, V182). {@code true} (the default, and
         * every pre-V182 row) prints/charges {@code piecesFinal} rounded UP to the next
         * {@code piecesPerBox} multiple, exactly as before. {@code false} sells the
         * wastage-adjusted piece count UNROUNDED — {@link #boxes} is then the full-box count and
         * {@code piecesFinal - boxes * piecesPerBox} (the frontend's own arithmetic, not a separate
         * wire field) is the loose-piece remainder. Never false together with an English
         * per-sqm price mode — see {@code DealQuotationService#requireBoxDataForPerSqm}. */
        boolean roundToFullBox,
        // ── GLA-123 slice S1 (2026-09-19; owner ruling revised 2026-09-19 — sales MAY edit a
        // linked line's price, subject to CEO re-approval, rather than the field being locked) ──
        /** {@code true} when this TILE row is linked to a {@code pricing_decision_item} (i.e.
         * {@code sales.quotation_item.pricing_decision_item_id IS NOT NULL}) AND its currently
         * saved price — {@link #unitPrice}/{@link #discountPct} under NET, {@link
         * #specialPriceSqm} under SPECIAL_SQM, or {@link #netUnitPrice} under DIRECT_NET — or the
         * document's {@code priceMode} itself, no longer matches the CEO's ORIGINAL decision
         * (below). Recomputed fresh on every read (repository join, never a stored column) — see
         * {@link DealQuotationRepository#mapItemColumns}. Drives the editor's
         * "เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ" marker; does NOT block the save (S2's dual
         * approval, reusing the SAME comparison, is what actually requires the CEO to re-approve
         * before ISSUE). Always {@code false} on a {@code DEAL_DIRECT} quotation and on any
         * {@code PRICING_REQUEST} row with no decision link (an unlinked TILE row is impossible in
         * S1 — extra rows are refused for this origin, see {@code DealQuotationService#update}). */
        boolean priceChangedFromCeo,
        /** The CEO's ORIGINAL {@code pricing_decision_item.list_unit_price} for this line —
         * meaningful under NET mode, the effective list price the CEO's discount applied to. Null
         * for an unlinked row or a {@code DEAL_DIRECT} quotation. */
        BigDecimal ceoListUnitPrice,
        /** The CEO's ORIGINAL {@code pricing_decision_item.discount_pct} — meaningful under NET
         * mode. Null for an unlinked row or a {@code DEAL_DIRECT} quotation. */
        BigDecimal ceoDiscountPct,
        /** The CEO's ORIGINAL {@code pricing_decision_item.special_price_sqm} — meaningful under
         * SPECIAL_SQM mode. Null for an unlinked row or a {@code DEAL_DIRECT} quotation. */
        BigDecimal ceoSpecialPriceSqm,
        /** The CEO's ORIGINAL {@code pricing_decision_item.direct_net_price} — meaningful under
         * DIRECT_NET mode. Null for an unlinked row or a {@code DEAL_DIRECT} quotation. */
        BigDecimal ceoDirectNetPrice,
        /** The CEO's ORIGINAL {@code pricing_decision_item.net_unit_price} — the resulting net
         * per แผ่น the CEO approved, regardless of mode; what the editor's "ราคา CEO: ฿…" badge
         * shows. Non-null exactly when this row is linked to a decision item (i.e. the one signal
         * that replaces the old boolean "priceLocked" flag — a {@code DEAL_DIRECT} row or an
         * unlinked TILE row always reads null here). */
        BigDecimal ceoNetUnitPrice
    ) {
        /** The pre-GLA-123 canonical shape (no CEO-original/changed-flag fields) — kept so every
         * existing construction site (tests, mostly) compiles unchanged. Defaults every new field
         * to false/null, correct for every DEAL_DIRECT fixture (nothing before this feature was
         * ever linked to a pricing decision). */
        public DealQuotationItemDto(
            long id, int seq, String locationLabel, Long catalogPriceId, String productCode,
            String brand, String model, String color, String texture, String sizeText,
            BigDecimal thicknessMm, BigDecimal sqmPerPiece, String quantityMode, BigDecimal areaSqm,
            Integer piecesInput, String wastageMode, BigDecimal wastageValue, Integer piecesPerBox,
            BigDecimal unitPrice, BigDecimal discountPct, String originCountry,
            Integer leadTimeMinDays, Integer leadTimeMaxDays, String itemNotes,
            BigDecimal piecesPerSqm, int piecesBeforeWastage, int piecesAfterWastage, int piecesFinal,
            Integer boxes, BigDecimal netUnitPrice, BigDecimal lineAmount,
            String descriptionLine, String sizeLine, String calculationLine,
            String lineType, BigDecimal quantity, String unit, BigDecimal specialPriceSqm,
            BigDecimal adjustmentPct, LocalDate adjustmentDeadline, String specialPriceLine,
            BigDecimal adjustmentAmount, boolean hasPicture, String picturePlacement, String pictureUrl,
            BigDecimal sqmPerBox, boolean roundToFullBox) {
            this(id, seq, locationLabel, catalogPriceId, productCode, brand, model, color, texture, sizeText,
                thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput, wastageMode, wastageValue,
                piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays, leadTimeMaxDays, itemNotes,
                piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal, boxes, netUnitPrice,
                lineAmount, descriptionLine, sizeLine, calculationLine, lineType, quantity, unit,
                specialPriceSqm, adjustmentPct, adjustmentDeadline, specialPriceLine, adjustmentAmount,
                hasPicture, picturePlacement, pictureUrl, sqmPerBox, roundToFullBox,
                false, null, null, null, null, null);
        }

        /** The pre-V176 canonical shape (no {@link #sqmPerBox}). */
        public DealQuotationItemDto(
            long id, int seq, String locationLabel, Long catalogPriceId, String productCode,
            String brand, String model, String color, String texture, String sizeText,
            BigDecimal thicknessMm, BigDecimal sqmPerPiece, String quantityMode, BigDecimal areaSqm,
            Integer piecesInput, String wastageMode, BigDecimal wastageValue, Integer piecesPerBox,
            BigDecimal unitPrice, BigDecimal discountPct, String originCountry,
            Integer leadTimeMinDays, Integer leadTimeMaxDays, String itemNotes,
            BigDecimal piecesPerSqm, int piecesBeforeWastage, int piecesAfterWastage, int piecesFinal,
            Integer boxes, BigDecimal netUnitPrice, BigDecimal lineAmount,
            String descriptionLine, String sizeLine, String calculationLine,
            String lineType, BigDecimal quantity, String unit, BigDecimal specialPriceSqm,
            BigDecimal adjustmentPct, LocalDate adjustmentDeadline, String specialPriceLine,
            BigDecimal adjustmentAmount, boolean hasPicture, String picturePlacement, String pictureUrl) {
            this(id, seq, locationLabel, catalogPriceId, productCode, brand, model, color, texture, sizeText,
                thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput, wastageMode, wastageValue,
                piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays, leadTimeMaxDays, itemNotes,
                piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal, boxes, netUnitPrice,
                lineAmount, descriptionLine, sizeLine, calculationLine, lineType, quantity, unit,
                specialPriceSqm, adjustmentPct, adjustmentDeadline, specialPriceLine, adjustmentAmount,
                hasPicture, picturePlacement, pictureUrl, null, true);
        }

        /** The pre-GLA-75 shape — no picture. Kept so existing call sites (the calculate-line
         * preview and many test fixtures) compile unchanged; same device as
         * {@code DealQuotationRequests.ItemInput}'s legacy constructor. */
        public DealQuotationItemDto(
            long id, int seq, String locationLabel, Long catalogPriceId, String productCode,
            String brand, String model, String color, String texture, String sizeText,
            BigDecimal thicknessMm, BigDecimal sqmPerPiece, String quantityMode, BigDecimal areaSqm,
            Integer piecesInput, String wastageMode, BigDecimal wastageValue, Integer piecesPerBox,
            BigDecimal unitPrice, BigDecimal discountPct, String originCountry,
            Integer leadTimeMinDays, Integer leadTimeMaxDays, String itemNotes,
            BigDecimal piecesPerSqm, int piecesBeforeWastage, int piecesAfterWastage, int piecesFinal,
            Integer boxes, BigDecimal netUnitPrice, BigDecimal lineAmount,
            String descriptionLine, String sizeLine, String calculationLine,
            String lineType, BigDecimal quantity, String unit, BigDecimal specialPriceSqm,
            BigDecimal adjustmentPct, LocalDate adjustmentDeadline, String specialPriceLine,
            BigDecimal adjustmentAmount) {
            this(id, seq, locationLabel, catalogPriceId, productCode, brand, model, color, texture, sizeText,
                thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput, wastageMode, wastageValue,
                piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays, leadTimeMaxDays, itemNotes,
                piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal, boxes, netUnitPrice,
                lineAmount, descriptionLine, sizeLine, calculationLine, lineType, quantity, unit,
                specialPriceSqm, adjustmentPct, adjustmentDeadline, specialPriceLine, adjustmentAmount,
                false, null, null, null, true);
        }

        /** This item carrying {@code value} as its {@link #sqmPerBox} (repository/preview paths). */
        public DealQuotationItemDto withSqmPerBox(BigDecimal value) {
            return new DealQuotationItemDto(id, seq, locationLabel, catalogPriceId, productCode, brand, model,
                color, texture, sizeText, thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput,
                wastageMode, wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes, piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal,
                boxes, netUnitPrice, lineAmount, descriptionLine, sizeLine, calculationLine, lineType, quantity,
                unit, specialPriceSqm, adjustmentPct, adjustmentDeadline, specialPriceLine, adjustmentAmount,
                hasPicture, picturePlacement, pictureUrl, value, roundToFullBox,
                priceChangedFromCeo, ceoListUnitPrice, ceoDiscountPct, ceoSpecialPriceSqm, ceoDirectNetPrice,
                ceoNetUnitPrice);
        }

        /** This item carrying {@code value} as its {@link #roundToFullBox} — same device as
         * {@link #withSqmPerBox}, for the same reason (both are appended fields written after the
         * shorter legacy constructor most call sites still build from). */
        public DealQuotationItemDto withRoundToFullBox(boolean value) {
            return new DealQuotationItemDto(id, seq, locationLabel, catalogPriceId, productCode, brand, model,
                color, texture, sizeText, thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput,
                wastageMode, wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes, piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal,
                boxes, netUnitPrice, lineAmount, descriptionLine, sizeLine, calculationLine, lineType, quantity,
                unit, specialPriceSqm, adjustmentPct, adjustmentDeadline, specialPriceLine, adjustmentAmount,
                hasPicture, picturePlacement, pictureUrl, sqmPerBox, value,
                priceChangedFromCeo, ceoListUnitPrice, ceoDiscountPct, ceoSpecialPriceSqm, ceoDirectNetPrice,
                ceoNetUnitPrice);
        }

        /** This item carrying the GLA-123 CEO-comparison fields — same device as
         * {@link #withSqmPerBox}/{@link #withRoundToFullBox} (appended fields written after the
         * shorter legacy constructors most call sites still build from). */
        public DealQuotationItemDto withCeoComparison(boolean changed, BigDecimal listUnitPrice,
                BigDecimal discountPct2, BigDecimal specialPriceSqm2, BigDecimal directNetPrice, BigDecimal netUnitPrice2) {
            return new DealQuotationItemDto(id, seq, locationLabel, catalogPriceId, productCode, brand, model,
                color, texture, sizeText, thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput,
                wastageMode, wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes, piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal,
                boxes, netUnitPrice, lineAmount, descriptionLine, sizeLine, calculationLine, lineType, quantity,
                unit, specialPriceSqm, adjustmentPct, adjustmentDeadline, specialPriceLine, adjustmentAmount,
                hasPicture, picturePlacement, pictureUrl, sqmPerBox, roundToFullBox,
                changed, listUnitPrice, discountPct2, specialPriceSqm2, directNetPrice, netUnitPrice2);
        }

        /** This item with its picture fields set from the stored link (repository read path). */
        public DealQuotationItemDto withPicture(long quotationId, String placement) {
            boolean has = placement != null;
            return new DealQuotationItemDto(id, seq, locationLabel, catalogPriceId, productCode, brand, model,
                color, texture, sizeText, thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput,
                wastageMode, wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes, piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal,
                boxes, netUnitPrice, lineAmount, descriptionLine, sizeLine, calculationLine, lineType, quantity,
                unit, specialPriceSqm, adjustmentPct, adjustmentDeadline, specialPriceLine, adjustmentAmount,
                has, has ? placement : null,
                has ? "/api/deal-quotations/" + quotationId + "/items/" + id + "/picture" : null, sqmPerBox,
                roundToFullBox,
                priceChangedFromCeo, ceoListUnitPrice, ceoDiscountPct, ceoSpecialPriceSqm, ceoDirectNetPrice,
                ceoNetUnitPrice);
        }
    }
}
