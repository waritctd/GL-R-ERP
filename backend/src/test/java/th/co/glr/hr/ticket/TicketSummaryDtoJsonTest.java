package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** {@code quotedRecipients} is a server-side fact for the stage gates, not part of the wire shape. */
class TicketSummaryDtoJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void quotedRecipients_isNotSerialised() throws Exception {
        TicketSummaryDto summary = new TicketSummaryDto(
            10L, "PR-2026-0001", "PRICE_REQUEST", "Test deal", TicketStatus.QUOTATION_ISSUED, "NORMAL",
            1L, "Sales User", null, null, "Customer", null, null, "Project",
            null, null, null, Instant.now(), Instant.now(), null, 1, false, null, null,
            DealStage.NEGOTIATION, null, null, Instant.now(),
            DealLifecycle.ACTIVE, TenderRequirement.UNKNOWN, DepositPolicy.REQUIRED, null,
            EntryChannel.DESIGNER_LED);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(summary));

        assertThat(json.has("liveDirectQuotation")).as("sibling fact still serialised").isTrue();
        assertThat(json.has("quotedRecipients")).as("server-side fact must not reach the wire").isFalse();
    }
}
