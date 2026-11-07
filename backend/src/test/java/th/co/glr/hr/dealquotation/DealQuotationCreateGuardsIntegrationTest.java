package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest;
import th.co.glr.hr.ticket.DealRoute;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.DirectQuotationLocks;
import th.co.glr.hr.ticket.EntryChannel;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketEventKind;

/**
 * Quotation ↔ deal linking slice 2 — the two refusals on CREATING a direct quotation, through the
 * real {@link DealQuotationService#create} against real Postgres:
 *
 * <ul>
 *   <li><b>One pricing route per deal</b> (owner ruling 2026-09-30, the reverse of slice 1's
 *       {@link DirectQuotationLocks#requireNoLiveDirectQuotation}): a deal with ANY pricing request
 *       not CANCELLED / SUPERSEDED — a private DRAFT and a finished QUOTATION_ACCEPTED included —
 *       refuses a new direct quotation, 409 {@link DirectQuotationLocks#LIVE_PRICING_REQUEST_MESSAGE}.</li>
 *   <li><b>N6</b> (S2-B3): a deal already holding a live DEAL_DIRECT quotation (DRAFT /
 *       PENDING_APPROVAL / APPROVED) refuses a second one, 409 naming the NEWEST live one in the
 *       exception's details ({@code liveQuotationId}, {@code number}, {@code docStatus}) — rendered
 *       as top-level JSON fields by {@code ApiExceptionHandler} (pinned over HTTP by
 *       {@code DealQuotationSlice2HttpIntegrationTest}).</li>
 * </ul>
 *
 * <p>Precedence (mirrors {@code mockApi.dealQuotations.create}): authz 403 → recipient 400 →
 * live-pricing-request 409 → N6 409. Scope: ONLY {@code create}; a revision or reorder of an existing
 * direct quotation is not a new route choice and is pinned here as unaffected.
 *
 * <p>Written wrong-way-round: dead pricing requests, a pricing request on ANOTHER deal, and
 * dead/non-direct quotations must NOT block; every refusal asserts nothing was written.
 */
class DealQuotationCreateGuardsIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    /** Every status chk_pricing_request_status (as last re-declared, V141) allows — i.e. every
     * {@code PricingRequestStatus} constant — minus the two dead ends. */
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
                "ดีลนี้มีคำขอราคาที่ยังดำเนินการอยู่ — ใช้ใบเสนอราคาจากคำขอราคา หรือยกเลิกคำขอราคาก่อน");
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

    @Test
    void aLivePricingRequestOnAnotherDeal_doesNotBlock() {
        long other = deal(salesActor);
        insertPricingRequest(other, salesActor.id(), "DRAFT");
        long ticket = deal(salesActor);

        assertThat(quotationService.create(ticket, draft("OWNER"), salesActor).ticketId()).isEqualTo(ticket);
    }

    /** Owner ruling: cancelling the คำขอราคา lifts the lock (it is the rep's stated way out). */
    @Test
    void cancellingThePricingRequest_liftsTheLock() {
        long ticket = deal(salesActor);
        long pr = insertPricingRequest(ticket, salesActor.id(), "SUBMITTED");
        refusal(() -> quotationService.create(ticket, draft("OWNER"), salesActor), HttpStatus.CONFLICT);

        jdbc.update("UPDATE sales.pricing_request SET status = 'CANCELLED', cancelled_at = now() WHERE pricing_request_id = :id",
            Map.of("id", pr));

        assertThat(quotationService.create(ticket, draft("OWNER"), salesActor).docStatus())
            .isEqualTo(QuotationStatus.DRAFT);
    }

    /** Scope: a revision / reorder of an EXISTING direct quotation is not a new route choice. The two
     * can only coexist with a live pricing request through legacy data (slice 1 refuses the request
     * while the direct quotation is live), so the request is inserted behind the lock's back. */
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

    @Test
    void precedence_recipient400_beforeLivePricingRequest409_beforeN6() {
        long ticket = deal(salesActor);
        DealQuotationDto live = quotationService.create(ticket, draft("OWNER"), salesActor);
        insertPricingRequest(ticket, salesActor.id(), "DRAFT"); // coexisting only via legacy data

        ApiException missing = refusal(() -> quotationService.create(ticket, draft(null), salesActor),
            HttpStatus.BAD_REQUEST);
        assertThat(missing.getMessage()).isEqualTo("ต้องระบุผู้รับใบเสนอราคา");

        ApiException route = refusal(() -> quotationService.create(ticket, draft("OWNER"), salesActor),
            HttpStatus.CONFLICT);
        assertThat(route.getMessage())
            .as("the one-route refusal outranks N6, so it names the route, not %s", live.number())
            .isEqualTo(DirectQuotationLocks.LIVE_PRICING_REQUEST_MESSAGE);
        assertThat(quotationCount(ticket)).isEqualTo(1);
    }

    // ── N6 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void n6_aSecondCreate_onADealWithALiveDraft_is409_namingTheLiveOne_andWritesNothing() {
        long ticket = deal(salesActor);
        DealQuotationDto first = quotationService.create(ticket, draft("OWNER"), salesActor);

        ApiException e = refusal(() -> quotationService.create(ticket, draft("DESIGNER"), salesActor),
            HttpStatus.CONFLICT);

        assertThat(e.getMessage()).isEqualTo("ดีลนี้มีใบเสนอราคาตรงที่ใช้งานอยู่ (" + first.number()
            + ") — แก้ไขฉบับนั้น หรือสร้างฉบับแก้ไขแทนการออกเลขใหม่");
        assertThat(e.getDetails()).containsExactly(
            Map.entry("liveQuotationId", first.id()),
            Map.entry("number", first.number()),
            Map.entry("docStatus", QuotationStatus.DRAFT));
        assertThat(quotationCount(ticket)).isEqualTo(1);
    }

    @Test
    void n6_pendingApprovalAndApproved_areLiveToo() {
        long pendingDeal = deal(salesActor);
        DealQuotationDto pending = quotationService.create(pendingDeal, draft("OWNER"), salesActor);
        quotationService.submit(pending.id(), salesActor);
        ApiException e1 = refusal(() -> quotationService.create(pendingDeal, draft("OWNER"), salesActor),
            HttpStatus.CONFLICT);
        assertThat(e1.getDetails()).containsEntry("liveQuotationId", pending.id())
            .containsEntry("docStatus", QuotationStatus.PENDING_APPROVAL);

        long approvedDeal = deal(salesActor);
        DealQuotationDto approved = approved(approvedDeal, salesActor, "BUYER");
        ApiException e2 = refusal(() -> quotationService.create(approvedDeal, draft("BUYER"), salesActor),
            HttpStatus.CONFLICT);
        assertThat(e2.getDetails()).containsEntry("liveQuotationId", approved.id())
            .containsEntry("docStatus", QuotationStatus.APPROVED);
    }

    /** An APPROVED parent with its DRAFT revision: two live rows — N6 names the newest (the one being
     * worked on), the same row TicketSummaryDto.liveDirectQuotation serves. */
    @Test
    void n6_withTwoLiveRows_namesTheNewest() {
        long ticket = deal(salesActor);
        DealQuotationDto approved = approved(ticket, salesActor, "OWNER");
        DealQuotationDto revision = quotationService.createRevision(approved.id(), salesActor);

        ApiException e = refusal(() -> quotationService.create(ticket, draft("OWNER"), salesActor),
            HttpStatus.CONFLICT);

        assertThat(e.getDetails()).containsEntry("liveQuotationId", revision.id())
            .containsEntry("number", revision.number())
            .containsEntry("docStatus", QuotationStatus.DRAFT);
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
    void n6_approvedDesigner_allowsBuyer_andApprovedOwner_allowsBuyer() {
        long d = deal(salesActor);
        approved(d, salesActor, "DESIGNER");
        assertThat(quotationService.create(d, draft("BUYER"), salesActor).recipientType()).isEqualTo("BUYER");

        long o = deal(salesActor);
        approved(o, salesActor, "OWNER");
        assertThat(quotationService.create(o, draft("BUYER"), salesActor).recipientType()).isEqualTo("BUYER");
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

    @Test
    void n6_aDraftOrPendingEarlierQuotation_stillRefusesALaterParty() {
        long draftDeal = deal(salesActor);
        DealQuotationDto draftQt = quotationService.create(draftDeal, draft("DESIGNER"), salesActor);
        ApiException e1 = refusal(() -> quotationService.create(draftDeal, draft("OWNER"), salesActor), HttpStatus.CONFLICT);
        assertThat(e1.getDetails()).containsEntry("liveQuotationId", draftQt.id())
            .containsEntry("docStatus", QuotationStatus.DRAFT);

        long pendingDeal = deal(salesActor);
        DealQuotationDto pending = quotationService.create(pendingDeal, draft("DESIGNER"), salesActor);
        quotationService.submit(pending.id(), salesActor);
        ApiException e2 = refusal(() -> quotationService.create(pendingDeal, draft("OWNER"), salesActor), HttpStatus.CONFLICT);
        assertThat(e2.getDetails()).containsEntry("docStatus", QuotationStatus.PENDING_APPROVAL);
        assertThat(quotationCount(draftDeal)).isEqualTo(1);
        assertThat(quotationCount(pendingDeal)).isEqualTo(1);
    }

    /** EVERY live row must qualify: APPROVED DESIGNER + a DRAFT OWNER is two live rows, the DRAFT one
     * blocks even a BUYER create, and the 409 names the newest live one (the OWNER draft). */
    @Test
    void n6_twoLiveRows_oneNotApproved_refusesBuyer_namingTheNewest() {
        long ticket = deal(salesActor);
        approved(ticket, salesActor, "DESIGNER");
        DealQuotationDto ownerDraft = quotationService.create(ticket, draft("OWNER"), salesActor);

        ApiException e = refusal(() -> quotationService.create(ticket, draft("BUYER"), salesActor), HttpStatus.CONFLICT);

        assertThat(e.getDetails()).containsEntry("liveQuotationId", ownerDraft.id())
            .containsEntry("docStatus", QuotationStatus.DRAFT);
        assertThat(quotationCount(ticket)).isEqualTo(2);
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

    /** The 409 names the newest live row that BLOCKS, not merely the newest: here the APPROVED DESIGNER revision
     * (#3) is newest but would pass; the DRAFT OWNER (#2) is what refuses. */
    @Test
    void n6_namesTheNewestLiveRowThatBlocks_notJustTheNewest() {
        long ticket = deal(salesActor);
        DealQuotationDto designer = approved(ticket, salesActor, "DESIGNER");
        DealQuotationDto ownerDraft = quotationService.create(ticket, draft("OWNER"), salesActor);
        DealQuotationDto revision = quotationService.createRevision(designer.id(), salesActor);
        quotationService.submit(revision.id(), salesActor);
        quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(quotationService.get(ownerDraft.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.DRAFT);

        ApiException e = refusal(() -> quotationService.create(ticket, draft("BUYER"), salesActor), HttpStatus.CONFLICT);

        assertThat(e.getDetails()).containsEntry("liveQuotationId", ownerDraft.id())
            .containsEntry("docStatus", QuotationStatus.DRAFT);
    }

    @Test
    void n6_liftsOnceTheLiveQuotationIsCancelled() {
        long ticket = deal(salesActor);
        DealQuotationDto first = quotationService.create(ticket, draft("OWNER"), salesActor);
        refusal(() -> quotationService.create(ticket, draft("OWNER"), salesActor), HttpStatus.CONFLICT);

        quotationService.cancel(first.id(), new CancelRequest(null), salesActor);

        DealQuotationDto second = quotationService.create(ticket, draft("OWNER"), salesActor);
        assertThat(second.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(second.number()).isNotEqualTo(first.number());
    }

    @Test
    void n6_doesNotCountSupersededRejectedOrNonDirectRows() {
        long superseded = deal(salesActor);
        setDocStatus(quotationService.create(superseded, draft("OWNER"), salesActor).id(), "SUPERSEDED");
        assertThat(quotationService.create(superseded, draft("OWNER"), salesActor).docStatus())
            .as("the only prior quotation is SUPERSEDED").isEqualTo(QuotationStatus.DRAFT);

        long rejected = deal(salesActor);
        setDocStatus(quotationService.create(rejected, draft("OWNER"), salesActor).id(), "REJECTED");
        assertThat(quotationService.create(rejected, draft("OWNER"), salesActor).docStatus()).isEqualTo("DRAFT");

        // A PRICING_REQUEST-origin DRAFT is not a direct quotation (its request is cancelled here, so
        // the one-route rule is out of the way and only N6 is under test).
        long prOrigin = deal(salesActor);
        long pr = insertPricingRequest(prOrigin, salesActor.id(), "CANCELLED");
        long prRow = quotationService.create(prOrigin, draft("OWNER"), salesActor).id();
        jdbc.update("UPDATE sales.quotation SET origin = 'PRICING_REQUEST', pricing_request_id = :pr WHERE quotation_id = :id",
            Map.of("pr", pr, "id", prRow));
        assertThat(quotationService.create(prOrigin, draft("OWNER"), salesActor).docStatus()).isEqualTo("DRAFT");
    }

    /** Approving the revision supersedes the parent: the deal then holds exactly one live row (the
     * revision) and N6 still refuses — naming the revision, never the superseded parent. */
    @Test
    void n6_afterARevisionIsApproved_namesTheRevision() {
        long ticket = deal(salesActor);
        DealQuotationDto parent = approved(ticket, salesActor, "OWNER");
        DealQuotationDto revision = quotationService.createRevision(parent.id(), salesActor);
        quotationService.submit(revision.id(), salesActor);
        quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(quotationService.get(parent.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);

        ApiException e = refusal(() -> quotationService.create(ticket, draft("OWNER"), salesActor),
            HttpStatus.CONFLICT);
        assertThat(e.getDetails()).containsEntry("liveQuotationId", revision.id())
            .containsEntry("docStatus", QuotationStatus.APPROVED);
    }

    // ── route (owner ruling 2026-11-07): a direct quotation may not be addressed to a party OFF the deal's
    //    route. DealRoute decides (DESIGNER_LED all; OWNER_DIRECT skips the designer; BUYER_DIRECT skips designer
    //    and owner; UNSPECIFIED / unknown has no route => allowed). BUYER -> QUOTE_BUYER is on EVERY route. ────

    private static final String OWNER_DIRECT_DESIGNER_SENTENCE =
        "ดีลนี้เป็นเจ้าของติดต่อโดยตรง — ขั้นที่ 4 ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน";

    private void assertRouteRefused(long ticket, String recipient, String channel, String stage) {
        ApiException e = refusal(() -> quotationService.create(ticket, draft(recipient), salesActor), HttpStatus.CONFLICT);
        assertThat(e.getMessage()).as("%s on %s", recipient, channel).isEqualTo(DealRoute.refusalMessage(channel, stage));
        assertThat(e.getDetails()).as("the route refusal carries no structured body").isEmpty();
        assertThat(quotationCount(ticket)).as("nothing written").isZero();
        assertThat(summary(ticket).salesStage()).as("stage unchanged").isEqualTo(DealStage.LEAD_APPROACH);
        assertThat(eventCount(ticket, TicketEventKind.STAGE_CHANGED)).isZero();
    }

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

    @Test
    void route_ownerDirect_allowsTheOwnerAndTheBuyer() {
        long owner = deal(salesActor, EntryChannel.OWNER_DIRECT);
        assertThat(quotationService.create(owner, draft("OWNER"), salesActor).recipientType()).isEqualTo("OWNER");
        assertThat(summary(owner).salesStage()).isEqualTo(DealStage.QUOTE_OWNER);

        long buyer = deal(salesActor, EntryChannel.OWNER_DIRECT);
        assertThat(quotationService.create(buyer, draft("BUYER"), salesActor).recipientType()).isEqualTo("BUYER");
    }

    @Test
    void route_buyerDirect_refusesTheDesignerAndTheOwner_butNotTheBuyer() {
        assertRouteRefused(deal(salesActor, EntryChannel.BUYER_DIRECT), "DESIGNER", EntryChannel.BUYER_DIRECT,
            DealStage.QUOTE_DESIGN_SIDE);
        assertRouteRefused(deal(salesActor, EntryChannel.BUYER_DIRECT), "OWNER", EntryChannel.BUYER_DIRECT,
            DealStage.QUOTE_OWNER);
        assertThat(DealRoute.refusalMessage(EntryChannel.BUYER_DIRECT, DealStage.QUOTE_OWNER))
            .isEqualTo("ดีลนี้เป็นผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง — ขั้นที่ 5 ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน");

        long buyer = deal(salesActor, EntryChannel.BUYER_DIRECT);
        assertThat(quotationService.create(buyer, draft("BUYER"), salesActor).recipientType()).isEqualTo("BUYER");
        assertThat(summary(buyer).salesStage()).isEqualTo(DealStage.QUOTE_BUYER);
    }

    @Test
    void route_designerLed_allowsAllThreeParties() {
        for (String recipient : new String[] {"DESIGNER", "OWNER", "BUYER"}) {
            long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
            assertThat(quotationService.create(ticket, draft(recipient), salesActor).recipientType()).isEqualTo(recipient);
        }
    }

    /** A legacy deal (no stated channel) has NO route: gating it would brick it with no remedy (GLA-156). */
    @Test
    void route_unspecifiedChannel_allowsAllThreeParties() {
        for (String recipient : new String[] {"DESIGNER", "OWNER", "BUYER"}) {
            long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
            tickets.updateEntryChannel(ticket, EntryChannel.UNSPECIFIED);
            assertThat(quotationService.create(ticket, draft(recipient), salesActor).recipientType()).isEqualTo(recipient);
        }
    }

    /** Revision / reorder / approve / confirm-order are not a new route choice: a deal whose channel was changed
     * AFTER the quotation was addressed must not strand the quotation's own lifecycle. */
    @Test
    void route_revisionReorderApproveAndConfirmOrder_ofAnOffRouteAddressedQuotation_stillWork() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto designer = approved(ticket, salesActor, "DESIGNER");
        tickets.updateEntryChannel(ticket, EntryChannel.OWNER_DIRECT); // the designer is now off-route

        DealQuotationDto revision = quotationService.createRevision(designer.id(), salesActor);
        assertThat(revision.recipientType()).isEqualTo("DESIGNER");
        quotationService.submit(revision.id(), salesActor);
        DealQuotationDto approvedRevision = quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approvedRevision.docStatus()).isEqualTo(QuotationStatus.APPROVED);

        DealQuotationDto reorder = quotationService.createReorder(approvedRevision.id(), salesActor);
        assertThat(reorder.recipientType()).isEqualTo("DESIGNER");
        quotationService.cancel(reorder.id(), new CancelRequest(null), salesActor);

        quotationService.confirmOrderFromDirectQuotation(approvedRevision.id(), salesActor);
        assertThat(summary(ticket).salesStage()).isNotEqualTo(DealStage.LEAD_APPROACH);
    }
}
