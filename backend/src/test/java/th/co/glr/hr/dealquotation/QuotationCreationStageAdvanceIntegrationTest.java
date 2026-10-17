package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.EntryChannel;
import th.co.glr.hr.ticket.TicketEventKind;

/**
 * D-2 (owner ruling 2026-10-16, {@code .design/quote-stage-gates}): <b>the quote stages are reached
 * by quoting someone, never by clicking.</b> Addressing a quotation to a recipient moves the deal
 * onto that recipient's stage at <b>creation</b> time, forward-only.
 *
 * <p>This is the other half of {@code TicketService.requireStageFactsHold}'s quote-stage gate: that
 * gate closes the manual door, and these tests are what prove the rep is not stranded behind it.
 * Both halves must hold together or the stage becomes unreachable.
 *
 * <p>Every advance runs through the real {@link DealQuotationService} against real Postgres, so the
 * stage read back is the one {@code TicketService.autoAdvanceStage} actually wrote — including its
 * three silent no-ops (not ACTIVE, not strictly forward, off the deal's {@code DealRoute}), each
 * pinned below so the behaviour is a decision on record rather than an accident.
 *
 * <p><b>The inherited {@code deal(creator)} default is OWNER_DIRECT</b>, whose route excludes
 * {@code QUOTE_DESIGN_SIDE} — so every test here names its channel explicitly rather than taking
 * the default, and the off-route case uses it deliberately.
 */
class QuotationCreationStageAdvanceIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    // ── the advance ──────────────────────────────────────────────────────────────────────────────

    @Test
    void create_addressedToTheDesigner_movesADesignerLedDealToQuoteDesignSide() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.LEAD_APPROACH);

        quotationService.create(ticket, draft("DESIGNER"), salesActor);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);
        assertThat(eventCount(ticket, TicketEventKind.STAGE_CHANGED)).isEqualTo(1);
    }

    @Test
    void create_addressedToTheOwner_movesTheDealToQuoteOwner() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);

        quotationService.create(ticket, draft("OWNER"), salesActor);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_OWNER);
    }

    @Test
    void create_addressedToTheBuyer_movesTheDealToQuoteBuyer() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);

        quotationService.create(ticket, draft("BUYER"), salesActor);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_BUYER);
    }

    /**
     * The second hook ({@code DealQuotationService#update}): re-addressing a DRAFT is the rep
     * correcting who the document is for, and the stage must follow the correction. Without this
     * the deal would advertise ขั้น 4 while its only quotation is addressed to the owner — and the
     * fact gate would then refuse the hand correction, stranding it.
     */
    @Test
    void update_changingADraftsRecipient_movesTheDealToTheNewRecipientsStage() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto created = quotationService.create(ticket, draft("DESIGNER"), salesActor);
        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);

        quotationService.update(created.id(), draft("OWNER"), salesActor);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_OWNER);
    }

    /** Forward-only, through the re-address path: ขั้น 5 → a DESIGNER quotation is ขั้น 4, behind it. */
    @Test
    void update_changingTheRecipientBackwards_leavesTheStageWhereItIs() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto created = quotationService.create(ticket, draft("OWNER"), salesActor);
        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_OWNER);

        quotationService.update(created.id(), draft("DESIGNER"), salesActor);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_OWNER);
    }

    /**
     * Goal 3 of the ruling — a second quotation to a party already quoted leaves the stage alone.
     * It needs no rule of its own: the same recipient maps to the same pipeline index, so the
     * forward-only advance is a no-op. The first document is cancelled because N6 allows only one
     * live direct quotation per deal; cancelling it is the only way to reach a genuine second
     * create, and the stage must survive that too.
     */
    @Test
    void create_aSecondQuotationToTheSameRecipient_leavesTheStageWhereItIs() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto first = quotationService.create(ticket, draft("OWNER"), salesActor);
        quotationService.cancel(first.id(), new DealQuotationRequests.CancelRequest(null), salesActor);
        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_OWNER);

        quotationService.create(ticket, draft("OWNER"), salesActor);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_OWNER);
        assertThat(eventCount(ticket, TicketEventKind.STAGE_CHANGED)).isEqualTo(1);
    }

    /** Forward-only again, and the case that matters most: a late quotation must not drag a deal
     * that has already reached NEGOTIATION back to a quote stage. */
    @Test
    void create_onADealAlreadyPastTheQuoteStages_doesNotRegressIt() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        tickets.updateSalesStage(ticket, DealStage.NEGOTIATION);

        quotationService.create(ticket, draft("DESIGNER"), salesActor);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.NEGOTIATION);
        assertThat(eventCount(ticket, TicketEventKind.STAGE_CHANGED)).isZero();
    }

    // ── the three silent no-ops ──────────────────────────────────────────────────────────────────

    /**
     * The edge case D-2 names explicitly. {@code OWNER_DIRECT}'s route skips
     * {@code QUOTE_DESIGN_SIDE} (there is no designer to quote), and {@code autoAdvanceStage}
     * returns silently on an off-route target rather than throwing — because throwing would fail
     * the quotation creation that triggered it. So the document is written and the deal stays put.
     *
     * <p>Pinned deliberately rather than left to chance: it is the one shape in which a deal can
     * hold a quotation and not be on its recipient's stage.
     */
    @Test
    void create_addressedToTheDesignerOnAnOwnerDirectDeal_movesNothing_andStillCreatesTheQuotation() {
        long ticket = deal(salesActor, EntryChannel.OWNER_DIRECT);

        DealQuotationDto created = quotationService.create(ticket, draft("DESIGNER"), salesActor);

        assertThat(created.recipientType()).isEqualTo("DESIGNER");
        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.LEAD_APPROACH);
        assertThat(eventCount(ticket, TicketEventKind.STAGE_CHANGED)).isZero();
    }

    /** {@code autoAdvanceStage}'s ACTIVE test. {@code create} itself has no lifecycle guard, so
     * this is genuinely reachable: the quotation is written, the stage is not touched. */
    @Test
    void create_onANonActiveDeal_movesNothing_andStillCreatesTheQuotation() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        tickets.updateLifecycle(ticket, DealLifecycle.ON_HOLD);

        DealQuotationDto created = quotationService.create(ticket, draft("DESIGNER"), salesActor);

        assertThat(quotationCount(ticket)).isEqualTo(1);
        assertThat(created.recipientType()).isEqualTo("DESIGNER");
        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.LEAD_APPROACH);
        assertThat(eventCount(ticket, TicketEventKind.STAGE_CHANGED)).isZero();
    }

    // ── "start", not only "move" ─────────────────────────────────────────────────────────────────

    /**
     * {@code /quotations/new} → สร้างดีลใหม่: a deal created alongside its quotation lands directly
     * on the recipient's stage instead of at ขั้น 1. No extra mechanism — a brand-new deal sits at
     * index 0, so the recipient's stage is forward of it and the same advance applies. Its channel
     * is {@code UNSPECIFIED}, which has no route, so nothing is off-route either.
     *
     * <p>This supersedes #1083's "promote lands at S10": the deal is visible at its quote stage
     * immediately, and ยืนยันคำสั่งซื้อ remains the separate action that takes it to S10.
     */
    @Test
    void create_onAFreshQuotationOnlyDeal_startsItOnTheRecipientsStage() {
        long ticket = quotationOnlyDeal();
        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.LEAD_APPROACH);
        assertThat(summary(ticket).entryChannel()).isEqualTo(EntryChannel.UNSPECIFIED);

        quotationService.create(ticket, draft("BUYER"), salesActor);

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_BUYER);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The ghost deal {@code /quotations/new} → สร้างดีลใหม่ mints: {@code quotationOnly = true} and
     * no channel at all. A channel of {@code UNSPECIFIED} cannot be CHOSEN on create (400), so the
     * ghost path sends none and the column's own V144 default supplies it — which is the state that
     * matters here, since {@code UNSPECIFIED} has no route and so nothing is off-route.
     */
    private long quotationOnlyDeal() {
        return ticketService.create(new CreateTicketRequest("ดีลจากใบเสนอราคา", "NORMAL", customer.name(),
            customer.id(), projectId, null, null, null, List.of(), LocalDate.now().plusDays(7), true),
            salesActor).summary().id();
    }
}
