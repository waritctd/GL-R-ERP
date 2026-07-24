package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Stage L (release runbook) — L1, top priority. {@link CommissionService#list} (called by the
 * {@code GET /commissions} endpoint) has TWO layers of authorization, both proved here:
 * <ol>
 *   <li><b>Role gate</b> ({@code LIST_VIEWER_ROLES} = sales/sales_manager/ceo) — added 2026-07-24
 *       after this test surfaced that {@code list()} previously had <em>no</em> {@code requireRole}
 *       at all, so any authenticated user (incl. a plain employee/warehouse/qc) could read every
 *       rep's commission amounts. Owner decision: gate it to match the frontend's
 *       {@code canListCommissionRecords} exactly — NOT hr (payroll-ready feed) and NOT account
 *       (createFromDeal only).</li>
 *   <li><b>Row-scope</b> — the SQL predicate in {@link CommissionRepository#findRecords}:
 *       {@code WHERE (:salesRepId::bigint IS NULL OR cr.sales_rep_id = :salesRepId)}, where
 *       {@code list()} binds {@code salesRepId} only when {@code actor.role().equals("sales")}, so
 *       a sales rep sees only their own rows while sales_manager/ceo see the full feed.</li>
 * </ol>
 * A real money surface (commission amounts) that had zero prior test coverage — no existing test
 * called {@code commissionService.list}.
 *
 * <p>Per CLAUDE.md's "Permission changes must ship evidence": Mockito cannot prove this — a
 * mocked repository happily "passes" while the real {@code WHERE} clause does something else.
 * This class proves the row-scope against the real {@link CommissionService}, backed by the real
 * {@link CommissionRepository}, on real Postgres. {@link AuditService}, {@link
 * NotificationService}, {@link FileStorageService}, {@link CommissionAttachmentRepository}, and
 * {@link TicketRepository}/{@link AttachmentRepository} are mocked deliberately — {@code list()}
 * never touches them; only {@link CommissionRepository} and {@link EmployeeRepository}
 * participate in the decision or its persistence.
 *
 * <p>Every case is written the wrong way round: "can rep A see rep B's row" is the assertion
 * that matters, not "can rep A see their own".
 */
class CommissionListScopeIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate PAYROLL_MONTH = LocalDate.of(2026, 6, 1);
    private static final LocalDate OTHER_PAYROLL_MONTH = LocalDate.of(2026, 5, 1);

    private CommissionRepository commissions;
    private CommissionService commissionService;
    private EmployeeRepository employees;

    private long repAId;
    private long repBId;
    private UserPrincipal repAActor;
    private UserPrincipal repBActor;
    private UserPrincipal accountActor;
    private UserPrincipal hrActor;
    private UserPrincipal managerActor;
    private UserPrincipal ceoActor;
    private UserPrincipal importActor;
    private UserPrincipal employeeActor;
    private UserPrincipal warehouseActor;
    private UserPrincipal qcActor;

    @BeforeEach
    void wireRealCollaborators() {
        commissions = new CommissionRepository(jdbc);
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        commissionService = new CommissionService(
            commissions,
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class));

        repAId = createEmployee("พนักงานขาย เอ", "commission-list-a@glr.co.th", "SA", "แผนกขาย");
        repBId = createEmployee("พนักงานขาย บี", "commission-list-b@glr.co.th", "SA", "แผนกขาย");
        long accountId = createEmployee("ฝ่ายบัญชี ลิสต์", "commission-list-account@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        long managerId = createEmployee("ผู้จัดการขาย ลิสต์", "commission-list-manager@glr.co.th", "SA", "แผนกขาย");

        repAActor = principal(repAId, "sales");
        repBActor = principal(repBId, "sales");
        accountActor = principal(accountId, "account");
        hrActor = principal(999_888L, "hr");
        managerActor = principal(managerId, "sales_manager");
        ceoActor = principal(999_333L, "ceo");
        // Denied viewer roles — rejected at the role gate before any DB lookup, so synthetic ids.
        // hr reads commissions via the payroll-ready feed (PAYROLL_ROLES), account only does
        // createFromDeal — neither may call the list, per canListCommissionRecords.
        importActor = principal(999_777L, "import");
        employeeActor = principal(999_666L, "employee");
        warehouseActor = principal(999_555L, "warehouse");
        qcActor = principal(999_444L, "qc");
    }

    // ── L1: sales_rep_id row-scope (the load-bearing wrong-way-round case) ──────────────────

    /**
     * MUTATION-CHECK RECORD (actually run, not simulated): temporarily changed {@link
     * CommissionRepository#findRecords} from {@code WHERE (:salesRepId::bigint IS NULL OR
     * cr.sales_rep_id = :salesRepId) AND ...} to {@code WHERE (TRUE) AND ...} (neutralizing the
     * only authz this class exists to prove) and ran this class. Exactly two tests went red —
     * {@code listAsRepA_excludesRepBsRow} and {@code listAsRepB_excludesRepAsRow_symmetric} (both
     * failed with "expected not to contain [otherRep's id], but found it") — while {@code
     * listAsAllowedNonSalesRole_seesBothReps} and {@code
     * listAsRepA_differentPayrollMonth_isExcluded} stayed green (they do not depend on the
     * sales_rep_id predicate). Reverted the repository change; {@code git diff} against the
     * pre-mutation tree was empty afterwards.
     */
    @Test
    void listAsRepA_excludesRepBsRow() {
        long recordA = seedManualCommission(repAId, PAYROLL_MONTH);
        long recordB = seedManualCommission(repBId, PAYROLL_MONTH);

        List<Long> idsSeenByRepA = idsIn(commissionService.list(PAYROLL_MONTH, repAActor));

        assertThat(idsSeenByRepA).contains(recordA);
        assertThat(idsSeenByRepA).doesNotContain(recordB);
    }

    @Test
    void listAsRepB_excludesRepAsRow_symmetric() {
        long recordA = seedManualCommission(repAId, PAYROLL_MONTH);
        long recordB = seedManualCommission(repBId, PAYROLL_MONTH);

        List<Long> idsSeenByRepB = idsIn(commissionService.list(PAYROLL_MONTH, repBActor));

        assertThat(idsSeenByRepB).contains(recordB);
        assertThat(idsSeenByRepB).doesNotContain(recordA);
    }

    /**
     * The two allowed non-sales viewer roles — sales_manager and ceo — get the null
     * {@code salesRepId} filter and see EVERY rep's rows (they review/approve/oversee the whole
     * commission feed, so full visibility is intended). This is the "positive control" half of the
     * role gate: it must stay reachable. The denied half — import/plain-employee/warehouse/qc AND
     * hr/account (per {@code canListCommissionRecords}) → 403 — is
     * {@link #listAsUnprivilegedRole_isForbidden}.
     */
    @Test
    void listAsAllowedNonSalesRole_seesBothReps() {
        long recordA = seedManualCommission(repAId, PAYROLL_MONTH);
        long recordB = seedManualCommission(repBId, PAYROLL_MONTH);

        List<Long> idsSeenByManager = idsIn(commissionService.list(PAYROLL_MONTH, managerActor));
        assertThat(idsSeenByManager).contains(recordA, recordB);

        List<Long> idsSeenByCeo = idsIn(commissionService.list(PAYROLL_MONTH, ceoActor));
        assertThat(idsSeenByCeo).contains(recordA, recordB);
    }

    @Test
    void listAsRepA_differentPayrollMonth_isExcluded() {
        long recordThisMonth = seedManualCommission(repAId, PAYROLL_MONTH);
        long recordOtherMonth = seedManualCommission(repAId, OTHER_PAYROLL_MONTH);

        List<Long> idsSeenByRepA = idsIn(commissionService.list(PAYROLL_MONTH, repAActor));

        assertThat(idsSeenByRepA).contains(recordThisMonth);
        assertThat(idsSeenByRepA).doesNotContain(recordOtherMonth);
    }

    /**
     * The role gate ({@code LIST_VIEWER_ROLES} = sales/sales_manager/ceo) added 2026-07-24. Every
     * other role is rejected with 403 before any DB lookup and can never read a rep's money data
     * via {@code GET /api/commissions}: import/plain-employee/warehouse/qc have no business reason,
     * and — matching the frontend's {@code canListCommissionRecords} exactly — hr (reads via the
     * payroll-ready feed) and account (only does createFromDeal) are excluded from the list too.
     *
     * <p>MUTATION-CHECK RECORD (run + verified by Opus): remove the {@code
     * LIST_VIEWER_ROLES.contains(...)} guard at the top of {@link CommissionService#list} → this
     * test goes red (each denied role returns rows instead of throwing FORBIDDEN) while every other
     * test in this class stays green; then revert to an empty product-code diff.
     */
    @Test
    void listAsUnprivilegedRole_isForbidden() {
        seedManualCommission(repAId, PAYROLL_MONTH);
        List<UserPrincipal> denied =
            List.of(importActor, employeeActor, warehouseActor, qcActor, hrActor, accountActor);
        for (UserPrincipal actor : denied) {
            assertThatThrownBy(() -> commissionService.list(PAYROLL_MONTH, actor))
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    /**
     * Seeds one real commission row via the real {@link CommissionService#createManualCommission}
     * path (a {@code sales_manager} actor, {@link CommissionKind#ADJUSTMENT}) — avoids building a
     * full ticket/deal/invoice fixture chain this test doesn't otherwise need; {@code list()}
     * makes no distinction between SALE and manual-kind rows.
     */
    private long seedManualCommission(long salesRepId, LocalDate payrollMonth) {
        return commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("1000.00"),
            "commission-list-scope seed", payrollMonth, managerActor).id();
    }

    private static List<Long> idsIn(List<CommissionRecord> rows) {
        return rows.stream().map(CommissionRecord::id).toList();
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-" + employeeId + "@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
