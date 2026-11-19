package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest;
import th.co.glr.hr.ticket.DealRoute;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.DirectQuotationLocks;
import th.co.glr.hr.ticket.EntryChannel;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketEventKind;

/** Quotation ↔ deal linking slice 2. Scope: ONLY create; a revision or reorder of an existing direct quotation
 * is not a new route choice and is pinned here as unaffected. Precedence (mirrors
 * mockApi.dealQuotations.create): authz 403 → recipient 400 → live-pricing-request 409 → N6 409. */
class DealQuotationCreateGuardsIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    /** Every status chk_pricing_request_status (as last re-declared, V141) allows — i.e. every PricingRequestStatus
     * constant — minus the two dead ends. */
    private static final List<String> LIVE_PRICING_REQUEST_STATUSES = List.of(
        "DRAFT", "SUBMITTED", "IMPORT_REVIEWING", "AWAITING_FACTORY_RESPONSE", "READY_FOR_CEO_REVIEW",
        "CEO_REVIEWING", "APPROVED_FOR_QUOTATION", "QUOTATION_ISSUED", "QUOTATION_ACCEPTED");

    // ── one pricing route per deal ───────────────────────────────────────────────────────────────

    @Test
    void aLivePricingRequest_inEveryLiveStatus_refusesADirectQuotation_andWritesNothing() {
        for (String status : LIVE_PRICING_REQUEST_STATUSES) {
            long ticket = deal(salesActor);
            insertPricingRequest(ticket, salesActor.id(), status);

            ApiException e = refusal(() -> quotationService.create(ticket, draft("OWNER"), salesActor),
                HttpStatus.CONFLICT);

            assertThat(e.getMessage()).as(status).isEqualTo(DirectQuotationLocks.LIVE_PRICING_REQUEST_MESSAGE);
            assertThat(e.getMessage()).isEqualTo(
                "งานนี้มีคำขอราคาที่ยังดำเนินการอยู่ — ใช้ใบเสนอราคาจากคำขอราคา หรือยกเลิกคำขอราคาก่อน");
            assertThat(e.getDetails()).as("the one-route refusal carries no structured body").isEmpty();
            assertThat(quotationCount(ticket)).as(status).isZero();
        }
    }

    @Test
    void cancelledAndSupersededPricingRequests_doNotBlock() {
        long ticket = deal(salesActor);
        insertPricingRequest(ticket, salesActor.id(), "CANCELLED");
        insertPricingRequest(ticket, salesActor.id(), "SUPERSEDED");

        DealQuotationDto created = quotationService.create(ticket, draft("OWNER"), salesActor);

        assertThat(created.docStatus()).isEqualTo(QuotationStatus.DRAFT);
    }

    /** Scope: a revision / reorder of an EXISTING direct quotation is not a new route choice. The two can only
     * coexist with a live pricing request through legacy data (slice 1 refuses the request while the direct
     * quotation is live), so the request is inserted behind the lock's back. */
    @Test
    void revisionAndReorderOfAnExistingDirectQuotation_areNotRefusedByALivePricingRequest() {
        long ticket = deal(salesActor);
        DealQuotationDto approved = approved(ticket, salesActor, "OWNER");
        insertPricingRequest(ticket, salesActor.id(), "DRAFT");

        DealQuotationDto revision = quotationService.createRevision(approved.id(), salesActor);
        assertThat(revision.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        quotationService.cancel(revision.id(), new CancelRequest(null), salesActor);

        DealQuotationDto reorder = quotationService.createReorder(approved.id(), salesActor);
        assertThat(reorder.docStatus()).isEqualTo(QuotationStatus.DRAFT);
    }

    // ── precedence ───────────────────────────────────────────────────────────────────────────────

    // ── N6 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void n6_aSecondCreate_onADealWithALiveDraft_is409_namingTheLiveOne_andWritesNothing() {
        long ticket = deal(salesActor);
        DealQuotationDto first = quotationService.create(ticket, draft("OWNER"), salesActor);

        ApiException e = refusal(() -> quotationService.create(ticket, draft("DESIGNER"), salesActor),
            HttpStatus.CONFLICT);

        assertThat(e.getMessage()).isEqualTo("งานนี้มีใบเสนอราคาที่ใช้งานอยู่ (" + first.number()
            + ") — แก้ไขฉบับนั้น หรือสร้างฉบับแก้ไขแทนการออกเลขใหม่");
        assertThat(e.getDetails()).containsExactly(
            Map.entry("liveQuotationId", first.id()),
            Map.entry("number", first.number()),
            Map.entry("docStatus", QuotationStatus.DRAFT));
        assertThat(quotationCount(ticket)).isEqualTo(1);
    }

    // ── N6 relaxation: a LATER party may be quoted once every live direct quotation is APPROVED and
    //    addressed to a strictly EARLIER party on the route DESIGNER < OWNER < BUYER ────────────────

    @Test
    void n6_approvedDesigner_allowsOwner_andMovesTheDealToQuoteOwner() {
        long ticket = deal(salesActor);
        DealQuotationDto designer = approved(ticket, salesActor, "DESIGNER");

        DealQuotationDto owner = quotationService.create(ticket, draft("OWNER"), salesActor);

        assertThat(owner.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(owner.recipientType()).isEqualTo("OWNER");
        assertThat(quotationService.get(designer.id(), salesActor).docStatus()).as("the earlier one is untouched")
            .isEqualTo(QuotationStatus.APPROVED);
        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_OWNER);
    }

    @Test
    void n6_approvedDesigner_stillRefusesDesigner_andApprovedOwner_refusesDesignerAndOwner() {
        long d = deal(salesActor);
        DealQuotationDto designer = approved(d, salesActor, "DESIGNER");
        ApiException same = refusal(() -> quotationService.create(d, draft("DESIGNER"), salesActor), HttpStatus.CONFLICT);
        assertThat(same.getDetails()).containsEntry("liveQuotationId", designer.id());
        assertThat(quotationCount(d)).isEqualTo(1);

        long o = deal(salesActor);
        DealQuotationDto owner = approved(o, salesActor, "OWNER");
        ApiException earlier = refusal(() -> quotationService.create(o, draft("DESIGNER"), salesActor), HttpStatus.CONFLICT);
        assertThat(earlier.getMessage()).isEqualTo(DirectQuotationLocks.liveDirectQuotationMessage(owner.number()));
        refusal(() -> quotationService.create(o, draft("OWNER"), salesActor), HttpStatus.CONFLICT);
        assertThat(quotationCount(o)).isEqualTo(1);
    }

    /** Once the order is confirmed from the APPROVED quotation the route is closed: a later-party draft could
     * never be submitted, so the old 409 stands. */
    @Test
    void n6_afterTheOrderIsConfirmed_aLaterPartyIsStillRefused_andNothingIsWritten() {
        long ticket = deal(salesActor);
        DealQuotationDto designer = approved(ticket, salesActor, "DESIGNER");
        quotationService.confirmOrderFromDirectQuotation(designer.id(), salesActor);

        ApiException e = refusal(() -> quotationService.create(ticket, draft("OWNER"), salesActor), HttpStatus.CONFLICT);

        assertThat(e.getDetails()).containsEntry("liveQuotationId", designer.id())
            .containsEntry("docStatus", QuotationStatus.APPROVED);
        assertThat(quotationCount(ticket)).isEqualTo(1);
    }

    // ── route (owner ruling 2026-11-07): a direct quotation may not be addressed to a party OFF the deal's
    //    route. DealRoute decides (DESIGNER_LED all; OWNER_DIRECT skips the designer; BUYER_DIRECT skips designer
    //    and owner; UNSPECIFIED / unknown has no route => allowed). BUYER -> QUOTE_BUYER is on EVERY route. ────

    private static final String OWNER_DIRECT_DESIGNER_SENTENCE =
        "ช่องทางรับงานของงานนี้คือเจ้าของติดต่อโดยตรง — ขั้นที่ 4 ไม่อยู่ในเส้นทางนี้ — แก้ช่องทางรับงานก่อน";

    @Test
    void route_ownerDirect_refusesTheDesigner_withTheRouteSentence_andWritesNothing() {
        long ticket = deal(salesActor, EntryChannel.OWNER_DIRECT);

        ApiException e = refusal(() -> quotationService.create(ticket, draft("DESIGNER"), salesActor), HttpStatus.CONFLICT);

        assertThat(e.getMessage()).isEqualTo(OWNER_DIRECT_DESIGNER_SENTENCE);
        assertThat(e.getMessage()).isEqualTo(DealRoute.refusalMessage(EntryChannel.OWNER_DIRECT, DealStage.QUOTE_DESIGN_SIDE));
        assertThat(e.getDetails()).isEmpty();
        assertThat(quotationCount(ticket)).isZero();
        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.LEAD_APPROACH);
        assertThat(eventCount(ticket, TicketEventKind.STAGE_CHANGED)).isZero();
    }

}
