package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Slice 6.3: {@link TicketSummaryDto#expectedQuarter} and {@link TicketSummaryDto#receiptSource}. The decision half of
 * the import projection (the enforcement half — that import's list rows really come back without them — is
 * {@code SummaryDrawnDetailsIntegrationTest}, against Postgres): {@link TicketSummaryDto#withoutMoney()} withholds both,
 * and every other copy keeps both (a copy that silently dropped one would blank คาดปิด / the Express chip).
 */
class TicketSummaryDtoDrawnDetailsTest {

    private static TicketSummaryDto full() {
        return new TicketSummaryDto(
            10L, "PR-2026-0001", "PRICE_REQUEST", "Test deal", TicketStatus.QUOTATION_ISSUED, "NORMAL",
            1L, "Sales User", null, null, "Customer", null, null, "Project",
            null, null, null, Instant.now(), Instant.now(), null, 1, false, null, null,
            DealStage.ORDER_RECEIVED, null, null, Instant.now(),
            DealLifecycle.ACTIVE, TenderRequirement.UNKNOWN, DepositPolicy.REQUIRED, null,
            EntryChannel.DESIGNER_LED)
            .withExpectedQuarter("2026-Q4")
            .withReceiptSource("EXPRESS")
            .withEstimatedValue(new BigDecimal("5000"));
    }

    @Test
    void withoutMoney_withholdsTheReceiptSourceAndTheExpectedQuarter() {
        TicketSummaryDto source = full();
        assertThat(source.expectedQuarter()).as("fixture").isEqualTo("2026-Q4");
        assertThat(source.receiptSource()).as("fixture").isEqualTo("EXPRESS");

        TicketSummaryDto projected = source.withoutMoney();

        assertThat(projected.receiptSource()).isNull();
        assertThat(projected.expectedQuarter()).isNull();
        // unchanged by this slice: what the no-money view already showed it still shows
        assertThat(projected.customerName()).isEqualTo("Customer");
        assertThat(projected.estimatedValue()).isNull();
    }

    @Test
    void everyOtherCopyKeepsBothFields() {
        List<UnaryOperator<TicketSummaryDto>> copies = List.of(
            s -> s.withCustomerAndProject("Frozen", "Frozen project"),
            s -> s.withKeyedById(7L),
            s -> s.withMovement(true, false, LocalDate.of(2026, 11, 1)),
            s -> s.withEstimatedValue(new BigDecimal("1")),
            s -> s.withPauseReturnOn(LocalDate.of(2026, 12, 1)),
            TicketSummaryDto::withoutActivity);

        for (UnaryOperator<TicketSummaryDto> copy : copies) {
            TicketSummaryDto copied = copy.apply(full());
            assertThat(copied.expectedQuarter()).isEqualTo("2026-Q4");
            assertThat(copied.receiptSource()).isEqualTo("EXPRESS");
        }
    }

    @Test
    void bothFieldsReachTheWire() throws Exception {
        JsonNode json = new ObjectMapper().findAndRegisterModules().readTree(
            new ObjectMapper().findAndRegisterModules().writeValueAsString(full()));

        assertThat(json.path("expectedQuarter").asText()).isEqualTo("2026-Q4");
        assertThat(json.path("receiptSource").asText()).isEqualTo("EXPRESS");
    }
}
