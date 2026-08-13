package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.commission.CommissionKind;
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
 * TicketRepository#hasRecordedCommission} closes that gap, but it is a DELIBERATE second copy of
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

    // ═══ Behavioural cases, one shape each — see the bullet list on issue #736 ═══════════════

    @Test
    void closedPaidTicketWithNoCommission_isNotRecorded() {
        long ticketId = closedPaidDeal();

        assertThat(commissionRecordedOf(ticketId)).isFalse();
    }

    @Test
    void closedPaidTicketAfterALiveSaleCommission_isRecorded() {
        long ticketId = closedPaidDeal();
        insertSaleCommission(ticketId, CommissionStatus.SUBMITTED);

        assertThat(commissionRecordedOf(ticketId)).isTrue();
    }

    /** A rejected commission is not a recorded one — the deal genuinely still needs one. */
    @Test
    void rejectedCommission_isNotRecorded() {
        long ticketId = closedPaidDeal();
        insertSaleCommission(ticketId, CommissionStatus.REJECTED);

        assertThat(commissionRecordedOf(ticketId)).isFalse();
    }

    /**
     * VOID is unreachable through the application by design (see {@code CommissionStatus.VOID}'s
     * own Javadoc: "the only writes in the tree are fixtures that bypass the service entirely") —
     * this fixture is exactly that kind of bypass, inserted directly rather than via any service.
     */
    @Test
    void voidCommission_isNotRecorded() {
        long ticketId = closedPaidDeal();
        insertSaleCommission(ticketId, CommissionStatus.VOID);

        assertThat(commissionRecordedOf(ticketId)).isFalse();
    }

    /**
     * Catches a missing {@code WHERE source_ticket_id} predicate: a live SALE commission exists,
     * but against a DIFFERENT ticket, so the ticket under test must stay unrecorded while the
     * other one correctly flips true.
     */
    @Test
    void aLiveCommissionOnADifferentTicket_leavesThisTicketNotRecorded() {
        long ticketId = closedPaidDeal();
        long otherTicketId = closedPaidDeal();
        insertSaleCommission(otherTicketId, CommissionStatus.APPROVED);

        assertThat(commissionRecordedOf(ticketId)).isFalse();
        assertThat(commissionRecordedOf(otherTicketId)).isTrue();
    }

    /**
     * Catches a missing {@code kind = 'SALE'} predicate: an ADJUSTMENT commission genuinely
     * pointed at this ticket's id must not count as its (nonexistent) sale commission.
     */
    @Test
    void aNonSaleKindAgainstThisTicket_isNotRecorded() {
        long ticketId = closedPaidDeal();
        insertManualCommission(ticketId, CommissionKind.ADJUSTMENT, CommissionStatus.APPROVED);

        assertThat(commissionRecordedOf(ticketId)).isFalse();
    }

    /**
     * The flag must survive the LIST projection too, not just a single-ticket load — the account
     * Overview worklist ({@code accountActions.js}) is built from {@code findSummaries} rows, not
     * a per-ticket detail fetch.
     */
    @Test
    void theFlagSurvivesTheListProjection() {
        long ticketId = closedPaidDeal();
        insertSaleCommission(ticketId, CommissionStatus.SUBMITTED);

        List<TicketSummaryDto> rows = tickets.findSummaries(null, DealStage.CLOSED_PAID, repId, null, null);
        TicketSummaryDto row = rows.stream().filter(r -> r.id() == ticketId).findFirst()
            .orElseThrow(() -> new AssertionError("ticket " + ticketId + " missing from findSummaries rows"));

        assertThat(row.commissionRecorded())
            .as("commissionRecorded must reach LIST rows, not just single-ticket findById")
            .isTrue();
    }

    // ═══ THE ANTI-DRIFT ASSERTION ══════════════════════════════════════════════════════════

    /**
     * Loops a fresh ticket for every fixture shape above (plus a CLAWBACK-kind row, a second
     * non-SALE kind, and MANAGER_APPROVED, a third live status) and asserts {@code
     * TicketRepository}'s flag equals {@code CommissionRepository#hasActiveCommissionForTicket}
     * for every one of them. This — not the individual tests above — is what makes the duplicated
     * SQL safe: change one predicate without the other and this goes red.
     */
    @Test
    void neverDisagreesWithCommissionRepositoryAcrossEveryFixtureShape() {
        long noCommission = closedPaidDeal();

        long submitted = closedPaidDeal();
        insertSaleCommission(submitted, CommissionStatus.SUBMITTED);

        long managerApproved = closedPaidDeal();
        insertSaleCommission(managerApproved, CommissionStatus.MANAGER_APPROVED);

        long approved = closedPaidDeal();
        insertSaleCommission(approved, CommissionStatus.APPROVED);

        long rejected = closedPaidDeal();
        insertSaleCommission(rejected, CommissionStatus.REJECTED);

        long voided = closedPaidDeal();
        insertSaleCommission(voided, CommissionStatus.VOID);

        long clawbackOnly = closedPaidDeal();
        insertClawbackCommission(clawbackOnly, CommissionStatus.APPROVED);

        long adjustmentOnly = closedPaidDeal();
        insertManualCommission(adjustmentOnly, CommissionKind.ADJUSTMENT, CommissionStatus.APPROVED);

        long elsewhereTarget = closedPaidDeal();
        long elsewhereDecoy = closedPaidDeal();
        insertSaleCommission(elsewhereDecoy, CommissionStatus.APPROVED);

        for (long ticketId : List.of(noCommission, submitted, managerApproved, approved, rejected,
                voided, clawbackOnly, adjustmentOnly, elsewhereTarget, elsewhereDecoy)) {
            assertThat(commissionRecordedOf(ticketId))
                .as("TicketRepository#hasRecordedCommission must never disagree with "
                    + "CommissionRepository#hasActiveCommissionForTicket for ticket %d", ticketId)
                .isEqualTo(commissions.hasActiveCommissionForTicket(ticketId));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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

    /**
     * V102's {@code chk_invoice_details_evidence_present} requires every row to carry either a
     * real attachment or a closed-vocabulary {@code evidence_provenance}. {@code
     * PRE_ERP_PAPER_APPROVAL} is the value {@code DashboardRepositoryIntegrationTest} already uses
     * for exactly this shape (attachment-less, reporting/read-only test data) — following the same
     * precedent here rather than inventing a second one.
     */
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

    /**
     * A SALE-kind commission row, inserted directly (not via {@link CommissionRepository}) so a
     * status unreachable through the application ({@code VOID}) can still be fixtured, and so
     * {@code REJECTED}/{@code APPROVED} don't need to be walked through the real manager/CEO
     * approval chain just to set up a read-only assertion.
     */
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

    /**
     * A CLAWBACK-kind commission — also invoice-backed per {@code chk_commission_manual_fields},
     * so it doubles as a second "the kind predicate, not just the status one, is what excludes
     * this row" fixture alongside {@link #insertManualCommission}.
     */
    private long insertClawbackCommission(long ticketId, String status) {
        long invoiceId = insertInvoice();
        return jdbc.queryForObject("""
            INSERT INTO sales.commission_record
                (invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
                 payroll_month, actual_received, commissionable_base)
            VALUES
                (:invoiceId, :ticketId, :repId, :repId, 'CLAWBACK', :status,
                 date_trunc('month', CURRENT_DATE)::date, -1000.00, -1000.00)
            RETURNING commission_id
            """,
            new MapSqlParameterSource()
                .addValue("invoiceId", invoiceId)
                .addValue("ticketId", ticketId)
                .addValue("repId", repId)
                .addValue("status", status),
            Long.class);
    }

    /**
     * A manual-kind commission ({@code ADJUSTMENT}/{@code MANAGER}/{@code STOCK_BONUS}/{@code
     * INCENTIVE}) — no invoice, per {@code chk_commission_manual_fields}. {@code source_ticket_id}
     * is free-form at the schema level regardless of kind (nothing forces it NULL for a manual
     * row), so this can still be pointed at a real ticket to prove the {@code kind} predicate, not
     * just the {@code status} one, is what keeps a manual entry from counting.
     */
    private long insertManualCommission(long ticketId, String kind, String status) {
        return jdbc.queryForObject("""
            INSERT INTO sales.commission_record
                (invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
                 payroll_month, actual_received, commissionable_base, manual_amount, manual_reason)
            VALUES
                (NULL, :ticketId, :repId, :repId, :kind, :status,
                 date_trunc('month', CURRENT_DATE)::date, 0, 0, 100.00, 'ค่าคอมทดสอบ')
            RETURNING commission_id
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("repId", repId)
                .addValue("kind", kind)
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
