package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.ticket.DealStage;
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
 * <p><b>The handover pin:</b> neither create nor update moves {@code sales_stage} or writes a
 * {@code STAGE_CHANGED} event — the recipient → stage rule is owned by the entry-channel/stage-route
 * work ({@code .design/deal-route-staging} §5 Flow 10), so this slice must not also own it.
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

    /** The handover pin: a BUYER quotation on a LEAD_APPROACH deal would be S8 under the rule this
     * slice does NOT own — the stage must not move, and no STAGE_CHANGED may be written. */
    @Test
    void create_doesNotMoveTheStage_andWritesNoStageChangedEvent() {
        long ticket = deal(salesActor, "BUYER_DIRECT");
        TicketSummaryDto before = summary(ticket);
        assertThat(before.salesStage()).isEqualTo(DealStage.LEAD_APPROACH);

        quotationService.create(ticket, draft("BUYER"), salesActor);

        TicketSummaryDto after = summary(ticket);
        assertThat(after.salesStage()).isEqualTo(DealStage.LEAD_APPROACH);
        assertThat(after.stageUpdatedAt()).isEqualTo(before.stageUpdatedAt());
        assertThat(eventCount(ticket, TicketEventKind.STAGE_CHANGED)).isZero();
    }

    /** A revision or reorder copies every header field verbatim — the recipient included. Before
     * slice 2 the copy hard-coded 'UNSPECIFIED' (harmless while every direct row was UNSPECIFIED);
     * now it would silently drop the rep's choice from the document the deal advertises as live.
     * (mockApi's mintDealQuotationRevision/Reorder structuredClone the source, recipient and all.) */
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

    @Test
    void update_onADraft_changesTheRecipient_andStillMovesNoStage() {
        long ticket = deal(salesActor, "DESIGNER_LED");
        DealQuotationDto created = quotationService.create(ticket, draft("DESIGNER"), salesActor);
        TicketSummaryDto before = summary(ticket);

        DealQuotationDto updated = quotationService.update(created.id(), draft("BUYER"), salesActor);

        assertThat(updated.recipientType()).isEqualTo("BUYER");
        assertThat(updated.recipientLabel()).isEqualTo(THAI.get("BUYER"));
        assertThat(recipientColumns(created.id())).containsEntry("recipient_type", "BUYER");
        TicketSummaryDto after = summary(ticket);
        assertThat(after.salesStage()).isEqualTo(before.salesStage());
        assertThat(after.stageUpdatedAt()).isEqualTo(before.stageUpdatedAt());
        assertThat(eventCount(ticket, TicketEventKind.STAGE_CHANGED)).isZero();
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

    /** A PRICING_REQUEST row's recipient belongs to its คำขอราคา — ANY value is refused, even a
     * well-formed one, and even the one already stored. (Omitting it is the pre-existing path, pinned
     * by PricingRequestQuotationIntegrationTest's own update tests, whose requests carry none.) */
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
}
