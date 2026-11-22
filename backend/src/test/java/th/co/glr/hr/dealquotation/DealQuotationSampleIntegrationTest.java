package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketRepository;

/** Spec 0001 child 1: a sample lives beside real quotations and never counts as a sale or becomes an order. */
class DealQuotationSampleIntegrationTest extends AbstractDealDirectQuotationIntegrationTest {

    private DealQuotationDto approvedSample(long ticketId) {
        return approved(ticketId, draft().withIsSample(true), salesActor);
    }

    private String status(long quotationId) {
        return quotationService.get(quotationId, salesActor).docStatus();
    }

    private String stage(long ticketId) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    @Test
    void sampleLivesBesideARealQuotationAndChangesNoValue() {
        long ticketId = deal(salesActor);
        DealQuotationDto real = approved(ticketId, salesActor);
        quotationService.confirmOrderFromDirectQuotation(real.id(), salesActor);
        TicketRepository tickets = new TicketRepository(jdbc);
        BigDecimal payable = tickets.payableAmount(ticketId);
        String stage = stage(ticketId);
        long countBefore = quotationService.counts(salesActor).all();

        DealQuotationDto sample = approvedSample(ticketId);

        assertThat(sample.isSample()).isTrue();
        assertThat(quotationService.counts(salesActor).all()).isEqualTo(countBefore);
        assertThat(status(real.id())).isEqualTo(QuotationStatus.APPROVED);
        assertThat(status(sample.id())).isEqualTo(QuotationStatus.APPROVED);
        assertThat(tickets.payableAmount(ticketId)).isEqualByComparingTo(payable);
        assertThat(stage(ticketId)).isEqualTo(stage);
    }

    @Test
    void aJobWithOnlySamplesStaysAtItsStage() {
        long ticketId = deal(salesActor);
        String stage = stage(ticketId);

        approvedSample(ticketId);

        assertThat(stage(ticketId)).isEqualTo(stage);
    }

    @Test
    void aSampleWaitingForApprovalStaysInThePendingCountOnly() {
        long ticketId = deal(salesActor);
        var before = quotationService.counts(salesManagerActor);
        DealQuotationDto sample = quotationService.create(ticketId, draft().withIsSample(true), salesActor);

        quotationService.submit(sample.id(), salesActor);

        var after = quotationService.counts(salesManagerActor);
        assertThat(after.pendingApproval()).isEqualTo(before.pendingApproval() + 1);
        assertThat(after.all()).isEqualTo(before.all());
    }

    @Test
    void confirmingASampleIsRefusedEvenOnAnAlreadyConfirmedDeal() {
        long ticketId = deal(salesActor);
        DealQuotationDto sample = approvedSample(ticketId);
        assertThat(refusal(() -> quotationService.confirmOrderFromDirectQuotation(sample.id(), salesActor),
            HttpStatus.CONFLICT).getMessage())
            .isEqualTo("ใบเสนอราคาสำหรับสั่งตัวอย่างยืนยันคำสั่งซื้อไม่ได้ เอาเครื่องหมายสั่งตัวอย่างออกก่อน");

        DealQuotationDto real = approved(ticketId, salesActor);
        quotationService.confirmOrderFromDirectQuotation(real.id(), salesActor);

        refusal(() -> quotationService.confirmOrderFromDirectQuotation(sample.id(), salesActor), HttpStatus.CONFLICT);
    }

    @Test
    void confirmingARealOrderLeavesAnOpenSampleAlone() {
        long ticketId = deal(salesActor);
        DealQuotationDto real = approved(ticketId, salesActor);
        DealQuotationDto openSample = quotationService.create(ticketId, draft().withIsSample(true), salesActor);

        quotationService.confirmOrderFromDirectQuotation(real.id(), salesActor);

        assertThat(status(openSample.id())).isEqualTo(QuotationStatus.DRAFT);
    }

