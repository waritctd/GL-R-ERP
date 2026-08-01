package th.co.glr.hr.payroll.obligation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.payroll.PayrollDeductionKind;
import th.co.glr.hr.payroll.PayrollLineDto;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationCreateRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationDto;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationOverrideRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationProgressDto;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationUpdateRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.RemittanceLedgerRowDto;

/**
 * Business logic for the deduction-obligation record + remittance ledger (issue #373).
 *
 * <p><b>{@link #resolveMonthlyAmount}</b> is the payroll-engine hook: {@code PayrollService
 * #calculateLine} calls it in place of reading the HR-typed {@code studentLoanDeduction}/
 * {@code legalExecutionDeduction} field directly, for both {@code STUDENT_LOAN} and
 * {@code LEGAL_EXECUTION}. Read-only — safe to call from both {@code preview} and {@code process}.
 * When no obligation exists for the employee/kind it falls back to whatever HR typed this run,
 * exactly reproducing pre-existing behaviour for anyone not yet migrated to an obligation record.
 *
 * <p><b>{@link #recordRemittances}</b> is the only side-effecting hook, called by {@code
 * PayrollService#process} once (inside process's own transaction) after the period's lines are
 * persisted — it upserts one ledger row per (obligation, payrollMonth) using the ACTUAL persisted
 * deduction (after every other cap, e.g. ป.วิ.พ. ม.302, has already been applied), then re-derives
 * the obligation's ACTIVE/COMPLETED state from the recomputed cumulative total.
 *
 * <p>Every mutating HR method here double-checks its role, the same {@code requireRole} idiom
 * {@code PayrollService}/{@code TaxAllowanceDeclarationService} already use — the controller's
 * {@code @PreAuthorize} is not the only gate.
 */
