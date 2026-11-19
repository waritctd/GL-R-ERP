package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.EntryChannel;
import th.co.glr.hr.ticket.TicketService;

/**
 * V210 (review H2, round-2 N1): ACTIVE deals left behind a quote stage although they hold an UNSPECIFIED-recipient
 * quotation that reached the customer (APPROVED/ISSUED/SENT/ACCEPTED) move forward like a BUYER quotation would —
 * QUOTE_BUYER, else the nearest on-route stage before it (TicketService#stageForUnspecifiedRecipient) — with a STAGE_CHANGED event
 * stamped at the quotation's approval time. Executes the migration file verbatim against the migrated schema (the
 * V175 / V193 / V204 technique), so the fixture can be built first. Annotated itself: @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class ApprovedQuotationStageBackfillMigrationIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private static final String MIGRATION = "db/migration/V210__backfill_stage_from_approved_quotations.sql";
    private static final String NOTE = "ย้ายอัตโนมัติจากใบเสนอราคาที่อนุมัติแล้ว (ย้อนหลัง)";

    @Test
    void designerLedDealAtS1WithAnApprovedUnspecifiedQuotation_movesToQuoteBuyer() throws IOException {
        long ticket = stuckDeal(EntryChannel.DESIGNER_LED);
        Timestamp approvedAt = approvedAt(ticket);
        Timestamp createdAt = createdAt(ticket);

        runMigration();

        assertThat(summary(ticket).salesStage()).isEqualTo(DealStage.QUOTE_BUYER);
        Map<String, Object> event = jdbc.queryForMap("""
            SELECT from_status, to_status, message, created_at FROM sales.ticket_event
             WHERE ticket_id = :id AND kind = 'STAGE_CHANGED'
            """, Map.of("id", ticket));
        assertThat(event).containsEntry("from_status", DealStage.LEAD_APPROACH)
            .containsEntry("to_status", DealStage.QUOTE_BUYER).containsEntry("message", NOTE);
        // Stamped at the approval, not at deploy time: a backfill must not make 123 old deals look freshly touched.
        assertThat(event.get("created_at")).isEqualTo(approvedAt);
        assertThat(createdAt(ticket)).isEqualTo(createdAt);
    }

    /** The SQL target must agree with TicketService.stageForUnspecifiedRecipient (the live path) for every channel
     * the column can hold. entry_channel is NOT NULL (V51), so NULL cannot reach the SQL; DealRouteTest pins the Java
     * answer for null. Each deal starts at LEAD_APPROACH, so every channel must move to exactly the Java target. */
    @Test
    void backfillTargetMatchesTheLiveRuleForEveryChannel() throws IOException {
        Map<String, Long> byChannel = new java.util.LinkedHashMap<>();
        for (String channel : new java.util.TreeSet<>(EntryChannel.VALID)) {
            byChannel.put(channel, stuckDeal(channel));
        }

        runMigration();

        byChannel.forEach((channel, ticket) -> {
            assertThat(summary(ticket).salesStage()).as(channel)
                .isEqualTo(TicketService.stageForUnspecifiedRecipient(channel));
        });
    }

    // ── round-2 N4 ───────────────────────────────────────────────────────────

    // ── round-2 N3 ───────────────────────────────────────────────────────────

    @Test
    void rerunningIsANoOp() throws IOException {
        long ticket = stuckDeal(EntryChannel.DESIGNER_LED);

        runMigration();
        runMigration();

        assertThat(eventCount(ticket, "STAGE_CHANGED")).isEqualTo(1);
    }

    // ── rollback ─────────────────────────────────────────────────────────────

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** An ACTIVE deal at LEAD_APPROACH holding an APPROVED quotation recorded as UNSPECIFIED (the prod shape). */
    private long stuckDeal(String channel) {
        // A deal can no longer be CREATED with an UNSPECIFIED channel; older rows still carry it, so set it after.
        boolean unspecified = EntryChannel.UNSPECIFIED.equals(channel);
        long ticket = deal(salesActor, unspecified ? EntryChannel.DESIGNER_LED : channel);
        if (unspecified) {
            jdbc.update("UPDATE sales.ticket SET entry_channel = 'UNSPECIFIED' WHERE ticket_id = :id", Map.of("id", ticket));
        }
        String recipient = EntryChannel.BUYER_DIRECT.equals(channel) ? "BUYER"
            : EntryChannel.OWNER_DIRECT.equals(channel) ? "OWNER" : "DESIGNER";
        DealQuotationDto approved = approved(ticket, salesActor, recipient);
        jdbc.update("""
            UPDATE sales.quotation SET recipient_type = 'UNSPECIFIED', approved_at = now() - interval '40 days'
             WHERE quotation_id = :id
            """, Map.of("id", approved.id()));
        resetToLeadApproach(ticket);
        return ticket;
    }

    private void resetToLeadApproach(long ticket) {
        setStage(ticket, DealStage.LEAD_APPROACH);
        jdbc.update("DELETE FROM sales.ticket_event WHERE ticket_id = :id AND kind = 'STAGE_CHANGED'",
            Map.of("id", ticket));
    }

    private void setStage(long ticket, String stage) {
        jdbc.update("UPDATE sales.ticket SET sales_stage = :s WHERE ticket_id = :id", Map.of("s", stage, "id", ticket));
    }

    private Timestamp approvedAt(long ticket) {
        return jdbc.queryForObject("SELECT max(approved_at) FROM sales.quotation WHERE ticket_id = :id",
            Map.of("id", ticket), Timestamp.class);
    }

    private Timestamp createdAt(long ticket) {
        return jdbc.queryForObject("SELECT created_at FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticket), Timestamp.class);
    }

    private void runMigration() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as(MIGRATION).isNotNull();
            jdbc.getJdbcTemplate().execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
