package th.co.glr.hr.payroll.obligation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

/** Business logic for the deduction-obligation record + remittance ledger. #resolveMonthlyAmount is the
 * payroll-engine hook: PayrollService #calculateLine calls it in place of reading the HR-typed
 * studentLoanDeduction/ legalExecutionDeduction field directly, for both STUDENT_LOAN and LEGAL_EXECUTION. */
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

    // Payroll-engine hooks — no actor/role check; these are internal service-to-service calls, never exposed
    // directly over HTTP.

    /** The monthly figure PayrollCalculator should be asked to deduct for this employee/kind this period.
     * Obligation has no instructedTotal (nullable — open question to the owner, see V106's header) -> the monthly
     * instructed amount, uncapped, always. */
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

    /** Records what was ACTUALLY deducted this processed period against every employee's driving obligation (if
     * any), then re-derives ACTIVE/COMPLETED from the recomputed cumulative total. */
    public void recordRemittances(LocalDate payrollMonth, long payrollPeriodId, List<PayrollLineDto> lines) {
        for (PayrollLineDto line : lines) {
            recordOne(line.employeeId(), DeductionObligationKind.STUDENT_LOAN, payrollMonth, payrollPeriodId, line.studentLoanDeduction());
            recordOne(line.employeeId(), DeductionObligationKind.LEGAL_EXECUTION, payrollMonth, payrollPeriodId, line.legalExecutionDeduction());
        }
    }

    /** Issue #376's shortfall tracking, generalising #373's requested/actual/shortfall triple: records a row in
     * hr.payroll_deduction_shortfall for every employee whose LEGAL_EXECUTION deduction came out lower than what
     * was actually asked for this period -- pure record-keeping, changes NO deducted amount. */
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
            // A period where nothing was actually deducted is not a remittance -- delete (never just skip) so a reprocess
            // that newly zeroes out a previously-nonzero period removes the STALE row instead of leaving it stranded.
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

    // HR CRUD

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

    /** Decision 3's "prominent, HR must confirm" state: recorded, never assumed. Purely evidentiary — does NOT
     * itself change deduction behaviour (a COMPLETED obligation already deducts 0 the moment it transitions, see
     * #resolveMonthlyAmount). */
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

    /** Decision 3's required mitigation: the ONLY way a deduction resumes past instructedTotal. A reason is
     * mandatory (see DeductionObligationOverrideRequest) — this is a deliberate decision to keep deducting a
     * court-ordered/statutory obligation past the figure on file, and it must be defensible later. */
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

    // Reads

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

    /** Employee read-only view (decision 4): every obligation ever recorded for the caller, each with
     * total/paid-to-date/remaining/expected-end. No mutation path exists here or anywhere else for an employee —
     * the dispute route is to the authority, never HR (see the issue's own references). */
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

    // helpers

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
