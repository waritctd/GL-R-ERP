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

    @Test
    void unspecifiedRecipient_onAnOwnerDirectDeal_advancesLikeBuyer_toQuoteBuyer() {
        long ticket = deal(salesActor, EntryChannel.OWNER_DIRECT);
        DealQuotationDto created = quotationService.create(ticket, draft("OWNER"), salesActor);
        jdbc.update("UPDATE sales.quotation SET recipient_type = 'UNSPECIFIED' WHERE quotation_id = :id",
            Map.of("id", created.id()));
        tickets.updateSalesStage(ticket, DealStage.LEAD_APPROACH);

        approve(created);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_BUYER);
    }

    /** Round-2 N1: BUYER_DIRECT used to get nothing; QUOTE_BUYER is on its route, so it now moves too. */
    @Test
    void unspecifiedRecipient_onABuyerDirectDeal_advancesToQuoteBuyer() {
        long ticket = deal(salesActor, EntryChannel.BUYER_DIRECT);
        DealQuotationDto created = quotationService.create(ticket, draft("BUYER"), salesActor);
        jdbc.update("UPDATE sales.quotation SET recipient_type = 'UNSPECIFIED' WHERE quotation_id = :id",
            Map.of("id", created.id()));
        tickets.updateSalesStage(ticket, DealStage.LEAD_APPROACH);

        approve(created);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_BUYER);
    }

    @Test
    void unspecifiedRecipient_neverRegressesADealAlreadyPast() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto created = unspecified(ticket);
        tickets.updateSalesStage(ticket, DealStage.NEGOTIATION);

        approve(created);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.NEGOTIATION);
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

    /** Wrong-way-round: the bill-to customer is NOT the designer, so it must never land in designer_name. */
    @Test
    void designerQuotation_neverWritesTheBillToCustomerIntoDesignerName() {
        long ticket = deal(salesActor);

        DealQuotationDto approved = approved(ticket, salesActor, "DESIGNER");

        assertThat(approved.customerName()).isEqualTo(customer.name());
        assertThat(parties(ticket).get("designer_name")).isNull();
    }

    @Test
    void designerName_isSkippedWhenTheDCoIsNotInTheDirectory() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("DESIGNER"), salesActor);
        setUnitCode(created.id(), "NOPE99");

        approve(created);

        assertThat(parties(ticket).get("designer_name")).isNull();
    }

    @Test
    void approvalFillsAWhitespaceOnlyDesignerName() {
        long ticket = deal(salesActor);
        setParty(ticket, "designer_name", "   ");
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

    /** Round-2 N3: the D.Co. is the project's designer whoever the document is addressed to, so an OWNER quotation
     * still names the designer from the directory — and owner_name is still never filled. */
    @Test
    void ownerQuotation_fillsDesignerNameFromItsDCo_butNeverOwnerName() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("OWNER"), salesActor);
        setUnitCode(created.id(), DESIGNER_CODE);

        approve(created);

        assertThat(parties(ticket)).containsEntry("owner_name", null).containsEntry("designer_name", DESIGNER_NAME)
            .containsEntry("buyer_name", null);
    }

    /** Round-2 N3: a BUYER quotation names both its bidder and, from its D.Co., the designer. */
    @Test
    void buyerQuotation_fillsDesignerNameFromItsDCoToo() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("BUYER"), salesActor);
        attachToBuyerParty(ticket, created.id());
        setUnitCode(created.id(), DESIGNER_CODE);

        approve(created);

        assertThat(parties(ticket)).containsEntry("designer_name", DESIGNER_NAME).containsEntry("buyer_name", BIDDER_NAME)
            .containsEntry("owner_name", null);
    }

    /** Final review: an UNSPECIFIED quotation's BUYER party is the bill-to customer (V204), never a name source — it
     * fills the designer from its D.Co. and leaves buyer_name blank, even when its party is a real sibling bidder
     * (an UNSPECIFIED document cannot be told apart from the bill-to one). Never owner_name either. */
    @Test
    void unspecifiedQuotation_fillsDesignerFromItsDCo_butNeverBuyerName() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto created = unspecified(ticket);
        attachToBuyerParty(ticket, created.id());
        setUnitCode(created.id(), DESIGNER_CODE);

        approve(created);

        assertThat(parties(ticket)).containsEntry("designer_name", DESIGNER_NAME).containsEntry("buyer_name", null)
            .containsEntry("owner_name", null);
        // It still advances like a BUYER quotation (the stage rule is unchanged).
        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_BUYER);
    }

    @Test
    void ownerQuotation_neverOverwritesATypedDesigner() {
        long ticket = deal(salesActor);
        setParty(ticket, "designer_name", "คุณบี");
        DealQuotationDto created = quotationService.create(ticket, draft("OWNER"), salesActor);
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

    /** The quotation's party is not a BUYER (here a DESIGNER-role party): no bidder to name, nothing written. */
    @Test
    void buyerName_isSkippedWhenTheQuotationsPartyIsNotABuyer() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("BUYER"), salesActor);
        CustomerDto other = customers.create("บริษัท ไม่ใช่ผู้ซื้อ จำกัด", "0100000000404", "404 ถนนทดสอบ", "สนญ.",
            "02-000-0404");
        Long partyId = jdbc.queryForObject("""
            INSERT INTO sales.deal_party (ticket_id, customer_id, role, status)
            VALUES (:t, :c, 'DESIGNER', 'QUOTED') RETURNING party_id
            """, Map.of("t", ticket, "c", other.id()), Long.class);
        jdbc.update("UPDATE sales.quotation SET party_id = :p WHERE quotation_id = :id",
            Map.of("p", partyId, "id", created.id()));

        approve(created);

        assertThat(parties(ticket).get("buyer_name")).isNull();
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

    // ── add-on reuse (V231): an add-on that reuses approved lines at their approved price needs no second approval ──

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
        // The root order's own approval (the fixture) notified the approvers; only what THIS submit sends counts.
        jdbc.update("DELETE FROM hr.notification", Map.of());
        ReusableApprovedItemsDto reusable = quotationService.reusableApprovedItems(addOn, salesActor);
        assertThat(reusable.sourceDepositPercent()).isEqualTo(50);
        List<ItemInput> lines = reusable.items().stream().map(i -> tweak.apply(i.item())).toList();
        DealQuotationDto created = quotationService.create(addOn, new UpsertDealQuotationRequest(null, null, null,
            null, depositPercent, "CASH_ON_DELIVERY", 0, 30, null, "NET", "TH", "THB", lines)
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
