package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Proves effectiveWinProbability and reopenedAt/reopenCount actually reach the wire. The defect in issue #738
 * was never that the computation was wrong — WinProbabilityDefaultsTest already pins the numbers, and
 * effectiveWinProbability() always returned the right one. */
class TicketSummaryDtoSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    /** Builds a summary at {@code salesStage} with the given override, via the short constructor. */
    private static TicketSummaryDto summaryAt(String salesStage) {
        return new TicketSummaryDto(
            10L, "PR-2026-0001", "PRICE_REQUEST", "Test deal", TicketStatus.QUOTATION_ISSUED, "NORMAL",
            1L, "Sales User", null, null, "Customer", null, null, "Project",
            null, null, null, Instant.now(), Instant.now(), null, 1, false, null, null,
            salesStage, null, null, Instant.now(),
            DealLifecycle.ACTIVE, TenderRequirement.UNKNOWN, DepositPolicy.REQUIRED, null,
            EntryChannel.DESIGNER_LED);
    }

    @Test
    void serializesTheComputedWinProbability() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(summaryAt(DealStage.NEGOTIATION)));

        assertThat(json.has("effectiveWinProbability"))
            .as("TicketSummaryDto must SERIALIZE effectiveWinProbability — a record method Jackson "
                + "cannot see is what forced the frontend to keep its own copy of the table (#738)")
            .isTrue();
        assertThat(json.get("effectiveWinProbability").asInt())
            .isEqualTo(WinProbabilityDefaults.defaultFor(DealStage.NEGOTIATION));
    }

    /** Every stage, wrong-way-round: the serialized value must equal the backend's own table for each one, so a
     * stage added to DealStage#ORDER without a defaults entry fails here too rather than shipping a 0 the client
     * would faithfully render. */
    @Test
    void serializedValueMatchesTheTableForEveryStage() throws Exception {
        for (String stage : DealStage.ORDER) {
            JsonNode json = mapper.readTree(mapper.writeValueAsString(summaryAt(stage)));
            assertThat(json.get("effectiveWinProbability").asInt())
                .as("serialized effectiveWinProbability for %s", stage)
                .isEqualTo(WinProbabilityDefaults.defaultFor(stage));
        }
    }

    /** The override branch, which is the half a stage-keyed test cannot reach: a rep who has set an explicit win%
     * must have THAT number serialized, not the stage default. Uses the canonical constructor via
     * withCustomerAndProject's sibling path — the short constructor always leaves the override null. */
    @Test
    void serializesTheRepOverrideInPreferenceToTheStageDefault() throws Exception {
        TicketSummaryDto base = summaryAt(DealStage.LEAD_APPROACH);
        TicketSummaryDto overridden = new TicketSummaryDto(
            base.id(), base.code(), base.type(), base.title(), base.status(), base.priority(),
            base.createdById(), base.createdByName(), base.assignedToId(), base.assignedToName(),
            base.customerName(), base.customerId(), base.projectId(), base.projectName(),
            base.contactId(), base.contactName(), base.note(), base.createdAt(), base.updatedAt(),
            base.closedAt(), base.itemCount(), base.hasEdits(), base.paymentStatus(),
            base.fulfillmentStatus(), base.salesStage(), base.lostReason(), base.lostAt(),
            base.stageUpdatedAt(), base.lifecycle(), base.tenderRequirement(), base.depositPolicy(),
            base.depositPolicyReason(), base.entryChannel(), base.billingDate(), base.dueDate(),
            base.creditTermDays(), base.lastFollowUpAt(), base.nextFollowUpAt(), base.paymentStage(),
            base.amountPayable(), base.amountPaid(), base.amountOutstanding(), base.overdue(),
            base.closeConfirmedAt(), base.closeConfirmedByName(), base.invoiceOnFile(),
            base.cancelReason(), base.cancelledAt(), 85, base.designerName(), base.ownerName(),
            base.buyerName(), base.stale(), base.commissionRecorded(), base.reopenedAt(),
            base.reopenCount());

        JsonNode json = mapper.readTree(mapper.writeValueAsString(overridden));

        assertThat(json.get("effectiveWinProbability").asInt()).isEqualTo(85);
        assertThat(json.get("effectiveWinProbability").asInt())
            .as("the override must WIN — equal to the LEAD_APPROACH default would mean the "
                + "serialized property ignored winProbabilityOverride")
            .isNotEqualTo(WinProbabilityDefaults.defaultFor(DealStage.LEAD_APPROACH));
    }

    /** Builds a summary carrying an explicit reopen state, via the canonical constructor. */
    private static TicketSummaryDto summaryReopened(Instant reopenedAt, int reopenCount) {
        TicketSummaryDto base = summaryAt(DealStage.NEGOTIATION);
        return new TicketSummaryDto(
            base.id(), base.code(), base.type(), base.title(), base.status(), base.priority(),
            base.createdById(), base.createdByName(), base.assignedToId(), base.assignedToName(),
            base.customerName(), base.customerId(), base.projectId(), base.projectName(),
            base.contactId(), base.contactName(), base.note(), base.createdAt(), base.updatedAt(),
            base.closedAt(), base.itemCount(), base.hasEdits(), base.paymentStatus(),
            base.fulfillmentStatus(), base.salesStage(), base.lostReason(), base.lostAt(),
            base.stageUpdatedAt(), base.lifecycle(), base.tenderRequirement(), base.depositPolicy(),
            base.depositPolicyReason(), base.entryChannel(), base.billingDate(), base.dueDate(),
            base.creditTermDays(), base.lastFollowUpAt(), base.nextFollowUpAt(), base.paymentStage(),
            base.amountPayable(), base.amountPaid(), base.amountOutstanding(), base.overdue(),
            base.closeConfirmedAt(), base.closeConfirmedByName(), base.invoiceOnFile(),
            base.cancelReason(), base.cancelledAt(), base.winProbabilityOverride(),
            base.designerName(), base.ownerName(), base.buyerName(), base.stale(),
            base.commissionRecorded(), reopenedAt, reopenCount);
    }

    /** A reopened deal (issue #757): reopened_at/reopen_count are written on every reopen by
     * TicketRepository#clearDealLost (V58). reopenCount uses 3, not 1, so a hardcoded default or an off-by-one
     * increment cannot pass. */
    @Test
    void serializesReopenedAtAndReopenCountForAReopenedDeal() throws Exception {
        Instant reopenedAt = Instant.parse("2026-08-01T09:00:00Z");
        JsonNode json = mapper.readTree(mapper.writeValueAsString(summaryReopened(reopenedAt, 3)));

        assertThat(json.has("reopenedAt"))
            .as("TicketSummaryDto must SERIALIZE reopenedAt — clearDealLost (V58) writes it on "
                + "every reopen, but the DTO never declared it as a component before #757")
            .isTrue();
        assertThat(json.get("reopenedAt").isNull())
            .as("a REOPENED deal must serialize a non-null reopenedAt")
            .isFalse();
        // This class's plain ObjectMapper writes Instant as a numeric timestamp (epoch seconds —
        // WRITE_DATES_AS_TIMESTAMPS is the ObjectMapper default), not an ISO-8601 string, so the round-trip check
        // compares epoch seconds rather than assuming a text format.
        assertThat(json.get("reopenedAt").asLong())
            .as("the serialized reopenedAt must carry the actual value, not a placeholder")
            .isEqualTo(reopenedAt.getEpochSecond());

        assertThat(json.has("reopenCount")).as("reopenCount must also reach the wire").isTrue();
        assertThat(json.get("reopenCount").asInt())
            .as("reopenCount must round-trip its real value (3), not a hardcoded 0 or 1")
            .isEqualTo(3);
    }

    /** Wrong-way-round from the test above: a deal that was NEVER lost-then-reopened must serialize reopenedAt as
     * null and reopenCount as 0 — a client can only distinguish "reopened" from "never reopened" if BOTH states
     * are observable on the wire, not just the reopened one. */
    @Test
    void serializesNullReopenedAtAndZeroReopenCountForANeverReopenedDeal() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(summaryReopened(null, 0)));

        assertThat(json.has("reopenedAt")).as("the field must be present on the wire, just null")
            .isTrue();
        assertThat(json.get("reopenedAt").isNull())
            .as("a never-reopened deal must serialize reopenedAt as null, not omit it")
            .isTrue();
        assertThat(json.get("reopenCount").asInt())
            .as("a never-reopened deal must serialize reopenCount as 0")
            .isEqualTo(0);
    }
}
