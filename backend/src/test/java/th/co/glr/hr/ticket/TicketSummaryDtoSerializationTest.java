package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code effectiveWinProbability} actually reaches the wire.
 *
 * <p><b>Why a serialization test and not a call to the method.</b> The defect in issue #738 was
 * never that the computation was wrong — {@link WinProbabilityDefaultsTest} already pins the
 * numbers, and {@code effectiveWinProbability()} always returned the right one. The defect was that
 * <em>nobody outside the JVM could see it</em>: Jackson serializes a record's COMPONENTS, so an
 * extra record method is silently dropped, and the frontend re-derived the value from a
 * hand-copied stage→% table instead. A test that calls the method directly passes both before and
 * after the fix and is therefore evidence of nothing; only asserting on the produced JSON can tell
 * the two states apart.
 *
 * <p><b>Mutation-check.</b> Delete the {@code @JsonProperty} from
 * {@link TicketSummaryDto#effectiveWinProbability()} and {@link #serializesTheComputedWinProbability}
 * fails on a missing node — which is exactly the production state this fix ends.
 *
 * <p>A plain {@code ObjectMapper} is deliberate: this asks what Jackson does with the record,
 * which is the whole question. Spring's configured mapper adds modules for time types and nothing
 * that changes property discovery, so booting a context would only slow the answer down.
 */
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

    /**
     * Every stage, wrong-way-round: the serialized value must equal the backend's own table for
     * each one, so a stage added to {@link DealStage#ORDER} without a defaults entry fails here
     * too rather than shipping a 0 the client would faithfully render.
     */
    @Test
    void serializedValueMatchesTheTableForEveryStage() throws Exception {
        for (String stage : DealStage.ORDER) {
            JsonNode json = mapper.readTree(mapper.writeValueAsString(summaryAt(stage)));
            assertThat(json.get("effectiveWinProbability").asInt())
                .as("serialized effectiveWinProbability for %s", stage)
                .isEqualTo(WinProbabilityDefaults.defaultFor(stage));
        }
    }

    /**
     * The override branch, which is the half a stage-keyed test cannot reach: a rep who has set an
     * explicit win% must have THAT number serialized, not the stage default. Uses the canonical
     * constructor via {@code withCustomerAndProject}'s sibling path — the short constructor always
     * leaves the override null.
     */
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
            base.buyerName(), base.stale(), base.commissionRecorded());

        JsonNode json = mapper.readTree(mapper.writeValueAsString(overridden));

        assertThat(json.get("effectiveWinProbability").asInt()).isEqualTo(85);
        assertThat(json.get("effectiveWinProbability").asInt())
            .as("the override must WIN — equal to the LEAD_APPROACH default would mean the "
                + "serialized property ignored winProbabilityOverride")
            .isNotEqualTo(WinProbabilityDefaults.defaultFor(DealStage.LEAD_APPROACH));
    }
}