    @Test
    void markGuardsRefuseTheUnsafeChanges() {
        long ticketId = deal(salesActor);
        DealQuotationDto real = approved(ticketId, salesActor);
        DealQuotationDto sample = approvedSample(ticketId);

        assertThat(refusal(() -> quotationService.setSample(sample.id(), false, salesActor), HttpStatus.CONFLICT)
            .getMessage()).contains(real.number());
        refusal(() -> quotationService.createReorder(sample.id(), salesActor), HttpStatus.CONFLICT);

        quotationService.confirmOrderFromDirectQuotation(real.id(), salesActor);
        refusal(() -> quotationService.setSample(real.id(), true, salesActor), HttpStatus.CONFLICT);
    }

    @Test
    void takingTheMarkOffMakesARealOrderPossible() {
        long ticketId = deal(salesActor);
        DealQuotationDto sample = approvedSample(ticketId);

        assertThat(quotationService.setSample(sample.id(), false, salesActor).isSample()).isFalse();

        quotationService.confirmOrderFromDirectQuotation(sample.id(), salesActor);
        assertThat(new TicketRepository(jdbc).isOrderConfirmedFromQuotation(ticketId, sample.id())).isTrue();
    }

    @Test
    void aDraftRevisionMarkedByMistakeCanBeTurnedBackToReal() {
        long ticketId = deal(salesActor);
        DealQuotationDto real = approved(ticketId, salesActor);
        DealQuotationDto revision = quotationService.createRevision(real.id(), salesActor);

        quotationService.setSample(revision.id(), true, salesActor);

        assertThat(quotationService.setSample(revision.id(), false, salesActor).isSample()).isFalse();
    }

    @Test
    void aRepWhoDoesNotOwnTheDealCannotChangeTheMark() {
        long ticketId = deal(salesActor);
        DealQuotationDto quotation = quotationService.create(ticketId, draft(), salesActor);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        UserPrincipal stranger = actor(createEmployee(employees, "พนักงานขาย อีกคน", "sales-other@glr.co.th",
            "SALES", "แผนกขาย", null), "sales");

        refusal(() -> quotationService.setSample(quotation.id(), true, stranger), HttpStatus.FORBIDDEN);
        refusal(() -> quotationService.update(quotation.id(), draft().withIsSample(true), salesManagerActor),
            HttpStatus.FORBIDDEN);
        refusal(() -> quotationService.create(ticketId, draft().withIsSample(true), salesManagerActor),
            HttpStatus.FORBIDDEN);
    }

    @Test
    void aRevisionKeepsTheMarkAndAReorderStartsReal() {
        long ticketId = deal(salesActor);
        DealQuotationDto sample = approvedSample(ticketId);

        DealQuotationDto revision = quotationService.createRevision(sample.id(), salesActor);
        DealQuotationDto reorder = quotationService.createReorder(sample.id(), salesActor);

        assertThat(revision.isSample()).isTrue();
        assertThat(reorder.isSample()).isFalse();
    }

    @Test
    void aBidOutcomePicksTheRealQuotationOverANewerSample() {
        long ticketId = deal(salesActor);
        DealQuotationDto real = approved(ticketId, salesActor);
        DealQuotationDto sample = quotationService.create(ticketId, draft().withIsSample(true), salesActor);
        quotationService.submit(sample.id(), salesActor);
        quotationService.approve(sample.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(real.partyId()).isNotNull();

        assertThat(new th.co.glr.hr.dealparty.DealPartyRepository(jdbc)
            .findLatestApprovedDirectQuotationId(real.partyId())).contains(real.id());
    }

    @Test
    void theHintNeverChangesTheMark() {
        long ticketId = deal(salesActor);
        ItemInput onePiece = new ItemInput(
            null, null, null, "Panaria", "Trilogy", "Ivory", "Lappato", "60x120", bd("10.00"),
            bd("0.72"), WastageCalculator.QUANTITY_MODE_PIECES, null, 1,
            WastageCalculator.WASTAGE_MODE_NONE, null, 2, bd("100.00"), BigDecimal.ZERO, "อิตาลี",
            75, 90, null, WastageCalculator.LINE_TYPE_TILE, null, null, null, null, null, null, null,
            null, null, null, false, null, null);

        DealQuotationDto saved = quotationService.create(ticketId, request("NET", onePiece, onePiece), salesActor);

        assertThat(saved.looksLikeSample()).isTrue();
        assertThat(saved.isSample()).isFalse();
    }
}
