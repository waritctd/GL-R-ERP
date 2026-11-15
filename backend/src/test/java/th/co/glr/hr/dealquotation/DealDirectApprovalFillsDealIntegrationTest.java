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

/**
 * Spec 2026-11-15 §7.2 — "deals fill from quotations", as corrected after review (H1/H2). Approving a DEAL_DIRECT
 * quotation advances the deal forward-only onto its recipient's stage — or, for an UNSPECIFIED recipient, onto the
 * first quote stage on the deal's route — and fills a blank party name ONLY from a source that names that party:
 * designer_name from the designer directory entry behind the quotation's D.Co. (unit_code); buyer_name from the
 * quotation's BUYER party's customer; owner_name never. Expectations are independent fixture values (the seeded
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

    /** H2: 178 of 289 approved quotations on prod are UNSPECIFIED (legacy rows and their revisions/reorders). */
    @Test
    void unspecifiedRecipient_advancesADesignerLedDealToTheFirstQuoteStage() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto created = unspecified(ticket);

        approve(created);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);
    }

    @Test
    void unspecifiedRecipient_onAnOwnerDirectDeal_advancesToQuoteOwner_theFirstOnRoute() {
        long ticket = deal(salesActor, EntryChannel.OWNER_DIRECT);
        DealQuotationDto created = quotationService.create(ticket, draft("OWNER"), salesActor);
        jdbc.update("UPDATE sales.quotation SET recipient_type = 'UNSPECIFIED' WHERE quotation_id = :id",
            Map.of("id", created.id()));
        tickets.updateSalesStage(ticket, DealStage.LEAD_APPROACH);

        approve(created);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_OWNER);
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

    @Test
    void ownerName_isNeverFilledAutomatically() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("OWNER"), salesActor);
        setUnitCode(created.id(), DESIGNER_CODE);

        approve(created);

        assertThat(parties(ticket)).containsEntry("owner_name", null).containsEntry("designer_name", null)
            .containsEntry("buyer_name", null);
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
