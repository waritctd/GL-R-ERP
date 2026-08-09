package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollRepository.DraftRowVersion;
import th.co.glr.hr.payroll.obligation.DeductionObligationRepository;
import th.co.glr.hr.payroll.obligation.DeductionObligationService;
import th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Payroll draft optimistic concurrency (issue #422 follow-up, PR #426 left this open): proves,
 * against real Postgres through the real {@link PayrollService} AND {@link PayrollRepository},
 * that the {@code If-Match} check on {@code PUT /api/payroll/input-draft} is real -- not decorative
 * -- and that {@link PayrollRepository#lockInputDraftMonth}'s advisory lock is what makes it real.
 *
 * <p>Mockito cannot reach any of this: a mocked repository happily returns whatever the test tells
 * it to regardless of what the SQL underneath would actually do, and no mock can reproduce two
 * genuinely concurrent Postgres transactions racing the same advisory lock. Every case here is
 * written wrong-way-round per CLAUDE.md's evidence bar: the assertion is "the stale/racing write is
 * REFUSED and the first writer's values survive," not "a well-behaved caller can save."
 *
 * <p>This harness wires {@link PayrollService} with {@code new} (see {@code
 * PayrollReprocessAndAttendanceDataFlowIntegrationTest} for the identical pattern), so {@code
 * @Transactional} is INERT without a Spring proxy -- every racing call in {@link
 * #exactlyOneOfTwoConcurrentSavesWinsAndTheLoserIs409ed()} is wrapped in an explicit {@link
 * TransactionTemplate} bound to the same DataSource {@code jdbc} uses, mirroring {@code
 * PricingDecisionIntegrationTest#approveConcurrently_exactlyOneWins_oneApprovalEventOneNotification},
 * so the advisory lock (transaction-scoped) actually spans the whole {@code saveInputDraft} call.
 */
class PayrollDraftOptimisticConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository payrollRepository;
    private PayrollService payrollService;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
        CommissionService commissionService = new CommissionService(
            new CommissionRepository(jdbc),
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        payrollService = new PayrollService(
            payrollRepository,
            new PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            mock(PayslipRenderer.class),
            new LeaveRepository(jdbc),
            new th.co.glr.hr.payroll.export.KBankPctExporter(),
            new th.co.glr.hr.payroll.export.Pnd1Exporter(),
            new th.co.glr.hr.payroll.export.SsoExporter(),
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new AppProperties(),
            new DeductionObligationService(
                new DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class),
                new PayrollDeductionShortfallRepository(jdbc)));
    }

    // ---- Case 1: stale token refused, first writer's values survive ------------------------

    @Test
    void secondSaveWithAStaleTokenIsRefused_andTheFirstWritersValuesSurvive() {
        long employeeId = seedEmployee("OCC-001", "แรก", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 8, 1);

        payrollService.saveInputDraft(request(month, employeeId, "111.00"), hr(), PayrollDraftETag.EMPTY);

        // Second caller never re-read after the first save -- still holds the ORIGINAL (now stale)
        // empty-set token, exactly like a second HR user who opened the page before the first save.
        assertThatThrownBy(() ->
            payrollService.saveInputDraft(request(month, employeeId, "222.00"), hr(), PayrollDraftETag.EMPTY))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // Assert the STORED ROW CONTENT, not just that an exception was thrown -- the refused
        // write must not have landed even partially.
        List<PayrollEmployeeInputRequest> drafts = payrollRepository.findInputDrafts(month);
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).specialPay1())
            .as("the first writer's value must survive a refused second write")
            .isEqualByComparingTo("111.00");
    }

    // ---- Case 2: current token succeeds, returned ETag differs from the one sent -----------

    @Test
    void saveWithTheCurrentTokenSucceeds_andReturnsADifferentETag() {
        long employeeId = seedEmployee("OCC-002", "ถูกต้อง", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 8, 1);

        PayrollInputDraftDtos.PayrollInputDraftResponse first =
            payrollService.saveInputDraft(request(month, employeeId, "111.00"), hr(), PayrollDraftETag.EMPTY);
        assertThat(first.etag()).isNotEqualTo(PayrollDraftETag.EMPTY);

        PayrollInputDraftDtos.PayrollInputDraftResponse second =
            payrollService.saveInputDraft(request(month, employeeId, "222.00"), hr(), first.etag());

        assertThat(second.etag())
            .as("a genuine write must change the month-level ETag")
            .isNotEqualTo(first.etag());
        assertThat(payrollRepository.findInputDrafts(month).get(0).specialPay1()).isEqualByComparingTo("222.00");
    }

    // ---- Case 3: missing If-Match refused, nothing written ----------------------------------

    @Test
    void missingIfMatchIsRefused_andNothingIsWritten() {
        long employeeId = seedEmployee("OCC-003", "ไม่มี", "โทเค็น");
        LocalDate month = LocalDate.of(2026, 8, 1);

        assertThatThrownBy(() -> payrollService.saveInputDraft(request(month, employeeId, "111.00"), hr(), null))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
        assertThatThrownBy(() -> payrollService.saveInputDraft(request(month, employeeId, "111.00"), hr(), "   "))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED));

        assertThat(payrollRepository.findInputDrafts(month)).isEmpty();
    }

    // ---- Case 4: genuine concurrency -- the reason a real DB is required --------------------

    /**
     * Two HR sessions that both loaded the SAME empty draft set (both hold {@link
     * PayrollDraftETag#EMPTY}) race to be the first to save. This is the case that actually proves
     * the lock/conditional-update holds: without {@link PayrollRepository#lockInputDraftMonth},
     * both transactions could read the version set BEFORE either writes, both see a match, and
     * both commit -- a lost update with no error to either caller. With the lock, whichever
     * transaction acquires it first proceeds through read-compare-write and commits (releasing the
     * lock); the second blocks until then, re-reads the NOW-CHANGED version set, and its ETag
     * compare genuinely fails.
     *
     * <p>Opus review finding F7: the mutation oracle for THIS test is probabilistic -- with the
     * lock removed, it only reds if both threads happen to read their version snapshot before
     * either commits (a genuine race between two 10-second-budgeted futures, not a deterministic
     * ordering). {@code @RepeatedTest(20)} runs the whole race 20 times (a fresh schema per {@code
     * AbstractPostgresIntegrationTest#resetSchema} thanks to {@code @BeforeEach} re-running each
     * repetition), so a lock regression that only manifests on, say, 1 in 5 attempts still gets
     * caught rather than surviving on a lucky single run.
     */
    @RepeatedTest(20)
    void exactlyOneOfTwoConcurrentSavesWinsAndTheLoserIs409ed() throws Exception {
        long employeeId = seedEmployee("OCC-004", "แข่งขัน", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 8, 1);

        var txManager = new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource());
        var txTemplate = new TransactionTemplate(txManager);

        Callable<BigDecimal> byFirstUser = () -> txTemplate.execute(status -> {
            payrollService.saveInputDraft(request(month, employeeId, "500.00"), hr(), PayrollDraftETag.EMPTY);
            return new BigDecimal("500.00");
        });
        Callable<BigDecimal> bySecondUser = () -> txTemplate.execute(status -> {
            payrollService.saveInputDraft(request(month, employeeId, "700.00"), hr(), PayrollDraftETag.EMPTY);
            return new BigDecimal("700.00");
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<BigDecimal> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try {
            Future<BigDecimal> f1 = executor.submit(byFirstUser);
            Future<BigDecimal> f2 = executor.submit(bySecondUser);
            for (Future<BigDecimal> f : List.of(f1, f2)) {
                try {
                    successes.add(f.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes).as("exactly one racing save must win").hasSize(1);
        assertThat(failures).as("exactly one racing save must be refused").hasSize(1);
        assertThat(failures.get(0)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // The DB must hold EXACTLY the winner's value -- not the loser's, not a merge of both.
        List<PayrollEmployeeInputRequest> drafts = payrollRepository.findInputDrafts(month);
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).specialPay1()).isEqualByComparingTo(successes.get(0));
    }

    // ---- Case 5: version increments per row across saves ------------------------------------

    @Test
    void versionIncrementsPerRowAcrossSuccessiveSaves() {
        long employeeId = seedEmployee("OCC-005", "เวอร์ชัน", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 8, 1);

        var r1 = payrollService.saveInputDraft(request(month, employeeId, "1.00"), hr(), PayrollDraftETag.EMPTY);
        assertThat(versionFor(month, employeeId)).isEqualTo(0);

        var r2 = payrollService.saveInputDraft(request(month, employeeId, "2.00"), hr(), r1.etag());
        assertThat(versionFor(month, employeeId)).isEqualTo(1);

        payrollService.saveInputDraft(request(month, employeeId, "3.00"), hr(), r2.etag());
        assertThat(versionFor(month, employeeId)).isEqualTo(2);
    }

    // ---- Case 6: process() clears drafts; empty set has the well-defined empty token --------

    @Test
    void processClearsDrafts_emptySetHasTheWellDefinedEmptyToken_andAPreProcessTokenIsThenRefused() {
        long employeeId = seedEmployee("OCC-006", "ประมวลผล", "ทดสอบ", new BigDecimal("30000.00"));
        LocalDate month = LocalDate.of(2026, 8, 1);

        var beforeProcess = payrollService.saveInputDraft(request(month, employeeId, "50.00"), hr(), PayrollDraftETag.EMPTY);
        assertThat(beforeProcess.etag()).isNotEqualTo(PayrollDraftETag.EMPTY);

        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());

        assertThat(payrollRepository.findInputDrafts(month))
            .as("process() must clear the month's draft rows")
            .isEmpty();
        var afterProcess = payrollService.getInputDraft(month, hr());
        assertThat(afterProcess.etag())
            .as("an emptied draft set must report the same well-defined empty token as a month that never had one")
            .isEqualTo(PayrollDraftETag.EMPTY);

        // The pre-process token is now stale against the (empty) post-process state.
        assertThatThrownBy(() ->
            payrollService.saveInputDraft(request(month, employeeId, "60.00"), hr(), beforeProcess.etag()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ---- Case 6b: ABA across a delete-and-reinsert (Opus review finding F1) -----------------

    /**
     * The exact scenario the F1 review comment walks through: HR-A's stale token from BEFORE a
     * process() must stay refused even after a SECOND round of drafting starts from scratch and
     * lands its rows back at version 0 -- the same {@code (employeeId, version)} pairs the
     * pre-process state had. Without {@code draftId} in the ETag material, this token would
     * incorrectly MATCH the post-reinsert state and silently overwrite HR-B's fresh work with
     * HR-A's stale one. Case 6 above stops at the empty state and never re-saves before replaying
     * the pre-process token -- this is the case that actually exercises the reinsert.
     */
    @Test
    void aPreProcessTokenIsRefusedEvenAfterANewDraftRoundLandsBackAtVersionZero() {
        long employeeId = seedEmployee("OCC-006B", "เอบีเอ", "ทดสอบ", new BigDecimal("30000.00"));
        LocalDate month = LocalDate.of(2026, 8, 1);

        // HR-A's round: save, keep the token, leave the tab open.
        var hrARound = payrollService.saveInputDraft(request(month, employeeId, "50.00"), hr(), PayrollDraftETag.EMPTY);

        // The month gets processed, clearing every draft row for it.
        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());

        // HR-B starts a brand-new drafting round post-process: a fresh INSERT for the same
        // employee/month, landing back at version 0 (the column DEFAULT) -- the exact
        // (employeeId=..., version=0) pair HR-A's round ALSO started at, but backed by a NEW
        // draft_id (the IDENTITY sequence never reuses a value).
        var hrBRound = payrollService.saveInputDraft(request(month, employeeId, "999.00"), hr(), PayrollDraftETag.EMPTY);

        // HR-A, unaware any of this happened, tries to save with the ORIGINAL pre-process token.
        assertThatThrownBy(() ->
            payrollService.saveInputDraft(request(month, employeeId, "12345.00"), hr(), hrARound.etag()))
            .as("a pre-process token must not silently match a post-reinsert state that happens to "
                + "share the same (employeeId, version) pair")
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // HR-B's values must survive untouched.
        List<PayrollEmployeeInputRequest> drafts = payrollRepository.findInputDrafts(month);
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).specialPay1())
            .as("HR-B's post-process save must survive HR-A's refused stale write")
            .isEqualByComparingTo("999.00");
        assertThat(hrBRound.etag()).isNotEqualTo(hrARound.etag());
    }

    // ---- Case 7: multi-employee set, only the middle row moves (Opus review finding F5) -----

    /**
     * Every other case in this file uses exactly one employee -- this is the only one where
     * per-row ordering and the ETag material's field-separator actually matter end to end (a
     * single-row set can never expose an ordering bug). Three employees, save once (all land at
     * version 0), then a second save touches ONLY the middle one. Asserts: the token changes, the
     * OTHER two rows' versions and figures are completely undisturbed, and the middle row's new
     * value is exactly what was submitted -- not merged, not applied to a neighbour.
     */
    @Test
    void threeEmployeeDraftSet_savingOnlyTheMiddleEmployeeLeavesTheOthersUntouched() {
        long first = seedEmployee("OCC-007A", "หนึ่ง", "ทดสอบ");
        long middle = seedEmployee("OCC-007B", "กลาง", "ทดสอบ");
        long last = seedEmployee("OCC-007C", "สาม", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 8, 1);

        var initial = payrollService.saveInputDraft(
            new ProcessPayrollRequest(month, List.of(
                inputWithSpecialPay1(first, new BigDecimal("10.00")),
                inputWithSpecialPay1(middle, new BigDecimal("20.00")),
                inputWithSpecialPay1(last, new BigDecimal("30.00")))),
            hr(), PayrollDraftETag.EMPTY);

        // Second save's inputs list contains ONLY the middle employee -- saveInputDrafts upserts
        // whatever is submitted, so `first`/`last` must be left exactly as they were.
        var afterMiddleChange = payrollService.saveInputDraft(
            new ProcessPayrollRequest(month, List.of(inputWithSpecialPay1(middle, new BigDecimal("99.00")))),
            hr(), initial.etag());

        assertThat(afterMiddleChange.etag())
            .as("changing one row out of three must still change the month token")
            .isNotEqualTo(initial.etag());

        List<DraftRowVersion> versions = payrollRepository.findInputDraftVersions(month);
        assertThat(versions).hasSize(3);
        assertThat(versionFor(versions, first)).as("untouched row's version must not move").isEqualTo(0);
        assertThat(versionFor(versions, middle)).as("the only saved row's version must move").isEqualTo(1);
        assertThat(versionFor(versions, last)).as("untouched row's version must not move").isEqualTo(0);

        List<PayrollEmployeeInputRequest> drafts = payrollRepository.findInputDrafts(month);
        assertThat(specialPay1For(drafts, first)).as("untouched row's figure must not move").isEqualByComparingTo("10.00");
        assertThat(specialPay1For(drafts, middle)).as("the saved row's figure").isEqualByComparingTo("99.00");
        assertThat(specialPay1For(drafts, last)).as("untouched row's figure must not move").isEqualByComparingTo("30.00");
    }

    // ---- Case 8: getInputDraft is ONE query, not two unsynchronized reads (Opus review F2) --

    /**
     * {@link PayrollService#getInputDraft} used to call {@link PayrollRepository#findInputDrafts}
     * and {@link PayrollRepository#findInputDraftVersions} as two separate, unsynchronized round
     * trips -- a writer committing in the gap could hand the caller old figures paired with a
     * NEW (legitimately current) token, an unsafe-direction race a mutated {@code If-Match}
     * compare cannot catch because the token genuinely does match. The fix (both pieces now come
     * from {@link PayrollRepository#findInputDraftRows}, one statement) is proven here
     * deterministically -- not via a timing-dependent race, which this specific hazard cannot be
     * forced into reliably -- by spying on the real {@code NamedParameterJdbcTemplate} and
     * asserting {@code getInputDraft} issues EXACTLY ONE query against {@code
     * hr.payroll_input_draft}. Mutating {@code PayrollService#getInputDraft} back to calling
     * {@code findInputDrafts}/{@code findInputDraftVersions} as two top-level calls (each of which
     * independently invokes {@code findInputDraftRows}, per that method's own delegation) makes
     * this count 2, and this test catches it.
     */
    @Test
    void getInputDraftIssuesExactlyOneQueryAgainstTheDraftTable_notTwoUnsynchronizedReads() {
        long employeeId = seedEmployee("OCC-008", "หนึ่งคิวรี", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 8, 1);
        payrollService.saveInputDraft(request(month, employeeId, "10.00"), hr(), PayrollDraftETag.EMPTY);

        NamedParameterJdbcTemplate spiedJdbc = spy(jdbc);
        PayrollRepository spiedRepository = new PayrollRepository(spiedJdbc);
        PayrollService serviceWithSpy = serviceWithRepository(spiedRepository);

        serviceWithSpy.getInputDraft(month, hr());

        verify(spiedJdbc, times(1)).query(
            contains("hr.payroll_input_draft"), any(Map.class), any(RowMapper.class));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private int versionFor(LocalDate month, long employeeId) {
        return versionFor(payrollRepository.findInputDraftVersions(month), employeeId);
    }

    private int versionFor(List<DraftRowVersion> versions, long employeeId) {
        return versions.stream()
            .filter(row -> row.employeeId() == employeeId)
            .map(DraftRowVersion::version)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no draft version row for employee " + employeeId));
    }

    private BigDecimal specialPay1For(List<PayrollEmployeeInputRequest> drafts, long employeeId) {
        return drafts.stream()
            .filter(row -> row.employeeId() == employeeId)
            .map(PayrollEmployeeInputRequest::specialPay1)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no draft row for employee " + employeeId));
    }

    /** Same wiring as {@link #wireRealCollaborators}, parameterized on the repository -- used by
     * the F2 regression test to swap in a Mockito-spied {@code NamedParameterJdbcTemplate} without
     * duplicating every other collaborator's construction. */
    private PayrollService serviceWithRepository(PayrollRepository repository) {
        CommissionService commissionService = new CommissionService(
            new CommissionRepository(jdbc),
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        return new PayrollService(
            repository,
            new PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            mock(PayslipRenderer.class),
            new LeaveRepository(jdbc),
            new th.co.glr.hr.payroll.export.KBankPctExporter(),
            new th.co.glr.hr.payroll.export.Pnd1Exporter(),
            new th.co.glr.hr.payroll.export.SsoExporter(),
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new AppProperties(),
            new DeductionObligationService(
                new DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class),
                new PayrollDeductionShortfallRepository(jdbc)));
    }

    private ProcessPayrollRequest request(LocalDate month, long employeeId, String specialPay1) {
        return new ProcessPayrollRequest(month, List.of(inputWithSpecialPay1(employeeId, new BigDecimal(specialPay1))));
    }

    private PayrollEmployeeInputRequest inputWithSpecialPay1(long employeeId, BigDecimal specialPay1) {
        return new PayrollEmployeeInputRequest(
            employeeId,
            specialPay1,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO, // nonTaxableIncome
            BigDecimal.ZERO, // unpaidLeaveDays
            BigDecimal.ZERO, // studentLoanDeduction
            BigDecimal.ZERO, // legalExecutionDeduction
            BigDecimal.ZERO, // otherPostTaxDeductions
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, // warningLetterDeduction
            BigDecimal.ZERO, // customerReturnDeduction
            BigDecimal.ZERO, // otherPretaxDeduction
            null); // withholdingTaxOverride
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh) {
        return seedEmployee(code, firstNameTh, lastNameTh, new BigDecimal("30000.00"));
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh, BigDecimal salary) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, :salary, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", salary),
            Long.class);
    }
}
