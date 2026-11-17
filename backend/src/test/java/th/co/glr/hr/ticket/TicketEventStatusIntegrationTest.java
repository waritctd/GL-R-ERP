package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Regression for the deal-pipeline stage/lifecycle events (V50+). Mockito-based unit tests stub the
 * repository, so addEventInternal's UPDATE never runs there; this bug only reproduces against a real database
 * (it surfaced on hosted UAT). */
class TicketEventStatusIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private long actorId;

    @BeforeEach
    void wire() {
        tickets = new TicketRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        actorId = employees.create(new UpsertEmployeeRequest(
            null, null, "เทสเตอร์ ขาย", null, null, null, null, null, null, null,
            "seller@glr.co.th", null, "SALES", "SALES Division", "แผนกทดสอบ",
            null, null, null, "ACT", new BigDecimal("25000"), null, null, null, null, null, null, null));
    }

    private int seq;

    private long insertTicket(String status, String stage) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, status, priority, created_by, sales_stage)
            VALUES (:code, 'ทดสอบสถานะ', :status, 'HIGH', :actor, :stage)
            RETURNING ticket_id
            """,
            new MapSqlParameterSource()
                .addValue("code", "TST-" + (++seq))
                .addValue("status", status)
                .addValue("actor", actorId)
                .addValue("stage", stage),
            Long.class);
    }

    private String columnOf(long id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", id), String.class);
    }

    @Test
    void stageChangeEvent_doesNotWriteStageIntoTicketStatus() {
        long ticketId = insertTicket(TicketStatus.QUOTATION_ISSUED, DealStage.AWAITING_BUYER);
        tickets.updateSalesStage(ticketId, DealStage.QUOTE_BUYER);

        // The stage-advance event carries the stage code as its "to" label. It must neither violate chk_ticket_status
        // nor overwrite the ticket status with a stage code.
        assertThatCode(() -> tickets.addEvent(ticketId, actorId, "เทสเตอร์ ขาย",
            TicketEventKind.STAGE_CHANGED, DealStage.AWAITING_BUYER, DealStage.QUOTE_BUYER, "advance"))
            .doesNotThrowAnyException();

        assertThat(columnOf(ticketId, "status")).isEqualTo(TicketStatus.QUOTATION_ISSUED);
        assertThat(columnOf(ticketId, "sales_stage")).isEqualTo(DealStage.QUOTE_BUYER);
    }

    @Test
    void lifecycleEvent_doesNotWriteStageIntoTicketStatus() {
        long ticketId = insertTicket(TicketStatus.QUOTATION_ISSUED, DealStage.NEGOTIATION);

        assertThatCode(() -> tickets.addEvent(ticketId, actorId, "เทสเตอร์ ขาย",
            TicketEventKind.ON_HOLD, DealStage.NEGOTIATION, DealStage.NEGOTIATION, "พักไว้"))
            .doesNotThrowAnyException();

        assertThat(columnOf(ticketId, "status")).isEqualTo(TicketStatus.QUOTATION_ISSUED);
    }

    /** The contract this test pins INVERTED with the status machine, so it is worth stating plainly. That is
     * exactly the conflation the machine removes: logging an event is now purely logging an event, and the status
     * is moved only by TicketRepository#transitionStatus. */
    @Test
    void genuineStatusTransitionEvent_noLongerWritesTicketStatus() {
        long ticketId = insertTicket(TicketStatus.QUOTATION_ISSUED, DealStage.DELIVERED);

        tickets.addEvent(ticketId, actorId, "เทสเตอร์ ขาย",
            TicketEventKind.CLOSED, TicketStatus.QUOTATION_ISSUED, TicketStatus.CLOSED, null);

        assertThat(columnOf(ticketId, "status")).isEqualTo(TicketStatus.QUOTATION_ISSUED);
    }

    /** …and the side effect that DOES still key off toStatus keeps working unchanged. */
    @Test
    void transitionThenEvent_togetherProduceTheSameRowAsTheOldSingleStatement() {
        long ticketId = insertTicket(TicketStatus.QUOTATION_ISSUED, DealStage.DELIVERED);
        assertThat(closedAt(ticketId)).isNull();

        assertThat(tickets.transitionStatus(ticketId, TicketStatus.QUOTATION_ISSUED, TicketStatus.CLOSED))
            .isEqualTo(1);
        tickets.addEvent(ticketId, actorId, "เทสเตอร์ ขาย",
            TicketEventKind.CLOSED, TicketStatus.QUOTATION_ISSUED, TicketStatus.CLOSED, null);

        assertThat(columnOf(ticketId, "status")).isEqualTo(TicketStatus.CLOSED);
        assertThat(closedAt(ticketId)).isNotNull();
    }

    private java.sql.Timestamp closedAt(long id) {
        return jdbc.queryForObject("SELECT closed_at FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", id), java.sql.Timestamp.class);
    }
}
