package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;

/**
 * Quotation ↔ deal linking slice 2, S2-B4 — {@link TicketSummaryDto#liveDirectQuotation} on the deal
 * LIST ({@code TicketService#list} / {@code #listPage} → {@code TicketRepository#findSummaries}) and on
 * the single-deal read ({@code TicketService#get} → {@code TicketRepository#findById}), through the real
 * services against real Postgres.
 *
 * <p>Pinned: {@code {id, number, docStatus, recipientType}} of the NEWEST live DEAL_DIRECT row;
 * {@code null} when there is none; CANCELLED / SUPERSEDED / REJECTED rows and PRICING_REQUEST-origin
 * rows never count; list and detail agree.
 */
class TicketListLiveDirectQuotationIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    @Test
    void listRowAndDetail_carryTheLiveDraft_andADealWithNoneCarriesNull() {
        long withDraft = deal(salesActor);
        long without = deal(salesActor);
        DealQuotationDto draftQuotation = quotationService.create(withDraft, draft("DESIGNER"), salesActor);

        LiveDirectQuotationDto expected = new LiveDirectQuotationDto(draftQuotation.id(), draftQuotation.number(),
            QuotationStatus.DRAFT, "DESIGNER");
        assertThat(listRow(withDraft).liveDirectQuotation()).isEqualTo(expected);
        assertThat(listRow(without).liveDirectQuotation()).isNull();
        assertThat(ticketService.get(withDraft, salesActor).summary().liveDirectQuotation()).isEqualTo(expected);
        assertThat(ticketService.get(without, salesActor).summary().liveDirectQuotation()).isNull();
        assertThat(pagedRow(withDraft).liveDirectQuotation()).isEqualTo(expected);
    }

    @Test
    void pendingApprovalAndApproved_areCarriedWithTheirStatus() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("OWNER"), salesActor);
        quotationService.submit(created.id(), salesActor);
        assertThat(listRow(ticket).liveDirectQuotation().docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);

        quotationService.approve(created.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(ticketService.get(ticket, salesActor).summary().liveDirectQuotation())
            .isEqualTo(new LiveDirectQuotationDto(created.id(), created.number(), QuotationStatus.APPROVED, "OWNER"));
    }

    @Test
    void withTwoLiveRows_theNewestIsServed_thenTheSurvivorOnceTheParentIsSuperseded() {
        long ticket = deal(salesActor);
        DealQuotationDto parent = approved(ticket, salesActor, "OWNER");
        DealQuotationDto revision = quotationService.createRevision(parent.id(), salesActor);

        assertThat(listRow(ticket).liveDirectQuotation().id()).as("APPROVED parent + DRAFT revision → the revision")
            .isEqualTo(revision.id());

        quotationService.submit(revision.id(), salesActor);
        quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor);
        LiveDirectQuotationDto live = ticketService.get(ticket, salesActor).summary().liveDirectQuotation();
        assertThat(live.id()).isEqualTo(revision.id());
        assertThat(live.docStatus()).isEqualTo(QuotationStatus.APPROVED);
    }

    @Test
    void cancelledSupersededRejectedAndPricingRequestRows_areIgnored() {
        long cancelled = deal(salesActor);
        DealQuotationDto c = quotationService.create(cancelled, draft("OWNER"), salesActor);
        quotationService.cancel(c.id(), new CancelRequest(null), salesActor);

        long superseded = deal(salesActor);
        setDocStatus(quotationService.create(superseded, draft("OWNER"), salesActor).id(), "SUPERSEDED");

        long rejected = deal(salesActor);
        setDocStatus(quotationService.create(rejected, draft("OWNER"), salesActor).id(), "REJECTED");

        long prOrigin = deal(salesActor);
        long prRow = quotationService.create(prOrigin, draft("OWNER"), salesActor).id();
        jdbc.update("UPDATE sales.quotation SET origin = 'PRICING_REQUEST' WHERE quotation_id = :id",
            Map.of("id", prRow));

        for (long ticket : new long[] {cancelled, superseded, rejected, prOrigin}) {
            assertThat(listRow(ticket).liveDirectQuotation()).as("ticket %s", ticket).isNull();
            assertThat(ticketService.get(ticket, salesActor).summary().liveDirectQuotation()).isNull();
        }
    }

    /** A pre-slice-2 direct row carries recipient_type UNSPECIFIED — served raw, not invented. */
    @Test
    void aLegacyUnspecifiedRow_isServedWithItsRawRecipientType() {
        long ticket = deal(salesActor);
        long id = quotationService.create(ticket, draft("OWNER"), salesActor).id();
        jdbc.update("UPDATE sales.quotation SET recipient_type = 'UNSPECIFIED', recipient_label = NULL WHERE quotation_id = :id",
            Map.of("id", id));

        assertThat(listRow(ticket).liveDirectQuotation().recipientType()).isEqualTo("UNSPECIFIED");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private TicketSummaryDto listRow(long ticketId) {
        List<TicketSummaryDto> rows = ticketService.list(null, salesActor);
        return rows.stream().filter(r -> r.id() == ticketId).findFirst().orElseThrow();
    }

    private TicketSummaryDto pagedRow(long ticketId) {
        return ticketService.listPage(null, salesActor, th.co.glr.hr.common.PageRequest.resolve(null, null))
            .items().stream().filter(r -> r.id() == ticketId).findFirst().orElseThrow();
    }
}