@Service
public class DeductionObligationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Set<String> VIEW_ROLES = Set.of("hr", "ceo");
    private static final Set<String> EDIT_ROLES = Set.of("hr");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final DeductionObligationRepository repository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    private final PayrollDeductionShortfallRepository shortfallRepository;

    public DeductionObligationService(
        DeductionObligationRepository repository,
        EmployeeRepository employeeRepository,
        AuditService auditService,
        PayrollDeductionShortfallRepository shortfallRepository
    ) {
        this.repository = repository;
        this.employeeRepository = employeeRepository;
        this.auditService = auditService;
        this.shortfallRepository = shortfallRepository;
    }

    // ==================================================================================
    // Payroll-engine hooks — no actor/role check; these are internal service-to-service calls,
    // never exposed directly over HTTP.
    // ==================================================================================

    /**
     * The monthly figure {@code PayrollCalculator} should be asked to deduct for this employee/kind
     * this period.
     *
     * <ul>
     *   <li>No obligation on file for this employee/kind for THIS payroll month (either none exists,
     *       or one exists but its {@code start_date} is after this month — D2 fix, Opus review) -&gt;
     *       {@code manualFallback} (whatever HR typed this run, or zero) — unchanged legacy behaviour.
     *   <li>Obligation has no {@code instructedTotal} (nullable — open question to the owner, see
     *       V106's header) -&gt; the monthly instructed amount, uncapped, always.
     *   <li>Otherwise, {@code remaining} is computed FRESH every call as {@code instructedTotal}
     *       minus every OTHER period's already-recorded remittance (see {@link
     *       DeductionObligationRepository#sumRemittanceExcludingMonth}) — deliberately NEVER from
     *       the stored {@code status} column (D1 fix, Opus review). {@code status} is a snapshot
     *       {@link #recordRemittances} writes AFTER a run finishes; trusting it here made a re-run of
     *       the very month that caused completion see "COMPLETED" and stop BEFORE recomputing that
     *       month's own remainder, silently deleting the final instalment. If {@code remaining == 0}
     *       (every OTHER period alone already reaches the total) this is the genuine auto-stop --
     *       {@code 0} unless HR has recorded an explicit {@code overrideContinuePastTotal}, in which
     *       case the monthly figure resumes uncapped. Otherwise {@code min(monthlyInstructedAmount,
     *       remaining)} — the draw-down clamp: the final instalment before completion is exactly the
     *       remainder, never an overshoot, which structurally removes the overpay case.
     * </ul>
     */
    public BigDecimal resolveMonthlyAmount(
        long employeeId, DeductionObligationKind kind, LocalDate payrollMonth, BigDecimal manualFallback
    ) {
        Optional<DeductionObligationDto> maybeObligation = repository.findDrivingObligation(employeeId, kind, payrollMonth);
        if (maybeObligation.isEmpty()) {
            return money(manualFallback);
        }
        DeductionObligationDto obligation = maybeObligation.get();
        BigDecimal monthly = money(obligation.monthlyInstructedAmount());
        if (obligation.instructedTotal() == null) {
            return monthly;
        }
        BigDecimal alreadyRemittedOtherPeriods = repository.sumRemittanceExcludingMonth(obligation.id(), payrollMonth);
        BigDecimal remaining = money(obligation.instructedTotal().subtract(alreadyRemittedOtherPeriods)).max(BigDecimal.ZERO);
        if (remaining.signum() == 0) {
            return obligation.overrideContinuePastTotal() ? monthly : ZERO;
        }
        return monthly.min(remaining);
    }

    /**
     * Records what was ACTUALLY deducted this processed period against every employee's driving
     * obligation (if any), then re-derives ACTIVE/COMPLETED from the recomputed cumulative total.
     * Called once from {@code PayrollService#process}, inside that method's own {@code
     * @Transactional} — never from {@code preview}, which must have no side effects.
     */
    public void recordRemittances(LocalDate payrollMonth, long payrollPeriodId, List<PayrollLineDto> lines) {
        for (PayrollLineDto line : lines) {
            recordOne(line.employeeId(), DeductionObligationKind.STUDENT_LOAN, payrollMonth, payrollPeriodId, line.studentLoanDeduction());
            recordOne(line.employeeId(), DeductionObligationKind.LEGAL_EXECUTION, payrollMonth, payrollPeriodId, line.legalExecutionDeduction());
        }
    }

    /**
     * Issue #376's shortfall tracking, generalising #373's requested/actual/shortfall triple: records
     * a row in {@code hr.payroll_deduction_shortfall} for every employee whose LEGAL_EXECUTION
     * deduction came out lower than what was actually asked for this period -- pure record-keeping,
     * changes NO deducted amount (every figure here is read back from what {@code
     * PayrollCalculator}/{@code PayrollService#calculateLine} already computed and {@code
     * hr.payroll_line} already persisted).
     *
     * <p><b>D-376-1 fix (Opus review):</b> {@code requested} is read from {@link
     * PayrollLineDto#legalExecutionRequested()} -- the EXACT value {@code calculateLine} resolved and
     * fed to {@code PayrollCalculator} as {@code garnishmentRequested} for THIS line -- never
     * recomputed by calling {@link #resolveMonthlyAmount} again here. A second call is unsafe: this
     * method runs AFTER {@link #recordRemittances} in {@code PayrollService#process}, which may have
     * just flipped an obligation's ACTIVE/COMPLETED status; {@link
     * DeductionObligationRepository#findDrivingObligation}'s {@code ORDER BY (status='ACTIVE') DESC}
     * means a second resolve, for an employee with more than one LEGAL_EXECUTION obligation on file,
     * can pick a DIFFERENT obligation than the one {@code calculateLine} actually used earlier in the
     * same run -- fabricating a "requested" figure the court order never asked for. Reviewer's probe:
     * a completing obligation (monthly ฿5,000, override set) and a separate ACTIVE ฿100/฿100 total
     * obligation together produced a phantom ฿4,900 shortfall against a ฿100 court order that was
     * satisfied in full. Reading the value off the line instead of re-deriving it removes the bug
     * class entirely, in both directions (the same flip could also silently SUPPRESS a real
     * shortfall by resolving to a smaller or zero figure the second time).
     *
     * <p>Any difference between {@code legalExecutionRequested} and the persisted {@code
     * line.legalExecutionDeduction()} can therefore only be the ALREADY-enforced ป.วิ.พ. ม.302 cap
     * that ran inside the calculator (issue #376's explicit instruction: reuse that machinery, never
     * duplicate it) -- this method does not reimplement any part of that cap, it only reads its
     * output.
     *
     * <p>{@code STUDENT_LOAN} is deliberately excluded: no cap is enforced against it (see {@link
     * DeductionObligationKind#STUDENT_LOAN}'s own javadoc -- item (1) territory, {@code
     * DeductionCapGroup.NONE}), so requested always equals actual and there is nothing to record. A
     * period where the obligation's OWN remaining-balance clamp reduces the monthly figure (the final
     * instalment, or auto-stop at the instructed total) is NOT a shortfall either -- it is the
     * obligation being correctly satisfied, already fully visible via {@code
     * hr.deduction_obligation.status} and #373's remittance ledger.
     *
     * <p>Idempotent per (employee, payrollMonth, LEGAL_EXECUTION_GARNISHMENT) via {@link
     * PayrollDeductionShortfallRepository#upsert}/{@link PayrollDeductionShortfallRepository#delete}
     * -- a reprocess self-corrects instead of duplicating or stranding a stale row, matching {@link
     * #recordRemittances}'s own guarantee.
     */
    public void recordGarnishmentShortfalls(LocalDate payrollMonth, long payrollPeriodId, List<PayrollLineDto> lines) {
        for (PayrollLineDto line : lines) {
            BigDecimal requested = money(line.legalExecutionRequested());
            BigDecimal actual = money(line.legalExecutionDeduction());
            BigDecimal shortfall = money(requested.subtract(actual)).max(ZERO);
            if (shortfall.signum() == 0) {
                shortfallRepository.delete(line.employeeId(), payrollMonth, PayrollDeductionKind.LEGAL_EXECUTION_GARNISHMENT);
            } else {
                String reason = "ป.วิ.พ. ม.302 -- เพดานการอายัดเงินเดือนตามประเภท " + line.garnishmentType()
                    + " (PayrollGarnishmentType): ขอหักงวดนี้ ฿" + requested.toPlainString()
                    + " แต่กฎหมายอนุญาตให้หักได้จริงเพียง ฿" + actual.toPlainString()
                    + " ส่วนต่าง ฿" + shortfall.toPlainString()
                    + " ยังไม่ได้ส่งให้เจ้าหนี้ตามหมายบังคับคดี ต้องพิจารณาในงวดถัดไปหรือแจ้งเจ้าพนักงานบังคับคดี";
                shortfallRepository.upsert(line.employeeId(), payrollMonth,
                    PayrollDeductionKind.LEGAL_EXECUTION_GARNISHMENT, requested, actual, shortfall, reason, payrollPeriodId);
            }
        }
    }

    private void recordOne(
        long employeeId, DeductionObligationKind kind, LocalDate payrollMonth, long payrollPeriodId, BigDecimal actualAmount
    ) {
        Optional<DeductionObligationDto> maybeObligation = repository.findDrivingObligation(employeeId, kind, payrollMonth);
        if (maybeObligation.isEmpty()) {
            return; // No obligation record for this employee/kind (or not yet started) -- nothing to track.
        }
        DeductionObligationDto obligation = maybeObligation.get();
        BigDecimal amount = money(actualAmount);
        if (amount.signum() == 0) {
            // A period where nothing was actually deducted is not a remittance -- delete (never just
            // skip) so a reprocess that newly zeroes out a previously-nonzero period removes the
            // STALE row instead of leaving it stranded. Keeps countRemittedPeriods/findLedger honest
            // as "periods money actually moved" (issue #373's "N of M งวด" progress figure).
            repository.deleteRemittance(obligation.id(), payrollMonth);
        } else {
            repository.upsertRemittance(obligation.id(), payrollMonth, amount, payrollPeriodId);
        }
        if (obligation.instructedTotal() != null) {
            BigDecimal totalRemitted = repository.sumRemittanceTotal(obligation.id());
            boolean reachedTotal = totalRemitted.compareTo(obligation.instructedTotal()) >= 0;
            repository.applyCompletionTransition(obligation.id(), reachedTotal);
        }
    }

    // ==================================================================================
    // HR CRUD
    // ==================================================================================

    @Transactional
    public DeductionObligationDto create(DeductionObligationCreateRequest request, UserPrincipal actor) {
        requireRole(actor, EDIT_ROLES);
        if (!employeeRepository.exists(request.employeeId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบข้อมูลพนักงาน");
        }
        long id;
        try {
            id = repository.insert(request, actor.employeeId());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT,
                "พนักงานคนนี้มีรายการหักที่ยังดำเนินการอยู่ (ACTIVE) สำหรับประเภทนี้แล้ว "
                    + "กรุณาแก้ไขรายการเดิม หรือปิดรายการเดิมก่อนสร้างใหม่");
        }
        DeductionObligationDto created = repository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Obligation not found after insert"));
        auditService.record(actor, "CREATE_DEDUCTION_OBLIGATION", "deduction_obligation", id, null, created);
        return created;
    }

    @Transactional
    public DeductionObligationDto update(long obligationId, DeductionObligationUpdateRequest request, UserPrincipal actor) {
        requireRole(actor, EDIT_ROLES);
        DeductionObligationDto existing = requireObligation(obligationId);
        int rows = repository.update(obligationId, request, actor.employeeId());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ไม่สามารถแก้ไขรายการที่ปิดแล้ว (STOPPED) ได้");
        }
        DeductionObligationDto updated = requireObligation(obligationId);
        auditService.record(actor, "UPDATE_DEDUCTION_OBLIGATION", "deduction_obligation", obligationId, existing, updated);
        return updated;
    }

    @Transactional
    public DeductionObligationDto stop(long obligationId, UserPrincipal actor) {
        requireRole(actor, EDIT_ROLES);
        DeductionObligationDto existing = requireObligation(obligationId);
        int rows = repository.stop(obligationId, actor.employeeId());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ถูกปิดไปแล้ว");
        }
        DeductionObligationDto updated = requireObligation(obligationId);
        auditService.record(actor, "STOP_DEDUCTION_OBLIGATION", "deduction_obligation", obligationId, existing, updated);
        return updated;
    }

    /**
     * Decision 3's "prominent, HR must confirm" state: recorded, never assumed. Purely evidentiary
     * — does NOT itself change deduction behaviour (a {@code COMPLETED} obligation already deducts
     * 0 the moment it transitions, see {@link #resolveMonthlyAmount}).
     */
    @Transactional
    public DeductionObligationDto acknowledgeCompletion(long obligationId, UserPrincipal actor) {
        requireRole(actor, EDIT_ROLES);
        DeductionObligationDto existing = requireObligation(obligationId);
        if (existing.status() != DeductionObligationStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ยังไม่ครบยอดตามที่หน่วยงานแจ้ง");
        }
        int rows = repository.acknowledgeCompletion(obligationId, actor.employeeId());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ยังไม่ครบยอดตามที่หน่วยงานแจ้ง");
        }
        DeductionObligationDto updated = requireObligation(obligationId);
        auditService.record(actor, "ACKNOWLEDGE_DEDUCTION_OBLIGATION_COMPLETION", "deduction_obligation",
            obligationId, existing, updated);
        return updated;
    }

    /**
     * Decision 3's required mitigation: the ONLY way a deduction resumes past {@code
     * instructedTotal}. A reason is mandatory (see {@link DeductionObligationOverrideRequest}) — this
     * is a deliberate decision to keep deducting a court-ordered/statutory obligation past the figure
     * on file, and it must be defensible later.
     */
    @Transactional
    public DeductionObligationDto overrideContinue(long obligationId, DeductionObligationOverrideRequest request, UserPrincipal actor) {
        requireRole(actor, EDIT_ROLES);
        DeductionObligationDto existing = requireObligation(obligationId);
        if (existing.status() != DeductionObligationStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ยังไม่ครบยอด จึงยังไม่มีอะไรให้ override");
        }
        int rows = repository.setOverrideContinue(obligationId, actor.employeeId(), request.reason());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ยังไม่ครบยอด จึงยังไม่มีอะไรให้ override");
        }
        DeductionObligationDto updated = requireObligation(obligationId);
        auditService.record(actor, "OVERRIDE_DEDUCTION_OBLIGATION_CONTINUE", "deduction_obligation",
            obligationId, existing, updated);
        return updated;
    }

    /** Turns the override back off — the obligation resumes auto-stop behaviour from the next resolve. */
    @Transactional
    public DeductionObligationDto clearOverrideContinue(long obligationId, UserPrincipal actor) {
        requireRole(actor, EDIT_ROLES);
        DeductionObligationDto existing = requireObligation(obligationId);
        int rows = repository.clearOverrideContinue(obligationId, actor.employeeId());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ไม่ได้อยู่ในสถานะ override");
        }
        DeductionObligationDto updated = requireObligation(obligationId);
        auditService.record(actor, "CLEAR_DEDUCTION_OBLIGATION_OVERRIDE", "deduction_obligation",
            obligationId, existing, updated);
        return updated;
    }

    // ==================================================================================
    // Reads
    // ==================================================================================

    public List<DeductionObligationDto> list(Long employeeId, DeductionObligationKind kind, DeductionObligationStatus status, UserPrincipal actor) {
        requireRole(actor, VIEW_ROLES);
        return repository.findAll(employeeId, kind, status);
    }

    /** HR/CEO single-obligation progress view. Employees use {@link #getOwn} instead — never by id. */
    public DeductionObligationProgressDto progress(long obligationId, UserPrincipal actor) {
        requireRole(actor, VIEW_ROLES);
        DeductionObligationDto obligation = requireObligation(obligationId);
        return buildProgress(obligation);
    }

    /**
     * Employee read-only view (decision 4): every obligation ever recorded for the caller, each with
     * total/paid-to-date/remaining/expected-end. No mutation path exists here or anywhere else for an
     * employee — the dispute route is to the authority, never HR (see the issue's own references).
     */
    public List<DeductionObligationProgressDto> getOwn(UserPrincipal actor) {
        requireEmployeeActor(actor);
        return repository.findForEmployee(actor.employeeId()).stream()
            .map(this::buildProgress)
            .toList();
    }

    private DeductionObligationProgressDto buildProgress(DeductionObligationDto obligation) {
        BigDecimal totalRemitted = repository.sumRemittanceTotal(obligation.id());
        int periodsRemitted = repository.countRemittedPeriods(obligation.id());
        List<RemittanceLedgerRowDto> ledger = repository.findLedger(obligation.id());

        BigDecimal remaining = null;
        Integer estimatedPeriodsRemaining = null;
        LocalDate estimatedEndMonth = null;
        if (obligation.instructedTotal() != null) {
            remaining = money(obligation.instructedTotal().subtract(totalRemitted)).max(BigDecimal.ZERO);
            if (obligation.monthlyInstructedAmount().signum() > 0 && remaining.signum() > 0) {
                estimatedPeriodsRemaining = remaining
                    .divide(obligation.monthlyInstructedAmount(), 0, RoundingMode.CEILING)
                    .intValue();
                estimatedEndMonth = LocalDate.now(BUSINESS_ZONE)
                    .withDayOfMonth(1)
                    .plusMonths(estimatedPeriodsRemaining);
            } else if (remaining.signum() == 0) {
                estimatedPeriodsRemaining = 0;
                estimatedEndMonth = LocalDate.now(BUSINESS_ZONE).withDayOfMonth(1);
            }
        }
        return new DeductionObligationProgressDto(
            obligation, totalRemitted, remaining, periodsRemitted, estimatedPeriodsRemaining, estimatedEndMonth, ledger);
    }

    // ==================================================================================
    // helpers
    // ==================================================================================

    private DeductionObligationDto requireObligation(long obligationId) {
        return repository.findById(obligationId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการภาระผูกพันนี้"));
    }

    private void requireEmployeeActor(UserPrincipal actor) {
        if (actor == null || actor.employeeId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (actor == null || !allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
