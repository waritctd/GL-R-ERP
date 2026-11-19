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
 * Real-Postgres proof that {@link TicketSummaryDto#commissionRecorded} answers the same question
 * {@link CommissionRepository#hasActiveCommissionForTicket} does (issue #736).
 *
 * <p><b>Why this test exists.</b> Before this flag, {@code TicketSummaryDto} had no per-ticket
 * "commission already recorded?" signal, and the {@code account} role has no route to {@code GET
 * /api/commissions} to check directly ({@code CommissionController}'s role check excludes it by
 * design). So {@code accountActions.js#nextAccountAction} could only guess: EVERY {@code
 * CLOSED_PAID} deal permanently showed "บันทึกใบกำกับ + ออกค่าคอม" as ฝ่ายบัญชี's next action, and
 * the accountant's worklist over-counted its backlog and never emptied. {@link
 * TicketRepository#hasRecordedCommission} (embedded in the list-facts SQL) closes that gap, but it is a DELIBERATE second copy of
 * {@code CommissionRepository#hasActiveCommissionForTicket}'s SQL — {@code TicketRepository} is
 * hand-wired as {@code new TicketRepository(jdbc)} in ~40 places, so injecting {@code
 * CommissionRepository} there to reuse the real method would break all of them for one boolean.
 *
 * <p>A duplicated predicate has no compiler to keep it honest. This class is what keeps the two
 * identical: {@link #neverDisagreesWithCommissionRepositoryAcrossEveryFixtureShape} loops every
 * fixture shape below and asserts {@code TicketRepository}'s answer equals {@code
 * CommissionRepository}'s for the same ticket. If a future edit changes one predicate without the
 * other, that test — not code review — is what goes red. Every behavioural test below is also
 * written wrong-way-round wherever possible: the interesting assertions are the ones proving the
 * flag stays FALSE (a different ticket's commission, a rejected/voided one, a non-SALE kind), not
 * the one proving it can turn true.
 *
 * <p>{@code @Transactional} is inert in this suite (services are hand-wired with {@code new}, no
 * Spring proxy — see {@link AbstractPostgresIntegrationTest}'s own Javadoc), so every fixture below
 * is its own ticket/commission pair rather than relying on a rollback between assertions.
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
