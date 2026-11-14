package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DealStage;

/**
 * Spec 2026-11-15 §7.2 — "deals fill from quotations". Approving a DEAL_DIRECT quotation (the moment it reaches the
 * customer) advances the deal onto its recipient's stage, forward-only, and writes the quotation's addressee
 * ({@code customer_name}) into the matching blank {@code designer_name} / {@code owner_name} / {@code buyer_name}.
 * A name the rep typed is never overwritten. Real service, real Postgres. Annotated itself: {@code @EnabledIf} is not
 * inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class DealDirectApprovalFillsDealIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

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

    @Test
    void approvalFillsBlankDesignerName() {
        long ticket = deal(salesActor);
        assertThat(parties(ticket).get("designer_name")).isNull();

        DealQuotationDto approved = approved(ticket, salesActor, "DESIGNER");

        assertThat(approved.customerName()).isNotBlank();
        assertThat(parties(ticket).get("designer_name")).isEqualTo(approved.customerName());
    }

    @Test
    void approvalFillsAWhitespaceOnlyDesignerName() {
        long ticket = deal(salesActor);
        setParty(ticket, "designer_name", "   ");

        DealQuotationDto approved = approved(ticket, salesActor, "DESIGNER");

        assertThat(parties(ticket).get("designer_name")).isEqualTo(approved.customerName());
    }

    @Test
    void approvalDoesNotOverwriteTypedDesigner() {
        long ticket = deal(salesActor);
        setParty(ticket, "designer_name", "คุณบี");

        approved(ticket, salesActor, "DESIGNER");

        assertThat(parties(ticket).get("designer_name")).isEqualTo("คุณบี");
    }

    @Test
    void ownerAndBuyerRecipientsFillTheirOwnColumn() {
        long ownerDeal = deal(salesActor);
        DealQuotationDto toOwner = approved(ownerDeal, salesActor, "OWNER");
        assertThat(parties(ownerDeal)).containsEntry("owner_name", toOwner.customerName())
            .containsEntry("designer_name", null).containsEntry("buyer_name", null);

        long buyerDeal = deal(salesActor);
        DealQuotationDto toBuyer = approved(buyerDeal, salesActor, "BUYER");
        assertThat(parties(buyerDeal)).containsEntry("buyer_name", toBuyer.customerName())
            .containsEntry("designer_name", null).containsEntry("owner_name", null);
    }

    @Test
    void lostDealIsNotAdvancedOrFilled() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("DESIGNER"), salesActor);
        tickets.updateSalesStage(ticket, DealStage.SPEC_APPROVED);
        jdbc.update("UPDATE sales.ticket SET lifecycle = :l WHERE ticket_id = :id",
            Map.of("l", DealLifecycle.CLOSED_LOST, "id", ticket));

        approve(created);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.SPEC_APPROVED);
        assertThat(parties(ticket).get("designer_name")).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void approve(DealQuotationDto created) {
        quotationService.submit(created.id(), salesActor);
        quotationService.approve(created.id(), new ApproveRequest(null), salesManagerActor);
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
