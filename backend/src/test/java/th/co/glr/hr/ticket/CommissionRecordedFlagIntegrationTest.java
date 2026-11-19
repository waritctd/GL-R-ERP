package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionStatus;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres proof that {@link TicketSummaryDto#commissionRecorded} turns true for a ticket with a live
 * SALE commission (issue #736). {@link TicketRepository#hasRecordedCommission} is a deliberate second copy of
 * {@code CommissionRepository#hasActiveCommissionForTicket}'s SQL, because {@code TicketRepository} is
 * hand-wired as {@code new TicketRepository(jdbc)} in many places. {@code @Transactional} is inert in this
 * suite (see {@link AbstractPostgresIntegrationTest}), so each fixture is its own ticket/commission pair.
 */
class CommissionRecordedFlagIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private CommissionRepository commissions;
    private long repId;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        commissions = new CommissionRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        repId = createEmployee(employees, "เซลล์ ทดสอบคอม", "commflag-rep@glr.co.th");
    }

    @Test
    void closedPaidTicketAfterALiveSaleCommission_isRecorded() {
        long ticketId = closedPaidDeal();
        insertSaleCommission(ticketId, CommissionStatus.SUBMITTED);

        assertThat(commissionRecordedOf(ticketId)).isTrue();
    }

    private boolean commissionRecordedOf(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary().commissionRecorded();
    }

    private long closedPaidDeal() {
        long ticketId = createDealWithOneItem();
        tickets.updateSalesStage(ticketId, DealStage.CLOSED_PAID);
        return ticketId;
    }

    private long createDealWithOneItem() {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบค่าคอม", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("100.00"), null, "PIECE", null, null, null, null, "THB")));
        return tickets.create(request, tickets.nextTicketCode(), repId, "เซลล์ ทดสอบคอม");
    }

    /** V102's chk_invoice_details_evidence_present requires every row to carry either a real attachment or a
     * closed-vocabulary evidence_provenance. */
    private long insertInvoice() {
        String invoiceNumber = "COMMFLAG-" + System.nanoTime();
        return jdbc.queryForObject("""
            INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount, evidence_provenance)
            VALUES (:invoiceNumber, CURRENT_DATE, 1000.00, 'PRE_ERP_PAPER_APPROVAL')
            RETURNING invoice_id
            """,
            new MapSqlParameterSource().addValue("invoiceNumber", invoiceNumber),
            Long.class);
    }

    /** A SALE-kind commission row, inserted directly (not via CommissionRepository) so a status unreachable through
     * the application (VOID) can still be fixtured, and so REJECTED/APPROVED don't need to be walked through the
     * real manager/CEO approval chain just to set up a read-only assertion. */
    private long insertSaleCommission(long ticketId, String status) {
        long invoiceId = insertInvoice();
        return jdbc.queryForObject("""
            INSERT INTO sales.commission_record
                (invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
                 payroll_month, actual_received, commissionable_base)
            VALUES
                (:invoiceId, :ticketId, :repId, :repId, 'SALE', :status,
                 date_trunc('month', CURRENT_DATE)::date, 1000.00, 1000.00)
            RETURNING commission_id
            """,
            new MapSqlParameterSource()
                .addValue("invoiceId", invoiceId)
                .addValue("ticketId", ticketId)
                .addValue("repId", repId)
                .addValue("status", status),
            Long.class);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }
}
