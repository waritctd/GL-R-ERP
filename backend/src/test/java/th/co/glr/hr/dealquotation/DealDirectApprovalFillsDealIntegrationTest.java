package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.EntryChannel;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealparty.DealPartyRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.ReusableApprovedItemsDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Spec 2026-11-15 §7.2 — "deals fill from quotations", as corrected after review (H1/H2). Approving a DEAL_DIRECT
 * quotation advances the deal forward-only onto its recipient's stage — an UNSPECIFIED recipient advances like a BUYER
 * one (QUOTE_BUYER, else the nearest on-route stage before it) — and fills a blank party name ONLY from a source that names that party:
 * designer_name from the designer directory entry behind the quotation's D.Co. (unit_code), for ANY recipient (round-2
 * N3: the D.Co. is the project's designer); buyer_name from the quotation's BUYER party's customer, for a BUYER or
 * UNSPECIFIED recipient; owner_name never. Expectations are independent fixture values (the seeded
 * directory name, the seeded bidder), never the quotation's own bill-to customer. Real service, real Postgres.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class DealDirectApprovalFillsDealIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private static final String DESIGNER_CODE = "ZT01";
    private static final String DESIGNER_NAME = "ทดสอบ สตูดิโอ ออกแบบ";
    private static final String BIDDER_NAME = "บริษัท ผู้รับเหมา ประมูล จำกัด";

    @BeforeEach
    void seedDesigner() {
        jdbc.update("INSERT INTO sales.designer (code, name) VALUES (:c, :n)",
            Map.of("c", DESIGNER_CODE, "n", DESIGNER_NAME));
    }

    // ── stage ─────────────────────────────────────────────────────────────────

    /** Creation already advances (D-2); this pins that APPROVAL does too — e.g. a deal moved back after creation. */
    @Test
    void approvingDesignerQuotationAdvancesToQuoteDesignSide() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("DESIGNER"), salesActor);
        tickets.updateSalesStage(ticket, DealStage.SPEC_APPROVED);

        approve(created);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);
    }

    @Test
    void approvalNeverRegressesStage() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("DESIGNER"), salesActor);
        tickets.updateSalesStage(ticket, DealStage.NEGOTIATION);

        approve(created);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.NEGOTIATION);
    }

    /** H2 / round-2 N1: 178 of 289 approved quotations on prod are UNSPECIFIED (legacy rows and their
     * revisions/reorders). Owner-approved V204 reading: a direct quotation is addressed to a buyer, so it advances like
     * a BUYER one — QUOTE_BUYER, never the designer stage. */
    @Test
    void unspecifiedRecipient_onADesignerLedDeal_advancesLikeBuyer_toQuoteBuyer() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto created = unspecified(ticket);

        approve(created);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_BUYER);
    }

    // ── party names ───────────────────────────────────────────────────────────

    @Test
    void designerName_comesFromTheDesignerDirectoryEntryBehindTheDCo() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("DESIGNER"), salesActor);
        setUnitCode(created.id(), DESIGNER_CODE);

        approve(created);

        assertThat(parties(ticket).get("designer_name")).isEqualTo(DESIGNER_NAME);
    }

    @Test
    void approvalDoesNotOverwriteTypedDesigner() {
        long ticket = deal(salesActor);
        setParty(ticket, "designer_name", "คุณบี");
        DealQuotationDto created = quotationService.create(ticket, draft("DESIGNER"), salesActor);
        setUnitCode(created.id(), DESIGNER_CODE);

        approve(created);

        assertThat(parties(ticket).get("designer_name")).isEqualTo("คุณบี");
    }

    @Test
    void buyerName_comesFromTheQuotationsBuyerParty() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("BUYER"), salesActor);
        attachToBuyerParty(ticket, created.id());

        approve(created);

        assertThat(parties(ticket)).containsEntry("buyer_name", BIDDER_NAME).containsEntry("designer_name", null)
            .containsEntry("owner_name", null);
    }

    @Test
    void lostDealIsNotAdvancedOrFilled() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("DESIGNER"), salesActor);
        setUnitCode(created.id(), DESIGNER_CODE);
        tickets.updateSalesStage(ticket, DealStage.SPEC_APPROVED);
        jdbc.update("UPDATE sales.ticket SET lifecycle = :l WHERE ticket_id = :id",
            Map.of("l", DealLifecycle.CLOSED_LOST, "id", ticket));

        approve(created);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.SPEC_APPROVED);
        assertThat(parties(ticket).get("designer_name")).isNull();
    }

    // ── add-on reuse (V232): an add-on that reuses approved lines at their approved price needs no second approval ──

    /** Every line reuses an approved price and the deposit % is the first order's: APPROVED in one submit, and the
     * approvers (sales manager) hear nothing. */
    @Test
    void addOn_allLinesReused_approvesInOneSubmit_withoutNotifyingApprovers() {
        long addOn = addOnWithApprovedRoot();

        DealQuotationDto submitted = submitReuse(addOn, UnaryOperator.identity(), 50);

        assertThat(submitted.docStatus()).isEqualTo("APPROVED");
        assertThat(submitted.approvalNote()).isEqualTo(DealQuotationService.REUSE_RULE_NOTE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification WHERE employee_id = :e",
            Map.of("e", salesManagerActor.id()), Integer.class)).isZero();
    }

    /** The lock is the server's: a reused line posted with a different unit price is NOT approved by rule — it falls
     * back to the ordinary flow (PENDING_APPROVAL, approvers notified); never a 400. */
    @Test
    void addOn_reusedLineWithAChangedPrice_fallsBackToApproval() {
        long addOn = addOnWithApprovedRoot();

        DealQuotationDto submitted = submitReuse(addOn,
            item -> item.withPerPiecePrices(item.unitPrice().add(BigDecimal.ONE), null), 50);

        assertThat(submitted.docStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification WHERE employee_id = :e",
            Map.of("e", salesManagerActor.id()), Integer.class)).isPositive();
    }

    /** The first order's deposit % is part of the rule: a different one needs approval. */
    @Test
    void addOn_changedDepositPercent_fallsBackToApproval() {
        long addOn = addOnWithApprovedRoot();

        assertThat(submitReuse(addOn, UnaryOperator.identity(), 30).docStatus()).isEqualTo("PENDING_APPROVAL");
    }

    /** A claim of reuse for an item that was never approved in this project's chain (here another deal's approved
     * line) is dropped when saved, so the line is an ordinary one and goes to approval. */
    @Test
    void addOn_claimOfAnItemOutsideTheChain_isIgnoredAndNeedsApproval() {
        long addOn = addOnWithApprovedRoot();
        DealQuotationDto elsewhere = approved(deal(otherSalesActor), otherSalesActor, "BUYER");
        long foreignItemId = elsewhere.items().get(0).id();

        DealQuotationDto submitted = submitReuse(addOn, item -> item.withSourceQuotationItemId(foreignItemId), 50);

        assertThat(submitted.docStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(submitted.items().get(0).sourceQuotationItemId()).isNull();
    }

    @Test
    void reusableApprovedItems_isConflictOnANonAddOnDeal() {
        long ticket = deal(salesActor);
        approved(ticket, salesActor, "BUYER");

        ApiException refusal = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
            () -> quotationService.reusableApprovedItems(ticket, salesActor));

        assertThat(refusal.getStatus().value()).isEqualTo(409);
    }

    /** The rule checks the ITEM, not only the price: a line whose product code or sqm/piece differs from its source,
     * price kept, is not approved by rule. */
    @Test
    void addOn_reusedLineWithAChangedProductCodeOrSqmPerPiece_fallsBackToApproval() {
        long addOn = addOnWithApprovedRoot();
        assertThat(submitReuse(addOn, i -> retarget(i, "OTHER-CODE", i.sqmPerPiece()), 50).docStatus())
            .isEqualTo("PENDING_APPROVAL");
        long second = addOnWithApprovedRoot();
        assertThat(submitReuse(second, i -> retarget(i, i.productCode(),
            i.sqmPerPiece() == null ? null : i.sqmPerPiece().add(new BigDecimal("0.01"))), 50).docStatus())
            .isEqualTo("PENDING_APPROVAL");
    }

    /** Header terms are part of the rule: a different credit-days (like a different deposit %) needs approval. */
    @Test
    void addOn_changedCreditDays_fallsBackToApproval() {
        long addOn = addOnWithApprovedRoot();

        assertThat(submitReuse(addOn, UnaryOperator.identity(), 50, 15).docStatus()).isEqualTo("PENDING_APPROVAL");
    }

    /** The printed delivery promise is part of what is sold: a reused line with a changed lead time needs approval. */
    @Test
    void addOn_reusedLineWithAChangedLeadTime_fallsBackToApproval() {
        long addOn = addOnWithApprovedRoot();

        DealQuotationDto submitted = submitReuse(addOn, i -> new ItemInput(i.locationLabel(), i.catalogPriceId(),
            i.productCode(), i.brand(), i.model(), i.color(), i.texture(), i.sizeText(), i.thicknessMm(), i.sqmPerPiece(),
            i.quantityMode(), i.areaSqm(), i.piecesInput(), i.wastageMode(), i.wastageValue(), i.piecesPerBox(),
            i.unitPrice(), i.discountPct(), i.originCountry(),
            i.leadTimeMinDays() == null ? 1 : i.leadTimeMinDays() + 1, i.leadTimeMaxDays() == null ? null : i.leadTimeMaxDays() + 1, i.itemNotes(), i.lineType(),
            i.description(), i.quantity(), i.unit(), i.specialPriceSqm(), i.directNetPrice(), i.adjustmentPct(),
            i.adjustmentDeadline(), i.adjustmentAmount(), i.id(), i.sqmPerBox(), i.roundToFullBox(), i.listPriceSqm(),
            i.directNetPriceSqm(), i.priceMode(), i.stockSource(), i.sourceQuotationItemId()), 50);

        assertThat(submitted.docStatus()).isEqualTo("PENDING_APPROVAL");
    }

    /** Free text nobody approved must not print on a rule-approved quotation: a reused line with a changed note needs approval. */
    @Test
    void addOn_reusedLineWithAChangedNote_fallsBackToApproval() {
        long addOn = addOnWithApprovedRoot();

        DealQuotationDto submitted = submitReuse(addOn, i -> new ItemInput(i.locationLabel(), i.catalogPriceId(),
            i.productCode(), i.brand(), i.model(), i.color(), i.texture(), i.sizeText(), i.thicknessMm(), i.sqmPerPiece(),
            i.quantityMode(), i.areaSqm(), i.piecesInput(), i.wastageMode(), i.wastageValue(), i.piecesPerBox(),
            i.unitPrice(), i.discountPct(), i.originCountry(), i.leadTimeMinDays(), i.leadTimeMaxDays(), "ราคานี้รวมค่าขนส่ง",
            i.lineType(), i.description(), i.quantity(), i.unit(), i.specialPriceSqm(), i.directNetPrice(), i.adjustmentPct(),
            i.adjustmentDeadline(), i.adjustmentAmount(), i.id(), i.sqmPerBox(), i.roundToFullBox(), i.listPriceSqm(),
            i.directNetPriceSqm(), i.priceMode(), i.stockSource(), i.sourceQuotationItemId()), 50);

        assertThat(submitted.docStatus()).isEqualTo("PENDING_APPROVAL");
    }

    /** Only approved prices addressed to the SAME party are sources: a quotation of the project addressed to another
     * party (a losing bidder) is not offered, and a claim on its line is dropped, so the line needs approval. */
    @Test
    void addOn_approvedQuotationOfAnotherParty_isNotOfferedAndCannotBeClaimed() {
        long addOn = addOnWithApprovedRoot();
        List<ItemInput> lines = quotationService.reusableApprovedItems(addOn, salesActor).items().stream()
            .map(i -> i.item()).toList();
        long root = jdbc.queryForObject("SELECT parent_ticket_id FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", addOn), Long.class);
        long rootQuotation = jdbc.queryForObject("SELECT quotation_id FROM sales.quotation WHERE ticket_id = :id",
            Map.of("id", root), Long.class);
        attachToBuyerParty(root, rootQuotation);

        assertThat(quotationService.reusableApprovedItems(addOn, salesActor).items()).isEmpty();
        DealQuotationDto created = quotationService.create(addOn, new UpsertDealQuotationRequest(null, null, null,
            null, 50, "CASH_ON_DELIVERY", 0, 30, null, "NET", "TH", "THB", lines).withRecipientType("BUYER"), salesActor);
        assertThat(created.items().get(0).sourceQuotationItemId()).isNull();
        assertThat(quotationService.submit(created.id(), salesActor).docStatus()).isEqualTo("PENDING_APPROVAL");
    }

    /** A human said no: resubmitting (the rejected draft becomes a revision) is never approved by the rule. */
    @Test
    void addOn_rejectedByAPerson_isNotApprovedByRuleWhenResubmitted() {
        long addOn = addOnWithApprovedRoot();
        List<ItemInput> lines = quotationService.reusableApprovedItems(addOn, salesActor).items().stream()
            .map(i -> i.item()).toList();
        DealQuotationDto created = quotationService.create(addOn, new UpsertDealQuotationRequest(null, null, null,
            null, 50, "CASH_ON_DELIVERY", 0, 30, null, "NET", "TH", "THB", lines).withRecipientType("BUYER"), salesActor);
        jdbc.update("UPDATE sales.quotation SET doc_status = 'PENDING_APPROVAL' WHERE quotation_id = :id",
            Map.of("id", created.id()));
        quotationService.reject(created.id(), new DealQuotationRequests.RejectRequest("ราคาต้องตรวจอีกครั้ง"), salesManagerActor);

        assertThat(quotationService.submit(created.id(), salesActor).docStatus()).isEqualTo("PENDING_APPROVAL");
    }

    /** A rule-approved quotation records its basis and sources, shows no person as approver on the DTO (the PDF prints the source's), and tells the rep nothing. */
    @Test
    void addOn_ruleApproval_isMarked_hasNoApproverNameOrSignature_andDoesNotNotifyTheRep() {
        jdbc.update("INSERT INTO hr.employee_signature (employee_id, mime_type, image) VALUES (:e, 'image/png', :img)",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("e", salesManagerActor.id())
                .addValue("img", new byte[] {1, 2, 3}));
        long addOn = addOnWithApprovedRoot();

        DealQuotationDto submitted = submitReuse(addOn, UnaryOperator.identity(), 50);

        // The printed approver slot is the SOURCE quotation's approver: name + signature image, with the reuse text.
        var signatories = quotationService.toRenderModel(submitted).signatories();
        assertThat(signatories.approvedBy()).contains("(อนุมัติตามราคาเดิม QT-");
        assertThat(signatories.approvedBy()).doesNotStartWith("(");
        assertThat(signatories.approverSignaturePng()).containsExactly(1, 2, 3);

        assertThat(submitted.approvalBasis()).isEqualTo("REUSED_APPROVED_PRICE");
        assertThat(submitted.approvalSourceNumbers()).startsWith("QT-");
        assertThat(submitted.approvedByName()).isNull();
        assertThat(submitted.approverHasSignature()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.quotation_approver_snapshot WHERE quotation_id = :id",
            Map.of("id", submitted.id()), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification WHERE employee_id = :e",
            Map.of("e", salesActor.id()), Integer.class)).isZero();
    }

    /** The same ItemInput with a different product code / sqm per piece, price and everything else unchanged. */
    private static ItemInput retarget(ItemInput i, String productCode, BigDecimal sqmPerPiece) {
        return new ItemInput(i.locationLabel(), i.catalogPriceId(), productCode, i.brand(), i.model(), i.color(),
            i.texture(), i.sizeText(), i.thicknessMm(), sqmPerPiece, i.quantityMode(), i.areaSqm(), i.piecesInput(),
            i.wastageMode(), i.wastageValue(), i.piecesPerBox(), i.unitPrice(), i.discountPct(), i.originCountry(),
            i.leadTimeMinDays(), i.leadTimeMaxDays(), i.itemNotes(), i.lineType(), i.description(), i.quantity(),
            i.unit(), i.specialPriceSqm(), i.directNetPrice(), i.adjustmentPct(), i.adjustmentDeadline(),
            i.adjustmentAmount(), i.id(), i.sqmPerBox(), i.roundToFullBox(), i.listPriceSqm(), i.directNetPriceSqm(),
            i.priceMode(), i.stockSource(), i.sourceQuotationItemId());
    }

    private long addOnWithApprovedRoot() {
        long root = deal(salesActor);
        approved(root, salesActor, "BUYER");
        long addOn = new DealPartyRepository(jdbc).forkAddOn(root, summary(root).code() + "-2", salesActor.id());
        // DealPartyService#createAddOnOrder also gives the order its party row (the buyer, still bidding).
        jdbc.update("INSERT INTO sales.deal_party (ticket_id, customer_id, role, status, is_headline) "
            + "VALUES (:t, :c, 'BUYER', 'ACTIVE', TRUE)", Map.of("t", addOn, "c", customer.id()));
        return addOn;
    }

    /** Saves an add-on draft whose lines are the picker's items (each mapped by {@code tweak}), then submits it. */
    private DealQuotationDto submitReuse(long addOn, UnaryOperator<ItemInput> tweak, int depositPercent) {
        return submitReuse(addOn, tweak, depositPercent, 0);
    }

    private DealQuotationDto submitReuse(long addOn, UnaryOperator<ItemInput> tweak, int depositPercent, int creditDays) {
        // The root order's own approval (the fixture) notified the approvers; only what THIS submit sends counts.
        jdbc.update("DELETE FROM hr.notification", Map.of());
        ReusableApprovedItemsDto reusable = quotationService.reusableApprovedItems(addOn, salesActor);
        assertThat(reusable.sourceDepositPercent()).isEqualTo(50);
        List<ItemInput> lines = reusable.items().stream().map(i -> tweak.apply(i.item())).toList();
        DealQuotationDto created = quotationService.create(addOn, new UpsertDealQuotationRequest(null, null, null,
            null, depositPercent, "CASH_ON_DELIVERY", creditDays, 30, null, "NET", "TH", "THB", lines)
            .withRecipientType("BUYER"), salesActor);
        return quotationService.submit(created.id(), salesActor);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private DealQuotationDto unspecified(long ticket) {
        DealQuotationDto created = quotationService.create(ticket, draft("DESIGNER"), salesActor);
        jdbc.update("UPDATE sales.quotation SET recipient_type = 'UNSPECIFIED' WHERE quotation_id = :id",
            Map.of("id", created.id()));
        tickets.updateSalesStage(ticket, DealStage.LEAD_APPROACH);
        return created;
    }

    private void approve(DealQuotationDto created) {
        quotationService.submit(created.id(), salesActor);
        quotationService.approve(created.id(), new ApproveRequest(null), salesManagerActor);
    }

    private void setUnitCode(long quotationId, String code) {
        jdbc.update("UPDATE sales.quotation SET unit_code = :c WHERE quotation_id = :id",
            Map.of("c", code, "id", quotationId));
    }

    /** A second customer bidding as BUYER on this deal, and the quotation addressed to that party. */
    private void attachToBuyerParty(long ticket, long quotationId) {
        CustomerDto bidder = customers.create(BIDDER_NAME, "0100000000303", "303 ถนนทดสอบ", "สนญ.", "02-000-0303");
        Long partyId = jdbc.queryForObject("""
            INSERT INTO sales.deal_party (ticket_id, customer_id, role, status)
            VALUES (:t, :c, 'BUYER', 'QUOTED') RETURNING party_id
            """, Map.of("t", ticket, "c", bidder.id()), Long.class);
        jdbc.update("UPDATE sales.quotation SET party_id = :p WHERE quotation_id = :id",
            Map.of("p", partyId, "id", quotationId));
    }

    private Map<String, Object> parties(long ticketId) {
        return jdbc.queryForMap(
            "SELECT designer_name, owner_name, buyer_name FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId));
    }

    private void setParty(long ticketId, String column, String value) {
        jdbc.update("UPDATE sales.ticket SET " + column + " = :v WHERE ticket_id = :id",
            Map.of("v", value, "id", ticketId));
    }
}
