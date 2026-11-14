package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.ticket.DealRoute;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.EntryChannel;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Quotation ↔ deal linking slice 2, S2-B1 — {@code UpsertDealQuotationRequest.recipientType} on a
 * direct quotation, through the real {@link DealQuotationService} against real Postgres.
 *
 * <p>Contract pinned here: required on create ({@code DESIGNER | OWNER | BUYER} only — missing/blank
 * 400 "ต้องระบุผู้รับใบเสนอราคา", anything else incl. {@code UNSPECIFIED} 400 "ไม่รองรับผู้รับ
 * ใบเสนอราคา '…'"); persisted to {@code sales.quotation.recipient_type} with the Thai
 * {@code recipient_label}; on update omitted/null keeps the stored value, a value on a non-DRAFT row
 * is 409, a value on a PRICING_REQUEST-origin row is 409, an invalid value is 400.
 *
 * <p><b>Stage side effect (owner ruling 2026-10-16, {@code .design/quote-stage-gates} D-2).</b>
 * Create and update move the deal to the recipient's stage. The full behaviour (forward-only, the
 * three silent no-ops, "start" as well as "move") lives in
 * {@link QuotationCreationStageAdvanceIntegrationTest}; the two stage assertions here keep the
 * recipient slice's own tests honest about that side effect.
 *
 * <p>Written wrong-way-round: every refusal asserts nothing was written.
 */
class DealQuotationRecipientIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private static final Map<String, String> THAI = Map.of(
        "DESIGNER", "ผู้ออกแบบ", "OWNER", "เจ้าของโครงการ", "BUYER", "ผู้ซื้อ / ผู้รับเหมา");

    // ── create ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void create_persistsEachRecipient_withItsThaiLabel() {
        for (String recipient : new String[] {"DESIGNER", "OWNER", "BUYER"}) {
            long ticket = deal(salesActor);
            DealQuotationDto created = quotationService.create(ticket, draft(recipient), salesActor);

            assertThat(created.recipientType()).as(recipient).isEqualTo(recipient);
            assertThat(created.recipientLabel()).as(recipient).isEqualTo(THAI.get(recipient));
            assertThat(recipientColumns(created.id()))
                .containsEntry("recipient_type", recipient)
                .containsEntry("recipient_label", THAI.get(recipient));
            assertThat(created.origin()).isEqualTo("DEAL_DIRECT");
        }
    }

    @Test
    void create_withoutARecipient_is400_andWritesNothing() {
        long ticket = deal(salesActor);
        for (String missing : Arrays.asList(null, "", "   ")) {
            ApiException e = refusal(() -> quotationService.create(ticket, draft(missing), salesActor),
                HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).as("recipient=%s", missing).isEqualTo("ต้องระบุผู้รับใบเสนอราคา");
        }
        assertThat(quotationCount(ticket)).isZero();
    }

    @Test
    void create_withUnspecifiedOrAnUnknownRecipient_is400_andWritesNothing() {
        long ticket = deal(salesActor);
        for (String bad : new String[] {"UNSPECIFIED", "designer", "CEO"}) {
            ApiException e = refusal(() -> quotationService.create(ticket, draft(bad), salesActor),
                HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).isEqualTo("ไม่รองรับผู้รับใบเสนอราคา '" + bad + "'");
        }
        assertThat(quotationCount(ticket)).isZero();
    }

    /** Pre-existing authz still runs FIRST: a non-owner rep gets the 403 even with a malformed body. */
    @Test
    void create_byANonOwnerRep_is403_evenWithoutARecipient() {
        long ticket = deal(salesActor);
        assertForbidden(() -> quotationService.create(ticket, draft(null), otherSalesActor));
        assertForbidden(() -> quotationService.create(ticket, draft("OWNER"), otherSalesActor));
        assertThat(quotationCount(ticket)).isZero();
    }

    /** A BUYER quotation on a LEAD_APPROACH deal IS S8 under D-2, and S8 is on BUYER_DIRECT's
     * route — so the create moves the stage and writes the STAGE_CHANGED event. */
    @Test
    void create_movesTheStageToTheRecipientsStage_andWritesAStageChangedEvent() {
        long ticket = deal(salesActor, "BUYER_DIRECT");
        TicketSummaryDto before = summary(ticket);
        assertThat(before.salesStage()).isEqualTo(DealStage.LEAD_APPROACH);

        quotationService.create(ticket, draft("BUYER"), salesActor);

        TicketSummaryDto after = summary(ticket);
        assertThat(after.salesStage()).isEqualTo(DealStage.QUOTE_BUYER);
        assertThat(after.stageUpdatedAt()).isNotEqualTo(before.stageUpdatedAt());
        assertThat(eventCount(ticket, TicketEventKind.STAGE_CHANGED)).isEqualTo(1);
    }

    /** A revision or reorder copies every header field verbatim — the recipient included. */
    @Test
    void revisionAndReorder_carryTheSourceRecipientAndLabel() {
        long ticket = deal(salesActor, "DESIGNER_LED");
        DealQuotationDto approved = approved(ticket, salesActor, "DESIGNER");

        DealQuotationDto revision = quotationService.createRevision(approved.id(), salesActor);
        assertThat(revision.recipientType()).isEqualTo("DESIGNER");
        assertThat(revision.recipientLabel()).isEqualTo(THAI.get("DESIGNER"));
        quotationService.cancel(revision.id(), new DealQuotationRequests.CancelRequest(null), salesActor);

        DealQuotationDto reorder = quotationService.createReorder(approved.id(), salesActor);
        assertThat(reorder.recipientType()).isEqualTo("DESIGNER");
        assertThat(reorder.recipientLabel()).isEqualTo(THAI.get("DESIGNER"));
        assertThat(recipientColumns(reorder.id())).containsEntry("recipient_type", "DESIGNER");
    }

    // ── update ───────────────────────────────────────────────────────────────────────────────────

    /** Re-addressing a DRAFT moves the stage with it (D-2's second hook): the create put this deal
     * on S4 for the designer, and the correction to BUYER takes it to S8. */
    @Test
    void update_onADraft_changesTheRecipient_andMovesTheStageWithIt() {
        long ticket = deal(salesActor, "DESIGNER_LED");
        DealQuotationDto created = quotationService.create(ticket, draft("DESIGNER"), salesActor);
        TicketSummaryDto before = summary(ticket);
        assertThat(before.salesStage()).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);

        DealQuotationDto updated = quotationService.update(created.id(), draft("BUYER"), salesActor);

        assertThat(updated.recipientType()).isEqualTo("BUYER");
        assertThat(updated.recipientLabel()).isEqualTo(THAI.get("BUYER"));
        assertThat(recipientColumns(created.id())).containsEntry("recipient_type", "BUYER");
        TicketSummaryDto after = summary(ticket);
        assertThat(after.salesStage()).isEqualTo(DealStage.QUOTE_BUYER);
        assertThat(eventCount(ticket, TicketEventKind.STAGE_CHANGED)).isEqualTo(2);
    }

    @Test
    void update_withTheRecipientOmitted_keepsTheStoredOne() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("OWNER"), salesActor);

        DealQuotationDto updated = quotationService.update(created.id(), draft(null), salesActor);

        assertThat(updated.recipientType()).isEqualTo("OWNER");
        assertThat(updated.recipientLabel()).isEqualTo(THAI.get("OWNER"));
    }

    @Test
    void update_withAnInvalidRecipient_is400_andChangesNothing() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("OWNER"), salesActor);

        for (String bad : new String[] {"UNSPECIFIED", "", "owner"}) {
            ApiException e = refusal(() -> quotationService.update(created.id(), draft(bad), salesActor),
                HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).isEqualTo("ไม่รองรับผู้รับใบเสนอราคา '" + bad + "'");
        }
        assertThat(recipientColumns(created.id())).containsEntry("recipient_type", "OWNER");
    }

    @Test
    void update_recipientOnAPendingApprovalQuotation_is409_andChangesNothing() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("OWNER"), salesActor);
        quotationService.submit(created.id(), salesActor);

        refusal(() -> quotationService.update(created.id(), draft("BUYER"), salesActor), HttpStatus.CONFLICT);

        assertThat(recipientColumns(created.id())).containsEntry("recipient_type", "OWNER");
        assertThat(quotationService.get(created.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
    }

    @Test
    void update_recipientOnAnApprovedQuotation_is409() {
        long ticket = deal(salesActor);
        DealQuotationDto approved = approved(ticket, salesActor, "OWNER");

        refusal(() -> quotationService.update(approved.id(), draft("DESIGNER"), salesActor), HttpStatus.CONFLICT);

        assertThat(recipientColumns(approved.id())).containsEntry("recipient_type", "OWNER");
    }

    /** A PRICING_REQUEST row's recipient belongs to its คำขอราคา — ANY value is refused, even a well-formed one,
     * and even the one already stored. (Omitting it is the pre-existing path, pinned by
     * PricingRequestQuotationIntegrationTest's own update tests, whose requests carry none.) */
    @Test
    void update_recipientOnAPricingRequestOriginQuotation_is409_evenForTheStoredValue() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft("OWNER"), salesActor);
        long pr = insertPricingRequest(ticket, salesActor.id(), "APPROVED_FOR_QUOTATION");
        jdbc.update("""
            UPDATE sales.quotation
               SET origin = 'PRICING_REQUEST', pricing_request_id = :pr, recipient_type = 'DESIGNER',
                   recipient_label = 'คุณสมชาย (ผู้ออกแบบ)'
             WHERE quotation_id = :id
            """, Map.of("pr", pr, "id", created.id()));

        for (String value : new String[] {"BUYER", "DESIGNER", "nonsense"}) {
            ApiException e = refusal(() -> quotationService.update(created.id(), draft(value), salesActor),
                HttpStatus.CONFLICT);
            assertThat(e.getMessage()).isEqualTo("ผู้รับของใบเสนอราคาจากคำขอราคามาจากคำขอราคา — แก้ที่คำขอราคาต้นทาง");
        }
        assertThat(recipientColumns(created.id()))
            .containsEntry("recipient_type", "DESIGNER")
            .containsEntry("recipient_label", "คุณสมชาย (ผู้ออกแบบ)");
    }

    // ── update: route (owner ruling 2026-11-07). Only a CHANGE of recipient to an OFF-route party is refused. ──

    @Test
    void update_changingTheRecipientToAnOffRouteParty_is409_withTheRouteSentence_andChangesNothing() {
        long owner = deal(salesActor, EntryChannel.OWNER_DIRECT);
        DealQuotationDto draft = quotationService.create(owner, draft("OWNER"), salesActor);
        int events = eventCount(owner, TicketEventKind.STAGE_CHANGED);

        ApiException e = refusal(() -> quotationService.update(draft.id(), draft("DESIGNER"), salesActor), HttpStatus.CONFLICT);

        assertThat(e.getMessage()).isEqualTo(
            "ดีลนี้เป็นเจ้าของติดต่อโดยตรง — ขั้นที่ 4 ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน");
        assertThat(e.getMessage()).isEqualTo(DealRoute.refusalMessage(EntryChannel.OWNER_DIRECT, DealStage.QUOTE_DESIGN_SIDE));
        assertThat(recipientColumns(draft.id())).containsEntry("recipient_type", "OWNER");
        assertThat(summary(owner).salesStage()).isEqualTo(DealStage.QUOTE_OWNER);
        assertThat(eventCount(owner, TicketEventKind.STAGE_CHANGED)).isEqualTo(events);

        long buyerDirect = deal(salesActor, EntryChannel.BUYER_DIRECT);
        DealQuotationDto buyerDraft = quotationService.create(buyerDirect, draft("BUYER"), salesActor);
        for (String off : new String[] {"DESIGNER", "OWNER"}) {
            ApiException b = refusal(() -> quotationService.update(buyerDraft.id(), draft(off), salesActor), HttpStatus.CONFLICT);
            assertThat(b.getMessage()).as(off).startsWith("ดีลนี้เป็นผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง — ขั้นที่ ");
            assertThat(b.getMessage()).as(off).endsWith(" ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน");
        }
        assertThat(recipientColumns(buyerDraft.id())).containsEntry("recipient_type", "BUYER");
    }

    @Test
    void update_changingTheRecipientToAnOnRouteParty_stillWorks() {
        long ticket = deal(salesActor, EntryChannel.OWNER_DIRECT);
        DealQuotationDto draft = quotationService.create(ticket, draft("OWNER"), salesActor);

        assertThat(quotationService.update(draft.id(), draft("BUYER"), salesActor).recipientType()).isEqualTo("BUYER");
        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_BUYER);
    }

    /** The editor re-sends the stored recipient on EVERY save; a draft addressed before the channel was corrected
     * must stay saveable, and may move to an on-route party — but not to another off-route one. */
    @Test
    void update_anUnchangedOffRouteRecipient_isNotRefused_onALegacyDraft() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto draft = quotationService.create(ticket, draft("DESIGNER"), salesActor);
        tickets.updateEntryChannel(ticket, EntryChannel.BUYER_DIRECT); // designer AND owner are now off-route

        assertThat(quotationService.update(draft.id(), draft("DESIGNER"), salesActor).recipientType()).isEqualTo("DESIGNER");
        assertThat(quotationService.update(draft.id(), draft(null), salesActor).recipientType()).isEqualTo("DESIGNER");
        refusal(() -> quotationService.update(draft.id(), draft("OWNER"), salesActor), HttpStatus.CONFLICT);
        assertThat(recipientColumns(draft.id())).containsEntry("recipient_type", "DESIGNER");
        assertThat(quotationService.update(draft.id(), draft("BUYER"), salesActor).recipientType()).isEqualTo("BUYER");
    }

    @Test
    void update_onAnUnspecifiedChannelDeal_changesToAnyParty() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto draft = quotationService.create(ticket, draft("BUYER"), salesActor);
        tickets.updateEntryChannel(ticket, EntryChannel.UNSPECIFIED);

        assertThat(quotationService.update(draft.id(), draft("DESIGNER"), salesActor).recipientType()).isEqualTo("DESIGNER");
        assertThat(quotationService.update(draft.id(), draft("OWNER"), salesActor).recipientType()).isEqualTo("OWNER");
    }

    /** A REVISION draft of an off-route-addressed quotation re-sends its parent's recipient on save: not refused. */
    @Test
    void update_aRevisionDraft_ofAnOffRouteAddressedParent_isNotStranded() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto parent = approved(ticket, salesActor, "DESIGNER");
        tickets.updateEntryChannel(ticket, EntryChannel.OWNER_DIRECT);
        DealQuotationDto revision = quotationService.createRevision(parent.id(), salesActor);

        assertThat(quotationService.update(revision.id(), draft("DESIGNER"), salesActor).recipientType()).isEqualTo("DESIGNER");
    }
}
